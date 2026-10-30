package com.expensetracker.offline.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException

@RunWith(AndroidJUnit4::class)
class BackupEngineTest {
    private val magic = "ETBK_V4_".toByteArray(Charsets.UTF_8)

    @Test
    fun encryptedBackupStream_roundTripsSettingsAndDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDb = File(context.cacheDir, "backup-engine-source.db")
        val restoredDb = File(context.cacheDir, "backup-engine-restored.db")
        val dbContents = ByteArray(128 * 1024) { (it % 251).toByte() }
        val settings = "{\"prefs_files\":{}}".toByteArray(Charsets.UTF_8)

        try {
            sourceDb.writeBytes(dbContents)
            val encrypted = ByteArrayOutputStream().also {
                BackupEngine.encryptBackupStream(it, "correct horse battery staple", magic, settings, sourceDb)
            }.toByteArray()

            val result = BackupEngine.decryptBackupStream(
                ByteArrayInputStream(encrypted),
                "correct horse battery staple",
                magic,
                restoredDb
            )

            assertEquals(settings.toList(), (result as BackupEngine.StreamDecryptResult.V4Success).settingsBytes?.toList())
            assertArrayEquals(dbContents, restoredDb.readBytes())
        } finally {
            sourceDb.delete()
            restoredDb.delete()
        }
    }

    @Test
    fun encryptedBackupStream_rejectsWrongPassphraseAndTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDb = File(context.cacheDir, "backup-engine-source.db")
        val restoredDb = File(context.cacheDir, "backup-engine-restored.db")
        sourceDb.writeBytes(ByteArray(128) { it.toByte() })
        val encrypted = ByteArrayOutputStream().also {
            BackupEngine.encryptBackupStream(it, "correct horse battery staple", magic, byteArrayOf(), sourceDb)
        }.toByteArray()

        try {
            assertThrows(GeneralSecurityException::class.java) {
                BackupEngine.decryptBackupStream(
                    ByteArrayInputStream(encrypted), "wrong passphrase", magic, restoredDb
                )
            }
            assertFalse(restoredDb.exists())

            val tampered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) {
                BackupEngine.decryptBackupStream(
                    ByteArrayInputStream(tampered), "correct horse battery staple", magic, restoredDb
                )
            }
            assertFalse(restoredDb.exists())
        } finally {
            sourceDb.delete()
            restoredDb.delete()
        }
    }
}
