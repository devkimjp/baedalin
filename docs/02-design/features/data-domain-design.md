# baedalin Data & Domain Design Document

> Version: 1.0.0 | Created: 2026-05-07 | Status: Draft

## 1. Overview
데이터 영속성 관리(DataStore)와 비즈니스 로직(UseCase)을 처리하는 레이어를 설계합니다. UI 레이어는 이 레이어를 통해서만 데이터에 접근합니다.

## 2. Architecture
### Components
- **PresetRepository:** 프리셋의 CRUD를 담당하는 인터페이스.
- **PresetRepositoryImpl:** DataStore와 Serialization을 사용하여 데이터를 실제로 저장하고 로드.
- **UseCases:**
    - `GetPresetsUseCase`: 전체 프리셋 목록 조회.
    - `SavePresetUseCase`: 프리셋 저장 또는 업데이트.
    - `DeletePresetUseCase`: 프리셋 삭제.
    - `GetActivePresetUseCase`: 현재 활성화된(대상 앱이 감지된) 프리셋 조회.

## 3. Data Flow
1. `DataStore`에서 JSON 문자열 로드.
2. `Kotlinx.Serialization`으로 `List<PresetEntity>` 변환.
3. `Repository`에서 `Domain Model`로 매핑하여 `Flow`로 전달.
4. `UseCase`에서 비즈니스 필터링 및 가공.

## 4. Implementation Plan
### Step 1: Domain Model 정의
- `data` 레이어의 `Entity`와 분리된 순수 Kotlin 객체인 `Preset` 및 `Mapping` 모델 정의.

### Step 2: Repository 구현
- `PresetRepository` 인터페이스 정의.
- `PresetRepositoryImpl` 클래스에서 DataStore 로직 구현.

### Step 3: DI 설정
- `RepositoryModule`을 통해 인터페이스와 구현체 바인딩.

### Step 4: UseCase 구현
- 각 비즈니스 요구사항에 맞는 UseCase 클래스 생성.
