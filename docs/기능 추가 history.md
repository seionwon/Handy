# 기능 추가 history

Handy 포크(`seionwon/Handy`)의 주요 기능 변경, 업스트림 동기화 및 검증 이력을 시간순으로 기록합니다.

<a id="hist-20261121-001"></a>

### 2026-11-21 12:13:30 +09:00 — 업스트림(cjpais/Handy v0.10.1+) 포크 동기화 및 커스텀 기능 보존

| 항목      | 내용                                                                           |
| --------- | ------------------------------------------------------------------------------ |
| ID        | `hist-20261121-001`                                                            |
| 변경 종류 | 업스트림 동기화 · 병합 충돌 해결 · 기능 통합                                   |
| 상태      | 검증 완료 · GitHub `origin/main` 푸시 완료 (로컬 macOS PKG 재빌드/설치는 별도) |

#### 목적

`seionwon/Handy` 포크(`255c80a`, 13개 커스텀 커밋)를 업스트림 `cjpais/Handy:main` (`4807c21`, v0.10.1 + 태국어 번역, 69개 커밋)과 동기화하면서, 포크에 구현된 핵심 macOS Screen Sharing·입력 안전성·권한 복구·보안·서명/패키징 기능을 빠짐없이 유지합니다.

#### 열어보는 위치 및 사용 방법

- **새 온보딩 / 권한 화면**: 첫 실행 또는 macOS/Windows 권한 누락 시 메인 창에서 자동으로 열리며, `설정 > 디버그`에서 온보딩 미리보기·실행을 열 수 있습니다.
  - 권한 화면 우측 상단의 `종료(Quit)` 버튼과 하단의 `권한 다시 확인`, `Handy 권한 복구`, `Handy 다시 시작` 버튼을 그대로 사용할 수 있습니다.
- **오디오/비디오 파일 전사**: 메인 창에 파일을 드래그 앤 드롭하거나 `기록(History)` 화면에서 파일을 추가해 백그라운드 전사할 수 있습니다.
- **트레이 언어 전환 / 언어 감지 / 전사 훅**: 메뉴 막대 트레이의 언어 하위 메뉴, `설정 > 일반`의 언어 설정, `~/Library/Application Support/com.pais.handy/hooks/transcription` 실행 파일로 사용할 수 있습니다.
- **Screen Sharing 클립보드 핸드오프**: 기존과 동일하게 Apple 화면 공유가 전면일 때 자동 적용되며, 집중 테스트는 `/Applications/Handy.app/Contents/MacOS/handy --check-screen-sharing-focus`로 실행합니다.
- **포크 전용 종료 버튼**: 메인 창 하단 푸터 우측 및 권한/로딩 화면에서 사용할 수 있습니다.

#### 주요 동작 및 오류 처리

- **Screen Sharing + 비동기 붙여넣기 통합**: 업스트림의 비차단 `"paste"` 워커 스레드(`actions.rs`, `clipboard.rs`)를 도입해 Handy 자체 창(온보딩 Try It 등) 붙여넣기 시 메인 스레드 차단을 제거하면서도, macOS Screen Sharing(공유 클립보드 ON의 비활성화 `FocusPanel` 경로와 OFF의 `편집 > 클립보드 보내기` 경로)은 메인 스레드 전용 헬퍼로 안전하게 실행되도록 통합했습니다. 비동기 Screen Sharing 실패는 업스트림의 새 `NoticePanel`(`notices::raise`)로 전달됩니다.
- **macOS 입력 안전성 및 패치된 `handy-keys` 유지**: `Cargo.toml`/`Cargo.lock`에서 `src-tauri/vendor/handy-keys` (`0.3.4`, macOS 수동 `ListenOnly` 이벤트 탭·비차단 콜백·합성 입력 필터링·3초 타임아웃)가 실제로 링크되도록 고정하고, `handy_keys.rs`의 5초 타임아웃·포커스 상실/30초 자동 종료·`handy-keys-recording-stopped` 이벤트를 새 `useShortcutCapture.ts`에 연결했습니다.
- **권한 확인/복구 안전성 통합**: 업스트림이 새로 추가한 자동 `tccutil reset`(`reset_macos_permission`)과 `tauri-plugin-macos-permissions-api` 요청 호출을 제거하고, 포크의 순차 읽기 전용 `pollPermissions`, 명시적 `permissions::repair_accessibility_permission`(메인 창 전용·유효 권한 보존), `request_accessibility_permission`, `open_accessibility_settings`, `request_microphone_permission`(실제 `RcBlock` 콜백) 및 중복 초기화 방지 `initializeInputSystem`을 새 v0.10.1 `Permissions`/`ReturningPermissions`/`useTryEngine`에 통합했습니다.
- **다국어 번역**: 태국어(`th`) 포함 전체 27개 로케일(기준 `en` + 26개 언어, 총 676개 키)을 업스트림 v0.10.1 키와 포크 권한/복구 키로 동기화했습니다.

#### 검증 결과

- `bun run check:translations`: 전체 26개 비교 언어(676개 키) 누락/초과 없음 통과
- `bun run build` (`tsc && vite build`): 성공
- `bun run lint` (`eslint src`): 오류·경고 없음 통과
- `bun run test:permissions`, `bun run test:keyboard`, `bun src/components/settings/history/clipboard.test.ts`, `bun run check:catalog`, `bun run check:model-languages`: 모두 통과
- `bun run test:macos-signing`: 로컬 서명 통합 테스트 통과
- `cargo test --manifest-path src-tauri/vendor/handy-keys/Cargo.toml --lib`: 56개 통과(권한 의존 3개 ignored), `observation_uses_passive_tap` 포함
- `CMAKE_POLICY_VERSION_MINIMUM=3.5 cargo test --manifest-path src-tauri/Cargo.toml --features disable-updater --lib`: 382개 통과, 0개 실패 (Screen Sharing 14개, clipboard, `input::chord_tests`, `history_audio_path`, `llm_client`, `permissions`, `macos_install`, `macos_instance`, `disable-updater` 등 포크 핵심 테스트 44개 모두 포함)
- `CMAKE_POLICY_VERSION_MINIMUM=3.5 cargo clippy --manifest-path src-tauri/Cargo.toml --features disable-updater --tests` 및 `cargo check --bins --features disable-updater`: 빌드 성공, 포크 수정 파일 경고 없음
- `bunx prettier --check` (수정 파일 전체) 및 `cargo fmt --check`: 통과
- `codememo-browser`로 `680×570` 해상도에서 `tests/fixtures/permissions.html` 렌더링 및 버튼(`Quit`, `Open settings`, `Check Again`, `Repair Handy permission`, `Restart Handy`) 표시·오류 0건 확인

