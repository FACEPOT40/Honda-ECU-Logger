"""
Honda ECU Logger - M0 protocol probe (READ-ONLY)

Wakes a Honda/Keihin K-line ECU, reads the ECM ID, then polls the live-data
tables (default 0x17 and 0x20) and prints raw bytes plus a HondaECU-style
decode. Everything is also written to a CSV for later analysis.

Only these commands can ever be sent (whitelist):
  wake-up  FE 04 72 8C
  init     72 05 00 F0 99
  query    72 05 71 <table> <checksum>
There are NO flash / EEPROM / security-access commands in this file.

Usage:
  pip install pyserial
  python m0_probe.py --list                      # show COM ports
  python m0_probe.py --port COM3 --seconds 30    # probe the bike
  python m0_probe.py --port COM3 --scan          # which tables exist, which bytes move
  python m0_probe.py --sim --seconds 5           # test without the bike
"""

import argparse
import csv
import datetime as dt
import os
import sys
import time

# decoding and the simulator are shared with the app (project folder = parent of tools\)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from decode import DECODERS  # noqa: E402
from kline import SimECU, payload  # noqa: E402

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    serial = None

BAUD = 10400


# ---------------------------------------------------------------- protocol --

def checksum(data: bytes) -> int:
    return (0x100 - (sum(data) & 0xFF)) & 0xFF


def build(body: bytes) -> bytes:
    return body + bytes([checksum(body)])


WAKE_MSG = bytes.fromhex("FE 04 72 8C")
INIT_MSG = bytes.fromhex("72 05 00 F0 99")


def query_msg(table: int) -> bytes:
    return build(bytes([0x72, 0x05, 0x71, table]))


ALLOWED_PREFIXES = (WAKE_MSG, INIT_MSG, bytes([0x72, 0x05, 0x71]))

# live-data tables listed on the RE-ECU-th PGM-FI sheet (all read with the same 71 query)
SCAN_TABLES = [0x10, 0x11, 0x13, 0x17, 0x20, 0x21, 0x60, 0x61, 0x63, 0x67, 0x70, 0x71, 0xD1]


class KLine:
    """Minimal, read-only Honda K-line client."""

    def __init__(self, port, timeout=0.3, verbose=False):
        self.verbose = verbose
        self.echo = None  # learned on first exchange
        self.ser = serial.Serial(port, BAUD, bytesize=8, parity="N",
                                 stopbits=1, timeout=timeout)

    def close(self):
        self.ser.close()

    def _log(self, *a):
        if self.verbose:
            print("   ", *a)

    def wake(self):
        self.ser.reset_input_buffer()
        self.ser.break_condition = True      # TX low
        time.sleep(0.070)
        self.ser.break_condition = False     # TX high
        time.sleep(0.120)

    def _read_exact(self, n):
        buf = b""
        deadline = time.time() + 0.6
        while len(buf) < n and time.time() < deadline:
            buf += self.ser.read(n - len(buf))
        return buf

    def send(self, msg: bytes):
        if not msg.startswith(ALLOWED_PREFIXES):
            raise RuntimeError("Blocked non-whitelisted command: " + msg.hex(" "))
        self.ser.reset_input_buffer()
        self.ser.write(msg)
        self.ser.flush()
        self._log("TX", msg.hex(" "))
        # K-line is single-wire: our own bytes usually come back as echo.
        first = self._read_exact(len(msg))
        if self.echo is None:
            self.echo = (first == msg)
            self._log("echo detected" if self.echo else "no echo")
        if self.echo:
            if first != msg:
                self._log("echo mismatch:", first.hex(" "))
            return self._read_reply(b"")
        return self._read_reply(first)

    def _read_reply(self, pre: bytes):
        hdr = pre[:2]
        if len(hdr) < 2:
            hdr += self._read_exact(2 - len(hdr))
        if len(hdr) < 2:
            self._log("RX timeout")
            return None
        length = hdr[1]
        rest = pre[2:]
        if length > 2 and len(rest) < length - 2:
            rest += self._read_exact(length - 2 - len(rest))
        msg = hdr + rest
        ok = (sum(msg) & 0xFF) == 0
        self._log("RX", msg.hex(" "), "" if ok else "(BAD CHECKSUM)")
        return msg if ok else None


# ----------------------------------------------------------------- summary --

def print_changes(seen):
    """Per table: the bytes that changed during polling (d0 = first byte after '71 tt')."""
    print("\nBytes that changed (min..max, number of different values):")
    for t, payloads in seen.items():
        n = min(len(d) for d in payloads)
        moved = []
        for i in range(n):
            vals = [d[i] for d in payloads]
            if min(vals) != max(vals):
                moved.append(f"d{i}={min(vals)}..{max(vals)} ({len(set(vals))})")
        print(f"  0x{t:02X} ({len(payloads)} replies): " + (", ".join(moved) or "nothing changed"))


# -------------------------------------------------------------------- main --

