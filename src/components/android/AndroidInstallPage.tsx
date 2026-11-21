import React, { useEffect, useMemo, useState } from "react";
import {
  Check,
  Copy,
  Cpu,
  Download,
  ExternalLink,
  FileAudio,
  Keyboard,
  Link2,
  Mic,
  Moon,
  ShieldCheck,
  Smartphone,
  Sparkles,
  Sun,
  Terminal,
} from "lucide-react";
import "../../App.css";
import HandyHand from "../icons/HandyHand";
import HandyTextLogo from "../icons/HandyTextLogo";
import { QrCode } from "./QrCode";

type Lang = "ko" | "en";
type QrTarget = "apk" | "page";

interface AndroidApkMeta {
  version: string;
  versionCode?: number;
  packageId: string;
  fileName: string;
  downloadUrl: string;
  relativeDownloadUrl?: string;
  latestAliasUrl?: string;
  gradleOutputFile?: string;
  sizeBytes: number;
  sha256: string;
  minSdk: number;
  targetSdk: number;
  abis: string[];
  builtAt: string;
}

const FALLBACK_VERSION = "0.10.2-seionwon";
const FALLBACK_FILE_NAME = `Handy-v${FALLBACK_VERSION}.apk`;
const FALLBACK_RELATIVE_APK = `downloads/${FALLBACK_FILE_NAME}`;
const RELATIVE_META_PATH = "downloads/android-apk-meta.json";

