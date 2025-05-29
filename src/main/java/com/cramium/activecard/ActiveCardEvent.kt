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
    KG_ROUND_BROADCAST(1001),           // Commitment distribution
    KG_INIT_MNEMONIC_KEYGEN_PROCESS(1002),
    KG_INIT_MNEMONIC_PAILLIER_PROCESS(1003),
    KG_STORE_EXTERNAL_PARTY_IDENTITY_PUBKEY(1004),
    KG_STORE_GROUP_PARTY_DATA(1005),
    KG_STORE_PARTY_IDENTITY_PRIVATE_KEY(1006),
    KG_SEND_EXCHANGE_MESSAGE(1007),
//    KG_SEND_PREPARE_PARTY_DATA(1008),
    // Error + control
    KG_ERROR(1010),                      // Error report within Key‑Gen session
    KG_ABORT(1011)                       // Abort entire Key‑Gen session
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