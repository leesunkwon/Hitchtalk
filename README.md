# 히치토크 (HitchTalk)

히치토크는 조수석에 탄 ‘가상의 외국인 여행자’와 출퇴근길 차 안에서 1:1로 영어 대화를 나누는, 주행 데이터 기반 차량용 보이스 롤플레잉 영어 튜터입니다.

## 실행 방법

### 제출 APK 실행

1. [제출용 Release APK](apk/이선권_Hitchtalk_release.apk)를 내려받습니다.
2. Pleos Connect 에뮬레이터를 실행하고 APK를 설치합니다.
3. 기어를 **P(정차)**로 설정한 뒤 앱 목록에서 **히치토크**를 실행합니다. 처음 실행하면 차량 상태를 확인하는 동안 조작 제한 화면이 잠시 표시될 수 있습니다.
4. 환영 화면에서 회원가입 또는 이메일/비밀번호 로그인을 진행합니다.
5. 음성 대화에 동의하고 마이크 권한을 허용합니다.
6. **설정 → 음성 → OpenAI API 키 → API 키 등록**에서 본인의 키를 등록한 뒤 **연결 확인**을 실행합니다. 키는 이 기기에 암호화해 저장하며, API 사용량과 요금은 해당 OpenAI 계정에 반영됩니다.
7. 안전 신호 확인과 음성 서비스 연결이 완료되면 홈에서 여행자를 선택해 대화를 시작합니다.

최신 제출 APK **1.1**에는 기본 OpenAI API 키가 포함되어 있지 않습니다. 각 사용자가 앱 설정에서 자신의 키를 등록해야 음성 대화를 시작할 수 있습니다.

