package com.cramium.activecard.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.cramium.activecard.ble.converter.extractManufacturerData
import com.cramium.activecard.ble.extensions.resolveCharacteristic
import com.cramium.activecard.ble.extensions.writeCharWithResponse
import com.cramium.activecard.ble.extensions.writeCharWithoutResponse
import com.cramium.activecard.ble.model.ConnectionQueue
import com.cramium.activecard.ble.model.ScanMode
import com.cramium.activecard.ble.utils.toBleState
import com.polidea.rxandroidble3.LogConstants
import com.polidea.rxandroidble3.LogOptions
import com.polidea.rxandroidble3.NotificationSetupMode
import com.polidea.rxandroidble3.RxBleClient
import com.polidea.rxandroidble3.RxBleConnection
import com.polidea.rxandroidble3.RxBleDevice
import com.polidea.rxandroidble3.RxBleDeviceServices
import com.polidea.rxandroidble3.scan.IsConnectable
import com.polidea.rxandroidble3.scan.ScanFilter
import com.polidea.rxandroidble3.scan.ScanSettings
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.rx3.await
import java.util.UUID
import java.util.concurrent.TimeUnit

interface BleClient {
    val connectionUpdateSubject: SharedFlow<ConnectionUpdate>
    fun initializeClient()
    fun scanForDevices(
        services: List<ParcelUuid>,
        scanMode: ScanMode,
        requireLocationServicesEnabled: Boolean
    ): Flow<ScanInfo>

    fun bondDevice(deviceId: String)
    fun connectToDevice(deviceId: String)
    fun disconnectDevice(deviceId: String)
    fun discoverServices(deviceId: String): Flow<RxBleDeviceServices>
    fun clearGattCache(deviceId: String): Flow<Unit>
    fun readCharacteristic(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int
    ): Flow<CharOperationResult>

    fun setupNotification(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int
    ): Flow<ByteArray>

    fun writeCharacteristicWithResponse(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
        value: ByteArray
    ): Flow<CharOperationResult>

    fun writeCharacteristicWithoutResponse(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
        value: ByteArray
    ): Flow<CharOperationResult>

    fun negotiateMtuSize(deviceId: String, size: Int): Flow<MtuNegotiateResult>
    fun observeBleStatus(): Flow<BleStatus>
    fun requestConnectionPriority(deviceId: String, priority: ConnectionPriority):
            Flow<RequestConnectionPriorityResult>

    fun readRssi(deviceId: String): Flow<Int>
}

class BleClientImpl(private val context: Context) : BleClient {
    companion object {
        private val TAG = BleClientImpl::class.java.simpleName
        lateinit var rxBleClient: RxBleClient
        internal var activeConnections = mutableMapOf<String, DeviceConnector>()
    }

    init {
        initializeClient()
    }

