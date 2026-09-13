package com.flashalarm.miband

import com.flashalarm.miband.data.ble.AuthResult
import com.flashalarm.miband.data.ble.BleConstants
import com.flashalarm.miband.data.ble.HuamiAuthHandler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HuamiAuthHandlerTest {

    private lateinit var authHandler: HuamiAuthHandler
    private val testAuthKeyHex = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        authHandler = HuamiAuthHandler()
        val success = authHandler.setAuthKeyHex(testAuthKeyHex)
        assertTrue("Auth key hex parsing should succeed", success)
    }

    @Test
    fun `test AES-128 ECB challenge encryption`() {
        val challenge = ByteArray(16) { it.toByte() }
        val key = ByteArray(16) { 0x01 }

        val encrypted = HuamiAuthHandler.encryptAes128(challenge, key)
        assertNotNull("Encrypted challenge must not be null", encrypted)
        assertEquals(16, encrypted?.size)
    }

    @Test
    fun `test start handshake packet`() {
        val packet = authHandler.startHandshake()
        assertArrayEquals(BleConstants.AUTH_CMD_REQUEST_RANDOM_MODERN, packet)
        assertEquals(5, packet.size)
        assertEquals((0x02 or 0x80).toByte(), packet[0])
        assertEquals(HuamiAuthHandler.Step.WAITING_CHALLENGE, authHandler.currentStep)
    }

    @Test
    fun `test handle random challenge notification produces encrypted response packet with 0x83 opcode`() {
        authHandler.startHandshake()

        // Mock notification from Mi Band 6 (OpCode 0x82 or 0x02):
        // [0x10, 0x82, 0x01, ... 16 bytes challenge ...]
        val mockChallenge = ByteArray(16) { (it + 1).toByte() }
        val mockNotification = byteArrayOf(0x10, (0x02 or 0x80).toByte(), 0x01) + mockChallenge

        val result = authHandler.handleAuthNotification(mockNotification)
        assertTrue("Result should be SendPacket", result is AuthResult.SendPacket)

        val sendPacket = (result as AuthResult.SendPacket).data
        assertEquals(18, sendPacket.size)
        assertEquals((0x03 or 0x80).toByte(), sendPacket[0]) // 0x83
        assertEquals(0x08.toByte(), sendPacket[1])
        assertEquals(HuamiAuthHandler.Step.WAITING_CONFIRMATION, authHandler.currentStep)
    }

    @Test
    fun `test handle legacy random challenge notification with 0x02 opcode also works`() {
        authHandler.startHandshake()

        val mockChallenge = ByteArray(16) { (it + 1).toByte() }
        val mockNotification = byteArrayOf(0x10, 0x02, 0x01) + mockChallenge

        val result = authHandler.handleAuthNotification(mockNotification)
        assertTrue("Result should be SendPacket", result is AuthResult.SendPacket)

        val sendPacket = (result as AuthResult.SendPacket).data
        assertEquals(18, sendPacket.size)
        assertEquals((0x03 or 0x80).toByte(), sendPacket[0]) // 0x83
        assertEquals(0x08.toByte(), sendPacket[1])
    }

    @Test
    fun `test status 7 auto-fallback switches from modern to legacy mode`() {
        authHandler.startHandshake()
        assertEquals(HuamiAuthHandler.AuthProtocolMode.MODERN_CRYPT_08, authHandler.currentProtocolMode)

        // Band responds with status 7 on 0x83 (invalid flag)
        val status7Notification = byteArrayOf(0x10, (0x03 or 0x80).toByte(), 0x07)
        val result = authHandler.handleAuthNotification(status7Notification)

        assertTrue("Result should trigger new handshake packet with fallback mode", result is AuthResult.SendPacket)
        assertEquals(HuamiAuthHandler.AuthProtocolMode.LEGACY_08, authHandler.currentProtocolMode)
        assertArrayEquals(BleConstants.AUTH_CMD_REQUEST_RANDOM, (result as AuthResult.SendPacket).data)
    }

    @Test
    fun `test handle auth success confirmation with 0x83 opcode`() {
        authHandler.startHandshake()
        // Band sends modern success confirmation: [0x10, 0x83, 0x01]
        val successNotification = byteArrayOf(0x10, (0x03 or 0x80).toByte(), 0x01)
        val result = authHandler.handleAuthNotification(successNotification)

        assertEquals(AuthResult.Success, result)
        assertEquals(HuamiAuthHandler.Step.AUTHENTICATED, authHandler.currentStep)
    }

    @Test
    fun `test handle auth success confirmation with legacy 0x03 opcode`() {
        authHandler.startHandshake()
        // Band sends legacy success confirmation: [0x10, 0x03, 0x01]
        val successNotification = byteArrayOf(0x10, 0x03, 0x01)
        val result = authHandler.handleAuthNotification(successNotification)

        assertEquals(AuthResult.Success, result)
        assertEquals(HuamiAuthHandler.Step.AUTHENTICATED, authHandler.currentStep)
    }

    @Test
    fun `test authKey sanitization with 0x prefix, spaces and hyphens`() {
        val testKeyWithPrefixAndSpaces = "0x 01 23 45 67 - 89 AB CD EF - 01 23 45 67 - 89 ab cd ef"
        val handler = HuamiAuthHandler()
        val success = handler.setAuthKeyHex(testKeyWithPrefixAndSpaces)
        assertTrue("Sanitized auth key should parse successfully", success)
    }
}
