package com.pais.handy.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One editor session, shared by the voice keyboard and the accessibility button. */
internal class EditorDictation(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: HandySettingsRepository,
    private val finishOnPause: Boolean = false,
    private val onStatus: (String?) -> Unit,
    private val onPartial: (String) -> Unit = {},
    private val onInserted: (String) -> Unit = {},
    private val speech: SystemSpeechEngine = SystemSpeechEngine(context),
    private val historyDb: HistoryDatabase = HistoryDatabase(context),
) {
    class Target(val isFocused: () -> Boolean, val insert: (String) -> Boolean)

    class Session(val target: Target, mode: EngineMode) {
        val capture = if (mode == EngineMode.WHISPER_OFFLINE) AudioCapture() else null
        var processing: Job? = null
        var timeout: Job? = null
        private var inserted = false

        fun ownsInput(active: Session?): Boolean = active === this && target.isFocused()

        fun insertIfCurrent(active: Session?, text: String): Boolean {
            if (inserted || !ownsInput(active)) return false
            inserted = target.insert(text)
            return inserted
        }
    }

    private val models by lazy { ModelDownloader.get(context) }
    private var active: Session? = null
    val busy: Boolean get() = active != null
    var recording: Boolean = false
        private set

    init {
        scope.coroutineContext.job.invokeOnCompletion { historyDb.close() }
    }

    fun start(target: Target) {
        if (busy || !target.isFocused()) return
        val micPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (micPermission != PackageManager.PERMISSION_GRANTED) {
            onStatus(context.getString(R.string.ime_need_mic))
            return
        }
        val settings = settingsRepo.current()
        val session = Session(target, settings.engineMode)
        val capture = session.capture
        if (capture != null) {
            if (models.installedModelFile(settings.selectedModelId) == null) {
                onStatus(context.getString(R.string.ime_need_model))
                return
            }
            capture.start(scope).onFailure {
                onStatus(context.getString(R.string.dictation_audio_error))
                return
            }
        }
        active = session
        recording = true
        onStatus(context.getString(R.string.ime_listening))
        if (finishOnPause) watchForEndOfSpeech(session)
        if (capture == null) {
            speech.start(
                bcp47Language = HandyLanguages.byCode(settings.selectedLanguage).bcp47,
                onRmsLevel = {},
                onPartial = { if (session.ownsInput(active)) onPartial(it) },
                onResult = { raw ->
                    if (session.ownsInput(active) && session.processing == null) {
                        process(session) { deliver(session, raw, null, "Instant Speech") }
                    }
                },
                onError = {
                    if (session.ownsInput(active) && session.processing == null) {
                        cancel()
                        onStatus(it)
                    }
                },
                onSpeechEnd = {
                    if (session.ownsInput(active) && recording) awaitSystemResult(session)
                },
            )
        }
    }

    private fun watchForEndOfSpeech(session: Session) {
        session.timeout = scope.launch {
            val capture = session.capture
            if (capture == null) {
                // The system recognizer detects the end of an utterance itself.
                delay(SpeechPause.MAX_DURATION_MS)
                if (active === session) stop()
                return@launch
            }
            val started = SystemClock.elapsedRealtime()
            val pause = SpeechPause()
            while (active === session && recording) {
                delay(100)
                when (pause.check(SystemClock.elapsedRealtime() - started, capture.level.value)) {
                    SpeechPause.Result.LISTEN -> Unit
                    SpeechPause.Result.FINISH -> {
                        stop()
                        return@launch
                    }
                    SpeechPause.Result.NO_SPEECH -> {
                        cancel()
                        onStatus(context.getString(R.string.dictation_no_speech))
                        return@launch
                    }
                }
            }
        }
    }

    fun stop() {
        val session = active ?: return
        if (!recording) return
        val capture = session.capture
        if (capture == null) {
            awaitSystemResult(session)
            speech.stopListening()
            return
        }
        recording = false
        onStatus(context.getString(R.string.ime_transcribing))
        val settings = settingsRepo.current()
        process(session) {
            val samples = withContext(NonCancellable) { capture.stopAndGetSamples() }
            if (!session.ownsInput(active)) return@process
            val model = models.installedModelFile(settings.selectedModelId)
            if (samples.size < 3200 || model == null) {
                val reason = if (model == null) R.string.ime_need_model else R.string.dictation_no_speech
                onStatus(context.getString(reason))
                return@process
            }
            val raw = WhisperEngine.transcribe(
                model, samples, settings.selectedLanguage, settings.translateToEnglish, settings.customWords,
            )
            deliver(session, raw, samples, ModelCatalog.byId(settings.selectedModelId)?.name ?: "Whisper")
        }
    }

    private fun awaitSystemResult(session: Session) {
        recording = false
        session.timeout?.cancel()
        onStatus(context.getString(R.string.ime_transcribing))
        session.timeout = scope.launch {
            delay(15_000)
            if (active === session) {
                cancel()
                onStatus(context.getString(R.string.dictation_no_result))
            }
        }
    }

    private fun process(session: Session, action: suspend () -> Unit) {
        recording = false
        session.timeout?.cancel()
        if (session.capture == null) speech.destroy()
        session.processing = scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.ownsInput(active)) onStatus(e.message ?: context.getString(R.string.dictation_failed))
            } finally {
                if (active === session) active = null
            }
        }
    }

    private suspend fun deliver(session: Session, raw: String, samples: FloatArray?, engine: String) {
        if (!session.ownsInput(active)) return
        val settings = settingsRepo.current()
        val cleaned = TextPostProcessor.finalizeTranscript(raw, settings.removeFillerWords, false)
        if (cleaned.isEmpty()) {
            onStatus(context.getString(R.string.dictation_no_speech))
            return
        }
        val polished = if (settings.postProcessEnabled && settings.postProcessApiKey.isNotBlank()) {
            onStatus(context.getString(R.string.ime_post_processing))
            LlmClient.postProcess(settings, cleaned).getOrNull()
        } else null
        val output = polished ?: cleaned
        if (!session.ownsInput(active)) return
        if (!session.insertIfCurrent(active, if (settings.appendTrailingSpace) "$output " else output)) {
            onStatus(context.getString(R.string.dictation_choose_field))
            return
        }
        onInserted(output)
        // Once inserted, finish the history entry even if the user switches editors.
        withContext(NonCancellable + Dispatchers.IO) {
            val wav = samples?.let { File.createTempFile("handy-ime-", ".wav", historyDb.recordingsDir) }
            try {
                if (wav != null) WavCodec.writeWavFile(wav, samples)
                val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                historyDb.insert(title, wav?.name, null, settings.selectedLanguage, engine, cleaned, polished)
            } catch (e: Exception) {
                wav?.delete()
                throw e
            }
        }
    }

    fun cancel() {
        val session = active ?: return
        active = null
        recording = false
        session.timeout?.cancel()
        session.processing?.cancel()
        val capture = session.capture
        if (capture == null) speech.cancel()
        else if (capture.isRecording) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { capture.cancel() }
        }
        onStatus(null)
    }

    fun destroy() {
        cancel()
        speech.destroy()
    }
}
