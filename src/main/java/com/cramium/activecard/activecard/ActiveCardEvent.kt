package com.cramium.activecard.activecard

enum class ActiveCardEvent(val id: Int) {
    NONCE_REQUEST(1),
    NONCE_RESPONSE(2),
    SIGNED_NONCE(3),
    SIGNATURE_VERIFICATION_RESULT(4),
    SEND_IDENTITY_PUBLIC_KEY(5),
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