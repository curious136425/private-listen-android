package com.privatelisten.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

class PlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitAttempts = 0
    private var pendingSpeak = false
    private var loadGeneration = 0L
    private var book: BookEntity? = null
    private var chapter: ChapterEntity? = null
    private var segments: List<String> = emptyList()
    private var currentChapter = 0
    private var currentSegment = 0
    private var currentOffset = 0
    private var speechRate = 1.0f
    private var isPlaying = false
    private var isFinished = false
    private var utteranceGeneration = 0L
    private var activeUtteranceId: String? = null
    private var activeFragmentEnd = 0
    private var fragmentRetryCount = 0
    private var sleepEndsAt = 0L
    private var sleepRunnable: Runnable? = null
    private var resumeAfterAudioFocus = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying) {
                resumeAfterAudioFocus = false
                pausePlayback()
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post { handleAudioFocusChange(change) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ttsPlayback")
            .apply { setReferenceCounted(false) }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_EXPORTED,
        )
        initializeTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action ?: PlaybackContract.ACTION_PLAY) {
            PlaybackContract.ACTION_PLAY -> play(intent?.getLongExtra(PlaybackContract.EXTRA_BOOK_ID, 0L) ?: 0L)
            PlaybackContract.ACTION_PAUSE -> pausePlayback()
            PlaybackContract.ACTION_STOP -> stopPlayback(resetPosition = true, stopService = true)
            PlaybackContract.ACTION_PREVIOUS -> moveSegment(-1)
            PlaybackContract.ACTION_NEXT -> moveSegment(1)
            PlaybackContract.ACTION_SELECT_CHAPTER -> selectChapter(
                intent?.getIntExtra(PlaybackContract.EXTRA_CHAPTER, 0) ?: 0,
            )
            PlaybackContract.ACTION_SET_RATE -> setRate(
                intent?.getFloatExtra(PlaybackContract.EXTRA_RATE, 1.0f) ?: 1.0f,
            )
            PlaybackContract.ACTION_SET_SLEEP -> setSleepTimer(
                intent?.getIntExtra(PlaybackContract.EXTRA_SLEEP_MINUTES, 0) ?: 0,
            )
            PlaybackContract.ACTION_QA_AUDIO_NOISY -> {
                if (packageName.endsWith(".qa") && isPlaying) {
                    Log.i(TAG, "QA simulated audio becoming noisy; pausing")
                    pausePlayback()
                }
            }
            PlaybackContract.ACTION_QA_AUDIO_CAN_DUCK -> {
                if (packageName.endsWith(".qa") && isPlaying) {
                    Log.i(TAG, "QA simulated transient can-duck focus change")
                    handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun initializeTts() {
        ttsReady = false
        ttsInitAttempts++
        tts = TextToSpeech(this) { result ->
            val engine = tts ?: return@TextToSpeech
            if (result == TextToSpeech.SUCCESS) {
                ttsReady = true
                engine.setOnUtteranceProgressListener(utteranceListener)
                configureTts()
                if (pendingSpeak) {
                    pendingSpeak = false
                    speakCurrentSegment()
                }
            } else if (ttsInitAttempts < MAX_TTS_INIT_ATTEMPTS) {
                Log.w(TAG, "TTS initialization failed ($result), retrying")
                engine.shutdown()
                tts = null
                handler.postDelayed(::initializeTts, TTS_RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "TTS initialization failed after retry: $result")
                isPlaying = false
                pendingSpeak = false
                releaseWakeLock()
                publishState()
            }
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.i(TAG, "onStart $utteranceId")
            publishState()
        }

        override fun onDone(utteranceId: String?) {
            handler.post { advanceAfter(utteranceId) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            Log.i(TAG, "onStop $utteranceId interrupted=$interrupted")
        }

        @Deprecated("Required by older Android API levels")
        override fun onError(utteranceId: String?) {
            handler.post { handleTtsError(utteranceId, TextToSpeech.ERROR) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handler.post { handleTtsError(utteranceId, errorCode) }
        }
    }

    private fun configureTts() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val requestedVoice = book?.voiceName ?: ContentStore.loadSettings(this).defaultVoiceName
        if (!requestedVoice.isNullOrBlank()) {
            engine.voices?.firstOrNull { it.name == requestedVoice }?.let(engine::setVoice)
        } else if (engine.voice?.locale?.language != Locale.CHINESE.language) {
            engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        }
        engine.setSpeechRate(speechRate)
        val voice = engine.voice
        ContentStore.saveDiagnostics(
            this,
            TtsDiagnostics(
                engine = engine.defaultEngine ?: "系统默认 TTS",
                voice = voice?.name ?: "系统默认",
                locale = voice?.locale?.toLanguageTag() ?: "未知",
                needsNetwork = voice?.isNetworkConnectionRequired,
            ),
        )
        Log.i(
            TAG,
            "engine=${engine.defaultEngine} voice=${voice?.name} " +
                "locale=${voice?.locale?.toLanguageTag()} network=${voice?.isNetworkConnectionRequired}",
        )
    }

    private fun play(requestedBookId: Long) {
        val bookId = requestedBookId.takeIf { it > 0 } ?: ContentStore.selectedBookId(this)
        if (bookId <= 0) {
            stopPlayback(resetPosition = false, stopService = true)
            return
        }
        ContentStore.selectBook(this, bookId)
        isPlaying = true
        isFinished = false
        acquireWakeLock()
        val token = ++loadGeneration
        serviceScope.launch {
            val loadedBook = LibraryRepository.getBook(this@PlaybackService, bookId)
            if (loadedBook == null) {
                withContext(Dispatchers.Main) { stopPlayback(false, true) }
                return@launch
            }
            val chapterIndex = if (loadedBook.isFinished) 0 else {
                loadedBook.currentChapter.coerceIn(0, (loadedBook.totalChapters - 1).coerceAtLeast(0))
            }
            val loadedChapter = LibraryRepository.getChapter(this@PlaybackService, bookId, chapterIndex)
            withContext(Dispatchers.Main) {
                if (token != loadGeneration || loadedChapter == null) return@withContext
                book = loadedBook
                chapter = loadedChapter
                currentChapter = chapterIndex
                currentSegment = if (loadedBook.isFinished) 0 else loadedBook.currentSegment
                currentOffset = if (loadedBook.isFinished) 0 else loadedBook.currentOffset
                speechRate = loadedBook.speechRate
                isFinished = false
                segments = TextChunker.split(loadedChapter.text)
                currentSegment = currentSegment.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
                currentOffset = currentOffset.coerceAtLeast(0)
                configureTts()
                if (!requestAudioFocus()) {
                    pausePlayback()
                } else if (ttsReady) {
                    speakCurrentSegment()
                } else {
                    pendingSpeak = true
                }
                publishState()
            }
        }
    }

    private fun speakCurrentSegment() {
        if (!isPlaying || !ttsReady || segments.isEmpty()) return
        while (currentSegment < segments.size) {
            val text = segments[currentSegment]
            currentOffset = currentOffset.coerceIn(0, text.length)
            if (currentOffset >= text.length) {
                currentSegment++
                currentOffset = 0
                continue
            }
            val fragment = SpeechFragmenter.next(text, currentOffset) ?: continue
            if (fragment.text.isBlank()) {
                currentOffset = fragment.endOffset
                continue
            }
            break
        }
        if (currentSegment >= segments.size) {
            loadChapter(currentChapter + 1, 0, 0, finishIfPastEnd = true)
            return
        }

        val fragment = SpeechFragmenter.next(segments[currentSegment], currentOffset) ?: return
        activeFragmentEnd = fragment.endOffset
        utteranceGeneration++
        activeUtteranceId = "fragment:$utteranceGeneration:${book?.id}:$currentChapter:$currentSegment:$currentOffset"
        persistProgress(forceBackup = false)
        val result = tts?.speak(fragment.text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId)
        if (result == TextToSpeech.ERROR) handleTtsError(activeUtteranceId, TextToSpeech.ERROR)
        updateNotification()
        publishState()
    }

    private fun advanceAfter(utteranceId: String?) {
        if (!isPlaying || utteranceId != activeUtteranceId) return
        fragmentRetryCount = 0
        currentOffset = activeFragmentEnd
        if (currentOffset >= segments[currentSegment].length) {
            currentSegment++
            currentOffset = 0
        }
        if (currentSegment >= segments.size) {
            loadChapter(currentChapter + 1, 0, 0, finishIfPastEnd = true)
        } else {
            speakCurrentSegment()
        }
    }

    private fun handleTtsError(utteranceId: String?, errorCode: Int) {
        if (utteranceId != activeUtteranceId) return
        Log.e(TAG, "TTS error chapter=$currentChapter segment=$currentSegment code=$errorCode")
        if (fragmentRetryCount < MAX_FRAGMENT_RETRIES && isPlaying) {
            fragmentRetryCount++
            activeUtteranceId = null
            handler.postDelayed(::speakCurrentSegment, TTS_RETRY_DELAY_MS * fragmentRetryCount)
        } else {
            fragmentRetryCount = 0
            pausePlayback()
        }
    }

    private fun pausePlayback(abandonFocus: Boolean = true) {
        pendingSpeak = false
        isPlaying = false
        utteranceGeneration++
        activeUtteranceId = null
        tts?.stop()
        persistProgress(forceBackup = true)
        releaseWakeLock()
        if (abandonFocus) abandonAudioFocus()
        updateNotification()
        publishState()
    }

    private fun stopPlayback(resetPosition: Boolean, stopService: Boolean) {
        pendingSpeak = false
        isPlaying = false
        utteranceGeneration++
        activeUtteranceId = null
        tts?.stop()
        if (resetPosition) {
            currentChapter = 0
            currentSegment = 0
            currentOffset = 0
        }
        isFinished = false
        persistProgress(forceBackup = true)
        cancelSleepTimer()
        releaseWakeLock()
        abandonAudioFocus()
        publishState()
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun finishBook() {
        val activeBook = book ?: return
        currentChapter = (activeBook.totalChapters - 1).coerceAtLeast(0)
        currentSegment = (segments.size - 1).coerceAtLeast(0)
        currentOffset = segments.getOrNull(currentSegment)?.length ?: 0
        isPlaying = false
        isFinished = true
        activeUtteranceId = null
        persistProgress(forceBackup = true)
        cancelSleepTimer()
        releaseWakeLock()
        abandonAudioFocus()
        publishState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun moveSegment(direction: Int) {
        if (book == null || chapter == null) return
        if (direction < 0) {
            if (currentSegment > 0) {
                currentSegment--
                currentOffset = 0
                restartCurrentIfPlaying()
            } else if (currentChapter > 0) {
                loadChapter(currentChapter - 1, Int.MAX_VALUE, 0, finishIfPastEnd = false)
            }
        } else {
            if (currentSegment < segments.lastIndex) {
                currentSegment++
                currentOffset = 0
                restartCurrentIfPlaying()
            } else {
                loadChapter(currentChapter + 1, 0, 0, finishIfPastEnd = false)
            }
        }
    }

    private fun selectChapter(requested: Int) {
        val activeBook = book ?: return
        loadChapter(requested.coerceIn(0, activeBook.totalChapters - 1), 0, 0, finishIfPastEnd = false)
    }

    private fun loadChapter(
        requestedChapter: Int,
        requestedSegment: Int,
        requestedOffset: Int,
        finishIfPastEnd: Boolean,
    ) {
        val activeBook = book ?: return
        if (requestedChapter >= activeBook.totalChapters) {
            if (finishIfPastEnd) finishBook()
            return
        }
        if (requestedChapter < 0) return
        val wasPlaying = isPlaying
        val token = ++loadGeneration
        serviceScope.launch {
            val loaded = LibraryRepository.getChapter(this@PlaybackService, activeBook.id, requestedChapter)
            withContext(Dispatchers.Main) {
                if (token != loadGeneration || loaded == null) return@withContext
                chapter = loaded
                currentChapter = requestedChapter
                segments = TextChunker.split(loaded.text)
                currentSegment = if (requestedSegment == Int.MAX_VALUE) segments.lastIndex else requestedSegment
                currentSegment = currentSegment.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
                currentOffset = requestedOffset.coerceAtLeast(0)
                isFinished = false
                persistProgress(forceBackup = false)
                if (wasPlaying) restartCurrentIfPlaying() else publishState()
            }
        }
    }

    private fun restartCurrentIfPlaying() {
        isFinished = false
        persistProgress(forceBackup = false)
        if (isPlaying) {
            utteranceGeneration++
            activeUtteranceId = null
            tts?.stop()
            speakCurrentSegment()
        } else {
            updateNotification()
            publishState()
        }
    }

    private fun setRate(requested: Float) {
        speechRate = requested.takeIf { it in ALLOWED_RATES } ?: 1.0f
        book = book?.copy(speechRate = speechRate)
        tts?.setSpeechRate(speechRate)
        persistProgress(forceBackup = false)
        if (isPlaying) restartCurrentIfPlaying() else publishState()
    }

    private fun persistProgress(forceBackup: Boolean) {
        val activeBook = book ?: return
        runBlocking(Dispatchers.IO) {
            LibraryRepository.updateProgress(
                this@PlaybackService,
                activeBook.id,
                currentChapter,
                currentSegment,
                currentOffset,
                speechRate,
                isFinished,
            )
            if (forceBackup) LibraryBackup.writeAutomatic(this@PlaybackService, force = true)
        }
        book = activeBook.copy(
            currentChapter = currentChapter,
            currentSegment = currentSegment,
            currentOffset = currentOffset,
            speechRate = speechRate,
            isFinished = isFinished,
        )
    }

    private fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes > 0) {
            sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
            sleepRunnable = Runnable {
                sleepEndsAt = 0L
                sleepRunnable = null
                stopPlayback(resetPosition = false, stopService = true)
            }.also { handler.postDelayed(it, minutes * 60_000L) }
        }
        updateNotification()
        publishState()
    }

    private fun cancelSleepTimer() {
        sleepRunnable?.let(handler::removeCallbacks)
        sleepRunnable = null
        sleepEndsAt = 0L
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener, handler)
            .setWillPauseWhenDucked(false)
            .build()
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun handleAudioFocusChange(change: Int) {
        when (audioFocusAction(change)) {
            AudioFocusAction.RESUME_IF_NEEDED -> {
                if (resumeAfterAudioFocus && !isPlaying && book != null) {
                    resumeAfterAudioFocus = false
                    isPlaying = true
                    acquireWakeLock()
                    speakCurrentSegment()
                }
            }
            AudioFocusAction.CONTINUE -> {
                // Notification sounds commonly request MAY_DUCK. For spoken-word playback,
                // keep the narration continuous instead of introducing a disruptive pause.
                Log.i(TAG, "Transient can-duck focus change; continuing playback")
            }
            AudioFocusAction.PAUSE_AND_RESUME -> {
                // Calls and other short exclusive audio sessions use transient focus.
                resumeAfterAudioFocus = isPlaying
                if (isPlaying) pausePlayback(abandonFocus = false)
            }
            AudioFocusAction.PAUSE -> {
                resumeAfterAudioFocus = false
                if (isPlaying) pausePlayback()
            }
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
    }

    private fun publishState() {
        val activeBook = book
        sendBroadcast(
            Intent(PlaybackContract.ACTION_STATE)
                .setPackage(packageName)
                .putExtra(PlaybackContract.EXTRA_BOOK_ID, activeBook?.id ?: 0L)
                .putExtra(PlaybackContract.EXTRA_TITLE, activeBook?.title.orEmpty())
                .putExtra(PlaybackContract.EXTRA_CHAPTER_TITLE, chapter?.title.orEmpty())
                .putExtra(PlaybackContract.EXTRA_CHAPTER, currentChapter)
                .putExtra(PlaybackContract.EXTRA_TOTAL_CHAPTERS, activeBook?.totalChapters ?: 0)
                .putExtra(PlaybackContract.EXTRA_TEXT, segments.getOrNull(currentSegment).orEmpty())
                .putExtra(PlaybackContract.EXTRA_SEGMENT, currentSegment)
                .putExtra(PlaybackContract.EXTRA_OFFSET, currentOffset)
                .putExtra(PlaybackContract.EXTRA_TOTAL, segments.size)
                .putExtra(PlaybackContract.EXTRA_RATE, speechRate)
                .putExtra(PlaybackContract.EXTRA_PLAYING, isPlaying)
                .putExtra(PlaybackContract.EXTRA_FINISHED, isFinished)
                .putExtra(PlaybackContract.EXTRA_SLEEP_END, sleepEndsAt),
        )
    }

    private fun createNotificationChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台朗读", NotificationManager.IMPORTANCE_LOW).apply {
                description = "让私人听读在锁屏和切换应用后继续朗读"
                setSound(null, null)
            },
        )
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = if (isPlaying) PlaybackContract.ACTION_PAUSE else PlaybackContract.ACTION_PLAY
        val progress = when {
            book == null -> "准备朗读"
            (book?.totalChapters ?: 0) > 1 -> "第 ${currentChapter + 1}/${book?.totalChapters} 章 · ${currentSegment + 1}/${segments.size} 段"
            else -> "第 ${currentSegment + 1} / ${segments.size} 段"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(book?.title ?: "私人听读")
            .setContentText(progress)
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "上一段", action(2, PlaybackContract.ACTION_PREVIOUS)).build())
            .addAction(Notification.Action.Builder(null, if (isPlaying) "暂停" else "播放", action(3, toggleAction)).build())
            .addAction(Notification.Action.Builder(null, "下一段", action(4, PlaybackContract.ACTION_NEXT)).build())
            .addAction(Notification.Action.Builder(null, "停止", action(5, PlaybackContract.ACTION_STOP)).build())
            .build()
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(12 * 60 * 60 * 1_000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onDestroy() {
        cancelSleepTimer()
        releaseWakeLock()
        abandonAudioFocus()
        runCatching { unregisterReceiver(noisyReceiver) }
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PrivateListenPlayback"
        private const val CHANNEL_ID = "private_listen_playback"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_FRAGMENT_RETRIES = 2
        private const val MAX_TTS_INIT_ATTEMPTS = 2
        private const val TTS_RETRY_DELAY_MS = 750L
        private val ALLOWED_RATES = setOf(0.8f, 1.0f, 1.2f, 1.5f)
    }
}
