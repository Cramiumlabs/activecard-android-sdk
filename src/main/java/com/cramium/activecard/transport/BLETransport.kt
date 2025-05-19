package com.cramium.activecard.transport

import android.util.Log
import com.cramium.activecard.ActiveCardEvent
import com.cramium.activecard.TransportMessageWrapper
import com.cramium.activecard.ble.BleClient
import com.cramium.activecard.ble.CharOperationFailed
import com.cramium.activecard.exception.ActiveCardException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

class BLETransport(
    private val bleClient: BleClient,
) : Transport {
    companion object {
        val CLIENT_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val RX_UUID: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
        val TX_UUID: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
        val UART_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
    }

    private var cachedBuf: ByteArray? = null
    private var txJob: Job? = null
    override val connectionType: ConnectionType = ConnectionType.BLE
    private val packetHelper = BLEPacketHelper()
    val receiveMessage get() = packetHelper.receiveMessage
    override fun writeData(data: TransportMessageWrapper): Boolean {
        return true
    }

    override fun readData(data: ByteArray): Boolean {
        packetHelper.emit(data)
        cachedBuf = if (cachedBuf == null) byteArrayOf() else cachedBuf
        cachedBuf = cachedBuf!! + data
        when (val message = BLEPacketHelper.parseFullMessagePayload(cachedBuf!!)) {
            is ParseResult.Full -> {
                cachedBuf = null
                return true
            }

            is ParseResult.Partial -> {
                return false
            }

            is ParseResult.Error -> throw message.error
        }
    }


    suspend fun writeData(deviceId: String, messageType: Int, content: ByteArray, isEncrypted: Boolean) {
        val packets = BLEPacketHelper.prepareMessagePackets(messageType, content, isEncrypted)
        withContext(Dispatchers.IO) {
            for (packet in packets) {
                withTimeout(500) {
                    Log.d("AC_Simulator", "Sending message: ${ActiveCardEvent.fromValue(messageType)} - size: ${packet.size}")
                    val result =
                        bleClient.writeCharacteristicWithoutResponse(deviceId, TX_UUID, 0, packet)
                            .catch { e ->
                                // Flow emission error (e.g. BLE stack failure)
                                throw ActiveCardException("cra-aks-008-00", "BLE write-flow error", e)
                            }
                            .first()
                    if (result is CharOperationFailed) {
                        throw ActiveCardException("cra-aks-008-00", "BLE write failed: ${result.errorMessage}")
                    }
                }
                delay(50)
            }
        }
    }

    fun unsubscribeTx() {
        txJob?.cancel()
    }

    fun subscribeToTx(deviceId: String): Flow<ByteArray> {
        return bleClient.setupNotification(deviceId, RX_UUID, 0)
            .map { data ->
                val trimData = data.copyOfRange(5, data.size)
                packetHelper.emit(trimData)
                trimData
            }
            .catch { e -> Log.e("BLETransport", "Got error: $e") }
    }
}

