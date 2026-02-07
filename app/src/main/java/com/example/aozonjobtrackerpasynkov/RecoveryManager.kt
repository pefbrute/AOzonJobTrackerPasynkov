package com.example.aozonjobtrackerpasynkov

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * RecoveryManager - управляет восстановлением при неизвестном экране.
 * 
 * Алгоритм:
 * 1. Back recovery: нажать BACK до N раз, проверяя экран после каждого
 * 2. Home+relaunch: если BACK не помог, нажать HOME и перезапустить Ozon Job
 * 3. Safe Mode: если X циклов подряд провалились, пауза на Y минут
 * 4. Backoff: увеличение интервала при повторных провалах
 */
class RecoveryManager(
    private val context: Context,
    private val accessibilityService: AccessibilityService
) {
    
    companion object {
        private const val TAG = "RecoveryManager"
        
        // Лимиты восстановления
        const val MAX_BACK_ATTEMPTS = 5
        const val MAX_RELAUNCH_ATTEMPTS = 1
        const val CONSECUTIVE_FAILURES_FOR_SAFE_MODE = 3
        const val SAFE_MODE_DURATION_MS = 5 * 60 * 1000L  // 5 минут
        const val BACKOFF_INCREMENT_MS = 60 * 1000L       // +60 секунд за провал
        const val MAX_BACKOFF_MS = 5 * 60 * 1000L         // Максимум 5 минут
    }
    
    // Состояние текущего recovery цикла
    private var backAttempts = 0
    private var relaunchAttempts = 0
    
    // Глобальные счётчики
    private var consecutiveFailures = 0
    private var currentBackoffMs = 0L
    
    // Safe Mode
    private var safeModeUntil = 0L
    
    /**
     * Результат шага recovery
     */
    sealed class RecoveryResult {
        object Continue : RecoveryResult()          // Продолжить recovery
        object Success : RecoveryResult()           // Успешно вернулись на известный экран
        object SafeModeActivated : RecoveryResult() // Активирован Safe Mode
        object NeedManualHelp : RecoveryResult()    // Требуется ручное вмешательство
    }
    
    /**
     * Действие recovery
     */
    sealed class RecoveryAction {
        object PressBack : RecoveryAction()
        object PressHome : RecoveryAction()
        data class RelaunchApp(val packageName: String) : RecoveryAction()
        object Wait : RecoveryAction()
        object EnterSafeMode : RecoveryAction()
    }
    
    /**
     * Проверяет, активен ли Safe Mode
     */
    fun isInSafeMode(): Boolean {
        val now = System.currentTimeMillis()
        if (safeModeUntil > now) {
            val remainingMs = safeModeUntil - now
            Log.d(TAG, "Safe Mode active, remaining: ${remainingMs / 1000}s")
            return true
        }
        return false
    }
    
    /**
     * Возвращает время до окончания Safe Mode (в мс), или 0 если не активен
     */
    fun getSafeModeRemainingMs(): Long {
        val now = System.currentTimeMillis()
        return if (safeModeUntil > now) safeModeUntil - now else 0
    }
    
    /**
     * Возвращает текущий backoff в мс
     */
    fun getCurrentBackoffMs(): Long = currentBackoffMs
    
    /**
     * Определяет следующее действие recovery
     */
    fun getNextAction(): RecoveryAction {
        // Проверяем Safe Mode
        if (isInSafeMode()) {
            return RecoveryAction.Wait
        }
        
        // 1. Сначала пробуем BACK
        if (backAttempts < MAX_BACK_ATTEMPTS) {
            return RecoveryAction.PressBack
        }
        
        // 2. Затем Home + Relaunch
        if (relaunchAttempts < MAX_RELAUNCH_ATTEMPTS) {
            return if (relaunchAttempts == 0) {
                RecoveryAction.PressHome
            } else {
                RecoveryAction.RelaunchApp("ru.ozon.hire")
            }
        }
        
        // 3. Исчерпали все попытки — Safe Mode
        return RecoveryAction.EnterSafeMode
    }
    
    /**
     * Выполняет шаг recovery и возвращает результат.
     * Вызывается из State Machine при состоянии RECOVERY.
     */
    fun executeStep(currentScreen: ScreenDetector.ScreenResult): RecoveryResult {
        Log.d(TAG, "executeStep: backAttempts=$backAttempts, relaunchAttempts=$relaunchAttempts, screen=${currentScreen.screenId}")
        
        // Проверяем, вышли ли из UNKNOWN
        if (currentScreen.screenId != ScreenDetector.ScreenId.UNKNOWN) {
            Log.i(TAG, "Recovery SUCCESS! Found known screen: ${currentScreen.screenId}")
            onRecoverySuccess()
            return RecoveryResult.Success
        }
        
        // Проверяем Safe Mode
        if (isInSafeMode()) {
            return RecoveryResult.SafeModeActivated
        }
        
        val action = getNextAction()
        
        when (action) {
            is RecoveryAction.PressBack -> {
                backAttempts++
                Log.d(TAG, "Executing BACK ($backAttempts/$MAX_BACK_ATTEMPTS)")
                accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return RecoveryResult.Continue
            }
            
            is RecoveryAction.PressHome -> {
                Log.d(TAG, "Executing HOME")
                accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                relaunchAttempts++
                return RecoveryResult.Continue
            }
            
            is RecoveryAction.RelaunchApp -> {
                Log.d(TAG, "Relaunching ${action.packageName}")
                launchOzonJob()
                return RecoveryResult.Continue
            }
            
            is RecoveryAction.Wait -> {
                return RecoveryResult.SafeModeActivated
            }
            
            is RecoveryAction.EnterSafeMode -> {
                activateSafeMode()
                return RecoveryResult.SafeModeActivated
            }
        }
    }
    
    /**
     * Сбрасывает счётчики для нового цикла проверки
     */
    fun resetForNewCycle() {
        backAttempts = 0
        relaunchAttempts = 0
        Log.d(TAG, "Reset for new cycle")
    }
    
    /**
     * Вызывается при успешном recovery
     */
    private fun onRecoverySuccess() {
        consecutiveFailures = 0
        currentBackoffMs = 0
        resetForNewCycle()
        Log.i(TAG, "Recovery success - counters reset")
    }
    
    /**
     * Вызывается при провале цикла (не нашли слоты и экран UNKNOWN в конце)
     */
    fun onCycleFailure() {
        consecutiveFailures++
        currentBackoffMs = (currentBackoffMs + BACKOFF_INCREMENT_MS).coerceAtMost(MAX_BACKOFF_MS)
        Log.w(TAG, "Cycle failure #$consecutiveFailures, backoff=${currentBackoffMs}ms")
        
        if (consecutiveFailures >= CONSECUTIVE_FAILURES_FOR_SAFE_MODE) {
            activateSafeMode()
        }
        
        resetForNewCycle()
    }
    
    /**
     * Вызывается при успешном цикле (нашли слоты или без ошибок)
     */
    fun onCycleSuccess() {
        consecutiveFailures = 0
        currentBackoffMs = 0
        resetForNewCycle()
        Log.d(TAG, "Cycle success - all counters reset")
    }
    
    /**
     * Активирует Safe Mode
     */
    private fun activateSafeMode() {
        safeModeUntil = System.currentTimeMillis() + SAFE_MODE_DURATION_MS
        Log.w(TAG, "SAFE MODE ACTIVATED for ${SAFE_MODE_DURATION_MS / 1000}s")
        
        // TODO: Отправить уведомление пользователю через сервис
    }
    
    /**
     * Принудительно выключает Safe Mode
     */
    fun deactivateSafeMode() {
        safeModeUntil = 0
        consecutiveFailures = 0
        Log.i(TAG, "Safe Mode manually deactivated")
    }
    
    /**
     * Запускает Ozon Job через intent
     */
    private fun launchOzonJob() {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("ru.ozon.hire")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched Ozon Job")
            } else {
                Log.e(TAG, "Ozon Job not found!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Ozon Job: ${e.message}")
        }
    }
    
    /**
     * Возвращает статус для логирования
     */
    fun getStatusString(): String {
        return "back=$backAttempts/$MAX_BACK_ATTEMPTS, relaunch=$relaunchAttempts/$MAX_RELAUNCH_ATTEMPTS, " +
               "failures=$consecutiveFailures, backoff=${currentBackoffMs}ms, safeMode=${isInSafeMode()}"
    }
}
