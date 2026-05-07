package kr.disys.baedalin.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kr.disys.baedalin.data.local.entity.MappingEntity
import kr.disys.baedalin.data.local.entity.PresetEntity
import kr.disys.baedalin.domain.model.Mapping
import kr.disys.baedalin.domain.model.Preset
import kr.disys.baedalin.domain.repository.PresetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class PresetRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : PresetRepository {

    private val PRESETS_KEY = stringPreferencesKey("presets_json")

    override fun getPresets(): Flow<List<Preset>> {
        return dataStore.data.map { preferences ->
            val json = preferences[PRESETS_KEY] ?: "[]"
            val entities = Json.decodeFromString<List<PresetEntity>>(json)
            entities.map { it.toDomain() }
        }
    }

    override suspend fun savePreset(preset: Preset) {
        dataStore.edit { preferences ->
            val json = preferences[PRESETS_KEY] ?: "[]"
            val entities = Json.decodeFromString<List<PresetEntity>>(json).toMutableList()
            
            val newEntity = preset.toEntity()
            val index = entities.indexOfFirst { it.id == newEntity.id }
            
            if (index != -1) {
                entities[index] = newEntity
            } else {
                entities.add(newEntity)
            }
            
            preferences[PRESETS_KEY] = Json.encodeToString(entities)
        }
    }

    override suspend fun deletePreset(presetId: String) {
        dataStore.edit { preferences ->
            val json = preferences[PRESETS_KEY] ?: "[]"
            val entities = Json.decodeFromString<List<PresetEntity>>(json).toMutableList()
            entities.removeAll { it.id == presetId }
            preferences[PRESETS_KEY] = Json.encodeToString(entities)
        }
    }

    override fun getPresetByPackageName(packageName: String): Flow<Preset?> {
        return getPresets().map { presets ->
            presets.find { it.packageName == packageName }
        }
    }

    // Mapper functions
    private fun PresetEntity.toDomain() = Preset(
        id = id,
        name = name,
        packageName = packageName,
        mappings = mappings.map { it.toDomain() },
        isActive = isActive
    )

    private fun MappingEntity.toDomain() = Mapping(
        id = id,
        keyCode = keyCode,
        x = x,
        y = y
    )

    private fun Preset.toEntity() = PresetEntity(
        id = id,
        name = name,
        packageName = packageName,
        mappings = mappings.map { it.toEntity() },
        isActive = isActive
    )

    private fun Mapping.toEntity() = MappingEntity(
        id = id,
        keyCode = keyCode,
        x = x,
        y = y
    )
}
