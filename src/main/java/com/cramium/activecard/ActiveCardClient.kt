package com.cramium.activecard

import android.content.Context
import android.util.Log
import com.cramium.activecard.ble.BleClient
import com.cramium.activecard.ble.BleClientImpl
import com.cramium.activecard.ble.ConnectionUpdate
import com.cramium.activecard.ble.ConnectionUpdateError
import com.cramium.activecard.ble.ConnectionUpdateSuccess
import com.cramium.activecard.ble.MtuNegotiateResult
import com.cramium.activecard.ble.ScanInfo
import com.cramium.activecard.ble.model.ConnectionState
import com.cramium.activecard.ble.model.ScanMode
import com.cramium.activecard.exception.ActiveCardException
import com.cramium.activecard.transport.BLETransport
import com.cramium.activecard.utils.Constants.UNKNOWN_NONCE
import com.cramium.activecard.utils.Ed25519Signer
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * An interface representing an active card device for BLE/USB communication.
 *
 * This interface provides methods to initialize, connect, and interact with the underlying data transport layer.
 */
interface ActiveCardClient {
    /**
     * A flow emitting connection status updates.
     */
    val connectionUpdate: SharedFlow<ConnectionUpdate>

    /**
     * A flow emitting received messages from the device.
     */
    val receiveMessage: SharedFlow<TransportMessageWrapper>

    /**
     * Attempts to connect to the active card device over the chosen transport channel (BLE or USB).
     *
     * @param deviceId The unique identifier of the target device.
     */
    fun connectToDevice(deviceId: String)

    /**
     * Disconnects from the active card device. Stops any ongoing communication.
     *
     * @param deviceId The unique identifier of the target device.
     */
    fun disconnect(deviceId: String)

    /**
     * Scans for available active card devices.
     *
     * @return A cold [Flow] emitting [ScanInfo] objects as devices are discovered.
     */
    fun scanForDevices(): Flow<ScanInfo>

    /**
     * Sends a message to the active card device.
     *
     * @param deviceId    The unique identifier of the target device.
     * @param messageType An integer representing the type of the message (defined by your protocol).
     * @param content     The payload to be sent, as a [ByteArray].
     * @param isEncrypted A flag indicating whether the content is already encrypted.
     */
    suspend fun sendMessage(
        deviceId: String,
        messageType: Int,
        content: ByteArray,
        isEncrypted: Boolean = true
    )

    /**
     * Performs an authentication handshake with the active card device.
     *
     * @param deviceId     The unique identifier of the target device.
     * @param acPublicKey  The ActiveCard's public key bytes.
     * @param mobilePubKey The mobile device's public key bytes.
     * @param onDone       Callback invoked when authentication completes.
     * @return A [Job] representing the authentication coroutine.
     */
    fun authenticateFlow(
        deviceId: String,
        acPublicKey: ByteArray,
        mobilePubKey: ByteArray,
        onDone: () -> Unit
    ): Job

    /**
     * Negotiates the MTU size for BLE characteristic transfers.
     *
     * @param deviceId The unique identifier of the target device.
     * @param size     The desired MTU size in bytes.
     * @return A [Flow] emitting [MtuNegotiateResult] values.
     */
    fun negotiateMtuSize(deviceId: String, size: Int): Flow<MtuNegotiateResult>

    /**
     * Subscribes to the device’s TX characteristic to receive incoming data.
     *
     * @param deviceId The unique identifier of the target device.
     * @return A [Flow] emitting raw [ByteArray] chunks as they arrive.
     */
    fun subscribeToTx(deviceId: String): Flow<ByteArray>
}
/**
 * A default implementation of the [ActiveCardClient] interface, handling BLE/USB communication
 *
 * @property context The [Context] used to initialize and manage resources for the underlying service.
 */
