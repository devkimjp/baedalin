package kr.disys.baedalin.ui.main

import kr.disys.baedalin.domain.model.Preset
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction

data class MainUiState(
    val presets: List<Preset> = emptyList(),
    val isMappingEnabled: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val isBatteryOptimized: Boolean = true, // 기본값은 최적화 중으로 설정
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    
    // Device States
    val selectedDeviceDescriptor: String? = null,
    val selectedDeviceName: String = "장치를 추가하세요",
    val devices: List<InputDeviceInfo> = emptyList(),
    val shakeDeviceSelector: Int = 0,
    
    // UI Visibility States
    val showDevicePicker: Boolean = false,
    val showAppPicker: Boolean = false,
    val targetPresetForPicker: String? = null,
    
    // Recording States
    val recordingFunction: DeliveryFunction? = null,
    val recordingClickType: ClickType? = null,
    val conflictFunction: DeliveryFunction? = null,
    val pendingKeyCode: Int? = null,
    val keyEventTrigger: Int = 0,
    
    val mappingVersion: Int = 0,
    
    // Wizard States
    val currentPermissionStep: Int = 0,
    val isMappingWizardActive: Boolean = false,
    val currentMappingStep: Int = 0,
    val wizardSelectedFunction: DeliveryFunction? = null,
    val wizardSelectedClickType: ClickType? = null
)

data class InputDeviceInfo(
    val name: String,
    val descriptor: String,
    val isConnected: Boolean = false
)
