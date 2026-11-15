package com.qmxsettings.app.cat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * Offline QMX simulator. Answers the same CAT commands a real QMX does (Menu Manager
 * MM/ML/MU plus the common two-letter and Q commands), driven by a menu tree built from the
 * QMX operating manual (assets/qmx_menu_demo.json). Nothing touches USB.
 */
class DemoRadio(menuJson: String) : ByteTransport {
    override var onData: ((ByteArray) -> Unit)? = null
    override var onState: ((TransportState) -> Unit)? = null

    private class Node(
        val name: String,
        val type: Int,
        val param: Int,
        val columns: Int?,
        var value: String,
        val values: MutableList<String>?,
        val children: MutableList<Node> = mutableListOf(),
    )

    private val firmware: String
    private val lists = mutableMapOf<Int, List<String>>()
    private val roots: List<Node>
    private val pending = StringBuilder()
    private val timer = Timer("qmx.demo", true)
    @Volatile private var open = false

    // Two-letter command state.
    private val cat = mutableMapOf(
        "FA" to "00014074000", "FB" to "00007030000", "MD" to "6", "AG" to "091", "RG" to "054",
        "KS" to "18", "TR" to "4", "RR" to "6", "SP" to "0", "RT" to "0", "AI" to "0", "SS" to "0",
        "FR" to "0", "FT" to "0", "BN" to "5", "TQ" to "0", "QB" to "1", "QC" to "30", "QJ" to "50",
        "Q0" to "25000000", "Q1" to "0", "Q3" to "0", "Q4" to "80", "Q5" to "60", "Q6" to "4",
        "Q7" to "2", "Q8" to "5", "Q9" to "0", "QA" to "0",
    )
    private var rit = 0

    init {
        val o = JSONObject(menuJson)
        firmware = o.optString("firmware", "1_04_003")
        val l = o.getJSONObject("lists")
        for (k in l.keys()) {
            val a = l.getJSONArray(k)
            lists[k.toInt()] = List(a.length()) { a.getString(it) }
        }
        roots = parse(o.getJSONArray("menu"))
    }

    private fun parse(a: JSONArray): List<Node> = List(a.length()) { i ->
        val j = a.getJSONObject(i)
        val type = j.getInt("type")
        val param = when (type) {
            5, 7 -> j.optInt("list", 0)
            2, 3 -> j.optInt("len", 0)
            else -> 0
        }
        val vals = j.optJSONArray("values")?.let { v -> MutableList(v.length()) { v.optString(it) } }
        Node(j.getString("name"), type, param, j.optInt("columns", 0).takeIf { it > 0 },
            j.optString("value", ""), vals).also { n ->
            j.optJSONArray("children")?.let { n.children += parse(it) }
        }
    }

    override fun open() {
        open = true
        onState?.invoke(TransportState.Connecting)
        timer.schedule(150) { onState?.invoke(TransportState.Connected) }
    }

    override fun close() {
        open = false
        timer.cancel()
        onState?.invoke(TransportState.Idle)
    }

