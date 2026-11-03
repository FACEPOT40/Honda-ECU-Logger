# H-ECU Logger (K-line): notes for Claude

Name (3 Nov 2026, Pai's choice): **H-ECU Logger**, subtitle **K-line** (room for "K-line / CAN"
later). Was "Honda ECU Logger". Storage names stay `HondaECULogger` on purpose (PC fallback log
folder, viewer QSettings, phone Documents/HondaECULogger) so old rides and settings are still
found; the Android package stays `com.pai.hondaecu` (changing it would make a separate app).
Keep the Honda wing / Honda styling out of the branding (trademark).
Icon (3 Nov, Pai's pick "F1, deep blue + amber"): italic H (#1976d2) with an amber (#ffa000)
lightning bolt through the crossbar, on near-black #08090c. Source `assets/icon.svg`;
`python tools\make_icon.py` rebuilds `assets/icon.ico` (PC window/taskbar via `viewer.app_icon()`,
and the .exe via `build_exe.bat`). Android: adaptive `res/drawable/ic_launcher*.xml` (same shapes,
scaled 0.46 into the safe zone; `_mono` for themed icons) and the white notification icon `ic_app`.
Keep all of them in step when the design changes.

Windows desktop app (Python, PySide6 + pyqtgraph + pyserial) that logs live data from a
Honda/Keihin K-line ECU and records rides to CSV. Owner: Pai (IT support, learning; explain
changes briefly and plainly). License GPL-3.0. Full background: `docs/PLAN.md`.

## Hard rules
- **Read-only, always.** Only wake (`FE 04 72 8C`), init (`72 05 00 F0 99`), table queries
  (`72 05 71 <table> <cs>`) and fault-code reads (`72 05 74 <page> <cs>` current,
  `72 05 73 <page> <cs>` past) may be sent, each exactly 5 bytes with a correct checksum.
  `kline.allowed()` and `Protocol.isAllowed()` enforce this. The two fault-code reads were added
  3 Nov 2026 with Pai's explicit OK ("read only"). Never clear fault codes (`72 05 60 03`), never
  add write / flash / EEPROM / security-access commands, and never widen the whitelist further
  without Pai's explicit decision.
- Never commit third-party `.bin`, `.xdf`, `.dat`, `.exe` files or real ride logs (`logs/`).
  The XDF author forbids redistribution.
- Don't touch HondaECU / Honda Flash tool binaries (no reverse engineering or bypassing protection).

## Files
- `kline.py`: serial link (10400 8N1, break-based wake, echo detection, checksum), whitelist
  `allowed()`, `read_dtcs()`, `SimECU` (has one past fault, 07-01)
- Fault codes (3 Nov): request/reply from HondaECU's open-source `threads/kline.py` +
  `frames/error.py` (0x74 = current, 0x73 = past, pages 1..11, codes as (main, sub) pairs at
  payload bytes 1-6). `decode.DTC_NAMES` names only common Honda PGM-FI groups (eculib's list is
  gone); main number = MIL blinks. Read once per connection + "Read again" (PC Faults button,
  phone Faults button). `m0_probe.py --dtc` reads them. Not yet tried on the real ECU.
- `decode.py`: `FIELDS`, `decode_17`, `decode_20`, `decode_d1`, `DECODERS`, `TEXT`/`fmt_value`,
  `alerts` (byte map in docstring)
- `worker.py`: `PollWorker` QThread (connect, ECM ID, poll 0x17 + 0x20 every cycle and 0xD1
  every 5th (`EVERY`), auto-reconnect)
- `recorder.py`: CSV writer (wall-clock `timestamp` primary, `elapsed_s`, marker, raw bytes)
- `app.py`: main window (live values, 4 dual-axis graphs, record, marker `M`)
- `viewer.py`: ride viewer (tick values, one graph per value, presets, one-at-a-time, cursor readout,
  map cell at the cursor, "Cell hits (map table)" window)
- `cells.py`: map cell math (3 Nov): the tune's RPM / TPS map axes (`RPM_AXIS`, `TPS_AXIS`, same
  for fuel and ignition), nearest cell, `steady()` and `hits()` (time, O2, lean %, injector per
  cell; O2 only with fuel on, sensor warm <= 1.2 V, optionally steady). Analysis only.
- `tools/m0_probe.py`: console protocol probe (uses `decode.py` and `kline.SimECU`)

