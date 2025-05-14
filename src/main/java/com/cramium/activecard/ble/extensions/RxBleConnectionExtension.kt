package com.cramium.activecard.ble.extensions

import android.bluetooth.BluetoothGattCharacteristic
import com.polidea.rxandroidble3.RxBleConnection
import io.reactivex.rxjava3.core.Single
import java.util.UUID

fun RxBleConnection.resolveCharacteristic(
    uuid: UUID,
    instanceId: Int
): Single<BluetoothGattCharacteristic> =
    discoverServices().flatMap { services ->
        Single.just(
            services.bluetoothGattServices.flatMap { service ->
                service.characteristics.filter {
                    it.uuid == uuid
                }
            }.single()
        )
    }

fun RxBleConnection.writeCharWithResponse(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray
): Single<ByteArray> {
    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    return writeCharacteristic(characteristic, value)
}

fun RxBleConnection.writeCharWithoutResponse(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray
): Single<ByteArray> {
    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    return writeCharacteristic(characteristic, value)
}
