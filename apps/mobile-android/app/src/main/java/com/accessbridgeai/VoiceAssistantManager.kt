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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Base64
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
    @Volatile
    private var isRecording = false

    init {
        // Initialize TTS
        textToSpeech = TextToSpeech(context, this)

        // Initialize STT if available (Fallback/Phase 1)
        val isSttAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d("VoiceManager", "Speech Recognition Available: $isSttAvailable")
        if (isSttAvailable) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
        }
    }

    // --- Phase 2: CHIME & AUDIO STREAMING ---

    /**
     * Plays a subtle chime to acknowledge user input activation.
     */
    fun playChime() {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            toneG.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 150)
        } catch (e: Exception) {
            Log.e("VoiceManager", "Chime failed", e)
        }
    }

    /**
     * Starts capturing raw audio chunks and streams them via the provided callback.
     * Used for background listening without opening the main UI.
     */
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

    /**
     * Halts all active audio operations (TTS, Streaming, Playback) immediately.
     */
    fun stopAllOperations() {
        isRecording = false
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        textToSpeech?.stop()
        onSpeechStateChanged("IDLE")
    }

    // --- Phase 3: BACKGROUND AUDIO PLAYBACK (TTS) ---

    fun playAudioResponse(base64Audio: String) {
        if (base64Audio.isBlank()) {
            Log.w("VoiceManager", "playAudioResponse: Received empty or blank audio data.")
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
                val usage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.media.AudioAttributes.USAGE_ASSISTANT
                } else {
                    android.media.AudioAttributes.USAGE_MEDIA
                }
                
                val playbackAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(playbackAttributes)
                
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { 
                    val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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
                    onSpeechStateChanged("IDLE")
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        audioFocusRequest?.let { req ->
                            audioManager.abandonAudioFocusRequest(req as android.media.AudioFocusRequest)
                        }
                        audioFocusRequest = null
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.abandonAudioFocus(null)
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e("VoiceManager", "MediaPlayer error: $what, $extra")
                    onSpeechStateChanged("IDLE")
                    if (tempFile.exists()) tempFile.delete()
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error playing audio response", e)
            onSpeechStateChanged("IDLE")
        }
    }

    // --- 1. TEXT-TO-SPEECH (TTS) LOGIC (LOCAL) ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("VoiceManager", "TTS Language not supported")
            } else {
                isTtsInitialized = true
            }
        } else {
            Log.e("VoiceManager", "TTS Initialization failed")
        }
    }

    fun speak(text: String) {
        if (!isTtsInitialized || text.isBlank()) {
            Log.d("VoiceManager", "speak: Skipping empty or blank text.")
            return
        }
        onSpeechStateChanged("SPEAKING")
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

    // --- 2. SPEECH-TO-TEXT (STT) LOGIC (LEGACY/PHASE 1) ---
    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        Log.d("VoiceManager", "startListening() called")
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
                    else -> "Unknown error: $error"
                }
                Log.e("VoiceManager", "Speech Recognition Error: $errorMessage")
                onSpeechStateChanged("IDLE")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.getOrNull(0)
                Log.d("VoiceManager", "onResults: $spokenText")
                if (!spokenText.isNullOrBlank()) {
                    onSpeechRecognized(spokenText)
                    onSpeechStateChanged("IDLE")
                } else {
                    Log.d("VoiceManager", "onResults: Ignoring empty or blank transcript.")
                    onSpeechStateChanged("IDLE")
                }
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
