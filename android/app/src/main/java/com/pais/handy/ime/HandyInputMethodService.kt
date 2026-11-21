package com.pais.handy.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.pais.handy.MainActivity
import com.pais.handy.R
import com.pais.handy.audio.AudioCapture
import com.pais.handy.audio.WavCodec
import com.pais.handy.data.EngineMode
import com.pais.handy.data.HandyLanguages
import com.pais.handy.data.HandySettingsRepository
import com.pais.handy.data.HistoryDatabase
import com.pais.handy.data.ModelCatalog
import com.pais.handy.data.ModelDownloader
import com.pais.handy.stt.LlmClient
import com.pais.handy.stt.SystemSpeechEngine
import com.pais.handy.stt.TextPostProcessor
import com.pais.handy.stt.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HandyInputMethodService : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepo: HandySettingsRepository
    private lateinit var modelDownloader: ModelDownloader
    private lateinit var historyDb: HistoryDatabase
    private lateinit var systemSpeech: SystemSpeechEngine
    private val audioCapture = AudioCapture()

    private var engineChip: TextView? = null
    private var langChip: TextView? = null
    private var aiChip: TextView? = null
    private var statusText: TextView? = null
    private var micBtn: Button? = null
    private var cancelBtn: Button? = null

    private var backspaceJob: Job? = null
    private var activeRecordingMode: EngineMode? = null
    private var isBusy: Boolean = false

    override fun onCreate() {
        super.onCreate()
        settingsRepo = HandySettingsRepository(this)
        modelDownloader = ModelDownloader(this)
        historyDb = HistoryDatabase(this)
        systemSpeech = SystemSpeechEngine(this)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.handy_keyboard_view, null)
        engineChip = root.findViewById(R.id.ime_engine_chip)
        langChip = root.findViewById(R.id.ime_lang_chip)
        aiChip = root.findViewById(R.id.ime_ai_chip)
        statusText = root.findViewById(R.id.ime_status_text)
        micBtn = root.findViewById(R.id.ime_mic_btn)
        cancelBtn = root.findViewById(R.id.ime_cancel_btn)

        engineChip?.setOnClickListener {
            if (activeRecordingMode != null || isBusy) return@setOnClickListener
            val current = settingsRepo.current()
            val next = if (current.engineMode == EngineMode.SYSTEM_INSTANT) {
                EngineMode.WHISPER_OFFLINE
            } else {
                EngineMode.SYSTEM_INSTANT
            }
            settingsRepo.update { it.copy(engineMode = next) }
            refreshChips()
        }

        langChip?.setOnClickListener {
            if (activeRecordingMode != null || isBusy) return@setOnClickListener
            val cycle = listOf("auto", "ko", "en", "ja", "zh")
            val cur = settingsRepo.current().selectedLanguage
            val next = cycle[(cycle.indexOf(cur) + 1) % cycle.size]
            settingsRepo.update { it.copy(selectedLanguage = next) }
            refreshChips()
        }

        aiChip?.setOnClickListener {
            if (activeRecordingMode != null || isBusy) return@setOnClickListener
            settingsRepo.update { it.copy(postProcessEnabled = !it.postProcessEnabled) }
            refreshChips()
        }

        root.findViewById<TextView>(R.id.ime_open_app)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        cancelBtn?.setOnClickListener {
            cancelActiveDictation()
        }

        micBtn?.setOnTouchListener { view, event ->
            val holdMode = settingsRepo.current().holdToTalk
            if (!holdMode) {
                if (event.action == MotionEvent.ACTION_UP) {
                    view.performClick()
                    toggleDictation()
                }
                true
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startDictation()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (activeRecordingMode != null) {
                            stopAndCommitDictation()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        root.findViewById<Button>(R.id.ime_switch_btn)?.setOnClickListener {
            val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                false
            }
            if (!switched) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        root.findViewById<Button>(R.id.ime_space_btn)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
            tickHaptic()
        }

        val backspaceBtn = root.findViewById<Button>(R.id.ime_backspace_btn)
        backspaceBtn?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    deleteCharBeforeCursor()
                    backspaceJob?.cancel()
                    backspaceJob = serviceScope.launch {
                        delay(380)
                        while (isActive) {
                            deleteCharBeforeCursor()
                            delay(55)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    backspaceJob?.cancel()
                    backspaceJob = null
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        root.findViewById<Button>(R.id.ime_enter_btn)?.setOnClickListener {
            val editorInfo = currentInputEditorInfo
            val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE
            val handled = when (action) {
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_DONE -> currentInputConnection?.performEditorAction(action) == true
                else -> false
            }
            if (!handled) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            tickHaptic()
        }

        refreshChips()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshChips()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        backspaceJob?.cancel()
        if (activeRecordingMode != null) {
            cancelActiveDictation()
        }
    }

    override fun onDestroy() {
        backspaceJob?.cancel()
        systemSpeech.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun deleteCharBeforeCursor() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun toggleDictation() {
        if (isBusy) return
        if (activeRecordingMode != null) {
            stopAndCommitDictation()
        } else {
            startDictation()
        }
    }

    private fun startDictation() {
        if (isBusy || activeRecordingMode != null) return
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            statusText?.text = getString(R.string.ime_need_mic)
            return
        }

        val settings = settingsRepo.current()
        if (settings.engineMode == EngineMode.WHISPER_OFFLINE) {
            val modelFile = modelDownloader.installedModelFile(settings.selectedModelId)
            if (modelFile == null) {
                statusText?.text = getString(R.string.ime_need_model)
                return
            }
            audioCapture.start(serviceScope)
                .onSuccess {
                    activeRecordingMode = EngineMode.WHISPER_OFFLINE
                    tickHaptic()
                    updateRecordingUi(true, getString(R.string.ime_listening))
                }
                .onFailure { e ->
                    statusText?.text = e.message ?: "Microphone error"
                }
        } else {
            activeRecordingMode = EngineMode.SYSTEM_INSTANT
            tickHaptic()
            updateRecordingUi(true, getString(R.string.ime_listening))
            val lang = HandyLanguages.byCode(settings.selectedLanguage)
            systemSpeech.start(
                bcp47Language = lang.bcp47,
                onRmsLevel = {},
                onPartial = { partial ->
                    statusText?.text = partial
                },
                onResult = { rawText ->
                    activeRecordingMode = null
                    handleFinalTranscript(rawText, null, "Instant Speech")
                },
                onError = { err ->
                    activeRecordingMode = null
                    updateRecordingUi(false, err)
                },
            )
        }
    }

    private fun stopAndCommitDictation() {
        val mode = activeRecordingMode ?: return
        activeRecordingMode = null
        if (mode == EngineMode.SYSTEM_INSTANT) {
            systemSpeech.stopListening()
            updateRecordingUi(false, getString(R.string.ime_transcribing))
            return
        }

        isBusy = true
        updateRecordingUi(false, getString(R.string.ime_transcribing))
        val settings = settingsRepo.current()
        val model = ModelCatalog.byId(settings.selectedModelId)
        val modelFile = modelDownloader.installedModelFile(settings.selectedModelId)

        serviceScope.launch {
            try {
                val samples = audioCapture.stopAndGetSamples()
                if (samples.size < 3200 || modelFile == null) {
                    isBusy = false
                    updateRecordingUi(false, idlePrompt())
                    return@launch
                }
                val wavName = "handy-ime-${System.currentTimeMillis()}.wav"
                val wavFile = File(historyDb.recordingsDir, wavName)
                withContext(Dispatchers.IO) {
                    WavCodec.writeWavFile(wavFile, samples)
                }
                val raw = WhisperEngine.transcribe(
                    modelFile = modelFile,
                    samples = samples,
                    language = settings.selectedLanguage,
                    translateToEnglish = settings.translateToEnglish,
                    customWords = settings.customWords,
                )
                isBusy = false
                handleFinalTranscript(raw, wavName, model?.name ?: "Whisper")
            } catch (e: Exception) {
                isBusy = false
                updateRecordingUi(false, e.message ?: "Transcription failed")
            }
        }
    }

    private fun cancelActiveDictation() {
        val mode = activeRecordingMode
        activeRecordingMode = null
        if (mode == EngineMode.SYSTEM_INSTANT) {
            systemSpeech.cancel()
        } else if (mode == EngineMode.WHISPER_OFFLINE) {
            serviceScope.launch { audioCapture.cancel() }
        }
        isBusy = false
        updateRecordingUi(false, idlePrompt())
    }

    private fun handleFinalTranscript(rawText: String, audioFileName: String?, engineLabel: String) {
        val settings = settingsRepo.current()
        val cleaned = TextPostProcessor.finalizeTranscript(
            text = rawText,
            removeFillerWords = settings.removeFillerWords,
            appendTrailingSpace = false,
        )
        if (cleaned.isEmpty()) {
            updateRecordingUi(false, idlePrompt())
            return
        }

        isBusy = true
        serviceScope.launch {
            var postProcessed: String? = null
            if (settings.postProcessEnabled && settings.postProcessApiKey.isNotBlank()) {
                statusText?.text = getString(R.string.ime_post_processing)
                postProcessed = LlmClient.postProcess(settings, cleaned).getOrNull()
            }
            val outputBase = postProcessed ?: cleaned
            val finalOutput = if (settings.appendTrailingSpace) "$outputBase " else outputBase

            currentInputConnection?.commitText(finalOutput, 1)
            tickHaptic()

            val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            withContext(Dispatchers.IO) {
                historyDb.insert(
                    title = title,
                    audioFileName = audioFileName,
                    sourceName = null,
                    language = settings.selectedLanguage,
                    engineLabel = engineLabel,
                    transcriptionText = cleaned,
                    postProcessedText = postProcessed,
                )
            }
            isBusy = false
            updateRecordingUi(false, outputBase)
        }
    }

    private fun refreshChips() {
        val s = settingsRepo.current()
        val isKo = Locale.getDefault().language == "ko"
        engineChip?.text = if (s.engineMode == EngineMode.WHISPER_OFFLINE) {
            ModelCatalog.byId(s.selectedModelId)?.name?.replace("Whisper ", "Whisper ")
                ?: "Whisper"
        } else {
            if (isKo) "즉시 인식" else "Instant"
        }
        val lang = HandyLanguages.byCode(s.selectedLanguage)
        langChip?.text = if (isKo) lang.labelKo else lang.labelEn
        aiChip?.text = getString(if (s.postProcessEnabled) R.string.ime_ai_on else R.string.ime_ai_off)
        if (activeRecordingMode == null && !isBusy) {
            micBtn?.text = idlePrompt()
            statusText?.text = idlePrompt()
        }
    }

    private fun idlePrompt(): String {
        val hold = settingsRepo.current().holdToTalk
        return getString(if (hold) R.string.ime_hold_to_speak else R.string.ime_tap_to_speak)
    }

    private fun updateRecordingUi(recording: Boolean, status: String) {
        statusText?.text = status
        cancelBtn?.visibility = if (recording) View.VISIBLE else View.GONE
        micBtn?.setBackgroundResource(
            if (recording) R.drawable.bg_ime_mic_active else R.drawable.bg_ime_mic_idle,
        )
        micBtn?.text = if (recording) getString(R.string.ime_listening) else idlePrompt()
    }

    private fun tickHaptic() {
        if (!settingsRepo.current().hapticFeedback) return
        micBtn?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
