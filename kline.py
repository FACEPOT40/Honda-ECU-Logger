"""K-line link for Honda/Keihin ECUs. READ-ONLY by design.

Only three kinds of message can ever leave this module (whitelist):
  wake-up  FE 04 72 8C
  init     72 05 00 F0 99
  query    72 05 71 <table> <checksum>
"""

import math
import random
import struct
import time

import decode

try:
    import serial
    import serial.tools.list_ports
except ImportError:  # allows simulator-only use
    serial = None

BAUD = 10400


def checksum(data: bytes) -> int:
    return (0x100 - (sum(data) & 0xFF)) & 0xFF


def build(body: bytes) -> bytes:
    return body + bytes([checksum(body)])


WAKE_MSG = bytes.fromhex("FE 04 72 8C")
INIT_MSG = bytes.fromhex("72 05 00 F0 99")
INIT_OK = bytes.fromhex("02 04 00 FA")
ALLOWED_PREFIXES = (WAKE_MSG, INIT_MSG, bytes([0x72, 0x05, 0x71]))


def query_msg(table: int) -> bytes:
    return build(bytes([0x72, 0x05, 0x71, table]))


def payload(reply: bytes, table: int) -> bytes:
    """Strip header (type, len), echoed '71 tt' and checksum."""
    body = reply[2:-1]
    if len(body) >= 2 and body[0] == 0x71 and body[1] == table:
        body = body[2:]
    return body


def list_ports():
    if serial is None:
        return []
    return [(p.device, p.description) for p in serial.tools.list_ports.comports()]


class KLine:
    def __init__(self, port, timeout=0.3):
        if serial is None:
            raise RuntimeError("pyserial is not installed")
        self.echo = None
        # write_timeout: a write to an unplugged cable must fail, not hang forever
        self.ser = serial.Serial(port, BAUD, bytesize=8, parity="N",
                                 stopbits=1, timeout=timeout, write_timeout=1.0)

    def close(self):
        try:
            self.ser.close()
        except Exception:
            pass

    def abort(self):
        """From another thread: make a read or write that is waiting return now (Disconnect)."""
        for cancel in (getattr(self.ser, "cancel_read", None), getattr(self.ser, "cancel_write", None)):
            try:
                if cancel:
                    cancel()
            except Exception:
                pass

    def wake(self):
        self.ser.reset_input_buffer()
        self.ser.break_condition = True
        time.sleep(0.070)
        self.ser.break_condition = False
        time.sleep(0.120)

    def _read_exact(self, n, limit=0.6):
        buf = b""
        deadline = time.time() + limit
        while len(buf) < n and time.time() < deadline:
            buf += self.ser.read(n - len(buf))
        return buf

    def send(self, msg: bytes):
        if not msg.startswith(ALLOWED_PREFIXES):
            raise RuntimeError("Blocked non-whitelisted command: " + msg.hex(" "))
        self.ser.reset_input_buffer()
        self.ser.write(msg)
        self.ser.flush()
        first = self._read_exact(len(msg))
        if self.echo is None:
            self.echo = (first == msg)
        if self.echo:
            return self._read_reply(b"")
        return self._read_reply(first)

    def _read_reply(self, pre: bytes):
        hdr = pre[:2]
        if len(hdr) < 2:
            hdr += self._read_exact(2 - len(hdr))
        if len(hdr) < 2:
            return None
        length = hdr[1]
        rest = pre[2:]
        if length > 2 and len(rest) < length - 2:
            rest += self._read_exact(length - 2 - len(rest))
        msg = hdr + rest
        return msg if (sum(msg) & 0xFF) == 0 else None


class SimECU:
    """Simulated ECU with plausible riding data (no bike needed)."""
    echo = True

    def __init__(self, *a, **k):
        self.t0 = time.time()

    def close(self):
        pass

    def wake(self):
        time.sleep(0.19)

    def send(self, msg):
        if not msg.startswith(ALLOWED_PREFIXES):
            raise RuntimeError("Blocked")
        time.sleep(0.02)
        if msg == WAKE_MSG:
            return bytes.fromhex("0E 04 72 7C")
        if msg == INIT_MSG:
            return INIT_OK
        table = msg[3]
        t = time.time() - self.t0
        cyc = (math.sin(t / 3) + 1) / 2              # 0..1 slow throttle wave
        tps_raw = int(cyc * 150)
        rpm = int(1400 + cyc * 7000 + random.uniform(-40, 40))
        if table == 0x00:
            data = bytes.fromhex("01 03 3E 0D 01 00 00 00 00 00")   # as the real Wave sends it
        elif table == 0x17:
            ect = int(min(40 + t * 0.5, 112))
            # same layout as the real Wave (see decode.py); 0xFF = sensor not fitted
            inj = int((2.5 + cyc * 4) * decode.INJ_DIVISOR)
            ign = int((16 + cyc * 30 + 64) * 2)
            data = struct.pack(">HBBBB4BBHBBB3B", rpm, 0x19 + int(cyc * 120), tps_raw,
                               0x75, ect + 40, 0xFF, 0xFF, 0xFF, 0xFF,
                               138 - random.randint(0, 5), inj, ign,
                               0x80, 0, 0, 0, 0)   # d14 always 0x80 on the Wave; d15 speed: none
        elif table == 0x20:
            o2 = random.randint(20, 230) if cyc < 0.6 else random.randint(180, 230)
            data = bytes([o2, 128 + random.randint(-6, 6), 0])
        elif table == 0xD1:
            # neutral while the throttle is (nearly) closed, else in gear; fuel pump on
            data = bytes([0x01 if cyc < 0.15 else 0x00, 0, 0, 0, 0x01, 0x10])
        else:
            data = b""   # like a real ECU: "02 05 71 tt cs" = no such table
        body = bytes([0x02, len(data) + 5, 0x71, table]) + data
        return body + bytes([checksum(body)])
