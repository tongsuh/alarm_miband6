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
        assertArrayEquals(BleConstants.AUTH_CMD_REQUEST_RANDOM, packet)
        assertEquals(HuamiAuthHandler.Step.WAITING_CHALLENGE, authHandler.currentStep)
    }

    @Test
    fun `test handle random challenge notification produces encrypted response packet`() {
        authHandler.startHandshake()

        // Mock notification from Mi Band 6:
        // [0x10, 0x02, 0x01, ... 16 bytes challenge ...]
        val mockChallenge = ByteArray(16) { (it + 1).toByte() }
        val mockNotification = byteArrayOf(0x10, 0x02, 0x01) + mockChallenge

        val result = authHandler.handleAuthNotification(mockNotification)
        assertTrue("Result should be SendPacket", result is AuthResult.SendPacket)

        val sendPacket = (result as AuthResult.SendPacket).data
        assertEquals(18, sendPacket.size)
        assertEquals(0x03.toByte(), sendPacket[0])
        assertEquals(0x08.toByte(), sendPacket[1])
        assertEquals(HuamiAuthHandler.Step.WAITING_CONFIRMATION, authHandler.currentStep)
    }

    @Test
    fun `test handle auth success confirmation`() {
        authHandler.startHandshake()
        // Band sends success confirmation: [0x10, 0x03, 0x01]
        val successNotification = byteArrayOf(0x10, 0x03, 0x01)
        val result = authHandler.handleAuthNotification(successNotification)

        assertEquals(AuthResult.Success, result)
        assertEquals(HuamiAuthHandler.Step.AUTHENTICATED, authHandler.currentStep)
    }
}
