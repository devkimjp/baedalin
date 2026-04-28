package kr.disys.baedalin.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class GestureManager(private val service: AccessibilityService) {

    fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        service.dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 100) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        service.dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performZoom(centerX: Float, centerY: Float, zoomIn: Boolean) {
        val gestureBuilder = GestureDescription.Builder()
        val tapPath = Path()
        tapPath.moveTo(centerX, centerY)
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))

        val swipePath = Path()
        swipePath.moveTo(centerX, centerY)
        val offset = if (zoomIn) 200f else -200f
        swipePath.lineTo(centerX, centerY + offset)
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 100, 200))

        service.dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun captureUISnapshot(): String? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            val logDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = logDateFormat.format(Date())
            val dir = service.getExternalFilesDir(null) ?: service.filesDir
            val file = File(dir, "ui_snapshot_$timestamp.json")
            
            val json = JSONObject()
            json.put("timestamp", System.currentTimeMillis())
            json.put("packageName", root.packageName)
            json.put("nodes", dumpNodeToJson(root))
            
            FileWriter(file).use { it.write(json.toString(2)) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("GestureManager", "Snapshot failed", e)
            null
        } finally {
            root.recycle()
        }
    }

    private fun dumpNodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        json.put("class", node.className?.toString()?.split(".")?.last() ?: "View")
        json.put("text", node.text?.toString() ?: "")
        json.put("desc", node.contentDescription?.toString() ?: "")
        json.put("id", node.viewIdResourceName ?: "")
        json.put("clickable", node.isClickable)
        json.put("bounds", JSONObject().apply {
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
        })

        if (node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    children.put(dumpNodeToJson(child))
                    child.recycle()
                }
            }
            json.put("children", children)
        }
        return json
    }
}
