package dev.frakw.ftmsbridge.ftms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.frakw.ftmsbridge.R
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.DiscoveredBike
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val sampleAccumulator = FtmsSampleAccumulator()

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                val address = result.device.address
                val name = result.scanRecord?.deviceName ?: result.device.name ?: appContext.getString(R.string.ftms_bike)
                val device = DiscoveredBike(name, address, result.rssi)
                mutableState.update {
                    it.copy(
                        devices =
                        (it.devices.filterNot { existing -> existing.address == address } + device)
                            .sortedByDescending { discovered -> discovered.signalDbm },
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) = fail(appContext.getString(R.string.bluetooth_scan_failed, errorCode))
        }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (!isCurrent(gatt)) {
                    gatt.close()
                    return
                }
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        sampleAccumulator.reset()
                        gatt.close()
                        if (this@AndroidFtmsClient.gatt === gatt) this@AndroidFtmsClient.gatt = null
                        fail(appContext.getString(R.string.bike_disconnected, status))
                    }

                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        addDiagnostic("Connected; discovering services")
                        mutableState.update { it.copy(connection = ConnectionState.CONNECTING) }
                        gatt.discoverServices()
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        sampleAccumulator.reset()
                        mutableState.update { it.copy(connection = ConnectionState.DISCONNECTED, latest = null) }
                        gatt.close()
                        if (this@AndroidFtmsClient.gatt === gatt) this@AndroidFtmsClient.gatt = null
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (!isCurrent(gatt)) return
                if (status != BluetoothGatt.GATT_SUCCESS) return fail(appContext.getString(R.string.service_discovery_failed, status))
                val service =
                    gatt.getService(FTMS_SERVICE)
                        ?: return fail(appContext.getString(R.string.ftms_service_missing))
                addDiagnostic("FTMS characteristics: " + service.characteristics.joinToString { it.uuid.toString() })
                val data =
                    service.getCharacteristic(INDOOR_BIKE_DATA)
                        ?: return fail(appContext.getString(R.string.bike_data_missing))
                if (!gatt.setCharacteristicNotification(data, true)) {
                    return fail(appContext.getString(R.string.bike_notifications_failed))
                }
                val cccd = data.getDescriptor(CCCD) ?: return fail(appContext.getString(R.string.bike_cccd_missing))
                val accepted = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (accepted != BluetoothGatt.GATT_SUCCESS) fail(appContext.getString(R.string.notification_config_failed, accepted))
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (!isCurrent(gatt)) return
                if (descriptor.uuid != CCCD) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    addDiagnostic("Indoor Bike Data notifications enabled")
                    mutableState.update { it.copy(connection = ConnectionState.READY, error = null) }
                } else {
                    fail(appContext.getString(R.string.notification_write_failed, status))
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (!isCurrent(gatt)) return
                if (characteristic.uuid != INDOOR_BIKE_DATA) return
                val raw = value.toHex()
                when (val result = parser.parse(value, Instant.now())) {
                    is FtmsPacketParser.Result.Success -> {
                        val merged = sampleAccumulator.merge(result.sample)
                        mutableState.update {
                            it.copy(
                                latest = merged,
                                rawPacket = raw,
                                error = null,
                            )
                        }
                    }

                    is FtmsPacketParser.Result.Failure -> {
                        addDiagnostic("Rejected $raw: ${result.reason}")
                        mutableState.update { it.copy(rawPacket = raw, error = result.reason) }
                    }
                }
            }
        }

    override fun startScan() {
        if (adapter?.isEnabled != true) return fail(appContext.getString(R.string.bluetooth_off))
        mutableState.update {
            it.copy(
                connection = ConnectionState.SCANNING,
                devices = emptyList(),
                error = null,
            )
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(FTMS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback) ?: fail(appContext.getString(R.string.ble_scanner_unavailable))
    }

    override fun stopScan() {
        scanner?.stopScan(scanCallback)
        mutableState.update {
            if (it.connection == ConnectionState.SCANNING) {
                it.copy(connection = ConnectionState.DISCONNECTED)
            } else {
                it
            }
        }
    }

    override fun connect(address: String) {
        stopScan()
        if (adapter?.isEnabled != true) return fail(appContext.getString(R.string.bluetooth_off))
        val device = adapter?.getRemoteDevice(address) ?: return fail(appContext.getString(R.string.bike_address_invalid))
        mutableState.update {
            it.copy(
                connection = ConnectionState.CONNECTING,
                selected =
                it.devices.firstOrNull { discovered -> discovered.address == address }
                    ?: DiscoveredBike(device.name ?: appContext.getString(R.string.ftms_bike), address, 0),
                latest = null,
                error = null,
            )
        }
        sampleAccumulator.reset()
        gatt?.close()
        gatt = connectGatt(device)
    }

    @Suppress("DEPRECATION")
    private fun connectGatt(device: BluetoothDevice): BluetoothGatt? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
        val settings =
            BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()
        device.connectGatt(settings, appContext.mainExecutor, callback)
    } else {
        device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        sampleAccumulator.reset()
        mutableState.update { it.copy(connection = ConnectionState.DISCONNECTED, latest = null) }
    }

    private fun isCurrent(callbackGatt: BluetoothGatt): Boolean = gatt === callbackGatt

    private fun addDiagnostic(message: String) {
        val line = "${Instant.now()}  $message"
        mutableState.update {
            it.copy(
                diagnostics = (it.diagnostics + line).takeLast(200),
            )
        }
    }

    private fun fail(message: String) {
        addDiagnostic(message)
        mutableState.update { it.copy(connection = ConnectionState.ERROR, error = message) }
    }

    companion object {
        val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        val INDOOR_BIKE_DATA: UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
