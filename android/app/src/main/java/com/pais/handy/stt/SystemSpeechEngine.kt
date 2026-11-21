package com.pais.handy.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

    fun start(
        bcp47Language: String?,
        onRmsLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Android speech recognition service is unavailable on this device. Switch to Offline Whisper in Models.")
                return@post
            }
            destroyInternal()
            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            recognizer = sr
            var lastPartial = ""
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    onRmsLevel(normalized)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    onRmsLevel(0f)
                }

                override fun onError(error: Int) {
                    isListening = false
                    onRmsLevel(0f)
                    if (error == SpeechRecognizer.ERROR_NO_MATCH && lastPartial.isNotBlank()) {
                        onResult(lastPartial)
                        return
                    }
                    onError(describeError(error))
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    onRmsLevel(0f)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty().ifBlank { lastPartial }
                    onResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
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
            isListening = true
            sr.startListening(intent)
        }
    }

    fun stopListening() {
        mainHandler.post {
            isListening = false
            runCatching { recognizer?.stopListening() }
        }
    }

    fun cancel() {
        mainHandler.post {
            isListening = false
            runCatching { recognizer?.cancel() }
            destroyInternal()
        }
    }

    fun destroy() {
        mainHandler.post { destroyInternal() }
    }

    private fun destroyInternal() {
        isListening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network unavailable for system speech. Download an offline Whisper model for 100% local transcription."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        else -> "Speech recognition error ($code)"
    }
}
