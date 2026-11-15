package com.qmxsettings.app.cat

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/** A byte pipe to a radio's CAT port. Callbacks may arrive on any thread. */
interface ByteTransport {
    var onData: ((ByteArray) -> Unit)?
    var onState: ((TransportState) -> Unit)?
    fun open()
    fun close()
    fun write(data: ByteArray)
}

sealed class TransportState {
    data object Idle : TransportState()
    data object Connecting : TransportState()
    data object Connected : TransportState()
    data class Failed(val message: String) : TransportState()
}

/** An attached USB serial port. */
data class UsbPortInfo(val id: String, val name: String, val looksLikeQrpLabs: Boolean)

/**
 * USB CDC-ACM / USB-serial transport (usb-serial-for-android), adapted from JS8Firefly.
 * QMX exposes up to three virtual COM ports; each shows up as its own port here.
 */
class UsbSerialTransport(
    context: Context,
    private val portId: String,
    private val baud: Int = 115200,
) : ByteTransport {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.qmxsettings.app.USB_PERMISSION"
        private const val STM_VID = 0x0483 // QRP Labs QMX/QDX use the STMicro VID

        private fun drivers(manager: UsbManager): List<UsbSerialDriver> {
            val found = UsbSerialProber.getDefaultProber().findAllDrivers(manager).toMutableList()
            // CDC-ACM radios (QMX/QDX) whose IDs aren't in the library's default table.
            for (d in manager.deviceList.values) {
                if (found.none { it.device.deviceId == d.deviceId } && CdcAcmSerialDriver.probe(d)) {
                    found.add(CdcAcmSerialDriver(d))
                }
            }
            return found
        }

        private fun key(vid: Int, pid: Int, port: Int) = "%04X:%04X:%d".format(vid, pid, port)

        /** Lists attached USB serial ports. Only enumerates; never opens anything. */
        fun availablePorts(context: Context): List<UsbPortInfo> {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return drivers(manager).flatMap { drv ->
                val d = drv.device
                val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { d.deviceName }
                val qrp = d.vendorId == STM_VID || name.uppercase().let { "QMX" in it || "QDX" in it || "QRP" in it }
                drv.ports.mapIndexed { i, _ ->
                    UsbPortInfo(
                        key(d.vendorId, d.productId, i),
                        if (drv.ports.size > 1) "$name (port ${i + 1})" else name,
                        qrp,
                    )
                }
            }.sortedWith(compareByDescending<UsbPortInfo> { it.looksLikeQrpLabs }.thenBy { it.name })
        }
    }

    private val context = context.applicationContext
    override var onData: ((ByteArray) -> Unit)? = null
    override var onState: ((TransportState) -> Unit)? = null
    private val manager = this.context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile private var closed = false
    private val ioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "qmx.usb") }

    override fun open() {
        val match = drivers(manager).flatMap { drv -> drv.ports.indices.map { drv to it } }
            .firstOrNull { (drv, i) -> key(drv.device.vendorId, drv.device.productId, i) == portId }
        if (match == null) {
            onState?.invoke(TransportState.Failed("Radio not found on USB"))
            return
        }
        val (drv, index) = match
        onState?.invoke(TransportState.Connecting)
        if (manager.hasPermission(drv.device)) {
            openPort(drv, index)
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                unregister()
                if (closed) return
                if (manager.hasPermission(drv.device)) openPort(drv, index)
                else onState?.invoke(TransportState.Failed("USB permission denied"))
            }
        }
        receiver = r
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        manager.requestPermission(drv.device, pi)
    }

    private fun unregister() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    private fun openPort(drv: UsbSerialDriver, index: Int) {
        val connection = manager.openDevice(drv.device)
        if (connection == null) {
            onState?.invoke(TransportState.Failed("Cannot open USB device (in use by another app?)"))
            return
        }
        val p = drv.ports[index]
        try {
            p.open(connection)
            p.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Keep RTS/DTR low: some setups key PTT on them.
            try { p.setRTS(false) } catch (_: Exception) {}
            try { p.setDTR(false) } catch (_: Exception) {}
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            onState?.invoke(TransportState.Failed("Cannot open serial port: ${e.message ?: "error"}"))
            return
        }
        port = p
        val m = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                if (!closed) onData?.invoke(data.copyOf())
            }

            override fun onRunError(e: Exception) {
                if (!closed) onState?.invoke(TransportState.Failed("Radio disconnected"))
            }
        })
        io = m
        m.start()
        onState?.invoke(TransportState.Connected)
    }

    override fun close() {
        if (closed) return
        closed = true
        unregister()
        val m = io
        val p = port
        io = null
        port = null
        ioExec.execute {
            try { m?.stop() } catch (_: Exception) {}
            try { p?.close() } catch (_: Exception) {}
        }
        ioExec.shutdown()
        onState?.invoke(TransportState.Idle)
    }

    override fun write(data: ByteArray) {
        val p = port ?: return
        if (ioExec.isShutdown) return
        ioExec.execute { try { p.write(data, 500) } catch (_: Exception) {} }
    }
}
