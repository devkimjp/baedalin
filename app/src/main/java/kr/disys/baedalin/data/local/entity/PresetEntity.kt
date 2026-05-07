package kr.disys.baedalin.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class MappingEntity(
    val id: String,
    val keyCode: Int,
    val x: Float,
    val y: Float
)

@Serializable
data class PresetEntity(
    val id: String,
    val name: String,
    val packageName: String,
    val mappings: List<MappingEntity> = emptyList(),
    val isActive: Boolean = false
)
