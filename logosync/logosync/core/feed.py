"""The feed snapshot: the newest abbreviation received for each team.

Written from the UDP thread, read from the GUI thread, so every access goes
through one lock. The GUI polls `version` / `content_version` to know when to
redraw.
"""
from __future__ import annotations

import re
import threading
import time
from collections import deque
from dataclasses import dataclass, field, replace
from datetime import datetime
from pathlib import Path

from .names import normalize_name
from .parser import ParseResult, decode, parse_text


@dataclass
class FeedEntry:
    name: str
    abbr: str
    first_seen: float
    last_seen: float
    count: int = 1
    earlier_abbrs: list[str] = field(default_factory=list)

    @property
    def key(self) -> str:
        return normalize_name(self.name)


@dataclass
class RawPacket:
    seq: int
    when: float
    sender: str
    text: str
    records: int
    errors: list[str]


class FeedRecorder:
    """Appends datagrams that changed the snapshot to a daily log file.

    The feed may repeat all day, so only new teams and changed abbreviations
    (plus bad packets) are written. A recording can be replayed with
    `read_feed_file` / Load feed file.
    """

    def __init__(self, folder: Path):
        self.folder = Path(folder)

    def write(self, when: float, sender: str, text: str) -> None:
        stamp = datetime.fromtimestamp(when)
        escaped = text.encode("unicode_escape").decode("ascii")
        try:
            self.folder.mkdir(parents=True, exist_ok=True)
            path = self.folder / f"feed-{stamp:%Y-%m-%d}.log"
            with open(path, "a", encoding="utf-8") as f:
                f.write(f"{stamp.isoformat(timespec='milliseconds')}\t{sender}\t{escaped}\n")
        except OSError:
            pass  # Recording is a convenience; never let it stop the feed.


_RECORDED_LINE = re.compile(r"^\d{4}-\d\d-\d\dT\S*\t[^\t]*\t(.*)$")


def read_feed_file(path: Path) -> list[str]:
    """Datagram texts from a LogoSync recording, or one per line from any text file."""
    texts = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\r\n")
            m = _RECORDED_LINE.match(line)
            if m:
                texts.append(m.group(1).encode("ascii", "replace").decode("unicode_escape"))
            elif line.strip():
                texts.append(line)
    return texts


class FeedSnapshot:
    def __init__(self, recorder: FeedRecorder | None = None, raw_limit: int = 300):
        self._lock = threading.Lock()
        self._entries: dict[str, FeedEntry] = {}
        self._raw: deque[RawPacket] = deque(maxlen=raw_limit)
        self.recorder = recorder
        self.packets = 0
        self.bad_records = 0
        self.last_packet_at: float | None = None
        self.last_sender = ""
        self.version = 0          # bumps on every packet
        self.content_version = 0  # bumps only when teams/abbreviations change

    def ingest(self, data: bytes, sender: str = "", now: float | None = None) -> ParseResult:
        return self.ingest_text(decode(data), sender, now)

    def ingest_text(self, text: str, sender: str = "", now: float | None = None,
                    record: bool = True) -> ParseResult:
        now = time.time() if now is None else now
        result = parse_text(text)
        changed = False
        with self._lock:
            self.packets += 1
            self.bad_records += len(result.errors)
            self.last_packet_at = now
            self.last_sender = sender
            for rec in result.records:
                key = normalize_name(rec.name)
                entry = self._entries.get(key)
                if entry is None:
                    self._entries[key] = FeedEntry(rec.name, rec.abbr, now, now)
                    changed = True
                    continue
                entry.count += 1
                entry.last_seen = now
                entry.name = rec.name
                if entry.abbr != rec.abbr:
                    entry.earlier_abbrs.append(entry.abbr)
                    entry.abbr = rec.abbr
                    changed = True
            self._raw.append(RawPacket(self.packets, now, sender, text,
                                       len(result.records), result.errors))
            self.version += 1
            if changed:
                self.content_version += 1
        if record and self.recorder and (changed or result.errors):
            self.recorder.write(now, sender, text)
        return result

    def entries(self) -> list[FeedEntry]:
        with self._lock:
            return sorted((replace(e, earlier_abbrs=list(e.earlier_abbrs))
                           for e in self._entries.values()),
                          key=lambda e: e.key)

    def raw_since(self, seq: int) -> list[RawPacket]:
        with self._lock:
            return [p for p in self._raw if p.seq > seq]

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()
            self._raw.clear()
            self.packets = 0
            self.bad_records = 0
            self.last_packet_at = None
            self.last_sender = ""
            self.version += 1
            self.content_version += 1
