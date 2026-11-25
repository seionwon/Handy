package com.pais.handy

import com.pais.handy.ime.SpeechPause
import com.pais.handy.ime.SpeechPause.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPauseTest {
    @Test fun endsOnlyAfterSpeechAndATrailingPause() {
        val pause = SpeechPause()
        assertEquals(Result.LISTEN, pause.check(2_000, 0.01f))
        assertEquals(Result.LISTEN, pause.check(2_100, 0.15f))
        assertEquals(Result.LISTEN, pause.check(3_300, 0.01f))
        assertEquals(Result.LISTEN, pause.check(3_400, 0.12f))
        assertEquals(Result.LISTEN, pause.check(4_899, 0f))
        assertEquals(Result.FINISH, pause.check(4_900, 0f))
    }

    @Test fun silentInputTimesOutWithoutTranscribing() {
        val pause = SpeechPause()
        assertEquals(Result.LISTEN, pause.check(7_999, 0.02f))
        assertEquals(Result.NO_SPEECH, pause.check(8_000, 0.02f))
    }

    @Test fun continuousSoundHasABoundedRecordingTime() {
        val pause = SpeechPause()
        assertEquals(Result.LISTEN, pause.check(100, 0.2f))
        assertEquals(Result.LISTEN, pause.check(SpeechPause.MAX_DURATION_MS - 1, 0.3f))
        assertEquals(Result.FINISH, pause.check(SpeechPause.MAX_DURATION_MS, 0.2f))
    }
}