#### 실제 반영 상태 및 한계

- **GitHub 포크 동기화**: 병합 커밋 `a050392`를 `origin/main`에 푸시 완료하여 `cjpais/Handy:main` 대비 `behind_by: 0` 상태를 확인했습니다.
- **미수행 항목 / 한계**:
  - 로컬 `/Applications/Handy.app` 배포용 macOS PKG 빌드·설치(`bun scripts/build-macos-share.ts`)와 실제 Apple 화면 공유 세션에서의 라이브 입력 확인은 이번 Git 동기화 범위에 포함되지 않아 별도 진행이 필요합니다.
  - 업스트림의 `bun run check:cjk-font` 스크립트는 업스트림 자체 리팩터링(`../src/components/onboarding/data/languages` 경로 미갱신)으로 인해 업스트림 상태 그대로 실패하며 이번 동기화 범위에서 임의 수정하지 않았습니다.
  - Playwright 브라우저 전체 스위트(`tests/permissions.spec.ts`)는 샌드박스 내 데스크톱 Chrome 실행 제한으로 인해 직접 실행하지 않고 `codememo-browser` 렌더링 확인 및 순수 로직 단위 테스트(`test:permissions`)로 검증했습니다.

<a id="hist-20261121-002"></a>

### 2026-11-21 12:42:27 +09:00 — 네이티브 Android 버전 및 CodeMemo 실행·휴대폰 설치 허브 추가

| 항목      | 내용                                                                                     |
| --------- | ---------------------------------------------------------------------------------------- |
| ID        | `hist-20261121-002`                                                                      |
| 변경 종류 | 신규 플랫폼 앱(Android) · 네이티브 오프라인 STT · 시스템 음성 키보드(IME) · 모바일 설치  |
| 상태      | Android APK 빌드·서명 검증·단위 테스트·브라우저 설치 허브 검증 완료 (실기기 설치는 별도) |

#### 목적

데스크톱 전용 Tauri 앱인 Handy(`com.pais.handy`, v0.10.1)의 핵심 경험(완전 오프라인 Whisper 받아쓰기, 실시간 입력, 오디오 파일 전사, 전사 기록, AI 후처리)을 Android 스마트폰에서도 네이티브로 사용할 수 있도록 `android/` 앱을 구축하고, CodeMemo의 실행(Run) 버튼으로 휴대폰에 바로 APK를 설치할 수 있게 합니다.

#### 열어보는 위치 및 사용 방법

- **CodeMemo 실행 버튼으로 휴대폰 설치**:
  - **Android 빌드 실행**: `android/` 루트 Gradle 프로젝트(`android/gradlew`, `android/app/build.gradle.kts`)가 `android/app/build/outputs/apk/debug/app-debug.apk`를 생성합니다.
  - **웹 설치 허브**: 일반 브라우저에서 개발/미리보기 서버(`bun run dev` / `bun run preview`, `http://127.0.0.1:1420/`)를 열면 Tauri 데스크톱 UI 대신 `AndroidInstallPage`(`src/components/android/AndroidInstallPage.tsx`)가 표시되어 원클릭 APK 다운로드(`/downloads/Handy-Android.apk`), QR 코드(`APK 바로 받기` / `설치 안내 페이지` 전환 및 LAN 주소 입력 지원), SHA-256 검증값, USB ADB 설치 명령 및 4단계 음성 키보드 활성화 안내를 제공합니다.
  - **수동 APK 빌드·게시**: `bun run build:android` (`scripts/build-android.ts`)로 Debug APK 빌드·`apksigner` 서명 검증 후 `public/downloads/Handy-Android.apk`와 `android-apk-meta.json`에 게시할 수 있으며, Vite 미들웨어(`vite.config.ts`)도 최신 Gradle 빌드 APK를 `application/vnd.android.package-archive` 헤더로 자동 스트리밍합니다.
- **Android 앱 화면 구성 (`com.pais.handy`)**:
  - **받아쓰기(Dictate)**: 길게 누르기/토글 녹음, 실시간 오디오 레벨 미터, 모델 다운로드 없이 즉시 동작하는 `Android 즉시 음성 인식`(`SpeechRecognizer`) 및 온디바이스 `Whisper.cpp` 전환, 복사·공유·음성 키보드 설정 바로가기.
  - **기록(History)**: 로컬 SQLite(`HistoryDatabase`) 전사 기록 검색·복사·WAV 재생·재전사 및 기기 내 오디오 파일(M4A/MP3/WAV) 디코딩 전사(`AudioFileDecoder`).
  - **모델(Models)**: Whisper Tiny/Base/Small(Multilingual & English) GGML 모델 다운로드·진행률·삭제·활성 모델 선택.
  - **설정(Settings)**: 전사 언어, 길게 눌러 말하기, 필러 제거, 사용자 정의 단어, AI 후처리(OpenAI/Anthropic/OpenRouter/Groq/Custom), 라이트/다크/시스템 테마(Handy `#DA5893`/`#FAA2CA`·`#FBFBFB`/`#2C2B29` 토큰 반영), 한국어·영어 리소스.
  - **시스템 전체 `Handy 음성 키보드` (`HandyInputMethodService`)**: 안드로이드 `설정 > 일반 > 키보드 목록 및 기본 키보드`에서 활성화하면 카카오톡·메모·브라우저 등 모든 앱 입력창에서 음성 입력·스페이스·엔터·백스페이스·키보드 전환을 사용할 수 있습니다.

#### 주요 동작 및 오류 처리

- **온디바이스 Whisper.cpp NDK 엔진**: `whisper.cpp` v1.9.5 CPU 소스(MIT)를 `android/app/src/main/cpp/whisper/`에 벤더링하고 CMake 3.22.1 + NDK 28.2로 `arm64-v8a`, `x86_64`용 `libhandy_whisper.so`를 `-O3`로 빌드했습니다. JNI 브리지(`handy_whisper_jni.cpp`)는 Modified UTF-8 깨짐을 막기 위해 UTF-8 바이트 배열로 텍스트를 전달합니다.
- **데스크톱 앱 무영향 격리**: `src/main.tsx`는 `"__TAURI_INTERNALS__" in window`일 때 기존 Tauri 데스크톱 초기화 경로를 그대로 유지하고, 일반 브라우저 환경에서만 `AndroidInstallPage`를 동적 임포트합니다.

#### 검증 결과

