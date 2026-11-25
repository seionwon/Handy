package com.pais.handy.ui.screens

import androidx.compose.runtime.Composable

// The standard app has no cross-keyboard dictation service to configure.
@Composable
internal fun DictationButtonSettings(hasMicPermission: Boolean, onRequestMicPermission: () -> Unit) = Unit
