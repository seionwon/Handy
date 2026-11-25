package com.pais.handy.ime

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.pais.handy.R
import com.pais.handy.data.HandySettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class DictationButtonService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dictation: EditorDictation
    private lateinit var keyguard: KeyguardManager
    private var controller: AccessibilityButtonController? = null
    private var toast: Toast? = null

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            dictation.pressAccessibilityButton(inputMethod, keyguard::isKeyguardLocked) {
                showStatus(getString(it))
            }
        }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = dictation.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        dictation = EditorDictation(
            this, scope, HandySettingsRepository(this), finishOnPause = true,
            onStatus = ::showStatus, onInserted = { showStatus(null) },
        )
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller?.unregisterAccessibilityButtonCallback(buttonCallback)
        controller = accessibilityButtonController.also { it.registerAccessibilityButtonCallback(buttonCallback) }
    }

    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) = dictation.cancel()
        override fun onFinishInput() = dictation.cancel()
    }

    private fun showStatus(message: String?) {
        toast?.cancel()
        toast = message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).also(Toast::show) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = dictation.cancel()

    override fun onUnbind(intent: Intent?): Boolean {
        dictation.cancel()
        controller?.unregisterAccessibilityButtonCallback(buttonCallback)
        controller = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        controller?.unregisterAccessibilityButtonCallback(buttonCallback)
        dictation.destroy()
        scope.cancel()
        showStatus(null)
        super.onDestroy()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun EditorDictation.pressAccessibilityButton(
    input: InputMethod?,
    isLocked: () -> Boolean,
    onUnavailable: (Int) -> Unit,
) {
    if (recording) {
        stop()
        return
    }
    if (busy) return
    val field = input?.currentInputEditorInfo
    val connection = input?.currentInputConnection
    if (input == null || !input.currentInputStarted || field == null || connection == null ||
        isLocked() || field.inputType == InputType.TYPE_NULL) {
        onUnavailable(R.string.dictation_choose_field)
        return
    }
    if (!acceptsDictation(field.inputType, field.imeOptions)) {
        onUnavailable(R.string.ime_private_field)
        return
    }
    // Android returns a new connection wrapper on each lookup. Target the original
    // connection and cancel from the service on every editor start, restart or finish.
    start(EditorDictation.Target(
        isFocused = { input.currentInputStarted && input.currentInputEditorInfo === field && !isLocked() },
        insert = { text ->
            connection.commitText(text, 1, null)
            true // AccessibilityInputConnection dispatches the edit without a return value.
        },
    ))
}
