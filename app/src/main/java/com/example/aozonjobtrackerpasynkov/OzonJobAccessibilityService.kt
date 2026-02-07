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

class OzonJobAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private enum class State {
        IDLE,
        FIND_WAREHOUSES_TAB,
        FIND_SEARCH_FIELD,
        TYPE_SEARCH_QUERY,
        SELECT_WAREHOUSE,
        CLICK_ENROLL,
        FIND_JOB_CARD,
        CHECK_SLOTS
    }

    private var currentState = State.IDLE
    private var lastActionTime = 0L

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
        
        private val _serviceState = MutableSharedFlow<String>(replay = 1)
        val serviceState = _serviceState.asSharedFlow()

        private val _slotStatus = MutableSharedFlow<String?>(replay = 1)
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
        isCheckRequested = true
        currentState = State.FIND_WAREHOUSES_TAB 
        Log.d(TAG, "Starting check cycle")
        
        // Kickstart processing without waiting for event
        rootInActiveWindow?.let { 
            processEvent(it) 
        } ?: Log.w(TAG, "Cannot kickstart: rootInActiveWindow is null")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        instance = this
        _serviceState.tryEmit("Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.v(TAG, "onAccessibilityEvent: type=${event?.eventType}, package=${event?.packageName}, req=$isCheckRequested")
        
        if (!isCheckRequested) return
        
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "rootInActiveWindow is null")
            return
        }
        
        if ((System.currentTimeMillis() % 3000) < 100) {
            Log.d(TAG, "Active App: ${rootNode.packageName}, State: $currentState")
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
            if (currentState != State.CHECK_SLOTS && currentState != State.FIND_JOB_CARD && currentState != State.IDLE) {
                Log.d(TAG, "Opportunistic jump: Found '$JOB_NAME', switching to FIND_JOB_CARD")
                currentState = State.FIND_JOB_CARD
                return true
            }
        }

        return false
    }

    private fun processEvent(rootNode: AccessibilityNodeInfo) {
        try {
            val sharedPrefs = getSharedPreferences("OzonPrefs", MODE_PRIVATE)
            val delaySeconds = sharedPrefs.getFloat("action_delay", 3.0f)
            val delayMillis = (delaySeconds * 1000).toLong()

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
            
            Log.v(TAG, "processEvent: State=$currentState, RootPkg=${rootNode.packageName}")

            when (currentState) {
                State.FIND_WAREHOUSES_TAB -> processFindWarehousesTab(rootNode)
                State.FIND_SEARCH_FIELD -> processFindSearchField(rootNode)
                State.TYPE_SEARCH_QUERY -> processTypeSearchQuery(rootNode)
                State.SELECT_WAREHOUSE -> processSelectWarehouse(rootNode)
                State.CLICK_ENROLL -> processClickEnroll(rootNode)
                State.FIND_JOB_CARD -> processFindJobCard(rootNode)
                State.CHECK_SLOTS -> processCheckSlots(rootNode)
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processEvent", e)
        }
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
            _serviceState.tryEmit("Clicked 'Склады'")
        } else {
            if (!hasDumpedHierarchy || (System.currentTimeMillis() % 10000) < 500) {
                Log.d(TAG, "Tab 'Склады' not found in hierarchy of ${rootNode.packageName}. Dumping...")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
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
            _serviceState.tryEmit("Clicked Search Icon (Scanner)")
        } else {
            if (!hasDumpedHierarchy || (System.currentTimeMillis() % 8000) < 500) {
                Log.d(TAG, "Search screen verification failed. Root: ${rootNode.packageName}. Dumping...")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
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
            _serviceState.tryEmit("Typed Warehouse Name")
        }
    }

    private fun processSelectWarehouse(rootNode: AccessibilityNodeInfo) {
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
        } else {
            if (!hasDumpedHierarchy || (System.currentTimeMillis() % 10000) < 500) {
                Log.d(TAG, "Warehouse card '$WAREHOUSE_NAME' not found (Recursive). Dumping...")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
            }
        }
    }

    private fun processClickEnroll(rootNode: AccessibilityNodeInfo) {
        val target = findNodeRecursive(rootNode) { 
            val t = it.text?.toString() ?: ""
            t.contains("Записаться", ignoreCase = true)
        }

        if (target != null) {
            Log.d(TAG, "SUCCESS! Found Enroll button ('${target.text}'). Clicking...")
            performClick(target)
            target.recycle()
            currentState = State.FIND_JOB_CARD
            lastActionTime = System.currentTimeMillis()
            _serviceState.tryEmit("Clicked 'Записаться'")
        } else {
            if (!hasDumpedHierarchy || (System.currentTimeMillis() % 10000) < 500) {
                Log.d(TAG, "Still missing 'Записаться' button. Dumping...")
                dumpNodeHierarchy(rootNode, 0)
                hasDumpedHierarchy = true
            }
        }
    }

    private fun processFindJobCard(rootNode: AccessibilityNodeInfo) {
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
            // If "НЕТ МЕСТ" is present, we need to be careful.
            val noSlotsVisible = allTexts.any { it.contains("НЕТ МЕСТ", ignoreCase = true) }
            
            if (!noSlotsVisible && foundDates.isNotEmpty()) {
                availableDays.addAll(foundDates)
            } else if (foundDates.isNotEmpty()) {
                // If some dates are shown but "НЕТ МЕСТ" is also there, 
                // it might mean some days are closed. 
                // This is complex without full hierarchy, but let's take all found dates 
                // if we don't see "НЕТ МЕСТ" right next to them.
                availableDays.addAll(foundDates)
            }

            if (availableDays.isNotEmpty()) {
                 val daysString = availableDays.distinct().joinToString(", ")
                 Log.i(TAG, "=== SLOTS FOUND for $JOB_NAME: $daysString ===")
                 _slotStatus.tryEmit(daysString)
            } else {
                 Log.i(TAG, "No slots for $JOB_NAME yet.")
                 _slotStatus.tryEmit(null)
            }
            
            jobCard.recycle()
            isCheckRequested = false
            currentState = State.IDLE
            Log.d(TAG, "Check cycle complete. Returning Home.")
            performGlobalAction(GLOBAL_ACTION_HOME)
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
        // Disabled for production to save resources
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
