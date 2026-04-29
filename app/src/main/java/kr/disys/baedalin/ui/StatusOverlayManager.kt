package kr.disys.baedalin.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class StatusOverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var statusOverlayView: View? = null
    private var statusTextView: TextView? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private val hideStatusRunnable = Runnable { hideStatusOverlay() }
    
    private var currentStatusPriority = 0 
    private var statusGeneration = 0 
    private var lastMessageContent: String? = null
    private var lastMessageTimestamp = 0L

    fun showStatusOverlay(message: String, durationMs: Long = 2000, priority: Int = 1) {
        val now = System.currentTimeMillis()
        val trimmedMsg = message.trim()
        
        if (trimmedMsg == lastMessageContent?.trim() && now - lastMessageTimestamp < 1500) {
            statusHandler.removeCallbacks(hideStatusRunnable)
            statusHandler.postDelayed(hideStatusRunnable, durationMs)
            return
        }
        
        lastMessageContent = trimmedMsg
        lastMessageTimestamp = now
        statusHandler.removeCallbacks(hideStatusRunnable)
        
        if (priority < currentStatusPriority) return

        currentStatusPriority = priority
        statusGeneration++

        if (statusOverlayView == null) {
            val container = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(0xEE000000.toInt())
                    cornerRadius = 60f
                    setStroke(4, Color.parseColor("#FFD700"))
                }
                setPadding(80, 50, 80, 50)
                elevation = 20f
            }
            
            statusTextView = TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }
            container.addView(statusTextView)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                y = -50
            }

            try {
                windowManager.addView(container, params)
                statusOverlayView = container
            } catch (e: Exception) {
                Log.e("StatusOverlay", "Failed to add status overlay", e)
            }
        } else {
            statusOverlayView?.animate()?.cancel()
            statusOverlayView?.alpha = 1f
            if (statusTextView?.text != message) {
                statusTextView?.text = message
            }
        }

        statusHandler.postDelayed(hideStatusRunnable, durationMs)
    }

    fun hideStatusOverlay() {
        val view = statusOverlayView ?: return
        try {
            windowManager.removeView(view)
            statusOverlayView = null
            statusTextView = null
            currentStatusPriority = 0
        } catch (e: Exception) {}
    }

    fun cleanup() {
        statusHandler.removeCallbacks(hideStatusRunnable)
        hideStatusOverlay()
    }
}
