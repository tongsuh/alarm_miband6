package com.flashalarm.miband.data.ble.protocol2021

import android.util.Log
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

fun interface Huami2021PayloadHandler {
    fun handle2021Payload(type: Short, payload: ByteArray)
}

class Huami2021ChunkedDecoder(
    private var handler: Huami2021PayloadHandler? = null,
    private val force2021Protocol: Boolean = true
) {
    companion object {
        private const val TAG = "Huami2021Decoder"
    }

    private var currentHandle: Byte? = null
    private var currentType: Int = 0
    private var currentLength: Int = 0
    private var reassemblyBuffer: ByteBuffer? = null

    var lastHandle: Byte = 0
        private set
    var lastCount: Byte = 0
        private set

    @Volatile
    private var sharedSessionKey: ByteArray? = null

    fun setEncryptionParameters(sessionKey: ByteArray) {
        this.sharedSessionKey = sessionKey.copyOf()
    }

    fun setHandler(handler: Huami2021PayloadHandler) {
        this.handler = handler
    }

    /**
     * Decodes incoming chunk from 0x0017 characteristic.
     * @return true if this chunk requests an immediate ACK packet [0x04, 0x00, handle, 0x01, count]
     */
    @Synchronized
    fun decode(data: ByteArray): Boolean {
        if (data.isEmpty() || data[0] != 0x03.toByte()) {
            Log.w(TAG, "Ignoring non-chunked payload: size=${data.size}")
            return false
        }

        var i = 1
        val flags = data[i++]
        val encrypted = (flags.toInt() and 0x08) == 0x08
        val firstChunk = (flags.toInt() and 0x01) == 0x01
        val lastChunk = (flags.toInt() and 0x02) == 0x02
        val needsAck = (flags.toInt() and 0x04) == 0x04

        if (force2021Protocol) {
            i++ // skip extended header byte
        }

        if (i >= data.size) {
            Log.e(TAG, "Malformed chunked header: index $i out of range")
            return false
        }

        val handle = data[i++]
        if (currentHandle != null && currentHandle != handle) {
            Log.w(TAG, "Ignoring chunk handle $handle, expected $currentHandle")
            return false
        }

        lastHandle = handle
        lastCount = data[i++]

        if (firstChunk) {
            val b0 = data[i++].toInt() and 0xFF
            val b1 = data[i++].toInt() and 0xFF
            val b2 = data[i++].toInt() and 0xFF
            val b3 = data[i++].toInt() and 0xFF
            var fullLength = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            currentLength = fullLength

            if (encrypted) {
                var encryptedLength = fullLength + 8
                val overflow = encryptedLength % 16
                if (overflow > 0) {
                    encryptedLength += (16 - overflow)
                }
                fullLength = encryptedLength
            }

            reassemblyBuffer = ByteBuffer.allocate(fullLength)
            val t0 = data[i++].toInt() and 0xFF
            val t1 = data[i++].toInt() and 0xFF
            currentType = t0 or (t1 shl 8)
            currentHandle = handle
        }

        val remainingInChunk = data.size - i
        if (remainingInChunk > 0 && reassemblyBuffer != null) {
            val buf = reassemblyBuffer!!
            val toPut = minOf(remainingInChunk, buf.remaining())
            buf.put(data, i, toPut)
        }

        if (lastChunk) {
            val buf = reassemblyBuffer?.array()
            currentHandle = null
            reassemblyBuffer = null

            if (buf != null) {
                var finalPayload = buf
                if (encrypted) {
                    val key = sharedSessionKey
                    if (key == null) {
                        Log.e(TAG, "Received encrypted message but no shared session key is present")
                        currentType = 0
                        return needsAck
                    }
                    val messageKey = ByteArray(16)
                    for (j in 0 until 16) {
                        messageKey[j] = (key[j].toInt() xor handle.toInt()).toByte()
                    }
                    val decrypted = decryptAes128(buf, messageKey)
                    if (decrypted != null && decrypted.size >= currentLength) {
                        finalPayload = decrypted.copyOfRange(0, currentLength)
                    } else {
                        Log.e(TAG, "Failed decrypting chunked payload")
                        currentType = 0
                        return needsAck
                    }
                }

                val hexPreview = finalPayload.take(20).joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "Reassembled 2021 payload: type=0x%04X len=${finalPayload.size} [$hexPreview]".format(currentType))

                try {
                    handler?.handle2021Payload(currentType.toShort(), finalPayload)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in handle2021Payload", e)
                }
            }
            currentType = 0
        }

        return needsAck
    }

    private fun decryptAes128(input: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(input)
        } catch (e: Exception) {
            Log.e(TAG, "AES decryption failure", e)
            null
        }
    }
}
