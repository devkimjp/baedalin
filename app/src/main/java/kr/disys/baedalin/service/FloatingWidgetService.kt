package kr.disys.baedalin.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import kr.disys.baedalin.MainActivity
import kr.disys.baedalin.R
import kr.disys.baedalin.model.Presets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private val overlayViews = mutableMapOf<String, View>()
    private val ICON_SIZE = 100 
    private var settingsParams: WindowManager.LayoutParams? = null

    private var isMoveMode: Boolean = false
    private var currentPreset: String = "DEFAULT"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            ACTION_SHOW_WIDGET -> {
                val newPreset = intent.getStringExtra("preset_name")
                if (newPreset != null) {
                    currentPreset = newPreset
                    getSharedPreferences("mappings", Context.MODE_PRIVATE).edit {
                        putString("active_preset", currentPreset)
                    }
                }

                val functionName = intent.getStringExtra("function_name")
                val icon = intent.getStringExtra("icon") ?: "?"
                val tooltip = intent.getStringExtra("tooltip") ?: ""
                val targetX = intent.getIntExtra("x", -1)
                val targetY = intent.getIntExtra("y", -1)
                val color = intent.getIntExtra("color", 0xAAFF0000.toInt())
                val isSettings = intent.getBooleanExtra("is_settings", false)

                if (isSettings) {
                    showSettingsWidget()
                } else if (functionName != null) {
                    showWidget(functionName, icon, tooltip, targetX, targetY, color)
                }
            }
            ACTION_LOAD_PRESET -> {
                val presetName = intent.getStringExtra("preset_name")
                if (presetName != null) {
                    loadPresetInternal(presetName)
                }
            }
            ACTION_HIDE_WIDGET -> {
                val functionName = intent?.getStringExtra("function_name")
                if (functionName != null) hideWidget(functionName)
            }
            ACTION_HIDE_ALL -> hideAll()
            ACTION_HIDE_PRESETS -> hidePresets()
        }
        
        return START_NOT_STICKY
    }

    private fun loadPresetInternal(presetName: String) {
        if (currentPreset == presetName && overlayViews.size > 1) {
            // 이미 해당 프리셋이 활성화되어 있고 위젯들이 표시 중이면 로드 생략
            return
        }
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

        presetList.forEach { info ->
            showWidget(info.function.name, info.icon, info.tooltip, info.x, info.y, color)
        }
    }

    private fun toggleMoveMode() {
        isMoveMode = !isMoveMode
        val toastMsg = if (isMoveMode) "이동 모드 활성화 (위젯을 드래그하여 배치하세요)" else "LOCK 모드 활성화 (터치 투과)"
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()

        // 전체 위젯의 터치 플래그 갱신 (설정 위젯은 제외)
        overlayViews.forEach { (name, view) ->
            if (name != "SYSTEM_SETTINGS") {
                val params = view.layoutParams as WindowManager.LayoutParams
                if (isMoveMode) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                windowManager.updateViewLayout(view, params)
            }
        }
    }

    private fun showSettingsWidget() {
        val functionName = "SYSTEM_SETTINGS"
        if (overlayViews.containsKey(functionName)) return

        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            x = prefs.getInt("${functionName}_x", 800)
            y = prefs.getInt("${functionName}_y", 200)
        }
        settingsParams = params

        // 외부 컨테이너 (그림자 효과를 위해 패딩 포함)
        val root = FrameLayout(this).apply {
            setPadding(10, 10, 10, 10)
        }

        // 세로형 툴바 배경 (캡슐 모양)
        val toolbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(5, 15, 5, 15)
            val shadowColor = 0x22000000.toInt()
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 60f
                setColor(Color.WHITE)
                // 얇은 테두리로 선명도 확보
                setStroke(1, 0xFFDDDDDD.toInt())
            }
            background = shape
            // Elevations / Shadows (API 21+)
            elevation = 8f
        }

        val triggerPreset = { presetName: String ->
            // 1. 위젯 프리셋 즉시 갱신
            loadPresetInternal(presetName)

            // 2. 해당 배달 앱 실행
            val packageName = prefs.getString("${presetName}_custom_pkg", Presets.getPackageName(presetName)) ?: ""
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "앱을 찾을 수 없습니다: $packageName", Toast.LENGTH_SHORT).show()
            }
        }

        // 통합 터치 리스너 정의 (드래그와 클릭 유연하게 지원)
        var initialTouchX = 0f
        var initialTouchY = 0f
        var offsetX = 0f
        var offsetY = 0f
        var isMoved = false

        val unifiedTouchListener = object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = root.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        offsetX = event.rawX - p.x
                        offsetY = event.rawY - p.y
                        isMoved = false
                        return true 
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            p.x = (event.rawX - offsetX).toInt()
                            p.y = (event.rawY - offsetY).toInt()
                            windowManager.updateViewLayout(root, p)
                            isMoved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isMoved) {
                            settingsParams?.x = p.x
                            settingsParams?.y = p.y
                            prefs.edit { 
                                putInt("${functionName}_x", p.x)
                                putInt("${functionName}_y", p.y)
                            }
                        } else {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        }

        // 모든 구성 요소에 통합 터치 리스너 적용
        root.setOnTouchListener(unifiedTouchListener)
        toolbarContainer.setOnTouchListener(unifiedTouchListener)

        val createToolbarIcon = { resId: Int, onClick: () -> Unit ->
            ImageView(this).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val size = 110
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 10, 0, 10)
                }
                setPadding(25, 25, 25, 25)
                setOnClickListener { onClick() }
                setOnTouchListener(unifiedTouchListener)
            }
        }

        // 아이콘 생성 및 툴바 추가
        val btnSettings = createToolbarIcon(R.drawable.ic_simple_settings) { 
            Toast.makeText(this, "설정 메뉴", Toast.LENGTH_SHORT).show()
        }
        val btnBaemin = createToolbarIcon(R.drawable.ic_simple_baemin) { triggerPreset("BAEMIN") }
        val btnCoupang = createToolbarIcon(R.drawable.ic_simple_coupang) { triggerPreset("COUPANG") }
        val btnLock = createToolbarIcon(R.drawable.ic_simple_lock) { toggleMoveMode() }
        val btnClose = createToolbarIcon(R.drawable.ic_simple_close) { hideAll() }

        toolbarContainer.addView(btnSettings)
        toolbarContainer.addView(btnBaemin)
        toolbarContainer.addView(btnCoupang)
        toolbarContainer.addView(btnLock)
        toolbarContainer.addView(btnClose)

        root.addView(toolbarContainer)

        windowManager.addView(root, params)
        overlayViews[functionName] = root
    }

    private fun showWidget(functionName: String, icon: String, tooltip: String, targetX: Int, targetY: Int, color: Int) {
        hideWidget(functionName)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (isMoveMode) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
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

        val circleView = TextView(this).apply {
            text = icon
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(2, Color.WHITE)
            }
            background = shape
            layoutParams = LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE)
        }
        container.addView(circleView)

        container.setOnTouchListener(object : View.OnTouchListener {
            private var dragInitialX = 0f
            private var dragInitialY = 0f
            private var dragOffsetX = 0f
            private var dragOffsetY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (!isMoveMode) return false
                val p = container.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragInitialX = event.rawX
                        dragInitialY = event.rawY
                        dragOffsetX = event.rawX - p.x
                        dragOffsetY = event.rawY - p.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(event.rawX - dragInitialX) > 10 || Math.abs(event.rawY - dragInitialY) > 10) {
                            p.x = (event.rawX - dragOffsetX).toInt()
                            p.y = (event.rawY - dragOffsetY).toInt()
                            windowManager.updateViewLayout(container, p)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit { putInt(prefKeyX, p.x); putInt(prefKeyY, p.y) }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(container, params)
        overlayViews[functionName] = container
    }

    private fun hideWidget(functionName: String) {
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
        _isRunning.value = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        const val ACTION_SHOW_WIDGET = "ACTION_SHOW_WIDGET"
        const val ACTION_HIDE_WIDGET = "ACTION_HIDE_WIDGET"
        const val ACTION_HIDE_ALL = "ACTION_HIDE_ALL"
        const val ACTION_HIDE_PRESETS = "ACTION_HIDE_PRESETS"
        const val ACTION_LOAD_PRESET = "ACTION_LOAD_PRESET"
        const val ACTION_UPDATE_KEY = "ACTION_UPDATE_KEY"
    }
}
