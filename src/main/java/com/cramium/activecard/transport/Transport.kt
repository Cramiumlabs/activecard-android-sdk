package com.cramium.activecard.transport

import com.cramium.activecard.TransportMessageWrapper

interface Transport {
    val connectionType: ConnectionType
    fun writeData(data: TransportMessageWrapper): Boolean
    fun readData(data: ByteArray): Boolean
}

enum class ConnectionType {
    BLE, USB, NFC
}