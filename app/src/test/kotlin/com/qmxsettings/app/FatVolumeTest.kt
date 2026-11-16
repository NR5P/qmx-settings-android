package com.qmxsettings.app

import com.qmxsettings.app.firmware.BlockDevice
import com.qmxsettings.app.firmware.FatVolume
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class FileBlockDevice(f: File, private val offsetBlocks: Long = 0) : BlockDevice {
    private val raf = RandomAccessFile(f, "rw")
    override val blockSize = 512
    override val blockCount = raf.length() / 512 - offsetBlocks
    val writes = mutableListOf<Pair<Long, Int>>()
    override fun read(lba: Long, count: Int): ByteArray { val b = ByteArray(count * 512); raf.seek(lba * 512); raf.readFully(b); return b }
    override fun write(lba: Long, data: ByteArray) { require(data.size % 512 == 0); writes += lba to data.size / 512; raf.seek(lba * 512); raf.write(data) }
}

/** Runs against images made by macOS (newfs_msdos + Finder copy); FAT_DIR is set by the verification script. */
class FatVolumeTest {
    private val dir = System.getenv("FAT_DIR")?.let(::File)

    private fun exercise(img: File, expectBits: Int) {
        val src = File(dir, "src")
        val dev = FileBlockDevice(img)
        val v = FatVolume(dev)
        assertEquals(expectBits, v.fatBits)
        val names = v.files().map { it.name }
        println("${img.name}: FAT${v.fatBits} start=${v.partitionStart} cluster=${v.clusterBytes} files=$names")
        assertArrayEquals(File(src, "EEPROM").readBytes(), v.read(v.find("EEPROM")!!))
        assertArrayEquals(File(src, "1_02_005.QMX").readBytes(), v.read(v.find("1_02_005.QMX")!!))
        val newFw = File(src, "NEW.QMX").readBytes()
        val dataLbaBefore = dev.writes.size
        assertEquals(FatVolume.WriteOutcome.Complete, v.write("1_04_015.QMX", newFw))
        // The firmware data must go out as increasing LBAs between the first dir write and the FAT writes.
        val w = dev.writes.drop(dataLbaBefore)
        println("  write order: ${w.take(3)} … ${w.takeLast(3)} (${w.size} writes)")
        assertEquals(FatVolume.WriteOutcome.Complete, v.write("EEPROM", File(src, "EEPROM.NEW").readBytes()))
        val v2 = FatVolume(FileBlockDevice(img))
        assertArrayEquals(newFw, v2.read(v2.find("1_04_015.QMX")!!))
        assertArrayEquals(File(src, "EEPROM.NEW").readBytes(), v2.read(v2.find("EEPROM")!!))
        assertEquals(1, v2.files().count { it.name == "EEPROM" })
    }

    @Test fun fat16Image() { assumeTrue(dir != null); exercise(File(dir, "f16.img"), 16) }
    @Test fun fat12Image() { assumeTrue(dir != null); exercise(File(dir, "f12.img"), 12) }

    @Test fun mbrPartitionedImage() {
        assumeTrue(dir != null)
        // Wrap the FAT16 volume in an MBR with the partition at LBA 63.
        val vol = File(dir, "f16-orig.img").readBytes()
        val mbr = ByteArray(63 * 512)
        val p = 446
        mbr[p + 4] = 0x06
        FatVolume.put32(mbr, p + 8, 63); FatVolume.put32(mbr, p + 12, (vol.size / 512).toLong())
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        val img = File(dir, "mbr.img"); img.writeBytes(mbr + vol)
        val v = FatVolume(FileBlockDevice(img))
        assertEquals(63L, v.partitionStart)
        assertArrayEquals(File(dir, "src/EEPROM").readBytes(), v.read(v.find("EEPROM")!!))
    }

    @Test fun shortNames() {
        assumeTrue(dir != null)
        val v = FatVolume(FileBlockDevice(File(dir, "f16-orig.img")))
        assertEquals("1_04_015QMX", String(v.shortName("1_04_015.qmx")))
        assertEquals("EEPROM     ", String(v.shortName("EEPROM")))
    }
}
