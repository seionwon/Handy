package com.pais.handy.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EngineMode(val id: String) {
    SYSTEM_INSTANT("system_instant"),
    WHISPER_OFFLINE("whisper_offline");

    companion object {
        fun fromId(id: String?): EngineMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM_INSTANT
    }
}

enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class LlmApiFormat {
    OPENAI,
    ANTHROPIC,
}

data class LlmProviderPreset(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val format: LlmApiFormat,
)

object LlmProviders {
    val all: List<LlmProviderPreset> = listOf(
        LlmProviderPreset(
            id = "openai",
            label = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            format = LlmApiFormat.OPENAI,
        ),
        LlmProviderPreset(
            id = "anthropic",
            label = "Anthropic",
            defaultBaseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-3-5-haiku-latest",
            format = LlmApiFormat.ANTHROPIC,
        ),
        LlmProviderPreset(
            id = "openrouter",
            label = "OpenRouter",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openai/gpt-4o-mini",
            format = LlmApiFormat.OPENAI,
        ),
        LlmProviderPreset(
            id = "groq",
            label = "Groq",
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            format = LlmApiFormat.OPENAI,
        ),
        LlmProviderPreset(
            id = "custom",
            label = "Custom (OpenAI)",
            defaultBaseUrl = "http://127.0.0.1:11434/v1",
            defaultModel = "llama3.2",
            format = LlmApiFormat.OPENAI,
        ),
    )

    fun byId(id: String?): LlmProviderPreset =
        all.firstOrNull { it.id == id } ?: all.first()
}

data class HandyLanguage(
    val code: String,
    val labelKo: String,
    val labelEn: String,
    val bcp47: String?,
)

object HandyLanguages {
    val all: List<HandyLanguage> = listOf(
        HandyLanguage("auto", "자동 감지", "Auto", null),
        HandyLanguage("ko", "한국어", "Korean", "ko-KR"),
        HandyLanguage("en", "영어", "English", "en-US"),
        HandyLanguage("ja", "일본어", "Japanese", "ja-JP"),
        HandyLanguage("zh", "중국어", "Chinese", "zh-CN"),
        HandyLanguage("es", "스페인어", "Spanish", "es-ES"),
        HandyLanguage("fr", "프랑스어", "French", "fr-FR"),
        HandyLanguage("de", "독일어", "German", "de-DE"),
        HandyLanguage("vi", "베트남어", "Vietnamese", "vi-VN"),
        HandyLanguage("th", "태국어", "Thai", "th-TH"),
    )

    fun byCode(code: String?): HandyLanguage =
        all.firstOrNull { it.code == code } ?: all.first()
}

data class HandySettingsState(
    val engineMode: EngineMode = EngineMode.SYSTEM_INSTANT,
    val selectedModelId: String = "whisper-base-q5",
    val selectedLanguage: String = "auto",
    val translateToEnglish: Boolean = false,
    val holdToTalk: Boolean = false,
    val appendTrailingSpace: Boolean = true,
    val autoCopyToClipboard: Boolean = true,
    val hapticFeedback: Boolean = true,
    val removeFillerWords: Boolean = true,
    val customWords: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val postProcessEnabled: Boolean = false,
    val postProcessProviderId: String = "openai",
    val postProcessBaseUrl: String = "https://api.openai.com/v1",
    val postProcessApiKey: String = "",
    val postProcessKeyScope: String? = null,
    val postProcessModel: String = "gpt-4o-mini",
    val postProcessPrompt: String = DEFAULT_POST_PROCESS_PROMPT,
) {
    companion object {
        const val DEFAULT_POST_PROCESS_PROMPT =
            "Clean up this voice transcription. Fix punctuation, spacing, and obvious speech recognition typos while preserving the original language and meaning. Return only the cleaned text."
    }
}

class HandySettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<HandySettingsState> = _state.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = load()
    }

    init {
        // The legacy shared key has no trustworthy provider or origin.
        if (prefs.contains("post_process_api_key")) prefs.edit().remove("post_process_api_key").apply()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun current(): HandySettingsState = _state.value

    fun update(transform: (HandySettingsState) -> HandySettingsState) = synchronized(settingsLock) {
        val previous = load()
        val next = transform(previous)
        val scope = credentialScope(next.postProcessProviderId, next.postProcessBaseUrl)
        val editor = prefs.edit()
        if (scope != null && scope == previous.postProcessKeyScope && next.postProcessApiKey != previous.postProcessApiKey) {
            ProviderCredentials.write(editor, scope, next.postProcessApiKey)
        }
        save(next, editor)
        _state.value = load()
    }

    private fun load(): HandySettingsState {
        val providerId = prefs.getString("post_process_provider_id", "openai") ?: "openai"
        val preset = LlmProviders.byId(providerId)
        val baseUrl = prefs.getString("post_process_base_url", preset.defaultBaseUrl) ?: preset.defaultBaseUrl
        val scope = credentialScope(providerId, baseUrl)
        val rawWords = prefs.getString("custom_words", "") ?: ""
        val customWords = rawWords
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return HandySettingsState(
            engineMode = EngineMode.fromId(prefs.getString("engine_mode", EngineMode.SYSTEM_INSTANT.id)),
            selectedModelId = prefs.getString("selected_model_id", "whisper-base-q5") ?: "whisper-base-q5",
            selectedLanguage = prefs.getString("selected_language", "auto") ?: "auto",
            translateToEnglish = prefs.getBoolean("translate_to_english", false),
            holdToTalk = prefs.getBoolean("hold_to_talk", false),
            appendTrailingSpace = prefs.getBoolean("append_trailing_space", true),
            autoCopyToClipboard = prefs.getBoolean("auto_copy_to_clipboard", true),
            hapticFeedback = prefs.getBoolean("haptic_feedback", true),
            removeFillerWords = prefs.getBoolean("remove_filler_words", true),
            customWords = customWords,
            themeMode = ThemeMode.fromId(prefs.getString("theme_mode", ThemeMode.SYSTEM.id)),
            postProcessEnabled = prefs.getBoolean("post_process_enabled", false),
            postProcessProviderId = providerId,
            postProcessBaseUrl = baseUrl,
            postProcessApiKey = ProviderCredentials.read(prefs, scope),
            postProcessKeyScope = scope,
            postProcessModel = prefs.getString("post_process_model", preset.defaultModel)
                ?: preset.defaultModel,
            postProcessPrompt = prefs.getString(
                "post_process_prompt",
                HandySettingsState.DEFAULT_POST_PROCESS_PROMPT,
            ) ?: HandySettingsState.DEFAULT_POST_PROCESS_PROMPT,
        )
    }

    private fun save(s: HandySettingsState, editor: SharedPreferences.Editor) {
        editor
            .putString("engine_mode", s.engineMode.id)
            .putString("selected_model_id", s.selectedModelId)
            .putString("selected_language", s.selectedLanguage)
            .putBoolean("translate_to_english", s.translateToEnglish)
            .putBoolean("hold_to_talk", s.holdToTalk)
            .putBoolean("append_trailing_space", s.appendTrailingSpace)
            .putBoolean("auto_copy_to_clipboard", s.autoCopyToClipboard)
            .putBoolean("haptic_feedback", s.hapticFeedback)
            .putBoolean("remove_filler_words", s.removeFillerWords)
            .putString("custom_words", s.customWords.joinToString("\n"))
            .putString("theme_mode", s.themeMode.id)
            .putBoolean("post_process_enabled", s.postProcessEnabled)
            .putString("post_process_provider_id", s.postProcessProviderId)
            .putString("post_process_base_url", s.postProcessBaseUrl)
            .putString("post_process_model", s.postProcessModel)
            .putString("post_process_prompt", s.postProcessPrompt)
            .apply()
    }

    companion object {
        private val settingsLock = Any()
        private const val PREFS_NAME = "handy_android_settings"
    }
}
