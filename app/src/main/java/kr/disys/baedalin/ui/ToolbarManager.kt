package kr.disys.baedalin.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kr.disys.baedalin.R
import kr.disys.baedalin.util.OverlayFactory

class ToolbarManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val callbacks: ToolbarCallbacks,
    private val onOpenSettings: () -> Unit,
    private val isNightMode: () -> Boolean
) {
    interface ToolbarCallbacks {
        fun onAddWidget()
        fun onToggleMoveMode()
        fun onTogglePresetsVisibility()
        fun onPowerOff()
        fun onOpenMainActivity()
        fun onLaunchApp(name: String)
        fun onFold(folded: Boolean)
        fun onSavePosition(x: Int, y: Int)
    }

    var root: View? = null
        private set
    private var isFolded = false
    
    private var btnFoldView: ImageView? = null
    private var btnMoveView: ImageView? = null
    private lateinit var settingsIcon: ImageView
    var currentParams: WindowManager.LayoutParams? = null
        private set

    fun showToolbar(initialX: Int, initialY: Int, alpha: Float, folded: Boolean) {
        if (root != null) return

        isFolded = folded
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = initialX
            y = initialY
            windowAnimations = 0
        }
        currentParams = params

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(12, 12, 12, 12)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 75f
                val night = isNightMode()
                if (night) {
                    setColor(Color.parseColor("#F21E293B")) 
                    setStroke(4, Color.parseColor("#6366F1"))
                } else {
                    setColor(Color.parseColor("#F2FFFFFF"))
                    setStroke(4, Color.parseColor("#CBD5E1"))
                }
            }
            elevation = 20f
            this.alpha = alpha
        }
        this.root = container

        val touchListener = setupTouchListener(params)
        setupIcons(touchListener)

        windowManager.addView(container, params)
        setFolded(folded)
    }

    fun updateTheme(isNightMode: Boolean) {
        val container = root as? LinearLayout ?: return
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 75f
            if (isNightMode) {
                setColor(Color.parseColor("#F21E293B"))
                setStroke(4, Color.parseColor("#6366F1"))
            } else {
                setColor(Color.parseColor("#F2FFFFFF"))
                setStroke(4, Color.parseColor("#CBD5E1"))
            }
        }

        val iconColor = if (isNightMode) Color.WHITE else Color.parseColor("#1E293B")
        btnFoldView?.setColorFilter(iconColor)
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialX = 0f
            private var initialY = 0f
            private var offsetX = 0f
            private var offsetY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val currentRoot = root ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = event.rawX
                        initialY = event.rawY
                        offsetX = event.rawX - params.x
                        offsetY = event.rawY - params.y
                        moved = false
                        v.isPressed = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialX
                        val dy = event.rawY - initialY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            params.x = (event.rawX - offsetX).toInt()
                            params.y = (event.rawY - offsetY).toInt()
                            windowManager.updateViewLayout(currentRoot, params)
                            moved = true
                            v.isPressed = false
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        if (moved) {
                            callbacks.onSavePosition(params.x, params.y)
                        } else {
                            v.performClick()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun setupIcons(touchListener: View.OnTouchListener) {
        val container = root as? LinearLayout ?: return
        container.setOnTouchListener(touchListener)
        
        btnFoldView = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_fold, 100).apply {
            setColorFilter(if (isNightMode()) Color.WHITE else Color.parseColor("#1E293B"))
            setOnTouchListener(touchListener)
            setOnClickListener { 
                isFolded = !isFolded
                callbacks.onFold(isFolded)
                setFolded(isFolded)
            }
        }
        
        btnMoveView = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_lock_v7, 100).apply {
            setColorFilter(Color.parseColor("#EF4444"))
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onToggleMoveMode() }
        }

        /* 
        val btnAdd = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_add, 100).apply {
            setColorFilter(if (isNightMode()) Color.WHITE else Color.parseColor("#1E293B"))
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onAddWidget() }
        }
        
        settingsIcon = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_settings, 100).apply {
            setColorFilter(if (isNightMode()) Color.WHITE else Color.parseColor("#1E293B"))
            setOnTouchListener(touchListener)
            setOnClickListener { onOpenSettings() }
        }
        */
        
        val btnBaemin = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_baemin, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onLaunchApp("BAEMIN") }
        }
        
        val btnCoupang = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_coupang, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onLaunchApp("COUPANG") }
        }

        val btnYogiyo = OverlayFactory.createToolbarIcon(context, R.drawable.ic_yogiyo, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onLaunchApp("YOGIYO") }
        }
        
        val btnClose = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_power, 100).apply {
            setColorFilter(Color.parseColor("#EF4444"))
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onPowerOff() }
        }

        container.addView(btnFoldView)
        container.addView(btnMoveView)
        // container.addView(btnAdd)
        // container.addView(settingsIcon)
        container.addView(btnBaemin)
        container.addView(btnCoupang)
        container.addView(btnYogiyo)
        container.addView(btnClose)
    }

    fun setFolded(folded: Boolean) {
        val currentRoot = root as? LinearLayout ?: return
        isFolded = folded
        val params = currentParams ?: return
        
        for (i in 1 until currentRoot.childCount) {
            currentRoot.getChildAt(i).visibility = if (folded) View.GONE else View.VISIBLE
        }
        btnFoldView?.setImageResource(if (folded) R.drawable.ic_toolbar_unfold else R.drawable.ic_toolbar_fold)
        
        try {
            if (currentRoot.parent != null) {
                windowManager.removeViewImmediate(currentRoot)
            }
            windowManager.addView(currentRoot, params)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to refresh window in setFolded", e)
            try { windowManager.updateViewLayout(currentRoot, params) } catch (e2: Exception) {}
        }
    }

    fun updateAlpha(alpha: Float) {
        root?.alpha = alpha
    }

    fun updateMoveIcon(isMoveMode: Boolean) {
        btnMoveView?.apply {
            if (isMoveMode) {
                setImageResource(R.drawable.ic_toolbar_unlock_v7)
                setColorFilter(Color.parseColor("#84CC16"))
            } else {
                setImageResource(R.drawable.ic_toolbar_lock_v7)
                setColorFilter(Color.parseColor("#EF4444"))
            }
        }
    }

    fun hide() {
        root?.let {
            windowManager.removeView(it)
            root = null
        }
    }
}
