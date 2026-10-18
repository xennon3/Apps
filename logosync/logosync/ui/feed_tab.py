"""Step 1: listen to the UDP feed."""
from __future__ import annotations

import threading
import time
from datetime import datetime

from PySide6.QtCore import QTimer, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (QCheckBox, QComboBox, QFileDialog, QFormLayout, QGridLayout,
                               QGroupBox, QHBoxLayout, QLabel, QMessageBox, QPlainTextEdit,
                               QPushButton, QSpinBox, QSplitter, QTableWidget, QVBoxLayout,
                               QWidget)
from PySide6.QtCore import Qt

from ..core.feed import read_feed_file
from ..core.names import normalize_name
from ..core.sender import DEMO_FEED, send_records
from ..core.udp import ALL_ADAPTERS, DEFAULT_PORT, local_addresses
from .common import AMBER, GREEN, ago, item, setup_table
from .state import AppState

FRESH_SECONDS = 5


class FeedTab(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.state = state
        s = state.settings

        # Connection settings
        self.address = QComboBox(editable=True)
        self.address.addItem(f"{ALL_ADAPTERS}   (all network adapters)", ALL_ADAPTERS)
        for ip in local_addresses():
            self.address.addItem(ip, ip)
        saved = s.value("listen/address", ALL_ADAPTERS)
        idx = self.address.findData(saved)
        if idx >= 0:
            self.address.setCurrentIndex(idx)
        else:
            self.address.setEditText(saved)
        self.port = QSpinBox(minimum=1, maximum=65535)
        self.port.setValue(int(s.value("listen/port", DEFAULT_PORT)))
        self.share = QCheckBox("Share the port with other programs")
        self.share.setToolTip("Turn on if another program on this PC also listens to this port.")
        self.share.setChecked(s.value("listen/share", False, type=bool))
        self.autostart = QCheckBox("Start listening when LogoSync opens")
        self.autostart.setChecked(s.value("listen/autostart", True, type=bool))
        self.autostart.toggled.connect(lambda v: s.setValue("listen/autostart", v))
        self.toggle = QPushButton("Start listening")
        self.toggle.setDefault(True)
        self.toggle.clicked.connect(self.toggle_listening)

        conn = QGroupBox("UDP feed")
        form = QFormLayout(conn)
        form.addRow("Listen on", self.address)
        form.addRow("Port", self.port)
        form.addRow("", self.share)
        form.addRow("", self.autostart)
        form.addRow("", self.toggle)

        # Live statistics. "Listening" proves nothing about the server, so show evidence.
        self.stat_state = QLabel()
        self.stat_packets = QLabel()
        self.stat_teams = QLabel()
        self.stat_bad = QLabel()
        self.stat_last = QLabel()
        self.stat_from = QLabel()
        stats = QGroupBox("What's arriving")
        grid = QGridLayout(stats)
        for row, (label, widget) in enumerate([
                ("Status", self.stat_state), ("Packets received", self.stat_packets),
                ("Teams received", self.stat_teams), ("Unreadable records", self.stat_bad),
                ("Last packet", self.stat_last), ("From", self.stat_from)]):
            grid.addWidget(QLabel(label), row, 0)
            grid.addWidget(widget, row, 1)
        grid.setColumnStretch(1, 1)

        clear = QPushButton("Clear feed")
        clear.setToolTip("Forget everything received so far (start a fresh snapshot).")
        clear.clicked.connect(self.clear_feed)
        load = QPushButton("Load feed file…")
        load.setToolTip("Replay a LogoSync recording, or any text file with one\n"
                        "'Team Name, ABBR' per line.")
        load.clicked.connect(self.load_feed)
        test = QPushButton("Send test feed")
        test.setToolTip("Send the demo teams to this PC, to try LogoSync without the real server.")
        test.clicked.connect(self.send_test_feed)
        recordings = QPushButton("Recordings")
        recordings.setToolTip("Every new team or changed abbreviation is saved here, one file per day.")
        recordings.clicked.connect(self.open_recordings)
        tools = QHBoxLayout()
        for b in (clear, load, test, recordings):
            tools.addWidget(b)
        tools.addStretch()

        top = QHBoxLayout()
        top.addWidget(conn, 1)
        top.addWidget(stats, 1)

        self.table = QTableWidget()
        setup_table(self.table, ["Team name", "Abbreviation", "Times received", "Last received",
                                 "Logo in folder"], stretch=0)
        self.table.verticalHeader().setDefaultSectionSize(26)
        self.raw = QPlainTextEdit(readOnly=True)
        self.raw.setMaximumBlockCount(500)
        self.raw.setPlaceholderText("Raw packets appear here exactly as received.")
        font = self.raw.font()
        font.setFamily("Consolas")
        font.setStyleHint(font.StyleHint.Monospace)
        self.raw.setFont(font)

        raw_box = QGroupBox("Raw packets")
        QVBoxLayout(raw_box).addWidget(self.raw)
        teams_box = QGroupBox("Teams in the feed (newest abbreviation wins)")
        QVBoxLayout(teams_box).addWidget(self.table)
        split = QSplitter(Qt.Vertical)
        split.addWidget(teams_box)
        split.addWidget(raw_box)
        split.setStretchFactor(0, 3)
        split.setStretchFactor(1, 1)

        layout = QVBoxLayout(self)
        layout.addLayout(top)
        layout.addLayout(tools)
        layout.addWidget(split, 1)

        self._seen_content = -1
        self._last_raw = 0
        self._table_drawn_at = 0.0
        state.listeningChanged.connect(self.refresh)
        state.mappingChanged.connect(self.redraw_table)
        state.folderChanged.connect(self.redraw_table)
        state.listenFailed.connect(self._listen_failed)
        self.timer = QTimer(self, interval=300, timeout=self.refresh)
        self.timer.start()
        self.refresh()

    # Listening

    def _host(self) -> str:
        data = self.address.currentData()
        text = self.address.currentText().strip()
        if data and text.startswith(data):
            return data
        return text.split()[0] if text else ALL_ADAPTERS

    def toggle_listening(self) -> None:
        if self.state.listening:
            self.state.stop_listening()
        else:
            self.start_listening(quiet=False)

    def start_listening(self, quiet: bool = True) -> bool:
        host, port = self._host(), self.port.value()
        s = self.state.settings
        s.setValue("listen/address", host)
        s.setValue("listen/port", port)
        s.setValue("listen/share", self.share.isChecked())
        try:
            self.state.start_listening(host, port, self.share.isChecked())
            return True
        except OSError as exc:
            msg = (f"Couldn't listen on {host}:{port}.\n\n{exc}\n\n"
                   "Another program may already be using this port. Close it, tick "
                   "“Share the port with other programs”, or pick another port.")
            self.stat_state.setText(f"⚠ {exc}")
            if not quiet:
                QMessageBox.warning(self, "LogoSync", msg)
            return False

    def _listen_failed(self, error: str) -> None:
        QMessageBox.warning(self, "LogoSync", f"Listening stopped: {error}")

    # Feed tools

    def clear_feed(self) -> None:
        self.state.snapshot.clear()
        self.raw.clear()
        self._last_raw = 0
        self.refresh()

    def load_feed(self) -> None:
        start = str(self.state.recordings_dir if self.state.recordings_dir.exists() else "")
        path, _ = QFileDialog.getOpenFileName(self, "Load feed file", start,
                                              "Feed files (*.log *.txt *.csv);;All files (*)")
        if not path:
            return
        try:
            texts = read_feed_file(path)
        except OSError as exc:
            QMessageBox.warning(self, "LogoSync", f"Couldn't read the file:\n{exc}")
            return
        before = len(self.state.snapshot.entries())
        for t in texts:
            self.state.snapshot.ingest_text(t, "file", record=False)
        after = len(self.state.snapshot.entries())
        QMessageBox.information(self, "LogoSync", f"Read {len(texts)} packet(s): "
                                f"{after - before} new team(s), {after} in total.")

    def send_test_feed(self) -> None:
        if not self.state.listening and not self.start_listening(quiet=False):
            return
        host = self.state.listener.host
        target = "127.0.0.1" if host == ALL_ADAPTERS else host
        port = self.state.listener.port
        threading.Thread(target=send_records, args=(DEMO_FEED, target, port),
                         kwargs={"per_packet": 1, "interval": 0.08}, daemon=True).start()

    def open_recordings(self) -> None:
        self.state.recordings_dir.mkdir(parents=True, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.state.recordings_dir)))

    # Display

    def refresh(self) -> None:
        snap = self.state.snapshot
        listening = self.state.listening
        self.toggle.setText("Stop listening" if listening else "Start listening")
        for w in (self.address, self.port, self.share):
            w.setEnabled(not listening)

        fresh = snap.last_packet_at is not None and time.time() - snap.last_packet_at < FRESH_SECONDS
        if listening:
            where = f"{self.state.listener.host}:{self.state.listener.port}"
            if fresh:
                self.stat_state.setText(f"● Receiving on {where}")
                self.stat_state.setStyleSheet(f"color: {GREEN.name()}; font-weight: bold")
            else:
                self.stat_state.setText(f"● Listening on {where}, nothing arriving right now")
                self.stat_state.setStyleSheet(f"color: {AMBER.name()}; font-weight: bold")
        elif not self.stat_state.text().startswith("⚠"):
            self.stat_state.setText("Not listening")
            self.stat_state.setStyleSheet("")
        self.stat_packets.setText(f"{snap.packets:,}")
        entries = snap.entries()
        self.stat_teams.setText(str(len(entries)))
        self.stat_bad.setText(str(snap.bad_records))
        self.stat_bad.setStyleSheet(f"color: {AMBER.name()}" if snap.bad_records else "")
        self.stat_last.setText(ago(snap.last_packet_at))
        self.stat_from.setText(snap.last_sender or "—")

        for p in snap.raw_since(self._last_raw):
            stamp = datetime.fromtimestamp(p.when).strftime("%H:%M:%S.%f")[:-3]
            note = "" if not p.errors else "   ⚠ " + "; ".join(p.errors)
            self.raw.appendPlainText(f"{stamp}  {p.sender:<21} {p.text!r}{note}")
            self._last_raw = p.seq

        # Redraw the team table when content changes, and every few seconds for the times.
        if snap.content_version != self._seen_content or time.time() - self._table_drawn_at > 3:
            self.redraw_table(entries)

    def redraw_table(self, entries=None) -> None:
        snap = self.state.snapshot
        entries = entries if isinstance(entries, list) else snap.entries()
        self._seen_content = snap.content_version
        self._table_drawn_at = time.time()
        logos = {t.norm_name: t.filename for t in self.state.teams()}
        self.table.setRowCount(len(entries))
        for r, e in enumerate(entries):
            changed = f" (was {', '.join(e.earlier_abbrs)})" if e.earlier_abbrs else ""
            logo = logos.get(normalize_name(e.name))
            self.table.setItem(r, 0, item(e.name))
            self.table.setItem(r, 1, item(e.abbr + changed, bold=True,
                                          color=AMBER if changed else None))
            self.table.setItem(r, 2, item(f"{e.count:,}", align_center=True))
            self.table.setItem(r, 3, item(ago(e.last_seen)))
            self.table.setItem(r, 4, item(logo or "— no logo mapped",
                                          color=None if logo else AMBER))
