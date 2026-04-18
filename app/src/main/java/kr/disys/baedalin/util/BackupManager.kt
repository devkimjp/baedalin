package kr.disys.baedalin.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import kr.disys.baedalin.model.DeliveryFunction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object BackupManager {

    fun backupConfig(context: Context) {
        try {
            val root = JSONObject()

            // 1. Smartphone Info
            val deviceInfo = JSONObject()
            deviceInfo.put("model", Build.MODEL)
            deviceInfo.put("android_version", Build.VERSION.RELEASE)
            
            val metrics: DisplayMetrics = context.resources.displayMetrics
            deviceInfo.put("width", metrics.widthPixels)
            deviceInfo.put("height", metrics.heightPixels)
            deviceInfo.put("density", metrics.densityDpi)
            root.put("device_info", deviceInfo)

            // 2. Preset Info
            val presetsArray = JSONObject()
            val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)
            
            val appNames = listOf("BAEMIN", "COUPANG", "YOGIYO")
            appNames.forEach { app ->
                val appJson = JSONObject()
                DeliveryFunction.entries.forEach { func ->
                    val x = prefs.getInt("${app}_${func.name}_x", -1)
                    val y = prefs.getInt("${app}_${func.name}_y", -1)
                    if (x != -1 && y != -1) {
                        val pos = JSONObject()
                        pos.put("x", x)
                        pos.put("y", y)
                        appJson.put(func.name, pos)
                    }
                }
                // Custom package if exists
                val customPkg = prefs.getString("${app}_custom_pkg", null)
                if (customPkg != null) {
                    appJson.put("custom_package", customPkg)
                }
                presetsArray.put(app, appJson)
            }
            root.put("presets", presetsArray)

            val jsonString = root.toString(4)
            saveJsonToPublicStorage(context, jsonString)
        } catch (e: Exception) {
            Log.e("BackupManager", "Backup failed", e)
        }
    }

    private fun saveJsonToPublicStorage(context: Context, jsonContent: String) {
        val fileName = "baedalin_config_backup.json"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ using MediaStore (Survives app deletion)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Baedalin")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(jsonContent.toByteArray())
                }
                Log.d("BackupManager", "Backup saved to Documents/Baedalin via MediaStore")
            }
        } else {
            // Older Android versions
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val baedalinDir = File(documentsDir, "Baedalin")
            if (!baedalinDir.exists()) baedalinDir.mkdirs()
            
            val file = File(baedalinDir, fileName)
            FileOutputStream(file).use { it.write(jsonContent.toByteArray()) }
            Log.d("BackupManager", "Backup saved to ${file.absolutePath}")
        }
    }
}
