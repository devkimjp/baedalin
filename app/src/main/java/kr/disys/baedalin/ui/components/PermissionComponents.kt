package kr.disys.baedalin.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

@Composable
fun PermissionWizard(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    isBluetoothEnabled: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onComplete: () -> Unit = {}
) {
    var currentStep by remember { 
        mutableStateOf(
            if (!isOverlayEnabled) 0 
            else if (!isAccessibilityEnabled) 1 
            else 2
        ) 
    }
    val totalSteps = 3
    val context = LocalContext.current

    // 모든 권한이 허용되면 완료 호출
    LaunchedEffect(isAccessibilityEnabled, isOverlayEnabled, isBluetoothEnabled) {
        if (isAccessibilityEnabled && isOverlayEnabled && isBluetoothEnabled) {
            onComplete()
        }
    }

    Scaffold(
        bottomBar = {
            Box(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                if (currentStep == 0 && isOverlayEnabled) {
                    Button(
                        onClick = { currentStep = 1 },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("다음 단계로", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (currentStep == 1 && isAccessibilityEnabled) {
                    Button(
                        onClick = { currentStep = 2 },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("다음 단계로", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (currentStep == 2 && isBluetoothEnabled) {
                    Button(
                        onClick = { onComplete() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("시작하기", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                },
                label = "stepAnimation"
            ) { step ->
                when (step) {
                    0 -> PermissionStepContent(
                        title = "다른 앱 위에 그리기",
                        description = "배달 앱 화면 위에 조작 버튼(위젯)을 띄우기 위해 이 권한이 꼭 필요합니다.\n설정 화면에서 '달마링'을 찾아 활성화해주세요.",
                        icon = Icons.Default.Layers,
                        isGranted = isOverlayEnabled,
                        onAction = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                            context.startActivity(intent)
                        }
                    )
                    1 -> PermissionStepContent(
                        title = "접근성 서비스 활성화",
                        description = "리모컨 버튼 신호를 실제 터치 동작으로 연결하기 위한 핵심 권한입니다.\n[설치된 앱] → [달마링 키매퍼]를 '사용'으로 켜주세요.",
                        icon = Icons.Default.TouchApp,
                        isGranted = isAccessibilityEnabled,
                        onAction = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                    2 -> PermissionStepContent(
                        title = "근처 기기 접근 권한",
                        description = "사용하시는 블루투스 리모컨의 이름을 확인하고 구분하기 위해 필요한 권한입니다.\n허용해주셔야 리모컨을 정확히 찾아낼 수 있습니다.",
                        icon = Icons.Default.Bluetooth,
                        isGranted = isBluetoothEnabled,
                        onAction = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                // 1차적으로 권한 요청 시도
                                onRequestBluetoothPermission()
                            }
                        },
                        onSecondaryAction = {
                            // 권한이 거부되었을 경우를 대비해 설정 화면으로 이동하는 옵션 제공
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionStepContent(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    onAction: () -> Unit,
    onSecondaryAction: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = if (isGranted) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (!isGranted) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("권한 설정하러 가기", fontSize = 16.sp)
                }

                onSecondaryAction?.let {
                    TextButton(
                        onClick = it,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("시스템 설정에서 직접 허용하기")
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFE8F5E9)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(8.dp))
                    Text("설정이 완료되었습니다!", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
