# baedalin UI & ViewModel Design Document

> Version: 1.0.0 | Created: 2026-05-07 | Status: Draft

## 1. Overview
기존 `MainViewModel`에 집중되어 있던 데이터 관리 로직을 `UseCase`로 이관하고, UI 상태를 `StateFlow`를 통해 안정적으로 관리하도록 설계합니다.

## 2. Architecture
### Components
- **MainViewModel (Refactored):**
    - `GetPresetsUseCase`, `SavePresetUseCase` 등을 주입받아 사용.
    - UI에 노출할 `StateFlow<MainUiState>` 정의.
    - SharedPreferences 직접 접근 코드 제거.
- **UI Components:**
    - `MainScreen`, `PresetList`, `MappingEditor` 등으로 컴포넌트 분리 및 패키지 정리.
    - `HiltViewModel` 어노테이션을 통한 의존성 주입.

## 3. UI State Model
```kotlin
data class MainUiState(
    val presets: List<Preset> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
```

## 4. Implementation Plan
### Step 1: UI State 정의
- `MainUiState` 클래스를 정의하여 ViewModel과 UI 간의 인터페이스 규격화.

### Step 2: MainViewModel 리팩토링
- `@HiltViewModel` 적용 및 UseCase 주입.
- 기존 로직을 UseCase 호출로 대체.
- 초기 로드 시 `getPresets()` 호출 및 StateFlow 업데이트.

### Step 3: UI 컴포넌트 이동 및 연결
- 기존 `ui/components` 하위 파일들을 `ui/main` 등 목적에 맞는 패키지로 이동.
- ViewModel의 StateFlow를 UI에서 `collectAsStateWithLifecycle`로 구독.
