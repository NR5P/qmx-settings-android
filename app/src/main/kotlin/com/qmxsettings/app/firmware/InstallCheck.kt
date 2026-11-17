package com.qmxsettings.app.firmware

import com.qmxsettings.app.cat.RadioFamily
import com.qmxsettings.app.radio.RadioModel

/** One thing the user should know before installing. [Level.BLOCK] ones stop the install. */
data class InstallNote(val text: String, val level: Level) {
    enum class Level { INFO, WARN, BLOCK }
}

/**
 * Rules from the QRP Labs firmware histories and manuals, applied to a planned install of [target]
 * over [current] (the version on the radio's update drive) for the user's [model] and PCB [rev].
 * [release] is the target's entry in the official list (null for a file the user picked).
 */
object InstallCheck {
    fun notes(model: RadioModel, rev: String?, current: FirmwareVersion?, target: FirmwareVersion, release: FirmwareRelease?): List<InstallNote> {
        val out = mutableListOf<InstallNote>()
        fun info(s: String) { out += InstallNote(s, InstallNote.Level.INFO) }
        fun warn(s: String) { out += InstallNote(s, InstallNote.Level.WARN) }
        fun block(s: String) { out += InstallNote(s, InstallNote.Level.BLOCK) }

        if (model.update != com.qmxsettings.app.radio.UpdateMethod.QFU) { block("${model.label} firmware can't be installed over USB."); return out }
        if (release == null) warn("$target isn't in the official list on qrp-labs.com, so its release notes can't be checked.")
        else if (release.beta) warn("$target is a beta version: newer features, higher risk of bugs. Stable versions are the safer choice.")
        if (release?.factoryReset == true)
            warn("Installing $target executes a Factory Reset: all settings return to defaults. After it restarts, choose your band version on the radio (Tune knob, then press Menu or the Tune knob).")

        when (model.family) {
            RadioFamily.QMX -> {
                if (release?.qmxPlusOnly == true && model != RadioModel.QMX_PLUS)
                    block("QRP Labs says $target is for QMX+ only and does not work on QMX. (Set My radio to QMX+ on the Radio tab if this radio is a QMX+.)")
                if (model == RadioModel.QMX && rev in setOf("2", "3", "4", "5") && target < FirmwareVersion.MIN_REV2)
                    block("QMX PCB Rev $rev should only use firmware ${FirmwareVersion.MIN_REV2} and up.")
                if (current != null && target > current) {
                    if (current < FirmwareVersion.EEPROM_RELAYOUT && target >= FirmwareVersion.EEPROM_RELAYOUT)
                        warn("Upgrading past ${FirmwareVersion.EEPROM_RELAYOUT} re-arranges the EEPROM and performs a factory reset automatically.")
                    if (current < FirmwareVersion.MENU_MANAGER && target >= FirmwareVersion.MENU_MANAGER)
                        info("After this update the app can read and change every menu setting over USB (Menu Manager, ${FirmwareVersion.MENU_MANAGER}+).")
                }
                if (current != null && target < current && current >= FirmwareVersion.EEPROM_RELAYOUT && target < FirmwareVersion.EEPROM_RELAYOUT)
                    warn("Going below ${FirmwareVersion.EEPROM_RELAYOUT} crosses an EEPROM layout change; expect a factory reset.")
            }
            RadioFamily.QDX -> {
                // From the QDX history: 1_03 is "firmware for PCB Rev 2"; 1_04 fixed Rev 3 TX popping; 1_05 finalized Rev 4 PIN bias SMPS.
                if (rev == "2" && target < FirmwareVersion.of("1_03")) block("QDX PCB Rev 2 needs firmware 1_03 or later.")
                if (rev in setOf("3", "3a") && target < FirmwareVersion.of("1_04")) warn("QDX PCB Rev 3 should use 1_04 or later (fixes TX “popping”).")
                if (rev == "4" && target < FirmwareVersion.of("1_05")) warn("QDX PCB Rev 4 should use 1_05 or later (Rev 4 PIN-diode bias control).")
                info("QRP Labs advises always installing the latest QDX firmware.")
            }
            RadioFamily.QCX -> {}
        }

        if (current != null) when {
            target == current -> info("This reinstalls the version already on the radio ($current).")
            target < current -> warn("This is a downgrade from $current to $target. Settings added after $target are dropped and the configuration may need checking.")
            current.major != target.major || current.minor != target.minor ->
                info("Big jump from $current to $target: existing settings carry over, new settings start at their defaults. Check the settings afterwards.")
        }
        return out
    }

    private val BACKUP = Regex("""^EEPROM-(?:(QMX|QDX)-)?(\d+_\d{2}(?:_\d{3})?)""", RegexOption.IGNORE_CASE)

    /** Firmware family and version an EEPROM backup came from, from its name (EEPROM-QMX-1_02_005-….bin). */
    fun backupInfo(fileName: String): Pair<String, FirmwareVersion>? = BACKUP.find(fileName)?.let {
        (it.groupValues[1].ifEmpty { "QMX" }.uppercase()) to FirmwareVersion.of(it.groupValues[2])
    }

    fun backupName(ext: String, fwFile: String, stamp: String) = "EEPROM-${ext.uppercase()}-${fwFile.substringBeforeLast('.')}-$stamp.bin"

    /** EEPROM layouts change between versions, so only restore a backup taken on the same radio type and firmware. */
    fun restoreProblem(backupFile: String, backupSize: Long, driveExt: String, driveFirmware: FirmwareVersion?, driveEepromSize: Long?): String? {
        val info = backupInfo(backupFile) ?: return "Can't tell which radio and firmware this backup came from."
        if (!info.first.equals(driveExt, true)) return "This is a ${info.first} backup but the radio is a $driveExt."
        if (driveEepromSize != null && backupSize != driveEepromSize) return "Backup is $backupSize bytes but this radio's EEPROM is $driveEepromSize bytes."
        if (driveFirmware == null) return "Can't tell which firmware is on the radio."
        if (info.second != driveFirmware) return "This backup was taken on firmware ${info.second} but the radio has $driveFirmware. EEPROM layouts change between versions; restore only onto the same version (reinstall ${info.second} first if you need this backup)."
        return null
    }
}
