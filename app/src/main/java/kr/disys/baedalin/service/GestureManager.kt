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
        
        val metrics = service.resources.displayMetrics
        val minSize = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        
        val gestureBuilder = GestureDescription.Builder()
        
        // 화면 중앙을 기준으로 대각선 방향으로 핀치 제스처 생성
        // 확대: 중앙 근처(startOffset) -> 외곽(endOffset)
        // 축소: 외곽(endOffset) -> 중앙 근처(startOffset)
        val startOffset = minSize * 0.10f
        val endOffset = minSize * 0.35f
        
        val startX1: Float
        val startY1: Float
        val endX1: Float
        val endY1: Float
        
        val startX2: Float
        val startY2: Float
        val endX2: Float
        val endY2: Float
        
        if (zoomIn) {
            // 확대 (안에서 밖으로)
            startX1 = centerX - startOffset
            startY1 = centerY - startOffset
            endX1 = centerX - endOffset
            endY1 = centerY - endOffset
            
            startX2 = centerX + startOffset
            startY2 = centerY + startOffset
            endX2 = centerX + endOffset
            endY2 = centerY + endOffset
        } else {
            // 축소 (밖에서 안으로)
            startX1 = centerX - endOffset
            startY1 = centerY - endOffset
            endX1 = centerX - startOffset
            endY1 = centerY - startOffset
            
            startX2 = centerX + endOffset
            startY2 = centerY + endOffset
            endX2 = centerX + startOffset
            endY2 = centerY + startOffset
        }
        
        val path1 = Path()
        path1.moveTo(startX1, startY1)
        path1.lineTo(endX1, endY1)
        
        val path2 = Path()
        path2.moveTo(startX2, startY2)
        path2.lineTo(endX2, endY2)
        
        // 지속 시간을 600ms로 늘려 시스템이 핀치 제스처를 더 정확하게 인식하도록 함
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path1, 0, 600))
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path2, 0, 600))
        
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
