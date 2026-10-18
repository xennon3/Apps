"""Step 3: preview what would change, then sync (and undo)."""
from __future__ import annotations

import time

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import (QHBoxLayout, QLabel, QMessageBox, QPushButton, QTableWidget,
                               QVBoxLayout, QWidget)

from ..core.models import Move
from ..core.planner import (BLOCKED, MISSING_FILE, NOT_IN_FEED, RENAME, UNCHANGED, Plan,
                            build_plan)
from ..core.renamer import SyncError, apply_moves, undo_last
from .common import AMBER, GREEN, RED, item, setup_table, thumb_item
from .state import AppState

STALE_SECONDS = 120
LABELS = {
    RENAME: ("Will rename", GREEN),
    UNCHANGED: ("Up to date", None),
    NOT_IN_FEED: ("Not in feed", AMBER),
    MISSING_FILE: ("File missing", RED),
}


class SyncTab(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.state = state
        self.plan = Plan()

        self.sync_btn = QPushButton("Sync abbreviations")
        self.sync_btn.setStyleSheet("QPushButton { font-weight: bold; padding: 6px 18px; }")
        self.sync_btn.clicked.connect(self.sync)
        self.undo_btn = QPushButton("Undo last sync")
        self.undo_btn.clicked.connect(self.undo)
        refresh = QPushButton("Refresh preview")
        refresh.clicked.connect(self.refresh)
        bar = QHBoxLayout()
        bar.addWidget(self.sync_btn)
        bar.addWidget(self.undo_btn)
        bar.addWidget(refresh)
        bar.addStretch()

        self.summary = QLabel()
        self.summary.setStyleSheet("font-size: 14px")
        self.notes = QLabel()
        self.notes.setWordWrap(True)

        self.table = QTableWidget()
        setup_table(self.table, ["Logo", "Team", "Current file", "Feed abbreviation", "New file",
                                 "Result"], stretch=5)

        layout = QVBoxLayout(self)
        layout.addLayout(bar)
        layout.addWidget(self.summary)
        layout.addWidget(self.notes)
        layout.addWidget(self.table, 1)

        for sig in (state.folderChanged, state.mappingChanged, state.filesChanged):
            sig.connect(self.refresh)
        self._feed_version = -1
        QTimer(self, interval=700, timeout=self._watch_feed).start()
        self.refresh()

    def _watch_feed(self) -> None:
        if self.state.snapshot.content_version != self._feed_version and self.isVisible():
            self.refresh()

    def refresh(self) -> None:
        state = self.state
        self._feed_version = state.snapshot.content_version
        self.plan = build_plan(state.teams(), state.snapshot.entries(), state.files())
        rows = sorted(self.plan.rows, key=lambda r: (
            {RENAME: 0, **{b: 1 for b in BLOCKED}, NOT_IN_FEED: 2, UNCHANGED: 3}[r.status],
            r.team.norm_name))
        self.table.setRowCount(len(rows))
        for i, r in enumerate(rows):
            label, color = LABELS.get(r.status, ("Blocked", RED))
            result = f"{label}: {r.detail}" if r.detail else label
            path = None if r.status == MISSING_FILE else state.folder / r.old_name
            self.table.setItem(i, 0, thumb_item(path, r.team.name))
            self.table.setItem(i, 1, item(r.team.name))
            self.table.setItem(i, 2, item(r.old_name))
            self.table.setItem(i, 3, item(r.entry.abbr if r.entry else "—"))
            self.table.setItem(i, 4, item(r.new_name if r.status == RENAME else "",
                                          bold=True, color=GREEN))
            self.table.setItem(i, 5, item(result, color=color))
        self._update_summary()

    def _update_summary(self) -> None:
        p = self.plan
        state = self.state
        if not state.folder:
            self.summary.setText("Choose your logo folder to start.")
            self.notes.setText("")
        elif not p.rows:
            self.summary.setText("No teams mapped yet. Map your logos on the Team mapping tab first.")
            self.notes.setText("")
        else:
            parts = [f"<b style='color:{GREEN.name()}'>{len(p.renames)} to rename</b>",
                     f"{p.count(UNCHANGED)} up to date"]
            if p.count(NOT_IN_FEED):
                parts.append(f"<span style='color:{AMBER.name()}'>{p.count(NOT_IN_FEED)} not in feed</span>")
            blocked = p.count(*BLOCKED)
            if blocked:
                parts.append(f"<b style='color:{RED.name()}'>{blocked} blocked</b>")
            self.summary.setText(" • ".join(parts))
            notes = []
            if p.feed_only:
                names = ", ".join(e.name for e in p.feed_only[:12])
                more = f" and {len(p.feed_only) - 12} more" if len(p.feed_only) > 12 else ""
                notes.append(f"In the feed but no logo mapped: {names}{more}.")
            if blocked:
                notes.append("Blocked rows are skipped; nothing is ever overwritten.")
            stale = self._stale_seconds()
            if stale:
                notes.append(f"⚠ Nothing received from the feed for {stale // 60} min.")
            self.notes.setText(" ".join(notes))
        self.sync_btn.setEnabled(bool(p.renames))
        self.sync_btn.setText(f"Sync abbreviations ({len(p.renames)})" if p.renames
                              else "Sync abbreviations")
        last = state.store.last_completed_sync(state.folder_id) if state.folder_id else None
        self.undo_btn.setEnabled(bool(last and last.kind == "sync" and last.undone_by is None))

    def _stale_seconds(self) -> int:
        at = self.state.snapshot.last_packet_at
        if at is None or not self.state.listening:
            return 0
        age = int(time.time() - at)
        return age if age > STALE_SECONDS else 0

    def sync(self) -> None:
        self.refresh()  # always act on a fresh plan
        renames = self.plan.renames
        if not renames:
            return
        lines = [f"  {r.old_name}  →  {r.new_name}" for r in renames[:15]]
        if len(renames) > 15:
            lines.append(f"  …and {len(renames) - 15} more")
        warn = []
        missing = self.plan.count(NOT_IN_FEED)
        if missing:
            warn.append(f"{missing} mapped team(s) haven't been received from the feed. "
                        "They'll keep their current file names. Is the feed complete?")
        blocked = self.plan.count(*BLOCKED)
        if blocked:
            warn.append(f"{blocked} team(s) are blocked and will be skipped.")
        stale = self._stale_seconds()
        if stale:
            warn.append(f"The feed has been quiet for {stale // 60} min.")
        text = f"Rename {len(renames)} logo file(s)?\n\n" + "\n".join(lines)
        if warn:
            text += "\n\n⚠ " + "\n⚠ ".join(warn)
        if QMessageBox.question(self, "Sync abbreviations", text) != QMessageBox.Yes:
            return
        moves = [Move(r.team.id, r.old_name, r.new_name, r.entry.name, r.entry.abbr)
                 for r in renames]
        self._run(lambda: apply_moves(self.state.store, self.state.folder_id,
                                      self.state.folder, moves),
                  f"Renamed {len(moves)} file(s). The image bytes were verified unchanged.")

    def undo(self) -> None:
        last = self.state.store.last_completed_sync(self.state.folder_id)
        if not last:
            return
        moves = self.state.store.moves(last.id)
        lines = "\n".join(f"  {m.new_name}  →  {m.old_name}" for m in moves[:15])
        if QMessageBox.question(self, "Undo last sync",
                                f"Put {len(moves)} file(s) back to their previous names?\n\n{lines}"
                                ) != QMessageBox.Yes:
            return
        self._run(lambda: undo_last(self.state.store, self.state.folder_id, self.state.folder),
                  f"Undone: {len(moves)} file(s) are back to their previous names.")

    def _run(self, action, success: str) -> None:
        self.state.busy = True
        try:
            action()
        except SyncError as exc:
            QMessageBox.critical(self, "LogoSync", str(exc))
        except OSError as exc:
            QMessageBox.critical(self, "LogoSync", f"Couldn't rename: {exc}")
        else:
            QMessageBox.information(self, "LogoSync", success)
        finally:
            self.state.busy = False
            self.state.mappingChanged.emit()
