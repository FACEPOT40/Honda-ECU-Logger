"""Ride log viewer: tick the values you want, each gets its own graph.

All graphs share one time axis. Move the mouse over a graph to read every
value at that moment. Can also run on its own:  python viewer.py ride.csv
"""

import csv
import datetime as dt
import os
import sys

import numpy as np
import pyqtgraph as pg
from PySide6.QtCore import QSettings, Qt, QTimer
from PySide6.QtGui import QBrush, QColor, QFont, QIcon, QKeySequence, QShortcut
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QFileDialog, QGraphicsItem,
                               QGridLayout, QHBoxLayout, QHeaderView, QLabel, QListWidget,
                               QListWidgetItem, QMainWindow, QMessageBox,
                               QPushButton, QSplitter, QTableWidget, QTableWidgetItem,
                               QVBoxLayout, QWidget)

import cells
import theme
from decode import FIELDS, fmt_value
from recorder import default_log_dir

KEYS = [f[0] for f in FIELDS]
INFO = {k: (lbl, unit, dec) for k, lbl, unit, dec in FIELDS}
COLORS = {
    "rpm": "#4fc3f7", "tps_pct": "#ffb74d", "ect_c": "#ef5350",
    "batt_v": "#9ccc65", "speed_kmh": "#ba68c8", "gps_kmh": "#7986cb", "inj_ms": "#4db6ac",
    "ign_deg": "#fff176", "o2_v": "#f06292", "stft": "#e0e0e0",
    "mode_d14": "#90a4ae", "neutral": "#a1887f",
}
PRESETS = {
    "Overview (RPM, TPS, ignition)": ["rpm", "tps_pct", "ign_deg"],
    "Fuel (O2, STFT, injector)": ["rpm", "tps_pct", "o2_v", "stft", "inj_ms"],
    "Ignition (advance)": ["rpm", "tps_pct", "ign_deg"],
    "Health (temp, battery)": ["rpm", "ect_c", "batt_v"],
    "Everything": KEYS,
}
DEFAULT = ["rpm", "tps_pct", "o2_v", "stft"]
TIME_FORMATS = ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S",
                "%d/%m/%Y %H:%M:%S.%f", "%d/%m/%Y %H:%M:%S", "%d/%m/%Y %H:%M")


def parse_time(s):
    for f in TIME_FORMATS:
        try:
            return dt.datetime.strptime(s.strip(), f).timestamp()
        except (ValueError, AttributeError):
            pass
    return None


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return float("nan")


def fmt_dur(s):
    s = int(round(s))
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60}:{s % 60:02d}"


def load_csv(path):
    """Return (t array, {key: values array}, [(t, marker)])."""
    t, cols, markers = [], {k: [] for k in KEYS}, []
    with open(path, newline="", encoding="utf-8-sig") as f:   # -sig: files re-saved by Excel
        reader = csv.DictReader(f)
        present = [k for k in KEYS if k in (reader.fieldnames or [])]
        for row in reader:
            ts = parse_time(row.get("timestamp") or "")
            if ts is None:
                continue
            t.append(ts)
            for k in present:
                cols[k].append(num(row.get(k)))
            if (row.get("marker") or "").strip():
                markers.append((ts, row["marker"].strip()))
    if not t:
        raise ValueError("No data rows with a valid timestamp were found in this file.")
    return np.array(t), {k: np.array(cols[k], dtype=float) for k in present}, markers


