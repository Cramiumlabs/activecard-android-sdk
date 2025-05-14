package com.cramium.activecard.ble

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.cramium.activecard.ble.model.ConnectionQueue
import com.cramium.activecard.ble.model.ConnectionState
import com.cramium.activecard.ble.model.toConnectionState
import com.polidea.rxandroidble3.RxBleConnection
import com.polidea.rxandroidble3.RxBleCustomOperation
import com.polidea.rxandroidble3.RxBleDevice
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit

internal class DeviceConnector(
    private val device: RxBleDevice,
    private val updateListeners: (update: ConnectionUpdate) -> Unit,
    private val connectionQueue: ConnectionQueue,
    private val shouldCheckDeviceStatus: Boolean,
) {
    companion object {
        private const val minTimeMsBeforeDisconnectingIsAllowed = 200L
        private const val delayMsAfterClearingCache = 300L
    }

    private val connectDeviceSubject = BehaviorSubject.create<EstablishConnectionResult>()

    private var timestampEstablishConnection: Long = 0

    @VisibleForTesting
    internal var connectionDisposable: Disposable? = null

    private val lazyConnection =
        lazy {
            connectionDisposable = establishConnection(device)
            connectDeviceSubject
        }

    private val currentConnection: EstablishConnectionResult?
        get() = if (lazyConnection.isInitialized()) connection.value else null

    internal val connection by lazyConnection

    private val connectionStatusUpdates: Disposable by lazy {
        device.observeConnectionStateChanges()
            .startWithItem(device.connectionState)
            .map<ConnectionUpdate> { state -> ConnectionUpdateSuccess(device.macAddress, state.toConnectionState()) }
            .onErrorReturn { error ->
                ConnectionUpdateError(
                    device.macAddress,
                    error.message
                        ?: "Unknown error",
                )
            }
            .subscribe { update ->
                updateListeners.invoke(update)
            }
    }

    internal fun disconnectDevice(deviceId: String) {
        val diff = System.currentTimeMillis() - timestampEstablishConnection

        /*
        in order to prevent Android from ignoring disconnects we add a delay when we try to
        disconnect to quickly after establishing connection. https://issuetracker.google.com/issues/37121223
         */
        if (diff < minTimeMsBeforeDisconnectingIsAllowed) {
            Single.timer(minTimeMsBeforeDisconnectingIsAllowed - diff, TimeUnit.MILLISECONDS)
                .doFinally {
                    sendDisconnectedUpdate(deviceId)
                    disposeSubscriptions()
                }.subscribe()
        } else {
            sendDisconnectedUpdate(deviceId)
            disposeSubscriptions()
        }
    }

    private fun sendDisconnectedUpdate(deviceId: String) {
        updateListeners(ConnectionUpdateSuccess(deviceId, ConnectionState.DISCONNECTED))
    }

    private fun disposeSubscriptions() {
        connectionDisposable?.dispose()
        connectDeviceSubject.onComplete()
        connectionStatusUpdates.dispose()
    }

    private fun establishConnection(rxBleDevice: RxBleDevice): Disposable {
        val deviceId = rxBleDevice.macAddress
        val status = rxBleDevice.connectionState.toConnectionState()
        connectionQueue.addToQueue(deviceId)
        updateListeners(ConnectionUpdateSuccess(deviceId, ConnectionState.CONNECTING))

        return waitUntilFirstOfQueue(deviceId)
            .switchMap { queue ->
                if (shouldCheckDeviceStatus && status == ConnectionState.DISCONNECTED) {
                    Observable.just(
                        EstablishConnectionFailure(deviceId,
                        "Device must not establish connection when attempt to write/read")
                    )
                } else if (!queue.contains(deviceId)) {
                    Observable.just(
                        EstablishConnectionFailure(
                            deviceId,
                            "Device is not in queue",
                        ),
                    )
                } else {
                    connectDevice(rxBleDevice)
                        .map<EstablishConnectionResult> { EstablishedConnection(rxBleDevice.macAddress, it) }
                }
            }
            .onErrorReturn { error ->
                EstablishConnectionFailure(
                    rxBleDevice.macAddress,
                    error.message ?: "Unknown error",
                )
            }
            .doOnNext {
                // Trigger side effect by calling the lazy initialization of this property so
                // listening to changes starts.
                connectionStatusUpdates
                timestampEstablishConnection = System.currentTimeMillis()
                connectionQueue.removeFromQueue(deviceId)
                if (it is EstablishConnectionFailure) {
                    updateListeners.invoke(ConnectionUpdateError(deviceId, it.errorMessage))
                }
            }
            .doOnError {
                connectionQueue.removeFromQueue(deviceId)
                updateListeners.invoke(
                    ConnectionUpdateError(
                        deviceId,
                        it.message
                            ?: "Unknown error",
                    ),
                )
            }
            .subscribe(
                { connectDeviceSubject.onNext(it) },
                { throwable -> connectDeviceSubject.onError(throwable) },
            )
    }

    private fun connectDevice(
        rxBleDevice: RxBleDevice,
    ): Observable<RxBleConnection> =
        rxBleDevice.establishConnection(false)
            .observeOn(AndroidSchedulers.mainThread())
            .retry(4) { throwable ->
                Log.e("DeviceConnector", "Error: ${throwable.message}")
                true
            }
            .compose {
                it
            }

    internal fun clearGattCache(): Completable =
        currentConnection?.let { connection ->
            when (connection) {
                is EstablishedConnection -> clearGattCache(connection.rxConnection)
                is EstablishConnectionFailure -> Completable.error(Throwable(connection.errorMessage))
            }
        } ?: Completable.error(IllegalStateException("Connection is not established"))

    /**
     * Clear GATT attribute cache using an undocumented method `BluetoothGatt.refresh()`.
     *
     * May trigger the following warning in the system message log:
     *
     * https://android.googlesource.com/platform/frameworks/base/+/pie-release/config/hiddenapi-light-greylist.txt
     *
     *     Accessing hidden method Landroid/bluetooth/BluetoothGatt;->refresh()Z (light greylist, reflection)
     *
     * Known to work up to Android Q beta 2.
     */
    private fun clearGattCache(connection: RxBleConnection): Completable {
        val operation =
            RxBleCustomOperation<Unit> { bluetoothGatt, _, _ ->
                try {
                    val refreshMethod = bluetoothGatt.javaClass.getMethod("refresh")
                    val success = refreshMethod.invoke(bluetoothGatt) as Boolean
                    if (success) {
                        Observable.empty<Unit>()
                            .delay(delayMsAfterClearingCache, TimeUnit.MILLISECONDS)
                    } else {
                        val reason = "BluetoothGatt.refresh() returned false"
                        Observable.error(RuntimeException(reason))
                    }
                } catch (e: ReflectiveOperationException) {
                    Observable.error<Unit>(e)
                }
            }
        return connection.queue(operation).ignoreElements()
    }

    private fun waitUntilFirstOfQueue(deviceId: String) =
        connectionQueue.observeQueue()
            .filter { queue ->
                queue.firstOrNull() == deviceId || !queue.contains(deviceId)
            }
            .takeUntil { it.isEmpty() || it.first() == deviceId }

    /**
     * Reads the current RSSI value of the device
     */
    internal fun readRssi(): Single<Int> =
        currentConnection?.let { connection ->
            when (connection) {
                is EstablishedConnection -> connection.rxConnection.readRssi()
                is EstablishConnectionFailure -> Single.error(Throwable(connection.errorMessage))
            }
        } ?: Single.error(IllegalStateException("Connection is not established"))
}