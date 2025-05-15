package com.cramium.activecard.exception

class ActiveCardException(
    val code: String,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
