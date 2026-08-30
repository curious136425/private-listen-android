package com.privatelisten.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class Page { HOME, NEW, PLAYER, SETTINGS }

private val ListenPurple = Color(0xFF8173B4)
private val ListenPurpleDeep = Color(0xFF625394)
private val ListenLavender = Color(0xFFF0EDF8)
private val ListenBackground = Color(0xFFF7F5FA)
private val ListenInk = Color(0xFF2D2936)
private val ListenMuted = Color(0xFF756F80)
private val ListenLine = Color(0xFFE2DDEB)
private val ListenFogBlue = Color(0xFF8CAFC5)

private val ListenColorScheme = lightColorScheme(
    primary = ListenPurple,
    onPrimary = Color.White,
    primaryContainer = ListenLavender,
    onPrimaryContainer = ListenPurpleDeep,
    secondary = ListenFogBlue,
    background = ListenBackground,
    onBackground = ListenInk,
    surface = Color.White,
    onSurface = ListenInk,
    surfaceVariant = Color(0xFFF3F0F7),
    onSurfaceVariant = ListenMuted,
    outline = ListenLine,
)

private val ListenTypography = Typography(
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = ListenMuted),
)

class MainActivity : ComponentActivity() {
    private var page by mutableStateOf(Page.HOME)
    private var books by mutableStateOf<List<BookEntity>>(emptyList())
    private var activeBook by mutableStateOf<BookEntity?>(null)
    private var activeChapters by mutableStateOf<List<ChapterEntity>>(emptyList())
    private var snapshot by mutableStateOf(PlaybackSnapshot())
    private var diagnostics by mutableStateOf(TtsDiagnostics())
    private var settings by mutableStateOf(AppSettings())
    private var importing by mutableStateOf(false)
    private var importError by mutableStateOf<String?>(null)
    private var receiverRegistered = false
    private var ttsProbe: TextToSpeech? = null
    private var pendingBackupText: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val text = pendingBackupText
        pendingBackupText = null
        if (uri != null && text != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { it.write(text) }
                        ?: error("无法写入备份")
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "书库备份已导出", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { showToast("备份导出失败：${it.message}") }
            }
        }
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("无法读取备份")
                    LibraryBackup.restoreLibrary(this@MainActivity, text)
                }.onSuccess { ids ->
                    refreshLibrary()
                    showToast("已恢复 ${ids.size} 本内容")
                }.onFailure { showToast("备份恢复失败：${it.message}") }
            }
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PlaybackContract.ACTION_STATE) return
            snapshot = PlaybackSnapshot(
                bookId = intent.getLongExtra(PlaybackContract.EXTRA_BOOK_ID, 0L),
                title = intent.getStringExtra(PlaybackContract.EXTRA_TITLE).orEmpty(),
                chapterTitle = intent.getStringExtra(PlaybackContract.EXTRA_CHAPTER_TITLE).orEmpty(),
                currentText = intent.getStringExtra(PlaybackContract.EXTRA_TEXT).orEmpty(),
                currentChapter = intent.getIntExtra(PlaybackContract.EXTRA_CHAPTER, 0),
                totalChapters = intent.getIntExtra(PlaybackContract.EXTRA_TOTAL_CHAPTERS, 0),
                currentSegment = intent.getIntExtra(PlaybackContract.EXTRA_SEGMENT, 0),
                currentOffset = intent.getIntExtra(PlaybackContract.EXTRA_OFFSET, 0),
                totalSegments = intent.getIntExtra(PlaybackContract.EXTRA_TOTAL, 0),
                speechRate = intent.getFloatExtra(PlaybackContract.EXTRA_RATE, 1.0f),
                isPlaying = intent.getBooleanExtra(PlaybackContract.EXTRA_PLAYING, false),
                isFinished = intent.getBooleanExtra(PlaybackContract.EXTRA_FINISHED, false),
                sleepEndsAt = intent.getLongExtra(PlaybackContract.EXTRA_SLEEP_END, 0L),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = ContentStore.loadDiagnostics(this)
        settings = ContentStore.loadSettings(this)
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) page = Page.PLAYER
        lifecycleScope.launch(Dispatchers.IO) {
            LibraryRepository.migrateLegacyIfNeeded(this@MainActivity)
            refreshLibrary()
        }
        probeSystemTts()

        setContent {
            MaterialTheme(
                colorScheme = ListenColorScheme,
                typography = ListenTypography,
                shapes = androidx.compose.material3.Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(18.dp),
                    large = RoundedCornerShape(24.dp),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PrivateListenApp(
                        page = page,
                        books = books,
                        activeBook = activeBook,
                        chapters = activeChapters,
                        snapshot = snapshot,
                        diagnostics = diagnostics,
                        settings = settings,
                        importing = importing,
                        importError = importError,
                        onNew = { page = Page.NEW },
                        onSettings = { page = Page.SETTINGS },
                        onBackHome = {
                            page = Page.HOME
                            lifecycleScope.launch(Dispatchers.IO) { refreshLibrary() }
                        },
                        onOpenBook = { id -> openBook(id, autoplay = false) },
                        onPlayBook = { id -> openBook(id, autoplay = true) },
                        onCreateText = ::createTextBook,
                        onImportEpub = ::importEpub,
                        onDelete = ::deleteBook,
                        onRename = ::renameBook,
                        onExport = ::exportLibrary,
                        onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) },
                        onSaveSettings = ::saveSettings,
                        onCommand = ::sendPlaybackCommand,
                        onRate = { rate ->
                            sendPlaybackCommand(PlaybackContract.ACTION_SET_RATE) {
                                putExtra(PlaybackContract.EXTRA_RATE, rate)
                            }
                        },
                        onSleep = { minutes ->
                            sendPlaybackCommand(PlaybackContract.ACTION_SET_SLEEP) {
                                putExtra(PlaybackContract.EXTRA_SLEEP_MINUTES, minutes)
                            }
                        },
                        onChapter = { chapter ->
                            sendPlaybackCommand(PlaybackContract.ACTION_SELECT_CHAPTER) {
                                putExtra(PlaybackContract.EXTRA_CHAPTER, chapter)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                playbackReceiver,
                IntentFilter(PlaybackContract.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        lifecycleScope.launch(Dispatchers.IO) { refreshLibrary() }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(playbackReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        ttsProbe?.shutdown()
        ttsProbe = null
        super.onDestroy()
    }

    private suspend fun refreshLibrary() {
        val loadedBooks = LibraryRepository.listBooks(this)
        val selectedId = ContentStore.selectedBookId(this)
        val selected = loadedBooks.firstOrNull { it.id == selectedId }
        val chapters = selected?.let { LibraryRepository.getChapters(this, it.id) }.orEmpty()
        val nextSnapshot = if (!snapshot.isPlaying) snapshotFrom(selected, chapters) else snapshot
        withContext(Dispatchers.Main) {
            books = loadedBooks
            activeBook = selected
            activeChapters = chapters
            if (!snapshot.isPlaying) snapshot = nextSnapshot
        }
    }

    private fun openBook(bookId: Long, autoplay: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            ContentStore.selectBook(this@MainActivity, bookId)
            val selected = LibraryRepository.getBook(this@MainActivity, bookId) ?: return@launch
            val chapters = LibraryRepository.getChapters(this@MainActivity, bookId)
            withContext(Dispatchers.Main) {
                activeBook = selected
                activeChapters = chapters
                snapshot = snapshotFrom(selected, chapters)
                page = Page.PLAYER
                if (autoplay) {
                    requestNotificationPermissionIfNeeded()
                    sendPlaybackCommand(PlaybackContract.ACTION_PLAY) {
                        putExtra(PlaybackContract.EXTRA_BOOK_ID, bookId)
                    }
                }
            }
        }
    }

    private fun createTextBook(title: String, text: String, rate: Float, voice: String?) {
        if (text.isBlank()) {
            Toast.makeText(this, "请输入或导入文字", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                LibraryRepository.addBook(
                    this@MainActivity,
                    ImportedBook(
                        title = title.ifBlank { "未命名" },
                        sourceType = BookSourceType.TEXT,
                        chapters = listOf(ChapterDraft("正文", text)),
                    ),
                    rate,
                    voice,
                )
            }.onSuccess { id ->
                withContext(Dispatchers.Main) { openBook(id, autoplay = true) }
            }.onFailure { showToast("保存失败：${it.message}") }
        }
    }

    private fun importEpub(uri: Uri, fallbackName: String, rate: Float, voice: String?) {
        if (importing) return
        importing = true
        importError = null
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val imported = contentResolver.openInputStream(uri)?.use {
                    EpubParser.parse(it, fallbackName.substringBeforeLast('.').ifBlank { "未命名书籍" })
                } ?: error("无法读取 EPUB")
                LibraryRepository.addBook(this@MainActivity, imported, rate, voice)
            }.onSuccess { id ->
                withContext(Dispatchers.Main) {
                    importing = false
                    Toast.makeText(this@MainActivity, "EPUB 导入成功", Toast.LENGTH_SHORT).show()
                    openBook(id, autoplay = false)
                }
            }.onFailure {
                Log.e("PrivateListenImport", "EPUB import failed", it)
                withContext(Dispatchers.Main) {
                    importing = false
                    importError = it.message ?: it.javaClass.simpleName
                }
                showToast("EPUB 导入失败：${it.message}")
            }
        }
    }

    private fun deleteBook(bookId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (ContentStore.selectedBookId(this@MainActivity) == bookId) {
                withContext(Dispatchers.Main) { sendPlaybackCommand(PlaybackContract.ACTION_STOP) }
            }
            LibraryRepository.delete(this@MainActivity, bookId)
            refreshLibrary()
        }
    }

    private fun renameBook(bookId: Long, title: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            LibraryRepository.rename(this@MainActivity, bookId, title)
            refreshLibrary()
        }
    }

    private fun exportLibrary() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { LibraryBackup.exportLibrary(this@MainActivity) }
                .onSuccess { text ->
                    withContext(Dispatchers.Main) {
                        pendingBackupText = text
        exportLauncher.launch("听笺-书库备份.json")
                    }
                }
                .onFailure { showToast("生成备份失败：${it.message}") }
        }
    }

    private fun saveSettings(next: AppSettings) {
        settings = next
        ContentStore.saveSettings(this, next)
        Toast.makeText(this, "默认设置已保存", Toast.LENGTH_SHORT).show()
        page = Page.HOME
    }

    private fun snapshotFrom(book: BookEntity?, chapters: List<ChapterEntity>): PlaybackSnapshot {
        book ?: return PlaybackSnapshot()
        val chapterIndex = book.currentChapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        val chapter = chapters.getOrNull(chapterIndex)
        val segments = TextChunker.split(chapter?.text.orEmpty())
        val segment = book.currentSegment.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
        return PlaybackSnapshot(
            bookId = book.id,
            title = book.title,
            chapterTitle = chapter?.title.orEmpty(),
            currentText = segments.getOrNull(segment).orEmpty(),
            currentChapter = chapterIndex,
            totalChapters = book.totalChapters,
            currentSegment = segment,
            currentOffset = book.currentOffset,
            totalSegments = segments.size,
            speechRate = book.speechRate,
            isFinished = book.isFinished,
        )
    }

    private fun probeSystemTts() {
        ttsProbe = TextToSpeech(this) { result ->
            val probe = ttsProbe ?: return@TextToSpeech
            if (result == TextToSpeech.SUCCESS) {
                val voice = probe.voice
                val voices = probe.voices
                    ?.filter { it.locale.language == "zh" }
                    ?.map { it.name }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()
                diagnostics = TtsDiagnostics(
                    engine = probe.defaultEngine ?: "系统默认 TTS",
                    voice = voice?.name ?: "系统默认",
                    locale = voice?.locale?.toLanguageTag() ?: "未知",
                    needsNetwork = voice?.isNetworkConnectionRequired,
                    chineseVoiceNames = voices,
                )
                ContentStore.saveDiagnostics(this, diagnostics)
            } else {
                diagnostics = TtsDiagnostics(voice = "初始化失败 ($result)")
            }
            probe.shutdown()
            ttsProbe = null
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun sendPlaybackCommand(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, PlaybackService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

@Composable
private fun PrivateListenApp(
    page: Page,
    books: List<BookEntity>,
    activeBook: BookEntity?,
    chapters: List<ChapterEntity>,
    snapshot: PlaybackSnapshot,
    diagnostics: TtsDiagnostics,
    settings: AppSettings,
    importing: Boolean,
    importError: String?,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onBackHome: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onPlayBook: (Long) -> Unit,
    onCreateText: (String, String, Float, String?) -> Unit,
    onImportEpub: (Uri, String, Float, String?) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onSaveSettings: (AppSettings) -> Unit,
    onCommand: (String) -> Unit,
    onRate: (Float) -> Unit,
    onSleep: (Int) -> Unit,
    onChapter: (Int) -> Unit,
) {
    when (page) {
        Page.HOME -> HomeScreen(
            books, onNew, onSettings, onOpenBook, onPlayBook, onDelete, onRename, onExport, onRestore,
        )
        Page.NEW -> {
            BackHandler(onBack = onBackHome)
            NewScreen(diagnostics, settings, importing, importError, onBackHome, onCreateText, onImportEpub)
        }
        Page.PLAYER -> {
            BackHandler(onBack = onBackHome)
            PlayerScreen(activeBook, chapters, snapshot, onBackHome, onCommand, onRate, onSleep, onChapter)
        }
        Page.SETTINGS -> {
            BackHandler(onBack = onBackHome)
            SettingsScreen(diagnostics, settings, onBackHome, onSaveSettings)
        }
    }
}

@Composable
private fun HomeScreen(
    books: List<BookEntity>,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onPlayBook: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
) {
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    var renameTarget by remember { mutableStateOf<BookEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("听笺", style = MaterialTheme.typography.headlineMedium)
        Text("把想读的内容，放进一段安静时间里", color = ListenMuted)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("新建 / 导入") }
            OutlinedButton(onClick = onSettings) { Text("设置") }
        }
        Spacer(Modifier.height(2.dp))
        Text("我的听读 · ${books.size} 本", style = MaterialTheme.typography.titleMedium)
        if (books.isEmpty()) {
            Text("还没有内容。可以粘贴文字、导入 TXT，或导入无 DRM 的 EPUB。")
        }
        books.forEach { book ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    book.author?.let { Text("作者：$it", color = ListenMuted) }
                    val status = when {
                        book.isFinished -> "已读完"
                        book.lastPlayedAt <= 0 -> "未开始"
                        else -> "听读中"
                    }
                    val type = if (book.sourceType == BookSourceType.EPUB.name) "EPUB" else "文字"
                    Text("$status · $type · ${book.totalChapters} 章", color = ListenPurpleDeep, fontWeight = FontWeight.SemiBold)
                    if (book.lastPlayedAt > 0) {
                        Text(
                            "进度：第 ${book.currentChapter + 1} 章 · 第 ${book.currentSegment + 1} 段",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(book.lastPlayedAt)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPlayBook(book.id) }) {
                            Text(if (book.isFinished) "重新播放" else if (book.lastPlayedAt > 0) "继续听" else "开始听")
                        }
                        TextButton(onClick = { onOpenBook(book.id) }) { Text("查看") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            renameTarget = book
                            renameText = book.title
                        }) { Text("重命名") }
                        TextButton(onClick = { deleteTarget = book }) { Text("删除") }
                    }
                }
            }
        }
        HorizontalDivider(color = ListenLine)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExport, enabled = books.isNotEmpty()) { Text("导出书库备份") }
            OutlinedButton(onClick = onRestore) { Text("恢复备份") }
        }
        Text("内容变更和暂停时会自动备份；卸载前仍建议手动导出。", style = MaterialTheme.typography.bodySmall)
        BackgroundProtectionCard(context, batteryIgnored)
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除《${target.title}》？") },
            text = { Text("将删除这本内容和它的播放进度。此操作不会删除手机中的原始 TXT/EPUB 文件。") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(target.id) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(renameText, { renameText = it }, label = { Text("标题") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = null
                    onRename(target.id, renameText)
                }, enabled = renameText.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun BackgroundProtectionCard(context: Context, batteryIgnored: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F8)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("荣耀后台保护", fontWeight = FontWeight.SemiBold)
            Text(
                if (batteryIgnored) "Android 电池优化已放行。" else "Android 电池优化尚未放行。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "建议在应用详情的“耗电详情/应用启动管理”中关闭自动管理，并允许自启动、关联启动和后台活动。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                }
            }) { Text("打开应用后台设置") }
            if (!batteryIgnored) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }) { Text("请求忽略电池优化") }
            }
        }
    }
}

