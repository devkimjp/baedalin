package kr.disys.baedalin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityManager
import android.hardware.input.InputManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material.icons.filled.*
import android.content.ClipData
import android.content.ClipboardManager
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.model.ShareConfig
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.service.KeyMapperAccessibilityService
import kr.disys.baedalin.ui.theme.BaedalinTheme
import kr.disys.baedalin.util.ShareManager
import androidx.compose.ui.res.painterResource

object KeyRecordingState {
    var isRecording: Boolean = false
}

class MainActivity : ComponentActivity() {
    
    private var recordingFunction by mutableStateOf<DeliveryFunction?>(null)
    private var recordingClickType by mutableStateOf<ClickType?>(null)
    
    private var showAppPicker by mutableStateOf(false)
    private var targetPresetForPicker by mutableStateOf<String?>(null)

    private var conflictFunction by mutableStateOf<DeliveryFunction?>(null)
    private var pendingKeyCode by mutableStateOf<Int?>(null)

    private var selectedDeviceDescriptor by mutableStateOf<String?>(null)
    private var selectedDeviceName by mutableStateOf("장치를 추가하세요")
    private var inputDevices by mutableStateOf<List<InputDeviceInfo>>(emptyList())
    private var showDevicePicker by mutableStateOf(false)
    private var shakeDeviceSelector by mutableIntStateOf(0)
    
