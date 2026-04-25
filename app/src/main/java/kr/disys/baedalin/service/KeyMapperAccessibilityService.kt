package kr.disys.baedalin.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.content.Intent
import kr.disys.baedalin.KeyRecordingState
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.model.Presets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray
import androidx.core.content.edit
import android.media.AudioManager
import android.media.ToneGenerator

class KeyMapperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyCode = -1
    private var clickCount = 0
    private val doubleClickTimeout = 300L
    private val longPressTimeout = 500L
    
    private var pendingClickRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressed = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private val logDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "is_recording" || key == "is_mapping_enabled") {
            updateKeyFilterState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("KeyMapper", "Service onCreate - Process: ${android.os.Process.myPid()}")
    }

    override fun onServiceConnected() {
        Log.d("KeyMapper", "KeyMapperAccessibilityService connected")
        updateKeyFilterState()
        
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
            
        serviceScope.launch {
            FloatingWidgetService.isInterceptionActive.collect {
                updateKeyFilterState()
            }
        }
    }

    private fun updateKeyFilterState() {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        val isRecording = prefs.getBoolean("is_recording", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value
        
        val info = serviceInfo ?: AccessibilityServiceInfo()
        
        if (isMappingEnabled || isRecording) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            
            val shouldFilterKeys = isRecording || (isMappingEnabled && isInterceptionActive)
            var targetFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            
            if (shouldFilterKeys) {
                targetFlags = targetFlags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                Log.d("KeyMapper", "Key Filter: ACTIVE")
            } else {
                Log.d("KeyMapper", "Key Filter: WINDOW_ONLY")
            }
            
            info.flags = targetFlags
        } else {
            info.eventTypes = 0
            info.feedbackType = 0
            info.notificationTimeout = 0
            info.flags = 0
            Log.d("KeyMapper", "Key Filter: STEALTH")
        }
        
        serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        getSharedPreferences("mappings", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            currentPackageName = packageName
            
            val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
            val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
            val isRunning = FloatingWidgetService.isRunning.value
            
            if (!isMappingEnabled) return

            val preset = Presets.getPresetFromPackage(packageName)
            
            if (isRunning) {
                if (preset != null) {
                    val intent = Intent(this, FloatingWidgetService::class.java).apply {
                        action = FloatingWidgetService.ACTION_LOAD_PRESET
                        putExtra("preset_name", preset)
                    }
                    startService(intent)
                } else {
                    if (packageName != "com.android.systemui" && 
                        packageName != "android" && 
                        packageName != "kr.disys.baedalin") {
                        val intent = Intent(this, FloatingWidgetService::class.java).apply {
                            action = FloatingWidgetService.ACTION_HIDE_PRESETS
                        }
                        startService(intent)
                    }
                }
                FloatingWidgetService.instance?.updateToolbarState()
            }
        }
    }

    private fun captureUISnapshot() {
        val root = rootInActiveWindow ?: return
        try {
            val timestamp = logDateFormat.format(Date())
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "ui_snapshot_$timestamp.json")
            
            val json = JSONObject()
            json.put("timestamp", System.currentTimeMillis())
            json.put("packageName", root.packageName)
            json.put("nodes", dumpNodeToJson(root))
            
            FileWriter(file).use { it.write(json.toString(2)) }
            
            playSuccessSound()
            
            val intent = Intent(this, FloatingWidgetService::class.java).apply {
                action = "ACTION_SNAPSHOT_COMPLETE"
                putExtra("success", true)
                putExtra("path", file.absolutePath)
            }
            startService(intent)
            
        } catch (e: Exception) {
            Log.e("KeyMapper", "Snapshot failed", e)
            val intent = Intent(this, FloatingWidgetService::class.java).apply {
                action = "ACTION_SNAPSHOT_COMPLETE"
                putExtra("success", false)
            }
            startService(intent)
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

    private fun playSuccessSound() {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneG.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            handler.postDelayed({ toneG.release() }, 2000)
        } catch (e: Exception) {
            Log.e("KeyMapper", "Failed to play sound", e)
        }
    }

    companion object {
        var currentPackageName: String = ""
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_UI_SNAPSHOT") {
            captureUISnapshot()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val isMappingEnabled = prefs.getBoolean("is_mapping_enabled", false)
        val isRecording = KeyRecordingState.isRecording || prefs.getBoolean("is_recording", false)
        val isInterceptionActive = FloatingWidgetService.isInterceptionActive.value

        if (isRecording) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val intent = Intent(this, kr.disys.baedalin.MainActivity::class.java).apply {
                    action = "ACTION_KEY_RECORDED"
                    putExtra("keycode", event.keyCode)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
            return true 
        }

        if (!isMappingEnabled || !isInterceptionActive) {
            return false
        }

        val targetDescriptor = prefs.getString("selected_device_descriptor", null)
        val device = InputDevice.getDevice(event.deviceId)
        if (device == null || device.descriptor != targetDescriptor) {
            return false
        }
        
        val keyCode = event.keyCode
        val action = event.action
        val prefix = targetDescriptor ?: "GLOBAL"
        
        if (!isKeyMapped(keyCode, prefix)) {
            return false
        }

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true
            if (keyCode != lastKeyCode) {
                pendingClickRunnable?.let { handler.removeCallbacks(it) }
                clickCount = 0
            }
            lastKeyCode = keyCode
            return true
        }

        if (action == KeyEvent.ACTION_UP) {
            clickCount++
            pendingClickRunnable?.let { handler.removeCallbacks(it) }
            pendingClickRunnable = Runnable {
                val type = if (clickCount >= 2) ClickType.DOUBLE else ClickType.SINGLE
                if (FloatingWidgetService.isMoveMode.value) {
                    FloatingWidgetService.forceLockMode()
                }
                handleAction(keyCode, type, prefix)
                clickCount = 0
            }.also { handler.postDelayed(it, doubleClickTimeout) }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun isKeyMapped(keyCode: Int, prefix: String): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        DeliveryFunction.entries.forEach { function ->
            if (prefs.getInt("${prefix}_${function.name}_keycode", -1) == keyCode) {
                return true
            }
        }
        return false
    }

    private fun handleAction(keyCode: Int, clickType: ClickType, prefix: String): Boolean {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("is_mapping_enabled", false)) return false
        
        val activePreset = prefs.getString("active_preset", "DEFAULT") ?: "DEFAULT"
        val function = DeliveryFunction.entries.find { func ->
            val mappedKey = prefs.getInt("${prefix}_${func.name}_keycode", -1)
            val mappedClick = prefs.getString("${prefix}_${func.name}_clicktype", ClickType.SINGLE.name)
            mappedKey == keyCode && mappedClick == clickType.name
        }
        
        if (function != null) {
            val displayMetrics = resources.displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val centerY = displayMetrics.heightPixels / 2f

            when (function) {
                DeliveryFunction.ZOOM_OUT -> {
                    val gestureBuilder = GestureDescription.Builder()
                    val tapPath = Path()
                    tapPath.moveTo(centerX, centerY)
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))
                    val swipePath = Path()
                    swipePath.moveTo(centerX, centerY)
                    swipePath.lineTo(centerX, centerY - 200f)
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 100, 200))
                    dispatchGesture(gestureBuilder.build(), null, null)
                    return true
                }
                DeliveryFunction.ZOOM_IN -> {
                    val gestureBuilder = GestureDescription.Builder()
                    val tapPath = Path()
                    tapPath.moveTo(centerX, centerY)
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))
                    val swipePath = Path()
                    swipePath.moveTo(centerX, centerY)
                    swipePath.lineTo(centerX, centerY + 200f)
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 100, 200))
                    dispatchGesture(gestureBuilder.build(), null, null)
                    return true
                }
                else -> {
                    val x = prefs.getInt("${activePreset}_${function.name}_x", -1).toFloat()
                    val y = prefs.getInt("${activePreset}_${function.name}_y", -1).toFloat()
                    if (x != -1f && y != -1f) {
                        performTap(x + 50f, y + 90f)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }
}
