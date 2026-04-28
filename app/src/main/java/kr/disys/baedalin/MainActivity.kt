package kr.disys.baedalin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.service.KeyMapperAccessibilityService
import kr.disys.baedalin.ui.MainViewModel
import kr.disys.baedalin.ui.components.*
import kr.disys.baedalin.ui.theme.BaedalinTheme
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
    
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        handleIntent(intent)
        
        setContent {
            BaedalinTheme {
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.isAccessibilityEnabled = isAccessibilityServiceEnabled(this@MainActivity, KeyMapperAccessibilityService::class.java)
                            viewModel.isOverlayEnabled = Settings.canDrawOverlays(this@MainActivity)
                            
                            if (viewModel.isMappingEnabled) {
                                startService(Intent(this@MainActivity, FloatingWidgetService::class.java).apply {
                                    action = FloatingWidgetService.ACTION_START_SERVICE_ONLY
                                })
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { 
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                if (!viewModel.isAccessibilityEnabled || !viewModel.isOverlayEnabled) {
                    PermissionWizard(
                        isAccessibilityEnabled = viewModel.isAccessibilityEnabled,
                        isOverlayEnabled = viewModel.isOverlayEnabled
                    )
                } else {
                    Box {
                        MainScreen(viewModel = viewModel)
                        
                        if (viewModel.showDevicePicker) {
                            DevicePickerDialog(
                                devices = viewModel.inputDevices,
                                selectedDescriptor = viewModel.selectedDeviceDescriptor,
                                onDismiss = { viewModel.showDevicePicker = false },
                                onDeviceSelected = { device ->
                                    viewModel.saveSelectedDevice(device)
                                    viewModel.showDevicePicker = false
                                }
                            )
                        }

                        if (viewModel.showAppPicker) {
                            AppPickerDialog(
                                onDismiss = { viewModel.showAppPicker = false },
                                onAppSelected = { pkgName ->
                                    saveCustomPackage(viewModel.targetPresetForPicker!!, pkgName)
                                    viewModel.showAppPicker = false
                                    loadPreset(viewModel.targetPresetForPicker!!)
                                }
                            )
                        }

                        if (viewModel.conflictFunction != null) {
                            AlertDialog(
                                onDismissRequest = { 
                                    viewModel.conflictFunction = null
                                    getSharedPreferences("mappings", Context.MODE_PRIVATE).edit { putBoolean("is_recording", false) }
                                },
                                title = { Text("키 중복 확인") },
                                text = { Text("'${viewModel.conflictFunction?.label}' 기능에 이미 설정된 키입니다.\n현재 기능으로 변경하시겠습니까?") },
                                confirmButton = {
                                    Button(onClick = {
                                        viewModel.executeSaveMapping(viewModel.recordingFunction!!, viewModel.recordingClickType!!, viewModel.pendingKeyCode!!)
                                        viewModel.conflictFunction = null
                                    }) { Text("변경") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { 
                                        viewModel.conflictFunction = null
                                        viewModel.recordingFunction = null
                                        viewModel.recordingClickType = null
                                        getSharedPreferences("mappings", Context.MODE_PRIVATE).edit { putBoolean("is_recording", false) }
                                    }) { Text("취소") }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val preset = intent?.getStringExtra("load_preset")
        if (preset != null) {
            loadPreset(preset)
        }

        if (intent?.action == FloatingWidgetService.ACTION_START_RECORDING) {
            val funcName = intent.getStringExtra("function_name")
            val function = DeliveryFunction.entries.find { it.name == funcName }
            if (function != null) {
                viewModel.startRecording(function, ClickType.SINGLE)
            }
        }

        if (intent?.action == FloatingWidgetService.ACTION_UPDATE_UI) {
            viewModel.updateMappingVersion()
        }

        if (intent?.action == "ACTION_KEY_RECORDED") {
            val keyCode = intent.getIntExtra("keycode", -1)
            if (keyCode != -1 && viewModel.recordingFunction != null && viewModel.recordingClickType != null) {
                val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
                val prefix = viewModel.selectedDeviceDescriptor ?: "GLOBAL"
                
                val conflict = DeliveryFunction.entries.find { 
                    prefs.getInt("${prefix}_${it.name}_keycode", -1) == keyCode 
                }

                if (conflict != null && conflict != viewModel.recordingFunction) {
                    viewModel.conflictFunction = conflict
                    viewModel.pendingKeyCode = keyCode
                } else {
                    viewModel.executeSaveMapping(viewModel.recordingFunction!!, viewModel.recordingClickType!!, keyCode)
                }
            }
        }
    }

    private fun loadPreset(presetName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("active_preset", presetName) }

        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_LOAD_PRESET
            putExtra("preset_name", presetName)
        })
        
        viewModel.updateMappingVersion()
    }

    private fun saveCustomPackage(preset: String, pkgName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("${preset}_custom_pkg", pkgName) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.recordingFunction != null && viewModel.recordingClickType != null) {
            val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
            val prefix = viewModel.selectedDeviceDescriptor ?: "GLOBAL"
            
            val conflict = DeliveryFunction.entries.find { 
                prefs.getInt("${prefix}_${it.name}_keycode", -1) == keyCode 
            }

            if (conflict != null && conflict != viewModel.recordingFunction) {
                viewModel.conflictFunction = conflict
                viewModel.pendingKeyCode = keyCode
                return true
            }

            viewModel.executeSaveMapping(viewModel.recordingFunction!!, viewModel.recordingClickType!!, keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == context.packageName && 
            it.resolveInfo.serviceInfo.name == service.name 
        }
    }
}
