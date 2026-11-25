package com.pais.handy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pais.handy.BuildConfig
import com.pais.handy.data.HandyLanguages
import com.pais.handy.data.HandySettingsState
import com.pais.handy.data.LlmProviders
import com.pais.handy.data.ThemeMode
import com.pais.handy.ui.HandyViewModel.Companion.isKorean
import com.pais.handy.ui.HandyViewModel.Companion.localized

@Composable
fun SettingsScreen(
    settings: HandySettingsState,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onUpdateSettings: ((HandySettingsState) -> HandySettingsState) -> Unit,
    onSelectLlmProvider: (String) -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
) {
    val ko = isKorean()
    val scroll = rememberScrollState()
    var customWordsDraft by remember(settings.customWords) {
        mutableStateOf(settings.customWords.joinToString("\n"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DictationButtonSettings(hasMicPermission, onRequestMicPermission)

        // Keyboard IME Setup
        SettingsSection(title = localized("시스템 음성 키보드 (IME)", "System Voice Keyboard (IME)")) {
            Text(
                text = when {
                    imeSelected -> localized("Handy 음성 키보드가 현재 기본 키보드로 선택되어 있습니다.", "Handy Voice Keyboard is currently active.")
                    imeEnabled -> localized("Handy 음성 키보드가 활성화되었습니다. 키보드 전환 버튼(🌐)으로 바로 전환하세요.", "Handy Voice Keyboard is enabled. Switch to it from any input field.")
                    else -> localized("시스템 설정에서 'Handy 음성 키보드'를 켜면 모든 앱에서 음성으로 바로 입력할 수 있습니다.", "Enable Handy Voice Keyboard in Android Settings to dictate into any app.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenImeSettings) {
                    Text(localized("키보드 설정 열기", "Keyboard Settings"))
                }
                OutlinedButton(onClick = onShowImePicker) {
                    Text(localized("키보드 전환", "Switch Keyboard"))
                }
            }
        }

        // Language & Input behavior
        SettingsSection(title = localized("음성 및 입력", "Speech & Input")) {
            Text(
                text = localized("인식 언어", "Dictation Language"),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (lang in HandyLanguages.all) {
                    FilterChip(
                        selected = settings.selectedLanguage == lang.code,
                        onClick = { onUpdateSettings { it.copy(selectedLanguage = lang.code) } },
                        label = { Text(if (ko) lang.labelKo else lang.labelEn) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            ToggleRow(
                title = localized("누르고 말하기 (Push-to-Talk)", "Hold to Talk"),
                subtitle = localized("끄면 탭해서 시작/중지하는 토글 방식으로 동작합니다.", "Off uses tap-to-toggle recording."),
                checked = settings.holdToTalk,
                onCheckedChange = { v -> onUpdateSettings { it.copy(holdToTalk = v) } },
            )
            ToggleRow(
                title = localized("영어로 번역 (Whisper)", "Translate to English (Whisper)"),
                subtitle = localized("오프라인 Whisper 사용 시 음성을 영어로 번역해 출력합니다.", "Translates speech to English when using Offline Whisper."),
                checked = settings.translateToEnglish,
                onCheckedChange = { v -> onUpdateSettings { it.copy(translateToEnglish = v) } },
            )
            ToggleRow(
                title = localized("자동 클립보드 복사", "Auto-copy to Clipboard"),
                subtitle = localized("앱에서 음성 입력이 끝나면 자동으로 복사합니다.", "Automatically copy finished transcripts in the app."),
                checked = settings.autoCopyToClipboard,
                onCheckedChange = { v -> onUpdateSettings { it.copy(autoCopyToClipboard = v) } },
            )
            ToggleRow(
                title = localized("문장 끝 공백 추가", "Append Trailing Space"),
                subtitle = localized("연속해서 말할 때 단어 사이 공백을 자동으로 붙입니다.", "Adds a space after pasted text for continuous dictation."),
                checked = settings.appendTrailingSpace,
                onCheckedChange = { v -> onUpdateSettings { it.copy(appendTrailingSpace = v) } },
            )
            ToggleRow(
                title = localized("추임새(um/uh) 제거", "Filler Word Removal"),
                subtitle = localized("영어 추임새와 불필요한 공백을 정리합니다.", "Removes filler words like um/uh."),
                checked = settings.removeFillerWords,
                onCheckedChange = { v -> onUpdateSettings { it.copy(removeFillerWords = v) } },
            )
            ToggleRow(
                title = localized("진동 피드백", "Haptic Feedback"),
                subtitle = localized("녹음 시작·종료 시 짧은 진동을 울립니다.", "Subtle vibration on record start/stop."),
                checked = settings.hapticFeedback,
                onCheckedChange = { v -> onUpdateSettings { it.copy(hapticFeedback = v) } },
            )
        }

        // Custom words
        SettingsSection(title = localized("사용자 지정 단어 (Whisper 프롬프트)", "Custom Words (Whisper Prompt)")) {
            OutlinedTextField(
                value = customWordsDraft,
                onValueChange = { next ->
                    customWordsDraft = next
                    val list = next.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    onUpdateSettings { it.copy(customWords = list) }
                },
                minLines = 2,
                maxLines = 4,
                placeholder = { Text(localized("한 줄에 하나씩 고유명사·전문용어를 입력하세요", "One custom term per line")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // LLM Post-Processing
        SettingsSection(title = localized("AI 후처리 (선택)", "LLM Post-Processing (Optional)")) {
            ToggleRow(
                title = localized("AI 문장 다듬기 활성화", "Enable Post-Processing"),
                subtitle = localized("변환된 텍스트의 맞춤법·문장부호를 지정한 LLM으로 다듬습니다.", "Polishes punctuation and typos via your configured LLM."),
                checked = settings.postProcessEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(postProcessEnabled = v) } },
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (provider in LlmProviders.all) {
                    FilterChip(
                        selected = settings.postProcessProviderId == provider.id,
                        onClick = { onSelectLlmProvider(provider.id) },
                        label = { Text(provider.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.postProcessBaseUrl,
                isError = settings.postProcessKeyScope == null,
                supportingText = if (settings.postProcessKeyScope == null) {
                    { Text(localized("외부 서버에는 HTTPS 주소를 입력하세요.", "Use an HTTPS address for a remote server.")) }
                } else null,
                onValueChange = { v -> onUpdateSettings { it.copy(postProcessBaseUrl = v) } },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.postProcessModel,
                onValueChange = { v -> onUpdateSettings { it.copy(postProcessModel = v) } },
                label = { Text(localized("모델 이름", "Model")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.postProcessApiKey,
                enabled = settings.postProcessKeyScope != null,
                onValueChange = { v -> onUpdateSettings {
                    if (it.postProcessKeyScope == settings.postProcessKeyScope) it.copy(postProcessApiKey = v) else it
                } },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.postProcessPrompt,
                onValueChange = { v -> onUpdateSettings { it.copy(postProcessPrompt = v) } },
                label = { Text(localized("프롬프트", "Prompt")) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Theme & About
        SettingsSection(title = localized("화면 테마", "Appearance")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ThemeMode.entries) {
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> localized("시스템", "System")
                        ThemeMode.LIGHT -> localized("라이트", "Light")
                        ThemeMode.DARK -> localized("다크", "Dark")
                    }
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                        label = { Text(label) },
                    )
                }
            }
        }

        Text(
            text = "Handy Android v${BuildConfig.VERSION_NAME} · Offline Whisper.cpp + Voice IME",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
