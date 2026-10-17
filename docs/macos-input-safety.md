# macOS 입력·권한 수정

보고된 시스템 전체 입력 장애를 일부러 재현하지 않고, 코드에서 확인한 장애 경로를 수정했다. 실제 macOS 권한 변경 전후의 동작은 별도 검증이 필요하다.

- macOS의 단축키·진단·단축키 설정용 감시기는 수동 `ListenOnly` 이벤트 탭을 사용한다. 입력을 차단하지 않으므로 단축키 조합이 다른 앱에도 전달될 수 있다. 다른 앱과 겹치지 않는 단축키를 선택한다. 수동 감시와 입력 필터의 차이는 [Apple의 CGEventTapOptions 문서](https://developer.apple.com/documentation/coregraphics/cgeventtapoptions)에 설명되어 있다.
- 이벤트 콜백은 잠긴 상태를 기다리지 않는다. 사용자가 비활성화한 탭을 자동으로 다시 켜지 않으며, Handy가 합성한 붙여넣기·전송 키 입력으로 단축키가 재실행되지 않는다. Fn 상태 처리도 바로잡았다.
- 이벤트 탭 생성은 3초, 관리자 응답은 5초로 제한한다. 초기화와 단축키 구현 전환은 UI 스레드 밖에서 처리한다. 생성 완료를 확인한 뒤 상태를 공개하고, 중복 초기화를 막으며, 초기화 실패를 프런트엔드에 전달한다.
- 권한 확인에서는 입력 감시기를 만들지 않는다. 신규·기존 사용자 모두 현재 승인 상태를 먼저 확인하고, 필요한 권한이 이미 있으면 권한 화면을 건너뛴다. 승인 여부를 확인한 후 메인 앱에서 입력 시스템을 한 번 초기화한다. 늦게 도착한 시작 상태 조회는 화면 종료 후 무시한다.
- 손쉬운 사용 설정 버튼은 클릭 시 권한을 다시 확인한다. 이미 승인됐다면 설정을 열지 않으며, 네이티브 명령도 실제 승인 상태를 재확인한다. 거부된 경우에만 시스템 설정을 여는 명령 하나를 호출한다. 처리 중 중복 클릭을 막고, 설정 카드도 첫 확인이 끝날 때까지 표시하지 않는다. 다른 창에서 돌아오면 승인 상태를 다시 읽는다. 목록에 없는 앱만 + 버튼으로 설치한 Handy.app을 추가한다.
- 마이크 권한 요청에 실제 완료 콜백을 전달한다. 이전 플러그인의 요청 경로는 빈 콜백을 전달했다. 이미 거부된 경우에는 해당 설정 화면을 연다.
- 시작 상태·권한 조회를 기다리는 화면에서도 ‘종료’ 버튼을 사용할 수 있다.
- 붙여넣기·자동 전송 중 오류나 패닉이 발생해도 이미 누른 합성 키의 해제를 시도한다. 한 키의 해제가 실패해도 다른 키의 해제를 계속한다.
- 단축키 설정은 세션마다 별도 취소 상태를 사용하며, 메인 창의 포커스를 잃거나 30초가 지나면 종료한다. 키 입력은 메인 창에만 전달한다. 프런트엔드의 중복 저장과 늦은 이벤트 구독 정리도 처리한다.
- 단축키 설정의 10ms 반복 조회를 100ms 제한이 있는 채널 수신으로 바꾸고, 관리자 반복 조회는 10ms에서 20ms로 줄였다. 권한 확인도 이전 요청이 끝난 뒤 다음 요청을 예약한다.

## 기존 Handy 항목과 승인 유지

기존 Handy 항목은 삭제하지 않고 켜 둔다. Handy는 [AXIsProcessTrusted](https://developer.apple.com/documentation/applicationservices/1460720-axisprocesstrusted)로 현재 프로세스의 실제 승인을 확인하고 유효한 권한을 그대로 사용한다. 앱을 완전히 종료하고 DMG를 추출한 다음 `/Applications/Handy.app`을 실행하면 다른 빌드 사본을 실행하는 혼동을 줄일 수 있다.

권한 목록에 항목이 있다는 사실과 현재 실행 파일에 승인이 있다는 사실은 다르다. 이 환경의 임시 서명은 코드 해시를 식별 조건으로 사용한다. 수정 빌드마다 그 값이 달라질 수 있어, 예전 항목이 켜져 있어도 macOS가 현재 앱을 거부할 수 있다. 이 경우 앱이 기존 승인을 강제로 재사용할 수는 없으며 현재 버전의 승인이 필요하다. [Apple의 지정 요구사항 설명](https://developer.apple.com/documentation/technotes/tn3127-inside-code-signing-requirements)을 참고한다.

배포 스크립트에 기존 인증서를 지정하는 `HANDY_MACOS_SIGNING_IDENTITY` 옵션을 추가했다. 이후에도 같은 서명 신원을 유지하기 위한 옵션이며, 과거 임시 서명을 같은 신원으로 바꾸는 기능은 아니다. 현재 환경에는 사용할 수 있는 서명 인증서가 없어 제공하는 로컬 빌드는 여전히 임시 서명이다. 코드는 TCC 데이터베이스를 수정하거나 권한을 자동 초기화하지 않는다.

## 앱 검색의 중복 아이콘

작업 폴더에서 일반 빌드 사본과 배포 준비 사본을 확인했다. 설치본과 이름·번들 ID가 같아 검색 중복의 원인이 될 수 있다. 빌드 트리는 `src-tauri/target.noindex/`로 옮기고 기존 `target` 경로는 심볼릭 링크로 유지했다. 앞으로 개발·배포 빌드도 이 경로를 사용한다. DMG 임시 사본은 검색 제외 폴더에 만들고 성공·실패 후 정리한다.

기존 사본의 검색 등록 해제는 해당 저장소 경로에만 시도한다. 현재 도구 환경에서는 Launch Services가 `-10814`를 반환해 검색 목록에서 사라졌는지는 확인하지 못했다. 실제 화면에서 설치본 하나가 나오는지는 별도 확인이 필요하다. [정리 명령과 한계](../BUILD.md#duplicate-handy-icons-in-macos-app-search)를 참고한다.

## 보안 점검

- CSP를 활성화해 실행할 스크립트와 불러올 리소스를 제한했다. [Tauri CSP 문서](https://v2.tauri.app/security/csp/)를 기준으로 IPC, 로컬 오디오와 필요한 스타일을 허용한다.
- 무제한 asset 파일 접근과 앱 데이터 전체 읽기 범위를 제거했다. 파일 이름, 확장자, 실제 경로와 심볼릭 링크를 검증한 녹음 WAV 파일에만 읽기 권한을 추가한다. 기록 정리와 재전사에도 같은 검증을 적용한다.
- 녹음 오버레이에서 설정·파일·권한 요청 플러그인 권한을 제거했다. 메인 창에서는 사용하지 않는 카메라·전체 디스크·화면 녹화·입력 모니터링 요청 권한을 제거했다.
- API 인증 헤더를 로그에서 숨기고, API 요청의 자동 리디렉션을 막아 사용자 지정 인증 헤더가 다른 서버로 전달되는 경로를 차단했다.
- Vite 개발 서버는 `127.0.0.1`에만 바인딩한다. 현재 잠금 파일의 Vite 6.4.1은 [GHSA-p9ff-h696-f583](https://github.com/vitejs/vite/security/advisories/GHSA-p9ff-h696-f583)의 영향 버전이다. 해당 공지의 외부 네트워크 노출 조건을 기본 설정에서 차단했지만, 네트워크 사용이 가능한 환경에서 Vite를 6.4.2 이상으로 업데이트해야 한다. 개발 서버 취약점이며 배포 앱은 Vite 서버를 실행하지 않는다.

전체 의존성 취약점 조회는 네트워크 제한 때문에 완료하지 못했다. API 키의 운영체제 키체인 저장 전환과 외부 침투 테스트는 이번 점검에 포함하지 않았다.

## 검증

다음 검사를 실행한다. 입력 감시기를 실제로 설치하거나 키를 OS에 전송하는 테스트는 실행하지 않는다.

```sh
bun run build
bun run lint
bun run test:permissions
bun run test:keyboard
bun run check:translations
cargo clippy --manifest-path src-tauri/Cargo.toml --release --features disable-updater
cargo test --manifest-path src-tauri/Cargo.toml --release --features disable-updater --lib input::chord_tests
cargo test --manifest-path src-tauri/Cargo.toml --release --features disable-updater --lib history_audio_path
cargo test --manifest-path src-tauri/Cargo.toml --release --features disable-updater --lib llm_client::tests::anthropic_format_uses_api_key_headers
cargo test --manifest-path src-tauri/vendor/handy-keys/Cargo.toml --lib
```

macOS 네이티브 빌드는 선택한 Xcode SDK와 Rust 대상 아키텍처에 맞는 환경이 필요하다. 이 환경의 로컬 빌드와 검사에는 `src-tauri/target/build-this-mac.sh` 및 `check-input-fix.sh`의 환경을 사용했다.

권한 재사용·시작 단계 선택·조회 실패 처리·입력 초기화의 중복 방지·조회 취소는 `bun run test:permissions`로 검사한다. 권한 화면의 브라우저 회귀 테스트는 `tests/permissions.spec.ts`에 있으며 기존 설정 카드와 온보딩의 중복 요청 방지·첫 조회 중 버튼 숨김·기존 승인 재사용·포커스 복귀 후 갱신을 포함한다. 모든 네이티브 명령을 모의 처리한다. 현재 실행 환경에서는 로컬 서버 바인딩과 Chrome 실행이 거부되어 브라우저 검사를 완료하지 못했다. 제한이 없는 환경에서는 다음과 같이 실행한다.

```sh
bun run test:playwright tests/permissions.spec.ts
```

실제 권한 검증 시에는 미등록·기존 승인·기존 거부·앱 교체 뒤 오래된 항목·권한 철회·재시작을 각각 확인하고, 단축키 설정 중 포커스 이동과 빠른 시작/취소도 확인한다.
