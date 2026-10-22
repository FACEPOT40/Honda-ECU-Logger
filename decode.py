"""Raw table bytes -> real units.

Formulas follow HondaECU (src/frames/data.py). The table 0x17 field layout is
NOT yet verified on the Wave 125i; raw bytes are always logged so values can be
recomputed later if a position or formula turns out to be wrong.
"""

import struct

FIELDS = [
    # key, label, unit, decimals
    ("rpm", "RPM", "rpm", 0),
    ("tps_pct", "TPS", "%", 1),
    ("ect_c", "ECT", "°C", 0),
    ("batt_v", "Batt", "V", 1),
    ("speed_kmh", "Speed", "km/h", 0),
    ("inj_ms", "Inj", "ms", 2),
    ("ign_deg", "Ign", "°", 1),
    ("o2_v", "O2", "V", 2),
    ("stft", "STFT", "", 3),
]

FMT_17 = ">H12BHBBB"


def decode_17(d: bytes) -> dict:
    n = struct.calcsize(FMT_17)
    if len(d) < n:
        return {}
    v = list(struct.unpack(FMT_17, d[:n]))
    v = v[:9] + [0xFF, 0xFF] + v[9:]          # HondaECU insertion for 0x13/0x17
    out = {
        "rpm": v[0],
        "tps_v": round(v[1] / 255 * 5, 2),
        "tps_pct": round(v[2] / 1.6, 2),
        "ect_c": v[4] - 40,
        "batt_v": round(v[11] / 10, 2),
        "speed_kmh": v[12],
        "inj_ms": round(v[13] / 0xFFFF * 265.5, 2),
        "ign_deg": round(-64 + v[14] / 255 * 127.5, 2),
    }
    out["iat_c"] = None if v[6] == 0xFF else v[6] - 40     # 0xFF = not available
    out["map_mbar"] = None if v[8] == 0xFF else v[8] * 10
    return out


def decode_20(d: bytes) -> dict:
    if len(d) < 3:
        return {}
    return {
        "o2_v": round(d[0] / 255 * 5, 2),
        "stft": round(d[1] / 255 * 2, 4),
        "o2_heater_v": round(d[2] / 255 * 5, 2),
    }


def alerts(s: dict) -> set:
    """Return keys that should be shown in red."""
    bad = set()
    ect, batt, rpm = s.get("ect_c"), s.get("batt_v"), s.get("rpm") or 0
    o2, tps = s.get("o2_v"), s.get("tps_pct") or 0
    if ect is not None and ect > 110:
        bad.add("ect_c")
    if batt is not None and rpm > 1500 and batt < 12.5:
        bad.add("batt_v")
    if o2 is not None and o2 < 0.45 and tps > 50 and rpm > 3000:
        bad.add("o2_v")                       # lean at high load
    return bad
