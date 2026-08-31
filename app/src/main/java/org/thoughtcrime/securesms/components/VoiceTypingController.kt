package org.thoughtcrime.securesms.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.EditText
import androidx.core.content.ContextCompat
import org.signal.core.util.logging.Log
import java.io.IOException

/**
 * AJ fork: in-app voice typing, ported from the standalone BulkSendKeyboard IME
 * (ajubhajesar/Fstest, com.simple.zt/BulkSendKeyboard.java) into a native Molly feature.
 *
 * The port drops the InputMethodService wrapper entirely. That existed for exactly one reason:
 * getCurrentInputConnection() is the only way for one app's process to type into a *different*
 * app's focused text field, which is what a keyboard fundamentally is. Inside Molly, this
 * controller already holds a direct reference to composeText - there's no process boundary to
 * bridge, so InputConnection, the IME manifest plumbing, and the SCOPED_PACKAGE check the
 * original needed to detect "am I currently inside com.aj.signal" are all unnecessary. Gboard
 * (or whatever system keyboard) is untouched and still does all normal typing; this is an
 * additional input source into the same EditText, not a keyboard replacement.
 *
 * The always-on segmented-session mic pipeline (AudioRecord -> pipe -> EXTRA_AUDIO_SOURCE) is
 * ported as-is from the original - that's the part that makes this "voice typing" rather than
 * "dictate one sentence and stop," and it's already solved/tuned there.
 *
 * Deliberately NOT ported: the original's send-on-every-pause behavior - each recognized phrase
 * was immediately committed *and* sent as its own message, no review. That's a different risk
 * profile inside a real messaging app than inside a keyboard demo: a mis-heard word there stays
 * a keyboard buffer; here it would be a real, delivered message to a real contact. This
 * controller only ever inserts into the draft at the cursor - sending stays a separate,
 * deliberate action. Can be wired back in explicitly later if actually wanted.
 */
