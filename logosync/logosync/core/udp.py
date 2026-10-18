"""Background UDP listener (no Qt, so it can be tested on its own)."""
from __future__ import annotations

import logging
import socket
import threading
from typing import Callable

log = logging.getLogger(__name__)

DEFAULT_PORT = 20004
ALL_ADAPTERS = "0.0.0.0"


def local_addresses() -> list[str]:
    """IPv4 addresses of this PC's network adapters, best effort."""
    found = {"127.0.0.1"}
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except OSError:
        pass
    return sorted(found)


class UdpListener:
    def __init__(self, host: str, port: int,
                 on_datagram: Callable[[bytes, str], None],
                 on_error: Callable[[Exception], None] | None = None,
                 share_port: bool = False):
        self.host = host or ALL_ADAPTERS
        self.port = port
        self.on_datagram = on_datagram
        self.on_error = on_error
        self.share_port = share_port
        self._sock: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        """Bind and start receiving. Raises OSError (e.g. port in use) right away."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            if self.share_port:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
            except OSError:
                pass
            sock.bind((self.host, self.port))
            sock.settimeout(0.5)
        except OSError:
            sock.close()
            raise
        self._sock = sock
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="udp-listener", daemon=True)
        self._thread.start()
        log.info("Listening on %s:%s", self.host, self.port)

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)
        if self._sock:
            self._sock.close()
        self._sock = None
        self._thread = None

    def _run(self) -> None:
        sock = self._sock
        while not self._stop.is_set():
            try:
                data, addr = sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError as exc:
                if self._stop.is_set():
                    break
                # Windows reports an earlier ICMP "port unreachable" on the next
                # receive (WSAECONNRESET); it means nothing for a listener.
                if getattr(exc, "winerror", None) == 10054:
                    continue
                log.exception("UDP receive failed")
                if self.on_error:
                    self.on_error(exc)
                break
            try:
                self.on_datagram(data, f"{addr[0]}:{addr[1]}")
            except Exception:
                log.exception("Error handling datagram")
