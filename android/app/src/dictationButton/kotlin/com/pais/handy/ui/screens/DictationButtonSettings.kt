package com.pais.handy.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pais.handy.R
import com.pais.handy.ime.DictationButtonService

@Composable
internal fun DictationButtonSettings(hasMicPermission: Boolean, onRequestMicPermission: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        enabled = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
            val service = it.resolveInfo.serviceInfo
            service.packageName == context.packageName && service.name == DictationButtonService::class.java.name
        } == true
        onPauseOrDispose {}
    }
    SettingsSection(title = stringResource(R.string.dictation_button_summary)) {
        val message = when {
            settingsUnavailable -> R.string.dictation_settings_unavailable
            enabled -> R.string.dictation_button_enabled
            else -> R.string.dictation_button_setup
        }
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            if (hasMicPermission) {
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    settingsUnavailable = false
                } catch (_: ActivityNotFoundException) {
                    settingsUnavailable = true
                }
            } else {
                onRequestMicPermission()
            }
        }) {
            Text(stringResource(if (hasMicPermission) R.string.dictation_button_settings else R.string.dictation_allow_mic))
        }
    }
}
