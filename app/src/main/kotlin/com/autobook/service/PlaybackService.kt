package com.autobook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.autobook.MainActivity
import com.autobook.R
import com.autobook.data.db.ChapterEntity
import com.autobook.domain.chapter.ContentCleaner
import com.autobook.domain.tts.EdgeTTSEngine
import com.autobook.domain.tts.SystemTTSEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlaybackService : Service() {

    private val binder = PlaybackBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var systemTTS: SystemTTSEngine
    private var edgeTTS: EdgeTTSEngine? = null
    private var useEdgeTTS: Boolean = false
    private lateinit var contentCleaner: ContentCleaner
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences
    private val voiceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "selected_voice" -> if (!useEdgeTTS) setVoice()
            "tts_engine" -> switchEngine()
            "edge_voice" -> updateEdgeVoice()
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

        systemTTS = SystemTTSEngine(applicationContext)
        contentCleaner = ContentCleaner()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(voiceChangeListener)

        useEdgeTTS = prefs.getString("tts_engine", "system") == "edge"

        createNotificationChannel()
        setupMediaSession()

        if (useEdgeTTS) {
            initEdgeTTS()
        }

        serviceScope.launch {
            val initialized = systemTTS.initialize()
            if (!useEdgeTTS) {
                _ttsReady.value = initialized
                if (initialized) {
                    setupSystemTTSListener()
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
        mediaSession = MediaSessionCompat(this, "AIAnyBookPlayback").apply {
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
            })

            isActive = true
        }
    }

    private fun initEdgeTTS() {
        edgeTTS?.shutdown()
        edgeTTS = EdgeTTSEngine(cacheDir).apply {
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
        } else {
            edgeTTS?.shutdown()
            edgeTTS = null
            _ttsReady.value = true
            setupSystemTTSListener()
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
            currentSentenceIndex++

            if (sentence == ContentCleaner.PARAGRAPH_BREAK) {
                // Insert a pause for paragraph breaks, then continue
                if (useEdgeTTS) {
                    edgeTTS?.speakWithPause("", "para_$currentSentenceIndex", 600)
                } else {
                    systemTTS.speakWithPause("", "para_$currentSentenceIndex", 600)
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
        currentChapter = chapter
        sentences = contentCleaner.splitIntoSentences(chapter.textContent)
        currentSentenceIndex = startSentence
        _currentPosition.value = startSentence
        updateMediaMetadata()
        updateNotification()
    }

    fun play() {
        if (_playbackState.value == PlaybackState.PLAYING) return

        if (sentences.isEmpty()) return

        // If TTS isn't ready yet, queue play for when it's initialized
        if (!_ttsReady.value) {
            pendingPlay = true
            return
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
        currentSentenceIndex = 0
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

    fun getCurrentSentenceIndex(): Int = currentSentenceIndex

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
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, currentSentenceIndex.toLong(), playbackSpeed)

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    fun setBookInfo(title: String?, coverPath: String?) {
        currentBookTitle = title
        currentCoverPath = coverPath
        updateMediaMetadata()
    }

    private fun updateMediaMetadata() {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentChapter?.title ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentBookTitle ?: "AIAnyBook")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentBookTitle ?: "AIAnyBook")

        // Load cover art
        currentCoverPath?.let { path ->
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                }
            } catch (_: Exception) {}
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun createNotification(): Notification {
        val chapterTitle = currentChapter?.title ?: "Ready to play"
        val bookTitle = currentBookTitle ?: "AIAnyBook"

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

        // Cover art
        currentCoverPath?.let { path ->
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                }
            } catch (_: Exception) {}
        }

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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(voiceChangeListener)
        systemTTS.shutdown()
        edgeTTS?.shutdown()
        mediaSession.release()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "autobook_playback"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.autobook.action.PLAY"
        const val ACTION_PAUSE = "com.autobook.action.PAUSE"
        const val ACTION_STOP = "com.autobook.action.STOP"
        const val ACTION_FORWARD = "com.autobook.action.FORWARD"
        const val ACTION_REWIND = "com.autobook.action.REWIND"
    }
}

enum class PlaybackState {
    IDLE, PLAYING, PAUSED, ERROR
}
