package kr.disys.baedalin.util

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kr.disys.baedalin.model.*

object ShareManager {
    private const val PREFS_NAME = "mappings"
    private const val TAG = "ShareManager"

    fun getDeviceInfo(context: Context): DeviceInfo {
        val metrics = context.resources.displayMetrics
        return DeviceInfo(
            model = Build.MODEL,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            dpi = metrics.densityDpi
        )
    }

    fun exportConfig(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceInfo = getDeviceInfo(context)
        
        val coordinates = mutableListOf<CoordinateEntry>()
        val customWidgets = mutableListOf<CustomWidgetInfo>()
        
        val presets = listOf("BAEMIN", "COUPANG", "YOGIYO")
        
        presets.forEach { preset ->
            // 1. 기본 위젯 좌표 수집
            val presetList = when(preset) {
                "BAEMIN" -> Presets.BAEMIN
                "COUPANG" -> Presets.COUPANG
                "YOGIYO" -> Presets.YOGIYO
                else -> emptyList()
            }
            
            presetList.forEach { info ->
                val x = prefs.getInt("${preset}_${info.function.name}_x", -1)
                val y = prefs.getInt("${preset}_${info.function.name}_y", -1)
                if (x != -1 && y != -1) {
                    coordinates.add(CoordinateEntry(preset, info.function.name, x, y))
                }
            }
            
            // 2. 커스텀 위젯 정보 수집
            val listKey = "${preset}_active_custom_widgets"
            val activeWidgets = prefs.getString(listKey, "") ?: ""
            if (activeWidgets.isNotEmpty()) {
                val counter = prefs.getInt("${preset}_custom_counter", 1)
                val lastX = prefs.getInt("${preset}_last_added_x", 200)
                val lastY = prefs.getInt("${preset}_last_added_y", 250)
                
                customWidgets.add(CustomWidgetInfo(preset, activeWidgets, counter, lastX, lastY))
                
                // 개별 커스텀 위젯 좌표 수집
                activeWidgets.split(",").filter { it.isNotBlank() }.forEach { label ->
                    val funcName = "${preset}_CUSTOM_$label"
                    val cx = prefs.getInt("${preset}_${funcName}_x", -1)
                    val cy = prefs.getInt("${preset}_${funcName}_y", -1)
                    if (cx != -1 && cy != -1) {
                        coordinates.add(CoordinateEntry(preset, funcName, cx, cy))
                    }
                }
            }
        }
        
        val shareConfig = ShareConfig(deviceInfo = deviceInfo, coordinates = coordinates, customWidgets = customWidgets)
        val json = shareConfig.toJSONString()
        return Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
    }

    fun importConfig(context: Context, base64Code: String): ShareConfig? {
        return try {
            val json = String(Base64.decode(base64Code, Base64.NO_WRAP))
            val config = ShareConfig.fromJSONString(json)
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                // 1. 좌표 적용
                config.coordinates.forEach { entry ->
                    putInt("${entry.preset}_${entry.function}_x", entry.x)
                    putInt("${entry.preset}_${entry.function}_y", entry.y)
                }
                
                // 2. 커스텀 위젯 정보 적용
                config.customWidgets.forEach { custom ->
                    putString("${custom.preset}_active_custom_widgets", custom.activeWidgets)
                    putInt("${custom.preset}_custom_counter", custom.counter)
                    putInt("${custom.preset}_last_added_x", custom.lastX)
                    putInt("${custom.preset}_last_added_y", custom.lastY)
                }
            }
            config
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            null
        }
    }
}
