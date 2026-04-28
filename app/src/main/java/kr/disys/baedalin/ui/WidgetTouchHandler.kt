package kr.disys.baedalin.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class WidgetTouchHandler(
    private val windowManager: WindowManager,
    private val onMove: (Int, Int) -> Unit,
    private val onSave: (Int, Int) -> Unit,
    private val onClick: () -> Unit,
    private val onLongClick: () -> Unit,
    private val onMappingMode: () -> Unit,
    private val isMoveMode: () -> Boolean,
    private val isRecording: () -> Boolean
) : View.OnTouchListener {

    private var dragInitialX = 0f
    private var dragInitialY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var longClickHandled = false
    private var moveModeActiveForThis = false
    private val handler = Handler(Looper.getMainLooper())

    private val moveModeRunnable = Runnable {
        moveModeActiveForThis = true
        onLongClick()
    }

    private val mappingModeRunnable = Runnable {
        longClickHandled = true
        moveModeActiveForThis = false
        onMappingMode()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val p = v.layoutParams as WindowManager.LayoutParams
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longClickHandled = false
                moveModeActiveForThis = isMoveMode()
                
                dragInitialX = event.rawX
                dragInitialY = event.rawY
                dragOffsetX = event.rawX - p.x
                dragOffsetY = event.rawY - p.y
                
                if (!moveModeActiveForThis) {
                    handler.postDelayed(moveModeRunnable, 1500)
                }
                if (moveModeActiveForThis) {
                    handler.postDelayed(mappingModeRunnable, 2000)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.rawX - dragInitialX)
                val dy = Math.abs(event.rawY - dragInitialY)
                
                if (isRecording()) return true

                if (dx > 25 || dy > 25) {
                    if (moveModeActiveForThis) {
                        val newX = (event.rawX - dragOffsetX).toInt()
                        val newY = (event.rawY - dragOffsetY).toInt()
                        onMove(newX, newY)
                        longClickHandled = true 
                    } else {
                        handler.removeCallbacks(moveModeRunnable)
                        handler.removeCallbacks(mappingModeRunnable)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(moveModeRunnable)
                handler.removeCallbacks(mappingModeRunnable)
                
                if (moveModeActiveForThis && longClickHandled) {
                    onSave(p.x, p.y)
                } else if (!longClickHandled) {
                    if (!isMoveMode()) {
                        v.performClick()
                        onClick()
                    }
                }
                return true
            }
        }
        return false
    }
}