@Composable
private fun NewScreen(
    diagnostics: TtsDiagnostics,
    settings: AppSettings,
    importing: Boolean,
    importError: String?,
    onBack: () -> Unit,
    onStartText: (String, String, Float, String?) -> Unit,
    onImportEpub: (Uri, String, Float, String?) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf(settings.defaultSpeechRate) }
    var voice by remember { mutableStateOf(settings.defaultVoiceName) }
    var voiceExpanded by remember { mutableStateOf(false) }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("无法读取文件")
                if (title.isBlank()) title = queryDisplayName(context, uri).substringBeforeLast('.')
            }.onFailure { Toast.makeText(context, "TXT 导入失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val epubLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) onImportEpub(uri, queryDisplayName(context, uri), rate, voice)
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("新建 / 导入", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(1.dp))
        }
        if (importing) Text("正在解析并导入 EPUB，请稍候……", fontWeight = FontWeight.SemiBold)
        importError?.let { Text("上次导入失败：$it", color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("标题") }, singleLine = true)
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth().height(300.dp),
            label = { Text("文字内容") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (pasted.isNotEmpty()) text = pasted
            }) { Text("粘贴") }
            OutlinedButton(onClick = { txtLauncher.launch(arrayOf("text/plain", "text/*")) }) { Text("导入 TXT") }
            OutlinedButton(
                onClick = { epubLauncher.launch(arrayOf("application/epub+zip", "application/zip")) },
                enabled = !importing,
            ) { Text("导入 EPUB") }
        }
        Text("语速", style = MaterialTheme.typography.titleMedium)
        RateButtons(rate) { rate = it }
        Text("声音：${voice ?: diagnostics.voice}")
        if (diagnostics.chineseVoiceNames.size > 1) {
            Button(onClick = { voiceExpanded = true }) { Text("选择中文声音") }
            DropdownMenu(voiceExpanded, { voiceExpanded = false }) {
                diagnostics.chineseVoiceNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { voice = name; voiceExpanded = false },
                    )
                }
            }
        } else {
            Text("系统只返回一个中文声音标识，男/女声请在荣耀 TTS 设置中选择。", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "TTS：${diagnostics.engine} · ${diagnostics.locale} · " +
                if (diagnostics.needsNetwork == false) "系统报告可离线" else "联网要求未知",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { onStartText(title, text, rate, voice) },
            enabled = text.isNotBlank() && !importing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存并开始听") }
        Text("EPUB 仅支持无 DRM 文件；复杂图片、版式和表格不会进入朗读正文。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlayerScreen(
    book: BookEntity?,
    chapters: List<ChapterEntity>,
    snapshot: PlaybackSnapshot,
    onBack: () -> Unit,
    onCommand: (String) -> Unit,
    onRate: (Float) -> Unit,
    onSleep: (Int) -> Unit,
    onChapter: (Int) -> Unit,
) {
    var chapterExpanded by remember { mutableStateOf(false) }
    var sleepExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回书库") }
        Text(snapshot.title.ifBlank { book?.title ?: "播放" }, style = MaterialTheme.typography.headlineSmall)
        book?.author?.let { Text("作者：$it") }
        if (snapshot.totalChapters > 1 || chapters.size > 1) {
            Text("第 ${snapshot.currentChapter + 1} / ${snapshot.totalChapters.coerceAtLeast(chapters.size)} 章")
            Button(onClick = { chapterExpanded = true }) {
                Text(snapshot.chapterTitle.ifBlank { chapters.getOrNull(snapshot.currentChapter)?.title ?: "选择章节" })
            }
            DropdownMenu(chapterExpanded, { chapterExpanded = false }) {
                chapters.forEach { chapter ->
                    DropdownMenuItem(
                        text = { Text("${chapter.chapterIndex + 1}. ${chapter.title}") },
                        onClick = { chapterExpanded = false; onChapter(chapter.chapterIndex) },
                    )
                }
            }
        } else {
            Text(snapshot.chapterTitle.ifBlank { "正文" })
        }
        Text(
            if (snapshot.isFinished) "已读完 · 共 ${snapshot.totalSegments} 段"
            else "${snapshot.currentSegment + 1} / ${snapshot.totalSegments.coerceAtLeast(1)} 段",
            style = MaterialTheme.typography.titleMedium,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Text(
                snapshot.currentText.ifBlank { "正在准备朗读内容……" },
                Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { onCommand(PlaybackContract.ACTION_PREVIOUS) }) { Text("上一段") }
            Button(onClick = {
                onCommand(if (snapshot.isPlaying) PlaybackContract.ACTION_PAUSE else PlaybackContract.ACTION_PLAY)
            }) { Text(if (snapshot.isPlaying) "暂停" else if (snapshot.isFinished) "重新播放" else "播放") }
            OutlinedButton(onClick = { onCommand(PlaybackContract.ACTION_NEXT) }) { Text("下一段") }
        }
        TextButton(onClick = { onCommand(PlaybackContract.ACTION_STOP) }) { Text("停止并回到开头") }
        Text("语速：${snapshot.speechRate}x")
        RateButtons(snapshot.speechRate, onRate)
        Button(onClick = { sleepExpanded = true }) {
            Text(if (snapshot.sleepEndsAt > 0) "睡眠定时已开启" else "睡眠定时")
        }
        DropdownMenu(sleepExpanded, { sleepExpanded = false }) {
            listOf(15, 30, 60, 0).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(if (minutes == 0) "关闭定时" else "$minutes 分钟") },
                    onClick = { sleepExpanded = false; onSleep(minutes) },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    diagnostics: TtsDiagnostics,
    settings: AppSettings,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit,
) {
    var rate by remember { mutableStateOf(settings.defaultSpeechRate) }
    var voice by remember { mutableStateOf(settings.defaultVoiceName) }
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("基础设置", style = MaterialTheme.typography.headlineSmall)
        Text("默认语速")
        RateButtons(rate) { rate = it }
        Text("默认声音：${voice ?: diagnostics.voice}")
        if (diagnostics.chineseVoiceNames.size > 1) {
            Button(onClick = { expanded = true }) { Text("选择声音") }
            DropdownMenu(expanded, { expanded = false }) {
                diagnostics.chineseVoiceNames.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { voice = name; expanded = false })
                }
            }
            TextButton(onClick = { voice = null }) { Text("跟随系统默认") }
        } else {
            Text("荣耀当前只返回“${diagnostics.voice}”，应用无法可靠区分 YOYO 男声和女声。")
        }
        HorizontalDivider()
        Text("系统 TTS 信息", style = MaterialTheme.typography.titleMedium)
        Text("Engine：${diagnostics.engine}")
        Text("Voice：${diagnostics.voice}")
        Text("Locale：${diagnostics.locale}")
        Text("需要网络：${diagnostics.needsNetwork ?: "未知"}")
        Button(onClick = { onSave(AppSettings(rate, voice)) }, modifier = Modifier.fillMaxWidth()) {
            Text("保存默认设置")
        }
    }
}

@Composable
private fun RateButtons(current: Float, onSelect: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.8f, 1.0f, 1.2f, 1.5f).forEach { rate ->
            if (rate == current) Button(onClick = { onSelect(rate) }) { Text("${rate}x") }
            else OutlinedButton(onClick = { onSelect(rate) }) { Text("${rate}x") }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0) ?: "导入内容"
    }
    return uri.lastPathSegment ?: "导入内容"
}
