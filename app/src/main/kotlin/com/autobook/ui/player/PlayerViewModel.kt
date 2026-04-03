package com.autobook.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autobook.data.db.BookEntity
import com.autobook.data.db.ChapterEntity
import com.autobook.data.repository.BookRepository
import com.autobook.service.PlaybackService
import com.autobook.service.PlaybackState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val repository: BookRepository,
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val PREF_SPEED = "playback_speed"
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    // Pending action to execute once service is connected
    private var pendingChapter: ChapterEntity? = null
    private var pendingStartOffset: Int = 0
    private var pendingPlay: Boolean = false
    // User clicked play before book finished loading
    private var playWhenReady: Boolean = false

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book: StateFlow<BookEntity?> = _book

    private val _chapters = MutableStateFlow<List<ChapterEntity>>(emptyList())
    val chapters: StateFlow<List<ChapterEntity>> = _chapters

    private val _currentChapter = MutableStateFlow<ChapterEntity?>(null)
    val currentChapter: StateFlow<ChapterEntity?> = _currentChapter

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition

    private val _playbackSpeed = MutableStateFlow(
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getFloat(PREF_SPEED, 1.0f)
    )
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private val _skipSeconds = MutableStateFlow(prefs.getInt("skip_seconds", 15))
    val skipSeconds: StateFlow<Int> = _skipSeconds

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as PlaybackService.PlaybackBinder
            playbackService = binder.getService()
            serviceBound = true

            // Apply saved speed
            val savedSpeed = _playbackSpeed.value
            if (savedSpeed != 1.0f) {
                playbackService?.setSpeed(savedSpeed)
            }

            // Execute pending load if any
            pendingChapter?.let { chapter ->
                Log.d(TAG, "Loading pending chapter: ${chapter.title}, willPlay=$pendingPlay")
                playbackService?.loadChapter(chapter, pendingStartOffset)

                // If we also have pending play, start playback after loading
                if (pendingPlay) {
                    playbackService?.play()
                    pendingPlay = false
                }

                pendingChapter = null
            }

            viewModelScope.launch {
                playbackService?.playbackState?.collect { state ->
                    _playbackState.value = state
                }
            }

            viewModelScope.launch {
                playbackService?.currentPosition?.collect { position ->
                    _currentPosition.value = position
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            playbackService = null
            serviceBound = false
        }
    }

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            val bookEntity = repository.getBook(bookId)
            _book.value = bookEntity
            Log.d(TAG, "Book loaded: ${bookEntity?.title}, chapters: ${bookEntity?.totalChapters}")

            val chapterList = repository.getChaptersForBookSync(bookId)
            _chapters.value = chapterList
            Log.d(TAG, "Chapters loaded: ${chapterList.size}")

            // Mark this book as most recently accessed so the Resume card updates
            bookEntity?.let { repository.updateReadPosition(bookId, it.currentChapterIndex, it.currentCharOffset) }

            bookEntity?.let { book ->
                val chapter = chapterList.getOrNull(book.currentChapterIndex)
                _currentChapter.value = chapter
                Log.d(TAG, "Current chapter: ${chapter?.title}")

                chapter?.let {
                    if (serviceBound && playbackService != null) {
                        playbackService?.loadChapter(it, book.currentCharOffset)
                        // If user already clicked play, start now
                        if (playWhenReady) {
                            playWhenReady = false
                            playbackService?.play()
                        }
                    } else {
                        // Service not ready yet — queue it
                        pendingChapter = it
                        pendingStartOffset = book.currentCharOffset
                        pendingPlay = playWhenReady
                        playWhenReady = false
                        bindService()
                    }
                }
            }
        }
    }

    private fun bindService() {
        if (!serviceBound) {
            Log.d(TAG, "Binding service...")
            val intent = Intent(context, PlaybackService::class.java)
            context.startService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun playPause() {
        Log.d(TAG, "playPause called, state=${_playbackState.value}, service=${playbackService != null}, chapter=${_currentChapter.value?.title}")

        // Book hasn't loaded yet — queue play for when it's ready
        if (_currentChapter.value == null) {
            Log.d(TAG, "Chapter not loaded yet, queuing play")
            playWhenReady = true
            return
        }

        if (playbackService == null) {
            // Service not bound yet — bind and queue play
            val chapter = _currentChapter.value
            if (chapter != null) {
                pendingChapter = chapter
                pendingStartOffset = _book.value?.currentCharOffset ?: 0
                pendingPlay = true
                bindService()
            }
            return
        }

        playbackService?.let { service ->
            when (_playbackState.value) {
                PlaybackState.PLAYING -> service.pause()
                PlaybackState.PAUSED -> service.play()
                PlaybackState.IDLE -> {
                    // Load current chapter and play
                    _currentChapter.value?.let { ch ->
                        service.loadChapter(ch, _book.value?.currentCharOffset ?: 0)
                        service.play()
                    }
                }
                else -> {}
            }
        }
    }

    private fun skipMillis(): Int {
        return prefs.getInt("skip_seconds", 15) * 1000
    }

    fun skipForward() {
        playbackService?.skipForward(skipMillis())
        savePosition()
    }

    fun skipBackward() {
        playbackService?.skipBackward(skipMillis())
        savePosition()
    }

    fun nextChapter() {
        viewModelScope.launch {
            val book = _book.value ?: return@launch
            val nextIndex = book.currentChapterIndex + 1

            if (nextIndex < _chapters.value.size) {
                val nextChapter = _chapters.value[nextIndex]
                _currentChapter.value = nextChapter

                repository.updateReadPosition(book.id, nextIndex, 0)
                _book.value = book.copy(currentChapterIndex = nextIndex)
                playbackService?.stop()
                playbackService?.loadChapter(nextChapter, 0)
                playbackService?.play()
            }
        }
    }

    fun previousChapter() {
        viewModelScope.launch {
            val book = _book.value ?: return@launch
            val prevIndex = (book.currentChapterIndex - 1).coerceAtLeast(0)

            val prevChapter = _chapters.value[prevIndex]
            _currentChapter.value = prevChapter

            repository.updateReadPosition(book.id, prevIndex, 0)
            _book.value = book.copy(currentChapterIndex = prevIndex)
            playbackService?.stop()
            playbackService?.loadChapter(prevChapter, 0)
            playbackService?.play()
        }
    }

    fun seekToProgress(progress: Float) {
        viewModelScope.launch {
            val book = _book.value ?: return@launch
            val chapterList = _chapters.value
            if (chapterList.isEmpty()) return@launch

            val totalWords = chapterList.sumOf { it.textContent.split(Regex("\\s+")).size }
            val targetWords = (totalWords * progress).toInt()

            var accumulated = 0
            for ((index, chapter) in chapterList.withIndex()) {
                val chapterWords = chapter.textContent.split(Regex("\\s+")).size
                if (accumulated + chapterWords >= targetWords || index == chapterList.lastIndex) {
                    // Seek to this chapter
                    val wordsIntoChapter = (targetWords - accumulated).coerceAtLeast(0)
                    // Convert words into approximate sentence index
                    val sentences = chapter.textContent.split(Regex("[.!?]+\\s+"))
                    var sentenceWords = 0
                    var sentenceIndex = 0
                    for ((si, sentence) in sentences.withIndex()) {
                        sentenceWords += sentence.split(Regex("\\s+")).size
                        if (sentenceWords >= wordsIntoChapter) {
                            sentenceIndex = si
                            break
                        }
                        sentenceIndex = si
                    }

                    _currentChapter.value = chapter
                    repository.updateReadPosition(book.id, index, sentenceIndex)
                    _book.value = book.copy(currentChapterIndex = index, currentCharOffset = sentenceIndex)
                    playbackService?.stop()
                    playbackService?.loadChapter(chapter, sentenceIndex)
                    playbackService?.play()
                    break
                }
                accumulated += chapterWords
            }
        }
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        playbackService?.setSpeed(speed)
        prefs.edit().putFloat(PREF_SPEED, speed).apply()
    }

    fun savePosition() {
        viewModelScope.launch {
            val book = _book.value ?: return@launch
            val chapterIndex = book.currentChapterIndex
            val sentenceIndex = playbackService?.getCurrentSentenceIndex() ?: 0

            repository.updateReadPosition(book.id, chapterIndex, sentenceIndex)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (serviceBound) {
            savePosition()
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
