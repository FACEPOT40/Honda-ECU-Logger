"""Build assets/icon.ico (Windows window, taskbar and .exe icon) from assets/icon.svg.

Run again after changing the SVG:  python tools\\make_icon.py
The .ico holds one PNG per size (supported since Windows Vista)."""

import os
import struct
import sys

from PySide6.QtCore import QBuffer, QByteArray, QIODevice, Qt
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

SIZES = [16, 24, 32, 48, 64, 128, 256]
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")


def png(renderer, size):
    img = QImage(size, size, QImage.Format_ARGB32)
    img.fill(Qt.transparent)
    p = QPainter(img)
    p.setRenderHint(QPainter.Antialiasing)
    renderer.render(p)
    p.end()
    data = QByteArray()
    buf = QBuffer(data)
    buf.open(QIODevice.WriteOnly)
    img.save(buf, "PNG")
    return bytes(data)


def main():
    app = QGuiApplication(sys.argv)  # noqa: F841 (needed for QPainter)
    renderer = QSvgRenderer(os.path.join(ASSETS, "icon.svg"))
    pngs = [png(renderer, s) for s in SIZES]
    out = struct.pack("<HHH", 0, 1, len(SIZES))  # ICO header: type 1 = icon
    offset = 6 + 16 * len(SIZES)
    for s, data in zip(SIZES, pngs):
        out += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    out += b"".join(pngs)
    path = os.path.join(ASSETS, "icon.ico")
    with open(path, "wb") as f:
        f.write(out)
    print(f"wrote {os.path.normpath(path)} ({len(out):,} bytes, sizes {SIZES})")


if __name__ == "__main__":
    main()
