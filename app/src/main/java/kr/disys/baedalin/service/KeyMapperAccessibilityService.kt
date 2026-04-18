package kr.disys.baedalin.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.KeyRecordingState

class KeyMapperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyCode = -1
    private var clickCount = 0
    private val doubleClickTimeout = 300L
    private val longPressTimeout = 500L
    
    private var pendingClickRunnable: Runnable? = null
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
            // flagDefault를 제거하고 flagRequestFilterKeyEvents를 확실히 포함
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or 
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        this.serviceInfo = info
        Log.d("KeyMapper", "Service Config Applied: ${info.flags}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log.d("KeyMapper", "AccessibilityEvent: ${event?.eventType}")
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 키 입력 모드(녹화 중)일 때는 가로채지 않고 MainActivity로 이벤트를 보냄
        if (KeyRecordingState.isRecording) {
            return false
        }
        
        val keyCode = event.keyCode
        val action = event.action

        // 모든 키 입력을 일단 로그로 남겨서 서비스가 이벤트를 받는지 확인
        Log.d("KeyMapper", "Incoming KeyEvent: keyCode=$keyCode, action=$action")

        // KeyCode 24 (Volume Up) 특수 처리: 콜확인 동작으로 즉시 변환
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.d("KeyMapper", "Volume Up detected, performing CALL_CHECK tap")
                handleCallCheckAction()
            }
            return true
        }

        val isMapped = isKeyMapped(keyCode)
        
        if (isMapped) {
            Log.d("KeyMapper", ">> DETECTED MAPPED KEY: $keyCode <<")
        }

        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode != lastKeyCode) {
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
                clickCount = 0
            }
            
            lastKeyCode = keyCode
            isLongPressed = false
            
            handler.postDelayed({
                if (lastKeyCode == keyCode && !isLongPressed) {
                    isLongPressed = true
                    if (handleAction(keyCode, ClickType.LONG)) {
                        Log.d("KeyMapper", "Long press handled for $keyCode")
                    }
                }
            }, longPressTimeout)
            
            if (isMapped) return true
        }

        if (action == KeyEvent.ACTION_UP) {
            handler.removeCallbacksAndMessages(null)
            
            if (isLongPressed) {
                isLongPressed = false
                clickCount = 0
                return if (isMapped) true else super.onKeyEvent(event)
            }

            clickCount++
            
            pendingClickRunnable?.let { handler.removeCallbacks(it) }
            
            val clickRunnable = Runnable {
                handleAction(keyCode, if (clickCount >= 2) ClickType.DOUBLE else ClickType.SINGLE)
                clickCount = 0
                lastKeyCode = -1
            }
            
            pendingClickRunnable = clickRunnable
            handler.postDelayed(clickRunnable, doubleClickTimeout)
            
            if (isMapped) return true
        }

        return super.onKeyEvent(event)
    }

    private fun isKeyMapped(keyCode: Int): Boolean {
        val prefs = getSharedPreferences("mappings", MODE_PRIVATE)
        // SharedPreferences 내용을 상세히 출력하여 실제 저장된 값을 확인
        val allEntries = prefs.all
        Log.d("KeyMapper", "Checking mapping for key $keyCode. Current mappings count: ${allEntries.size}")
        
        var found = false
        DeliveryFunction.entries.forEach { function ->
            val storedKey = prefs.getInt("${function.name}_keycode", -1)
            if (storedKey != -1) {
                Log.d("KeyMapper", "  Mapping entry: ${function.name}_keycode = $storedKey")
                if (storedKey == keyCode) {
                    found = true
                }
            }
        }
        return found
    }

    private fun handleCallCheckAction() {
        val prefs = getSharedPreferences("mappings", MODE_PRIVATE)
        val activePreset = prefs.getString("active_preset", "DEFAULT") ?: "DEFAULT"
        val function = DeliveryFunction.CALL_CHECK
        
        val x = prefs.getInt("${activePreset}_${function.name}_x", -1).toFloat()
        val y = prefs.getInt("${activePreset}_${function.name}_y", -1).toFloat()
        
        if (x != -1f && y != -1f) {
            val tapX = x + 50f
            val tapY = y + 90f
            Log.d("KeyMapper", "PERFORMING CALL_CHECK TAP at ($tapX, $tapY)")
            performTap(tapX, tapY)
        } else {
            Log.w("KeyMapper", "Coordinates NOT FOUND for CALL_CHECK in preset $activePreset")
        }
    }

    private fun handleAction(keyCode: Int, clickType: ClickType): Boolean {
        val prefs = getSharedPreferences("mappings", MODE_PRIVATE)
        val activePreset = prefs.getString("active_preset", "DEFAULT") ?: "DEFAULT"
        
        Log.d("KeyMapper", "handleAction: keyCode=$keyCode, clickType=$clickType, activePreset=$activePreset")
        
        // 매칭되는 첫 번째 기능만 찾아서 실행 (중복 동작 방지)
        val function = DeliveryFunction.entries.find { func ->
            val mappedKey = prefs.getInt("${func.name}_keycode", -1)
            val mappedClick = prefs.getString("${func.name}_clicktype", ClickType.SINGLE.name)
            mappedKey == keyCode && mappedClick == clickType.name
        }
        
        if (function != null) {
            val x = prefs.getInt("${activePreset}_${function.name}_x", -1).toFloat()
            val y = prefs.getInt("${activePreset}_${function.name}_y", -1).toFloat()
            
            if (x != -1f && y != -1f) {
                val tapX = x + 50f
                val tapY = y + 90f
                Log.d("KeyMapper", "PERFORMING ACTION: ${function.name} at ($tapX, $tapY)")
                
                when (function) {
                    DeliveryFunction.ZOOM_IN -> performSwipe(tapX, tapY, tapX + 200, tapY + 200)
                    DeliveryFunction.ZOOM_OUT -> performSwipe(tapX + 200, tapY + 200, tapX, tapY)
                    else -> performTap(tapX, tapY)
                }
                return true
            } else {
                Log.w("KeyMapper", "Coordinates NOT FOUND for ${function.name} in preset $activePreset")
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
