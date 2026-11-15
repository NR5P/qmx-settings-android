package com.qmxsettings.app.menu

/**
 * QMX Menu Manager (CAT MM/ML/MU, firmware 1_02_006+) data model.
 *
 * Discovery reply:  MM<type>|<param>|<name>[;]   e.g. "MM5|3|Keyer mode", "MM0|0|Band config.[16]"
 * Get reply:        MM<value>                   e.g. "MMIAMBIC A", "MM4"
 * List reply:       ML<v1>|<v2>|...             e.g. "MLStraight|IAMBIC A|IAMBIC B|Ultimatic"
 */
enum class MenuType(val code: Int, val label: String, val editable: Boolean) {
    SUBMENU(0, "Menu", false),
    ACTION(1, "Application", false),
    STRING(2, "Text", true),
    NUMBER(3, "Number", true),
    BYTE(4, "Byte", true),
    LIST(5, "List", true),
    INFO(6, "Info", false),
    MASK(7, "Switch", true);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** One parsed discovery reply. */
data class MenuItemInfo(val type: MenuType, val param: Int, val name: String, val columns: Int?)

object MenuProtocol {
    private val colSuffix = Regex("""^(.*)\[(\d+)]$""")

    /** Parses a discovery frame ("MM5|3|Keyer mode", without ';'). Returns null if malformed. */
    fun parseDiscovery(frame: String): MenuItemInfo? {
        if (!frame.startsWith("MM")) return null
        val body = frame.substring(2)
        val parts = body.split('|', limit = 3)
        if (parts.size < 3) return null
        val type = parts[0].trim().toIntOrNull()?.let { MenuType.of(it) } ?: return null
        val param = parts[1].trim().toIntOrNull() ?: 0
        var name = parts[2]
        var cols: Int? = null
        colSuffix.matchEntire(name)?.let { m ->
            name = m.groupValues[1]
            cols = m.groupValues[2].toIntOrNull()
        }
        return MenuItemInfo(type, param, name, cols)
    }

    /** Parses a Get frame ("MM4") into its value text ("4"). */
    fun parseValue(frame: String): String? = if (frame.startsWith("MM")) frame.substring(2) else null

    /** Parses an ML frame into its list of values. */
    fun parseList(frame: String): List<String>? =
        if (frame.startsWith("ML")) frame.substring(2).split('|') else null

    /**
     * Whether a menu item name can be used verbatim as a path segment. Numeric names
     * (e.g. CW filter widths "50", "100") must be addressed by index, per the manual; names
     * with protocol delimiters can't be sent either.
     */
    fun nameIsAddressable(name: String): Boolean =
        name.isNotBlank() && name.toIntOrNull() == null && name.none { it in "|;=?[]" } && name.trim() == name

    /** Builds the path text for [names]/[indexes], preferring names (stable across firmware). */
    fun encodePath(names: List<String>, indexes: List<Int>): String =
        names.indices.joinToString("|") { i -> if (nameIsAddressable(names[i])) names[i] else indexes[i].toString() }

    fun indexPath(indexes: List<Int>): String = indexes.joinToString("|")

    fun getCommand(path: String, column: Int? = null) = "MM$path${column?.let { "[$it]" } ?: ""};"
    fun setCommand(path: String, value: String, column: Int? = null) = "MM$path${column?.let { "[$it]" } ?: ""}=$value;"
    fun queryCommand(path: String) = "MM$path?;"
    fun listCommand(listType: Int) = "ML$listType;"

    /** Rejects characters that would break MM framing. */
    fun valueIsSendable(v: String) = v.none { it == ';' || it == '\r' || it == '\n' || it == '|' || it == '=' }
}

/**
 * A node of the discovered menu tree. Grid pages (Band config.) have [columns] set on the
 * page; their rows then hold one value per column in [values].
 */
class MenuNode(
    val names: List<String>,
    val indexes: List<Int>,
    val type: MenuType,
    /** For LIST/MASK: list type number. For STRING/NUMBER: field length. */
    val param: Int,
    val columns: Int? = null,
    /** Set on rows of a grid page: the page's column count. */
    val gridColumns: Int? = null,
) {
    val name: String get() = names.last()
    val children = mutableListOf<MenuNode>()
    var value: String? = null
    var values: MutableList<String?>? = null
    var readError = false

    val path: String get() = MenuProtocol.encodePath(names, indexes)
    val indexPath: String get() = MenuProtocol.indexPath(indexes)
    /** Human-readable, stable key used by profiles. */
    val key: String get() = names.joinToString(" › ")
    val depth: Int get() = names.size
    val isGridRow: Boolean get() = gridColumns != null
    val isContainer: Boolean get() = type == MenuType.SUBMENU
    val hasValue: Boolean get() = type.editable || type == MenuType.INFO
    val listType: Int? get() = if (type == MenuType.LIST || type == MenuType.MASK) param else null

    fun walk(): Sequence<MenuNode> = sequence {
        yield(this@MenuNode)
        for (c in children) yieldAll(c.walk())
    }

    /** Leaf settings beneath this node (including itself if it is one). */
    fun settings(): List<MenuNode> = walk().filter { it.hasValue }.toList()

    fun displayValue(column: Int? = null): String =
        (if (column != null) values?.getOrNull(column) else value) ?: if (readError) "—" else "…"
}

data class MenuTree(
    val firmware: String?,
    val roots: List<MenuNode>,
    val lists: Map<Int, List<String>>,
) {
    fun all(): Sequence<MenuNode> = roots.asSequence().flatMap { it.walk() }
    fun find(key: String): MenuNode? = all().firstOrNull { it.key.equals(key, ignoreCase = true) }
    fun settingCount(): Int = all().sumOf { n -> if (!n.hasValue) 0 else (n.gridColumns ?: 1) }
}

/** Validates a proposed value for [node]; returns an error message or null if OK. */
fun validateValue(node: MenuNode, value: String, lists: Map<Int, List<String>>): String? {
    if (!MenuProtocol.valueIsSendable(value)) return "Value can't contain ; | = or line breaks"
    return when (node.type) {
        MenuType.NUMBER -> {
            if (!Regex("""^-?\d+(\.\d+)?$""").matches(value)) "Enter a number"
            else if (node.param in 1 until value.length) "At most ${node.param} characters"
            else null
        }
        MenuType.BYTE -> value.toIntOrNull()?.let { if (it in 0..255) null else "0 to 255" } ?: "0 to 255"
        MenuType.STRING -> if (node.param > 0 && value.length > node.param) "At most ${node.param} characters" else null
        MenuType.LIST, MenuType.MASK -> {
            val opts = node.listType?.let { lists[it] }
            if (opts != null && opts.none { it.equals(value, ignoreCase = true) }) "Pick one of: ${opts.joinToString()}" else null
        }
        else -> "This item can't be changed"
    }
}
