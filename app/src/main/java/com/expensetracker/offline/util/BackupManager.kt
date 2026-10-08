package com.expensetracker.offline.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.sqlite.db.SimpleSQLiteQuery
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed class BackupResult {
    object Success : BackupResult()
    data class Error(val message: String) : BackupResult()
}

sealed class RestoreResult {
    object SuccessRestart : RestoreResult()
    object WrongPassphrase : RestoreResult()
    data class Error(val message: String) : RestoreResult()
}

object BackupManager {

    private const val DATABASE_NAME = "expense_tracker.db"
    private const val PREFS_NAME = "expense_tracker_prefs"

    private val MAGIC_V2 = "ETBK_V2_".toByteArray(Charsets.UTF_8)
    private val MAGIC_V3 = "ETBK_V3_".toByteArray(Charsets.UTF_8)
    private val MAGIC_V4 = "ETBK_V4_".toByteArray(Charsets.UTF_8) // You already have this one
    private val PREF_FILES = listOf(PREFS_NAME)
    private val EXCLUDED_PREF_KEYS = setOf("key_backup_folder_uri", "key_last_backup_timestamp")

    private val BACKED_UP_PREF_KEYS = listOf(
        "key_monthly_budget_v2", "key_budget_explicitly_set", "key_necessities_json",
        "key_necessities_migrated_v1", "key_necessity_chunk", "key_start_day_of_month",
        "key_low_balance_alert_enabled", "key_low_balance_threshold"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // LEGACY RESTORE HELPERS (Keep these to support restoring V2/V3 backups)
    // ─────────────────────────────────────────────────────────────────────────

    private val LEGACY_V3_KEYS = listOf(
        "key_monthly_budget_v2", "key_monthly_budget", "key_budget_explicitly_set", "key_necessities_json",
        "key_necessities_migrated_v1", "key_necessity_chunk", "key_start_day_of_month",
        "key_low_balance_alert_enabled", "key_low_balance_threshold"
    )

    private fun applySettingsV3(context: Context, bytes: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val editor = prefs.edit()
        root.optJSONArray("custom_categories")?.let { arr ->
            editor.putStringSet("key_custom_categories", (0 until arr.length()).map { arr.getString(it) }.toSet())
        }
        root.optJSONObject("prefs")?.let { values ->
            for (key in LEGACY_V3_KEYS) {
                val entry = values.optJSONObject(key) ?: continue
                putEntry(editor, key, entry)
            }
        }
        editor.commit()
        SettingsRepository(context).monthlyBudget
    }

    private fun deserializeCategories(bytes: ByteArray): Set<String> {
        if (bytes.isEmpty()) return emptySet()
        val jsonArray = JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until jsonArray.length()).map { jsonArray.getString(it) }.toSet()
    }

    private fun putEntry(editor: SharedPreferences.Editor, key: String, entry: JSONObject) {
        when (entry.optString("t")) {
            "s" -> editor.putString(key, entry.getString("v"))
            "b" -> editor.putBoolean(key, entry.getBoolean("v"))
            "i" -> editor.putInt(key, entry.getInt("v"))
            "l" -> editor.putLong(key, entry.getLong("v"))
            "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
            "ss" -> {
                val arr = entry.getJSONArray("v")
                editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
            }
        }
    }

