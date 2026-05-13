package kr.disys.baedalin.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kr.disys.baedalin.MainActivity
import kr.disys.baedalin.R
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.ui.StatusOverlayManager
import kr.disys.baedalin.ui.ToolbarManager
import kr.disys.baedalin.ui.WidgetTouchHandler
import kr.disys.baedalin.ui.overlay.OverlayManager
import kr.disys.baedalin.util.OverlayFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kr.disys.baedalin.domain.repository.MappingRepository
import kr.disys.baedalin.model.DeliveryFunction

@AndroidEntryPoint
class FloatingWidgetService : Service() {

    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var appSwitcher: AppSwitcher
    @Inject lateinit var mappingRepository: MappingRepository
    
    private val ICON_SIZE = 100 
    private var currentPreset: String = "DEFAULT"
    private var isToolbarFolded = false
    private var isPresetsHidden = false
    
    private lateinit var statusManager: StatusOverlayManager
    private lateinit var toolbarManager: ToolbarManager
    private var screenBorderView: View? = null

    // 커스텀 위젯 관리를 위한 상태 변수
    private var lastAddedX = 200
    private var lastAddedY = 250

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun addNumberedWidget() {
        serviceScope.launch {
            val preset = currentPreset
            val counter = mappingRepository.getCustomWidgetCounter(preset).first()
            val label = counter.toString()
            val functionName = "${preset}_CUSTOM_$label"
            val color = Presets.getColor(preset)
            
            // 1. 위젯 표시
            showWidget(
                functionName = functionName,
                icon = label,
                tooltip = "사용자 $label",
                targetX = lastAddedX,
                targetY = lastAddedY,
                color = color
            )
            
            // 2. 데이터 저장
            mappingRepository.addCustomWidget(preset, label)
            mappingRepository.setCustomWidgetCounter(preset, counter + 1)
            mappingRepository.saveWidgetPosition(preset, "last_added", lastAddedX + 60, lastAddedY + 60)

            // 3. 좌표 및 카운터 갱신
            lastAddedX += 60
            lastAddedY += 60
            if (lastAddedX > 800 || lastAddedY > 1200) {
                lastAddedX = 200
                lastAddedY = 250
            }
            statusManager.showStatusOverlay("커스텀 위젯 $label 추가됨", 1000)
        }
    }

