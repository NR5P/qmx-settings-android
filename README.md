# QMX Settings (Android)

Read, change and save every configuration setting on a QRP Labs QMX / QMX+ over USB CAT, plus
session controls for QDX. Settings "profiles" let you switch the radio between operating setups
(POTA CW, FT8 at home, SSB portable…) and show exactly what will change before writing.

## How it works
- **Menu Manager (QMX firmware 1_02_006+)**: the app walks the radio's whole config menu with
  `MMpath?;` discovery, reads value lists with `MLn;`, reads values with `MMpath;` and writes with
  `MMpath=value;` (saved to EEPROM), verifying each write by reading it back. `MU;` reloads config
  so changes take effect even when *System config › CAT config › MM Effect* is "On demand".
  Because the menu is discovered live, new firmware menu items appear automatically.
- **Controls tab**: two-letter / Q commands (VFOs, mode, band, AF/RF gain, keyer speed, tune rate,
  RIT, AI, CAT timeout, TCXO, VOX, IQ mode…). Session-only, not saved in EEPROM. QDX uses its own
  Q-command numbering (QDX has no Menu Manager).
- **Profiles**: pick which menu sections (and optionally session controls) to remember; apply
  shows a diff with per-item checkboxes. Export/import as JSON.
- **Console**: raw CAT terminal with a log of every command sent/received.
- **Demo radio**: an offline simulated QMX (menu tree built from the 1_04_003 operating manual).

Safety: nothing is sent until you tap **Connect**; no CR/LF is ever sent (a carriage return would
switch the QMX port into terminal mode); the app never transmits, runs hardware tests or factory
reset.

## Build
```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # discovery/read/write/profile tests against the simulator
```

## Notes / not yet verified on hardware
- Writing a List item sends the option text first, then its index if the radio didn't take the text
  (the CAT manual doesn't say which form Set expects); every write is read back.
- Demo-mode values and some menu names are approximations from the manual's text; a real radio's
  live menu is always what the app shows when connected.
- Reference: [QMX CAT manual 1_04_003](https://qrp-labs.com/images/qmx/manuals/cat_1_04_003.pdf) (QRP Labs).
