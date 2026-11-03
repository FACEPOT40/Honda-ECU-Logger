# H-ECU Logger (K-line)

**K-line live data logger for Honda / Keihin ECUs.**

Record live ECU data from Honda/Keihin K-line motorcycles while you ride, then review the logs as graphs afterwards to check your road-tuning results.

You can't look at a screen while riding. So: connect, press **Start Record**, ride, press **Stop**, and analyze the timestamped CSV later (in the app or in Excel).

There are two apps in this repo, recording the same CSV format:
- **Windows app** (Python): live screen, recording and the full ride viewer
- **Android app** (`android/`): a phone recorder for the handlebar or pocket, with live tiles and graphs, a simple ride viewer and GPS speed

> **Read-only.** The apps only send wake-up, init, live-data and fault-code read queries (each checked against a strict whitelist). They contain no ECU write / flash / EEPROM commands and never clear fault codes.
> **Never look at the screen while riding.**
> Not affiliated with Honda Motor Co.

![screenshot](docs/screenshot.png)

## Features
- Live values: RPM, TPS, engine temp (ECT), battery, speed, injector time, ignition advance, O2 voltage, STFT, gear (N / in gear), Mode (d14, raw)
  - Speed only works on Honda bikes with an ECU speed sensor; on the Wave (cable speedometer) it always reads 0
  - The ECU only knows neutral or in gear, not which gear
- Real-time graphs (10 / 30 / 60 s window) with wall-clock time axis
- **Start / Stop Record** to CSV with wall-clock timestamps (ms); `elapsed_s` also saved (hidden on screen by default, *View → Show elapsed time*)
- **Marker** button / `M` key: marks an event (e.g. "heard knock") in the CSV and on the graphs
- Red alerts: engine temp ≥ 120 °C, battery < 12.5 V while running, O2 lean (< 0.45 V) at high throttle
- **Simulator** mode to try the app without a bike
- **Ride viewer** (*Open CSV*): tick the values you want, and each one gets its own graph on a shared time axis
  - Presets: Overview / Fuel / Ignition / Health / Everything
  - **One graph at a time** with ◀ ▶ (or Page Up / Page Down) to flip through values
  - Move the mouse over any graph to read **every value at that moment**, shown on each graph next to the cursor and in the side panel
  - min / avg / max for the time range in view; click a marker to jump to it
  - Mouse wheel zooms time; your last selection is remembered
  - Drop a CSV onto the .exe, or run `python viewer.py ride.csv`, to open it directly
  - The cursor also shows which **fuel / ignition map cell** (RPM × TPS) the engine was in
  - **Cell hits (map table)**: time, O2, lean share and injector time per map cell for the range in view, to see which cells need tuning
- **Fault codes (DTC)**: read once on connect and with *Read again*; the Faults button turns green (none), orange (past) or red (current). Read only, codes are never cleared
- Auto-reconnect if the K-line link drops or the cable is unplugged (a running recording carries on)

## Android app
A recorder for the phone, so you don't have to carry the laptop on rides. Built and tested in the emulator; first run on a real phone and bike still to come.

Made for a phone on the handlebar (portrait):
- Tabs **Live | Graphs | Logs**. Live **tiles** in the same colors as the PC app fill the screen, so the fewer values you show, the bigger the numbers; or live **graphs** (10 / 30 / 60 s)
- **Filter** (⋮ menu) picks the values on screen; Mode and Gear are hidden by default. Hidden values are still recorded
- Two big buttons: **Connect** and **Start Record**. While recording they become **⚑ Marker** (the phone buzzes) and **■ Stop**, which needs a long press so a bump can't end the recording. The screen stays on while recording
- Saves the same CSV as the PC app (Documents/HondaECULogger on the phone), so phone rides open in the PC viewer
- **GPS speed** from the phone (speed only, location is never stored), in the `gps_kmh` column
- **Logs**: open a recorded ride (from the phone or the laptop) with zoomable graphs and a cursor, a **ride summary** and a **map cell table** (landscape)
- Status and fault-code chips at the top (tap for details); Simulator and About in the ⋮ menu
- Keeps recording with the screen off too (foreground service)

What you need: an Android phone with USB OTG, an **OTG adapter** (USB-C to USB-A) and the same FTDI K-line cable. **No driver to install**: the app talks to the FTDI chip itself. On the first connect Android asks to allow the app to use the USB device; press Allow.

