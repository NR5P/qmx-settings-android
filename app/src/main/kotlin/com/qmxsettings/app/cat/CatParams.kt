package com.qmxsettings.app.cat

enum class RadioFamily(val label: String) {
    QMX("QMX / QMX+"),
    QDX("QDX"),
}

enum class ParamKind { NUMBER, LIST, TEXT, FREQ }

/**
 * A two-letter (or Q-series) CAT parameter, read with "<CODE>;" and set with "<CODE><value>;".
 * These act on the current operating session only (not saved in EEPROM), except where noted.
 */
data class CatParam(
    val code: String,
    val label: String,
    val group: String,
    val kind: ParamKind,
    val help: String,
    val options: List<Pair<String, String>> = emptyList(), // radio value -> label
    val min: Long? = null,
    val max: Long? = null,
    val unit: String = "",
    val readOnly: Boolean = false,
    /** Command used to read; reply prefix to strip. */
    val getCmd: String = "$code;",
    val replyPrefix: String = code,
    /** Prefix placed before the value when setting. */
    val setPrefix: String = code,
    val storedInEeprom: Boolean = false,
) {
    fun parseReply(frame: String): String? =
        if (frame.uppercase().startsWith(replyPrefix)) frame.substring(replyPrefix.length) else null

    fun setCommand(value: String) = "$setPrefix$value;"

    fun display(value: String?): String {
        if (value == null) return "—"
        if (kind == ParamKind.LIST) {
            val v = value.trimStart('0').ifEmpty { "0" }
            return options.firstOrNull { it.first == value || it.first == v }?.second ?: value
        }
        if (kind == ParamKind.FREQ) return value.toLongOrNull()?.let { formatHz(it) } ?: value
        val n = value.toLongOrNull()
        val shown = n?.toString() ?: value // "054" -> "54"
        // Older QMX firmware reports Q4/Q5 as a raw 16-bit value rather than a 0-99 percentage.
        if (unit == "%" && n != null && max != null && n > max) return "$shown (raw)"
        return if (unit.isNotEmpty()) "$shown $unit" else shown
    }

    fun validate(value: String): String? {
        if (value.any { it == ';' || it == '\r' || it == '\n' }) return "Invalid characters"
        return when (kind) {
            ParamKind.NUMBER, ParamKind.FREQ -> {
                val n = value.toLongOrNull() ?: return "Enter a whole number"
                if (min != null && n < min) "Minimum $min" else if (max != null && n > max) "Maximum $max" else null
            }
            ParamKind.LIST -> if (options.none { it.first == value }) "Pick a value" else null
            ParamKind.TEXT -> null
        }
    }
}

fun formatHz(hz: Long): String {
    val s = hz.toString().padStart(4, '0')
    val mhz = hz / 1_000_000
    val khz = (hz / 1000) % 1000
    val h = hz % 1000
    return if (hz >= 1_000_000) "%d.%03d.%03d".format(mhz, khz, h) else s
}

private val onOff = listOf("0" to "Off", "1" to "On")
private val enDis = listOf("0" to "Disabled", "1" to "Enabled")

