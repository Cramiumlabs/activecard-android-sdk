package com.cramium.activecard.simulator

import com.google.gson.annotations.SerializedName

data class ActiveCardQr(
    @SerializedName("identityPublicKey")
    val identityPublicKey: String,

    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("deviceName")
    val deviceName: String,

    @SerializedName("ownerUser")
    val ownerUser: String,

    @SerializedName("firmwareVersion")
    val firmwareVersion: String,

    @SerializedName("timestamp")
    val timestamp: Long
)