const COPY = {
  ko: {
    pageTitle: (v: string) =>
      `Handy v${v} for Android — APK 다운로드 및 설치 안내`,
    badge: "Android 네이티브 앱",
    heroTitle: "안드로이드에서도 완전 오프라인 음성 받아쓰기",
    heroSubtitle:
      "Handy의 온디바이스 Whisper.cpp 엔진과 시스템 전체 음성 키보드(IME)를 안드로이드 폰에 바로 설치해 사용하세요.",
    statusReady: "APK 설치 파일 준비 완료",
    statusMissing: "APK가 아직 게시되지 않았습니다",
    statusChecking: "APK 상태를 확인하는 중…",
    downloadBtn: (v: string, size: string) =>
      size !== "—"
        ? `Handy v${v} APK 다운로드 (${size})`
        : `Handy v${v} APK 다운로드`,
    directLinkLabel: "APK 직접 다운로드 링크",
    directLinkHint:
      "휴대폰에서 위 링크를 누르거나 QR 코드를 스캔하면 버전이 포함된 APK 파일을 바로 내려받을 수 있습니다.",
    rebuildHint:
      "터미널에서 bun run build:android 를 실행하면 버전이 포함된 APK가 자동 생성됩니다.",
    copyLink: "링크 복사",
    copied: "복사됨",
    qrTitle: "휴대폰 카메라로 스캔해 설치",
    qrModeApk: "APK 바로 받기",
    qrModePage: "설치 안내 페이지",
    qrAria: "Handy Android 설치 QR 코드",
    localhostNoticeTitle: "현재 주소가 127.0.0.1 / localhost 입니다",
    localhostNoticeBody:
      "휴대폰에서 스캔하려면 CodeMemo가 연 모바일 접속 주소(LAN/터널 URL)로 열거나, 아래에 맥의 로컬 IP 주소(예: http://192.168.0.10:1420)를 입력해 QR 코드를 갱신하세요.",
    customOriginPlaceholder: "http://192.168.0.10:1420",
    customOriginLabel: "휴대폰 접속용 주소 (선택)",
    resetOrigin: "기본값",
    stepsHeading: "휴대폰 설치 및 음성 키보드 켜기",
    stepsSub:
      "처음 한 번만 설정하면 카카오톡·메모·브라우저 등 모든 앱에서 바로 받아쓸 수 있습니다.",
    steps: [
      {
        title: "1. APK 다운로드 및 설치",
        body: "휴대폰 브라우저에서 위 링크를 눌러 버전이 표시된 APK를 내려받은 뒤 실행하세요. 보안 안내가 뜨면 '이 출처의 앱 허용(알 수 없는 앱 설치)'을 켜고 설치합니다.",
      },
      {
        title: "2. 마이크 권한 및 음성 엔진 준비",
        body: "Handy 앱을 열고 마이크 권한을 허용하세요. 모델 없이도 'Android 즉시 음성 인식'으로 바로 시작할 수 있으며, '모델' 탭에서 Whisper Tiny/Base/Small을 받으면 완전 오프라인으로 동작합니다.",
      },
      {
        title: "3. Handy 음성 키보드(IME) 활성화",
        body: "안드로이드 설정 > 일반 > 키보드 목록 및 기본 키보드에서 'Handy 음성 키보드'를 켭니다. 앱의 받아쓰기/설정 탭에 바로가기 버튼이 있습니다.",
      },
      {
        title: "4. 어떤 앱에서나 음성 입력",
        body: "메시지나 메모 입력창에서 하단 키보드 전환 버튼(🌐 또는 ⌨️)으로 Handy 음성 키보드를 선택하고 마이크 버튼을 눌러 말하면 자동으로 입력됩니다.",
      },
    ],
    featuresHeading: "Android 버전에 포함된 기능",
    features: [
      {
        icon: "cpu",
        title: "온디바이스 Whisper.cpp",
        body: "NDK C++ 네이티브 라이브러리(libhandy_whisper.so)를 내장해 네트워크 없이 기기에서 직접 한국어·다국어 음성을 전사합니다.",
      },
      {
        icon: "keyboard",
        title: "시스템 전체 음성 키보드",
        body: "Android InputMethodService로 구현되어 카카오톡, Slack, 브라우저, 메모 등 모든 앱의 입력창에 바로 텍스트를 입력합니다.",
      },
      {
        icon: "audio",
        title: "실시간 인식 · 오디오 파일 전사",
        body: "다운로드 없이 즉시 동작하는 실시간 음성 인식과 더불어 녹음 파일(M4A/MP3/WAV)을 선택해 Whisper로 일괄 전사할 수 있습니다.",
      },
      {
        icon: "sparkles",
        title: "기록 · 사용자 단어 · AI 후처리",
        body: "로컬 SQLite 전사 기록, 녹음 WAV 재생/재전사, 필러 제거, 사용자 정의 단어, OpenAI·Anthropic·OpenRouter·Groq 후처리를 지원합니다.",
      },
    ],
    metaVersion: "버전",
    metaSize: "파일 크기",
    metaAndroid: "지원 버전",
    metaAbis: "네이티브 ABI",
    metaPackage: "패키지 ID",
    metaSha256: "SHA-256",
    adbHeading: "USB(ADB)로 바로 설치하기",
    adbCommand: (apkFile: string) => `adb install -r ${apkFile}`,
    desktopFootnote:
      "데스크톱 앱(macOS/Windows/Linux)은 Tauri로 실행되며, 일반 브라우저로 열었을 때만 이 Android 설치 허브가 표시됩니다.",
    langSwitch: "English",
    themeLight: "라이트 모드",
    themeDark: "다크 모드",
    minSdkLabel: (sdk: number) => `Android 8.0+ (API ${sdk}–36)`,
  },
  en: {
    pageTitle: (v: string) =>
      `Handy v${v} for Android — APK Download & Install Guide`,
    badge: "Native Android App",
    heroTitle: "Offline speech-to-text on your Android phone",
    heroSubtitle:
      "Install Handy's native Android app with on-device Whisper.cpp transcription and a system-wide Voice Keyboard (IME).",
    statusReady: "APK ready to install",
    statusMissing: "APK has not been published yet",
    statusChecking: "Checking APK availability…",
    downloadBtn: (v: string, size: string) =>
      size !== "—"
        ? `Download Handy v${v} APK (${size})`
        : `Download Handy v${v} APK`,
    directLinkLabel: "Direct APK Download Link",
    directLinkHint:
      "Tap the versioned APK link above on your phone or scan the QR code to download and install.",
    rebuildHint:
      "Run bun run build:android in the workspace to compile and publish the versioned APK.",
    copyLink: "Copy link",
    copied: "Copied",
    qrTitle: "Scan with your phone camera",
    qrModeApk: "Direct APK",
    qrModePage: "Install page",
    qrAria: "Handy Android install QR code",
    localhostNoticeTitle: "Opened on 127.0.0.1 / localhost",
    localhostNoticeBody:
      "Phones cannot reach your Mac's localhost directly. Open this page via CodeMemo's LAN/tunnel URL or enter your Mac's LAN address below (e.g. http://192.168.0.10:1420) to update the QR code.",
    customOriginPlaceholder: "http://192.168.0.10:1420",
    customOriginLabel: "Phone-reachable address (optional)",
    resetOrigin: "Reset",
    stepsHeading: "Install & Enable the Voice Keyboard",
    stepsSub:
      "Once enabled, you can dictate into any Android app directly from the keyboard.",
    steps: [
      {
        title: "1. Download and install the APK",
        body: "Tap the versioned APK link above on your Android phone, open the downloaded file, and allow 'Install unknown apps' for your browser when prompted.",
      },
      {
        title: "2. Grant microphone access & pick an engine",
        body: "Launch Handy and allow microphone access. Start immediately with Android Instant Speech, or download Whisper Tiny/Base/Small in the Models tab for 100% offline transcription.",
      },
      {
        title: "3. Enable Handy Voice Keyboard (IME)",
        body: "Open Android Settings > System > Keyboard > On-screen keyboard and toggle on 'Handy Voice Keyboard' (shortcuts are built right into Handy).",
      },
      {
        title: "4. Dictate anywhere",
        body: "In any text field, tap the keyboard switcher icon (🌐 / ⌨️), choose Handy Voice Keyboard, and press the mic button to speak.",
      },
    ],
    featuresHeading: "What's built into Handy Android",
    features: [
      {
        icon: "cpu",
        title: "On-device Whisper.cpp",
        body: "Bundled native NDK engine (libhandy_whisper.so) transcribes speech privately offline with zero cloud lock-in.",
      },
      {
        icon: "keyboard",
        title: "System-wide Voice Keyboard",
        body: "Native Android InputMethodService inserts dictated text straight into chat, notes, email, and browser inputs.",
      },
      {
        icon: "audio",
        title: "Instant Speech & File Transcription",
        body: "Use zero-download system speech recognition with live partials, or decode and transcribe M4A/MP3/WAV audio files offline.",
      },
      {
        icon: "sparkles",
        title: "History, Custom Words & LLM Post-Process",
        body: "Local SQLite history with WAV playback and retry, filler word cleanup, custom vocabulary, and OpenAI/Anthropic/OpenRouter/Groq post-processing.",
      },
    ],
    metaVersion: "Version",
    metaSize: "Size",
    metaAndroid: "Compatibility",
    metaAbis: "Native ABIs",
    metaPackage: "Package ID",
    metaSha256: "SHA-256",
    adbHeading: "Install via USB (ADB)",
    adbCommand: (apkFile: string) => `adb install -r ${apkFile}`,
    desktopFootnote:
      "The desktop app runs inside Tauri. Opening this server in a regular browser shows the Handy Android installer.",
    langSwitch: "한국어",
    themeLight: "Light",
    themeDark: "Dark",
    minSdkLabel: (sdk: number) => `Android 8.0+ (API ${sdk}–36)`,
  },
} as const;

