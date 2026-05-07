# baedalin Service Layer Design Document

> Version: 1.0.0 | Created: 2026-05-07 | Status: Draft

## 1. Overview
가장 비대한 컴포넌트인 `FloatingWidgetService`와 `KeyMapperAccessibilityService`를 리팩토링하여 책임을 분산하고 유지보수성을 높입니다.

## 2. Architecture
### Components
- **OverlayManager:** 윈도우 매니저를 통한 플로팅 위젯의 생성, 업데이트, 제거를 전담하는 클래스.
- **KeyEventHandler:** 접근성 서비스로부터 전달받은 키 이벤트를 매핑된 비즈니스 액션으로 변환하는 컴포넌트.
- **FloatingWidgetService (Refactored):** 서비스 생명주기 관리 및 포그라운드 알림 유지에 집중.
- **KeyMapperAccessibilityService (Refactored):** 시스템 이벤트 수신 및 윈도우 상태 감지에 집중.

## 3. Data Flow
1. `AccessibilityService` -> `KeyEvent` 수신.
2. `KeyEventHandler` -> `ActivePresetUseCase`를 통해 현재 패키지에 맞는 매핑 정보 조회.
3. `GestureManager` -> 실제 클릭/스와이프 동작 수행.
4. `OverlayManager` -> 현재 매핑 상태 및 녹화 상태를 화면에 표시.

## 4. Implementation Plan
### Step 1: OverlayManager 신설
- `FloatingWidgetService`의 `WindowManager` 관련 코드를 `OverlayManager`로 이전.
- Hilt를 통해 주입 가능하도록 설정.

### Step 2: KeyEventHandler 신설
- 키 이벤트를 가공하고 비즈니스 로직을 호출하는 유틸리티/UseCase 정의.

### Step 3: Service 적용
- 각 서비스에 `@AndroidEntryPoint` 적용.
- 기존의 복잡한 로직을 새로 만든 매니저들로 교체.
