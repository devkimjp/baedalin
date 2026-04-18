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
import android.widget.LinearLayout
import android.widget.TextView
import kr.disys.baedalin.MainActivity

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private val overlayViews = mutableMapOf<String, View>()
    private val ICON_SIZE = 100 
    private var settingsParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val functionName = intent?.getStringExtra("function_name")
        val icon = intent?.getStringExtra("icon") ?: "?"
        val tooltip = intent?.getStringExtra("tooltip") ?: ""
        val targetX = intent?.getIntExtra("x", -1) ?: -1
        val targetY = intent?.getIntExtra("y", -1) ?: -1
        val color = intent?.getIntExtra("color", 0xAAFF0000.toInt()) ?: 0xAAFF0000.toInt()
        val isSettings = intent?.getBooleanExtra("is_settings", false) ?: false

        when (action) {
            ACTION_SHOW_WIDGET -> {
                if (isSettings) {
                    showSettingsWidget()
                } else if (functionName != null) {
                    showWidget(functionName, icon, tooltip, targetX, targetY, color)
                }
            }
            ACTION_HIDE_WIDGET -> {
                if (functionName != null) hideWidget(functionName)
            }
            ACTION_HIDE_ALL -> hideAll()
            ACTION_HIDE_PRESETS -> hidePresets()
        }
        
        return START_NOT_STICKY
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

        val root = FrameLayout(this)
        val settingsIcon = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC333333.toInt())
            }
            background = shape
            layoutParams = FrameLayout.LayoutParams(120, 120)
        }

        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE222222.toInt())
            setPadding(20, 20, 20, 20)
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 125
            layoutParams = lp
        }

        // 프리셋 선택 시 MainActivity를 호출하여 앱 실행 및 위젯 갱신 트리거
        val triggerPreset = { presetName: String ->
            menuLayout.visibility = View.GONE
            windowManager.updateViewLayout(root, settingsParams!!)
            
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("load_preset", presetName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }

        val btnBaemin = Button(this).apply { text = "배민"; setOnClickListener { triggerPreset("BAEMIN") } }
        val btnCoupang = Button(this).apply { text = "쿠팡"; setOnClickListener { triggerPreset("COUPANG") } }
        val btnYogiyo = Button(this).apply { text = "요기요"; setOnClickListener { triggerPreset("YOGIYO") } }
        val btnClose = Button(this).apply { 
            text = "전체 종료"
            setBackgroundColor(Color.RED)
            setOnClickListener { hideAll() } 
        }

        menuLayout.addView(btnBaemin)
        menuLayout.addView(btnCoupang)
        menuLayout.addView(btnYogiyo)
        menuLayout.addView(btnClose)

        root.addView(menuLayout)
        root.addView(settingsIcon)

        settingsIcon.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = settingsParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            p.x = initialX + dx
                            p.y = initialY + dy
                            windowManager.updateViewLayout(root, p)
                            isMoved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            menuLayout.visibility = if (menuLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            windowManager.updateViewLayout(root, p)
                        } else {
                            prefs.edit().putInt("${functionName}_x", p.x).putInt("${functionName}_y", p.y).apply()
                        }
                        return true
                    }
                }
                return false
            }
        })
        
        windowManager.addView(root, params)
        overlayViews[functionName] = root
    }

    private fun showWidget(functionName: String, icon: String, tooltip: String, targetX: Int, targetY: Int, color: Int) {
        hideWidget(functionName)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }
        
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        // SharedPreferences 키를 currentPresetName 대신 모델에서 직접 가져오지 않으므로 functionName만 쓰거나 컨텍스트 전달 필요
        // 여기서는 단순화를 위해 functionName을 사용하며, 실제 운영시엔 앱별 접두어를 붙이는 것이 좋습니다.
        
        val offsetX = ICON_SIZE / 2
        val offsetY = ICON_SIZE / 2 + 40

        if (targetX != -1 && targetY != -1) {
            params.x = targetX - offsetX
            params.y = targetY - offsetY
        } else {
            params.x = prefs.getInt("${functionName}_x", 100)
            params.y = prefs.getInt("${functionName}_y", 100)
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
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = container.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initialX + (event.rawX - initialTouchX).toInt()
                        p.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, p)
                        prefs.edit().putInt("${functionName}_x", p.x).putInt("${functionName}_y", p.y).apply()
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
        stopSelf()
    }

    companion object {
        const val ACTION_SHOW_WIDGET = "ACTION_SHOW_WIDGET"
        const val ACTION_HIDE_WIDGET = "ACTION_HIDE_WIDGET"
        const val ACTION_HIDE_ALL = "ACTION_HIDE_ALL"
        const val ACTION_HIDE_PRESETS = "ACTION_HIDE_PRESETS"
    }
}
