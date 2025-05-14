package com.cramium.activecard.ble.model

import com.polidea.rxandroidble3.RxBleConnection

@Suppress("Detekt.MagicNumber")
enum class ConnectionState(val code: Int) {
    CONNECTING(0),
    CONNECTED(1),
    DISCONNECTING(2),
    DISCONNECTED(3),
    UNKNOWN(4)
}

fun RxBleConnection.RxBleConnectionState.toConnectionState(): ConnectionState =
    when (this.name) {
        "DISCONNECTED" -> ConnectionState.DISCONNECTED
        "CONNECTING" -> ConnectionState.CONNECTING
        "CONNECTED" -> ConnectionState.CONNECTED
        "DISCONNECTING" -> ConnectionState.DISCONNECTING
        else -> ConnectionState.UNKNOWN
}
