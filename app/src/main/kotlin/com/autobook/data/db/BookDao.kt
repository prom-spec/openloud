package com.autobook.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun getBookFlow(bookId: String): Flow<BookEntity?>

    @Query("SELECT COUNT(*) FROM books WHERE LOWER(title) = LOWER(:title)")
    suspend fun countByTitle(title: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET title = :newTitle WHERE id = :bookId")
    suspend fun renameBook(bookId: String, newTitle: String)

    @Query("UPDATE books SET author = :newAuthor WHERE id = :bookId")
    suspend fun updateAuthor(bookId: String, newAuthor: String?)

    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :bookId")
    suspend fun updateCoverPath(bookId: String, coverPath: String?)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("UPDATE books SET currentChapterIndex = :chapterIndex, currentCharOffset = :charOffset, lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateReadPosition(bookId: String, chapterIndex: Int, charOffset: Int, timestamp: Long)
}
