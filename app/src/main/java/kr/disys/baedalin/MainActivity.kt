package kr.disys.baedalin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.service.KeyMapperAccessibilityService
import kr.disys.baedalin.ui.theme.BaedalinTheme

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
    private var selectedDeviceName by mutableStateOf("모든 장치 (전역 감시)")
    private var inputDevices by mutableStateOf<List<InputDeviceInfo>>(emptyList())
    private var showDevicePicker by mutableStateOf(false)
    
    // 매핑 변경 시 UI를 즉시 갱신하기 위한 상태
    private var mappingVersion by mutableIntStateOf(0)

    data class InputDeviceInfo(val name: String, val descriptor: String, val isConnected: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            BaedalinTheme {
                val context = LocalContext.current
                var isAccessibilityEnabled by remember { mutableStateOf(false) }
                var isOverlayEnabled by remember { mutableStateOf(false) }
                val isServiceRunning by FloatingWidgetService.isRunning.collectAsState()

                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isAccessibilityEnabled = isAccessibilityServiceEnabled(context, KeyMapperAccessibilityService::class.java)
                            isOverlayEnabled = Settings.canDrawOverlays(context)
                            Log.d("KeyMapper", "MainActivity ON_RESUME - Accessibility: $isAccessibilityEnabled, Overlay: $isOverlayEnabled, Service: $isServiceRunning")
                            
                            // 메인 화면 활성화 시 툴바 외 위젯 숨김 강제
                            if (isServiceRunning) {
                                context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                                    action = FloatingWidgetService.ACTION_HIDE_PRESETS
                                })
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                            mappingVersion = mappingVersion,
                            isServiceRunning = isServiceRunning,
                            onDeviceClick = { showDevicePicker = true },
                            onStartService = {
                                if (isServiceRunning) {
                                    startService(Intent(context, FloatingWidgetService::class.java).apply {
                                        action = FloatingWidgetService.ACTION_HIDE_ALL
                                    })
                                } else {
                                    val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
                                    val activePreset = prefs.getString("active_preset", "BAEMIN") ?: "BAEMIN"
                                    loadPreset(activePreset)
                                }
                            },
                            onUpdateMappingVersion = { mappingVersion++ },
                            onStartRecording = { func, type ->
                                if (recordingFunction == func && recordingClickType == type) {
                                    // 이미 해당 기능의 입력 모드라면 해제 (기존 키 유지)
                                    recordingFunction = null
                                    recordingClickType = null
                                    KeyRecordingState.isRecording = false
                                    Toast.makeText(context, "키 입력 모드가 해제되었습니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    recordingFunction = func
                                    recordingClickType = type
                                    KeyRecordingState.isRecording = true
                                    Toast.makeText(context, "${func.label}의 키 입력을 대기 중입니다...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        
                        if (showDevicePicker) {
                            DevicePickerDialog(
                                devices = inputDevices,
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
                Toast.makeText(this, "${function.label}의 키 입력을 대기 중입니다...", Toast.LENGTH_SHORT).show()
            }
        }

        // UI 갱신 요청 처리
        if (intent?.action == FloatingWidgetService.ACTION_UPDATE_UI) {
            mappingVersion++
        }
    }

    private fun loadPreset(presetName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("active_preset", presetName) }

        val presetList = when(presetName) {
            "BAEMIN" -> Presets.BAEMIN
            "COUPANG" -> Presets.COUPANG
            "YOGIYO" -> Presets.YOGIYO
            else -> Presets.BAEMIN
        }
        val color = Presets.getColor(presetName)

        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_HIDE_PRESETS
        })

        val prefix = selectedDeviceDescriptor ?: "GLOBAL"
        presetList.forEach { info ->
            val keycode = prefs.getInt("${prefix}_${info.function.name}_keycode", -1)
            val keyInfo = if (keycode != -1) {
                val keyName = KeyEvent.keyCodeToString(keycode).replace("KEYCODE_", "")
                "$keycode ($keyName)"
            } else null

            val intent = Intent(this, FloatingWidgetService::class.java).apply {
                action = FloatingWidgetService.ACTION_SHOW_WIDGET
                putExtra("preset_name", presetName)
                putExtra("function_name", info.function.name)
                putExtra("icon", info.icon)
                putExtra("tooltip", info.tooltip)
                putExtra("x", info.x)
                putExtra("y", info.y)
                putExtra("color", color)
                if (keyInfo != null) putExtra("key_info", keyInfo)
            }
            startService(intent)
        }
        
        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_SHOW_WIDGET
            putExtra("is_settings", true)
        })
        
        mappingVersion++ // 프리셋 로드 시에도 UI 갱신
    }

    private fun saveCustomPackage(preset: String, pkgName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit { putString("${preset}_custom_pkg", pkgName) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        return super.onKeyDown(keyCode, event)
    }

    private fun executeSaveMapping(func: DeliveryFunction, type: ClickType, keyCode: Int) {
        saveMapping(func, type, keyCode)
        
        Toast.makeText(this, "${func.label} 에 키코드 $keyCode 가 설정되었습니다.", Toast.LENGTH_SHORT).show()
        
        recordingFunction = null
        recordingClickType = null
        KeyRecordingState.isRecording = false

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
        selectedDeviceName = history.find { it.descriptor == selectedDeviceDescriptor }?.name ?: "모든 장치 (전역 감시)"
        mappingVersion++
    }

    private fun saveSelectedDevice(device: InputDeviceInfo?) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        if (device == null) {
            prefs.edit { remove("selected_device_descriptor") }
            selectedDeviceDescriptor = null
            selectedDeviceName = "모든 장치 (전역 감시)"
        } else {
            prefs.edit { putString("selected_device_descriptor", device.descriptor) }
            selectedDeviceDescriptor = device.descriptor
            selectedDeviceName = device.name
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
    mappingVersion: Int,
    isServiceRunning: Boolean,
    onDeviceClick: () -> Unit,
    onStartService: () -> Unit,
    onUpdateMappingVersion: () -> Unit,
    onStartRecording: (DeliveryFunction, ClickType) -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)

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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("감시할 입력 장치", style = MaterialTheme.typography.labelMedium)
                        Text(selectedDeviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                }
            }

            HorizontalDivider()

            Text("프리셋 로드 (위젯 표시 전용)", style = MaterialTheme.typography.titleMedium)
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PresetButton("배민프리셋", "BAEMIN")
                PresetButton("쿠팡프리셋", "COUPANG")
                PresetButton("요기요프리셋", "YOGIYO")
            }

            // 모드별 커스텀 위젯 관리 섹션
            val presets = listOf("BAEMIN", "COUPANG", "YOGIYO")
            
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
                                        modifier = Modifier.size(36.dp),
                                        shape = CircleShape,
                                        color = if (isRecording) Color.Red 
                                                else if (isMapped) MaterialTheme.colorScheme.primaryContainer 
                                                else MaterialTheme.colorScheme.surfaceVariant,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isServiceRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceRunning) "서비스 중지" else "서비스 시작",
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
    onDismiss: () -> Unit,
    onDeviceSelected: (MainActivity.InputDeviceInfo?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("입력 장치 선택") },
        text = {
            LazyColumn {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(null) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("모든 장치 (전역 감시)", style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }
                items(devices) { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val statusColor = if (device.isConnected) Color(0xFF4CAF50) else Color.Gray
                            val statusText = if (device.isConnected) "연결됨" else "연결 안 됨"
                            Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = device.descriptor, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
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
fun RowScope.PresetButton(name: String, presetName: String) {
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
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
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
            mappingVersion = 0,
            isServiceRunning = false,
            onDeviceClick = {},
            onStartService = {},
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
