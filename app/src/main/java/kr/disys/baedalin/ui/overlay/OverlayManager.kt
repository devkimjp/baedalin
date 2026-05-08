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
        try {
            if (view.parent != null) {
                windowManager.removeViewImmediate(view)
            }
            windowManager.addView(view, params)
            overlayViews[id] = view
        } catch (e: Exception) {
            // 이미 추가되었거나 다른 에러가 발생한 경우 처리
            overlayViews[id] = view
            try { windowManager.updateViewLayout(view, params) } catch (e2: Exception) {}
        }
    }

    fun hideOverlay(id: String) {
        overlayViews[id]?.let {
            try {
                if (it.parent != null) {
                    windowManager.removeViewImmediate(it)
                }
            } catch (e: Exception) {}
            overlayViews.remove(id)
        }
    }

    fun hideAll() {
        overlayViews.values.forEach { 
            try {
                if (it.parent != null) {
                    windowManager.removeViewImmediate(it)
                }
            } catch (e: Exception) {}
        }
        overlayViews.clear()
    }

    fun updateOverlay(id: String, params: WindowManager.LayoutParams) {
        overlayViews[id]?.let {
            windowManager.updateViewLayout(it, params)
        }
    }

    fun isShowing(id: String): Boolean = overlayViews.containsKey(id)

    fun getOverlayView(id: String): View? = overlayViews[id]

    fun getAllOverlayIds(): List<String> = overlayViews.keys.toList()
}
