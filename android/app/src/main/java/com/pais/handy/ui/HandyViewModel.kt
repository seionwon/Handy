package com.pais.handy.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.PersistableBundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pais.handy.audio.AudioCapture
import com.pais.handy.audio.AudioFileDecoder
import com.pais.handy.audio.WavCodec
import com.pais.handy.data.EngineMode
import com.pais.handy.data.HandyLanguages
import com.pais.handy.data.HandySettingsRepository
import com.pais.handy.data.HandySettingsState
import com.pais.handy.data.HistoryDatabase
import com.pais.handy.data.HistoryEntry
import com.pais.handy.data.LlmProviders
import com.pais.handy.data.ModelCatalog
import com.pais.handy.data.ModelDownloadProgress
import com.pais.handy.data.ModelDownloader
import com.pais.handy.data.WhisperModelInfo
import com.pais.handy.stt.LlmClient
import com.pais.handy.stt.SystemSpeechEngine
import com.pais.handy.stt.TextPostProcessor
import com.pais.handy.stt.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DictationPhase {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    POST_PROCESSING,
}

data class HandyUiState(
    val phase: DictationPhase = DictationPhase.IDLE,
    val liveLevel: Float = 0f,
    val elapsedMs: Long = 0L,
    val partialText: String = "",
    val latestRawText: String = "",
    val latestPostProcessedText: String? = null,
    val latestEngineLabel: String = "",
    val errorMessage: String? = null,
    val history: List<HistoryEntry> = emptyList(),
    val playingEntryId: Long? = null,
    val transcribingFileLabel: String? = null,
    val imeEnabled: Boolean = false,
    val imeSelected: Boolean = false,
)

class HandyViewModel(application: Application) : AndroidViewModel(application) {
    val settingsRepo = HandySettingsRepository(application)
    val modelDownloader = ModelDownloader.get(application)
    private val historyDb = HistoryDatabase(application)
    private val audioCapture = AudioCapture()
    private val systemSpeech = SystemSpeechEngine(application)

    val settings: StateFlow<HandySettingsState> = settingsRepo.state
    val downloads: StateFlow<Map<String, ModelDownloadProgress>> = modelDownloader.downloads
    val installedModelIds: StateFlow<Set<String>> = modelDownloader.installedIds

