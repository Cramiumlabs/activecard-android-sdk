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
    FORGET_ACK(10)                       // Acknowledge device forget
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