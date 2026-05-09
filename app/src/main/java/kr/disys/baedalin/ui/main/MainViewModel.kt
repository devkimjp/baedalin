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

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getPresetsUseCase: GetPresetsUseCase,
    private val savePresetUseCase: SavePresetUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
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
        inputManager.registerInputDeviceListener(deviceListener, null)
        loadInitialDevice()
        refreshDeviceList()
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

    override fun onCleared() {
        inputManager.unregisterInputDeviceListener(deviceListener)
        super.onCleared()
    }
}
