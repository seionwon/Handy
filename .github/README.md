# Handy · seionwon 포크

데스크톱에서 단축키로 받아쓰는 Handy에 **Mac 간 화면 공유 받아쓰기**와 **Android 앱·음성 키보드**를 더했습니다.

Dictate into a remote Mac through Apple Screen Sharing, transcribe audio offline on Android, or use Handy as an Android voice keyboard.

## 원본 Handy와 달라진 점 / What changed in this fork

- **원격 Mac에 받아쓰기 · Mac-to-Mac Screen Sharing** — 말하는 쪽 Mac에만 Handy를 설치합니다. Apple 화면 공유에서 원격 Mac의 입력칸에 커서를 두고 단축키로 받아쓰면 그 자리에 글이 붙습니다. **공유 클립보드 사용**을 켜 둔 채 쓸 수 있습니다.
- **Android에서 오프라인 전사 · Offline Whisper** — 휴대폰에 Whisper 모델을 한 번 내려받으면 마이크로 받아쓰거나 저장해 둔 음성 파일을 기기에서 텍스트로 바꿀 수 있습니다.
- **다른 Android 앱에 입력 · Voice keyboard** — 평소 쓰는 키보드를 유지하려면 Handy 앱에서 받아쓴 글을 원하는 입력칸에 붙여넣으세요. **Handy 음성 키보드**를 선택하면 그 입력칸에서 바로 받아쓸 수 있습니다.

## 설치 파일 / Downloads

<!-- installer-downloads:start -->

| 기기 / Device | 버전 / Version | 다운로드 / Download |
| --- | --- | --- |
| Android 8.0+ · ARM64 / x86_64 | 0.10.3-seionwon | **[Android APK](https://github.com/seionwon/Handy/releases/download/installers-0.10.3-seionwon/Handy-v0.10.3-seionwon.apk)** |
| macOS · Apple Silicon (M 시리즈) | 0.10.2-seionwon | **[macOS PKG](https://github.com/seionwon/Handy/releases/download/installers-0.10.3-seionwon/Handy-ScreenSharing-0.10.2-seionwon-arm64.pkg)** |
| Windows x64 · 일반 설치 | 0.10.2-seionwon | **[Windows EXE](https://github.com/seionwon/Handy/releases/download/installers-0.10.3-seionwon/Handy_0.10.2-seionwon_x64-setup.exe)** |
| Windows x64 · MSI 설치가 필요한 경우 | 0.10.2-seionwon | **[Windows MSI](https://github.com/seionwon/Handy/releases/download/installers-0.10.3-seionwon/Handy_0.10.2-seionwon_x64_en-US.msi)** |

<!-- installer-downloads:end -->

Mac용 PKG로 업데이트하면 기존 설정·모델·전사 기록이 유지됩니다. Windows x64 설치 파일에는 필요한 Visual C++ 런타임이 포함돼 있습니다.

[전체 릴리스 / All releases](https://github.com/seionwon/Handy/releases) · [한국어 사용 안내](https://github.com/seionwon/Handy/blob/HEAD/README.ko.md) · [English guide](https://github.com/seionwon/Handy/blob/HEAD/README.md) · [원본 Handy / Upstream](https://github.com/cjpais/Handy)
