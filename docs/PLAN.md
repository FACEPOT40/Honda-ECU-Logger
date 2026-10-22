# Honda ECU Logger: Project Plan

A desktop app that reads live diagnostic data from Honda motorcycle ECUs over K-line, shows it as **numbers and real-time graphs**, and **records it to CSV** with Start / Stop Record. The goal is ride-and-review: you can't look at a screen while riding, so you record the ride and analyze the logs afterwards to check road-tuning results.

- **Test bike:** Honda Wave 125i (2021), ECU **38770-K73-T61**, ECM ID **01 03 3E 0D 01**
- **Cable:** FTDI FT232R USB-to-K-line (connects to the red 4-pin DLC)
- **First platform:** Windows (.exe), Android (WebUSB) later
- **Read-only:** the code contains no ECU write commands
- **GitHub repo:** `Honda-ECU-Logger` (private at first, GPL-3.0 license, README states "Not affiliated with Honda Motor Co.")
- **Never commit:** third-party `.bin`, `.xdf`, `.dat`, `.exe` files or logs from the real bike (blocked via `.gitignore`)

---

## 1. Tech stack

| Part | Choice | Why |
|---|---|---|
| Language | Python 3.12 | Modern; avoids HondaECU's outdated Python 3.7 dependencies |
| UI | PySide6 (Qt) | Modern-looking desktop UI |
| Graphs | pyqtgraph | Smooth real-time plotting |
| Cable I/O | pyserial + official FTDI driver (COM port) | No libusbK, no dependency on `eculib` (gone from GitHub and PyPI) |
| Packaging | PyInstaller | Single double-click .exe |

---

## 2. UI (draft)

```
┌────────────────────────────────────────────────────────────────┐
│ [COM3 ▼] [Connect]  ● Connected  ECM: 01 03 3E 0D 01 (K73-T61) │
│ [● Start Record] [■ Stop]  ● REC 12:01:01 (started 11:58:46) [⚑]│
├──────────────┬─────────────────────────────────────────────────┤
│ RPM    5,820 │  Graph 1: RPM + TPS                             │
│ TPS     68 % │  Graph 2: O2 + STFT                             │
│ ECT    104°C │  Graph 3: Ignition + Injector                   │
│ Batt  13.8 V │  Graph 4: Engine temp + Battery                 │
│ Speed  72 km │                                                 │
│ Inj   4.1 ms │  Window: [10s] [30s] [60s]        ⚑ = Marker    │
│ Ign   31.0 ° │                                                 │
│ O2    0.78 V │  ⚠️ Out-of-range values turn red                 │
│ STFT   1.02  │                                                 │
└──────────────┴─────────────────────────────────────────────────┘
```

### Features
- Choose COM port / Connect / show ECM ID and ECU part number
- Live numbers + real-time graphs (10 / 30 / 60 s window)
- **Start / Stop Record** → CSV with timestamps
- **Wall-clock time first:** the screen shows the real clock (e.g. `12:01:01`) and the record start time; graph time axis uses wall-clock time
- `elapsed_s` (seconds since Start Record) is **always saved in the CSV but hidden on the dashboard by default** (can be enabled in settings)
- **Marker:** press to mark an event (e.g. heard a "tick tick" knock sound); saved in CSV and drawn as a line on the graph
- **Red alerts:** engine temp > 110°C, O2 on the lean side (< 0.45 V) at high throttle, low battery
- **Simulator mode:** generates fake data to test the UI without the bike
- **Open CSV:** load an old log and view it as graphs
- Auto-reconnect on connection loss without losing the recording

---

## 3. CSV format

```
timestamp,elapsed_s,rpm,tps_pct,ect_c,batt_v,speed_kmh,inj_ms,ign_deg,o2_v,stft,marker,raw_17,raw_20
2026-10-23 08:15:02.341,0.000,1420,0.0,62,13.9,0,1.20,16.2,0.45,1.00,,<hex>,<hex>
```

- `timestamp`: **wall-clock time (primary)**, date + time to the millisecond, from the computer's clock
- `elapsed_s`: seconds since Start Record (saved, hidden on screen by default)
- `marker`: marker text/number (empty = none)
- `raw_17`, `raw_20`: raw table bytes (hex), so values can be recomputed later if a formula turns out to be wrong, without riding again

---

## 4. K-line protocol (Honda / Keihin)

