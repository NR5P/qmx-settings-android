package com.qmxsettings.app

import com.fazecast.jSerialComm.SerialPort
import com.qmxsettings.app.cat.ByteTransport
import com.qmxsettings.app.cat.CatClient
import com.qmxsettings.app.cat.CatParams
import com.qmxsettings.app.cat.CatReply
import com.qmxsettings.app.cat.RadioFamily
import com.qmxsettings.app.cat.TransportState
import com.qmxsettings.app.menu.MenuRepository
import com.qmxsettings.app.menu.MenuTree
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Mac/PC serial transport so the app's CAT code can be exercised against a real radio. */
class JvmSerialTransport(private val name: String) : ByteTransport {
    override var onData: ((ByteArray) -> Unit)? = null
    override var onState: ((TransportState) -> Unit)? = null
    private var port: SerialPort? = null
    @Volatile private var running = false
    override fun open() {
        val p = SerialPort.getCommPort(name)
        p.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0)
        check(p.openPort()) { "Could not open $name" }
        port = p; running = true
        Thread {
            val buf = ByteArray(512)
            while (running) {
                val n = p.readBytes(buf, buf.size)
                if (n > 0) onData?.invoke(buf.copyOf(n))
            }
        }.apply { isDaemon = true }.start()
        onState?.invoke(TransportState.Connected)
    }
    override fun close() { running = false; Thread.sleep(150); port?.closePort() }
    override fun write(data: ByteArray) { port!!.writeBytes(data, data.size) }
}

/**
 * Runs only when QMX_PORT is set, e.g. QMX_PORT=/dev/cu.usbmodem1101 ./gradlew testDebugUnitTest --tests '*RealRadio*'
 * QMX_PHASE: "read" (default) = read-only snapshot.
 */
class RealRadioTest {
    private val portName = System.getenv("QMX_PORT")
    private val outDir = File(System.getenv("QMX_OUT") ?: "../radio-test-logs").apply { mkdirs() }

    private fun log(s: String) { println(s); File(outDir, "run.log").appendText(s + "\n") }

    private fun snapshot(tree: MenuTree): JSONObject {
        val o = JSONObject()
        for (n in tree.all()) if (n.hasValue) {
            if (n.gridColumns != null) n.values?.forEachIndexed { c, v -> o.put("${n.key}[$c]", v ?: JSONObject.NULL) }
            else o.put(n.key, n.value ?: JSONObject.NULL)
        }
        return o
    }

    @Test fun readOnlySnapshot() = runBlocking {
        assumeTrue("set QMX_PORT to run", portName != null)
        val t = JvmSerialTransport(portName!!)
        val cat = CatClient(t)
        t.open()
        val logJob = launchLog(cat, File(outDir, "traffic.log"))
        try {
            val start = System.currentTimeMillis()
            val vn = (cat.query("VN;") as? CatReply.Ok)?.frame
            log("VN reply: $vn")
            val repo = MenuRepository(cat)
            log("Menu Manager: ${repo.supportsMenuManager()}")
            val tree = repo.discover(vn) { }
            log("Discovered roots: ${tree.roots.map { it.name }}")
            log("Lists: ${tree.lists.size} types; all nodes: ${tree.all().count()}")
            val t0 = System.currentTimeMillis()
            repo.readAll(tree.roots)
            log("Read all in ${System.currentTimeMillis() - t0} ms; settings=${tree.settingCount()}")
            val failed = tree.all().filter { it.hasValue && it.readError }.map { it.key }.toList()
            log("Read errors (${failed.size}): $failed")
            val catVals = JSONObject()
            for (p in CatParams.forFamily(RadioFamily.QMX)) {
                val r = cat.query(p.getCmd, p.replyPrefix)
                val v = (r as? CatReply.Ok)?.let { p.parseReply(it.frame) }
                catVals.put(p.code, v ?: JSONObject.NULL)
                log("CAT ${p.code} (${p.label}): $r -> ${p.display(v)}")
            }
            val snap = JSONObject().put("firmware", vn).put("menu", snapshot(tree)).put("cat", catVals)
                .put("lists", JSONObject(tree.lists.mapKeys { it.key.toString() }))
            val tree2 = JSONObject()
            for (n in tree.all()) tree2.put(n.key, "${n.type} p=${n.param} cols=${n.columns} idx=${n.indexPath} path=${n.path}")
            snap.put("structure", tree2)
            File(outDir, "../radio-backup-${System.currentTimeMillis()}.json").writeText(snap.toString(2))
            log("Total ${System.currentTimeMillis() - start} ms")
        } finally {
            logJob.cancel(); t.close()
        }
    }

    /** Session-only writes (not EEPROM), each read back and restored; then full compare with the backup. */
    @Test fun sessionWritesAndRestore() = runBlocking {
        assumeTrue("set QMX_PORT and QMX_PHASE=write to run", portName != null && System.getenv("QMX_PHASE") == "write")
        val backup = File("..").listFiles { f -> f.name.startsWith("radio-backup-") }!!.maxBy { it.name }
        val orig = JSONObject(backup.readText()).getJSONObject("cat")
        log("Using backup ${backup.name}")
        val t = JvmSerialTransport(portName!!)
        val cat = CatClient(t)
        t.open()
        val logJob = launchLog(cat, File(outDir, "traffic-write.log"))
        val params = CatParams.forFamily(RadioFamily.QMX).associateBy { it.code }
        suspend fun get(code: String): String? { val p = params[code]!!; return (cat.query(p.getCmd, p.replyPrefix) as? CatReply.Ok)?.let { p.parseReply(it.frame) } }
        suspend fun set(code: String, v: String): CatReply = cat.command(params[code]!!.setCommand(v))
        val failures = mutableListOf<String>()
        try {
            for ((code, newVal) in listOf("KS" to "12", "AG" to "60", "FA" to "7101000", "MD" to "3")) {
                val before = get(code)
                val r = set(code, newVal)
                val after = get(code)
                val ok = r !is CatReply.Error && after != null && com.qmxsettings.app.profiles.PlannedChange.same(after, newVal)
                log("WRITE $code: $before -> sent $newVal ($r) -> read $after  ${if (ok) "OK" else "FAIL"}")
                if (!ok) failures += code
                val rr = set(code, before!!)
                log("  restore $code=$before ($rr) -> ${get(code)}")
            }
            // Invalid value handling: radio should reject or clamp; restore either way.
            val ks = get("KS")!!
            val bad = set("KS", "999")
            log("INVALID KS999 -> $bad, now ${get("KS")}")
            set("KS", ks)
            // Unknown command is answered with '?' and doesn't wedge the parser.
            log("UNKNOWN ZZ; -> ${cat.query("ZZ;", "ZZ")}; VN after -> ${cat.query("VN;")}")
        } finally {
            // Final check: every value that was readable must match the backup; restore any that don't.
            for ((code, p) in params) {
                val want = orig.opt(code).takeIf { it != JSONObject.NULL } as String? ?: continue
                var now = get(code)
                if (now != want && !p.readOnly) { set(code, want); now = get(code) }
                log("FINAL $code: backup=$want now=$now ${if (now == want) "match" else "MISMATCH"}")
                if (now != want) failures += "final:$code"
            }
            logJob.cancel(); t.close()
        }
        log("Failures: $failures")
        org.junit.Assert.assertTrue("failures: $failures", failures.isEmpty())
    }

}

private fun kotlinx.coroutines.CoroutineScope.launchLog(cat: CatClient, f: File) = launch {
    cat.log.collect { f.appendText((if (it.outgoing) "> " else "< ") + it.text + "\n") }
}