- `./android/gradlew testDebugUnitTest assembleDebug`: Kotlin/Compose 및 CMake/NDK `whisper.cpp`(`arm64-v8a`, `x86_64`) 빌드 성공, JUnit 단위 테스트 9개 전체 통과(`LlmClientTest` 2, `TextPostProcessorTest` 4, `WavCodecTest` 3)
- `bun run build:android --publish-only`: `apksigner verify --verbose` 통과(APK Signature Scheme v2, 1 signer, 24.48 MB), `lib/arm64-v8a/libhandy_whisper.so` 및 `lib/x86_64/libhandy_whisper.so` 포함 확인
- `bun run test:qr`: 순수 TypeScript QR 매트릭스 생성기 단위 테스트 통과
- `bun run lint`, `bun run build`, `bun run check:translations`, `bun run test:permissions`, `bun run test:keyboard`: 모두 오류 없이 통과
- HTTP 헤더 검증: `GET/HEAD /downloads/Handy-Android.apk`가 `200 OK`, `Content-Type: application/vnd.android.package-archive`, `Content-Disposition: attachment; filename="Handy-Android.apk"` 반환 확인
- `codememo-browser` 시각 검증: `1280×800`(한국어 전환 포함) 및 `390×844`(모바일) 뷰포트에서 오류 0건, QR 코드·APK 메타데이터·설치 안내 레이아웃 정상 렌더링 확인

#### 실제 반영 상태 및 한계

- **빌드 산출물 위치**: `android/app/build/outputs/apk/debug/app-debug.apk` 및 `public/downloads/Handy-Android.apk`(gitignored)에 즉시 설치 가능한 Debug APK가 생성되어 있습니다.
- **미수행 항목 / 한계**:
  - 실제 물리 Android 스마트폰에서의 APK 사이드로드 설치, 마이크 녹음, Whisper 모델 다운로드 및 `Handy 음성 키보드` 타 앱 입력 동작은 호스트에 연결된 Android 기기가 없어 에뮬레이터/실기기 테스트를 수행하지 못했습니다.
  - 플레이스토어 배포용 Release 키스토어 서명은 포함하지 않았으며 로컬/휴대폰 설치용 Debug 서명 APK로 구성되어 있습니다.

<a id="hist-20261121-003"></a>

### 2026-11-21 13:47:39 +09:00 — 실행 시 버전 포함 Android APK 다운로드 링크 자동 노출 및 산출물 버전화

| 항목      | 내용                                                                                     |
| --------- | ---------------------------------------------------------------------------------------- |
| ID        | `hist-20261121-003`                                                                      |
| 변경 종류 | Android APK 파일명 버전화 · 실행 서버/설치 허브 직접 다운로드 링크 노출 개선             |
| 상태      | Gradle 빌드·서명 검증·dev/preview 헤더 및 브라우저 렌더링 검증 완료 (실기기 설치는 별도) |

#### 목적

CodeMemo 실행 버튼을 눌렀을 때 버전 번호가 붙은 Android APK 다운로드 링크(`Handy-v0.10.1-android.apk`)가 서버 실행 로그와 브라우저 설치 허브 양쪽에서 즉시 노출되고, Gradle APK 산출물·HTTP `Content-Disposition` 파일명·QR 코드까지 모두 버전 번호를 일관되게 포함하도록 개선합니다.

#### 열어보는 위치 및 사용 방법

- **실행 서버 로그 (`bun run dev` / `bun run preview`)**: 서버 시작 직후 `Android APK (v0.10.1): http://<host>:<port>/downloads/Handy-v0.10.1-android.apk` 링크(0.0.0.0 바인딩 시 LAN IP 링크 포함)가 출력됩니다.
- **브라우저 설치 허브 (`AndroidInstallPage`)**: 실행 화면 상단 히어로 영역에 `Handy v0.10.1 APK 다운로드 (24.5 MB)` 버튼과 별도의 클릭 가능한 **APK 직접 다운로드 링크** 카드(`Handy-v0.10.1-android.apk` 전체 URL 및 복사 버튼), 그리고 버전 파일명 배지가 붙은 QR 코드가 바로 표시됩니다.
- **Gradle 및 게시 APK 파일**:
  - Gradle 출력: `android/app/build/outputs/apk/debug/Handy-v0.10.1-debug.apk` (`output-metadata.json` 연동)
  - 게시 출력: `android/downloads/Handy-v0.10.1-android.apk`, `dist/downloads/Handy-v0.10.1-android.apk`

#### 주요 동작 및 오류 처리

- **단일 버전 소스 동기화**: `android/app/build.gradle.kts`가 루트 `package.json`의 `version`(`0.10.1`)을 읽어 `versionName`/`versionCode`(`1001`) 및 `base.archivesName = "Handy-v0.10.1"`에 자동 반영합니다.
- **버전 포함 URL 및 하위 호환 리디렉션**: `vite.config.ts`가 `/downloads/Handy-v0.10.1-android.apk`를 `application/vnd.android.package-archive` 및 `Content-Disposition: attachment; filename="Handy-v0.10.1-android.apk"`로 스트리밍하고, 기존 `/downloads/Handy-Android.apk` 요청은 `302 Found`로 버전 URL로 리디렉션합니다.
- **서브패스 프록시 대응 및 데스크톱 번들 비대화 방지**: `AndroidInstallPage.tsx`는 현재 페이지 URL 기준 상대 경로(`downloads/...`)로 메타데이터와 APK 링크를 계산해 프록시 하위 경로에서도 깨지지 않도록 했으며, 게시 위치를 `public/downloads/`에서 `android/downloads/`(+ `dist/downloads/`)로 옮겨 `tauri build` 시 데스크톱 앱 번들에 25MB Android APK가 섞이지 않도록 분리했습니다.

#### 검증 결과

- `./android/gradlew testDebugUnitTest assembleDebug`: `android/app/build/outputs/apk/debug/Handy-v0.10.1-debug.apk` 생성 및 `output-metadata.json` (`outputFile: "Handy-v0.10.1-debug.apk"`) 확인, 단위 테스트 통과
- `bun run build:android --publish-only`: `apksigner verify --verbose` 통과(24.48 MB), `android/downloads/Handy-v0.10.1-android.apk` 및 `dist/downloads/Handy-v0.10.1-android.apk` 게시 확인
- `bun run lint`, `bun run build`, `bun run test:qr`, `bun run check:translations`, `bun run test:permissions`, `bun run test:keyboard`: 전체 통과
- `bun run dev`(포트 `1420`) 및 `bun run preview`(포트 `4173`) 검증: 시작 로그에 버전 APK 링크 출력, `/downloads/Handy-v0.10.1-android.apk` `200 OK` + 버전 `Content-Disposition`, `/downloads/Handy-Android.apk` `302 Found` 리디렉션 확인
- `codememo-browser` 시각 검증: `1280×800`(한국어) 및 `390×844`(모바일) 뷰포트에서 `Handy v0.10.1 APK 다운로드 (24.5 MB)` 버튼, 클릭 가능한 `Handy-v0.10.1-android.apk` 링크 카드, QR 코드 및 오류 0건 확인

