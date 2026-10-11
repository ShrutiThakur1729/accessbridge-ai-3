package com.accessbridgeai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

interface SpeechProvider {
    fun setLanguage(languageCode: String)
    fun startListening()
    fun stopListening()
    fun cancel()
    fun release()
    fun isAvailable(): Boolean
}

class AndroidSpeechProvider(
    private val context: Context,
    private val onSpeechRecognized: (String) -> Unit,
    private val onSpeechStateChanged: (String) -> Unit,
    private val onPartialResults: (String) -> Unit = {}
) : SpeechProvider {

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguageCode: String = "en-IN"

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
        }
    }

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun setLanguage(languageCode: String) {
        currentLanguageCode = when (languageCode) {
            "en" -> "en-IN"
            "hi" -> "hi-IN"
            "mr" -> "mr-IN"
            else -> languageCode
        }
    }

    override fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        onSpeechStateChanged("LISTENING")
        speechRecognizer?.startListening(intent)
    }

    override fun stopListening() {
        speechRecognizer?.stopListening()
    }

    override fun cancel() {
        speechRecognizer?.cancel()
        onSpeechStateChanged("IDLE")
    }

    override fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                onSpeechStateChanged("THINKING")
            }
            override fun onError(error: Int) {
                Log.e("AndroidSpeechProvider", "Speech recognition error code: $error")
                onSpeechStateChanged("IDLE")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.getOrNull(0)?.trim() ?: ""
                if (text.isNotEmpty()) {
                    onSpeechRecognized(text)
                }
                onSpeechStateChanged("IDLE")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.getOrNull(0)
                if (!text.isNullOrBlank()) {
                    onPartialResults(text)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}

class BhashiniSpeechProvider(
    private val context: Context,
    private val onSpeechRecognized: (String) -> Unit,
    private val onSpeechStateChanged: (String) -> Unit,
    private val fallbackProvider: SpeechProvider
) : SpeechProvider {

    private var currentLanguageCode: String = "hi-IN"

    override fun isAvailable(): Boolean = false // Set to true when BHASHINI API credentials/endpoints are active

    override fun setLanguage(languageCode: String) {
        currentLanguageCode = languageCode
        fallbackProvider.setLanguage(languageCode)
    }

    override fun startListening() {
        Log.d("BhashiniSpeechProvider", "Bhashini REST pipeline not active; using fallback provider.")
        fallbackProvider.startListening()
    }

    override fun stopListening() = fallbackProvider.stopListening()
    override fun cancel() = fallbackProvider.cancel()
    override fun release() = fallbackProvider.release()
}