    // 매핑 변경 시 UI를 즉시 갱신하기 위한 상태
    private var mappingVersion by mutableIntStateOf(0)

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { refreshDeviceList() }
        override fun onInputDeviceRemoved(deviceId: Int) { refreshDeviceList() }
        override fun onInputDeviceChanged(deviceId: Int) { refreshDeviceList() }
    }

    data class InputDeviceInfo(val name: String, val descriptor: String, val isConnected: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(deviceListener, null)
        
        // 앱 초기 실행 시 매핑 상태 초기화 (안전장치)
        // 앱 시작 시 상태를 강제로 false로 초기화하던 코드를 제거하여 이전 상태를 유지하거나 서비스와의 동기화 충돌을 방지함
        Log.d("KeyMapper", "MainActivity.onCreate - Resetting is_mapping_enabled to false")

        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            BaedalinTheme {
                val context = LocalContext.current
                val prefs = remember { context.getSharedPreferences("mappings", Context.MODE_PRIVATE) }
                var isMappingEnabled by remember { 
                    mutableStateOf(prefs.getBoolean("is_mapping_enabled", false)) 
                }
                // showResetDialog는 MainScreen 내부로 이동함
                
                // SharedPreferences 변경 리스너 등록
                DisposableEffect(prefs) {
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                        if (key == "is_mapping_enabled") {
                            isMappingEnabled = p.getBoolean("is_mapping_enabled", false)
                        }
                        // 매핑 관련 데이터가 변경되면 UI 갱신 (mappingVersion 증가)
                        mappingVersion++
                    }
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose {
                        prefs.unregisterOnSharedPreferenceChangeListener(listener)
                    }
                }

                var isAccessibilityEnabled by remember { mutableStateOf(false) }
                var isOverlayEnabled by remember { mutableStateOf(false) }

                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isAccessibilityEnabled = isAccessibilityServiceEnabled(context, KeyMapperAccessibilityService::class.java)
                            isOverlayEnabled = Settings.canDrawOverlays(context)
                            Log.d("KeyMapper", "MainActivity ON_RESUME - Accessibility: $isAccessibilityEnabled, Overlay: $isOverlayEnabled, Mapping: $isMappingEnabled")
                            
                            // 메인 화면 활성화 시 툴바 표시 동기화 (좌표 위젯은 숨김)
                            if (isMappingEnabled) {
                                context.startService(Intent(context, FloatingWidgetService::class.java).apply {
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

                if (!isAccessibilityEnabled || !isOverlayEnabled) {
                    PermissionWizard(
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        isOverlayEnabled = isOverlayEnabled
                    )
                } else {
                    // 권한 허용된 상태에서 홈 진입 시 장치 목록 로드
                    LaunchedEffect(Unit) {
                        refreshDeviceList()
                    }
                    
                        Box {
                            MainScreen(
                                recordingFunction = recordingFunction,
                                recordingClickType = recordingClickType,
                                selectedDeviceName = selectedDeviceName,
                                selectedDeviceDescriptor = selectedDeviceDescriptor,
                                inputDevices = inputDevices,
                                mappingVersion = mappingVersion,
                                isServiceRunning = isMappingEnabled,
                                shakeTrigger = shakeDeviceSelector,
                                onDeviceClick = { showDevicePicker = true },
                                onStartService = {
                                    val newStatus = !isMappingEnabled
                                    Log.d("KeyMapper", "onStartService clicked: changing mapping state to $newStatus")
                                    
                                    // Prefs 즉시 업데이트 (접근성 서비스 동기화용)
                                    context.getSharedPreferences("mappings", Context.MODE_PRIVATE).edit(commit = true) {
                                        putBoolean("is_mapping_enabled", newStatus)
                                    }

                                    if (!newStatus) {
                                        Log.d("KeyMapper", "Sending ACTION_HIDE_ALL to stop service")
                                        context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                                            action = FloatingWidgetService.ACTION_HIDE_ALL
                                        })
                                    } else {
                                        if (selectedDeviceDescriptor == null) {
                                            Log.d("KeyMapper", "Start failed: No device selected")
                                            shakeDeviceSelector++ // 깜빡임 효과 유발
                                            Toast.makeText(context, "장치를 먼저 선택해주세요!", Toast.LENGTH_SHORT).show()
                                            return@MainScreen
                                        }
                                        Log.d("KeyMapper", "Starting service via ACTION_START_SERVICE_ONLY")
                                        context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                                            action = FloatingWidgetService.ACTION_START_SERVICE_ONLY
                                        })
                                        Toast.makeText(context, "배달 매핑 서비스가 시작되었습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                    isMappingEnabled = newStatus
                                },
                                onResetSettings = {
                                    isMappingEnabled = false
                                },
                                onUpdateMappingVersion = { mappingVersion++ },
                            onStartRecording = { func, type ->
                                val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)
                                if (recordingFunction == func && recordingClickType == type) {
                                    // 이미 해당 기능의 입력 모드라면 해제 (기존 키 유지)
                                    recordingFunction = null
                                    recordingClickType = null
                                    KeyRecordingState.isRecording = false
                                    prefs.edit { putBoolean("is_recording", false) }
                                    Toast.makeText(context, "키 입력 모드가 해제되었습니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    recordingFunction = func
                                    recordingClickType = type
                                    KeyRecordingState.isRecording = true
                                    prefs.edit { putBoolean("is_recording", true) }
                                    Toast.makeText(context, "${func.label}의 키 입력을 대기 중입니다...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        
                        if (showDevicePicker) {
                            DevicePickerDialog(
                                devices = inputDevices,
                                selectedDescriptor = selectedDeviceDescriptor,
                                onDismiss = { showDevicePicker = false },
                                onDeviceSelected = { device ->
                                    saveSelectedDevice(device)
                                    showDevicePicker = false
                                }
                            )
                        }

                        if (showAppPicker) {
                            AppPickerDialog(
                                onDismiss = { showAppPicker = false },
                                onAppSelected = { pkgName ->
                                    saveCustomPackage(targetPresetForPicker!!, pkgName)
                                    showAppPicker = false
                                    loadPreset(targetPresetForPicker!!)
                                }
                            )
                        }

                        if (conflictFunction != null) {
                            AlertDialog(
                                onDismissRequest = { 
                                    conflictFunction = null
                                    KeyRecordingState.isRecording = false
                                    getSharedPreferences("mappings", Context.MODE_PRIVATE).edit { putBoolean("is_recording", false) }
                                },
                                title = { Text("키 중복 확인") },
                                text = { Text("'${conflictFunction?.label}' 기능에 이미 설정된 키입니다.\n현재 기능으로 변경하시겠습니까?") },
                                confirmButton = {
                                    Button(onClick = {
                                        executeSaveMapping(recordingFunction!!, recordingClickType!!, pendingKeyCode!!)
                                        conflictFunction = null
                                    }) { Text("변경") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { 
                                        conflictFunction = null
                                        recordingFunction = null
                                        recordingClickType = null
                                        KeyRecordingState.isRecording = false
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

        // 더블 클릭 연동: 자동 레코딩 모드 시작
        if (intent?.action == FloatingWidgetService.ACTION_START_RECORDING) {
            val funcName = intent.getStringExtra("function_name")
            val function = DeliveryFunction.entries.find { it.name == funcName }
            if (function != null) {
                recordingFunction = function
                recordingClickType = ClickType.SINGLE
                KeyRecordingState.isRecording = true
                getSharedPreferences("mappings", Context.MODE_PRIVATE).edit { putBoolean("is_recording", true) }
                Toast.makeText(this, "${function.label}의 키 입력을 대기 중입니다...", Toast.LENGTH_SHORT).show()
            }
        }

        // UI 갱신 요청 처리
        if (intent?.action == FloatingWidgetService.ACTION_UPDATE_UI) {
            mappingVersion++
        }

        // 접근성 서비스로부터 전달받은 레코딩된 키 처리
        if (intent?.action == "ACTION_KEY_RECORDED") {
            val keyCode = intent.getIntExtra("keycode", -1)
            Log.d("KeyMapper", "Received ACTION_KEY_RECORDED: $keyCode, func=$recordingFunction")
            if (keyCode != -1 && recordingFunction != null && recordingClickType != null) {
                val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
                val prefix = selectedDeviceDescriptor ?: "GLOBAL"
                
                val conflict = DeliveryFunction.entries.find { 
                    prefs.getInt("${prefix}_${it.name}_keycode", -1) == keyCode 
                }

                if (conflict != null && conflict != recordingFunction) {
                    conflictFunction = conflict
                    pendingKeyCode = keyCode
                } else {
                    executeSaveMapping(recordingFunction!!, recordingClickType!!, keyCode)
                }
            }
        }
    }

    private fun loadPreset(presetName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("active_preset", presetName) }

        // 서비스에 프리셋 로드 명령 전달 (서비스 내부에서 모든 위젯 표시 및 상태 변경 처리)
        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_LOAD_PRESET
            putExtra("preset_name", presetName)
        })
        
        mappingVersion++ // UI 갱신 트리거
    }

    private fun saveCustomPackage(preset: String, pkgName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("${preset}_custom_pkg", pkgName) }
    }

    override fun onDestroy() {
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(deviceListener)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("KeyMapper", "MainActivity.onKeyDown: keyCode=$keyCode, isRecording=${recordingFunction != null}")
        if (recordingFunction != null && recordingClickType != null) {
            val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
            val prefix = selectedDeviceDescriptor ?: "GLOBAL"
            
            // 다른 기능에서 이 키를 이미 매핑했는지 확인
            val conflict = DeliveryFunction.entries.find { 
                prefs.getInt("${prefix}_${it.name}_keycode", -1) == keyCode 
            }

            if (conflict != null && conflict != recordingFunction) {
                // 중복 발견 시 다이얼로그 호출을 위해 상태 저장
                conflictFunction = conflict
                pendingKeyCode = keyCode
                return true
            }

            // 중복 없으면 바로 저장
            executeSaveMapping(recordingFunction!!, recordingClickType!!, keyCode)
            return true
        }
        return false
    }

    private fun executeSaveMapping(func: DeliveryFunction, type: ClickType, keyCode: Int) {
        saveMapping(func, type, keyCode)
        
        Toast.makeText(this, "${func.label} 에 키코드 $keyCode 가 설정되었습니다.", Toast.LENGTH_SHORT).show()
        
        recordingFunction = null
        recordingClickType = null
        KeyRecordingState.isRecording = false
        getSharedPreferences("mappings", Context.MODE_PRIVATE).edit { putBoolean("is_recording", false) }

        val intent = Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_UPDATE_KEY
            putExtra("function_name", func.name)
            putExtra("keycode", keyCode)
        }
        startService(intent)
        
        mappingVersion++ // 매핑 저장 시 UI 갱신 트리거
    }

    private fun saveMapping(function: DeliveryFunction, clickType: ClickType, keyCode: Int) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val prefix = selectedDeviceDescriptor ?: "GLOBAL"
        
        prefs.edit {
            // 중요: 동일한 키와 '클릭 타입'이 모두 일치하는 경우에만 기존 기록을 삭제
            DeliveryFunction.entries.forEach { existingFunc ->
                val storedKey = prefs.getInt("${prefix}_${existingFunc.name}_keycode", -1)
                val storedType = prefs.getString("${prefix}_${existingFunc.name}_clicktype", "")
                
                if (storedKey == keyCode && storedType == clickType.name) {
                    remove("${prefix}_${existingFunc.name}_keycode")
                    remove("${prefix}_${existingFunc.name}_clicktype")
                    Log.d("KeyMapper", "Removed duplicate mapping for ${existingFunc.name} ($storedType) using key $keyCode")
                }
            }
            
            putInt("${prefix}_${function.name}_keycode", keyCode)
            putString("${prefix}_${function.name}_clicktype", clickType.name)
        }
        
        Log.d("KeyMapper", "Saved new mapping: ${function.name} ($clickType) -> key $keyCode")
    }

    private fun refreshDeviceList() {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("device_history", "[]") ?: "[]"
        
        // 간단한 수동 파싱 (장치명|식별자 형태의 리스트)
        val history = historyJson.removeSurrounding("[", "]")
            .split(";;;")
            .filter { it.isNotBlank() }
            .mapNotNull { 
                val parts = it.trim().split("|||")
                if (parts.size >= 2) InputDeviceInfo(parts[0], parts[1], false) else null
            }.toMutableList()

        val deviceIds = InputDevice.getDeviceIds()
        val currentConnected = mutableListOf<InputDeviceInfo>()
        
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val isExternalDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                device.isExternal
            } else {
                !device.isVirtual
            }
            if (isExternalDevice && (device.sources and InputDevice.SOURCE_KEYBOARD != 0)) {
                val info = InputDeviceInfo(device.name, device.descriptor, true)
                currentConnected.add(info)
                
                // 히스토리에 없으면 추가, 있으면 연결 상태 업데이트
                val index = history.indexOfFirst { it.descriptor == info.descriptor }
                if (index == -1) {
                    history.add(info)
                } else {
                    history[index] = info
                }
            }
        }

        // 히스토리 저장 (구분자 변경: ;;; 와 |||)
        val newHistoryJson = history.joinToString(prefix = "[", postfix = "]", separator = ";;;") { "${it.name}|||${it.descriptor}" }
        prefs.edit { putString("device_history", newHistoryJson) }

        inputDevices = history.sortedByDescending { it.isConnected }
        
        selectedDeviceDescriptor = prefs.getString("selected_device_descriptor", null)
        val selectedDevice = history.find { it.descriptor == selectedDeviceDescriptor }
        selectedDeviceName = selectedDevice?.name ?: "장치를 추가하세요"
        mappingVersion++
    }

    private fun saveSelectedDevice(device: InputDeviceInfo?) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        if (device == null) {
            prefs.edit { remove("selected_device_descriptor") }
            selectedDeviceDescriptor = null
            selectedDeviceName = "장치를 추가하세요"
        } else {
            prefs.edit { putString("selected_device_descriptor", device.descriptor) }
            selectedDeviceDescriptor = device.descriptor
            selectedDeviceName = device.name
            shakeDeviceSelector = 0 // 장치 선택 시 빨간색 알람 해제
        }
        Toast.makeText(this, "감시 장치 설정: $selectedDeviceName", Toast.LENGTH_SHORT).show()
        mappingVersion++
    }
}

@Composable
fun PermissionWizard(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "필수 권한 설정이 필요합니다",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "라이더님의 안전한 운행을 위해\n아래 두 가지 권한을 반드시 허용해주세요.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(40.dp))
        
        PermissionItem(
            title = "다른 앱 위에 그리기",
            description = "배달 앱 화면 위에 위젯을 띄우기 위해 필요합니다.",
            isGranted = isOverlayEnabled,
            onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                context.startActivity(intent)
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        PermissionItem(
            title = "접근성 서비스 활성화",
            description = "리모컨 신호를 터치 동작으로 변환하기 위해 필요합니다.\n[달마링 키매퍼 서비스]를 '사용'으로 설정해주세요.",
            isGranted = isAccessibilityEnabled,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        
        if (isAccessibilityEnabled && isOverlayEnabled) {
            Spacer(modifier = Modifier.height(40.dp))
            Button(
                onClick = { /* Will recompose to MainScreen automatically */ },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("시작하기", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isGranted) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = description, fontSize = 12.sp, lineHeight = 16.sp)
            }
            
            if (!isGranted) {
                Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("설정", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun AppPickerDialog(onDismiss: () -> Unit, onAppSelected: (String) -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val installedApps = remember {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { appInfo ->
                AppEntry(
                    name = appInfo.loadLabel(packageManager).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(packageManager)
                )
            }.sortedBy { it.name }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("배달 앱 선택") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(installedApps) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAppSelected(app.packageName) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = app.icon.toBitmap().asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = app.name, style = MaterialTheme.typography.bodyLarge)
                            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

data class AppEntry(val name: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    recordingFunction: DeliveryFunction?,
    recordingClickType: ClickType?,
    selectedDeviceName: String,
    selectedDeviceDescriptor: String?,
    inputDevices: List<MainActivity.InputDeviceInfo>,
    mappingVersion: Int,
    isServiceRunning: Boolean,
    shakeTrigger: Int,
    onDeviceClick: () -> Unit,
    onStartService: () -> Unit,
    onResetSettings: () -> Unit, // 추가
    onUpdateMappingVersion: () -> Unit,
    onStartRecording: (DeliveryFunction, ClickType) -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)
    var transparency by remember { mutableStateOf(prefs.getFloat("toolbar_transparency", 1.0f)) }
    var showResetDialog by remember { mutableStateOf(false) } // 내부로 이동

    // 매핑 정보가 하나라도 있는지 체크
    val hasAnyMapping = remember(mappingVersion, selectedDeviceDescriptor) {
        var found = false
        val devicePrefix = selectedDeviceDescriptor ?: "GLOBAL"
        
        // 1. 장치/글로벌 기반 매핑 확인 (개별 기능 설정)
        DeliveryFunction.entries.forEach { function ->
            if (prefs.getInt("${devicePrefix}_${function.name}_keycode", -1) != -1) found = true
        }
        
        // 2. 프리셋 기반 매핑 확인 (툴바 위젯)
        if (!found) {
            val presets = listOf("BAEMIN", "COUPANG", "YOGIYO")
            presets.forEach { preset ->
                // 기본 위젯 확인
                Presets.BAEMIN.forEach { info ->
                    if (prefs.getInt("${preset}_${info.function.name}_SINGLE", -1) != -1) found = true
                }
                // 커스텀 위젯 확인
                if (!found) {
                    val active = prefs.getString("${preset}_active_custom_widgets", "") ?: ""
                    active.split(",").filter { it.isNotBlank() }.forEach { label ->
                        if (prefs.getInt("${preset}_CUSTOM_${label}_SINGLE", -1) != -1) found = true
                    }
                }
            }
        }
        found
    }

    // 장치 선택 강조 애니메이션 (깜빡임)
    val shakeColor by animateColorAsState(
        targetValue = if (shakeTrigger > 0 && shakeTrigger % 2 != 0) Color.Red.copy(alpha = 0.3f) 
                     else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        animationSpec = repeatable(
            iterations = 3,
            animation = tween(durationMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeColor"
    )

    // 실제 깜빡임을 위해 shakeTrigger가 변경될 때마다 애니메이션을 리셋하거나 특정 로직 수행 가능
    // 여기서는 단순 색상 변화로 구현

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("달마링 키매퍼") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 장치 선택 섹션
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onDeviceClick() },
                colors = CardDefaults.cardColors(containerColor = if (shakeTrigger > 0) shakeColor else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                border = if (shakeTrigger > 0 && shakeTrigger % 2 != 0) BorderStroke(2.dp, Color.Red) else null
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("감시할 입력 장치", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selectedDeviceDescriptor != null) {
                                val isConnected = inputDevices.find { it.descriptor == selectedDeviceDescriptor }?.isConnected ?: false
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(if (isConnected) Color(0xFF4CAF50) else Color.Gray, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(selectedDeviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                }
            }

            HorizontalDivider()

            Text("프리셋 로드 (위젯 표시 전용)", style = MaterialTheme.typography.titleMedium)
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PresetButton("배민프리셋", "BAEMIN", R.drawable.ic_toolbar_baemin)
                PresetButton("쿠팡프리셋", "COUPANG", R.drawable.ic_toolbar_coupang)
            }

            // 모드별 커스텀 위젯 관리 섹션
            val presets = listOf("BAEMIN", "COUPANG")
            
            presets.forEach { presetName ->
                val listKey = "${presetName}_active_custom_widgets"
                val activeWidgets = remember(mappingVersion) {
                    prefs.getString(listKey, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                }

                if (activeWidgets.isNotEmpty()) {
                    val modeLabel = when(presetName) {
                        "BAEMIN" -> "배민"
                        "COUPANG" -> "쿠팡"
                        "YOGIYO" -> "요기요"
                        else -> presetName
                    }
                    val modeColor = Color(Presets.getColor(presetName))

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("$modeLabel 커스텀 위젯", style = MaterialTheme.typography.titleMedium, color = modeColor)
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeWidgets.forEach { label ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = modeColor.copy(alpha = 0.1f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier.size(32.dp).background(modeColor, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("사용자 위젯 $label", style = MaterialTheme.typography.bodyLarge)
                                    }
                                    IconButton(onClick = {
                                        val newList = activeWidgets.filter { it != label }.joinToString(",")
                                        prefs.edit { putString(listKey, newList) }
                                        
                                        // 서비스에 위젯 제거 명령 전달
                                        val intent = Intent(context, FloatingWidgetService::class.java).apply {
                                            action = FloatingWidgetService.ACTION_HIDE_WIDGET
                                            putExtra("function_name", "${presetName}_CUSTOM_$label")
                                        }
                                        context.startService(intent)
                                        
                                        Toast.makeText(context, "$modeLabel 위젯 $label 삭제됨", Toast.LENGTH_SHORT).show()
                                        onUpdateMappingVersion()
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            SharingSection(
                onUpdateMappingVersion = onUpdateMappingVersion
            )

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("툴바 투명도: ${(transparency * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = transparency,
                    onValueChange = { 
                        transparency = it
                        prefs.edit { putFloat("toolbar_transparency", it) }
                        val intent = Intent(context, FloatingWidgetService::class.java).apply {
                            action = FloatingWidgetService.ACTION_UPDATE_TRANSPARENCY
                            putExtra("transparency", it)
                        }
                        context.startService(intent)
                    },
                    valueRange = 0.2f..1.0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            // 초기화 구역 추가
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("데이터 초기화", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { showResetDialog = true }, // 대화상자 표시
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("초기화", color = Color.White)
                }
            }

            // 초기화 확인 대화상자 구현
            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("데이터 초기화") },
                    text = { Text("좌표값과 키매핑정보를 모두 초기화 합니다.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showResetDialog = false
                                // 1. 데이터 삭제
                                prefs.edit { 
                                    clear() 
                                    putBoolean("is_mapping_enabled", false) // 명시적으로 false 설정
                                    commit()
                                }
                                
                                // 2. 서비스 중지 (실행 중일 경우)
                                val intent = Intent(context, FloatingWidgetService::class.java).apply {
                                    action = FloatingWidgetService.ACTION_HIDE_ALL
                                }
                                context.startService(intent)
                                
                                // 3. UI 상태 갱신
                                onResetSettings()
                                onUpdateMappingVersion()
                                
                                Toast.makeText(context, "모든 설정이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("확인", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text("취소")
                        }
                    }
                )
            }

            HorizontalDivider()

            Text("개별 기능 설정", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(DeliveryFunction.entries) { function ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(function.label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                
                                val mappings = remember(function, selectedDeviceDescriptor, mappingVersion) {
                                    val prefix = selectedDeviceDescriptor ?: "GLOBAL"
                                    listOf(ClickType.SINGLE, ClickType.DOUBLE).mapNotNull { type ->
                                        val k = prefs.getInt("${prefix}_${function.name}_keycode", -1)
                                        val t = prefs.getString("${prefix}_${function.name}_clicktype", "")
                                        if (k != -1 && t == type.name) {
                                            val keyName = KeyEvent.keyCodeToString(k).replace("KEYCODE_", "")
                                            "${if(type == ClickType.SINGLE) "S" else "D"}:$k ($keyName)"
                                        } else null
                                    }.joinToString(" ")
                                }
                                if (mappings.isNotEmpty()) {
                                    Text(mappings, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(ClickType.SINGLE, ClickType.DOUBLE).forEach { type ->
                                    val isRecording = recordingFunction == function && recordingClickType == type
                                    val prefix = selectedDeviceDescriptor ?: "GLOBAL"
                                    val isMapped = remember(function, selectedDeviceDescriptor, type, mappingVersion) {
                                        prefs.getInt("${prefix}_${function.name}_keycode", -1) != -1 && 
                                        prefs.getString("${prefix}_${function.name}_clicktype", "") == type.name
                                    }

                                    Surface(
                                        onClick = { onStartRecording(function, type) },
                                        enabled = !isServiceRunning, // 실제 배달 모드일 때만 비활성화
                                        modifier = Modifier.size(36.dp),
                                        shape = CircleShape,
                                        color = if (isRecording) Color.Red 
                                                else if (isMapped) {
                                                    if (isServiceRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                    else MaterialTheme.colorScheme.primaryContainer
                                                }
                                                else {
                                                    if (isServiceRunning) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                },
                                        tonalElevation = 2.dp
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = if (type == ClickType.SINGLE) "S" else "D",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isRecording) Color.White 
                                                        else if (isMapped) MaterialTheme.colorScheme.onPrimaryContainer 
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 서비스 시작/중지 버튼 (맨 하단 고정)
            Button(
                onClick = onStartService,
                enabled = hasAnyMapping || isServiceRunning, // 실행 중일 때는 중지를 위해 항상 활성화, 아니면 매핑이 있어야 활성화
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error 
                                    else if (!hasAnyMapping) Color.Gray 
                                    else MaterialTheme.colorScheme.primary,
                    contentColor = if (isServiceRunning) MaterialTheme.colorScheme.onError else Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceRunning) "서비스 중지" 
                           else if (!hasAnyMapping) "매핑 정보 없음" 
                           else "서비스 시작",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun DevicePickerDialog(
    devices: List<MainActivity.InputDeviceInfo>,
    selectedDescriptor: String?,
    onDismiss: () -> Unit,
    onDeviceSelected: (MainActivity.InputDeviceInfo?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("입력 장치 선택") },
        text = {
            LazyColumn {
                items(devices) { device ->
                    val isSelected = device.descriptor == selectedDescriptor
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val statusColor = if (device.isConnected) Color(0xFF4CAF50) else Color.Gray
                            val statusText = when {
                                isSelected && device.isConnected -> "사용 중 (연결됨)"
                                isSelected && !device.isConnected -> "연결 대기 중..."
                                device.isConnected -> "연결됨"
                                else -> "연결 안 됨"
                            }
                            
                            Text(text = device.name, style = MaterialTheme.typography.bodyLarge, 
                                 fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = statusText, style = MaterialTheme.typography.bodySmall, 
                                     color = if (isSelected && !device.isConnected) MaterialTheme.colorScheme.error else statusColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = device.descriptor.take(12) + "...", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "선택됨",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun RowScope.PresetButton(name: String, presetName: String, iconResId: Int) {
    val context = LocalContext.current
    Button(
        onClick = {
            // 1. 위젯 표시 (프리셋 로드)
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("load_preset", presetName)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            
            // 2. 해당 앱 실행 (서비스 시작과는 독립적으로 동작)
            (context as? MainActivity)?.let { activity ->
                val prefs = activity.getSharedPreferences("mappings", Context.MODE_PRIVATE)
                val packageName = prefs.getString("${presetName}_custom_pkg", Presets.getPackageName(presetName)) ?: ""
                val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    activity.startActivity(launchIntent)
                } else {
                    Toast.makeText(activity, "앱을 찾을 수 없습니다: $packageName", Toast.LENGTH_SHORT).show()
                }
            }
        },
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = iconResId),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}

fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { 
        it.resolveInfo.serviceInfo.packageName == context.packageName && 
        it.resolveInfo.serviceInfo.name == service.name 
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    BaedalinTheme {
        MainScreen(
            recordingFunction = null,
            recordingClickType = null,
            selectedDeviceName = "테스트 장치",
            selectedDeviceDescriptor = null,
            inputDevices = emptyList(),
            mappingVersion = 0,
            isServiceRunning = false,
            shakeTrigger = 0,
            onDeviceClick = {},
            onStartService = {},
            onResetSettings = {},
            onUpdateMappingVersion = {},
            onStartRecording = { _, _ -> }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PermissionWizardPreview() {
    BaedalinTheme {
        PermissionWizard(isAccessibilityEnabled = false, isOverlayEnabled = true)
    }
}
@Composable
fun SharingSection(
    onUpdateMappingVersion: () -> Unit
) {
    val context = LocalContext.current
    var showShareDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf<ShareConfig?>(null) }
    var shareCode by remember { mutableStateOf("") }
    var importCode by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("좌표 공유 및 백업", style = MaterialTheme.typography.titleMedium)
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val code = ShareManager.exportConfig(context)
                    shareCode = ShareManager.createShareMessage(context, code)
                    showShareDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("설정 공유하기")
            }
            
            Button(
                onClick = {
                    importCode = ""
                    showImportDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("설정 불러오기")
            }
        }
    }

    if (showShareDialog) {
        val deviceInfo = ShareManager.getDeviceInfo(context)
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text("내 설정 공유 코드") },
            text = {
                Column {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("현재 기기 정보", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("모델: ${deviceInfo.model}", fontWeight = FontWeight.Bold)
                            Text("해상도: ${deviceInfo.width}x${deviceInfo.height} (${deviceInfo.dpi}dpi)")
                        }
                    }
                    Text("아래 안내 문구를 포함한 전체 내용을 복사하여 다른 고객님께 보내주세요. 받는 분은 이 전체 내용을 그대로 붙여넣기만 하면 됩니다.", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = shareCode,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("BaedalinConfig", shareCode)
                    clipboard.setPrimaryClip(clip)
                    
                    // 앱 선택 공유 기능 추가
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareCode)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "설정 공유하기"))
                    
                    Toast.makeText(context, "코드가 복사되었으며 공유 창이 열립니다.", Toast.LENGTH_SHORT).show()
                    showShareDialog = false
                }) { Text("공유/복사하기") }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) { Text("닫기") }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("설정 불러오기") },
            text = {
                Column {
                    Text("공유받은 코드를 아래에 붙여넣으세요.", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importCode,
                        onValueChange = { importCode = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        placeholder = { Text("이곳에 붙여넣으세요", fontSize = 12.sp) }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        val base64Code = ShareManager.extractBase64(importCode)
                        val json = String(android.util.Base64.decode(base64Code, android.util.Base64.NO_WRAP))
                        val config = ShareConfig.fromJSONString(json)
                        showConfirmDialog = config
                        showImportDialog = false
                    } catch (e: Exception) {
                        Toast.makeText(context, "유효한 공유 코드를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("취소") }
            }
        )
    }

    if (showConfirmDialog != null) {
        val config = showConfirmDialog!!
        val currentDevice = ShareManager.getDeviceInfo(context)
        val isDifferent = config.deviceInfo.model != currentDevice.model || 
                          config.deviceInfo.width != currentDevice.width || 
                          config.deviceInfo.height != currentDevice.height

        AlertDialog(
            onDismissRequest = { showConfirmDialog = null },
            title = { Text("불러오기 확인") },
            text = {
                Column {
                    if (isDifferent) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                "주의: 공유된 설정의 기기와 현재 기기의 해상도가 다릅니다. 위젯 위치가 어긋날 수 있습니다.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Text("작성 기기: ${config.deviceInfo.model}", fontWeight = FontWeight.Bold)
                    Text("해상도: ${config.deviceInfo.width}x${config.deviceInfo.height} (${config.deviceInfo.dpi}dpi)")
                    Spacer(Modifier.height(8.dp))
                    Text("위 설정을 현재 기기에 적용하시겠습니까?\n(기존 좌표 정보가 모두 덮어써집니다.)")
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (ShareManager.importConfig(context, importCode) != null) {
                        Toast.makeText(context, "설정이 성공적으로 적용되었습니다.", Toast.LENGTH_SHORT).show()
                        onUpdateMappingVersion()
                    } else {
                        Toast.makeText(context, "설정 적용에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    showConfirmDialog = null
                }) { Text("적용하기") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = null }) { Text("취소") }
            }
        )
    }
}
