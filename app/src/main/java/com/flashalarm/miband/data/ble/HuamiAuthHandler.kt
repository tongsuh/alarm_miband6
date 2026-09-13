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

    var currentStep: Step = Step.IDLE
        private set

    var currentModeFlag: Byte = BleConstants.AUTH_BYTE_MODE_STANDARD
        private set

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

    fun startHandshake(useAltMode: Boolean = false): ByteArray {
        currentStep = Step.WAITING_CHALLENGE
        currentModeFlag = if (useAltMode) BleConstants.AUTH_BYTE_MODE_ALT else BleConstants.AUTH_BYTE_MODE_STANDARD
        Log.i(TAG, "Starting auth handshake with mode flag 0x%02X".format(currentModeFlag))
        return byteArrayOf(BleConstants.AUTH_BYTE_RANDOM_KEY_OP, currentModeFlag)
    }

    fun startPairing(useAltMode: Boolean = false): ByteArray {
        currentStep = Step.WAITING_PAIR_CONFIRM
        currentModeFlag = if (useAltMode) BleConstants.AUTH_BYTE_MODE_ALT else BleConstants.AUTH_BYTE_MODE_STANDARD
        Log.i(TAG, "Sending pairing key to band with flag 0x%02X".format(currentModeFlag))
        val packet = ByteArray(18)
        packet[0] = BleConstants.AUTH_BYTE_PAIR_OP
        packet[1] = currentModeFlag
        System.arraycopy(authKeyBytes, 0, packet, 2, 16)
        return packet
    }

    fun handleAuthNotification(value: ByteArray?): AuthResult {
        if (value == null || value.isEmpty()) {
            currentStep = Step.FAILED
            return AuthResult.Failed("手环返回认证数据为空")
        }

        val hexStr = value.joinToString(separator = " ") { "%02X".format(it) }
        Log.i(TAG, "handleAuthNotification: [$hexStr], currentStep=$currentStep")

        if (value.size < 3 || value[0] != BleConstants.AUTH_BYTE_RESPONSE_PREFIX) {
            currentStep = Step.FAILED
            return AuthResult.Failed("手环返回报文非标准Auth响应: [$hexStr]")
        }

        val opCode = value[1]
        val status = value[2]

        when (opCode) {
            BleConstants.AUTH_BYTE_PAIR_OP -> { // 0x01
                if (status == BleConstants.AUTH_BYTE_SUCCESS) {
                    Log.i(TAG, "User tapped screen! Pairing accepted. Proceeding to random challenge...")
                    currentStep = Step.WAITING_CHALLENGE
                    return AuthResult.SendPacket(byteArrayOf(BleConstants.AUTH_BYTE_RANDOM_KEY_OP, currentModeFlag))
                } else {
                    currentStep = Step.FAILED
                    return AuthResult.Failed("手环屏幕配对未确认或被取消 (状态码: $status)")
                }
            }

            BleConstants.AUTH_BYTE_RANDOM_KEY_OP -> { // 0x02
                if (status == BleConstants.AUTH_BYTE_FAIL_NOT_PAIRED || status == BleConstants.AUTH_BYTE_FAIL_INVALID_KEY) {
                    Log.w(TAG, "Band returned not paired status $status. Triggering pairing key registration...")
                    return AuthResult.SendPacket(startPairing(currentModeFlag == BleConstants.AUTH_BYTE_MODE_ALT))
                }

                if (status == BleConstants.AUTH_BYTE_FAIL_INVALID_FLAG && currentModeFlag == BleConstants.AUTH_BYTE_MODE_STANDARD) {
                    Log.w(TAG, "Band returned status 7 on mode 0x08, auto-retrying with alt mode 0x00...")
                    return AuthResult.SendPacket(startHandshake(useAltMode = true))
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

                // Send response packet: [0x03, currentModeFlag] + encrypted 16 bytes (Gadgetbridge standard: [0x03, 0x08] + cipher)
                val responsePacket = ByteArray(18)
                responsePacket[0] = BleConstants.AUTH_BYTE_ENCRYPTED_KEY_OP
                responsePacket[1] = currentModeFlag
                System.arraycopy(encrypted, 0, responsePacket, 2, 16)

                currentStep = Step.WAITING_CONFIRMATION
                Log.i(TAG, "Sending encrypted challenge response [18 bytes] with mode 0x%02X".format(currentModeFlag))
                return AuthResult.SendPacket(responsePacket)
            }

            BleConstants.AUTH_BYTE_ENCRYPTED_KEY_OP -> { // 0x03
                return when (status) {
                    BleConstants.AUTH_BYTE_SUCCESS -> {
                        currentStep = Step.AUTHENTICATED
                        Log.i(TAG, "Huami authentication handshake completed with SUCCESS!")
                        AuthResult.Success
                    }
                    BleConstants.AUTH_BYTE_FAIL_INVALID_FLAG -> {
                        if (currentModeFlag == BleConstants.AUTH_BYTE_MODE_STANDARD) {
                            Log.w(TAG, "Status 7 on mode 0x08, auto-retrying with alt mode 0x00...")
                            AuthResult.SendPacket(startHandshake(useAltMode = true))
                        } else {
                            currentStep = Step.FAILED
                            AuthResult.Failed("AuthKey认证被手环拒绝 (状态码: $status，指令标志不受支持)")
                        }
                    }
                    BleConstants.AUTH_BYTE_FAIL_NOT_PAIRED -> {
                        currentStep = Step.FAILED
                        AuthResult.Failed("AuthKey认证被手环拒绝 (状态码: $status，密钥不匹配)。请核验AuthKey是否正确！")
                    }
                    else -> {
                        currentStep = Step.FAILED
                        AuthResult.Failed("AuthKey认证被手环拒绝 (状态码: $status)。请核验AuthKey是否对应此手环MAC！")
                    }
                }
            }

            else -> {
                Log.w(TAG, "Unhandled auth opCode: $opCode")
                return AuthResult.Failed("未知认证OpCode: $opCode")
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
