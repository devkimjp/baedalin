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
import android.provider.Settings
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingWizard(
    onComplete: (DeliveryFunction, ClickType, Int) -> Unit,
    onDismiss: () -> Unit,
    getUnmappedFunctions: () -> List<DeliveryFunction>,
    recordedKeyCode: Int? = null
) {
    val context = LocalContext.current
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
        mutableStateOf(if (hasPermission && unmappedFunctions.isNotEmpty()) unmappedFunctions.first() else null) 
    }
    var selectedClickType by remember { mutableStateOf<ClickType?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            currentStepIdx = 0 // 권한 획득 후 새로운 activeSteps(1,2,3)의 0번 인덱스(기능 선택)로
        }
    }

    // 매핑 완료 후 다음 기능을 찾는 로직 (자동화)
    val moveToNextFunction = {
        val remaining = getUnmappedFunctions()
        if (remaining.isNotEmpty()) {
            selectedFunction = remaining.first()
            // 다음 기능 매핑 시에는 '키 입력(단계 2)'으로 바로 점프
            val keyStepIdx = activeSteps.indexOf(2)
            if (keyStepIdx != -1) currentStepIdx = keyStepIdx
        } else {
            onDismiss()
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
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "버튼 매핑 마법사",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    selectedFunction?.let {
                        Text(
                            text = "[${it.label}] 설정 중",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "닫기")
                }
            }

            // Step Indicator
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(totalSteps) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp) // 조금 더 두껍게
                            .clip(CircleShape)
                            .background(
                                when {
                                    index < currentStepIdx -> MaterialTheme.colorScheme.primary // 완료됨
                                    index == currentStepIdx -> kr.disys.baedalin.ui.theme.AccentOrange // 현재 단계 강조
                                    else -> MaterialTheme.colorScheme.surfaceVariant // 대기 중
                                }
                            )
                    )
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
                        onFunctionSelected = {
                            selectedFunction = it
                            if (currentStepIdx < totalSteps - 1) currentStepIdx++
                        }
                    )
                    2 -> KeyRecordingStep(
                        selectedFunction = selectedFunction!!,
                        recordedKeyCode = recordedKeyCode,
                        onNext = { if (currentStepIdx < totalSteps - 1) currentStepIdx++ }
                    )
                    3 -> ClickTypeSelectionStep(
                        onTypeSelected = {
                            selectedClickType = it
                            onComplete(selectedFunction!!, it, recordedKeyCode!!)
                            moveToNextFunction() // 매핑 완료 후 다음 기능으로!
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FunctionSelectionStep(onFunctionSelected: (DeliveryFunction) -> Unit) {
    Column {
        Text(
            "설정할 기능을 선택해주세요",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(300.dp)
        ) {
            items(DeliveryFunction.entries) { function ->
                Card(
                    onClick = { onFunctionSelected(function) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier.padding(16.dp).fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            function.label,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
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
    onNext: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
    ) {
        Icon(
            imageVector = if (recordedKeyCode == null) Icons.Default.SettingsRemote else Icons.Default.BluetoothConnected,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = if (recordedKeyCode == null) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (recordedKeyCode == null) "리모컨 버튼을 눌러주세요" else "[${selectedFunction.label}] 버튼이 인식되었습니다!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (recordedKeyCode == null) 
                "[${selectedFunction.label}] 기능에 연결할\n리모컨 버튼을 지금 눌러주세요." 
                else "입력된 키 코드: $recordedKeyCode",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (recordedKeyCode != null) {
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("다음 (클릭 방식 선택)", fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun ClickTypeSelectionStep(onTypeSelected: (ClickType) -> Unit) {
    Column {
        Text(
            "어떻게 눌렀을 때 동작할까요?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ClickTypeCard(
                title = "한 번 누르기",
                description = "일반적인 입력",
                icon = Icons.Default.TouchApp,
                onClick = { onTypeSelected(ClickType.SINGLE) },
                modifier = Modifier.weight(1f)
            )
            ClickTypeCard(
                title = "두 번 누르기",
                description = "빠르게 두 번",
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
            modifier = Modifier.size(80.dp),
            tint = if (hasPermission) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (hasPermission) "블루투스 준비 완료" else "블루투스 권한이 필요합니다",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "리모컨 장치를 정확하게 찾기 위해\n'근처 기기' 접근 권한이 필요합니다.\n이 권한이 있어야 시스템 장치를 제외할 수 있습니다.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(40.dp))
        
        if (!hasPermission) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("권한 허용하기", fontSize = 16.sp)
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
                    Text("시스템 설정에서 허용하기")
                }
            }
        } else {
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("다음 단계로", fontSize = 16.sp)
            }
        }
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
