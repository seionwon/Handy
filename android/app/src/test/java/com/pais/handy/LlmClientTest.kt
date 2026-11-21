package com.pais.handy

import com.pais.handy.data.LlmApiFormat
import com.pais.handy.stt.LlmClient
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmClientTest {
    @Test
    fun parsesOpenAiAndAnthropicResponses() {
        val openai = """{"choices":[{"message":{"content":"Polished text."}}]}"""
        assertEquals("Polished text.", LlmClient.parseResponseText(LlmApiFormat.OPENAI, openai))

        val anthropic = """{"content":[{"type":"text","text":"다듬어진 문장."}]}"""
        assertEquals("다듬어진 문장.", LlmClient.parseResponseText(LlmApiFormat.ANTHROPIC, anthropic))
    }

    @Test
    fun resolvesEndpointsWithoutDuplicatingPaths() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmClient.endpointUrl("https://api.openai.com/v1/", LlmApiFormat.OPENAI),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            LlmClient.endpointUrl("https://api.anthropic.com/v1", LlmApiFormat.ANTHROPIC),
        )
    }
}
