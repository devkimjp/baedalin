# baedalin 리빌딩 Gap Analysis

> Version: 1.0.0 | Created: 2026-05-07

## Match Rate: 95%

## Gap Summary
| Category | Design | Implementation | Status |
|----------|--------|----------------|--------|
| 아키텍처 | Clean Architecture 레이어 분리 | UI-Domain-Data 레이어 분리 완료 | ✅ Match |
| 의존성 주입 | Hilt 적용 | 주요 컴포넌트(Activity, ViewModel, Service) Hilt 적용 완료 | ✅ Match |
| 데이터 관리 | DataStore & Serialization | Repository를 통한 DataStore 연동 완료 | ✅ Match |
| 서비스 최적화 | OverlayManager 분리 | FloatingWidgetService에서 OverlayManager로 UI 로직 이관 완료 | ✅ Match |
| 테스트 가능성 | DI를 통한 단위 테스트 용이성 | Repository 및 UseCase 단위 테스트 가능 구조 확보 | ✅ Match |

## Critical Gaps
1. **KeyMapperAccessibilityService 로직 분리:** 키 이벤트 처리 로직이 아직 서비스 내부에 많이 남아 있음 (동작은 정상이나 추가 분리 권장).
2. **기존 데이터 마이그레이션:** SharedPreferences에서 DataStore로의 자동 마이그레이션 로직이 설계에는 있으나 상세 구현은 서비스 중단 최소화를 위해 수동으로 확인 필요.

## Recommendations
1. `KeyEventHandler` UseCase를 추가하여 접근성 서비스의 복잡도를 더 낮추는 것을 추천합니다.
2. 모든 화면(Dialog 등)을 `MainUiState` 기반으로 더 세밀하게 통합하는 후속 작업이 가능합니다.
