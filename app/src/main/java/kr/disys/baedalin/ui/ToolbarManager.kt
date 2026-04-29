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

    var root: View? = null
        private set
    private var isFolded = false
    
    private var btnFoldView: ImageView? = null
    private var btnMoveView: ImageView? = null
    private var currentParams: WindowManager.LayoutParams? = null

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
        this.root = container

        val touchListener = setupTouchListener(params)
        setupIcons(touchListener)

        windowManager.addView(container, params)
        setFolded(folded)
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
            setOnTouchListener(touchListener)
            setOnClickListener { 
                isFolded = !isFolded
                callbacks.onFold(isFolded)
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
        val currentRoot = root as? LinearLayout ?: return
        isFolded = folded
        val params = currentParams ?: return
        
        // 1. 가시성 변경
        for (i in 1 until currentRoot.childCount) {
            currentRoot.getChildAt(i).visibility = if (folded) View.GONE else View.VISIBLE
        }
        btnFoldView?.setImageResource(if (folded) R.drawable.ic_toolbar_unfold else R.drawable.ic_toolbar_fold)
        
        // 2. 윈도우 재등록 (위치 캐시 파기)
        // 뷰를 제거했다가 즉시 다시 추가함으로써 이전 위치 정보가 남지 않도록 합니다.
        try {
            if (currentRoot.parent != null) {
                windowManager.removeViewImmediate(currentRoot)
            }
            windowManager.addView(currentRoot, params)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to refresh window in setFolded", e)
            // 실패 시 최후의 수단으로 일반 업데이트 시도
            try { windowManager.updateViewLayout(currentRoot, params) } catch (e2: Exception) {}
        }
    }

    fun updateAlpha(alpha: Float) {
        root?.alpha = alpha
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
