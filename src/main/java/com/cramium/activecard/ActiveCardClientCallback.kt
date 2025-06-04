package com.cramium.activecard

import android.util.Log
import com.cramium.activecard.transport.BLEPacketHelper
import com.cramium.activecard.transport.ProtoBufHelper
import com.cramium.sdk.client.LocalPartyCallback
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class ActiveCardClientCallback: LocalPartyCallback {
    private val _sendMessage: MutableSharedFlow<TransportMessageWrapper> = MutableSharedFlow(replay = 1, extraBufferCapacity = 1000)
    val sendMessage: SharedFlow<TransportMessageWrapper>
        get() = _sendMessage

    override fun initLocalPartyMnemonicKeygenProcess(groupId: String, secretNumber: Long) {
        Log.d("ActiveCardClientCallback", "initLocalPartyMnemonicKeygenProcess")
        val mnemonicKeygenProcess = ProtoBufHelper.buildKeygenProcess(groupId, secretNumber)
        _sendMessage.tryEmit(
            BLEPacketHelper.buildTransportMessageWrapper(
                ActiveCardEvent.KG_INIT_MNEMONIC_KEYGEN_PROCESS.id,
                mnemonicKeygenProcess.toByteArray()
            )
        )
    }

    override fun initLocalPartyPaillierProcess(groupId: String) {
        Log.d("ActiveCardClientCallback", "initLocalPartyPaillierProcess")
        val paillierProcess = ProtoBufHelper.buildPaillierProcess(groupId)
        _sendMessage.tryEmit(
            BLEPacketHelper.buildTransportMessageWrapper(
                ActiveCardEvent.KG_INIT_MNEMONIC_PAILLIER_PROCESS.id,
                paillierProcess.toByteArray()
            )
        )
    }

    override fun sendLocalExternalPartyIdentityPubKey(groupId: String, data: ByteArray) {
        Log.d("ActiveCardClientCallback", "sendLocalExternalPartyIdentityPubKey")
        val groupData = ProtoBufHelper.buildGroupData(groupId, data)
        _sendMessage.tryEmit(
            BLEPacketHelper.buildTransportMessageWrapper(
                ActiveCardEvent.KG_STORE_EXTERNAL_PARTY_IDENTITY_PUBKEY.id,
                groupData.toByteArray()
            )
        )
    }

    override fun sendLocalGroupPartyData(groupId: String, data: ByteArray) {
        Log.d("ActiveCardClientCallback", "sendLocalGroupPartyData")
        val groupData = ProtoBufHelper.buildGroupData(groupId, data)
        _sendMessage.tryEmit(
            BLEPacketHelper.buildTransportMessageWrapper(
                ActiveCardEvent.KG_STORE_GROUP_PARTY_DATA.id,
                groupData.toByteArray()
            )
        )
    }

    override fun sendLocalPartyIdentityPrivateKey(groupId: String, data: ByteArray) {
        Log.d("ActiveCardClientCallback", "sendLocalPartyIdentityPrivateKey")
        val groupData = ProtoBufHelper.buildGroupData(groupId, data)
        _sendMessage.tryEmit(
            BLEPacketHelper.buildTransportMessageWrapper(
                ActiveCardEvent.KG_STORE_PARTY_IDENTITY_PRIVATE_KEY.id,
                groupData.toByteArray()
            )
        )
    }

    override fun sendingExchangeMessage(groupId: String, msg: ByteArray) {
        Log.d("ActiveCardClientCallback", "sendingExchangeMessage")
        val exchangeMessage = ProtoBufHelper.buildExchangeMessage(groupId, msg)
        _sendMessage.tryEmit(
            BLEPacketHelper.buildTransportMessageWrapper(
                ActiveCardEvent.KG_ROUND_BROADCAST.id,
                exchangeMessage.toByteArray()
            )
        )
    }
}