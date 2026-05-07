package kr.disys.baedalin.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kr.disys.baedalin.domain.usecase.GetPresetsUseCase
import kr.disys.baedalin.domain.usecase.SavePresetUseCase
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getPresetsUseCase: GetPresetsUseCase,
    private val savePresetUseCase: SavePresetUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Compatibility properties for MainActivity
    var isAccessibilityEnabled: Boolean
        get() = _uiState.value.isAccessibilityEnabled
        set(value) { _uiState.update { it.copy(isAccessibilityEnabled = value) } }

    var isOverlayEnabled: Boolean
        get() = _uiState.value.isOverlayEnabled
        set(value) { _uiState.update { it.copy(isOverlayEnabled = value) } }

    val isMappingEnabled: Boolean get() = _uiState.value.isMappingEnabled

    var showDevicePicker: Boolean
        get() = _uiState.value.showDevicePicker
        set(value) { _uiState.update { it.copy(showDevicePicker = value) } }

    val inputDevices: List<InputDeviceInfo> get() = _uiState.value.inputDevices
    val selectedDeviceDescriptor: String? get() = _uiState.value.selectedDeviceDescriptor
    val selectedDeviceName: String get() = _uiState.value.selectedDeviceName
    val shakeDeviceSelector: Int get() = _uiState.value.shakeDeviceSelector

    var showAppPicker: Boolean
        get() = _uiState.value.showAppPicker
        set(value) { _uiState.update { it.copy(showAppPicker = value) } }

    var targetPresetForPicker: String?
        get() = _uiState.value.targetPresetForPicker
        set(value) { _uiState.update { it.copy(targetPresetForPicker = value) } }

    var conflictFunction: DeliveryFunction?
        get() = _uiState.value.conflictFunction
        set(value) { _uiState.update { it.copy(conflictFunction = value) } }

    var pendingKeyCode: Int?
        get() = _uiState.value.pendingKeyCode
        set(value) { _uiState.update { it.copy(pendingKeyCode = value) } }

    val recordingFunction: DeliveryFunction? get() = _uiState.value.recordingFunction
    val recordingClickType: ClickType? get() = _uiState.value.recordingClickType
    val mappingVersion: Int get() = _uiState.value.mappingVersion

    init {
        observePresets()
        // TODO: 장치 리스트 초기화 및 리스너 등록 로직 이전 필요
    }

    private fun observePresets() {
        getPresetsUseCase()
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onEach { presets ->
                _uiState.update { it.copy(presets = presets, isLoading = false) }
            }
            .catch { e ->
                _uiState.update { it.copy(errorMessage = e.message, isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    fun toggleService() {
        val currentStatus = _uiState.value.isMappingEnabled
        _uiState.update { it.copy(isMappingEnabled = !currentStatus) }
    }

    fun saveSelectedDevice(device: InputDeviceInfo?) {
        _uiState.update { 
            it.copy(
                selectedDeviceDescriptor = device?.descriptor,
                selectedDeviceName = device?.name ?: "장치를 추가하세요"
            )
        }
    }

    fun startRecording(func: DeliveryFunction, type: ClickType) {
        _uiState.update { 
            if (it.recordingFunction == func && it.recordingClickType == type) {
                it.copy(recordingFunction = null, recordingClickType = null)
            } else {
                it.copy(recordingFunction = func, recordingClickType = type)
            }
        }
    }

    fun executeSaveMapping(func: DeliveryFunction, type: ClickType, keyCode: Int) {
        // TODO: UseCase를 통한 저장 로직 구현 필요
        stopRecording()
        _uiState.update { 
            it.copy(
                mappingVersion = it.mappingVersion + 1
            )
        }
    }

    fun stopRecording() {
        _uiState.update { 
            it.copy(recordingFunction = null, recordingClickType = null)
        }
    }

    fun updateMappingVersion() {
        _uiState.update { it.copy(mappingVersion = it.mappingVersion + 1) }
    }
}
