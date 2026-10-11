package com.accessbridgeai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class VoiceAssistantManager(
    private val context: Context,
    private val onSpeechRecognized: (String) -> Unit,
    private val onSpeechStateChanged: (String) -> Unit,
    private val onPartialResults: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var currentLanguageCode: String = "en-IN"

    init {
        // Initialize TTS
        textToSpeech = TextToSpeech(context, this)

        // Initialize STT if available
        val isSttAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d("VoiceManager", "Speech Recognition Available: $isSttAvailable")
        if (isSttAvailable) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
        }
    }

    fun setLanguage(code: String) {
        val bcp47 = when (code) {
            "en" -> "en-IN"
            "hi" -> "hi-IN"
            "mr" -> "mr-IN"
            else -> "en-IN"
        }
        currentLanguageCode = bcp47
        if (isTtsInitialized) {
            val locale = Locale.forLanguageTag(bcp47)
            val result = textToSpeech?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("VoiceManager", "TTS Language not supported: $bcp47")
            }
        }
        Log.d("VoiceManager", "Language set to: $currentLanguageCode")
    }

    // --- 1. TEXT-TO-SPEECH (TTS) LOGIC ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale.forLanguageTag(currentLanguageCode)
            val result = textToSpeech?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("VoiceManager", "TTS Language not supported: $currentLanguageCode")
            } else {
                isTtsInitialized = true
            }
        } else {
            Log.e("VoiceManager", "TTS Initialization failed")
        }
    }

    fun speak(text: String, languageCode: String? = null) {
        if (!isTtsInitialized) return
        
        // Handle temporary language override for TTS
        val finalLanguage = if (languageCode != null) {
            when (languageCode) {
                "en" -> "en-IN"
                "hi" -> "hi-IN"
                "mr" -> "mr-IN"
                else -> languageCode // Backend might send full tag
            }
        } else {
            currentLanguageCode
        }
        
        Log.d("VoiceManager", "TTS_LANGUAGE:$finalLanguage")
        textToSpeech?.setLanguage(Locale.forLanguageTag(finalLanguage))

        onSpeechStateChanged("SPEAKING")
        Log.d("VoiceManager", "TTS_RESULT: Speaking text")
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AccessBridgeTTS")

        // Listen for when speech finishes to reset state
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onSpeechStateChanged("IDLE")
            }
            override fun onError(utteranceId: String?) {
                onSpeechStateChanged("IDLE")
            }
        })
    }

    // --- 2. SPEECH-TO-TEXT (STT) LOGIC ---
    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        Log.d("VoiceManager", "STT_START")
        Log.d("VoiceManager", "STT_LANGUAGE:$currentLanguageCode")
        onSpeechStateChanged("LISTENING")
        speechRecognizer?.startListening(intent)
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("VoiceManager", "onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                Log.d("VoiceManager", "onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d("VoiceManager", "onEndOfSpeech")
                onSpeechStateChanged("THINKING")
            }
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
                    SpeechRecognizer.ERROR_SERVER -> "Error from server"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
                    else -> "Unknown error: $error"
                }
                Log.e("VoiceManager", "STT_ERROR:$errorMessage")
                
                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    onSpeechStateChanged("LANGUAGE_UNSUPPORTED_ON_DEVICE:$currentLanguageCode")
                } else {
                    onSpeechStateChanged("IDLE")
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.getOrNull(0)
                Log.d("VoiceManager", "STT_RESULT:$transcript")
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    onSpeechRecognized(spokenText)
                    onSpeechStateChanged("IDLE")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("VoiceManager", "onPartialResults: ${matches?.getOrNull(0)}")
                if (!matches.isNullOrEmpty()) {
                    onPartialResults(matches[0])
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    // --- Cleanup ---
    fun release() {
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
