"""Map cells: which fuel / ignition map cell (RPM x TPS) a moment of a ride falls in, and a
"cell hit" summary per cell (time spent, O2, injector). Analysis of a CSV only: nothing is
ever sent to the ECU.

The axes are the K73-T61 tune's map axes, as TunerPro shows them; the fuel and ignition maps
use the same ones. The ECU blends the 4 cells around the current point; here each moment
counts in its nearest cell, the number to look at first.
"""

import numpy as np

RPM_AXIS = np.array([800, 1100, 1200, 1300, 1400, 1500, 1600, 1800, 2000, 2250, 2500, 2750,
                     3000, 3250, 3500, 3750, 4000, 4250, 4500, 5000, 5500, 6000, 6500, 7000,
                     7500, 8000, 8500, 9000, 9400], dtype=float)
TPS_AXIS = np.array([0.0, 0.3, 0.5, 1.0, 1.5, 2.6, 4.0, 5.2, 6.5, 7.8, 9.2, 10.5, 13.0, 16.4,
                     19.7, 22.9, 26.2, 29.4, 32.7, 36.1, 41.4, 48.1, 55.5, 63.4, 72.4, 73.3,
                     78.5, 91.7, 100.6])

LEAN_V = 0.45          # narrowband O2 below this = lean side
O2_MAX_V = 1.2         # above this the sensor is still cold (reads up to 5 V): not a mixture
STEADY_S = 0.5         # "steady": throttle and RPM stayed within these over the last 0.5 s
STEADY_TPS = 1.3
STEADY_RPM = 400
GAP_S = 0.5            # a gap in the log counts at most this long


def nearest(axis, values):
    """Index of the nearest axis point for each value; -1 for NaN."""
    v = np.asarray(values, dtype=float)
    i = np.clip(np.searchsorted(axis, v), 1, len(axis) - 1)
    i = np.where(v - axis[i - 1] <= axis[i] - v, i - 1, i)
    return np.where(np.isnan(v), -1, i)


def cell_name(r, c):
    return f"{RPM_AXIS[r]:.0f} rpm × {TPS_AXIS[c]:g} %"


def steady(t, rpm, tps):
    """True where throttle and RPM stayed nearly constant over the last STEADY_S seconds."""
    out = np.zeros(len(t), dtype=bool)
    start = np.searchsorted(t, t - STEADY_S)
    for i in range(len(t)):
        a = start[i]
        if t[i] - t[a] < STEADY_S * 0.6:         # not enough history yet
            continue
        w_t, w_r = tps[a:i + 1], rpm[a:i + 1]
        if np.isnan(w_t).any() or np.isnan(w_r).any():
            continue
        out[i] = np.ptp(w_t) <= STEADY_TPS and np.ptp(w_r) <= STEADY_RPM
    return out


def hits(t, data, mask=None, steady_mask=None):
    """Per-cell summary of the samples where mask is True (default: all).

    seconds: time the engine ran in the cell (fuel cut included)
    n, o2, lean_pct, inj, duty, ect: from samples with fuel on and a warm O2 sensor, and only where
    steady_mask is True if given (O2 lags behind quick throttle changes; see steady()).
    ect is the average engine temp of those samples, so a lean cell can be told apart as
    cold-engine (warm-up) or hot.
    """
    t = np.asarray(t, dtype=float)
    shape = (len(RPM_AXIS), len(TPS_AXIS))
    rpm, tps = data["rpm"], data["tps_pct"]
    nan = np.full(len(t), np.nan)
    o2, inj, ect = data.get("o2_v", nan), data.get("inj_ms", nan), data.get("ect_c", nan)
    m = np.ones(len(t), dtype=bool) if mask is None else np.asarray(mask)
    r, c = nearest(RPM_AXIS, rpm), nearest(TPS_AXIS, tps)
    dt = np.clip(np.diff(t, append=t[-1]), 0, GAP_S)

    running = m & (r >= 0) & (c >= 0) & (rpm > 0)
    seconds = np.zeros(shape)
    np.add.at(seconds, (r[running], c[running]), dt[running])

    use = running & (inj > 0) & (o2 >= 0) & (o2 <= O2_MAX_V)
    if steady_mask is not None:
        use &= steady_mask
    n = np.zeros(shape)
    o2_sum, lean, inj_sum = np.zeros(shape), np.zeros(shape), np.zeros(shape)
    idx = (r[use], c[use])
    np.add.at(n, idx, 1)
    np.add.at(o2_sum, idx, o2[use])
    np.add.at(lean, idx, (o2[use] < LEAN_V).astype(float))
    np.add.at(inj_sum, idx, inj[use])
    duty_sum = np.zeros(shape)                   # injector duty %, as decode.inj_duty()
    np.add.at(duty_sum, idx, inj[use] * rpm[use] / 1200)
    has_t = use & np.isfinite(ect)
    t_n, t_sum = np.zeros(shape), np.zeros(shape)
    np.add.at(t_n, (r[has_t], c[has_t]), 1)
    np.add.at(t_sum, (r[has_t], c[has_t]), ect[has_t])
    with np.errstate(invalid="ignore", divide="ignore"):
        return {"seconds": seconds, "n": n, "o2": o2_sum / n,
                "lean_pct": 100 * lean / n, "inj": inj_sum / n, "duty": duty_sum / n, "ect": t_sum / t_n}