    private fun loadStoredCustomWidgets() {
        serviceScope.launch {
            val preset = currentPreset
            val activeWidgets = mappingRepository.getActiveCustomWidgets(preset).first()
            val color = Presets.getColor(preset)

            activeWidgets.forEach { label ->
                showWidget(
                    functionName = "${preset}_CUSTOM_$label",
                    icon = label,
                    tooltip = "사용자 $label",
                    targetX = -1,
                    targetY = -1,
                    color = color
                )
            }
            
            val lastAdded = mappingRepository.getWidgetPosition(preset, "last_added").first()
            lastAddedX = if (lastAdded.first != -1) lastAdded.first else 200
            lastAddedY = if (lastAdded.second != -1) lastAdded.second else 250
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        statusManager = StatusOverlayManager(this)
        _isRunning.value = true
        
        mappingRepository.isNightMode()
            .onEach { _isNightMode.value = it }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleAction(intent)
        return START_NOT_STICKY
    }

    private fun setInterceptionActive(active: Boolean) {
        if (_isInterceptionActive.value != active) {
            _isInterceptionActive.value = active
            Log.d("KeyMapper", "FloatingWidgetService: setInterceptionActive=$active")
            
            val intent = Intent("ACTION_REFRESH_FILTER").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
    }

    private fun handleAction(intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("KeyMapper", "FloatingWidgetService.handleAction: action=$action")
        
        when (action) {
            ACTION_SHOW_WIDGET -> {
                intent.getStringExtra("preset_name")?.let { currentPreset = it }
                showSettingsWidget()
            }
            ACTION_HIDE_ALL -> hideAll()
            ACTION_HIDE_PRESETS -> setPresetsVisibility(true)
            ACTION_HIDE_WIDGET -> intent.getStringExtra("function_name")?.let { hideWidget(it) }
            ACTION_LOAD_PRESET -> {
                val preset = intent.getStringExtra("preset_name") ?: "BAEMIN"
                loadPresetInternal(preset)
                loadStoredCustomWidgets()
                showSettingsWidget()
                _isMappingEnabled.value = true
                setInterceptionActive(true)
            }
            ACTION_UPDATE_TRANSPARENCY -> {
                val alpha = intent.getFloatExtra("transparency", 1.0f)
                if (::toolbarManager.isInitialized) toolbarManager.updateAlpha(alpha)
            }
            ACTION_START_SERVICE_ONLY -> {
                showSettingsWidget()
                _isMappingEnabled.value = true
                setInterceptionActive(false)
            }
            ACTION_SET_TOOLBAR_VISIBILITY -> {
                val visible = intent.getBooleanExtra("visible", true)
                if (visible) showSettingsWidget() else hideWidget("SYSTEM_SETTINGS")
            }
            ACTION_UPDATE_KEY -> {
                val label = intent.getStringExtra("label") ?: "버튼"
                val keyName = intent.getStringExtra("key_name")
                statusManager.showStatusOverlay("[$label] 매핑되었습니다.\n$keyName", 3000)
                loadPresetInternal(currentPreset)
            }
            "ACTION_TOGGLE_THEME" -> {
                val newMode = !_isNightMode.value
                _isNightMode.value = newMode
                serviceScope.launch {
                    mappingRepository.setNightMode(newMode)
                }
                
                if (::toolbarManager.isInitialized) toolbarManager.updateTheme(newMode)
                statusManager.updateTheme(newMode)
                
                overlayManager.getAllOverlayIds().forEach { id ->
                    if (id != "SYSTEM_SETTINGS") {
                        val view = overlayManager.getOverlayView(id) as? LinearLayout
                        val tooltipView = view?.findViewById<TextView>(10002)
                        tooltipView?.apply {
                            setTextColor(if (newMode) Color.LTGRAY else Color.WHITE)
                            setBackgroundColor(if (newMode) 0xEE111111.toInt() else 0xCC000000.toInt())
                        }
                    }
                }
            }
            "ACTION_SET_INTERCEPTION" -> {
                val active = intent.getBooleanExtra("active", false)
                setInterceptionActive(active)
            }
        }
    }

    private fun loadPresetInternal(presetName: String) {
        currentPreset = presetName
        
        serviceScope.launch {
            mappingRepository.setActivePreset(presetName)
            
            val presetList = when(presetName) {
                "BAEMIN" -> Presets.BAEMIN
                "COUPANG" -> Presets.COUPANG
                else -> Presets.BAEMIN
            }
            val color = Presets.getColor(presetName)

            overlayManager.getAllOverlayIds().forEach { id ->
                if (id != "SYSTEM_SETTINGS") hideWidget(id)
            }
            setPresetsVisibility(false)

            val deviceDescriptor = mappingRepository.getSelectedDeviceDescriptor().first() ?: "GLOBAL"
            val offsetX = ICON_SIZE / 2
            val offsetY = ICON_SIZE / 2 + 40
            
            presetList.forEach { info ->
                val pos = mappingRepository.getWidgetPosition(presetName, info.function.name).first()
                val targetX = if (pos.first != -1) pos.first else info.x - offsetX
                val targetY = if (pos.second != -1) pos.second else info.y - offsetY
                
                // keyMappingRepository still uses DeliveryFunction/ClickType but we need a simple check
                val keycode = mappingRepository.getMapping(deviceDescriptor, info.function, kr.disys.baedalin.model.ClickType.SINGLE).first()
                val keyInfo = if (keycode != null) {
                    val keyName = android.view.KeyEvent.keyCodeToString(keycode).replace("KEYCODE_", "")
                    "$keycode ($keyName)"
                } else null
                
                showWidget(info.function.name, info.icon, info.tooltip, targetX, targetY, color, keyInfo)
            }
        }
    }

    private fun showWidget(functionName: String, icon: String, tooltip: String, targetX: Int, targetY: Int, color: Int, keyInfo: String? = null) {
        serviceScope.launch {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (_isMoveMode.value) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                
                val pos = mappingRepository.getWidgetPosition(currentPreset, functionName).first()
                if (pos.first != -1 && pos.second != -1) {
                    x = pos.first
                    y = pos.second
                } else if (targetX != -1 && targetY != -1) {
                    x = targetX
                    y = targetY
                    mappingRepository.saveWidgetPosition(currentPreset, functionName, x, y)
                } else {
                    x = 100
                    y = 100
                }
            }

            val container = LinearLayout(this@FloatingWidgetService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                
                addView(View(this@FloatingWidgetService).apply {
                    id = 10001
                    layoutParams = LinearLayout.LayoutParams(20, 20)
                    setBackgroundColor(Color.YELLOW)
                    visibility = if (_isMoveMode.value) View.VISIBLE else View.GONE
                })

                addView(TextView(this@FloatingWidgetService).apply { 
                    id = 10002
                    text = tooltip 
                    setTextColor(if (_isNightMode.value) Color.LTGRAY else Color.WHITE)
                    setBackgroundColor(if (_isNightMode.value) 0xEE111111.toInt() else 0xCC000000.toInt())
                    setPadding(8, 4, 8, 4)
                    textSize = 10f
                })
                addView(OverlayFactory.createCircleIcon(this@FloatingWidgetService, icon, color, ICON_SIZE))
                
                if (keyInfo != null) {
                    addView(TextView(this@FloatingWidgetService).apply {
                        text = keyInfo
                        setTextColor(Color.YELLOW)
                        setBackgroundColor(0xAA000000.toInt())
                        setPadding(4, 2, 4, 2)
                        textSize = 9f
                        gravity = Gravity.CENTER
                    })
                }
            }

            container.setOnTouchListener(WidgetTouchHandler(
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager,
                onMove = { x, y ->
                    params.x = x
                    params.y = y
                    overlayManager.updateOverlay(functionName, params)
                },
                onSave = { x, y -> 
                    serviceScope.launch {
                        mappingRepository.saveWidgetPosition(currentPreset, functionName, x, y)
                        if (kr.disys.baedalin.KeyRecordingState.recordingFunction == null) {
                            statusManager.showStatusOverlay("위치 저장 완료", 1000)
                        }
                    }
                },
                onClick = {
                    val intent = Intent(ACTION_MANUAL_CLICK).apply {
                        setPackage(packageName)
                        putExtra("function_name", functionName)
                    }
                    sendBroadcast(intent)
                },
                onLongClick = { 
                    statusManager.showStatusOverlay("${tooltip} 위젯 선택됨", 1000)
                    triggerVibration(50)
                },
                onMappingMode = { 
                    triggerVibration(150)
                    startMappingCountdown(tooltip, functionName)
                },
                isMoveMode = { _isMoveMode.value },
                isRecording = { kr.disys.baedalin.KeyRecordingState.recordingFunction != null }
            ))

            overlayManager.showOverlay(functionName, container, params)
        }
    }

    private fun showSettingsWidget() {
        val functionName = "SYSTEM_SETTINGS"
        if (overlayManager.isShowing(functionName)) return

        if (!::toolbarManager.isInitialized) {
            toolbarManager = ToolbarManager(
                this, 
                getSystemService(Context.WINDOW_SERVICE) as WindowManager, 
                object : ToolbarManager.ToolbarCallbacks {
                    override fun onAddWidget() { addNumberedWidget() }
                    override fun onToggleMoveMode() { 
                        val newMode = !_isMoveMode.value
                        _isMoveMode.value = newMode
                        
                        val toastMsg = if (newMode) {
                            showScreenBorder()
                            "위젯 위치 설정(드래그 가능 상태)"
                        } else {
                            hideScreenBorder()
                            "위젯 위치 잠금(배달 앱 조작 가능)"
                        }
                        statusManager.showStatusOverlay(toastMsg, 2000)
                        
                        updateToolbarState()
                        
                        val ids = overlayManager.getAllOverlayIds()
                        ids.forEach { id ->
                            if (id != "SYSTEM_SETTINGS") {
                                val view = overlayManager.getOverlayView(id)
                                val p = view?.layoutParams as? WindowManager.LayoutParams
                                if (p != null) {
                                    if (newMode) {
                                        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                        view?.findViewById<View>(10001)?.visibility = View.VISIBLE
                                    } else {
                                        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        view?.findViewById<View>(10001)?.visibility = View.GONE
                                    }
                                    overlayManager.updateOverlay(id, p)
                                }
                            }
                        }
                    }
                    override fun onTogglePresetsVisibility() { setPresetsVisibility(!isPresetsHidden) }
                    override fun onPowerOff() { hideAll() }
                    override fun onOpenMainActivity() {
                        val intent = Intent(this@FloatingWidgetService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                    override fun onLaunchApp(name: String) { 
                        val pkg = Presets.getPackageName(name)
                        appSwitcher.launchApp(pkg)
                        loadPresetInternal(name) 
                    }
                    override fun onFold(folded: Boolean) { isToolbarFolded = folded }
                    override fun onSavePosition(x: Int, y: Int) {
                        serviceScope.launch {
                            mappingRepository.saveWidgetPosition("SYSTEM", "toolbar", x, y)
                        }
                    }
                },
                onOpenSettings = {
                    val intent = Intent(this@FloatingWidgetService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                },
                isNightMode = { _isNightMode.value }
            )
        }

        serviceScope.launch {
            val pos = mappingRepository.getWidgetPosition("SYSTEM", "toolbar").first()
            val initialX = if (pos.first != -1) pos.first else 800
            val initialY = if (pos.second != -1) pos.second else 500

            val opacity = mappingRepository.getToolbarOpacity().first()
            toolbarManager.showToolbar(initialX, initialY, opacity, isToolbarFolded)
            toolbarManager.root?.let {
                overlayManager.showOverlay(functionName, it, toolbarManager.currentParams!!)
            }
        }
    }

    fun updateToolbarState() {
        if (::toolbarManager.isInitialized) {
            try {
                toolbarManager.updateMoveIcon(_isMoveMode.value)
            } catch (e: Exception) {
                Log.e("KeyMapper", "Failed to update toolbar state", e)
            }
        }
    }

    private fun hideWidget(id: String) = overlayManager.hideOverlay(id)

    private fun showScreenBorder() {
        if (screenBorderView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val frameLayout = FrameLayout(this).apply {
            val border = GradientDrawable().apply {
                setStroke(15, Color.YELLOW)
                setColor(Color.TRANSPARENT)
            }
            foreground = border
            
            val textView = TextView(this.context).apply {
                text = "위젯 위치 설정 모드 활성\n\n위젯을 드래그하여 배치하세요\n설정 완료 후 다시 자물쇠를 누르세요"
                setTextColor(Color.YELLOW)
                textSize = 16f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                setPadding(40, 30, 40, 30)
                android.graphics.Typeface.DEFAULT_BOLD.also { typeface = it }
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
            }
            
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            lp.topMargin = 40
            addView(textView, lp)
        }
        screenBorderView = frameLayout

        try {
            wm.addView(screenBorderView, params)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to add screen border", e)
        }
    }

    private fun hideScreenBorder() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenBorderView?.let {
            try {
                if (it.parent != null) {
                    wm.removeViewImmediate(it)
                }
            } catch (e: Exception) {
                Log.e("KeyMapper", "Failed to remove screen border", e)
            } finally {
                screenBorderView = null
            }
        }
    }

    private fun triggerVibration(durationMs: Long = 100) {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE), attrs)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.e("KeyMapper", "Vibration failed", e)
        }
    }

    private fun startMappingCountdown(tooltip: String, functionName: String) {
        val mappingHandler = Handler(Looper.getMainLooper())
        var secondsLeft = 5

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (kr.disys.baedalin.KeyRecordingState.recordingFunction == null && secondsLeft < 5) return

                if (secondsLeft > 0) {
                    statusManager.showStatusOverlay("[$tooltip]\n매핑할 키를 입력하세요... (${secondsLeft}초)", 1500)
                    secondsLeft--
                    mappingHandler.postDelayed(this, 1000)
                } else {
                    statusManager.showStatusOverlay("[$tooltip] 매핑 시간 초과", 3000)
                    kr.disys.baedalin.KeyRecordingState.recordingFunction = null
                    startService(Intent(this@FloatingWidgetService, kr.disys.baedalin.service.KeyMapperAccessibilityService::class.java).apply {
                        action = "ACTION_CANCEL_DIRECT_RECORDING"
                    })
                }
            }
        }

