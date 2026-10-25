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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
    private const val PREFS_NAME = SettingsRepository.PREFS_NAME

    private val MAGIC_V2 = "ETBK_V2_".toByteArray(Charsets.UTF_8)
    private val MAGIC_V3 = "ETBK_V3_".toByteArray(Charsets.UTF_8)
    private val MAGIC_V4 = "ETBK_V4_".toByteArray(Charsets.UTF_8)
    private val PREF_FILES = listOf(PREFS_NAME)
    private val EXCLUDED_PREF_KEYS = setOf(
        "key_backup_folder_uri", "key_last_backup_timestamp", "key_auto_backup_enabled",
        "key_last_scan_txns", "key_last_scan_reviews", "key_pending_delete_txn_ids",
        "key_last_auto_backup_ok", "key_last_auto_backup_run_time", "key_last_auto_backup_reason",
        "key_auto_backup_passphrase_enc", "key_auto_backup_passphrase"
    )

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
            editor.remove("key_last_scan_txns")
                .remove("key_last_scan_reviews")
                .remove("key_pending_delete_txn_ids")
            for (key in values.keys()) {
                if (key in EXCLUDED_PREF_KEYS) continue
                putEntry(editor, key, values.getJSONObject(key))
            }
            editor.commit()
        }
        SettingsRepository(context).monthlyBudget
    }

    suspend fun createBackup(context: Context, targetTreeUri: String, passphrase: String): BackupResult = withContext(Dispatchers.IO) {
        val tmpDbFile = File(context.cacheDir, "vacuum_temp.db")
        var createdFileUri: Uri? = null

        fun discardPartialFile() {
            createdFileUri?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            createdFileUri = null
        }

        try {
            if (passphrase.isBlank()) return@withContext BackupResult.Error("The backup passphrase can't be blank.")

            val app = context.applicationContext as ExpenseTrackerApp
            if (tmpDbFile.exists()) tmpDbFile.delete()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
            createdFileUri = newFileUri

            val outputStream = context.contentResolver.openOutputStream(newFileUri)
                ?: run {
                    discardPartialFile()
                    return@withContext BackupResult.Error("Could not open file for writing.")
                }

            val settingsBytes = serializeSettings(context)
            val dbSize = tmpDbFile.length()
            if (dbSize <= 0L) {
                outputStream.close()
                discardPartialFile()
                return@withContext BackupResult.Error("Could not read the database snapshot.")
            }

            outputStream.use { os ->
                BackupEngine.encryptBackupStream(
                    outputStream = os,
                    passphrase = passphrase,
                    magicHeader = MAGIC_V4,
                    settingsBytes = settingsBytes,
                    dbFile = tmpDbFile
                )
            }
            tmpDbFile.delete()

            if (DocumentsContract.renameDocument(context.contentResolver, newFileUri, fileName) == null) {
                discardPartialFile()
                return@withContext BackupResult.Error("Could not finish writing the backup file.")
            }
            createdFileUri = null

            BackupResult.Success
        } catch (e: OutOfMemoryError) {
            discardPartialFile()
            BackupResult.Error("Not enough memory to back up the database on this device.")
        } catch (e: Exception) {
            discardPartialFile()
            BackupResult.Error("Backup failed: ${e.localizedMessage}")
        } finally {
            tmpDbFile.delete()
        }
    }

    suspend fun restoreBackup(context: Context, backupFileUri: String, passphrase: String): RestoreResult = withContext(Dispatchers.IO) {
        val dbPath = context.getDatabasePath(DATABASE_NAME)
        val tmpDbFile = File(dbPath.parentFile, "$DATABASE_NAME.restore_tmp")
        val backupDbFile = File(dbPath.parentFile, "$DATABASE_NAME.bak")

        try {
            val uri = Uri.parse(backupFileUri)

            var settingsBytes: ByteArray? = null
            var legacyDecryptedBytes: ByteArray? = null
            var detectedVersion = 4

            // 1. Try New Secure Streaming Decryption First (V4 Stream)
            try {
                val streamResult = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BackupEngine.decryptBackupStream(inputStream, passphrase, MAGIC_V4, tmpDbFile)
                } ?: return@withContext RestoreResult.Error("Backup file is unreadable.")

                when (streamResult) {
                    is BackupEngine.StreamDecryptResult.V4Success -> {
                        settingsBytes = streamResult.settingsBytes
                        if (tmpDbFile.length() <= 16L) {
                            tmpDbFile.delete()
                            return@withContext RestoreResult.Error("This backup doesn't contain a valid database.")
                        }
                    }
                    is BackupEngine.StreamDecryptResult.LegacyPayload -> {
                        legacyDecryptedBytes = streamResult.payload
                    }
                    is BackupEngine.StreamDecryptResult.FormatError -> {
                        tmpDbFile.delete()
                        return@withContext RestoreResult.Error(streamResult.message)
                    }
                }
            } catch (e: GeneralSecurityException) {
                tmpDbFile.delete()
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

            // 4. Safely close and nullify the singleton Room database reference
            AppDatabase.closeAndResetInstance()

            // 5. Move current DB to .bak (Safety rollback mechanism)
            val walFile = File(dbPath.path + "-wal")
            val shmFile = File(dbPath.path + "-shm")
            val journalFile = File(dbPath.path + "-journal")

            if (dbPath.exists()) dbPath.renameTo(backupDbFile)
            val backupWalFile = File(dbPath.path + "-bak-wal")
            val backupShmFile = File(dbPath.path + "-bak-shm")
            if (walFile.exists()) walFile.renameTo(backupWalFile) else backupWalFile.delete()
            if (shmFile.exists()) shmFile.renameTo(backupShmFile) else backupShmFile.delete()
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
                if (backupWalFile.exists()) backupWalFile.renameTo(walFile)
                if (backupShmFile.exists()) backupShmFile.renameTo(shmFile)
                return@withContext RestoreResult.Error("Backup contains a corrupt database or is from a newer version of the app. Restored old database.")
            }

            // 8. Success. Clean up and apply settings
            backupDbFile.delete()
            backupWalFile.delete()
            backupShmFile.delete()
            val restoredSettings = settingsBytes
            if (restoredSettings != null) {
                when (detectedVersion) {
                    4, 3 -> applySettingsV4(context, restoredSettings)
                    2 -> context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putStringSet("key_custom_categories", deserializeCategories(restoredSettings)).commit()
                }
            }

            // Eagerly reopen the new database instance so any concurrent observer gets a live pool
            AppDatabase.getInstance(context)

            RestoreResult.SuccessRestart
        } catch (e: Exception) {
            e.printStackTrace()
            tmpDbFile.delete()
            if (!dbPath.exists() && backupDbFile.exists()) backupDbFile.renameTo(dbPath)
            RestoreResult.Error("Restore failed: ${e.localizedMessage}")
        }
    }

    internal fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }
}

