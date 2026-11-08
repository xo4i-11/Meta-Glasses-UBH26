package com.smarteyes.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the user is and where they're pointing (for SOS and Sky Mode).
 *
 * Sky Mode uses the PHONE's rotation sensor: hold the phone flat against your chest/lanyard facing
 * out, or point its top edge at the sky. (Upgrade later: the glasses' own IMU via DAT mwdat-motion.)
 */
class Pointing(private val context: Context) : SensorEventListener {
  private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  private val rotation = FloatArray(9)
  private val orientation = FloatArray(3)

  @Volatile var azimuthDeg = 0.0
    private set

  @Volatile var altitudeDeg = 0.0
    private set

  fun start() {
    sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
      sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
    }
  }

  fun stop() = sensors.unregisterListener(this)

  override fun onSensorChanged(e: SensorEvent) {
    SensorManager.getRotationMatrixFromVector(rotation, e.values)
    // Remap so we measure where the BACK CAMERA (out of the phone's back) points
    val remapped = FloatArray(9)
    SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
    SensorManager.getOrientation(remapped, orientation)
    var az = Math.toDegrees(orientation[0].toDouble())
    lastLocation()?.let {
      az += GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
    }
    azimuthDeg = (az + 360) % 360
    altitudeDeg = -Math.toDegrees(orientation[1].toDouble())
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  /** A FRESH fix for SOS (fused: GPS + Wi-Fi + cell). Falls back to the last known one after [timeoutMs]. */
  @SuppressLint("MissingPermission")
  suspend fun freshLocation(timeoutMs: Long = 5000): Location? {
    val fix =
        withTimeoutOrNull(timeoutMs) {
          suspendCancellableCoroutine<Location?> { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            runCatching {
                  locations.getCurrentLocation(LocationManager.FUSED_PROVIDER, cancel, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                }
                .onFailure { if (cont.isActive) cont.resume(null) }
          }
        }
    if (fix != null) {
      cached = fix
      cachedAt = System.currentTimeMillis()
    }
    return fix ?: lastLocation()
  }

  private var cached: Location? = null
  private var cachedAt = 0L

  @SuppressLint("MissingPermission")
  fun lastLocation(): Location? {
    val now = System.currentTimeMillis()
    if (cached != null && now - cachedAt < 30_000) return cached
    cached =
        runCatching {
              listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                  .mapNotNull { locations.getLastKnownLocation(it) }
                  .maxByOrNull { it.time }
            }
            .getOrNull()
    cachedAt = now
    return cached
  }
}
