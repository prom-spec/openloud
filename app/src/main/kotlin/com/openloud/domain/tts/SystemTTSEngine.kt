package com.openloud.domain.tts

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SystemTTSEngine(private val context: Context) {

    companion object {
        private const val TAG = "SystemTTSEngine"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var audioSessionId: Int = 0

    suspend fun initialize(): Boolean = suspendCoroutine { continuation ->
        // Generate a stable audio session ID for LoudnessEnhancer
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioSessionId = audioManager.generateAudioSessionId()
        Log.d(TAG, "Generated audio session ID: $audioSessionId")

        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                tts?.language = Locale.US
                selectBestVoice()
                // Natural narration pitch — slightly lower
                tts?.setPitch(0.95f)
                // Slightly slower than default for narration clarity
                tts?.setSpeechRate(0.92f)
            }
            continuation.resume(isInitialized)
        }
    }

    private fun selectBestVoice() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedVoice = prefs.getString("selected_voice", null)

        if (savedVoice != null) {
            tts?.voices?.find { it.name == savedVoice }?.let { voice ->
                tts?.voice = voice
                Log.d(TAG, "Using saved voice: ${voice.name}")
                return
            }
        }

        // Only en-US voices, sorted same as Settings screen
        val englishVoices = tts?.voices?.filter {
            it.locale.language == "en" && it.locale.country == "US"
        }?.sortedWith(compareByDescending<Voice> {
            it.quality
        }.thenBy {
            if (it.isNetworkConnectionRequired) 1 else 0 // offline first (matches Settings)
        }.thenBy {
            it.name
        })

        // Default to voice #12 (index 11) — reads best per user preference
        val defaultIndex = 11
        val bestVoice = if (englishVoices != null && englishVoices.size > defaultIndex) {
            englishVoices[defaultIndex]
        } else {
            englishVoices?.firstOrNull()
        }

        if (bestVoice != null) {
            tts?.voice = bestVoice
            Log.d(TAG, "Auto-selected voice #${(englishVoices?.indexOf(bestVoice) ?: -1) + 1}: ${bestVoice.name} (quality=${bestVoice.quality}, locale=${bestVoice.locale}, network=${bestVoice.isNetworkConnectionRequired})")
        } else {
            Log.w(TAG, "No suitable voice found, using default")
        }

        Log.d(TAG, "Available EN voices: ${englishVoices?.take(15)?.mapIndexed { i, v -> "#${i+1} ${v.name} (q=${v.quality}, net=${v.isNetworkConnectionRequired})" }}")
    }

    /** Re-read saved voice from SharedPreferences and apply it */
    fun reloadVoice() {
        selectBestVoice()
    }

    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed * 0.92f) // Apply our base rate adjustment
    }

    /**
     * Speak a chunk of text (may be multiple sentences batched together).
     * Uses SSML when supported for more natural prosody.
     */
    fun speakChunk(text: String, utteranceId: String) {
        if (!isInitialized || text.isBlank()) return

        // Route audio through our stable session ID so LoudnessEnhancer works
        val params = Bundle().apply {
            if (audioSessionId != 0) {
                putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
            }
        }

        // Try SSML for natural prosody (pauses at commas, emphasis)
        val ssml = buildSSML(text)
        val result = tts?.speak(ssml, TextToSpeech.QUEUE_ADD, params, utteranceId)

        // If SSML failed (some engines don't support it), fall back to plain text
        if (result != TextToSpeech.SUCCESS) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        }
    }

    /**
     * Build SSML markup for more natural speech.
     */
    private fun buildSSML(text: String): String {
        val sb = StringBuilder()
        sb.append("<speak>")
        sb.append("<prosody rate=\"medium\" pitch=\"-2st\">")

        // Add break hints at semicolons and em-dashes for natural pauses
        var processed = text
            .replace(";", ";<break time=\"200ms\"/>")
            .replace(" — ", " <break time=\"250ms\"/> ")
            .replace(" - ", " <break time=\"150ms\"/> ")

        sb.append(processed)
        sb.append("</prosody>")
        sb.append("</speak>")
        return sb.toString()
    }

    // Keep legacy method for compatibility
    fun speakSentence(text: String, utteranceId: String) {
        speakChunk(text, utteranceId)
    }

    fun speakWithPause(text: String, utteranceId: String, pauseMs: Int = 500) {
        if (!isInitialized) return
        tts?.playSilentUtterance(pauseMs.toLong(), TextToSpeech.QUEUE_ADD, "pause_$utteranceId")
        if (text.isNotBlank()) {
            speakChunk(text, utteranceId)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun pause() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun setProgressListener(listener: UtteranceProgressListener) {
        tts?.setOnUtteranceProgressListener(listener)
    }

    fun isPlaying(): Boolean {
        return tts?.isSpeaking == true
    }

    fun getAvailableVoices(): List<Voice> {
        return tts?.voices?.filter {
            it.locale.language == "en"
        }?.sortedWith(compareByDescending<Voice> {
            it.quality
        }.thenBy { it.name }) ?: emptyList()
    }

    /**
     * Get the audio session ID for applying audio effects like LoudnessEnhancer.
     * Returns 0 if not available.
     */
    fun getAudioSessionId(): Int = audioSessionId
}
