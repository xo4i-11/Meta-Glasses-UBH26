package com.smarteyes.app

import android.app.Application

class SmartEyesApp : Application() {
  /** One controller for the whole app, so rotating/recreating the activity never drops BLE or the glasses. */
  val controller by lazy { SmartEyesController(this) }
}
