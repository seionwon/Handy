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

Handy는 [AXIsProcessTrusted](https://developer.apple.com/documentation/applicationservices/1460720-axisprocesstrusted)로 현재 프로세스의 실제 승인을 확인하고 유효한 권한을 그대로 사용한다. 2026-10-17 실제 설치본의 시스템 로그에서 `Failed to match existing code requirement`가 손쉬운 사용과 마이크 서비스에 대해 발생했다. 저장된 이전 빌드의 코드 해시와 실행 중인 새 빌드의 코드 해시가 달랐고, 권한 화면이 다음 단계로 넘어가지 않는 원인이었다. 단순히 권한 확인 UI를 바꾸는 이전 수정으로는 이 원인이 해결되지 않았다.

권한 목록에 항목이 있다는 사실과 현재 실행 파일에 승인이 있다는 사실은 다르다. 이전 배포본의 임시 서명은 코드 해시를 식별 조건으로 사용한다. 수정 빌드마다 그 값이 달라질 수 있어, 예전 항목이 켜져 있어도 macOS가 현재 앱을 거부할 수 있다. 이 경우 앱이 기존 승인을 강제로 재사용할 수는 없으며 현재 버전의 승인이 필요하다. [Apple의 지정 요구사항 설명](https://developer.apple.com/documentation/technotes/tn3127-inside-code-signing-requirements)을 참고한다.

0.9.9부터 배포 스크립트의 기본값은 같은 로컬 인증서를 계속 사용하는 방식이다. 전용 키체인은 `~/Library/Application Support/HandyBuildSigning/`에 0700 권한으로 보관하고 암호 파일은 0600으로 제한한다. 암호는 명령 인자가 아닌 표준 입력으로 전달하며, 일회용 개인키·PKCS#12 파일을 정리하고 서명 후 키체인을 잠근다. 기본 키체인 검색 목록을 유지하고 시스템 신뢰 루트를 추가하지 않는다. 이미 보유한 인증서를 쓰는 `HANDY_MACOS_SIGNING_IDENTITY` 옵션도 유지한다. CI에서 매번 새 신원을 생성하는 동작과 임시 서명 배포는 거부한다. [Apple의 서명 신원·지정 요구사항 설명](https://developer.apple.com/library/archive/technotes/tn2206/)을 따른다.

이전 임시 서명에서 고정 인증서로 넘어오는 시점에는 새 신원에 대한 승인이 한 번 필요하다. **Handy를 완전히 종료한 상태에서 기존 Handy 항목을 한 번 껐다 켜고 `/Applications/Handy.app`을 실행**해 갱신한다. 기존 항목을 삭제하거나 TCC 데이터베이스를 초기화하지 않는다. 권한 화면에 ‘권한 다시 확인’과 정상 재시작 버튼을 추가했고, 작은 창에서도 복구 안내와 버튼에 접근하도록 스크롤을 지원한다. 실제 사용자의 승인 이후 다음 단계로 넘어가는 최종 확인은 사용자 조작이 필요하다.

## 앱 검색의 중복 아이콘

실제 Mac에 과거 Handy 설치 DMG 3개가 동시에 마운트되어 있었고, 각 볼륨의 앱이 Launch Services에 등록되어 있었다. 세 설치 디스크를 정상 추출하고 해당 앱 등록을 해제했다. 휴지통에 옮겨진 예전 Handy의 등록도 해제했으며 휴지통의 파일은 삭제하지 않았다. 작업 폴더에 남은 배포·빌드용 앱 사본도 정리했다.

`.noindex`는 Spotlight 제외에 유용하지만 Launch Services 등록까지 없애지는 못했다. 배포 성공 후에는 생성된 앱 번들을 등록 해제하고 제거해 DMG만 배포한다. 설치된 Handy의 시작 시에는 전용 설치 표시 파일과 앱 번들 ID가 일치하는 읽기 전용 DMG만 정상 추출한다. 쓰기 가능한 이미지·시스템 이미지·다른 앱·표시가 없는 볼륨은 대상에서 제외하고 사용 중인 볼륨을 강제로 추출하지 않는다. [정리 명령과 한계](../BUILD.md#duplicate-handy-icons-in-macos-app-search)를 참고한다.

0.9.9를 `/Applications/Handy.app`에 설치해 실행한 뒤 설치 디스크가 자동 추출되는 것을 실제 로그와 마운트 상태로 확인했다. Spotlight 검색 결과와 Launch Services의 존재하는 앱 경로 모두 설치본 하나만 반환했다.

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

권한 재사용·시작 단계 선택·조회 실패 처리·입력 초기화의 중복 방지·조회 취소는 `bun run test:permissions`로 검사한다. 권한 화면의 브라우저 회귀 테스트 15개를 시스템 Chrome의 독립 프로필에서 실행해 통과했다. 기존 승인 재사용·중복 요청 방지·조회 취소·권한 다시 확인·정상 재시작·680×570 창에서 복구 버튼 접근을 포함하며, 모든 네이티브 명령은 모의 처리한다. 기본 브라우저 설치가 준비된 환경에서는 다음과 같이 실행한다.

```sh
bun run test:playwright tests/permissions.spec.ts
```

실제 권한 검증 시에는 미등록·기존 승인·기존 거부·앱 교체 뒤 오래된 항목·권한 철회·재시작을 각각 확인하고, 단축키 설정 중 포커스 이동과 빠른 시작/취소도 확인한다.

실제 다른 실행 파일 두 개를 같은 로컬 인증서로 서명해 지정 요구사항이 동일한지, 각 서명의 엄격 검증이 성공하는지, 사용자 키체인 검색 목록이 그대로인지 확인했다. 설치 디스크 선별 테스트 3개와 클립보드·키 해제 관련 Rust 테스트 20개도 통과했다. 이 검증은 실제 OS 권한 승인을 대신하지 않는다.

설치 이미지 검증에서는 별도의 포장 오류도 확인했다. `hdiutil makehybrid -hfs`로 만든 이미지가 파일마다 FinderInfo 속성을 추가해, 원본 앱의 서명은 정상이어도 마운트된 앱의 서명은 실패했다. 이미지를 `hdiutil create`의 압축 APFS 형식으로 바꾸고, 체크섬에 더해 실제 마운트된 앱과 `ditto`로 복사한 앱의 서명을 모두 엄격 검증한다. [Apple의 FinderInfo 관련 설명](https://developer.apple.com/library/archive/qa/qa1940/_index.html)을 참고한다.
