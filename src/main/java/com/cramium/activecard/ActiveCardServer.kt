package com.cramium.activecard

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.cramium.activecard.ble.BleServer
import com.cramium.activecard.ble.BleServerImpl
import com.cramium.activecard.exception.ActiveCardException
import com.cramium.activecard.transport.BLEPacketHelper
import com.cramium.activecard.transport.ProtoBufHelper
import com.cramium.activecard.utils.Ed25519Signer
import com.cramium.activecard.utils.generateNonce
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * An interface representing the server side of an active card transport layer.
 *
 * This interface handles BLE advertising and incoming message flows, as well as the
 * authentication handshake with a mobile client.
 */
interface ActiveCardServer {
    /**
     * A flow emitting messages received from the mobile client.
     * Each [TransportMessageWrapper] contains the raw payload, encryption metadata,
     * and session identifiers.
     */
    val receiveMessage: SharedFlow<TransportMessageWrapper>

    /**
     * Begins BLE advertising under the given device name.
     *
     * @param deviceName the human-readable name to broadcast for discovery by clients.
     */
    fun startAdvertising(deviceName: String)

    /**
     * Stops any ongoing BLE advertising.
     * After calling this, clients will no longer discover the server.
     */
    fun stopAdvertising()

    /**
     * Observes and handles the authentication handshake initiated by a client.
     *
     * @param acPublicKey       The server's public key bytes for signing and key exchange.
     * @param acPrivateKey       The server's private key bytes for signing and key exchange.
     * @param saveMobilePublicKey Callback to persist the client's public key once received.
     * @param onDone             Optional callback invoked when the handshake completes.
     * @return A [Flow] emitting a single [Unit] when authentication completes successfully.
     */
    fun observeAuthenticationFlow(
        acPublicKey: ByteArray,
        acPrivateKey: ByteArray,
        saveMobilePublicKey: (ByteArray) -> Unit,
        onDone: () -> Unit = {}
    ): Flow<Unit>
}


@SuppressLint("MissingPermission")
class ActiveCardServerImpl(
    context: Context
) : ActiveCardServer {
    private val bleServer: BleServer = BleServerImpl(context)

    override val receiveMessage: SharedFlow<TransportMessageWrapper>
        get() = bleServer.receiveMessage

    override fun startAdvertising(deviceName: String) {
        bleServer.start(deviceName)
    }

    override fun stopAdvertising() {
        bleServer.stop()
    }

    override fun observeAuthenticationFlow(
        acPublicKey: ByteArray,
        acPrivateKey: ByteArray,
        saveMobilePublicKey: (ByteArray) -> Unit,
        onDone: () -> Unit
    ): Flow<Unit> {
        var mobilePriKey = byteArrayOf()
        val nonceBytes = generateNonce()
        return bleServer.receiveMessage
            .map { result ->
                when (ActiveCardEvent.fromValue(result.messageType)) {
                    ActiveCardEvent.CHALLENGE -> {
                        val nonce = NonceRequest.parseFrom(result.contents)
                        Log.d("AC_Simulator", "Receive challenge event $nonce")
                        val signedNonce = ProtoBufHelper.buildSignedNonce(nonce.nonce.toByteArray(), acPrivateKey)
                        Log.d("AC_Simulator", "Send signed nonce event $signedNonce")
                        sendMessage(ActiveCardEvent.SIGNED_NONCE.id, signedNonce.toByteArray())
                    }

                    ActiveCardEvent.SIGNATURE_VERIFICATION_RESULT -> {
                        val verificationResult = SignatureVerificationResult.parseFrom(result.contents.toByteArray())
                        Log.d("AC_Simulator", "Receive verification result event $verificationResult")
                        if (!verificationResult.valid) throw ActiveCardException("cra-mks-008-01", "Signature verification failed")
                    }

                    ActiveCardEvent.SEND_IDENTITY_PUBLIC_KEY -> {
                        val pubKeyMessage = IdentityPublicKey.parseFrom(result.contents.toByteArray())
                        mobilePriKey = pubKeyMessage.pubkey.toByteArray()
                        saveMobilePublicKey(pubKeyMessage.pubkey.toByteArray())
                        val nonce = ProtoBufHelper.buildNonceRequest(nonceBytes)
                        Log.d("AC_Simulator", "Send challenge $nonce")
                        sendMessage(ActiveCardEvent.CHALLENGE.id, nonce.toByteArray())
                    }

                    ActiveCardEvent.SIGNED_NONCE -> {
                        val signedNonce = SignedNonce.parseFrom(result.contents.toByteArray())
                        Log.d("AC_Simulator", "Receive signed nonce event $signedNonce")
                        val message = ProtoBufHelper.buildVerifySignedNonce(mobilePriKey, nonceBytes, signedNonce.signature.toByteArray())
                        if (message.valid) {
                            Log.d("AC_Simulator", "Send signature verification result event $message")
                            sendMessage(ActiveCardEvent.SIGNATURE_VERIFICATION_RESULT.id, message.toByteArray())
                            onDone()
                        }
                        else throw ActiveCardException("cra-aks-008-00", "Signature verification failed")
                    }

                    else -> {}
                }
            }
    }
    fun shareSecretEstablishment(): Flow<Unit> {
        return bleServer.receiveMessage
            .map { result ->
                when (ActiveCardEvent.fromValue(result.messageType)) {
                    ActiveCardEvent.SEND_ECDH_PUBLIC_KEY -> {
                        val ecdhMobile = EcdhPublicKey.parseFrom(result.contents.toByteArray())
                        // TODO: Derive shared ecdh key from active-card device
                        val keypair = byteArrayOf()
                        val ecdhActiveCard = ProtoBufHelper.buildECDHPublicKey(keypair, "active_card")
                        sendMessage(ActiveCardEvent.SEND_ECDH_PUBLIC_KEY.id, ecdhActiveCard.toByteArray())
                    }
                    else -> {}
                }
            }
    }

    fun ownershipAssociate(
        mobilePublicKey: ByteArray,
        onDone: () -> Unit,
        onFailed: () -> Unit,
    ): Flow<Unit> {
        return bleServer.receiveMessage
            .map { result ->
                when (ActiveCardEvent.fromValue(result.messageType)) {
                    ActiveCardEvent.SEND_USER_IDENTITY -> {
                        val userIdentity = UserIdentity.parseFrom(result.contents.toByteArray())
                        val isVerified = Ed25519Signer.verify(mobilePublicKey, userIdentity.encryptedUserId.toByteArray(), userIdentity.signature.toByteArray())
                        val confirmation = ProtoBufHelper.buildPairingConfirmation(isVerified)
                        if (isVerified) {
                            sendMessage(ActiveCardEvent.PAIRING_CONFIRMATION.id, confirmation.toByteArray())
                            onDone()
                        } else {
                            onFailed()
                        }
                    }
                    else -> {}
                }
            }
    }


    private suspend fun sendMessage(messageType: Int, content: ByteArray) {
        val packets = BLEPacketHelper.prepareMessagePackets(messageType, content)
        for (packet in packets) {
            bleServer.notifyClients(packet)
        }
    }
}
