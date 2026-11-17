package com.qmxsettings.app.firmware

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class FirmwareRelease(val version: String, val date: String, val notes: String, val url: String) {
    val beta get() = notes.contains("beta", ignoreCase = true)
    val stable get() = notes.startsWith("Stable", ignoreCase = true)
    /** e.g. 1_04_000: "Use only on QMX+ it does not work on QMX". */
    val qmxPlusOnly get() = Regex("""only on QMX\+""", RegexOption.IGNORE_CASE).containsMatchIn(notes)
    /** e.g. 1_04_002: "Installing this firmware executes a Factory Reset". */
    val factoryReset get() = Regex("""(executes|performs) (a )?factory reset""", RegexOption.IGNORE_CASE).containsMatchIn(notes)
    val parsedVersion get() = FirmwareVersion.parse(version)
}

/** A firmware image ready to copy to the QMX: the .QMX file name and its bytes. */
data class FirmwareImage(val fileName: String, val data: ByteArray) {
    val version get() = fileName.substringBeforeLast('.')
}

/** Official QMX / QMX+ firmware list, read from the QRP Labs QMX page. */
object FirmwareCatalog {
    const val PAGE = "https://qrp-labs.com/qmx.html"
    private const val HOST = "https://qrp-labs.com"
    /** "QMX firmware images are always a 529K file" (assembly + operating manuals): every published image is 541696 bytes. */
    const val IMAGE_SIZE = 541696
    /** Every published .QMX file starts with this plain-text marker before the encrypted payload. */
    private val MAGIC = "QRP Labs".toByteArray(Charsets.US_ASCII)
    private val NAME = Regex("""^\d+_\d{2}_\d{3}\.QMX$""", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<FirmwareRelease> {
        val out = mutableListOf<FirmwareRelease>()
        for (row in Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)) {
            val r = row.groupValues[1]
            val link = Regex("""href="([^"]*/qmx/firmware/[^"]+\.(?:zip|qmx))"""", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1) ?: continue
            val cells = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(r)
                .map { text(it.groupValues[1]) }.toList()
            val version = cells.getOrNull(0)?.takeIf { Regex("""\d+_\d{2}_\d{3}""").matches(it) } ?: continue
            out += FirmwareRelease(version, cells.getOrElse(1) { "" }, cells.getOrElse(2) { "" }, if (link.startsWith("http")) link else HOST + link)
        }
        return out.distinctBy { it.version }
    }

    private fun text(h: String) = h.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ").trim()

    fun fetchReleases(): List<FirmwareRelease> = parse(String(get(PAGE), Charsets.UTF_8))

    fun download(r: FirmwareRelease): FirmwareImage {
        val img = fromFile(r.url.substringAfterLast('/'), get(r.url))
        if (img.version != r.version) throw IOException("Download for ${r.version} contained ${img.fileName} — not installing")
        return img
    }

    /** Accepts a downloaded .zip (containing one .QMX) or a bare .QMX file. */
    fun fromFile(name: String, bytes: ByteArray): FirmwareImage {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val img = if (isZip) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                generateSequence { z.nextEntry }.filter { !it.isDirectory && it.name.substringAfterLast('/').endsWith(".QMX", true) }
                    .map { FirmwareImage(it.name.substringAfterLast('/'), z.readBytes()) }.firstOrNull()
            } ?: throw IOException("No .QMX firmware file inside $name")
        } else FirmwareImage(name.substringAfterLast('/'), bytes)
        return validate(img)
    }

    fun validate(img: FirmwareImage): FirmwareImage {
        if (!NAME.matches(img.fileName)) throw IOException("\"${img.fileName}\" doesn't look like a QMX firmware file (expected e.g. 1_04_010.QMX)")
        if (img.data.size != IMAGE_SIZE) throw IOException("${img.fileName} is ${img.data.size} bytes; QMX firmware images are always $IMAGE_SIZE bytes (529 KB)")
        if (!img.data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException("${img.fileName} doesn't have the QRP Labs firmware header — not a QMX firmware file")
        return img.copy(fileName = img.fileName.uppercase())
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "QMX-Settings-Android")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} from ${URL(url).host}")
            val body = c.inputStream.use { it.readBytes() }
            val expected = c.contentLengthLong
            if (expected >= 0 && body.size.toLong() != expected) throw IOException("Download incomplete (${body.size} of $expected bytes)")
            return body
        } finally { c.disconnect() }
    }
}
