package kr.disys.baedalin.ui.components

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kr.disys.baedalin.ui.MainViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape

data class AppEntry(val name: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

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

@Composable
fun DevicePickerDialog(
    devices: List<MainViewModel.InputDeviceInfo>,
    selectedDescriptor: String?,
    onDismiss: () -> Unit,
    onDeviceSelected: (MainViewModel.InputDeviceInfo?) -> Unit
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
