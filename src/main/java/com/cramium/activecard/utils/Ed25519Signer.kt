package com.cramium.activecard.utils

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object Ed25519Signer {

    init {
        // register BC so we can parse SEC1 + use SHA256withECDSA
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun loadPrivateKey(sec1Der: ByteArray): PrivateKey {
        // Parse the SEC1 ECPrivateKey structure
        val seq = ASN1Sequence.fromByteArray(sec1Der)
        val ecPriv = ECPrivateKey.getInstance(seq)

        // Wrap into PKCS#8 PrivateKeyInfo
        val algId = org.bouncycastle.asn1.x509.AlgorithmIdentifier(
            X9ObjectIdentifiers.id_ecPublicKey,
            ecPriv.parameters
        )
        val p8 = PrivateKeyInfo(algId, ecPriv)
        val pkcs8Bytes = p8.encoded

        val keySpec = PKCS8EncodedKeySpec(pkcs8Bytes)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(keySpec)
    }

    private fun loadPublicKey(x509Der: ByteArray): PublicKey {
        val keySpec = X509EncodedKeySpec(x509Der)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(keySpec)
    }

    /**
     * @param privateKeySec1Der: DER bytes from Go's x509.MarshalECPrivateKey(priv)
     * @param message: bytes to sign
     * @return DER-encoded ECDSA signature (ASN.1 SEQUENCE of r and s)
     */
    fun sign(privateKeySec1Der: ByteArray, message: ByteArray): ByteArray {
        val priv = loadPrivateKey(privateKeySec1Der)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(priv)
        signer.update(message)
        return signer.sign()
    }

    /**
     * @param publicKeyX509Der: DER bytes from Go's x509.MarshalPKIXPublicKey(pub)
     * @param message: the signed bytes
     * @param signature: DER-encoded ECDSA signature
     */
    fun verify(publicKeyX509Der: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val pub = loadPublicKey(publicKeyX509Der)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pub)
        verifier.update(message)
        return verifier.verify(signature)
    }
}
