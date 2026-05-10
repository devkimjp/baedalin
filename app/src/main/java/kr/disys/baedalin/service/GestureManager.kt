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

class GestureManager(private var service: AccessibilityService) {

    fun setService(newService: AccessibilityService) {
        this.service = newService
    }

    fun performTap(x: Float, y: Float) {
        Log.d("GestureManager", "[TOUCH] Attempting TAP at ($x, $y)")
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        
        val gesture = gestureBuilder.build()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("GestureManager", "[TOUCH] TAP Success at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e("GestureManager", "[TOUCH] TAP Cancelled/Failed at ($x, $y)")
            }
        }, null)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 100) {
        Log.d("GestureManager", "[TOUCH] Attempting SWIPE from ($startX, $startY) to ($endX, $endY)")
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        
        service.dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("GestureManager", "[TOUCH] SWIPE Success")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e("GestureManager", "[TOUCH] SWIPE Cancelled/Failed")
            }
        }, null)
    }

    fun performZoom(centerX: Float, centerY: Float, zoomIn: Boolean) {
        Log.d("GestureManager", "[TOUCH] Attempting ZOOM (in=$zoomIn) at ($centerX, $centerY)")
        val gestureBuilder = GestureDescription.Builder()
        
        // 두 손가락의 시작과 끝 지점 계산
        val startX1: Float
        val startY1: Float
        val endX1: Float
        val endY1: Float
        
        val startX2: Float
        val startY2: Float
        val endX2: Float
        val endY2: Float
        
        val offset = 200f
        val move = 300f
        
        if (zoomIn) {
            // 밖으로 벌리기 (확대)
            startX1 = centerX - offset; startY1 = centerY
            endX1 = centerX - (offset + move); endY1 = centerY
            
            startX2 = centerX + offset; startY2 = centerY
            endX2 = centerX + (offset + move); endY2 = centerY
        } else {
            // 안으로 모으기 (축소)
            startX1 = centerX - (offset + move); startY1 = centerY
            endX1 = centerX - offset; endY1 = centerY
            
            startX2 = centerX + (offset + move); startY2 = centerY
            endX2 = centerX + offset; endY2 = centerY
        }
        
        val path1 = Path()
        path1.moveTo(startX1, startY1)
        path1.lineTo(endX1, endY1)
        
        val path2 = Path()
        path2.moveTo(startX2, startY2)
        path2.lineTo(endX2, endY2)
        
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path1, 0, 400))
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path2, 0, 400))
        
        service.dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("GestureManager", "[TOUCH] ZOOM Success")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e("GestureManager", "[TOUCH] ZOOM Cancelled/Failed")
            }
        }, null)
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
