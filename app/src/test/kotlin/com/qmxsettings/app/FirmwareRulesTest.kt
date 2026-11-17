package com.qmxsettings.app

import com.qmxsettings.app.cat.CatParams
import com.qmxsettings.app.cat.RadioFamily
import com.qmxsettings.app.firmware.FirmwareCatalog
import com.qmxsettings.app.firmware.FirmwareRelease
import com.qmxsettings.app.firmware.FirmwareVersion
import com.qmxsettings.app.firmware.InstallCheck
import com.qmxsettings.app.firmware.InstallNote.Level
import com.qmxsettings.app.radio.QDX_QFU
import com.qmxsettings.app.radio.RadioModel
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
        fun n(m: RadioModel, rev: String?, cur: String?, tgt: String, r: FirmwareRelease?) = InstallCheck.notes(m, rev, cur?.let { v(it) }, v(tgt), r)
        val up = n(RadioModel.QMX, "4", "1_02_005", "1_04_010", stable)
        assertTrue(up.none { it.level == Level.BLOCK }); assertTrue(up.any { "Menu Manager" in it.text })
        assertTrue(n(RadioModel.QMX, null, "1_02_005", "1_04_015", beta, ).any { it.level == Level.WARN && "beta" in it.text })
        assertTrue(n(RadioModel.QMX, null, "1_02_005", "1_04_000", plusOnly).any { it.level == Level.BLOCK })
        assertTrue(n(RadioModel.QMX_PLUS, null, "1_02_005", "1_04_000", plusOnly).none { it.level == Level.BLOCK })
        assertTrue(n(RadioModel.QMX, null, "1_02_005", "1_04_002", reset).any { "Factory Reset" in it.text })
        assertTrue(n(RadioModel.QMX, null, "1_04_010", "1_02_005", null).any { "downgrade" in it.text })
        assertTrue(n(RadioModel.QMX, null, "1_02_005", "1_02_005", null).any { "reinstalls" in it.text })
        assertTrue(n(RadioModel.QMX, "3", null, "1_00_009", null).any { it.level == Level.BLOCK })
        // QDX rules
        assertTrue(n(RadioModel.QDX, "2", "1_02", "1_02", null).any { it.level == Level.BLOCK })
        assertTrue(n(RadioModel.QDX, "5", "1_10", "1_12", FirmwareRelease("1_12", "", "- Bug fixes", "u")).none { it.level == Level.BLOCK })
        assertTrue(n(RadioModel.QCX_PLUS, null, null, "1_08", null).any { it.level == Level.BLOCK })
    }

    @Test fun restoreOnlyOnSameRadioAndFirmware() {
        val name = InstallCheck.backupName("QMX", "1_02_005.QMX", "20261116-120000")
        assertEquals("EEPROM-QMX-1_02_005-20261116-120000.bin", name)
        assertNull(InstallCheck.restoreProblem(name, 2048, "QMX", v("1_02_005"), 2048))
        assertNull(InstallCheck.restoreProblem("EEPROM-1_02_005-20261116-120000.bin", 2048, "QMX", v("1_02_005"), 2048)) // older naming
        assertNotNull(InstallCheck.restoreProblem(name, 2048, "QMX", v("1_04_010"), 2048))
        assertNotNull(InstallCheck.restoreProblem(name, 2048, "QMX", v("1_02_005"), 4096))
        assertNotNull(InstallCheck.restoreProblem(name, 2048, "QDX", v("1_02_005"), 2048))
        assertNull(InstallCheck.restoreProblem(InstallCheck.backupName("QDX", "1_12.QDX", "x"), 100, "QDX", v("1_12"), 100))
        assertNotNull(InstallCheck.restoreProblem("EEPROM-x.bin", 2048, "QMX", v("1_02_005"), 2048))
    }

    @Test fun qdxCommandsFollowFirmware() {
        fun codes(fw: String?) = CatParams.forRadio(RadioFamily.QDX, fw?.let { v(it) }).map { "${it.code}:${it.label}" }
        val old = codes("1_05")
        assertTrue(old.contains("Q3:Receiver gain")); assertTrue(old.contains("Q4:VOX")); assertTrue(old.contains("QB:Japan band limits"))
        val new = codes("1_12")
        assertTrue(new.contains("Q3:VOX")); assertTrue(new.contains("Q4:TX rise threshold")); assertTrue(new.contains("QB:CAT timeout enable"))
        assertFalse(new.contains("Q3:Receiver gain"))
        assertTrue(codes("1_03").contains("AG:Audio gain")); assertTrue(codes("1_04").contains("AG:Audio gain (current band)"))
        assertEquals(codes("1_12"), codes(null)) // unknown version = newest
        assertEquals(1, new.count { it.startsWith("Q3:") }); assertEquals(1, new.count { it.startsWith("AG:") })
        assertTrue(v("1_12") > v("1_10")); assertTrue(v("1_06") > v("1_05")); assertEquals(v("1_12"), FirmwareVersion.parse("VN1_12"))
    }

    @Test fun qcxCommands() {
        val q = CatParams.forRadio(RadioFamily.QCX, null).map { it.code }
        assertTrue(q.containsAll(listOf("FA", "FB", "KS", "FR"))); assertFalse(q.contains("VN"))
    }

    /** ALLFW_DIR holds every firmware download listed on qrp-labs.com/qmx.html, plus qmx.html. */
    /** QDX_DIR holds qdx.html, qdxm.html and 1_12.zip from qrp-labs.com. */
    @Test fun qdxCatalogAndImage() {
        val dir = System.getenv("QDX_DIR")?.let(::File); assumeTrue(dir != null)
        val rel = (FirmwareCatalog.parse(File(dir, "qdx.html").readText(), QDX_QFU) + FirmwareCatalog.parse(File(dir, "qdxm.html").readText(), QDX_QFU)).distinctBy { it.version }
        println("qdx releases: " + rel.map { it.version })
        assertTrue(rel.any { it.version == "1_12" }); assertTrue(rel.size >= 10)
        val img = FirmwareCatalog.fromFile("1_12.zip", File(dir, "1_12.zip").readBytes(), QDX_QFU)
        assertEquals("1_12.QDX", img.fileName)
        try { FirmwareCatalog.fromFile("1_12.zip", File(dir, "1_12.zip").readBytes()); org.junit.Assert.fail("QDX file accepted as QMX") } catch (_: java.io.IOException) {}
    }

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
