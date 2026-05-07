package kr.disys.baedalin.di

import kr.disys.baedalin.data.repository.PresetRepositoryImpl
import kr.disys.baedalin.domain.repository.PresetRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        presetRepositoryImpl: PresetRepositoryImpl
    ): PresetRepository
}
