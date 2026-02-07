package com.example.aozonjobtrackerpasynkov

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ScreenDetector - распознаёт текущий экран Ozon Job по якорным элементам.
 * Возвращает ScreenResult с id экрана, уверенностью и списком найденных якорей.
 */
object ScreenDetector {
    
    private const val TAG = "ScreenDetector"
    
    /**
     * Идентификаторы экранов MVP
     */
    enum class ScreenId {
        RECORDS_MAIN,              // Главный экран "Записи"
        WAREHOUSES_LIST,           // Экран "Выберите склад"
        WAREHOUSE_CARD_PETROVSKOE, // Карточка склада Петровское
        WORKS_LIST_PETROVSKOE,     // Список работ склада
        UNKNOWN                    // Неизвестный экран
    }
    
    /**
     * Результат распознавания экрана
     */
    data class ScreenResult(
        val screenId: ScreenId,
        val confidence: Float,        // 0.0 - 1.0
        val matchedAnchors: List<String>
    )
    
    // Минимальный порог уверенности для принятия решения
    private const val MIN_CONFIDENCE_THRESHOLD = 0.5f
    
    /**
     * Распознаёт текущий экран по дереву AccessibilityNodeInfo
     */
    fun detectScreen(root: AccessibilityNodeInfo?): ScreenResult {
        if (root == null) {
            Log.w(TAG, "detectScreen: root is null")
            return ScreenResult(ScreenId.UNKNOWN, 0f, emptyList())
        }
        
        // Собираем все тексты для анализа
        val allTexts = mutableListOf<String>()
        collectAllTexts(root, allTexts)
        
        val results = mutableListOf<ScreenResult>()
        
        // Проверяем каждый экран
        results.add(checkWorksListPetrovskoe(allTexts, root))
        results.add(checkWarehouseCardPetrovskoe(allTexts, root))
        results.add(checkWarehousesList(allTexts, root))
        results.add(checkRecordsMain(allTexts, root))
        
        // Выбираем результат с наивысшей уверенностью
        val bestResult = results.maxByOrNull { it.confidence } 
            ?: ScreenResult(ScreenId.UNKNOWN, 0f, emptyList())
        
        Log.d(TAG, "detectScreen: best=${bestResult.screenId}, confidence=${bestResult.confidence}, anchors=${bestResult.matchedAnchors}")
        
        return if (bestResult.confidence >= MIN_CONFIDENCE_THRESHOLD) {
            bestResult
        } else {
            Log.w(TAG, "Confidence too low (${bestResult.confidence}), returning UNKNOWN")
            ScreenResult(ScreenId.UNKNOWN, bestResult.confidence, bestResult.matchedAnchors)
        }
    }
    
    /**
     * Проверяет экран WORKS_LIST_PETROVSKOE (список работ)
     * Якоря: "Производство непрофиль", "К списку работ", "Выберите время"
     */
    private fun checkWorksListPetrovskoe(texts: List<String>, root: AccessibilityNodeInfo): ScreenResult {
        val anchors = mutableListOf<String>()
        var score = 0f
        
        // Primary anchor: название работы
        if (texts.any { it.contains(OzonJobAccessibilityService.JOB_NAME, ignoreCase = true) }) {
            anchors.add("JOB_NAME:${OzonJobAccessibilityService.JOB_NAME}")
            score += 0.5f
        }
        
        // Secondary anchors
        if (texts.any { it.contains("К списку работ", ignoreCase = true) }) {
            anchors.add("К списку работ")
            score += 0.25f
        }
        if (texts.any { it.contains("Выберите время", ignoreCase = true) }) {
            anchors.add("Выберите время")
            score += 0.25f
        }
        if (texts.any { it.contains("регистрация", ignoreCase = true) }) {
            anchors.add("регистрация")
            score += 0.15f
        }
        
        return ScreenResult(ScreenId.WORKS_LIST_PETROVSKOE, score.coerceAtMost(1f), anchors)
    }
    
