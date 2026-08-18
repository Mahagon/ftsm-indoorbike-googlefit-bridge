package dev.frakw.ftmsbridge.ftms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.DiscoveredBike
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidFtmsClient(
    context: Context,
    private val parser: FtmsPacketParser = FtmsPacketParser(),
) : FtmsClient {
    private val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(FtmsClientState())
    override val state: StateFlow<FtmsClientState> = mutableState.asStateFlow()
    private var gatt: BluetoothGatt? = null

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                val address = result.device.address
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "FTMS bike"
                val device = DiscoveredBike(name, address, result.rssi)
                val devices =
                    (mutableState.value.devices.filterNot { it.address == address } + device)
                        .sortedByDescending { it.signalDbm }
                mutableState.value = mutableState.value.copy(devices = devices)
            }

            override fun onScanFailed(errorCode: Int) = fail("Bluetooth scan failed ($errorCode)")
        }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        fail("Bike disconnected (GATT $status)")
                    }

                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        addDiagnostic("Connected; discovering services")
                        mutableState.value = mutableState.value.copy(connection = ConnectionState.CONNECTING)
                        gatt.discoverServices()
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        mutableState.value = mutableState.value.copy(connection = ConnectionState.DISCONNECTED)
                        gatt.close()
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return fail("Service discovery failed ($status)")
                val service =
                    gatt.getService(FTMS_SERVICE)
                        ?: return fail("Device does not expose the FTMS service")
                addDiagnostic("FTMS characteristics: " + service.characteristics.joinToString { it.uuid.toString() })
                val data =
                    service.getCharacteristic(INDOOR_BIKE_DATA)
                        ?: return fail("Indoor Bike Data characteristic is missing")
                if (!gatt.setCharacteristicNotification(data, true)) {
                    return fail("Could not enable Indoor Bike Data notifications")
                }
                val cccd = data.getDescriptor(CCCD) ?: return fail("Indoor Bike Data has no CCCD")
                val accepted = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (accepted != BluetoothGatt.GATT_SUCCESS) fail("Could not configure notifications ($accepted)")
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid != CCCD) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    addDiagnostic("Indoor Bike Data notifications enabled")
                    mutableState.value = mutableState.value.copy(connection = ConnectionState.READY, error = null)
                } else {
                    fail("Notification descriptor write failed ($status)")
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid != INDOOR_BIKE_DATA) return
                val raw = value.toHex()
                when (val result = parser.parse(value, Instant.now())) {
                    is FtmsPacketParser.Result.Success -> {
                        mutableState.value =
                            mutableState.value.copy(
                                latest = result.sample,
                                rawPacket = raw,
                                error = null,
                            )
                    }

                    is FtmsPacketParser.Result.Failure -> {
                        addDiagnostic("Rejected $raw: ${result.reason}")
                        mutableState.value = mutableState.value.copy(rawPacket = raw, error = result.reason)
                    }
                }
            }
        }

    override fun startScan() {
        if (adapter?.isEnabled != true) return fail("Bluetooth is turned off")
        mutableState.value =
            mutableState.value.copy(
                connection = ConnectionState.SCANNING,
                devices = emptyList(),
                error = null,
            )
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(FTMS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback) ?: fail("BLE scanner unavailable")
    }

    override fun stopScan() {
        scanner?.stopScan(scanCallback)
        if (mutableState.value.connection == ConnectionState.SCANNING) {
            mutableState.value = mutableState.value.copy(connection = ConnectionState.DISCONNECTED)
        }
    }

    override fun connect(address: String) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return fail("Bike address is invalid")
        val selected =
            mutableState.value.devices.firstOrNull { it.address == address }
                ?: DiscoveredBike(device.name ?: "FTMS bike", address, 0)
        mutableState.value =
            mutableState.value.copy(
                connection = ConnectionState.CONNECTING,
                selected = selected,
                error = null,
            )
        gatt?.close()
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        mutableState.value = mutableState.value.copy(connection = ConnectionState.DISCONNECTED)
    }

    private fun addDiagnostic(message: String) {
        val line = "${Instant.now()}  $message"
        mutableState.value =
            mutableState.value.copy(
                diagnostics = (mutableState.value.diagnostics + line).takeLast(200),
            )
    }

    private fun fail(message: String) {
        addDiagnostic(message)
        mutableState.value = mutableState.value.copy(connection = ConnectionState.ERROR, error = message)
    }

    companion object {
        val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        val INDOOR_BIKE_DATA: UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
