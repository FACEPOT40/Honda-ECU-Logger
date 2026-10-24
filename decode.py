"""Raw table bytes -> real units.

Layout and formulas follow the RE-ECU-th "Protokol Live Data PGM-FI Honda Bike"
sheet, checked against real Wave 125i (K73-T61) logs on 24 Oct 2026.
d = payload after "71 <table>"; the sheet numbers the same bytes D9, D10, ...
(d[0] = D9). Raw bytes are always logged so values can be recomputed later.

Table 0x17 (19 bytes on the Wave):
  d0-1  RPM (16-bit)            confirmed (idle ~1000, revs to 5400)
  d2    TPS volts  *5/256       confirmed
  d3    TPS %      /1.6         confirmed: raw 150 at full throttle = 93.75 %, the ECU's own
                                scale (same as HondaECU). The sheet's *0.5 would read 75 %,
                                and /1.6 lines up better with the tune's ignition map columns
  d4    ECT volts  *5/256
  d5    ECT °C     -40          confirmed. On the air-cooled Wave this is the
                                engine (oil) temp sensor
  d6-7  0xFF on the Wave
  d8    EOT volts  *5/256       0xFF on the Wave (not fitted)
  d9    EOT °C     -40          0xFF on the Wave (not fitted)
  d10   Battery V  *0.1         confirmed (11.7 key on, 8.8 cranking, 13-14 running)
  d11-12 Injector (16-bit) / INJ_DIVISOR ms   position confirmed (0 on decel
                                fuel cut), divisor unverified
  d13   Ignition °BTDC -64+*0.5 confirmed against the ignition map
  d14   Mode (raw)              the sheet calls it fast idle (0x80 = on), but on the Wave
                                it is 0x80 both cold (41 °C) and hot (129 °C idle), so its
                                meaning is unknown here. Logged raw for other Honda bikes
  d15   Speed km/h              only on bikes with an ECU speed sensor. Always 0 on the
                                Wave: cable-driven speedometer, no sensor (0 on a ride and
                                in a wheel-spinning scan; ECU-SHOP's speed is phone GPS).
                                Kept for other Honda bikes

Table 0x20: d0 O2 *5/256, d1 STFT /128 (128 = 1.00), d2 O2 heater *5/256.

Table 0xD1 (6 bytes on the Wave): d0 00 = in gear, 01 = neutral, 02/03 = side-stand
switch states (sheet; the Wave has none); d4 fuel pump 01 = on; d5 fan (0x10 on the
Wave, which has no fan). The ECU only knows neutral / in gear, not which gear.
"""

# ms = raw16 / INJ_DIVISOR. 200 from the RE-ECU-th sheet; HondaECU uses
# 65535/265.5 (~246.8). Unverified: idle reads ~4.5 ms with 200, ~3.6 ms with 246.8.
INJ_DIVISOR = 200

FIELDS = [
    # key, label, unit, decimals
    ("rpm", "RPM", "rpm", 0),
    ("tps_pct", "TPS", "%", 1),
    ("ect_c", "Eng temp", "°C", 0),
    ("batt_v", "Batt", "V", 1),
    ("speed_kmh", "Speed", "km/h", 0),
    ("inj_ms", "Inj", "ms", 2),
    ("ign_deg", "Ign", "°", 1),
    ("o2_v", "O2", "V", 2),
    ("stft", "STFT", "", 3),
    ("mode_d14", "Mode (d14)", "", 0),
    ("neutral", "Gear", "", 0),
]
DECIMALS = {k: dec for k, _, _, dec in FIELDS}

# on/off values: shown as words, stored as 1/0 so they can be graphed
TEXT = {
    "neutral": {0: "Gear", 1: "N"},
}


def fmt_value(key, v):
    """Text for a value on screen: '-' when missing, words for on/off values."""
    if v is None:
        return "-"
    if key in TEXT:
        return TEXT[key].get(int(v), str(v))
    return f"{v:,.{DECIMALS[key]}f}"


def _temp(raw):
    """°C from a raw temperature byte; 0xFF = sensor not fitted."""
    return None if raw == 0xFF else raw - 40


def decode_17(d: bytes) -> dict:
    if len(d) < 16:
        return {}
    return {
        "rpm": d[0] << 8 | d[1],
        "tps_v": round(d[2] * 5 / 256, 2),
        "tps_pct": round(d[3] / 1.6, 2),
        "ect_c": _temp(d[5]),
        "eot_c": _temp(d[9]),
        "batt_v": round(d[10] * 0.1, 1),
        "inj_ms": round((d[11] << 8 | d[12]) / INJ_DIVISOR, 2),
        "ign_deg": round(-64 + d[13] * 0.5, 1),
        "mode_d14": d[14],
        "speed_kmh": d[15],
    }


def decode_20(d: bytes) -> dict:
    if len(d) < 3:
        return {}
    return {
        "o2_v": round(d[0] * 5 / 256, 2),
        "stft": round(d[1] / 128, 4),
        "o2_heater_v": round(d[2] * 5 / 256, 2),
    }


def decode_d1(d: bytes) -> dict:
    if len(d) < 1:
        return {}
    return {"neutral": 1 if d[0] == 0x01 else 0}


DECODERS = {0x17: decode_17, 0x20: decode_20, 0xD1: decode_d1}


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