#### 실제 반영 상태 및 한계

- 로컬 `main` 브랜치에 반영 및 빌드·검증을 완료했으며, 실제 Android 기기에서의 브라우저 다운로드 및 설치 확인은 실기기 연결 후 직접 진행이 필요합니다.

<a id="hist-20261121-004"></a>

### 2026-11-21 15:49:49 +09:00 — FocusTempo 방식과 동일한 프로젝트 루트·Gradle 디버그 APK(`Handy-v0.10.1.apk`) 배치 및 실행 탭 연동

| 항목      | 내용                                                                              |
| --------- | --------------------------------------------------------------------------------- |
| ID        | `hist-20261121-004`                                                               |
| 변경 종류 | Android APK 파일명·출력 경로 통일 (`FocusTempo` 패턴 적용)                        |
| 상태      | Gradle 빌드·서명 검증·HTTP 헤더 및 브라우저 렌더링 검증 완료 (실기기 설치는 별도) |

#### 목적

`FocusTempo` 프로젝트가 CodeMemo 실행 탭에 APK 링크를 제공하는 방식(`FocusTempo/FocusTempo.apk` 및 `FocusTempo/app/build/outputs/apk/debug/FocusTempo.apk`, `androidComponents`로 디버그 출력 파일명 지정)을 Handy에도 그대로 적용하되 버전 번호를 붙여 `Handy-v0.10.1.apk`로 통일합니다.

#### 열어보는 위치 및 사용 방법

- **CodeMemo 실행 탭 APK 링크**:
  - 프로젝트 루트: `Handy-v0.10.1.apk`
  - Gradle 디버그 출력: `android/app/build/outputs/apk/debug/Handy-v0.10.1.apk` (`output-metadata.json` 동일 반영)
- **웹 실행 서버 (`bun run dev` / `bun run preview`)**:
  - 시작 로그 및 설치 허브(`AndroidInstallPage`)에서 `/downloads/Handy-v0.10.1.apk`와 `/Handy-v0.10.1.apk` 모두 동일 파일로 다운로드할 수 있습니다.
- **빌드·동기화 명령**: `./android/gradlew assembleDebug` 실행 시 `syncRootDebugApk` 태스크가 자동으로 프로젝트 루트의 `Handy-v0.10.1.apk`를 최신화하고 이전 버전 APK를 정리합니다. `bun run build:android`도 `apksigner` 검증 후 동일 경로를 갱신합니다.

#### 주요 동작 및 오류 처리

- **FocusTempo 동일 `androidComponents` 구성**: `android/app/build.gradle.kts`에서 `base.archivesName` 대신 `androidComponents { onVariants(selector().withBuildType("debug")) { ... output.outputFileName.set("Handy-v$handyVersionName.apk") } }`를 사용해 `-debug` 접미사 없이 `Handy-v0.10.1.apk`를 직접 생성합니다.
- **중복 산출물 제거**: 이전 `android/downloads/`, `dist/downloads/`, `public/downloads/` 중복 복사본을 제거해 프로젝트 내 APK를 루트(`Handy-v0.10.1.apk`)와 Gradle 출력(`android/app/build/outputs/apk/debug/Handy-v0.10.1.apk`) 두 곳으로만 깔끔하게 유지합니다.
- **Git 저장소 보호**: 루트 `Handy-v*.apk`는 `.gitignore`로 제외해 25MB 바이너리가 GitHub 포크 저장소에 쌓이지 않도록 하면서도 디스크에 상시 존재해 CodeMemo 실행 탭이 바로 노출할 수 있도록 했습니다.

#### 검증 결과

- `./android/gradlew testDebugUnitTest assembleDebug`: 경고 0건으로 빌드·단위 테스트 통과 및 `syncRootDebugApk` 자동 실행 확인
- `find . -name '*.apk'`: 프로젝트 루트 `Handy-v0.10.1.apk` 및 `android/app/build/outputs/apk/debug/Handy-v0.10.1.apk` 2개만 존재 확인 (`output-metadata.json`의 `outputFile: "Handy-v0.10.1.apk"`)
- `bun run build:android --publish-only`: `apksigner verify --verbose` 통과(24.48 MB, APK Signature Scheme v2)
- `bun run lint`, `bun run build`, `bun run test:qr`, `bun run check:translations`, `bun run test:permissions`, `bun run test:keyboard`: 전체 통과
- `bun run dev` HTTP 헤더 및 `codememo-browser` 확인: `/downloads/Handy-v0.10.1.apk` 및 `/Handy-v0.10.1.apk` 모두 `200 OK` + `Content-Disposition: attachment; filename="Handy-v0.10.1.apk"`, `/downloads/Handy-Android.apk` `302 Found` 리디렉션, 데스크톱(한국어)·모바일 뷰포트 오류 0건 확인

#### 실제 반영 상태 및 한계

- 로컬 `main` 브랜치에 반영 및 APK 생성·검증을 완료했으며, 실제 Android 기기에서의 설치 및 동작 확인은 실기기에서 직접 진행이 필요합니다.

<a id="hist-20261121-005"></a>

### 2026-11-21 21:28:53 +09:00 — 포크 버전 `0.10.2-seionwon` 승격, MS Windows x64·macOS ARM64 설치 파일 빌드 및 실행 탭 단일 파일 정리

| 항목      | 내용                                                                                              |
| --------- | ------------------------------------------------------------------------------------------------- |
| ID        | `hist-20261121-005`                                                                               |
| 변경 종류 | 포크 버전 정책 반영 · MS Windows x64 CI 빌드 · macOS ARM64 PKG 패키징 · 실행 탭 중복 산출물 정리  |
| 상태      | Windows x64(NSIS/MSI)·macOS ARM64(PKG)·Android(APK) 빌드 및 검증 완료 (실기기 실행 테스트는 별도) |

#### 목적

