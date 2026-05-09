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
import android.view.KeyEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("mappings", Context.MODE_PRIVATE) }
    
    val primaryColor = Color(0xFF3B82F6) // Stitch Pro Blue
    val accentColor = Color(0xFFF97316)  // Stitch Pro Orange

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text("달마링", fontWeight = FontWeight.ExtraBold, color = primaryColor) 
                },
                actions = {
                    IconButton(onClick = { viewModel.showDevicePicker = true }) {
                        Icon(
                            imageVector = Icons.Default.SettingsRemote, 
                            contentDescription = "장치 설정",
                            tint = if (uiState.selectedDeviceDescriptor != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color(0xFFF8FAFC)) // Soft background
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 서비스 상태 & 대형 스위치
            ServiceStatusCard(
                isEnabled = uiState.isMappingEnabled,
                deviceName = uiState.selectedDeviceName,
                onToggle = { viewModel.toggleService() },
                primaryColor = primaryColor
            )

            // 2. 빠른 실행 메뉴 (Grid)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MenuCard(
                    title = "매핑 마법사",
                    description = "단계별 설정",
                    icon = Icons.Default.AutoFixHigh,
                    color = accentColor,
                    onClick = { viewModel.openMappingWizard() },
                    modifier = Modifier.weight(1f)
                )
                MenuCard(
                    title = "공유/백업",
                    description = "설정 보내기",
                    icon = Icons.Default.CloudUpload,
                    color = primaryColor,
                    onClick = { /* Sharing Dialog handled by SharingSection logic below */ },
                    modifier = Modifier.weight(1f)
                )
            }

            // 3. 프리셋 선택
            Text("배달 앱 프리셋", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PresetItem("배민", R.drawable.ic_toolbar_baemin, "BAEMIN", viewModel)
                PresetItem("쿠팡", R.drawable.ic_toolbar_coupang, "COUPANG", viewModel)
                PresetItem("요기요", R.drawable.ic_yogiyo, "YOGIYO", viewModel)
            }

            // 4. 기능 리스트 (Compact)
            Text("개별 매핑 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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

            // 숨겨진 위젯 관리 (임시 공유 컴포넌트 활용)
            SharingSection(onUpdateMappingVersion = { viewModel.updateMappingVersion() })
        }
    }

    // Mapping Wizard Dialog
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
    onToggle: () -> Unit,
    primaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) primaryColor else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEnabled) "서비스 실행 중" else "서비스 중지됨",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = if (isEnabled) Color.White else Color.Black
                )
                Text(
                    text = "연결된 장치: $deviceName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEnabled) Color.White.copy(alpha = 0.8f) else Color.Gray
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF1E293B).copy(alpha = 0.3f),
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(description, fontSize = 12.sp, color = Color.Gray)
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
            .clickable { 
                val intent = Intent(context, MainActivity::class.java).apply {
                    putExtra("load_preset", presetName)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
                
                val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)
                val packageName = prefs.getString("${presetName}_custom_pkg", Presets.getPackageName(presetName)) ?: ""
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                } else {
                    Toast.makeText(context, "앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp)
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = iconResId),
                contentDescription = name,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(function.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                
                val devicePrefix = uiState.selectedDeviceDescriptor ?: "GLOBAL"
                val singleKey = prefs.getInt("${devicePrefix}_${function.name}_keycode", -1)
                if (singleKey != -1) {
                    val keyName = KeyEvent.keyCodeToString(singleKey).replace("KEYCODE_", "")
                    Text("버튼: $keyName", fontSize = 11.sp, color = Color(0xFF3B82F6))
                } else {
                    Text("설정된 버튼 없음", fontSize = 11.sp, color = Color.LightGray)
                }
            }
            
            // 기존 개별 설정용 미니 버튼 (위저드 외 수동 설정용)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MappingChip("S", function, ClickType.SINGLE, uiState, viewModel)
                MappingChip("D", function, ClickType.DOUBLE, uiState, viewModel)
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
            .size(28.dp)
            .clickable { viewModel.startRecording(function, type) },
        shape = CircleShape,
        color = if (isRecording) Color.Red else Color(0xFFF1F5F9)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label, 
                fontSize = 11.sp, 
                fontWeight = FontWeight.Bold,
                color = if (isRecording) Color.White else Color.Gray
            )
        }
    }
}
