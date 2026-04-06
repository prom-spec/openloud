package com.openloud.domain.tts

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Edge TTS engine — uses Microsoft Edge's free neural TTS via WebSocket.
 * Produces high-quality audio without any API key.
 */
class EdgeTTSEngine(private val cacheDir: File, private val audioSessionId: Int = 0) {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVolumeBoostMb: Int = 0

    init {
        if (audioSessionId != 0) {
            try {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(0)
                    enabled = false
                }
                Log.d(TAG, "EdgeTTS LoudnessEnhancer created on session $audioSessionId")
            } catch (e: Exception) {
                Log.w(TAG, "EdgeTTS LoudnessEnhancer not supported: ${e.message}")
            }
        }
    }

    fun setVolumeBoost(gainMb: Int) {
        currentVolumeBoostMb = gainMb.coerceIn(0, 1500)
        loudnessEnhancer?.let {
            try {
                it.setTargetGain(currentVolumeBoostMb)
                it.enabled = currentVolumeBoostMb > 0
                Log.d(TAG, "Edge volume boost set to ${currentVolumeBoostMb}mB")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set Edge volume boost: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "EdgeTTSEngine"
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val GEC_VERSION = "1-143.0.3650.75"
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

        // Best English neural voices
        val VOICES = listOf(
            Voice("en-US-AvaMultilingualNeural", "Ava (Female, Conversational)", "Female"),
            Voice("en-US-AndrewMultilingualNeural", "Andrew (Male, Conversational)", "Male"),
            Voice("en-US-EmmaMultilingualNeural", "Emma (Female, Warm)", "Female"),
            Voice("en-US-BrianMultilingualNeural", "Brian (Male, Warm)", "Male"),
            Voice("en-US-JennyNeural", "Jenny (Female, Clear)", "Female"),
            Voice("en-US-GuyNeural", "Guy (Male, Clear)", "Male"),
            Voice("en-US-AriaNeural", "Aria (Female, Expressive)", "Female"),
            Voice("en-US-DavisNeural", "Davis (Male, Natural)", "Male"),
            Voice("en-US-AmberNeural", "Amber (Female, Friendly)", "Female"),
            Voice("en-US-ChristopherNeural", "Christopher (Male, Reliable)", "Male"),
        )
    }

    data class Voice(val id: String, val displayName: String, val gender: String)

    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var listener: Listener? = null
    private var currentVoice: String = VOICES[0].id
    private var speechRate: String = "+0%"
    private var pitch: String = "+0Hz"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchedAudio: Pair<String, ByteArray>? = null  // text -> audio
    private var isReady = true
    private var clockSkewSeconds: Double = 0.0
    private var retryCount = 0

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun setVoice(voiceId: String) {
        currentVoice = voiceId
    }

    fun setSpeed(speed: Float) {
        // Convert multiplier to percentage: 1.0 = +0%, 1.5 = +50%, 0.5 = -50%
        val pct = ((speed - 1.0f) * 100).toInt()
        speechRate = if (pct >= 0) "+${pct}%" else "${pct}%"
    }

    /**
     * Pre-synthesize the next sentence while current is playing.
     * Call this as soon as you know what the next sentence will be.
     */
    fun prefetch(text: String) {
        if (text.isBlank()) return
        // Don't re-prefetch same text
        if (prefetchedAudio?.first == text) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            try {
                val audio = synthesize(text)
                if (audio != null && audio.isNotEmpty()) {
                    prefetchedAudio = text to audio
                    Log.d(TAG, "Prefetched ${audio.size} bytes for: ${text.take(40)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed: ${e.message}")
            }
        }
    }

    fun speakSentence(text: String, utteranceId: String) {
        if (text.isBlank()) {
            listener?.onDone(utteranceId)
            return
        }

        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                listener?.onStart(utteranceId)

                // Check if we already have this audio prefetched
                val cached = prefetchedAudio
                val audioBytes = if (cached != null && cached.first == text) {
                    Log.d(TAG, "Using prefetched audio for: ${text.take(40)}")
                    prefetchedAudio = null
                    cached.second
                } else {
                    prefetchJob?.cancel() // Cancel any in-progress prefetch
                    prefetchedAudio = null
                    synthesize(text)
                }

                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    playAudio(audioBytes, utteranceId)
                } else {
                    Log.e(TAG, "No audio received for: ${text.take(50)}")
                    withContext(Dispatchers.Main) {
                        listener?.onError(utteranceId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TTS error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    listener?.onError(utteranceId)
                }
            }
        }
    }

    fun speakWithPause(text: String, utteranceId: String, pauseMs: Int = 500) {
        currentJob?.cancel()
        currentJob = scope.launch {
            delay(pauseMs.toLong())
            if (text.isNotBlank()) {
                speakSentence(text, utteranceId)
            } else {
                listener?.onDone(utteranceId)
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.reset()
            } catch (_: Exception) {}
        }
    }

    fun pause() = stop()

    fun shutdown() {
        stop()
        scope.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        client.dispatcher.executorService.shutdown()
    }

    fun isReady() = isReady

    private suspend fun synthesize(text: String): ByteArray? {
        // Try with clock skew correction on 403
        for (attempt in 0..2) {
            val result = doSynthesize(text)
            if (result != null) {
                retryCount = 0
                return result
            }
            // On failure, try to fix clock skew
            Log.w(TAG, "Synthesis attempt $attempt failed, adjusting clock skew...")
            resolveClockSkew()
            delay(500)
        }
        return null
    }

    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private suspend fun resolveClockSkew() {
        try {
            val url = buildWsUrl(UUID.randomUUID().toString().replace("-", "")).replace("wss://", "https://")
            val request = Request.Builder().url(url).head()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .build()
            val response = client.newCall(request).execute()
            val dateHeader = response.header("Date")
            response.close()
            if (dateHeader != null) {
                val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("GMT")
                val serverTime = fmt.parse(dateHeader)?.time?.toDouble()?.div(1000)
                if (serverTime != null) {
                    val clientTime = System.currentTimeMillis().toDouble() / 1000
                    clockSkewSeconds = serverTime - clientTime
                    Log.d(TAG, "Clock skew adjusted to ${clockSkewSeconds}s")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clock skew resolution failed: ${e.message}")
        }
    }

    private suspend fun doSynthesize(text: String): ByteArray? = suspendCancellableCoroutine { cont ->
        val audioBuffer = mutableListOf<ByteArray>()
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val url = buildWsUrl(uuid)

        val muid = generateMuid()
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val speechConfig = buildSpeechConfig(uuid)
                webSocket.send(speechConfig)
                val ssml = buildSSML(text, uuid)
                webSocket.send(ssml)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "done")
                    val combined = combineAudio(audioBuffer)
                    if (cont.isActive) cont.resume(combined) {}
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary frame: first 2 bytes are header, rest is audio data
                val data = bytes.toByteArray()
                if (data.size > 2) {
                    val audio = data.copyOfRange(2, data.size)
                    audioBuffer.add(audio)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                if (cont.isActive) cont.resume(null) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) {
                    val combined = combineAudio(audioBuffer)
                    cont.resume(combined) {}
                }
            }
        })

        cont.invokeOnCancellation {
            ws.cancel()
        }
    }

    private fun combineAudio(buffers: List<ByteArray>): ByteArray {
        val totalSize = buffers.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (buf in buffers) {
            System.arraycopy(buf, 0, result, offset, buf.size)
            offset += buf.size
        }
        return result
    }

    private suspend fun playAudio(audioBytes: ByteArray, utteranceId: String) {
        val tmpFile = File(cacheDir, "edge_tts_${System.currentTimeMillis()}.mp3")
        try {
            FileOutputStream(tmpFile).use { it.write(audioBytes) }

            withContext(Dispatchers.Main) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    // Use stable audio session ID so LoudnessEnhancer stays attached
                    if (audioSessionId != 0) {
                        setAudioSessionId(audioSessionId)
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(tmpFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        tmpFile.delete()
                        listener?.onDone(utteranceId)
                    }
                    setOnErrorListener { _, _, _ ->
                        tmpFile.delete()
                        listener?.onError(utteranceId)
                        true
                    }
                    start()
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    fun getAudioSessionId(): Int = audioSessionId

    // --- Protocol helpers ---

    private fun buildWsUrl(connectionId: String): String {
        return "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_TOKEN" +
                "&Sec-MS-GEC=${genSecMsGec()}" +
                "&Sec-MS-GEC-Version=$GEC_VERSION" +
                "&ConnectionId=$connectionId"
    }

    private fun buildSpeechConfig(requestId: String): String {
        val timestamp = datetimeStr()
        return "X-Timestamp:$timestamp\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n" +
                "\r\n" +
                """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"$OUTPUT_FORMAT"}}}}"""
    }

    private fun buildSSML(text: String, requestId: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val timestamp = datetimeStr()
        return "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:$timestamp\r\n" +
                "Path:ssml\r\n" +
                "\r\n" +
                """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US"><voice name="$currentVoice"><prosody pitch="$pitch" rate="$speechRate" volume="+0%">$escaped</prosody></voice></speak>"""
    }

    private fun genSecMsGec(): String {
        var t = (System.currentTimeMillis().toDouble() / 1000) + clockSkewSeconds
        t += 11644473600.0
        t -= (t % 300.0)
        t *= 1e9 / 100.0
        val ticks = t.toLong()
        val strToHash = "${ticks}$TRUSTED_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun datetimeStr(): String {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val format = DateTimeFormatter.ofPattern(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000' '(Coordinated Universal Time)'",
            Locale.ENGLISH
        )
        return now.format(format)
    }
}
