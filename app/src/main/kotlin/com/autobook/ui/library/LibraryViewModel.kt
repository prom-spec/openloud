package com.autobook.ui.library

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autobook.data.db.BookEntity
import com.autobook.data.repository.BookRepository
import com.autobook.domain.cover.CoverArtFetcher
import com.autobook.domain.cover.CoverResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class LibraryViewModel(
    private val repository: BookRepository,
    private val context: Context
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    val books: StateFlow<List<BookEntity>> = repository.getAllBooks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            repository.deleteBook(book)
        }
    }

    fun deleteBookWithFiles(book: BookEntity) {
        viewModelScope.launch {
            repository.deleteBookWithChapters(book)
        }
    }

    fun renameBook(book: BookEntity, newTitle: String) {
        viewModelScope.launch {
            repository.renameBook(book.id, newTitle)
        }
    }

    fun updateAuthor(book: BookEntity, newAuthor: String?) {
        viewModelScope.launch {
            repository.updateAuthor(book.id, newAuthor)
        }
    }

    fun editBook(book: BookEntity, newTitle: String, newAuthor: String?) {
        viewModelScope.launch {
            if (newTitle != book.title) repository.renameBook(book.id, newTitle)
            if (newAuthor != book.author) repository.updateAuthor(book.id, newAuthor)
        }
    }

    private val _researchingCover = MutableStateFlow<String?>(null)
    val researchingCover: StateFlow<String?> = _researchingCover

    // Cover search results
    private val _coverSearchResults = MutableStateFlow<List<CoverResult>>(emptyList())
    val coverSearchResults: StateFlow<List<CoverResult>> = _coverSearchResults

    private val _coverSearchLoading = MutableStateFlow(false)
    val coverSearchLoading: StateFlow<Boolean> = _coverSearchLoading

    private val _selectedCoverBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedCoverBitmap: StateFlow<Bitmap?> = _selectedCoverBitmap

    private val fetcher = CoverArtFetcher(context)

    fun searchCovers(book: BookEntity) {
        viewModelScope.launch {
            _coverSearchLoading.value = true
            _coverSearchResults.value = emptyList()
            _selectedCoverBitmap.value = null
            try {
                val results = fetcher.searchCovers(book.title, book.author)
                _coverSearchResults.value = results
            } catch (e: Exception) {
                _coverSearchResults.value = emptyList()
            } finally {
                _coverSearchLoading.value = false
            }
        }
    }

    fun selectCoverForCrop(cover: CoverResult) {
        viewModelScope.launch {
            val bitmap = fetcher.downloadBitmap(cover.url)
            _selectedCoverBitmap.value = bitmap
        }
    }

    fun clearCoverSearch() {
        _coverSearchResults.value = emptyList()
        _selectedCoverBitmap.value = null
        _coverSearchLoading.value = false
    }

    fun reSearchCover(book: BookEntity) {
        viewModelScope.launch {
            _researchingCover.value = book.id
            try {
                // Delete old cover
                book.coverPath?.let { java.io.File(it).delete() }
                val newCoverPath = fetcher.fetchAndSaveCover(book.title, book.author, book.id)
                repository.updateCoverPath(book.id, newCoverPath)
            } catch (e: Exception) {
                // If fetch fails, clear cover
                repository.updateCoverPath(book.id, null)
            } finally {
                _researchingCover.value = null
            }
        }
    }

    fun setCustomCover(bookId: String, bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val coversDir = File(context.filesDir, "covers")
                if (!coversDir.exists()) coversDir.mkdirs()
                val coverFile = File(coversDir, "$bookId.jpg")
                // Delete old cover first
                if (coverFile.exists()) coverFile.delete()
                FileOutputStream(coverFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                repository.updateCoverPath(bookId, coverFile.absolutePath)
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }
}
