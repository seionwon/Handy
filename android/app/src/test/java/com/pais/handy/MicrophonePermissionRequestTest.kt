package com.pais.handy

import com.pais.handy.ui.MicrophoneAction
import com.pais.handy.ui.MicrophonePermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePermissionRequestTest {
    @Test fun repeatedTapsOpenOnePromptAndStartOneRecording() {
        val mic = MicrophonePermissionRequest()
        var prompts = 0
        repeat(2) { mic.request(MicrophoneAction.RECORD) { prompts++ } }
        assertEquals(1, prompts)
        assertTrue(mic.finish(granted = true))
        assertFalse(mic.finish(granted = true))
    }

    @Test fun denialAndOneTimeAccessCanBeRequestedAgain() {
        val mic = MicrophonePermissionRequest()
        var prompts = 0
        mic.request(MicrophoneAction.RECORD) { prompts++ }
        assertFalse(mic.finish(granted = false))
        mic.request(MicrophoneAction.RECORD) { prompts++ }
        assertTrue(mic.finish(granted = true))
        mic.request(MicrophoneAction.RECORD) { prompts++ }
        assertEquals(3, prompts)
    }

    @Test fun restoringAPendingRequestPreservesTheUserAction() {
        for (action in MicrophoneAction.entries) {
            val mic = MicrophonePermissionRequest()
            mic.request(action) {}
            val restored = MicrophonePermissionRequest(mic.pendingAction)
            restored.request(MicrophoneAction.RECORD) { error("A prompt is already open") }
            assertEquals(action == MicrophoneAction.RECORD, restored.finish(granted = true))
        }
        assertFalse(MicrophonePermissionRequest().finish(granted = true))
    }
}