class VoiceTypingController(
  private val context: Context,
  private val composeText: EditText,
  private val onListeningChanged: (Boolean) -> Unit
) {

  companion object {
    private val TAG = Log.tag(VoiceTypingController::class.java)
    private const val AUDIO_SAMPLE_RATE = 16000
    private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val PREFS_NAME = "voice_typing_prefs"
    private const val PREF_VOICE_LANG = "voice_lang"
  }

  private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private var voiceLang: String = prefs.getString(PREF_VOICE_LANG, "gu") ?: "gu"

  private var speechRecognizer: SpeechRecognizer? = null
  private var listening = false

  // Always-on segmented session plumbing - see startAudioSourcePipeline(). Ported as-is.
  private var audioRecord: AudioRecord? = null
  private var captureThread: Thread? = null
  @Volatile private var capturing = false
  private var pipeRead: ParcelFileDescriptor? = null
  private var pipeWrite: ParcelFileDescriptor? = null

  private val mainHandler = Handler(Looper.getMainLooper())

  fun isListening(): Boolean = listening

  fun hasPermission(): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  }

  /** gu -> en -> mix -> gu. Restarts an in-progress session so the new language takes effect immediately. */
  fun cycleLanguage(): String {
    voiceLang = when (voiceLang) {
      "gu" -> "en"
      "en" -> "mix"
      else -> "gu"
    }
    prefs.edit().putString(PREF_VOICE_LANG, voiceLang).apply()
    if (listening) {
      stop()
      start()
    }
    return languageLabel()
  }

  fun languageLabel(): String = when (voiceLang) {
    "en" -> "EN"
    "mix" -> "MIX"
    else -> "GU"
  }

  fun toggle() {
    if (listening) stop() else start()
  }

  fun start() {
    if (!hasPermission()) {
      Log.w(TAG, "start() called without RECORD_AUDIO permission.")
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      Log.w(TAG, "Speech recognition not available on this device.")
      return
    }

    // Fresh instance every restart, matching the original - the native engine is built for
    // single-shot use; reusing one across restarts is what jams into ERROR_RECOGNIZER_BUSY.
    releaseRecognizer()
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    recognizer.setRecognitionListener(recognitionListener)
    speechRecognizer = recognizer

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    // "mix" isn't a real bilingual mode - there's no BCP-47 tag for Gujarati+English, and the
    // engine locks to one language model per session. It maps to gu-IN because that engine
    // already tends to phonetically render English loanwords reasonably on its own - "mix"
    // just means "don't fight the Gujarati model's existing tolerance for English words," not
    // genuine per-word language switching. Ported reasoning, unchanged.
    val bcp47 = if (voiceLang == "en") "en-IN" else "gu-IN"
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    intent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1100)
    intent.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 800)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val audioSource = startAudioSourcePipeline()
      if (audioSource != null) {
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AUDIO_ENCODING)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AUDIO_SAMPLE_RATE)
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        Log.d(TAG, "start(): segmented=ON(audio-source, always-on) lang=$bcp47")
      } else {
        intent.putExtra(
          RecognizerIntent.EXTRA_SEGMENTED_SESSION,
          RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
        )
        Log.d(TAG, "start(): segmented=ON(silence-based, audio-source setup failed) lang=$bcp47")
      }
    } else {
      Log.d(TAG, "start(): segmented=OFF (API<33) lang=$bcp47")
    }

    recognizer.startListening(intent)
    listening = true
    onListeningChanged(true)
  }

  fun stop() {
    listening = false
    stopAudioSourcePipeline()
    releaseRecognizer()
    onListeningChanged(false)
  }

  /** Call from the host Fragment's onDestroyView so the mic/recognizer never outlives the screen. */
  fun destroy() {
    stop()
  }

  private fun releaseRecognizer() {
    speechRecognizer?.let {
      it.cancel()
      it.destroy()
    }
    speechRecognizer = null
  }

  /**
   * Opens the mic ourselves and streams PCM into a pipe for EXTRA_AUDIO_SOURCE. Per the docs
   * this is the only segmentation mode that doesn't end on a silence timeout - it ends when,
   * and only when, we close the pipe. Returns the read end to attach to the intent, or null if
   * setup failed for any reason (caller falls back to silence-based segmentation). Ported as-is.
   */
  private fun startAudioSourcePipeline(): ParcelFileDescriptor? {
    return try {
      val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_ENCODING)
      if (minBuf <= 0) return null
      val bufSize = minBuf * 2

      val rec = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        AUDIO_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AUDIO_ENCODING,
        bufSize
      )
      if (rec.state != AudioRecord.STATE_INITIALIZED) {
        rec.release()
        return null
      }

      val pipe = ParcelFileDescriptor.createPipe()
      val readSide = pipe[0]
      val writeSide = pipe[1]

      rec.startRecording()

      audioRecord = rec
      pipeRead = readSide
      pipeWrite = writeSide
      capturing = true

      val thread = Thread(
        {
          android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
          val buf = ByteArray(bufSize)
          try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
              while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) out.write(buf, 0, n)
              }
            }
          } catch (e: IOException) {
            Log.d(TAG, "audio pipe closed: ${e.message}")
          }
        },
        "voice-audio-source"
      )
      captureThread = thread
      thread.start()

      readSide
    } catch (e: Exception) {
      Log.w(TAG, "audio-source pipeline setup failed: ${e.message}")
      stopAudioSourcePipeline()
      null
    }
  }

  private fun stopAudioSourcePipeline() {
    capturing = false
    audioRecord?.let {
      try {
        it.stop()
      } catch (ignored: Exception) {
      }
    }
    captureThread?.let {
      try {
        it.join(300)
      } catch (ignored: InterruptedException) {
      }
    }
    captureThread = null
    audioRecord?.release()
    audioRecord = null
    closeQuietly(pipeRead)
    pipeRead = null
    pipeWrite = null
  }

  private fun closeQuietly(pfd: ParcelFileDescriptor?) {
    if (pfd == null) return
    try {
      pfd.close()
    } catch (ignored: IOException) {
    }
  }

  private fun restartListening(delayMs: Long = 60) {
    mainHandler.postDelayed({
      if (listening) start()
    }, delayMs)
  }

  private fun topResult(bundle: Bundle?): String? {
    if (bundle == null) return null
    val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    return if (!matches.isNullOrEmpty()) matches[0] else null
  }

  /**
   * Inserts recognized text at the current cursor position and leaves it there for review -
   * see the class doc for why this deliberately does not auto-send, unlike the original
   * keyboard's handleTranscript(). Editable.replace() on a start==end range is a plain insert;
   * the cursor lands after the inserted text, so successive segments append in reading order
   * without extra bookkeeping here.
   */
  private fun insertTranscript(transcript: String?) {
    if (transcript.isNullOrBlank()) return
    val output = transcript.trim() + " "
    val editable = composeText.text ?: return
    val start = composeText.selectionStart.coerceIn(0, editable.length)
    val end = composeText.selectionEnd.coerceIn(0, editable.length)
    editable.replace(minOf(start, end), maxOf(start, end), output)
  }

  private val recognitionListener = object : RecognitionListener {
    override fun onResults(results: Bundle?) {
      if (!listening) return
      insertTranscript(topResult(results))
      if (listening) restartListening()
    }

    // API 33+ segmented session path (see EXTRA_SEGMENTED_SESSION above). Both are `default`
    // methods on the interface in newer SDKs; overriding is safe on older ones too since they
    // simply never get called by an engine that doesn't support segmented mode.
    override fun onSegmentResults(segmentResults: Bundle) {
      if (!listening) return
      insertTranscript(topResult(segmentResults))
      // Deliberately NOT calling restartListening() here - the whole point of segmented mode
      // is the engine keeps the mic open across this firing multiple times. Restarting here
      // would defeat it.
    }

    override fun onEndOfSegmentedSession() {
      // The segmented session itself closed (long silence, engine decided to end it, etc.) -
      // this is the actual restart point in segmented mode.
      if (listening) restartListening()
    }

    override fun onError(error: Int) {
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
        stop()
        return
      }
      if (!listening) return
      if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
        // Engine hasn't released the previous session yet - back off instead of hammering it.
        restartListening(400)
      } else {
        restartListening(0) // auto-recover (timeouts etc.) - no delay needed
      }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    // Required abstract method on RecognitionListener - EXTRA_PARTIAL_RESULTS is false so this
    // shouldn't fire in practice, but the interface still demands an override either way.
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
  }
}
