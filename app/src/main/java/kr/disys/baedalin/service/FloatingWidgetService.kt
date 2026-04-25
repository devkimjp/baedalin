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

    // ?바 버튼 참조 ??용
    private var btnAddView: View? = null
    private var btnMoveView: View? = null
    private var btnHideView: View? = null
    private var btnCloseView: View? = null
    private var btnSaveView: View? = null
    private var btnBaeminView: View? = null
    private var btnCoupangView: View? = null
    private var btnSnapshotView: View? = null
    private var btnFoldView: ImageView? = null

    private fun addNumberedWidget(prefs: android.content.SharedPreferences) {
        val preset = currentPreset
        val counterKey = "${preset}_custom_counter"
        val counter = prefs.getInt(counterKey, 1)
        val label = counter.toString()
        val functionName = "${preset}_CUSTOM_$label"
        val color = Presets.getColor(preset)
        
        // 1. ?젯 ?시
        showWidget(
            functionName = functionName,
            icon = label,
            tooltip = "?용???젯 $label",
            targetX = lastAddedX,
            targetY = lastAddedY,
            color = color
        )
        
        // 2. ?이?????(모드?분리)
        val listKey = "${preset}_active_custom_widgets"
        val currentWidgets = prefs.getString(listKey, "") ?: ""
        val newList = if (currentWidgets.isEmpty()) label else "$currentWidgets,$label"
        
        prefs.edit { 
            putString(listKey, newList)
            putInt(counterKey, counter + 1)
            putInt("${preset}_last_added_x", lastAddedX + 60)
            putInt("${preset}_last_added_y", lastAddedY + 60)
        }

        // 3. 좌표 ?카운??갱신 (로컬 ?태???재 모드??맞춰 ?기??
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
                        tooltip = "?용???젯 $label",
                        targetX = -1, // ??된 좌표 ?용
                        targetY = -1,
                        color = color
                    )
                }
            }
        }
        
        // 모드?카운???마??좌표 복구
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
        
        // ?리?이 ?겨지???매핑 간섭??중?
        _isInterceptionActive.value = !isPresetsHidden
        
        // ?바?????이??태 ?기??
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
        btnSnapshotView?.visibility = visibility
        
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
                loadStoredCustomWidgets() // ??된 커스? ?젯?도 복구
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
                showSettingsWidget() // ?리??로드 ???바???께 ?시
                _isMappingEnabled.value = true
                _isInterceptionActive.value = true // 매핑 ?터?트 ?성??
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("is_mapping_enabled", true) }
                Log.d("KeyMapper", "is_mapping_enabled successfully set to TRUE")
            }
            "ACTION_SNAPSHOT_COMPLETE" -> {
                val success = intent.getBooleanExtra("success", false)
                val path = intent.getStringExtra("path")
                if (success) {
                    Toast.makeText(this, "스냅샷 저장 완료: $path", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "스냅샷 저장 실패", Toast.LENGTH_SHORT).show()
                }
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
                _isInterceptionActive.value = false // 초기?는 좌표 ?젯 ??
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) { 
                    putBoolean("is_mapping_enabled", true) 
                }
            }
            ACTION_FLASH_WIDGET -> {
                val functionName = intent.getStringExtra("function_name")
                val x = intent.getFloatExtra("x", -1f)
                val y = intent.getFloatExtra("y", -1f)
                if (functionName != null && x != -1f && y != -1f) {
                    flashWidget(functionName, x.toInt(), y.toInt())
                }
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
        
        // ?리??로드 ????/?힘 ?태 ?제
        setPresetsVisibility(false)
        setToolbarFolded(false)

        // 위젯을 상시로 보여주지 않기 위해 showWidget 호출을 주석 처리하거나 제거합니다.
        /*
        val prefix = prefs.getString("selected_device_descriptor", "GLOBAL") ?: "GLOBAL"
        presetList.forEach { info ->
            val keycode = prefs.getInt("${prefix}_${info.function.name}_keycode", -1)
            val keyInfo = if (keycode != -1) {
                val keyName = KeyEvent.keyCodeToString(keycode).replace("KEYCODE_", "")
                "$keycode ($keyName)"
            } else null
            
            showWidget(info.function.name, info.icon, info.tooltip, info.x, info.y, color, keyInfo)
        }
        */

        // ???리?의 커스? ?젯??로드
        // loadStoredCustomWidgets()
    }

    private fun toggleMoveMode() {
        val currentMode = _isMoveMode.value
        _isMoveMode.value = !currentMode
        
        val toastMsg = if (_isMoveMode.value) {
            showScreenBorder()
            "?동 모드 ?성??(?젯???? ???습?다)"
        } else {
            hideScreenBorder()
            "?금 모드 ?성??(?젯 ?치 고정)"
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
        
        updateToolbarState() // ?태 변????이?갱신
    }

    // ?바 ?이콘의 ?성/비활???태(?상, ?명????시??데?트
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
                // ?물??버튼? ?? ?성 ?태??시
                iv.alpha = 1.0f
                iv.colorFilter = null
                iv.setImageResource(if (_isMoveMode.value) R.drawable.ic_toolbar_unlock_v7 else R.drawable.ic_toolbar_lock_v7)

                if (!isTargetApp) {
                    // 배달 ???탈 ???바??동?로 ?음
                    if (!isToolbarFolded) {
                        setToolbarFolded(true)
                    }
                } else {
                    // 배달 ??진입 ???전 ?용???정 ?태?복구
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
            
            // ??된 ?명???용
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
                    setMargins(0, 0, 0, 0) // 간격??좁히??해 마진 ?거
                }
                setPadding(15, 15, 15, 15) // ?딩 추?
                
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
            Toast.makeText(this@FloatingWidgetService, "변경사?? ??되?습?다.", Toast.LENGTH_SHORT).show()
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

        val btnSnapshot = createToolbarIcon(R.drawable.ic_toolbar_snapshot) { _ -> 
            takeUISnapshot()
        }
        btnSnapshotView = btnSnapshot
        val btnFold = createToolbarIcon(R.drawable.ic_toolbar_fold) { v -> 
            val newState = !isToolbarFolded
            preferredFoldedState = newState // ?용?? 직접 변경한 ?태?기억
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

        // ?청?신 ?서???이?배치 (?기, ?금, 배?, 쿠팡, 종료)
        toolbarContainer.addView(btnFold)
        toolbarContainer.addView(btnMove)
        toolbarContainer.addView(btnBaemin)
        toolbarContainer.addView(btnCoupang)
        toolbarContainer.addView(btnSnapshot)
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

        // ??된 좌표가 ?으?불러?고, ?으??리??좌표 ?용
        val savedX = prefs.getInt(prefKeyX, -1)
        val savedY = prefs.getInt(prefKeyY, -1)

        if (savedX != -1 && savedY != -1) {
            params.x = savedX
            params.y = savedY
        } else if (targetX != -1 && targetY != -1) {
            params.x = targetX - offsetX
            params.y = targetY - offsetY
            // 처음 ?시????기본 좌표????
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

        // ?동 모드 ???????래??들 추?
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
            private var lastClickTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (!_isMoveMode.value) return false
                val p = container.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClickTime < 300) {
                            // ?블 ?릭 감? -> MainActivity ?행 (?코??모드)
                            val intent = Intent(this@FloatingWidgetService, MainActivity::class.java).apply {
                                action = ACTION_START_RECORDING
                                putExtra("function_name", functionName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            startActivity(intent)
                        }
                        lastClickTime = currentTime

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
        const val ACTION_FLASH_WIDGET = "ACTION_FLASH_WIDGET"
    }


    private fun launchApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("KeyMapper", "Failed to launch app: $packageName", e)
                Toast.makeText(this, "?을 ?행?????습?다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "?이 ?치?어 ?? ?습?다: $packageName", Toast.LENGTH_SHORT).show()
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

        screenBorderView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(10, Color.RED) // 10px ?께??빨간 ?두?
                setColor(Color.TRANSPARENT) // 배경? ?명
            }
        }

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
                setColor(Color.parseColor("#CC000000")) // 80% ?명 검??
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
            y = 200 // ?단?서 200px ??
        }

        try {
            windowManager.addView(toastView, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager.removeViewImmediate(toastView)
                } catch (e: Exception) {}
            }, 2000)
        } catch (e: Exception) {}
    }

    private fun takeUISnapshot() {
        val intent = Intent(this, KeyMapperAccessibilityService::class.java).apply {
            action = "ACTION_UI_SNAPSHOT"
        }
        startService(intent)
        Toast.makeText(this, "현재 화면 분석 중...", Toast.LENGTH_SHORT).show()
    }

    private fun flashWidget(functionName: String, x: Int, y: Int) {
        val presetList = when(currentPreset) {
            "BAEMIN" -> Presets.BAEMIN
            "COUPANG" -> Presets.COUPANG
            "YOGIYO" -> Presets.YOGIYO
            else -> Presets.BAEMIN
        }
        
        val info = presetList.find { it.function.name == functionName } ?: return
        val color = Presets.getColor(currentPreset)
        
        // 위젯 표시
        showWidget(functionName, info.icon, info.tooltip, x, y, color)
        
        // 1초 후 제거
        Handler(Looper.getMainLooper()).postDelayed({
            hideWidget(functionName)
        }, 1000)
    }
}
