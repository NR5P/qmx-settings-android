package com.qmxsettings.app.radio

import com.qmxsettings.app.cat.RadioFamily

/** How a model's firmware is updated. */
enum class UpdateMethod { QFU, AVR }

/** QFU (QRP Labs Firmware Update) details for a firmware family. */
data class QfuSpec(
    /** Extension of the firmware file, e.g. "QMX". */
    val ext: String,
    /** Name of the configuration file on the update drive. */
    val eepromName: String,
    /** "Firmware images are always a … file" — exact size from the manuals and every published image. */
    val imageSize: Int,
    /** Product pages whose firmware tables list official downloads. */
    val pages: List<String>,
    /** Path fragment of firmware download links on those pages. */
    val linkPath: String,
)

val QMX_QFU = QfuSpec("QMX", "EEPROM", 541_696, listOf("https://qrp-labs.com/qmx.html"), "/qmx/firmware/")
val QDX_QFU = QfuSpec("QDX", "EEPROM.QDX", 122_368, listOf("https://qrp-labs.com/qdx.html", "https://qrp-labs.com/qdxm.html"), "/qdx/firmware/")

/** One step of a guide; [warning] steps are highlighted. */
data class GuideStep(val text: String, val warning: Boolean = false)

/**
 * QRP Labs radios this app knows about, with what each manual says about connecting, CAT,
 * firmware updates and recovery. Sources: qrp-labs.com product pages, QMX/QMX+ assembly and
 * operating manuals (1_04), QDX operating manual 1_10 and assembly 1.24, QCX operating manual 1.08.
 */
enum class RadioModel(
    val label: String,
    val family: RadioFamily,
    val pcbRevs: List<String>,
    val bandVersions: List<String>,
    val update: UpdateMethod,
    val qfu: QfuSpec?,
    /** CAT serial speed; native-USB radios ignore it. */
    val baud: Int,
    val usbNative: Boolean,
    val page: String,
) {
    QMX("QMX", RadioFamily.QMX, listOf("1", "2", "3", "4", "5"), listOf("80-20m", "60-15m", "20-10m"),
        UpdateMethod.QFU, QMX_QFU, 115200, true, "https://qrp-labs.com/qmx.html"),
    QMX_PLUS("QMX+", RadioFamily.QMX, listOf("1", "2", "3"), listOf("160-6m"),
        UpdateMethod.QFU, QMX_QFU, 115200, true, "https://qrp-labs.com/qmxp.html"),
    QDX("QDX", RadioFamily.QDX, listOf("1", "2", "3", "3a", "4", "5", "6"), listOf("80-20m (5 band)", "20-10m (6 band)"),
        UpdateMethod.QFU, QDX_QFU, 115200, true, "https://qrp-labs.com/qdx.html"),
    QDX_M("QDX-M", RadioFamily.QDX, emptyList(), listOf("Single band (set by jumper wires)"),
        UpdateMethod.QFU, QDX_QFU, 115200, true, "https://qrp-labs.com/qdxm.html"),
    QCX_PLUS("QCX+", RadioFamily.QCX, emptyList(), listOf("Single band"), UpdateMethod.AVR, null, 38400, false, "https://qrp-labs.com/qcxp.html"),
    QCX_MINI("QCX-mini", RadioFamily.QCX, emptyList(), listOf("Single band"), UpdateMethod.AVR, null, 38400, false, "https://qrp-labs.com/qcxmini.html"),
    QCX("QCX (original)", RadioFamily.QCX, emptyList(), listOf("Single band"), UpdateMethod.AVR, null, 38400, false, "https://qrp-labs.com/qcx.html");

    companion object {
        fun forFamily(f: RadioFamily) = entries.filter { it.family == f }
    }
}

/** What the user told the app about their radio (detection can't tell QMX from QMX+, QDX from QDX-M, or QCX models apart). */
data class MyRadio(val model: RadioModel = RadioModel.QMX, val pcbRev: String? = null, val bandVersion: String? = null)

object Guides {
    fun connect(m: RadioModel): List<GuideStep> = when (m.family) {
        RadioFamily.QMX, RadioFamily.QDX -> listOf(
            GuideStep("Connect the ${m.label}'s USB port to the phone with a USB OTG cable or adapter (the phone must be the USB host)."),
            GuideStep("The radio shows up as up to three serial ports; use the first one for CAT. Only one app at a time can use a port, so close JS8Firefly / FT8 apps first."),
            GuideStep("Never send a carriage return: on ${m.family.label} a CR switches the port into the text Terminal. This app never sends one."),
        ) + if (m.family == RadioFamily.QDX) listOf(GuideStep("QDX has no front-panel menu; its configuration is in the Terminal and in the CAT Q-commands on the Controls tab.")) else emptyList()
        RadioFamily.QCX -> listOf(
            GuideStep("The ${m.label} has no USB. CAT is a 38400-baud TTL serial port on the 3.5 mm CAT jack, so you need a USB-to-TTL serial cable (FTDI, CP2102 or CH340, 5 V or 3.3 V TTL) wired to that jack, plugged into the phone with an OTG adapter."),
            GuideStep("The port uses TTL voltage levels; a true RS-232 serial cable is the wrong kind and can damage the radio.", warning = true),
            GuideStep(if (m == RadioModel.QCX) "Older QCX PCBs need three extra components fitted before CAT works (see the QCX operating manual)." else "CAT works on all ${m.label} boards."),
            GuideStep("Menu commands (QM) only answer while you're not inside the menu on the radio itself."),
        )
    }

