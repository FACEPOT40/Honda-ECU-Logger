"""Start/Stop Record -> CSV with wall-clock timestamps."""

import csv
import datetime as dt
import os
import sys
import tempfile

VALUES = ["rpm", "tps_pct", "ect_c", "batt_v", "speed_kmh", "inj_ms", "ign_deg",
          "o2_v", "stft", "fast_idle"]
COLUMNS = ["timestamp", "elapsed_s"] + VALUES + ["marker", "raw_17", "raw_20"]


def app_dir():
    """Folder of app.py, or of the .exe when built with PyInstaller."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def default_log_dir():
    """logs\\ next to the app; Documents\\HondaECULogger\\logs if that folder
    can't be written (e.g. the .exe sits in Program Files). Not relative to the
    current folder, which depends on how the app was started."""
    for base in (app_dir(),
                 os.path.join(os.path.expanduser("~"), "Documents", "HondaECULogger")):
        folder = os.path.join(base, "logs")
        try:
            os.makedirs(folder, exist_ok=True)
            with tempfile.TemporaryFile(dir=folder):
                pass
            return folder
        except OSError:
            continue
    return folder


class Recorder:
    def __init__(self, folder=None):
        self.folder = folder or default_log_dir()
        self.f = None
        self.w = None
        self.t0 = None
        self.path = None
        self.started_at = None
        self.pending_marker = ""

    @property
    def active(self):
        return self.f is not None

    def start(self):
        os.makedirs(self.folder, exist_ok=True)
        self.started_at = dt.datetime.now()
        self.path = os.path.join(self.folder,
                                 f"ride_{self.started_at:%Y%m%d_%H%M%S}.csv")
        self.f = open(self.path, "w", newline="", encoding="utf-8")
        self.w = csv.writer(self.f)
        self.w.writerow(COLUMNS)
        self.t0 = None
        return self.path

    def mark(self, label):
        self.pending_marker = label

    def write(self, s: dict):
        if not self.active:
            return
        if self.t0 is None:
            self.t0 = s["t"]
        ts = dt.datetime.fromtimestamp(s["t"]).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
        row = [ts, f"{s['t'] - self.t0:.3f}"]
        for k in VALUES:
            v = s.get(k)
            row.append("" if v is None else v)
        row += [self.pending_marker, s.get("raw_17", ""), s.get("raw_20", "")]
        self.pending_marker = ""
        self.w.writerow(row)

    def stop(self):
        if self.f:
            self.f.flush()
            self.f.close()
        self.f = self.w = None
        return self.path
