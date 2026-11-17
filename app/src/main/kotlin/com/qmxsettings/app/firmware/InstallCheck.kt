package com.qmxsettings.app.firmware

/** One thing the user should know before installing. [blocking] ones stop the install unless acknowledged. */
data class InstallNote(val text: String, val level: Level) {
    enum class Level { INFO, WARN, BLOCK }
}

/**
 * Rules from the QRP Labs QMX firmware history and manuals, applied to a planned install of [target]
 * over [current] (the version on the radio's update drive). [release] is the target's entry from the
 * official list (null when installing a file the user picked).
 */
object InstallCheck {
    fun notes(current: FirmwareVersion?, target: FirmwareVersion, release: FirmwareRelease?, isQmxPlus: Boolean): List<InstallNote> {
        val out = mutableListOf<InstallNote>()
        fun info(s: String) { out += InstallNote(s, InstallNote.Level.INFO) }
        fun warn(s: String) { out += InstallNote(s, InstallNote.Level.WARN) }
        fun block(s: String) { out += InstallNote(s, InstallNote.Level.BLOCK) }

        if (release?.qmxPlusOnly == true && !isQmxPlus)
            block("QRP Labs says $target is for QMX+ only and does not work on QMX. Only install it if this radio is a QMX+.")
        if (release == null) warn("$target isn't in the official list on qrp-labs.com, so its release notes can't be checked.")
        else if (release.beta) warn("$target is a beta version: newer features, higher risk of bugs. Stable versions are the safer choice.")
        if (release?.factoryReset == true)
            warn("Installing $target executes a Factory Reset: all settings return to defaults. After it restarts, choose your band version on the radio (Tune knob, then press Menu or the Tune knob).")
        if (target < FirmwareVersion.MIN_REV2)
            warn("PCB Rev 2 and later should only use firmware ${FirmwareVersion.MIN_REV2} and up.")

        if (current != null) {
            when {
                target == current -> info("This reinstalls the version already on the radio ($current).")
                target < current -> {
                    warn("This is a downgrade from $current to $target. Settings added after $target are dropped and the configuration may need checking.")
                    if (current >= FirmwareVersion.EEPROM_RELAYOUT && target < FirmwareVersion.EEPROM_RELAYOUT)
                        warn("Going below ${FirmwareVersion.EEPROM_RELAYOUT} crosses an EEPROM layout change; expect a factory reset.")
                }
                else -> {
                    if (current < FirmwareVersion.EEPROM_RELAYOUT && target >= FirmwareVersion.EEPROM_RELAYOUT)
                        warn("Upgrading past ${FirmwareVersion.EEPROM_RELAYOUT} re-arranges the EEPROM and performs a factory reset automatically.")
                    if (current < FirmwareVersion.MENU_MANAGER && target >= FirmwareVersion.MENU_MANAGER)
                        info("After this update the app can read and change every menu setting over USB (Menu Manager, ${FirmwareVersion.MENU_MANAGER}+).")
                    if (current.minor != target.minor || current.major != target.major)
                        info("Big jump from $current to $target: existing settings carry over, new settings start at their defaults. Look through the Settings tab afterwards.")
                }
            }
        }
        return out
    }

    /** The firmware version an EEPROM backup was taken on, from its file name (EEPROM-1_02_005-2026….bin). */
    fun backupVersion(fileName: String) = FirmwareVersion.parse(fileName)

    /** EEPROM layouts change between versions, so only restore a backup taken on the same firmware. */
    fun restoreProblem(backupFile: String, backupSize: Long, driveFirmware: FirmwareVersion?, driveEepromSize: Long?): String? {
        val bv = backupVersion(backupFile)
        if (driveEepromSize != null && backupSize != driveEepromSize) return "Backup is $backupSize bytes but this radio's EEPROM is $driveEepromSize bytes."
        if (bv == null || driveFirmware == null) return "Can't tell which firmware this backup came from, so it can't be matched to the radio."
        if (bv != driveFirmware) return "This backup was taken on firmware $bv but the radio has $driveFirmware. EEPROM layouts change between versions; restore only onto the same version (reinstall $bv first if you need this backup)."
        return null
    }
}
