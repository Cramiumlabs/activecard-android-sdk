package com.cramium.activecard.transport

import com.cramium.activecard.BroadcastExchangeMessage
import com.cramium.activecard.EcdhPublicKey
import com.cramium.activecard.GroupData
import com.cramium.activecard.IdentityPublicKey
import com.cramium.activecard.InitiateMnemonicKeyGen
import com.cramium.activecard.InitiatePaillierKeyGen
import com.cramium.activecard.NonceRequest
import com.cramium.activecard.PairingConfirmation
import com.cramium.activecard.SignatureVerificationResult
import com.cramium.activecard.SignedNonce
import com.cramium.activecard.UserIdentity
import com.cramium.activecard.utils.Ed25519Signer
import com.google.protobuf.ByteString

object ProtoBufHelper {
    fun buildIdentityPublicKey(key: ByteArray, source: String): IdentityPublicKey {
        return IdentityPublicKey.newBuilder()
            .setPubkey(ByteString.copyFrom(key))
            .setSource(source)
            .build()
    }

    fun buildNonceRequest(nonceBytes: ByteArray): NonceRequest {
        return NonceRequest.newBuilder()
            .setNonce(ByteString.copyFrom(nonceBytes))
            .build()
    }

    fun buildSignedNonce(nonce: ByteArray, acPrivateKey: ByteArray): SignedNonce {
        val signed = Ed25519Signer.sign(acPrivateKey, nonce)
        return SignedNonce.newBuilder()
            .setSignature(ByteString.copyFrom(signed))
            .build()
    }

    fun buildVerifySignedNonce(pubKey: ByteArray, nonce: ByteArray, signed: ByteArray): SignatureVerificationResult {
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

    fun buildPairingConfirmation(confirmed: Boolean): PairingConfirmation {
        return PairingConfirmation.newBuilder()
            .setConfirmed(confirmed)
            .build()
    }

    fun buildUserIdentity(userId: String, privateKey: ByteArray): UserIdentity {
        val encrypted = Ed25519Signer.sign(privateKey, userId.toByteArray())
        return UserIdentity.newBuilder()
            .setSignature(ByteString.copyFrom(encrypted))
            .setEncryptedUserId(ByteString.copyFrom(userId.toByteArray()))
            .build()
    }

    fun buildECDHPublicKey(key: ByteArray, source: String, signature: ByteArray): EcdhPublicKey {
        return EcdhPublicKey.newBuilder()
            .setPublicKey(ByteString.copyFrom(key))
            .setSource(source)
            .setSignature(ByteString.copyFrom(signature))
            .build()
    }

    fun buildKeygenProcess(groupId: String, secretNumber: Long) : InitiateMnemonicKeyGen {
        return InitiateMnemonicKeyGen.newBuilder()
            .setGroupId(groupId)
            .setSecretNumber(secretNumber)
            .build()
    }

    fun buildGroupData(groupId: String, data: ByteArray): GroupData {
        return GroupData.newBuilder()
            .setGroupId(groupId)
            .setData(ByteString.copyFrom(data))
            .build()
    }

    fun buildPaillierProcess(groupId: String): InitiatePaillierKeyGen {
        return InitiatePaillierKeyGen.newBuilder()
            .setGroupId(groupId)
            .build()
    }

    fun buildExchangeMessage(groupId: String, msg: ByteArray): BroadcastExchangeMessage {
        return BroadcastExchangeMessage.newBuilder()
            .setGroupId(groupId)
            .setMsg(ByteString.copyFrom(msg))
            .build()
    }
}