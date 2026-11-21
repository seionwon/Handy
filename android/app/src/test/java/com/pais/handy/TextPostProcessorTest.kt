package com.pais.handy

import com.pais.handy.stt.TextPostProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPostProcessorTest {
    @Test
    fun cleansWhisperArtifactsAndLonePeriod() {
        assertEquals("", TextPostProcessor.cleanWhisperArtifacts("[BLANK_AUDIO]"))
        assertEquals("", TextPostProcessor.cleanWhisperArtifacts(" . "))
        assertEquals("안녕하세요 반갑습니다", TextPostProcessor.cleanWhisperArtifacts(" [MUSIC] 안녕하세요  반갑습니다 "))
    }

    @Test
    fun stripsThinkBlocksAndRejectsBlankPostProcessedReplies() {
        assertNull(TextPostProcessor.cleanPostProcessed("<think>internal</think>\u200B  "))
        assertEquals("정리된 문장입니다.", TextPostProcessor.cleanPostProcessed("<think>ok</think>\n정리된 문장입니다."))
    }

    @Test
    fun removesEnglishFillersAndAppendsOptionalSpace() {
        val out = TextPostProcessor.finalizeTranscript(
            text = "um hello uh world",
            removeFillerWords = true,
            appendTrailingSpace = true,
        )
        assertEquals("hello world ", out)
    }

    @Test
    fun rejectsUnsafeRecordingFileNames() {
        assertTrue(TextPostProcessor.isSafeRecordingFileName("handy-12345.wav"))
        assertFalse(TextPostProcessor.isSafeRecordingFileName("../secret.wav"))
        assertFalse(TextPostProcessor.isSafeRecordingFileName("/tmp/secret.wav"))
        assertFalse(TextPostProcessor.isSafeRecordingFileName("sub/file.wav"))
        assertFalse(TextPostProcessor.isSafeRecordingFileName("settings.json"))
    }
}
