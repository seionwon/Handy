package com.pais.handy

import com.pais.handy.audio.AudioFileDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioFileDecoderTest {
    @Test
    fun readsStreamUpToLimit() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(data, AudioFileDecoder.readBounded(data.inputStream(), data.size))
    }

    @Test
    fun rejectsStreamOverLimit() {
        val data = ByteArray(200_000)
        assertThrows(AudioFileDecoder.AudioTooLongException::class.java) {
            AudioFileDecoder.readBounded(data.inputStream(), data.size - 1)
        }
    }
}
