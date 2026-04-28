package kr.disys.baedalin.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kr.disys.baedalin.MainActivity
import kr.disys.baedalin.R
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.model.ShareConfig
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.ui.MainViewModel
import kr.disys.baedalin.util.ShareManager
import android.view.KeyEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mappings", Context.MODE_PRIVATE) }
    var transparency by remember { mutableStateOf(prefs.getFloat("toolbar_transparency", 1.0f)) }

    // 매핑 정보가 하나라도 있는지 체크
    val hasAnyMapping = remember(viewModel.mappingVersion, viewModel.selectedDeviceDescriptor) {
        var found = false
        val devicePrefix = viewModel.selectedDeviceDescriptor ?: "GLOBAL"
        
        // 1. 장치/글로벌 기반 매핑 확인 (개별 기능 설정)
        DeliveryFunction.entries.forEach { function ->
            if (prefs.getInt("${devicePrefix}_${function.name}_keycode", -1) != -1) found = true
        }
        
        // 2. 프리셋 기반 매핑 확인 (툴바 위젯)
        if (!found) {
            val presets = listOf("BAEMIN", "COUPANG", "YOGIYO")
            presets.forEach { preset ->
                Presets.BAEMIN.forEach { info ->
                    if (prefs.getInt("${preset}_${info.function.name}_SINGLE", -1) != -1) found = true
                }
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

    val shakeColor by animateColorAsState(
        targetValue = if (viewModel.shakeDeviceSelector > 0 && viewModel.shakeDeviceSelector % 2 != 0) Color.Red.copy(alpha = 0.3f) 
                     else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        animationSpec = repeatable(
            iterations = 3,
            animation = tween(durationMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeColor"
    )

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
                modifier = Modifier.fillMaxWidth().clickable { viewModel.showDevicePicker = true },
                colors = CardDefaults.cardColors(containerColor = if (viewModel.shakeDeviceSelector > 0) shakeColor else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                border = if (viewModel.shakeDeviceSelector > 0 && viewModel.shakeDeviceSelector % 2 != 0) BorderStroke(2.dp, Color.Red) else null
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("감시할 입력 장치", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (viewModel.selectedDeviceDescriptor != null) {
                                val isConnected = viewModel.inputDevices.find { it.descriptor == viewModel.selectedDeviceDescriptor }?.isConnected ?: false
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(if (isConnected) Color(0xFF4CAF50) else Color.Gray, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(viewModel.selectedDeviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
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
            val presetsList = listOf("BAEMIN", "COUPANG")
            
            presetsList.forEach { presetName ->
                val listKey = "${presetName}_active_custom_widgets"
                val activeWidgets = remember(viewModel.mappingVersion) {
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
                                        
                                        context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                                            action = FloatingWidgetService.ACTION_HIDE_WIDGET
                                            putExtra("function_name", "${presetName}_CUSTOM_$label")
                                        })
                                        
                                        Toast.makeText(context, "$modeLabel 위젯 $label 삭제됨", Toast.LENGTH_SHORT).show()
                                        viewModel.updateMappingVersion()
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
                onUpdateMappingVersion = { viewModel.updateMappingVersion() }
            )

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("툴바 투명도: ${(transparency * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = transparency,
                    onValueChange = { 
                        transparency = it
                        prefs.edit { putFloat("toolbar_transparency", it) }
                        context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                            action = FloatingWidgetService.ACTION_UPDATE_TRANSPARENCY
                            putExtra("transparency", it)
                        })
                    },
                    valueRange = 0.2f..1.0f,
                    modifier = Modifier.fillMaxWidth()
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
                                
                                val mappings = remember(function, viewModel.selectedDeviceDescriptor, viewModel.mappingVersion) {
                                    val prefix = viewModel.selectedDeviceDescriptor ?: "GLOBAL"
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
                                    val isRecording = viewModel.recordingFunction == function && viewModel.recordingClickType == type
                                    val prefix = viewModel.selectedDeviceDescriptor ?: "GLOBAL"
                                    val isMapped = remember(function, viewModel.selectedDeviceDescriptor, type, viewModel.mappingVersion) {
                                        prefs.getInt("${prefix}_${function.name}_keycode", -1) != -1 && 
                                        prefs.getString("${prefix}_${function.name}_clicktype", "") == type.name
                                    }

                                    Surface(
                                        onClick = { viewModel.startRecording(function, type) },
                                        enabled = !viewModel.isMappingEnabled,
                                        modifier = Modifier.size(36.dp),
                                        shape = CircleShape,
                                        color = if (isRecording) Color.Red 
                                                else if (isMapped) {
                                                    if (viewModel.isMappingEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                    else MaterialTheme.colorScheme.primaryContainer
                                                }
                                                else {
                                                    if (viewModel.isMappingEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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

            Button(
                onClick = { viewModel.toggleService() },
                enabled = hasAnyMapping || viewModel.isMappingEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isMappingEnabled) MaterialTheme.colorScheme.error 
                                    else if (!hasAnyMapping) Color.Gray 
                                    else MaterialTheme.colorScheme.primary,
                    contentColor = if (viewModel.isMappingEnabled) MaterialTheme.colorScheme.onError else Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(
                    imageVector = if (viewModel.isMappingEnabled) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (viewModel.isMappingEnabled) "서비스 중지" 
                           else if (!hasAnyMapping) "매핑 정보 없음" 
                           else "서비스 시작",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            if (viewModel.isMappingEnabled) {
                OutlinedButton(
                    onClick = {
                        val currentVisible = prefs.getBoolean("toolbar_visible", true)
                        val nextVisible = !currentVisible
                        prefs.edit { putBoolean("toolbar_visible", nextVisible) }
                        
                        context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                            action = FloatingWidgetService.ACTION_SET_TOOLBAR_VISIBILITY
                            putExtra("visible", nextVisible)
                        })
                        viewModel.updateMappingVersion()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    val isVisible = prefs.getBoolean("toolbar_visible", true)
                    Icon(imageVector = if (isVisible) Icons.Default.Close else Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isVisible) "툴바 임시 숨기기" else "숨겨진 툴바 보이기")
                }
            }
        }
    }
}

@Composable
fun RowScope.PresetButton(name: String, presetName: String, iconResId: Int) {
    val context = LocalContext.current
    Button(
        onClick = {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("load_preset", presetName)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            
            val activity = context as? MainActivity
            val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)
            val packageName = prefs.getString("${presetName}_custom_pkg", Presets.getPackageName(presetName)) ?: ""
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
            } else {
                Toast.makeText(context, "앱을 찾을 수 없습니다: $packageName", Toast.LENGTH_SHORT).show()
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
