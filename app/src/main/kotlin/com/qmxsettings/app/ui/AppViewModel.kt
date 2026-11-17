package com.qmxsettings.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qmxsettings.app.cat.ByteTransport
import com.qmxsettings.app.cat.CatClient
import com.qmxsettings.app.cat.CatLogLine
import com.qmxsettings.app.cat.CatParam
import com.qmxsettings.app.cat.CatParams
import com.qmxsettings.app.cat.CatReply
import com.qmxsettings.app.cat.DemoRadio
import com.qmxsettings.app.cat.RadioFamily
import com.qmxsettings.app.cat.TransportState
import com.qmxsettings.app.cat.UsbPortInfo
import com.qmxsettings.app.cat.UsbSerialTransport
import com.qmxsettings.app.menu.DiscoveryProgress
import com.qmxsettings.app.menu.MenuNode
import com.qmxsettings.app.menu.MenuRepository
import com.qmxsettings.app.menu.MenuTree
import com.qmxsettings.app.profiles.EntryKind
import com.qmxsettings.app.profiles.PlannedChange
import com.qmxsettings.app.profiles.Profile
import com.qmxsettings.app.profiles.ProfileEntry
import com.qmxsettings.app.profiles.ProfileStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

enum class LinkMode { NONE, DEMO, USB }

sealed class ConnState {
    data object Disconnected : ConnState()
    data object Connecting : ConnState()
    data object Connected : ConnState()
    data class Failed(val message: String) : ConnState()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val demoJson: String by lazy { app.assets.open("qmx_menu_demo.json").bufferedReader().use { it.readText() } }

