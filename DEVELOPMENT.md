# Baedalin Development & Deployment Guide

## 🚀 Deployment Process (Mandatory Clean Install)

사용자의 요청에 따라 모든 배포 작업은 반드시 다음 순서를 준수해야 합니다. 이는 디바이스의 모든 설치 정보와 설정을 완전히 초기화하여 깨끗한 상태에서 테스트하기 위함입니다.

1.  **Build**: 
    ```powershell
    $env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
    .\gradlew.bat assembleDebug
    ```

2.  **Uninstall (Clean Data)**:
    - 기기에 설치된 기존 앱과 데이터를 완전히 삭제합니다.
    ```powershell
    adb -s <DEVICE_ID> uninstall kr.disys.baedalin
    ```

3.  **Re-install**:
    - 빌드된 최신 APK를 설치합니다.
    ```powershell
    adb -s <DEVICE_ID> install app/build/outputs/apk/debug/app-debug.apk
    ```

## 🛠 Troubleshooting

- **ADB Connection Refused (10061)**: 기기에서 무선 디버깅이 활성화되어 있는지 확인하십시오.
- **Unauthorized Device**: 기기 화면에 나타나는 RSA 키 지문 승인 팝업에서 '허용'을 선택하십시오.
- **JAVA_HOME**: 빌드 에러 발생 시 안드로이드 스튜디오 내장 JRE(`jbr`) 경로가 올바른지 확인하십시오.

---
*이 문서는 사용자의 특별 지시에 따라 작성되었으며, 모든 배포 시 이 절차를 엄격히 따릅니다.*
