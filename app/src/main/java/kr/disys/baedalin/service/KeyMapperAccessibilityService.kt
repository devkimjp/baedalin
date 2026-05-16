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
import android.view.WindowManager
import kr.disys.baedalin.domain.repository.MappingRepository
import kr.disys.baedalin.ui.overlay.OverlayManager
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
    private var isSwitchingApp = false
    private var lastSwitchedPackage: String? = null
    private val longPressTimeout = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressed = false
    private var mediaSession: android.media.session.MediaSession? = null
    
    @Inject lateinit var gestureManager: GestureManager
    @Inject lateinit var appSwitcher: AppSwitcher
    @Inject lateinit var keyEventHandler: KeyEventHandler
    @Inject lateinit var mappingRepository: MappingRepository
    @Inject lateinit var overlayManager: OverlayManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isMappingEnabledLocal = false
    private var activePresetLocal = "BAEMIN"

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
                    updateKeyFilterState()
                }
                "ACTION_REFRESH_FILTER" -> {
                    Log.d("KeyMapper", "Force refreshing key filter via broadcast")
                    updateKeyFilterState()
                }
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "is_recording" || key == "is_mapping_enabled") {
            updateKeyFilterState()
        }
        if (key == "double_click_timeout") {
            val measured = prefs.getLong("double_click_timeout", 300L)
            doubleClickTimeout = (measured * 1.1).toLong() // 10% 여유 추가
            Log.d("KeyMapper", "Updated doubleClickTimeout (with 10% margin): $doubleClickTimeout ms")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("KeyMapper", "Service onCreate - Process: ${android.os.Process.myPid()}")
        val filter = IntentFilter().apply {
            addAction("ACTION_START_DIRECT_RECORDING")
            addAction("ACTION_CANCEL_DIRECT_RECORDING")
            addAction("ACTION_REFRESH_FILTER")
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
        val measured = prefs.getLong("double_click_timeout", 500L)
        doubleClickTimeout = (measured * 1.1).toLong() // 10% 여유 추가
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            
        serviceScope.launch {
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
            launch {
                FloatingWidgetService.isMappingEnabled.collect { enabled ->
                    isMappingEnabledLocal = enabled
                    Log.d("KeyMapper", "[STATE] isMappingEnabled updated (from service): $enabled")
                    updateKeyFilterState()
                }
            }
            launch {
                mappingRepository.getActivePreset().collect { preset ->
                    activePresetLocal = preset
                    Log.d("KeyMapper", "[STATE] activePreset updated: $preset")
                }
            }
        }
    }

    private fun setupMediaSession() {
        try {
            mediaSession?.release()
            mediaSession = android.media.session.MediaSession(this, "DalmalingMediaHijacker").apply {
                // [CRITICAL] 시스템을 속이기 위해 '재생 중' 상태를 강제로 보고하고 모든 미디어 버튼 가로채기
                reportPlayingState()

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

    private fun reportPlayingState() {
        try {
            val state = android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY or 
                           android.media.session.PlaybackState.ACTION_PAUSE or 
                           android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                           android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                           android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build()
            mediaSession?.setPlaybackState(state)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to report playing state", e)
        }
    }


    private fun updateKeyFilterState() {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val isMappingEnabled = isMappingEnabledLocal
        val isRecording = prefs.getBoolean("is_recording", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value
        
        // [버그 수정] info 객체 하나만 사용하여 일관성 확보
        val info = serviceInfo ?: AccessibilityServiceInfo()
        
        // [버그 수정] isInterceptionActive(툴바 활성)도 조건에 추가
        // 기존: isMappingEnabled=false 상태에서 툴바가 떠있어도 STEALTH로 빠져 키 차단
        if (isMappingEnabled || isRecording || isInterceptionActive ||
            kr.disys.baedalin.KeyRecordingState.recordingFunction != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            
            val isDirectRecording = kr.disys.baedalin.KeyRecordingState.recordingFunction != null
            val isMoveMode = FloatingWidgetService.isMoveMode.value
            val shouldFilterKeys = isRecording || isDirectRecording || isMoveMode || isInterceptionActive
            var targetFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            
            if (shouldFilterKeys) {
                targetFlags = targetFlags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                Log.d("KeyMapper", "Key Filter: ACTIVE (INTERCEPTING ALL KEYS)")
            }

            if (isInterceptionActive) {
                if (mediaSession == null || mediaSession?.isActive == false) {
                    setupMediaSession()
                    mediaSession?.isActive = true
                    Log.d("KeyMapper", "[SYSTEM] MediaSession RE-INITIALIZED for priority")
                }
                reportPlayingState()
            } else if (shouldFilterKeys) {
                if (mediaSession?.isActive == false) {
                    mediaSession?.isActive = true
                    Log.d("KeyMapper", "[SYSTEM] MediaSession activated")
                }
            } else {
                Log.d("KeyMapper", "Key Filter: WINDOW_ONLY (NOT INTERCEPTING)")
                mediaSession?.isActive = false
            }
            
            // info.flags에 직접 targetFlags를 설정 → setServiceInfo(info) 시 올바르게 반영됨
            info.flags = targetFlags
            Log.d("KeyMapper", "[SYSTEM] flags set: $targetFlags (isRecording=$isRecording, isMappingEnabled=$isMappingEnabled)")
        } else {
            info.eventTypes = 0
            info.feedbackType = 0
            info.notificationTimeout = 0
            info.flags = 0
            Log.d("KeyMapper", "Key Filter: STEALTH (Fully Disabled)")
        }
        
        // 단 한 번의 setServiceInfo 호출로 적용 (중복 호출 제거)
        setServiceInfo(info)
        Log.d("KeyMapper", "[SYSTEM] setServiceInfo applied: flags=${info.flags}, eventTypes=${info.eventTypes}")
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {}
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        serviceScope.cancel()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // [개선] 앱 전환 직후 2초 동안은 이전 앱의 이벤트를 무시하여 위젯 깜빡임 방지
            if (appSwitcher.isSwitching() && packageName == appSwitcher.getLastSwitchedPackage()) {
                Log.d("KeyMapper", "Ignoring window change for old app during transition: $packageName")
                return
            }
            
            val isFullScreen = event.isFullScreen
            
            currentPackageName = packageName 
            
            val isRunning = FloatingWidgetService.isRunning.value
            
            Log.d("KeyMapper", "Window changed: $packageName (Full:$isFullScreen), enabled=$isMappingEnabledLocal, running=$isRunning")

            if (!isMappingEnabledLocal) return

            val preset = Presets.getPresetFromPackage(packageName)
            
            if (preset != null) {
                Log.d("KeyMapper", "Delivery App Detected: $packageName -> Loading $preset")
                
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) {
                    putString("active_preset", preset)
                }

                val intent = Intent(this, FloatingWidgetService::class.java).apply {
                    action = FloatingWidgetService.ACTION_LOAD_PRESET
                    putExtra("preset_name", preset)
                }
                startService(intent)
            } else if (isRunning) {
                val isBaedalinApp = packageName == "kr.disys.baedalin"
                val isIgnorePackage = packageName == "com.android.systemui" || 
                                    packageName == "android" || 
                                    packageName == "com.samsung.android.sidegesturepad" ||
                                    packageName == "com.samsung.android.app.cocktailbarservice"
                                    
                if (isBaedalinApp) {
                    Log.d("KeyMapper", "Baedalin app detected. Keeping interception active for testing.")
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
        
        // 1. 모든 RAW 키 입력 로깅 (진단을 위해 INFO 레벨로 출력)
        Log.i("KeyMapper", ">>> RAW EVENT: code=$keyCode, action=$keyAction, time=$eventTime")
        
        // 미디어 세션 우선권 유지를 위해 주기적으로 재생 상태 보고
        if (keyAction == KeyEvent.ACTION_DOWN) reportPlayingState()

        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val targetDescriptor = prefs.getString("selected_device_descriptor", null) ?: "GLOBAL"
        
        // 1. 장치 필터링
        val device = InputDevice.getDevice(event.deviceId)
        if (device != null && targetDescriptor != "GLOBAL" && device.descriptor != targetDescriptor) {
            Log.v("KeyMapper", "Ignored: Device mismatch (${device.descriptor} != $targetDescriptor)")
            return false
        }
        
        // 2. 핵심 상태 확인
        val isRecording = KeyRecordingState.isRecording || prefs.getBoolean("is_recording", false)
        val isMappingEnabled = isMappingEnabledLocal
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value
        val directRecordingFunction = KeyRecordingState.recordingFunction
        
        // [강력 조치] 기기 전용 매핑과 GLOBAL 매핑을 모두 확인하여 인식률 극대화 (Dual Lookup)
        val prefix = targetDescriptor
        val isMapped = isKeyMapped(keyCode, prefix) || isKeyMapped(keyCode, "GLOBAL")
        
        Log.d("KeyMapper", "Status: isRecording=$isRecording, isMappingEnabled=$isMappingEnabled, isInterceptionActive=$isInterceptionActive, isMapped=$isMapped (Prefix: $prefix)")

        // 3. 시스템 가로채기 판단
        val shouldIntercept = isRecording || directRecordingFunction != null || 
                             ((isMappingEnabledLocal || isInterceptionActive) && isInterceptionActive && isMapped)
        
        if (shouldIntercept && keyAction == KeyEvent.ACTION_DOWN) {
            if (!isRecording && event.repeatCount > 0) {
                Log.d("KeyMapper", "Ignored: Repeat count > 0")
                return true
            }
            Log.i("KeyMapper", "[INTERCEPT] Strongly consuming DOWN: $keyCode (Repeat=${event.repeatCount})")
            
            if (keyCode != lastKeyCode) {
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
                clickCount = 0
                lastKeyCode = keyCode
            } else {
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
            }
            
            if (directRecordingFunction != null) {
                saveDirectMapping(directRecordingFunction, keyCode)
                KeyRecordingState.recordingFunction = null
                playSuccessSound()
                updateKeyFilterState()
                Toast.makeText(this, "매핑 완료: $keyCode", Toast.LENGTH_SHORT).show()
            }
            
            if (isRecording) {
                Log.d("KeyMapper", "[RECORDING] Capture in Wizard: $keyCode")
                // [버그 수정] startActivity 제거: FLAG_REORDER_TO_FRONT가 ModalBottomSheet를 닫아버림
                // 브로드캐스트만으로 MainActivity의 keyReceiver가 처리하므로 충분
                sendBroadcast(Intent("ACTION_KEY_RECORDED").apply {
                    setPackage(packageName)
                    putExtra("keycode", keyCode)
                })
            }
            
            return true
        }

        if (shouldIntercept && keyAction == KeyEvent.ACTION_UP) {
            if (isRecording || directRecordingFunction != null) {
                return true
            }

            // UP 이벤트에서도 기기 전용 매핑과 GLOBAL 매핑을 모두 고려
            val isDoubleMapped = isKeyMappedToDouble(keyCode, prefix) || isKeyMappedToDouble(keyCode, "GLOBAL")
            Log.d("KeyMapper", "[INTERCEPT] UP: $keyCode, doubleMapped=$isDoubleMapped")
            
            if (!isDoubleMapped) {
                // 더블 클릭 매핑이 없는 경우 즉시 실행 (이때도 우선순위 기기 -> GLOBAL 순으로 확인)
                val usedPrefix = if (isKeyMapped(keyCode, prefix)) prefix else "GLOBAL"
                handleAction(keyCode, ClickType.SINGLE, usedPrefix)
                clickCount = 0
                lastKeyCode = -1
                return true
            }

            clickCount++
            // [개선] 클래스 필드에 저장된 10% 가산된 타임아웃 사용
            pendingClickRunnable?.let { handler.removeCallbacks(it) }
            
            pendingClickRunnable = Runnable {
                val type = if (clickCount >= 2) ClickType.DOUBLE else ClickType.SINGLE
                val usedPrefix = if (isKeyMapped(keyCode, prefix)) prefix else "GLOBAL"
                Log.d("KeyMapper", "[TOUCH] Dispatching $type (Total=$clickCount, Timeout=${doubleClickTimeout}ms) via $usedPrefix")
                handleAction(keyCode, type, usedPrefix)
                clickCount = 0
                lastKeyCode = -1
                pendingClickRunnable = null
            }.also { 
                handler.postDelayed(it, doubleClickTimeout)
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
        val isMappingEnabled = isMappingEnabledLocal
        val isRecording = prefs.getBoolean("is_recording", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value

        if (!isMappingEnabled && !isRecording && !isInterceptionActive) {
            Log.d("KeyMapper", "[TOUCH] handleAction aborted: All triggers are OFF")
            return false
        }
        
        val activePreset = activePresetLocal
        Log.d("KeyMapper", "[TOUCH] handleAction: keyCode=$keyCode, clickType=$clickType, prefix=$prefix, activePreset=$activePreset")
        
        var function = DeliveryFunction.entries.find { func ->
            val mappedKey = prefs.getInt("${prefix}_${func.name}_${clickType.name}_keycode", -1)
            mappedKey == keyCode
        }
        
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
                    gestureManager.performZoom(centerX, centerY, false)
                    return true
                }
                DeliveryFunction.ZOOM_IN -> {
                    gestureManager.performZoom(centerX, centerY, true)
                    return true
                }
                DeliveryFunction.SWITCH_APP -> {
                    appSwitcher.switchBetweenDeliveryApps(activePresetLocal)
                    return true
                }
                else -> {
                    // [개선] 위젯이 화면에 표시 중이라면 OverlayManager에서 실시간 좌표를 가져옴 (이동된 좌표 즉시 반영)
                    val overlayView = overlayManager.getOverlayView(function.name)
                    if (overlayView != null) {
                        val params = overlayView.layoutParams as WindowManager.LayoutParams
                        // 위젯 container 내부 구조(핸들20 + 툴팁 + 아이콘50)를 고려한 보정값 적용
                        val tapX = params.x + 50f
                        val tapY = params.y + 100f
                        Log.d("KeyMapper", "[TOUCH] PERFORMING TAP (REALTIME OVERLAY POS): ${function.name} at ($tapX, $tapY)")
                        gestureManager.performTap(tapX, tapY)
                        return true
                    }

                    // 위젯이 없는 경우(잠금 모드 등) 저장된 좌표 또는 기본 좌표 사용
                    val widgetPrefs = getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
                    var x = widgetPrefs.getInt("${activePreset}_${function.name}_x", -1).toFloat()
                    var y = widgetPrefs.getInt("${activePreset}_${function.name}_y", -1).toFloat()
                    
                    if (x == -1f || y == -1f) {
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
                        val tapX = x + 50f
                        val tapY = y + 100f
                        Log.d("KeyMapper", "[TOUCH] PERFORMING TAP (STORED POS): ${function.name} at ($tapX, $tapY)")
                        gestureManager.performTap(tapX, tapY)
                        return true
                    }
                }
            }
        }
        return false
    }
}
