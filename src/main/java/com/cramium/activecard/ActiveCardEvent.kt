package com.cramium.activecard

enum class ActiveCardEvent(val id: Int) {
    NONCE_REQUEST(1),                    // Request for random nonce
    NONCE_RESPONSE(2),                   // Response with nonce
    SIGNED_NONCE(3),                     // Signed nonce for verification
    SIGNATURE_VERIFICATION_RESULT(4),    // Result of signature verification
    SEND_IDENTITY_PUBLIC_KEY(5),         // Exchange identity public key
    SEND_ECDH_PUBLIC_KEY(6),             // Exchange ECDH public key
    ECDH_EXCHANGE_ACK(7),                // Acknowledge ECDH exchange
    SEND_USER_IDENTITY(8),               // Send encrypted user identity
    PAIRING_CONFIRMATION(9),             // Confirm pairing status
    FORGET_DEVICE(10),                   // Request to forget device
    FORGET_ACK(11)                       // Acknowledge device forget
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