object CatParams {
    private val qmx = listOf(
        CatParam("VN", "Firmware", "Radio info", ParamKind.TEXT, "Firmware version.", readOnly = true),
        CatParam("OM", "Model code", "Radio info", ParamKind.TEXT, "Model number (QMX reports QC).", readOnly = true),
        CatParam("UI", "Unique ID", "Radio info", ParamKind.TEXT, "96-bit unique ID of the STM32 processor.", readOnly = true),
        CatParam("ID", "Kenwood ID", "Radio info", ParamKind.TEXT, "Always 020 (TS-480 emulation).", readOnly = true),

        CatParam("FA", "VFO A", "Operating", ParamKind.FREQ, "VFO A frequency in Hz.", min = 100_000, max = 99_999_999, unit = "Hz"),
        CatParam("FB", "VFO B", "Operating", ParamKind.FREQ, "VFO B frequency in Hz.", min = 100_000, max = 99_999_999, unit = "Hz"),
        CatParam("MD", "Mode", "Operating", ParamKind.LIST, "Operating mode. (SWR puts the radio into SWR tune mode, so it's not offered here.)",
            options = listOf("1" to "LSB", "2" to "USB", "3" to "CW", "5" to "AM", "6" to "Digi (FSK)", "7" to "CW-R", "9" to "FSK-R")),
        CatParam("BN", "Band number", "Operating", ParamKind.NUMBER, "Band column number from Band config (0-based).", min = 0, max = 15),
        CatParam("FR", "VFO mode", "Operating", ParamKind.LIST, "Which VFO is in use.",
            options = listOf("0" to "VFO A", "1" to "VFO B", "2" to "Split")),
        CatParam("SP", "Split", "Operating", ParamKind.LIST, "Split operation.", options = onOff),
        CatParam("TR", "Tune rate", "Operating", ParamKind.LIST, "Frequency step per click of the Tune knob.",
            options = listOf("0" to "10 MHz", "1" to "1 MHz", "2" to "100 kHz", "3" to "10 kHz", "4" to "1 kHz", "5" to "500 Hz", "6" to "100 Hz", "7" to "10 Hz")),
        CatParam("RT", "RIT", "Operating", ParamKind.LIST, "RIT on/off.", options = onOff),
        CatParam("RR", "RIT rate", "Operating", ParamKind.LIST, "RIT step per click.",
            options = listOf("4" to "1 kHz", "5" to "500 Hz", "6" to "100 Hz", "7" to "10 Hz", "8" to "1 Hz")),

        CatParam("AG", "AF gain (volume)", "Audio & RF", ParamKind.NUMBER,
            "Audio gain in 0.25 dB steps (e.g. 91 = 22.75 dB). Range 0-799 but high values are far too loud.",
            min = 0, max = 799, unit = "× 0.25 dB", getCmd = "AG0;", replyPrefix = "AG0", setPrefix = "AG0"),
        CatParam("RG", "RF gain (current band)", "Audio & RF", ParamKind.NUMBER, "Receive RF gain in dB for the active band.", min = 0, max = 99, unit = "dB"),
        CatParam("SS", "SSB TX source", "Audio & RF", ParamKind.LIST, "Where SSB transmit audio comes from.",
            options = listOf("0" to "USB audio", "1" to "Two-tone test", "2" to "External mic")),
        CatParam("KS", "Keyer speed", "CW", ParamKind.NUMBER, "Keyer speed in WPM.", min = 5, max = 60, unit = "WPM"),

        CatParam("AI", "Auto Info", "CAT", ParamKind.LIST, "Unsolicited IF; reports to the CAT host.",
            options = listOf("0" to "Off", "1" to "Old format (IF every 1.5 s)", "2" to "Extended (on change)", "3" to "Both")),
        CatParam("QB", "CAT timeout enable", "CAT", ParamKind.LIST, "Return to RX if no CAT command arrives while transmitting.", options = enDis),
        CatParam("QC", "CAT timeout", "CAT", ParamKind.NUMBER, "CAT timeout in seconds.", min = 1, max = 999, unit = "s"),

        CatParam("Q0", "TCXO reference", "Session (Q-commands)", ParamKind.NUMBER, "TCXO reference frequency, 24999000-25001000 Hz.", min = 24_999_000, max = 25_001_000, unit = "Hz"),
        CatParam("Q1", "Sideband", "Session (Q-commands)", ParamKind.LIST, "Digi sideband.", options = listOf("0" to "USB", "1" to "LSB")),
        CatParam("Q3", "VOX", "Session (Q-commands)", ParamKind.LIST, "VOX enable.", options = enDis),
        CatParam("Q4", "TX rise threshold", "Session (Q-commands)", ParamKind.NUMBER, "Percent, default 80. Firmware before 1_02_006 reports a raw 0-65535 value instead.", min = 0, max = 99, unit = "%"),
        CatParam("Q5", "TX fall threshold", "Session (Q-commands)", ParamKind.NUMBER, "Percent, default 60. Firmware before 1_02_006 reports a raw 0-65535 value instead.", min = 0, max = 99, unit = "%"),
        CatParam("Q6", "Cycle min", "Session (Q-commands)", ParamKind.NUMBER, "Digi frequency measurement Cycle Min.", min = 0, max = 999),
        CatParam("Q7", "Sample min", "Session (Q-commands)", ParamKind.NUMBER, "Digi frequency measurement Sample Min.", min = 0, max = 999),
        CatParam("Q8", "Discard", "Session (Q-commands)", ParamKind.NUMBER, "Digi frequency measurement Discard.", min = 0, max = 999),
        CatParam("Q9", "IQ mode", "Session (Q-commands)", ParamKind.LIST, "Stream raw I/Q to the USB sound card.", options = enDis),
        CatParam("QA", "Japan band limits", "Session (Q-commands)", ParamKind.LIST, "Restrict TX to Japanese band limits.", options = enDis),
        CatParam("QJ", "TX shift threshold", "Session (Q-commands)", ParamKind.NUMBER, "See the Digi config section of the manual.", min = 0, max = 9999),
    )

