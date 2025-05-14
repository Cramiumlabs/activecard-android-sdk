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
import com.cramium.activecard.exception.MpcException
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
 * An interface representing an active card device for BLE/USB communication. This interface
 * provides methods to initialize, connect, and interact with the underlying data transport layer.
 */
interface ActiveCardClient {
    val connectionUpdate: SharedFlow<ConnectionUpdate>
    val receiveMessage: SharedFlow<TransportMessageWrapper>
    /**
     * Attempts to connect to the active card device over the chosen transport channel (BLE or USB).
     *
     * @return `true` if the connection is successfully initiated, `false` otherwise.
     */
    fun connectToDevice(deviceId: String)

    /**
     * Disconnects the active card device. Any ongoing communication will be halted.
     */
    fun disconnect(deviceId: String)

    fun scanForDevices(): Flow<ScanInfo>

    /**
     * Sends a message to the active card device.
     *
     * @param messageType An integer representing the type of the message (defined by your protocol).
     * @param content The payload to be sent, as a [ByteArray].
     * @param isEncrypted A flag indicating whether the content is already encrypted.
     * Currently not used for additional logic, but can be extended in the future for
     * handling encryption within the method.
     */
    suspend fun sendMessage(deviceId: String, messageType: Int, content: ByteArray, isEncrypted: Boolean  = false)

    fun authenticateFlow(deviceId: String, acPublicKey: ByteArray, mobilePubKey: ByteArray, onDone: () -> Unit): Job

    fun negotiateMtuSize(deviceId: String, size: Int): Flow<MtuNegotiateResult>

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
        val messageWrapper = TransportMessageWrapper.newBuilder()
            .setMessageType(messageType)
            .setMessageSize(content.size)
            .setIsEncrypted(isEncrypted)
            .setIv(ByteString.copyFrom(ByteArray(1) { 0 }))
            .setTag(ByteString.copyFrom(ByteArray(1) { 0 }))
            .setContents(ByteString.copyFrom(content))
            .setSessionId(ByteString.copyFrom(ByteArray(8) { it.toByte() }))
            .setSessionStartTime(System.currentTimeMillis())
            .build()
        bleTransport.writeData(deviceId, messageWrapper)
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
                            else throw MpcException("cra-mks-008-00", "Signature verification failed")
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