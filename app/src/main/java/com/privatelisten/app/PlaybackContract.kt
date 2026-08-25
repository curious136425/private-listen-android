package com.privatelisten.app

data class PlaybackSnapshot(
    val bookId: Long = 0,
    val title: String = "",
    val chapterTitle: String = "",
    val currentText: String = "",
    val currentChapter: Int = 0,
    val totalChapters: Int = 0,
    val currentSegment: Int = 0,
    val currentOffset: Int = 0,
    val totalSegments: Int = 0,
    val speechRate: Float = 1.0f,
    val isPlaying: Boolean = false,
    val isFinished: Boolean = false,
    val sleepEndsAt: Long = 0L,
)

object PlaybackContract {
    const val ACTION_PLAY = "com.privatelisten.app.action.PLAY"
    const val ACTION_PAUSE = "com.privatelisten.app.action.PAUSE"
    const val ACTION_STOP = "com.privatelisten.app.action.STOP"
    const val ACTION_PREVIOUS = "com.privatelisten.app.action.PREVIOUS"
    const val ACTION_NEXT = "com.privatelisten.app.action.NEXT"
    const val ACTION_SET_RATE = "com.privatelisten.app.action.SET_RATE"
    const val ACTION_SET_SLEEP = "com.privatelisten.app.action.SET_SLEEP"
    const val ACTION_SELECT_CHAPTER = "com.privatelisten.app.action.SELECT_CHAPTER"
    const val ACTION_STATE = "com.privatelisten.app.action.STATE"
    const val ACTION_QA_AUDIO_NOISY = "com.privatelisten.app.action.QA_AUDIO_NOISY"
    const val ACTION_QA_AUDIO_CAN_DUCK = "com.privatelisten.app.action.QA_AUDIO_CAN_DUCK"

    const val EXTRA_BOOK_ID = "book_id"
    const val EXTRA_CHAPTER = "chapter"
    const val EXTRA_CHAPTER_TITLE = "chapter_title"
    const val EXTRA_TOTAL_CHAPTERS = "total_chapters"
    const val EXTRA_RATE = "rate"
    const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_SEGMENT = "segment"
    const val EXTRA_OFFSET = "offset"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_PLAYING = "playing"
    const val EXTRA_FINISHED = "finished"
    const val EXTRA_SLEEP_END = "sleep_end"
}
