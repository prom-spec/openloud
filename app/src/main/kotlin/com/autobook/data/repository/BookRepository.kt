package com.autobook.data.repository

import com.autobook.data.db.BookDao
import com.autobook.data.db.BookEntity
import com.autobook.data.db.ChapterDao
import com.autobook.data.db.ChapterEntity
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) {
    fun getAllBooks(): Flow<List<BookEntity>> = bookDao.getAllBooks()

    suspend fun getBook(bookId: String): BookEntity? = bookDao.getBook(bookId)

    fun getBookFlow(bookId: String): Flow<BookEntity?> = bookDao.getBookFlow(bookId)

    suspend fun bookExistsByTitle(title: String): Boolean = bookDao.countByTitle(title) > 0

    suspend fun insertBook(book: BookEntity) = bookDao.insertBook(book)

    suspend fun updateBook(book: BookEntity) = bookDao.updateBook(book)

    suspend fun renameBook(bookId: String, newTitle: String) = bookDao.renameBook(bookId, newTitle)

    suspend fun deleteBook(book: BookEntity) = bookDao.deleteBook(book)

    suspend fun deleteBookWithChapters(book: BookEntity) {
        chapterDao.deleteChaptersForBook(book.id)
        bookDao.deleteBook(book)
        // Clean up files
        book.filePath?.let { java.io.File(it).delete() }
        book.coverPath?.let { java.io.File(it).delete() }
    }

    suspend fun updateReadPosition(bookId: String, chapterIndex: Int, charOffset: Int) {
        bookDao.updateReadPosition(bookId, chapterIndex, charOffset, System.currentTimeMillis())
    }

    fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>> =
        chapterDao.getChaptersForBook(bookId)

    suspend fun getChaptersForBookSync(bookId: String): List<ChapterEntity> =
        chapterDao.getChaptersForBookSync(bookId)

    suspend fun getChapterByIndex(bookId: String, index: Int): ChapterEntity? =
        chapterDao.getChapterByIndex(bookId, index)

    suspend fun insertChapters(chapters: List<ChapterEntity>) =
        chapterDao.insertChapters(chapters)
}
