package com.privatelisten.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigrationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun prepareCleanQaStorage() {
        PrivateListenDatabase.resetForTests()
        context.deleteDatabase("private-listen-library.db")
        context.getSharedPreferences("private_listen_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanQaStorage() {
        PrivateListenDatabase.resetForTests()
        context.deleteDatabase("private-listen-library.db")
        context.getSharedPreferences("private_listen_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun migratesVersion011SharedPreferencesIntoRoom() = runBlocking {
        context.getSharedPreferences("private_listen_state", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("title", "旧版内容")
            .putString("original_text", "第一段。第二段。")
            .putInt("current_segment", 1)
            .putInt("current_offset", 3)
            .putFloat("speech_rate", 1.2f)
            .putLong("last_played_at", 123456L)
            .putString("voice_name", "zh")
            .putBoolean("is_finished", false)
            .commit()

        LibraryRepository.migrateLegacyIfNeeded(context)

        val books = LibraryRepository.listBooks(context)
        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("旧版内容", book.title)
        assertEquals(1, book.currentSegment)
        assertEquals(3, book.currentOffset)
        assertEquals(1.2f, book.speechRate)
        assertEquals(book.id, ContentStore.selectedBookId(context))
        val chapters = LibraryRepository.getChapters(context, book.id)
        assertEquals("第一段。第二段。", chapters.single().text)
        assertTrue(ContentStore.isLegacyMigrationDone(context))
    }

    @Test
    fun libraryBackupRoundTripRestoresMultipleBooksAndProgress() = runBlocking {
        val first = LibraryRepository.addBook(
            context,
            ImportedBook("文字一", sourceType = BookSourceType.TEXT, chapters = listOf(ChapterDraft("正文", "甲。"))),
            1.0f,
            null,
        )
        val second = LibraryRepository.addBook(
            context,
            ImportedBook(
                "书籍二",
                "作者",
                BookSourceType.EPUB,
                listOf(ChapterDraft("第一章", "乙。"), ChapterDraft("第二章", "丙。")),
            ),
            1.5f,
            "zh",
        )
        LibraryRepository.updateProgress(context, second, 1, 0, 1, 1.5f, false)
        val backup = LibraryBackup.exportLibrary(context)

        LibraryRepository.delete(context, first)
        LibraryRepository.delete(context, second)
        assertTrue(LibraryRepository.listBooks(context).isEmpty())

        val restoredIds = LibraryBackup.restoreLibrary(context, backup)
        val restored = LibraryRepository.listBooks(context)
        assertEquals(2, restoredIds.size)
        assertEquals(setOf("文字一", "书籍二"), restored.map { it.title }.toSet())
        val epub = restored.single { it.title == "书籍二" }
        assertEquals(2, LibraryRepository.getChapters(context, epub.id).size)
        assertEquals(1, epub.currentChapter)
        assertEquals(1, epub.currentOffset)
        assertFalse(epub.isFinished)
    }
}
