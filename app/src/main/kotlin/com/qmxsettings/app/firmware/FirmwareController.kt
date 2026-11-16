package com.qmxsettings.app.firmware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** What's on the QMX bootloader drive. */
data class DriveInfo(val label: String, val firmwareFile: String?, val eepromSize: Long?, val files: List<String>)

data class Op(val text: String, val fraction: Float? = null)

/**
 * Firmware update + EEPROM backup/restore through the QMX's QFU bootloader, which presents the radio
 * as a small USB flash drive holding the firmware file and an EEPROM file.
 */
class FirmwareController(private val context: Context, private val scope: CoroutineScope, private val notify: (String) -> Unit) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val backupDir = File(context.filesDir, "eeprom-backups").apply { mkdirs() }

    var releases by mutableStateOf<List<FirmwareRelease>>(emptyList()); private set
    var releasesError by mutableStateOf<String?>(null); private set
    var image by mutableStateOf<FirmwareImage?>(null); private set
    var drive by mutableStateOf<DriveInfo?>(null); private set
    var driveError by mutableStateOf<String?>(null); private set
    var op by mutableStateOf<Op?>(null); private set
    var lastResult by mutableStateOf<String?>(null); private set
    val backups = mutableStateListOf<File>()

    val busy get() = op != null

    init { refreshBackups() }

    private fun refreshBackups() {
        backups.clear()
        backups += backupDir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
    }

    private fun run(start: String, block: suspend () -> Unit) {
        if (busy) { notify("Busy — wait for the current step to finish"); return }
        op = Op(start)
        scope.launch {
            try { block() } catch (e: Exception) { notify(e.message ?: e.toString()); lastResult = "Failed: ${e.message ?: e}" } finally { op = null }
        }
    }

    // MARK: firmware source

    fun loadReleases() = run("Reading firmware list from qrp-labs.com") {
        releasesError = null
        releases = try { withContext(Dispatchers.IO) { FirmwareCatalog.fetchReleases() } } catch (e: Exception) {
            releasesError = "Couldn't read ${FirmwareCatalog.PAGE}: ${e.message}"; emptyList()
        }
        if (releases.isEmpty() && releasesError == null) releasesError = "No firmware versions found on ${FirmwareCatalog.PAGE}"
    }

    fun download(r: FirmwareRelease) = run("Downloading ${r.version}") {
        image = withContext(Dispatchers.IO) { FirmwareCatalog.download(r) }
        notify("Ready to install ${image!!.version}")
    }

    fun useFile(name: String, bytes: ByteArray) = run("Checking $name") {
        image = withContext(Dispatchers.Default) { FirmwareCatalog.fromFile(name, bytes) }
        notify("Ready to install ${image!!.version}")
    }

    // MARK: the bootloader drive

    /** USB drives currently attached, QRP Labs (STMicro VID / "QMX" name) first. */
    private fun candidates(): List<UsbDevice> = manager.deviceList.values.filter { UsbMassStorage.isMassStorage(it) }
        .sortedByDescending { it.vendorId == 0x0483 || "${it.manufacturerName} ${it.productName}".contains("QMX", true) || "${it.productName}".contains("QRP", true) }

    fun scan() = run("Looking for the QMX update drive") { scanNow() }

    private suspend fun scanNow() {
        drive = null; driveError = null
        val d = candidates().firstOrNull()
        if (d == null) { driveError = "No QMX update drive found. Put the radio in update mode (step 2) with the phone connected to its USB port."; return }
        drive = withDrive(d) { _, v -> describe(v, "${d.manufacturerName ?: ""} ${d.productName ?: d.deviceName}".trim()) }
    }

    private fun describe(v: FatVolume, label: String): DriveInfo {
        val files = v.files()
        return DriveInfo(label, files.firstOrNull { it.name.endsWith(".QMX", true) }?.name, files.firstOrNull { it.name.equals("EEPROM", true) }?.size, files.map { it.name })
    }

    private suspend fun permission(d: UsbDevice): Boolean {
        if (manager.hasPermission(d)) return true
        val action = "${context.packageName}.USB_DRIVE_PERMISSION"
        return suspendCancellableCoroutine { cont ->
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(manager.hasPermission(d))
                }
            }
            ContextCompat.registerReceiver(context, r, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(d, PendingIntent.getBroadcast(context, 1, Intent(action).setPackage(context.packageName), flags))
            cont.invokeOnCancellation { try { context.unregisterReceiver(r) } catch (_: Exception) {} }
        }
    }

    private suspend fun <T> withDrive(d: UsbDevice, block: (UsbMassStorage, FatVolume) -> T): T {
        if (!permission(d)) throw IOException("USB permission denied")
        return withContext(Dispatchers.IO) {
            UsbMassStorage.open(manager, d).use { ms -> block(ms, FatVolume(ms)) }
        }
    }

    /** Refuses to touch any drive that isn't laid out like the QMX bootloader drive. */
    private fun requireQmx(v: FatVolume): Pair<FatEntry, FatEntry> {
        val files = v.files()
        val fw = files.firstOrNull { it.name.endsWith(".QMX", true) }
        val ee = files.firstOrNull { it.name.equals("EEPROM", true) }
        if (fw == null || ee == null) throw IOException("This USB drive doesn't look like a QMX in update mode (expected a .QMX file and EEPROM; found ${files.map { it.name }})")
        return fw to ee
    }

    private fun saveBackup(bytes: ByteArray, fwName: String): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(backupDir, "EEPROM-${fwName.substringBeforeLast('.')}-$ts.bin")
        f.writeBytes(bytes)
        refreshBackups()
        return f
    }

    fun backupEeprom() = run("Backing up EEPROM") {
        val d = candidates().firstOrNull() ?: throw IOException("QMX update drive not found")
        val (bytes, fw) = withDrive(d) { _, v -> val (fw, ee) = requireQmx(v); v.read(ee) to fw.name }
        val f = saveBackup(bytes, fw)
        lastResult = "EEPROM backed up (${bytes.size} bytes) to ${f.name}"
        notify(lastResult!!)
    }

    fun install() {
        val img = image ?: return notify("Choose a firmware version first")
        run("Preparing to install ${img.version}") {
            val d = candidates().firstOrNull() ?: throw IOException("QMX update drive not found")
            val outcome = withDrive(d) { _, v ->
                val (fw, ee) = requireQmx(v)
                // Always keep a copy of the configuration before changing firmware.
                op = Op("Backing up EEPROM")
                val backup = saveBackup(v.read(ee), fw.name)
                op = Op("Writing ${img.fileName}", 0f)
                val total = img.data.size.toFloat()
                val o = v.write(img.fileName, img.data) { done -> op = Op("Writing ${img.fileName}", done / total) }
                o to backup
            }
            lastResult = when (outcome.first) {
                FatVolume.WriteOutcome.Complete -> "Firmware ${img.version} copied to the QMX. It installs it and restarts by itself; then reconnect on the Radio tab to check the version. (EEPROM backup: ${outcome.second.name})"
                is FatVolume.WriteOutcome.DataWrittenThenDisconnected -> "All of ${img.version} was sent and the radio disconnected, which is what it does when it restarts into new firmware. Reconnect on the Radio tab to check the version. (EEPROM backup: ${outcome.second.name})"
            }
            notify("Firmware sent to the QMX")
            delay(500); drive = null
        }
    }

    fun restoreEeprom(backup: File) = run("Restoring EEPROM") {
        val bytes = backup.readBytes()
        val d = candidates().firstOrNull() ?: throw IOException("QMX update drive not found")
        val outcome = withDrive(d) { _, v ->
            val (fw, ee) = requireQmx(v)
            if (ee.size != bytes.size.toLong()) throw IOException("Backup is ${bytes.size} bytes but this radio's EEPROM is ${ee.size} bytes — not restoring")
            saveBackup(v.read(ee), fw.name) // safety copy of what's being replaced
            v.write("EEPROM", bytes)
        }
        lastResult = "EEPROM restored from ${backup.name}" + if (outcome is FatVolume.WriteOutcome.DataWrittenThenDisconnected) " (radio disconnected afterwards)" else ""
        notify(lastResult!!)
    }

    fun deleteBackup(f: File) { f.delete(); refreshBackups() }
}