Build (Android Studio, or from the command line with `JAVA_HOME` set to Android Studio's `jbr`):
```bat
android\gradlew.bat testDebugUnitTest assembleDebug
```
Output: `android\app\build\outputs\apk\debug\app-debug.apk`; copy it to the phone and install it (allow installing from unknown sources).

### Release APK (for GitHub Releases)
Signed with your own key, so every later version installs as an update.

1. Once: create the signing key outside the project (it asks for a password; there is only one).
   In PowerShell:
   ```powershell
   New-Item -ItemType Directory -Force "$env:USERPROFILE\Documents\H-ECU-keys"
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -keystore "$env:USERPROFILE\Documents\H-ECU-keys\h-ecu-release.jks" -alias h-ecu -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=FACEPOT40"
   ```
   (In Command Prompt use `%USERPROFILE%` instead of `$env:USERPROFILE` and leave out the `&`.)
   Copy `android\keystore.properties.example` to `android\keystore.properties` and fill in the path and password. **Back up the `.jks` file and the password**: without them you can never publish an update. Neither ever goes into git.
2. Each release: raise `versionCode` (and `versionName`) in `android\app\build.gradle.kts`, then
   ```bat
   build_apk_release.bat
   ```
   Output: `dist\H-ECU-Logger-<version>.apk`. Upload it on GitHub: *Releases → Draft a new release*, tag e.g. `v0.3.0`.

A phone with a debug build installed must uninstall it once before the first release build (different key). Recorded rides stay in Documents/HondaECULogger; open older ones with *Open other file*.

## Tested hardware
| Item | Value |
|---|---|
| Bike | Honda Wave 125i (2021), ECU 38770-K73-T61 (ECM ID 01 03 3E 0D 01) |
| Cable | FTDI FT232R USB-to-K-line, on the red 4-pin DLC |
| Driver | Official FTDI VCP driver (shows as a COM port) |

The table 0x17 layout follows the RE-ECU-th PGM-FI live data sheet and was checked against real Wave logs: RPM, TPS (full throttle reads 93.75 %, the ECU's own scale), engine temp, battery and ignition are confirmed. The injector scale (HondaECU's, about 4 µs per count) was checked against ECU-SHOP and the tune's fuel map. ⚠️ STFT never moves on the test bike (its tune probably runs without O2 feedback), so that formula is unverified. Raw bytes are always saved (`raw_17`, `raw_20`) so values can be recomputed later.

## Install & run (Windows)
1. Install **Python 3.12** from python.org (tick *Add Python to PATH*).
2. In this folder:
   ```bat
   pip install -r requirements.txt
   python app.py
   ```
3. Tick **Simulator** and press **Connect** to try it without a bike.

### On the bike
1. Cable in the DLC, **key ON** (engine off or running), kill switch on RUN.
2. Close other programs using the cable (HondaECU, Honda Flash EcuK-Line Tools).
3. Choose the COM port, untick Simulator, **Connect**.
4. **Start Record** before setting off, **Stop** when back. Logs are saved in `logs\ride_YYYYMMDD_HHMMSS.csv`.

### Build a single .exe
```bat
build_exe.bat
```
Output: `dist\H-ECU-Logger.exe`

### Build the Windows installer (app + FTDI driver)
Needs [Inno Setup 6](https://jrsoftware.org/isinfo.php) (`winget install JRSoftware.InnoSetup`) and FTDI's official VCP driver package from ftdichip.com, unzipped as `CDM-v2.12.36.20-WHQL-Certified` in this folder (it is not in git: it's FTDI's).
```bat
build_installer.bat
```
Output: `dist\H-ECU-Logger-Setup-<version>.exe`. It installs the app into Program Files with Start menu shortcuts and an uninstall entry, and (ticked by default) installs the FTDI cable driver with Windows' own `pnputil`. Ride logs go to `Documents\HondaECULogger\logs`.

## CSV columns
`timestamp, elapsed_s, rpm, tps_pct, ect_c, batt_v, speed_kmh, inj_ms, ign_deg, o2_v, stft, mode_d14, neutral, gps_kmh, marker, raw_17, raw_20, raw_d1`

`neutral` is 1/0 (1 = in neutral). `gps_kmh` is the phone's GPS speed (empty in PC logs or without a GPS fix). `mode_d14` is the raw byte 14 of table 0x17: the PGM-FI sheet calls it fast idle, but on the Wave it is always 128 (0x80), so its meaning is unknown there.

## Protocol test tool
`tools/m0_probe.py` is a console script that wakes the ECU, reads the ECM ID and prints raw table bytes. Use it to verify a new bike/ECU:
```bat
python tools\m0_probe.py --list
python tools\m0_probe.py --port COM3 --seconds 30 -v
python tools\m0_probe.py --port COM3 --dtc -v      # read fault codes only
```

## Credits
- Protocol notes: [AutotronicCommunity/Honda_Keihin_KLine_Protocol](https://github.com/AutotronicCommunity/Honda_Keihin_KLine_Protocol)
- Value formulas: [HondaECU](https://github.com/aeaphichart/HondaECU-1) (MCU Innovations / RyanHope, GPL-3.0)
- Table layout: RE-ECU-th "Protokol Live Data PGM-FI Honda Bike" sheet

## License
GPL-3.0
