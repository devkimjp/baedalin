package kr.disys.baedalin.model

enum class ActionType {
    TAP, SWIPE
}

enum class ClickType {
    SINGLE, DOUBLE, LONG
}

enum class DeliveryFunction(val label: String) {
    CALL_CHECK("콜확인(배민)"),
    ACCEPT("수락"),
    REJECT("거절"),
    PATH("경로보기"),
    ZOOM_IN("지도 확대"),
    ZOOM_OUT("지도 축소")
}

data class Mapping(
    val function: DeliveryFunction,
    val keyCode: Int,
    val clickType: ClickType,
    val startX: Float,
    val startY: Float,
    val endX: Float? = null,
    val endY: Float? = null
)

data class PresetInfo(val function: DeliveryFunction, val icon: String, val tooltip: String, val x: Int, val y: Int)

object Presets {
    val BAEMIN = listOf(
        PresetInfo(DeliveryFunction.CALL_CHECK, "✆", "콜확인(배민)", 517, 320),
        PresetInfo(DeliveryFunction.REJECT, "Ⓡ", "거절", 725, 2130),
        PresetInfo(DeliveryFunction.ACCEPT, "Ⓐ", "수락", 252, 2134),
        PresetInfo(DeliveryFunction.PATH, "Ⓟ", "경로", 107, 1588)
    )

    val COUPANG = BAEMIN // 동일하게 설정
    val YOGIYO = BAEMIN   // 동일하게 설정
    
    fun getPackageName(preset: String): String = when (preset) {
        "BAEMIN" -> "com.woowahan.bros"
        "COUPANG" -> "com.coupang.mobile.eats.courier"
        "YOGIYO" -> "kr.co.yogiyo.riderapp" // 요기요 라이더 패키지명 업데이트
        else -> ""
    }

    fun getPresetFromPackage(packageName: String): String? = when (packageName) {
        "com.woowahan.bros" -> "BAEMIN"
        "com.coupang.mobile.eats.courier" -> "COUPANG"
        "kr.co.yogiyo.riderapp" -> "YOGIYO"
        else -> null
    }

    fun getColor(preset: String): Int = when (preset) {
        "BAEMIN" -> 0xAA2AC1BC.toInt()
        "COUPANG" -> 0xAA007AFF.toInt()
        "YOGIYO" -> 0xAAFA0050.toInt()
        else -> 0xAAFF0000.toInt()
    }
}
