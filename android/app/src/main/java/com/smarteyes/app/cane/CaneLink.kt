package com.smarteyes.app.cane

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Messages from the cane (firmware/smart_cane/smart_cane.ino), relayed by the backend. */
sealed class CaneEvent {
  data class Telemetry(val distanceCm: Int, val hole: Boolean) : CaneEvent()

  data class Hole(val on: Boolean) : CaneEvent()

  /** describe | read | ask | repeat | auto */
  data class Button(val name: String) : CaneEvent()

  object FallCountdown : CaneEvent()

  object Cancelled : CaneEvent()

  data class Sos(val reason: String, val lat: Double?, val lon: Double?) : CaneEvent()
}

/**
 * The phone never talks to the cane directly. Both connect to the backend:
 *   phone <-> wss://backend/ws/device?role=phone <-> backend <-> /ws/device?role=cane <-> cane
 * So the phone keeps a single Bluetooth link (the glasses). Reconnects forever.
 */
class CaneLink(private val baseUrl: () -> String, private val token: String) {
  private fun url() = baseUrl().replaceFirst("http", "ws") + "/ws/device?role=phone&token=$token"
  private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
  private val main = Handler(Looper.getMainLooper())
  private var socket: WebSocket? = null
  private var running = false

  private val _serverUp = MutableStateFlow(false)
  val serverUp: StateFlow<Boolean> = _serverUp
  private val _caneOnline = MutableStateFlow(false)
  val caneOnline: StateFlow<Boolean> = _caneOnline
  private val _events = MutableSharedFlow<CaneEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val events: SharedFlow<CaneEvent> = _events

  fun start() {
    running = true
    connect()
  }

  fun stop() {
    running = false
    socket?.close(1000, "bye")
    socket = null
  }

  private fun connect() {
    if (!running) return
    socket = runCatching { http.newWebSocket(Request.Builder().url(url()).build(), listener) }.getOrElse {
      Log.w(TAG, "bad server address: ${it.message}")
      retry()
      null
    }
  }

  private val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          Log.i(TAG, "connected to backend relay")
          _serverUp.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          val o = runCatching { JSONObject(text) }.getOrNull() ?: return
          val ev: CaneEvent? =
              when (o.optString("t")) {
                "status" -> {
                  _caneOnline.value = o.optBoolean("cane")
                  null
                }
                "tele" -> {
                  _caneOnline.value = true
                  CaneEvent.Telemetry(o.optInt("d", -1), o.optBoolean("hole"))
                }
                "hole" -> CaneEvent.Hole(o.optBoolean("on"))
                "btn" -> CaneEvent.Button(o.optString("b"))
                "fall" -> CaneEvent.FallCountdown
                "cancel" -> CaneEvent.Cancelled
                "sos" ->
                    CaneEvent.Sos(
                        o.optString("reason", "SOS"),
                        if (o.isNull("lat")) null else o.optDouble("lat"),
                        if (o.isNull("lon")) null else o.optDouble("lon"))
                else -> null
              }
          ev?.let { _events.tryEmit(it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          Log.w(TAG, "relay connection failed: ${t.message}")
          retry()
        }
      }

  /** Server address changed: drop the socket; the retry loop reconnects to the new one. */
  fun reconnect() {
    val s = socket
    socket = null
    if (s != null) s.cancel() else retry()
  }

  private fun retry() {
    _serverUp.value = false
    _caneOnline.value = false
    if (running) main.postDelayed({ connect() }, 2000)
  }

  private fun send(o: JSONObject) {
    socket?.send(o.toString())
  }

  /** Play a sound on the cane speaker. */
  fun tone(freqHz: Int, pulses: Int, onMs: Int, offMs: Int) =
      send(JSONObject().put("t", "tone").put("f", freqHz).put("n", pulses).put("on", onMs).put("off", offMs))

  /** "Where's my cane?": cane plays a locator siren for 20 s (stops when picked up). */
  fun find() = send(JSONObject().put("t", "find"))

  /** Shows auto-announce state on the cane's OLED. */
  fun setAuto(on: Boolean) = send(JSONObject().put("t", "auto").put("on", on))

  /** Lets the backend attach our location to SOS events coming from the cane. */
  fun sendLocation(lat: Double, lon: Double) = send(JSONObject().put("t", "loc").put("lat", lat).put("lon", lon))

  companion object {
    private const val TAG = "CaneLink"
  }
}
