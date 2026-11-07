package com.smarteyes

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Records ~4 s of speech, from the glasses mic over Bluetooth SCO when available, else the phone mic.
 * SCO is always released before returning so the answer plays back as normal media audio.
 */
class VoiceRecorder(context: Context) {
  private val ctx = context.applicationContext
  private val am = ctx.getSystemService(AudioManager::class.java)

  @SuppressLint("MissingPermission") // RECORD_AUDIO requested in MainActivity
  suspend fun record4s(): ByteArray? = withContext(Dispatchers.IO) {
    val sco = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    val usingSco = sco != null && am.setCommunicationDevice(sco)
    if (usingSco) delay(500) // let the SCO link come up
    val file = File(ctx.cacheDir, "question.m4a")
    val rec = MediaRecorder(ctx)
    try {
      rec.setAudioSource(if (usingSco) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
      rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      rec.setAudioSamplingRate(16_000)
      rec.setOutputFile(file)
      rec.prepare()
      rec.start()
      delay(4000)
      rec.stop()
      file.readBytes()
    } catch (e: Exception) {
      null
    } finally {
      rec.release()
      if (usingSco) am.clearCommunicationDevice()
    }
  }
}