References: [AutotronicCommunity/Honda_Keihin_KLine_Protocol](https://github.com/AutotronicCommunity/Honda_Keihin_KLine_Protocol) and HondaECU source (`src/threads/kline.py`, `src/frames/data.py`)

| Step | Send | ECU reply |
|---|---|---|
| Wake-up | Pull TX LOW 70 ms → HIGH 120 ms (serial break) | - |
| Baud | 10400 bps, 8N1 | - |
| Wake-up message | `FE 04 72 8C` | `0E 04 72 7C` |
| Init | `72 05 00 F0 99` | `02 04 00 FA` |
| Request ECM ID | `72 05 71 00 18` | |
| Request table 0x17 (main values) | `72 05 71 17 01` | |
| Request table 0x20 (O2/STFT) | `72 05 71 20 F8` | |

- **Message format:** `72` + length (incl. checksum) + query code + table number + checksum
- **Checksum:** `(0x100 − sum of all bytes) AND 0xFF`
- **Talk to the ECU at least every 2 seconds**, or it goes back to sleep
- **K-line is single-wire:** transmitted bytes are echoed back and must be read and discarded before reading the reply

---

## 5. Value decoding (from HondaECU `data.py`)

### Table 0x17 (main values)
| Value | Formula | Notes |
|---|---|---|
| Engine speed (rpm) | 16-bit value (big-endian) | |
| TPS (V) | raw ÷ 255 × 5 | |
| TPS (%) | raw ÷ 1.6 | HondaECU's own scale; full throttle reads 93.75% (raw 150) |
| ECT (°C) | raw − 40 | Engine (oil) temperature |
| IAT (°C) | raw − 40 | **Wave sends 0xFF (= 215°C) = not available** |
| MAP (mBar) | raw × 10 | **Wave sends 0xFF (= 2550) = not available** |
| Battery (V) | raw ÷ 10 | |
| Vehicle speed (km/h) | raw | |
| Injector (ms) | raw16 ÷ 65535 × 265.5 | |
| Ignition (°) | −64 + raw ÷ 255 × 127.5 | |

⚠️ HondaECU **inserts two 0xFF bytes** before decoding table 0x17, to match the big-bike layouts it was tested on. We must **verify on the Wave ECU** that the fields after byte 9 (battery, speed, injector, ignition) are in the right positions, by comparing against the ECU-SHOP app.

### Table 0x20 (O2)
| Value | Formula |
|---|---|
| O2 sensor (V) | raw ÷ 255 × 5 (normally swings 0.1–0.9 V when warm) |
| STFT | raw ÷ 255 × 2 (1.00 = no correction) |
| O2 heater (V) | raw ÷ 255 × 5 |

With the engine off or the sensor still cold, O2 reads 5.0 V (raw 0xFF). That's normal.

---

## 6. Code structure

```
Honda-ECU-Logger/
├── app.py            # Entry point + main window (live values, 4 graphs, record, marker)
├── kline.py          # K-line protocol (wake-up, init, table queries), read-only; also SimECU (simulator mode)
├── decode.py         # Raw → real units (tables 0x17, 0x20), alerts
├── worker.py         # Background polling thread (connect, ECM ID, poll 0x17 + 0x20, auto-reconnect)
├── recorder.py       # Start/Stop Record → CSV
├── viewer.py         # Ride viewer for saved CSV logs
├── tools/m0_probe.py # Protocol test script for the real bike (M0)
├── build_exe.bat     # PyInstaller build → dist\HondaECULogger.exe
├── run.bat
├── requirements.txt, requirements-dev.txt
├── docs/PLAN.md      # This file
├── README.md
├── LICENSE           # GPL-3.0
└── .gitignore
```

(The plan originally had a separate `simulator.py` and a `ui/` folder; during development the simulator went into `kline.py` and the whole UI into `app.py`.)

### Safety rules in code
- `kline.py` may only send commands on a **whitelist**: wake-up, init (`00 F0`), table queries (`71 xx`)
- No flash / EEPROM / security-access commands anywhere in the code

---

## 7. Milestones

| Step | What | User action | Status |
|---|---|---|---|
| **M0** | Protocol test script (no UI): wake ECU → read 0x17/0x20 → print raw + decoded values | ⭐ Test on the real bike (key on / engine running) and send the output | ☐ Not tested on the bike (simulator only) |
| **M1** | UI + numbers + graphs + simulator mode | Check the look (no bike needed) | ◐ Built, simulator-tested |
| **M2** | Start/Stop Record, CSV, Marker, alerts | Record a test ride | ◐ Built, Start Record bug |
| **M3** | Open CSV viewer + build .exe | Real use | ◐ Viewer built, .exe not built |
| Later | Android version (WebUSB in Chrome) | Buy a USB OTG adapter | ☐ |

Legend: ☐ not done · ◐ partly done · ☑ done. The cable, driver and ECU are known to work (HondaECU read and flash succeeded), but this app's code has never talked to the real ECU yet, so **no decoded value can be trusted until M0 passes**.

### What M0 must confirm
1. The ECU can be woken through the FTDI driver (COM) using a serial break
2. How long table 0x17 is on the Wave, and whether field positions match HondaECU's decoding
3. Achievable polling rate (expected ~10–20 reads/second)

---

## 8. Before using on the bike

- FT232R appears under **Ports (COM & LPT)** with the official FTDI driver (not libusbK)
- Close other programs using the cable (HondaECU, Honda Flash EcuK-Line Tools) first
- Battery healthy (key on ≥ 12.4 V)
- **Never look at the screen while riding.** Start Record before setting off and review the graphs afterwards

---

## 9. Related references

- File currently in the ECU: `wave125i-54mm_PAI_v2.bin` (first half: shop tune + fuel/ignition edits; second half: from a K73-TC2 file)
- ECU database from `ecuData.dat`: K73-T32 = `01 02 58 0D 01`, **K73-T61 = `01 03 3E 0D 01`**, K73-TC2 = `01 03 4F 18 01`
- HondaECU (open source, Aug 2019): [aeaphichart/HondaECU-1](https://github.com/aeaphichart/HondaECU-1)