Windows PowerShell에서 저장소 루트를 기준으로 설치하는 예시입니다. SDK 경로가 다르면 `$adb`를 수정합니다. 한글 파일명 전송 문제를 피하도록 설치할 때만 임시 영문 이름으로 복사합니다.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
Copy-Item ".\apk\이선권_Hitchtalk_release.apk" "$env:TEMP\hitchtalk-release.apk" -Force
& $adb install -r "$env:TEMP\hitchtalk-release.apk"
```

여러 기기가 연결되어 있으면 `adb devices`로 확인한 기기를 `-s <기기 ID>`로 지정합니다. 기존 Debug 앱과 제출 APK의 서명이 다르면 업데이트 설치가 거절될 수 있으므로, 별도 에뮬레이터에서 Release를 테스트합니다.

### 소스에서 빌드

1. Android Studio에서 저장소 루트를 엽니다.
2. `local.properties.example`을 참고해 루트 `local.properties`에 Android SDK 경로를 설정합니다. 이미 생성된 파일의 SDK 경로는 유지합니다.
3. Firebase Android 설정 파일을 `app/google-services.json`에 배치합니다.
4. Gradle Sync 후 빌드합니다.

```properties
# local.properties 예시
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
```

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

설치 가능한 Release를 만들려면 `keystore.properties.example`을 참고해 로컬 `keystore.properties`에 서명 키 경로·비밀번호·별칭을 설정합니다. 설정이 없으면 서명되지 않은 APK가 생성됩니다. 서명된 결과물은 `app/build/outputs/apk/release/app-release.apk`입니다. 앱 업데이트에는 동일한 서명 키가 필요하므로 키와 비밀번호를 안전하게 백업합니다.

빌드에 OpenAI API 키를 입력할 필요가 없습니다. 빌드한 앱을 설치한 뒤 위 실행 방법에 따라 로그인하고, **설정 → 음성 → OpenAI API 키**에서 본인의 키를 등록하고 **연결 확인**을 실행합니다. 키 등록·변경·삭제는 앱 설정에서 처리합니다.

## 테스트 환경 및 필요한 설정

| 항목 | 개발·검증에 사용한 환경 |
| --- | --- |
| 개발 OS | Windows |
| 개발 도구 | Android Studio, Android SDK Platform-Tools(ADB) |
| JDK | JDK 25 · 검증 버전: Android Studio JBR 25.0.3 |
| 빌드 도구 | Gradle Wrapper 9.6.0, Android Gradle Plugin 9.4.1 |
| Android SDK | minSdk 29, compileSdk 37, targetSdk 37 |
| Pleos SDK | Vehicle 2.3.2, SpeechToText 3.3.1, TextToSpeech 3.3.1 |

- **인터넷:** Firebase 인증·기록 저장, OpenAI REST API, Pleos 음성 서비스에 연결할 수 있어야 합니다.
- **Firebase:** 소스에서 빌드할 때 자신의 Android 앱 설정 파일을 준비합니다. Authentication의 이메일/비밀번호 로그인을 활성화하고, [Firestore 규칙](firebase/firestore.rules)과 [인덱스 설정](firebase/firestore.indexes.json)을 적용합니다.
- **OpenAI:** 본인 계정의 유효한 API 키와 모델 접근 권한·사용 한도가 필요합니다. **설정 → 음성 → OpenAI API 키 → API 키 등록** 후 **연결 확인**을 실행합니다. 제출 APK에도 기본 키가 없으므로 이 설정이 필요합니다.
- **마이크:** 앱의 마이크 권한과 AI 대화 동의가 필요합니다. 에뮬레이터로 호스트 마이크 입력이 전달되도록 설정하고 **설정 → 음성 → 마이크 테스트**에서 녹음·재생을 확인합니다.
- **차량 신호:** P 기어와 방향지시등·비상등의 상태를 확인할 수 있어야 승차·대화를 시작할 수 있습니다. 미확인 상태에서는 조작 또는 대화를 제한합니다.

현재 개발 에뮬레이터에는 비상등 조회에 필요한 `android.car.permission.CAR_EXTERIOR_LIGHTS` 권한이 부여되지 않아, 제출 Release의 실제 차량 신호 기반 승차·음성 대화는 제한됩니다. 앱 내부 모의 입력과 D 주행 미리보기는 Debug 전용이며 제출 Release에는 적용되지 않습니다.

확인한 범위는 **Release 1.1의 OpenAI 키 미포함·서명·업데이트 설치·P 정차 상태의 환영/로그인 화면 진입**, **단위 테스트 38개 통과**, **Release Lint 통과**, **Debug 계측 테스트 11개 통과**입니다. 키 관리 계측 테스트는 합성 키와 모의 응답으로 등록·교체·삭제, 암호화 저장과 계정별 격리, 인증 오류 처리를 확인했습니다. 실제 차량 신호를 사용한 승차부터 대화 완료까지의 전체 과정은 검증하지 않았습니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease
```

`local.properties`, Firebase 설정 파일, 서명 키와 비밀번호는 Git에서 제외됩니다. 개인 OpenAI API 키는 앱에서 등록하며 이 기기의 암호화 저장소에 보관됩니다.

이전에 공개한 APK에 포함된 키는 새 APK로 교체해도 과거 파일에서 제거되지 않습니다. 이전 공개 APK의 키는 OpenAI 계정에서 직접 폐기해야 합니다.

## Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 값 |
| --- | --- |
| 에뮬레이터 | Pleos Connect **v2.1.0** |
| AVD 이름 | `Pleos_Connect_v2.1.0` |
| 실행 OS | Android 14 · API 34 |
| CPU 아키텍처 | x86_64 |
| 화면 해상도 | **2560 × 1440 px**, 가로 방향 |
| 화면 밀도 | **160 dpi** |

화면 규격은 에뮬레이터 전체 디스플레이 기준입니다. 앱이 표시되는 영역은 Pleos 차량 UI와 분할 화면 비율에 따라 달라집니다.

## APK 파일 위치

- 파일: [`apk/이선권_Hitchtalk_release.apk`](apk/이선권_Hitchtalk_release.apk)
- 빌드: 전용 키로 서명한 **Release**, 버전 **1.1** (`versionCode 2`)
- 패키지: `com.kotlinsun.hitchtalk`

소스에서 새로 빌드한 APK를 제출할 때도 위 폴더와 파일명을 사용합니다. Git에는 해당 제출용 APK 한 개만 포함하도록 설정되어 있습니다.
