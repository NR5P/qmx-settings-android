package com.qmxsettings.app.menu

import com.qmxsettings.app.cat.CatClient
import com.qmxsettings.app.cat.CatReply
import kotlinx.coroutines.CancellationException

data class DiscoveryProgress(val phase: String, val done: Int, val total: Int?)

data class WriteResult(val ok: Boolean, val readBack: String?, val message: String)

/** Discovers, reads and writes the QMX configuration menu over CAT Menu Manager commands. */
class MenuRepository(private val cat: CatClient) {

    /** True if the radio answers a Menu Manager discovery query (QMX firmware 1_02_006+). */
    suspend fun supportsMenuManager(): Boolean =
        (cat.query(MenuProtocol.queryCommand("0"), "MM") as? CatReply.Ok)
            ?.let { MenuProtocol.parseDiscovery(it.frame) } != null

    /** Walks the full menu tree with MMpath?; queries, then fetches ML lists. Values are not read. */
    suspend fun discover(firmware: String?, progress: (DiscoveryProgress) -> Unit = {}): MenuTree {
        var count = 0
        val listTypes = sortedSetOf<Int>()

        suspend fun children(parentNames: List<String>, parentIdx: List<Int>, gridCols: Int?): List<MenuNode> {
            val out = mutableListOf<MenuNode>()
            if (parentNames.size >= 6) return out // safety: QMX menus are ~3 deep
            val parentPath = MenuProtocol.indexPath(parentIdx)
            for (i in 0 until 64) {
                val path = if (parentPath.isEmpty()) "$i" else "$parentPath|$i"
                val reply = queryWithRetry(MenuProtocol.queryCommand(path))
                val info = (reply as? CatReply.Ok)?.let { MenuProtocol.parseDiscovery(it.frame) } ?: break
                // Guard against firmware that clamps out-of-range indexes to the last item.
                if (out.isNotEmpty() && out.last().name == info.name && out.last().type == info.type) break
                val node = MenuNode(
                    names = parentNames + info.name,
                    indexes = parentIdx + i,
                    type = info.type,
                    param = info.param,
                    columns = info.columns,
                    gridColumns = gridCols,
                )
                node.listType?.let { listTypes += it }
                count++
                progress(DiscoveryProgress("Discovering menu: ${node.key}", count, null))
                if (info.type == MenuType.SUBMENU) {
                    node.children += children(node.names, node.indexes, info.columns)
                }
                out += node
                if (count > 2000) break
            }
            return out
        }

        val roots = children(emptyList(), emptyList(), null)
        val lists = mutableMapOf<Int, List<String>>()
        for ((n, t) in listTypes.withIndex()) {
            progress(DiscoveryProgress("Reading value lists", n, listTypes.size))
            (queryWithRetry(MenuProtocol.listCommand(t), "ML") as? CatReply.Ok)
                ?.let { MenuProtocol.parseList(it.frame) }?.let { lists[t] = it }
        }
        return MenuTree(firmware, roots, lists)
    }

    private suspend fun queryWithRetry(cmd: String, prefix: String = "MM"): CatReply {
        var r = cat.query(cmd, prefix)
        if (r is CatReply.Timeout) r = cat.query(cmd, prefix)
        return r
    }

    /** Reads one value (a grid cell if [column] is set). */
    suspend fun read(node: MenuNode, column: Int? = null): String? {
        val r = queryWithRetry(MenuProtocol.getCommand(node.path, column))
        return (r as? CatReply.Ok)?.let { MenuProtocol.parseValue(it.frame) }
    }

    /** Reads every value under [nodes] into the tree. */
    suspend fun readAll(nodes: List<MenuNode>, progress: (DiscoveryProgress) -> Unit = {}) {
        val settings = nodes.flatMap { it.settings() }
        val total = settings.sumOf { it.gridColumns ?: 1 }
        var done = 0
        for (n in settings) {
            try {
                val cols = n.gridColumns
                if (cols != null) {
                    val vals = MutableList<String?>(cols) { null }
                    for (c in 0 until cols) {
                        vals[c] = read(n, c)
                        done++
                        progress(DiscoveryProgress("Reading ${n.key}", done, total))
                    }
                    n.values = vals
                    n.readError = vals.all { it == null }
                } else {
                    val v = read(n)
                    n.value = v
                    n.readError = v == null
                    done++
                    progress(DiscoveryProgress("Reading ${n.key}", done, total))
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun sameValue(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.trim().equals(b.trim(), ignoreCase = true)) return true
        val x = a.trim().toDoubleOrNull()
        val y = b.trim().toDoubleOrNull()
        return x != null && y != null && x == y
    }

    /**
     * Writes [value] with MMpath=value; then reads it back to confirm. For list items the
     * text value is tried first, then the option's index if the radio didn't take the text.
     * Menu Manager Set commands ARE saved to the radio's EEPROM.
     */
    suspend fun write(node: MenuNode, value: String, lists: Map<Int, List<String>>, column: Int? = null): WriteResult {
        if (!node.type.editable) return WriteResult(false, null, "${node.name} is read-only")
        if (!MenuProtocol.valueIsSendable(value)) return WriteResult(false, null, "Invalid characters in value")
        val options = node.listType?.let { lists[it] }
        val canonical = options?.firstOrNull { it.equals(value, ignoreCase = true) } ?: value

        cat.command(MenuProtocol.setCommand(node.path, canonical, column))
        var back = read(node, column)
        if (!sameValue(back, canonical) && options != null) {
            val idx = options.indexOfFirst { it.equals(canonical, ignoreCase = true) }
            if (idx >= 0) {
                cat.command(MenuProtocol.setCommand(node.path, idx.toString(), column))
                back = read(node, column)
            }
        }
        if (column != null) {
            val vals = node.values ?: MutableList<String?>(node.gridColumns ?: (column + 1)) { null }.also { node.values = it }
            if (column < vals.size) vals[column] = back
        } else {
            node.value = back
        }
        val ok = sameValue(back, canonical)
        return WriteResult(ok, back, if (ok) "Saved ${node.name} = $back" else "Radio reports ${node.name} = ${back ?: "no reply"}")
    }

    /** MU; makes the radio reload configuration (needed when "MM Effect" is "On demand"). */
    suspend fun reload() = cat.command("MU;")
}
