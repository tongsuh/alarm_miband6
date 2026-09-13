package com.flashalarm.miband.data.ble

import android.util.Log
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
        WAITING_PAIR_CONFIRM,
        WAITING_CHALLENGE,
        WAITING_CONFIRMATION,
        AUTHENTICATED,
        FAILED
    }

    enum class AuthProtocolMode {
        MODERN_CRYPT_08,  // [0x82, 0x08, 0x02, 0x01, 0x00] -> [0x83, 0x08] + AES (Gadgetbridge / Amazfish Mi Band 4/5/6 standard)
        LEGACY_08,        // [0x02, 0x08] -> [0x03, 0x08] + AES (Legacy Mi Band 2/3)
        LEGACY_00         // [0x02, 0x00] -> [0x03, 0x00] + AES (Legacy Alternative)
    }

    var currentStep: Step = Step.IDLE
        private set

    var currentProtocolMode: AuthProtocolMode = AuthProtocolMode.MODERN_CRYPT_08
        private set

    val currentModeFlag: Byte
        get() = when (currentProtocolMode) {
            AuthProtocolMode.MODERN_CRYPT_08, AuthProtocolMode.LEGACY_08 -> BleConstants.AUTH_BYTE_MODE_STANDARD
            AuthProtocolMode.LEGACY_00 -> BleConstants.AUTH_BYTE_MODE_ALT
        }

    fun setAuthKeyHex(hexKey: String): Boolean {
        var cleanKey = hexKey.trim()
            .replace(":", "")
            .replace(" ", "")
            .replace("-", "")
        if (cleanKey.startsWith("0x", ignoreCase = true)) {
            cleanKey = cleanKey.substring(2)
        }
        if (cleanKey.length != 32) {
            Log.e(TAG, "AuthKey length is ${cleanKey.length}, expected 32 hex chars (16 bytes)")
            return false
        }
        return try {
            val bytes = ByteArray(16)
            for (i in 0 until 16) {
                val byteVal = cleanKey.substring(i * 2, i * 2 + 2).toInt(16)
                bytes[i] = byteVal.toByte()
            }
            this.authKeyBytes = bytes
            Log.i(TAG, "AuthKey successfully parsed into 16 bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing authKey hex string", e)
            false
        }
    }

    fun startHandshake(mode: AuthProtocolMode = AuthProtocolMode.MODERN_CRYPT_08): ByteArray {
        currentStep = Step.WAITING_CHALLENGE
        currentProtocolMode = mode
        Log.i(TAG, "Starting auth handshake with protocol mode: $currentProtocolMode")
        return when (mode) {
            AuthProtocolMode.MODERN_CRYPT_08 -> BleConstants.AUTH_CMD_REQUEST_RANDOM_MODERN
            AuthProtocolMode.LEGACY_08 -> BleConstants.AUTH_CMD_REQUEST_RANDOM
            AuthProtocolMode.LEGACY_00 -> BleConstants.AUTH_CMD_REQUEST_RANDOM_ALT
        }
    }

    fun startHandshake(useAltMode: Boolean, legacy: Boolean = false): ByteArray {
        val mode = when {
            legacy && useAltMode -> AuthProtocolMode.LEGACY_00
            legacy -> AuthProtocolMode.LEGACY_08
            useAltMode -> AuthProtocolMode.LEGACY_00
            else -> AuthProtocolMode.MODERN_CRYPT_08
        }
        return startHandshake(mode)
    }

    fun startPairing(): ByteArray {
        currentStep = Step.WAITING_PAIR_CONFIRM
        Log.i(TAG, "Sending pairing key to band with flag 0x%02X".format(currentModeFlag))
        val packet = ByteArray(18)
        packet[0] = BleConstants.AUTH_BYTE_PAIR_OP
        packet[1] = currentModeFlag
        System.arraycopy(authKeyBytes, 0, packet, 2, 16)
        return packet
    }

    fun startPairing(useAltMode: Boolean, legacy: Boolean = false): ByteArray {
        return startPairing()
    }

    fun handleAuthNotification(value: ByteArray?): AuthResult {
        if (value == null || value.isEmpty()) {
            currentStep = Step.FAILED
            return AuthResult.Failed("手环返回认证数据为空")
        }

        val hexStr = value.joinToString(separator = " ") { "%02X".format(it) }
        Log.i(TAG, "handleAuthNotification: [$hexStr], currentStep=$currentStep, mode=$currentProtocolMode")

        if (value.size < 3 || value[0] != BleConstants.AUTH_BYTE_RESPONSE_PREFIX) {
            currentStep = Step.FAILED
            return AuthResult.Failed("手环返回报文非标准Auth响应: [$hexStr]")
        }

        val rawOpCode = value[1]
        val maskedOpCode = (rawOpCode.toInt() and 0x0F).toByte()
        val status = value[2]

        when (maskedOpCode) {
            BleConstants.AUTH_BYTE_PAIR_OP -> { // 0x01
                if (status == BleConstants.AUTH_BYTE_SUCCESS) {
                    Log.i(TAG, "User tapped screen! Pairing accepted. Proceeding to random challenge...")
                    return AuthResult.SendPacket(startHandshake(currentProtocolMode))
                } else {
                    currentStep = Step.FAILED
                    return AuthResult.Failed("手环屏幕配对未确认或被取消 (状态码: $status)")
                }
            }

            BleConstants.AUTH_BYTE_RANDOM_KEY_OP -> { // 0x02 (matches both 0x02 and 0x82)
                if (status == BleConstants.AUTH_BYTE_FAIL_NOT_PAIRED || status == BleConstants.AUTH_BYTE_FAIL_INVALID_KEY) {
                    Log.w(TAG, "Band returned not paired status $status. Triggering pairing key registration...")
                    return AuthResult.SendPacket(startPairing())
                }

                // If modern mode fails with invalid flag 0x07 on challenge request, try legacy mode
                if (status == BleConstants.AUTH_BYTE_FAIL_INVALID_FLAG && currentProtocolMode == AuthProtocolMode.MODERN_CRYPT_08) {
                    Log.w(TAG, "Band returned status 7 on modern 0x82 challenge request, falling back to legacy 0x02...")
                    return AuthResult.SendPacket(startHandshake(AuthProtocolMode.LEGACY_08))
                }

                if (status != BleConstants.AUTH_BYTE_SUCCESS) {
                    currentStep = Step.FAILED
                    return AuthResult.Failed("手环拒绝随机Challenge请求 (状态码: $status)")
                }

                if (value.size < 19) {
                    currentStep = Step.FAILED
                    return AuthResult.Failed("随机Challenge载荷长度不足: ${value.size} 字节")
                }

                // Extract 16 bytes random challenge (bytes 3..18)
                val challenge = ByteArray(16)
                System.arraycopy(value, 3, challenge, 0, 16)
                Log.d(TAG, "Challenge received: ${challenge.joinToString(separator = "") { "%02X".format(it) }}")

                // Encrypt challenge with 16-byte AuthKey using AES/ECB/NoPadding
                val encrypted = encryptAes128(challenge, authKeyBytes)
                    ?: run {
                        currentStep = Step.FAILED
                        return AuthResult.Failed("AES-128加密运算失败")
                    }

                // Prepare response packet:
                // If modern crypt mode: [0x83, 0x08] + encrypted (Gadgetbridge / Amazfish standard for Mi Band 4/5/6)
                // If legacy mode: [0x03, currentModeFlag] + encrypted
                val responsePacket = ByteArray(18)
                responsePacket[0] = if (currentProtocolMode == AuthProtocolMode.MODERN_CRYPT_08) {
                    BleConstants.AUTH_BYTE_ENCRYPTED_KEY_OP_CRYPT // 0x83
                } else {
                    BleConstants.AUTH_BYTE_ENCRYPTED_KEY_OP // 0x03
                }
                responsePacket[1] = currentModeFlag
                System.arraycopy(encrypted, 0, responsePacket, 2, 16)

                currentStep = Step.WAITING_CONFIRMATION
                Log.i(TAG, "Sending encrypted challenge response [18 bytes] with opcode 0x%02X, modeFlag 0x%02X"
                    .format(responsePacket[0], responsePacket[1]))
                return AuthResult.SendPacket(responsePacket)
            }

            BleConstants.AUTH_BYTE_ENCRYPTED_KEY_OP -> { // 0x03 (matches both 0x03 and 0x83)
                return when (status) {
                    BleConstants.AUTH_BYTE_SUCCESS -> {
                        currentStep = Step.AUTHENTICATED
                        Log.i(TAG, "Huami authentication handshake completed with SUCCESS!")
                        AuthResult.Success
                    }
                    BleConstants.AUTH_BYTE_FAIL_INVALID_FLAG -> {
                        // If modern mode returned status 7, fall back to legacy mode
                        if (currentProtocolMode == AuthProtocolMode.MODERN_CRYPT_08) {
                            Log.w(TAG, "Status 7 on modern mode 0x83, falling back to legacy 0x03 mode...")
                            AuthResult.SendPacket(startHandshake(AuthProtocolMode.LEGACY_08))
                        } else if (currentProtocolMode == AuthProtocolMode.LEGACY_08) {
                            Log.w(TAG, "Status 7 on legacy 0x08 mode, falling back to legacy 0x00 mode...")
                            AuthResult.SendPacket(startHandshake(AuthProtocolMode.LEGACY_00))
                        } else {
                            currentStep = Step.FAILED
                            AuthResult.Failed("AuthKey认证被手环拒绝 (状态码: $status，指令格式不受支持)")
                        }
                    }
                    BleConstants.AUTH_BYTE_FAIL_NOT_PAIRED -> {
                        currentStep = Step.FAILED
                        AuthResult.Failed("AuthKey认证被手环拒绝 (状态码: 4，密钥不匹配)。请核验AuthKey是否对应此手环！")
                    }
                    else -> {
                        currentStep = Step.FAILED
                        AuthResult.Failed("AuthKey认证被手环拒绝 (状态码: $status)。请核验AuthKey是否对应此手环MAC！")
                    }
                }
            }

            else -> {
                Log.w(TAG, "Unhandled auth opCode: raw=0x%02X, masked=0x%02X".format(rawOpCode, maskedOpCode))
                return AuthResult.Failed("未知认证OpCode: 0x%02X".format(rawOpCode))
            }
        }
    }

    companion object {
        private const val TAG = "HuamiAuthHandler"

        fun encryptAes128(input: ByteArray, key: ByteArray): ByteArray? {
            return try {
                val secretKey = SecretKeySpec(key, "AES")
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                cipher.doFinal(input)
            } catch (e: Exception) {
                Log.e(TAG, "AES encryption error", e)
                null
            }
        }
    }
}
