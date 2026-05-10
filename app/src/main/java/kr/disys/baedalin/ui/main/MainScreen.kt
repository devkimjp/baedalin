package kr.disys.baedalin.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.disys.baedalin.MainActivity
import kr.disys.baedalin.R
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.model.ShareConfig
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.util.ShareManager
import kr.disys.baedalin.ui.components.MappingWizard
import kr.disys.baedalin.ui.theme.AccentOrange
import kr.disys.baedalin.ui.theme.ErrorRed
import kr.disys.baedalin.ui.theme.SuccessGreen
import kr.disys.baedalin.ui.theme.BaedalinTheme
import android.view.KeyEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("mappings", Context.MODE_PRIVATE) }
    val isNight by FloatingWidgetService.isNightMode.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text("달마링", fontWeight = FontWeight.Black, fontSize = 26.sp, color = MaterialTheme.colorScheme.primary) 
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = { 
                        val intent = Intent(context, FloatingWidgetService::class.java).apply {
                            action = "ACTION_TOGGLE_THEME"
                        }
                        context.startService(intent)
                    }) {
                        Icon(
                            imageVector = if (isNight) Icons.Default.WbSunny else Icons.Default.NightsStay,
                            contentDescription = "테마 전환",
                            tint = if (isNight) Color(0xFFFBBF24) else Color(0xFF6366F1),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.showDevicePicker = true }) {
                        Icon(
                            imageVector = Icons.Default.SettingsRemote, 
                            contentDescription = "장치 설정",
                            tint = if (uiState.selectedDeviceDescriptor != null) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. 서비스 상태 & 대형 스위치
            ServiceStatusCard(
                isEnabled = uiState.isMappingEnabled,
                deviceName = uiState.selectedDeviceName,
                onToggle = { viewModel.toggleService() }
            )

            // 2. 빠른 실행 메뉴 (Grid)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MenuCard(
                    title = "매핑 시작",
                    description = "쉽게 설정하기",
                    icon = Icons.Default.AutoFixHigh,
                    color = AccentOrange,
                    onClick = { viewModel.openMappingWizard() },
                    modifier = Modifier.weight(1f)
                )
                MenuCard(
                    title = "백업/공유",
                    description = "설정 옮기기",
                    icon = Icons.Default.CloudUpload,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { viewModel.exportConfig() },
                    modifier = Modifier.weight(1f)
                )
            }

            // 3. 프리셋 선택
            Text("배달 앱 선택", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PresetItem("배민", R.drawable.ic_toolbar_baemin, "BAEMIN", viewModel)
                PresetItem("쿠팡", R.drawable.ic_toolbar_coupang, "COUPANG", viewModel)
                PresetItem("요기요", R.drawable.ic_yogiyo, "YOGIYO", viewModel)
            }

            // 4. 기능 리스트 (Compact)
            Text("버튼 확인/수정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(DeliveryFunction.entries) { function ->
                    FunctionMappingRow(
                        function = function,
                        uiState = uiState,
                        prefs = prefs,
                        viewModel = viewModel
                    )
                }
            }

            // 하단 섹션
            SharingSection(onUpdateMappingVersion = { viewModel.updateMappingVersion() })
        }
    }

    if (uiState.isMappingWizardActive) {
        MappingWizard(
            onComplete = { func, type, code -> viewModel.executeSaveMapping(func, type, code) },
            onDismiss = { viewModel.closeMappingWizard() },
            recordedKeyCode = uiState.pendingKeyCode
        )
    }
}

@Composable
fun ServiceStatusCard(
    isEnabled: Boolean,
    deviceName: String,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = if (!isEnabled) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)) else null
    ) {
        Row(
            modifier = Modifier.padding(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEnabled) "서비스 작동 중" else "서비스 꺼짐",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "장치: $deviceName",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.scale(1.5f), // 스위치 크기 확대
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.LightGray
                )
            )
        }
    }
}

