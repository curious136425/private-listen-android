package com.privatelisten.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object LibraryBackup {
    private const val VERSION = 2
    private const val AUTO_BACKUP_INTERVAL_MS = 60_000L
    @Volatile private var lastAutomaticBackupAt = 0L

    suspend fun exportLibrary(context: Context): String {
        val dao = PrivateListenDatabase.get(context).libraryDao()
        val books = JSONArray()
        dao.listBooks().forEach { book ->
            val chapters = JSONArray()
            dao.getChapters(book.id).forEach { chapter ->
                chapters.put(
                    JSONObject()
                        .put("title", chapter.title)
                        .put("text", chapter.text),
                )
            }
            books.put(
                JSONObject()
                    .put("title", book.title)
                    .put("author", book.author ?: JSONObject.NULL)
                    .put("sourceType", book.sourceType)
                    .put("currentChapter", book.currentChapter)
                    .put("currentSegment", book.currentSegment)
                    .put("currentOffset", book.currentOffset)
                    .put("speechRate", book.speechRate.toDouble())
                    .put("lastPlayedAt", book.lastPlayedAt)
                    .put("importedAt", book.importedAt)
                    .put("voiceName", book.voiceName ?: JSONObject.NULL)
                    .put("isFinished", book.isFinished)
                    .put("chapters", chapters),
            )
        }
        return JSONObject()
            .put("backupVersion", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("books", books)
            .toString(2)
    }

    suspend fun restoreLibrary(context: Context, jsonText: String): List<Long> {
        val json = JSONObject(jsonText)
        return when (json.optInt("backupVersion", -1)) {
            1 -> listOf(restoreVersion1(context, json))
            VERSION -> restoreVersion2(context, json)
            else -> error("不支持的备份版本")
        }.also { ids ->
            ids.lastOrNull()?.let { ContentStore.selectBook(context, it) }
            writeAutomatic(context, force = true)
        }
    }

    suspend fun writeAutomatic(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAutomaticBackupAt < AUTO_BACKUP_INTERVAL_MS) return
        val directory = File(context.filesDir, "backups")
        if (!directory.exists() && !directory.mkdirs()) return
        val temporary = File(directory, "library-latest.json.tmp")
        val destination = File(directory, "library-latest.json")
        temporary.writeText(exportLibrary(context), Charsets.UTF_8)
        val moved = runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.isSuccess
        if (moved) lastAutomaticBackupAt = now
    }

    fun automaticBackupFile(context: Context): File =
        File(File(context.filesDir, "backups"), "library-latest.json")

    private suspend fun restoreVersion1(context: Context, json: JSONObject): Long {
        val text = json.getString("originalText")
        require(text.isNotBlank()) { "备份中没有文字内容" }
        val rate = allowedRate(json.optDouble("speechRate", 1.0).toFloat())
        val dao = PrivateListenDatabase.get(context).libraryDao()
        return dao.insertImportedBook(
            BookEntity(
                title = json.optString("title", "未命名").ifBlank { "未命名" },
                author = null,
                sourceType = BookSourceType.TEXT.name,
                totalChapters = 1,
                currentSegment = json.optInt("currentSegment", 0).coerceAtLeast(0),
                currentOffset = json.optInt("currentOffset", 0).coerceAtLeast(0),
                speechRate = rate,
                lastPlayedAt = json.optLong("lastPlayedAt", System.currentTimeMillis()),
                voiceName = if (json.isNull("voiceName")) null else json.optString("voiceName"),
                isFinished = json.optBoolean("isFinished", false),
            ),
            listOf(ChapterDraft("正文", text)),
        )
    }

    private suspend fun restoreVersion2(context: Context, json: JSONObject): List<Long> {
        val books = json.optJSONArray("books") ?: error("备份中没有书库数据")
        require(books.length() <= 10_000) { "备份中的书籍数量异常" }
        val dao = PrivateListenDatabase.get(context).libraryDao()
        return buildList {
            for (bookIndex in 0 until books.length()) {
                val source = books.getJSONObject(bookIndex)
                val chapterArray = source.optJSONArray("chapters") ?: continue
                require(chapterArray.length() in 1..10_000) { "章节数量异常" }
                val chapters = buildList {
                    for (chapterIndex in 0 until chapterArray.length()) {
                        val chapter = chapterArray.getJSONObject(chapterIndex)
                        val text = chapter.optString("text")
                        if (text.isNotBlank()) {
                            add(ChapterDraft(chapter.optString("title", "第 ${chapterIndex + 1} 章"), text))
                        }
                    }
                }
                if (chapters.isEmpty()) continue
                add(
                    dao.insertImportedBook(
                        BookEntity(
                            title = source.optString("title", "未命名").ifBlank { "未命名" },
                            author = if (source.isNull("author")) null else source.optString("author"),
                            sourceType = source.optString("sourceType", BookSourceType.TEXT.name)
                                .takeIf { it in BookSourceType.entries.map(BookSourceType::name) }
                                ?: BookSourceType.TEXT.name,
                            totalChapters = chapters.size,
                            currentChapter = source.optInt("currentChapter", 0)
                                .coerceIn(0, chapters.lastIndex),
                            currentSegment = source.optInt("currentSegment", 0).coerceAtLeast(0),
                            currentOffset = source.optInt("currentOffset", 0).coerceAtLeast(0),
                            speechRate = allowedRate(source.optDouble("speechRate", 1.0).toFloat()),
                            lastPlayedAt = source.optLong("lastPlayedAt", System.currentTimeMillis()),
                            importedAt = source.optLong("importedAt", System.currentTimeMillis()),
                            voiceName = if (source.isNull("voiceName")) null else source.optString("voiceName"),
                            isFinished = source.optBoolean("isFinished", false),
                        ),
                        chapters,
                    ),
                )
            }
        }.also { require(it.isNotEmpty()) { "备份中没有可恢复的正文" } }
    }

    private fun allowedRate(rate: Float): Float =
        rate.takeIf { it in setOf(0.8f, 1.0f, 1.2f, 1.5f) } ?: 1.0f
}
