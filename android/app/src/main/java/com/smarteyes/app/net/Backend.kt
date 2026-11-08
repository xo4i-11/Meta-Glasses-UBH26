package com.smarteyes.app.net

import android.net.Uri
import com.smarteyes.app.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

data class DescribeResult(val text: String, val hazard: String, val latencyMs: Long)

/** Talks to our FastAPI backend (backend/app/main.py). API keys never live on the phone. */
class Backend(@Volatile var baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')) {
  private val token = BuildConfig.APP_TOKEN
  private val json = "application/json".toMediaType()
  private val http =
      OkHttpClient.Builder()
          .connectTimeout(4, TimeUnit.SECONDS)
          .readTimeout(15, TimeUnit.SECONDS)
          .writeTimeout(10, TimeUnit.SECONDS)
          .build()

  private fun Request.Builder.auth() = apply { if (token.isNotEmpty()) header("X-App-Token", token) }

  suspend fun describe(jpeg: ByteArray, mode: String, question: String = "", lat: Double? = null, lon: Double? = null): DescribeResult =
      withContext(Dispatchers.IO) {
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("mode", mode)
                .addFormDataPart("question", question)
                .addFormDataPart("source", "glasses")
                .apply {
                  if (lat != null && lon != null) {
                    addFormDataPart("lat", lat.toString())
                    addFormDataPart("lon", lon.toString())
                  }
                }
                .build()
        val req = Request.Builder().url("$baseUrl/api/describe").post(body).auth().build()
        http.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) throw IOException("describe ${resp.code}")
          val o = JSONObject(resp.body!!.string())
          DescribeResult(o.optString("text"), o.optString("hazard"), o.optLong("latency_ms"))
        }
      }

  suspend fun askMemory(question: String): String =
      withContext(Dispatchers.IO) {
        val req =
            Request.Builder()
                .url("$baseUrl/api/memory/ask")
                .post(JSONObject().put("question", question).toString().toRequestBody(json))
                .auth()
                .build()
        http.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) throw IOException("memory ${resp.code}")
          JSONObject(resp.body!!.string()).optString("answer")
        }
      }

  suspend fun sky(lat: Double, lon: Double, azimuth: Double, altitude: Double): String =
      withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/sky?lat=$lat&lon=$lon&az=$azimuth&alt=$altitude").auth().build()
        http.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) throw IOException("sky ${resp.code}")
          JSONObject(resp.body!!.string()).optString("text")
        }
      }

  suspend fun health(): Boolean =
      withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url("$baseUrl/health").build()).execute().use { it.isSuccessful } }
            .getOrDefault(false)
      }

  /** Fire-and-forget: hazard log, SOS, etc. Stored in Tiger Data and shown on the dashboard. */
  fun postEvent(fields: Map<String, Any?>) = postAsync("/api/events", fields)

  /** Fire-and-forget: YOLO memory log (labels only, never images). */
  fun postSighting(description: String, objects: List<Map<String, String>>, lat: Double?, lon: Double?) =
      postAsync("/api/sightings", mapOf("description" to description, "objects" to org.json.JSONArray(objects.map { JSONObject(it) }), "source" to "yolo", "lat" to lat, "lon" to lon))

  /** Fire-and-forget telemetry for the dashboard only (not stored). */
  fun live(fields: Map<String, Any?>) = postAsync("/api/live", fields)

  private fun postAsync(path: String, fields: Map<String, Any?>) {
    val o = JSONObject()
    fields.forEach { (k, v) -> if (v != null) o.put(k, if (v is Map<*, *>) JSONObject(v) else v) }
    val req = Request.Builder().url("$baseUrl$path").post(o.toString().toRequestBody(json)).auth().build()
    http.newCall(req).enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {}

          override fun onResponse(call: Call, response: Response) = response.close()
        })
  }

  /** URL that streams ElevenLabs MP3; MediaPlayer starts playing before the clip is finished. */
  fun ttsUrl(text: String): String =
      "$baseUrl/api/tts?text=${Uri.encode(text)}" + if (token.isNotEmpty()) "&token=${Uri.encode(token)}" else ""
}