function formatBytes(bytes: number): string {
  if (bytes <= 0) return "0 B";
  const mb = bytes / (1024 * 1024);
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

function isLoopbackHost(hostname: string): boolean {
  return (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "::1" ||
    hostname === "[::1]"
  );
}

function getCurrentBaseUrl(): URL {
  if (typeof window === "undefined") {
    return new URL("http://127.0.0.1:1420/");
  }
  const url = new URL(window.location.href);
  url.search = "";
  url.hash = "";
  if (!url.pathname.endsWith("/")) {
    url.pathname = url.pathname.replace(/\/[^/]*$/, "/");
  }
  return url;
}

function applyCustomOrigin(baseUrl: URL, customOrigin: string): URL {
  const trimmed = customOrigin.trim();
  if (!trimmed) return baseUrl;
  try {
    const withScheme = /^https?:\/\//i.test(trimmed)
      ? trimmed
      : `http://${trimmed}`;
    const parsed = new URL(withScheme);
    const merged = new URL(baseUrl.toString());
    merged.protocol = parsed.protocol;
    merged.host = parsed.host;
    return merged;
  } catch {
    return baseUrl;
  }
}

export const AndroidInstallPage: React.FC = () => {
  const [lang, setLang] = useState<Lang>(() =>
    typeof navigator !== "undefined" &&
    navigator.language.toLowerCase().startsWith("ko")
      ? "ko"
      : "en",
  );
  const [theme, setTheme] = useState<"light" | "dark">(() => {
    if (typeof document === "undefined") return "light";
    const attr = document.documentElement.dataset.theme;
    if (attr === "dark" || attr === "light") return attr;
    return window.matchMedia?.("(prefers-color-scheme: dark)").matches
      ? "dark"
      : "light";
  });
  const [meta, setMeta] = useState<AndroidApkMeta | null>(null);
  const [metaStatus, setMetaStatus] = useState<"loading" | "ready" | "missing">(
    "loading",
  );
  const [qrTarget, setQrTarget] = useState<QrTarget>("apk");
  const [customOrigin, setCustomOrigin] = useState<string>("");
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const copy = COPY[lang];
  const version = meta?.version ?? FALLBACK_VERSION;

  useEffect(() => {
    document.title = copy.pageTitle(version);
  }, [copy, version]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  useEffect(() => {
    let cancelled = false;
    const metaUrl = new URL(RELATIVE_META_PATH, getCurrentBaseUrl());
    metaUrl.searchParams.set("t", String(Date.now()));
    fetch(metaUrl.toString(), { cache: "no-store" })
      .then(async (res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = (await res.json()) as AndroidApkMeta;
        if (!cancelled) {
          setMeta(data);
          setMetaStatus("ready");
        }
      })
      .catch(() => {
        if (!cancelled) {
          setMeta(null);
          setMetaStatus("missing");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const currentBaseUrl = useMemo(() => getCurrentBaseUrl(), []);
  const onLoopback = isLoopbackHost(currentBaseUrl.hostname);

  const effectiveBaseUrl = useMemo(
    () => applyCustomOrigin(currentBaseUrl, customOrigin),
    [currentBaseUrl, customOrigin],
  );

  const apkFileName = meta?.fileName ?? FALLBACK_FILE_NAME;
  const relativeApkPath =
    meta?.relativeDownloadUrl ??
    (meta?.downloadUrl
      ? meta.downloadUrl.replace(/^\/+/, "")
      : FALLBACK_RELATIVE_APK);

  const localApkHref = useMemo(
    () => new URL(relativeApkPath, currentBaseUrl).toString(),
    [relativeApkPath, currentBaseUrl],
  );
  const effectiveApkUrl = useMemo(
    () => new URL(relativeApkPath, effectiveBaseUrl).toString(),
    [relativeApkPath, effectiveBaseUrl],
  );
  const effectivePageUrl = effectiveBaseUrl.toString();
  const qrUrl = qrTarget === "apk" ? effectiveApkUrl : effectivePageUrl;

  const copyText = async (key: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedKey(key);
      window.setTimeout(() => {
        setCopiedKey((curr) => (curr === key ? null : curr));
      }, 1800);
    } catch {
      // Clipboard API may be blocked on insecure non-localhost origins.
    }
  };

  const renderFeatureIcon = (kind: string) => {
    const cls = "h-5 w-5 text-background-ui shrink-0";
    switch (kind) {
      case "cpu":
        return <Cpu className={cls} />;
      case "keyboard":
        return <Keyboard className={cls} />;
      case "audio":
        return <FileAudio className={cls} />;
      default:
        return <Sparkles className={cls} />;
    }
  };

  const langButtonText = copy.langSwitch;
  const themeButtonLabel =
    theme === "dark" ? copy.themeLight : copy.themeDark;
  const statusBadgeText =
    metaStatus === "ready"
      ? copy.statusReady
      : metaStatus === "missing"
        ? copy.statusMissing
        : copy.statusChecking;
  const versionText = `v${version}`;
  const sizeText = meta ? formatBytes(meta.sizeBytes) : "—";
  const downloadBtnText = copy.downloadBtn(version, sizeText);
  const abisText = meta ? meta.abis.join(" · ") : "arm64-v8a · x86_64";
  const androidCompatText = copy.minSdkLabel(meta?.minSdk ?? 26);
  const shortShaText = meta ? `${meta.sha256.slice(0, 16)}…` : "—";
  const packageIdText = meta?.packageId ?? "com.pais.handy";
  const adbCmdText = copy.adbCommand(apkFileName);
  const buttonType = "button";
  const urlInputType = "url";
  const mainRole = "main";

  return (
    <div className="min-h-screen bg-background text-text font-ui selection:bg-logo-primary/35">
      <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6 sm:py-10">
        {/* Top bar */}
        <header className="flex flex-wrap items-center justify-between gap-4 pb-6 border-b border-line">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-logo-primary/25 border border-line">
              <HandyHand width={24} height={26} />
            </div>
            <div className="flex items-center gap-2.5">
              <HandyTextLogo width={88} height={31} />
              <span className="inline-flex items-center gap-1 rounded-full bg-background-ui/12 px-2.5 py-0.5 text-xs font-semibold text-background-ui">
                <Smartphone className="h-3.5 w-3.5" />
                {copy.badge}
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              type={buttonType}
              onClick={() => setLang(lang === "ko" ? "en" : "ko")}
              className="rounded-lg border border-line bg-wash px-3 py-1.5 text-xs font-medium text-text hover:border-line-strong transition-colors cursor-pointer"
            >
              {langButtonText}
            </button>
            <button
              type={buttonType}
              aria-label={themeButtonLabel}
              onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
              className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-wash px-3 py-1.5 text-xs font-medium text-text hover:border-line-strong transition-colors cursor-pointer"
            >
              {theme === "dark" ? (
                <Sun className="h-3.5 w-3.5" />
              ) : (
                <Moon className="h-3.5 w-3.5" />
              )}
              <span>{themeButtonLabel}</span>
            </button>
          </div>
        </header>

        <main role={mainRole} className="mt-7 space-y-8">
          {/* Hero section */}
          <section className="grid grid-cols-1 gap-6 lg:grid-cols-12 rounded-2xl border border-line bg-wash/40 p-5 sm:p-7">
            <div className="lg:col-span-7 flex flex-col justify-center gap-5">
              <div className="space-y-3.5">
                <div className="inline-flex flex-wrap items-center gap-2">
                  <span
                    className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold ${
                      metaStatus === "ready"
                        ? "bg-moss-wash text-moss"
                        : metaStatus === "missing"
                          ? "bg-warning-wash text-warning"
                          : "bg-wash text-ink-2"
                    }`}
                  >
                    <ShieldCheck className="h-3.5 w-3.5" />
                    {statusBadgeText}
                  </span>
                  <span className="rounded-full border border-line px-2.5 py-1 text-xs font-semibold text-text">
                    {versionText}
                  </span>
                  <span className="rounded-full border border-line px-2.5 py-1 text-xs font-medium text-ink-2">
                    {sizeText}
                  </span>
                </div>

                <h1 className="font-display text-2xl sm:text-3xl font-bold tracking-tight text-text leading-tight break-keep">
                  {copy.heroTitle}
                </h1>
                <p className="text-sm sm:text-base text-ink-2 leading-relaxed break-keep">
                  {copy.heroSubtitle}
                </p>
              </div>

              <div className="space-y-4">
                <div className="flex flex-wrap items-center gap-3">
                  <a
                    href={localApkHref}
                    download={apkFileName}
                    className={`inline-flex items-center justify-center gap-2 rounded-xl px-5 py-3 text-sm font-semibold text-white shadow-sm transition-opacity ${
                      metaStatus === "missing"
                        ? "bg-ink-3 pointer-events-none opacity-60"
                        : "bg-background-ui hover:opacity-90"
                    }`}
                  >
                    <Download className="h-4 w-4" />
                    <span>{downloadBtnText}</span>
                  </a>

                  <button
                    type={buttonType}
                    onClick={() => copyText("apk-link", effectiveApkUrl)}
                    className="inline-flex items-center gap-1.5 rounded-xl border border-line bg-background px-4 py-3 text-sm font-medium text-text hover:border-line-strong transition-colors cursor-pointer"
                  >
                    {copiedKey === "apk-link" ? (
                      <Check className="h-4 w-4 text-moss" />
                    ) : (
                      <Copy className="h-4 w-4 text-ink-2" />
                    )}
                    <span>
                      {copiedKey === "apk-link" ? copy.copied : copy.copyLink}
                    </span>
                  </button>
                </div>

                {/* Prominent versioned direct APK link */}
                <div className="rounded-xl border border-line bg-background p-3.5 space-y-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-text">
                      <Link2 className="h-3.5 w-3.5 text-background-ui" />
                      <span>{copy.directLinkLabel}</span>
                    </span>
                    <span className="rounded-md bg-background-ui/12 px-2 py-0.5 font-mono text-[11px] font-semibold text-background-ui">
                      {apkFileName}
                    </span>
                  </div>

                  <a
                    href={localApkHref}
                    download={apkFileName}
                    className={`group flex items-start justify-between gap-2 rounded-lg border border-line bg-wash/60 px-3 py-2 font-mono text-xs text-background-ui hover:border-background-ui/50 transition-colors ${
                      metaStatus === "missing"
                        ? "pointer-events-none opacity-60"
                        : ""
                    }`}
                  >
                    <span className="break-all underline decoration-background-ui/40 group-hover:decoration-background-ui">
                      {effectiveApkUrl}
                    </span>
                    <ExternalLink className="h-3.5 w-3.5 shrink-0 mt-0.5 opacity-75 group-hover:opacity-100" />
                  </a>

                  <p className="text-[11px] text-ink-2 leading-relaxed break-keep">
                    {copy.directLinkHint}
                  </p>
                </div>

                {metaStatus === "missing" && (
                  <p className="rounded-xl bg-warning-wash px-3.5 py-2.5 text-xs text-warning">
                    {copy.rebuildHint}
                  </p>
                )}

                {/* Metadata strip */}
                <dl className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 pt-1">
                  <div className="rounded-xl border border-line bg-background/80 px-3 py-2">
                    <dt className="text-[11px] text-ink-3">{copy.metaVersion}</dt>
                    <dd className="text-xs font-semibold text-text mt-0.5">
                      {versionText}
                    </dd>
                  </div>
                  <div className="rounded-xl border border-line bg-background/80 px-3 py-2">
                    <dt className="text-[11px] text-ink-3">{copy.metaSize}</dt>
                    <dd className="text-xs font-semibold text-text mt-0.5">
                      {sizeText}
                    </dd>
                  </div>
                  <div className="rounded-xl border border-line bg-background/80 px-3 py-2">
                    <dt className="text-[11px] text-ink-3">
                      {copy.metaAndroid}
                    </dt>
                    <dd className="text-xs font-semibold text-text mt-0.5">
                      {androidCompatText}
                    </dd>
                  </div>
                  <div className="rounded-xl border border-line bg-background/80 px-3 py-2">
                    <dt className="text-[11px] text-ink-3">{copy.metaAbis}</dt>
                    <dd className="text-xs font-semibold text-text mt-0.5">
                      {abisText}
                    </dd>
                  </div>
                </dl>
              </div>
            </div>

            {/* QR column */}
            <div className="lg:col-span-5 flex flex-col items-center justify-between gap-4 rounded-2xl border border-line bg-background p-5">
              <div className="w-full flex flex-col items-center gap-2.5 text-center">
                <span className="text-xs font-semibold text-text">
                  {copy.qrTitle}
                </span>
                <div className="inline-flex rounded-lg border border-line bg-wash p-0.5 text-[11px]">
                  <button
                    type={buttonType}
                    onClick={() => setQrTarget("apk")}
                    className={`whitespace-nowrap shrink-0 rounded-md px-2.5 py-1 font-medium transition-colors cursor-pointer ${
                      qrTarget === "apk"
                        ? "bg-background-ui text-white"
                        : "text-ink-2 hover:text-text"
                    }`}
                  >
                    {copy.qrModeApk}
                  </button>
                  <button
                    type={buttonType}
                    onClick={() => setQrTarget("page")}
                    className={`whitespace-nowrap shrink-0 rounded-md px-2.5 py-1 font-medium transition-colors cursor-pointer ${
                      qrTarget === "page"
                        ? "bg-background-ui text-white"
                        : "text-ink-2 hover:text-text"
                    }`}
                  >
                    {copy.qrModePage}
                  </button>
                </div>
              </div>

              <div className="flex flex-col items-center gap-2 py-1">
                <QrCode value={qrUrl} size={184} label={copy.qrAria} />
                {qrTarget === "apk" && (
                  <span className="rounded-full border border-line bg-wash px-2.5 py-0.5 font-mono text-[11px] font-medium text-ink-2">
                    {apkFileName}
                  </span>
                )}
              </div>

              <div className="w-full space-y-2.5">
                <div className="flex items-center gap-2 rounded-lg border border-line bg-wash/70 px-2.5 py-1.5">
                  <a
                    href={qrTarget === "apk" ? localApkHref : effectivePageUrl}
                    download={qrTarget === "apk" ? apkFileName : undefined}
                    className="flex-1 truncate font-mono text-[11px] text-ink-2 hover:text-background-ui hover:underline"
                  >
                    {qrUrl}
                  </a>
                  <button
                    type={buttonType}
                    onClick={() => copyText("qr-url", qrUrl)}
                    className="shrink-0 text-xs font-medium text-background-ui hover:underline cursor-pointer"
                  >
                    {copiedKey === "qr-url" ? copy.copied : copy.copyLink}
                  </button>
                </div>

                {onLoopback && (
                  <div className="rounded-xl bg-warning-wash/75 border border-warning/25 p-3 space-y-2">
                    <p className="text-[11px] font-semibold text-warning">
                      {copy.localhostNoticeTitle}
                    </p>
                    <p className="text-[11px] text-ink-2 leading-relaxed break-keep">
                      {copy.localhostNoticeBody}
                    </p>
                    <div className="flex items-center gap-1.5">
                      <input
                        type={urlInputType}
                        value={customOrigin}
                        onChange={(e) => setCustomOrigin(e.target.value)}
                        aria-label={copy.customOriginLabel}
                        placeholder={copy.customOriginPlaceholder}
                        className="flex-1 min-w-0 rounded-lg border border-line bg-background px-2.5 py-1.5 text-xs text-text focus:outline-none focus:border-background-ui"
                      />
                      {customOrigin && (
                        <button
                          type={buttonType}
                          onClick={() => setCustomOrigin("")}
                          className="rounded-lg border border-line bg-background px-2.5 py-1.5 text-xs text-ink-2 hover:text-text cursor-pointer"
                        >
                          {copy.resetOrigin}
                        </button>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </div>
          </section>

          {/* 4-step guide */}
          <section className="space-y-4">
            <div>
              <h2 className="font-display text-lg sm:text-xl font-bold text-text">
                {copy.stepsHeading}
              </h2>
              <p className="text-sm text-ink-2 mt-0.5">{copy.stepsSub}</p>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
              {copy.steps.map((step) => (
                <article
                  key={step.title}
                  className="rounded-2xl border border-line bg-background p-4 sm:p-5 space-y-1.5"
                >
                  <h3 className="text-sm font-semibold text-text">
                    {step.title}
                  </h3>
                  <p className="text-xs sm:text-sm text-ink-2 leading-relaxed">
                    {step.body}
                  </p>
                </article>
              ))}
            </div>
          </section>

          {/* Feature grid */}
          <section className="space-y-4">
            <h2 className="font-display text-lg sm:text-xl font-bold text-text">
              {copy.featuresHeading}
            </h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
              {copy.features.map((feat) => (
                <article
                  key={feat.title}
                  className="flex items-start gap-3.5 rounded-2xl border border-line bg-wash/35 p-4 sm:p-5"
                >
                  <div className="rounded-xl bg-background-ui/12 p-2.5">
                    {renderFeatureIcon(feat.icon)}
                  </div>
                  <div className="space-y-1">
                    <h3 className="text-sm font-semibold text-text">
                      {feat.title}
                    </h3>
                    <p className="text-xs sm:text-sm text-ink-2 leading-relaxed">
                      {feat.body}
                    </p>
                  </div>
                </article>
              ))}
            </div>
          </section>

          {/* Verification & ADB */}
          <section className="grid grid-cols-1 md:grid-cols-2 gap-4 rounded-2xl border border-line bg-wash/30 p-5">
            <div className="space-y-2">
              <div className="flex items-center gap-2 text-xs font-semibold text-text">
                <Terminal className="h-4 w-4 text-background-ui" />
                <span>{copy.adbHeading}</span>
              </div>
              <div className="flex items-center gap-2 rounded-xl border border-line bg-background px-3 py-2">
                <code className="flex-1 overflow-x-auto text-xs text-ink-2 whitespace-nowrap">
                  {adbCmdText}
                </code>
                <button
                  type={buttonType}
                  onClick={() => copyText("adb", adbCmdText)}
                  className="shrink-0 text-xs font-medium text-background-ui hover:underline cursor-pointer"
                >
                  {copiedKey === "adb" ? copy.copied : copy.copyLink}
                </button>
              </div>
            </div>

            <div className="space-y-2">
              <div className="flex items-center gap-2 text-xs font-semibold text-text">
                <Mic className="h-4 w-4 text-background-ui" />
                <span>
                  {copy.metaPackage} · {packageIdText}
                </span>
              </div>
              <div className="flex items-center gap-2 rounded-xl border border-line bg-background px-3 py-2">
                <code className="flex-1 truncate text-xs text-ink-2">
                  {copy.metaSha256}: {shortShaText}
                </code>
                {meta && (
                  <button
                    type={buttonType}
                    onClick={() => copyText("sha", meta.sha256)}
                    className="shrink-0 text-xs font-medium text-background-ui hover:underline cursor-pointer"
                  >
                    {copiedKey === "sha" ? copy.copied : copy.copyLink}
                  </button>
                )}
              </div>
            </div>
          </section>
        </main>

        <footer className="mt-8 pt-5 border-t border-line text-center text-xs text-ink-3">
          {copy.desktopFootnote}
        </footer>
      </div>
    </div>
  );
};

export default AndroidInstallPage;