def main():
    ap = argparse.ArgumentParser(description="Honda ECU Logger M0 probe (read-only)")
    ap.add_argument("--port", help="COM port, e.g. COM3")
    ap.add_argument("--list", action="store_true", help="list serial ports")
    ap.add_argument("--seconds", type=float, default=20, help="how long to poll")
    ap.add_argument("--tables", default="17,20", help="hex table numbers, e.g. 17,20")
    ap.add_argument("--out", default=None, help="CSV output file")
    ap.add_argument("--scan", action="store_true",
                    help="ask every known table once, then poll the supported ones and "
                         "list the bytes that changed (ignores --tables)")
    ap.add_argument("--sim", action="store_true", help="simulated ECU (no bike)")
    ap.add_argument("-v", "--verbose", action="store_true", help="show raw TX/RX")
    args = ap.parse_args()

    if serial is None and not args.sim:
        sys.exit("pyserial not installed: run  pip install pyserial")

    if args.list:
        for p in serial.tools.list_ports.comports():
            print(f"{p.device:8} {p.description}")
        return

    if not args.port and not args.sim:
        sys.exit("Give --port COMx (use --list to find it) or --sim")

    tables = [int(t, 16) for t in args.tables.split(",")]
    out = args.out or f"m0_log_{dt.datetime.now():%Y%m%d_%H%M%S}.csv"

    ecu = SimECU() if args.sim else KLine(args.port, verbose=args.verbose)
    print(f"Port: {'SIMULATOR' if args.sim else args.port}  |  log: {out}")

    try:
        # 1) wake + init (retry a few times)
        ok = False
        for attempt in range(1, 6):
            print(f"[{attempt}] wake-up ...", end=" ")
            ecu.wake()
            r = ecu.send(WAKE_MSG)
            print("reply:", r.hex(" ") if r else "none", end=" | ")
            r = ecu.send(INIT_MSG)
            print("init:", r.hex(" ") if r else "none")
            if r == bytes.fromhex("02 04 00 FA"):
                ok = True
                break
            time.sleep(0.5)
        if not ok:
            print("\nNo init reply. Check: key ON, kill switch RUN, cable in DLC, "
                  "correct COM port, other ECU programs closed. Try -v for details.")
            return
        print("ECU awake. Echo on line:", getattr(ecu, "echo", "?"))

        # 2) ECM ID
        r = ecu.send(query_msg(0x00))
        print("ECM ID reply:", r.hex(" ") if r else "none",
              "| ID:", payload(r, 0x00)[:5].hex(" ") if r else "-")

        # 2b) scan: find out which tables this ECU has
        if args.scan:
            tables = []
            print("\nScanning tables:")
            for t in SCAN_TABLES:
                r = ecu.send(query_msg(t))
                d = payload(r, t) if r else None
                if r is None:
                    print(f"  0x{t:02X}  no reply")
                elif not d:
                    print(f"  0x{t:02X}  not supported")
                else:
                    print(f"  0x{t:02X}  {len(d):2} bytes  {d.hex(' ')}")
                    tables.append(t)
            if not tables:
                print("No live-data tables answered.")
                return
            print(f"\nPolling {', '.join(f'0x{t:02X}' for t in tables)} for "
                  f"{args.seconds:.0f} s. Rev the engine / spin the wheel now...")

        # 3) poll tables
        seen = {}   # table -> list of payloads, for the changed-bytes summary
        n, errors, t_start = 0, 0, time.time()
        with open(out, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["timestamp", "table", "len", "raw_hex", "decoded"])
            while time.time() - t_start < args.seconds:
                for t in tables:
                    r = ecu.send(query_msg(t))
                    ts = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
                    if not r:
                        errors += 1
                        w.writerow([ts, f"0x{t:02X}", 0, "", "NO REPLY"])
                        continue
                    d = payload(r, t)
                    if not d:   # "02 05 71 tt cs": the ECU has no such table
                        w.writerow([ts, f"0x{t:02X}", 0, "", "NOT SUPPORTED"])
                        print(f"{ts[11:]}  0x{t:02X} not supported by this ECU")
                        continue
                    dec = DECODERS[t](d) if t in DECODERS else None
                    w.writerow([ts, f"0x{t:02X}", len(d), d.hex(" "), dec])
                    seen.setdefault(t, []).append(d)
                    if not args.scan:   # a scan polls many tables; only the summary is printed
                        print(f"{ts[11:]}  0x{t:02X} [{len(d):2}] {d.hex(' ')}")
                        if dec:
                            print("              ", dec)
                    n += 1
        dur = time.time() - t_start
        print(f"\nDone: {n} replies, {errors} errors in {dur:.1f}s "
              f"({n / dur:.1f} replies/s). Log saved to {out}")
        print_changes(seen)
        print("Please send me this console output and the CSV file.")
    finally:
        ecu.close()


if __name__ == "__main__":
    main()
