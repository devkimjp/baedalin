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
    private var doubleClickTimeout = 300L
    private val longPressTimeout = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressed = false
    private var mediaSession: android.media.session.MediaSession? = null
    
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

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "is_recording" || key == "is_mapping_enabled") {
            updateKeyFilterState()
        }
        if (key == "double_click_timeout") {
            doubleClickTimeout = prefs.getLong("double_click_timeout", 300L)
            Log.d("KeyMapper", "Updated doubleClickTimeout: $doubleClickTimeout")
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
        
        instance = this
        // GestureManager에 서비스 인스턴스 설정 (터치 실행을 위해 필수)
        gestureManager.setService(this)

        updateKeyFilterState()
        setupMediaSession()
        
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        doubleClickTimeout = prefs.getLong("double_click_timeout", 500L)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            
        CoroutineScope(Dispatchers.Main).launch {
            launch {
                FloatingWidgetService.isInterceptionActive.collect { active ->
                    // [CRITICAL] 배달 앱이 활성화된 상태에서만 세션 활성화
                    mediaSession?.isActive = active
                    Log.d("KeyMapper", "[SYSTEM] MediaSession isActive set to: $active")
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

    private fun setupMediaSession() {
        try {
            mediaSession?.release()
            mediaSession = android.media.session.MediaSession(this, "DalmalingMediaHijacker").apply {
                setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or 
                         android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                
                // [CRITICAL] 시스템을 속이기 위해 '재생 중' 상태를 강제로 보고하고 모든 미디어 버튼 가로채기
                val state = android.media.session.PlaybackState.Builder()
                    .setActions(android.media.session.PlaybackState.ACTION_PLAY or 
                               android.media.session.PlaybackState.ACTION_PAUSE or 
                               android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                               android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                               android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build()
                setPlaybackState(state)

                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                        val event = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            mediaButtonIntent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            mediaButtonIntent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT)
                        }
                        
                        if (event != null) {
                            Log.d("KeyMapper", "[MEDIA] Session capture: ${event.keyCode} (Action: ${event.action})")
                            // [CRITICAL] 더블 클릭 로직은 ACTION_UP에서 클릭 횟수를 계산하므로, 
                            // DOWN과 UP 이벤트를 모두 onKeyEvent로 전달해야 함
                            onKeyEvent(event)
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                
                val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value
                isActive = isInterceptionActive
                Log.i("KeyMapper", "[SYSTEM] MediaSession initial isActive: $isInterceptionActive")
            }
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to setup MediaSession", e)
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
            
            Log.d("KeyMapper", "updateKeyFilterState: isRecording=$isRecording, isMappingEnabled=$isMappingEnabled, isInterceptionActive=$isInterceptionActive, isDirectRecording=$isDirectRecording")

            if (shouldFilterKeys) {
                targetFlags = targetFlags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                Log.d("KeyMapper", "Key Filter: ACTIVE (INTERCEPTING ALL KEYS)")
                
                // 가로채기 활성 시 미디어 세션도 함께 활성화하여 우선권 확보 (원래 상태 복구)
                if (mediaSession?.isActive == false) {
                    mediaSession?.isActive = true
                    Log.d("KeyMapper", "[SYSTEM] MediaSession activated for priority")
                }
            } else {
                Log.d("KeyMapper", "Key Filter: WINDOW_ONLY (NOT INTERCEPTING)")
                if (mediaSession?.isActive == true) {
                    mediaSession?.isActive = false
                    Log.d("KeyMapper", "[SYSTEM] MediaSession deactivated")
                }
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
        mediaSession?.release()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {}
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        instance = null
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
        var instance: KeyMapperAccessibilityService? = null
            private set
        var currentPackageName: String = ""
            private set
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyAction = event.action
        val eventTime = event.eventTime
        
        // [DEBUG] 모든 키 입력 원천 데이터 로그 (DOWN=0, UP=1)
        Log.d("KeyMapper", ">>> RAW: code=$keyCode, action=$keyAction, time=$eventTime")

        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val targetDescriptor = prefs.getString("selected_device_descriptor", null) ?: "GLOBAL"
        
        // 1. 장치 필터링
        val device = InputDevice.getDevice(event.deviceId)
        if (device != null && targetDescriptor != "GLOBAL" && device.descriptor != targetDescriptor) {
            Log.v("KeyMapper", "Ignored: Device mismatch (${device.descriptor} != $targetDescriptor)")
            return false
        }
        val prefix = targetDescriptor

        // 2. 핵심 상태 확인
        val isRecording = KeyRecordingState.isRecording || prefs.getBoolean("is_recording", false)
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value
        val isMapped = isKeyMapped(keyCode, prefix)
        val directRecordingFunction = KeyRecordingState.recordingFunction
        
        Log.d("KeyMapper", "Status: isRecording=$isRecording, isMappingEnabled=$isMappingEnabled, isInterceptionActive=$isInterceptionActive, isMapped=$isMapped")

        // 3. 시스템 간섭 차단 및 선제적 처리 (ACTION_DOWN)
        val shouldIntercept = isRecording || directRecordingFunction != null || (isMappingEnabled && isInterceptionActive && isMapped)
        
        if (shouldIntercept && keyAction == KeyEvent.ACTION_DOWN) {
            // 녹화 중일 때는 반복 입력(Repeat)도 개별 클릭으로 인정하여 가로챔
            if (!isRecording && event.repeatCount > 0) {
                Log.d("KeyMapper", "Ignored: Repeat count > 0")
                return true
            }
            Log.i("KeyMapper", "[INTERCEPT] Strongly consuming DOWN: $keyCode (Repeat=${event.repeatCount})")
            
            // 더블 클릭 타이머 관리
            if (keyCode != lastKeyCode) {
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
                clickCount = 0
                lastKeyCode = keyCode
            } else {
                pendingClickRunnable?.let { 
                    Log.d("KeyMapper", "[DEBUG] Continued sequence, stopping timer")
                    handler.removeCallbacks(it) 
                }
            }
            
            // 레코딩 트리거 (Toolbar)
            if (directRecordingFunction != null) {
                saveDirectMapping(directRecordingFunction, keyCode)
                KeyRecordingState.recordingFunction = null
                playSuccessSound()
                updateKeyFilterState()
                Toast.makeText(this, "매핑 완료: $keyCode", Toast.LENGTH_SHORT).show()
            }
            
            // 레코딩 트리거 (Wizard)
            // [FIX] 패키지명 체크가 간혹 누락될 수 있으므로, 녹화 중일 때는 더 공격적으로 가로챔
            if (isRecording) {
                Log.d("KeyMapper", "[RECORDING] Capture in Wizard: $keyCode")
                sendBroadcast(Intent("ACTION_KEY_RECORDED").apply {
                    setPackage(packageName)
                    putExtra("keycode", keyCode)
                })
                // MainActivity를 다시 전면으로 불러와 이벤트 확실히 전달
                val intent = Intent(this, kr.disys.baedalin.MainActivity::class.java).apply {
                    action = "ACTION_KEY_RECORDED"
                    putExtra("keycode", keyCode)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
            
            return true
        }

        // 4. 매핑 실행 처리 (ACTION_UP)
        if (shouldIntercept && keyAction == KeyEvent.ACTION_UP) {
            // [CRITICAL] 녹화 중일 때는 터치 동작을 절대로 실행하지 않음
            if (isRecording || directRecordingFunction != null) {
                return true
            }

            val prefixFinal = targetDescriptor
            val isDoubleMapped = isKeyMappedToDouble(keyCode, prefixFinal)
            Log.d("KeyMapper", "[INTERCEPT] UP: $keyCode, doubleMapped=$isDoubleMapped")
            
            if (!isDoubleMapped) {
                handleAction(keyCode, ClickType.SINGLE, prefixFinal)
                clickCount = 0
                lastKeyCode = -1
                return true
            }

            clickCount++
            val timeout = prefs.getLong("double_click_timeout", 500L) 
            Log.d("KeyMapper", "[TIMER] Waiting $timeout ms for next click (Count=$clickCount)")
            pendingClickRunnable?.let { handler.removeCallbacks(it) }
            
            pendingClickRunnable = Runnable {
                val type = if (clickCount >= 2) ClickType.DOUBLE else ClickType.SINGLE
                Log.d("KeyMapper", "[TOUCH] Dispatching $type (Total=$clickCount)")
                handleAction(keyCode, type, prefixFinal)
                clickCount = 0
                lastKeyCode = -1
                pendingClickRunnable = null
            }.also { 
                handler.postDelayed(it, timeout)
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
        val isRecording = prefs.getBoolean("is_recording", false)
        
        if (!isMappingEnabled && !isRecording) {
            Log.d("KeyMapper", "[TOUCH] handleAction aborted: Both Mapping and Recording are OFF")
            return false
        }
        
        val activePreset = prefs.getString("active_preset", "DEFAULT") ?: "DEFAULT"
        Log.d("KeyMapper", "[TOUCH] handleAction: keyCode=$keyCode, clickType=$clickType, prefix=$prefix, activePreset=$activePreset")
        
        // 1. 구체적인 클릭 타입 매핑 확인 (SINGLE/DOUBLE)
        var function = DeliveryFunction.entries.find { func ->
            val mappedKey = prefs.getInt("${prefix}_${func.name}_${clickType.name}_keycode", -1)
            mappedKey == keyCode
        }
        
        // 2. 만약 위에서 못 찾았고 clickType이 SINGLE이라면, 구버전 매핑(타입 구분 없음) 확인
        if (function == null && clickType == ClickType.SINGLE) {
            function = DeliveryFunction.entries.find { func ->
                val mappedKey = prefs.getInt("${prefix}_${func.name}_keycode", -1)
                mappedKey == keyCode
            }
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
