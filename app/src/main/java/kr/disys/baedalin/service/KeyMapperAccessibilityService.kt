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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import android.content.SharedPreferences

class KeyMapperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyCode = -1
    private var clickCount = 0
    private val doubleClickTimeout = 300L
    private val longPressTimeout = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressed = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "is_recording" || key == "is_mapping_enabled") {
            updateKeyFilterState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("KeyMapper", "Service onCreate - Process: ${android.os.Process.myPid()}")
    }

    override fun onServiceConnected() {
        Log.d("KeyMapper", "KeyMapperAccessibilityService connected")
        updateKeyFilterState()
        
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
            
        serviceScope.launch {
            FloatingWidgetService.isInterceptionActive.collect {
                updateKeyFilterState()
            }
        }
    }

    private fun updateKeyFilterState() {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        val isRecording = prefs.getBoolean("is_recording", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value
        
        val info = serviceInfo ?: AccessibilityServiceInfo()
        
        if (isMappingEnabled || isRecording) {
            // 중요: 윈도우 변화 감지는 서비스가 활성화된 동안 항상 켜두어야 배달 앱 진입을 감지할 수 있음
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            
            // 키 가로채기 플래그는 실제 위젯이 떠 있거나 레코딩 중일 때만 활성화
            val shouldFilterKeys = isRecording || (isMappingEnabled && isInterceptionActive)
            var targetFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            
            if (shouldFilterKeys) {
                targetFlags = targetFlags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                Log.d("KeyMapper", "Key Filter: ACTIVE (Window Detection + Key Filtering)")
            } else {
                // 키 필터링만 끄고 윈도우 감지는 유지 (Bypass 상태)
                Log.d("KeyMapper", "Key Filter: WINDOW_ONLY (Window Detection active, Keys bypassed)")
            }
            
            info.flags = targetFlags
        } else {
            // 완전 중지 상태 (STEALTH)
            info.eventTypes = 0
            info.feedbackType = 0
            info.notificationTimeout = 0
            info.flags = 0
            Log.d("KeyMapper", "Key Filter: STEALTH (Fully Disabled)")
        }
        
        serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            currentPackageName = packageName // 정적 변수 업데이트
            
            val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
            val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
            val isRunning = FloatingWidgetService.isRunning.value
            
            Log.d("KeyMapper", "Window changed: $packageName, enabled=$isMappingEnabled, running=$isRunning")

            if (!isMappingEnabled) return

            val preset = Presets.getPresetFromPackage(packageName)
            
            if (isRunning) {
                if (preset != null) {
                    Log.d("KeyMapper", "Delivery App Detected: $packageName -> Loading $preset")
                    val intent = Intent(this, FloatingWidgetService::class.java).apply {
                        action = FloatingWidgetService.ACTION_LOAD_PRESET
                        putExtra("preset_name", preset)
                    }
                    startService(intent)
                } else {
                    // 배달 앱이 아닌 경우 숨기기
                    if (packageName == "com.android.systemui" || 
                        packageName == "android" || 
                        packageName == "kr.disys.baedalin") {
                        // 시스템 UI나 본인 앱에서는 아이콘 상태만 최신화
                        FloatingWidgetService.instance?.updateToolbarState()
                        return
                    }
                    
                    val intent = Intent(this, FloatingWidgetService::class.java).apply {
                        action = FloatingWidgetService.ACTION_HIDE_PRESETS
                    }
                    startService(intent)
                }
                
                // 어떤 경우든 앱이 바뀌면 아이콘 상태 갱신
                FloatingWidgetService.instance?.updateToolbarState()
            }
        }
    }

    companion object {
        var currentPackageName: String = ""
            private set
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        val isRecording = KeyRecordingState.isRecording || prefs.getBoolean("is_recording", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value

        // 모든 키 이벤트 흐름 추적을 위해 최상단 로그 추가
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("KeyMapper", "[DEBUG] onKeyEvent IN: code=${event.keyCode}, enabled=$isMappingEnabled, active=$isInterceptionActive")
        }

        // 1. 레코딩 모드 처리 (최우선)
        if (isRecording) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.d("KeyMapper", "[DEBUG] RECORDING MODE: KeyCode=${event.keyCode} captured.")
                val intent = Intent(this, kr.disys.baedalin.MainActivity::class.java).apply {
                    action = "ACTION_KEY_RECORDED"
                    putExtra("keycode", event.keyCode)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
            return true 
        }
        
        // 2. 서비스 중지 상태면 즉시 바이패스
        if (!isMappingEnabled) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d("KeyMapper", "[DEBUG] BYPASS: isMappingEnabled is FALSE. Letting system handle code=${event.keyCode}")
            }
            return false
        }

        // 3. 위젯이 숨겨진 상태(비활성 앱)면 바이패스
        if (!isInterceptionActive) {
            Log.d("KeyMapper", "[DEBUG] BYPASS: isInterceptionActive is FALSE. keyCode=${event.keyCode}")
            return false
        }

        Log.d("KeyMapper", "[DEBUG] onKeyEvent: keyCode=${event.keyCode}, action=${event.action}")

        val targetDescriptor = prefs.getString("selected_device_descriptor", null)
        if (targetDescriptor == null) {
            Log.d("KeyMapper", "[DEBUG] BYPASS: No target device selected.")
            return false
        }
        
        val device = InputDevice.getDevice(event.deviceId)
        if (device == null || device.descriptor != targetDescriptor) {
            Log.d("KeyMapper", "[DEBUG] BYPASS: Device mismatch or null. target=$targetDescriptor")
            return false
        }
        
        val keyCode = event.keyCode
        val action = event.action
        val prefix = targetDescriptor ?: "GLOBAL"
        val isMapped = isKeyMapped(keyCode, prefix)
        
        if (!isMapped) {
            Log.d("KeyMapper", "[DEBUG] BYPASS: Key $keyCode is NOT mapped for $prefix")
            return false
        }

        Log.d("KeyMapper", "[DEBUG] INTERCEPT: Key $keyCode is MAPPED. action=${event.action}")
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
                
                // 이동 모드(Unlock) 중 매핑된 키 입력 시 자동 잠금 및 터치 실행
                if (FloatingWidgetService.isMoveMode.value) {
                    FloatingWidgetService.forceLockMode()
                }
                
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
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        if (!isMappingEnabled) {
            Log.d("KeyMapper", "[DEBUG] handleAction aborted: isMappingEnabled is FALSE")
            return false
        }
        
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