class LogViewer(QMainWindow):
    def __init__(self, path):
        super().__init__()
        self.t, self.data, self.markers = load_csv(path)
        self.name = os.path.basename(path)
        self.setWindowTitle(f"H-ECU Logger - {self.name}")
        self.resize(1350, 850)
        self.plots = {}
        self.vlines = []
        self.tags = {}            # key -> (dot, text) showing the value at the cursor
        self._bulk = False
        self.view_mask = np.ones(len(self.t), dtype=bool)    # samples in the time range in view
        self.cells_win = None
        self._steady = None       # computed once, when the cell table first needs it
        # min/avg/max and the cell table follow the zoom once dragging pauses, not on every step
        self._stats_timer = QTimer(self, singleShot=True, interval=100)
        self._stats_timer.timeout.connect(self.update_stats)

        # ---------------- left panel
        panel = QWidget()
        pv = QVBoxLayout(panel)
        dur = self.t[-1] - self.t[0]
        rate = (len(self.t) - 1) / dur if dur > 0 else 0
        info = QLabel(f"<b>{os.path.basename(path)}</b><br>"
                      f"Start {dt.datetime.fromtimestamp(self.t[0]):%Y-%m-%d %H:%M:%S}<br>"
                      f"Length {fmt_dur(dur)} · {len(self.t):,} samples · {rate:.1f}/s")
        info.setWordWrap(True)
        pv.addWidget(info)

        self.preset = QComboBox()
        self.preset.addItem("Choose a preset…")
        self.preset.addItems(list(PRESETS))
        self.preset.activated.connect(self.apply_preset)
        pv.addWidget(self.preset)

        solo_row = QHBoxLayout()
        self.solo = QCheckBox("One graph at a time")
        self.solo.toggled.connect(self.on_solo)
        prev_b, next_b = QPushButton("◀"), QPushButton("▶")
        for b in (prev_b, next_b):
            b.setFixedWidth(34)
        prev_b.setToolTip("Previous value (Page Up)")
        next_b.setToolTip("Next value (Page Down)")
        prev_b.clicked.connect(lambda: self.step(-1))
        next_b.clicked.connect(lambda: self.step(1))
        solo_row.addWidget(self.solo)
        solo_row.addStretch()
        solo_row.addWidget(prev_b)
        solo_row.addWidget(next_b)
        pv.addLayout(solo_row)

        self.cursor_lbl = QLabel("Cursor: move the mouse over a graph")
        self.cursor_lbl.setFont(QFont("Consolas", 10, QFont.Bold))
        pv.addWidget(self.cursor_lbl)

        grid = QGridLayout()
        grid.setVerticalSpacing(1)
        self.checks, self.val_lbl, self.stat_lbl = {}, {}, {}
        for i, k in enumerate(KEYS):
            lbl, unit, _ = INFO[k]
            sw = QLabel()
            sw.setFixedSize(12, 12)
            sw.setStyleSheet(f"background:{COLORS[k]}; border:1px solid {theme.LINE}; border-radius:2px")
            cb = QCheckBox(f"{lbl} ({unit})" if unit else lbl)
            val = QLabel("-")
            val.setFont(QFont("Consolas", 11, QFont.Bold))
            val.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
            val.setMinimumWidth(70)
            stat = QLabel("")
            stat.setStyleSheet(f"color:{theme.MUTED}; font-size:8pt")
            if k not in self.data or np.all(np.isnan(self.data[k])):
                cb.setEnabled(False)
                cb.setText(cb.text() + "  (no data)")
            cb.toggled.connect(lambda on, k=k: self.on_toggle(k, on))
            grid.addWidget(sw, 2 * i, 0)
            grid.addWidget(cb, 2 * i, 1)
            grid.addWidget(val, 2 * i, 2)
            grid.addWidget(stat, 2 * i + 1, 1, 1, 2)
            self.checks[k], self.val_lbl[k], self.stat_lbl[k] = cb, val, stat
        pv.addLayout(grid)
        note = QLabel("Grey text = min · avg · max of the time range in view")
        note.setStyleSheet(f"color:{theme.MUTED}; font-size:8pt")
        pv.addWidget(note)

        btns = QHBoxLayout()
        for text, fn in (("All", lambda: self.select(KEYS)), ("None", lambda: self.select([])),
                         ("Zoom to full ride", self.reset_zoom)):
            b = QPushButton(text)
            b.clicked.connect(fn)
            btns.addWidget(b)
        pv.addLayout(btns)
        cells_btn = QPushButton("Cell hits (map table)")
        cells_btn.setToolTip("Time and O2 per fuel / ignition map cell (RPM × TPS) for the range in view")
        cells_btn.clicked.connect(self.open_cells)
        cells_btn.setEnabled("rpm" in self.data and "tps_pct" in self.data)
        pv.addWidget(cells_btn)

        pv.addWidget(QLabel("<b>Markers</b> (click to jump)"))
        self.mark_list = QListWidget()
        for ts, m in self.markers:
            it = QListWidgetItem(f"{m}   {dt.datetime.fromtimestamp(ts):%H:%M:%S}   (+{fmt_dur(ts - self.t[0])})")
            it.setData(Qt.UserRole, ts)
            self.mark_list.addItem(it)
        if not self.markers:
            self.mark_list.addItem("No markers in this ride")
        self.mark_list.itemClicked.connect(self.jump_to_marker)
        pv.addWidget(self.mark_list, 1)

        hint = QLabel("Mouse wheel: zoom time · Drag: pan · Right-click: more options")
        hint.setWordWrap(True)
        hint.setStyleSheet(f"color:{theme.MUTED}; font-size:8pt")
        pv.addWidget(hint)

        # ---------------- graphs
        self.glw = pg.GraphicsLayoutWidget()
        self.glw.ci.setSpacing(2)
        self._proxy = pg.SignalProxy(self.glw.scene().sigMouseMoved, rateLimit=60,
                                     slot=lambda ev: self.on_mouse(ev[0]))

        split = QSplitter()
        split.addWidget(panel)
        split.addWidget(self.glw)
        split.setStretchFactor(1, 1)
        split.setSizes([330, 1020])
        self.setCentralWidget(split)

        QShortcut(QKeySequence(Qt.Key_PageUp), self, activated=lambda: self.step(-1))
        QShortcut(QKeySequence(Qt.Key_PageDown), self, activated=lambda: self.step(1))

        # restore last selection
        try:
            saved = QSettings("HondaECULogger", "Viewer").value("selected", DEFAULT)
            saved = [saved] if isinstance(saved, str) else list(saved or DEFAULT)
        except Exception:
            saved = DEFAULT
        self.select(saved)

    # ------------------------------------------------------------ selection
    def available(self):
        return [k for k in KEYS if self.checks[k].isEnabled()]

    def selected(self):
        return [k for k in KEYS if self.checks[k].isChecked()]

    def select(self, keys):
        self._bulk = True
        if self.solo.isChecked() and len(keys) > 1:
            self.solo.setChecked(False)
        for k in KEYS:
            self.checks[k].setChecked(k in keys and self.checks[k].isEnabled())
        self._bulk = False
        self.rebuild()

    def on_toggle(self, k, on):
        if self._bulk:
            return
        if on and self.solo.isChecked():
            self._bulk = True
            for other in KEYS:
                if other != k:
                    self.checks[other].setChecked(False)
            self._bulk = False
        self.rebuild()

    def on_solo(self, on):
        if self._bulk or not on:
            return
        sel = self.selected() or self.available()[:1]
        self._bulk = True
        for k in KEYS:
            self.checks[k].setChecked(k == sel[0])
        self._bulk = False
        self.rebuild()

    def step(self, d):
        avail = self.available()
        if not avail:
            return
        sel = self.selected()
        if len(sel) > 1:          # coming from the dashboard: start at its first graph
            nxt = sel[0]
        else:
            i = avail.index(sel[0]) if sel else -1
            nxt = avail[(i + d) % len(avail)]
        self._bulk = True
        self.solo.setChecked(True)
        self._bulk = False
        self.select([nxt])

    def apply_preset(self, idx):
        if idx > 0:
            self.select(PRESETS[self.preset.itemText(idx)])
        self.preset.setCurrentIndex(0)

    # ------------------------------------------------------------ graphs
    def first_plot(self):
        return next(iter(self.plots.values())) if self.plots else None

    def rebuild(self):
        fp = self.first_plot()
        xr = fp.vb.viewRange()[0] if fp else None
        self.glw.clear()
        self.plots, self.vlines, self.tags = {}, [], {}
        sel = self.selected()
        try:
            QSettings("HondaECULogger", "Viewer").setValue("selected", sel)
        except Exception:
            pass
        if not sel:
            self.glw.addLabel("Tick a value on the left to show its graph", color=theme.MUTED, size="12pt")
            self.update_stats()
            return
        first = None
        for row, k in enumerate(sel):
            lbl, unit, _ = INFO[k]
            p = self.glw.addPlot(row=row, col=0, axisItems={"bottom": pg.DateAxisItem()})
            p.showGrid(x=True, y=True, alpha=0.2)
            p.setDownsampling(auto=True, mode="peak")
            p.setClipToView(True)
            p.setMouseEnabled(x=True, y=False)
            p.setAutoVisible(y=True)
            p.enableAutoRange(axis="y")
            p.getAxis("left").setWidth(72)
            p.setLabel("left", f"{lbl} ({unit})" if unit else lbl, color=COLORS[k])
            if row < len(sel) - 1:
                p.getAxis("bottom").setStyle(showValues=False)
            # 1 px lines, drawn once and kept as an image until the view changes: moving the
            # cursor then no longer redraws every curve (it took ~270 ms per move, now ~16 ms)
            curve = p.plot(self.t, self.data[k], pen=pg.mkPen(COLORS[k], width=1), connect="finite")
            curve.curve.setCacheMode(QGraphicsItem.DeviceCoordinateCache)
            for ax in ("left", "bottom"):
                p.getAxis(ax).setCacheMode(QGraphicsItem.DeviceCoordinateCache)
            for ts, m in self.markers:
                p.addItem(pg.InfiniteLine(
                    ts, 90, pen=pg.mkPen("#ef5350", width=1, style=Qt.DashLine),
                    label=m if row == 0 else None,
                    labelOpts={"position": 0.92, "color": "#ef5350"}), ignoreBounds=True)
            v = pg.InfiniteLine(angle=90, movable=False, pen=pg.mkPen("#bbbbbb", width=1))
            v.setVisible(False)
            p.addItem(v, ignoreBounds=True)
            self.vlines.append(v)
            dot = pg.ScatterPlotItem(size=8, pen=pg.mkPen("#000000"), brush=pg.mkBrush(COLORS[k]))
            text = pg.TextItem(color=COLORS[k], fill=pg.mkBrush(20, 26, 34, 220))
            text.setFont(QFont("Consolas", 10, QFont.Bold))
            for item in (dot, text):
                item.setVisible(False)
                item.setZValue(10)
                p.addItem(item, ignoreBounds=True)
            self.tags[k] = (dot, text)
            if first is None:
                first = p
            else:
                p.setXLink(first)
            self.plots[k] = p
        first.sigXRangeChanged.connect(lambda *_: self._stats_timer.start())
        if xr:
            first.setXRange(*xr, padding=0)
        else:
            self.reset_zoom()
        self.update_stats()

    def reset_zoom(self):
        fp = self.first_plot()
        if fp:
            fp.setXRange(self.t[0], self.t[-1], padding=0.01)

    def update_stats(self):
        fp = self.first_plot()
        if fp:
            x0, x1 = fp.vb.viewRange()[0]
            mask = (self.t >= x0) & (self.t <= x1)
        else:
            mask = np.ones(len(self.t), dtype=bool)
        self.view_mask = mask
        if self.cells_win is not None and self.cells_win.isVisible():
            self.cells_win.refresh()
        for k in KEYS:
            if k not in self.data:
                continue
            y = self.data[k][mask]
            y = y[~np.isnan(y)]
            dec = INFO[k][2]
            self.stat_lbl[k].setText(
                f"min {y.min():,.{dec}f} · avg {y.mean():,.{dec}f} · max {y.max():,.{dec}f}"
                if y.size else "")

    def on_mouse(self, pos):
        for p in self.plots.values():
            if p.vb.sceneBoundingRect().contains(pos):
                x = p.vb.mapSceneToView(pos).x()
                break
        else:
            return
        self.show_at(x)

    def show_at(self, x):
        t = self.t
        i = int(np.clip(np.searchsorted(t, x), 0, len(t) - 1))
        if i > 0 and x - t[i - 1] < t[i] - x:
            i -= 1
        for v in self.vlines:
            v.setPos(t[i])
            v.setVisible(True)
        stamp = dt.datetime.fromtimestamp(t[i]).strftime("%H:%M:%S.%f")[:-3]
        cell = self.cell_at(i)
        self.cursor_lbl.setText(f"Cursor: {stamp}  (+{fmt_dur(t[i] - t[0])})\n"
                                f"Map cell: {cells.cell_name(*cell) if cell else '-'}")
        if self.cells_win is not None and self.cells_win.isVisible():
            self.cells_win.show_cursor(cell)
        for k in KEYS:
            if k in self.data:
                val = self.data[k][i]
                self.val_lbl[k].setText(fmt_value(k, None if np.isnan(val) else val))
        self.place_tags(i, stamp)

    def place_tags(self, i, stamp):
        """Dot + value label on each graph where the cursor line crosses the curve."""
        for row, (k, (dot, text)) in enumerate(self.tags.items()):
            val = self.data[k][i]
            if np.isnan(val):
                dot.setVisible(False)
                text.setVisible(False)
                continue
            s = f"{fmt_value(k, val)} {INFO[k][1]}".strip()
            if row == 0:
                s = f"{stamp}\n{s}"
            (x0, x1), (y0, y1) = self.plots[k].vb.viewRange()
            # keep the label inside the graph: flip left near the right edge, below near the top
            ax = 1.05 if self.t[i] > x0 + 0.75 * (x1 - x0) else -0.05
            ay = 0.0 if val > y0 + 0.6 * (y1 - y0) else 1.0
            dot.setData([self.t[i]], [val])
            text.setText(s)
            text.setAnchor((ax, ay))
            text.setPos(self.t[i], val)
            dot.setVisible(True)
            text.setVisible(True)

    def cell_at(self, i):
        """(row, col) of the nearest map cell at sample i, or None if the engine was off."""
        if "rpm" not in self.data or "tps_pct" not in self.data:
            return None
        rpm, tps = self.data["rpm"][i], self.data["tps_pct"][i]
        if np.isnan(rpm) or np.isnan(tps) or rpm <= 0:
            return None
        return int(cells.nearest(cells.RPM_AXIS, rpm)), int(cells.nearest(cells.TPS_AXIS, tps))

    def steady_mask(self):
        if self._steady is None:
            self._steady = cells.steady(self.t, self.data["rpm"], self.data["tps_pct"])
        return self._steady

    def open_cells(self):
        if self.cells_win is None:
            self.cells_win = CellHitsWindow(self)
        self.cells_win.show()
        self.cells_win.raise_()
        self.cells_win.refresh()

    def closeEvent(self, e):
        if self.cells_win is not None:
            self.cells_win.close()
        e.accept()

    def jump_to_marker(self, item):
        ts = item.data(Qt.UserRole)
        fp = self.first_plot()
        if ts is None or fp is None:
            return
        x0, x1 = fp.vb.viewRange()[0]
        w = min(x1 - x0, 60)
        fp.setXRange(ts - w / 2, ts + w / 2, padding=0)
        self.show_at(ts)