포크 버전 정책(`-seionwon` 접미사)에 따라 전체 플랫폼 버전을 `0.10.2-seionwon`으로 통일하고, GitHub Actions(`windows-latest`)에서 MS Windows x64 설치 파일(NSIS·MSI)을 빌드·감사해 내려받는 한편, macOS Apple Silicon PKG 설치 파일을 빌드해 루트에 배치하고 Android APK 중복 복사본을 제거해 CodeMemo 실행 탭에 플랫폼별 설치 파일만 깔끔하게 노출되도록 합니다.

#### 열어보는 위치 및 사용 방법

- **프로젝트 루트 설치 파일 (CodeMemo 실행 탭 노출 대상)**:
  - **Android**: `Handy-v0.10.2-seionwon.apk` (단일 파일만 유지, `android/app/build/outputs/apk/debug/` 중복본 자동 제거)
  - **macOS (Apple Silicon)**: `Handy-ScreenSharing-0.10.2-seionwon-arm64.pkg` (및 `.sha256` 체크섬)
  - **MS Windows x64**: `Handy_0.10.2-seionwon_x64-setup.exe` (일반 설치 + 포터블 모드 지원 NSIS), `Handy_0.10.2-seionwon_x64_en-US.msi` (MSI)
- **Windows 재빌드 워크플로**: GitHub Actions `.github/workflows/windows-fork-build.yml` (`workflow_dispatch`, 선택적으로 `include_arm64` 지원). `src-tauri/tauri.windows-fork.conf.json`으로 업스트림 Azure 서명 명령(`trusted-signing-cli`)과 업스트림 업데이터 산출물을 비활성화하고 `--features disable-updater`로 빌드합니다.
- **macOS 재빌드 명령**: `CMAKE_POLICY_VERSION_MINIMUM=3.5 bun scripts/build-macos-share.ts --arm64-only` (`scripts/build-macos-share.ts`·`scripts/verify-macos-package.ts`가 `-seionwon` SemVer 버전을 지원하며, Apple `pkgbuild`/Distribution의 숫자 전용 필드는 `0.10.2`로, `CFBundleShortVersionString`과 PKG 파일명은 `0.10.2-seionwon`으로 기록).

#### 주요 동작 및 오류 처리

- **버전 정책 일관 적용**: `package.json`, `src-tauri/Cargo.toml`, `src-tauri/Cargo.lock`, `src-tauri/tauri.conf.json`, Android `versionName`, macOS `CFBundleShortVersionString`, 설치 파일명 모두 `0.10.2-seionwon`을 사용하고, 숫자 전용 필드(Android `versionCode=1002`, Windows MSI `ProductVersion=0.10.2`, macOS `pkgbuild --version 0.10.2`)는 규격에 맞는 숫자 값을 유지합니다.
- **Windows 앱 로컬 런타임 포함**: Windows x64 빌드에 Vulkan SDK 1.4.309.0 + `spirv-headers`로 컴파일된 `transcribe-cpp` 런타임/백엔드 DLL 13개와 VC++ 재배포 런타임 DLL 9개(`msvcp140.dll`, `vcruntime140.dll`, `vcomp140.dll` 포함)를 앱 로컬로 번들해 별도 VC++ 재배포 패키지 없이 실행되도록 했습니다.
- **Android 실행 탭 중복 제거**: `android/app/build.gradle.kts`의 `syncRootDebugApk` 태스크와 `scripts/build-android.ts`가 루트 `Handy-v0.10.2-seionwon.apk`로 복사한 뒤 `android/app/build/outputs/apk/debug/*.apk` 원본을 삭제해 프로젝트 전체에 Android APK가 1개만 남도록 보장합니다.

#### 검증 결과

- **MS Windows x64 CI (`seionwon/Handy` run `44900119452`)**:
  - NSIS(`Handy_0.10.2-seionwon_x64-setup.exe`, 16.5 MB) 및 MSI(`Handy_0.10.2-seionwon_x64_en-US.msi`, 31.3 MB) 빌드 성공
  - Windows release Rust 단위 테스트: `354 passed; 0 failed; 20 ignored` 통과
  - Windows 런타임 감사: NSIS `/S /PORTABLE` 무인 설치 및 `msiexec /a` MSI 추출 후 `handy.exe`·전체 스테이징 DLL 존재 및 `handy.exe --list-devices` 정상 종료 확인
  - 로컬 다운로드 후 `shasum -a 256 -c` 검증 통과 (NSIS `56b6736e3c41…`, MSI `218b36e4b59c…`)
- **macOS ARM64 PKG (`Handy-ScreenSharing-0.10.2-seionwon-arm64.pkg`, 14.1 MB)**:
  - 지속 로컬 서명 인증서(`Handy Local Build`, `f833ee2fd790…`) 서명 및 `codesign --verify --deep --strict` 통과
  - `scripts/verify-macos-package.ts` 통과(네이티브 `must-close`, `/Applications/Handy.app` 고정, 스크립트 없음, `0.10.2-seionwon`·`arm64` 일치 확인) 및 `shasum -a 256 -c` 통과(`ae81e577d074…`)
- **Android APK (`Handy-v0.10.2-seionwon.apk`, 24.5 MB)**:
  - `./android/gradlew testDebugUnitTest assembleDebug` 및 `bun run build:android --publish-only` (`apksigner verify --verbose`, `versionCode=1002`) 통과, 프로젝트 전체에 APK 1개만 존재 확인
- **프론트엔드·로컬 Rust 검사**: `bun run lint`, `bun run build`, `bun run test:qr`, `bun run check:translations`, `bun run test:permissions`, `bun run test:keyboard`, `cargo test --features disable-updater --lib --locked` 통과

#### 실제 반영 상태 및 한계

- **설치 파일 위치**: 프로젝트 루트에 Android APK 1개, macOS ARM64 PKG 1개, Windows x64 NSIS/MSI 2개가 모두 준비되어 있으며 `.gitignore`로 Git 커밋에서는 제외되어 있습니다.
- **미수행 항목 / 한계**:
  - macOS 패키지는 현재 설치된 Rust 타깃(`aarch64-apple-darwin`) 및 Command Line Tools SDK 기준으로 `--arm64-only`(Apple Silicon 전용, Apple Intelligence 스텁)로 빌드되었습니다. Intel Mac 겸용 Universal 또는 Apple Intelligence 활성화 빌드가 필요하면 `rustup target add x86_64-apple-darwin` 및 Xcode 라이선스 동의 후 `bun scripts/build-macos-share.ts`를 실행해야 합니다.
  - Windows 설치 파일은 업스트림 Azure 코드 서명 없이 빌드되어 Windows SmartScreen 경고에서 "추가 정보 → 실행"이 필요하며, 실제 Windows PC에서의 GUI 녹음/붙여넣기 테스트와 macOS/Android 실기기 설치 테스트는 직접 확인이 필요합니다.

