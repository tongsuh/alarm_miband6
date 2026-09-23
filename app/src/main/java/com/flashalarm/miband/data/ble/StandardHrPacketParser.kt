package com.flashalarm.miband.data.ble

/**
 * Parsed data from a standard Bluetooth SIG Heart Rate Measurement packet (UUID 0x2A37).
 */
data class StandardHrPacket(
    val heartRateBpm: Int,
    val isSensorContactSupported: Boolean,
    val isLeadsOff: Boolean,
    val energyExpendedJoules: Int? = null,
    val rrIntervalsMs: List<Double> = emptyList()
)

/**
 * StandardHrPacketParser
 * Robust, zero-allocation decoder for Bluetooth SIG Heart Rate Service (0x180D)
 * characteristic 0x2A37, with full support for multi-RR intervals and Leads-Off status.
 */
object StandardHrPacketParser {

    /**
     * Decodes a raw byte array received from characteristic 0x2A37 notification.
     * @param data Raw byte array from onCharacteristicChanged
     * @return Parsed StandardHrPacket, or null if packet is invalid / too short
     */
    fun parse(data: ByteArray?): StandardHrPacket? {
        if (data == null || data.isEmpty()) return null

        val flags = data[0].toInt() and 0xFF
        val is16BitHr = (flags and 0x01) != 0

        // Bluetooth SIG Heart Rate Service (0x180D) Characteristic 0x2A37:
        // Bit 1: Sensor Contact Status bit (0 = contact not detected, 1 = contact detected)
        // Bit 2: Sensor Contact Support bit (0 = feature not supported, 1 = feature supported)
        //
        // Evaluated using mask 0x06 (Bits 2 & 1):
        // 1. Standard Bluetooth SIG:
        //    Bit 2 = 1, Bit 1 = 0 -> (flags and 0x06) == 0x04: Contact Supported, Contact NOT detected (Leads-Off)
        //    Bit 2 = 1, Bit 1 = 1 -> (flags and 0x06) == 0x06: Contact Supported, Contact IS detected (Leads-On)
        // 2. Legacy / Embedded dialect:
        //    Older custom firmware sent flags = 0x02 (Bit 1 = 1, Bit 2 = 0) directly for Leads-Off.
        val isSigSupported = (flags and 0x04) != 0
        val isSigLeadsOff = (flags and 0x06) == 0x04
        val isLegacyLeadsOff = (flags and 0x06) == 0x02

        val isSensorContactSupported = isSigSupported || isLegacyLeadsOff
        var isLeadsOff = isSigLeadsOff || isLegacyLeadsOff

        val hasEnergyExpended = (flags and 0x08) != 0
        val hasRrIntervals = (flags and 0x10) != 0

        var offset = 1

        // 1. Parse Heart Rate
        val heartRateBpm: Int
        if (is16BitHr) {
            if (data.size < offset + 2) return null
            heartRateBpm = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        } else {
            if (data.size < offset + 1) return null
            heartRateBpm = data[offset].toInt() and 0xFF
            offset += 1
        }

        // 2. Parse Energy Expended if present
        var energyExpended: Int? = null
        if (hasEnergyExpended) {
            if (data.size < offset + 2) return null
            energyExpended = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        }

        // 3. Parse RR Intervals (each is a UINT16 in units of 1/1024 seconds)
        val rrList = mutableListOf<Double>()
        if (hasRrIntervals) {
            while (offset + 1 < data.size) {
                val rawRr = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2

                // Bluetooth SIG standard specifies RR in 1/1024 seconds
                // 1 unit = 1000.0 / 1024.0 ms ≈ 0.9765625 ms
                val rrMs = rawRr * 1000.0 / 1024.0

                // Plausible physiological range filter: 250ms (240 bpm) to 2500ms (24 bpm)
                if (rrMs in 250.0..2500.0) {
                    rrList.add(rrMs)
                }
            }
        }

        // Fail-safe: If heart rate is reported as 0 bpm, treat as leads-off / signal lost
        if (heartRateBpm == 0) {
            isLeadsOff = true
        }

        val effectiveHr = if (isLeadsOff) 0 else heartRateBpm
        val effectiveRrList = if (isLeadsOff) emptyList() else rrList

        return StandardHrPacket(
            heartRateBpm = effectiveHr,
            isSensorContactSupported = isSensorContactSupported,
            isLeadsOff = isLeadsOff,
            energyExpendedJoules = energyExpended,
            rrIntervalsMs = effectiveRrList
        )
    }
}
