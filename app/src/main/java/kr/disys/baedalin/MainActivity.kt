package kr.disys.baedalin

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.service.KeyMapperAccessibilityService
import kr.disys.baedalin.ui.components.PermissionWizard
import kr.disys.baedalin.ui.main.MainScreen
import kr.disys.baedalin.ui.main.MainViewModel
import kr.disys.baedalin.ui.theme.BaedalinTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            BaedalinTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

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

                if (!uiState.isAccessibilityEnabled || !uiState.isOverlayEnabled) {
                    PermissionWizard(
                        isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                        isOverlayEnabled = uiState.isOverlayEnabled,
                        onComplete = {
                            // 권한 허용 후 상태 즉시 갱신
                            viewModel.isAccessibilityEnabled = isAccessibilityServiceEnabled(this@MainActivity, KeyMapperAccessibilityService::class.java)
                            viewModel.isOverlayEnabled = Settings.canDrawOverlays(this@MainActivity)
                        }
                    )
                } else {
                    Box {
                        MainScreen(viewModel = viewModel)
                        
                        // 중복 키 경고 다이얼로그 (위저드 외 상황 대비)
                        if (uiState.conflictFunction != null) {
                            AlertDialog(
                                onDismissRequest = { 
                                    viewModel.conflictFunction = null
                                    getSharedPreferences("mappings", Context.MODE_PRIVATE).edit { putBoolean("is_recording", false) }
                                },
                                title = { Text("키 중복 확인") },
                                text = { Text("'${uiState.conflictFunction?.label}' 기능에 이미 설정된 키입니다.\n현재 기능으로 변경하시겠습니까?") },
                                confirmButton = {
                                    Button(onClick = {
                                        viewModel.executeSaveMapping(uiState.recordingFunction!!, uiState.recordingClickType!!, uiState.pendingKeyCode!!)
                                        viewModel.conflictFunction = null
                                    }) { Text("변경") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { 
                                        viewModel.conflictFunction = null
                                        viewModel.stopRecording()
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
            handleKeyCodeInput(keyCode)
        }
    }

    private fun handleKeyCodeInput(keyCode: Int) {
        if (keyCode == -1) return
        
        val uiState = viewModel.uiState.value
        
        // 1. 매핑 위저드 중인 경우
        if (uiState.isMappingWizardActive) {
            viewModel.pendingKeyCode = keyCode
            return
        }

        // 2. 개별 녹화 중인 경우
        if (viewModel.recordingFunction != null && viewModel.recordingClickType != null) {
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

    private fun loadPreset(presetName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("active_preset", presetName) }

        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_LOAD_PRESET
            putExtra("preset_name", presetName)
        })
        
        viewModel.updateMappingVersion()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 볼륨 키 등 시스템 키 제외하고 가로채기
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }

        if (viewModel.uiState.value.isMappingWizardActive || 
            (viewModel.recordingFunction != null && viewModel.recordingClickType != null)) {
            handleKeyCodeInput(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, service).flattenToString()
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        
        if (enabledServices == null) return false
        
        return enabledServices.split(':').any { it.equals(expectedComponentName, ignoreCase = true) }
    }
}
