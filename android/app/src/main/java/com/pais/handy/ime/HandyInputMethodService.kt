package com.pais.handy.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import com.pais.handy.MainActivity
import com.pais.handy.R
import com.pais.handy.data.EngineMode
import com.pais.handy.data.HandyLanguages
import com.pais.handy.data.HandySettingsRepository
import com.pais.handy.data.ModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class HandyInputMethodService : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepo: HandySettingsRepository
    private lateinit var dictation: EditorDictation
    private var acceptsVoice = false

    private var engineChip: TextView? = null
    private var langChip: TextView? = null
    private var aiChip: TextView? = null
    private var statusText: TextView? = null
    private var micBtn: Button? = null
    private var cancelBtn: Button? = null

    private var backspaceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = HandySettingsRepository(this)
        dictation = EditorDictation(
            this, serviceScope, settingsRepo,
            onStatus = { message ->
                if (dictation.recording) tickHaptic()
                updateRecordingUi(dictation.recording, message ?: idlePrompt())
            },
            onPartial = { statusText?.text = it },
            onInserted = {
                tickHaptic()
                updateRecordingUi(false, it)
            },
        )
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.handy_keyboard_view, null)
        engineChip = root.findViewById(R.id.ime_engine_chip)
        langChip = root.findViewById(R.id.ime_lang_chip)
        aiChip = root.findViewById(R.id.ime_ai_chip)
        statusText = root.findViewById(R.id.ime_status_text)
        micBtn = root.findViewById(R.id.ime_mic_btn)
        cancelBtn = root.findViewById(R.id.ime_cancel_btn)

        engineChip?.setOnClickListener {
            if (dictation.busy) return@setOnClickListener
            val current = settingsRepo.current()
            val next = if (current.engineMode == EngineMode.SYSTEM_INSTANT) {
                EngineMode.WHISPER_OFFLINE
            } else {
                EngineMode.SYSTEM_INSTANT
            }
            settingsRepo.update { it.copy(engineMode = next) }
            refreshChips()
        }

        langChip?.setOnClickListener {
            if (dictation.busy) return@setOnClickListener
            val cycle = listOf("auto", "ko", "en", "ja", "zh")
            val cur = settingsRepo.current().selectedLanguage
            val next = cycle[(cycle.indexOf(cur) + 1) % cycle.size]
            settingsRepo.update { it.copy(selectedLanguage = next) }
            refreshChips()
        }

        aiChip?.setOnClickListener {
            if (dictation.busy) return@setOnClickListener
            settingsRepo.update { it.copy(postProcessEnabled = !it.postProcessEnabled) }
            refreshChips()
        }

        root.findViewById<TextView>(R.id.ime_open_app)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        cancelBtn?.setOnClickListener {
            dictation.cancel()
        }

        micBtn?.setOnTouchListener { view, event ->
            val holdMode = settingsRepo.current().holdToTalk
            if (!holdMode) {
                if (event.action == MotionEvent.ACTION_UP) {
                    view.performClick()
                    toggleDictation()
                }
                true
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startDictation()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dictation.cancel()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dictation.recording) {
                            dictation.stop()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        root.findViewById<Button>(R.id.ime_switch_btn)?.setOnClickListener {
            val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                false
            }
            if (!switched) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        root.findViewById<Button>(R.id.ime_space_btn)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
            tickHaptic()
        }

        val backspaceBtn = root.findViewById<Button>(R.id.ime_backspace_btn)
        backspaceBtn?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    deleteCharBeforeCursor()
                    backspaceJob?.cancel()
                    backspaceJob = serviceScope.launch {
                        delay(380)
                        while (isActive) {
                            deleteCharBeforeCursor()
                            delay(55)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    backspaceJob?.cancel()
                    backspaceJob = null
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        root.findViewById<Button>(R.id.ime_enter_btn)?.setOnClickListener {
            val editorInfo = currentInputEditorInfo
            val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE
            val handled = when (action) {
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_DONE -> currentInputConnection?.performEditorAction(action) == true
                else -> false
            }
            if (!handled) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            tickHaptic()
        }

        refreshChips()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        dictation.cancel()
        super.onStartInput(attribute, restarting)
        acceptsVoice = attribute != null && acceptsDictation(attribute.inputType, attribute.imeOptions)
    }

    override fun onFinishInput() {
        acceptsVoice = false
        dictation.cancel()
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        acceptsVoice = info != null && acceptsDictation(info.inputType, info.imeOptions)
        refreshChips()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        backspaceJob?.cancel()
        acceptsVoice = false
        dictation.cancel()
    }

    override fun onDestroy() {
        dictation.destroy()
        backspaceJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun deleteCharBeforeCursor() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun toggleDictation() {
        if (dictation.recording) dictation.stop() else startDictation()
    }

    private fun startDictation() {
        val target = currentInputConnection ?: return
        dictation.start(EditorDictation.Target(
            isFocused = { acceptsVoice && currentInputConnection === target },
            insert = { target.commitText(it, 1) },
        ))
    }

    private fun refreshChips() {
        micBtn?.isEnabled = acceptsVoice
        val s = settingsRepo.current()
        val isKo = Locale.getDefault().language == "ko"
        engineChip?.text = if (s.engineMode == EngineMode.WHISPER_OFFLINE) {
            ModelCatalog.byId(s.selectedModelId)?.name?.replace("Whisper ", "Whisper ")
                ?: "Whisper"
        } else {
            if (isKo) "즉시 인식" else "Instant"
        }
        val lang = HandyLanguages.byCode(s.selectedLanguage)
        langChip?.text = if (isKo) lang.labelKo else lang.labelEn
        aiChip?.text = getString(if (s.postProcessEnabled) R.string.ime_ai_on else R.string.ime_ai_off)
        if (!dictation.busy) {
            micBtn?.text = idlePrompt()
            statusText?.text = idlePrompt()
        }
    }

    private fun idlePrompt(): String {
        if (!acceptsVoice) return getString(R.string.ime_private_field)
        val hold = settingsRepo.current().holdToTalk
        return getString(if (hold) R.string.ime_hold_to_speak else R.string.ime_tap_to_speak)
    }

    private fun updateRecordingUi(recording: Boolean, status: String) {
        statusText?.text = status
        cancelBtn?.visibility = if (recording) View.VISIBLE else View.GONE
        micBtn?.setBackgroundResource(
            if (recording) R.drawable.bg_ime_mic_active else R.drawable.bg_ime_mic_idle,
        )
        micBtn?.text = if (recording) getString(R.string.ime_listening) else idlePrompt()
    }

    private fun tickHaptic() {
        if (!settingsRepo.current().hapticFeedback) return
        micBtn?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
