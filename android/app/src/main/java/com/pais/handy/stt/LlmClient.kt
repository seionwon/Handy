package com.pais.handy.stt

import com.pais.handy.data.HandySettingsState
import com.pais.handy.data.LlmApiFormat
import com.pais.handy.data.LlmProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI & Anthropic compatible LLM post-processing client.
 * Explicitly disables HTTP redirects (instanceFollowRedirects = false) so custom
 * authentication headers (x-api-key / Authorization) are never forwarded to another origin.
 */
object LlmClient {
    internal fun buildRequestPayload(
        format: LlmApiFormat,
        model: String,
        prompt: String,
        transcript: String,
    ): JSONObject = when (format) {
        LlmApiFormat.ANTHROPIC -> JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("system", prompt)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", transcript)
                    },
                ),
            )
        }
        LlmApiFormat.OPENAI -> JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", prompt)
                        },
                    )
                    .put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", transcript)
                        },
                    ),
            )
        }
    }

    internal fun parseResponseText(format: LlmApiFormat, body: String): String? {
        val json = JSONObject(body)
        val raw = when (format) {
            LlmApiFormat.ANTHROPIC -> {
                val blocks = json.optJSONArray("content") ?: return null
                val sb = StringBuilder()
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        sb.append(block.optString("text"))
                    }
                }
                sb.toString()
            }
            LlmApiFormat.OPENAI -> {
                val choices = json.optJSONArray("choices") ?: return null
                if (choices.length() == 0) return null
                choices.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?: return null
            }
        }
        return TextPostProcessor.cleanPostProcessed(raw)
    }

    internal fun endpointUrl(baseUrl: String, format: LlmApiFormat): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when (format) {
            LlmApiFormat.ANTHROPIC -> if (trimmed.endsWith("/messages")) trimmed else "$trimmed/messages"
            LlmApiFormat.OPENAI -> if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }
    }

    suspend fun postProcess(settings: HandySettingsState, transcript: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val preset = LlmProviders.byId(settings.postProcessProviderId)
                val model = settings.postProcessModel.trim()
                val prompt = settings.postProcessPrompt.trim()
                require(model.isNotEmpty()) { "LLM model is not configured" }
                require(prompt.isNotEmpty()) { "Post-processing prompt is empty" }

                val url = endpointUrl(settings.postProcessBaseUrl, preset.format)
                val payload = buildRequestPayload(preset.format, model, prompt, transcript)

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    // Never forward authentication headers across redirects.
                    instanceFollowRedirects = false
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 45_000
                    setRequestProperty("Content-Type", "application/json")
                    val key = settings.postProcessApiKey.trim()
                    if (key.isNotEmpty()) {
                        if (preset.format == LlmApiFormat.ANTHROPIC) {
                            setRequestProperty("x-api-key", key)
                            setRequestProperty("anthropic-version", "2023-06-01")
                        } else {
                            setRequestProperty("Authorization", "Bearer $key")
                        }
                    }
                }

                try {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(payload.toString())
                    }
                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) {
                        throw IllegalStateException("LLM API error ($status): ${responseBody.take(180)}")
                    }
                    parseResponseText(preset.format, responseBody)
                        ?: throw IllegalStateException("LLM returned an empty response")
                } finally {
                    conn.disconnect()
                }
            }
        }
}
