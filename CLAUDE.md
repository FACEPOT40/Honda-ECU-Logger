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
- `decode.py`: `FIELDS`, `decode_17`, `decode_20`, `alerts`
- `worker.py`: `PollWorker` QThread (connect, ECM ID, poll 0x17 + 0x20, auto-reconnect)
- `recorder.py`: CSV writer (wall-clock `timestamp` primary, `elapsed_s`, marker, raw bytes)
- `app.py`: main window (live values, 4 dual-axis graphs, record, marker `M`)
- `viewer.py`: ride viewer (tick values, one graph per value, presets, one-at-a-time, cursor readout)
- `tools/m0_probe.py`: console protocol probe

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
- **Start Record button can't be clicked** (reported 23 Oct 2026, v0.2.0). Likely cause found
  and fixed 23 Oct: logs went to a `logs` folder relative to the current folder, so starting the
  app from a non-writable folder made `Recorder.start()` raise PermissionError silently. Now
  `recorder.default_log_dir()` (next to app/.exe, fallback Documents\HondaECULogger\logs) plus an
  error popup. Not yet confirmed by Pai; if it still happens, ask for console output / exact steps.
- **M0 never run on the real bike**: our code has only talked to `SimECU`. Values can't be trusted yet.
- Table 0x17 layout (inj/ign/batt/speed positions) comes from HondaECU and is **unverified on
  the Wave**. Verify against real raw bytes (`raw_17` column) before trusting decoded values.
- STFT formula (raw/255*2) unverified; one real reading was 1.0039.
