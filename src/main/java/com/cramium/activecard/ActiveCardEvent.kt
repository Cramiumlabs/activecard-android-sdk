package com.cramium.activecard

enum class ActiveCardEvent(val id: Int) {
    CHALLENGE(1),                        // Request for random nonce and response with nonce
    SIGNED_NONCE(2),                     // Signed nonce for verification
    SIGNATURE_VERIFICATION_RESULT(3),    // Result of signature verification
    SEND_IDENTITY_PUBLIC_KEY(4),         // Exchange identity public key
    SEND_ECDH_PUBLIC_KEY(5),             // Exchange ECDH public key
    ECDH_EXCHANGE_ACK(6),                // Acknowledge ECDH exchange
    SEND_USER_IDENTITY(7),               // Send encrypted user identity
    PAIRING_CONFIRMATION(8),             // Confirm pairing status
    FORGET_DEVICE(9),                    // Request to forget device
    FORGET_ACK(10),                      // Acknowledge device forget

    // Key‑Gen round‑trip identifiers
    KG_INIT_MNEMONIC_KEYGEN_PROCESS(1001),         // Request for KeyGen process initialization on the AC side
    KG_INIT_MNEMONIC_PAILLIER_PROCESS(1002),       // Request for Paillier process initialization on the AC side
    KG_STORE_EXTERNAL_PARTY_IDENTITY_PUBKEY(1003), // Store external party identity public key
    KG_STORE_GROUP_PARTY_DATA(1004),               // Store group party data
    KG_STORE_PARTY_IDENTITY_PRIVATE_KEY(1005),     // Store party identity private key
    KG_ROUND_BROADCAST(1006),                      // Broadcast exchange message to party
    // Error + control
    KG_ERROR(1007),                                // Error report within Key‑Gen session
    KG_ABORT(1008),                                // Abort entire Key‑Gen session

    // Signing
    KG_INIT_SIGNING_PROCESS(1100)
    ;

    companion object {
        @JvmStatic
        fun fromValue(id: Int): ActiveCardEvent {
            val event = ActiveCardEvent.entries.firstOrNull { it.id == id }
            if (event == null) throw IllegalStateException("Invalid id: $id")
            else return event
        }
    }
}