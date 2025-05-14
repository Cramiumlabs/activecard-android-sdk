package com.cramium.activecard.transport

import android.util.Log
import com.cramium.activecard.TransportMessageWrapper
import com.cramium.activecard.exception.MpcException
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class BLEPacketHelper {
    companion object {
        private const val AES_GCM_IV_LENGTH: Int = 12
        private const val AES_GCM_TAG_LENGTH: Int = 16
        private const val AES_GCM_ENCRYPT_ENABLE_LENGTH: Int = 1
        private const val ENCRYPTED_FLAG: Byte = 1
        private const val UNENCRYPTED_FLAG: Byte = 0
        private val HEADERS = byteArrayOf(0x3F, 0x23, 0x23)

        private val PACKET_HEADER = byteArrayOf(109, 112, 99)
        private const val PACKET_SIZE = 120
        private const val PACKET_HEADER_BUFFER_SIZE =
            3 + // 3 bytes header
            1 + // 1 byte encrypted flag
            2 + // 2 byte message type (short type)
            4 + // 4 byte message size (long type)
            8 + // 8 byte session id
            4   // 4 byte session time in epoch

        /**
         * Builds the full message payload from the TransportMessageWrapper.
         *
         * This method constructs the complete message payload, including headers, encryption flags,
         * IV, tag, message type, message size, session ID, session start time, and content.
         *
         * @param message The message to be built.
         * @return The full message payload as a byte array.
         */
        private fun buildFullMessagePayload(message: TransportMessageWrapper): ByteArray {
            val ivBytes = message.iv.toByteArray()
            val tagBytes = message.tag.toByteArray()
            val contentBytes = message.contents.toByteArray()
            val sessionIdBytes = message.sessionId.toByteArray()
            val sessionStart = message.sessionStartTime.toInt()

            val buffer: ByteBuffer = if (message.isEncrypted) {
                ByteBuffer.allocate(message.messageSize + PACKET_HEADER_BUFFER_SIZE + AES_GCM_IV_LENGTH + AES_GCM_TAG_LENGTH + AES_GCM_ENCRYPT_ENABLE_LENGTH)
            } else {
                ByteBuffer.allocate(message.messageSize + PACKET_HEADER_BUFFER_SIZE)
            }
                .order(ByteOrder.BIG_ENDIAN)
                .apply {
                    put(HEADERS) // 3-byte fixed header
                    put(if (message.isEncrypted) ENCRYPTED_FLAG else UNENCRYPTED_FLAG)
                    if (message.isEncrypted) {
                        put(ivBytes)
                        put(tagBytes)
                    }
                    putShort(message.messageType.toShort()) // 2-byte messageType
                    putInt(message.messageSize) // 4-byte messageSize
                    put(sessionIdBytes) // variable-length sessionId (should be 8-byte)
                    putInt(sessionStart) // 4-byte sessionStartTime
                    put(contentBytes) // payload
                }
            return buffer.array()
        }

        /**
         * Unpacks a full message payload — the inverse of `buildFullMessagePayload`.
         *
         * @param payload The raw bytes (with headers, flags, IV/tag if present, etc.)
         */
        fun parseFullMessagePayload(payload: ByteArray): ParseResult {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

            val header = ByteArray(HEADERS.size).also { buffer.get(it) }
            if (!header.contentEquals(HEADERS)) return ParseResult.Error(
                MpcException("cra-mks-008-00", "Invalid header")
            )

            val encrypted = buffer.get() == ENCRYPTED_FLAG

            val ivBytes =
                if (encrypted) ByteArray(AES_GCM_IV_LENGTH).also { buffer.get(it) } else byteArrayOf()
            val tagBytes =
                if (encrypted) ByteArray(AES_GCM_TAG_LENGTH).also { buffer.get(it) } else byteArrayOf()

            val messageType = buffer.short.toInt() and 0xFFFF
            val messageSize = buffer.int

            val sessionIdBytes = ByteArray(8).also { buffer.get(it) }
            val sessionStartTime = buffer.int.toLong()

            if (buffer.remaining() < messageSize) return ParseResult.Partial(payload)

            val contentBytes = ByteArray(messageSize).also { buffer.get(it) }

            val message = TransportMessageWrapper.newBuilder()
                .setMessageType(messageType)
                .setMessageSize(messageSize)
                .setSessionId(ByteString.copyFrom(sessionIdBytes))
                .setSessionStartTime(sessionStartTime)
                .setContents(ByteString.copyFrom(contentBytes))
                .setIv(ByteString.copyFrom(ivBytes))
                .setTag(ByteString.copyFrom(tagBytes))
                .setIsEncrypted(encrypted)
                .build()
            return ParseResult.Full(message)
        }

        /**
         * Prepares the data to be sent over the BLE transport.
         *
         * This method takes a TransportMessageWrapper, constructs the complete message payload,
         * and splits it into smaller packets if necessary.
         *
         * @param message The message to be prepared for sending.
         * @return A list of byte arrays, where each byte array represents a packet to be sent.
         */
        fun prepareMessagePackets(message: TransportMessageWrapper): List<ByteArray> {
            val fullMessage = buildFullMessagePayload(message)
            return splitMessageIntoPackets(fullMessage)
        }


        /**
         * Splits a large byte array into smaller packets.
         *
         * This method takes a byte array and splits it into smaller packets of a predefined size.
         * Each packet is prepended with a header and a sequence number.
         *
         * @param message The byte array to be split.
         * @return A list of byte arrays, where each byte array represents a packet.
         */
        private fun splitMessageIntoPackets(message: ByteArray): List<ByteArray> {
            var segmentCounter = 0
            var startIndex = 0
            val packets = mutableListOf<ByteArray>()

            while (startIndex < message.size) {
                val endIndex = Math.min(startIndex + PACKET_SIZE, message.size)
                val packet = Arrays.copyOfRange(message, startIndex, endIndex)
                val packetWithHeader = addPacketHeader(packet, segmentCounter)
                packets.add(packetWithHeader)

                startIndex += PACKET_SIZE
                segmentCounter++
            }
            return packets
        }

        /**
         * Adds a header to a packet.
         *
         * This method adds a fixed header and a sequence number to a packet.
         *
         * @param packet The packet to which the header should be added.
         * @param segmentCounter The sequence number of the packet.
         * @return The packet with the header added.
         */
        private fun addPacketHeader(packet: ByteArray, segmentCounter: Int): ByteArray {
            val newPacket = ByteArray(packet.size + PACKET_HEADER.size + 2)
            val header = byteArrayOf(
                *PACKET_HEADER,
                (segmentCounter shr 8).toByte(),
                (segmentCounter and 0xFF).toByte()
            )
            System.arraycopy(header, 0, newPacket, 0, header.size)
            System.arraycopy(packet, 0, newPacket, header.size, packet.size)
            return newPacket
        }
    }

    fun emit(data: ByteArray) {
        _receiveMessage.tryEmit(data)
    }

    private var combineArray = byteArrayOf()
    private val _receiveMessage: MutableSharedFlow<ByteArray> = MutableSharedFlow(replay = 1)
    val receiveMessage: SharedFlow<TransportMessageWrapper>
        get() = _receiveMessage
            .map { incoming ->
                combineArray += incoming
                when (val parse = parseFullMessagePayload(combineArray)) {
                    is ParseResult.Full -> {
                        combineArray = byteArrayOf()
                        parse.msg
                    }

                    is ParseResult.Partial -> null
                    is ParseResult.Error -> {
                        combineArray = byteArrayOf()
                        throw parse.error
                    }
                }
            }
            .filterNotNull()
            .onEach { Log.d("BLEPacketHelper", "Received message: $it") }
            .shareIn(CoroutineScope(Dispatchers.IO), SharingStarted.Eagerly)
}

sealed class ParseResult {
    data class Partial(val data: ByteArray) : ParseResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Partial

            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    data class Error(val error: Throwable) : ParseResult()

    data class Full(val msg: TransportMessageWrapper) : ParseResult()
}