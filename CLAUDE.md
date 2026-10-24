# Honda ECU Logger: notes for Claude

Windows desktop app (Python, PySide6 + pyqtgraph + pyserial) that logs live data from a
Honda/Keihin K-line ECU and records rides to CSV. Owner: Pai (IT support, learning; explain
changes briefly and plainly). License GPL-3.0. Full background: `docs/PLAN.md`.

## Hard rules
- **Read-only, always.** Only wake (`FE 04 72 8C`), init (`72 05 00 F0 99`) and table queries
  (`72 05 71 <table> <cs>`) may be sent. `kline.ALLOWED_PREFIXES` enforces this. Never add
  write / flash / EEPROM / security-access commands, and never widen the whitelist.
- Never commit third-party `.bin`, `.xdf`, `.dat`, `.exe` files or real ride logs (`logs/`).
  The XDF author forbids redistribution.
- Don't touch HondaECU / Honda Flash tool binaries (no reverse engineering or bypassing protection).

## Files
- `kline.py`: serial link (10400 8N1, break-based wake, echo detection, checksum), `SimECU`
- `decode.py`: `FIELDS`, `decode_17`, `decode_20`, `DECODERS`, `alerts` (byte map in docstring)
- `worker.py`: `PollWorker` QThread (connect, ECM ID, poll 0x17 + 0x20, auto-reconnect)
- `recorder.py`: CSV writer (wall-clock `timestamp` primary, `elapsed_s`, marker, raw bytes)
- `app.py`: main window (live values, 4 dual-axis graphs, record, marker `M`)
- `viewer.py`: ride viewer (tick values, one graph per value, presets, one-at-a-time, cursor readout)
- `tools/m0_probe.py`: console protocol probe (uses `decode.py` and `kline.SimECU`)

## Run / test
```
pip install -r requirements.txt
python app.py            # tick Simulator -> Connect to test without the bike
python viewer.py logs\ride_x.csv
build_exe.bat            # dist\HondaECULogger.exe
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
- **M0 run on the real bike 24 Oct 2026** (key-on + engine-running logs, not committed).
  Link works (echo on, ~20 reads/s per table). Table 0x17 layout now follows the RE-ECU-th
  sheet, checked byte by byte against those logs; full map in the `decode.py` docstring.
  The old HondaECU layout (with its 0xFF insertion) was wrong for the injector on the Wave.
  Confirmed: RPM, TPS V, TPS % (/1.6: raw 150 at full throttle = 93.75 %), ECT (d5, the
  Wave's only temp sensor; EOT d9 = 0xFF), battery, ignition (matches Pai's ignition map
  within ~1.5°), injector position d11-12. The app itself has recorded on the real bike.
  Still open:
  - `INJ_DIVISOR` (200 from sheet vs ~246.8 HondaECU). Needs a warm idle (fast idle off)
    compared against the tune's fuel map; cold logs include start enrichment
  - STFT /128: only seen at 128 (cold engine, open loop); verify warm. ECU-SHOP's
    "TRIM -10.1" is probably the same byte as a %: (raw-128)/128*100, raw 115 = -10.2 %
- **No speed** on this bike: cable-driven speedometer, no ECU sensor. d15 stayed 0 on a ride
  and in a wheel-spinning scan; the mechanic confirmed ECU-SHOP's speed is phone GPS. Speed
  was removed from FIELDS / CSV (24 Oct). Idea for later: merge a phone GPS track by timestamp.
- `m0_probe.py --scan` (24 Oct): the Wave supports only 0x17, 0x20, 0x67 (mirror of 0x17),
  0x70 (mirror of 0x20) and 0xD1 (6 bytes: d0 neutral 01 / in gear 00, d4 fuel pump 01 = on,
  d5 fan = 0x10, meaningless, no fan). 0xD1 is not polled yet; polling it every few cycles
  would add a neutral/gear column without slowing the main tables much.