    private fun serializeSettings(context: Context): ByteArray {
        val files = JSONObject()
        for (name in PREF_FILES) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val values = JSONObject()
            for ((key, v) in prefs.all) {
                if (key in EXCLUDED_PREF_KEYS) continue
                val entry = JSONObject()
                when (v) {
                    is String -> entry.put("t", "s").put("v", v)
                    is Boolean -> entry.put("t", "b").put("v", v)
                    is Int -> entry.put("t", "i").put("v", v)
                    is Long -> entry.put("t", "l").put("v", v)
                    is Float -> entry.put("t", "f").put("v", v.toDouble())
                    is Set<*> -> entry.put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
                    else -> continue
                }
                values.put(key, entry)
            }
            files.put(name, values)
        }
        return JSONObject().put("prefs_files", files).toString().toByteArray(Charsets.UTF_8)
    }

    private fun applySettingsV4(context: Context, bytes: ByteArray) {
        val files = JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("prefs_files") ?: return
        for (name in files.keys()) {
            val values = files.getJSONObject(name)
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            // FIXED: Merge instead of clearing so we don't wipe excluded keys (like auto-backup state)
            for (key in values.keys()) putEntry(editor, key, values.getJSONObject(key))
            editor.commit()
        }
        SettingsRepository(context).monthlyBudget
    }

    suspend fun createBackup(context: Context, targetTreeUri: String, passphrase: String): BackupResult = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext as ExpenseTrackerApp
            val tmpDbFile = File(context.cacheDir, "vacuum_temp.db")
            if (tmpDbFile.exists()) tmpDbFile.delete()

            // FIXED: Use atomic VACUUM INTO for Android 11+, otherwise fallback to TRUNCATE (PO-E)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                app.database.query(SimpleSQLiteQuery("VACUUM INTO '${tmpDbFile.absolutePath}'")).use { it.moveToFirst() }
            } else {
                var attempts = 0
                while (true) {
                    val busy = app.database.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    if (busy == 0) break
                    if (++attempts >= 3) return@withContext BackupResult.Error("Database is busy, try again.")
                    delay(200)
                }
                context.getDatabasePath(DATABASE_NAME).copyTo(tmpDbFile)
            }

            val treeUri = Uri.parse(targetTreeUri)
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "ExpenseBackup_$timestamp.enc"

            val newFileUri = DocumentsContract.createDocument(context.contentResolver, docUri, "application/octet-stream", "$fileName.tmp")
                ?: return@withContext BackupResult.Error("Could not create file.")

            val outputStream = context.contentResolver.openOutputStream(newFileUri)
                ?: return@withContext BackupResult.Error("Could not open file for writing.")

            val settingsBytes = serializeSettings(context)
            val dbBytes = tmpDbFile.readBytes()
            tmpDbFile.delete()

            val lengthHeader = ByteBuffer.allocate(4).putInt(settingsBytes.size).array()

            val payload = ByteArray(MAGIC_V4.size + 4 + settingsBytes.size + dbBytes.size)
            System.arraycopy(MAGIC_V4, 0, payload, 0, MAGIC_V4.size)
            System.arraycopy(lengthHeader, 0, payload, MAGIC_V4.size, 4)
            System.arraycopy(settingsBytes, 0, payload, MAGIC_V4.size + 4, settingsBytes.size)
            System.arraycopy(dbBytes, 0, payload, MAGIC_V4.size + 4 + settingsBytes.size, dbBytes.size)

            outputStream.use { os ->
                BackupEngine.encryptPayload(os, passphrase, payload)
            }

            DocumentsContract.renameDocument(context.contentResolver, newFileUri, fileName)

            BackupResult.Success
        } catch (e: Exception) {
            BackupResult.Error("Backup failed: ${e.localizedMessage}")
        }
    }

    suspend fun restoreBackup(context: Context, backupFileUri: String, passphrase: String): RestoreResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext as ExpenseTrackerApp
        val dbPath = context.getDatabasePath(DATABASE_NAME)
        val tmpDbFile = File(dbPath.parentFile, "$DATABASE_NAME.restore_tmp")
        val backupDbFile = File(dbPath.parentFile, "$DATABASE_NAME.bak")

        try {
            val uri = Uri.parse(backupFileUri)

            var settingsBytes: ByteArray? = null
            var legacyDecryptedBytes: ByteArray? = null
            var detectedVersion = 4

            // 1. Try New Secure Decryption First (V4 Stream)
            try {
                val decryptedPayload = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BackupEngine.decryptPayload(inputStream, passphrase)
                }

                if (decryptedPayload != null && startsWith(decryptedPayload, MAGIC_V4)) {
                    val magicSize = MAGIC_V4.size
                    if (decryptedPayload.size < magicSize + 4) {
                        tmpDbFile.delete()
                        return@withContext RestoreResult.Error("Backup file is truncated.")
                    }

                    val buffer = ByteBuffer.wrap(decryptedPayload, magicSize, decryptedPayload.size - magicSize)
                    val settingsLength = buffer.int

                    if (settingsLength in 1..499999 && decryptedPayload.size >= magicSize + 4 + settingsLength) {
                        settingsBytes = ByteArray(settingsLength)
                        buffer.get(settingsBytes)
                    }

                    val dbStartIndex = magicSize + 4 + (settingsLength.takeIf { it in 1..499999 } ?: 0)
                    if (dbStartIndex >= decryptedPayload.size) {
                        tmpDbFile.delete()
                        return@withContext RestoreResult.Error("Backup database payload is missing.")
                    }

                    val dbBytes = decryptedPayload.copyOfRange(dbStartIndex, decryptedPayload.size)
                    if (dbBytes.size <= 16) {
                        tmpDbFile.delete()
                        return@withContext RestoreResult.Error("This backup doesn't contain a valid database.")
                    }

                    tmpDbFile.writeBytes(dbBytes)
                } else if (decryptedPayload != null) {
                    legacyDecryptedBytes = decryptedPayload
                }
            } catch (e: GeneralSecurityException) {
                // 2. FALLBACK: Attempt Legacy Decryption if V4 fails
                try {
                    val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("Could not read backup file for legacy decryption.")

                    legacyDecryptedBytes = BackupEngine.decryptLegacy(rawBytes)
                } catch (legacyEx: Exception) {
                    tmpDbFile.delete()
                    return@withContext RestoreResult.WrongPassphrase
                }
            } catch (e: Exception) {
                tmpDbFile.delete()
                return@withContext RestoreResult.Error("Backup file is damaged or unreadable.")
            }

            // 3. Process Legacy Bytes if Fallback was used
            if (legacyDecryptedBytes != null) {
                detectedVersion = when {
                    startsWith(legacyDecryptedBytes, MAGIC_V4) -> 4
                    startsWith(legacyDecryptedBytes, MAGIC_V3) -> 3
                    startsWith(legacyDecryptedBytes, MAGIC_V2) -> 2
                    else -> 0
                }

                val dbBytes: ByteArray = if (detectedVersion >= 2) {
                    val magicSize = MAGIC_V4.size
                    // Make sure we have enough bytes to read settings length
                    if (legacyDecryptedBytes.size < magicSize + 4) {
                        tmpDbFile.delete()
                        return@withContext RestoreResult.Error("Legacy backup file is truncated.")
                    }
                    val buffer = ByteBuffer.wrap(legacyDecryptedBytes, magicSize, legacyDecryptedBytes.size - magicSize)
                    val settingsLength = buffer.int

                    if (settingsLength in 1..499999 && legacyDecryptedBytes.size >= magicSize + 4 + settingsLength) {
                        settingsBytes = ByteArray(settingsLength)
                        buffer.get(settingsBytes)
                    }
                    val dbStartIndex = magicSize + 4 + (settingsLength.takeIf { it in 1..499999 } ?: 0)
                    if (dbStartIndex >= legacyDecryptedBytes.size) {
                        tmpDbFile.delete()
                        return@withContext RestoreResult.Error("Legacy backup database payload is missing.")
                    }
                    legacyDecryptedBytes.copyOfRange(dbStartIndex, legacyDecryptedBytes.size)
                } else {
                    legacyDecryptedBytes
                }

                if (dbBytes.size <= 16) {
                    tmpDbFile.delete()
                    return@withContext RestoreResult.Error("This backup doesn't contain a valid database.")
                }

                tmpDbFile.writeBytes(dbBytes)
            }

            // 4. Safely close existing connections
            app.database.close()

            // 5. Move current DB to .bak (Safety rollback mechanism)
            val walFile = File(dbPath.path + "-wal")
            val shmFile = File(dbPath.path + "-shm")
            val journalFile = File(dbPath.path + "-journal")

            if (dbPath.exists()) dbPath.renameTo(backupDbFile)
            walFile.delete()
            shmFile.delete()
            journalFile.delete()

            // 6. Swap new DB into place
            tmpDbFile.renameTo(dbPath)

            // 7. Check SQLite Integrity
            var isDbValid = false
            try {
                val testDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbPath.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                )
                testDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        isDbValid = cursor.getString(0).equals("ok", ignoreCase = true)
                    }
                }
                testDb.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        // Reject if strictly from a future app database version
                        // Older versions are fine because Room migrations will handle them!
                        val dbVersion = cursor.getInt(0)
                        if (dbVersion > AppDatabase.DATABASE_VERSION) {
                            isDbValid = false
                        }
                    }
                }
                testDb.close()
            } catch (e: Exception) {
                isDbValid = false
            }

            if (!isDbValid) {
                dbPath.delete()
                if (backupDbFile.exists()) backupDbFile.renameTo(dbPath)
                return@withContext RestoreResult.Error("Backup contains a corrupt database or is from a newer version of the app. Restored old database.")
            }

            // 8. Success. Clean up and apply settings
            backupDbFile.delete()
            if (settingsBytes != null) {
                when (detectedVersion) {
                    4, 3 -> applySettingsV4(context, settingsBytes!!)
                    2 -> context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putStringSet("key_custom_categories", deserializeCategories(settingsBytes!!)).commit()
                }
            }

            RestoreResult.SuccessRestart
        } catch (e: Exception) {
            e.printStackTrace()
            if (!dbPath.exists() && backupDbFile.exists()) backupDbFile.renameTo(dbPath)
            RestoreResult.Error("Restore failed: ${e.localizedMessage}")
        }
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }
}

