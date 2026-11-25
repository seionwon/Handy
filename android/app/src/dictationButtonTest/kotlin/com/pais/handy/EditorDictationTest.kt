package com.pais.handy

import android.Manifest
import android.accessibilityservice.InputMethod
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import com.pais.handy.audio.AudioCapture
import com.pais.handy.data.EngineMode
import com.pais.handy.data.HandySettingsRepository
import com.pais.handy.data.HandySettingsState
import com.pais.handy.data.HistoryDatabase
import com.pais.handy.data.ModelDownloader
import com.pais.handy.ime.EditorDictation
import com.pais.handy.ime.pressAccessibilityButton
import com.pais.handy.stt.SystemSpeechEngine
import com.pais.handy.stt.WhisperEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EditorDictationTest {
    private val context = mockk<Context>()
    private val speech = mockk<SystemSpeechEngine>(relaxed = true)
    private val history = mockk<HistoryDatabase>(relaxed = true)
    private val settings = mockk<HandySettingsRepository>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val results = mutableListOf<(String) -> Unit>()
    private val speechEnds = mutableListOf<() -> Unit>()
    private val field = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
    private val connection = mockk<InputMethod.AccessibilityInputConnection>(relaxed = true)
    private val input = mockk<InputMethod> {
        every { currentInputStarted } returns true
        every { currentInputEditorInfo } returns field
        every { currentInputConnection } returns connection
    }
    private lateinit var dictation: EditorDictation

    @Before fun setUp() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) } returns PackageManager.PERMISSION_GRANTED
        every { context.getString(any()) } returns "status"
        every { speech.start(any(), any(), any(), any(), any(), any()) } answers {
            results += arg<(String) -> Unit>(3)
            speechEnds += arg<() -> Unit>(5)
        }
        every { settings.current() } returns HandySettingsState()
        dictation = EditorDictation(context, scope, settings, finishOnPause = true, onStatus = {}, speech = speech, historyDb = history)
    }

    @After fun tearDown() {
        dictation.destroy()
        scope.cancel()
        unmockkStatic(ContextCompat::class)
    }

    @Test fun oneStartThenSpeechEndingInsertsOnceAtTheSelection() {
        val editor = StringBuilder("앞 선택 뒤")
        every { connection.commitText(any(), 1, null) } answers {
            editor.replace(2, 5, firstArg<CharSequence>().toString())
            Unit
        }
        dictation.pressAccessibilityButton(input, { false }) { error("Unexpected status $it") }
        assertTrue(dictation.recording)
        speechEnds.single().invoke()
        assertFalse(dictation.recording)
        results.single().invoke("한 문장")
        results.single().invoke("duplicate")
        assertEquals("앞 한 문장 뒤", editor.toString())
        verify(exactly = 1) { connection.commitText("한 문장 ", 1, null) }
        verify(exactly = 0) { speech.stopListening() }
        verify(timeout = 2_000, exactly = 1) { history.insert(any(), null, null, "auto", any(), "한 문장", null) }
    }

    @Test fun cancelThenStartInAnotherEditorIgnoresLateCallbacks() {
        val first = mutableListOf<String>()
        val next = mutableListOf<String>()
        dictation.start(EditorDictation.Target({ true }, first::add))
        dictation.cancel()
        dictation.start(EditorDictation.Target({ true }, next::add))
        speechEnds.first().invoke()
        results.first().invoke("late")
        assertTrue(dictation.recording)
        dictation.stop()
        verify(exactly = 1) { speech.stopListening() }
        results.last().invoke("다음")
        assertTrue(first.isEmpty())
        assertEquals(listOf("다음 "), next)
    }

    @Test fun noMicrophonePermissionOrFocusDoesNotStartTheRecognizer() {
        val messages = mutableListOf<Int>()
        dictation.pressAccessibilityButton(null, { false }, messages::add)
        dictation.pressAccessibilityButton(input, { true }, messages::add)
        assertEquals(listOf(R.string.dictation_choose_field, R.string.dictation_choose_field), messages)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) } returns PackageManager.PERMISSION_DENIED
        dictation.pressAccessibilityButton(input, { false }, messages::add)
        assertFalse(dictation.busy)
        verify(exactly = 0) { speech.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun changedEditorCannotReceivePendingText() {
        dictation.pressAccessibilityButton(input, { false }) { error("Unexpected status $it") }
        every { input.currentInputEditorInfo } returns EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        results.single().invoke("late")
        verify(exactly = 0) { connection.commitText(any(), any(), null) }
    }

    @Test fun oneButtonPressEndsOfflineCaptureOnSilenceAndInsertsTheTranscript() {
        val dir = Files.createTempDirectory("handy-dictation-test").toFile()
        mockkConstructor(AudioCapture::class)
        mockkObject(ModelDownloader, WhisperEngine)
        mockkStatic(SystemClock::class)
        try {
            every { settings.current() } returns HandySettingsState(engineMode = EngineMode.WHISPER_OFFLINE)
            val model = File(dir, "model.bin")
            every { ModelDownloader.get(context) } returns mockk {
                every { installedModelFile(any()) } returns model
            }
            every { history.recordingsDir } returns dir
            every { anyConstructed<AudioCapture>().start(scope) } returns Result.success(Unit)
            val levels = mockk<StateFlow<Float>>()
            every { levels.value } returnsMany listOf(0.2f, 0f)
            every { anyConstructed<AudioCapture>().level } returns levels
            every { SystemClock.elapsedRealtime() } returnsMany listOf(0L, 100L, 1_600L)
            val samples = FloatArray(4_096)
            coEvery { anyConstructed<AudioCapture>().stopAndGetSamples() } returns samples
            coEvery { WhisperEngine.transcribe(model, samples, "auto", false, emptyList()) } returns "오프라인"

            dictation.pressAccessibilityButton(input, { false }) { error("Unexpected status $it") }
            verify(timeout = 3_000, exactly = 1) { connection.commitText("오프라인 ", 1, null) }
            verify(timeout = 2_000, exactly = 1) { history.insert(any(), any(), null, "auto", any(), "오프라인", null) }
            verify(exactly = 0) { speech.start(any(), any(), any(), any(), any(), any()) }
        } finally {
            dictation.cancel()
            unmockkStatic(SystemClock::class)
            unmockkObject(ModelDownloader, WhisperEngine)
            unmockkConstructor(AudioCapture::class)
            dir.deleteRecursively()
        }
    }
}
