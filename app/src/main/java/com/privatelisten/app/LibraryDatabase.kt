package com.privatelisten.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

enum class BookSourceType { TEXT, EPUB }

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val sourceType: String,
    val totalChapters: Int,
    val currentChapter: Int = 0,
    val currentSegment: Int = 0,
    val currentOffset: Int = 0,
    val speechRate: Float = 1.0f,
    val lastPlayedAt: Long = 0L,
    val importedAt: Long = System.currentTimeMillis(),
    val voiceName: String? = null,
    val isFinished: Boolean = false,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "chapterIndex"], unique = true),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val title: String,
    val text: String,
)

data class ChapterDraft(val title: String, val text: String)

data class ImportedBook(
    val title: String,
    val author: String? = null,
    val sourceType: BookSourceType,
    val chapters: List<ChapterDraft>,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books ORDER BY lastPlayedAt DESC, importedAt DESC")
    suspend fun listBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBook(bookId: Long): BookEntity?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getChapters(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex LIMIT 1")
    suspend fun getChapter(bookId: Long, chapterIndex: Int): ChapterEntity?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun countBooks(): Int

    @Insert
    suspend fun insertBook(book: BookEntity): Long

    @Insert
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Transaction
    suspend fun insertImportedBook(book: BookEntity, chapters: List<ChapterDraft>): Long {
        val bookId = insertBook(book.copy(totalChapters = chapters.size))
        insertChapters(
            chapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    bookId = bookId,
                    chapterIndex = index,
                    title = chapter.title.ifBlank { "第 ${index + 1} 章" },
                    text = chapter.text,
                )
            },
        )
        return bookId
    }

    @Query(
        """
        UPDATE books SET currentChapter = :chapter, currentSegment = :segment,
            currentOffset = :offset, speechRate = :rate, isFinished = :finished,
            lastPlayedAt = :playedAt
        WHERE id = :bookId
        """,
    )
    suspend fun updateProgress(
        bookId: Long,
        chapter: Int,
        segment: Int,
        offset: Int,
        rate: Float,
        finished: Boolean,
        playedAt: Long,
    )

    @Query("UPDATE books SET title = :title WHERE id = :bookId")
    suspend fun rename(bookId: Long, title: String)

    @Query("UPDATE books SET speechRate = :rate, voiceName = :voiceName WHERE id = :bookId")
    suspend fun updatePlaybackSettings(bookId: Long, rate: Float, voiceName: String?)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: Long)
}

@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PrivateListenDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile private var instance: PrivateListenDatabase? = null

        fun get(context: Context): PrivateListenDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PrivateListenDatabase::class.java,
                "private-listen-library.db",
            ).build().also { instance = it }
        }

        fun resetForTests() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}

object LibraryRepository {
    private fun dao(context: Context) = PrivateListenDatabase.get(context).libraryDao()

    suspend fun listBooks(context: Context): List<BookEntity> = dao(context).listBooks()

    suspend fun getBook(context: Context, bookId: Long): BookEntity? = dao(context).getBook(bookId)

    suspend fun getChapters(context: Context, bookId: Long): List<ChapterEntity> =
        dao(context).getChapters(bookId)

    suspend fun getChapter(context: Context, bookId: Long, chapterIndex: Int): ChapterEntity? =
        dao(context).getChapter(bookId, chapterIndex)

    suspend fun addBook(context: Context, imported: ImportedBook, rate: Float, voiceName: String?): Long {
        require(imported.chapters.isNotEmpty()) { "书籍中没有可朗读的正文" }
        require(imported.chapters.any { it.text.isNotBlank() }) { "书籍中没有可朗读的正文" }
        val id = dao(context).insertImportedBook(
            BookEntity(
                title = imported.title.ifBlank { "未命名" },
                author = imported.author?.ifBlank { null },
                sourceType = imported.sourceType.name,
                totalChapters = imported.chapters.size,
                speechRate = rate,
                voiceName = voiceName,
                lastPlayedAt = 0L,
            ),
            imported.chapters,
        )
        ContentStore.selectBook(context, id)
        LibraryBackup.writeAutomatic(context)
        return id
    }

    suspend fun updateProgress(
        context: Context,
        bookId: Long,
        chapter: Int,
        segment: Int,
        offset: Int,
        rate: Float,
        finished: Boolean,
    ) {
        dao(context).updateProgress(
            bookId = bookId,
            chapter = chapter.coerceAtLeast(0),
            segment = segment.coerceAtLeast(0),
            offset = offset.coerceAtLeast(0),
            rate = rate,
            finished = finished,
            playedAt = System.currentTimeMillis(),
        )
    }

    suspend fun rename(context: Context, bookId: Long, title: String) {
        dao(context).rename(bookId, title.ifBlank { "未命名" })
        LibraryBackup.writeAutomatic(context)
    }

    suspend fun delete(context: Context, bookId: Long) {
        dao(context).deleteBook(bookId)
        if (ContentStore.selectedBookId(context) == bookId) ContentStore.selectBook(context, 0)
        LibraryBackup.writeAutomatic(context)
    }

    suspend fun updatePlaybackSettings(
        context: Context,
        bookId: Long,
        rate: Float,
        voiceName: String?,
    ) {
        dao(context).updatePlaybackSettings(bookId, rate, voiceName)
        LibraryBackup.writeAutomatic(context, force = true)
    }

    suspend fun migrateLegacyIfNeeded(context: Context) {
        if (ContentStore.isLegacyMigrationDone(context)) return
        if (dao(context).countBooks() == 0) {
            ContentStore.loadLegacy(context)?.let { legacy ->
                val bookId = dao(context).insertImportedBook(
                    BookEntity(
                        title = legacy.title,
                        author = null,
                        sourceType = BookSourceType.TEXT.name,
                        totalChapters = 1,
                        currentSegment = legacy.currentSegment,
                        currentOffset = legacy.currentOffset,
                        speechRate = legacy.speechRate,
                        lastPlayedAt = legacy.lastPlayedAt,
                        voiceName = legacy.voiceName,
                        isFinished = legacy.isFinished,
                    ),
                    listOf(ChapterDraft("正文", legacy.originalText)),
                )
                ContentStore.selectBook(context, bookId)
            }
        }
        ContentStore.markLegacyMigrationDone(context)
        LibraryBackup.writeAutomatic(context)
    }
}
