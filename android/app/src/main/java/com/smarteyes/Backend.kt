package com.smarteyes

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class Reply(val say: String, val hazard: Boolean, val audioId: String?, val serverMs: Int)

/** Thin client for the Smart Eyes backend. Throws IOException when unreachable or non-2xx. */
class Backend(private val baseUrl: String, val secret: String) {
  private val http = OkHttpClient.Builder()
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS) // /ask chains STT + Gemini + TTS
      .build()

  suspend fun frame(jpeg: ByteArray, mode: String, clientMs: Int?): Reply =
      post("/frame", multipart(clientMs)
          .addFormDataPart("image", "f.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
          .addFormDataPart("mode", mode))

  suspend fun ask(audio: ByteArray, filename: String, clientMs: Int?): Reply =
      post("/ask", multipart(clientMs)
          .addFormDataPart("audio", filename, audio.toRequestBody("audio/mp4".toMediaType())))

  fun audioUrl(id: String) = "$baseUrl/audio/$id"

  private fun multipart(clientMs: Int?) = MultipartBody.Builder().setType(MultipartBody.FORM).also {
    if (clientMs != null) it.addFormDataPart("client_ms", clientMs.toString())
  }

  private suspend fun post(path: String, body: MultipartBody.Builder): Reply = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(baseUrl + path).header("X-Secret", secret).post(body.build()).build()
    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
      val j = JSONObject(resp.body!!.string())
      Reply(
          say = j.getString("say"),
          hazard = j.getBoolean("hazard"),
          audioId = if (j.isNull("audio_id")) null else j.getString("audio_id"),
          serverMs = j.optInt("server_ms"),
      )
    }
  }
}
