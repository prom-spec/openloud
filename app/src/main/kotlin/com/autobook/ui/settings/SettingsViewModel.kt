package com.autobook.ui.settings

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    /** Current language filter — set from the book being read, or null for all */
    private val _languageFilter = MutableStateFlow<String?>(null)
    val languageFilter: StateFlow<String?> = _languageFilter

    private var tts: TextToSpeech? = null
    private var allVoices: List<VoiceInfo> = emptyList()

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        loadSelectedVoice()
        loadVoices()
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
    }

    companion object {
        const val PREF_SELECTED_VOICE = "selected_voice"
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