- `android/`: Kotlin recorder app (tiles, live graphs, Start/Stop Record, marker, recorded-ride viewer). Same
  read-only whitelist (`Protocol.kt`) and same CSV. `Decode.kt` / `CsvFormat.kt` must stay in step
  with `decode.py` / `recorder.py`. Build: `android\gradlew.bat testDebugUnitTest assembleDebug`
  with `JAVA_HOME` = Android Studio's `jbr`. `DecodeTest` compares with Python vectors.
  Tested 30 Oct on the emulator (`Medium_Phone_API_37.0`) in Simulator mode: tiles, record, marker,
  short screen-off, disconnect, no-cable message; its CSV matches `decode.py` and opens in the
  viewer. The emulator has no USB, so `KLine.kt` and the FTDI link are not yet run on a phone or the bike.
  Tiles use the same per-value colors as `viewer.py`, and a Values button hides tiles (saved on the
  phone); hiding is display only, the CSV keeps every value. Built 30 Oct, not yet seen on a screen.
  Live graphs (3 Nov): a Graphs/Tiles button switches the screen; `GraphView.kt` draws one colored
  panel per shown value over a 10/30/60 s window, with marker lines. `History.kt` keeps the last
  ~3,000 samples, filled by `LoggerService`, cleared on Connect; recording does not use it.
  Unit-tested (`HistoryTest`) and checked 3 Nov on a windowless emulator (`-no-window`, with
  Pai's OK): graphs, window buttons, markers, filter, hidden values still fully in the CSV.
  Recorded rides (3 Nov): Rides button -> `RidesActivity` (the app's own CSVs via MediaStore, plus
  "Open other file" for laptop CSVs) -> `RideViewActivity`, which reuses `GraphView` in recorded
  mode (pinch zoom, drag to move, tap cursor, min/avg/max, marker jump buttons). `RideData.kt`
  ports `load_csv` (columns by name, so old PC CSVs open). `Ui.kt` holds shared colors (`Look`)
  and the Values filter (`ValueFilter`). Unit-tested (`RideDataTest`; `-Dride.file=` loads a real
  CSV and matched Python on two rides); the screens are not yet seen on a phone.
  Ride summary + cell table on the phone (3 Nov): `Cells.kt` ports `cells.py` (must stay in step;
  `CellsTest -Dride.file=` + a Python compare gave 0 differences on 270 and 228 cells of two rides),
  `RideSummary.kt` (length, running/idle/fuel-cut time, max RPM/TPS, injector duty, temp and time
  >= 120 °C, battery low, GPS speed, O2 lean share, 3 leanest cells), `CellsActivity` +
  `CellGridView` (drag-scroll grid with sticky RPM/TPS headers, tap for details) for the range in
  view; `OpenRide` shares the loaded ride. The viewer's cursor line also names the map cell.
  Don't start the emulator unless Pai asks: the window it opens from the command line doesn't fit
  Pai's display. Pai runs it from Android Studio's Device Manager.

## Run / test
```
pip install -r requirements.txt
python app.py            # tick Simulator -> Connect to test without the bike
python viewer.py logs\ride_x.csv
build_exe.bat            # dist\H-ECU-Logger.exe
```

## Bike / ECU
Honda Wave 125i 2021, ECU 38770-K73-T61, ECM ID `01 03 3E 0D 01`. FTDI FT232R K-line cable
on the 4-pin DLC, official FTDI VCP driver (COM port). Close HondaECU / Flash tool before connecting.
Engine is modified (54 mm bore, 28 mm TB, cam, 125cc injector, E20, custom tune), so values
may look unusual.

## Known open issues
- ~~Start Record button can't be clicked~~ (reported 23 Oct 2026, v0.2.0). **Fixed and confirmed
  by Pai 23 Oct**: logs went to a `logs` folder relative to the current folder, so starting the
  app from a non-writable folder made `Recorder.start()` raise PermissionError silently. Now
  `recorder.default_log_dir()` (next to app/.exe, fallback Documents\HondaECULogger\logs) plus an
  error popup.
- ~~PC app sometimes crashes when the cable is unplugged before Disconnect~~ (reported 3 Nov).
  **Fixed 3 Nov**, confirmed with a fake link, not yet on the bike. Causes: a serial error in the
  poll loop killed the thread silently (stale "Connected", port never closed), and Disconnect
  dropped a QThread still stuck on the dead port after `wait(3000)`, which makes Qt abort the
  process. Now `PollWorker` catches errors per connection and keeps retrying (replugging resumes
  logging and a running recording), `stop()` cancels a blocked read/write (`KLine.abort`, plus
  `write_timeout=1`), `MainWindow.stop_worker` keeps an unfinished thread in `self.stopping`
  (terminated on quit), and `Recorder` flushes about once a second.
- **M0 run on the real bike 24 Oct 2026** (key-on + engine-running logs, not committed).
  Link works (echo on, ~20 reads/s per table). Table 0x17 layout now follows the RE-ECU-th
  sheet, checked byte by byte against those logs; full map in the `decode.py` docstring.
  The old HondaECU layout (with its 0xFF insertion) was wrong for the injector on the Wave.
  Confirmed: RPM, TPS V, TPS % (/1.6: raw 150 at full throttle = 93.75 %), ECT (d5, the
  Wave's only temp sensor; EOT d9 = 0xFF), battery, ignition (matches Pai's ignition map
  within ~1.5°), injector position d11-12. The app itself has recorded on the real bike.
  `INJ_DIVISOR` = 65535/265.5 (~246.8, HondaECU) since 25 Oct, not the sheet's 200: matches
  ECU-SHOP (3.69 vs our 3.90 ms at 4,083 rpm / 8 %) and makes logged raw = fuel base map / 4
  + ~54, i.e. the tune's map is in µs. WOT duty at 8,200 rpm ~74 %. CSVs recorded before
  25 Oct store inj_ms with /200 (about 23 % too high); raw_17 has the true value. Pai chose
  not to have the viewer recompute old logs.
  Still open:
  - STFT /128: stays 128 even on a hot 6-min ride (25 Oct), so closed loop is probably off
    in Pai's custom tune. ECU-SHOP's "TRIM -10.1" (Aug, likely older tune) is probably the
    same byte as a %: (raw-128)/128*100, raw 115 = -10.2 %
- d14: the sheet's "fast idle" (0x80 = on) is 0x80 both cold and at 129 °C idle on the Wave,
  so it is logged as raw `mode_d14` ("Mode (d14)"), meaning unknown. Renamed 25 Oct; older
  CSVs have a `fast_idle` column instead.
- Hot ride 25 Oct: ECT 117-129 °C all ride (stable, not climbing). Mechanic: up to ~130-140 °C
  for an air-cooled engine, "but that does not mean it's good". Pai chose red at >= 120 °C.
- **No speed** on this bike: cable-driven speedometer, no ECU sensor. d15 stayed 0 on a ride
  and in a wheel-spinning scan; the mechanic confirmed ECU-SHOP's speed is phone GPS. Pai
  wants Speed **kept** in the app anyway (shows 0 on the Wave) for other Honda bikes.
- **GPS speed** (3 Nov, Android only): `GpsSpeed.kt` reads the phone's GPS speed (~1/s) into a
  `gps_kmh` field and CSV column (after `neutral`, also in `decode.py` FIELDS, `recorder.py` and
  `viewer.py` COLORS so both sides stay in step; empty in PC logs). Each sample repeats the last
  fix; no fix for 3 s = empty. Pai chose **speed only**: never store latitude/longitude.
  Location permission is asked at start (refusing is fine); the service adds the `location` type
  only when it was granted. Built and unit-tested; not yet tried outdoors on the phone.
- `m0_probe.py --scan` (24 Oct): the Wave supports only 0x17, 0x20, 0x67 (mirror of 0x17),
  0x70 (mirror of 0x20) and 0xD1 (6 bytes: d0 neutral 01 / in gear 00, d4 fuel pump 01 = on,
  d5 fan = 0x10, meaningless, no fan). The ECU only knows neutral / in gear, not which gear.
- **Gear (neutral) value** added 24 Oct from 0xD1 d0: `worker.EVERY` polls 0xD1 only every 5th
  cycle and repeats its last value in each sample. `neutral` is stored as 1/0 and shown as
  N / Gear via `decode.TEXT` / `fmt_value`. Confirmed live on the 25 Oct ride. That ride
  logged 11.6 samples/s (was ~14 before 0xD1); raise `EVERY` if more speed is wanted.
