package com.openloud.ui.settings

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openloud.domain.tts.EdgeTTSEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SettingsViewModel(private val context: Context) : ViewModel() {

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices

    private val _selectedVoice = MutableStateFlow<String?>(null)
    val selectedVoice: StateFlow<String?> = _selectedVoice

    private val _skipSeconds = MutableStateFlow(15)
    val skipSeconds: StateFlow<Int> = _skipSeconds

    private val _volumeBoost = MutableStateFlow(0)
    val volumeBoost: StateFlow<Int> = _volumeBoost

    // TTS engine selection
    private val _ttsEngine = MutableStateFlow("system")
    val ttsEngine: StateFlow<String> = _ttsEngine

    // Edge TTS voices
    private val _edgeVoices = MutableStateFlow(EdgeTTSEngine.VOICES)
    val edgeVoices: StateFlow<List<EdgeTTSEngine.Voice>> = _edgeVoices

    private val _selectedEdgeVoice = MutableStateFlow<String?>(null)
    val selectedEdgeVoice: StateFlow<String?> = _selectedEdgeVoice

    /** Current language filter — set from the book being read, or null for all */
    private val _languageFilter = MutableStateFlow<String?>(null)
    val languageFilter: StateFlow<String?> = _languageFilter

    private var tts: TextToSpeech? = null
    private var allVoices: List<VoiceInfo> = emptyList()
    private var edgeTTSPreview: EdgeTTSEngine? = null

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        loadSelectedVoice()
        loadSkipSeconds()
        loadVolumeBoost()
        loadTTSEngine()
        loadVoices()
    }

    private fun loadTTSEngine() {
        _ttsEngine.value = prefs.getString(PREF_TTS_ENGINE, "system") ?: "system"
        _selectedEdgeVoice.value = prefs.getString(PREF_EDGE_VOICE, EdgeTTSEngine.VOICES[0].id)
    }

    fun setTTSEngine(engine: String) {
        _ttsEngine.value = engine
        prefs.edit().putString(PREF_TTS_ENGINE, engine).apply()
    }

    fun selectEdgeVoice(voiceId: String) {
        _selectedEdgeVoice.value = voiceId
        prefs.edit().putString(PREF_EDGE_VOICE, voiceId).apply()
    }

    fun testEdgeVoice(voiceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (edgeTTSPreview == null) {
                    edgeTTSPreview = EdgeTTSEngine(context.cacheDir)
                }
                edgeTTSPreview!!.setVoice(voiceId)
                edgeTTSPreview!!.setListener(object : EdgeTTSEngine.Listener {
                    override fun onStart(utteranceId: String) {}
                    override fun onDone(utteranceId: String) {}
                    override fun onError(utteranceId: String) {}
                })
                edgeTTSPreview!!.speakSentence("Hello, this is a test of this voice.", "test")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadSelectedVoice() {
        _selectedVoice.value = prefs.getString(PREF_SELECTED_VOICE, null)
    }

    private fun loadVoices() {
        viewModelScope.launch {
            val initialized = initializeTTS()
            if (initialized) {
                allVoices = withContext(Dispatchers.IO) {
                    tts?.voices?.filter { it.locale.language == "en" && it.locale.country == "US" }?.map { voice ->
                        VoiceInfo(
                            name = voice.name,
                            displayName = voice.locale.displayName,
                            locale = voice.locale.toString(),
                            languageCode = voice.locale.language,
                            quality = voice.quality,
                            isNetwork = voice.isNetworkConnectionRequired
                        )
                    }?.sortedWith(
                        compareByDescending<VoiceInfo> { it.quality }
                            .thenBy { if (it.isNetwork) 1 else 0 } // offline first
                            .thenBy { it.name }
                    ) ?: emptyList()
                }
                applyFilter()
            }
        }
    }

    /** Filter voices to match a book's language. Pass null to show all. */
    fun setLanguageFilter(languageCode: String?) {
        _languageFilter.value = languageCode
        applyFilter()
    }

    private fun applyFilter() {
        _voices.value = allVoices
    }

    private suspend fun initializeTTS(): Boolean = suspendCoroutine { continuation ->
        tts = TextToSpeech(context) { status ->
            continuation.resume(status == TextToSpeech.SUCCESS)
        }
    }

    fun selectVoice(voiceName: String) {
        _selectedVoice.value = voiceName
        prefs.edit().putString(PREF_SELECTED_VOICE, voiceName).apply()
    }

    private fun loadSkipSeconds() {
        _skipSeconds.value = prefs.getInt(PREF_SKIP_SECONDS, 15)
    }

    fun setSkipSeconds(seconds: Int) {
        _skipSeconds.value = seconds
        prefs.edit().putInt(PREF_SKIP_SECONDS, seconds).apply()
    }

    private fun loadVolumeBoost() {
        _volumeBoost.value = prefs.getInt(PREF_VOLUME_BOOST, 0)
    }

    fun setVolumeBoost(boostPercent: Int) {
        val clampedPercent = boostPercent.coerceIn(0, 100)
        _volumeBoost.value = clampedPercent
        prefs.edit().putInt(PREF_VOLUME_BOOST, clampedPercent).apply()
    }

    fun testVoice(voiceName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val voice = tts?.voices?.find { it.name == voiceName }
                    if (voice != null) {
                        tts?.voice = voice
                        tts?.speak(
                            "Hello, this is a test of this voice.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "test"
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
        edgeTTSPreview?.shutdown()
    }

    companion object {
        const val PREF_SELECTED_VOICE = "selected_voice"
        const val PREF_SKIP_SECONDS = "skip_seconds"
        const val PREF_VOLUME_BOOST = "volume_boost"
        const val PREF_TTS_ENGINE = "tts_engine"
        const val PREF_EDGE_VOICE = "edge_voice"
    }
}

data class VoiceInfo(
    val name: String,
    val displayName: String,
    val locale: String,
    val languageCode: String,
    val quality: Int,
    val isNetwork: Boolean = false
)