    private val _ui = MutableStateFlow(HandyUiState())
    val ui: StateFlow<HandyUiState> = _ui.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private var activeRecordingMode: EngineMode? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        refreshHistory()
        viewModelScope.launch {
            audioCapture.level.collect { lvl ->
                if (activeRecordingMode == EngineMode.WHISPER_OFFLINE) {
                    _ui.update { it.copy(liveLevel = lvl) }
                }
            }
        }
        viewModelScope.launch {
            audioCapture.elapsedMs.collect { ms ->
                if (activeRecordingMode == EngineMode.WHISPER_OFFLINE) {
                    _ui.update { it.copy(elapsedMs = ms) }
                }
            }
        }
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = historyDb.listAll()
            _ui.update { it.copy(history = entries) }
        }
    }

    fun refreshImeStatus() {
        val ctx = getApplication<Application>()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val pkg = ctx.packageName
        val enabled = imm.enabledInputMethodList.any { it.packageName == pkg }
        val defaultIme = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = defaultIme.startsWith("$pkg/")
        _ui.update { it.copy(imeEnabled = enabled, imeSelected = selected) }
    }

    fun openImeSettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun showImePicker() {
        val ctx = getApplication<Application>()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    fun startDictation() {
        if (_ui.value.phase != DictationPhase.IDLE) return
        val s = settingsRepo.current()
        _ui.update { it.copy(errorMessage = null, partialText = "") }

        if (s.engineMode == EngineMode.WHISPER_OFFLINE) {
            val modelFile = modelDownloader.installedModelFile(s.selectedModelId)
            if (modelFile == null) {
                _ui.update {
                    it.copy(
                        errorMessage = localized(
                            "먼저 '모델' 탭에서 Whisper 오프라인 모델을 다운로드하거나, 상단에서 '즉시 인식'으로 전환하세요.",
                            "Download a Whisper model in the Models tab first, or switch to Instant Speech.",
                        ),
                    )
                }
                return
            }
            audioCapture.start(viewModelScope)
                .onSuccess {
                    activeRecordingMode = EngineMode.WHISPER_OFFLINE
                    _ui.update { it.copy(phase = DictationPhase.RECORDING, elapsedMs = 0L) }
                }
                .onFailure { e ->
                    _ui.update { it.copy(errorMessage = e.message ?: "Failed to start microphone") }
                }
        } else {
            activeRecordingMode = EngineMode.SYSTEM_INSTANT
            _ui.update { it.copy(phase = DictationPhase.RECORDING, elapsedMs = 0L, liveLevel = 0.1f) }
            val lang = HandyLanguages.byCode(s.selectedLanguage)
            systemSpeech.start(
                bcp47Language = lang.bcp47,
                onRmsLevel = { lvl -> _ui.update { it.copy(liveLevel = lvl) } },
                onPartial = { partial -> _ui.update { it.copy(partialText = partial) } },
                onResult = { rawText ->
                    activeRecordingMode = null
                    finalizeDictation(rawText, null, localized("즉시 음성 인식", "Instant Speech"))
                },
                onError = { err ->
                    activeRecordingMode = null
                    _ui.update {
                        it.copy(
                            phase = DictationPhase.IDLE,
                            liveLevel = 0f,
                            errorMessage = err,
                        )
                    }
                },
            )
        }
    }

    fun stopDictation() {
        val mode = activeRecordingMode ?: return
        activeRecordingMode = null
        if (mode == EngineMode.SYSTEM_INSTANT) {
            systemSpeech.stopListening()
            _ui.update { it.copy(phase = DictationPhase.TRANSCRIBING, liveLevel = 0f) }
            return
        }

        val s = settingsRepo.current()
        val model = ModelCatalog.byId(s.selectedModelId)
        val modelFile = modelDownloader.installedModelFile(s.selectedModelId)
        _ui.update { it.copy(phase = DictationPhase.TRANSCRIBING, liveLevel = 0f) }

        viewModelScope.launch {
            try {
                val samples = audioCapture.stopAndGetSamples()
                if (samples.size < 3200 || modelFile == null) {
                    _ui.update {
                        it.copy(
                            phase = DictationPhase.IDLE,
                            errorMessage = localized("녹음이 너무 짧습니다.", "Recording was too short."),
                        )
                    }
                    return@launch
                }
                val wavName = "handy-${System.currentTimeMillis()}.wav"
                val wavFile = File(historyDb.recordingsDir, wavName)
                withContext(Dispatchers.IO) {
                    WavCodec.writeWavFile(wavFile, samples)
                }
                val raw = WhisperEngine.transcribe(
                    modelFile = modelFile,
                    samples = samples,
                    language = s.selectedLanguage,
                    translateToEnglish = s.translateToEnglish,
                    customWords = s.customWords,
                )
                finalizeDictation(raw, wavName, model?.name ?: "Whisper")
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        phase = DictationPhase.IDLE,
                        errorMessage = e.message ?: "Transcription failed",
                    )
                }
            }
        }
    }

    fun cancelDictation() {
        val mode = activeRecordingMode
        activeRecordingMode = null
        if (mode == EngineMode.SYSTEM_INSTANT) {
            systemSpeech.cancel()
        } else if (mode == EngineMode.WHISPER_OFFLINE) {
            viewModelScope.launch { audioCapture.cancel() }
        }
        _ui.update {
            it.copy(
                phase = DictationPhase.IDLE,
                liveLevel = 0f,
                elapsedMs = 0L,
                partialText = "",
            )
        }
    }

    private fun finalizeDictation(rawText: String, audioFileName: String?, engineLabel: String) {
        val s = settingsRepo.current()
        val cleaned = TextPostProcessor.finalizeTranscript(
            text = rawText,
            removeFillerWords = s.removeFillerWords,
            appendTrailingSpace = false,
        )
        if (cleaned.isEmpty()) {
            _ui.update {
                it.copy(
                    phase = DictationPhase.IDLE,
                    errorMessage = localized("인식된 음성이 없습니다.", "No speech recognized."),
                )
            }
            return
        }

        viewModelScope.launch {
            var postProcessed: String? = null
            var llmError: String? = null
            if (s.postProcessEnabled && s.postProcessApiKey.isNotBlank()) {
                _ui.update { it.copy(phase = DictationPhase.POST_PROCESSING) }
                val res = LlmClient.postProcess(s, cleaned)
                postProcessed = res.getOrNull()
                if (res.isFailure) {
                    llmError = localized(
                        "AI 후처리 실패 (원본 텍스트 유지): ${res.exceptionOrNull()?.message}",
                        "Post-processing failed (kept raw transcript): ${res.exceptionOrNull()?.message}",
                    )
                }
            }

            val finalCopy = if (s.appendTrailingSpace) "${postProcessed ?: cleaned} " else (postProcessed ?: cleaned)
            if (s.autoCopyToClipboard) {
                copyTextToClipboard(finalCopy, showToast = false)
            }

            val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            withContext(Dispatchers.IO) {
                historyDb.insert(
                    title = title,
                    audioFileName = audioFileName,
                    sourceName = null,
                    language = s.selectedLanguage,
                    engineLabel = engineLabel,
                    transcriptionText = cleaned,
                    postProcessedText = postProcessed,
                )
            }
            refreshHistory()
            _ui.update {
                it.copy(
                    phase = DictationPhase.IDLE,
                    partialText = "",
                    latestRawText = cleaned,
                    latestPostProcessedText = postProcessed,
                    latestEngineLabel = engineLabel,
                    errorMessage = llmError,
                )
            }
            if (s.autoCopyToClipboard) {
                _toast.tryEmit(localized("클립보드에 복사되었습니다.", "Copied to clipboard"))
            }
        }
    }

    fun rerunPostProcessOnLatest() {
        val raw = _ui.value.latestRawText
        if (raw.isBlank()) return
        val s = settingsRepo.current()
        if (s.postProcessApiKey.isBlank()) {
            _toast.tryEmit(localized("설정에서 LLM API 키를 먼저 입력하세요.", "Enter an LLM API key in Settings first."))
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(phase = DictationPhase.POST_PROCESSING, errorMessage = null) }
            LlmClient.postProcess(s, raw)
                .onSuccess { polished ->
                    _ui.update {
                        it.copy(
                            phase = DictationPhase.IDLE,
                            latestPostProcessedText = polished,
                        )
                    }
                    if (s.autoCopyToClipboard) copyTextToClipboard(polished, showToast = false)
                    _toast.tryEmit(localized("AI 다듬기 완료", "Post-processing complete"))
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(
                            phase = DictationPhase.IDLE,
                            errorMessage = e.message ?: "Post-processing failed",
                        )
                    }
                }
        }
    }

    fun clearLatest() {
        _ui.update {
            it.copy(
                partialText = "",
                latestRawText = "",
                latestPostProcessedText = null,
                errorMessage = null,
            )
        }
    }

    fun transcribeAudioUri(uri: Uri) {
        viewModelScope.launch {
            modelDownloader.awaitInitialScan()
            val s = settingsRepo.current()
            val targetModelId = if (modelDownloader.isInstalled(s.selectedModelId)) {
                s.selectedModelId
            } else {
                modelDownloader.installedIds.value.firstOrNull()
            }
            val model = targetModelId?.let { ModelCatalog.byId(it) }
            val modelFile = targetModelId?.let { modelDownloader.installedModelFile(it) }
            if (modelFile == null || model == null) {
                _toast.tryEmit(
                    localized(
                        "파일 전사에는 오프라인 Whisper 모델이 필요합니다. '모델' 탭에서 다운로드하세요.",
                        "File transcription requires a downloaded Whisper model in the Models tab.",
                    ),
                )
                return@launch
            }

            // The URI comes from whichever app shared it. Only content URIs are
            // files that app was allowed to hand over.
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                _toast.tryEmit(localized("이 파일은 열 수 없습니다.", "This file can't be opened."))
                return@launch
            }

            try {
                val displayName = resolveDisplayName(uri)
                _ui.update { it.copy(transcribingFileLabel = displayName, errorMessage = null) }
                val samples = withContext(Dispatchers.IO) {
                    AudioFileDecoder.decodeUriToMono16k(getApplication(), uri)
                }
                val wavName = "handy-file-${System.currentTimeMillis()}.wav"
                val wavFile = File(historyDb.recordingsDir, wavName)
                withContext(Dispatchers.IO) {
                    WavCodec.writeWavFile(wavFile, samples)
                }
                val raw = WhisperEngine.transcribe(
                    modelFile = modelFile,
                    samples = samples,
                    language = s.selectedLanguage,
                    translateToEnglish = s.translateToEnglish,
                    customWords = s.customWords,
                )
                val cleaned = TextPostProcessor.finalizeTranscript(
                    text = raw,
                    removeFillerWords = s.removeFillerWords,
                    appendTrailingSpace = false,
                )
                if (cleaned.isEmpty()) {
                    _toast.tryEmit(localized("파일에서 음성을 찾지 못했습니다.", "No speech found in file."))
                } else {
                    val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                    withContext(Dispatchers.IO) {
                        historyDb.insert(
                            title = title,
                            audioFileName = wavName,
                            sourceName = displayName,
                            language = s.selectedLanguage,
                            engineLabel = model.name,
                            transcriptionText = cleaned,
                            postProcessedText = null,
                        )
                    }
                    refreshHistory()
                    _toast.tryEmit(localized("파일 전사가 기록에 저장되었습니다.", "File transcription saved to History."))
                }
            } catch (e: AudioFileDecoder.AudioTooLongException) {
                _toast.tryEmit(localized("파일이 너무 길어 전사할 수 없습니다.", "This file is too long to transcribe."))
            } catch (e: OutOfMemoryError) {
                _toast.tryEmit(localized("파일이 너무 길어 전사할 수 없습니다.", "This file is too long to transcribe."))
            } catch (e: Exception) {
                _toast.tryEmit(
                    localized(
                        "파일 전사 실패: ${e.message}",
                        "File transcription failed: ${e.message}",
                    ),
                )
            } finally {
                _ui.update { it.copy(transcribingFileLabel = null) }
            }
        }
    }

    fun retryHistoryEntry(entry: HistoryEntry) {
        val wavFile = historyDb.resolveAudioFile(entry.audioFileName)
        if (wavFile == null || !wavFile.isFile) {
            _toast.tryEmit(localized("저장된 녹음 파일이 없어 재전사할 수 없습니다.", "No saved audio recording for this entry."))
            return
        }
        val s = settingsRepo.current()
        val targetModelId = if (modelDownloader.isInstalled(s.selectedModelId)) {
            s.selectedModelId
        } else {
            modelDownloader.installedIds.value.firstOrNull()
        }
        val model = targetModelId?.let { ModelCatalog.byId(it) }
        val modelFile = targetModelId?.let { modelDownloader.installedModelFile(it) }
        if (modelFile == null || model == null) {
            _toast.tryEmit(localized("재전사할 Whisper 모델을 먼저 다운로드하세요.", "Download a Whisper model first to retry."))
            return
        }

        _ui.update { it.copy(transcribingFileLabel = entry.sourceName ?: entry.title) }
        viewModelScope.launch {
            try {
                val samples = withContext(Dispatchers.IO) {
                    WavCodec.readWavFileToMono16k(wavFile)
                }
                val raw = WhisperEngine.transcribe(
                    modelFile = modelFile,
                    samples = samples,
                    language = s.selectedLanguage,
                    translateToEnglish = s.translateToEnglish,
                    customWords = s.customWords,
                )
                val cleaned = TextPostProcessor.finalizeTranscript(
                    text = raw,
                    removeFillerWords = s.removeFillerWords,
                    appendTrailingSpace = false,
                )
                if (cleaned.isNotEmpty()) {
                    var postProcessed: String? = null
                    if (s.postProcessEnabled && s.postProcessApiKey.isNotBlank()) {
                        postProcessed = LlmClient.postProcess(s, cleaned).getOrNull()
                    }
                    withContext(Dispatchers.IO) {
                        historyDb.updateTranscription(entry.id, cleaned, postProcessed, model.name)
                    }
                    refreshHistory()
                    _toast.tryEmit(localized("다시 전사 완료", "Retranscription complete"))
                } else {
                    _toast.tryEmit(localized("음성을 인식하지 못했습니다.", "No speech recognized."))
                }
            } catch (e: Exception) {
                _toast.tryEmit("Retry failed: ${e.message}")
            } finally {
                _ui.update { it.copy(transcribingFileLabel = null) }
            }
        }
    }

    fun toggleSaved(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDb.toggleSaved(entry.id)
            refreshHistory()
        }
    }

    fun deleteEntry(entry: HistoryEntry) {
        if (_ui.value.playingEntryId == entry.id) stopPlayback()
        viewModelScope.launch(Dispatchers.IO) {
            historyDb.deleteEntry(entry)
            refreshHistory()
        }
    }

    fun togglePlayback(entry: HistoryEntry) {
        if (_ui.value.playingEntryId == entry.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        val wavFile = historyDb.resolveAudioFile(entry.audioFileName) ?: return
        if (!wavFile.isFile) return
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(wavFile.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            mediaPlayer = mp
            _ui.update { it.copy(playingEntryId = entry.id) }
        }.onFailure {
            _toast.tryEmit(localized("오디오 재생 실패", "Audio playback failed"))
        }
    }

    fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        _ui.update { it.copy(playingEntryId = null) }
    }

    fun copyTextToClipboard(text: String, showToast: Boolean = true) {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Handy Transcript", text)
        // Dictation can hold private text: keep it out of the system's clipboard preview.
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        if (showToast) {
            _toast.tryEmit(localized("복사되었습니다.", "Copied"))
        }
    }

    fun shareText(text: String) {
        val ctx = getApplication<Application>()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, localized("텍스트 공유", "Share transcript")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }

    fun downloadModel(model: WhisperModelInfo) {
        modelDownloader.startDownload(model) {
            settingsRepo.update {
                it.copy(
                    selectedModelId = model.id,
                    engineMode = EngineMode.WHISPER_OFFLINE,
                )
            }
            _toast.tryEmit(
                localized(
                    "${model.name} 다운로드 완료 — 오프라인 모드로 전환되었습니다.",
                    "${model.name} downloaded — switched to Offline Whisper.",
                ),
            )
        }
    }

    fun cancelDownload(modelId: String) {
        modelDownloader.cancelDownload(modelId)
    }

    fun selectWhisperModel(modelId: String) {
        if (!modelDownloader.isInstalled(modelId)) return
        settingsRepo.update {
            it.copy(
                selectedModelId = modelId,
                engineMode = EngineMode.WHISPER_OFFLINE,
            )
        }
    }

    fun selectInstantEngine() {
        settingsRepo.update { it.copy(engineMode = EngineMode.SYSTEM_INSTANT) }
    }

    fun deleteModel(modelId: String) {
        modelDownloader.deleteModel(modelId)
        viewModelScope.launch { WhisperEngine.unload() }
        val s = settingsRepo.current()
        if (s.selectedModelId == modelId && s.engineMode == EngineMode.WHISPER_OFFLINE) {
            val fallback = modelDownloader.installedIds.value.firstOrNull()
            if (fallback != null) {
                settingsRepo.update { it.copy(selectedModelId = fallback) }
            } else {
                settingsRepo.update { it.copy(engineMode = EngineMode.SYSTEM_INSTANT) }
            }
        }
    }

    fun updateSettings(transform: (HandySettingsState) -> HandySettingsState) {
        try {
            settingsRepo.update(transform)
        } catch (_: java.security.GeneralSecurityException) {
            _toast.tryEmit(localized("보안 저장소에 API 키를 저장할 수 없습니다.", "Could not save the API key in Android Keystore."))
        } catch (_: java.security.ProviderException) {
            _toast.tryEmit(localized("보안 저장소를 사용할 수 없습니다.", "Android Keystore is unavailable."))
        }
    }

    fun selectLlmProvider(providerId: String) {
        val preset = LlmProviders.byId(providerId)
        settingsRepo.update {
            it.copy(
                postProcessProviderId = preset.id,
                postProcessBaseUrl = preset.defaultBaseUrl,
                postProcessModel = preset.defaultModel,
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val ctx = getApplication<Application>()
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "audio"
    }

    override fun onCleared() {
        stopPlayback()
        systemSpeech.destroy()
        super.onCleared()
    }

    companion object {
        fun isKorean(): Boolean = Locale.getDefault().language == "ko"
        fun localized(ko: String, en: String): String = if (isKorean()) ko else en
    }
}