@Composable
fun MenuCard(
    title: String,
    description: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(2.dp, color.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                color = color.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun RowScope.PresetItem(
    name: String,
    iconResId: Int,
    presetName: String,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(100.dp)
            .clickable { 
                val intent = Intent(context, MainActivity::class.java).apply {
                    putExtra("load_preset", presetName)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
            },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(12.dp)
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = iconResId),
                contentDescription = name,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun FunctionMappingRow(
    function: DeliveryFunction,
    uiState: MainUiState,
    prefs: android.content.SharedPreferences,
    viewModel: MainViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(function.label, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                
                val devicePrefix = uiState.selectedDeviceDescriptor ?: "GLOBAL"
                val singleKey = prefs.getInt("${devicePrefix}_${function.name}_keycode", -1)
                if (singleKey != -1) {
                    val keyName = KeyEvent.keyCodeToString(singleKey).replace("KEYCODE_", "")
                    Text("연결됨: $keyName", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                } else {
                    Text("버튼을 설정해 주세요", fontSize = 14.sp, color = Color.Gray)
                }
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MappingChip("단일", function, ClickType.SINGLE, uiState, viewModel)
                MappingChip("더블", function, ClickType.DOUBLE, uiState, viewModel)
            }
        }
    }
}

@Composable
fun MappingChip(
    label: String,
    function: DeliveryFunction,
    type: ClickType,
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val isRecording = uiState.recordingFunction == function && uiState.recordingClickType == type
    
    Surface(
        modifier = Modifier
            .height(44.dp)
            .widthIn(min = 60.dp)
            .clickable { viewModel.startRecording(function, type) },
        shape = RoundedCornerShape(12.dp),
        color = if (isRecording) ErrorRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(
                text = label, 
                fontSize = 14.sp, 
                fontWeight = FontWeight.Black,
                color = if (isRecording) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("설정 공유 및 백업", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val code = ShareManager.exportConfig(context)
                    if (code.isNotEmpty()) {
                        shareCode = ShareManager.createShareMessage(context, code)
                        showShareDialog = true
                    } else {
                        Toast.makeText(context, "공유할 좌표 설정이 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("보내기", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            
            Button(
                onClick = {
                    importCode = ""
                    showImportDialog = true
                },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("받기", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showShareDialog) {
        val deviceInfo = ShareManager.getDeviceInfo(context)
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text("내 설정 공유 코드", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("현재 기기 정보", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("모델: ${deviceInfo.model}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Text("해상도: ${deviceInfo.width}x${deviceInfo.height}")
                        }
                    }
                    Text("아래 내용을 복사해서 다른 분께 전달해 주세요.", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = shareCode,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("BaedalinConfig", shareCode)
                    clipboard.setPrimaryClip(clip)
                    
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareCode)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "설정 공유하기"))
                    showShareDialog = false
                }) { Text("복사 및 공유", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) { Text("닫기", fontSize = 16.sp) }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("설정 불러오기", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Text("전달받은 코드를 아래에 붙여넣어 주세요.", fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importCode,
                        onValueChange = { importCode = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        placeholder = { Text("코드를 여기에 붙여넣으세요") }
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
                        Toast.makeText(context, "코드가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("확인", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("취소", fontSize = 16.sp) }
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
            title = { Text("설정 적용 확인", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    if (isDifferent) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Text(
                                "주의: 보낸 기기와 해상도가 달라 위치가 어긋날 수 있습니다.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    Text("작성 기기: ${config.deviceInfo.model}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("해상도: ${config.deviceInfo.width}x${config.deviceInfo.height}")
                    Spacer(Modifier.height(12.dp))
                    Text("이 설정을 내 휴대폰에 적용할까요?", fontSize = 16.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (ShareManager.importConfig(context, importCode) != null) {
                        Toast.makeText(context, "설정이 성공적으로 적용되었습니다.", Toast.LENGTH_SHORT).show()
                        onUpdateMappingVersion()
                    }
                    showConfirmDialog = null
                }) { Text("지금 적용하기", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = null }) { Text("취소", fontSize = 16.sp) }
            }
        )
    }
}