    fun update(m: RadioModel, fwOnRadio: String?): List<GuideStep> = when (m.family) {
        RadioFamily.QMX -> listOf(
            GuideStep("On the ${m.label}: long-press the left centre button to open the menu, turn the Tune knob to Other › Update firmware, select it and confirm."),
            GuideStep("The screen goes blank with the backlight off and the radio appears as a USB drive holding the firmware file (e.g. ${fwOnRadio ?: "1_02_005"}.QMX) and EEPROM."),
            GuideStep("If the radio switches off instead, switch it on with the left encoder (rotary knob) button."),
            GuideStep("To leave update mode without changing anything, power the radio off and on."),
        )
        RadioFamily.QDX -> listOf(
            GuideStep("Power the ${m.label} up and watch the status LED: it flickers quickly for 5 seconds. Disconnect power DURING those 5 seconds, then reconnect it."),
            GuideStep("The LED now flashes slowly: the radio is in update mode and appears as a USB drive named QDX holding the firmware file (e.g. 1_12.QDX) and EEPROM.QDX."),
            GuideStep("Alternatively choose “Update firmware” in the QDX Terminal (that needs a terminal program, not this app)."),
            GuideStep("To leave update mode without changing anything, power the radio off and on."),
        )
        RadioFamily.QCX -> listOf(
            GuideStep("The ${m.label} (ATmega328 processor) has no USB update mode. Firmware is installed with an AVR programmer on the 2×3-pin ICSP header (e.g. USBasp with avrdude/AVRDUDESS), or by fitting a pre-programmed chip bought from QRP Labs."),
            GuideStep("QCX firmware files are hosted in the QRP Labs groups.io forum, not on qrp-labs.com, so this app can't download or install them."),
            GuideStep("All QCX firmware versions work on all QCX, QCX+ and QCX-mini PCB revisions."),
        )
    }

    fun recovery(m: RadioModel): List<GuideStep> = when (m.family) {
        RadioFamily.QMX -> listOf(GuideStep("If an update fails, put the radio back in update mode and copy the firmware again. If it won't start at all, a wire jumper from the Aux 2 pin to Ground forces update mode at power-up (assembly manual, section “Forcing firmware update mode”); remove it afterwards.", warning = true))
        RadioFamily.QDX -> listOf(GuideStep("If an update fails, use the 5-second power-cycle trick again to get back into update mode, then copy the firmware again.", warning = true))
        RadioFamily.QCX -> emptyList()
    }

    /** Notes specific to the chosen PCB revision. */
    fun revision(m: RadioModel, rev: String?): List<GuideStep> {
        if (rev == null) return emptyList()
        return when (m) {
            RadioModel.QMX -> when (rev) {
                "1" -> listOf(GuideStep("PCB Rev 1 can use any QMX firmware version."))
                "2", "3" -> listOf(
                    GuideStep("PCB Rev $rev should only use firmware 1_00_011 and up.", true),
                    GuideStep("Rev 1-3 boards need a modification for the grounded PTT output to work (see qrp-labs.com/qmx)."),
                ) + if (rev == "2") listOf(GuideStep("Rev 2 boards shipped in 2023 have a strongly recommended modification (see qrp-labs.com/qmx).")) else emptyList()
                "4" -> listOf(GuideStep("PCB Rev 4 doesn't need the PTT mod. Note the silkscreen error: “Rev 3” is printed under the power jack; the correct “Rev 4” is near the BNC."))
                else -> emptyList()
            }
            RadioModel.QDX -> when (rev) {
                "1" -> listOf(GuideStep("Rev 1 (October 2021, no revision printed on the PCB)."))
                "2" -> listOf(GuideStep("PCB Rev 2 needs firmware 1_03 or later.", true))
                "3", "3a" -> listOf(GuideStep("PCB Rev 3 should use firmware 1_04 or later (fixes the Rev 3 TX “popping”).", true))
                "4" -> listOf(GuideStep("PCB Rev 4 should use firmware 1_05 or later (Rev 4 PIN-diode bias control).", true))
                else -> listOf(GuideStep("QRP Labs recommends always installing the latest firmware on PCB Rev $rev."))
            }
            else -> emptyList()
        }
    }
}
