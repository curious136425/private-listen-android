package com.privatelisten.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class QaControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val playbackAction = when (intent?.action) {
            ACTION_SIMULATE_AUDIO_NOISY -> PlaybackContract.ACTION_QA_AUDIO_NOISY
            ACTION_SIMULATE_AUDIO_CAN_DUCK -> PlaybackContract.ACTION_QA_AUDIO_CAN_DUCK
            else -> return
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, PlaybackService::class.java)
                .setAction(playbackAction),
        )
    }

    companion object {
        const val ACTION_SIMULATE_AUDIO_NOISY =
            "com.privatelisten.app.qa.action.SIMULATE_AUDIO_NOISY"
        const val ACTION_SIMULATE_AUDIO_CAN_DUCK =
            "com.privatelisten.app.qa.action.SIMULATE_AUDIO_CAN_DUCK"
    }
}
