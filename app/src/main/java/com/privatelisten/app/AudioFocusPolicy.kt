package com.privatelisten.app

import android.media.AudioManager

internal enum class AudioFocusAction {
    RESUME_IF_NEEDED,
    CONTINUE,
    PAUSE_AND_RESUME,
    PAUSE,
}

internal fun audioFocusAction(change: Int): AudioFocusAction = when (change) {
    AudioManager.AUDIOFOCUS_GAIN -> AudioFocusAction.RESUME_IF_NEEDED
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusAction.CONTINUE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusAction.PAUSE_AND_RESUME
    AudioManager.AUDIOFOCUS_LOSS -> AudioFocusAction.PAUSE
    else -> AudioFocusAction.CONTINUE
}