object BackupEngine {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val SALT_SIZE = 16
    private const val TAG_LENGTH_BIT = 128
    private const val CHUNK_SIZE = 64 * 1024

    sealed class StreamDecryptResult {
        data class V4Success(val settingsBytes: ByteArray?) : StreamDecryptResult()
        data class LegacyPayload(val payload: ByteArray) : StreamDecryptResult()
        data class FormatError(val message: String) : StreamDecryptResult()
    }

    private fun getSecretKey(passphrase: String, salt: ByteArray, allowLegacyBlank: Boolean = false): SecretKeySpec {
        if (!allowLegacyBlank) {
            require(passphrase.isNotBlank()) { "Passphrase can't be blank" }
        }
        val effectivePassphrase = if (passphrase.isBlank()) "Default_ExpenseTracker_Secure_Key" else passphrase

        val algorithm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "PBKDF2WithHmacSHA256"
        } else {
            "PBKDF2WithHmacSHA1"
        }

        val factory = SecretKeyFactory.getInstance(algorithm)
        val spec = PBEKeySpec(effectivePassphrase.toCharArray(), salt, 100_000, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun encryptBackupStream(
        outputStream: OutputStream,
        passphrase: String,
        magicHeader: ByteArray,
        settingsBytes: ByteArray,
        dbFile: File
    ) {
        require(passphrase.isNotBlank()) { "Passphrase can't be blank" }
        val secureRandom = SecureRandom()
        val salt = ByteArray(SALT_SIZE).apply { secureRandom.nextBytes(this) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(passphrase, salt, allowLegacyBlank = false))
        val iv = cipher.iv

        outputStream.write(byteArrayOf(1)) // Version byte
        outputStream.write(salt)
        outputStream.write(iv)

        fun updateAndWrite(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            if (length <= 0) return
            val out = cipher.update(data, offset, length)
            if (out != null && out.isNotEmpty()) {
                outputStream.write(out)
            }
        }

        val lengthHeader = ByteBuffer.allocate(4).putInt(settingsBytes.size).array()
        updateAndWrite(magicHeader)
        updateAndWrite(lengthHeader)
        updateAndWrite(settingsBytes)

        val buffer = ByteArray(CHUNK_SIZE)
        dbFile.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                updateAndWrite(buffer, 0, read)
            }
        }

