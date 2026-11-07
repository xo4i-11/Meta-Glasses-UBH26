package com.smarteyes

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/** BLE contract with the cane firmware (see design spec). */
val CANE_SERVICE: UUID = UUID.fromString("709dd8c2-e2ae-492e-bcbc-ab2ad425d950")
val CANE_EVENT: UUID = UUID.fromString("51cfb510-0815-4e68-aabc-256bbb5a64fb")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Scans for the cane, subscribes to button events (1 PRESS, 2 HOLD, 3 DOUBLE), auto-reconnects. */
@SuppressLint("MissingPermission") // BLUETOOTH_SCAN/CONNECT requested in MainActivity before start()
class CaneBle(context: Context, private val onEvent: (Int) -> Unit, private val onStatus: (String) -> Unit) {
  private val ctx = context.applicationContext
  private val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
  private val main = Handler(Looper.getMainLooper())
  private var gatt: BluetoothGatt? = null
  private var scanning = false
  private var running = false

  fun start() {
    running = true
    scan()
  }

  fun stop() {
    running = false
    stopScan()
    gatt?.close()
    gatt = null
  }

  private fun scan() {
    if (!running || scanning || adapter?.isEnabled != true) return
    scanning = true
    onStatus("Cane: searching")
    adapter.bluetoothLeScanner.startScan(
        listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(CANE_SERVICE)).build()),
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
        scanCb)
  }

  private fun stopScan() {
    if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCb)
    scanning = false
  }

  private val scanCb = object : ScanCallback() {
    override fun onScanResult(type: Int, result: ScanResult) {
      stopScan()
      connect(result.device)
    }
  }

  private fun connect(device: BluetoothDevice) {
    gatt?.close()
    gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
  }

  private fun retry() = main.postDelayed({ scan() }, 2000)

  private val gattCb = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
      if (state == BluetoothProfile.STATE_CONNECTED) {
        g.discoverServices()
      } else {
        onStatus("Cane: disconnected")
        g.close()
        if (gatt === g) gatt = null
        retry()
      }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      val ch = g.getService(CANE_SERVICE)?.getCharacteristic(CANE_EVENT)
      if (ch == null) {
        onStatus("Cane: event characteristic missing")
        g.disconnect()
        return
      }
      g.setCharacteristicNotification(ch, true)
      g.writeDescriptor(ch.getDescriptor(CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
      onStatus("Cane: connected")
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
      val ev = value.firstOrNull()?.toInt() ?: return
      if (ev in 1..3) main.post { onEvent(ev) }
    }
  }
}
