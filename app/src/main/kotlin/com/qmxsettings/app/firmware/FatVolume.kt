package com.qmxsettings.app.firmware

import java.io.IOException

/** A device addressed in fixed-size blocks (a USB mass-storage LUN, or a disk image in tests). */
interface BlockDevice {
    val blockSize: Int
    val blockCount: Long
    fun read(lba: Long, count: Int): ByteArray
    fun write(lba: Long, data: ByteArray)
}

data class FatEntry(val name: String, val size: Long, val firstCluster: Int, val attr: Int, internal val dirIndex: Int) {
    val isDirectory get() = attr and 0x10 != 0
    val isVolumeLabel get() = attr and 0x08 != 0
}

/**
 * Minimal FAT12/FAT16 driver for the QMX "QFU" bootloader drive (a ~4 MB emulated flash stick holding
 * the firmware file and EEPROM). Root directory only: list, read a file, write a file.
 *
 * Writes mimic what desktop OSes do when you copy a file in (the bootloader is documented to work with
 * Windows, macOS and Linux file managers): directory entry first (size 0), then the data clusters in
 * order, then the FAT chain, then the final directory entry with start cluster and size.
 */
class FatVolume(private val dev: BlockDevice) {
    val partitionStart: Long
    val bytesPerSector: Int
    val sectorsPerCluster: Int
    private val reservedSectors: Int
    private val numFats: Int
    private val rootEntries: Int
    private val totalSectors: Long
    private val fatSectors: Int
    val fatBits: Int
    private val clusterCount: Int
    private val rootDirStart: Long
    private val rootDirSectors: Int
    private val dataStart: Long

    init {
        require(dev.blockSize == 512) { "Unsupported block size ${dev.blockSize}" }
        var s0 = dev.read(0, 1)
        var start = 0L
        if (!looksLikeBootSector(s0) && u16(s0, 510) == 0xAA55) {
            // MBR: use the first non-empty partition.
            val p = (0 until 4).map { 446 + it * 16 }.firstOrNull { s0[it + 4].toInt() != 0 && u32(s0, it + 8) > 0 }
                ?: throw IOException("No partition found on drive")
            start = u32(s0, p + 8)
            s0 = dev.read(start, 1)
        }
        if (!looksLikeBootSector(s0)) throw IOException("Drive is not FAT formatted")
        partitionStart = start
        bytesPerSector = u16(s0, 11)
        sectorsPerCluster = s0[13].toInt() and 0xFF
        reservedSectors = u16(s0, 14)
        numFats = s0[16].toInt() and 0xFF
        rootEntries = u16(s0, 17)
        totalSectors = u16(s0, 19).toLong().takeIf { it != 0L } ?: u32(s0, 32)
        fatSectors = u16(s0, 22)
        if (bytesPerSector != 512) throw IOException("Unsupported sector size $bytesPerSector")
        if (fatSectors == 0 || rootEntries == 0) throw IOException("FAT32 is not supported (QMX uses FAT16)")
        rootDirSectors = (rootEntries * 32 + bytesPerSector - 1) / bytesPerSector
        rootDirStart = partitionStart + reservedSectors + numFats.toLong() * fatSectors
        dataStart = rootDirStart + rootDirSectors
        clusterCount = ((totalSectors - (dataStart - partitionStart)) / sectorsPerCluster).toInt()
        fatBits = if (clusterCount < 4085) 12 else 16 // per the Microsoft FAT spec: type is set by cluster count
    }

    private fun looksLikeBootSector(s: ByteArray): Boolean {
        val bps = u16(s, 11); val spc = s[13].toInt() and 0xFF
        return (s[0].toInt() and 0xFF) in setOf(0xEB, 0xE9) && bps in setOf(512, 1024, 2048, 4096) &&
            spc != 0 && spc and (spc - 1) == 0 && (s[16].toInt() and 0xFF) in 1..2
    }

    val clusterBytes get() = sectorsPerCluster * bytesPerSector

