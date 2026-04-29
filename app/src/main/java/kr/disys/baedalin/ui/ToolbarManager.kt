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
    private val callbacks: ToolbarCallbacks
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

    var root: FrameLayout? = null
        private set
    private var toolbarContainer: LinearLayout? = null
    private var isFolded = false
    
    private var btnFoldView: ImageView? = null
    private var btnHideView: ImageView? = null
    private var btnMoveView: ImageView? = null

    fun showToolbar(initialX: Int, initialY: Int, alpha: Float, folded: Boolean) {
        if (root != null) {
            Log.d("KeyMapper", "ToolbarManager: Toolbar already showing, ignoring showToolbar request")
            return
        }

        Log.d("KeyMapper", "ToolbarManager: showing toolbar at ($initialX, $initialY), folded=$folded")
        isFolded = folded
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        root = FrameLayout(context)
        toolbarContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(8, 8, 8, 8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 60f
                setColor(Color.WHITE)
                setStroke(1, 0xFFDDDDDD.toInt())
            }
            elevation = 8f
            this.alpha = alpha
        }

        setupTouchListener(params)
        setupIcons(params)

        root?.addView(toolbarContainer)
        windowManager.addView(root, params)
        
        setFolded(folded)
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        val touchListener = object : View.OnTouchListener {
            private var initialX = 0f
            private var initialY = 0f
            private var offsetX = 0f
            private var offsetY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                            windowManager.updateViewLayout(root, params)
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
        root?.setOnTouchListener(touchListener)
        toolbarContainer?.setOnTouchListener(touchListener)
        return touchListener
    }

    private fun setupIcons(params: WindowManager.LayoutParams) {
        val container = toolbarContainer ?: return
        val touchListener = setupTouchListener(params)
        
        btnFoldView = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_fold, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { 
                isFolded = !isFolded
                callbacks.onFold(isFolded)
                setFolded(isFolded)
            }
        }
        
        btnMoveView = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_lock_v7, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onToggleMoveMode() }
        }
        
        val btnBaemin = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_baemin, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onLaunchApp("BAEMIN") }
        }
        
        val btnCoupang = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_coupang, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onLaunchApp("COUPANG") }
        }
        
        val btnClose = OverlayFactory.createToolbarIcon(context, R.drawable.ic_toolbar_power, 100).apply {
            setOnTouchListener(touchListener)
            setOnClickListener { callbacks.onPowerOff() }
        }

        container.addView(btnFoldView)
        container.addView(btnMoveView)
        container.addView(btnBaemin)
        container.addView(btnCoupang)
        container.addView(btnClose)
    }

    fun setFolded(folded: Boolean) {
        isFolded = folded
        val container = toolbarContainer ?: return
        for (i in 1 until container.childCount) {
            container.getChildAt(i).visibility = if (folded) View.GONE else View.VISIBLE
        }
        btnFoldView?.setImageResource(if (folded) R.drawable.ic_toolbar_unfold else R.drawable.ic_toolbar_fold)
    }

    fun updateAlpha(alpha: Float) {
        toolbarContainer?.alpha = alpha
    }

    fun updateMoveIcon(isMoveMode: Boolean) {
        btnMoveView?.setImageResource(if (isMoveMode) R.drawable.ic_toolbar_unlock_v7 else R.drawable.ic_toolbar_lock_v7)
    }

    fun hide() {
        root?.let {
            windowManager.removeView(it)
            root = null
        }
    }
}
