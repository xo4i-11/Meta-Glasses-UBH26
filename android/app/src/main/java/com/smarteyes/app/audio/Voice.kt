package com.smarteyes.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Two voices:
 *  - [warn]: Android's on-device TTS. Instant and works offline, used for safety warnings.
 *  - [say]:  ElevenLabs natural voice streamed from the backend, used for descriptions.
 *            Falls back to on-device TTS if the network or ElevenLabs is slow or down.
 *
 * Audio plays on the media stream, which Android routes to the Ray-Ban Meta speakers
 * whenever the glasses are connected as a Bluetooth audio device.
 */
class Voice(context: Context, private val ttsUrlFor: (String) -> String) {
  private val main = Handler(Looper.getMainLooper())
  private val attrs =
      AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()

  private var ttsReady = false
  private val tts =
      TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) configureTts()
      }
  private var player: MediaPlayer? = null

  @Volatile var lastSaid: String = ""
    private set

  var onSpeakStart: ((String, Long) -> Unit)? = null

  private fun configureTts() {
    tts.language = Locale.US
    tts.setSpeechRate(1.1f)
    tts.setAudioAttributes(attrs)
  }

  /** Safety warning: interrupts everything, speaks immediately on-device. */
  fun warn(text: String) {
    main.post {
      stopPlayer()
      lastSaid = text
      if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "warn-${System.nanoTime()}")
    }
  }

  /** Natural voice via ElevenLabs; on-device fallback after [timeoutMs]. */
  fun say(text: String, requestedAtMs: Long = System.currentTimeMillis(), timeoutMs: Long = 2500) {
    main.post {
        if (text.isBlank()) return@post
        stopPlayer()
        tts.stop()
        lastSaid = text
        var started = false
        val fallback = Runnable {
          if (!started) {
            Log.w(TAG, "ElevenLabs too slow, using on-device voice")
            stopPlayer()
            speakLocal(text, requestedAtMs)
            started = true
          }
        }
        val mp = MediaPlayer()
        player = mp
        try {
          mp.setAudioAttributes(attrs)
          mp.setDataSource(ttsUrlFor(text))
          mp.setOnPreparedListener {
            if (!started && player === mp) {
              started = true
              main.removeCallbacks(fallback)
              it.start()
              onSpeakStart?.invoke("elevenlabs", System.currentTimeMillis() - requestedAtMs)
            }
          }
          mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error $what/$extra")
            main.removeCallbacks(fallback)
            if (!started) {
              started = true
              speakLocal(text, requestedAtMs)
            }
            true
          }
          mp.setOnCompletionListener { if (player === mp) stopPlayer() }
          mp.prepareAsync()
          main.postDelayed(fallback, timeoutMs)
        } catch (e: Exception) {
          Log.w(TAG, "ElevenLabs playback failed", e)
          stopPlayer()
          speakLocal(text, requestedAtMs)
        }
      }
  }

  private fun speakLocal(text: String, requestedAtMs: Long) {
    if (ttsReady) {
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say-${System.nanoTime()}")
      onSpeakStart?.invoke("android-tts", System.currentTimeMillis() - requestedAtMs)
    }
  }

  private fun stopPlayer() {
    player?.let {
      runCatching { it.stop() }
      it.release()
    }
    player = null
  }

  fun shutdown() {
    main.post {
      stopPlayer()
      tts.shutdown()
    }
  }

  companion object {
    private const val TAG = "Voice"
  }
}
