package com.autobook.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autobook.data.db.BookEntity
import com.autobook.data.repository.BookRepository
import com.autobook.domain.cover.CoverArtFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun reSearchCover(book: BookEntity) {
        viewModelScope.launch {
            _researchingCover.value = book.id
            try {
                // Delete old cover
                book.coverPath?.let { java.io.File(it).delete() }
                val fetcher = CoverArtFetcher(context)
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
}
