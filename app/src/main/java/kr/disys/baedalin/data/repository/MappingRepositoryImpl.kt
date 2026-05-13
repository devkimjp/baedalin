package kr.disys.baedalin.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kr.disys.baedalin.domain.repository.MappingRepository
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MappingRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : MappingRepository {

    override fun getMapping(
        deviceDescriptor: String,
        function: DeliveryFunction,
        clickType: ClickType
    ): Flow<Int?> {
        val key = intPreferencesKey("${deviceDescriptor}_${function.name}_${clickType.name}_keycode")
        return dataStore.data.map { preferences ->
            preferences[key]
        }
    }

    override suspend fun saveMapping(
        deviceDescriptor: String,
        function: DeliveryFunction,
        clickType: ClickType,
        keyCode: Int
    ) {
        val key = intPreferencesKey("${deviceDescriptor}_${function.name}_${clickType.name}_keycode")
        dataStore.edit { preferences ->
            preferences[key] = keyCode
        }
    }

    override suspend fun removeMapping(
        deviceDescriptor: String,
        function: DeliveryFunction,
        clickType: ClickType
    ) {
        val key = intPreferencesKey("${deviceDescriptor}_${function.name}_${clickType.name}_keycode")
        dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    override fun isMappingEnabled(): Flow<Boolean> {
        val key = booleanPreferencesKey("is_mapping_enabled")
        return dataStore.data.map { preferences ->
            preferences[key] ?: false
        }
    }

    override suspend fun setMappingEnabled(enabled: Boolean) {
        val key = booleanPreferencesKey("is_mapping_enabled")
        dataStore.edit { preferences ->
            preferences[key] = enabled
        }
    }

    override fun getToolbarOpacity(): Flow<Float> {
        val key = floatPreferencesKey("toolbar_opacity")
        return dataStore.data.map { preferences ->
            preferences[key] ?: 1.0f
        }
    }

    override suspend fun setToolbarOpacity(opacity: Float) {
        val key = floatPreferencesKey("toolbar_opacity")
        dataStore.edit { preferences ->
            preferences[key] = opacity
        }
    }

    override fun getSelectedDeviceDescriptor(): Flow<String?> {
        val key = stringPreferencesKey("selected_device_descriptor")
        return dataStore.data.map { it[key] }
    }

    override suspend fun setSelectedDeviceDescriptor(descriptor: String?) {
        val key = stringPreferencesKey("selected_device_descriptor")
        dataStore.edit { preferences ->
            if (descriptor != null) preferences[key] = descriptor
            else preferences.remove(key)
        }
    }

    override fun getDoubleClickTimeout(): Flow<Long> {
        val key = longPreferencesKey("double_click_timeout")
        return dataStore.data.map { it[key] ?: 300L }
    }

    override suspend fun setDoubleClickTimeout(timeout: Long) {
        val key = longPreferencesKey("double_click_timeout")
        dataStore.edit { preferences ->
            preferences[key] = timeout
        }
    }

    override fun getWidgetPosition(preset: String, functionName: String): Flow<Pair<Int, Int>> {
        val xKey = intPreferencesKey("${preset}_${functionName}_x")
        val yKey = intPreferencesKey("${preset}_${functionName}_y")
        return dataStore.data.map { preferences ->
            val x = preferences[xKey] ?: -1
            val y = preferences[yKey] ?: -1
            Pair(x, y)
        }
    }

    override suspend fun saveWidgetPosition(preset: String, functionName: String, x: Int, y: Int) {
        val xKey = intPreferencesKey("${preset}_${functionName}_x")
        val yKey = intPreferencesKey("${preset}_${functionName}_y")
        dataStore.edit { preferences ->
            preferences[xKey] = x
            preferences[yKey] = y
        }
    }

    override fun isNightMode(): Flow<Boolean> {
        val key = booleanPreferencesKey("is_night_mode")
        return dataStore.data.map { it[key] ?: false }
    }

    override suspend fun setNightMode(enabled: Boolean) {
        val key = booleanPreferencesKey("is_night_mode")
        dataStore.edit { it[key] = enabled }
    }

    override fun getActivePreset(): Flow<String> {
        val key = stringPreferencesKey("active_preset")
        return dataStore.data.map { it[key] ?: "BAEMIN" }
    }

    override suspend fun setActivePreset(preset: String) {
        val key = stringPreferencesKey("active_preset")
        dataStore.edit { it[key] = preset }
    }

    override fun getCustomWidgetCounter(preset: String): Flow<Int> {
        val key = intPreferencesKey("${preset}_custom_counter")
        return dataStore.data.map { it[key] ?: 1 }
    }

    override suspend fun setCustomWidgetCounter(preset: String, counter: Int) {
        val key = intPreferencesKey("${preset}_custom_counter")
        dataStore.edit { it[key] = counter }
    }

    override fun getActiveCustomWidgets(preset: String): Flow<List<String>> {
        val key = stringPreferencesKey("${preset}_active_custom_widgets")
        return dataStore.data.map { it[key]?.split(",")?.filter { it.isNotBlank() } ?: emptyList() }
    }

    override suspend fun addCustomWidget(preset: String, label: String) {
        val key = stringPreferencesKey("${preset}_active_custom_widgets")
        dataStore.edit { preferences ->
            val current = preferences[key] ?: ""
            val newList = if (current.isEmpty()) label else "$current,$label"
            preferences[key] = newList
        }
    }
}
