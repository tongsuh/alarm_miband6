package com.flashalarm.miband.data.ble.protocol2021

import android.util.Log
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class Huami2021ChunkedEncoder(
    private val force2021Protocol: Boolean = true,
    private var mtu: Int = 23
) {
    companion object {
        private const val TAG = "Huami2021Encoder"
    }

    private var writeHandle: Byte = 0

    @Volatile
    private var encryptedSequenceNr: Int = 0

    @Volatile
    private var sharedSessionKey: ByteArray? = null

    @Synchronized
    fun setEncryptionParameters(sequenceNr: Int, sessionKey: ByteArray) {
        this.encryptedSequenceNr = sequenceNr
        this.sharedSessionKey = sessionKey.copyOf()
        Log.i(TAG, "Encryption parameters updated: seqNr=$sequenceNr")
    }

    @Synchronized
    fun setMtu(newMtu: Int) {
        this.mtu = newMtu.coerceAtLeast(23)
    }

    @Synchronized
    fun encode(
        type: Short,
        payload: ByteArray,
        extendedFlags: Boolean = true,
        encrypt: Boolean = false
    ): List<ByteArray> {
        var data = payload
        val length = data.size
        var remaining = length

        if (encrypt && sharedSessionKey == null) {
            Log.e(TAG, "Cannot encrypt without shared session key!")
            return emptyList()
        }

        writeHandle = (writeHandle + 1).toByte()
        var headerSize = if (extendedFlags) 11 else 10

        if (extendedFlags && encrypt) {
            val key = sharedSessionKey ?: return emptyList()
            val messageKey = ByteArray(16)
            for (i in 0 until 16) {
                messageKey[i] = (key[i].toInt() xor writeHandle.toInt()).toByte()
            }

            var encryptedLength = length + 8
            val overflow = encryptedLength % 16
            if (overflow > 0) {
                encryptedLength += (16 - overflow)
            }

            val encryptablePayload = ByteArray(encryptedLength)
            System.arraycopy(data, 0, encryptablePayload, 0, length)
            encryptablePayload[length] = (encryptedSequenceNr and 0xFF).toByte()
            encryptablePayload[length + 1] = ((encryptedSequenceNr shr 8) and 0xFF).toByte()
            encryptablePayload[length + 2] = ((encryptedSequenceNr shr 16) and 0xFF).toByte()
            encryptablePayload[length + 3] = ((encryptedSequenceNr shr 24) and 0xFF).toByte()
            encryptedSequenceNr++

            val crc = CRC32()
            crc.update(encryptablePayload, 0, length + 4)
            val checksum = crc.value.toInt()
            encryptablePayload[length + 4] = (checksum and 0xFF).toByte()
            encryptablePayload[length + 5] = ((checksum shr 8) and 0xFF).toByte()
            encryptablePayload[length + 6] = ((checksum shr 16) and 0xFF).toByte()
            encryptablePayload[length + 7] = ((checksum shr 24) and 0xFF).toByte()

            val encrypted = encryptAes128(encryptablePayload, messageKey)
            if (encrypted == null) {
                Log.e(TAG, "Failed encrypting chunked payload")
                return emptyList()
            }
            data = encrypted
            remaining = encryptedLength
        }

        val chunks = mutableListOf<ByteArray>()
        var count: Byte = 0

        while (remaining > 0) {
            val maxChunkLength = mtu - 3 - headerSize
            val copyBytes = minOf(remaining, maxChunkLength)
            val chunk = ByteArray(copyBytes + headerSize)

            var flags = 0
            if (encrypt) {
                flags = flags or 0x08
            }
            if (count == 0.toByte()) {
                flags = flags or 0x01
                var i = 4
                if (extendedFlags) {
                    i++
                }
                chunk[i++] = (length and 0xFF).toByte()
                chunk[i++] = ((length shr 8) and 0xFF).toByte()
                chunk[i++] = ((length shr 16) and 0xFF).toByte()
                chunk[i++] = ((length shr 24) and 0xFF).toByte()
                chunk[i++] = (type.toInt() and 0xFF).toByte()
                chunk[i] = ((type.toInt() shr 8) and 0xFF).toByte()
            }
            if (remaining <= maxChunkLength) {
                flags = flags or 0x06 // last chunk + needs ack
            }

            chunk[0] = 0x03
            chunk[1] = flags.toByte()
            if (extendedFlags) {
                chunk[2] = 0
                chunk[3] = writeHandle
                chunk[4] = count
            } else {
                chunk[2] = writeHandle
                chunk[3] = count
            }

            System.arraycopy(data, data.size - remaining, chunk, headerSize, copyBytes)
            chunks.add(chunk)
            remaining -= copyBytes
            headerSize = if (extendedFlags) 5 else 4
            count = (count + 1).toByte()
        }

        return chunks
    }

    private fun encryptAes128(input: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(input)
        } catch (e: Exception) {
            Log.e(TAG, "AES encryption failure", e)
            null
        }
    }
}