        kr.disys.baedalin.KeyRecordingState.recordingFunction = functionName
        startService(Intent(this, kr.disys.baedalin.service.KeyMapperAccessibilityService::class.java).apply {
            action = "ACTION_START_DIRECT_RECORDING"
            putExtra("function_name", functionName)
        })
        mappingHandler.post(countdownRunnable)
    }

    private fun setPresetsVisibility(hidden: Boolean) {
        isPresetsHidden = hidden
        _isInterceptionActive.value = !hidden
    }

    private fun hideAll() {
        hideScreenBorder()
        overlayManager.hideAll()
        _isRunning.value = false
        serviceScope.launch {
            mappingRepository.setMappingEnabled(false)
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        hideScreenBorder()
        statusManager.cleanup()
        overlayManager.hideAll()
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _isMappingEnabled = MutableStateFlow(false)
        val isMappingEnabled: StateFlow<Boolean> = _isMappingEnabled

        private val _isInterceptionActive = MutableStateFlow(false)
        val isInterceptionActive: StateFlow<Boolean> = _isInterceptionActive

        private val _isMoveMode = MutableStateFlow(false)
        val isMoveMode: StateFlow<Boolean> = _isMoveMode

        private val _isNightMode = MutableStateFlow(false)
        val isNightMode: StateFlow<Boolean> = _isNightMode

        var instance: FloatingWidgetService? = null

        const val ACTION_SHOW_WIDGET = "ACTION_SHOW_WIDGET"
        const val ACTION_HIDE_WIDGET = "ACTION_HIDE_WIDGET"
        const val ACTION_HIDE_ALL = "ACTION_HIDE_ALL"
        const val ACTION_HIDE_PRESETS = "ACTION_HIDE_PRESETS"
        const val ACTION_LOAD_PRESET = "ACTION_LOAD_PRESET"
        const val ACTION_UPDATE_TRANSPARENCY = "ACTION_UPDATE_TRANSPARENCY"
        const val ACTION_START_SERVICE_ONLY = "ACTION_START_SERVICE_ONLY"
        const val ACTION_SET_TOOLBAR_VISIBILITY = "ACTION_SET_TOOLBAR_VISIBILITY"
        const val ACTION_UPDATE_KEY = "ACTION_UPDATE_KEY"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_UPDATE_UI = "ACTION_UPDATE_UI"
        const val ACTION_MANUAL_CLICK = "ACTION_MANUAL_CLICK"
    }
}