    private fun readRoot(): ByteArray = dev.read(rootDirStart, rootDirSectors)
    private fun readFat(): ByteArray = dev.read(partitionStart + reservedSectors, fatSectors)

    fun list(): List<FatEntry> {
        val d = readRoot()
        val out = mutableListOf<FatEntry>()
        for (i in 0 until rootEntries) {
            val o = i * 32
            val first = d[o].toInt() and 0xFF
            if (first == 0) break
            if (first == 0xE5) continue
            val attr = d[o + 11].toInt() and 0xFF
            if (attr == 0x0F) continue // long-file-name fragment
            val base = String(d, o, 8, Charsets.US_ASCII).trimEnd()
            val ext = String(d, o + 8, 3, Charsets.US_ASCII).trimEnd()
            out += FatEntry(if (ext.isEmpty()) base else "$base.$ext", u32(d, o + 28), u16(d, o + 26), attr, i)
        }
        return out
    }

    fun files() = list().filter { !it.isDirectory && !it.isVolumeLabel }

    fun find(name: String) = files().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun fatGet(fat: ByteArray, c: Int): Int = if (fatBits == 16) u16(fat, c * 2) else {
        val o = c * 3 / 2
        val v = u16(fat, o)
        if (c and 1 == 0) v and 0xFFF else v shr 4
    }

    private fun fatSet(fat: ByteArray, c: Int, value: Int) {
        if (fatBits == 16) { put16(fat, c * 2, value); return }
        val o = c * 3 / 2
        val v = u16(fat, o)
        put16(fat, o, if (c and 1 == 0) (v and 0xF000) or (value and 0xFFF) else (v and 0x000F) or ((value and 0xFFF) shl 4))
    }

    private val eoc get() = if (fatBits == 16) 0xFFF8 else 0xFF8

    fun read(entry: FatEntry): ByteArray {
        val fat = readFat()
        val out = java.io.ByteArrayOutputStream()
        var c = entry.firstCluster
        var left = entry.size
        var guard = 0
        while (left > 0 && c in 2 until clusterCount + 2) {
            val chunk = dev.read(clusterLba(c), sectorsPerCluster)
            val n = minOf(left, chunk.size.toLong()).toInt()
            out.write(chunk, 0, n)
            left -= n
            c = fatGet(fat, c)
            if (++guard > clusterCount) throw IOException("FAT chain loop")
        }
        if (left > 0) throw IOException("File ${entry.name} is shorter on disk than its size")
        return out.toByteArray()
    }

    private fun clusterLba(c: Int) = dataStart + (c - 2).toLong() * sectorsPerCluster

    /** 8.3 upper-case name as 11 bytes, e.g. "1_04_015.QMX" -> "1_04_015QMX". */
    fun shortName(name: String): ByteArray {
        val dot = name.lastIndexOf('.')
        val base = (if (dot >= 0) name.substring(0, dot) else name).uppercase()
        val ext = (if (dot >= 0) name.substring(dot + 1) else "").uppercase()
        val ok = Regex("[A-Z0-9_\\-~!#$%&'(){}^@`]+")
        require(base.length in 1..8 && ok.matches(base)) { "File name must be 1-8 letters/digits/underscore: $name" }
        require(ext.length <= 3 && (ext.isEmpty() || ok.matches(ext))) { "Extension must be up to 3 characters: $name" }
        return (base.padEnd(8) + ext.padEnd(3)).toByteArray(Charsets.US_ASCII)
    }

