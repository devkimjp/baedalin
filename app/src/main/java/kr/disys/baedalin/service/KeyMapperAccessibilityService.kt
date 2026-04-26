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
import android.widget.Toast
import android.content.SharedPreferences
import androidx.core.content.edit
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "ACTION_START_DIRECT_RECORDING" -> {
                    kr.disys.baedalin.KeyRecordingState.recordingFunction = intent.getStringExtra("function_name")
                    Log.d("KeyMapper", "Started direct recording for: ${kr.disys.baedalin.KeyRecordingState.recordingFunction}")
                    updateKeyFilterState()
                }
                "ACTION_CANCEL_DIRECT_RECORDING" -> {
                    if (kr.disys.baedalin.KeyRecordingState.recordingFunction != null) {
                        Log.d("KeyMapper", "Cancelled direct recording for: ${kr.disys.baedalin.KeyRecordingState.recordingFunction}")
                        kr.disys.baedalin.KeyRecordingState.recordingFunction = null
                    }
                }
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "is_recording" || key == "is_mapping_enabled") {
            updateKeyFilterState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("KeyMapper", "Service onCreate - Process: ${android.os.Process.myPid()}")
        val filter = IntentFilter().apply {
            addAction("ACTION_START_DIRECT_RECORDING")
            addAction("ACTION_CANCEL_DIRECT_RECORDING")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onServiceConnected() {
        Log.d("KeyMapper", "KeyMapperAccessibilityService connected")
        updateKeyFilterState()
        
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
            
        serviceScope.launch {
            launch {
                FloatingWidgetService.isInterceptionActive.collect {
                    updateKeyFilterState()
                }
            }
            launch {
                FloatingWidgetService.isMoveMode.collect {
                    updateKeyFilterState()
                }
            }
        }
    }

    private fun updateKeyFilterState() {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        val isRecording = prefs.getBoolean("is_recording", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value
        
        val info = serviceInfo ?: AccessibilityServiceInfo()
        
        if (isMappingEnabled || isRecording || kr.disys.baedalin.KeyRecordingState.recordingFunction != null) {
            // 중요: 윈도우 변화 감지는 서비스가 활성화된 동안 항상 켜두어야 배달 앱 진입을 감지할 수 있음
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            
            // 키 가로채기 플래그는 실제 위젯이 떠 있거나 레코딩 중, 또는 언락 모드일 때 활성화
            val isDirectRecording = kr.disys.baedalin.KeyRecordingState.recordingFunction != null
            val isMoveMode = FloatingWidgetService.isMoveMode.value
            val shouldFilterKeys = isRecording || isDirectRecording || isMoveMode || (isMappingEnabled && isInterceptionActive)
            var targetFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            
            if (shouldFilterKeys) {
                targetFlags = targetFlags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                Log.d("KeyMapper", "Key Filter: ACTIVE (Recording=$isRecording, Direct=$isDirectRecording, Move=$isMoveMode, Mapping=$isMappingEnabled, Active=$isInterceptionActive)")
            } else {
                Log.d("KeyMapper", "Key Filter: WINDOW_ONLY")
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

    private fun captureUISnapshot() {
        val root = rootInActiveWindow ?: return
        try {
            val logDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = logDateFormat.format(Date())
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "ui_snapshot_$timestamp.json")
            
            val json = JSONObject()
            json.put("timestamp", System.currentTimeMillis())
            json.put("packageName", root.packageName)
            json.put("nodes", dumpNodeToJson(root))
            
            FileWriter(file).use { it.write(json.toString(2)) }
            
            playSuccessSound()
            
            val intent = Intent(this, FloatingWidgetService::class.java).apply {
                action = "ACTION_SNAPSHOT_COMPLETE"
                putExtra("success", true)
                putExtra("path", file.absolutePath)
            }
            startService(intent)
            
        } catch (e: Exception) {
            Log.e("KeyMapper", "Snapshot failed", e)
        } finally {
            root.recycle()
        }
    }

    private fun dumpNodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        json.put("class", node.className?.toString()?.split(".")?.last() ?: "View")
        json.put("text", node.text?.toString() ?: "")
        json.put("desc", node.contentDescription?.toString() ?: "")
        json.put("id", node.viewIdResourceName ?: "")
        json.put("clickable", node.isClickable)
        json.put("bounds", JSONObject().apply {
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
        })

        if (node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    children.put(dumpNodeToJson(child))
                    child.recycle()
                }
            }
            json.put("children", children)
        }
        return json
    }

    private fun playSuccessSound() {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneG.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            handler.postDelayed({ toneG.release() }, 2000)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to play sound", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            "ACTION_UI_SNAPSHOT" -> captureUISnapshot()
            "ACTION_START_DIRECT_RECORDING" -> {
                kr.disys.baedalin.KeyRecordingState.recordingFunction = intent.getStringExtra("function_name")
                Log.d("KeyMapper", "!!! STARTED DIRECT RECORDING via START_SERVICE for: ${kr.disys.baedalin.KeyRecordingState.recordingFunction} !!!")
                updateKeyFilterState()
            }
        }
        return super.onStartCommand(intent, flags, startId)
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
        val directRecordingFunction = kr.disys.baedalin.KeyRecordingState.recordingFunction
        if (directRecordingFunction != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val keyCode = event.keyCode
                val funcName = directRecordingFunction
                kr.disys.baedalin.KeyRecordingState.recordingFunction = null // 매핑 완료 후 해제
                
                // 기능명으로 라벨 찾기 (사전 정의된 기능 또는 커스텀 위젯)
                val function = DeliveryFunction.entries.find { it.name == funcName }
                val label = function?.label ?: "커스텀 $funcName"
                
                saveDirectMapping(funcName, keyCode)
                playSuccessSound()
                
                val keyName = KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
                
                // FloatingWidgetService에 UI 갱신 및 메시지 표시 알림
                val updateIntent = Intent(this, FloatingWidgetService::class.java).apply {
                    action = FloatingWidgetService.ACTION_UPDATE_KEY
                    putExtra("function_name", funcName)
                    putExtra("keycode", keyCode)
                    putExtra("key_name", keyName)
                    putExtra("label", label)
                }
                startService(updateIntent)
            }
            return true
        }

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

    private fun saveDirectMapping(functionName: String, keyCode: Int) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val targetDescriptor = prefs.getString("selected_device_descriptor", "GLOBAL") ?: "GLOBAL"
        
        prefs.edit(commit = true) {
            putInt("${targetDescriptor}_${functionName}_keycode", keyCode)
            putString("${targetDescriptor}_${functionName}_clicktype", ClickType.SINGLE.name)
        }
        Log.d("KeyMapper", "Direct mapping saved: $functionName -> $keyCode")
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