<a id="hist-20261122-001"></a>

### 2026-11-22 04:37:31 +09:00 — 데스크톱 시작 시 번역 로딩 및 긴 오디오 분할 최적화

| 항목      | 내용                                                            |
| --------- | --------------------------------------------------------------- |
| ID        | `hist-20261122-001`                                             |
| 변경 종류 | 데스크톱 성능 개선                                              |
| 상태      | 로컬 소스 반영, 프런트엔드 빌드 및 동작 검증, 설치 파일 배포 전 |

#### 목적 및 사용 방법

- 설정 창과 녹음 오버레이가 시작할 때 읽는 번역 데이터와 긴 녹음을 전사용 구간으로 나누는 데 드는 복사를 줄였습니다.
- 설정의 **정보 → 애플리케이션 언어**에서 표시 언어를 선택합니다. 선택한 언어와 대체 언어의 번역은 앱에 포함된 파일에서 읽습니다.
- **히스토리**에서 파일을 전사하면 개선한 분할 경로를 사용합니다. 받아쓰기에서는 모델의 스트리밍·장시간 음성 처리 지원 여부와 **고급 → 긴 오디오 분할** 설정에 따라 이 경로를 사용합니다.

#### 동작 및 오류 처리

- 데스크톱 설정 창은 저장된 언어의 번역을 준비한 뒤 첫 화면을 표시합니다. 한 창에서 이미 읽은 번역은 언어를 다시 선택할 때 재사용합니다. 선택한 번역 파일을 열 수 없으면 영어 문구를 표시합니다.
- 녹음 오버레이를 표시할 때 언어와 위치를 한 번에 조회합니다. 오버레이를 먼저 닫으면 이전 조회가 나중에 끝나도 닫힌 상태를 유지합니다.
- 여러 구간의 경계를 먼저 정해 남은 오디오를 한 번만 앞으로 옮깁니다. 한 구간으로 끝나는 녹음은 기존 샘플 버퍼를 그대로 넘기며, 음성 감지에 실패하면 남은 오디오를 한 구간으로 전사합니다.

#### 검증 결과

- 같은 프로덕션 빌드 조건에서 한국어 시작 경로의 JavaScript 크기 합계(압축 전)는 설정 창이 2,239,862바이트에서 1,095,829바이트로, 녹음 오버레이가 1,455,116바이트에서 312,278바이트로 줄었습니다.
- Apple Silicon에서 16kHz 합성 오디오 30분과 고정된 음성 판정값으로 분할 처리만 측정했습니다. 예열 후 7회 중앙값은 전체 입력에서 42.76ms → 1.64ms, 512샘플씩 입력했을 때 5.85ms → 4.26ms였습니다. 전후 모두 40초 구간 45개와 모든 샘플의 값·순서가 일치했습니다.
- 빌드된 화면을 로컬 DOM과 모의 Tauri 이벤트로 실행해 한국어 첫 표시, 번역 파일 오류 시 영어 표시, 아랍어의 오른쪽에서 왼쪽 쓰기, 녹음 준비·즉시 숨기기, 오버레이 표시당 설정 조회 1회를 확인했습니다. `bun run test:i18n`, 번역 키 검사, 프런트엔드 빌드와 린트가 통과했습니다.
- Rust 분할 테스트 8개를 포함해 362개가 통과했습니다. 기존 무시 항목은 20개이고, 로컬 테스트 서버를 여는 21개는 실행 환경에서 포트 바인딩을 거부해 검증하지 못했습니다. Rust 포맷 검사와 Clippy가 완료됐으며 Clippy는 변경 범위 밖 경고 8개를 보고했습니다.

#### 실제 반영 상태 및 한계

- 로컬 데스크톱 소스와 프런트엔드 빌드에 반영했습니다. 신규 설치 파일은 배포 전이며 실제 마이크로 녹음을 시작해 전사를 마칠 때까지의 시간은 미측정입니다.

<a id="hist-20261122-002"></a>

### 2026-11-22 11:31:36 +09:00 — Android에서 기존 키보드와 함께 쓰는 받아쓰기 버튼

| 항목      | 내용                                      |
| --------- | ----------------------------------------- |
| ID        | `hist-20261122-002`                       |
| 변경 종류 | Android 입력 기능 추가                    |
| 상태      | 로컬 소스와 테스트 APK 반영, 정식 배포 전 |

#### 목적 및 사용 방법

- Android 13 이상에서 평소 쓰던 키보드를 띄운 채 다른 앱의 현재 입력칸에 Handy로 받아쓸 수 있습니다.
- Handy의 **설정 → 기존 키보드에서 받아쓰기**에서 처음 한 번 마이크를 허용합니다. **접근성 설정 열기**를 눌러 **Handy 받아쓰기**를 켜고 바로가기로 **접근성 버튼**을 선택합니다. 처음 사용할 때는 **음성 입력** 화면에서도 같은 설정을 열 수 있습니다.
- 메모나 메시지의 입력칸을 누른 뒤 화면의 접근성 버튼을 한 번 누르고 말합니다. 말이 끝나면 커서 위치에 글이 들어가고 같은 키보드로 이어서 타이핑할 수 있습니다.

#### 동작 및 오류 처리

- 시스템 음성 인식은 기기가 말의 끝을 알려주면 결과를 입력합니다. 오프라인 Whisper는 말한 뒤 약 1.5초간 조용해지면 변환을 시작합니다. 녹음 중 같은 버튼을 다시 눌러 직접 마칠 수도 있습니다.
- 입력칸을 바꾸면 진행 중인 받아쓰기를 취소합니다. 입력할 곳이 없거나 Whisper 모델이 준비되지 않은 경우에는 다음에 할 일을 화면에 표시합니다.

#### 검증 결과

- Android 단위 테스트 22개가 통과했습니다. 시스템 인식 결과와 오프라인 Whisper 출력을 모의해 버튼을 한 번 누른 뒤의 자동 종료와 커서 입력, 중복 결과 처리, 입력칸 변경을 확인했습니다.
- 디버그 APK 빌드와 릴리스 Kotlin 컴파일이 통과했습니다. Android Lint는 오류 0개, 경고 39개를 보고했습니다.

#### 실제 반영 상태 및 한계

