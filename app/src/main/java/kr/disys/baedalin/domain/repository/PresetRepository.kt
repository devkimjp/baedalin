package kr.disys.baedalin.domain.repository

import kr.disys.baedalin.domain.model.Preset
import kotlinx.coroutines.flow.Flow

interface PresetRepository {
    fun getPresets(): Flow<List<Preset>>
    suspend fun savePreset(preset: Preset)
    suspend fun deletePreset(presetId: String)
    fun getPresetByPackageName(packageName: String): Flow<Preset?>
}
