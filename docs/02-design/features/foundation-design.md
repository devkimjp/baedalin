# baedalin Foundation Design Document

> Version: 1.0.0 | Created: 2026-05-07 | Status: Draft

## 1. Overview
리빌딩의 기초가 되는 의존성 주입(Hilt) 환경을 구축하고, 데이터 영속성 레이어(DataStore, Repository)의 설계를 정의합니다.

## 2. Architecture
### System Diagram
```
[UI: Compose/Service] -> [Domain: UseCase] -> [Data: Repository] -> [Source: DataStore/System]
```

### Components
- **DI Modules:** `NetworkModule`, `RepositoryModule`, `DataStoreModule` 등 Hilt 모듈 정의.
- **DataStore:** `MappingConfig` 및 `Preset` 정보를 JSON으로 저장하는 Preference DataStore.
- **Repository:** UI 레이어에 Flow를 제공하고 데이터 CRUD를 추상화하는 Interface 및 Implementation.

## 3. Data Model
### Entities (Kotlinx.Serialization)
```kotlin
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
    val mappings: List<MappingEntity>
)
```

## 4. Implementation Plan
### Step 1: Dependencies Update
- `build.gradle.kts`에 Hilt, DataStore, Serialization 의존성 추가.

### Step 2: Package Structure Refactoring
```
kr.disys.baedalin/
├── data/
│   ├── local/
│   │   ├── datastore/
│   │   └── entity/
│   └── repository/
├── domain/
│   ├── model/
│   └── usecase/
├── ui/
│   ├── main/
│   └── components/
└── di/
```

### Step 3: Base Classes
- `HiltAndroidApp` 상속 Application 클래스 생성.
- `BaseRepository`, `BaseUseCase` 등 공통 인터페이스 정의.

## 5. Test Plan
- **Hilt Injection Test:** 각 컴포넌트에 의존성이 정상적으로 주입되는지 확인.
- **DataStore Test:** 데이터 저장 및 복구가 Serialization을 거쳐 정상 작동하는지 단위 테스트.
