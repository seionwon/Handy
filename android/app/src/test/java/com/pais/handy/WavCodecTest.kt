package com.pais.handy

import com.pais.handy.audio.WavCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WavCodecTest {
    @Test
    fun roundTripMono16kPcm() {
        val original = FloatArray(160) { i -> ((i - 80) / 80.0f).coerceIn(-0.95f, 0.95f) }
        val wav = WavCodec.encodePcm16Wav(original, 16_000)
        assertEquals(44 + original.size * 2, wav.size)

        val decoded = WavCodec.readWavToMono16k(wav)
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertTrue("sample $i drifted", abs(original[i] - decoded[i]) < 0.001f)
        }
    }

    @Test
    fun resample48kTo16kPreservesDuration() {
        val input48k = FloatArray(48_000) { 0.5f }
        val out16k = WavCodec.resampleLinear(input48k, 48_000, 16_000)
        assertEquals(16_000, out16k.size)
        assertTrue(abs(out16k[8_000] - 0.5f) < 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidHeader() {
        WavCodec.readWavToMono16k("not a wav file at all 123456789012345678901234567890".toByteArray())
    }
}
