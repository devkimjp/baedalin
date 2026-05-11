package kr.disys.baedalin.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import android.Manifest
import android.content.Intent
import android.content.Context
import android.provider.Settings
import android.net.Uri
import androidx.compose.ui.res.stringResource
import kr.disys.baedalin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingWizard(
    onComplete: (DeliveryFunction, ClickType, Int) -> Unit,
    onDismiss: () -> Unit,
    onResetRecording: () -> Unit,
    getUnmappedFunctions: () -> List<DeliveryFunction>,
    devicePrefix: String, // 추가
    recordedKeyCode: Int? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val bluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        null
    }

    var hasPermission by remember {
        mutableStateOf(
            bluetoothPermission == null || 
            ContextCompat.checkSelfPermission(context, bluetoothPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val unmappedFunctions = getUnmappedFunctions()
    
    // 권한 여부에 따라 실제 진행할 단계 정의 (0: 권한, 1: 기능선택, 2: 키입력, 3: 타입선택)
    val activeSteps = remember(hasPermission) {
        if (hasPermission) listOf(1, 2, 3) else listOf(0, 1, 2, 3)
    }
    
    var currentStepIdx by remember(activeSteps) { 
        mutableStateOf(0) 
    }
    
    val currentStep = activeSteps.getOrElse(currentStepIdx) { 1 }
    val totalSteps = activeSteps.size

    var selectedFunction by remember { 
        mutableStateOf<DeliveryFunction?>(null) 
    }
    var selectedClickType by remember { mutableStateOf<ClickType?>(null) }
    
    // 마지막으로 기록된 키 코드를 로컬에 유지 (저장 후 UI 초기화 시 '???' 방지)
    var lastRecordedKey by remember { mutableStateOf<Int?>(recordedKeyCode) }
    LaunchedEffect(recordedKeyCode) {
        if (recordedKeyCode != null) {
            lastRecordedKey = recordedKeyCode
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            currentStepIdx = 0 // 권한 획득 후 새로운 activeSteps(1,2,3)의 0번 인덱스(기능 선택)로
        }
    }

    var showContinueDialog by remember { mutableStateOf(false) }
    
    // 매핑 완료 후 다음 기능을 찾는 로직 (자동화)
    val moveToNextFunction = {
        val listStepIdx = activeSteps.indexOf(1)
        if (listStepIdx != -1) {
            currentStepIdx = listStepIdx
        } else {
            onDismiss()
        }
    }

    // 다음 버튼 매핑 연속 진행
    val startNextMapping = {
        val remaining = getUnmappedFunctions()
        if (remaining.isNotEmpty()) {
            selectedFunction = remaining.first()
            val keyStepIdx = activeSteps.indexOf(2)
            if (keyStepIdx != -1) currentStepIdx = keyStepIdx
        } else {
            moveToNextFunction()
        }
    }

    // 단계 변경 시 처리
    LaunchedEffect(currentStepIdx) {
        val step = activeSteps.getOrNull(currentStepIdx)
        if (step == 2) {
            // 키 입력 단계에 진입하면 기존 녹화된 키 초기화
            onResetRecording()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 56.dp, start = 24.dp, end = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 이전 버튼 (첫 단계가 아닐 때만 표시)
                if (currentStepIdx > 0) {
                    IconButton(onClick = { currentStepIdx-- }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "이전")
                    }
                } else {
                    // 간격 유지를 위한 더미 스페이스
                    Spacer(modifier = Modifier.width(48.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (currentStep == 1) {
                        Text(
                            text = "리모컨 키를 할당할 버튼을 선택하세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = kr.disys.baedalin.ui.theme.AccentOrange,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        selectedFunction?.let {
                            Text(
                                text = stringResource(it.labelResId),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }

            // Step Indicator (권한 단계가 아닐 때만 표시)
            val indicatorSteps = activeSteps.filter { it > 0 }
            val currentIndicatorIdx = indicatorSteps.indexOf(activeSteps[currentStepIdx])
            
            if (currentIndicatorIdx != -1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(indicatorSteps.size) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        index < currentIndicatorIdx -> MaterialTheme.colorScheme.primary
                                        index == currentIndicatorIdx -> kr.disys.baedalin.ui.theme.AccentOrange
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .clickable(enabled = index < currentIndicatorIdx) {
                                    val targetStep = indicatorSteps[index]
                                    val targetIdx = activeSteps.indexOf(targetStep)
                                    if (targetIdx != -1) currentStepIdx = targetIdx
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = currentStep,
                label = "wizardStep"
            ) { step ->
                when (step) {
                    0 -> BluetoothPermissionStep(
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            bluetoothPermission?.let { launcher.launch(it) }
                        },
                        onNext = { if (currentStepIdx < totalSteps - 1) currentStepIdx++ }
                    )
                    1 -> FunctionSelectionStep(
                        prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE),
                        devicePrefix = devicePrefix,
                        onFunctionSelected = {
                            selectedFunction = it
                            if (currentStepIdx < totalSteps - 1) currentStepIdx++
                        }
                    )
                    2 -> {
                        KeyRecordingStep(
                            selectedFunction = selectedFunction!!,
                            recordedKeyCode = recordedKeyCode,
                            onNext = { if (currentStepIdx < totalSteps - 1) currentStepIdx++ },
                            onTimeout = {
                                // 시간 초과 시 다시 목록으로 (Step 1)
                                val listStepIdx = activeSteps.indexOf(1)
                                if (listStepIdx != -1) currentStepIdx = listStepIdx
                            }
                        )
                        // 키가 입력되면 대기 화면 없이 즉시 다음 단계로 전진
                        LaunchedEffect(recordedKeyCode) {
                            if (recordedKeyCode != null) {
                                if (currentStepIdx < totalSteps - 1) currentStepIdx++
                            }
                        }
                    }
                    3 -> ClickTypeSelectionStep(
                        recordedKeyCode = lastRecordedKey,
                        onTypeSelected = { type ->
                            val func = selectedFunction!!
                            val code = recordedKeyCode!!
                            onComplete(func, type, code)
                            
                            // 매핑 완료 피드백 및 다음 매핑 진행 여부 확인
                            val keyName = android.view.KeyEvent.keyCodeToString(code).replace("KEYCODE_", "")
                            val funcLabel = context.getString(func.labelResId)
                            val typeLabel = if (type == ClickType.SINGLE) context.getString(R.string.wizard_click_single) else context.getString(R.string.wizard_click_double)
                            
                            /* 
                            android.widget.Toast.makeText(
                                context, 
                                "[$funcLabel] ${context.getString(R.string.wizard_complete_title)}: $keyName ($typeLabel)", 
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            */
                            
                            val remaining = getUnmappedFunctions()
                            if (remaining.isNotEmpty()) {
                                showContinueDialog = true
                            } else {
                                moveToNextFunction()
                            }
                        }
                    )
                }
            }

            if (showContinueDialog) {
                AlertDialog(
                    onDismissRequest = { 
                        showContinueDialog = false
                        moveToNextFunction()
                    },
                    title = { Text(stringResource(R.string.wizard_complete_title)) },
                    text = { 
                        val nextFunc = getUnmappedFunctions().firstOrNull()
                        val nextLabel = nextFunc?.let { context.getString(it.labelResId) } ?: ""
                        Column {
                            Text("현재 기능의 매핑이 성공적으로 저장되었습니다.")
                            if (nextLabel.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "다음으로 [$nextLabel] 매핑을 이어서 진행하시겠습니까?",
                                    fontWeight = FontWeight.Bold,
                                    color = kr.disys.baedalin.ui.theme.AccentOrange
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showContinueDialog = false
                                startNextMapping()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("계속하기", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showContinueDialog = false
                            moveToNextFunction()
                        }) {
                            Text(stringResource(R.string.wizard_btn_finish))
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FunctionSelectionStep(
    prefs: android.content.SharedPreferences,
    devicePrefix: String,
    onFunctionSelected: (DeliveryFunction) -> Unit
) {
    Column {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(400.dp)
        ) {
            items(DeliveryFunction.entries) { function ->
                val singleKey = prefs.getInt("${devicePrefix}_${function.name}_SINGLE_keycode", -1)
                val doubleKey = prefs.getInt("${devicePrefix}_${function.name}_DOUBLE_keycode", -1)
                val isAnyMapped = singleKey != -1 || doubleKey != -1

                Card(
                    onClick = { onFunctionSelected(function) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAnyMapped) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) 
                                       else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = if (isAnyMapped) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(function.labelResId),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        
                        if (isAnyMapped) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (singleKey != -1) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                }
                                if (doubleKey != -1) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(kr.disys.baedalin.ui.theme.AccentOrange))
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(kr.disys.baedalin.ui.theme.AccentOrange))
                                    }
                                }
                            }
                            
                            val keyLabel = when {
                                singleKey != -1 -> android.view.KeyEvent.keyCodeToString(singleKey).replace("KEYCODE_", "")
                                doubleKey != -1 -> android.view.KeyEvent.keyCodeToString(doubleKey).replace("KEYCODE_", "")
                                else -> ""
                            }
                            if (keyLabel.isNotEmpty()) {
                                Text(
                                    keyLabel, 
                                    fontSize = 10.sp, 
                                    color = MaterialTheme.colorScheme.primary, 
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KeyRecordingStep(
    selectedFunction: DeliveryFunction,
    recordedKeyCode: Int?,
    onNext: () -> Unit,
    onTimeout: () -> Unit // 시간 초과 콜백 추가
) {
    var timeLeft by remember { mutableStateOf(5) }
    
    // 카운트다운 로직
    LaunchedEffect(Unit) {
        while (timeLeft > 0 && recordedKeyCode == null) {
            kotlinx.coroutines.delay(1000)
            timeLeft--
        }
        if (timeLeft == 0 && recordedKeyCode == null) {
            onTimeout()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
    ) {
        // 타이머 원형 표시
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = timeLeft / 5f,
                modifier = Modifier.size(100.dp),
                strokeWidth = 8.dp,
                color = if (timeLeft > 1) MaterialTheme.colorScheme.primary else kr.disys.baedalin.ui.theme.AccentOrange
            )
            Text(
                text = timeLeft.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.wizard_press_button),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.wizard_press_desc, stringResource(selectedFunction.labelResId)),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ClickTypeSelectionStep(
    recordedKeyCode: Int?,
    onTypeSelected: (ClickType) -> Unit
) {
    Column {
        val keyName = recordedKeyCode?.let { android.view.KeyEvent.keyCodeToString(it).replace("KEYCODE_", "") } ?: "???"
        Text(
            "Code: $keyName\n${stringResource(R.string.wizard_click_type_title)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ClickTypeCard(
                title = stringResource(R.string.wizard_click_single),
                description = "Single",
                icon = Icons.Default.TouchApp,
                onClick = { onTypeSelected(ClickType.SINGLE) },
                modifier = Modifier.weight(1f)
            )
            ClickTypeCard(
                title = stringResource(R.string.wizard_click_double),
                description = "Double",
                icon = Icons.Default.AdsClick,
                onClick = { onTypeSelected(ClickType.DOUBLE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun BluetoothPermissionStep(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(100.dp), // 크기 확대
            tint = if (hasPermission) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (hasPermission) "준비가 완료되었습니다" else "연결 권한이 필요합니다",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "리모컨 인식을 위해 '근처 기기' 권한이 필요합니다.\n아래 버튼을 눌러 진행해 주세요.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!hasPermission) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("권한 허용하기", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                
                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("시스템 설정에서 허용하기", fontSize = 14.sp)
                }
            }
        } else {
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(80.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("다음 단계로 진행하기", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(40.dp)) // 하단 여백 추가하여 너무 붙지 않게 함
    }
}

@Composable
fun ClickTypeCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(160.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
