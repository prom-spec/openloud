package com.autobook.domain.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Edge TTS engine — uses Microsoft Edge's free neural TTS via WebSocket.
 * Produces high-quality audio without any API key.
 */
class EdgeTTSEngine(private val cacheDir: File) {

    companion object {
        private const val TAG = "EdgeTTSEngine"
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val GEC_VERSION = "1-131.0.2903.51"
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
    private var isReady = true

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

    fun speakSentence(text: String, utteranceId: String) {
        if (text.isBlank()) {
            listener?.onDone(utteranceId)
            return
        }

        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                listener?.onStart(utteranceId)
                val audioBytes = synthesize(text)
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

    private suspend fun synthesize(text: String): ByteArray? = suspendCancellableCoroutine { cont ->
        val audioBuffer = mutableListOf<ByteArray>()
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val url = buildWsUrl(uuid)

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Send speech config
                val speechConfig = buildSpeechConfig(uuid)
                webSocket.send(speechConfig)

                // Send SSML
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
                // Binary frame: first 2 bytes = header length, rest = audio
                val data = bytes.toByteArray()
                if (data.size > 2) {
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (data.size > headerLen + 2) {
                        val audio = data.copyOfRange(headerLen + 2, data.size)
                        audioBuffer.add(audio)
                    }
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
        var t = System.currentTimeMillis().toDouble() / 1000
        t += 11644473600
        t -= (t % 300)
        t *= 1e9 / 100
        val s = "%d$TRUSTED_TOKEN".format(t.toLong()).toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(s)
        return BigInteger(1, digest).toString(16).uppercase().padStart(64, '0')
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
