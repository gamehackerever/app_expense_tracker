package com.expensetracker.offline.engine.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the auto-backup passphrase encrypted with a non-exportable AES-GCM key held in the
 * Android Keystore, instead of as plain text in SharedPreferences.
 *
 * Plain-text values written by older versions are migrated automatically on first access.
 * If the key is gone (e.g. app data restored onto a new device), [load] returns null and the
 * user simply has to enter the passphrase again.
 */
object SecurePassphraseStore {
    private const val PREFS = "expense_tracker_prefs"
    private const val KEY_ENCRYPTED = "key_auto_backup_passphrase_enc"
    private const val KEY_LEGACY_PLAINTEXT = "key_auto_backup_passphrase"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "auto_backup_passphrase_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG = "SecurePassphraseStore"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(context: Context, passphrase: String): Boolean {
        if (passphrase.isEmpty()) {
            clear(context)
            return true
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val cipherText = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(cipherText, Base64.NO_WRAP)
            prefs(context).edit {
                putString(KEY_ENCRYPTED, blob)
                remove(KEY_LEGACY_PLAINTEXT)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store passphrase securely", e)
            false
        }
    }

    @Synchronized
    fun load(context: Context): String? {
        migrateLegacy(context)
        val blob = prefs(context).getString(KEY_ENCRYPTED, null) ?: return null
        return try {
            val (ivPart, dataPart) = blob.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(128, Base64.decode(ivPart, Base64.NO_WRAP))
                )
            }
            String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Stored passphrase can no longer be decrypted", e)
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit {
            remove(KEY_ENCRYPTED)
            remove(KEY_LEGACY_PLAINTEXT)
        }
    }

    /** Moves a plain-text passphrase from older versions into encrypted storage. */
    @Synchronized
    fun migrateLegacy(context: Context) {
        val p = prefs(context)
        val legacy = p.getString(KEY_LEGACY_PLAINTEXT, null)?.takeIf { it.isNotEmpty() } ?: return
        if (p.contains(KEY_ENCRYPTED)) {
            p.edit { remove(KEY_LEGACY_PLAINTEXT) }
        } else {
            // If encryption fails the plain-text value is kept, so auto-backup keeps working.
            save(context, legacy)
        }
    }
}