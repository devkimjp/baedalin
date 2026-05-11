package kr.disys.baedalin

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
    
    private val bluetoothPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.isBluetoothEnabled = isGranted
    }
    
    private val keyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "ACTION_KEY_RECORDED") {
                val keyCode = intent.getIntExtra("keycode", -1)
                handleKeyCodeInput(keyCode)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        
        val filter = android.content.IntentFilter("ACTION_KEY_RECORDED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keyReceiver, filter)
        }

        setContent {
            val isNight by FloatingWidgetService.isNightMode.collectAsStateWithLifecycle()
            BaedalinTheme(darkTheme = isNight) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.isAccessibilityEnabled = isAccessibilityServiceEnabled(this@MainActivity, KeyMapperAccessibilityService::class.java)
                            viewModel.isOverlayEnabled = Settings.canDrawOverlays(this@MainActivity)
                            viewModel.isBluetoothEnabled = checkBluetoothPermission()
                            viewModel.isBatteryOptimized = checkBatteryOptimization()
                            
                            if (viewModel.isMappingEnabled && !uiState.isMappingWizardActive) {
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

                val isBatteryExempt = !uiState.isBatteryOptimized
                val allPermissionsGranted = uiState.isAccessibilityEnabled && uiState.isOverlayEnabled && uiState.isBluetoothEnabled && isBatteryExempt

                if (!allPermissionsGranted) {
                    PermissionWizard(
                        isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                        isOverlayEnabled = uiState.isOverlayEnabled,
                        isBluetoothEnabled = uiState.isBluetoothEnabled,
                        isBatteryOptimized = uiState.isBatteryOptimized,
                        onRequestBluetoothPermission = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        },
                        onComplete = {
                            // 권한 허용 후 상태 즉시 갱신
                            viewModel.isAccessibilityEnabled = isAccessibilityServiceEnabled(this@MainActivity, KeyMapperAccessibilityService::class.java)
                            viewModel.isOverlayEnabled = Settings.canDrawOverlays(this@MainActivity)
                            viewModel.isBluetoothEnabled = checkBluetoothPermission()
                            viewModel.isBatteryOptimized = checkBatteryOptimization()
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
                                text = { Text("'${uiState.conflictFunction?.let { getString(it.labelResId) }}' 기능에 이미 설정된 키입니다.\n현재 기능으로 변경하시겠습니까?") },
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

    override fun onDestroy() {
        try {
            unregisterReceiver(keyReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
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
            // [CRITICAL] 매핑 위저드 중에는 개별 녹화 명령 무시
            if (viewModel.uiState.value.isMappingWizardActive) return
            
            val funcName = intent.getStringExtra("function_name")
            val function = DeliveryFunction.entries.find { it.name == funcName }
            if (function != null) {
                viewModel.startRecording(function, ClickType.SINGLE)
            }
        }

        if (intent?.action == FloatingWidgetService.ACTION_UPDATE_UI) {
            viewModel.updateMappingVersion()
        }
    }

    private var lastKeyInputTime = 0L
    private var lastKeyInputCode = -1

    private fun handleKeyCodeInput(keyCode: Int) {
        if (keyCode == -1) return
        
        val currentTime = System.currentTimeMillis()
        // [DEDUPLICATION] 녹화 중일 때는 하드웨어 특성을 고려하여 10ms로 대폭 완화 (빠른 연타 허용)
        if (keyCode == lastKeyInputCode && currentTime - lastKeyInputTime < 10) {
            Log.d("MainActivity", "Ignoring extreme duplicate key input: $keyCode")
            return
        }
        lastKeyInputTime = currentTime
        lastKeyInputCode = keyCode
        
        // ViewModel에 이벤트 발생 알림 (SharedFlow)
        viewModel.onKeyEvent(keyCode)
        
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
        
        // 해당 배달 앱 실행
        val packageName = kr.disys.baedalin.model.Presets.getPackageName(presetName)
        if (packageName.isNotEmpty()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(launchIntent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "앱 실행 실패: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                android.widget.Toast.makeText(this, "앱이 설치되어 있지 않습니다: $packageName", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        viewModel.updateMappingVersion()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // 키가 눌렸을 때(Action Down)만 처리
        if (event.action == KeyEvent.ACTION_DOWN) {
            val uiState = viewModel.uiState.value
            
            // 매핑 위저드 중이거나 개별 녹화 중일 때는 모든 키(볼륨키 포함)를 가로챔
            if (uiState.isMappingWizardActive || 
                (viewModel.recordingFunction != null && viewModel.recordingClickType != null)) {
                
                // 시스템 키(홈, 최근 앱 등)를 제외한 나머지 키 처리
                if (keyCode != KeyEvent.KEYCODE_HOME && keyCode != KeyEvent.KEYCODE_APP_SWITCH) {
                    Log.d("MainActivity", "Intercepting key for mapping: $keyCode")
                    handleKeyCodeInput(keyCode)
                    return true // 이벤트를 소비하여 시스템 동작 방지
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, service).flattenToString()
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        
        if (enabledServices == null) return false
        
        return enabledServices.split(':').any { it.equals(expectedComponentName, ignoreCase = true) }
    }

    private fun checkBatteryOptimization(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
