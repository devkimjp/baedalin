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
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingWizard(
    onComplete: (DeliveryFunction, ClickType, Int) -> Unit,
    onDismiss: () -> Unit,
    getUnmappedFunctions: () -> List<DeliveryFunction>,
    recordedKeyCode: Int? = null
) {
    var currentStep by remember { mutableStateOf(0) }
    var selectedFunction by remember { mutableStateOf<DeliveryFunction?>(null) }
    var selectedClickType by remember { mutableStateOf<ClickType?>(null) }
    
    val totalSteps = 3

    // 매핑 완료 후 다음 기능을 찾는 로직
    val moveToNextFunction = {
        val remaining = getUnmappedFunctions()
        if (remaining.isNotEmpty()) {
            selectedFunction = remaining.first()
            currentStep = 1 // 키 입력 단계로 바로 이동
        } else {
            onDismiss() // 더 이상 설정할 기능이 없으면 닫기
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
                Text(
                    text = "버튼 매핑 마법사",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
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
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (index <= currentStep) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.surfaceVariant
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
                    0 -> FunctionSelectionStep(
                        onFunctionSelected = {
                            selectedFunction = it
                            currentStep = 1
                        }
                    )
                    1 -> KeyRecordingStep(
                        selectedFunction = selectedFunction!!,
                        recordedKeyCode = recordedKeyCode,
                        onNext = { currentStep = 2 }
                    )
                    2 -> ClickTypeSelectionStep(
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
            text = if (recordedKeyCode == null) "리모컨 버튼을 눌러주세요" else "버튼이 인식되었습니다!",
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
