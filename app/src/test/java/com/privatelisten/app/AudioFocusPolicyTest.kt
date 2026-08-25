package com.privatelisten.app

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusPolicyTest {
    @Test
    fun `notification duck request keeps narration playing`() {
        assertEquals(
            AudioFocusAction.CONTINUE,
            audioFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }

    @Test
    fun `exclusive transient focus pauses and later resumes`() {
        assertEquals(
            AudioFocusAction.PAUSE_AND_RESUME,
            audioFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            AudioFocusAction.RESUME_IF_NEEDED,
            audioFocusAction(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `permanent focus loss pauses without automatic resume`() {
        assertEquals(
            AudioFocusAction.PAUSE,
            audioFocusAction(AudioManager.AUDIOFOCUS_LOSS),
        )
    }
}
