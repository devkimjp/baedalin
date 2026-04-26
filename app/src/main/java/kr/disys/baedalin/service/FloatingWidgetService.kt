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
import android.widget.TextView
import android.widget.Toast
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
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

    private var currentPreset: String = "DEFAULT"

    private var customWidgetCounter = 1
    private var lastAddedX = 200
    private var lastAddedY = 250
    private var isToolbarFolded = false
    private var preferredFoldedState = false
    private var isPresetsHidden = false
    private var screenBorderView: View? = null

    // 툴바 버튼 참조 저장용
    private var btnAddView: View? = null
    private var btnMoveView: View? = null
    private var btnHideView: View? = null
    private var btnCloseView: View? = null
    private var btnSaveView: View? = null
    private var btnBaeminView: View? = null
    private var btnCoupangView: View? = null
    private var btnFoldView: ImageView? = null

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
        
        // 툴바의 눈 아이콘 상태 동기화
        (btnHideView as? ImageView)?.let { 
            it.setImageResource(if (isPresetsHidden) R.drawable.ic_toolbar_hide_off else R.drawable.ic_toolbar_hide)
        }
    }

    private fun setToolbarFolded(folded: Boolean) {
        isToolbarFolded = folded
        val visibility = if (isToolbarFolded) View.GONE else View.VISIBLE
        btnAddView?.visibility = visibility
        btnMoveView?.visibility = visibility
        btnHideView?.visibility = visibility
        btnCloseView?.visibility = visibility
        btnSaveView?.visibility = visibility
        btnBaeminView?.visibility = visibility
        btnCoupangView?.visibility = visibility
        
        val iconRes = if (isToolbarFolded) R.drawable.ic_toolbar_unfold else R.drawable.ic_toolbar_fold
        btnFoldView?.setImageResource(iconRes)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("KeyMapper", "FloatingWidgetService.onStartCommand: action=$action")
        
        when (action) {
            ACTION_SHOW_WIDGET -> {
                val newPreset = intent.getStringExtra("preset_name")
                if (newPreset != null) {
                    currentPreset = newPreset
                }
                showSettingsWidget()
                loadStoredCustomWidgets() // 저장된 커스텀 위젯들도 복구
            }
            ACTION_HIDE_ALL -> hideAll()
            ACTION_HIDE_PRESETS -> {
                setPresetsVisibility(true)
                setToolbarFolded(true)
            }
            ACTION_HIDE_WIDGET -> {
                val name = intent.getStringExtra("function_name")
                if (name != null) hideWidget(name)
            }
            ACTION_LOAD_PRESET -> {
                val preset = intent.getStringExtra("preset_name") ?: "BAEMIN"
                Log.d("KeyMapper", "!!! RECEIVED ACTION_LOAD_PRESET for $preset !!!")
                loadPresetInternal(preset)
                showSettingsWidget() // 프리셋 로드 시 툴바도 함께 표시
                _isMappingEnabled.value = true
                _isInterceptionActive.value = true // 매핑 인터셉트 활성화
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("is_mapping_enabled", true) }
                Log.d("KeyMapper", "is_mapping_enabled successfully set to TRUE")
            }
            ACTION_UPDATE_TRANSPARENCY -> {
                val alpha = intent.getFloatExtra("transparency", 1.0f)
                overlayViews["SYSTEM_SETTINGS"]?.let { root ->
                    val toolbar = (root as? FrameLayout)?.getChildAt(0)
                    toolbar?.alpha = alpha
                }
            }
            ACTION_START_SERVICE_ONLY -> {
                Log.d("KeyMapper", "Starting service: Toolbar only")
                showSettingsWidget()
                _isMappingEnabled.value = true
                _isInterceptionActive.value = false // 초기에는 좌표 위젯 숨김
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { 
                    putBoolean("is_mapping_enabled", true) 
                }
            }
            ACTION_SET_TOOLBAR_VISIBILITY -> {
                val visible = intent.getBooleanExtra("visible", true)
                if (visible) showSettingsWidget() else hideWidget("SYSTEM_SETTINGS")
            }
        }
        return START_NOT_STICKY
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

    // 툴바 아이콘의 활성/비활성 상태(색상, 투명도)를 실시간 업데이트
    fun updateToolbarState() {
        Handler(Looper.getMainLooper()).post {
            val currentApp = KeyMapperAccessibilityService.currentPackageName
            val preset = Presets.getPresetFromPackage(currentApp)
            val isTargetApp = preset != null || 
                             currentApp.contains("woowahan") || 
                             currentApp.contains("coupang") || 
                             currentApp == "kr.disys.baedalin"

            btnMoveView?.let { v ->
                val iv = v as ImageView
                // 자물쇠 버튼은 항상 활성 상태로 표시
                iv.alpha = 1.0f
                iv.colorFilter = null
                iv.setImageResource(if (_isMoveMode.value) R.drawable.ic_toolbar_unlock_v7 else R.drawable.ic_toolbar_lock_v7)

                if (!isTargetApp) {
                    // 배달 앱 이탈 시 툴바를 자동으로 접음
                    if (!isToolbarFolded) {
                        setToolbarFolded(true)
                    }
                } else {
                    // 배달 앱 진입 시 이전 사용자 설정 상태로 복구
                    if (isToolbarFolded != preferredFoldedState) {
                        setToolbarFolded(preferredFoldedState)
                    }
                }
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

        val root = FrameLayout(this).apply {
            setPadding(10, 10, 10, 10)
        }

        val toolbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(8, 8, 8, 8)
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 60f
                setColor(Color.WHITE)
                setStroke(1, 0xFFDDDDDD.toInt())
            }
            background = shape
            elevation = 8f
            
            // 저장된 투명도 적용
            val alpha = prefs.getFloat("toolbar_transparency", 1.0f)
            this.alpha = alpha
        }

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

        root.setOnTouchListener(unifiedTouchListener)
        toolbarContainer.setOnTouchListener(unifiedTouchListener)

        val createToolbarIcon = { resId: Int, onClick: (View) -> Unit ->
            ImageView(this).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val size = 100 
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, 0, 0) // 간격을 좁히기 위해 마진 제거
                }
                setPadding(15, 15, 15, 15) // 패딩 추가
                
                setOnClickListener { v -> onClick(v) }
                setOnTouchListener(unifiedTouchListener)
            }
        }

        val btnAdd = createToolbarIcon(R.drawable.ic_toolbar_add) { _ -> addNumberedWidget(prefs) }
        val btnMove = createToolbarIcon(R.drawable.ic_toolbar_lock_v7) { v -> 
            toggleMoveMode() 
        }
        btnMoveView = btnMove
        val btnHide = createToolbarIcon(R.drawable.ic_toolbar_hide) { v -> 
            togglePresetsVisibility() 
            (v as ImageView).setImageResource(if (isPresetsHidden) R.drawable.ic_toolbar_hide_off else R.drawable.ic_toolbar_hide)
        }
        val btnClose = createToolbarIcon(R.drawable.ic_toolbar_power) { _ -> 
            Log.d("KeyMapper", "Toolbar Power Button clicked: Stopping service and updating Prefs")
            getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { 
                putBoolean("is_mapping_enabled", false) 
            }
            hideAll() 
        }
        val btnSave = createToolbarIcon(R.drawable.ic_toolbar_save) { _ -> 
            val intentAction = Intent(this@FloatingWidgetService, MainActivity::class.java).apply {
                action = ACTION_UPDATE_UI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intentAction)
            Toast.makeText(this@FloatingWidgetService, "변경사항이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        }
        val btnBaemin = createToolbarIcon(R.drawable.ic_toolbar_baemin) { _ -> 
            val pkg = Presets.getPackageName("BAEMIN")
            launchApp(pkg)
            loadPresetInternal("BAEMIN")
            setPresetsVisibility(false) 
            Toast.makeText(this@FloatingWidgetService, "배민 실행 및 프리셋 활성화", Toast.LENGTH_SHORT).show()
        }
        val btnCoupang = createToolbarIcon(R.drawable.ic_toolbar_coupang) { _ -> 
            val pkg = Presets.getPackageName("COUPANG")
            launchApp(pkg)
            loadPresetInternal("COUPANG")
            setPresetsVisibility(false) 
            Toast.makeText(this@FloatingWidgetService, "쿠팡 실행 및 프리셋 활성화", Toast.LENGTH_SHORT).show()
        }
        val btnFold = createToolbarIcon(R.drawable.ic_toolbar_fold) { v -> 
            val newState = !isToolbarFolded
            preferredFoldedState = newState // 사용자가 직접 변경한 상태를 기억
            setToolbarFolded(newState)
        }

        this.btnAddView = btnAdd
        this.btnMoveView = btnMove
        this.btnHideView = btnHide
        this.btnCloseView = btnClose
        this.btnSaveView = btnSave
        this.btnBaeminView = btnBaemin
        this.btnCoupangView = btnCoupang
        this.btnFoldView = btnFold as ImageView

        // 요청하신 순서대로 아이콘 배치 (접기, 잠금, 배민, 쿠팡, 종료)
        toolbarContainer.addView(btnFold)
        toolbarContainer.addView(btnMove)
        toolbarContainer.addView(btnBaemin)
        toolbarContainer.addView(btnCoupang)
        toolbarContainer.addView(btnClose)

        root.addView(toolbarContainer)
        windowManager.addView(root, params)
        overlayViews[functionName] = root
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

        // 이동 모드 시 나타날 드래그 핸들 추가
        val dragHandle = View(this).apply {
            id = R.id.drag_handle
            layoutParams = LinearLayout.LayoutParams(ICON_SIZE / 2, 10).apply {
                setMargins(0, 5, 0, 0)
            }
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

        container.setOnTouchListener(object : View.OnTouchListener {
            private var dragInitialX = 0f
            private var dragInitialY = 0f
            private var dragOffsetX = 0f
            private var dragOffsetY = 0f
            private var longClickHandled = false
            private var moveModeActiveForThis = false
            private val longPressHandler = Handler(Looper.getMainLooper())

            private val moveModeRunnable = Runnable {
                moveModeActiveForThis = true
                showCustomToast("${tooltip} 이동 모드 활성화")
                dragHandle.visibility = View.VISIBLE
                
                // 이동 모드 시 진동 피드백 (선택 사항)
                container.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }

            private val mappingModeRunnable = Runnable {
                longClickHandled = true
                moveModeActiveForThis = false
                // 언락 모드가 아닐 때만 핸들을 숨김
                if (!_isMoveMode.value) {
                    dragHandle.visibility = View.GONE
                }
                
                showCustomToast("매핑할 키를 입력하세요...")
                container.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                
                val intent = Intent("ACTION_START_DIRECT_RECORDING").apply {
                    setPackage(packageName)
                    putExtra("function_name", functionName)
                }
                sendBroadcast(intent)
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = container.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longClickHandled = false
                        // 현재 전역 이동 모드 상태를 따름 (언락 모드면 즉시 이동 가능)
                        moveModeActiveForThis = _isMoveMode.value
                        
                        dragInitialX = event.rawX
                        dragInitialY = event.rawY
                        dragOffsetX = event.rawX - p.x
                        dragOffsetY = event.rawY - p.y
                        
                        if (!moveModeActiveForThis) {
                            longPressHandler.postDelayed(moveModeRunnable, 1500)
                        }
                        longPressHandler.postDelayed(mappingModeRunnable, 3000)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.rawX - dragInitialX)
                        val dy = Math.abs(event.rawY - dragInitialY)
                        
                        if (dx > 15 || dy > 15) {
                            if (moveModeActiveForThis) {
                                p.x = (event.rawX - dragOffsetX).toInt()
                                p.y = (event.rawY - dragOffsetY).toInt()
                                windowManager.updateViewLayout(container, p)
                                longClickHandled = true // 이동 중에는 매핑 모드 진입 방지
                                longPressHandler.removeCallbacks(mappingModeRunnable)
                            } else {
                                // 이동 모드 전인데 움직임이 크면 롱프레스 취소
                                longPressHandler.removeCallbacks(moveModeRunnable)
                                longPressHandler.removeCallbacks(mappingModeRunnable)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressHandler.removeCallbacks(moveModeRunnable)
                        longPressHandler.removeCallbacks(mappingModeRunnable)
                        
                        if (moveModeActiveForThis && longClickHandled) {
                            // 이동 완료: 좌표 저장
                            prefs.edit { putInt(prefKeyX, p.x); putInt(prefKeyY, p.y) }
                            showCustomToast("위치 저장 완료")
                            // 언락 모드가 아닐 때만(즉, 1회성 이동일 때만) 핸들을 숨김
                            if (!_isMoveMode.value) {
                                dragHandle.visibility = View.GONE
                            }
                        } else if (!longClickHandled) {
                            if (_isMoveMode.value) {
                                // 언락 모드 중 싱글탭 → 즉시 키 매핑 시작 + 5초 카운트다운
                                Log.d("KeyMapper", "Unlock mode single tap -> Starting key mapping for $functionName")
                                longClickHandled = true
                                // 언락 모드이므로 핸들을 숨기지 않음
                                container.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

                                val mappingHandler = Handler(Looper.getMainLooper())
                                var secondsLeft = 5

                                // 카운트다운 실행
                                val countdownRunnable = object : Runnable {
                                    override fun run() {
                                        if (secondsLeft > 0) {
                                            showCustomToast("[$tooltip] 매핑할 키를 입력하세요... (${secondsLeft}초)")
                                            secondsLeft--
                                            mappingHandler.postDelayed(this, 1000)
                                        } else {
                                            // 5초 경과 → 매핑 취소
                                            showCustomToast("[$tooltip] 매핑 시간 초과 (취소됨)")
                                            val cancelIntent = Intent("ACTION_CANCEL_DIRECT_RECORDING").apply {
                                                setPackage(packageName)
                                            }
                                            sendBroadcast(cancelIntent)
                                        }
                                    }
                                }
                                mappingHandler.post(countdownRunnable)

                                val startIntent = Intent("ACTION_START_DIRECT_RECORDING").apply {
                                    setPackage(packageName)
                                    putExtra("function_name", functionName)
                                }
                                sendBroadcast(startIntent)
                            } else {
                                // 잠금 모드 중 싱글탭 → 기존 클릭 이벤트 전달
                                v.performClick()
                                val intent = Intent("ACTION_MANUAL_CLICK").apply {
                                    setPackage(packageName)
                                    putExtra("function_name", functionName)
                                }
                                sendBroadcast(intent)
                            }
                        }
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
        const val ACTION_UPDATE_KEY = "ACTION_UPDATE_KEY"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_UPDATE_UI = "ACTION_UPDATE_UI"
        const val ACTION_UPDATE_TRANSPARENCY = "ACTION_UPDATE_TRANSPARENCY"
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
                text = "⚠️ 언락 모드 활성화 ⚠️\n\n위젯 1회 탭 → 즉시 키 매핑\n잠금 버튼을 누르면 종료됩니다."
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
                Gravity.CENTER
            ))
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
        val toastView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackground(GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000")) // 80% 투명 검정
                cornerRadius = 50f
            })
            setPadding(40, 20, 40, 20)
            gravity = Gravity.CENTER
            textSize = 14f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200 // 하단에서 200px 위
        }

        try {
            windowManager.addView(toastView, params)
            // 2초 후 제거
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager.removeView(toastView)
                } catch (e: Exception) {}
            }, 2000)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to show custom toast", e)
        }
    }
}