    private val qdx = listOf(
        CatParam("FA", "VFO A", "Operating", ParamKind.FREQ, "VFO A frequency in Hz.", min = 100_000, max = 99_999_999, unit = "Hz"),
        CatParam("FB", "VFO B", "Operating", ParamKind.FREQ, "VFO B frequency in Hz.", min = 100_000, max = 99_999_999, unit = "Hz"),
        CatParam("FR", "VFO mode", "Operating", ParamKind.LIST, "Which VFO is in use.", options = listOf("0" to "VFO A", "1" to "VFO B", "2" to "Split")),
        CatParam("SP", "Split", "Operating", ParamKind.LIST, "Split operation.", options = onOff),
        CatParam("AG", "AF gain", "Audio & RF", ParamKind.NUMBER, "Audio gain ÷ 256 (default 5000 → 19).", min = 0, max = 255),
        CatParam("Q0", "TCXO reference", "Configuration (Q-commands)", ParamKind.NUMBER, "24999000-25001000 Hz.", min = 24_999_000, max = 25_001_000, unit = "Hz"),
        CatParam("Q1", "Sideband", "Configuration (Q-commands)", ParamKind.LIST, "Sideband.", options = listOf("0" to "USB", "1" to "LSB")),
        CatParam("Q2", "Default frequency", "Configuration (Q-commands)", ParamKind.NUMBER, "Start-up frequency. Saved in EEPROM.", min = 100_000, max = 99_999_999, unit = "Hz", storedInEeprom = true),
        CatParam("Q3", "Receiver gain", "Configuration (Q-commands)", ParamKind.NUMBER, "Default 5000.", min = 0, max = 65535),
        CatParam("Q4", "VOX", "Configuration (Q-commands)", ParamKind.LIST, "VOX enable.", options = enDis),
        CatParam("Q5", "TX rise threshold", "Configuration (Q-commands)", ParamKind.NUMBER, "Percent, default 80.", min = 0, max = 99, unit = "%"),
        CatParam("Q6", "TX fall threshold", "Configuration (Q-commands)", ParamKind.NUMBER, "Percent, default 60.", min = 0, max = 99, unit = "%"),
        CatParam("Q7", "Cycle min", "Configuration (Q-commands)", ParamKind.NUMBER, "Cycle Min.", min = 0, max = 999),
        CatParam("Q8", "Sample min", "Configuration (Q-commands)", ParamKind.NUMBER, "Sample Min.", min = 0, max = 999),
        CatParam("Q9", "Discard", "Configuration (Q-commands)", ParamKind.NUMBER, "Discard.", min = 0, max = 999),
        CatParam("QA", "IQ mode", "Configuration (Q-commands)", ParamKind.LIST, "Stream raw I/Q to USB audio.", options = enDis),
        CatParam("QB", "Japan band limits", "Configuration (Q-commands)", ParamKind.LIST, "Japanese band limits.", options = enDis),
    )

    fun forFamily(f: RadioFamily) = if (f == RadioFamily.QDX) qdx else qmx
}
