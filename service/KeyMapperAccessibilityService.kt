package kr.disys.baedalin.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import kr.disys.baedalin.model.ClickType

class KeyMapperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyCode = -1
    private var clickCount = 0
    private val DOUBLE_CLICK_TIMEOUT = 300L
    private val LONG_PRESS_TIMEOUT = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var isLongPress = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == lastKeyCode) {
                // Potential double click, cancel pending single click
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
            }
            
            lastKeyCode = keyCode
            
            // Start long press detection
            isLongPress = false
            handler.postDelayed({
                if (lastKeyCode == keyCode && !isLongPress) {
                    isLongPress = true
                    handleAction(keyCode, ClickType.LONG)
                }
            }, LONG_PRESS_TIMEOUT)
            
            return true // Consume down event
        }

        if (action == KeyEvent.ACTION_UP) {
            if (isLongPress) {
                isLongPress = false
                return true
            }
            
            // Cancel long press detection since key is up
            handler.removeCallbacksAndMessages(null)

            clickCount++
            
            val clickRunnable = Runnable {
                if (clickCount == 1) {
                    handleAction(keyCode, ClickType.SINGLE)
                } else if (clickCount >= 2) {
                    handleAction(keyCode, ClickType.DOUBLE)
                }
                clickCount = 0
                lastKeyCode = -1
            }
            
            pendingClickRunnable = clickRunnable
            handler.postDelayed(clickRunnable, DOUBLE_CLICK_TIMEOUT)
            
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun handleAction(keyCode: Int, clickType: ClickType) {
        Log.d("KeyMapper", "Action: keyCode=$keyCode, type=$clickType")
        // In real app: find Mapping for this keyCode + clickType from SharedPreferences/DB
        // then call performTap or performSwipe
    }

    fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 100) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(gestureBuilder.build(), null, null)
    }
}