    /**
     * Проверяет экран WAREHOUSE_CARD_PETROVSKOE (карточка склада)
     * Якоря: "Петровское" + "Записаться", адрес склада
     */
    private fun checkWarehouseCardPetrovskoe(texts: List<String>, root: AccessibilityNodeInfo): ScreenResult {
        val anchors = mutableListOf<String>()
        var score = 0f
        
        val hasWarehouseName = texts.any { 
            it.equals(OzonJobAccessibilityService.WAREHOUSE_NAME, ignoreCase = true) 
        }
        val hasEnrollButton = texts.any { 
            it.contains("Записаться", ignoreCase = true) || it.contains("ГРАФИК", ignoreCase = true)
        }
        
        if (hasWarehouseName) {
            anchors.add("WAREHOUSE:${OzonJobAccessibilityService.WAREHOUSE_NAME}")
            score += 0.3f
        }
        
        if (hasEnrollButton) {
            anchors.add("Записаться")
            score += 0.4f
        }
        
        // Дополнительные якоря карточки склада
        if (texts.any { it.contains("Московская область", ignoreCase = true) }) {
            anchors.add("Московская область")
            score += 0.15f
        }
        
        // Негативный якорь: если видим "Выберите склад", это не карточка
        if (texts.any { it.contains("Выберите склад", ignoreCase = true) }) {
            score -= 0.3f
        }
        
        return ScreenResult(ScreenId.WAREHOUSE_CARD_PETROVSKOE, score.coerceIn(0f, 1f), anchors)
    }
    
    /**
     * Проверяет экран WAREHOUSES_LIST (выбор склада)
     * Якоря: "Выберите склад", "Карта", иконка поиска
     */
    private fun checkWarehousesList(texts: List<String>, root: AccessibilityNodeInfo): ScreenResult {
        val anchors = mutableListOf<String>()
        var score = 0f
        
        if (texts.any { it.contains("Выберите склад", ignoreCase = true) }) {
            anchors.add("Выберите склад")
            score += 0.5f
        }
        
        if (texts.any { it.equals("Карта", ignoreCase = true) }) {
            anchors.add("Карта")
            score += 0.2f
        }
        
        // Проверяем наличие поля поиска
        val hasSearchField = findNodeRecursive(root) { node ->
            node.viewIdResourceName?.contains("search") == true ||
            node.contentDescription?.toString()?.contains("поиск", ignoreCase = true) == true
        } != null
        
        if (hasSearchField) {
            anchors.add("SearchField")
            score += 0.2f
        }
        
        // Негативный якорь: если видим "Записаться", это карточка склада
        if (texts.any { it.contains("Записаться", ignoreCase = true) }) {
            score -= 0.4f
        }
        
        return ScreenResult(ScreenId.WAREHOUSES_LIST, score.coerceIn(0f, 1f), anchors)
    }
    
    /**
     * Проверяет экран RECORDS_MAIN (главный экран "Записи")
     * Якоря: "Записи" в хедере, нижняя панель с "Склады", "Выплаты"
     */
    private fun checkRecordsMain(texts: List<String>, root: AccessibilityNodeInfo): ScreenResult {
        val anchors = mutableListOf<String>()
        var score = 0f
        
        // Нижняя панель навигации
        val hasBottomNav = findNodeRecursive(root) { node ->
            val id = node.viewIdResourceName ?: ""
            id.contains("warehouseTab") || id.contains("paymentTab") || id.contains("recordsTab")
        } != null
        
        if (hasBottomNav) {
            anchors.add("BottomNavigation")
            score += 0.3f
        }
        
        // Табы "Склады", "Выплаты" видны в нижней панели
        if (texts.any { it.equals("Склады", ignoreCase = true) }) {
            anchors.add("Склады")
            score += 0.2f
        }
        if (texts.any { it.equals("Выплаты", ignoreCase = true) }) {
            anchors.add("Выплаты")
            score += 0.15f
        }
        if (texts.any { it.equals("Записи", ignoreCase = true) }) {
            anchors.add("Записи")
            score += 0.2f
        }
        
        // Негативные якоря
        if (texts.any { it.contains("Выберите склад", ignoreCase = true) }) {
            score -= 0.4f  // Это WAREHOUSES_LIST
        }
        if (texts.any { it.contains("Записаться", ignoreCase = true) }) {
            score -= 0.3f  // Это карточка склада
        }
        
        return ScreenResult(ScreenId.RECORDS_MAIN, score.coerceIn(0f, 1f), anchors)
    }
    
    /**
     * Рекурсивно собирает все тексты из дерева нод
     */
    private fun collectAllTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        node.text?.toString()?.let { if (it.isNotBlank()) list.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) list.add(it) }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectAllTexts(child, list)
                child.recycle()
            }
        }
    }
    
    /**
     * Рекурсивный поиск ноды по предикату
     */
    private fun findNodeRecursive(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, predicate)
            if (found != null) {
                if (child != found) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
}
