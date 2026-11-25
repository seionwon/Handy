package com.pais.handy.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.pais.handy.R
import java.util.Locale

/**
 * Wraps Android's built-in SpeechRecognizer for instant, zero-download speech-to-text
 * with live partial transcripts. All SpeechRecognizer calls run on the main looper.
 */
class SystemSpeechEngine(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    var isListening: Boolean = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

    fun start(
        bcp47Language: String?,
        onRmsLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSpeechEnd: () -> Unit = {},
    ) {
        onMain {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError(context.getString(R.string.dictation_recognizer_unavailable))
                return@onMain
            }
            destroyInternal()
            val sr = try {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            } catch (e: RuntimeException) {
                onError(e.localizedMessage ?: context.getString(R.string.dictation_failed))
                return@onMain
            }
            recognizer = sr
            var lastPartial = ""
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (recognizer === sr) isListening = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    if (recognizer !== sr) return
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    onRmsLevel(normalized)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (recognizer !== sr) return
                    isListening = false
                    onRmsLevel(0f)
                    onSpeechEnd()
                }

                override fun onError(error: Int) {
                    if (recognizer !== sr) return
                    isListening = false
                    onRmsLevel(0f)
                    if (error == SpeechRecognizer.ERROR_NO_MATCH && lastPartial.isNotBlank()) {
                        onResult(lastPartial)
                        return
                    }
                    onError(describeError(error))
                }

                override fun onResults(results: Bundle?) {
                    if (recognizer !== sr) return
                    isListening = false
                    onRmsLevel(0f)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty().ifBlank { lastPartial }
                    onResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognizer !== sr) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        lastPartial = text
                        onPartial(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                val lang = bcp47Language ?: Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            }
            try {
                isListening = true
                sr.startListening(intent)
            } catch (e: RuntimeException) {
                destroyInternal()
                onError(e.localizedMessage ?: context.getString(R.string.dictation_failed))
            }
        }
    }

    fun stopListening() {
        onMain {
            isListening = false
            runCatching { recognizer?.stopListening() }
        }
    }

    fun cancel() {
        onMain {
            isListening = false
            runCatching { recognizer?.cancel() }
            destroyInternal()
        }
    }

    fun destroy() {
        onMain { destroyInternal() }
    }

    private fun destroyInternal() {
        isListening = false
        val old = recognizer
        recognizer = null
        runCatching { old?.destroy() }
    }

    private fun describeError(code: Int): String {
        val message = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> R.string.dictation_audio_error
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.ime_need_mic
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.dictation_network_error
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.dictation_no_speech
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.dictation_recognizer_busy
            else -> return context.getString(R.string.dictation_recognizer_error, code)
        }
        return context.getString(message)
    }
}
