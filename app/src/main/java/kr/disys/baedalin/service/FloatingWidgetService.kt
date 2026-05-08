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
import androidx.core.content.edit
import dagger.hilt.android.AndroidEntryPoint
import kr.disys.baedalin.MainActivity
import kr.disys.baedalin.R
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.ui.StatusOverlayManager
import kr.disys.baedalin.ui.ToolbarManager
import kr.disys.baedalin.ui.WidgetTouchHandler
import kr.disys.baedalin.ui.overlay.OverlayManager
import kr.disys.baedalin.util.OverlayFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@AndroidEntryPoint
class FloatingWidgetService : Service() {

    @Inject lateinit var overlayManager: OverlayManager
    
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

    private fun addNumberedWidget() {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val preset = currentPreset
        val counterKey = "${preset}_custom_counter"
        val counter = prefs.getInt(counterKey, 1)
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
        
        // 2. 데이터 저장 (목록 관리)
        val listKey = "${preset}_active_custom_widgets"
        val currentWidgets = prefs.getString(listKey, "") ?: ""
        val newList = if (currentWidgets.isEmpty()) label else "$currentWidgets,$label"      
        
        prefs.edit { 
            putString(listKey, newList)
            putInt(counterKey, counter + 1)
            putInt("${preset}_last_added_x", lastAddedX + 60)
            putInt("${preset}_last_added_y", lastAddedY + 60)
        }

        // 3. 좌표 및 카운터 갱신
        lastAddedX += 60
        lastAddedY += 60
        if (lastAddedX > 800 || lastAddedY > 1200) {
            lastAddedX = 200
            lastAddedY = 250
        }
        statusManager.showStatusOverlay("커스텀 위젯 $label 추가됨", 1000)
    }

