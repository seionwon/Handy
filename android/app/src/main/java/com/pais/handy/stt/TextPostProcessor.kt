package com.pais.handy.stt

/**
 * Shared text cleanup and safety helpers matching Handy desktop guarantees.
 */
object TextPostProcessor {
    private val whisperBracketTokens = Regex("\\[(?:BLANK_AUDIO|MUSIC|APPLAUSE|LAUGHTER|SILENCE|INAUDIBLE|NOISE|SOUND)]", RegexOption.IGNORE_CASE)
    private val whisperParenTokens = Regex("\\((?:music|applause|laughter|silence|inaudible|noise|blank audio)\\)", RegexOption.IGNORE_CASE)
    private val thinkBlockRegex = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val invisibleChars = Regex("[\\u200B\\u200C\\u200D\\uFEFF]")
    private val englishFillers = Regex("\\b(?:uh+|um+|uhm+|er+|erm+|hmm+)\\b[,\\s]*", RegexOption.IGNORE_CASE)
    private val multiSpace = Regex("[ \\t]{2,}")

    fun cleanWhisperArtifacts(raw: String): String {
        var text = whisperBracketTokens.replace(raw, " ")
        text = whisperParenTokens.replace(text, " ")
        text = invisibleChars.replace(text, "")
        text = multiSpace.replace(text, " ").trim()
        // Drop transcripts that are only a lone period (matches upstream #2434)
        if (text == "." || text == "。") return ""
        return text
    }

    fun cleanPostProcessed(raw: String): String? {
        var text = thinkBlockRegex.replace(raw, "")
        text = invisibleChars.replace(text, "").trim()
        return text.ifBlank { null }
    }

    fun finalizeTranscript(
        text: String,
        removeFillerWords: Boolean,
        appendTrailingSpace: Boolean,
    ): String {
        var out = text.trim()
        if (out.isEmpty()) return ""
        if (removeFillerWords) {
            out = englishFillers.replace(out, "")
            out = multiSpace.replace(out, " ").trim()
        }
        if (out.isEmpty()) return ""
        return if (appendTrailingSpace) "$out " else out
    }

    /**
     * Direct WAV child filename check matching desktop's history_audio_path::resolve.
     */
    fun isSafeRecordingFileName(fileName: String): Boolean {
        if (fileName.isBlank()) return false
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains(':') || fileName.contains('\u0000')) {
            return false
        }
        if (fileName == "." || fileName == ".." || fileName.contains("..")) return false
        return fileName.endsWith(".wav", ignoreCase = true)
    }
}
