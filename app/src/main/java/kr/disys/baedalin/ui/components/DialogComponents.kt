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
import kr.disys.baedalin.ui.main.MainViewModel
import kr.disys.baedalin.ui.main.InputDeviceInfo
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
    devices: List<InputDeviceInfo>,
    selectedDescriptor: String?,
    onDismiss: () -> Unit,
    onDeviceSelected: (InputDeviceInfo?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("연결할 장치 선택", fontWeight = FontWeight.Black, fontSize = 22.sp) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(devices) { device ->
                    val isSelected = device.descriptor == selectedDescriptor
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) }
                            .padding(vertical = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val statusColor = if (device.isConnected) Color(0xFF22C55E) else Color.Gray
                                val statusText = when {
                                    isSelected && device.isConnected -> "사용 중 (연결됨)"
                                    isSelected && !device.isConnected -> "연결 대기 중..."
                                    device.isConnected -> "연결됨"
                                    else -> "연결 안 됨"
                                }
                                
                                Text(
                                    text = device.name, 
                                    style = MaterialTheme.typography.titleLarge, 
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                    Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = statusText, 
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSelected && !device.isConnected) MaterialTheme.colorScheme.error else statusColor
                                    )
                                }
                            }
                            
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "선택됨",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) { 
                Text("닫기", fontSize = 18.sp, fontWeight = FontWeight.Bold) 
            }
        }
    )
}
