package com.pais.handy.ime

/** End one-button Whisper dictation on trailing silence, not on the initial pause. */
internal class SpeechPause {
    enum class Result { LISTEN, FINISH, NO_SPEECH }

    private var lastSpeechMs: Long? = null

    fun check(elapsedMs: Long, level: Float): Result {
        // AudioCapture publishes RMS scaled by 5.5.
        if (level >= 0.06f) lastSpeechMs = elapsedMs
        val last = lastSpeechMs
        return when {
            last == null && elapsedMs >= 8_000 -> Result.NO_SPEECH
            last != null && (elapsedMs - last >= 1_500 || elapsedMs >= MAX_DURATION_MS) -> Result.FINISH
            else -> Result.LISTEN
        }
    }

    companion object {
        const val MAX_DURATION_MS = 120_000L
    }
}