        val finalBytes = cipher.doFinal()
        if (finalBytes != null && finalBytes.isNotEmpty()) {
            outputStream.write(finalBytes)
        }
        outputStream.flush()
    }

    fun decryptBackupStream(
        inputStream: InputStream,
        passphrase: String,
        magicV4: ByteArray,
        targetDbFile: File
    ): StreamDecryptResult {
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

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(passphrase, salt, allowLegacyBlank = true), spec)

        if (targetDbFile.exists()) targetDbFile.delete()

        val headerBuf = ByteArrayOutputStream()
        var isV4Resolved: Boolean? = null
        var headerParsed = false
        var expectedHeaderTotal = -1
        var settingsBytes: ByteArray? = null
        var legacySink: ByteArrayOutputStream? = null

        var hasCiphertext = false
        val inBuffer = ByteArray(CHUNK_SIZE)

        try {
            FileOutputStream(targetDbFile).use { dbOut ->
                fun processPlaintext(plain: ByteArray?) {
                    if (plain == null || plain.isEmpty()) return
                    var offset = 0

                    if (isV4Resolved == null) {
                        val needed = (magicV4.size + 4) - headerBuf.size()
                        val take = minOf(needed, plain.size)
                        headerBuf.write(plain, 0, take)
                        offset += take

                        if (headerBuf.size() == magicV4.size + 4) {
                            val hdr = headerBuf.toByteArray()
                            if (BackupManager.startsWith(hdr, magicV4)) {
                                isV4Resolved = true
                                val sLen = ByteBuffer.wrap(hdr, magicV4.size, 4).int
                                val validSLen = if (sLen in 1..499999) sLen else 0
                                expectedHeaderTotal = magicV4.size + 4 + validSLen
                                if (validSLen == 0) {
                                    headerParsed = true
                                    headerBuf.reset()
                                }
                            } else {
                                isV4Resolved = false
                                legacySink = ByteArrayOutputStream().apply {
                                    write(hdr)
                                }
                            }
                        } else {
                            return
                        }
                    }

                    if (isV4Resolved == true) {
                        if (!headerParsed && headerBuf.size() < expectedHeaderTotal) {
                            val needed = expectedHeaderTotal - headerBuf.size()
                            val take = minOf(needed, plain.size - offset)
                            headerBuf.write(plain, offset, take)
                            offset += take
                            if (headerBuf.size() == expectedHeaderTotal) {
                                val fullHdr = headerBuf.toByteArray()
                                val sLen = expectedHeaderTotal - (magicV4.size + 4)
                                if (sLen > 0) {
                                    settingsBytes = fullHdr.copyOfRange(magicV4.size + 4, expectedHeaderTotal)
                                }
                                headerParsed = true
                                headerBuf.reset()
                            }
                        }
                        val remaining = plain.size - offset
                        if (remaining > 0) {
                            dbOut.write(plain, offset, remaining)
                        }
                    } else if (isV4Resolved == false) {
                        val remaining = plain.size - offset
                        if (remaining > 0) {
                            legacySink?.write(plain, offset, remaining)
                        }
                    }
                }

                while (true) {
                    val read = inputStream.read(inBuffer)
                    if (read <= 0) break
                    hasCiphertext = true
                    processPlaintext(cipher.update(inBuffer, 0, read))
                }

                if (!hasCiphertext) throw GeneralSecurityException("Invalid backup file: empty payload")
                processPlaintext(cipher.doFinal())
                dbOut.flush()
            }
        } catch (e: Exception) {
            targetDbFile.delete()
            throw e
        }

        return when (isV4Resolved) {
            true -> {
                if (!headerParsed) {
                    targetDbFile.delete()
                    StreamDecryptResult.FormatError("Backup file is truncated.")
                } else if (targetDbFile.length() == 0L) {
                    targetDbFile.delete()
                    StreamDecryptResult.FormatError("Backup database payload is missing.")
                } else {
                    StreamDecryptResult.V4Success(settingsBytes)
                }
            }
            false -> {
                targetDbFile.delete()
                StreamDecryptResult.LegacyPayload(legacySink?.toByteArray() ?: ByteArray(0))
            }
            null -> {
                targetDbFile.delete()
                StreamDecryptResult.FormatError("Backup file is truncated.")
            }
        }
    }

    fun encryptPayload(outputStream: OutputStream, passphrase: String, payload: ByteArray) {
        require(passphrase.isNotBlank()) { "Passphrase can't be blank" }
        val secureRandom = SecureRandom()
        val salt = ByteArray(SALT_SIZE).apply { secureRandom.nextBytes(this) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(passphrase, salt, allowLegacyBlank = false))
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
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(passphrase, salt, allowLegacyBlank = true), spec)

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