def _blend(color, f):
    """The dark panel color blended toward color by f (0..1), like the shading of a TunerPro
    map (and CellGridView.kt on the phone)."""
    f = float(np.clip(f, 0, 1))
    c, b = QColor(color), QColor(theme.PANEL)
    return QColor(int(b.red() + (c.red() - b.red()) * f), int(b.green() + (c.green() - b.green()) * f),
                  int(b.blue() + (c.blue() - b.blue()) * f))


class CellHitsWindow(QWidget):
    """Per map cell (RPM rows × TPS columns, like TunerPro): time, O2, injector, for the time
    range in view in the viewer. Follows the viewer's zoom and highlights the cursor's cell."""

    MODES = {   # label: (key in cells.hits, number format)
        "Time in cell (s)": ("seconds", "{:.1f}"),
        "Average O2 (V)": ("o2", "{:.2f}"),
        "Lean share (% of samples below 0.45 V)": ("lean_pct", "{:.0f}"),
        "Average injector (ms)": ("inj", "{:.2f}"),
        "Samples used for O2 and injector": ("n", "{:.0f}"),
    }
    MIN_SAMPLES = 5

    def __init__(self, viewer):
        super().__init__(None, Qt.Window)
        self.viewer = viewer
        self.setWindowTitle(f"Cell hits - {viewer.name}")
        self.resize(1500, 820)
        lay = QVBoxLayout(self)
        top = QHBoxLayout()
        self.mode = QComboBox()
        self.mode.addItems(list(self.MODES))
        self.mode.setCurrentIndex(1)
        self.mode.currentIndexChanged.connect(self.refresh)
        self.steady = QCheckBox("Steady throttle only (O2 and injector)")
        self.steady.setChecked(True)
        self.steady.setToolTip("Leaves out the moments right after the throttle or RPM changed: "
                               "the O2 sensor lags behind, so those readings mislead")
        self.steady.toggled.connect(self.refresh)
        self.range_lbl = QLabel()
        top.addWidget(QLabel("Show:"))
        top.addWidget(self.mode)
        top.addSpacing(16)
        top.addWidget(self.steady)
        top.addStretch()
        top.addWidget(self.range_lbl)
        lay.addLayout(top)

        self.table = QTableWidget(len(cells.RPM_AXIS), len(cells.TPS_AXIS))
        self.table.setHorizontalHeaderLabels([f"{v:g}" for v in cells.TPS_AXIS])
        self.table.setVerticalHeaderLabels([f"{v:.0f}" for v in cells.RPM_AXIS])
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setFont(QFont("Segoe UI", 8))
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        self.table.verticalHeader().setSectionResizeMode(QHeaderView.Stretch)
        lay.addWidget(self.table, 1)
        legend = QLabel(
            "Rows = RPM, columns = TPS %, the same axes as the fuel and ignition maps in TunerPro. "
            "Each moment counts in its nearest cell. O2 colors: blue = lean side (below 0.45 V), "
            "orange / red = rich. Faded cells in italics = fewer than 5 samples, not reliable. "
            "Zoom or move the graphs to look at part of the ride; the cursor's cell is selected.")
        legend.setWordWrap(True)
        legend.setStyleSheet(f"color:{theme.MUTED}")
        lay.addWidget(legend)

    def refresh(self):
        v = self.viewer
        mask = v.view_mask
        h = cells.hits(v.t, v.data, mask, v.steady_mask() if self.steady.isChecked() else None)
        key, fmt = self.MODES[self.mode.currentText()]
        vals, n = h[key], h["n"]
        t_in = v.t[mask]
        span = f"{dt.datetime.fromtimestamp(t_in[0]):%H:%M:%S} – {dt.datetime.fromtimestamp(t_in[-1]):%H:%M:%S}" \
            if t_in.size else "-"
        self.range_lbl.setText(f"Range in view: {span} · engine running {fmt_dur(h['seconds'].sum())}")
        top = np.nanmax(vals) if np.isfinite(vals).any() and np.nanmax(vals) > 0 else 1
        for r in range(vals.shape[0]):
            for c in range(vals.shape[1]):
                x = vals[r, c]
                empty = (key == "seconds" and x < 0.05) or (key != "seconds" and (n[r, c] == 0 or np.isnan(x)))
                item = QTableWidgetItem("" if empty else fmt.format(x))
                item.setTextAlignment(Qt.AlignCenter)
                if not empty:
                    bg = self.color(key, x, top)
                    if key != "seconds" and n[r, c] < self.MIN_SAMPLES:
                        # too few samples to trust: a faded cell in italics, still readable
                        b = QColor(theme.PANEL)
                        bg = QColor((bg.red() + 2 * b.red()) // 3, (bg.green() + 2 * b.green()) // 3,
                                    (bg.blue() + 2 * b.blue()) // 3)
                        font = item.font()
                        font.setItalic(True)
                        item.setFont(font)
                        item.setForeground(QBrush(QColor(theme.MUTED)))
                    item.setBackground(QBrush(bg))
                item.setToolTip(self.tooltip(h, r, c))
                self.table.setItem(r, c, item)

    @staticmethod
    def color(key, x, top):
        if key == "o2":            # lean side blue, rich side orange to red
            return _blend("#4f8ff7", (cells.LEAN_V - x) / cells.LEAN_V) if x < cells.LEAN_V \
                else _blend("#ff7043", (x - cells.LEAN_V) / 0.5)
        if key == "lean_pct":
            return _blend("#4f8ff7", x / 100)
        return _blend({"seconds": "#26a69a", "inj": "#4db6ac", "n": "#9e9e9e"}[key], 0.15 + 0.85 * x / top)

    @staticmethod
    def tooltip(h, r, c):
        lines = [cells.cell_name(r, c), f"{h['seconds'][r, c]:.1f} s running"]
        if h["n"][r, c]:
            lines += [f"O2 {h['o2'][r, c]:.2f} V average, {h['lean_pct'][r, c]:.0f} % of samples lean",
                      f"Injector {h['inj'][r, c]:.2f} ms average", f"{h['n'][r, c]:.0f} samples"]
        return "\n".join(lines)

    def show_cursor(self, cell):
        if cell is None:
            self.table.clearSelection()
            return
        self.table.setCurrentCell(*cell)


def app_icon():
    """assets/icon.ico next to this file, or unpacked from the .exe (PyInstaller's _MEIPASS)."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return QIcon(os.path.join(base, "assets", "icon.ico"))


def main():
    pg.setConfigOptions(antialias=True)
    app = QApplication(sys.argv)
    theme.apply(app)
    app.setWindowIcon(app_icon())
    path = sys.argv[1] if len(sys.argv) > 1 else \
        QFileDialog.getOpenFileName(None, "Open ride log", default_log_dir(), "CSV (*.csv)")[0]
    if not path:
        return
    try:
        w = LogViewer(path)
    except Exception as e:
        QMessageBox.warning(None, "H-ECU Logger", f"Could not open file:\n{e}")
        return
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
