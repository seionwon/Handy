package com.pais.handy.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono 16-bit PCM microphone audio via Android AudioRecord
 * and publishes smoothed RMS levels for the recording visualizer.
 */
class AudioCapture {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val pcmBuffer = ByteArrayOutputStream()
    private val lock = Any()

    @Volatile
    var isRecording: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Result<Unit> = runCatching {
        synchronized(lock) {
            if (isRecording) return@runCatching
            val minBuf = AudioRecord.getMinBufferSize(
                WavCodec.TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBuf > 0) { "Microphone AudioRecord configuration unavailable" }

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavCodec.TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, WavCodec.TARGET_SAMPLE_RATE),
            )
            check(rec.state == AudioRecord.STATE_INITIALIZED) {
                rec.release()
                "Failed to initialize microphone AudioRecord"
            }

            try {
                rec.startRecording()
                check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start recording" }
            } catch (e: RuntimeException) {
                rec.release()
                throw e
            }
            pcmBuffer.reset()
            recorder = rec
            isRecording = true
            _level.value = 0f
            _elapsedMs.value = 0L
            val startedAt = System.currentTimeMillis()
            captureJob = scope.launch(Dispatchers.IO) {
                val readChunk = ShortArray(1024)
                val byteChunk = ByteArray(readChunk.size * 2)
                while (isActive && isRecording) {
                    val count = rec.read(readChunk, 0, readChunk.size)
                    if (count <= 0) continue
                    var sumSq = 0.0
                    val bb = ByteBuffer.wrap(byteChunk).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until count) {
                        val s = readChunk[i]
                        bb.putShort(s)
                        val norm = s.toDouble() / 32768.0
                        sumSq += norm * norm
                    }
                    synchronized(lock) {
                        pcmBuffer.write(byteChunk, 0, count * 2)
                    }
                    val rms = sqrt(sumSq / count).toFloat()
                    _level.value = (rms * 5.5f).coerceIn(0f, 1f)
                    _elapsedMs.value = System.currentTimeMillis() - startedAt
                }
            }
        }
    }

    suspend fun stopAndGetSamples(): FloatArray {
        val job: Job?
        val rec: AudioRecord?
        synchronized(lock) {
            isRecording = false
            job = captureJob
            captureJob = null
            rec = recorder
            recorder = null
        }
        try {
            rec?.stop()
        } catch (_: Exception) {
        }
        job?.join()
        rec?.release()
        _level.value = 0f

        val bytes = synchronized(lock) { pcmBuffer.toByteArray() }
        val sampleCount = bytes.size / 2
        val samples = FloatArray(sampleCount)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            samples[i] = bb.short.toFloat() / 32768f
        }
        return samples
    }

    suspend fun cancel() {
        stopAndGetSamples()
        synchronized(lock) { pcmBuffer.reset() }
        _elapsedMs.value = 0L
    }
}
