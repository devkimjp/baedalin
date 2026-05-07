# baedalin 프로젝트 전체 리빌딩 Plan

> Version: 1.0.0 | Created: 2026-05-07 | Status: Draft

## 1. Executive Summary
현재 'baedalin' 앱은 핵심 기능(AccessibilityService, FloatingWidget)이 잘 구현되어 있으나, 비즈니스 로직과 UI 로직이 강하게 결합된 'Fat Component' 구조를 가지고 있습니다. 코드 유지보수성과 확장성을 개선하기 위해 최신 안드로이드 아키텍처 가이드를 준수하는 전체 리빌딩을 수행합니다.

## 2. Goals and Objectives
- **Clean Architecture 도입:** 레이어(Data, Domain, UI)를 명확히 분리하여 관심사 분리 실현.
- **의존성 주입(Hilt) 적용:** 컴포넌트 간 결합도를 낮추고 테스트 코드 작성이 용이한 구조 구축.
- **데이터 영속성 고도화:** 불안정한 문자열 기반 직렬화에서 Kotlinx.Serialization 및 DataStore 기반으로 전환.
- **상태 관리 체계화:** Flow와 단일 진실 공급원(SSOT) 원칙에 기반한 데이터 흐름 구축.
- **UI 로직 분리:** FloatingWidgetService 등 서비스 컴포넌트의 책임을 분산하여 코드 복잡도 감소.

## 3. Scope
### In Scope
- Clean Architecture 레이어 구조 설계 (Data, Domain, UI)
- Hilt를 활용한 DI 환경 구축
- SharedPreferences를 DataStore/Serialization으로 교체
- ViewModel의 데이터 관리 로직을 Repository로 이전
- FloatingWidgetService 및 AccessibilityService 리팩토링 (Overlay Manager 분리)

### Out of Scope
- 신규 기능 추가 (리팩토링에 집중)
- 서버 API 연동 (기존 로컬 기능 유지)
- 대규모 UI 디자인 변경 (기존 테마 유지하며 구조만 개선)

## 4. Success Criteria
| Criterion | Metric | Target |
|-----------|--------|--------|
| 계층 분리 | Domain 레이어 존재 여부 | Data/Domain/UI 레이어 완전 분리 |
| 의존성 관리 | Hilt 도입 여부 | 모든 주요 컴포넌트에 Hilt 주입 완료 |
| 데이터 안정성 | Serialization 도입 | 수동 문자열 파싱 로직 100% 제거 |
| 코드 유지보수성 | Lint 경고 및 복잡도 | SonarQube 등 정적 분석 지표 개선 |

## 5. Timeline (Phases)
| Milestone | Description |
|-----------|-------------|
| Phase 1: Foundation | Hilt 설정, Data Layer(Repository/DataStore) 구축 |
| Phase 2: Domain | UseCase 및 Business Logic 이동 |
| Phase 3: UI Refactoring | ViewModel 개선 및 Overlay 관리 로직 분리 |
| Phase 4: Service Cleanup | FloatingWidget/Accessibility Service 경량화 |
| Phase 5: Validation | 회귀 테스트 및 성능 최적화 |

## 6. Risks
| Risk | Impact | Mitigation |
|------|--------|------------|
| 서비스 간 통신 단절 | High | 기존 Intent 통신을 Flow 또는 DI 기반 이벤트 채널로 신중하게 교체 |
| 백업 데이터 호환성 | Medium | 기존 SharedPreferences 데이터를 신규 DataStore로 마이그레이션하는 로직 포함 |
| 접근성 서비스 권한 문제 | High | 리팩토링 후 시스템 권한 유지 상태 반복 검증 |