    override fun write(data: ByteArray) {
        if (!open) return
        val replies = StringBuilder()
        synchronized(pending) {
            for (b in data) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == ';') {
                    handle(pending.toString())?.let { replies.append(it).append(';') }
                    pending.setLength(0)
                } else pending.append(c)
            }
        }
        if (replies.isNotEmpty()) {
            val out = replies.toString().toByteArray(Charsets.ISO_8859_1)
            // Small delay, like a real USB round trip.
            timer.schedule(8) { if (open) onData?.invoke(out) }
        }
    }

    // MARK: - Menu Manager

    /** Resolves a path ("Audio|AGC settings|0") to a node, plus an optional [column]. */
    private fun resolve(path: String): Pair<Node, Int?>? {
        var p = path
        var col: Int? = null
        Regex("""^(.*)\[(\d+)]$""").matchEntire(p)?.let { col = it.groupValues[2].toInt(); p = it.groupValues[1] }
        var level = roots
        var node: Node? = null
        for (seg in p.split('|')) {
            val idx = seg.toIntOrNull()
            node = if (idx != null) level.getOrNull(idx) else level.firstOrNull { it.name.equals(seg, ignoreCase = true) }
            if (node == null) return null
            level = node.children
        }
        return node?.let { it to col }
    }

    private fun mm(body: String): String {
        if (body.endsWith("?")) {
            val n = resolve(body.dropLast(1))?.first ?: return "?"
            val name = n.name + (n.columns?.let { "[$it]" } ?: "")
            return "MM${n.type}|${n.param}|$name"
        }
        val eq = body.indexOf('=')
        if (eq >= 0) {
            val (n, col) = resolve(body.substring(0, eq)) ?: return "?"
            var v = body.substring(eq + 1)
            if (n.type in setOf(0, 1, 6)) return "?"
            if (n.type == 5 || n.type == 7) {
                val opts = lists[n.param] ?: emptyList()
                v = opts.firstOrNull { it.equals(v, ignoreCase = true) }
                    ?: v.toIntOrNull()?.let { opts.getOrNull(it) } ?: return "?"
            }
            if (n.type == 3 && v.toDoubleOrNull() == null) return "?"
            if (n.type == 4 && (v.toIntOrNull() ?: -1) !in 0..255) return "?"
            if (n.type == 2 && n.param > 0 && v.length > n.param) return "?"
            if (col != null) {
                val vals = n.values ?: return "?"
                if (col !in vals.indices) return "?"
                vals[col] = v
            } else n.value = v
            return "" // no reply on success
        }
        val (n, col) = resolve(body) ?: return "?"
        if (n.type == 0 || n.type == 1) return "?"
        if (col != null) return n.values?.getOrNull(col)?.let { "MM$it" } ?: "?"
        if (n.values != null) return "?"
        return "MM${n.value}"
    }

    // MARK: - Command dispatch

    private fun handle(raw: String): String? {
        if (raw.isEmpty()) return null
        val cmd = raw.take(2).uppercase(Locale.US)
        val arg = raw.drop(2)
        val r = when (cmd) {
            "MM" -> mm(arg)
            "ML" -> arg.toIntOrNull()?.let { lists[it] }?.let { "ML" + it.joinToString("|") } ?: "?"
            "MU" -> ""
            "VN" -> "VN${firmware}QMX"
            "OM" -> "OMQC"
            "ID" -> "ID020"
            "UI" -> "UI000000000000000000DE0000"
            "PS" -> if (arg.isEmpty()) "PS1" else "?" // never "power off" the demo
            "FW" -> if (cat["MD"] == "3" || cat["MD"] == "7") "FW0300" else "FW3200"
            "SM" -> "SM${(20..38).random()}"
            "SA" -> "SA${(0..12).random()}"
            "PC" -> "PC0"
            "SW" -> "SW"
            "TM" -> if (arg.isEmpty()) "TM" + java.text.SimpleDateFormat("HHmmss", Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()) else ""
            "LC" -> "LC" + "%-16s%-16s".format(freqText(), "DEMO QMX  ${modeName()}")
            "IF" -> "IF" + cat["FA"] + "     " + "%+05d".format(rit) + (cat["RT"] ?: "0") + "0000" +
                (cat["TQ"] ?: "0") + (cat["MD"] ?: "6") + "0" + "0" + (cat["SP"] ?: "0") + "000 "
            "BU", "BD" -> { val b = (cat["BN"]!!.toInt() + if (cmd == "BU") 1 else -1).coerceIn(0, 11); cat["BN"] = "$b"; "" }
            "RC" -> { rit = 0; "" }
            "RU", "RD" -> { arg.toIntOrNull()?.let { rit = if (cmd == "RU") it else -it }; "" }
            "TX", "RX", "KD", "KY", "TA", "C2", "PL" -> "" // accepted; demo never transmits
            "AG" -> if (arg.isEmpty() || arg == "0") "AG0" + cat["AG"] else { cat["AG"] = arg.drop(1).padStart(3, '0'); "" }
            else -> if (cmd in cat) {
                if (arg.isEmpty()) cmd + cat[cmd] else {
                    cat[cmd] = if (cmd == "FA" || cmd == "FB") arg.padStart(11, '0') else arg
                    ""
                }
            } else "?"
        }
        return r.ifEmpty { null }
    }

    private fun freqText(): String {
        val hz = cat["FA"]!!.toLong()
        return "%,d".format(Locale.US, hz).replace(',', '.') + " A"
    }

    private fun modeName() = when (cat["MD"]) {
        "1" -> "LSB"; "2" -> "USB"; "3" -> "CW"; "5" -> "AM"; "6" -> "DIGI"; "7" -> "CWR"; else -> "?"
    }
}
