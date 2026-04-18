package kr.disys.baedalin

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.ui.theme.BaedalinTheme

class MainActivity : ComponentActivity() {
    
    private var recordingFunction by mutableStateOf<DeliveryFunction?>(null)
    private var recordingClickType by mutableStateOf<ClickType?>(null)
    
    // App selection state
    private var showAppPicker by mutableStateOf(false)
    private var targetPresetForPicker by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            BaedalinTheme {
                Box {
                    MainScreen(
                        recordingFunction = recordingFunction,
                        recordingClickType = recordingClickType,
                        onStartRecording = { func, type ->
                            recordingFunction = func
                            recordingClickType = type
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
        // Check if there's a custom saved package first
        var packageName = prefs.getString("${presetName}_custom_pkg", Presets.getPackageName(presetName)) ?: ""
        
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        
        if (launchIntent == null) {
            // App not found, show picker
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

        // 1. Hide Presets
        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_HIDE_PRESETS
        })
        
        // 2. Launch App
        startActivity(launchIntent)
        
        // 3. Show Preset Widgets
        presetList.forEach { info ->
            val intent = Intent(this, FloatingWidgetService::class.java).apply {
                action = FloatingWidgetService.ACTION_SHOW_WIDGET
                putExtra("function_name", info.function.name)
                putExtra("icon", info.icon)
                putExtra("tooltip", info.tooltip)
                putExtra("x", info.x)
                putExtra("y", info.y)
                putExtra("color", color)
            }
            startService(intent)
        }
        
        // 4. Show Settings Widget
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
            saveMapping(recordingFunction!!, recordingClickType!!, keyCode)
            recordingFunction = null
            recordingClickType = null
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun saveMapping(function: DeliveryFunction, clickType: ClickType, keyCode: Int) {
        val prefs = getSharedPreferences("mappings", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("${function.name}_keycode", keyCode)
            .putString("${function.name}_clicktype", clickType.name)
            .apply()
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
                        // App Icon
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
            if (recordingFunction != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "${recordingFunction.label} (${recordingClickType?.name}) 키를 누르세요...",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Permission Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, modifier = Modifier.weight(1f)) {
                    Text("접근성")
                }
                Button(onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    }
                }, modifier = Modifier.weight(1f)) {
                    Text("오버레이")
                }
            }

            Divider()

            Text("프리셋 로드 (앱 실행 포함)", style = MaterialTheme.typography.titleMedium)
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PresetButton("배민설정", "BAEMIN")
                PresetButton("쿠팡설정", "COUPANG")
                PresetButton("요기요설정", "YOGIYO")
            }

            Divider()

            Text("개별 기능 설정", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(DeliveryFunction.entries) { function ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(function.label, modifier = Modifier.weight(1f))
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
                                
                                Button(onClick = { onStartRecording(function, ClickType.SINGLE) }) {
                                    Text("키")
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
