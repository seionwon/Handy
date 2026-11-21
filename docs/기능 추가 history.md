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
- **보안·패키징 유지**: strict CSP, 최소 권한 capabilities, `history_audio_path` 경로/심볼릭 링크 검증(`transcription_jobs.rs` 포함), `llm_client.rs`의 민감 인증 헤더 및 리디렉션 차단(`Policy::none()`), `disable-updater` Cargo 피처, Vite `127.0.0.1` 바인딩, `macos_install`/`macos_instance`, `scripts/build-macos-share.ts` 및 로컬 서명/PKG 도구를 모두 보존했습니다.
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
- **보안 및 안정성**: `LlmClient.kt`는 리디렉션으로 인한 API 키 유출을 막기 위해 `instanceFollowRedirects = false`를 적용했고, `TextPostProcessor.kt`는 경로 이탈 파일명 차단 및 `<think>` 블록 정리·빈 응답 거부를 수행합니다.
- **데스크톱 앱 무영향 격리**: `src/main.tsx`는 `"__TAURI_INTERNALS__" in window`일 때 기존 Tauri 데스크톱 초기화 경로를 그대로 유지하고, 일반 브라우저 환경에서만 `AndroidInstallPage`를 동적 임포트합니다. `vite.config.ts`의 `127.0.0.1` 바인딩(GHSA-p9ff-h696-f583 방어)도 그대로 유지했습니다.

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