    /** Short descriptions per menu key, from the operating manual (via the demo file). */
    val help: Map<String, String> by lazy {
        val out = mutableMapOf<String, String>()
        fun walk(a: JSONArray, prefix: List<String>) {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val names = prefix + o.getString("name")
                o.optString("help").takeIf { it.isNotBlank() }?.let { out[names.joinToString(" › ").lowercase()] = it }
                o.optJSONArray("children")?.let { walk(it, names) }
            }
        }
        try { walk(JSONObject(demoJson).getJSONArray("menu"), emptyList()) } catch (_: Exception) {}
        out
    }

    private val prefs = app.getSharedPreferences("radio", android.content.Context.MODE_PRIVATE)

    /** What the user told us about their radio (model, PCB rev, band version); saved on the phone. */
    var myRadio by mutableStateOf(loadMyRadio()); private set
    /** Radio family detected from the connected radio's replies (null = not connected / unknown). */
    var detectedFamily by mutableStateOf<RadioFamily?>(null); private set

    private fun loadMyRadio(): com.qmxsettings.app.radio.MyRadio {
        val m = prefs.getString("model", null)?.let { n -> com.qmxsettings.app.radio.RadioModel.entries.firstOrNull { it.name == n } }
            ?: com.qmxsettings.app.radio.RadioModel.QMX
        return com.qmxsettings.app.radio.MyRadio(m, prefs.getString("rev", null), prefs.getString("band", null))
    }

    fun updateMyRadio(r: com.qmxsettings.app.radio.MyRadio) {
        val clean = r.copy(
            pcbRev = r.pcbRev?.takeIf { it in r.model.pcbRevs },
            bandVersion = r.bandVersion?.takeIf { it in r.model.bandVersions } ?: r.model.bandVersions.singleOrNull(),
        )
        myRadio = clean
        prefs.edit().putString("model", clean.model.name).putString("rev", clean.pcbRev).putString("band", clean.bandVersion).apply()
        if (!connected) family = clean.model.family
        fw.onRadioChanged()
    }

    /** Firmware update and EEPROM backup via the radio's QFU bootloader drive. */
    val fw = com.qmxsettings.app.firmware.FirmwareController(app, viewModelScope, { myRadio }) { message = it }

    private val store = ProfileStore(File(app.filesDir, "profiles"))

    var mode by mutableStateOf(LinkMode.NONE); private set
    var conn by mutableStateOf<ConnState>(ConnState.Disconnected); private set
    var ports by mutableStateOf<List<UsbPortInfo>>(emptyList()); private set
    var family by mutableStateOf(RadioFamily.QMX)

    var firmware by mutableStateOf<String?>(null); private set
    var menuManager by mutableStateOf(false); private set
    var tree by mutableStateOf<MenuTree?>(null); private set
    /** Bumped whenever node values change (nodes are plain mutable objects). */
    var treeVersion by mutableIntStateOf(0); private set
    var progress by mutableStateOf<DiscoveryProgress?>(null); private set
    val catValues = mutableStateMapOf<String, String?>()
    /** CAT codes the radio answered with "?" (command not in this firmware). */
    val unsupported = mutableStateListOf<String>()
    val profiles = mutableStateListOf<Profile>()
    val log = mutableStateListOf<CatLogLine>()
    var message by mutableStateOf<String?>(null)
    /** Send MU; after each single edit so it takes effect even with "MM Effect" = On demand. */
    var reloadAfterEdit by mutableStateOf(true)

    private var transport: ByteTransport? = null
    private var cat: CatClient? = null
    private var repo: MenuRepository? = null
    private var logJob: Job? = null
    private var work: Job? = null

    init {
        family = myRadio.model.family
        profiles += store.all()
        refreshPorts()
    }

    val connected get() = conn == ConnState.Connected
    val busy get() = progress != null

    fun refreshPorts() {
        ports = UsbSerialTransport.availablePorts(getApplication())
    }

    // MARK: - Connection

    fun connectDemo() = connect(LinkMode.DEMO, DemoRadio(demoJson))

    /** Opens the USB port. Only ever called from an explicit Connect tap. */
    fun connectUsb(port: UsbPortInfo) = connect(LinkMode.USB, UsbSerialTransport(getApplication(), port.id, myRadio.model.baud))

    private fun connect(m: LinkMode, t: ByteTransport) {
        disconnect()
        mode = m
        conn = ConnState.Connecting
        transport = t
        val c = CatClient(t)
        cat = c
        repo = MenuRepository(c)
        logJob = viewModelScope.launch {
            c.log.collect { line ->
                log += line
                if (log.size > 600) log.removeRange(0, log.size - 500)
            }
        }
        viewModelScope.launch {
            val ok = suspendCancellableCoroutine { cont ->
                t.onState = { s ->
                    viewModelScope.launch {
                        when (s) {
                            TransportState.Connected -> { if (cont.isActive) cont.resume(true) }
                            is TransportState.Failed -> {
                                if (cont.isActive) cont.resume(false)
                                conn = ConnState.Failed(s.message)
                            }
                            else -> {}
                        }
                    }
                }
                t.open()
            }
            if (!ok) return@launch
            conn = ConnState.Connected
            identify()
        }
    }

    fun disconnect() {
        work?.cancel()
        logJob?.cancel()
        transport?.onState = null
        transport?.close()
        transport = null
        cat = null
        repo = null
        mode = LinkMode.NONE
        conn = ConnState.Disconnected
        firmware = null
        menuManager = false
        detectedFamily = null
        tree = null
        progress = null
        catValues.clear()
        unsupported.clear()
    }

    private suspend fun identify() {
        val c = cat ?: return
        progress = DiscoveryProgress("Identifying radio", 0, null)
        val vn = (c.query("VN;") as? CatReply.Ok)?.frame?.removePrefix("VN")
        firmware = vn
        // QMX/QMX+ answer VN1_04_010QMX; QDX answers VN1_12; (no suffix); QCX has no VN command but answers ID020;.
        detectedFamily = when {
            vn?.contains("QMX", ignoreCase = true) == true -> RadioFamily.QMX
            vn?.contains("QDX", ignoreCase = true) == true || (vn != null && Regex("""^\d+_\d{2}[a-z]?$""").matches(vn)) -> RadioFamily.QDX
            vn == null && (c.query("ID;") as? CatReply.Ok)?.frame == "ID020" -> RadioFamily.QCX
            else -> null
        }
        family = detectedFamily ?: myRadio.model.family
        if (detectedFamily != null && myRadio.model.family != detectedFamily) {
            // The radio says it's a different family than My radio: switch so firmware rules and commands are right.
            val m = com.qmxsettings.app.radio.RadioModel.forFamily(detectedFamily!!).first()
            updateMyRadio(com.qmxsettings.app.radio.MyRadio(m))
            message = "Detected a ${detectedFamily!!.label} radio — check My radio on the Radio tab"
        }
        menuManager = family == RadioFamily.QMX && repo?.supportsMenuManager() == true
        progress = null
        if (menuManager) loadMenu() else readCat()
    }

    private fun launchWork(block: suspend () -> Unit) {
        if (work?.isActive == true) { message = "Busy — wait for the current operation"; return }
        work = viewModelScope.launch {
            try { block() } finally { progress = null }
        }
    }

    // MARK: - Menu Manager

    fun loadMenu() = launchWork {
        val r = repo ?: return@launchWork
        val t = r.discover(firmware) { progress = it }
        tree = t
        r.readAll(t.roots) { progress = it }
        treeVersion++
        message = "Read ${t.settingCount()} settings"
        // QMX reports its band version (System config › Band version, read-only); use it to fill in My radio.
        t.all().firstOrNull { it.name.equals("Band version", true) }?.value?.trim()?.let { bv ->
            val m = myRadio.model
            val match = com.qmxsettings.app.radio.RadioModel.forFamily(RadioFamily.QMX).firstOrNull { mm -> mm.bandVersions.any { it.equals(bv, true) } }
            if (match != null && (myRadio.bandVersion == null || m != match))
                updateMyRadio(myRadio.copy(model = match, bandVersion = match.bandVersions.first { it.equals(bv, true) }))
        }
    }

    fun refresh(node: MenuNode) = launchWork {
        repo?.readAll(listOf(node)) { progress = it }
        treeVersion++
    }

    fun write(node: MenuNode, value: String, column: Int?) = launchWork {
        val r = repo ?: return@launchWork
        val t = tree ?: return@launchWork
        progress = DiscoveryProgress("Writing ${node.name}", 0, null)
        val res = r.write(node, value, t.lists, column)
        if (res.ok && reloadAfterEdit && hasMu) r.reload()
        treeVersion++
        message = res.message
    }

    // MARK: - Two-letter CAT parameters

    /** Parsed firmware version of the connected radio (null for unknown / QDX). */
    val fwVersion get() = if (family == RadioFamily.QMX) com.qmxsettings.app.firmware.FirmwareVersion.parse(firmware) else null
    /** CAT commands that exist on this radio's firmware. */
    val params: List<CatParam> get() = CatParams.forRadio(family, fwVersion)
    /** MU (config reload) exists from firmware 1_04_000; on older Menu Manager firmware edits apply immediately. */
    private val hasMu get() = fwVersion?.let { it >= com.qmxsettings.app.firmware.FirmwareVersion.V1_04 } ?: true

    fun readCat() = launchWork {
        val c = cat ?: return@launchWork
        val list = params
        for ((i, p) in list.withIndex()) {
            progress = DiscoveryProgress("Reading ${p.label}", i, list.size)
            val r = c.query(p.getCmd, p.replyPrefix)
            catValues[p.code] = (r as? CatReply.Ok)?.let { p.parseReply(it.frame) }
            if (r is CatReply.Error) { if (p.code !in unsupported) unsupported += p.code } else unsupported -= p.code
        }
    }

    fun writeCat(p: CatParam, value: String) = launchWork {
        val c = cat ?: return@launchWork
        progress = DiscoveryProgress("Setting ${p.label}", 0, null)
        val r = c.command(p.setCommand(value))
        val back = (c.query(p.getCmd, p.replyPrefix) as? CatReply.Ok)?.let { p.parseReply(it.frame) }
        catValues[p.code] = back
        message = if (r is CatReply.Error) "Radio rejected ${p.label}" else "${p.label}: ${p.display(back)}"
    }

    fun syncClock() = launchWork {
        val c = cat ?: return@launchWork
        val t = SimpleDateFormat("HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val r = c.command("TM$t;")
        message = if (r is CatReply.Error) "Radio rejected the time" else "Radio clock set to UTC ${t.chunked(2).joinToString(":")}"
    }

    // MARK: - Console

    fun sendRaw(text: String) {
        val c = cat ?: return
        val cmds = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        viewModelScope.launch {
            for (cmd in cmds) {
                if (cmd.any { it == '\r' || it == '\n' }) continue
                c.raw("$cmd;")
                delay(30)
            }
        }
    }

    // MARK: - Profiles

    /** Top-level menu sections that hold settings (for the "save profile" picker). */
    fun sections(): List<MenuNode> = tree?.roots?.filter { it.settings().any { s -> s.type.editable } } ?: emptyList()

    fun createProfile(name: String, notes: String, sectionKeys: Set<String>, catCodes: Set<String>) {
        val entries = mutableListOf<ProfileEntry>()
        tree?.roots?.filter { it.key in sectionKeys }?.forEach { root ->
            for (n in root.settings()) {
                if (!n.type.editable) continue
                val cols = n.gridColumns
                if (cols != null) {
                    n.values?.forEachIndexed { c, v -> if (v != null) entries += ProfileEntry(EntryKind.MENU, n.key, v, c, "${n.key} [${c + 1}]") }
                } else n.value?.let { entries += ProfileEntry(EntryKind.MENU, n.key, it, null, n.key) }
            }
        }
        for (p in params) {
            if (p.code in catCodes && !p.readOnly) catValues[p.code]?.let { entries += ProfileEntry(EntryKind.CAT, p.code, it, null, p.label) }
        }
        if (entries.isEmpty()) { message = "Nothing to save — read the radio first"; return }
        val prof = Profile(name = name.ifBlank { "Profile" }, notes = notes, radio = family.label, firmware = firmware ?: "", entries = entries)
        store.save(prof)
        profiles.removeAll { it.id == prof.id }
        profiles += prof
        profiles.sortBy { it.name.lowercase() }
        message = "Saved \"${prof.name}\" (${entries.size} settings)"
    }

    fun deleteProfile(p: Profile) {
        store.delete(p)
        profiles.removeAll { it.id == p.id }
    }

    fun renameProfile(p: Profile, name: String, notes: String) {
        val np = p.copy(name = name, notes = notes)
        store.save(np)
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i >= 0) profiles[i] = np
    }

    fun exportJson(p: Profile): String = p.toJson().toString(2)

    fun importJson(text: String) {
        try {
            val p = Profile.fromJson(JSONObject(text), newId = true)
            store.save(p)
            profiles += p
            profiles.sortBy { it.name.lowercase() }
            message = "Imported \"${p.name}\""
        } catch (e: Exception) {
            message = "Not a valid profile file"
        }
    }

    /** Compares a profile against the values last read from the radio. */
    fun plan(p: Profile): List<PlannedChange> = p.entries.map { e ->
        when (e.kind) {
            EntryKind.MENU -> {
                val n = tree?.find(e.key)
                val cur = if (e.column != null) n?.values?.getOrNull(e.column) else n?.value
                PlannedChange(e, cur, n != null && n.type.editable)
            }
            EntryKind.CAT -> {
                val p2 = params.firstOrNull { it.code == e.key }
                PlannedChange(e, catValues[e.key], p2 != null && !p2.readOnly)
            }
        }
    }

    fun apply(changes: List<PlannedChange>) = launchWork {
        val r = repo
        val c = cat ?: return@launchWork
        val t = tree
        var ok = 0
        val failed = mutableListOf<String>()
        val todo = changes.filter { it.found && it.changes }
        for ((i, ch) in todo.withIndex()) {
            val e = ch.entry
            progress = DiscoveryProgress("Writing ${e.label}", i, todo.size)
            when (e.kind) {
                EntryKind.MENU -> {
                    val n = t?.find(e.key)
                    if (r == null || n == null) { failed += e.label; continue }
                    if (r.write(n, e.value, t.lists, e.column).ok) ok++ else failed += e.label
                }
                EntryKind.CAT -> {
                    val p = params.firstOrNull { it.code == e.key } ?: run { failed += e.label; null } ?: continue
                    // The radio doesn't range-check (e.g. KS999 is stored as 231), so never send an invalid value.
                    if (p.validate(e.value) != null) { failed += e.label; continue }
                    val res = c.command(p.setCommand(e.value))
                    val back = (c.query(p.getCmd, p.replyPrefix) as? CatReply.Ok)?.let { p.parseReply(it.frame) }
                    catValues[p.code] = back
                    if (res !is CatReply.Error && back != null && PlannedChange.same(back, e.value)) ok++ else failed += e.label
                }
            }
        }
        if (r != null && hasMu && todo.any { it.entry.kind == EntryKind.MENU }) r.reload()
        treeVersion++
        message = if (failed.isEmpty()) "Applied $ok change(s)" else "Applied $ok, failed ${failed.size}: ${failed.take(3).joinToString()}"
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
