package com.pais.handy.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure-Kotlin 16 kHz mono 16-bit PCM WAV codec and linear resampler.
 * Compatible with whisper.cpp's float[-1..1] 16 kHz mono audio input.
 */
object WavCodec {
    const val TARGET_SAMPLE_RATE = 16_000

    fun encodePcm16Wav(samples: FloatArray, sampleRate: Int = TARGET_SAMPLE_RATE): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())

        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val s = (clamped * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
            pcm.putShort(s)
        }
        out.write(pcm.array())
        return out.toByteArray()
    }

    fun writeWavFile(file: File, samples: FloatArray, sampleRate: Int = TARGET_SAMPLE_RATE) {
        file.parentFile?.mkdirs()
        file.writeBytes(encodePcm16Wav(samples, sampleRate))
    }

    fun readWavToMono16k(bytes: ByteArray): FloatArray {
        require(bytes.size >= 44) { "Invalid WAV file: too short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "Invalid WAV header" }

        var pos = 12
        var channels = 1
        var sampleRate = TARGET_SAMPLE_RATE
        var bitsPerSample = 16
        var audioFormat = 1
        var dataStart = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = buf.getInt(pos + 4)
            pos += 8
            if (chunkSize < 0 || pos + chunkSize > bytes.size) break
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize >= 16) {
                        audioFormat = buf.getShort(pos).toInt() and 0xFFFF
                        channels = buf.getShort(pos + 2).toInt() and 0xFFFF
                        sampleRate = buf.getInt(pos + 4)
                        bitsPerSample = buf.getShort(pos + 14).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataStart = pos
                    dataSize = chunkSize
                    break
                }
            }
            pos += chunkSize + (chunkSize and 1)
        }

        require(dataStart >= 0 && dataSize > 0) { "WAV file has no audio data" }
        require(audioFormat == 1 && bitsPerSample == 16 && channels >= 1) {
            "Unsupported WAV format (format=$audioFormat, bits=$bitsPerSample, ch=$channels)"
        }

        val frameCount = dataSize / (2 * channels)
        val mono = FloatArray(frameCount)
        var offset = dataStart
        for (i in 0 until frameCount) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += buf.getShort(offset).toFloat() / 32768f
                offset += 2
            }
            mono[i] = sum / channels
        }
        return resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE)
    }

    fun readWavFileToMono16k(file: File): FloatArray = readWavToMono16k(file.readBytes())

    fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate <= 0 || toRate <= 0 || fromRate == toRate) {
            return input
        }
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = ((input.size.toDouble() / ratio).toInt()).coerceAtLeast(1)
        val out = FloatArray(outLen)
        val lastIdx = input.size - 1
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceAtMost(lastIdx)
            val idx1 = (idx0 + 1).coerceAtMost(lastIdx)
            val frac = (srcPos - idx0).toFloat()
            out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
        }
        return out
    }
}
