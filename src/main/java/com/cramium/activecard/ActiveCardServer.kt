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
import com.cramium.sdk.client.LocalPartyCallback
import com.cramium.sdk.client.MpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
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

    fun keygen(): Job
}


@SuppressLint("MissingPermission")
class ActiveCardServerImpl(
    context: Context,
    private val callback: ActiveCardServerCallback,
    private val mpcClient: MpcClient
) : ActiveCardServer {
    private val bleServer: BleServer = BleServerImpl(context)
    override val receiveMessage: SharedFlow<TransportMessageWrapper>
        get() = bleServer.receiveMessage
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    fun shareSecretEstablishment(
        acPrivateKey: ByteArray,
        mobilePublicKey: ByteArray,
    ): Flow<Unit> {
        return bleServer.receiveMessage
            .map { result ->
                when (ActiveCardEvent.fromValue(result.messageType)) {
                    ActiveCardEvent.SEND_ECDH_PUBLIC_KEY -> {
                        val ecdhMobile = EcdhPublicKey.parseFrom(result.contents.toByteArray())
                        val verify = Ed25519Signer.verify(mobilePublicKey, ecdhMobile.publicKey.toByteArray(), ecdhMobile.signature.toByteArray())
                        if (verify) {
                            val keypair = byteArrayOf()  // TODO: Generate ecdh keypair from go-sdk here
                            val signature = Ed25519Signer.sign(acPrivateKey, keypair)
                            val ecdhActiveCard = ProtoBufHelper.buildECDHPublicKey(keypair, "active_card", signature)
                            sendMessage(ActiveCardEvent.SEND_ECDH_PUBLIC_KEY.id, ecdhActiveCard.toByteArray())
                            // TODO: Derive shared ecdh key from mobile device
                        } else {
                            throw ActiveCardException("cra-aks-008-00", "Signature verification failed")
                        }
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

    private var keygenJob: Job? = null
    override fun keygen(): Job {
        return scope.launch {
            callback.sendMessage
                .onEach {
                    Log.d("AC_Simulator", "Send message: $it")
                    sendMessage(it)
                }
                .launchIn(this@launch)

            bleServer.receiveMessage
                .onEach { result ->
                    when (ActiveCardEvent.fromValue(result.messageType)) {
                        ActiveCardEvent.KG_INIT_MNEMONIC_KEYGEN_PROCESS -> {
                            keygenJob?.cancel()
                            keygenJob = null
                            val mnemonicKeygenProcess = MnemonicKeygenProcess.parseFrom(result.contents)
                            Log.d("AC_Simulator", "Receive KG_INIT_MNEMONIC_KEYGEN_PROCESS event $mnemonicKeygenProcess")
                            keygenJob = mpcClient.localPartyMnemonicKeyGen(mnemonicKeygenProcess.groupId, mnemonicKeygenProcess.secretNumber)
                        }
                        ActiveCardEvent.KG_INIT_MNEMONIC_PAILLIER_PROCESS -> {
                            val paillierProcess = PaillierProcess.parseFrom(result.contents)
                            mpcClient.localPartyPaillier(paillierProcess.groupId)
                        }
                        ActiveCardEvent.KG_STORE_EXTERNAL_PARTY_IDENTITY_PUBKEY -> {
                            val groupData = GroupData.parseFrom(result.contents)
                            mpcClient.localPartySaveExternalPartyIdentityPublicKey(groupData.groupId, groupData.data.toByteArray())
                            Log.d("AC_Simulator", "Receive KG_STORE_EXTERNAL_PARTY_IDENTITY_PUBKEY event $groupData")
                        }
                        ActiveCardEvent.KG_STORE_GROUP_PARTY_DATA -> {
                            val groupData = GroupData.parseFrom(result.contents)
                            mpcClient.preparePartyGroupData(groupData.groupId, groupData.data.toByteArray())
                            Log.d("AC_Simulator", "Receive KG_STORE_GROUP_PARTY_DATA event $groupData")
                        }
                        ActiveCardEvent.KG_STORE_PARTY_IDENTITY_PRIVATE_KEY -> {
                            val groupData = GroupData.parseFrom(result.contents)
                            mpcClient.saveInternalIdentityPrivateKey(groupData.groupId, groupData.data.toByteArray())
                            Log.d("AC_Simulator", "Receive KG_STORE_PARTY_IDENTITY_PRIVATE_KEY event $groupData")
                        }
                        ActiveCardEvent.KG_SEND_EXCHANGE_MESSAGE -> {
                            val exchangeMessage = ExchangeMessage.parseFrom(result.contents)
                            mpcClient.inputPartyInMsg(exchangeMessage.groupId, exchangeMessage.msg.toByteArray())
                            Log.d("AC_Simulator", "Receive KG_SEND_EXCHANGE_MESSAGE event $exchangeMessage")
                        }
//                        ActiveCardEvent.KG_SEND_PREPARE_PARTY_DATA -> {
//                            val prepareData = ExchangeMessage.parseFrom(result.contents)
//                            mpcClient.preparePartyGroupData(prepareData.groupId, prepareData.msg.toByteArray())
//                        }
                        else -> {}
                    }
                }
                .launchIn(this@launch)
        }
    }

    private suspend fun sendMessage(message: TransportMessageWrapper) {
        val fullMessage = BLEPacketHelper.buildFullMessagePayload(message)
        val packets = BLEPacketHelper.splitMessageIntoPackets(fullMessage)
        for (packet in packets) {
            delay(20)
            bleServer.notifyClients(packet)
        }
    }

    private suspend fun sendMessage(messageType: Int, content: ByteArray) {
        val packets = BLEPacketHelper.prepareMessagePackets(messageType, content)
        for (packet in packets) {
            delay(20)
            bleServer.notifyClients(packet)
        }
    }
}
