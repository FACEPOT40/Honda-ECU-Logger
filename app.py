"""H-ECU Logger (K-line): live ECU data with graphs, Start/Stop Record to CSV.
Read-only: it never writes to the ECU."""

import bisect
import datetime as dt
import sys
import time
from collections import deque
from itertools import islice

import pyqtgraph as pg
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QAction, QFont, QKeySequence, QShortcut
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QDialog, QFileDialog, QFrame, QGraphicsItem,
                               QGridLayout, QHBoxLayout, QLabel, QMainWindow,
                               QMessageBox, QPushButton, QVBoxLayout, QWidget)

from decode import FIELDS, alerts, dtc_text, fmt_value
from kline import list_ports
import theme
from recorder import Recorder
from viewer import LogViewer, app_icon
from worker import PollWorker

APP_NAME = "H-ECU Logger"
SUBTITLE = "K-line"
VERSION = "0.3.2"

# graph rows: (left key, right key or None, title)
GRAPHS = [
    ("rpm", "tps_pct", "RPM / TPS %"),
    ("o2_v", "stft", "O2 V / STFT"),
    ("ign_deg", "inj_ms", "Ignition ° / Injector ms"),
    ("ect_c", "batt_v", "Engine temp °C / Battery V"),
]
COLORS = ("#4fc3f7", "#ffb74d")
LABELS = {k: (lbl, unit) for k, lbl, unit, _ in FIELDS}
BUFFER = 6000  # samples kept in memory for the live graphs


class DualPlot:
    """A pyqtgraph plot with a left and an optional right Y axis."""

    def __init__(self, layout, row, left, right, title):
        self.plot = layout.addPlot(row=row, col=0,
                                   axisItems={"bottom": pg.DateAxisItem()})
        self.plot.setTitle(title, size="9pt")
        self.plot.showGrid(x=True, y=True, alpha=0.2)
        self.plot.setLabel("left", LABELS[left][0], color=COLORS[0])
        # 1.5 px lines: 2 px antialiased lines are much slower to draw 10 times a second
        self.c1 = self.plot.plot(pen=pg.mkPen(COLORS[0], width=1.5))
        self.c2 = None
        if right:
            self.vb2 = pg.ViewBox()
            self.plot.showAxis("right")
            self.plot.scene().addItem(self.vb2)
            self.plot.getAxis("right").linkToView(self.vb2)
            self.plot.getAxis("right").setLabel(LABELS[right][0], color=COLORS[1])
            self.vb2.setXLink(self.plot)
            self.c2 = pg.PlotCurveItem(pen=pg.mkPen(COLORS[1], width=1.5))
            self.vb2.addItem(self.c2)
            self.plot.vb.sigResized.connect(self._sync)
        self.markers = []

    def _sync(self):
        self.vb2.setGeometry(self.plot.vb.sceneBoundingRect())
        self.vb2.linkedViewChanged(self.plot.vb, self.vb2.XAxis)

    def set_data(self, x, y1, y2=None):
        self.c1.setData(x, y1, connect="finite")
        if self.c2 is not None and y2 is not None:
            self.c2.setData(x, y2, connect="finite")
            self.vb2.enableAutoRange(axis=pg.ViewBox.YAxis)

    def add_marker(self, t, text):
        line = pg.InfiniteLine(pos=t, angle=90, movable=False,
                               pen=pg.mkPen("#ef5350", width=1, style=Qt.DashLine),
                               label=text, labelOpts={"position": 0.9, "color": "#ef5350"})
        self.plot.addItem(line)
        self.markers.append(line)

    def clear_markers(self):
        for m in self.markers:
            self.plot.removeItem(m)
        self.markers = []


