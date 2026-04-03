package com.autobook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.autobook.R
import com.autobook.data.db.ChapterEntity
import com.autobook.domain.chapter.ContentCleaner
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

    private lateinit var ttsEngine: SystemTTSEngine
    private lateinit var contentCleaner: ContentCleaner
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences
    private val voiceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "selected_voice") {
            setVoice()
        }
    }

    private var currentChapter: ChapterEntity? = null
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

        ttsEngine = SystemTTSEngine(applicationContext)
        contentCleaner = ContentCleaner()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(voiceChangeListener)

        createNotificationChannel()
        setupMediaSession()

        serviceScope.launch {
            val initialized = ttsEngine.initialize()
            ttsReady = initialized
            if (initialized) {
                setupTTSListener()
                // If play was requested before TTS was ready, start now
                if (pendingPlay) {
                    pendingPlay = false
                    play()
                }
            }
        }
    }

    @Volatile
    private var ttsReady = false
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
        mediaSession = MediaSessionCompat(this, "AutoBookPlayback").apply {
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

    private fun setupTTSListener() {
        ttsEngine.setProgressListener(object : UtteranceProgressListener() {
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
                ttsEngine.speakWithPause("", "para_$currentSentenceIndex", 600)
                return
            }

            ttsEngine.speakSentence(sentence, "sentence_$currentSentenceIndex")
        } else {
            // Chapter finished
            _playbackState.value = PlaybackState.PAUSED
            updateMediaSessionState()
        }
    }

    fun loadChapter(chapter: ChapterEntity, startSentence: Int = 0) {
        currentChapter = chapter
        sentences = contentCleaner.splitIntoSentences(chapter.textContent)
        currentSentenceIndex = startSentence
        _currentPosition.value = startSentence
    }

    fun play() {
        if (_playbackState.value == PlaybackState.PLAYING) return

        if (sentences.isEmpty()) return

        // If TTS isn't ready yet, queue play for when it's initialized
        if (!ttsReady) {
            pendingPlay = true
            return
        }

        _playbackState.value = PlaybackState.PLAYING
        playNextSentence()
        startForeground(NOTIFICATION_ID, createNotification())
        updateMediaSessionState()
    }

    fun pause() {
        ttsEngine.pause()
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
        ttsEngine.stop()
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
            ttsEngine.stop()
            playNextSentence()
        }
    }

    fun skipBackward(millis: Int) {
        val sentencesToSkip = (millis / 3000).coerceAtLeast(1)
        currentSentenceIndex = (currentSentenceIndex - sentencesToSkip).coerceAtLeast(0)
        _currentPosition.value = currentSentenceIndex

        if (_playbackState.value == PlaybackState.PLAYING) {
            ttsEngine.stop()
            playNextSentence()
        }
    }

    fun setVoice() {
        ttsEngine.reloadVoice()
        // Restart current utterance so new voice takes effect immediately
        if (_playbackState.value == PlaybackState.PLAYING) {
            ttsEngine.stop()
            if (currentSentenceIndex > 0) currentSentenceIndex--
            playNextSentence()
        }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        ttsEngine.setSpeed(speed)
        // Restart current utterance so new speed takes effect immediately
        if (_playbackState.value == PlaybackState.PLAYING) {
            ttsEngine.stop()
            // Back up one sentence since playNextSentence increments
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

    private fun createNotification(): Notification {
        val title = currentChapter?.title ?: "AutoBook"

        val playPauseAction = if (_playbackState.value == PlaybackState.PLAYING) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                createPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                createPendingIntent(ACTION_PLAY)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Playing")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_rew, "Rewind", createPendingIntent(ACTION_REWIND))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_ff, "Forward", createPendingIntent(ACTION_FORWARD))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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
        ttsEngine.shutdown()
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
