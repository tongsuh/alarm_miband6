package com.flashalarm.miband

import com.flashalarm.miband.data.ble.StandardHrPacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardHrPacketParserTest {

    @Test
    fun `test null or empty packet returns null`() {
        assertNull(StandardHrPacketParser.parse(null))
        assertNull(StandardHrPacketParser.parse(byteArrayOf()))
    }

    @Test
    fun `test ESP32 dialect flags 0x02 is correctly identified as leads off`() {
        // ESP32 sends flags = 0x02, HR = 0
        val raw = byteArrayOf(0x02, 0x00)
        val packet = StandardHrPacketParser.parse(raw)

        assertNotNull(packet)
        assertTrue("Flags 0x02 must indicate contact supported in embedded dialect", packet!!.isSensorContactSupported)
        assertTrue("Flags 0x02 must be parsed as leads-off", packet.isLeadsOff)
        assertEquals(0, packet.heartRateBpm)
        assertTrue("RR intervals must be empty when leads-off", packet.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `test SIG standard flags 0x04 contact supported not detected leads off`() {
        // SIG Standard: Bit 2 = 1, Bit 1 = 0 -> flags = 0x04 (contact not detected)
        val raw = byteArrayOf(0x04, 0x00)
        val packet = StandardHrPacketParser.parse(raw)

        assertNotNull(packet)
        assertTrue(packet!!.isSensorContactSupported)
        assertTrue(packet.isLeadsOff)
        assertEquals(0, packet.heartRateBpm)
    }

    @Test
    fun `test SIG standard flags 0x06 contact detected leads on with valid HR and RR`() {
        // SIG Standard: Bit 2 = 1, Bit 1 = 1 -> contact supported and detected (0x06)
        // Bit 4 = 1 -> RR intervals present (0x10) -> total flags = 0x16
        // HR = 75 bpm (0x4B)
        // RR = 800 ms -> raw value = 800 * 1024 / 1000 = 819.2 ≈ 819 (0x0333) -> low=0x33, high=0x03
        val raw = byteArrayOf(0x16.toByte(), 0x4B.toByte(), 0x33.toByte(), 0x03.toByte())
        val packet = StandardHrPacketParser.parse(raw)

        assertNotNull(packet)
        assertTrue(packet!!.isSensorContactSupported)
        assertFalse("Leads should NOT be off", packet.isLeadsOff)
        assertEquals(75, packet.heartRateBpm)
        assertEquals(1, packet.rrIntervalsMs.size)
        // 819 * 1000.0 / 1024.0 = 799.8046875 ms
        assertEquals(799.8, packet.rrIntervalsMs[0], 0.5)
    }

    @Test
    fun `test device without contact support with 0x00 flags`() {
        // Flags 0x00: 8-bit HR, contact not supported, no RR
        val raw = byteArrayOf(0x00, 68)
        val packet = StandardHrPacketParser.parse(raw)

        assertNotNull(packet)
        assertFalse(packet!!.isSensorContactSupported)
        assertFalse(packet.isLeadsOff)
        assertEquals(68, packet.heartRateBpm)
    }

    @Test
    fun `test 0 bpm heart rate automatically triggers leads off fail-safe`() {
        // Even if flags is 0x00 (no contact bit), a reported HR of 0 must fail-safe to leads-off
        val raw = byteArrayOf(0x00, 0x00)
        val packet = StandardHrPacketParser.parse(raw)

        assertNotNull(packet)
        assertTrue("0 bpm must trigger leads-off fail-safe", packet!!.isLeadsOff)
        assertEquals(0, packet.heartRateBpm)
    }

    @Test
    fun `test 16-bit heart rate parsing`() {
        // Flags 0x01: 16-bit HR
        // HR = 300 bpm (0x012C) -> low=0x2C, high=0x01
        val raw = byteArrayOf(0x01, 0x2C.toByte(), 0x01)
        val packet = StandardHrPacketParser.parse(raw)

        assertNotNull(packet)
        assertEquals(300, packet!!.heartRateBpm)
    }

    @Test
    fun `test RR physiological range filter drops noise`() {
        // Flags = 0x10 (has RR), HR = 70
        // RR 1: 100ms (raw = 102) -> out of range (<250ms), should be dropped
        // RR 2: 1000ms (raw = 1024 -> 0x0400) -> valid, kept
        // RR 3: 3000ms (raw = 3072) -> out of range (>2500ms), should be dropped
        val raw = byteArrayOf(
            0x10.toByte(), 70,
            102, 0,
            0x00, 0x04,
            0x00, 0x0C
        )
        val packet = StandardHrPacketParser.parse(raw)

        assertNotNull(packet)
        assertEquals(70, packet!!.heartRateBpm)
        assertEquals(1, packet.rrIntervalsMs.size)
        assertEquals(1000.0, packet.rrIntervalsMs[0], 0.1)
    }
}