    private fun loadStoredCustomWidgets() {
        val preset = currentPreset
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val listKey = "${preset}_active_custom_widgets"
        val activeWidgets = prefs.getString(listKey, "") ?: ""
        val color = Presets.getColor(preset)

        if (activeWidgets.isNotEmpty()) {
            activeWidgets.split(",").forEach { label ->
                if (label.isNotBlank()) {
                    showWidget(
                        functionName = "${preset}_CUSTOM_$label",
                        icon = label,
                        tooltip = "사용자 $label",
                        targetX = -1, // 기존 저장 좌표 사용
                        targetY = -1,
                        color = color
                    )
                }
            }
        }
        
        lastAddedX = prefs.getInt("${preset}_last_added_x", 200)
        lastAddedY = prefs.getInt("${preset}_last_added_y", 250)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        statusManager = StatusOverlayManager(this)
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleAction(intent)
        return START_NOT_STICKY
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
                _isInterceptionActive.value = true
            }
            ACTION_UPDATE_TRANSPARENCY -> {
                val alpha = intent.getFloatExtra("transparency", 1.0f)
                if (::toolbarManager.isInitialized) toolbarManager.updateAlpha(alpha)
            }
            ACTION_START_SERVICE_ONLY -> {
                showSettingsWidget()
                _isMappingEnabled.value = true
                _isInterceptionActive.value = false
            }
            ACTION_SET_TOOLBAR_VISIBILITY -> {
                val visible = intent.getBooleanExtra("visible", true)
                if (visible) showSettingsWidget() else hideWidget("SYSTEM_SETTINGS")
            }
            ACTION_UPDATE_KEY -> {
                val functionName = intent.getStringExtra("function_name")
                val keyName = intent.getStringExtra("key_name")
                val label = intent.getStringExtra("label") ?: "버튼"
                statusManager.showStatusOverlay("[$label] 매핑되었습니다.\n$keyName", 3000)
                loadPresetInternal(currentPreset)
            }
        }
    }

    private fun loadPresetInternal(presetName: String) {
        currentPreset = presetName
        val presetList = when(presetName) {
            "BAEMIN" -> Presets.BAEMIN
            "COUPANG" -> Presets.COUPANG
            "YOGIYO" -> Presets.YOGIYO
            else -> Presets.BAEMIN
        }
        val color = Presets.getColor(presetName)

        overlayManager.getAllOverlayIds().forEach { id ->
            if (id != "SYSTEM_SETTINGS") hideWidget(id)
        }
        setPresetsVisibility(false)

        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val sharedPrefs = getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
        val offsetX = ICON_SIZE / 2
        val offsetY = ICON_SIZE / 2 + 40
        val prefix = prefs.getString("selected_device_descriptor", "GLOBAL") ?: "GLOBAL"
        presetList.forEach { info ->
            val savedX = sharedPrefs.getInt("${presetName}_${info.function.name}_x", -1)
            val savedY = sharedPrefs.getInt("${presetName}_${info.function.name}_y", -1)
            
            val targetX = if (savedX != -1) savedX else info.x - offsetX
            val targetY = if (savedY != -1) savedY else info.y - offsetY
            
            val keycode = prefs.getInt("${prefix}_${info.function.name}_keycode", -1)
            val keyInfo = if (keycode != -1) {
                val keyName = android.view.KeyEvent.keyCodeToString(keycode).replace("KEYCODE_", "")
                "$keycode ($keyName)"
            } else null
            
            showWidget(info.function.name, info.icon, info.tooltip, targetX, targetY, color, keyInfo)
        }
    }

    private fun showWidget(functionName: String, icon: String, tooltip: String, targetX: Int, targetY: Int, color: Int, keyInfo: String? = null) {
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
            
            val sharedPrefs = getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
            val savedX = sharedPrefs.getInt("${currentPreset}_${functionName}_x", -1)
            val savedY = sharedPrefs.getInt("${currentPreset}_${functionName}_y", -1)

            if (savedX != -1 && savedY != -1) {
                x = savedX
                y = savedY
            } else if (targetX != -1 && targetY != -1) {
                x = targetX
                y = targetY
                sharedPrefs.edit { putInt("${currentPreset}_${functionName}_x", x); putInt("${currentPreset}_${functionName}_y", y) }
            } else {
                x = 100
                y = 100
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            
            addView(View(this@FloatingWidgetService).apply {
                id = 10001
                layoutParams = LinearLayout.LayoutParams(20, 20)
                setBackgroundColor(Color.YELLOW)
                visibility = if (_isMoveMode.value) View.VISIBLE else View.GONE
            })

            addView(TextView(this@FloatingWidgetService).apply { 
                text = tooltip 
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC000000.toInt())
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
                val sharedPrefs = getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
                sharedPrefs.edit {
                    putInt("${currentPreset}_${functionName}_x", x)
                    putInt("${currentPreset}_${functionName}_y", y)
                }
                if (kr.disys.baedalin.KeyRecordingState.recordingFunction == null) {
                    statusManager.showStatusOverlay("위치 저장 완료", 1000)
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

    private fun showSettingsWidget() {
        val functionName = "SYSTEM_SETTINGS"
        if (overlayManager.isShowing(functionName)) return

        if (!::toolbarManager.isInitialized) {
            toolbarManager = ToolbarManager(this, getSystemService(Context.WINDOW_SERVICE) as WindowManager, object : ToolbarManager.ToolbarCallbacks {
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
                    launchApp(pkg)
                    loadPresetInternal(name) 
                }
                override fun onFold(folded: Boolean) { isToolbarFolded = folded }
                override fun onSavePosition(x: Int, y: Int) {}
            })
        }
        toolbarManager.showToolbar(800, 200, 1.0f, isToolbarFolded)
        toolbarManager.root?.let {
            overlayManager.showOverlay(functionName, it, toolbarManager.currentParams!!)
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

    private fun launchApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("KeyMapper", "Failed to launch app: $packageName", e)
                Toast.makeText(this, "앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "앱이 설치되어 있지 않습니다: $packageName", Toast.LENGTH_SHORT).show()
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
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        hideScreenBorder()
        statusManager.cleanup()
        overlayManager.hideAll()
        _isRunning.value = false
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