    /**
     * Writes [data] as [name] in the root directory, replacing any existing file of that name.
     * [progress] receives bytes of file data written. If [stopAfterData] the FAT/dir finalisation is
     * attempted but failures are tolerated (the QMX may reboot as soon as it has the whole image).
     */
    fun write(name: String, data: ByteArray, progress: (Long) -> Unit = {}): WriteOutcome {
        val sn = shortName(name)
        val root = readRoot()
        val fat = readFat()
        // Reuse an entry with the same name (overwrite), else the first free slot.
        var slot = -1
        var oldFirst = 0
        for (i in 0 until rootEntries) {
            val o = i * 32
            val b = root[o].toInt() and 0xFF
            if (b != 0 && b != 0xE5 && (root[o + 11].toInt() and 0xFF) != 0x0F && root.copyOfRange(o, o + 11).contentEquals(sn)) {
                slot = i; oldFirst = u16(root, o + 26); break
            }
        }
        if (slot < 0) slot = (0 until rootEntries).firstOrNull { val b = root[it * 32].toInt() and 0xFF; b == 0 || b == 0xE5 }
            ?: throw IOException("Root directory is full")

        // Free the old chain (in memory) so overwriting doesn't need extra space.
        if (oldFirst >= 2) {
            var c = oldFirst; var g = 0
            while (c in 2 until clusterCount + 2 && g++ <= clusterCount) { val n = fatGet(fat, c); fatSet(fat, c, 0); c = n }
        }
        val need = ((data.size + clusterBytes - 1) / clusterBytes).coerceAtLeast(if (data.isEmpty()) 0 else 1)
        val clusters = (2 until clusterCount + 2).filter { fatGet(fat, it) == 0 }.take(need)
        if (clusters.size < need) throw IOException("Not enough free space on the drive")

        // 1) directory entry with size 0 (what an OS does on create)
        writeDirEntry(root, slot, sn, 0, 0)
        dev.write(rootDirStart + slot * 32 / bytesPerSector, root.copyOfRange(slot * 32 / bytesPerSector * bytesPerSector, (slot * 32 / bytesPerSector + 1) * bytesPerSector))

        // 2) data, in cluster order, in runs of contiguous clusters
        var written = 0L
        var i = 0
        while (i < clusters.size) {
            var j = i
            while (j + 1 < clusters.size && clusters[j + 1] == clusters[j] + 1 && j - i < 31) j++
            val from = i * clusterBytes
            val to = minOf(data.size, (j + 1) * clusterBytes)
            val buf = ByteArray((j - i + 1) * clusterBytes)
            System.arraycopy(data, from, buf, 0, to - from)
            dev.write(clusterLba(clusters[i]), buf)
            written += to - from
            progress(written)
            i = j + 1
        }

        // 3) FAT chain (all copies), 4) final directory entry
        return try {
            for ((k, c) in clusters.withIndex()) fatSet(fat, c, if (k + 1 < clusters.size) clusters[k + 1] else eoc)
            for (f in 0 until numFats) dev.write(partitionStart + reservedSectors + f.toLong() * fatSectors, fat)
            writeDirEntry(root, slot, sn, clusters.firstOrNull() ?: 0, data.size.toLong())
            val sec = slot * 32 / bytesPerSector
            dev.write(rootDirStart + sec, root.copyOfRange(sec * bytesPerSector, (sec + 1) * bytesPerSector))
            WriteOutcome.Complete
        } catch (e: IOException) {
            WriteOutcome.DataWrittenThenDisconnected(e.message ?: "I/O error")
        }
    }

    private fun writeDirEntry(root: ByteArray, slot: Int, sn: ByteArray, first: Int, size: Long) {
        val o = slot * 32
        java.util.Arrays.fill(root, o, o + 32, 0)
        System.arraycopy(sn, 0, root, o, 11)
        root[o + 11] = 0x20 // archive
        // Fixed timestamp 2026-01-01 00:00 (the bootloader ignores dates).
        val date = ((2026 - 1980) shl 9) or (1 shl 5) or 1
        put16(root, o + 16, date); put16(root, o + 18, date); put16(root, o + 24, date)
        put16(root, o + 26, first)
        put32(root, o + 28, size)
    }

    sealed class WriteOutcome {
        data object Complete : WriteOutcome()
        /** All file data was sent, then the drive went away (normal for a QMX that reboots straight into new firmware). */
        data class DataWrittenThenDisconnected(val error: String) : WriteOutcome()
    }

    companion object {
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
        fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun put32(b: ByteArray, o: Int, v: Long) { put16(b, o, (v and 0xFFFF).toInt()); put16(b, o + 2, ((v shr 16) and 0xFFFF).toInt()) }
    }
}
