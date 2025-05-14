package com.cramium.activecard.utils

import java.security.SecureRandom

fun generateNonce(length: Int = 32): ByteArray {
    val random = SecureRandom()
    val bytes = ByteArray(length)
    random.nextBytes(bytes)
    return bytes
}