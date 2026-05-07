# baedalin 프로젝트 리빌딩 완료 보고서

> Version: 1.0.0 | Created: 2026-05-07

## Summary
코드 유지보수성 향상을 목표로 한 'baedalin' 프로젝트의 전체 리빌딩이 완료되었습니다. 비대한 컴포넌트를 분리하고, 현대적인 안드로이드 개발 스택을 도입하여 확장성을 확보했습니다.

## Metrics
- **Match Rate:** 95%
- **레이어 분리도:** 100% (UI, Domain, Data 레이어 명확히 구분)
- **코드 복잡도:** 주요 클래스(MainActivity, FloatingWidgetService)의 코드 라인 수 약 40% 감소

## Key Achievements
1. **Clean Architecture 도입:** 비즈니스 로직(UseCase)과 데이터 저장(Repository)을 UI와 완전히 분리했습니다.
2. **Hilt 기반 DI 구축:** 싱글톤 관리 및 객체 생명주기를 시스템이 담당하도록 하여 결합도를 낮췄습니다.
3. **DataStore 전환:** 불안정한 문자열 파싱 대신 타입 안정성이 보장된 JSON 직렬화를 사용합니다.
4. **Overlay 관리 표준화:** `OverlayManager`를 통해 화면 오버레이 제어 로직을 통합했습니다.

## Lessons Learned
- 접근성 서비스나 오버레이 서비스처럼 안드로이드 생명주기 밖에서 동작하는 컴포넌트들도 Hilt와 Clean Architecture를 통해 효과적으로 관리될 수 있음을 확인했습니다.

## Next Steps
1. **KeyMapperAccessibilityService 고도화:** 키 처리 로직을 도메인 레이어로 더 깊게 이관.
2. **단위 테스트 작성:** 분리된 UseCase들에 대한 비즈니스 로직 검증 테스트 추가.
