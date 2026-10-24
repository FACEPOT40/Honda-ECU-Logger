"""Background polling thread: wake -> init -> ECM ID -> poll 0x17/0x20 forever.
Auto-reconnects after repeated missing replies."""

import time

from PySide6.QtCore import QThread, Signal

from decode import DECODERS
from kline import INIT_MSG, INIT_OK, WAKE_MSG, KLine, SimECU, payload, query_msg

ECU_NAMES = {
    "01 02 58 0d 01": "38770-K73-T32",
    "01 03 3e 0d 01": "38770-K73-T61",
    "01 03 4f 18 01": "38770-K73-TC2",
}


class PollWorker(QThread):
    status = Signal(str)
    ecm = Signal(str)
    sample = Signal(dict)

    def __init__(self, port=None, simulate=False):
        super().__init__()
        self.port = port
        self.simulate = simulate
        self._run = True

    def stop(self):
        self._run = False

    def _connect(self):
        link = SimECU() if self.simulate else KLine(self.port)
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
            try:
                link = self._connect()
            except Exception as e:  # e.g. COM port busy / missing
                self.status.emit(f"Error: {e}")
                return
            if link is None:
                self.status.emit("No reply from ECU. Key ON? Cable in DLC? Retrying…")
                time.sleep(2)
                continue

            r = link.send(query_msg(0x00))
            if r:
                # the Wave sends 5 ID bytes + 5 zero bytes; the ID is the first 5
                ecm_id = payload(r, 0x00)[:5].hex(" ")
                self.ecm.emit(f"{ecm_id.upper()}  ({ECU_NAMES.get(ecm_id, 'unknown')})")
            connected = "Connected" + (" (SIMULATOR)" if self.simulate else "")
            self.status.emit(connected)

            tables = list(DECODERS)
            misses = 0
            while self._run and misses < 5:
                s = {"t": time.time()}
                got = False
                for t in list(tables):
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
                    s.update(DECODERS[t](d))
                    s[f"raw_{t:02x}"] = d.hex()
                if got:
                    misses = 0
                    self.sample.emit(s)
                else:
                    misses += 1
            link.close()
            if self._run:
                self.status.emit("Connection lost, reconnecting…")
        self.status.emit("Disconnected")
