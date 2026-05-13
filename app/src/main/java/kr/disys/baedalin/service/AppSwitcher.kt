package kr.disys.baedalin.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kr.disys.baedalin.model.Presets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSwitcher @Inject constructor(
    private val context: Context
) {
    private var isSwitchingApp = false
    private var lastSwitchedPackage: String? = null

    fun isSwitching(): Boolean = isSwitchingApp
    fun getLastSwitchedPackage(): String? = lastSwitchedPackage

    fun switchBetweenDeliveryApps(activePreset: String) {
        val nextPreset = if (activePreset == "BAEMIN") "COUPANG" else "BAEMIN"
        val nextPackage = Presets.getPackageName(nextPreset)
        val currentPackage = Presets.getPackageName(activePreset)
        
        Log.i("AppSwitcher", "[APP_SWITCH] Optimized switching: $activePreset -> $nextPreset")
        
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(nextPackage)
            if (intent != null) {
                isSwitchingApp = true
                lastSwitchedPackage = currentPackage
                
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                
                val options = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
                context.startActivity(intent, options)
                
                Log.i("AppSwitcher", "[APP_SWITCH] Fast Intent sent for $nextPackage")
                
                // Transition mode reset after 2 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isSwitchingApp = false
                    lastSwitchedPackage = null
                    Log.d("AppSwitcher", "[APP_SWITCH] Transition mode cleared")
                }, 2000)
            } else {
                Log.w("AppSwitcher", "[APP_SWITCH] Target app not found: $nextPackage")
                Toast.makeText(context, "$nextPreset 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("AppSwitcher", "[APP_SWITCH] Error during fast transition", e)
            isSwitchingApp = false
        }
    }
    
    fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("AppSwitcher", "Failed to launch app: $packageName", e)
                Toast.makeText(context, "앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "앱이 설치되어 있지 않습니다: $packageName", Toast.LENGTH_SHORT).show()
        }
    }
}