def build_graphs():
    widget = pg.GraphicsLayoutWidget()
    plots = [DualPlot(widget, i, l, r, t) for i, (l, r, t) in enumerate(GRAPHS)]
    for p in plots[1:]:
        p.plot.setXLink(plots[0].plot)
    # time labels only under the bottom graph (the axes were most of the drawing time, 10x a
    # second); the side scales are kept as images and only redrawn when their range changes
    for p in plots:
        if p is not plots[-1]:
            p.plot.getAxis("bottom").setStyle(showValues=False)
        for ax in ("left", "right"):
            p.plot.getAxis(ax).setCacheMode(QGraphicsItem.DeviceCoordinateCache)
    return widget, plots


REPO_URL = "https://github.com/FACEPOT40/Honda-ECU-Logger"


class AboutDialog(QDialog):
    """Help → About: name, version, what the app does and doesn't do, credits, license.
    The phone's AboutActivity.kt says the same."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle(f"About {APP_NAME}")
        self.setFixedWidth(520)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(24, 24, 24, 16)
        lay.setSpacing(14)

        head = QHBoxLayout()
        head.setSpacing(16)
        logo = QLabel()
        logo.setPixmap(app_icon().pixmap(80, 80))
        head.addWidget(logo, 0, Qt.AlignTop)
        head.addWidget(QLabel(
            f"<div style='font-size:20pt; font-weight:600'>{APP_NAME}</div>"
            f"<div style='font-size:11pt; color:{theme.AMBER}'>{SUBTITLE} · version {VERSION}</div>"
            f"<div style='color:{theme.MUTED}'>Live data logger for Honda / Keihin ECUs</div>"), 1)
        lay.addLayout(head)

        def section(title, text):
            return (f"<p style='margin:0 0 10px 0'><b>{title}</b><br>"
                    f"<span style='color:{theme.MUTED}'>{text}</span></p>")

        body = QLabel(
            section("Read only", "Reads live data and fault codes, nothing else. It never writes, "
                                 "flashes or clears anything in the ECU.")
            + section("Ride safe", "Never look at the screen while riding: record, ride, and look "
                                   "at the graphs afterwards.")
            + section("Tested on", "Honda Wave 125i (2021), ECU 38770-K73-T61, with an FTDI FT232R "
                                   "K-line cable.")
            + section("Thanks to", "AutotronicCommunity (K-line protocol notes) · HondaECU by "
                                   "RyanHope / MCU Innovations (value formulas, fault-code reads, GPL-3.0) · "
                                   "RE-ECU-th (PGM-FI live data sheet)")
            + section("License", f"Made by FACEPOT40 (ปาย). GPL-3.0, free and open source: "
                                 f"<a style='color:#4fc3f7' href='{REPO_URL}'>source on GitHub</a>")
            + f"<p style='margin:0; color:{theme.MUTED}; font-size:8pt'>Not affiliated with or endorsed "
              "by Honda Motor Co., Ltd. Honda is a trademark of its owner.</p>")
        body.setWordWrap(True)
        body.setTextFormat(Qt.RichText)
        body.setOpenExternalLinks(True)
        lay.addWidget(body)

        close = theme.role(QPushButton("Close"), "primary")
        close.clicked.connect(self.accept)
        close.setDefault(True)
        lay.addWidget(close, 0, Qt.AlignRight)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} ({SUBTITLE}) {VERSION}")
        self.resize(1250, 800)
        self.worker = None
        self.stopping = []        # stopped polling threads that have not ended yet
        self.rec = Recorder()
        self.marker_n = 0
        self.window_s = 30
        self.viewers = []
        self.buf = {"t": deque(maxlen=BUFFER)}
        for k, *_ in FIELDS:
            self.buf[k] = deque(maxlen=BUFFER)

        central = QWidget()
        root = QVBoxLayout(central)

        # --- row 1: connection
        r1 = QHBoxLayout()
        self.port_box = QComboBox()
        self.port_box.setMinimumWidth(260)
        refresh = QPushButton("⟳")
        refresh.setFixedWidth(32)
        refresh.clicked.connect(self.refresh_ports)
        self.sim_box = QCheckBox("Simulator")
        self.connect_btn = theme.role(QPushButton("Connect"), "primary")
        self.connect_btn.clicked.connect(self.toggle_connect)
        self.status_lbl = QLabel("● Disconnected")
        self.ecm_lbl = QLabel("ECM: -")
        for w in (self.port_box, refresh, self.sim_box, self.connect_btn):
            r1.addWidget(w)
        r1.addSpacing(16)
        r1.addWidget(self.status_lbl)
        r1.addStretch()
        r1.addWidget(self.ecm_lbl)
        self.faults = None          # last kline.read_dtcs() result
        self.faults_btn = QPushButton("Faults: -")
        self.faults_btn.setToolTip("Fault codes (DTC) read from the ECU. Read only: the app never clears them.")
        self.faults_btn.clicked.connect(self.show_faults)
        r1.addWidget(self.faults_btn)
        root.addLayout(r1)

        # --- row 2: recording
        r2 = QHBoxLayout()
        self.rec_btn = theme.role(QPushButton("● Start Record"), "record")
        self.rec_btn.clicked.connect(self.start_record)
        self.stop_btn = QPushButton("■ Stop")
        self.stop_btn.clicked.connect(self.stop_record)
        self.stop_btn.setEnabled(False)
        self.mark_btn = QPushButton("⚑ Marker (M)")
        self.mark_btn.clicked.connect(self.add_marker)
        open_btn = QPushButton("Open CSV")
        open_btn.clicked.connect(self.open_csv)
        self.clock_lbl = QLabel()
        self.clock_lbl.setFont(QFont("Consolas", 14, QFont.Bold))
        self.elapsed_lbl = QLabel()
        self.elapsed_lbl.setVisible(False)
        for w in (self.rec_btn, self.stop_btn, self.clock_lbl, self.elapsed_lbl):
            r2.addWidget(w)
        r2.addStretch()
        for s in (10, 30, 60):
            b = QPushButton(f"{s}s")
            b.setFixedWidth(46)
            b.clicked.connect(lambda _=False, s=s: self.set_window(s))
            r2.addWidget(b)
        r2.addSpacing(16)
        r2.addWidget(self.mark_btn)
        r2.addWidget(open_btn)
        root.addLayout(r2)

        # --- body: values + graphs
        body = QHBoxLayout()
        grid = QGridLayout()
        self.value_lbl = {}
        big = QFont("Consolas", 20, QFont.Bold)
        for i, (k, lbl, unit, _) in enumerate(FIELDS):
            name = QLabel(lbl)
            name.setFont(QFont("Segoe UI", 11))
            val = QLabel("-")
            val.setFont(big)
            val.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
            val.setMinimumWidth(130)
            u = QLabel(unit)
            grid.addWidget(name, i, 0)
            grid.addWidget(val, i, 1)
            grid.addWidget(u, i, 2)
            self.value_lbl[k] = val
        grid.setRowStretch(len(FIELDS), 1)
        left = QFrame(objectName="panel")
        left.setLayout(grid)
        left.setFixedWidth(280)
        body.addWidget(left)
        gw, self.plots = build_graphs()
        body.addWidget(gw, 1)
        root.addLayout(body, 1)
        self.setCentralWidget(central)

        # menu
        view = self.menuBar().addMenu("View")
        self.show_elapsed = QAction("Show elapsed time", self, checkable=True)
        self.show_elapsed.toggled.connect(self.elapsed_lbl.setVisible)
        view.addAction(self.show_elapsed)
        helpm = self.menuBar().addMenu("Help")
        about = QAction("About", self)
        about.triggered.connect(lambda: AboutDialog(self).exec())
        helpm.addAction(about)

        QShortcut(QKeySequence("M"), self, activated=self.add_marker)

        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(100)
        self.refresh_ports()

    # ------------------------------------------------------------ connection
    def refresh_ports(self):
        self.port_box.clear()
        for dev, desc in list_ports():
            self.port_box.addItem(f"{dev}  {desc}", dev)
        if self.port_box.count() == 0:
            self.port_box.addItem("(no COM ports found)", None)

    def toggle_connect(self):
        if self.worker:
            self.stop_worker(self.worker)
            self.worker = None
            self.connect_btn.setText("Connect")
            self.set_status("Disconnected")
            return
        sim = self.sim_box.isChecked()
        port = self.port_box.currentData()
        if not sim and not port:
            QMessageBox.warning(self, APP_NAME, "No COM port selected.")
            return
        self.worker = PollWorker(port=port, simulate=sim)
        self.worker.status.connect(self.set_status)
        self.worker.ecm.connect(lambda s: self.ecm_lbl.setText("ECM: " + s))
        self.worker.sample.connect(self.on_sample)
        self.worker.faults.connect(self.on_faults)
        self.worker.start()
        self.connect_btn.setText("Disconnect")

    def stop_worker(self, w):
        """Stop a polling thread. Never drop one that is still running: Qt aborts the whole
        app then (the crash after unplugging the cable). A thread stuck on a vanished COM port
        is kept in self.stopping until it ends; its late updates no longer reach the screen."""
        for sig in (w.status, w.ecm, w.sample, w.faults):
            try:
                sig.disconnect()
            except (RuntimeError, TypeError):
                pass
        w.stop()
        if not w.wait(3000):
            self.stopping.append(w)
            w.finished.connect(lambda: self.stopping.remove(w) if w in self.stopping else None)

    # ------------------------------------------------------------ fault codes
    def on_faults(self, res):
        self.faults = res
        if not res["supported"]:
            text, color = "Faults: not readable", theme.MUTED
        elif res["current"]:
            text, color = f"Faults: {len(res['current'])} current, {len(res['past'])} past", theme.RED
        elif res["past"]:
            text, color = f"Faults: {len(res['past'])} past", theme.ORANGE
        else:
            text, color = "Faults: none", theme.GREEN
        self.faults_btn.setText(text)
        self.faults_btn.setStyleSheet(f"color:{color}; font-weight:bold")

    def show_faults(self):
        res = self.faults
        if res is None:
            body = "Not read yet: the app reads the fault codes each time it connects."
        elif not res["supported"]:
            body = "The ECU did not answer the fault-code queries."
        else:
            body = ""
            for key, title in (("current", "Current faults"), ("past", "Past (stored) faults")):
                body += f"<b>{title}</b><br>"
                body += "<br>".join(dtc_text(m, s) for m, s in res[key]) if res[key] else "none"
                body += "<br><br>"
            body += (f"<span style='color:{theme.MUTED}'>The number before the dash is the MIL blink count. "
                     "Names are the usual Honda PGM-FI groups: check the Wave 125i service manual. "
                     "This app only reads codes; it never clears them.</span>")
        box = QMessageBox(self)
        box.setWindowTitle("Fault codes (DTC)")
        box.setTextFormat(Qt.RichText)
        box.setText(body)
        again = box.addButton("Read again", QMessageBox.ActionRole) if self.worker else None
        box.addButton(QMessageBox.Close)
        box.exec()
        if again is not None and box.clickedButton() is again and self.worker:
            self.faults_btn.setText("Faults: reading…")
            self.worker.request_faults()

    def set_status(self, text):
        color = theme.GREEN if text.startswith("Connected") else \
            theme.RED if text.startswith(("Error", "Disconnected")) else theme.ORANGE
        self.status_lbl.setText(f"<span style='color:{color}'>●</span> {text}")

    # ------------------------------------------------------------ data
    def on_sample(self, s):
        self.buf["t"].append(s["t"])
        for k, *_ in FIELDS:
            v = s.get(k)
            self.buf[k].append(float("nan") if v is None else v)
        bad = alerts(s)
        for k, *_ in FIELDS:
            v = s.get(k)
            lbl = self.value_lbl[k]
            lbl.setText(fmt_value(k, v))
            lbl.setStyleSheet(f"color:{theme.RED}" if k in bad else "")
        self.rec.write(s)

    def tick(self):
        now = time.time()
        clock = dt.datetime.now().strftime("%H:%M:%S")
        if self.rec.active:
            self.clock_lbl.setText(
                f"<span style='color:{theme.RED}'>● REC</span> {clock} "
                f"(started {self.rec.started_at:%H:%M:%S})")
            el = int((dt.datetime.now() - self.rec.started_at).total_seconds())
            self.elapsed_lbl.setText(f"+{el // 60:02d}:{el % 60:02d}")
        else:
            self.clock_lbl.setText(clock)
            self.elapsed_lbl.setText("")
        t = list(self.buf["t"])
        if not t:
            return
        # draw only the samples in the time window: the buffer keeps ~7 minutes (6,000
        # samples), and drawing all of it 10 times a second made the live screen lag
        i0 = bisect.bisect_left(t, now - self.window_s - 1)
        t = t[i0:]
        for p, (l, r, _) in zip(self.plots, GRAPHS):
            p.set_data(t, list(islice(self.buf[l], i0, None)),
                       list(islice(self.buf[r], i0, None)) if r else None)
        self.plots[0].plot.setXRange(now - self.window_s, now, padding=0)
        for p in self.plots:
            p.plot.enableAutoRange(axis=pg.ViewBox.YAxis)

    def set_window(self, s):
        self.window_s = s

    # ------------------------------------------------------------ recording
    def start_record(self):
        try:
            path = self.rec.start()
        except OSError as e:
            # without this the click silently did nothing (e.g. logs folder not writable)
            QMessageBox.warning(self, APP_NAME, f"Could not start recording:\n{e}")
            return
        self.marker_n = 0
        for p in self.plots:
            p.clear_markers()
        self.rec_btn.setEnabled(False)
        self.stop_btn.setEnabled(True)
        self.statusBar().showMessage(f"Recording to {path}")

    def stop_record(self):
        path = self.rec.stop()
        self.rec_btn.setEnabled(True)
        self.stop_btn.setEnabled(False)
        self.statusBar().showMessage(f"Saved {path}")

    def add_marker(self):
        self.marker_n += 1
        label = f"M{self.marker_n}"
        self.rec.mark(label)
        for p in self.plots:
            p.add_marker(time.time(), label)
        self.statusBar().showMessage(f"Marker {label} at {dt.datetime.now():%H:%M:%S}", 3000)

    def open_csv(self, path=None):
        if not path:
            path, _ = QFileDialog.getOpenFileName(self, "Open log", self.rec.folder, "CSV (*.csv)")
        if path:
            try:
                v = LogViewer(path)
            except Exception as e:
                QMessageBox.warning(self, APP_NAME, f"Could not open file:\n{e}")
                return
            v.show()
            self.viewers.append(v)

    def closeEvent(self, e):
        if self.rec.active:
            self.rec.stop()
        if self.worker:
            self.stop_worker(self.worker)
            self.worker = None
        for w in list(self.stopping):     # still stuck: end it, or Qt aborts while quitting
            w.terminate()
            w.wait(2000)
        e.accept()


def main():
    pg.setConfigOptions(antialias=True)
    app = QApplication(sys.argv)
    theme.apply(app)
    app.setApplicationName(APP_NAME)
    app.setWindowIcon(app_icon())
    if sys.platform == "win32":  # own taskbar icon, not python.exe's, when run from source
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("HECULogger.App")
    w = MainWindow()
    w.show()
    # a CSV dropped onto the .exe (or passed on the command line) opens in the viewer
    if len(sys.argv) > 1 and sys.argv[1].lower().endswith(".csv"):
        w.open_csv(sys.argv[1])
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
