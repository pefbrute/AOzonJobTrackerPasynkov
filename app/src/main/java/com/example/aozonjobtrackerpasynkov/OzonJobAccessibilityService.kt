package com.example.aozonjobtrackerpasynkov

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import com.example.aozonjobtrackerpasynkov.data.StatsRepository

class OzonJobAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    enum class State {
        IDLE,
        BOOTSTRAP_DETECT_AND_ROUTE,  // NEW: Определяем экран и выбираем маршрут
        RECOVERY,                     // NEW: Восстановление при неизвестном экране
        FIND_WAREHOUSES_TAB,
        FIND_SEARCH_FIELD,
        TYPE_SEARCH_QUERY,
        SELECT_WAREHOUSE,
        CLICK_ENROLL,
        FIND_JOB_CARD,
        CHECK_SLOTS,
        REFRESH_BACK
    }

    private var refreshCount = 0
    private val MAX_REFRESH_ATTEMPTS = 5000 // Was 10, now practically infinite

    private var currentState = State.IDLE
    private var lastActionTime = 0L
    private var lastDumpTime = 0L
    private val DUMP_INTERVAL = 10000L // 10 seconds
    
    // NEW: Recovery manager для восстановления при неизвестных экранах
    private var recoveryManager: RecoveryManager? = null
    
    // NEW: Счётчик циклов для логирования
    private var cycleId = 0
    
    // NEW: Время начала цикла для статистики
    private var cycleStartTime = 0L
    
    // NEW: Repository для записи статистики
    private var statsRepository: StatsRepository? = null
    
    // Watchdog для периодического «пинка» если события перестали приходить
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastStateSyncTime = 0L
    private var lastProgressTime = 0L // Время последнего успешного действия или обнаружения цели
    
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isCheckRequested && currentState != State.IDLE) {
                rootInActiveWindow?.let { 
                    Log.v(TAG, "Watchdog: kickstarting processEvent (Current State: $currentState)")
                    processEvent(it) 
                }
            }
            watchdogHandler.postDelayed(this, 2500) // Раз в 2.5 секунды
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        instance = null
    }

    companion object {
        const val WAREHOUSE_NAME = "Петровское" 
        const val JOB_NAME = "Производство непрофиль"
        private const val TAG = "OzonJobAccessibility"
        
        private var instance: OzonJobAccessibilityService? = null
        
        private val _serviceState = MutableSharedFlow<String>(
            replay = 1,
            extraBufferCapacity = 5,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val serviceState = _serviceState.asSharedFlow()

        private val _slotStatus = MutableSharedFlow<String?>(
            replay = 1,
            extraBufferCapacity = 5,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val slotStatus = _slotStatus.asSharedFlow()

        var isCheckRequested = false
        
        fun startCheckCycle() {
            instance?.startCheckCycleInternal() ?: Log.e(TAG, "Service not connected")
        }

        fun stopCheckCycle() {
            isCheckRequested = false
            instance?.stopCheckCycleInternal() ?: Log.e(TAG, "Service not connected")
        }
    }
    
    private fun stopCheckCycleInternal() {
        isCheckRequested = false
        currentState = State.IDLE
        Log.d(TAG, "Check cycle STOPPED by user")
        _serviceState.tryEmit("Monitoring Stopped")
    }

    private fun startCheckCycleInternal() {
        // Проверяем Safe Mode
        recoveryManager?.let {
            if (it.isInSafeMode()) {
                val remainingMs = it.getSafeModeRemainingMs()
                Log.w(TAG, "Safe Mode active, remaining: ${remainingMs / 1000}s")
                _serviceState.tryEmit("Safe Mode (${remainingMs / 1000}s)")
                return
            }
        }
        
        isCheckRequested = true
        refreshCount = 0
        cycleId++
        cycleStartTime = System.currentTimeMillis()  // NEW: Засекаем время начала
        
        // NEW: Начинаем с определения экрана
        currentState = State.BOOTSTRAP_DETECT_AND_ROUTE
        Log.d(TAG, "Starting check cycle #$cycleId with BOOTSTRAP_DETECT_AND_ROUTE")
        
        lastProgressTime = System.currentTimeMillis()
        lastStateSyncTime = System.currentTimeMillis()
        
        recoveryManager?.resetForNewCycle()
        
        // Kickstart processing without waiting for event
        rootInActiveWindow?.let { 
            processEvent(it) 
        } ?: Log.w(TAG, "Cannot kickstart: rootInActiveWindow is null")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        instance = this
        
        // NEW: Инициализируем RecoveryManager
        recoveryManager = RecoveryManager(applicationContext, this)
        
        // NEW: Инициализируем StatsRepository
        statsRepository = StatsRepository.getInstance(applicationContext)
        
        watchdogHandler.post(watchdogRunnable)
        _serviceState.tryEmit("Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isCheckRequested) return
        
        val rootNode = rootInActiveWindow
        if (rootNode == null) return
        
        // Only act on Ozon app or system gestures
        if (rootNode.packageName != "ru.ozon.hire") {
            // Log once in a while if we are in wrong app
            val now = System.currentTimeMillis()
            if (now - lastDumpTime > DUMP_INTERVAL) {
                Log.d(TAG, "Waiting for Ozon... current: ${rootNode.packageName}")
                lastDumpTime = now
            }
            return
        }

        processEvent(rootNode)
    }

    private var hasDumpedHierarchy = false

    private var lastState = State.IDLE

    private fun checkOpportunisticJumps(rootNode: AccessibilityNodeInfo): Boolean {
        // REMOVED: Jump to CLICK_ENROLL. 
        // We must follow the flow: FIND_WAREHOUSES -> SEARCH -> TYPE -> SELECT to be safe.
        
        // If we see the job name and potentially slot info, we might be in CHECK_SLOTS
        val hasJob = findNodeRecursive(rootNode) { it.text?.toString()?.contains(JOB_NAME, ignoreCase = true) == true }
        if (hasJob != null) {
            hasJob.recycle()
            
            // Verifier: only jump if we also see "Выберите время" or "К списку работ" or similar
            val isActuallySlotScreen = findNodeRecursive(rootNode) { 
                val t = it.text?.toString() ?: ""
                t.contains("время", ignoreCase = true) || t.contains("списку", ignoreCase = true) || t.contains("регистрация", ignoreCase = true)
            }
            
            if (isActuallySlotScreen != null) {
                isActuallySlotScreen.recycle()
                if (currentState != State.CHECK_SLOTS && currentState != State.FIND_JOB_CARD && 
                    currentState != State.IDLE && currentState != State.REFRESH_BACK) {
                    Log.d(TAG, "Opportunistic jump: Found '$JOB_NAME' + Slot marker, switching to FIND_JOB_CARD")
                    currentState = State.FIND_JOB_CARD
                    return true
                }
            }
        }

        return false
    }

    private fun processEvent(rootNode: AccessibilityNodeInfo) {
        try {
            val sharedPrefs = getSharedPreferences("OzonPrefs", MODE_PRIVATE)
            val fastRefresh = sharedPrefs.getBoolean("fast_refresh", false)
            
            val delaySeconds = if (fastRefresh && (currentState == State.REFRESH_BACK || (currentState == State.CLICK_ENROLL && refreshCount > 0))) {
                sharedPrefs.getFloat("refresh_delay", 1.5f)
            } else {
                sharedPrefs.getFloat("action_delay", 3.0f)
            }
            
            // Dynamic acceleration for simple navigation steps
            val multiplier = when (currentState) {
                State.BOOTSTRAP_DETECT_AND_ROUTE -> 0.2f // Quick screen detection
                State.TYPE_SEARCH_QUERY -> 0.3f          // Loupe -> Keyboard
                State.SELECT_WAREHOUSE -> 0.4f           // Typing -> Select card
                State.CLICK_ENROLL -> 0.3f               // Select -> Enroll button
                State.FIND_JOB_CARD -> 0.3f              // Enroll -> Job list
                State.CHECK_SLOTS -> 0.1f                // Aggressive check for slots (0.3s)
                State.FIND_SEARCH_FIELD -> 0.4f          // Tab -> Loupe
                State.FIND_WAREHOUSES_TAB -> 0.4f        // Start screen -> Tab
                State.REFRESH_BACK -> 0.2f               // Quick back action
                else -> 1.0f
            }
            
            val delayMillis = (delaySeconds * 1000 * multiplier).toLong().coerceAtLeast(100L)

            // throttle actions to avoid rapid double-clicks and state loops
            if (System.currentTimeMillis() - lastActionTime < delayMillis) return

            if (currentState != lastState) {
                Log.d(TAG, "State changed: $lastState -> $currentState")
                hasDumpedHierarchy = false
                lastState = currentState
            }
            
            checkOpportunisticJumps(rootNode)
            
            if (currentState != lastState) {
                Log.d(TAG, "State changed after jump: $lastState -> $currentState")
                lastState = currentState
            }
            
            Log.v(TAG, "processEvent: State=$currentState, Delay=${delayMillis}ms")

            // NEW: Авто-коррекция состояния если мы «заблудились»
            if (currentState != State.RECOVERY && currentState != State.BOOTSTRAP_DETECT_AND_ROUTE) {
                val now = System.currentTimeMillis()
                // Если мы в одном состоянии более 10 секунд без прогресса — сброс на BOOTSTRAP
                if (now - lastProgressTime > 10000L && lastProgressTime != 0L) {
                    Log.w(TAG, "State stuck detected ($currentState for 10s). Resetting to BOOTSTRAP.")
                    currentState = State.BOOTSTRAP_DETECT_AND_ROUTE
                    lastProgressTime = now
                    return
                }
                
                // Периодическая проверка: соответствует ли текущий экран текущему состоянию?
                if (now - lastStateSyncTime > 4000L) {
                    lastStateSyncTime = now
                    val screenResult = ScreenDetector.detectScreen(rootNode)
                    if (screenResult.screenId != ScreenDetector.ScreenId.UNKNOWN && screenResult.confidence > 0.7f) {
                        val predictedState = Router.route(screenResult)
                        // Если Router уверен что мы на другом экране — переключаемся
                        if (predictedState != currentState && predictedState != State.RECOVERY) {
                            Log.i(TAG, "Auto-Sync: Screen is ${screenResult.screenId}, switching $currentState -> $predictedState")
                            currentState = predictedState
                            lastProgressTime = now
                            return
                        }
                    }
                }
            }

            when (currentState) {
                State.BOOTSTRAP_DETECT_AND_ROUTE -> processBootstrapDetectAndRoute(rootNode)
                State.RECOVERY -> processRecovery(rootNode)
                State.FIND_WAREHOUSES_TAB -> processFindWarehousesTab(rootNode)
                State.FIND_SEARCH_FIELD -> processFindSearchField(rootNode)
                State.TYPE_SEARCH_QUERY -> processTypeSearchQuery(rootNode)
                State.SELECT_WAREHOUSE -> processSelectWarehouse(rootNode)
                State.CLICK_ENROLL -> processClickEnroll(rootNode)
                State.FIND_JOB_CARD -> processFindJobCard(rootNode)
                State.CHECK_SLOTS -> processCheckSlots(rootNode)
                State.REFRESH_BACK -> processRefreshBack()
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processEvent: ${e.message}")
        }
    }

    /**
     * NEW: Определяет текущий экран и выбирает маршрут к цели
     */
    private fun processBootstrapDetectAndRoute(rootNode: AccessibilityNodeInfo) {
        val screenResult = ScreenDetector.detectScreen(rootNode)
        
        Log.i(TAG, "BOOTSTRAP cycle #$cycleId: screen=${screenResult.screenId}, " +
              "confidence=${screenResult.confidence}, anchors=${screenResult.matchedAnchors}")
        
        _serviceState.tryEmit("Detected: ${screenResult.screenId}")
        
        // Проверяем, безопасно ли продолжать
        if (!Router.isSafeToAutomate(screenResult)) {
            Log.w(TAG, "Not safe to automate, entering RECOVERY")
            _serviceState.tryEmit("Unknown screen, recovering...")
            _slotStatus.tryEmit(null) // Reset status
            currentState = State.RECOVERY
            return
        }
        
        // Получаем следующее состояние от Router
        val nextState = Router.route(screenResult)
        Log.d(TAG, "Router decision: $nextState")
        
        currentState = nextState
        lastActionTime = System.currentTimeMillis()
    }
    
    /**
     * NEW: Обработка состояния RECOVERY - восстановление при неизвестном экране
     */
    private fun processRecovery(rootNode: AccessibilityNodeInfo) {
        val manager = recoveryManager ?: run {
            Log.e(TAG, "RecoveryManager is null!")
            currentState = State.IDLE
            return
        }
        
        // Повторно определяем экран
        val screenResult = ScreenDetector.detectScreen(rootNode)
        
        Log.d(TAG, "RECOVERY: screen=${screenResult.screenId}, status=${manager.getStatusString()}")
        
        when (val result = manager.executeStep(screenResult)) {
            is RecoveryManager.RecoveryResult.Success -> {
                Log.i(TAG, "Recovery SUCCESS! Routing to ${screenResult.screenId}")
                currentState = Router.route(screenResult)
                _serviceState.tryEmit("Recovered: ${screenResult.screenId}")
            }
            
            is RecoveryManager.RecoveryResult.Continue -> {
                // Ждём следующего события
                lastActionTime = System.currentTimeMillis()
                _serviceState.tryEmit("Recovery in progress...")
            }
            
            is RecoveryManager.RecoveryResult.SafeModeActivated -> {
                Log.w(TAG, "SAFE MODE activated!")
                isCheckRequested = false
                currentState = State.IDLE
                _serviceState.tryEmit("Safe Mode ON")
                // TODO: Отправить уведомление пользователю
            }
            
            is RecoveryManager.RecoveryResult.NeedManualHelp -> {
                Log.e(TAG, "Manual help required!")
                isCheckRequested = false
                currentState = State.IDLE
                _serviceState.tryEmit("Need manual help")
            }
        }
    }

    private fun processRefreshBack() {
        Log.d(TAG, "Refreshing: Executing BACK action (Attempt $refreshCount)")
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Set lastActionTime to give it time to go back
        lastActionTime = System.currentTimeMillis()
        currentState = State.CLICK_ENROLL 
        val msg = "Fast Refresh ($refreshCount/10)..."
        _serviceState.tryEmit(msg)
        _slotStatus.tryEmit(null)
    }

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

    private fun processFindWarehousesTab(rootNode: AccessibilityNodeInfo) {
        val target = findNodeRecursive(rootNode) { node ->
            val id = node.viewIdResourceName ?: ""
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            id.contains("warehouseTab") || text.contains("Склады") || desc.contains("warehouseTab")
        }

        if (target != null) {
            Log.d(TAG, "Found 'Склады' tab. Clicking...")
            performClick(target)
            target.recycle()
            currentState = State.FIND_SEARCH_FIELD
            lastActionTime = System.currentTimeMillis()
            lastProgressTime = System.currentTimeMillis()
            _serviceState.tryEmit("Clicked 'Склады'")
        } else {
            val now = System.currentTimeMillis()
            if (!hasDumpedHierarchy || (now - lastDumpTime > DUMP_INTERVAL)) {
                Log.d(TAG, "Tab 'Склады' not found in hierarchy of ${rootNode.packageName}. Dumping...")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
                lastDumpTime = now
            }
        }
    }

    private fun processFindSearchField(rootNode: AccessibilityNodeInfo) {
        // 1. Check if search bar is ALREADY open
        val searchBar = rootNode.findAccessibilityNodeInfosByViewId("ru.ozon.hire:id/search_src_text").firstOrNull()
        if (searchBar != null || rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null) {
            Log.d(TAG, "Search bar is open, proceeding to type.")
            currentState = State.TYPE_SEARCH_QUERY
            return
        }

        // 2. Click the Search Icon (Loupe) using scanner coordinates
        // User data: [312, 2306][434, 2428]
        val searchX = (312 + 434) / 2
        val searchY = (2306 + 2428) / 2

        // Verify we are on the right screen (see "Выберите склад" or "Карта")
        val header = findNodeRecursive(rootNode) { 
            val t = it.text?.toString() ?: ""
            t.contains("Выберите склад", ignoreCase = true) || t.contains("Карта", ignoreCase = true)
        }

        if (header != null) {
            Log.d(TAG, "Found header '${header.text}'. Clicking Search Icon at ($searchX, $searchY)")
            clickAt(searchX, searchY)
            header.recycle()
            currentState = State.TYPE_SEARCH_QUERY
            lastActionTime = System.currentTimeMillis()
            lastProgressTime = System.currentTimeMillis()
            _serviceState.tryEmit("Clicked Search Icon (Scanner)")
        } else {
            val now = System.currentTimeMillis()
            if (!hasDumpedHierarchy || (now - lastDumpTime > DUMP_INTERVAL)) {
                Log.d(TAG, "Search screen verification failed. Root: ${rootNode.packageName}. Dumping...")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
                lastDumpTime = now
            }
        }
    }

    private fun processTypeSearchQuery(rootNode: AccessibilityNodeInfo) {
        val focus = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) {
            Log.d(TAG, "Input focused. Typing $WAREHOUSE_NAME")
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, WAREHOUSE_NAME)
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            currentState = State.SELECT_WAREHOUSE
            lastActionTime = System.currentTimeMillis()
            lastProgressTime = System.currentTimeMillis()
            _serviceState.tryEmit("Typed Warehouse Name")
        }
    }

    private fun processSelectWarehouse(rootNode: AccessibilityNodeInfo) {
        _serviceState.tryEmit("Searching for $WAREHOUSE_NAME...")
        val target = findNodeRecursive(rootNode) { 
            val text = it.text?.toString() ?: ""
            !it.isEditable && text.equals(WAREHOUSE_NAME, ignoreCase = true)
        }
        
        if (target != null) {
            Log.d(TAG, "Warehouse card '$WAREHOUSE_NAME' found. Clicking...")
            performClick(target)
            target.recycle()
            currentState = State.CLICK_ENROLL
            lastActionTime = System.currentTimeMillis()
            lastProgressTime = System.currentTimeMillis()
        } else {
            val now = System.currentTimeMillis()
            if (!hasDumpedHierarchy || (now - lastDumpTime > DUMP_INTERVAL)) {
                Log.d(TAG, "Warehouse card '$WAREHOUSE_NAME' not found (Recursive). Dumping...")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
                lastDumpTime = now
            }
        }
    }

    private fun processClickEnroll(rootNode: AccessibilityNodeInfo) {
        val target = findNodeRecursive(rootNode) { 
            val t = (it.text?.toString() ?: "") + (it.contentDescription?.toString() ?: "")
            t.contains("Записаться", ignoreCase = true) || t.contains("ГРАФИК", ignoreCase = true)
        }

        if (target != null) {
            Log.d(TAG, "SUCCESS! Found Enroll button ('${target.text}'). Clicking...")
            performClick(target)
            target.recycle()
            currentState = State.FIND_JOB_CARD
            lastActionTime = System.currentTimeMillis()
            lastProgressTime = System.currentTimeMillis()
            _serviceState.tryEmit("Clicked 'Записаться'")
        } else {
            val now = System.currentTimeMillis()
            if (!hasDumpedHierarchy || (now - lastDumpTime > DUMP_INTERVAL)) {
                Log.d(TAG, "Searching for 'Записаться' button... (Current State: $currentState)")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
                lastDumpTime = now
            }
        }
    }

    private fun processFindJobCard(rootNode: AccessibilityNodeInfo) {
        _serviceState.tryEmit("Locating $JOB_NAME...")
        val target = findNodeRecursive(rootNode) { 
            it.text?.toString()?.contains(JOB_NAME, ignoreCase = true) == true 
        }

        if (target != null) {
            Log.d(TAG, "FOUND JOB CARD: $JOB_NAME! Moving to CHECK_SLOTS")
            target.recycle()
            currentState = State.CHECK_SLOTS
            _serviceState.tryEmit("Found Job Card")
        } else {
            val scrollable = findScrollableNode(rootNode)
            if (scrollable != null) {
                Log.d(TAG, "Scrolling to find $JOB_NAME...")
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                lastActionTime = System.currentTimeMillis()
                lastProgressTime = System.currentTimeMillis()
                _serviceState.tryEmit("Scrolling...")
            }
        }
    }

    private fun dumpTexts(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) Log.d(TAG, "Visible Text: $text")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { 
                dumpTexts(it)
                it.recycle()
            }
        }
    }

    private fun processCheckSlots(rootNode: AccessibilityNodeInfo) {
        val jobCard = findNodeRecursive(rootNode) { it.text?.toString()?.contains(JOB_NAME, ignoreCase = true) == true }
        
        if (jobCard != null) {
            val availableDays = mutableListOf<String>()
            val allTexts = mutableListOf<String>()
            collectAllTexts(rootNode, allTexts)
            
            // Heuristic for Ozon Job: days are usually like "7 февраля, Сб"
            val dateRegex = Regex("""\d+\s+[а-яА-Я]+,\s+[а-яА-Я]{2}""")
            
            val foundDates = allTexts.filter { dateRegex.find(it) != null }
            
            // In the combined view, if a job has slots, the dates are visible.
            val noSlotsVisible = allTexts.any { it.contains("НЕТ МЕСТ", ignoreCase = true) }
            
            if (!noSlotsVisible && foundDates.isNotEmpty()) {
                availableDays.addAll(foundDates)
            } else if (foundDates.isNotEmpty()) {
                availableDays.addAll(foundDates)
            }

            val durationMs = System.currentTimeMillis() - cycleStartTime
            val found = availableDays.isNotEmpty()
            
            // Log for statistics every attempt
            statsRepository?.recordCheck(
                isSuccess = true,
                slotsFound = found,
                slotDays = if (found) availableDays.distinct().joinToString(", ") else null,
                durationMs = durationMs
            )

            if (found) {
                 val daysString = availableDays.distinct().joinToString(", ")
                 Log.i(TAG, "=== SLOTS FOUND for $JOB_NAME: $daysString ===")
                 _slotStatus.tryEmit(daysString)
                 refreshCount = 0
                 isCheckRequested = false
                 currentState = State.IDLE
                 
                 recoveryManager?.onCycleSuccess()
                 
                 Log.d(TAG, "Check cycle complete. Slots found. Duration: ${durationMs}ms. Returning Home.")
                 performGlobalAction(GLOBAL_ACTION_HOME)
            } else {
                 Log.i(TAG, "No slots for $JOB_NAME yet.")
                 val sharedPrefs = getSharedPreferences("OzonPrefs", MODE_PRIVATE)
                 val fastRefresh = sharedPrefs.getBoolean("fast_refresh", false)
                 
                 if (fastRefresh && refreshCount < MAX_REFRESH_ATTEMPTS) {
                     refreshCount = refreshCount + 1
                     _slotStatus.tryEmit(null)
                     currentState = State.REFRESH_BACK
                 } else {
                     Log.d(TAG, "No slots and fast refresh exhausted/off. Returning Home.")
                     _slotStatus.tryEmit(null)
                     refreshCount = 0
                     isCheckRequested = false
                     currentState = State.IDLE
                     
                     recoveryManager?.onCycleSuccess()
                     performGlobalAction(GLOBAL_ACTION_HOME)
                 }
            }
            jobCard.recycle()
        } else {
            // Fallback: if we are in CHECK_SLOTS but don't see the job, maybe we see 'Записаться'?
            val hasEnroll = findNodeRecursive(rootNode) { 
                (it.text?.toString() ?: "").contains("Записаться", ignoreCase = true) 
            }
            if (hasEnroll != null) {
                hasEnroll.recycle()
                Log.d(TAG, "Stuck in CHECK_SLOTS but see 'Записаться'. Moving to CLICK_ENROLL.")
                _serviceState.tryEmit("Stuck? Retrying click...")
                currentState = State.CLICK_ENROLL
            } else {
                _slotStatus.tryEmit(null)
            }
        }
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val t = node.text?.toString()
        if (!t.isNullOrEmpty()) list.add(t)
        val d = node.contentDescription?.toString()
        if (!d.isNullOrEmpty()) list.add(d)
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { 
                collectAllTexts(it, list)
                it.recycle()
            }
        }
    }

    private fun dumpNodeHierarchy(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 15) return // Too deep
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.replace("\n", " ") ?: ""
        val desc = node.contentDescription?.toString()?.replace("\n", " ") ?: ""
        val className = node.className?.toString()?.split(".")?.last() ?: "Node"
        val clickable = if (node.isClickable) "[C]" else ""
        
        Log.v(TAG, "$indent$className: T='$text' D='$desc' $clickable")
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { 
                dumpNodeHierarchy(it, depth + 1)
                it.recycle()
            }
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findScrollableNode(child)
            if (res != null) return res
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable && target.parent != null) {
            target = target.parent
        }
        
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (target.isClickable) {
            val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!success) {
                clickAt(rect.centerX(), rect.centerY())
            }
        } else {
             clickAt(rect.centerX(), rect.centerY())
        }
    }

    private fun clickAt(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
