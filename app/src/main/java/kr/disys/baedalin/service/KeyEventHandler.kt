package kr.disys.baedalin.service

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kr.disys.baedalin.domain.repository.MappingRepository
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyEventHandler @Inject constructor(
    private val mappingRepository: MappingRepository,
    private val gestureManager: GestureManager,
    private val appSwitcher: AppSwitcher
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    suspend fun isKeyMapped(keyCode: Int, deviceDescriptor: String): Boolean {
        // 기기 전용 매핑과 GLOBAL 매핑 모두 확인
        val prefix = deviceDescriptor
        return hasMapping(keyCode, prefix) || hasMapping(keyCode, "GLOBAL")
    }

    private suspend fun hasMapping(keyCode: Int, prefix: String): Boolean {
        for (func in DeliveryFunction.entries) {
            for (type in ClickType.entries) {
                val mapped = mappingRepository.getMapping(prefix, func, type).firstOrNull()
                if (mapped == keyCode) return true
            }
        }
        return false
    }

    fun handleKeyAction(keyCode: Int, clickType: ClickType, deviceDescriptor: String, activePreset: String) {
        scope.launch {
            val prefix = deviceDescriptor
            var function = findMappedFunction(keyCode, clickType, prefix)
            
            if (function == null && clickType == ClickType.SINGLE) {
                // 하위 호환성 또는 기본 매핑 확인
                function = findMappedFunction(keyCode, null, prefix)
            }
            
            if (function == null) {
                function = findMappedFunction(keyCode, clickType, "GLOBAL")
            }

            if (function != null) {
                executeFunction(function, activePreset)
            }
        }
    }

    private suspend fun findMappedFunction(keyCode: Int, clickType: ClickType?, prefix: String): DeliveryFunction? {
        return DeliveryFunction.entries.find { func ->
            if (clickType != null) {
                mappingRepository.getMapping(prefix, func, clickType).firstOrNull() == keyCode
            } else {
                // ClickType이 지정되지 않은 경우 (기존 방식 하위 호환)
                // 실제로는 MappingRepository에서 이 케이스를 지원하도록 하거나 여기서 루프를 돌아야 함
                false 
            }
        }
    }

    private fun executeFunction(function: DeliveryFunction, activePreset: String) {
        Log.d("KeyEventHandler", "Executing function: ${function.name} for preset: $activePreset")
        
        when (function) {
            DeliveryFunction.ZOOM_OUT -> gestureManager.performZoom(0f, 0f, false) // Needs center coords
            DeliveryFunction.ZOOM_IN -> gestureManager.performZoom(0f, 0f, true)
            DeliveryFunction.SWITCH_APP -> appSwitcher.switchBetweenDeliveryApps(activePreset)
            else -> {
                // Tap logic based on widget positions (needs WidgetPositionRepository)
            }
        }
    }
}
