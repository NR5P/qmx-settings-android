package com.qmxsettings.app.firmware

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB Mass Storage, Bulk-Only Transport with SCSI commands: just enough to read and write blocks on
 * the QMX bootloader's emulated flash drive.
 */
class UsbMassStorage private constructor(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : BlockDevice, AutoCloseable {
    private var tag = 1
    override var blockSize = 512; private set
    override var blockCount = 0L; private set
    var vendor = ""; private set
    var product = ""; private set

    companion object {
        private const val TIMEOUT = 5000

        /** The mass-storage interface of [d], if it has one (class 8, SCSI transparent, bulk-only). */
        fun findInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceProtocol == 0x50 }

        fun isMassStorage(d: UsbDevice) = findInterface(d) != null

        fun open(manager: UsbManager, d: UsbDevice): UsbMassStorage {
            val intf = findInterface(d) ?: throw IOException("Not a USB drive")
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val inEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw IOException("No bulk IN endpoint")
            val outEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw IOException("No bulk OUT endpoint")
            val conn = manager.openDevice(d) ?: throw IOException("Cannot open USB drive")
            // force = true detaches Android's own USB storage driver if it grabbed the drive.
            if (!conn.claimInterface(intf, true)) { conn.close(); throw IOException("Cannot claim USB drive (in use?)") }
            val m = UsbMassStorage(conn, intf, inEp, outEp)
            try { m.init() } catch (e: Exception) { m.close(); throw e }
            return m
        }
    }

    private fun init() {
        val inq = command(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36, true)!!
        vendor = String(inq, 8, 8, Charsets.US_ASCII).trim()
        product = String(inq, 16, 16, Charsets.US_ASCII).trim()
        // Media can take a moment after enumeration; retry TEST UNIT READY a few times.
        for (i in 0 until 10) {
            try { command(ByteArray(6), 0, true); break } catch (e: IOException) {
                if (i == 9) throw e
                try { requestSense() } catch (_: IOException) {}
                Thread.sleep(200)
            }
        }
        val cap = command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), 8, true)!!
        val bb = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        blockCount = (bb.getInt(0).toLong() and 0xFFFFFFFFL) + 1
        blockSize = bb.getInt(4)
    }

    private fun requestSense() = command(byteArrayOf(0x03, 0, 0, 0, 18, 0), 18, true)

    /** Sends one SCSI command [cdb]. For reads returns [len] bytes; for writes sends [out]. */
    @Synchronized
    private fun command(cdb: ByteArray, len: Int, dirIn: Boolean, out: ByteArray? = null): ByteArray? {
        val t = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355).putInt(t).putInt(len).put(if (dirIn) 0x80.toByte() else 0).put(0).put(cdb.size.toByte())
        cbw.put(cdb.copyOf(16))
        bulk(outEp, cbw.array(), 31)
        var data: ByteArray? = null
        if (len > 0) {
            if (dirIn) {
                data = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = conn.bulkTransfer(inEp, data, got, minOf(16384, len - got), TIMEOUT)
                    if (n <= 0) break
                    got += n
                    if (n % inEp.maxPacketSize != 0) break // short packet ends the data phase
                }
            } else {
                var sent = 0
                while (sent < len) {
                    val n = conn.bulkTransfer(outEp, out, sent, minOf(16384, len - sent), TIMEOUT)
                    if (n <= 0) throw IOException("USB write failed")
                    sent += n
                }
            }
        }
        val csw = ByteArray(13)
        var n = conn.bulkTransfer(inEp, csw, 13, TIMEOUT)
        if (n != 13) { clearHalt(inEp); n = conn.bulkTransfer(inEp, csw, 13, TIMEOUT) }
        if (n != 13) throw IOException("USB drive did not respond")
        val bb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x53425355 || bb.getInt(4) != t) throw IOException("Bad USB drive response")
        if (csw[12].toInt() != 0) throw IOException("Drive reported an error (SCSI 0x%02X)".format(cdb[0]))
        return data
    }

    private fun bulk(ep: UsbEndpoint, b: ByteArray, len: Int) {
        if (conn.bulkTransfer(ep, b, len, TIMEOUT) != len) throw IOException("USB transfer failed (drive disconnected?)")
    }

    private fun clearHalt(ep: UsbEndpoint) {
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, TIMEOUT) // CLEAR_FEATURE(ENDPOINT_HALT)
    }

    private fun rw10(op: Int, lba: Long, blocks: Int): ByteArray = byteArrayOf(
        op.toByte(), 0, (lba shr 24).toByte(), (lba shr 16).toByte(), (lba shr 8).toByte(), lba.toByte(),
        0, (blocks shr 8).toByte(), blocks.toByte(), 0,
    )

    override fun read(lba: Long, count: Int): ByteArray {
        val out = ByteArray(count * blockSize)
        var done = 0
        while (done < count) {
            val n = minOf(64, count - done)
            val part = command(rw10(0x28, lba + done, n), n * blockSize, true)!!
            System.arraycopy(part, 0, out, done * blockSize, part.size)
            done += n
        }
        return out
    }

    override fun write(lba: Long, data: ByteArray) {
        require(data.size % blockSize == 0)
        val count = data.size / blockSize
        var done = 0
        while (done < count) {
            val n = minOf(64, count - done)
            command(rw10(0x2A, lba + done, n), n * blockSize, false, data.copyOfRange(done * blockSize, (done + n) * blockSize))
            done += n
        }
    }

    override fun close() {
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        conn.close()
    }
}
