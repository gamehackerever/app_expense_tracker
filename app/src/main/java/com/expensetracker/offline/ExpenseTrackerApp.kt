package com.expensetracker.offline

import android.app.Application
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.data.repository.TransactionRepository
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.interceptor.NotificationCaptureService
import com.expensetracker.offline.engine.ml.SmsPatternLearner
import com.expensetracker.offline.engine.notification.DailyDigestWorker
import com.expensetracker.offline.engine.notification.DailySummaryReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ExpenseTrackerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase
        get() = AppDatabase.getInstance(this)

    @Volatile private var cachedRepo: TransactionRepository? = null
    @Volatile private var cachedDbRef: AppDatabase? = null

    val repository: TransactionRepository
        get() {
            val currentDb = database
            val existing = cachedRepo
            if (existing != null && cachedDbRef === currentDb) return existing
            return synchronized(this) {
                val dbNow = database
                if (cachedRepo == null || cachedDbRef !== dbNow) {
                    cachedDbRef = dbNow
                    cachedRepo = TransactionRepository(dbNow, this)
                }
                cachedRepo!!
            }
        }

    override fun onCreate() {
        super.onCreate()
        // Loads learned templates from disk, so keep it off the main thread. Anything that parses
        // messages calls initialize() itself first (it is idempotent and synchronized).
        appScope.launch { runCatching { SmsPatternLearner.initialize(this@ExpenseTrackerApp) } }

        DailySummaryReceiver.cancelLegacyAlarm(this)
        DailyDigestWorker.schedule(this)
        AutoBackupWorker.schedule(this)

        // Ensure NotificationCaptureService stays bound when auto-capture is enabled
        val settings = SettingsRepository(this)
        com.expensetracker.offline.util.CardPaymentReconciler.hintProvider = { settings.cardPayeeHints() }
        if (settings.disclosureAccepted && !settings.isManualModePreferred) {
            NotificationCaptureService.forceRebind(this)
        }

        // Commit any soft-deleted transaction IDs that were still inside their 5-second undo window
        // when the app process was previously killed by the OS or swiped away from Recents.
        appScope.launch {
            runCatching {
                val leftoverDeletes = settings.consumeAllPendingDeleteIds()
                if (leftoverDeletes.isNotEmpty()) {
                    repository.deleteTransactionsByIds(leftoverDeletes.toList())
                }
            }
        }
    }
}
