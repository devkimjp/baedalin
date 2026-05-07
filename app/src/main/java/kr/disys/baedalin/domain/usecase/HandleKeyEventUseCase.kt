package kr.disys.baedalin.domain.usecase

import android.view.KeyEvent
import kr.disys.baedalin.domain.model.Preset
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import javax.inject.Inject

class HandleKeyEventUseCase @Inject constructor(
    private val getPresetsUseCase: GetPresetsUseCase
) {
    // 특정 키 이벤트가 매핑된 동작인지 확인하고, 해당 동작을 반환
    suspend fun getMappingForEvent(keyCode: Int, clickType: ClickType, packageName: String): DeliveryFunction? {
        // TODO: Repository를 통해 현재 패키지에 맞는 매핑 정보 조회 로직 구현
        // 현재는 구현을 위한 구조만 잡음
        return null
    }
}
