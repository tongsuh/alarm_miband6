package com.flashalarm.miband.data.ble

import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.SecretKeySpec

sealed class AuthResult {
    data class SendPacket(val data: ByteArray) : AuthResult()
    data object Success : AuthResult()
    data class Failed(val error: String) : AuthResult()
}

class HuamiAuthHandler(
    private var authKeyBytes: ByteArray = ByteArray(16)
) {
    enum class Step {
        IDLE,
        WAITING_CHALLENGE,
        WAITING_CONFIRMATION,
        AUTHENTICATED,
        FAILED
    }

    var currentStep: Step = Step.IDLE
        private set

    fun setAuthKeyHex(hexKey: String): Boolean {
        val cleanKey = hexKey.trim().replace(":", "").replace(" ", "")
        if (cleanKey.length != 32) return false
        return try {
            val bytes = ByteArray(16)
            for (i in 0 until 16) {
                val byteVal = cleanKey.substring(i * 2, i * 2 + 2).toInt(16)
                bytes[i] = byteVal.toByte()
            }
            this.authKeyBytes = bytes
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startHandshake(): ByteArray {
        currentStep = Step.WAITING_CHALLENGE
        // Request random challenge
        return BleConstants.AUTH_CMD_REQUEST_RANDOM
    }

    fun handleAuthNotification(value: ByteArray?): AuthResult {
        if (value == null || value.isEmpty()) {
            currentStep = Step.FAILED
            return AuthResult.Failed("Empty response from auth characteristic")
        }

        // Response format from Mi Band 6:
        // [0x10, OpCode, Status, ... Payload ...]
        if (value.size < 3 || value[0] != BleConstants.AUTH_BYTE_RESPONSE_PREFIX) {
            currentStep = Step.FAILED
            return AuthResult.Failed("Malformed auth notification header: ${value.joinToString { "%02X".format(it) }}")
        }

        val opCode = value[1]
        val status = value[2]

        when (opCode) {
            BleConstants.AUTH_BYTE_RANDOM_KEY_OP -> {
                if (status != BleConstants.AUTH_BYTE_SUCCESS) {
                    currentStep = Step.FAILED
                    return AuthResult.Failed("Failed to request random challenge from band, status = $status")
                }
                if (value.size < 19) {
                    currentStep = Step.FAILED
                    return AuthResult.Failed("Random challenge payload too short: ${value.size} bytes")
                }

                // Extract 16 bytes random challenge (bytes 3..18)
                val challenge = ByteArray(16)
                System.arraycopy(value, 3, challenge, 0, 16)

                // Encrypt challenge with 16-byte AuthKey using AES/ECB/NoPadding
                val encrypted = encryptAes128(challenge, authKeyBytes)
                    ?: run {
                        currentStep = Step.FAILED
                        return AuthResult.Failed("AES encryption calculation failed")
                    }

                // Send response packet: [0x03, 0x08] + encrypted 16 bytes
                val responsePacket = ByteArray(18)
                responsePacket[0] = BleConstants.AUTH_BYTE_ENCRYPTED_KEY_OP
                responsePacket[1] = 0x08
                System.arraycopy(encrypted, 0, responsePacket, 2, 16)

                currentStep = Step.WAITING_CONFIRMATION
                return AuthResult.SendPacket(responsePacket)
            }

            BleConstants.AUTH_BYTE_ENCRYPTED_KEY_OP -> {
                return if (status == BleConstants.AUTH_BYTE_SUCCESS) {
                    currentStep = Step.AUTHENTICATED
                    AuthResult.Success
                } else {
                    currentStep = Step.FAILED
                    AuthResult.Failed("Authentication handshake rejected by Mi Band 6 (Status: $status). Check AuthKey!")
                }
            }

            else -> {
                return AuthResult.Failed("Unexpected auth opCode: $opCode")
            }
        }
    }

    companion object {
        fun encryptAes128(input: ByteArray, key: ByteArray): ByteArray? {
            return try {
                val secretKey = SecretKeySpec(key, "AES")
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                cipher.doFinal(input)
            } catch (e: Exception) {
                null
            }
        }
    }
}
