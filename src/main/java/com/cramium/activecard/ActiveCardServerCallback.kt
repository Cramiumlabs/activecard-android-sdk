package com.cramium.activecard

import android.util.Log
import com.cramium.activecard.transport.BLEPacketHelper
import com.cramium.activecard.transport.ProtoBufHelper
import com.cramium.sdk.client.LocalPartyCallback
import com.cramium.sdk.model.mpc.SigningRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class ActiveCardServerCallback: LocalPartyCallback {
    private val _sendMessage: MutableSharedFlow<TransportMessageWrapper> = MutableSharedFlow(replay = 1, extraBufferCapacity = 100)
    val sendMessage: SharedFlow<TransportMessageWrapper>
        get() = _sendMessage

    override fun initLocalPartyMnemonicKeygenProcess(groupId: String, secretNumber: Long) {
        Log.d("AC_Simulator", "Send local mnemonic keygen process")
    }

    override fun initLocalPartyPaillierProcess(groupId: String) {
        Log.d("AC_Simulator", "Send local paillier process")
    }

    override fun sendLocalExternalPartyIdentityPubKey(groupId: String, data: ByteArray) {
        Log.d("AC_Simulator", "Send local external party identity public key")
    }

    override fun sendLocalGroupPartyData(groupId: String, data: ByteArray) {
        Log.d("AC_Simulator", "Send local group party data")
    }

    override fun sendLocalPartyIdentityPrivateKey(groupId: String, data: ByteArray) {
        Log.d("AC_Simulator", "Send local party identity private key")
    }

    override fun sendingExchangeMessage(groupId: String, msg: ByteArray) {
        Log.d("AC_Simulator", "sendingExchangeMessage")
        val exchangeMessage = ProtoBufHelper.buildExchangeMessage(groupId, msg)
        _sendMessage.tryEmit(
            BLEPacketHelper.buildTransportMessageWrapper(
                ActiveCardEvent.KG_ROUND_BROADCAST.id,
                exchangeMessage.toByteArray()
            )
        )
    }

    override fun initLocalPartySigningProcess(signingRequest: SigningRequest) {
        Log.d("AC_Simulator", "Send local signing process")
    }
}