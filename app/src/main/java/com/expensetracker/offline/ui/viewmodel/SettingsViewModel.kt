package com.expensetracker.offline.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.backup.SecurePassphraseStore
import com.expensetracker.offline.util.BackupManager
import com.expensetracker.offline.util.BackupResult
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application.applicationContext)

    val monthlyBudget = MutableStateFlow(settingsRepo.monthlyBudget)
    val startDayOfMonth = MutableStateFlow(settingsRepo.startDayOfMonth)
    val necessityChunkLimit = MutableStateFlow(settingsRepo.necessityChunkLimit)
    val isBudgetExplicitlySet = MutableStateFlow(settingsRepo.isBudgetExplicitlySet)

    val categoryBudgets = MutableStateFlow(settingsRepo.getCategoryBudgets())
    val dismissedCategoryWarnings = MutableStateFlow<Set<String>>(emptySet())
    val dismissedCategoryWarningLevels = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
        val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
        dismissedCategoryWarningLevels.value = levels
        dismissedCategoryWarnings.value = levels.keys
    }

    fun setCategoryBudget(category: String, amountInPaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setCategoryBudget(category, amountInPaise)
            categoryBudgets.value = settingsRepo.getCategoryBudgets()
        }
    }

    fun removeCategoryBudget(category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.removeCategoryBudget(category)
            categoryBudgets.value = settingsRepo.getCategoryBudgets()
        }
    }

    fun dismissCategoryWarning(category: String, level: String = SettingsRepository.WARNING_LEVEL_OVER) {
        viewModelScope.launch(Dispatchers.IO) {
            val (cycleStart, _) = BillingCycleHelper.getCycleRange(startDayOfMonth.value)
            settingsRepo.dismissCategoryWarning(category, cycleStart, level)
            val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
            dismissedCategoryWarningLevels.value = levels
            dismissedCategoryWarnings.value = levels.keys
        }
    }

    fun updateStartDayOfMonth(day: Int) {
        val clamped = day.coerceIn(1, 28)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.startDayOfMonth = clamped
        }
        startDayOfMonth.value = clamped
        val (cycleStart, _) = BillingCycleHelper.getCycleRange(clamped)
        val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
        dismissedCategoryWarningLevels.value = levels
        dismissedCategoryWarnings.value = levels.keys
    }

    fun updateMonthlyBudget(newBudget: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.monthlyBudget = newBudget
        }
        monthlyBudget.value = newBudget
        isBudgetExplicitlySet.value = true
    }
    
    fun updateNecessityChunk(limit: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.necessityChunkLimit = limit
        }
        necessityChunkLimit.value = limit
    }

    fun refreshSettings() {
        monthlyBudget.value = settingsRepo.monthlyBudget
        startDayOfMonth.value = settingsRepo.startDayOfMonth
        necessityChunkLimit.value = settingsRepo.necessityChunkLimit
        isBudgetExplicitlySet.value = settingsRepo.isBudgetExplicitlySet
        categoryBudgets.value = settingsRepo.getCategoryBudgets()
    }

    fun exportBackupToUri(uri: Uri, password: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val result = BackupManager.createBackup(context, uri.toString(), password ?: "")
            withContext(Dispatchers.Main) { onResult(result is BackupResult.Success) }
        }
    }

    fun importBackupFromUri(uri: Uri, password: String? = null, onResult: (RestoreResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val result = BackupManager.restoreBackup(context, uri.toString(), password ?: "")
            if (result is RestoreResult.SuccessRestart) {
                monthlyBudget.value = settingsRepo.monthlyBudget
                startDayOfMonth.value = settingsRepo.startDayOfMonth
                necessityChunkLimit.value = settingsRepo.necessityChunkLimit
                isBudgetExplicitlySet.value = settingsRepo.isBudgetExplicitlySet
                categoryBudgets.value = settingsRepo.getCategoryBudgets()
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun configureAutoBackupPassphrase(passphrase: String?) {
        val context = getApplication<Application>().applicationContext
        if (passphrase != null) {
            SecurePassphraseStore.save(context, passphrase)
        } else {
            SecurePassphraseStore.clear(context)
        }
        AutoBackupWorker.schedule(context)
    }
}