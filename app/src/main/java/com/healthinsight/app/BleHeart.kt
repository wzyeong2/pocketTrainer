package com.healthinsight.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * 표준 BLE 심박 센서(GATT Heart Rate Service 0x180D) 클라이언트.
 * 갤럭시 워치에 '심박 브로드캐스터' 앱을 깔면 표준 심박 센서처럼 잡혀서 연결된다.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object BleHeart {
    private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEAS = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var bpm by mutableStateOf<Int?>(null); private set
    var connected by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var scanning by mutableStateOf(false); private set
    val found = mutableStateListOf<Pair<String, String>>() // (이름, 주소)

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null

    fun scan(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { status = "블루투스를 켜줘"; return }
        scanner = adapter.bluetoothLeScanner ?: run { status = "스캔 불가"; return }
        found.clear(); status = "심박 센서 검색 중..."; scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: "심박 센서"
                if (found.none { it.second == dev.address }) found.add(name to dev.address)
            }
            override fun onScanFailed(errorCode: Int) { status = "스캔 실패 ($errorCode)"; scanning = false }
        }
        scanner?.startScan(listOf(filter), settings, scanCb)
    }

    fun stopScan() {
        scanCb?.let { try { scanner?.stopScan(it) } catch (_: Exception) {} }
        scanCb = null; scanning = false
    }

    fun connect(context: Context, address: String) {
        stopScan()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val dev = adapter.getRemoteDevice(address)
        status = "연결 중..."
        gatt?.close()
        gatt = dev.connectGatt(context.applicationContext, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> { status = "서비스 탐색 중..."; g.discoverServices() }
                    BluetoothProfile.STATE_DISCONNECTED -> { connected = false; bpm = null; status = "연결 끊김" }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEAS)
                if (ch == null) { status = "이 기기엔 심박 서비스가 없어요"; return }
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
                connected = true; status = "연결됨 ✅"
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                if (ch.uuid == HR_MEAS) parseHr(ch.value)?.let { bpm = it }
            }
            // Android 13+(API 33) 신규 콜백
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                if (ch.uuid == HR_MEAS) parseHr(value)?.let { bpm = it }
            }
        })
    }

    fun disconnect() {
        stopScan()
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null; connected = false; bpm = null; status = ""
    }

    private fun parseHr(v: ByteArray?): Int? {
        if (v == null || v.isEmpty()) return null
        val flag = v[0].toInt()
        return if (flag and 0x01 == 0) v.getOrNull(1)?.let { it.toInt() and 0xFF }
        else if (v.size >= 3) (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        else null
    }
}
