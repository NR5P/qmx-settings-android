package com.qmxsettings.app

import com.qmxsettings.app.cat.CatParams
import com.qmxsettings.app.cat.RadioFamily
import com.qmxsettings.app.firmware.FirmwareCatalog
import com.qmxsettings.app.firmware.FirmwareRelease
import com.qmxsettings.app.firmware.FirmwareVersion
import com.qmxsettings.app.firmware.InstallCheck
import com.qmxsettings.app.firmware.InstallNote.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FirmwareRulesTest {
    private fun v(s: String) = FirmwareVersion.of(s)

    @Test fun versions() {
        assertEquals(v("1_02_005"), FirmwareVersion.parse("VN1_02_005QMX"))
        assertEquals(v("1_04_008"), FirmwareVersion.parse("1_04_008a"))
        assertTrue(v("1_04_010") > v("1_03_002")); assertTrue(v("1_02_009") > v("1_02_006"))
    }

    @Test fun catCommandsFollowFirmware() {
        fun codes(fw: String) = CatParams.forRadio(RadioFamily.QMX, v(fw)).map { "${it.code}:${it.label}" }
        val old = codes("1_02_005")
        assertTrue(old.contains("AG:RF gain (current band)")); assertFalse(old.any { it.startsWith("RG:") })
        assertFalse(old.any { it.startsWith("UI:") || it.startsWith("BN:") || it.startsWith("TR:") || it.startsWith("RR:") || it.startsWith("AI:") })
        assertTrue(old.any { it.startsWith("OM:") })
        val mid = codes("1_03_002")
        assertTrue(mid.contains("AG:AF gain (volume)")); assertTrue(mid.any { it.startsWith("RG:") }); assertFalse(mid.any { it.startsWith("TR:") })
        val new = codes("1_04_010")
        assertTrue(listOf("UI", "BN", "TR", "RR", "AI", "RG").all { c -> new.any { it.startsWith("$c:") } })
        assertEquals(1, new.count { it.startsWith("AG:") })
        assertFalse(codes("1_02_001").any { it.startsWith("OM:") })
    }

    private val beta = FirmwareRelease("1_04_015", "", "Consider this a Beta firmware version 1. x", "u")
    private val stable = FirmwareRelease("1_04_010", "", "Stable version 1. x", "u")
    private val plusOnly = FirmwareRelease("1_04_000", "", "Beta firmware version: Use only on QMX+ it does not work on QMX", "u")
    private val reset = FirmwareRelease("1_04_002", "", "Beta firmware version NOTE: Installing this firmware executes a Factory Reset: be sure…", "u")

    @Test fun installRules() {
        val up = InstallCheck.notes(v("1_02_005"), v("1_04_010"), stable, false)
        assertTrue(up.none { it.level == Level.BLOCK }); assertTrue(up.any { "Menu Manager" in it.text })
        assertTrue(InstallCheck.notes(v("1_02_005"), v("1_04_015"), beta, false).any { it.level == Level.WARN && "beta" in it.text })
        assertTrue(InstallCheck.notes(v("1_02_005"), v("1_04_000"), plusOnly, false).any { it.level == Level.BLOCK })
        assertTrue(InstallCheck.notes(v("1_02_005"), v("1_04_000"), plusOnly, true).none { it.level == Level.BLOCK })
        assertTrue(InstallCheck.notes(v("1_02_005"), v("1_04_002"), reset, false).any { "Factory Reset" in it.text })
        assertTrue(InstallCheck.notes(v("1_04_010"), v("1_02_005"), null, false).any { "downgrade" in it.text })
        assertTrue(InstallCheck.notes(v("1_02_005"), v("1_02_005"), null, false).any { "reinstalls" in it.text })
    }

    @Test fun restoreOnlyOnSameFirmware() {
        assertNull(InstallCheck.restoreProblem("EEPROM-1_02_005-20261116-120000.bin", 2048, v("1_02_005"), 2048))
        assertNotNull(InstallCheck.restoreProblem("EEPROM-1_02_005-20261116-120000.bin", 2048, v("1_04_010"), 2048))
        assertNotNull(InstallCheck.restoreProblem("EEPROM-1_02_005-20261116-120000.bin", 2048, v("1_02_005"), 4096))
        assertNotNull(InstallCheck.restoreProblem("EEPROM-x.bin", 2048, v("1_02_005"), 2048))
    }

    /** ALLFW_DIR holds every firmware download listed on qrp-labs.com/qmx.html, plus qmx.html. */
    @Test fun everyPublishedImagePassesValidation() {
        val dir = System.getenv("ALLFW_DIR")?.let(::File); assumeTrue(dir != null)
        val files = dir!!.listFiles { f -> f.name.endsWith(".zip") || f.name.endsWith(".qmx") }!!
        assertTrue(files.size >= 19)
        for (f in files) {
            val img = FirmwareCatalog.fromFile(f.name, f.readBytes())
            assertEquals(f.name.substringBefore('.'), img.version)
        }
        val rel = FirmwareCatalog.parse(File(dir, "qmx.html").readText())
        println("flags: " + rel.map { "${it.version}${if (it.beta) " beta" else ""}${if (it.stable) " stable" else ""}${if (it.qmxPlusOnly) " QMX+only" else ""}${if (it.factoryReset) " reset" else ""}" })
        assertEquals("1_04_010", rel.first { it.stable }.version)
    }
}
