package kr.disys.baedalin.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.content.Intent
import kr.disys.baedalin.KeyRecordingState
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.model.Presets

class KeyMapperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyCode = -1
    private var clickCount = 0
    private val doubleClickTimeout = 300L
    private val longPressTimeout = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressed = false

    override fun onCreate() {
        super.onCreate()
        Log.d("KeyMapper", "Service onCreate - Process: ${android.os.Process.myPid()}")
    }

    override fun onServiceConnected() {
        Log.d("KeyMapper", "Service Connected - Setting up config")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or 
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        this.serviceInfo = info
        Log.d("KeyMapper", "Service Config Applied: ${info.flags}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // 1. 자신의 앱 패키지이거나 시스템 관련 패키지는 무시 (이때는 상태 변경 금지)
            if (packageName == "kr.disys.baedalin" || 
                packageName == "com.android.systemui" || 
                packageName == "android") {
                return
            }

            val preset = Presets.getPresetFromPackage(packageName)
            
            if (FloatingWidgetService.isRunning.value) {
                if (preset != null) {
                    val intent = Intent(this, FloatingWidgetService::class.java).apply {
                        action = FloatingWidgetService.ACTION_LOAD_PRESET
                        putExtra("preset_name", preset)
                    }
                    startService(intent)
                } else {
                    // 2. 확실히 다른 앱(유튜브, 홈 화면 등)으로 나갔을 때만 숨김
                    val intent = Intent(this, FloatingWidgetService::class.java).apply {
                        action = FloatingWidgetService.ACTION_HIDE_PRESETS
                    }
                    startService(intent)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!FloatingWidgetService.isRunning.value || KeyRecordingState.isRecording) {
            return false
        }
        
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val targetDescriptor = prefs.getString("selected_device_descriptor", null)
        
        if (targetDescriptor != null) {
            val device = InputDevice.getDevice(event.deviceId)
            if (device == null || device.descriptor != targetDescriptor) {
                return false
            }
        }
        
        val keyCode = event.keyCode
        val action = event.action
        val prefix = targetDescriptor ?: "GLOBAL"
        val isMapped = isKeyMapped(keyCode, prefix)
        
        if (!isMapped) return super.onKeyEvent(event)

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true
            if (keyCode != lastKeyCode) {
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
                clickCount = 0
            }
            lastKeyCode = keyCode
            return true
        }

        if (action == KeyEvent.ACTION_UP) {
            clickCount++
            
            pendingClickRunnable?.let { handler.removeCallbacks(it) }
            
            pendingClickRunnable = Runnable {
                val type = if (clickCount >= 2) ClickType.DOUBLE else ClickType.SINGLE
                handleAction(keyCode, type, prefix)
                clickCount = 0
            }.also { handler.postDelayed(it, doubleClickTimeout) }
            
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun cancelAllTimers() {
        handler.removeCallbacksAndMessages(null)
        pendingClickRunnable = null
        longPressRunnable = null
    }

    private fun isKeyMapped(keyCode: Int, prefix: String): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        var found = false
        DeliveryFunction.entries.forEach { function ->
            val storedKey = prefs.getInt("${prefix}_${function.name}_keycode", -1)
            if (storedKey == keyCode) {
                found = true
            }
        }
        return found
    }

    private fun handleAction(keyCode: Int, clickType: ClickType, prefix: String): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val activePreset = prefs.getString("active_preset", "DEFAULT") ?: "DEFAULT"
        
        Log.d("KeyMapper", "handleAction: keyCode=$keyCode, clickType=$clickType, prefix=$prefix, activePreset=$activePreset")
        
        val function = DeliveryFunction.entries.find { func ->
            val mappedKey = prefs.getInt("${prefix}_${func.name}_keycode", -1)
            val mappedClick = prefs.getString("${prefix}_${func.name}_clicktype", ClickType.SINGLE.name)
            mappedKey == keyCode && mappedClick == clickType.name
        }
        
        if (function != null) {
            val displayMetrics = resources.displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val centerY = displayMetrics.heightPixels / 2f

            when (function) {
                DeliveryFunction.ZOOM_OUT -> {
                    Log.d("KeyMapper", "PERFORMING ZOOM_OUT: Double-tap and Drag UP")
                    val gestureBuilder = GestureDescription.Builder()

                    // 1. 첫 번째 탭 (빠르게 떼기)
                    val tapPath = Path()
                    tapPath.moveTo(centerX, centerY)
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))

                    // 2. 두 번째 탭 후 위로 드래그 (축소)
                    val swipePath = Path()
                    swipePath.moveTo(centerX, centerY)
                    swipePath.lineTo(centerX, centerY - 200f) // 위로 이동
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 100, 200))

                    dispatchGesture(gestureBuilder.build(), null, null)
                    return true
                }

                DeliveryFunction.ZOOM_IN -> {
                    Log.d("KeyMapper", "PERFORMING ZOOM_IN: Double-tap and Drag DOWN")
                    val gestureBuilder = GestureDescription.Builder()

                    // 1. 첫 번째 탭
                    val tapPath = Path()
                    tapPath.moveTo(centerX, centerY)
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))

                    // 2. 두 번째 탭 후 아래로 드래그 (확대)
                    val swipePath = Path()
                    swipePath.moveTo(centerX, centerY)
                    swipePath.lineTo(centerX, centerY + 200f) // 아래로 이동
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 100, 200))

                    dispatchGesture(gestureBuilder.build(), null, null)
                    return true
                }
                else -> {
                    val x = prefs.getInt("${activePreset}_${function.name}_x", -1).toFloat()
                    val y = prefs.getInt("${activePreset}_${function.name}_y", -1).toFloat()
                    
                    if (x != -1f && y != -1f) {
                        val tapX = x + 50f
                        val tapY = y + 90f
                        Log.d("KeyMapper", "PERFORMING ACTION: ${function.name} at ($tapX, $tapY)")
                        performTap(tapX, tapY)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 100) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(gestureBuilder.build(), null, null)
    }
}