// FIXED: Real Encryption Engine (PO-E)
object BackupEngine {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val SALT_SIZE = 16
    private const val TAG_LENGTH_BIT = 128

    private fun getSecretKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        // Prevent Keystore crash if an empty password slips through
        val safePassphrase = if (passphrase.isBlank()) "Default_ExpenseTracker_Secure_Key" else passphrase

        val algorithm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "PBKDF2WithHmacSHA256"
        } else {
            "PBKDF2WithHmacSHA1"
        }

        val factory = SecretKeyFactory.getInstance(algorithm)
        val spec = PBEKeySpec(safePassphrase.toCharArray(), salt, 100_000, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun encryptPayload(outputStream: OutputStream, passphrase: String, payload: ByteArray) {
        val secureRandom = SecureRandom()
        val salt = ByteArray(SALT_SIZE).apply { secureRandom.nextBytes(this) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(passphrase, salt))
        val iv = cipher.iv

        outputStream.write(byteArrayOf(1)) // Version byte
        outputStream.write(salt)
        outputStream.write(iv)

        val encryptedBytes = cipher.doFinal(payload)
        outputStream.write(encryptedBytes)
        outputStream.flush()
    }

    fun decryptPayload(inputStream: InputStream, passphrase: String): ByteArray {
        val version = inputStream.read()
        if (version != 1) throw GeneralSecurityException("Unsupported encryption version")

        val salt = ByteArray(SALT_SIZE)
        var totalRead = 0
        while (totalRead < SALT_SIZE) {
            val count = inputStream.read(salt, totalRead, SALT_SIZE - totalRead)
            if (count == -1) throw GeneralSecurityException("Invalid backup file: salt truncated")
            totalRead += count
        }

        val iv = ByteArray(IV_SIZE)
        totalRead = 0
        while (totalRead < IV_SIZE) {
            val count = inputStream.read(iv, totalRead, IV_SIZE - totalRead)
            if (count == -1) throw GeneralSecurityException("Invalid backup file: IV truncated")
            totalRead += count
        }

        val encryptedBytes = inputStream.readBytes()
        if (encryptedBytes.isEmpty()) throw GeneralSecurityException("Invalid backup file: empty payload")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(passphrase, salt), spec)

        return cipher.doFinal(encryptedBytes)
    }

    fun decryptLegacy(encryptedPayload: ByteArray): ByteArray {
        val staticPassphrase = "ExpenseTracker_Offline_Secure_v1".toCharArray()
        val staticSalt = "XpenseTrkr_S4lt".toByteArray(Charsets.UTF_8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(staticPassphrase, staticSalt, 10000, 256)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val iv = encryptedPayload.copyOfRange(0, IV_SIZE)
        val encryptedData = encryptedPayload.copyOfRange(IV_SIZE, encryptedPayload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        return cipher.doFinal(encryptedData)
    }
}