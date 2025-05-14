package com.cramium.activecard

import android.annotation.SuppressLint
import android.content.Context
import com.cramium.activecard.exception.MpcException
import com.cramium.activecard.ble.BleServer
import com.cramium.activecard.ble.BleServerImpl
import com.cramium.activecard.transport.BLEPacketHelper
import com.cramium.activecard.utils.Ed25519Signer
import com.cramium.activecard.utils.generateNonce
import com.google.protobuf.ByteString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map

interface ActiveCardServer {
    val receiveMessage: SharedFlow<TransportMessageWrapper>
    fun startAdvertising(deviceName: String)
    fun stopAdvertising()
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
                        if (!verificationResult.valid) throw MpcException("cra-mks-008-01", "Signature verification failed")
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

    private suspend fun sendMessage(messageType: Int, content: ByteArray, isEncrypted: Boolean = false) {
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
        val packets = BLEPacketHelper.prepareMessagePackets(messageWrapper)
        for (packet in packets) {
            bleServer.notifyClients(packet)
        }
    }
}
