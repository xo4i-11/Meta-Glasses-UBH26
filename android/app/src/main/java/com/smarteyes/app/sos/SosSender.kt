package com.smarteyes.app.sos

import android.content.Context
import android.util.Log
import com.smarteyes.app.net.Backend

/**
 * Emails the caregiver through our backend (POST /api/sos), with a Google Maps link to the user.
 * Works over Wi-Fi: the phone has no SIM, so SMS is not an option.
 */
class SosSender(context: Context, private val backend: Backend) {
  private val prefs = context.getSharedPreferences("smart_eyes", Context.MODE_PRIVATE)

  var caregiverEmail: String
    get() = prefs.getString("caregiver", "") ?: ""
    set(value) = prefs.edit().putString("caregiver", value.trim()).apply()

  /** Returns a short status for the voice / log. */
  suspend fun send(reason: String, lat: Double?, lon: Double?): String {
    val to = caregiverEmail
    if ("@" !in to) return "No caregiver email set"
    return try {
      backend.sos(to, reason, lat, lon)
      "Email sent to your caregiver"
    } catch (e: Exception) {
      Log.e("SosSender", "SOS email failed", e)
      "Email failed: ${e.message}"
    }
  }

  /** Follow-up after a cancelled SOS so the caregiver can stand down. */
  suspend fun sendAllClear(lat: Double?, lon: Double?): String {
    val to = caregiverEmail
    if ("@" !in to) return ""
    return try {
      backend.sos(to, "CANCELLED: SOS cancelled, they're OK (cane found / false alarm)", lat, lon)
      "Your caregiver was told you're OK"
    } catch (e: Exception) {
      Log.e("SosSender", "all-clear email failed", e)
      "Couldn't email the all-clear: ${e.message}"
    }
  }
}
