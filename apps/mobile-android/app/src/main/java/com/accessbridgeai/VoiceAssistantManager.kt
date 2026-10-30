package com.accessbridgeai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: Any? = null // AudioFocusRequest on API 26+
    private var currentLanguageCode: String = "en-IN"

    @Volatile
    private var isRecording = false

    private val mainHandler = Handler(Looper.getMainLooper())

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
            else -> code
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

    // --- CHIME & AUDIO STREAMING ---

    fun playChime() {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            toneG.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 150)
        } catch (e: Exception) {
            Log.e("VoiceManager", "Chime failed", e)
        }
    }

    fun startAudioStreaming(taskId: String, onChunk: (JSONObject) -> Unit) {
        if (isRecording) return

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("VoiceManager", "AudioRecord initialization failed")
                return
            }

            audioRecord.startRecording()
            isRecording = true
            onSpeechStateChanged("LISTENING")

            Thread {
                try {
                    val buffer = ByteArray(bufferSize)
                    while (isRecording) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val chunk = JSONObject().apply {
                                put("type", "AUDIO_CHUNK")
                                put("task_id", taskId)
                                put("audio", Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                                put("timestamp", System.currentTimeMillis())
                            }
                            onChunk(chunk)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VoiceManager", "Error in audio streaming loop", e)
                } finally {
                    try {
                        audioRecord.stop()
                        audioRecord.release()
                    } catch (e: Exception) {
                        Log.e("VoiceManager", "Error releasing AudioRecord", e)
                    }
                    Log.d("VoiceManager", "Audio streaming thread stopped.")
                }
            }.start()
        } catch (e: SecurityException) {
            Log.e("VoiceManager", "Microphone permission denied", e)
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to start AudioRecord", e)
        }
    }

    fun stopAudioStreaming() {
        isRecording = false
    }

    fun stopAllOperations() {
        isRecording = false
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error stopping speech recognizer", e)
        }
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        textToSpeech?.stop()
        onSpeechStateChanged("IDLE")
    }

    // --- BACKGROUND AUDIO PLAYBACK (MP3/Base64) ---

    fun playAudioResponse(base64Audio: String, onFinished: (() -> Unit)? = null) {
        if (base64Audio.isBlank()) {
            Log.w("VoiceManager", "playAudioResponse: Received empty or blank audio data.")
            onFinished?.invoke()
            return
        }
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)

            val tempFile = File.createTempFile("tts_audio_resp", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            mediaPlayer?.stop()
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes.USAGE_ASSISTANT
                } else {
                    AudioAttributes.USAGE_MEDIA
                }

                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(playbackAttributes)

                setDataSource(tempFile.absolutePath)
                setOnPreparedListener {
                    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(playbackAttributes)
                            .build()
                        audioFocusRequest = request
                        audioManager.requestAudioFocus(request)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    }

                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        start()
                        onSpeechStateChanged("SPEAKING")
                    }
                }
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    if (tempFile.exists()) tempFile.delete()

                    mainHandler.post {
                        onSpeechStateChanged("IDLE")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            audioFocusRequest?.let { req ->
                                audioManager.abandonAudioFocusRequest(req as AudioFocusRequest)
                            }
                            audioFocusRequest = null
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.abandonAudioFocus(null)
                        }
                        onFinished?.invoke()
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e("VoiceManager", "MediaPlayer error: $what, $extra")
                    if (tempFile.exists()) tempFile.delete()
                    mainHandler.post {
                        onSpeechStateChanged("IDLE")
                        onFinished?.invoke()
                    }
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error playing audio response", e)
            onSpeechStateChanged("IDLE")
            onFinished?.invoke()
        }
    }

    // --- TEXT-TO-SPEECH (TTS) LOGIC ---

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

    fun speak(text: String, languageCode: String? = null, onFinished: (() -> Unit)? = null) {
        if (!isTtsInitialized || text.isBlank()) {
            Log.d("VoiceManager", "speak: Skipping empty or blank text.")
            onFinished?.invoke()
            return
        }
        try {
            val finalLanguage = if (languageCode != null) {
                when (languageCode) {
                    "en" -> "en-IN"
                    "hi" -> "hi-IN"
                    "mr" -> "mr-IN"
                    else -> languageCode
                }
            } else {
                currentLanguageCode
            }

            Log.d("VoiceManager", "TTS_LANGUAGE:$finalLanguage")
            val setLangResult = textToSpeech?.setLanguage(Locale.forLanguageTag(finalLanguage))
            Log.d("VoiceManager", "TTS setLanguage($finalLanguage) returned: $setLangResult (0=AVAILABLE, 1=COUNTRY_AVAILABLE, 2=COUNTRY_VAR_AVAILABLE, -1=MISSING_DATA, -2=NOT_SUPPORTED)")

            onSpeechStateChanged("SPEAKING")
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AccessBridgeTTS")

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        onSpeechStateChanged("IDLE")
                        onFinished?.invoke()
                    }
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        onSpeechStateChanged("IDLE")
                        onFinished?.invoke()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("VoiceManager", "TTS Speak failed", e)
            onSpeechStateChanged("IDLE")
            onFinished?.invoke()
        }
    }

    fun speak(text: String, onFinished: (() -> Unit)?) {
        speak(text, null, onFinished)
    }

    // --- SPEECH-TO-TEXT (STT) LOGIC ---

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
                val finalSpeech = matches?.getOrNull(0)?.trim() ?: ""

                if (finalSpeech.isEmpty()) {
                    Log.w("AccessibilityAgent", "Suppressed USER_INTENT dispatch: Empty transcript.")
                    onSpeechStateChanged("IDLE")
                    return
                }

                Log.d("VoiceManager", "onResults: $finalSpeech")
                onSpeechRecognized(finalSpeech)
                onSpeechStateChanged("IDLE")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.getOrNull(0)
                Log.d("VoiceManager", "onPartialResults: $partialText")
                if (!partialText.isNullOrBlank()) {
                    onPartialResults(partialText)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    // --- Cleanup ---

    fun release() {
        isRecording = false
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        mediaPlayer?.release()
    }
}
