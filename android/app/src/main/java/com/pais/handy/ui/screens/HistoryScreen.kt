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
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pais.handy.data.HistoryEntry
import com.pais.handy.ui.HandyViewModel.Companion.localized

@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    playingEntryId: Long?,
    transcribingFileLabel: String?,
    onPickAudioFile: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onToggleSaved: (HistoryEntry) -> Unit,
    onTogglePlay: (HistoryEntry) -> Unit,
    onRetry: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var savedOnly by remember { mutableStateOf(false) }

    val filtered = remember(entries, query, savedOnly) {
        entries.filter { e ->
            (!savedOnly || e.saved) && (
                query.isBlank() ||
                    e.displayText.contains(query, ignoreCase = true) ||
                    e.transcriptionText.contains(query, ignoreCase = true) ||
                    (e.sourceName?.contains(query, ignoreCase = true) == true)
                )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Button(
            onClick = onPickAudioFile,
            enabled = transcribingFileLabel == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(localized("오디오 파일 불러와 전사하기", "Transcribe Audio File"))
        }

        if (transcribingFileLabel != null) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = localized("전사 중: $transcribingFileLabel", "Transcribing: $transcribingFileLabel"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(localized("기록 검색", "Search history")) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = savedOnly,
                onClick = { savedOnly = !savedOnly },
                label = { Text(localized("저장됨", "Saved")) },
            )
        }

        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = localized("아직 변환 기록이 없습니다.", "No transcriptions yet."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        isPlaying = playingEntryId == entry.id,
                        onCopy = { onCopy(entry.displayText) },
                        onShare = { onShare(entry.displayText) },
                        onToggleSaved = { onToggleSaved(entry) },
                        onTogglePlay = { onTogglePlay(entry) },
                        onRetry = { onRetry(entry) },
                        onDelete = { onDelete(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    isPlaying: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onToggleSaved: () -> Unit,
    onTogglePlay: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.sourceName ?: entry.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${entry.title} · ${entry.engineLabel} · ${entry.language.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleSaved, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (entry.saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = localized("저장", "Save"),
                        tint = if (entry.saved) colors.tertiary else colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.displayText,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!entry.postProcessedText.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = localized("원본: ${entry.transcriptionText}", "Raw: ${entry.transcriptionText}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!entry.audioFileName.isNullOrBlank()) {
                    IconButton(onClick = onTogglePlay, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = localized("재생", "Play"),
                            tint = colors.primary,
                        )
                    }
                    IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = localized("다시 전사", "Retry"),
                        )
                    }
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = localized("복사", "Copy"))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Share, contentDescription = localized("공유", "Share"))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = localized("삭제", "Delete"),
                        tint = colors.error,
                    )
                }
            }
        }
    }
}
