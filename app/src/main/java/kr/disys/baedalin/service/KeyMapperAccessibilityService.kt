package kr.disys.baedalin.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction

class KeyMapperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyCode = -1
    private var clickCount = 0
    private val DOUBLE_CLICK_TIMEOUT = 300L
    private val LONG_PRESS_TIMEOUT = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var isLongPressed = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only handle specific keys if needed, but for now we log everything
        val keyCode = event.keyCode
        val action = event.action

        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode != lastKeyCode) {
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
                clickCount = 0
            }
            
            lastKeyCode = keyCode
            isLongPressed = false
            
            handler.postDelayed({
                if (lastKeyCode == keyCode && !isLongPressed) {
                    isLongPressed = true
                    handleAction(keyCode, ClickType.LONG)
                }
            }, LONG_PRESS_TIMEOUT)
            
            return true 
        }

        if (action == KeyEvent.ACTION_UP) {
            handler.removeCallbacksAndMessages(null)
            
            if (isLongPressed) {
                isLongPressed = false
                clickCount = 0
                return true
            }

            clickCount++
            
            pendingClickRunnable?.let { handler.removeCallbacks(it) }
            
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
        
        // In a real app, we'd have a mapping of (keyCode, clickType) -> DeliveryFunction
        // For demonstration, let's assume keyCode 66 (ENTER) is Mapped to something
        // In production, user would configure this in MainActivity.
        
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        
        // Example: If user mapped a button to 'ACCEPT'
        // We'll iterate through functions to see which one is mapped to this keyCode/clickType
        // For now, let's just trigger based on some mock logic or just the first function found
        
        DeliveryFunction.entries.forEach { function ->
            val mappedKey = prefs.getInt("${function.name}_keycode", -1)
            val mappedClick = prefs.getString("${function.name}_clicktype", "")
            
            if (mappedKey == keyCode && mappedClick == clickType.name) {
                val x = prefs.getInt("${function.name}_x", -1).toFloat()
                val y = prefs.getInt("${function.name}_y", -1).toFloat()
                
                if (x != -1f && y != -1f) {
                    if (function == DeliveryFunction.ZOOM_IN) {
                        performSwipe(x, y, x + 200, y + 200)
                    } else if (function == DeliveryFunction.ZOOM_OUT) {
                        performSwipe(x + 200, y + 200, x, y)
                    } else {
                        performTap(x, y)
                    }
                }
            }
        }
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
