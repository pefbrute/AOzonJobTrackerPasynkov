package com.example.aozonjobtrackerpasynkov

import android.util.Log

/**
 * Router - определяет следующее состояние State Machine
 * на основе распознанного экрана.
 */
object Router {
    
    private const val TAG = "Router"
    
    /**
     * Определяет следующее состояние на основе текущего экрана.
     * Выбирает самый короткий путь к цели (список работ Петровское).
     */
    fun route(screenResult: ScreenDetector.ScreenResult): OzonJobAccessibilityService.State {
        val decision = when (screenResult.screenId) {
            ScreenDetector.ScreenId.WORKS_LIST_PETROVSKOE -> {
                // Уже на экране работ - сразу проверяем слоты
                OzonJobAccessibilityService.State.FIND_JOB_CARD
            }
            
            ScreenDetector.ScreenId.WAREHOUSE_CARD_PETROVSKOE -> {
                // На карточке склада - жмём "Записаться"
                OzonJobAccessibilityService.State.CLICK_ENROLL
            }
            
            ScreenDetector.ScreenId.WAREHOUSES_LIST -> {
                // На экране выбора склада - ищем/вводим поиск
                OzonJobAccessibilityService.State.FIND_SEARCH_FIELD
            }
            
            ScreenDetector.ScreenId.RECORDS_MAIN -> {
                // На главном экране - идём в "Склады"
                OzonJobAccessibilityService.State.FIND_WAREHOUSES_TAB
            }
            
            ScreenDetector.ScreenId.UNKNOWN -> {
                // Неизвестный экран - запускаем recovery
                OzonJobAccessibilityService.State.RECOVERY
            }
        }
        
        Log.d(TAG, "route: ${screenResult.screenId} (confidence=${screenResult.confidence}) -> $decision")
        return decision
    }
    
    /**
     * Определяет, безопасно ли продолжать автоматизацию.
     * Возвращает false, если видны признаки требующие ручного вмешательства.
     */
    fun isSafeToAutomate(screenResult: ScreenDetector.ScreenResult): Boolean {
        // Если confidence слишком низкий и экран UNKNOWN - небезопасно
        if (screenResult.screenId == ScreenDetector.ScreenId.UNKNOWN && 
            screenResult.confidence < 0.3f) {
            Log.w(TAG, "isSafeToAutomate: false - UNKNOWN screen with low confidence")
            return false
        }
        return true
    }
}
