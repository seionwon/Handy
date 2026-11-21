package com.pais.handy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pais.handy.data.EngineMode
import com.pais.handy.data.HandySettingsState
import com.pais.handy.data.ModelCatalog
import com.pais.handy.data.ModelDownloadProgress
import com.pais.handy.data.WhisperModelInfo
import com.pais.handy.ui.HandyViewModel.Companion.isKorean
import com.pais.handy.ui.HandyViewModel.Companion.localized

@Composable
fun ModelsScreen(
    settings: HandySettingsState,
    installedModelIds: Set<String>,
    downloads: Map<String, ModelDownloadProgress>,
    onSelectInstant: () -> Unit,
    onSelectWhisper: (String) -> Unit,
    onDownload: (WhisperModelInfo) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
) {
    val ko = isKorean()
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = localized("음성 인식 엔진", "Speech Recognition Engines"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = localized(
                    "즉시 인식으로 바로 시작하거나, 오프라인 Whisper 모델을 받아 100% 기기 내에서 전사할 수 있습니다.",
                    "Start right away with Instant Speech, or download a local Whisper GGML model for 100% offline on-device transcription.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        // Instant Android System Speech card
        item {
            val active = settings.engineMode == EngineMode.SYSTEM_INSTANT
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface,
                ),
                border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) colors.primary else colors.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = localized("Android 즉시 음성 인식", "Android Instant Speech"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = localized(
                                "다운로드 없이 실시간 부분 인식 지원 (0 MB)",
                                "Real-time partial results with zero download (0 MB)",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (active) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(localized("사용 중", "Active"), color = colors.secondary, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        OutlinedButton(onClick = onSelectInstant) {
                            Text(localized("선택", "Use"))
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = localized("오프라인 Whisper 모델 (GGML · 기기 내 실행)", "Offline Whisper Models (GGML · On-Device)"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
            )
        }

        items(ModelCatalog.models, key = { it.id }) { model ->
            val installed = installedModelIds.contains(model.id)
            val active = settings.engineMode == EngineMode.WHISPER_OFFLINE && settings.selectedModelId == model.id
            val progress = downloads[model.id]

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface,
                ),
                border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) colors.primary else colors.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (model.recommended) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = colors.secondaryContainer,
                                    ) {
                                        Text(
                                            text = localized("추천", "Recommended"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = if (ko) model.descriptionKo else model.descriptionEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${model.sizeMb} MB · ${model.speedGrade} · ${model.accuracyGrade}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }

                    if (progress != null) {
                        Spacer(Modifier.height(10.dp))
                        if (progress.error != null) {
                            Text(
                                text = progress.error,
                                color = colors.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { onDownload(model) }) {
                                Text(localized("다시 시도", "Retry"))
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { progress.fraction },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${(progress.fraction * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                IconButton(onClick = { onCancelDownload(model.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = localized("취소", "Cancel"), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (installed) {
                                IconButton(onClick = { onDeleteModel(model.id) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = localized("삭제", "Delete"),
                                        tint = colors.error,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                if (active) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(localized("사용 중", "Active"), color = colors.secondary, style = MaterialTheme.typography.labelLarge)
                                    }
                                } else {
                                    Button(onClick = { onSelectWhisper(model.id) }) {
                                        Text(localized("선택", "Activate"))
                                    }
                                }
                            } else {
                                OutlinedButton(onClick = { onDownload(model) }) {
                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(localized("다운로드 (${model.sizeMb} MB)", "Download (${model.sizeMb} MB)"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
