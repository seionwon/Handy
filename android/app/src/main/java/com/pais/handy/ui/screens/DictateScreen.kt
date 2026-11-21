package com.pais.handy.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pais.handy.data.EngineMode
import com.pais.handy.data.HandyLanguages
import com.pais.handy.data.HandySettingsState
import com.pais.handy.data.ModelCatalog
import com.pais.handy.ui.DictationPhase
import com.pais.handy.ui.HandyUiState
import com.pais.handy.ui.HandyViewModel.Companion.isKorean
import com.pais.handy.ui.HandyViewModel.Companion.localized

@Composable
fun DictateScreen(
    ui: HandyUiState,
    settings: HandySettingsState,
    hasMicPermission: Boolean,
    installedModelIds: Set<String>,
    onRequestMicPermission: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onCancelDictation: () -> Unit,
    onSwitchEngine: (EngineMode) -> Unit,
    onCycleLanguage: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onPolish: () -> Unit,
    onClear: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onGoToModels: () -> Unit,
) {
    val ko = isKorean()
    val scroll = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    val isRecording = ui.phase == DictationPhase.RECORDING
    val isProcessing = ui.phase == DictationPhase.TRANSCRIBING || ui.phase == DictationPhase.POST_PROCESSING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Voice Keyboard quick-setup banner
        if (!ui.imeEnabled || !ui.imeSelected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primaryContainer.copy(alpha = 0.55f)),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Keyboard,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = localized(
                                "다른 앱에서도 Handy 음성 키보드 사용하기",
                                "Use Handy Voice Keyboard in any app",
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = localized(
                            "카톡·메모·브라우저 등 어디서나 키보드 전환 버튼으로 바로 음성 입력할 수 있습니다.",
                            "Enable Handy Voice Keyboard to dictate directly into messaging, notes, and browsers.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!ui.imeEnabled) {
                            Button(onClick = onOpenImeSettings) {
                                Text(localized("키보드 활성화", "Enable Keyboard"))
                            }
                        }
                        OutlinedButton(onClick = onShowImePicker) {
                            Text(localized("키보드 선택", "Switch Keyboard"))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Engine & language selector pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val engineLabel = if (settings.engineMode == EngineMode.WHISPER_OFFLINE) {
                ModelCatalog.byId(settings.selectedModelId)?.name ?: "Whisper"
            } else {
                localized("즉시 음성 인식", "Instant Speech")
            }
            AssistChip(
                onClick = {
                    val next = if (settings.engineMode == EngineMode.SYSTEM_INSTANT) {
                        if (installedModelIds.isEmpty()) {
                            onGoToModels()
                            return@AssistChip
                        }
                        EngineMode.WHISPER_OFFLINE
                    } else {
                        EngineMode.SYSTEM_INSTANT
                    }
                    onSwitchEngine(next)
                },
                label = { Text(engineLabel) },
            )
            val lang = HandyLanguages.byCode(settings.selectedLanguage)
            AssistChip(
                onClick = onCycleLanguage,
                label = { Text(if (ko) lang.labelKo else lang.labelEn) },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Microphone permission gate
        if (!hasMicPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = localized("마이크 권한이 필요합니다", "Microphone permission required"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = localized(
                            "음성을 녹음해 텍스트로 변환하려면 마이크 접근을 허용해주세요.",
                            "Allow microphone access so Handy can listen and transcribe your voice.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestMicPermission) {
                        Text(localized("마이크 권한 허용", "Allow Microphone"))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Animated Voice Orb
        val pulseScale by animateFloatAsState(
            targetValue = if (isRecording) 1.0f + (ui.liveLevel * 0.16f) else 1.0f,
            label = "micPulse",
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(188.dp),
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(164.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.22f)),
                )
            }

            Surface(
                modifier = Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .pointerInput(settings.holdToTalk, ui.phase, hasMicPermission) {
                        detectTapGestures(
                            onPress = {
                                if (!hasMicPermission) {
                                    onRequestMicPermission()
                                    return@detectTapGestures
                                }
                                if (settings.holdToTalk) {
                                    onStartDictation()
                                    tryAwaitRelease()
                                    onStopDictation()
                                }
                            },
                            onTap = {
                                if (!hasMicPermission) {
                                    onRequestMicPermission()
                                    return@detectTapGestures
                                }
                                if (!settings.holdToTalk) {
                                    when (ui.phase) {
                                        DictationPhase.IDLE -> onStartDictation()
                                        DictationPhase.RECORDING -> onStopDictation()
                                        else -> {}
                                    }
                                }
                            },
                        )
                    },
                shape = CircleShape,
                color = if (isRecording) colors.error else colors.primary,
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        isProcessing -> CircularProgressIndicator(
                            color = colors.onPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp),
                        )
                        isRecording -> Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = localized("중지", "Stop"),
                            tint = colors.onError,
                            modifier = Modifier.size(48.dp),
                        )
                        else -> Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = localized("녹음", "Record"),
                            tint = colors.onPrimary,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Audio level visualizer bars while recording
        if (isRecording) {
            AudioLevelBars(level = ui.liveLevel, modifier = Modifier.height(28.dp))
            Spacer(Modifier.height(6.dp))
        }

        val statusLabel = when (ui.phase) {
            DictationPhase.IDLE -> if (settings.holdToTalk) {
                localized("버튼을 누르고 있는 동안 말하세요", "Press and hold to speak")
            } else {
                localized("탭해서 녹음을 시작하세요", "Tap to start speaking")
            }
            DictationPhase.RECORDING -> {
                val secs = ui.elapsedMs / 1000L
                if (secs > 0) {
                    localized("듣는 중… ${secs}초", "Listening… ${secs}s")
                } else {
                    localized("듣는 중…", "Listening…")
                }
            }
            DictationPhase.TRANSCRIBING -> localized("음성을 텍스트로 변환하는 중…", "Transcribing…")
            DictationPhase.POST_PROCESSING -> localized("AI로 문장을 다듬는 중…", "Polishing with AI…")
        }

        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        if (isRecording) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancelDictation,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(localized("취소", "Cancel"))
            }
        }

        // Error card
        ui.errorMessage?.let { err ->
            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.error.copy(alpha = 0.12f)),
            ) {
                Text(
                    text = err,
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Live partial or final transcript card
        val activeText = when {
            ui.partialText.isNotBlank() -> ui.partialText
            !ui.latestPostProcessedText.isNullOrBlank() -> ui.latestPostProcessedText
            ui.latestRawText.isNotBlank() -> ui.latestRawText
            else -> ""
        }

        if (activeText.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (ui.partialText.isNotBlank()) {
                                localized("실시간 인식 중", "Live transcript")
                            } else if (!ui.latestPostProcessedText.isNullOrBlank()) {
                                localized("AI 다듬기 결과", "Polished transcript")
                            } else {
                                localized("변환 결과", "Transcript")
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                        if (ui.partialText.isBlank()) {
                            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = localized("지우기", "Clear"),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = activeText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!ui.latestPostProcessedText.isNullOrBlank() && ui.latestRawText.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = localized("원본: ${ui.latestRawText}", "Raw: ${ui.latestRawText}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (ui.partialText.isBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onCopy(activeText) }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(localized("복사", "Copy"))
                            }
                            OutlinedButton(onClick = { onShare(activeText) }) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(localized("공유", "Share"))
                            }
                            if (settings.postProcessApiKey.isNotBlank()) {
                                OutlinedButton(onClick = onPolish) {
                                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("AI")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioLevelBars(level: Float, modifier: Modifier = Modifier) {
    val multipliers = listOf(0.45f, 0.8f, 1.0f, 0.7f, 0.95f, 0.65f, 0.4f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (m in multipliers) {
            val h by animateFloatAsState(
                targetValue = (6f + level * 22f * m).coerceIn(6f, 28f),
                label = "bar",
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