- 당시 로컬 테스트 APK를 `android/app/build/outputs/apk/debug/`에 빌드했습니다. 정식 배포 전이며, 실제 휴대폰에서 삼성 키보드와 Gboard를 띄워 녹음하는 조작은 기기 연결 제한으로 확인하지 못했습니다. [현재 APK 다운로드 위치](#hist-20261122-003)

<a id="hist-20261122-003"></a>

### 2026-11-22 12:45:38 +09:00 — Android 다운로드에 최신 APK 한 개만 표시

| 항목      | 내용                                             |
| --------- | ------------------------------------------------ |
| ID        | `hist-20261122-003`                              |
| 변경 종류 | Android 설치 파일 중복 표시 수정                 |
| 상태      | 로컬 테스트 APK와 빌드 절차에 반영, 정식 배포 전 |

#### 사용 방법 및 동작

- CodeMemo의 **실행 → Android**에서 **이 기기로 내려받기**를 누르면 마지막으로 빌드에 성공한 Handy APK를 받습니다. 당시 파일은 기존 키보드와 함께 쓰는 받아쓰기 버튼을 포함한 `Handy-v0.10.2-seionwon-debug.apk`였습니다. [이후 버전의 사용 방법](#hist-20261122-004)
- 새 APK가 완성되면 프로젝트 루트의 다운로드 파일을 교체하고 이전 버전과 빌드 폴더에 남은 복사본을 정리합니다. 빌드에 실패하면 직전에 완성된 APK를 계속 받을 수 있습니다.

#### 검증 결과

- Android 패키징과 조립을 반복한 뒤 프로젝트 전체의 APK가 한 개이고 Gradle 설치 경로가 같은 파일을 가리키는지 확인했습니다. 패키징 도중 실패를 재현한 뒤에도 직전 APK의 내용과 수정 시각이 그대로였습니다.
- 동시에 두 빌드가 완료되는 경우와 파일 교체 실패 시 복구를 포함해 패키징 테스트 6개와 Android 앱 테스트 22개가 통과했습니다.

<a id="hist-20261122-004"></a>

### 2026-11-22 13:10:53 +09:00 — Android 기본 받아쓰기의 간편한 설치와 첫 녹음 복원

| 항목      | 내용                                                                           |
| --------- | ------------------------------------------------------------------------------ |
| ID        | `hist-20261122-004`                                                            |
| 변경 종류 | Android 설치 및 첫 사용 흐름 수정                                              |
| 상태      | `0.10.3-seionwon` 릴리스 APK를 프로젝트 실행 탭과 Android 설치 페이지에서 제공 |

#### 사용 방법 및 동작

- CodeMemo의 **실행 → Android → 이 기기로 내려받기**에서 `Handy-v0.10.3-seionwon.apk`를 받습니다. 기본 APK는 접근성 버튼 설정 단계를 거치지 않고 **음성 입력** 화면으로 열립니다.
- 이전 테스트판(`-debug.apk`)에서 릴리스판으로 옮길 때는 서명이 달라 새로 설치해야 합니다. 기존 녹음과 기록이 필요하면 테스트판을 삭제하기 전에 따로 보관하세요.
- 기본 탭 방식에서는 녹음 버튼을 누르고 마이크 사용을 허용하면 바로 녹음이 시작됩니다. **누르고 말하기**를 선택한 경우에는 허용한 뒤 버튼을 누르고 있는 동안 녹음합니다. 마이크를 거절해도 다른 탭을 쓸 수 있으며, Android에서 다시 묻지 않는 경우에만 앱 설정으로 이동하는 선택지를 표시합니다.
- 평소 쓰는 키보드에서는 Handy에서 받아쓴 문장을 붙여넣을 수 있습니다. **자동 클립보드 복사**는 기본으로 켜져 있습니다. 입력칸에서 직접 받아쓰려면 Handy의 **설정 → 시스템 음성 키보드 (IME)**에서 Handy 키보드를 켜고 선택합니다.

#### 검증 및 제공 상태

- 기본 앱 테스트 21개와 패키징 테스트 6개가 통과했습니다. 첫 허용, 거절 후 재시도, 연속 탭과 화면 재생성 후의 처리를 확인했고, 다운로드용 APK는 프로젝트에 한 개입니다.
- 로컬 실행 탭용 APK를 빌드했습니다. 실제 휴대폰에서의 설치 화면은 확인하지 못했습니다.
- 2026-11-22 14:38:41 +09:00에 다운로드 파일을 릴리스 APK로 교체했습니다. 두 번 빌드해 같은 배포 서명을 확인했고, 설치 페이지의 다운로드 응답과 프로젝트 루트의 파일 내용이 일치했습니다. Google Play에는 게시하지 않았습니다.

<a id="hist-20261122-005"></a>

### 2026-11-22 17:52:34 +09:00 — 포크 저장소 첫 화면의 기기별 설치 파일

| 항목      | 내용                                                                    |
| --------- | ----------------------------------------------------------------------- |
| ID        | `hist-20261122-005`                                                     |
| 변경 종류 | GitHub 저장소 첫 화면 및 릴리스 배포                                    |
| 상태      | 포크 릴리스와 저장소 첫 화면 게시 완료, 비로그인 다운로드 4개 검증 완료 |

#### 사용 방법 및 동작

- 저장소를 열면 파일 목록 아래에 다운로드 표가 자동으로 표시됩니다. Android는 APK, Apple Silicon Mac은 PKG, Windows x64는 EXE를 누릅니다. MSI 설치가 필요한 사용자는 같은 표에서 MSI를 선택합니다. [포크 저장소 첫 화면](https://github.com/seionwon/Handy)
- 각 링크는 포크 릴리스에 첨부된 파일을 직접 가리킵니다. 게시한 Android 파일은 `0.10.3-seionwon`, macOS 및 Windows 파일은 `0.10.2-seionwon`입니다. [설치 파일 릴리스](https://github.com/seionwon/Handy/releases/tag/installers-0.10.3-seionwon)
- 저장소의 **About**에는 포크의 용도가, 다운로드 표 위의 **원본 Handy와 달라진 점**에는 Mac 화면 공유 받아쓰기, Android 오프라인 전사, 기존 키보드에서 붙여넣기와 Handy 음성 키보드로 직접 입력하는 방법이 표시됩니다. 다음 릴리스부터는 설치 표만 자동으로 갱신됩니다.

#### 검증 및 제공 상태

- 설치 파일 네 개를 로컬 Git 저장소 사이에 전송해 원본과 대조했고, 표를 렌더링해 파일 이름과 버전을 확인했습니다. 게시를 모의 실행해 중단된 업로드를 이어 마치고 공개 다운로드 확인 뒤에만 첫 화면이 갱신되는 동작을 검증했습니다.
- 2026-11-22 23:12:37 +09:00에 `installers-0.10.3-seionwon`을 포크의 최신 공개 릴리스로 게시했습니다. 기본 브랜치에 반영한 첫 화면 커밋은 `635f222`입니다.
- 2026-11-22 23:17:49 +09:00에 공개 상태 검증을 마쳤습니다. 로그인하지 않은 저장소 첫 화면과 릴리스 본문에서 네 개의 직접 다운로드 링크를 확인했고, 네 파일을 모두 내려받아 각 파일의 크기와 SHA-256을 원본과 대조했습니다.
- 2026-11-24 17:33:59 +09:00에 기본 브랜치의 포크 소개(`9b453af`)와 About의 공개 반영을 확인했습니다. 로그인하지 않은 저장소 첫 화면에 세 변경점이 다운로드 표보다 먼저 표시됐고, 네 설치 파일을 내려받아 원본과 SHA-256이 일치함을 확인했습니다. 다음 배포를 모의 실행해 작성한 소개가 유지되는 것도 확인했습니다.

<a id="hist-20261125-001"></a>

### 2026-11-25 01:11:00 +09:00 — 설정 창을 열 때 읽는 화면 코드 줄이기

| 항목      | 내용                                                            |
| --------- | --------------------------------------------------------------- |
| ID        | `hist-20261125-001`                                             |
| 변경 종류 | 데스크톱 성능 개선                                              |
| 상태      | 로컬 소스 반영, 프런트엔드 빌드 및 동작 검증, 설치 파일 배포 전 |

#### 목적 및 사용 방법

- 설정 창은 처음 표시하는 **일반** 탭에 필요한 코드만 읽고 시작합니다. 사용 방법은 이전과 같으며 따로 켤 설정은 없습니다.
- **히스토리**, **모델**, **고급**, **후처리**, **정보** 탭의 코드는 설정 화면이 표시된 뒤 창이 한가할 때 미리 읽습니다. **디버그** 탭, 첫 실행 온보딩, 권한·모델 업데이트 화면, 새 소식의 본문 표시 코드는 해당 화면을 열 때 읽습니다.

#### 동작 및 오류 처리

- 아직 읽지 않은 탭을 누르면 코드를 읽는 동안 현재 탭을 그대로 표시하다가 준비되면 바꿉니다. 빈 화면이 끼어들지 않습니다.
- 온보딩이나 권한 화면의 코드를 읽는 동안에는 기존 **로딩 중...** 화면을 표시합니다.
- 미리 읽기에 실패한 탭은 그 탭을 열 때 다시 읽습니다.

#### 검증 결과

- 같은 프로덕션 빌드 조건에서 설정 창이 시작할 때 읽는 JavaScript 합계(번역 파일 제외, 압축 전)는 1,055,059바이트에서 606,072바이트로 줄었습니다. 가장 큰 파일은 726,484바이트에서 279,420바이트가 됐습니다.
- 빌드된 화면을 로컬 브라우저와 모의 Tauri 응답으로 실행했습니다. 시작 시 다른 탭의 코드를 요청하지 않는 것, 일곱 개 탭이 모두 표시되는 것, 새 소식 본문, 첫 실행 온보딩 첫 화면을 확인했고 화면 오류는 없었습니다.
- 프런트엔드 빌드(타입 검사 포함), 린트, 번역 키 검사, `bun run test:i18n`이 통과했습니다.

#### 실제 반영 상태 및 한계

- 로컬 데스크톱 소스와 프런트엔드 빌드에 반영했습니다. 신규 설치 파일은 배포 전입니다.
- 실제 앱에서 창이 뜨기까지의 시간은 측정하지 못했습니다. Rust 백엔드와 Android 앱은 변경하지 않았습니다.
- 2026-11-25 02:13:04 +09:00에 소스를 포크 저장소 `main`에 올렸습니다. 이 변경을 담은 데스크톱 설치 파일은 아직 없습니다. [배포 상태](#hist-20261125-002)

<a id="hist-20261125-002"></a>

### 2026-11-25 01:53:47 +09:00 — `0.10.4-seionwon` 릴리스

| 항목      | 내용                                                                    |
| --------- | ----------------------------------------------------------------------- |
| ID        | `hist-20261125-002`                                                     |
| 변경 종류 | 버전 올림 및 설치 파일 릴리스                                           |
| 상태      | 소스 커밋·푸시 완료, Android APK 게시, macOS·Windows 설치 파일은 미배포 |

#### 사용 방법

- 저장소 첫 화면의 다운로드 표나 릴리스 페이지에서 기기에 맞는 파일을 받습니다. [설치 파일 릴리스](https://github.com/seionwon/Handy/releases/tag/installers-0.10.4-seionwon)
- Android는 `Handy-v0.10.4-seionwon.apk`입니다. 기존 `0.10.3-seionwon` 릴리스판 위에 그대로 설치해 업데이트할 수 있습니다. CodeMemo의 **실행 → Android**에서 받는 파일도 이 APK입니다.

#### 검증 결과

- Rust 단위 테스트 384개(무시 20개), Android 단위 테스트, 프런트엔드 빌드(타입 검사 포함)와 린트가 통과했습니다.
- Android APK는 빌드 스크립트가 배포 서명과 버전(`versionCode` 1004)을 확인했습니다.
- 게시 뒤 로그인하지 않은 상태로 설치 파일 네 개를 내려받아 SHA-256이 목록과 일치하는 것을 확인했고, 저장소 첫 화면의 다운로드 표가 새 릴리스를 가리키는 것을 확인했습니다.

#### 실제 반영 상태 및 한계

- 2026-11-25 02:13:04 +09:00에 버전을 `0.10.4-seionwon`으로 올려 포크 저장소 `main`에 올렸고, 설치 파일 릴리스 `installers-0.10.4-seionwon`을 최신 릴리스로 게시했습니다.
- **macOS·Windows**: 새 설치 파일을 만들지 못해 릴리스에는 `0.10.2-seionwon` 파일이 그대로 있습니다. macOS 패키지는 Developer ID Installer 인증서(`HANDY_MACOS_INSTALLER_IDENTITY`)가, Windows 빌드는 저장소 비밀 값 `HANDY_WINDOWS_CERT_PFX`와 `HANDY_WINDOWS_CERT_PASSWORD`가 있어야 하는데 둘 다 설정돼 있지 않습니다.
- 포크 저장소에서 GitHub Actions가 푸시와 태그에 실행되지 않아, 게시 단계는 같은 스크립트를 태그 체크아웃에서 직접 실행했습니다.
