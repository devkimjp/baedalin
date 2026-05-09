package kr.disys.baedalin.ui.main

import android.content.Context
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

    val inputDevices: List<InputDeviceInfo> get() = _uiState.value.inputDevices
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
        val devices = InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)
        }.filter { device ->
            !device.isVirtual && (device.sources and InputDevice.SOURCE_KEYBOARD != 0)
        }.map { device ->
            InputDeviceInfo(
                name = device.name,
                descriptor = device.descriptor,
                isConnected = true
            )
        }
        _uiState.update { state -> state.copy(inputDevices = devices) }
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

    fun saveSelectedDevice(device: InputDeviceInfo?) {
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
            putBoolean("is_recording", false)
        }
        
        stopRecording()
        closeMappingWizard()
        _uiState.update { state ->
            state.copy(
                mappingVersion = state.mappingVersion + 1
            )
        }
    }

    fun openMappingWizard() {
        _uiState.update { it.copy(isMappingWizardActive = true) }
    }

    fun closeMappingWizard() {
        _uiState.update { it.copy(isMappingWizardActive = false, pendingKeyCode = null) }
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
