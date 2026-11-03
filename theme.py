"""H-ECU Logger brand colors and the dark PC theme.

Same colors as the icon (assets/icon.svg) and the phone app (Look in android/.../Ui.kt).
Value colors (one per logged value) are COLORS in viewer.py, not here."""

import pyqtgraph as pg
from PySide6.QtGui import QColor, QPalette

NIGHT = "#08090c"    # window background, the icon's background
PANEL = "#141a22"    # value panel, inputs, buttons
LINE = "#232c38"     # borders, grid lines
BLUE = "#1976d2"     # main action (Connect), selection: the icon's H
AMBER = "#ffa000"    # Start Record: the icon's bolt
TEXT = "#e8edf3"
MUTED = "#8a96a6"    # labels, hints
DISABLED = "#4a5462"
GREEN = "#43a047"    # connected, no faults
ORANGE = "#fb8c00"   # connecting, past faults
RED = "#ef5350"      # error, current faults, value in the red zone, REC

STYLE = f"""
QPushButton {{ background:{PANEL}; border:1px solid {LINE}; border-radius:4px; padding:4px 10px; }}
QPushButton:hover {{ border-color:{MUTED}; }}
QPushButton:pressed {{ background:{LINE}; }}
QPushButton:disabled {{ color:{DISABLED}; border-color:{PANEL}; }}
QPushButton[role="primary"] {{ background:{BLUE}; border-color:{BLUE}; color:white; }}
QPushButton[role="primary"]:hover {{ border-color:#64b5f6; }}
QPushButton[role="record"] {{ background:{AMBER}; border-color:{AMBER}; color:{NIGHT}; font-weight:bold; }}
QPushButton[role="record"]:hover {{ border-color:#ffd54f; }}
QPushButton[role="record"]:disabled {{ background:{PANEL}; border-color:{PANEL}; color:{DISABLED}; }}
QComboBox, QLineEdit {{ background:{PANEL}; border:1px solid {LINE}; border-radius:4px; padding:3px 6px; }}
QComboBox:hover {{ border-color:{MUTED}; }}
QFrame#panel {{ background:{PANEL}; border-radius:6px; }}
QTableWidget {{ gridline-color:{LINE}; }}
QHeaderView::section {{ background:{PANEL}; color:{MUTED}; border:0; border-right:1px solid {LINE};
                        border-bottom:1px solid {LINE}; padding:2px; }}
QToolTip {{ background:{PANEL}; color:{TEXT}; border:1px solid {LINE}; }}
QMenu::item:selected, QMenuBar::item:selected {{ background:{BLUE}; }}
"""


def apply(app):
    """Dark Fusion look for the whole app. Call before any window or graph is made."""
    app.setStyle("Fusion")
    p = QPalette()
    for role, color in ((QPalette.Window, NIGHT), (QPalette.Base, PANEL), (QPalette.AlternateBase, LINE),
                        (QPalette.Button, PANEL), (QPalette.WindowText, TEXT), (QPalette.Text, TEXT),
                        (QPalette.ButtonText, TEXT), (QPalette.BrightText, "#ffffff"),
                        (QPalette.ToolTipBase, PANEL), (QPalette.ToolTipText, TEXT),
                        (QPalette.Highlight, BLUE), (QPalette.HighlightedText, "#ffffff"),
                        (QPalette.PlaceholderText, MUTED), (QPalette.Link, "#4fc3f7"),
                        (QPalette.Light, LINE), (QPalette.Midlight, LINE), (QPalette.Mid, LINE),
                        (QPalette.Dark, NIGHT), (QPalette.Shadow, "#000000")):
        p.setColor(role, QColor(color))
    for role in (QPalette.WindowText, QPalette.Text, QPalette.ButtonText):
        p.setColor(QPalette.Disabled, role, QColor(DISABLED))
    app.setPalette(p)
    app.setStyleSheet(STYLE)
    pg.setConfigOptions(background=NIGHT, foreground=MUTED)


def role(button, name):
    """Mark a button as "primary" (blue) or "record" (amber) for STYLE."""
    button.setProperty("role", name)
    return button