    private val connectionQueue = ConnectionQueue()
    private val allConnections = CompositeDisposable()
    private val _connectionUpdateSubject: MutableSharedFlow<ConnectionUpdate> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = 1)
    override val connectionUpdateSubject: SharedFlow<ConnectionUpdate>
        get() = _connectionUpdateSubject

    override fun initializeClient() {
        activeConnections = mutableMapOf()
        rxBleClient = RxBleClient.create(context)
    }

    override fun scanForDevices(
        services: List<ParcelUuid>,
        scanMode: ScanMode,
        requireLocationServicesEnabled: Boolean
    ): Flow<ScanInfo> {
        val filters =
            services.map { service ->
                ScanFilter.Builder()
                    .setServiceUuid(service)
                    .build()
            }.toTypedArray()

        return rxBleClient.scanBleDevices(
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build(),
            *filters,
        )
            .observeOn(AndroidSchedulers.mainThread())
            .map { result ->
                ScanInfo(
                    result.bleDevice.macAddress,
                    result.scanRecord.deviceName
                        ?: result.bleDevice.name ?: "",
                    result.rssi,
                    when (result.isConnectable) {
                        null -> Connectable.UNKNOWN
                        IsConnectable.LEGACY_UNKNOWN -> Connectable.UNKNOWN
                        IsConnectable.NOT_CONNECTABLE -> Connectable.NOT_CONNECTABLE
                        IsConnectable.CONNECTABLE -> Connectable.CONNECTABLE
                    },
                    result.scanRecord.serviceData?.mapKeys { it.key.uuid } ?: emptyMap(),
                    result.scanRecord.serviceUuids?.map { it.uuid } ?: emptyList(),
                    extractManufacturerData(result.scanRecord.manufacturerSpecificData),
                )
            }.asFlow()
    }

    @SuppressLint("MissingPermission")
    override fun bondDevice(deviceId: String) {
        val device = rxBleClient.getBleDevice(deviceId)
        // Check if the device is already bonded
        if (device.bluetoothDevice.getBondState() == BluetoothDevice.BOND_NONE) {
            device.bluetoothDevice.createBond()
        } else {
            Log.d(TAG, "Device is already bonded.")
        }
    }

    override fun connectToDevice(deviceId: String) {
        allConnections.add(
            getConnection(deviceId, shouldCheckDeviceStatus = false)
                .subscribe({ result ->
                    when (result) {
                        is EstablishedConnection -> {
                        }

                        is EstablishConnectionFailure -> {
                            _connectionUpdateSubject.tryEmit(
                                ConnectionUpdateError(
                                    deviceId,
                                    result.errorMessage,
                                ),
                            )
                        }
                    }
                }, { error ->
                    if (error.message?.contains("Already connected to device with MAC address") == true) {
                        Log.e("BleClient", "Already connected to device")
                        disconnectDevice(deviceId)
                    }
                    _connectionUpdateSubject.tryEmit(
                        ConnectionUpdateError(
                            deviceId,
                            error.message ?: "unknown error",
                        ),
                    )
                }),
        )
    }

    override fun disconnectDevice(deviceId: String) {
        activeConnections[deviceId]?.disconnectDevice(deviceId)
        activeConnections.remove(deviceId)
    }

    @SuppressLint("MissingPermission")
    override fun discoverServices(deviceId: String): Flow<RxBleDeviceServices> =
        getConnection(deviceId, shouldCheckDeviceStatus = true).flatMapSingle { connectionResult ->
            when (connectionResult) {
                is EstablishedConnection ->
                    if (rxBleClient.getBleDevice(connectionResult.deviceId).bluetoothDevice.bondState == BOND_BONDING) {
                        Single.error(
                            Exception(
                                "Bonding is in progress wait for bonding to be finished before executing more operations on the device",
                            ),
                        )
                    } else {
                        connectionResult.rxConnection.discoverServices()
                    }

                is EstablishConnectionFailure -> Single.error(Exception(connectionResult.errorMessage))
            }
        }.asFlow()

    override fun clearGattCache(deviceId: String): Flow<Unit> = flow {
        activeConnections[deviceId]?.let(DeviceConnector::clearGattCache)?.await()
        emit(Unit)
    }


    override fun readCharacteristic(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int
    ): Flow<CharOperationResult> =
        getConnection(deviceId, shouldCheckDeviceStatus = true).flatMapSingle { connectionResult ->
            when (connectionResult) {
                is EstablishedConnection -> {
                    connectionResult.rxConnection.resolveCharacteristic(
                        characteristicId,
                        characteristicInstanceId,
                    ).flatMap { c: BluetoothGattCharacteristic ->
                        connectionResult.rxConnection.readCharacteristic(c)
                            /*
                            On Android7 the ble stack frequently gives incorrectly
                            the error GAT_AUTH_FAIL(137) when reading char that will establish
                            the bonding with the peripheral. By retrying the operation once we
                            deviate between this flaky one time error and real auth failed cases
                             */
                            .retry(1) { Build.VERSION.SDK_INT < Build.VERSION_CODES.O }
                            .map { value ->
                                CharOperationSuccessful(deviceId, value.asList())
                            }
                    }
                }

                is EstablishConnectionFailure ->
                    Single.just(
                        CharOperationFailed(
                            deviceId,
                            "failed to connect ${connectionResult.errorMessage}",
                        ),
                    )
            }
        }.asFlow()

    override fun setupNotification(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int
    ): Flow<ByteArray> = getConnection(deviceId, shouldCheckDeviceStatus = true)
        .flatMap { deviceConnection ->
            setupNotificationOrIndication(
                deviceConnection,
                characteristicId,
                characteristicInstanceId,
            )
        }
        // now we have setup the subscription and we want the actual value
        .flatMap { notificationObservable ->
            notificationObservable
        }.asFlow()

    override fun writeCharacteristicWithResponse(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
        value: ByteArray
    ): Flow<CharOperationResult> =
        executeWriteOperation(
            deviceId,
            characteristicId,
            characteristicInstanceId,
            value,
            RxBleConnection::writeCharWithResponse,
        )

    override fun writeCharacteristicWithoutResponse(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
        value: ByteArray
    ): Flow<CharOperationResult> =
        executeWriteOperation(
            deviceId,
            characteristicId,
            characteristicInstanceId,
            value,
            RxBleConnection::writeCharWithoutResponse,
        )

    override fun negotiateMtuSize(deviceId: String, size: Int): Flow<MtuNegotiateResult> =
        getConnection(deviceId, shouldCheckDeviceStatus = true)
            .flatMapSingle { connectionResult ->
                when (connectionResult) {
                    is EstablishedConnection ->
                        connectionResult.rxConnection.requestMtu(size)
                            .map { value -> MtuNegotiateSuccessful(deviceId, value) }

                    is EstablishConnectionFailure ->
                        Single.just(
                            MtuNegotiateFailed(
                                deviceId,
                                "failed to connect ${connectionResult.errorMessage}",
                            ),
                        )
                }
            }
            .asFlow()

    override fun observeBleStatus(): Flow<BleStatus> =
        rxBleClient.observeStateChanges()
            .startWithItem(rxBleClient.state)
            .map { it.toBleState() }
            .asFlow()

    override fun requestConnectionPriority(
        deviceId: String,
        priority: ConnectionPriority
    ): Flow<RequestConnectionPriorityResult> =
        getConnection(deviceId, shouldCheckDeviceStatus = true)
            .switchMapSingle { connectionResult ->
                when (connectionResult) {
                    is EstablishedConnection ->
                        connectionResult.rxConnection.requestConnectionPriority(
                            priority.code,
                            2,
                            TimeUnit.SECONDS,
                        )
                            .toSingle {
                                RequestConnectionPrioritySuccess(deviceId)
                            }

                    is EstablishConnectionFailure ->
                        Single.fromCallable {
                            RequestConnectionPriorityFailed(deviceId, connectionResult.errorMessage)
                        }
                }
            }.asFlow()

    override fun readRssi(deviceId: String): Flow<Int> =
        getConnection(deviceId, shouldCheckDeviceStatus = true).flatMapSingle { connectionResult ->
            when (connectionResult) {
                is EstablishedConnection -> {
                    connectionResult.rxConnection.readRssi()
                }

                is EstablishConnectionFailure ->
                    Single.error(
                        java.lang.IllegalStateException(
                            "Reading RSSI failed. Device is not connected",
                        ),
                    )
            }
        }.asFlow()

    private fun getConnection(
        deviceId: String,
        shouldCheckDeviceStatus: Boolean = false,
    ): Observable<EstablishConnectionResult> {
        val device = rxBleClient.getBleDevice(deviceId)
        val connector =
            activeConnections.getOrPut(deviceId) {
                createDeviceConnector(device, shouldCheckDeviceStatus)
            }
        return connector.connection
    }

    private fun executeWriteOperation(
        deviceId: String,
        characteristicId: UUID,
        characteristicInstanceId: Int,
        value: ByteArray,
        bleOperation: RxBleConnection.(characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Single<ByteArray>,
    ): Flow<CharOperationResult> {
        return getConnection(deviceId, shouldCheckDeviceStatus = true)
            .flatMapSingle { connectionResult ->
                when (connectionResult) {
                    is EstablishedConnection -> {
                        connectionResult.rxConnection.resolveCharacteristic(
                            characteristicId,
                            characteristicInstanceId
                        )
                            .flatMap { characteristic ->
                                connectionResult.rxConnection.bleOperation(characteristic, value)
                                    .map { value ->
                                        CharOperationSuccessful(
                                            deviceId,
                                            value.asList()
                                        )
                                    }
                            }
                    }

                    is EstablishConnectionFailure -> {
                        Single.just(
                            CharOperationFailed(
                                deviceId,
                                "failed to connect ${connectionResult.errorMessage}",
                            ),
                        )
                    }
                }
            }.asFlow()
    }


    @SuppressLint("MissingPermission")
    private fun setupNotificationOrIndication(
        deviceConnection: EstablishConnectionResult,
        characteristicId: UUID,
        characteristicInstanceId: Int,
    ): Observable<Observable<ByteArray>> =
        when (deviceConnection) {
            is EstablishedConnection -> {
                if (rxBleClient.getBleDevice(deviceConnection.deviceId).bluetoothDevice.bondState == BOND_BONDING) {
                    Observable.error(
                        Exception("Bonding is in progress wait for bonding to be finished before executing more operations on the device"),
                    )
                } else {
                    deviceConnection.rxConnection.resolveCharacteristic(
                        characteristicId,
                        characteristicInstanceId,
                    ).flatMapObservable { characteristic ->
                        val mode =
                            if (characteristic.descriptors.isEmpty()) {
                                NotificationSetupMode.COMPAT
                            } else {
                                NotificationSetupMode.DEFAULT
                            }

                        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            deviceConnection.rxConnection.setupNotification(
                                characteristic,
                                mode,
                            )
                        } else {
                            deviceConnection.rxConnection.setupIndication(characteristic, mode)
                        }
                    }
                }
            }

            is EstablishConnectionFailure -> {
                Observable.just(Observable.empty())
            }
        }

    // enable this for extra debug output on the android stack
    private fun enableDebugLogging() =
        RxBleClient
            .updateLogOptions(
                LogOptions.Builder().setLogLevel(LogConstants.VERBOSE)
                    .setMacAddressLogSetting(LogConstants.MAC_ADDRESS_FULL)
                    .setUuidsLogSetting(LogConstants.UUIDS_FULL)
                    .setShouldLogAttributeValues(true)
                    .build(),
            )

    internal open fun createDeviceConnector(
        device: RxBleDevice,
        shouldCheckDeviceStatus: Boolean,
    ) = DeviceConnector(
        device,
        { update -> _connectionUpdateSubject.tryEmit(update) },
        connectionQueue,
        shouldCheckDeviceStatus
    )
}