package kr.disys.baedalin.ui.main

import android.content.Context
import android.util.Log
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
import kr.disys.baedalin.model.CoordinateEntry
import kr.disys.baedalin.model.CustomWidgetInfo
import kr.disys.baedalin.model.DeviceInfo
import kr.disys.baedalin.model.ShareConfig
import android.widget.Toast
import android.content.Intent
import kr.disys.baedalin.model.Presets
import kr.disys.baedalin.service.FloatingWidgetService
import kr.disys.baedalin.domain.repository.MappingRepository

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getPresetsUseCase: GetPresetsUseCase,
    private val savePresetUseCase: SavePresetUseCase,
    private val mappingRepository: MappingRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val inputManager by lazy { context.getSystemService(Context.INPUT_SERVICE) as InputManager }
    
    // Legacy SharedPreferences (Will be removed in next iteration A-5)
    private val prefs = context.getSharedPreferences("mappings", Context.MODE_PRIVATE)

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDeviceList()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshDeviceList()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDeviceList()
    }

    private val _keyEvents = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val keyEvents = _keyEvents.asSharedFlow()

    fun onKeyEvent(keyCode: Int) {
        viewModelScope.launch {
            _keyEvents.emit(keyCode)
        }
        _uiState.update { it.copy(keyEventTrigger = it.keyEventTrigger + 1) }
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

    var isBluetoothEnabled: Boolean
        get() = _uiState.value.isBluetoothEnabled
        set(value) { _uiState.update { state -> state.copy(isBluetoothEnabled = value) } }
        
    var isBatteryOptimized: Boolean
        get() = _uiState.value.isBatteryOptimized
        set(value) { _uiState.update { state -> state.copy(isBatteryOptimized = value) } }

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
        set(value) { 
            _uiState.update { state -> 
                state.copy(
                    pendingKeyCode = value,
                    keyEventTrigger = state.keyEventTrigger + 1
                ) 
            } 
        }

    val recordingFunction: DeliveryFunction? get() = _uiState.value.recordingFunction
    val recordingClickType: ClickType? get() = _uiState.value.recordingClickType
    val mappingVersion: Int get() = _uiState.value.mappingVersion

    init {
        observePresets()
        observeMappingSettings()
        try {
            inputManager.registerInputDeviceListener(deviceListener, null)
            loadInitialDevice()
            refreshDeviceList()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Init error", e)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMappingSettings() {
        mappingRepository.isMappingEnabled()
            .onEach { enabled -> _uiState.update { it.copy(isMappingEnabled = enabled) } }
            .launchIn(viewModelScope)

        mappingRepository.getToolbarOpacity()
            .onEach { opacity -> _uiState.update { it.copy(toolbarOpacity = opacity) } }
            .launchIn(viewModelScope)

        mappingRepository.getSelectedDeviceDescriptor()
            .onEach { descriptor ->
                val deviceName = if (descriptor != null) {
                    val deviceIds = InputDevice.getDeviceIds()
                    val device = deviceIds.toList().mapNotNull { id -> InputDevice.getDevice(id) }
                        .find { it.descriptor == descriptor }
                    device?.name ?: "연결됨 (이름 불명)"
                } else "장치를 추가하세요"
                
                _uiState.update { it.copy(
                    selectedDeviceDescriptor = descriptor,
                    selectedDeviceName = deviceName
                ) }
            }
            .launchIn(viewModelScope)

        mappingRepository.getSelectedDeviceDescriptor()
            .flatMapLatest { descriptor ->
                mappingRepository.getUnmappedFunctions(descriptor ?: "GLOBAL")
            }
            .onEach { unmapped ->
                _uiState.update { it.copy(unmappedFunctions = unmapped) }
            }
            .launchIn(viewModelScope)

        mappingRepository.getSelectedDeviceDescriptor()
            .flatMapLatest { descriptor ->
                mappingRepository.getAllMappings(descriptor ?: "GLOBAL")
            }
            .onEach { allMappings ->
                val mappingStateMap = allMappings.mapValues { (_, pair) ->
                    FunctionMappingState(singleKeyCode = pair.first, doubleKeyCode = pair.second)
                }
                _uiState.update { it.copy(mappings = mappingStateMap) }
            }
            .launchIn(viewModelScope)
    }

    private fun loadInitialDevice() {
        // [사용자 요청 수정] 앱 실행 시 무조건 서비스 시작 로직 제거. 
        // 권한 설정이 완료된 후 사용자가 명시적으로 켰을 때만 시작되도록 함.
    }

    private fun refreshDeviceList() {
        val hasBtPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

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
            val isExternal = !device.isVirtual
            val hasInputSource = (device.sources and (InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD)) != 0
            val isNotInternal = device.vendorId > 1 || device.productId > 1
            
            val systemDeviceNames = listOf(
                "gpio", "s2mps", "s2mpg", "pwrkey", "vbus", "sec_jack", "virtual", 
                "uinput", "qpnp", "hall_ic", "sensor", "snd_soc", "touchscreen", "panel", 
                "pmic", "st-i2c", "i2c", "headset", "mouse", "trackpad"
            )
            val isNotSystemDevice = systemDeviceNames.none { device.name.lowercase().contains(it.lowercase()) }
            val isBluetoothBonded = bondedNames.any { device.name.lowercase().contains(it) || it.contains(device.name.lowercase()) }
            
            if (hasBtPermission && bondedNames.isNotEmpty()) {
                isBluetoothBonded && !device.isVirtual
            } else {
                isExternal && isNotSystemDevice && hasInputSource && isNotInternal
            }
        }.map { device ->
            InputDeviceInfo(name = device.name, descriptor = device.descriptor, isConnected = true)
        }

        val previousDevices = _uiState.value.devices
        _uiState.update { state -> state.copy(devices = currentDevices) }

        val newlyAdded = currentDevices.find { current -> 
            previousDevices.none { prev -> prev.descriptor == current.descriptor }
        }

        val currentSelectedDescriptor = _uiState.value.selectedDeviceDescriptor
        val stillConnected = currentDevices.find { it.descriptor == currentSelectedDescriptor }
        
        if (stillConnected != null) {
            _uiState.update { it.copy(selectedDeviceName = stillConnected.name) }
        } else {
            val deviceToSelect = newlyAdded ?: currentDevices.firstOrNull()
            if (deviceToSelect != null) {
                viewModelScope.launch {
                    mappingRepository.setSelectedDeviceDescriptor(deviceToSelect.descriptor)
                    if (newlyAdded != null) {
                        Toast.makeText(context, "${deviceToSelect.name} 장치가 연결되어 자동으로 선택되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun observePresets() {
        getPresetsUseCase()
            .onStart { _uiState.update { state -> state.copy(isLoading = true) } }
            .onEach { presets -> _uiState.update { state -> state.copy(presets = presets, isLoading = false) } }
            .catch { e -> _uiState.update { state -> state.copy(errorMessage = e.message, isLoading = false) } }
            .launchIn(viewModelScope)
    }

    fun toggleService() {
        if (_uiState.value.isMappingWizardActive) return
        val currentStatus = _uiState.value.isMappingEnabled
        viewModelScope.launch {
            mappingRepository.setMappingEnabled(!currentStatus)
        }
    }
    
    fun updateToolbarOpacity(opacity: Float) {
        viewModelScope.launch {
            mappingRepository.setToolbarOpacity(opacity)
            val intent = Intent(context, FloatingWidgetService::class.java).apply {
                action = FloatingWidgetService.ACTION_UPDATE_TRANSPARENCY
                putExtra("transparency", opacity)
            }
            context.startService(intent)
        }
    }

    private fun saveWizardMapping() {
        val state = _uiState.value
        val func = state.wizardSelectedFunction ?: return
        val code = state.pendingKeyCode ?: return
        val type = state.wizardSelectedClickType ?: ClickType.SINGLE
        executeSaveMapping(func, type, code)
    }

    fun updateWizardStep(step: Int) {
        _uiState.update { it.copy(currentMappingStep = step) }
    }

    fun updateWizardFunction(function: DeliveryFunction?) {
        _uiState.update { it.copy(wizardSelectedFunction = function) }
    }

    fun updateWizardClickType(type: ClickType?) {
        _uiState.update { it.copy(wizardSelectedClickType = type) }
    }

    fun selectDevice(device: InputDeviceInfo?) {
        viewModelScope.launch {
            mappingRepository.setSelectedDeviceDescriptor(device?.descriptor)
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
        viewModelScope.launch {
            val prefix = selectedDeviceDescriptor ?: "GLOBAL"
            // [Note] Complex duplication logic still relies on SharedPreferences as an interim step
            // or should be fully migrated to Repository logic. 
            // For now, using Repository for simple save/remove.
            
            // 1. 중복 키 제거 (새로 저장하려는 키코드와 클릭 타입이 이미 다른 기능에 할당되어 있다면 제거)
            Log.d("KeyMapper", "[SAVE] Checking duplication for key=$keyCode, type=$type")
            DeliveryFunction.entries.forEach { f ->
                ClickType.entries.forEach { t ->
                    val existing = mappingRepository.getMapping(prefix, f, t).first()
                    // 같은 키코드이면서 클릭 타입까지 같을 때만 중복으로 판단하여 제거
                    if (existing == keyCode && t == type) {
                        if (f != func) {
                            Log.i("KeyMapper", "[SAVE] Removing duplicate mapping: ${f.name} ($t) was using $keyCode")
                            mappingRepository.removeMapping(prefix, f, t)
                        }
                    }
                }
            }

            Log.i("KeyMapper", "[SAVE] Saving new mapping: ${func.name} ($type) -> $keyCode")
            // 2. 새 매핑 저장
            mappingRepository.saveMapping(prefix, func, type, keyCode)
            
            stopRecording()
            _uiState.update { state ->
                state.copy(
                    mappingVersion = state.mappingVersion + 1,
                    pendingKeyCode = null
                )
            }
        }
    }

    fun saveDoubleClickTimeout(timeout: Long) {
        viewModelScope.launch {
            mappingRepository.setDoubleClickTimeout(timeout)
            updateMappingVersion()
        }
    }

    fun openMappingWizard() {
        viewModelScope.launch {
            mappingRepository.setMappingEnabled(false)
            _uiState.update { it.copy(
                isMappingEnabled = false,
                isMappingWizardActive = true, 
                pendingKeyCode = null,
                wizardSelectedFunction = null,
                wizardSelectedClickType = null,
                currentMappingStep = 0
            ) }
        }
    }

    fun resetPendingKeyCode() {
        _uiState.update { it.copy(pendingKeyCode = null) }
    }

    fun closeMappingWizard() {
        _uiState.update { it.copy(
            isMappingWizardActive = false, 
            pendingKeyCode = null,
            wizardSelectedFunction = null,
            wizardSelectedClickType = null
        ) }
        // TODO: Move is_recording to Repository
    }

    fun getUnmappedFunctions(): List<DeliveryFunction> {
        return _uiState.value.unmappedFunctions
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
        // Export logic will eventually move to UseCase + Repository
        viewModelScope.launch {
            try {
                val displayMetrics = context.resources.displayMetrics
                val config = ShareConfig(
                    deviceInfo = DeviceInfo(android.os.Build.MODEL, displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi),
                    coordinates = emptyList(), // Placeholder
                    customWidgets = emptyList() // Placeholder
                )
                val json = config.toJSONString()
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, json)
                    type = "text/plain"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(sendIntent, "설정 공유하기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(context, "내보내기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun importConfig(json: String) {
        // Import logic will move to Repository
        Toast.makeText(context, "가져오기 기능 개발 중 (Repository 이관 중)", Toast.LENGTH_SHORT).show()
    }

    override fun onCleared() {
        try {
            inputManager.unregisterInputDeviceListener(deviceListener)
        } catch (e: Exception) {}
        super.onCleared()
    }
}
