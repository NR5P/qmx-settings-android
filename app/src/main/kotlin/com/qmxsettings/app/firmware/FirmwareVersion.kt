package com.qmxsettings.app.firmware

/** A QMX firmware version such as 1_04_010 (from "VN1_04_010QMX;", "1_04_010.QMX" or "1_04_008a"). */
data class FirmwareVersion(val major: Int, val minor: Int, val build: Int) : Comparable<FirmwareVersion> {
    override fun compareTo(other: FirmwareVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.build })
    override fun toString() = "%d_%02d_%03d".format(major, minor, build)

    companion object {
        private val RX = Regex("""(\d+)_(\d{2})_(\d{3})""")
        fun parse(s: String?): FirmwareVersion? = s?.let { RX.find(it) }?.destructured?.let { (a, b, c) -> FirmwareVersion(a.toInt(), b.toInt(), c.toInt()) }
        fun of(s: String) = parse(s)!!

        // Milestones from the QRP Labs QMX firmware history (qrp-labs.com/qmx.html) and CAT manual.
        /** CAT OM and TB commands. */
        val OM_CMD = of("1_02_002")
        /** Menu Manager (MM/ML) CAT commands; RG = RF gain; AG changed from RF gain to volume (0.25 dB). */
        val MENU_MANAGER = of("1_02_006")
        /** 1_04_000 was QMX+ only; 1_04_001 is the first 1_04 for QMX. Adds MU, TR, RR, AI, PS, KD, MD8. */
        val V1_04 = of("1_04_000")
        val V1_04_QMX = of("1_04_001")
        /** UI, BN, BU, BD CAT commands; MM list-value fixes. */
        val V1_04_003 = of("1_04_003")
        /** Rev 2/3 PCBs should only use 1_00_011 and up. */
        val MIN_REV2 = of("1_00_011")
        /** EEPROM layout re-arranged here (automatic factory reset on upgrade). */
        val EEPROM_RELAYOUT = of("1_00_015")
    }
}
