package com.smarteyes.app.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Texts the caregiver straight from the phone's SIM. No paid service needed.
 * Needs the SEND_SMS permission (asked at startup) and a SIM with SMS.
 */
class SosSender(private val context: Context) {
  private val prefs = context.getSharedPreferences("smart_eyes", Context.MODE_PRIVATE)

  var caregiverNumber: String
    get() = prefs.getString("caregiver", "") ?: ""
    set(value) = prefs.edit().putString("caregiver", value.trim()).apply()

  /** Returns a short status for the voice / log. */
  fun send(reason: String, lat: Double?, lon: Double?): String {
    val number = caregiverNumber
    if (number.isBlank()) return "No caregiver number set"
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
      return "SMS permission missing"
    }
    val where = if (lat != null && lon != null) "https://maps.google.com/?q=$lat,$lon" else "location unknown"
    val text = "SMART EYES SOS: $reason. Location: $where"
    return try {
      val sms = context.getSystemService(SmsManager::class.java)
      sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
      "Text sent to your caregiver"
    } catch (e: Exception) {
      Log.e("SosSender", "SMS failed", e)
      "Text failed: ${e.message}"
    }
  }
}
