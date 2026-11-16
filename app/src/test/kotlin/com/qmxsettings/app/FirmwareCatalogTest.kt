package com.qmxsettings.app

import com.qmxsettings.app.firmware.FirmwareCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class FirmwareCatalogTest {
    @Test fun parsesTableRows() {
        val html = """<table><tr><td>1_04_015</td><td>24-Sep-2026</td><td>Consider this a Beta firmware version<br>1. Bug fix</td>
            <td><a href="/images/qmx/firmware/1_04_015.zip">zip</a></td></tr>
            <tr><td>1_04_010</td><td>14-Sep-2026</td><td>Stable version 1. Things</td><td><a href="/images/qmx/firmware/1_04_010.zip">zip</a></td></tr></table>"""
        val r = FirmwareCatalog.parse(html)
        assertEquals(listOf("1_04_015", "1_04_010"), r.map { it.version })
        assertTrue(r[0].beta); assertTrue(r[1].stable)
        assertEquals("https://qrp-labs.com/images/qmx/firmware/1_04_010.zip", r[1].url)
    }

    @Test(expected = IOException::class) fun rejectsWrongFiles() { FirmwareCatalog.fromFile("notes.txt", ByteArray(541696)) }
    @Test(expected = IOException::class) fun rejectsWrongSize() { FirmwareCatalog.fromFile("1_04_010.QMX", ByteArray(1000)) }

    /** FW_DIR holds qmx.html and 1_04_015.zip fetched from qrp-labs.com. */
    @Test fun realPageAndZip() {
        val dir = System.getenv("FW_DIR")?.let(::File); assumeTrue(dir != null)
        val r = FirmwareCatalog.parse(File(dir, "qmx.html").readText())
        println("releases=${r.size} first=${r.take(3).map { "${it.version} beta=${it.beta} stable=${it.stable}" }} lateststable=${r.firstOrNull { it.stable }?.version}")
        assertTrue(r.size >= 10)
        val img = FirmwareCatalog.fromFile("1_04_015.zip", File(dir, "1_04_015.zip").readBytes())
        assertEquals("1_04_015.QMX", img.fileName); assertEquals(541696, img.data.size)
    }
}