class ActiveCardClientImpl(
    private val context: Context
) : ActiveCardClient {
    private var nonce = UNKNOWN_NONCE
    private val scope = CoroutineScope(Dispatchers.Default)
    private val bleClient: BleClient = BleClientImpl(context).apply {
        initializeClient()
        connectionUpdateSubject
            .onEach { connection ->
                when (connection) {
                    is ConnectionUpdateSuccess -> {
                        when (connection.connectionState) {
                            ConnectionState.CONNECTED -> subscribeToTx(connection.deviceId).collect {}
                            else -> {}
                        }
                    }

                    is ConnectionUpdateError -> {
                        Log.e("ActiveCardImpl", "Connection error: ${connection.errorMessage}")
                    }
                }
            }
            .launchIn(scope)
    }
    private val bleTransport: BLETransport = BLETransport(bleClient)
    override val receiveMessage: SharedFlow<TransportMessageWrapper>
        get() = bleTransport.receiveMessage
    override val connectionUpdate: SharedFlow<ConnectionUpdate>
        get() = bleClient.connectionUpdateSubject

    override fun connectToDevice(deviceId: String) {
        bleClient.connectToDevice(deviceId)
    }

    override fun disconnect(deviceId: String) {
        bleClient.disconnectDevice(deviceId)
    }

    override suspend  fun sendMessage(
        deviceId: String,
        messageType: Int,
        content: ByteArray,
        isEncrypted: Boolean
    ) {
        Log.d("ActiveCardImpl", "Sending message: $messageType")
        bleTransport.writeData(deviceId, messageType, content, isEncrypted)
    }

    override fun scanForDevices(): Flow<ScanInfo> {
        return bleClient
            .scanForDevices(
                services = listOf(),
                scanMode = ScanMode.LOW_LATENCY,
                requireLocationServicesEnabled = true,
            )
    }

    override fun subscribeToTx(deviceId: String): Flow<ByteArray> {
        return bleTransport.subscribeToTx(deviceId)
    }

    override fun authenticateFlow(
        deviceId: String,
        acPublicKey: ByteArray,
        mobilePubKey: ByteArray,
        onDone: () -> Unit
    ): Job {
        nonce = UNKNOWN_NONCE
        return scope.launch {
            delay(2000)
            receiveMessage
                .onEach { result ->
                    when (ActiveCardEvent.fromValue(result.messageType)) {
                        ActiveCardEvent.NONCE_RESPONSE -> nonce = NonceResponse.parseFrom(result.contents).nonce.toByteArray()
                        ActiveCardEvent.SIGNED_NONCE -> {
                            val signedNonce = SignedNonce.parseFrom(result.contents.toByteArray())
                            val message = verifySignedNonce(acPublicKey, nonce, signedNonce.signature.toByteArray())
                            if (message.valid) {
                                sendMessage(deviceId, ActiveCardEvent.SIGNATURE_VERIFICATION_RESULT.id, message.toByteArray())
                                val pubKeyMessage = IdentityPublicKey.newBuilder()
                                    .setPubkey(ByteString.copyFrom(mobilePubKey))
                                    .setSource("mobile")
                                    .build()
                                sendMessage(deviceId, ActiveCardEvent.SEND_IDENTITY_PUBLIC_KEY.id, pubKeyMessage.toByteArray())
                                onDone()
                            }
                            else throw ActiveCardException("cra-aks-008-00", "Signature verification failed")
                        }
                        else -> {}
                    }
                }
                .launchIn(this)
            sendMessage(deviceId, ActiveCardEvent.NONCE_REQUEST.id, byteArrayOf())
        }
    }

    override fun negotiateMtuSize(deviceId: String, size: Int): Flow<MtuNegotiateResult> {
        return bleClient.negotiateMtuSize(deviceId, size)
    }

    private fun verifySignedNonce(pubKey: ByteArray, nonce: ByteArray, signed: ByteArray): SignatureVerificationResult {
        try {
            val isVerified = Ed25519Signer.verify(pubKey, nonce, signed)
            return SignatureVerificationResult.newBuilder()
                .setValid(isVerified)
                .setReason(if (isVerified) "Verification success" else "Verification failed")
                .build()
        } catch (e: Exception) {
            return SignatureVerificationResult.newBuilder()
                .setValid(false)
                .setReason(e.message)
                .build()
        }
    }
}