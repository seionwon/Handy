package com.pais.handy

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.pais.handy.data.EngineMode
import com.pais.handy.ime.EditorDictation
import com.pais.handy.data.ProviderCredentials
import com.pais.handy.data.ModelCatalog
import com.pais.handy.data.credentialScope
import com.pais.handy.data.providerOrigin
import com.pais.handy.ime.acceptsDictation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PrivacyTest {
    @Test fun providerCredentialsAreBoundToTransportHostPortAndProvider() {
        val initial = credentialScope("alpha", "https://EXAMPLE.org/v1")
        assertEquals(initial, credentialScope("alpha", "https://example.org:443/other"))
        assertNotEquals(initial, credentialScope("beta", "https://example.org/v1"))
        assertNotEquals(initial, credentialScope("alpha", "https://example.org:9443"))
        assertNotEquals(initial, credentialScope("alpha", "https://second.example.org"))
        for (url in listOf("http://localhost:11434", "http://127.0.0.1", "http://[::1]:8000"))
            assertNotNull(providerOrigin(url))
        for (url in listOf("http://example.org", "http://localhost.example.org", "https://user@example.org", "https://example.org?key=fixture"))
            assertNull(providerOrigin(url))
    }

    @Test fun voiceInputHonorsPrivateEditors() {
        val text = InputType.TYPE_CLASS_TEXT
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
            assertFalse(acceptsDictation(text or variation or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0))
        assertFalse(acceptsDictation(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
        assertFalse(acceptsDictation(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(acceptsDictation(InputType.TYPE_NULL, 0))
        assertTrue(acceptsDictation(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0))
        assertTrue(acceptsDictation(InputType.TYPE_CLASS_NUMBER, 0))
    }

    @Test fun resultsBelongOnlyToTheOriginalActiveEditor() {
        var focused = true
        val committed = mutableListOf<String>()
        val field = EditorDictation.Target({ focused }, committed::add)
        val original = EditorDictation.Session(field, EngineMode.SYSTEM_INSTANT)
        val restarted = EditorDictation.Session(field, EngineMode.SYSTEM_INSTANT)
        assertFalse(original.insertIfCurrent(restarted, "stale"))
        assertFalse(original.insertIfCurrent(null, "cancelled"))
        focused = false
        assertFalse(original.insertIfCurrent(original, "another editor"))
        focused = true
        assertTrue(restarted.insertIfCurrent(restarted, "한 문장 "))
        assertFalse(restarted.insertIfCurrent(restarted, "duplicate"))
        assertEquals(listOf("한 문장 "), committed)
    }

    @Test fun savedKeysUseRandomIvsAndAuthenticateTheProviderScope() {
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val first = ProviderCredentials.encrypt("provider-a", "fixture-value", key)
        val second = ProviderCredentials.encrypt("provider-a", "fixture-value", key)
        assertNotEquals(first, second)
        assertEquals("fixture-value", ProviderCredentials.decrypt("provider-a", first, key))
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            ProviderCredentials.decrypt("provider-b", first, key)
        }
    }

    @Test fun onlyExpectedModelContentIsAccepted() {
        val file = File.createTempFile("handy-model", ".bin")
        val model = ModelCatalog.models.first().copy(sizeBytes = 3, sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        try {
            file.writeText("abc")
            assertTrue(model.matches(file))
            file.writeText("abc1")
            assertFalse(model.matches(file))
            file.writeText("def")
            assertFalse(model.matches(file))
        } finally { file.delete() }
    }
}
