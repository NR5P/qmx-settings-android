package com.qmxsettings.app.firmware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
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
import com.qmxsettings.app.radio.MyRadio
import com.qmxsettings.app.radio.QMX_QFU
import com.qmxsettings.app.radio.QfuSpec

/** What's on a QRP Labs QFU bootloader drive (QMX/QMX+: x.QMX + EEPROM; QDX: x.QDX + EEPROM.QDX). */
data class DriveInfo(val label: String, val ext: String?, val firmwareFile: String?, val eepromSize: Long?, val files: List<String>)

data class Op(val text: String, val fraction: Float? = null)

/**
 * Firmware update + EEPROM backup/restore through the QMX's QFU bootloader, which presents the radio
 * as a small USB flash drive holding the firmware file and an EEPROM file.
 */
class FirmwareController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The user's radio (model + PCB rev); decides file names, sizes, rules and firmware list. */
    private val radio: () -> MyRadio,
    private val notify: (String) -> Unit,
) {
    private val spec: QfuSpec get() = radio().model.qfu ?: QMX_QFU
    private val name get() = radio().model.label
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val backupDir = File(context.filesDir, "eeprom-backups").apply { mkdirs() }
    /** Firmware kept on the phone so it can be installed without internet (e.g. in the field). */
    private val fwDir = File(context.filesDir, "firmware").apply { mkdirs() }
    private val releasesCache get() = File(fwDir, "releases-${spec.ext}.json")
    private var loadedFor: String? = null

    var releases by mutableStateOf<List<FirmwareRelease>>(emptyList()); private set
    var releasesError by mutableStateOf<String?>(null); private set
    var image by mutableStateOf<FirmwareImage?>(null); private set
    var drive by mutableStateOf<DriveInfo?>(null); private set
    var driveError by mutableStateOf<String?>(null); private set
    var op by mutableStateOf<Op?>(null); private set
    var lastResult by mutableStateOf<String?>(null); private set
    val backups = mutableStateListOf<File>()
    /** Firmware images stored on the phone, newest version first. */
    val stored = mutableStateListOf<FirmwareImage>()
    /** True when [releases] came from the phone's copy because qrp-labs.com couldn't be reached. */
    var releasesOffline by mutableStateOf(false); private set

    val busy get() = op != null

    init { refreshBackups(); onRadioChanged() }

    /** Call when My radio changes: switches the firmware list and stored files to that radio's family. */
    fun onRadioChanged() {
        if (loadedFor == spec.ext) return
        loadedFor = spec.ext
        image = null; drive = null; driveError = null; releasesError = null
        refreshStored()
        releases = try { parseCache(releasesCache.readText()) } catch (_: Exception) { emptyList() }
        releasesOffline = releases.isNotEmpty()
    }

    private fun refreshStored() {
        stored.clear()
        val sp = spec
        stored += fwDir.listFiles { f -> f.name.endsWith(".${sp.ext}", true) }.orEmpty().mapNotNull {
            try { FirmwareCatalog.validate(FirmwareImage(it.name, it.readBytes()), sp) } catch (_: Exception) { it.delete(); null }
        }.sortedByDescending { FirmwareVersion.parse(it.fileName) }
    }

    private fun store(img: FirmwareImage) { File(fwDir, img.fileName).writeBytes(img.data); refreshStored() }

    fun deleteStored(img: FirmwareImage) { File(fwDir, img.fileName).delete(); if (image?.fileName == img.fileName) image = null; refreshStored() }

    fun select(img: FirmwareImage) { image = img; notify("Ready to install ${img.version}") }

    /** Official list entry for [img] (release notes, beta/stable, QMX+-only, factory reset). */
    fun releaseFor(img: FirmwareImage) = releases.firstOrNull { it.version == img.version }

    private fun toCache(list: List<FirmwareRelease>) = org.json.JSONArray(list.map {
        org.json.JSONObject().put("v", it.version).put("d", it.date).put("n", it.notes).put("u", it.url)
    }).toString()

    private fun parseCache(s: String): List<FirmwareRelease> {
        val a = org.json.JSONArray(s)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { FirmwareRelease(it.getString("v"), it.getString("d"), it.getString("n"), it.getString("u")) }
    }

    private fun refreshBackups() {
        backups.clear()
        backups += backupDir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
    }

    private fun run(start: String, block: suspend () -> Unit) {
        if (busy) { notify("Busy — wait for the current step to finish"); return }
        op = Op(start)
        scope.launch {
            try { block() } catch (e: Exception) { Log.e("QmxFw", "$start failed", e); notify(e.message ?: e.toString()); lastResult = "Failed: ${e.message ?: e}" } finally { op = null }
        }
    }

    // MARK: firmware source

    fun loadReleases() = run("Reading firmware list from qrp-labs.com") {
        releasesError = null
        val sp = spec
        if (radio().model.qfu == null) { releases = emptyList(); return@run }
        val fresh = try { withContext(Dispatchers.IO) { FirmwareCatalog.fetchReleases(sp) } } catch (e: Exception) {
            releasesError = "Couldn't reach qrp-labs.com (${e.message})" + if (releases.isNotEmpty()) " — showing the list saved on this phone." else ""
            null
        }
        if (fresh != null) {
            if (fresh.isEmpty()) { releasesError = "No ${sp.ext} firmware versions found on ${sp.pages.joinToString()}"; return@run }
            releases = fresh; releasesOffline = false
            withContext(Dispatchers.IO) { releasesCache.writeText(toCache(fresh)) }
            // Keep the latest stable version on the phone so an update works without internet.
            // QDX releases aren't marked stable/beta; QRP Labs says to use the latest, so keep that.
            (fresh.firstOrNull { it.stable && !it.qmxPlusOnly } ?: fresh.firstOrNull().takeIf { sp.ext == "QDX" })?.let { r ->
                if (stored.none { it.version == r.version }) {
                    op = Op("Saving ${r.version} (latest stable) to this phone")
                    try { val img = withContext(Dispatchers.IO) { FirmwareCatalog.download(r, sp) }; withContext(Dispatchers.IO) { store(img) } } catch (_: Exception) {}
                }
            }
        }
    }

    fun download(r: FirmwareRelease) = run("Downloading ${r.version}") {
        val img = stored.firstOrNull { it.version == r.version } ?: withContext(Dispatchers.IO) { FirmwareCatalog.download(r, spec).also { store(it) } }
        image = img
        notify("Ready to install ${img.version}")
    }

    fun useFile(name: String, bytes: ByteArray) = run("Checking $name") {
        val img = withContext(Dispatchers.Default) { FirmwareCatalog.fromFile(name, bytes, spec) }
        withContext(Dispatchers.IO) { store(img) }
        image = img
        notify("Ready to install ${img.version}")
    }

    // MARK: the bootloader drive

    /** USB drives currently attached, QRP Labs (STMicro VID / "QMX"/"QDX" name) first. */
    private fun candidates(): List<UsbDevice> = manager.deviceList.values.filter { UsbMassStorage.isMassStorage(it) }
        .sortedByDescending { it.vendorId == 0x0483 || Regex("QMX|QDX|QRP", RegexOption.IGNORE_CASE).containsMatchIn("${it.manufacturerName} ${it.productName}") }

    fun scan() = run("Looking for the $name update drive") { scanNow() }

    private suspend fun scanNow() {
        drive = null; driveError = null
        val d = candidates().firstOrNull()
        if (d == null) { driveError = "No $name update drive found. Put the radio in update mode (step 2) with the phone connected to its USB port."; return }
        drive = withDrive(d) { _, v -> describe(v, "${d.manufacturerName ?: ""} ${d.productName ?: d.deviceName}".trim()) }
    }

    private fun describe(v: FatVolume, label: String): DriveInfo {
        val files = v.files()
        Log.i("QmxFw", "FAT${v.fatBits} partitionStart=${v.partitionStart} cluster=${v.clusterBytes} files=${files.map { "${it.name}:${it.size}@${it.firstCluster}" }}")
        // Work out which family's drive this is from its files, so a mismatch with My radio is caught.
        val fw = files.firstOrNull { it.name.endsWith(".QMX", true) || it.name.endsWith(".QDX", true) }
        val ext = fw?.name?.substringAfterLast('.')?.uppercase()
        val eeName = if (ext == "QDX") "EEPROM.QDX" else "EEPROM"
        return DriveInfo(label, ext, fw?.name, files.firstOrNull { it.name.equals(eeName, true) }?.size, files.map { it.name })
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

    /** Refuses to touch any drive that isn't laid out like this radio family's bootloader drive. */
    private fun requireRadio(v: FatVolume): Pair<FatEntry, FatEntry> {
        val sp = spec
        val files = v.files()
        val other = if (sp.ext == "QMX") "QDX" else "QMX"
        if (files.any { it.name.endsWith(".$other", true) }) throw IOException("This is a $other update drive but My radio is set to $name. Change My radio on the Radio tab first.")
        val fw = files.firstOrNull { it.name.endsWith(".${sp.ext}", true) }
        val ee = files.firstOrNull { it.name.equals(sp.eepromName, true) }
        if (fw == null || ee == null) throw IOException("This USB drive doesn't look like a $name in update mode (expected a .${sp.ext} file and ${sp.eepromName}; found ${files.map { it.name }})")
        return fw to ee
    }

    private fun saveBackup(bytes: ByteArray, fwName: String): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(backupDir, InstallCheck.backupName(spec.ext, fwName, ts))
        f.writeBytes(bytes)
        refreshBackups()
        return f
    }

    fun backupEeprom() = run("Backing up EEPROM") {
        val d = candidates().firstOrNull() ?: throw IOException("$name update drive not found")
        val (bytes, fw) = withDrive(d) { _, v -> val (fw, ee) = requireRadio(v); v.read(ee) to fw.name }
        val f = saveBackup(bytes, fw)
        Log.i("QmxFw", "EEPROM backup ${bytes.size} bytes -> ${f.name}")
        lastResult = "EEPROM backed up (${bytes.size} bytes) to ${f.name}"
        notify(lastResult!!)
    }

    /** What to tell the user before installing [image] over what's on the radio's update drive. */
    fun installNotes(): List<InstallNote> {
        val img = image ?: return emptyList()
        val target = FirmwareVersion.parse(img.fileName) ?: return emptyList()
        val r = radio()
        return InstallCheck.notes(r.model, r.pcbRev, FirmwareVersion.parse(drive?.firmwareFile), target, releaseFor(img))
    }

    fun install() {
        val img = image ?: return notify("Choose a firmware version first")
        if (installNotes().any { it.level == InstallNote.Level.BLOCK }) return notify("This version can't be installed on this radio — see the notes")
        run("Preparing to install ${img.version}") {
            val d = candidates().firstOrNull() ?: throw IOException("$name update drive not found")
            val outcome = withDrive(d) { _, v ->
                val (fw, ee) = requireRadio(v)
                // Always keep a copy of the configuration before changing firmware.
                op = Op("Backing up EEPROM")
                val backup = saveBackup(v.read(ee), fw.name)
                op = Op("Writing ${img.fileName}", 0f)
                val total = img.data.size.toFloat()
                val o = v.write(img.fileName, img.data) { done -> op = Op("Writing ${img.fileName}", done / total) }
                o to backup
            }
            lastResult = when (outcome.first) {
                FatVolume.WriteOutcome.Complete -> (if (releaseFor(img)?.factoryReset == true) "This version factory-resets the radio: when it restarts, choose your band version on the radio. " else "") + "Firmware ${img.version} copied to the $name. It installs it and restarts by itself; then reconnect on the Radio tab to check the version. (EEPROM backup: ${outcome.second.name})"
                is FatVolume.WriteOutcome.DataWrittenThenDisconnected -> "All of ${img.version} was sent and the radio disconnected, which is what it does when it restarts into new firmware. Reconnect on the Radio tab to check the version. (EEPROM backup: ${outcome.second.name})"
            }
            notify("Firmware sent to the $name")
            delay(500); drive = null
        }
    }

    fun restoreEeprom(backup: File) = run("Restoring EEPROM") {
        val bytes = backup.readBytes()
        val d = candidates().firstOrNull() ?: throw IOException("$name update drive not found")
        val outcome = withDrive(d) { _, v ->
            val (fw, ee) = requireRadio(v)
            InstallCheck.restoreProblem(backup.name, bytes.size.toLong(), spec.ext, FirmwareVersion.parse(fw.name), ee.size)?.let { throw IOException("Not restoring: $it") }
            saveBackup(v.read(ee), fw.name) // safety copy of what's being replaced
            v.write(spec.eepromName, bytes)
        }
        lastResult = "EEPROM restored from ${backup.name}" + if (outcome is FatVolume.WriteOutcome.DataWrittenThenDisconnected) " (radio disconnected afterwards)" else ""
        notify(lastResult!!)
    }

    fun deleteBackup(f: File) { f.delete(); refreshBackups() }
}
