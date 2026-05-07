package kr.disys.baedalin.domain.model

data class Mapping(
    val id: String,
    val keyCode: Int,
    val x: Float,
    val y: Float
)

data class Preset(
    val id: String,
    val name: String,
    val packageName: String,
    val mappings: List<Mapping> = emptyList(),
    val isActive: Boolean = false
)
