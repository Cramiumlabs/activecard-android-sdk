package com.cramium.activecard.transport

import com.cramium.activecard.IdentityPublicKey
import com.cramium.activecard.NonceRequest
import com.cramium.activecard.SignatureVerificationResult
import com.cramium.activecard.SignedNonce
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
}