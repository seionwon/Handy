package com.pais.handy.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pais.handy.ui.HandyViewModel.Companion.localized
import com.pais.handy.ui.screens.DictateScreen
import com.pais.handy.ui.screens.HistoryScreen
import com.pais.handy.ui.screens.ModelsScreen
import com.pais.handy.ui.screens.SettingsScreen
import com.pais.handy.ui.theme.HandyTheme

private enum class HandyTab(val icon: ImageVector) {
    DICTATE(Icons.Rounded.Mic),
    HISTORY(Icons.Rounded.History),
    MODELS(Icons.Rounded.Memory),
    SETTINGS(Icons.Rounded.Settings);

    fun label(): String = when (this) {
        DICTATE -> localized("음성 입력", "Dictate")
        HISTORY -> localized("기록", "History")
        MODELS -> localized("모델", "Models")
        SETTINGS -> localized("설정", "Settings")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandyApp(viewModel: HandyViewModel) {
    val settings by viewModel.settings.collectAsState()
    val ui by viewModel.ui.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val installedIds by viewModel.installedModelIds.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTab by rememberSaveable { mutableStateOf(HandyTab.DICTATE) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            currentTab = HandyTab.HISTORY
            viewModel.transcribeAudioUri(uri)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toast.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                viewModel.refreshImeStatus()
                viewModel.refreshHistory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HandyTheme(themeMode = settings.themeMode) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Handy",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    for (tab in HandyTab.entries) {
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label()) },
                            label = { Text(tab.label()) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentTab) {
                    HandyTab.DICTATE -> DictateScreen(
                        ui = ui,
                        settings = settings,
                        hasMicPermission = hasMicPermission,
                        installedModelIds = installedIds,
                        onRequestMicPermission = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onStartDictation = viewModel::startDictation,
                        onStopDictation = viewModel::stopDictation,
                        onCancelDictation = viewModel::cancelDictation,
                        onSwitchEngine = { mode ->
                            viewModel.settingsRepo.update { it.copy(engineMode = mode) }
                        },
                        onCycleLanguage = {
                            val cycle = listOf("auto", "ko", "en", "ja", "zh")
                            val next = cycle[(cycle.indexOf(settings.selectedLanguage) + 1) % cycle.size]
                            viewModel.settingsRepo.update { it.copy(selectedLanguage = next) }
                        },
                        onCopy = { viewModel.copyTextToClipboard(it) },
                        onShare = viewModel::shareText,
                        onPolish = viewModel::rerunPostProcessOnLatest,
                        onClear = viewModel::clearLatest,
                        onOpenImeSettings = viewModel::openImeSettings,
                        onShowImePicker = viewModel::showImePicker,
                        onGoToModels = { currentTab = HandyTab.MODELS },
                    )
                    HandyTab.HISTORY -> HistoryScreen(
                        entries = ui.history,
                        playingEntryId = ui.playingEntryId,
                        transcribingFileLabel = ui.transcribingFileLabel,
                        onPickAudioFile = {
                            filePickerLauncher.launch(arrayOf("audio/*", "video/ogg", "video/webm"))
                        },
                        onCopy = { viewModel.copyTextToClipboard(it) },
                        onShare = viewModel::shareText,
                        onToggleSaved = viewModel::toggleSaved,
                        onTogglePlay = viewModel::togglePlayback,
                        onRetry = viewModel::retryHistoryEntry,
                        onDelete = viewModel::deleteEntry,
                    )
                    HandyTab.MODELS -> ModelsScreen(
                        settings = settings,
                        installedModelIds = installedIds,
                        downloads = downloads,
                        onSelectInstant = viewModel::selectInstantEngine,
                        onSelectWhisper = viewModel::selectWhisperModel,
                        onDownload = viewModel::downloadModel,
                        onCancelDownload = viewModel::cancelDownload,
                        onDeleteModel = viewModel::deleteModel,
                    )
                    HandyTab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        imeEnabled = ui.imeEnabled,
                        imeSelected = ui.imeSelected,
                        onUpdateSettings = viewModel.settingsRepo::update,
                        onSelectLlmProvider = viewModel::selectLlmProvider,
                        onOpenImeSettings = viewModel::openImeSettings,
                        onShowImePicker = viewModel::showImePicker,
                    )
                }
            }
        }
    }
}
