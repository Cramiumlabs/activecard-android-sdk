package com.cramium.activecard

import android.annotation.SuppressLint
import android.content.Context
import com.cramium.activecard.ble.BleServer
import com.cramium.activecard.ble.BleServerImpl
import com.cramium.activecard.exception.ActiveCardException
import com.cramium.activecard.transport.BLEPacketHelper
import com.cramium.activecard.utils.Ed25519Signer
import com.cramium.activecard.utils.generateNonce
import com.google.protobuf.ByteString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map

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
     * @param acPrivateKey       The server's private key bytes for signing and key exchange.
     * @param saveMobilePublicKey Callback to persist the client's public key once received.
     * @param onDone             Optional callback invoked when the handshake completes.
     * @return A [Flow] emitting a single [Unit] when authentication completes successfully.
     */
    fun observeAuthenticationFlow(
        acPrivateKey: ByteArray,
        saveMobilePublicKey: (ByteArray) -> Unit,
        onDone: () -> Unit = {}
    ): Flow<Unit>
}


@SuppressLint("MissingPermission")
class ActiveCardServerImpl(
    context: Context
) : ActiveCardServer {
    override val receiveMessage: SharedFlow<TransportMessageWrapper>
        get() = bleServer.receiveMessage

    private val bleServer: BleServer = BleServerImpl(context)

    override fun startAdvertising(deviceName: String) {
        bleServer.start(deviceName)
    }

    override fun stopAdvertising() {
        bleServer.stop()
    }

    override fun observeAuthenticationFlow(
        acPrivateKey: ByteArray,
        saveMobilePublicKey: (ByteArray) -> Unit,
        onDone: () -> Unit
    ): Flow<Unit> {
        return bleServer.receiveMessage
            .map { result ->
                when (ActiveCardEvent.fromValue(result.messageType)) {
                    ActiveCardEvent.NONCE_REQUEST -> {
                        val nonce = NonceResponse.newBuilder()
                            .setNonce(ByteString.copyFrom(generateNonce()))
                            .build()
                        sendMessage(ActiveCardEvent.NONCE_RESPONSE.id, nonce.toByteArray())
                        delay(50)
                        val signedNonce = signedNonce(nonce.nonce.toByteArray(), acPrivateKey)
                        sendMessage(ActiveCardEvent.SIGNED_NONCE.id, signedNonce.toByteArray())
                    }

                    ActiveCardEvent.SIGNATURE_VERIFICATION_RESULT -> {
                        val verificationResult = SignatureVerificationResult.parseFrom(result.contents.toByteArray())
                        if (!verificationResult.valid) throw ActiveCardException("cra-mks-008-01", "Signature verification failed")
                    }

                    ActiveCardEvent.SEND_IDENTITY_PUBLIC_KEY -> {
                        val pubKeyMessage = IdentityPublicKey.parseFrom(result.contents.toByteArray())
                        saveMobilePublicKey(pubKeyMessage.pubkey.toByteArray())
                        onDone()
                    }

                    else -> {}
                }
            }
    }

    private fun signedNonce(nonce: ByteArray, acPrivateKey: ByteArray): SignedNonce {
        val signed = Ed25519Signer.sign(acPrivateKey, nonce)
        return SignedNonce.newBuilder()
            .setSignature(ByteString.copyFrom(signed))
            .build()
    }

    private suspend fun sendMessage(messageType: Int, content: ByteArray, isEncrypted: Boolean = true) {
        val packets = BLEPacketHelper.prepareMessagePackets(messageType, content, isEncrypted)
        for (packet in packets) {
            bleServer.notifyClients(packet)
        }
    }
}
