package com.cramium.activecard.ble.utils

import com.cramium.activecard.ble.BleStatus
import com.cramium.activecard.ble.ConnectionPriority
import com.polidea.rxandroidble3.RxBleClient
import com.polidea.rxandroidble3.RxBleClient.State.BLUETOOTH_NOT_AVAILABLE
import com.polidea.rxandroidble3.RxBleClient.State.BLUETOOTH_NOT_ENABLED
import com.polidea.rxandroidble3.RxBleClient.State.LOCATION_PERMISSION_NOT_GRANTED
import com.polidea.rxandroidble3.RxBleClient.State.LOCATION_SERVICES_NOT_ENABLED
import com.polidea.rxandroidble3.RxBleClient.State.READY

fun RxBleClient.State.toBleState(): BleStatus =
    when (this) {
        BLUETOOTH_NOT_AVAILABLE -> BleStatus.UNSUPPORTED
        LOCATION_PERMISSION_NOT_GRANTED -> BleStatus.UNAUTHORIZED
        BLUETOOTH_NOT_ENABLED -> BleStatus.POWERED_OFF
        LOCATION_SERVICES_NOT_ENABLED -> BleStatus.LOCATION_SERVICES_DISABLED
        READY -> BleStatus.READY
    }

fun Int.toConnectionPriority() =
    when (this) {
        0 -> ConnectionPriority.BALANCED
        1 -> ConnectionPriority.HIGH_PERFORMACE
        2 -> ConnectionPriority.LOW_POWER
        else -> ConnectionPriority.BALANCED
    }
