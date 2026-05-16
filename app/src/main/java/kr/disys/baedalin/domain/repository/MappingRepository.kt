package kr.disys.baedalin.domain.repository

import kotlinx.coroutines.flow.Flow
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction

interface MappingRepository {
    fun getMapping(deviceDescriptor: String, function: DeliveryFunction, clickType: ClickType): Flow<Int?>
    suspend fun saveMapping(deviceDescriptor: String, function: DeliveryFunction, clickType: ClickType, keyCode: Int)
    suspend fun removeMapping(deviceDescriptor: String, function: DeliveryFunction, clickType: ClickType)
    fun isMappingEnabled(): Flow<Boolean>
    suspend fun setMappingEnabled(enabled: Boolean)
    fun getToolbarOpacity(): Flow<Float>
    suspend fun setToolbarOpacity(opacity: Float)
    fun getSelectedDeviceDescriptor(): Flow<String?>
    suspend fun setSelectedDeviceDescriptor(descriptor: String?)
    fun getDoubleClickTimeout(): Flow<Long>
    suspend fun setDoubleClickTimeout(timeout: Long)
    fun getWidgetPosition(preset: String, functionName: String): Flow<Pair<Int, Int>>
    suspend fun saveWidgetPosition(preset: String, functionName: String, x: Int, y: Int)
    fun isNightMode(): Flow<Boolean>
    suspend fun setNightMode(enabled: Boolean)
    fun getActivePreset(): Flow<String>
    suspend fun setActivePreset(preset: String)
    fun getCustomWidgetCounter(preset: String): Flow<Int>
    suspend fun setCustomWidgetCounter(preset: String, counter: Int)
    fun getActiveCustomWidgets(preset: String): Flow<List<String>>
    suspend fun addCustomWidget(preset: String, label: String)
    fun getUnmappedFunctions(deviceDescriptor: String): Flow<List<DeliveryFunction>>
    fun getAllMappings(deviceDescriptor: String): Flow<Map<DeliveryFunction, Pair<Int?, Int?>>>
}
