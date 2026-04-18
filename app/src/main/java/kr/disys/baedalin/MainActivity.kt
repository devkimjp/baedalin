package kr.disys.baedalin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            BaedalinTheme {
                val context = LocalContext.current
                var isAccessibilityEnabled by remember { mutableStateOf(false) }
                var isOverlayEnabled by remember { mutableStateOf(false) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isAccessibilityEnabled = isAccessibilityServiceEnabled(context, KeyMapperAccessibilityService::class.java)
                            isOverlayEnabled = Settings.canDrawOverlays(context)
                            Log.d("KeyMapper", "MainActivity ON_RESUME - Accessibility: $isAccessibilityEnabled, Overlay: $isOverlayEnabled")
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
                    Box {
                        MainScreen(
                            recordingFunction = recordingFunction,
                            recordingClickType = recordingClickType,
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
                        
                        if (showAppPicker) {
                            AppPickerDialog(
                                onDismiss = { showAppPicker = false },
                                onAppSelected = { pkgName ->
                                    saveCustomPackage(targetPresetForPicker!!, pkgName)
                                    showAppPicker = false
                                    loadPresetWithApp(targetPresetForPicker!!)
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
            loadPresetWithApp(preset)
        }
    }

    private fun loadPresetWithApp(presetName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val packageName = prefs.getString("${presetName}_custom_pkg", Presets.getPackageName(presetName)) ?: ""
        
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        
        if (launchIntent == null) {
            targetPresetForPicker = presetName
            showAppPicker = true
            Toast.makeText(this, "설치된 앱을 찾을 수 없어 앱 선택창을 띄웁니다.", Toast.LENGTH_SHORT).show()
            return
        }

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
        
        startActivity(launchIntent)
        
        presetList.forEach { info ->
            val intent = Intent(this, FloatingWidgetService::class.java).apply {
                action = FloatingWidgetService.ACTION_SHOW_WIDGET
                putExtra("preset_name", presetName)
                putExtra("function_name", info.function.name)
                putExtra("icon", info.icon)
                putExtra("tooltip", info.tooltip)
                putExtra("x", info.x)
                putExtra("y", info.y)
                putExtra("color", color)
            }
            startService(intent)
        }
        
        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_SHOW_WIDGET
            putExtra("is_settings", true)
        })
    }

    private fun saveCustomPackage(preset: String, pkgName: String) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit().putString("${preset}_custom_pkg", pkgName).apply()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (recordingFunction != null && recordingClickType != null) {
            val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
            
            // 다른 기능에서 이 키를 이미 매핑했는지 확인
            val conflict = DeliveryFunction.entries.find { 
                prefs.getInt("${it.name}_keycode", -1) == keyCode 
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
    }

    private fun saveMapping(function: DeliveryFunction, clickType: ClickType, keyCode: Int) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        // 중요: 동일한 키가 다른 기능에 이미 할당되어 있다면 해당 기록을 삭제하여 중복 동작 방지
        DeliveryFunction.entries.forEach { existingFunc ->
            val storedKey = prefs.getInt("${existingFunc.name}_keycode", -1)
            if (storedKey == keyCode) {
                editor.remove("${existingFunc.name}_keycode")
                editor.remove("${existingFunc.name}_clicktype")
                Log.d("KeyMapper", "Removed duplicate mapping for ${existingFunc.name} using key $keyCode")
            }
        }
        
        editor.putInt("${function.name}_keycode", keyCode)
            .putString("${function.name}_clicktype", clickType.name)
            .apply()
        
        Log.d("KeyMapper", "Saved new mapping: ${function.name} -> key $keyCode")
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
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        PermissionItem(
            title = "접근성 서비스 활성화",
            description = "리모컨 신호를 터치 동작으로 변환하기 위해 필요합니다.\n[배달인 키매퍼 서비스]를 '사용'으로 설정해주세요.",
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
    onStartRecording: (DeliveryFunction, ClickType) -> Unit
) {
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("배달인 키매퍼") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 상단 안내 메시지 영역 삭제 (레이아웃 흩트러짐 방지)


            HorizontalDivider()

            Text("프리셋 로드 (앱 실행 포함)", style = MaterialTheme.typography.titleMedium)
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PresetButton("배민설정", "BAEMIN")
                PresetButton("쿠팡설정", "COUPANG")
                PresetButton("요기요설정", "YOGIYO")
            }

            HorizontalDivider()

            Text("개별 기능 설정", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(DeliveryFunction.entries) { function ->
                    val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)
                    val keyCode = prefs.getInt("${function.name}_keycode", -1)
                    val keyText = if (keyCode != -1) " (Key: $keyCode)" else ""

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(function.label + keyText, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = {
                                    val intent = Intent(context, FloatingWidgetService::class.java).apply {
                                        action = FloatingWidgetService.ACTION_SHOW_WIDGET
                                        putExtra("function_name", function.name)
                                        putExtra("tooltip", function.label)
                                        putExtra("icon", getIconFor(function))
                                    }
                                    context.startService(intent)
                                }) { Text("위젯") }
                                
                                Button(
                                    onClick = { onStartRecording(function, ClickType.SINGLE) },
                                    colors = if (recordingFunction == function) ButtonDefaults.buttonColors(containerColor = Color.Red) else ButtonDefaults.buttonColors()
                                ) {
                                    Text("키입력")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.PresetButton(name: String, presetName: String) {
    val context = LocalContext.current
    Button(
        onClick = {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("load_preset", presetName)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        },
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}

fun getIconFor(function: DeliveryFunction): String = when(function) {
    DeliveryFunction.CALL_CHECK -> "✆"
    DeliveryFunction.ACCEPT -> "Ⓐ"
    DeliveryFunction.REJECT -> "Ⓡ"
    DeliveryFunction.PATH -> "Ⓟ"
    DeliveryFunction.ZOOM_IN -> "⊕"
    DeliveryFunction.ZOOM_OUT -> "⊖"
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
