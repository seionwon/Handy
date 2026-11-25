package com.pais.handy.ui

internal enum class MicrophoneAction { RECORD, GRANT_ONLY }

internal class MicrophonePermissionRequest(restoredAction: MicrophoneAction? = null) {
    var pendingAction = restoredAction
        private set

    fun request(action: MicrophoneAction, launch: () -> Unit) {
        if (pendingAction != null) return
        pendingAction = action
        launch()
    }

    fun finish(granted: Boolean): Boolean {
        val startRecording = granted && pendingAction == MicrophoneAction.RECORD
        pendingAction = null
        return startRecording
    }
}
