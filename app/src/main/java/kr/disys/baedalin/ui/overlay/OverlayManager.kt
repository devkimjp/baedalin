package kr.disys.baedalin.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayViews = mutableMapOf<String, View>()

    fun showOverlay(id: String, view: View, params: WindowManager.LayoutParams) {
        hideOverlay(id)
        windowManager.addView(view, params)
        overlayViews[id] = view
    }

    fun hideOverlay(id: String) {
        overlayViews[id]?.let {
            windowManager.removeView(it)
            overlayViews.remove(id)
        }
    }

    fun hideAll() {
        overlayViews.values.forEach { windowManager.removeView(it) }
        overlayViews.clear()
    }

    fun updateOverlay(id: String, params: WindowManager.LayoutParams) {
        overlayViews[id]?.let {
            windowManager.updateViewLayout(it, params)
        }
    }

    fun isShowing(id: String): Boolean = overlayViews.containsKey(id)

    fun getOverlayView(id: String): View? = overlayViews[id]
}
