package kr.disys.baedalin.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object OverlayFactory {

    fun createCircleIcon(context: Context, icon: String, color: Int, size: Int): TextView {
        return TextView(context).apply {
            text = icon
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(2, Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    fun createTooltip(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(8, 4, 8, 4)
            textSize = 10f
        }
    }

    fun createToolbarIcon(context: Context, resId: Int, size: Int): ImageView {
        return ImageView(context).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(15, 15, 15, 15)
        }
    }

    fun createStatusContainer(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(0xEE000000.toInt())
                cornerRadius = 60f
                setStroke(4, Color.parseColor("#FFD700"))
            }
            setPadding(80, 50, 80, 50)
            elevation = 20f
        }
    }
}
