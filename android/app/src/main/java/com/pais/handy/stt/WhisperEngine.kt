package com.pais.handy.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal object WhisperNative {
    @Volatile
    private var loaded: Boolean = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("handy_whisper")
                loaded = true
            }
        }
    }

    external fun nativeInitContext(modelPath: String): Long
    external fun nativeFreeContext(contextPtr: Long)
    external fun nativeTranscribeUtf8(
        contextPtr: Long,
        numThreads: Int,
        language: String,
        translateToEnglish: Boolean,
        initialPrompt: String,
        audioData: FloatArray,
    ): ByteArray?
    external fun nativeSystemInfoUtf8(): ByteArray?
}

/**
 * Thread-safe manager for the native whisper.cpp context.
 * Keeps one loaded model cached in memory and swaps cleanly when the user picks another.
 */
object WhisperEngine {
    private val mutex = Mutex()
    private var loadedPath: String? = null
    private var contextPtr: Long = 0L

    suspend fun transcribe(
        modelFile: File,
        samples: FloatArray,
        language: String,
        translateToEnglish: Boolean,
        customWords: List<String>,
    ): String = withContext(Dispatchers.Default) {
        require(modelFile.isFile) { "Whisper model file does not exist: ${modelFile.name}" }
        if (samples.isEmpty()) return@withContext ""

        mutex.withLock {
            WhisperNative.ensureLoaded()
            val canonical = modelFile.canonicalPath
            if (contextPtr == 0L || loadedPath != canonical) {
                if (contextPtr != 0L) {
                    WhisperNative.nativeFreeContext(contextPtr)
                    contextPtr = 0L
                    loadedPath = null
                }
                val ptr = WhisperNative.nativeInitContext(canonical)
                check(ptr != 0L) { "Failed to load Whisper model (${modelFile.name})" }
                contextPtr = ptr
                loadedPath = canonical
            }

            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
            val initialPrompt = customWords.joinToString(", ")
            val utf8 = WhisperNative.nativeTranscribeUtf8(
                contextPtr = contextPtr,
                numThreads = threads,
                language = language.ifBlank { "auto" },
                translateToEnglish = translateToEnglish,
                initialPrompt = initialPrompt,
                audioData = samples,
            ) ?: error("Whisper transcription failed")

            TextPostProcessor.cleanWhisperArtifacts(String(utf8, Charsets.UTF_8))
        }
    }

    suspend fun unload() = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (contextPtr != 0L) {
                WhisperNative.nativeFreeContext(contextPtr)
                contextPtr = 0L
                loadedPath = null
            }
        }
    }

    fun systemInfo(): String = runCatching {
        WhisperNative.ensureLoaded()
        WhisperNative.nativeSystemInfoUtf8()?.let { String(it, Charsets.UTF_8) } ?: ""
    }.getOrDefault("")
}
