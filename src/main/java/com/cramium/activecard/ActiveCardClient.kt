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
import com.cramium.activecard.transport.ProtoBufHelper
import com.cramium.activecard.utils.Ed25519Signer
import com.cramium.activecard.utils.generateNonce
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
     * @param mobilePrivateKey The mobile device's private key bytes.
     * @param onDone       Callback invoked when authentication completes.
     * @return A [Job] representing the authentication coroutine.
     */
    fun authenticateFlow(
        deviceId: String,
        acPublicKey: ByteArray,
        mobilePubKey: ByteArray,
        mobilePrivateKey: ByteArray,
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

    override suspend fun sendMessage(
        deviceId: String,
        messageType: Int,
        content: ByteArray,
        isEncrypted: Boolean
    ) {
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
        mobilePrivateKey: ByteArray,
        onDone: () -> Unit
    ): Job {
        val nonceBytes = generateNonce()
        return scope.launch {
            delay(2000)
            receiveMessage
                .onEach { result ->
                    when (ActiveCardEvent.fromValue(result.messageType)) {
                        ActiveCardEvent.SIGNED_NONCE -> {
                            val signedNonce = SignedNonce.parseFrom(result.contents.toByteArray())
                            Log.d("AC_Simulator", "Receive signed nonce event $signedNonce")
                            val message = ProtoBufHelper.buildVerifySignedNonce(acPublicKey, nonceBytes, signedNonce.signature.toByteArray())
                            if (message.valid) {
                                Log.d("AC_Simulator", "Send signature verification result event $message")
                                sendMessage(deviceId, ActiveCardEvent.SIGNATURE_VERIFICATION_RESULT.id, message.toByteArray())
                                val pubKeyMessage = ProtoBufHelper.buildIdentityPublicKey(mobilePubKey, "mobile")
                                Log.d("AC_Simulator", "Send mobile public key event $pubKeyMessage")
                                sendMessage(deviceId, ActiveCardEvent.SEND_IDENTITY_PUBLIC_KEY.id, pubKeyMessage.toByteArray())
                            }
                            else throw ActiveCardException("cra-aks-008-00", "Signature verification failed")
                        }

                        ActiveCardEvent.CHALLENGE -> {
                            val nonce = NonceRequest.parseFrom(result.contents)
                            Log.d("AC_Simulator", "Receive challenge event $nonce")
                            val signedNonce = ProtoBufHelper.buildSignedNonce(nonce.nonce.toByteArray(), mobilePrivateKey)
                            Log.d("AC_Simulator", "Send signed nonce event $signedNonce")
                            sendMessage(deviceId, ActiveCardEvent.SIGNED_NONCE.id, signedNonce.toByteArray())
                        }

                        ActiveCardEvent.SIGNATURE_VERIFICATION_RESULT -> {
                            val verificationResult = SignatureVerificationResult.parseFrom(result.contents.toByteArray())
                            Log.d("AC_Simulator", "Receive verification result event $verificationResult")
                            if (!verificationResult.valid) throw ActiveCardException("cra-mks-008-01", "Signature verification failed")
                            onDone()
                        }

                        else -> {}
                    }
                }
                .launchIn(this)
            val nonce = ProtoBufHelper.buildNonceRequest(nonceBytes)
            Log.d("AC_Simulator", "Send challenge $nonce")
            sendMessage(deviceId, ActiveCardEvent.CHALLENGE.id, nonce.toByteArray())
        }
    }

    fun shareSecretEstablishment(
        deviceId: String,
        mobilePrivateKey: ByteArray,
        acPublicKey: ByteArray,
    ): Job {
        return scope.launch {
            // TODO: Need generate ecdh from go-sdk
            receiveMessage
                .onEach { result ->
                    when (ActiveCardEvent.fromValue(result.messageType)) {
                        ActiveCardEvent.SEND_ECDH_PUBLIC_KEY -> {
                            val ecdh = EcdhPublicKey.parseFrom(result.contents.toByteArray())
                            val verify = Ed25519Signer.verify(acPublicKey, ecdh.publicKey.toByteArray(), ecdh.signature.toByteArray())
                            if (verify) {
                                // TODO: Derive shared ecdh key from active-card device
                            } else {
                                throw ActiveCardException("cra-aks-008-00", "Signature verification failed")
                            }
                        }

                        else -> {}
                    }
                }
                .launchIn(this)
            val keypair = byteArrayOf() // TODO: Generate ecdh keypair from go-sdk here
            val signature = Ed25519Signer.sign(mobilePrivateKey, keypair)
            val ecdh = ProtoBufHelper.buildECDHPublicKey(keypair, "mobile", signature)
            sendMessage(deviceId, ActiveCardEvent.SEND_ECDH_PUBLIC_KEY.id, ecdh.toByteArray())
        }
    }

    fun ownershipAssociate(
        deviceId: String,
        userId: String,
        mobilePrivateKey: ByteArray,
        onDone: () -> Unit,
        onFailed: () -> Unit,
    ): Job {
        return scope.launch {
            receiveMessage
                .onEach { result ->
                    when (ActiveCardEvent.fromValue(result.messageType)) {
                        ActiveCardEvent.PAIRING_CONFIRMATION -> {
                            val confirmation =
                                PairingConfirmation.parseFrom(result.contents.toByteArray())
                            if (confirmation.confirmed) onDone() else onFailed()
                        }

                        else -> {}
                    }
                }
                .launchIn(this)
            val userIdentity = ProtoBufHelper.buildUserIdentity(userId, mobilePrivateKey)
            sendMessage(deviceId, ActiveCardEvent.SEND_USER_IDENTITY.id, userIdentity.toByteArray())
        }
    }

    override fun negotiateMtuSize(deviceId: String, size: Int): Flow<MtuNegotiateResult> {
        return bleClient.negotiateMtuSize(deviceId, size)
    }

}