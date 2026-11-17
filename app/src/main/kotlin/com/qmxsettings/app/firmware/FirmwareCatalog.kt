package com.qmxsettings.app.firmware

import com.qmxsettings.app.radio.QMX_QFU
import com.qmxsettings.app.radio.QfuSpec
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class FirmwareRelease(val version: String, val date: String, val notes: String, val url: String) {
    val beta get() = notes.contains("beta", ignoreCase = true)
    val stable get() = notes.startsWith("Stable", ignoreCase = true)
    /** e.g. QMX 1_04_000: "Use only on QMX+ it does not work on QMX". */
    val qmxPlusOnly get() = Regex("""only on QMX\+""", RegexOption.IGNORE_CASE).containsMatchIn(notes)
    /** e.g. QMX 1_04_002: "Installing this firmware executes a Factory Reset". */
    val factoryReset get() = Regex("""(executes|performs) (a )?factory reset""", RegexOption.IGNORE_CASE).containsMatchIn(notes)
    val parsedVersion get() = FirmwareVersion.parse(version)
}

/** A firmware image ready to copy to the radio: file name (e.g. 1_04_010.QMX, 1_12.QDX) and its bytes. */
data class FirmwareImage(val fileName: String, val data: ByteArray) {
    val version get() = fileName.substringBeforeLast('.')
    val ext get() = fileName.substringAfterLast('.', "").uppercase()
}

/** Official QRP Labs firmware lists, read from the product pages (qmx.html; qdx.html + qdxm.html). */
object FirmwareCatalog {
    private const val HOST = "https://qrp-labs.com"
    /** Every published .QMX / .QDX file starts with this plain-text marker before the encrypted payload. */
    private val MAGIC = "QRP Labs".toByteArray(Charsets.US_ASCII)
    private val VERSION = Regex("""^\d+_\d{2}(?:_\d{3})?[a-z]?$""")

    fun parse(html: String, spec: QfuSpec = QMX_QFU): List<FirmwareRelease> {
        val out = mutableListOf<FirmwareRelease>()
        val link = Regex("""href="([^"]*${Regex.escape(spec.linkPath)}[^"]+\.(?:zip|${spec.ext}))"""", RegexOption.IGNORE_CASE)
        for (row in Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)) {
            val r = row.groupValues[1]
            val href = link.find(r)?.groupValues?.get(1) ?: continue
            val cells = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(r)
                .map { text(it.groupValues[1]) }.toList()
            val version = cells.getOrNull(0)?.takeIf { VERSION.matches(it) } ?: continue
            out += FirmwareRelease(version, cells.getOrElse(1) { "" }, cells.getOrElse(2) { "" }, if (href.startsWith("http")) href else HOST + href)
        }
        return out.distinctBy { it.version }
    }

    private fun text(h: String) = h.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ").trim()

    /** All official versions for [spec], newest first, merged across its pages. */
    fun fetchReleases(spec: QfuSpec): List<FirmwareRelease> {
        val all = spec.pages.flatMap { parse(String(get(it), Charsets.UTF_8), spec) }
        return all.distinctBy { it.version }.sortedByDescending { FirmwareVersion.parse(it.version) }
    }

    fun download(r: FirmwareRelease, spec: QfuSpec): FirmwareImage {
        val bytes = try { get(r.url) } catch (e: NotFound) {
            throw IOException("${r.version} is listed on qrp-labs.com but its download link no longer works. Use a newer version, or a file you already have (Use a file…).")
        }
        val img = fromFile(r.url.substringAfterLast('/'), bytes, spec)
        if (!img.version.equals(r.version, true)) throw IOException("Download for ${r.version} contained ${img.fileName} — not installing")
        return img
    }

    /** Accepts a downloaded .zip (containing one firmware file) or a bare firmware file. */
    fun fromFile(name: String, bytes: ByteArray, spec: QfuSpec = QMX_QFU): FirmwareImage {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val img = if (isZip) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                generateSequence { z.nextEntry }.filter { !it.isDirectory && it.name.substringAfterLast('/').endsWith(".${spec.ext}", true) }
                    .map { FirmwareImage(it.name.substringAfterLast('/'), z.readBytes()) }.firstOrNull()
            } ?: throw IOException("No .${spec.ext} firmware file inside $name")
        } else FirmwareImage(name.substringAfterLast('/'), bytes)
        return validate(img, spec)
    }

    fun validate(img: FirmwareImage, spec: QfuSpec = QMX_QFU): FirmwareImage {
        if (!img.ext.equals(spec.ext, true) || !VERSION.matches(img.version))
            throw IOException("\"${img.fileName}\" isn't a ${spec.ext} firmware file (expected a name like ${if (spec.ext == "QMX") "1_04_010.QMX" else "1_12.QDX"})")
        if (img.data.size != spec.imageSize) throw IOException("${img.fileName} is ${img.data.size} bytes; ${spec.ext} firmware images are always ${spec.imageSize} bytes")
        if (!img.data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException("${img.fileName} doesn't have the QRP Labs firmware header")
        return img.copy(fileName = img.fileName.uppercase())
    }

    private class NotFound : IOException()

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "QMX-Settings-Android")
        try {
            if (c.responseCode == 404) throw NotFound()
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} from ${URL(url).host}")
            // qrp-labs.com answers missing files with an HTML error page; never treat that as firmware.
            if (url.endsWith(".zip", true) && c.contentType?.startsWith("text/html") == true) throw NotFound()
            val body = c.inputStream.use { it.readBytes() }
            val expected = c.contentLengthLong
            if (expected >= 0 && body.size.toLong() != expected) throw IOException("Download incomplete (${body.size} of $expected bytes)")
            return body
        } finally { c.disconnect() }
    }
}
