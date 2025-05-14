package com.cramium.activecard.exception

class MpcException(
    val code: String,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
