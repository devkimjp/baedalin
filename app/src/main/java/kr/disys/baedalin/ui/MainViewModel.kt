package kr.disys.baedalin.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.util.ShareManager

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)

    // UI States
    var isMappingEnabled by mutableStateOf(prefs.getBoolean("is_mapping_enabled", false))
        private set

    var mappingVersion by mutableIntStateOf(0)
        private set

    var isAccessibilityEnabled by mutableStateOf(false)
    var isOverlayEnabled by mutableStateOf(false)

    var recordingFunction by mutableStateOf<DeliveryFunction?>(null)
    var recordingClickType by mutableStateOf<ClickType?>(null)

    var showAppPicker by mutableStateOf(false)
    var targetPresetForPicker by mutableStateOf<String?>(null)

    var showDevicePicker by mutableStateOf(false)
    var shakeDeviceSelector by mutableIntStateOf(0)

    var conflictFunction by mutableStateOf<DeliveryFunction?>(null)
    var pendingKeyCode by mutableStateOf<Int?>(null)

    // Device States
    var selectedDeviceDescriptor by mutableStateOf<String?>(null)
    var selectedDeviceName by mutableStateOf("장치를 추가하세요")
    var inputDevices by mutableStateOf<List<InputDeviceInfo>>(emptyList())

    data class InputDeviceInfo(val name: String, val descriptor: String, val isConnected: Boolean = false)

    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == "is_mapping_enabled") {
            isMappingEnabled = p.getBoolean("is_mapping_enabled", false)
        }
        mappingVersion++
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { refreshDeviceList() }
        override fun onInputDeviceRemoved(deviceId: Int) { refreshDeviceList() }
        override fun onInputDeviceChanged(deviceId: Int) { refreshDeviceList() }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(deviceListener, null)
        
        selectedDeviceDescriptor = prefs.getString("selected_device_descriptor", null)
        refreshDeviceList()
    }

    fun refreshDeviceList() {
        val historyJson = prefs.getString("device_history", "[]") ?: "[]"
        val history = historyJson.removeSurrounding("[", "]")
            .split(";;;")
            .filter { it.isNotBlank() }
            .mapNotNull { 
                val parts = it.trim().split("|||")
                if (parts.size >= 2) InputDeviceInfo(parts[0], parts[1], false) else null
            }.toMutableList()

        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val isExternalDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                device.isExternal
            } else {
                !device.isVirtual
            }
            if (isExternalDevice && (device.sources and InputDevice.SOURCE_KEYBOARD != 0)) {
                val info = InputDeviceInfo(device.name, device.descriptor, true)
                val index = history.indexOfFirst { it.descriptor == info.descriptor }
                if (index == -1) {
                    history.add(info)
                } else {
                    history[index] = info
                }
            }
        }

        val newHistoryJson = history.joinToString(prefix = "[", postfix = "]", separator = ";;;") { "${it.name}|||${it.descriptor}" }
        prefs.edit().putString("device_history", newHistoryJson).apply()

        inputDevices = history.sortedByDescending { it.isConnected }
        
        val selectedDevice = history.find { it.descriptor == selectedDeviceDescriptor }
        selectedDeviceName = selectedDevice?.name ?: "장치를 추가하세요"
        mappingVersion++
    }

    fun saveSelectedDevice(device: InputDeviceInfo?) {
        if (device == null) {
            prefs.edit().remove("selected_device_descriptor").apply()
            selectedDeviceDescriptor = null
            selectedDeviceName = "장치를 추가하세요"
        } else {
            prefs.edit().putString("selected_device_descriptor", device.descriptor).apply()
            selectedDeviceDescriptor = device.descriptor
            selectedDeviceName = device.name
            shakeDeviceSelector = 0
        }
        Toast.makeText(context, "감시 장치 설정: $selectedDeviceName", Toast.LENGTH_SHORT).show()
        mappingVersion++
    }

    fun toggleService() {
        val newStatus = !isMappingEnabled
        prefs.edit().putBoolean("is_mapping_enabled", newStatus).commit()

        if (!newStatus) {
            context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                action = FloatingWidgetService.ACTION_HIDE_ALL
            })
        } else {
            if (selectedDeviceDescriptor == null) {
                shakeDeviceSelector++
                Toast.makeText(context, "장치를 먼저 선택해주세요!", Toast.LENGTH_SHORT).show()
                return
            }
            context.startService(Intent(context, FloatingWidgetService::class.java).apply {
                action = FloatingWidgetService.ACTION_START_SERVICE_ONLY
            })
            Toast.makeText(context, "배달 매핑 서비스가 시작되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    fun startRecording(func: DeliveryFunction, type: ClickType) {
        if (recordingFunction == func && recordingClickType == type) {
            recordingFunction = null
            recordingClickType = null
            prefs.edit().putBoolean("is_recording", false).apply()
            Toast.makeText(context, "키 입력 모드가 해제되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            recordingFunction = func
            recordingClickType = type
            prefs.edit().putBoolean("is_recording", true).apply()
            Toast.makeText(context, "${func.label}의 키 입력을 대기 중입니다...", Toast.LENGTH_SHORT).show()
        }
    }

    fun executeSaveMapping(func: DeliveryFunction, type: ClickType, keyCode: Int) {
        saveMapping(func, type, keyCode)
        Toast.makeText(context, "${func.label} 에 키코드 $keyCode 가 설정되었습니다.", Toast.LENGTH_SHORT).show()
        
        recordingFunction = null
        recordingClickType = null
        prefs.edit().putBoolean("is_recording", false).apply()

        val intent = Intent(context, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_UPDATE_KEY
            putExtra("function_name", func.name)
            putExtra("keycode", keyCode)
        }
        context.startService(intent)
        mappingVersion++
    }

    private fun saveMapping(function: DeliveryFunction, clickType: ClickType, keyCode: Int) {
        val prefix = selectedDeviceDescriptor ?: "GLOBAL"
        prefs.edit().apply {
            DeliveryFunction.entries.forEach { existingFunc ->
                val storedKey = prefs.getInt("${prefix}_${existingFunc.name}_keycode", -1)
                val storedType = prefs.getString("${prefix}_${existingFunc.name}_clicktype", "")
                
                if (storedKey == keyCode && storedType == clickType.name) {
                    remove("${prefix}_${existingFunc.name}_keycode")
                    remove("${prefix}_${existingFunc.name}_clicktype")
                }
            }
            putInt("${prefix}_${function.name}_keycode", keyCode)
            putString("${prefix}_${function.name}_clicktype", clickType.name)
        }.apply()
    }

    fun updateMappingVersion() {
        mappingVersion++
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(deviceListener)
        super.onCleared()
    }
}
