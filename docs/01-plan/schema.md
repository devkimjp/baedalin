# baedalin 리빌딩 Schema 및 용어 정의

> Version: 1.0.0 | Created: 2026-05-07

## 1. Terminology Glossary

| Term | Definition | Example |
|------|------------|---------|
| Preset | 특정 배달 앱에 최적화된 키 매핑 설정 세트 | baemin_preset, coupang_preset |
| Mapping | 실제 하드웨어 키(볼륨 등)와 화면 좌표 간의 연결 정보 | Volume Up -> Click (x: 500, y: 1200) |
| Target App | 접근성 서비스가 감지하여 키 매핑을 활성화할 대상 패키지 | `com.smartscore.baemin` |
| Overlay | 화면 최상단에 떠 있는 위젯 레이어 | Floating Controller |
| Service | 안드로이드 백그라운드 서비스 (Accessibility, Foreground) | `FloatingWidgetService` |

## 2. Entity Definitions (Kotlinx.Serialization 기반)

```kotlin
@Serializable
data class MappingConfig(
    val id: String,
    val name: String,
    val keyCode: Int,
    val targetX: Float,
    val targetY: Float,
    val type: MappingType = MappingType.CLICK
)

@Serializable
data class Preset(
    val id: String,
    val packageName: String,
    val mappings: List<MappingConfig>,
    val isActive: Boolean = false
)

enum class MappingType {
    CLICK, LONG_CLICK, SWIPE
}
```

## 3. Data Flow Diagram

```
[UI Layer (Activity/Service)] 
      <── Flow ── 
[Domain Layer (UseCase)] 
      <── Flow ── 
[Data Layer (Repository)] 
      <── DataStore / SharedPreferences 마이그레이션 ── 
[System]
```
