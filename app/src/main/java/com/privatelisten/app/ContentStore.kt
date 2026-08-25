package com.privatelisten.app

import android.content.Context

data class LegacySavedContent(
    val title: String,
    val originalText: String,
    val currentSegment: Int,
    val currentOffset: Int,
    val speechRate: Float,
    val lastPlayedAt: Long,
    val voiceName: String?,
    val isFinished: Boolean,
)

data class TtsDiagnostics(
    val engine: String = "系统默认 TTS",
    val voice: String = "读取中",
    val locale: String = "读取中",
    val needsNetwork: Boolean? = null,
    val chineseVoiceNames: List<String> = emptyList(),
)

data class AppSettings(
    val defaultSpeechRate: Float = 1.0f,
    val defaultVoiceName: String? = null,
)

object ContentStore {
    private const val PREFS = "private_listen_state"
    private const val TITLE = "title"
    private const val TEXT = "original_text"
    private const val SEGMENT = "current_segment"
    private const val OFFSET = "current_offset"
    private const val RATE = "speech_rate"
    private const val LAST_PLAYED = "last_played_at"
    private const val VOICE = "voice_name"
    private const val FINISHED = "is_finished"
    private const val ENGINE = "tts_engine"
    private const val ACTIVE_VOICE = "tts_active_voice"
    private const val LOCALE = "tts_locale"
    private const val NETWORK = "tts_needs_network"
    private const val SELECTED_BOOK = "selected_book_id"
    private const val LEGACY_MIGRATED = "legacy_migrated_to_room"
    private const val DEFAULT_RATE = "default_speech_rate"
    private const val DEFAULT_VOICE = "default_voice_name"

    fun loadLegacy(context: Context): LegacySavedContent? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(TEXT, null) ?: return null
        return LegacySavedContent(
            title = prefs.getString(TITLE, "未命名") ?: "未命名",
            originalText = text,
            currentSegment = prefs.getInt(SEGMENT, 0),
            currentOffset = prefs.getInt(OFFSET, 0),
            speechRate = prefs.getFloat(RATE, 1.0f),
            lastPlayedAt = prefs.getLong(LAST_PLAYED, 0L),
            voiceName = prefs.getString(VOICE, null),
            isFinished = prefs.getBoolean(FINISHED, false),
        )
    }

    fun isLegacyMigrationDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(LEGACY_MIGRATED, false)

    fun markLegacyMigrationDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(LEGACY_MIGRATED, true)
            .apply()
    }

    fun selectBook(context: Context, bookId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SELECTED_BOOK, bookId.coerceAtLeast(0))
            .apply()
    }

    fun selectedBookId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SELECTED_BOOK, 0L)

    fun saveSettings(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(DEFAULT_RATE, settings.defaultSpeechRate)
            .putString(DEFAULT_VOICE, settings.defaultVoiceName)
            .apply()
    }

    fun loadSettings(context: Context): AppSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppSettings(
            defaultSpeechRate = prefs.getFloat(DEFAULT_RATE, 1.0f),
            defaultVoiceName = prefs.getString(DEFAULT_VOICE, null),
        )
    }

    fun saveDiagnostics(context: Context, diagnostics: TtsDiagnostics) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENGINE, diagnostics.engine)
            .putString(ACTIVE_VOICE, diagnostics.voice)
            .putString(LOCALE, diagnostics.locale)
            .putBoolean(NETWORK, diagnostics.needsNetwork == true)
            .apply()
    }

    fun loadDiagnostics(context: Context): TtsDiagnostics {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TtsDiagnostics(
            engine = prefs.getString(ENGINE, "系统默认 TTS") ?: "系统默认 TTS",
            voice = prefs.getString(ACTIVE_VOICE, "读取中") ?: "读取中",
            locale = prefs.getString(LOCALE, "读取中") ?: "读取中",
            needsNetwork = if (prefs.contains(NETWORK)) prefs.getBoolean(NETWORK, false) else null,
        )
    }
}
