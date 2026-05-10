package kr.disys.baedalin.ui.main

import android.content.Context
import android.bluetooth.BluetoothManager
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kr.disys.baedalin.domain.usecase.GetPresetsUseCase
import kr.disys.baedalin.domain.usecase.SavePresetUseCase
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.core.content.edit
import kr.disys.baedalin.model.CoordinateEntry
import kr.disys.baedalin.model.CustomWidgetInfo
import kr.disys.baedalin.model.DeviceInfo
import kr.disys.baedalin.model.ShareConfig
import android.widget.Toast
import android.content.Intent
import kr.disys.baedalin.model.Presets

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getPresetsUseCase: GetPresetsUseCase,
    private val savePresetUseCase: SavePresetUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val inputManager by lazy { context.getSystemService(Context.INPUT_SERVICE) as InputManager }
    private val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDeviceList()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshDeviceList()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDeviceList()
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Compatibility properties for MainActivity
    var isAccessibilityEnabled: Boolean
        get() = _uiState.value.isAccessibilityEnabled
        set(value) { _uiState.update { state -> state.copy(isAccessibilityEnabled = value) } }

    var isOverlayEnabled: Boolean
        get() = _uiState.value.isOverlayEnabled
        set(value) { _uiState.update { state -> state.copy(isOverlayEnabled = value) } }

    val isMappingEnabled: Boolean get() = _uiState.value.isMappingEnabled

    var showDevicePicker: Boolean
        get() = _uiState.value.showDevicePicker
        set(value) { _uiState.update { state -> state.copy(showDevicePicker = value) } }

    val devices: List<InputDeviceInfo> get() = _uiState.value.devices
    val selectedDeviceDescriptor: String? get() = _uiState.value.selectedDeviceDescriptor
    val selectedDeviceName: String get() = _uiState.value.selectedDeviceName
    val shakeDeviceSelector: Int get() = _uiState.value.shakeDeviceSelector

    var showAppPicker: Boolean
        get() = _uiState.value.showAppPicker
        set(value) { _uiState.update { state -> state.copy(showAppPicker = value) } }

    var targetPresetForPicker: String?
        get() = _uiState.value.targetPresetForPicker
        set(value) { _uiState.update { state -> state.copy(targetPresetForPicker = value) } }

    var conflictFunction: DeliveryFunction?
        get() = _uiState.value.conflictFunction
        set(value) { _uiState.update { state -> state.copy(conflictFunction = value) } }

    var pendingKeyCode: Int?
        get() = _uiState.value.pendingKeyCode
        set(value) { _uiState.update { state -> state.copy(pendingKeyCode = value) } }

    val recordingFunction: DeliveryFunction? get() = _uiState.value.recordingFunction
    val recordingClickType: ClickType? get() = _uiState.value.recordingClickType
    val mappingVersion: Int get() = _uiState.value.mappingVersion

    init {
        observePresets()
        try {
            inputManager.registerInputDeviceListener(deviceListener, null)
            loadInitialDevice()
            refreshDeviceList()
        } catch (e: Exception) {
            // Unit Test 환경에서는 skip (또는 로깅)
        }
    }

    private fun loadInitialDevice() {
        val savedDescriptor = prefs.getString("selected_device_descriptor", null)
        if (savedDescriptor != null) {
            val device = InputDevice.getDeviceIds().toList().mapNotNull { id ->
                InputDevice.getDevice(id)
            }.find { d -> d.descriptor == savedDescriptor }
            
            _uiState.update { state -> state.copy(
                selectedDeviceDescriptor = savedDescriptor,
                selectedDeviceName = device?.name ?: "연결됨 (이름 불명)"
            )}
        }
        
        val isMapping = prefs.getBoolean("is_mapping_enabled", false)
        _uiState.update { state -> state.copy(isMappingEnabled = isMapping) }
    }

    private fun refreshDeviceList() {
        // Android 12(API 31) 이상에서는 BLUETOOTH_CONNECT 권한이 필요합니다.
        val hasBtPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // 블루투스 페어링된 장치 이름 수집 (연결 이력 확인용)
        val bondedNames = try {
            if (hasBtPermission) {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bm?.adapter?.bondedDevices?.map { it.name.lowercase() } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        val currentDevices = InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)
        }.filter { device ->
            // 필터링 기준:
            // 1. 가상 장치가 아닐 것
            // 2. 키보드, DPAD, 게임패드 등 입력 소스를 가지고 있을 것
            // 3. 벤더 ID가 0이나 1이 아닌 경우 (시스템 장치 제외)
            val isExternal = !device.isVirtual
            val hasInputSource = (device.sources and (InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD)) != 0
            val isNotInternal = device.vendorId > 1 || device.productId > 1
            
            // 시스템 내부 하드웨어 장치 제외 목록 (블랙리스트)
            val systemDeviceNames = listOf(
                "gpio", "s2mps", "s2mpg", "pwrkey", "vbus", "sec_jack", "virtual", 
                "uinput", "qpnp", "hall_ic", "sensor", "snd_soc", "touchscreen", "panel", 
                "pmic", "st-i2c", "i2c", "headset", "mouse", "trackpad"
            )
            val isNotSystemDevice = systemDeviceNames.none { device.name.lowercase().contains(it.lowercase()) }
            
            // 블루투스 연결 이력 확인
            val isBluetoothBonded = bondedNames.any { device.name.lowercase().contains(it) || it.contains(device.name.lowercase()) }
            
            if (hasBtPermission && bondedNames.isNotEmpty()) {
                // 블루투스 권한이 있고 페어링 이력이 있다면, 이력이 있는 장치만 우선 표시
                isBluetoothBonded && !device.isVirtual
            } else {
                // 권한이 없거나 이력이 없는 경우, 엄격한 물리 장치 필터 적용
                isExternal && isNotSystemDevice && hasInputSource && isNotInternal
            }
        }.map { device ->
            InputDeviceInfo(
                name = device.name,
                descriptor = device.descriptor,
                isConnected = true
            )
        }

        val previousDevices = _uiState.value.devices
        _uiState.update { state -> state.copy(devices = currentDevices) }

        // 새로 추가된 장치가 있는지 확인
        val newlyAdded = currentDevices.find { current -> 
            previousDevices.none { prev -> prev.descriptor == current.descriptor }
        }

        val currentSelectedDescriptor = _uiState.value.selectedDeviceDescriptor
        
        // 1. 현재 선택된 장치가 여전히 연결되어 있는지 확인
        val stillConnected = currentDevices.find { it.descriptor == currentSelectedDescriptor }
        
        if (stillConnected != null) {
            // 연결 유지 중이면 이름 등 최신 정보만 갱신 (필요시)
            _uiState.update { it.copy(selectedDeviceName = stillConnected.name) }
        } else {
            // 2. 선택된 장치가 없거나 끊겼다면, 새로 추가된 장치나 기존에 연결된 장치 중 하나를 자동 선택
            val deviceToSelect = newlyAdded ?: currentDevices.firstOrNull()
            
            if (deviceToSelect != null) {
                _uiState.update { state ->
                    state.copy(
                        selectedDeviceDescriptor = deviceToSelect.descriptor,
                        selectedDeviceName = deviceToSelect.name
                    )
                }
                prefs.edit {
                    putString("selected_device_descriptor", deviceToSelect.descriptor)
                }
                
                // 새로운 장치가 연결되어 자동 선택된 경우 토스트 알림
                if (newlyAdded != null) {
                    Toast.makeText(context, "${deviceToSelect.name} 장치가 연결되어 자동으로 선택되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observePresets() {
        getPresetsUseCase()
            .onStart { _uiState.update { state -> state.copy(isLoading = true) } }
            .onEach { presets ->
                _uiState.update { state -> state.copy(presets = presets, isLoading = false) }
            }
            .catch { e ->
                _uiState.update { state -> state.copy(errorMessage = e.message, isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    fun toggleService() {
        val currentStatus = _uiState.value.isMappingEnabled
        val nextStatus = !currentStatus
        _uiState.update { state -> state.copy(isMappingEnabled = nextStatus) }
        prefs.edit { putBoolean("is_mapping_enabled", nextStatus) }
    }

    fun selectDevice(device: InputDeviceInfo?) {
        _uiState.update { state ->
            state.copy(
                selectedDeviceDescriptor = device?.descriptor,
                selectedDeviceName = device?.name ?: "장치를 추가하세요"
            )
        }
        prefs.edit {
            putString("selected_device_descriptor", device?.descriptor)
        }
    }

    fun startRecording(func: DeliveryFunction, type: ClickType) {
        _uiState.update { state ->
            if (state.recordingFunction == func && state.recordingClickType == type) {
                state.copy(recordingFunction = null, recordingClickType = null)
            } else {
                state.copy(recordingFunction = func, recordingClickType = type)
            }
        }
    }

    fun executeSaveMapping(func: DeliveryFunction, type: ClickType, keyCode: Int) {
        val prefix = selectedDeviceDescriptor ?: "GLOBAL"
        prefs.edit(commit = true) {
            putInt("${prefix}_${func.name}_keycode", keyCode)
            putString("${prefix}_${func.name}_clicktype", type.name)
        }
        
        stopRecording()
        // closeMappingWizard() // 연속 매핑을 위해 여기서 닫지 않음
        _uiState.update { state ->
            state.copy(
                mappingVersion = state.mappingVersion + 1,
                pendingKeyCode = null // 다음 매핑을 위해 초기화
            )
        }
    }

    fun openMappingWizard() {
        _uiState.update { it.copy(isMappingWizardActive = true, pendingKeyCode = null) }
        prefs.edit { putBoolean("is_recording", true) }
    }

    fun closeMappingWizard() {
        _uiState.update { it.copy(isMappingWizardActive = false, pendingKeyCode = null) }
        prefs.edit { putBoolean("is_recording", false) }
    }

    fun getUnmappedFunctions(): List<DeliveryFunction> {
        val prefix = selectedDeviceDescriptor ?: "GLOBAL"
        return DeliveryFunction.entries.filter { func ->
            prefs.getInt("${prefix}_${func.name}_keycode", -1) == -1
        }
    }

    fun stopRecording() {
        _uiState.update { state ->
            state.copy(recordingFunction = null, recordingClickType = null)
        }
    }

    fun updateMappingVersion() {
        _uiState.update { state -> state.copy(mappingVersion = state.mappingVersion + 1) }
    }

    fun exportConfig() {
        viewModelScope.launch {
            try {
                val displayMetrics = context.resources.displayMetrics
                val deviceInfo = DeviceInfo(
                    model = android.os.Build.MODEL,
                    width = displayMetrics.widthPixels,
                    height = displayMetrics.heightPixels,
                    dpi = displayMetrics.densityDpi
                )

                val widgetPrefs = context.getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
                val coordinates = mutableListOf<CoordinateEntry>()
                
                listOf("BAEMIN", "COUPANG", "YOGIYO").forEach { preset ->
                    DeliveryFunction.entries.forEach { func ->
                        val x = widgetPrefs.getInt("${preset}_${func.name}_x", -1)
                        val y = widgetPrefs.getInt("${preset}_${func.name}_y", -1)
                        if (x != -1 && y != -1) {
                            coordinates.add(CoordinateEntry(preset, func.name, x, y))
                        }
                    }
                }

                val customWidgets = mutableListOf<CustomWidgetInfo>()
                listOf("BAEMIN", "COUPANG", "YOGIYO").forEach { preset ->
                    val active = prefs.getString("${preset}_active_custom_widgets", "") ?: ""
                    if (active.isNotEmpty()) {
                        customWidgets.add(CustomWidgetInfo(
                            preset = preset,
                            activeWidgets = active,
                            counter = prefs.getInt("${preset}_custom_counter", 1),
                            lastX = prefs.getInt("${preset}_last_added_x", 200),
                            lastY = prefs.getInt("${preset}_last_added_y", 250)
                        ))
                    }
                }

                val config = ShareConfig(
                    deviceInfo = deviceInfo,
                    coordinates = coordinates,
                    customWidgets = customWidgets
                )

                val json = config.toJSONString()
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, json)
                    type = "text/plain"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val shareIntent = Intent.createChooser(sendIntent, "설정 공유하기")
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shareIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "내보내기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun importConfig(json: String) {
        viewModelScope.launch {
            try {
                val config = ShareConfig.fromJSONString(json)
                val widgetPrefs = context.getSharedPreferences("WidgetPositions", Context.MODE_PRIVATE)
                
                widgetPrefs.edit(commit = true) {
                    config.coordinates.forEach { entry ->
                        putInt("${entry.preset}_${entry.function}_x", entry.x)
                        putInt("${entry.preset}_${entry.function}_y", entry.y)
                    }
                }

                prefs.edit(commit = true) {
                    config.customWidgets.forEach { info ->
                        putString("${info.preset}_active_custom_widgets", info.activeWidgets)
                        putInt("${info.preset}_custom_counter", info.counter)
                        putInt("${info.preset}_last_added_x", info.lastX)
                        putInt("${info.preset}_last_added_y", info.lastY)
                    }
                }

                updateMappingVersion()
                Toast.makeText(context, "설정을 불러왔습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "불러오기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCleared() {
        try {
            inputManager.unregisterInputDeviceListener(deviceListener)
        } catch (e: Exception) {}
        super.onCleared()
    }
}
