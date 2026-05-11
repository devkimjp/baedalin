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
import kr.disys.baedalin.R
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

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class KeyMapperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyCode = -1
    private var clickCount = 0
    private val doubleClickTimeout = 300L
    private val longPressTimeout = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressed = false
    
    private lateinit var gestureManager: GestureManager

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
        gestureManager = GestureManager(this)
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
        super.onServiceConnected()
        Log.d("KeyMapper", "onServiceConnected: Service Started")
        
        // GestureManager에 서비스 인스턴스 설정 (터치 실행을 위해 필수)
        gestureManager.setService(this)

        updateKeyFilterState()
        
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
            
        CoroutineScope(Dispatchers.Main).launch {
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
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val isFullScreen = event.isFullScreen
            
            currentPackageName = packageName // 정적 변수 업데이트
            
            val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
            val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
            val isRunning = FloatingWidgetService.isRunning.value
            
            Log.d("KeyMapper", "Window changed: $packageName (Full:$isFullScreen), enabled=$isMappingEnabled, running=$isRunning")

            if (!isMappingEnabled) return

            val preset = Presets.getPresetFromPackage(packageName)
            
            if (preset != null) {
                Log.d("KeyMapper", "Delivery App Detected: $packageName -> Loading $preset")
                
                // 접근성 서비스에서 직접 active_preset 업데이트
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) {
                    putString("active_preset", preset)
                }

                val intent = Intent(this, FloatingWidgetService::class.java).apply {
                    action = FloatingWidgetService.ACTION_LOAD_PRESET
                    putExtra("preset_name", preset)
                }
                startService(intent)
            } else if (isRunning) {
                // 배달 앱이 아닌 경우 숨기기 여부 결정
                val isBaedalinApp = packageName == "kr.disys.baedalin"
                val isIgnorePackage = packageName == "com.android.systemui" || 
                                    packageName == "android" || 
                                    packageName == "com.samsung.android.sidegesturepad" ||
                                    packageName == "com.samsung.android.app.cocktailbarservice"
                                    
                if (isBaedalinApp) {
                    Log.d("KeyMapper", "Baedalin app detected. Keeping interception active for testing.")
                    // 메인 앱에서도 키 가로채기가 작동하도록 설정 활성화
                    val intent = Intent(this, FloatingWidgetService::class.java).apply {
                        action = "ACTION_SET_INTERCEPTION"
                        putExtra("active", true)
                    }
                    startService(intent)
                    return
                }

                if (isIgnorePackage || !isFullScreen) {
                    Log.d("KeyMapper", "Ignoring window change: $packageName (isIgnore=$isIgnorePackage, isFull=$isFullScreen)")
                    FloatingWidgetService.instance?.updateToolbarState()
                    return
                }
                
                Log.d("KeyMapper", "Non-delivery full-screen app detected ($packageName). Hiding presets.")
                val intent = Intent(this, FloatingWidgetService::class.java).apply {
                    action = FloatingWidgetService.ACTION_HIDE_PRESETS
                }
                startService(intent)
            }
            
            FloatingWidgetService.instance?.updateToolbarState()
        }
    }

    private fun captureUISnapshot() {
        val path = gestureManager.captureUISnapshot()
        if (path != null) {
            playSuccessSound()
            val intent = Intent(this, FloatingWidgetService::class.java).apply {
                action = "ACTION_SNAPSHOT_COMPLETE"
                putExtra("success", true)
                putExtra("path", path)
            }
            startService(intent)
        }
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
                val funcName = intent.getStringExtra("function_name")
                kr.disys.baedalin.KeyRecordingState.recordingFunction = funcName
                Log.d("KeyMapper", "!!! STARTED DIRECT RECORDING for: $funcName !!!")
                Toast.makeText(this, "매핑 대기 중: ${funcName ?: "알 수 없음"}", Toast.LENGTH_SHORT).show()
                updateKeyFilterState()
            }
            "ACTION_CANCEL_DIRECT_RECORDING" -> {
                Log.d("KeyMapper", "!!! CANCELLED DIRECT RECORDING !!!")
                kr.disys.baedalin.KeyRecordingState.recordingFunction = null
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

        // 모든 키 이벤트(UP/DOWN 포함)를 디버깅을 위해 로그 기록 (scanCode 추가)
        Log.d("KeyMapper", "[DEBUG] onKeyEvent: code=${event.keyCode}, scan=${event.scanCode}, action=${event.action}, deviceId=${event.deviceId}")

        // 1. 레코딩 모드 처리 (최우선)
        val directRecordingFunction = kr.disys.baedalin.KeyRecordingState.recordingFunction
        if (directRecordingFunction != null) {
            Log.d("KeyMapper", "[RECORDING] Intercepting for $directRecordingFunction: code=${event.keyCode}")
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val keyCode = event.keyCode
                val funcName = directRecordingFunction
                kr.disys.baedalin.KeyRecordingState.recordingFunction = null // 매핑 완료 후 즉시 해제
                
                Log.d("KeyMapper", "[RECORDING] Mapping captured: $funcName -> $keyCode")
                
                // 기능명으로 라벨 찾기 (사전 정의된 기능 또는 커스텀 위젯)
                val function = DeliveryFunction.entries.find { it.name == funcName }
                val label = function?.let { getString(it.labelResId) } ?: "Custom $funcName"
                
                saveDirectMapping(funcName, keyCode)
                playSuccessSound()
                updateKeyFilterState() // 필터 상태 즉시 업데이트
                
                val keyName = KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
                Toast.makeText(this, "[$label] ${getString(R.string.wizard_complete_title)}: $keyName", Toast.LENGTH_SHORT).show()
                
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
                
                // 1. 브로드캐스트 전송 (가장 빠름)
                sendBroadcast(Intent("ACTION_KEY_RECORDED").apply {
                    setPackage(packageName)
                    putExtra("keycode", event.keyCode)
                })

                // 2. 혹시 앱이 백그라운드라면 전면으로 호출
                val intent = Intent(this, kr.disys.baedalin.MainActivity::class.java).apply {
                    action = "ACTION_KEY_RECORDED"
                    putExtra("keycode", event.keyCode)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
            return true 
        }
        
        // 2. 서비스 작동 스위치 확인
        if (!isMappingEnabled) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d("KeyMapper", "[DEBUG] BYPASS: isMappingEnabled is FALSE. (Check '작동' switch)")
            }
            return false
        }

        // 3. 위젯 활성화 상태 확인 (배달 앱 감지 여부)
        if (!isInterceptionActive) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d("KeyMapper", "[DEBUG] BYPASS: isInterceptionActive is FALSE. (Is a delivery app active?)")
            }
            return false
        }

        val targetDescriptor = prefs.getString("selected_device_descriptor", null)
        if (targetDescriptor == null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d("KeyMapper", "[DEBUG] BYPASS: No target device selected in settings.")
            }
            return false
        }
        
        val device = InputDevice.getDevice(event.deviceId)
        if (device == null || device.descriptor != targetDescriptor) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d("KeyMapper", "[DEBUG] BYPASS: Device mismatch. Target: $targetDescriptor, Current: ${device?.descriptor ?: "null"}")
            }
            return false
        }
        
        val keyCode = event.keyCode
        val action = event.action
        val prefix = targetDescriptor ?: "GLOBAL"
        val isMapped = isKeyMapped(keyCode, prefix)
        
        if (!isMapped) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d("KeyMapper", "[DEBUG] BYPASS: Key $keyCode is NOT mapped for $prefix")
            }
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
            val isDoubleMapped = isKeyMappedToDouble(keyCode, prefix)
            Log.d("KeyMapper", "[DEBUG] ACTION_UP: keyCode=$keyCode, isDoubleMapped=$isDoubleMapped, currentClickCount=$clickCount")
            
            if (!isDoubleMapped) {
                Log.d("KeyMapper", "[TOUCH] Immediate Action (Single only): keyCode=$keyCode, device=$prefix")
                handleAction(keyCode, ClickType.SINGLE, prefix)
                clickCount = 0
                return true
            }

            clickCount++
            Log.d("KeyMapper", "[DEBUG] clickCount incremented to $clickCount. Waiting for potential double click...")
            
            pendingClickRunnable?.let { 
                Log.d("KeyMapper", "[DEBUG] Removing existing pendingClickRunnable")
                handler.removeCallbacks(it) 
            }
            
            pendingClickRunnable = Runnable {
                val type = if (clickCount >= 2) ClickType.DOUBLE else ClickType.SINGLE
                Log.d("KeyMapper", "[TOUCH] Executing Delayed Action: keyCode=$keyCode, type=$type, count=$clickCount")
                
                val handled = handleAction(keyCode, type, prefix)
                if (!handled) {
                    Log.e("KeyMapper", "[TOUCH] handleAction Failed: No mapping found for $type on key $keyCode")
                }
                clickCount = 0
                pendingClickRunnable = null
            }.also { 
                val posted = handler.postDelayed(it, doubleClickTimeout)
                Log.d("KeyMapper", "[DEBUG] Posted delayed action (timeout=$doubleClickTimeout), success=$posted")
            }
            
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun isKeyMappedToDouble(keyCode: Int, prefix: String): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        return DeliveryFunction.entries.any { func ->
            val mappedKey = prefs.getInt("${prefix}_${func.name}_DOUBLE_keycode", -1)
            mappedKey == keyCode
        }
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
        return DeliveryFunction.entries.any { function ->
            val singleKey = prefs.getInt("${prefix}_${function.name}_SINGLE_keycode", -1)
            val doubleKey = prefs.getInt("${prefix}_${function.name}_DOUBLE_keycode", -1)
            singleKey == keyCode || doubleKey == keyCode
        }
    }

    private fun handleAction(keyCode: Int, clickType: ClickType, prefix: String): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        if (!isMappingEnabled) {
            Log.d("KeyMapper", "[TOUCH] handleAction aborted: isMappingEnabled is FALSE")
            return false
        }
        
        val activePreset = prefs.getString("active_preset", "DEFAULT") ?: "DEFAULT"
        
        Log.d("KeyMapper", "[TOUCH] handleAction: keyCode=$keyCode, clickType=$clickType, prefix=$prefix, activePreset=$activePreset")
        
        val function = DeliveryFunction.entries.find { func ->
            val mappedKey = prefs.getInt("${prefix}_${func.name}_${clickType.name}_keycode", -1)
            mappedKey == keyCode
        }
        
        if (function != null) {
            val displayMetrics = resources.displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val centerY = displayMetrics.heightPixels / 2f

            when (function) {
                DeliveryFunction.ZOOM_OUT -> {
                    Log.d("KeyMapper", "[TOUCH] PERFORMING ZOOM_OUT")
                    gestureManager.performZoom(centerX, centerY, false)
                    return true
                }

                DeliveryFunction.ZOOM_IN -> {
                    Log.d("KeyMapper", "[TOUCH] PERFORMING ZOOM_IN")
                    gestureManager.performZoom(centerX, centerY, true)
                    return true
                }
                else -> {
                    val widgetPrefs = getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
                    var x = widgetPrefs.getInt("${activePreset}_${function.name}_x", -1).toFloat()
                    var y = widgetPrefs.getInt("${activePreset}_${function.name}_y", -1).toFloat()
                    
                    if (x == -1f || y == -1f) {
                        Log.d("KeyMapper", "[TOUCH] Saved position not found for ${function.name} in $activePreset. Using default preset position.")
                        val presetList = when(activePreset) {
                            "BAEMIN" -> Presets.BAEMIN
                            "COUPANG" -> Presets.COUPANG
                            "YOGIYO" -> Presets.YOGIYO
                            else -> Presets.BAEMIN
                        }
                        val info = presetList.find { it.function.name == function.name }
                        if (info != null) {
                            x = info.x.toFloat()
                            y = info.y.toFloat()
                        }
                    }
                    
                    if (x != -1f && y != -1f) {
                        // 위젯 컨테이너 오프셋 보정:
                        // x는 중심(50), y는 인디케이터(20) + 툴팁(약 30) + 아이콘 중심(50) = 약 100
                        val tapX = x + 50f
                        val tapY = y + 100f
                        Log.d("KeyMapper", "[TOUCH] PERFORMING TAP: ${function.name} at ($tapX, $tapY) for preset $activePreset")
                        gestureManager.performTap(tapX, tapY)
                        return true
                    } else {
                        Log.e("KeyMapper", "[TOUCH] FAILED: No coordinates found for ${function.name}")
                    }
                }
            }
        } else {
            Log.d("KeyMapper", "[TOUCH] No function mapped to keyCode=$keyCode with clickType=$clickType")
        }
        return false
    }

}
