package com.smarteyes

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech

/**
 * Plays backend audio as normal media, so it routes to the glasses when they're the BT audio sink.
 * Falls back to Android TTS when there's no audio id or playback fails.
 */
class Player(context: Context) {
  private val ctx = context.applicationContext
  private val tts = TextToSpeech(ctx) {}
  private var mp: MediaPlayer? = null

  fun play(reply: Reply, backend: Backend, onStarted: () -> Unit = {}) {
    val id = reply.audioId
    if (id == null) {
      if (reply.say.isNotEmpty()) speakLocal(reply.say).also { onStarted() }
      return
    }
    stop()
    mp = MediaPlayer().apply {
      setAudioAttributes(AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build())
      setDataSource(ctx, Uri.parse(backend.audioUrl(id)), mapOf("X-Secret" to backend.secret))
      setOnPreparedListener { it.start(); onStarted() }
      setOnErrorListener { _, _, _ -> if (reply.say.isNotEmpty()) speakLocal(reply.say); true }
      setOnCompletionListener { it.release(); if (mp === it) mp = null }
      prepareAsync()
    }
  }

  fun speakLocal(text: String) {
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "smarteyes")
  }

  fun stop() {
    mp?.release()
    mp = null
  }
}
