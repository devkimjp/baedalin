package kr.disys.baedalin.service

import android.util.Log
import android.app.Service
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.media.AudioAttributes
import android.widget.TextView
import android.widget.Toast
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import androidx.core.content.edit
import kr.disys.baedalin.MainActivity
import kr.disys.baedalin.R
import kr.disys.baedalin.model.Presets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.disys.baedalin.ui.StatusOverlayManager
import kr.disys.baedalin.ui.ToolbarManager
import kr.disys.baedalin.ui.WidgetTouchHandler
import kr.disys.baedalin.util.OverlayFactory
import kr.disys.baedalin.KeyRecordingState
import kr.disys.baedalin.model.DeliveryFunction

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private val overlayViews = mutableMapOf<String, View>()
    private val ICON_SIZE = 100 
    private var settingsParams: WindowManager.LayoutParams? = null

    private var currentPreset: String = "DEFAULT"

    private var customWidgetCounter = 1
    private var lastAddedX = 200
    private var lastAddedY = 250
    private var isToolbarFolded = false
    private var preferredFoldedState = false
    private var isPresetsHidden = false
    private var screenBorderView: View? = null
    
    private lateinit var statusManager: StatusOverlayManager
    private lateinit var toolbarManager: ToolbarManager

    private fun showStatusOverlay(message: String, durationMs: Long = 2000, priority: Int = 1) {
        statusManager.showStatusOverlay(message, durationMs, priority)
    }

    private fun hideStatusOverlay() {
        statusManager.hideStatusOverlay()
    }

    private fun triggerVibration(durationMs: Long = 100) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // USAGE_ASSISTANCE_ACCESSIBILITY를 사용하여 무음 모드 등에서도 더 신뢰성 있게 진동 전달
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE), attrs)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.e("KeyMapper", "Vibration failed", e)
        }
    }

    private fun addNumberedWidget(prefs: android.content.SharedPreferences) {
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
            tooltip = "사용자 위젯 $label",
            targetX = lastAddedX,
            targetY = lastAddedY,
            color = color
        )
        
        // 2. 데이터 저장 (모드별 분리)
        val listKey = "${preset}_active_custom_widgets"
        val currentWidgets = prefs.getString(listKey, "") ?: ""
        val newList = if (currentWidgets.isEmpty()) label else "$currentWidgets,$label"
        
        prefs.edit { 
            putString(listKey, newList)
            putInt(counterKey, counter + 1)
            putInt("${preset}_last_added_x", lastAddedX + 60)
            putInt("${preset}_last_added_y", lastAddedY + 60)
        }

        // 3. 좌표 및 카운터 갱신 (로컬 상태도 현재 모드에 맞춰 동기화)
        lastAddedX += 60
        lastAddedY += 60
        if (lastAddedX > 800 || lastAddedY > 1200) {
            lastAddedX = 200
            lastAddedY = 250
        }
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
                        tooltip = "사용자 위젯 $label",
                        targetX = -1, // 저장된 좌표 사용
                        targetY = -1,
                        color = color
                    )
                }
            }
        }
        
        // 모드별 카운터 및 마지막 좌표 복구
        lastAddedX = prefs.getInt("${preset}_last_added_x", 200)
        lastAddedY = prefs.getInt("${preset}_last_added_y", 250)
    }

    private fun togglePresetsVisibility() {
        setPresetsVisibility(!isPresetsHidden)
    }

    private fun setPresetsVisibility(hidden: Boolean) {
        isPresetsHidden = hidden
        overlayViews.forEach { (name, view) ->
            if (name != "SYSTEM_SETTINGS") {
                view.visibility = if (isPresetsHidden) View.GONE else View.VISIBLE
            }
        }
        
        // 프리셋이 숨겨지면 키 매핑 간섭도 중지
        _isInterceptionActive.value = !isPresetsHidden
        
        // 툴바의 눈 아이콘 상태 동기화는 이제 ToolbarManager에서 담당하거나 생략 가능
        // (현재 ToolbarManager에는 눈 아이콘이 명시적으로 포함되어 있지 않음)
    }

    private fun setToolbarFolded(folded: Boolean) {
        isToolbarFolded = folded
        if (::toolbarManager.isInitialized) {
            toolbarManager.setFolded(folded)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
                loadStoredCustomWidgets()
            }
            ACTION_HIDE_ALL -> hideAll()
            ACTION_HIDE_PRESETS -> {
                setPresetsVisibility(true)
                setToolbarFolded(true)
            }
            ACTION_HIDE_WIDGET -> {
                intent.getStringExtra("function_name")?.let { hideWidget(it) }
            }
            ACTION_LOAD_PRESET -> {
                val preset = intent.getStringExtra("preset_name") ?: "BAEMIN"
                loadPresetInternal(preset)
                showSettingsWidget()
                _isMappingEnabled.value = true
                _isInterceptionActive.value = true
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("is_mapping_enabled", true) }
            }
            ACTION_UPDATE_TRANSPARENCY -> {
                val alpha = intent.getFloatExtra("transparency", 1.0f)
                if (::toolbarManager.isInitialized) toolbarManager.updateAlpha(alpha)
            }
            ACTION_START_SERVICE_ONLY -> {
                Log.d("KeyMapper", "ACTION_START_SERVICE_ONLY received")
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { 
                    putBoolean("is_mapping_enabled", true)
                    putBoolean("toolbar_visible", true) // 서비스 시작 시 툴바를 강제로 보이게 함
                }
                showSettingsWidget()
                _isMappingEnabled.value = true
                _isInterceptionActive.value = false
            }
            ACTION_SET_TOOLBAR_VISIBILITY -> {
                val visible = intent.getBooleanExtra("visible", true)
                if (visible) showSettingsWidget() else hideWidget("SYSTEM_SETTINGS")
            }
            ACTION_UPDATE_KEY -> {
                val label = intent.getStringExtra("label") ?: "버튼"
                val keyCode = intent.getIntExtra("keycode", -1)
                val keyName = intent.getStringExtra("key_name") ?: ""
                showStatusOverlay("[$label] 매핑 완료\n$keyName ($keyCode)", 3000, priority = 3)
                loadPresetInternal(currentPreset)
            }
        }
    }

    private fun loadPresetInternal(presetName: String) {
        if (currentPreset == presetName && overlayViews.size > 1 && !isPresetsHidden) return
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("active_preset", presetName) }
        currentPreset = presetName

        val presetList = when(presetName) {
            "BAEMIN" -> Presets.BAEMIN
            "COUPANG" -> Presets.COUPANG
            "YOGIYO" -> Presets.YOGIYO
            else -> Presets.BAEMIN
        }
        val color = Presets.getColor(presetName)

        hidePresets()
        
        // 프리셋 로드 시 숨김/접힘 상태 해제
        setPresetsVisibility(false)
        setToolbarFolded(false)

        val prefix = prefs.getString("selected_device_descriptor", "GLOBAL") ?: "GLOBAL"
        
        presetList.forEach { info ->
            val keycode = prefs.getInt("${prefix}_${info.function.name}_keycode", -1)
            val keyInfo = if (keycode != -1) {
                val keyName = KeyEvent.keyCodeToString(keycode).replace("KEYCODE_", "")
                "$keycode ($keyName)"
            } else null
            
            showWidget(info.function.name, info.icon, info.tooltip, info.x, info.y, color, keyInfo)
        }

        // 새 프리셋의 커스텀 위젯들 로드
        loadStoredCustomWidgets()
    }

    private fun toggleMoveMode() {
        val currentMode = _isMoveMode.value
        _isMoveMode.value = !currentMode
        
        val toastMsg = if (_isMoveMode.value) {
            showScreenBorder()
            "이동 모드 활성화 (위젯을 옮길 수 있습니다)"
        } else {
            hideScreenBorder()
            "잠금 모드 활성화 (위젯 위치 고정)"
        }
        
        showCustomToast(toastMsg)

        overlayViews.forEach { (name, view) ->
            if (name != "SYSTEM_SETTINGS") {
                val params = view.layoutParams as WindowManager.LayoutParams
                if (_isMoveMode.value) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    view.findViewById<View>(R.id.drag_handle)?.visibility = View.VISIBLE
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    view.findViewById<View>(R.id.drag_handle)?.visibility = View.GONE
                }
                windowManager.updateViewLayout(view, params)
            }
        }
        
        updateToolbarState() // 상태 변경 후 아이콘 갱신
    }

    fun updateToolbarState() {
        Handler(Looper.getMainLooper()).post {
            val currentApp = KeyMapperAccessibilityService.currentPackageName
            val preset = Presets.getPresetFromPackage(currentApp)
            val isTargetApp = preset != null || 
                             currentApp.contains("woowahan") || 
                             currentApp.contains("coupang") || 
                             currentApp == "kr.disys.baedalin"

            Log.d("KeyMapper", "updateToolbarState: app=$currentApp, isTarget=$isTargetApp")

            if (::toolbarManager.isInitialized) {
                toolbarManager.updateMoveIcon(_isMoveMode.value)
            }
            
            // 자동 접힘/펼침 로직 삭제 (사용자가 직접 제어하도록 함)
        }
    }


    private fun showWidget(functionName: String, icon: String, tooltip: String, targetX: Int, targetY: Int, color: Int, keyInfo: String? = null) {
        hideWidget(functionName)

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
            windowAnimations = 0
        }
        
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val prefKeyX = "${currentPreset}_${functionName}_x"
        val prefKeyY = "${currentPreset}_${functionName}_y"
        
        val offsetX = ICON_SIZE / 2
        val offsetY = ICON_SIZE / 2 + 40

        // 저장된 좌표가 있으면 불러오고, 없으면 프리셋 좌표 사용
        val savedX = prefs.getInt(prefKeyX, -1)
        val savedY = prefs.getInt(prefKeyY, -1)

        if (savedX != -1 && savedY != -1) {
            params.x = savedX
            params.y = savedY
        } else if (targetX != -1 && targetY != -1) {
            params.x = targetX - offsetX
            params.y = targetY - offsetY
            // 처음 표시될 때 기본 좌표를 저장
            prefs.edit { putInt(prefKeyX, params.x); putInt(prefKeyY, params.y) }
        } else {
            params.x = 100
            params.y = 100
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val tooltipView = TextView(this).apply {
            text = tooltip
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(8, 4, 8, 4)
            textSize = 10f
        }
        container.addView(tooltipView)

        val circleView = OverlayFactory.createCircleIcon(this, icon, color, ICON_SIZE)
        container.addView(circleView)

        val dragHandle = View(this).apply {
            id = R.id.drag_handle
            layoutParams = LinearLayout.LayoutParams(ICON_SIZE / 2, 10).apply { setMargins(0, 5, 0, 0) }
            setBackgroundColor(Color.LTGRAY)
            visibility = if (_isMoveMode.value) View.VISIBLE else View.GONE
        }
        container.addView(dragHandle)

        if (keyInfo != null) {
            val keyView = TextView(this).apply {
                text = keyInfo
                setTextColor(Color.YELLOW)
                setBackgroundColor(0xAA000000.toInt())
                setPadding(4, 2, 4, 2)
                textSize = 9f
                gravity = Gravity.CENTER
            }
            container.addView(keyView)
        }

        container.setOnTouchListener(WidgetTouchHandler(
            windowManager = windowManager,
            onMove = { x, y ->
                params.x = x
                params.y = y
                windowManager.updateViewLayout(container, params)
            },
            onSave = { x, y ->
                prefs.edit { putInt(prefKeyX, x); putInt(prefKeyY, y) }
                if (kr.disys.baedalin.KeyRecordingState.recordingFunction == null) {
                    showStatusOverlay("위치 저장 완료", 1000, priority = 1)
                }
                if (!_isMoveMode.value) dragHandle.visibility = View.GONE
            },
            onClick = {
                val intent = Intent("ACTION_MANUAL_CLICK").apply {
                    setPackage(packageName)
                    putExtra("function_name", functionName)
                }
                sendBroadcast(intent)
            },
            onLongClick = {
                showCustomToast("${tooltip} 이동 모드 활성화")
                dragHandle.visibility = View.VISIBLE
                triggerVibration(50)
            },
            onMappingMode = {
                if (!_isMoveMode.value) dragHandle.visibility = View.GONE
                triggerVibration(150)
                startMappingCountdown(tooltip, functionName)
            },
            isMoveMode = { _isMoveMode.value },
            isRecording = { kr.disys.baedalin.KeyRecordingState.recordingFunction != null }
        ))

        windowManager.addView(container, params)
        overlayViews[functionName] = container
    }

    private fun startMappingCountdown(tooltip: String, functionName: String) {
        val mappingHandler = Handler(Looper.getMainLooper())
        var secondsLeft = 5

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (kr.disys.baedalin.KeyRecordingState.recordingFunction == null && secondsLeft < 5) return

                if (secondsLeft > 0) {
                    showStatusOverlay("[$tooltip]\n매핑할 키를 입력하세요... (${secondsLeft}초)", 1500, priority = 2)
                    secondsLeft--
                    mappingHandler.postDelayed(this, 1000)
                } else {
                    showStatusOverlay("[$tooltip] 매핑 시간 초과", 3000, priority = 3)
                    kr.disys.baedalin.KeyRecordingState.recordingFunction = null
                    startService(Intent(this@FloatingWidgetService, KeyMapperAccessibilityService::class.java).apply {
                        action = "ACTION_CANCEL_DIRECT_RECORDING"
                    })
                }
            }
        }

        kr.disys.baedalin.KeyRecordingState.recordingFunction = functionName
        startService(Intent(this, KeyMapperAccessibilityService::class.java).apply {
            action = "ACTION_START_DIRECT_RECORDING"
            putExtra("function_name", functionName)
        })
        mappingHandler.post(countdownRunnable)
    }

    private fun showSettingsWidget() {
        val functionName = "SYSTEM_SETTINGS"
        if (overlayViews.containsKey(functionName)) return

        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val alpha = prefs.getFloat("toolbar_transparency", 1.0f)
        val savedX = prefs.getInt("${functionName}_x", 800)
        val savedY = prefs.getInt("${functionName}_y", 200)

        if (!::toolbarManager.isInitialized) {
            toolbarManager = ToolbarManager(this, windowManager, object : ToolbarManager.ToolbarCallbacks {
                override fun onAddWidget() { addNumberedWidget(prefs) }
                override fun onToggleMoveMode() { toggleMoveMode() }
                override fun onTogglePresetsVisibility() { togglePresetsVisibility() }
                override fun onPowerOff() {
                    prefs.edit(commit = true) { putBoolean("is_mapping_enabled", false) }
                    hideAll()
                }
                override fun onOpenMainActivity() {
                    val intent = Intent(this@FloatingWidgetService, MainActivity::class.java).apply {
                        action = ACTION_UPDATE_UI
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    startActivity(intent)
                }
                override fun onLaunchApp(name: String) {
                    val pkg = Presets.getPackageName(name)
                    launchApp(pkg)
                    loadPresetInternal(name)
                    setPresetsVisibility(false)
                }
                override fun onFold(folded: Boolean) {
                    preferredFoldedState = folded
                    setToolbarFolded(folded)
                }
                override fun onSavePosition(x: Int, y: Int) {
                    prefs.edit { putInt("${functionName}_x", x); putInt("${functionName}_y", y) }
                }
            })
        }
        toolbarManager.showToolbar(savedX, savedY, alpha, isToolbarFolded)
        toolbarManager.root?.let {
            overlayViews[functionName] = it
            Log.d("KeyMapper", "showSettingsWidget: Toolbar added to overlayViews")
        } ?: Log.e("KeyMapper", "showSettingsWidget: Failed to get toolbar root view")
    }

    private fun hideWidget(functionName: String) {
        if (functionName == "SYSTEM_SETTINGS") {
            if (::toolbarManager.isInitialized) toolbarManager.hide()
            overlayViews.remove(functionName)
            return
        }
        overlayViews[functionName]?.let {
            windowManager.removeView(it)
            overlayViews.remove(functionName)
        }
    }

    private fun hidePresets() {
        val iterator = overlayViews.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != "SYSTEM_SETTINGS") {
                windowManager.removeView(entry.value)
                iterator.remove()
            }
        }
    }

    private fun hideAll() {
        overlayViews.values.forEach { windowManager.removeView(it) }
        overlayViews.clear()
        _isMappingEnabled.value = false
        _isInterceptionActive.value = false
        getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("is_mapping_enabled", false) }
        Log.d("KeyMapper", "Mapping DISABLED via hideAll")
        _isRunning.value = false
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        hideScreenBorder()
        statusManager.cleanup()
        overlayViews.values.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {}
        }
        overlayViews.clear()
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

        fun forceLockMode() {
            instance?.let { service ->
                if (_isMoveMode.value) {
                    Handler(Looper.getMainLooper()).post {
                        service.toggleMoveMode()
                    }
                }
            }
        }

        const val ACTION_SHOW_WIDGET = "ACTION_SHOW_WIDGET"
        const val ACTION_HIDE_WIDGET = "ACTION_HIDE_WIDGET"
        const val ACTION_HIDE_ALL = "ACTION_HIDE_ALL"
        const val ACTION_HIDE_PRESETS = "ACTION_HIDE_PRESETS"
        const val ACTION_LOAD_PRESET = "ACTION_LOAD_PRESET"
        const val ACTION_UPDATE_UI = "ACTION_UPDATE_UI"
        const val ACTION_UPDATE_TRANSPARENCY = "ACTION_UPDATE_TRANSPARENCY"
        const val ACTION_UPDATE_KEY = "ACTION_UPDATE_KEY"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"

        const val ACTION_START_SERVICE_ONLY = "ACTION_START_SERVICE_ONLY"
        const val ACTION_SET_TOOLBAR_VISIBILITY = "ACTION_SET_TOOLBAR_VISIBILITY"
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

    private fun showScreenBorder() {
        Log.d("KeyMapper", "showScreenBorder called")
        if (screenBorderView != null) return

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
            setBackgroundColor(Color.TRANSPARENT)
            val border = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(15, Color.YELLOW) // 15px 두께의 노란 테두리
                setColor(Color.TRANSPARENT)
            }
            foreground = border

            val textView = TextView(this.context).apply {
                text = "⚠️ 언락 모드 활성화 ⚠️\n\n위젯 2초 롱터치 → 핫키 매핑\n잠금 버튼을 누르면 종료됩니다."
                setTextColor(Color.YELLOW)
                textSize = 16f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                setPadding(40, 30, 40, 30)
                android.graphics.Typeface.DEFAULT_BOLD.also { typeface = it }
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
            }
            addView(textView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = 20
            })
        }
        screenBorderView = frameLayout

        try {
            windowManager.addView(screenBorderView, params)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to add screen border", e)
        }
    }

    private fun hideScreenBorder() {
        Log.d("KeyMapper", "hideScreenBorder called")
        screenBorderView?.let {
            try {
                if (it.parent != null) {
                    windowManager.removeViewImmediate(it)
                    Log.d("KeyMapper", "screenBorderView removed successfully")
                }
            } catch (e: Exception) {
                Log.e("KeyMapper", "Failed to remove screen border", e)
            } finally {
                screenBorderView = null
            }
        }
    }

    private fun showCustomToast(message: String) {
        // 모든 메시지를 중앙 알림창으로 표시 (시인성 확보)
        showStatusOverlay(message, 2000)
    }
}
