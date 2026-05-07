package kr.disys.baedalin.domain.usecase

import kr.disys.baedalin.domain.model.Preset
import kr.disys.baedalin.domain.repository.PresetRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetPresetsUseCase @Inject constructor(
    private val repository: PresetRepository
) {
    operator fun invoke(): Flow<List<Preset>> = repository.getPresets()
}

class SavePresetUseCase @Inject constructor(
    private val repository: PresetRepository
) {
    suspend operator fun invoke(preset: Preset) = repository.savePreset(preset)
}

class GetPresetByPackageUseCase @Inject constructor(
    private val repository: PresetRepository
) {
    operator fun invoke(packageName: String): Flow<Preset?> = repository.getPresetByPackageName(packageName)
}
