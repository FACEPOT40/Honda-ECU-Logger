"""Background polling thread: wake -> init -> ECM ID -> poll 0x17/0x20 forever.
Auto-reconnects after repeated missing replies, and keeps retrying when the cable or COM port
disappears (unplugged), until Disconnect."""

import time

from PySide6.QtCore import QThread, Signal

from decode import DECODERS, add_derived
from kline import INIT_MSG, INIT_OK, WAKE_MSG, KLine, SimECU, payload, query_msg, read_dtcs

# tables that change slowly are asked only every Nth cycle, so the main values stay fast
EVERY = {0xD1: 5}   # neutral / in gear

ECU_NAMES = {
    "01 02 58 0d 01": "38770-K73-T32",
    "01 03 3e 0d 01": "38770-K73-T61",
    "01 03 4f 18 01": "38770-K73-TC2",
}


class PollWorker(QThread):
    status = Signal(str)
    ecm = Signal(str)
    sample = Signal(dict)
    faults = Signal(object)    # kline.read_dtcs() result, after connecting and on request

    def __init__(self, port=None, simulate=False):
        super().__init__()
        self.port = port
        self.simulate = simulate
        self._run = True
        self.link = None
        self._want_faults = False

    def request_faults(self):
        """Read the fault codes again between two polling cycles (read only)."""
        self._want_faults = True

    def stop(self):
        self._run = False
        link = self.link
        if link is not None and hasattr(link, "abort"):
            link.abort()        # unblock a read or write so the thread can finish quickly

    def _pause(self, seconds):
        """Sleep, but return early on Disconnect."""
        end = time.time() + seconds
        while self._run and time.time() < end:
            time.sleep(0.1)

    def _connect(self):
        link = SimECU() if self.simulate else KLine(self.port)
        self.link = link
        for attempt in range(1, 6):
            if not self._run:
                break
            self.status.emit(f"Waking ECU (try {attempt}/5)…")
            link.wake()
            link.send(WAKE_MSG)
            if link.send(INIT_MSG) == INIT_OK:
                return link
            time.sleep(0.5)
        link.close()
        return None

    def run(self):
        while self._run:
            link = None
            try:
                link = self._connect()
                if link is None:
                    if self._run:
                        self.status.emit("No reply from ECU. Key ON? Cable in DLC? Retrying…")
                    self._pause(2)
                    continue
                self._poll(link)
                if self._run:
                    self.status.emit("Connection lost, reconnecting…")
            except Exception as e:
                # COM port missing or busy, or the cable was unplugged while reading:
                # keep trying, so plugging it back in resumes logging (and a recording)
                if self._run:
                    self.status.emit(f"Error: {e}. Retrying…")
                    self._pause(2)
            finally:
                if link is not None:
                    link.close()
                self.link = None
        self.status.emit("Disconnected")

    def _poll(self, link):
        """ECM ID, then poll the tables until 5 cycles in a row get no reply."""
        r = link.send(query_msg(0x00))
        if r:
            # the Wave sends 5 ID bytes + 5 zero bytes; the ID is the first 5
            ecm_id = payload(r, 0x00)[:5].hex(" ")
            self.ecm.emit(f"{ecm_id.upper()}  ({ECU_NAMES.get(ecm_id, 'unknown')})")
        connected = "Connected" + (" (SIMULATOR)" if self.simulate else "")
        self.faults.emit(read_dtcs(link))      # about 22 short queries, once per connection
        self._want_faults = False
        self.status.emit(connected)

        tables = list(DECODERS)
        last = {}   # latest values of the slow tables, repeated in every sample
        cycle = misses = 0
        while self._run and misses < 5:
            if self._want_faults:
                self._want_faults = False
                self.faults.emit(read_dtcs(link))
            s = {"t": time.time()}
            got = False
            for t in list(tables):
                if cycle % EVERY.get(t, 1):
                    continue
                r = link.send(query_msg(t))
                if not r:
                    continue
                got = True
                d = payload(r, t)
                if not d:
                    # "02 05 71 tt cs": the ECU answered but has no such table
                    tables.remove(t)
                    self.status.emit(f"{connected}, table 0x{t:02X} not supported by this ECU")
                    continue
                vals = DECODERS[t](d)
                vals[f"raw_{t:02x}"] = d.hex()
                if t in EVERY:
                    last[t] = vals
                else:
                    s.update(vals)
            for vals in last.values():
                s.update(vals)
            cycle += 1
            if got:
                misses = 0
                self.sample.emit(add_derived(s))
            else:
                misses += 1
