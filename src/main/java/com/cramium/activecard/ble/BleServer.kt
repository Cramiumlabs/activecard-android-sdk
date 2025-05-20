package com.cramium.activecard.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.cramium.activecard.TransportMessageWrapper
import com.cramium.activecard.transport.BLEPacketHelper
import com.cramium.activecard.transport.BLETransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import java.util.Arrays
import java.util.UUID

interface BleServer {
    val receiveMessage: SharedFlow<TransportMessageWrapper>
    val connectedDevices: Set<BluetoothDevice>
    fun start(name: String = "AC_Simulator")
    fun stop()
    suspend fun notifyClients(data: ByteArray)
    fun disconnectDevice(device: BluetoothDevice)
}

@SuppressLint("MissingPermission")
class BleServerImpl(
    private val context: Context
): BleServer {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val advertiser: BluetoothLeAdvertiser? = adapter.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null
    private val clients = mutableSetOf<BluetoothDevice>()
    override val connectedDevices: Set<BluetoothDevice>
        get() = clients
    private val blePacketHelper = BLEPacketHelper()
    override val receiveMessage = blePacketHelper.receiveMessage
    companion object {
        private const val TAG = "BleGattServer"
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /** Start GATT server and BLE advertising */
    override fun start(name: String) {
        setupGattServer()
        startAdvertising(name)
    }

    /** Stop advertising and shut down GATT server */
    override fun stop() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
    }

    /** Build service, characteristics, and register the GATT server */
    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.apply {
            // Create primary service
            val service = BluetoothGattService(BLETransport.UART_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            // Write-only characteristic
            val writeChar = BluetoothGattCharacteristic(
                BLETransport.TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // Notify-only characteristic (requires CCC descriptor)
            val notifyChar = BluetoothGattCharacteristic(
                BLETransport.RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCC_DESCRIPTOR_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }

            // Add characteristics to service
            service.addCharacteristic(writeChar)
            service.addCharacteristic(notifyChar)

            // Register service
            addService(service)
        }
    }

    /** Advertise with the given device name and service UUID */
    private fun startAdvertising(name: String) {
        adapter.name = name
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BLETransport.UART_UUID))
            .build()
        Log.d("AC_Simulator", "Start advertising with device name: $name")
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started: $settingsInEffect")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    /** Send a notification to all connected clients */
    override suspend fun notifyClients(data: ByteArray) {
        delay(50)
        val service = gattServer?.getService(BLETransport.UART_UUID) ?: return
        val char = service.getCharacteristic(BLETransport.RX_UUID) ?: return
        Log.d("AC_Simulator", "Send data to client - data size: ${data.size}")
        clients.forEach { device ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, char, false, data)
            } else {
                char.value = data
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    /** Tear down the GATT connection to that device */
    override fun disconnectDevice(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.cancelConnection(device)
        } else {
            gattServer?.close()
            gattServer = null
        }
        clients.remove(device)
        Log.d(TAG, "Device forcibly disconnected: ${device.address}")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clients.add(device)
                Log.d(TAG, "Device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clients.remove(device)
                Log.d(TAG, "Device disconnected: ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val service = gattServer?.getService(BLETransport.UART_UUID)
            val readChar = service?.getCharacteristic(BLETransport.RX_UUID)
            if (characteristic.uuid == BLETransport.RX_UUID && readChar != null) {
                val value = readChar.value ?: byteArrayOf()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.d("BleGattServer", "onMtuChanged: $mtu")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d("AC_Simulator", "On write request: ${characteristic.uuid} - value: ${value.size}")
            blePacketHelper.emit(value.copyOfRange(5, value.size))
            if (characteristic.uuid == BLETransport.TX_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                // Client Characteristic Configuration (enable/disable notifications)
                val enabled = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.d(TAG, "Notifications ${if (enabled) "enabled" else "disabled"} for ${device.address}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Log.d(TAG, "Notification sent to ${device.address}, status=$status")
        }
    }
}