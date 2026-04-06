package com.openloud.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import androidx.core.content.FileProvider
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.openloud.MainActivity
import com.openloud.R
import com.openloud.data.db.AppDatabase
import com.openloud.data.db.ChapterEntity
import com.openloud.domain.chapter.ContentCleaner
import com.openloud.domain.tts.EdgeTTSEngine
import com.openloud.domain.tts.SystemTTSEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaBrowserServiceCompat() {

    private val binder = PlaybackBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: AppDatabase

    private lateinit var systemTTS: SystemTTSEngine
    private var edgeTTS: EdgeTTSEngine? = null
    private var useEdgeTTS: Boolean = false
    private lateinit var contentCleaner: ContentCleaner
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences
    private var currentVolumeBoostMb: Int = 0
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (_playbackState.value == PlaybackState.PAUSED) resume()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Could lower volume, but for TTS just pause
                hasAudioFocus = false
                pause()
            }
        }
    }
    private val voiceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "selected_voice" -> if (!useEdgeTTS) setVoice()
            "tts_engine" -> switchEngine()
            "edge_voice" -> updateEdgeVoice()
            "volume_boost" -> updateVolumeBoost()
        }
    }

    private var currentChapter: ChapterEntity? = null
    private var currentBookTitle: String? = null
    private var currentCoverPath: String? = null
    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex = 0
    private var playbackSpeed = 1.0f

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getDatabase(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        systemTTS = SystemTTSEngine(applicationContext)
        contentCleaner = ContentCleaner()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(voiceChangeListener)

        useEdgeTTS = prefs.getString("tts_engine", "system") == "edge"

        createNotificationChannel()
        setupMediaSession()

        // Set the session token for MediaBrowserService
        sessionToken = mediaSession.sessionToken

        serviceScope.launch {
            // Always initialize systemTTS first to generate a stable audio session ID
            val initialized = systemTTS.initialize()

            if (useEdgeTTS) {
                initEdgeTTS()
                updateVolumeBoost()
            } else {
                _ttsReady.value = initialized
                if (initialized) {
                    setupSystemTTSListener()
                    updateVolumeBoost()
                    if (pendingPlay) {
                        pendingPlay = false
                        play()
                    }
                }
            }
        }
    }

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady
    @Volatile
    private var pendingPlay = false

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audiobook Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for audiobook playback"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "OpenLoudPlayback").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                     MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resume()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    skipForward(15000)
                }

                override fun onSkipToPrevious() {
                    skipBackward(15000)
                }

                override fun onStop() {
                    stop()
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    mediaId?.let { handlePlayFromMediaId(it) }
                }

                override fun onSeekTo(pos: Long) {
                    val targetSentence = (pos / msPerSentence).toInt().coerceIn(0, sentences.size - 1)
                    currentSentenceIndex = targetSentence
                    _currentPosition.value = targetSentence
                    updateMediaSessionState()
                    if (_playbackState.value == PlaybackState.PLAYING) {
                        if (useEdgeTTS) edgeTTS?.stop() else systemTTS.stop()
                        playNextSentence()
                    }
                }
            })

            isActive = true
        }
    }

    private fun initEdgeTTS() {
        edgeTTS?.shutdown()
        // Pass the system TTS audio session ID so Edge TTS reuses the same session
        // and LoudnessEnhancer stays attached across sentence boundaries
        val sessionId = systemTTS.getAudioSessionId()
        edgeTTS = EdgeTTSEngine(cacheDir, sessionId).apply {
            val savedVoice = prefs.getString("edge_voice", EdgeTTSEngine.VOICES[0].id)
            setVoice(savedVoice ?: EdgeTTSEngine.VOICES[0].id)
            setSpeed(prefs.getFloat("playback_speed", 1.0f))
            setListener(object : EdgeTTSEngine.Listener {
                override fun onStart(utteranceId: String) {
                    _playbackState.value = PlaybackState.PLAYING
                    updateMediaSessionState()
                }
                override fun onDone(utteranceId: String) {
                    serviceScope.launch { playNextSentence() }
                }
                override fun onError(utteranceId: String) {
                    // Fallback: try system TTS for this sentence
                    android.util.Log.w("PlaybackService", "Edge TTS failed, falling back to system")
                    _playbackState.value = PlaybackState.ERROR
                }
            })
        }
        _ttsReady.value = true
    }

    private fun switchEngine() {
        val wasPlaying = _playbackState.value == PlaybackState.PLAYING
        stop()
        useEdgeTTS = prefs.getString("tts_engine", "system") == "edge"
        if (useEdgeTTS) {
            initEdgeTTS()
            updateVolumeBoost()
        } else {
            edgeTTS?.shutdown()
            edgeTTS = null
            _ttsReady.value = true
            setupSystemTTSListener()
            updateVolumeBoost()
        }
        if (wasPlaying) play()
    }

    private fun updateEdgeVoice() {
        val voiceId = prefs.getString("edge_voice", EdgeTTSEngine.VOICES[0].id)
        edgeTTS?.setVoice(voiceId ?: EdgeTTSEngine.VOICES[0].id)
    }

    private fun setupSystemTTSListener() {
        systemTTS.setProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _playbackState.value = PlaybackState.PLAYING
                updateMediaSessionState()
            }

            override fun onDone(utteranceId: String?) {
                serviceScope.launch {
                    playNextSentence()
                }
            }

            override fun onError(utteranceId: String?) {
                _playbackState.value = PlaybackState.ERROR
            }
        })
    }

    private fun playNextSentence() {
        if (currentSentenceIndex < sentences.size) {
            val sentence = sentences[currentSentenceIndex]
            _currentPosition.value = currentSentenceIndex
            updateMediaSessionState() // Update progress bar for Android Auto
            currentSentenceIndex++

            if (sentence == ContentCleaner.PARAGRAPH_BREAK) {
                // Insert a pause for paragraph breaks, then continue
                if (useEdgeTTS) {
                    edgeTTS?.speakWithPause("", "para_$currentSentenceIndex", 200)
                } else {
                    systemTTS.speakWithPause("", "para_$currentSentenceIndex", 200)
                }
                return
            }

            if (useEdgeTTS) {
                edgeTTS?.speakSentence(sentence, "sentence_$currentSentenceIndex")
                // Prefetch next non-break sentence for faster playback
                prefetchNextEdgeSentence()
            } else {
                systemTTS.speakSentence(sentence, "sentence_$currentSentenceIndex")
            }
        } else {
            // Chapter finished
            _playbackState.value = PlaybackState.PAUSED
            updateMediaSessionState()
        }
    }

    private fun prefetchNextEdgeSentence() {
        // Look ahead for the next actual sentence (skip paragraph breaks)
        var idx = currentSentenceIndex
        while (idx < sentences.size && sentences[idx] == ContentCleaner.PARAGRAPH_BREAK) {
            idx++
        }
        if (idx < sentences.size) {
            edgeTTS?.prefetch(sentences[idx])
        }
    }

    fun loadChapter(chapter: ChapterEntity, startSentence: Int = 0) {
        val wasPlaying = _playbackState.value == PlaybackState.PLAYING

        // Stop any ongoing TTS before switching chapters
        if (useEdgeTTS) edgeTTS?.stop() else systemTTS.stop()
        _playbackState.value = PlaybackState.IDLE

        currentChapter = chapter
        sentences = contentCleaner.splitIntoSentences(chapter.textContent)
        currentSentenceIndex = startSentence
        _currentPosition.value = startSentence
        updateMediaMetadata()
        updateMediaSessionState()
        updateNotification()

        // If was playing, automatically resume with new content
        if (wasPlaying) {
            play()
        }
    }

    fun play() {
        if (_playbackState.value == PlaybackState.PLAYING) return

        if (sentences.isEmpty()) return

        // If TTS isn't ready yet, queue play for when it's initialized
        if (!_ttsReady.value) {
            pendingPlay = true
            return
        }

        // Request audio focus
        if (!requestAudioFocus()) {
            Log.w("PlaybackService", "Failed to acquire audio focus")
        }

        _playbackState.value = PlaybackState.PLAYING
        playNextSentence()
        startForeground(NOTIFICATION_ID, createNotification())
        updateMediaSessionState()
    }

    fun pause() {
        if (useEdgeTTS) edgeTTS?.pause() else systemTTS.pause()
        _playbackState.value = PlaybackState.PAUSED
        updateMediaSessionState()
        updateNotification()
    }

    fun resume() {
        if (_playbackState.value == PlaybackState.PAUSED) {
            play()
        }
    }

    fun stop() {
        if (useEdgeTTS) edgeTTS?.stop() else systemTTS.stop()
        _playbackState.value = PlaybackState.IDLE
        // Preserve position so Android Auto / notification can resume from here
        abandonAudioFocus()
        updateMediaSessionState()
        stopForeground(true)
    }

    fun skipForward(millis: Int) {
        // Skip forward by a number of sentences (approximate)
        val sentencesToSkip = (millis / 3000).coerceAtLeast(1) // ~3 seconds per sentence
        currentSentenceIndex = (currentSentenceIndex + sentencesToSkip).coerceAtMost(sentences.size - 1)
        _currentPosition.value = currentSentenceIndex

        if (_playbackState.value == PlaybackState.PLAYING) {
            if (useEdgeTTS) edgeTTS?.stop() else systemTTS.stop()
            playNextSentence()
        }
    }

    fun skipBackward(millis: Int) {
        val sentencesToSkip = (millis / 3000).coerceAtLeast(1)
        currentSentenceIndex = (currentSentenceIndex - sentencesToSkip).coerceAtLeast(0)
        _currentPosition.value = currentSentenceIndex

        if (_playbackState.value == PlaybackState.PLAYING) {
            if (useEdgeTTS) edgeTTS?.stop() else systemTTS.stop()
            playNextSentence()
        }
    }

    fun setVoice() {
        if (useEdgeTTS) {
            updateEdgeVoice()
        } else {
            systemTTS.reloadVoice()
        }
        if (_playbackState.value == PlaybackState.PLAYING) {
            if (useEdgeTTS) edgeTTS?.stop() else systemTTS.stop()
            if (currentSentenceIndex > 0) currentSentenceIndex--
            playNextSentence()
        }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        if (useEdgeTTS) {
            edgeTTS?.setSpeed(speed)
        } else {
            systemTTS.setSpeed(speed)
        }
        if (_playbackState.value == PlaybackState.PLAYING) {
            if (useEdgeTTS) edgeTTS?.stop() else systemTTS.stop()
            if (currentSentenceIndex > 0) currentSentenceIndex--
            playNextSentence()
        }
    }

    /**
     * Set volume boost in millibels (0-1500 range, where 0=off, 1500=+15dB max)
     */
    fun setVolumeBoost(gainMb: Int) {
        currentVolumeBoostMb = gainMb.coerceIn(0, 1500)
        // Route to whichever engine is active — each owns its own LoudnessEnhancer
        systemTTS.setVolumeBoost(currentVolumeBoostMb)
        edgeTTS?.setVolumeBoost(currentVolumeBoostMb)
        Log.d("PlaybackService", "Volume boost set to ${currentVolumeBoostMb}mB")
    }

    private fun updateVolumeBoost() {
        val boostPercent = prefs.getInt("volume_boost", 0)
        val gainMb = (boostPercent * 15).coerceIn(0, 1500)
        setVolumeBoost(gainMb)
    }

    fun getCurrentSentenceIndex(): Int = currentSentenceIndex

    // Estimate milliseconds per sentence for progress reporting (Android Auto needs ms)
    private val msPerSentence = 3000L

    private fun estimatedPositionMs(): Long = currentSentenceIndex.toLong() * msPerSentence
    private fun estimatedDurationMs(): Long = sentences.size.toLong() * msPerSentence

    private fun updateMediaSessionState() {
        val state = when (_playbackState.value) {
            PlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackState.IDLE -> PlaybackStateCompat.STATE_STOPPED
            PlaybackState.ERROR -> PlaybackStateCompat.STATE_ERROR
        }

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, estimatedPositionMs(), if (_playbackState.value == PlaybackState.PLAYING) playbackSpeed else 0f)

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    fun setBookInfo(title: String?, coverPath: String?) {
        currentBookTitle = title
        currentCoverPath = coverPath
        // Pre-load cover bitmap for reuse in metadata + notification
        coverBitmap = loadCoverBitmap(coverPath)
        updateMediaMetadata()
    }

    private var coverBitmap: Bitmap? = null

    private fun loadCoverBitmap(path: String?, maxSize: Int = 512): Bitmap? {
        if (path == null) return null
        return try {
            // Decode with inSampleSize to avoid OOM on large covers
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            val width = options.outWidth
            val height = options.outHeight
            var sampleSize = 1
            while (width / sampleSize > maxSize || height / sampleSize > maxSize) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (_: Exception) { null }
    }

    private fun updateMediaMetadata() {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentChapter?.title ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentBookTitle ?: "OpenLoud")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentBookTitle ?: "OpenLoud")

        // Cover art bitmap — used by notification MediaStyle + Android Auto
        coverBitmap?.let { bmp ->
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bmp)
        }

        // Duration estimate for Android Auto progress bar
        if (sentences.isNotEmpty()) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, estimatedDurationMs())
        }

        // Content URI art — Android Auto requires content:// scheme with permission grant
        currentCoverPath?.let { path ->
            try {
                val file = java.io.File(path)
                if (file.exists()) {
                    val contentUri = FileProvider.getUriForFile(
                        this@PlaybackService, "com.openloud.fileprovider", file
                    )
                    // Grant read permission to all packages (needed for Android Auto which runs in a separate process)
                    grantUriPermission("com.google.android.projection.gearhead", contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val uriStr = contentUri.toString()
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uriStr)
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uriStr)
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uriStr)
                }
            } catch (_: Exception) {
                // Fall back to bitmap only (already set above)
            }
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun createNotification(): Notification {
        val chapterTitle = currentChapter?.title ?: "Ready to play"
        val bookTitle = currentBookTitle ?: "OpenLoud"

        val playPauseAction = if (_playbackState.value == PlaybackState.PLAYING) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "Pause",
                createPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "Play",
                createPendingIntent(ACTION_PLAY)
            )
        }

        // Tap notification to open app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(bookTitle)
            .setContentText(chapterTitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(R.drawable.ic_skip_back, "Rewind", createPendingIntent(ACTION_REWIND))
            .addAction(playPauseAction)
            .addAction(R.drawable.ic_skip_forward, "Forward", createPendingIntent(ACTION_FORWARD))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setOngoing(_playbackState.value == PlaybackState.PLAYING)

        // Cover art on notification
        coverBitmap?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stop()
            ACTION_FORWARD -> skipForward(15000)
            ACTION_REWIND -> skipBackward(15000)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Handle MediaBrowserService binding
        if (SERVICE_INTERFACE == intent?.action) {
            return super.onBind(intent)
        }
        // Handle local binding
        return binder
    }

    private fun requestAudioFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(true)
            .build()
        audioFocusRequest = request

        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
    }

    override fun onDestroy() {
        super.onDestroy()
        abandonAudioFocus()
        prefs.unregisterOnSharedPreferenceChangeListener(voiceChangeListener)
        systemTTS.shutdown()
        edgeTTS?.shutdown()
        mediaSession.release()
        serviceScope.cancel()
    }

    // MediaBrowserService methods for Android Auto support
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // Allow all clients (including Android Auto) to browse
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()

        serviceScope.launch(Dispatchers.IO) {
            val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()

            try {
                when {
                    parentId == MEDIA_ROOT_ID -> {
                        // Load all books, deduplicated by title (keep most recently read)
                        val bookList = database.bookDao().getAllBooksSync()
                        val seen = mutableSetOf<String>()
                        bookList.forEach { book ->
                            val key = book.title.lowercase().trim()
                            if (!seen.add(key)) return@forEach

                            val descBuilder = MediaDescriptionCompat.Builder()
                                .setMediaId("book_${book.id}")
                                .setTitle(book.title)
                                .setSubtitle(book.author ?: "Unknown Author")

                            // Set cover art via content URI (required by Android Auto) + bitmap
                            book.coverPath?.let { path ->
                                try {
                                    val file = java.io.File(path)
                                    if (file.exists()) {
                                        val contentUri = FileProvider.getUriForFile(
                                            this@PlaybackService,
                                            "com.openloud.fileprovider",
                                            file
                                        )
                                        descBuilder.setIconUri(contentUri)
                                    }
                                } catch (_: Exception) {}
                                loadCoverBitmap(path, 400)?.let { descBuilder.setIconBitmap(it) }
                            }

                            mediaItems.add(
                                MediaBrowserCompat.MediaItem(
                                    descBuilder.build(),
                                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE or MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                                )
                            )
                        }

                        withContext(Dispatchers.Main) {
                            result.sendResult(mediaItems)
                        }
                    }

                    parentId.startsWith("book_") -> {
                        // Load chapters for the book as playable items
                        val bookId = parentId.removePrefix("book_")
                        val book = database.bookDao().getBook(bookId)
                        val chapters = database.chapterDao().getChaptersForBookSync(bookId)
                        // Load book cover once for all chapters (content URI for Android Auto)
                        val bookCoverUri = book?.coverPath?.let { path ->
                            try {
                                val file = java.io.File(path)
                                if (file.exists()) FileProvider.getUriForFile(
                                    this@PlaybackService, "com.openloud.fileprovider", file
                                ) else null
                            } catch (_: Exception) { null }
                        }
                        val bookCoverBmp = loadCoverBitmap(book?.coverPath, 400)

                        chapters.forEach { chapter ->
                            val descBuilder = MediaDescriptionCompat.Builder()
                                .setMediaId("chapter_${chapter.bookId}_${chapter.id}")
                                .setTitle(chapter.title)
                                .setSubtitle(book?.title ?: "")

                            bookCoverUri?.let { descBuilder.setIconUri(it) }
                            bookCoverBmp?.let { descBuilder.setIconBitmap(it) }

                            mediaItems.add(
                                MediaBrowserCompat.MediaItem(
                                    descBuilder.build(),
                                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                                )
                            )
                        }

                        withContext(Dispatchers.Main) {
                            result.sendResult(mediaItems)
                        }
                    }

                    else -> {
                        withContext(Dispatchers.Main) {
                            result.sendResult(mediaItems)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Error loading children", e)
                withContext(Dispatchers.Main) {
                    result.sendResult(mediaItems)
                }
            }
        }
    }

    private fun handlePlayFromMediaId(mediaId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Save current position before switching
                saveCurrentPositionToDb()

                if (mediaId.startsWith("book_")) {
                    // User tapped a book — resume from saved position
                    val bookId = mediaId.removePrefix("book_")
                    val book = database.bookDao().getBook(bookId) ?: return@launch
                    val chapters = database.chapterDao().getChaptersForBookSync(bookId)
                    val chapter = chapters.getOrNull(book.currentChapterIndex) ?: chapters.firstOrNull() ?: return@launch

                    withContext(Dispatchers.Main) {
                        setBookInfo(book.title, book.coverPath)
                        loadChapter(chapter, book.currentCharOffset)
                        play()
                    }
                } else if (mediaId.startsWith("chapter_")) {
                    // Parse chapter ID from format: "chapter_{bookId}_{chapterId}"
                    val parts = mediaId.removePrefix("chapter_").split("_")
                    if (parts.size >= 2) {
                        val bookId = parts[0]
                        val chapterId = parts[1]

                        val book = database.bookDao().getBook(bookId)
                        val chapter = database.chapterDao().getChapter(chapterId)

                        if (book != null && chapter != null) {
                            withContext(Dispatchers.Main) {
                                setBookInfo(book.title, book.coverPath)
                                loadChapter(chapter, 0)
                                play()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Error playing from media ID", e)
            }
        }
    }

    /** Persist current read position to the database */
    private suspend fun saveCurrentPositionToDb() {
        val chapter = currentChapter ?: return
        val bookId = chapter.bookId
        val book = database.bookDao().getBook(bookId) ?: return
        val chapters = database.chapterDao().getChaptersForBookSync(bookId)
        val chapterIndex = chapters.indexOfFirst { it.id == chapter.id }.takeIf { it >= 0 } ?: return
        database.bookDao().updateReadPosition(bookId, chapterIndex, currentSentenceIndex, System.currentTimeMillis())
    }

    companion object {
        private const val CHANNEL_ID = "openloud_playback"
        private const val NOTIFICATION_ID = 1
        private const val MEDIA_ROOT_ID = "ROOT"

        const val ACTION_PLAY = "com.openloud.action.PLAY"
        const val ACTION_PAUSE = "com.openloud.action.PAUSE"
        const val ACTION_STOP = "com.openloud.action.STOP"
        const val ACTION_FORWARD = "com.openloud.action.FORWARD"
        const val ACTION_REWIND = "com.openloud.action.REWIND"
    }
}

enum class PlaybackState {
    IDLE, PLAYING, PAUSED, ERROR
}
