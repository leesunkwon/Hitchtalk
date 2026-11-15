# HitchTalk

HitchTalk은 주행 데이터 기반 1:1 보이스 롤플레잉 차량용 영어 튜터입니다. Pleos에서 가상 여행자와 영어를 연습하며, 계정과 여행 기록은 Firebase를 사용합니다. AI 대화와 리포트는 앱에서 OpenAI Responses REST API를 직접 호출하므로 별도 게이트웨이 배포가 필요 없습니다.

1. Pleos에서 정차(P)한 뒤 HitchTalk에 로그인합니다.
2. 빌드 시 설정한 OpenAI API 키가 자동으로 적용됩니다. **설정 → 음성 → OpenAI API 키 → 연결 확인**을 누르면 짧은 테스트 문장으로 응답을 확인할 수 있습니다. API 사용량은 해당 키의 OpenAI 프로젝트에 기록됩니다.
3. 음성 대화에 동의하고 마이크 권한을 허용합니다. 승차에는 기존 Pleos 안전 신호 확인이 필요합니다.

개발 환경 설정:

1. Android Studio에서 프로젝트를 열고 Android SDK 경로가 있는 루트 `local.properties`를 준비합니다. 새 환경에서는 `local.properties.example`을 복사하고 `sdk.dir`을 설정해도 됩니다.
2. `local.properties`에 `OPENAI_API_KEY=발급받은_키`를 설정합니다. 실제 키는 이 파일에만 입력합니다.
3. Firebase Console에서 받은 Android 앱 설정을 `app/google-services.json`에 배치합니다. 자신의 Firebase 프로젝트를 사용하는 경우 이메일/비밀번호 로그인을 활성화하고 `firebase/firestore.rules` 및 `firebase/firestore.indexes.json`도 적용합니다.
4. Gradle Sync 후 빌드합니다. 키를 변경한 경우 다시 빌드해 설치해야 합니다.

Gradle은 `local.properties`의 키를 `BuildConfig.OPENAI_API_KEY`에 주입합니다. Debug와 Release에 동일하게 적용되며, 기존 기기 등록 키보다 우선합니다. 키를 비워 두면 앱 설정에서 기기별로 키를 등록할 수 있습니다. `local.properties.example`에는 실제 키가 없습니다.

`local.properties`, `google-services.json`, 환경 변수 파일, 서명 키, IDE 로컬 설정과 빌드 결과물은 `.gitignore`로 제외합니다. Firebase 규칙과 인덱스, Gradle Wrapper, 예제 설정 파일은 저장소에 포함합니다. 이미 Git에서 추적한 파일에는 ignore 규칙이 소급 적용되지 않으므로 기존 저장소를 사용할 때는 추적 상태도 확인해야 합니다.

이 설정은 Git에 키가 올라가는 것을 방지합니다. 빌드 시 주입한 키는 APK에 포함되어 추출할 수 있습니다.

릴리스 빌드:

```powershell
.\gradlew.bat :app:assembleRelease
```

`keystore.properties.example`을 참고해 로컬 `keystore.properties`에 서명 키 경로와 비밀번호를 설정하면 Release 빌드에 자동으로 서명합니다. 설정이 없으면 서명되지 않은 APK가 생성되어 설치할 수 없습니다. 서명 키와 비밀번호는 Git에서 제외되며, 이후 같은 앱을 업데이트하려면 동일한 키가 필요하므로 안전하게 백업해야 합니다.

제출용 파일은 저장소의 `apk/이선권_Hitchtalk_release.apk`입니다. 위 명령으로 빌드한 `app/build/outputs/apk/release/app-release.apk`를 이 이름으로 복사합니다. `.gitignore`는 제출용 APK 한 개만 예외로 허용합니다. Debug와 Release 모두 설정한 OpenAI 키로 REST API를 직접 호출합니다.
