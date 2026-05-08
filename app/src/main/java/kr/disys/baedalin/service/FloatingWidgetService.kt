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
                val label = intent.getStringExtra("label")
                Toast.makeText(this, "$label -> $keyName 매핑 완료", Toast.LENGTH_SHORT).show()
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

        val sharedPrefs = getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
        val offsetX = ICON_SIZE / 2
        val offsetY = ICON_SIZE / 2 + 40

        presetList.forEach { info ->
            val savedX = sharedPrefs.getInt("${presetName}_${info.function.name}_x", -1)
            val savedY = sharedPrefs.getInt("${presetName}_${info.function.name}_y", -1)
            
            val targetX = if (savedX != -1) savedX else info.x - offsetX
            val targetY = if (savedY != -1) savedY else info.y - offsetY
            
            showWidget(info.function.name, info.icon, info.tooltip, targetX, targetY, color)
        }
    }

    private fun showWidget(functionName: String, icon: String, tooltip: String, targetX: Int, targetY: Int, color: Int) {
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
            x = targetX
            y = targetY
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

            addView(TextView(this@FloatingWidgetService).apply { text = tooltip })
            addView(OverlayFactory.createCircleIcon(this@FloatingWidgetService, icon, color, ICON_SIZE))
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
            },
            onClick = { /* 클릭 로직 */ },
            onLongClick = { /* 롱클릭 로직 */ },
            onMappingMode = { 
                val intent = Intent(this@FloatingWidgetService, kr.disys.baedalin.service.KeyMapperAccessibilityService::class.java).apply {
                    action = "ACTION_START_DIRECT_RECORDING"
                    putExtra("preset_name", currentPreset)
                    putExtra("function_name", functionName)
                }
                startService(intent)
            },
            isMoveMode = { _isMoveMode.value },
            isRecording = { false }
        ))

        overlayManager.showOverlay(functionName, container, params)
    }

    private fun showSettingsWidget() {
        val functionName = "SYSTEM_SETTINGS"
        if (overlayManager.isShowing(functionName)) return

        if (!::toolbarManager.isInitialized) {
            toolbarManager = ToolbarManager(this, getSystemService(Context.WINDOW_SERVICE) as WindowManager, object : ToolbarManager.ToolbarCallbacks {
                override fun onAddWidget() {}
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
                override fun onLaunchApp(name: String) { loadPresetInternal(name) }
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
    }
}
