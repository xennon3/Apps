"""Step 2: tie each logo file to a permanent team (once)."""
from __future__ import annotations

from dataclasses import dataclass
from functools import partial
from pathlib import Path

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import (QApplication, QComboBox, QDialog, QFileDialog, QHBoxLayout,
                               QLabel, QLineEdit, QMenu, QMessageBox, QPushButton, QTableWidget,
                               QVBoxLayout, QWidget)

from ..core.library import split_ext
from ..core.mapping import MappingError, export_mapping, import_mapping, save_mapping
from ..core.matcher import Suggestion, suggest
from ..core.models import Team
from ..core.names import normalize_name
from ..core.replace import ReplaceError, history_dir, replace_logo
from .common import AMBER, GREEN, RED, item, setup_table, thumb_item
from .logo_dialogs import HistoryDialog, ReplaceDialog
from .state import AppState

COL_LOGO, COL_FILE, COL_TEAM, COL_ID, COL_STATUS = range(5)


@dataclass
class Row:
    filename: str
    team: Team | None
    missing: bool = False

    @property
    def key(self) -> str:
        return self.filename.casefold()

    @property
    def saved_name(self) -> str:
        return self.team.name if self.team else ""


class DropTable(QTableWidget):
    """A table that takes a PNG dropped onto a row (to replace that team's logo)."""
    pngDropped = Signal(int, str)

    def __init__(self):
        super().__init__()
        self.setAcceptDrops(True)
        self.viewport().setAcceptDrops(True)
        self.setDragDropMode(QTableWidget.DropOnly)

    @staticmethod
    def _png(event) -> str | None:
        urls = event.mimeData().urls() if event.mimeData().hasUrls() else []
        if len(urls) == 1 and urls[0].isLocalFile() and urls[0].toLocalFile().lower().endswith(".png"):
            return urls[0].toLocalFile()
        return None

    def dragEnterEvent(self, event):
        if self._png(event):
            event.acceptProposedAction()
        else:
            event.ignore()

    def dragMoveEvent(self, event):
        row = self.rowAt(event.position().toPoint().y())
        if self._png(event) and row >= 0:
            self.selectRow(row)
            event.acceptProposedAction()
        else:
            event.ignore()

    def dropEvent(self, event):
        path = self._png(event)
        row = self.rowAt(event.position().toPoint().y())
        if path and row >= 0:
            event.acceptProposedAction()
            self.pngDropped.emit(row, path)
        else:
            event.ignore()


class MappingTab(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.state = state
        self.rows: list[Row] = []
        self.combos: dict[str, QComboBox] = {}
        self.pending: dict[str, str] = {}             # row key -> unsaved team name
        self.suggested: dict[str, Suggestion] = {}    # row key -> suggestion behind it
        self._stale = False
        self._feed = {}
        self._versions: dict[int, int] = {}

        intro = QLabel(
            "Tie each logo to its team <b>once</b>. Pick the team name exactly as the feed "
            "sends it; LogoSync remembers the team by a permanent ID, so next season only the "
            "file name changes. Tip: listen to the feed first, then click <b>Suggest matches</b>.")
        intro.setWordWrap(True)

        self.suggest_btn = QPushButton("✨ Suggest matches from feed")
        self.suggest_btn.clicked.connect(self.suggest_matches)
        self.save_btn = QPushButton("Save mapping")
        self.save_btn.clicked.connect(self.save)
        self.discard_btn = QPushButton("Discard changes")
        self.discard_btn.clicked.connect(self.discard)
        self.forget_btn = QPushButton("Forget missing teams")
        self.forget_btn.clicked.connect(self.forget_missing)
        self.replace_btn = QPushButton("Replace logo\u2026")
        self.replace_btn.setToolTip("New artwork for the selected team. It keeps its file name and "
                                    "team ID; the old image goes to Logo history.\n"
                                    "Tip: you can also drag a PNG onto a team's row.")
        self.replace_btn.clicked.connect(lambda: self.replace_logo())
        self.history_btn = QPushButton("Logo history\u2026")
        self.history_btn.setToolTip("Earlier logos of the selected team, with Restore.")
        self.history_btn.clicked.connect(lambda: self.show_history())
        export_btn = QPushButton("Export…")
        export_btn.setToolTip("Save the mapping to a .json file (backup, or use on another PC).")
        export_btn.clicked.connect(self.export)
        import_btn = QPushButton("Import…")
        import_btn.setToolTip("Load a mapping exported earlier. Logos are matched by their image "
                              "content, so it works even if files were renamed.")
        import_btn.clicked.connect(self.import_)
        self.filter = QLineEdit(placeholderText="Filter…", clearButtonEnabled=True)
        self.filter.setMaximumWidth(220)
        self.filter.textChanged.connect(self.apply_filter)
        self.summary = QLabel()

        bar = QHBoxLayout()
        for b in (self.suggest_btn, self.save_btn, self.discard_btn, self.forget_btn):
            bar.addWidget(b)
        bar.addSpacing(16)
        bar.addWidget(self.replace_btn)
        bar.addWidget(self.history_btn)
        bar.addStretch()
        bar.addWidget(export_btn)
        bar.addWidget(import_btn)
        bar.addWidget(self.filter)

        self.table = DropTable()
        self.table.pngDropped.connect(lambda r, path: self.replace_logo(self.rows[r], Path(path)))
        self.table.itemSelectionChanged.connect(self._update_buttons)
        self.table.setContextMenuPolicy(Qt.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self._context_menu)
        setup_table(self.table, ["Logo", "File", "Team (as named in the feed)", "Team ID", "Status"],
                    stretch=COL_STATUS)
        self.table.horizontalHeader().setMinimumSectionSize(60)

        self.empty = QLabel("Choose your logo folder (top of the window) to start.")
        self.empty.setAlignment(Qt.AlignCenter)
        self.empty.setStyleSheet("font-size: 15px; color: palette(mid); padding: 40px")

        layout = QVBoxLayout(self)
        layout.addWidget(intro)
        layout.addLayout(bar)
        layout.addWidget(self.summary)
        layout.addWidget(self.table, 1)
        layout.addWidget(self.empty, 1)

        state.folderChanged.connect(self._folder_changed)
        state.mappingChanged.connect(self.rebuild)
        state.filesChanged.connect(self.rebuild)
        self._feed_version = -1
        QTimer(self, interval=700, timeout=self._watch_feed).start()
        self.rebuild()

    # Data

    def text_of(self, row: Row) -> str:
        return self.pending.get(row.key, row.saved_name)

    def dirty(self) -> bool:
        return bool(self.pending)

    def _folder_changed(self) -> None:
        self.pending.clear()
        self.suggested.clear()
        self.rebuild()

    def _watch_feed(self) -> None:
        version = self.state.snapshot.content_version
        if version == self._feed_version and not self._stale:
            return
        focus = QApplication.focusWidget()
        if focus is not None and self.table.isAncestorOf(focus):
            self._stale = True  # don't rebuild under the user's cursor
            return
        self._feed_version = version
        self._stale = False
        self.rebuild()

    # Table

    def rebuild(self) -> None:
        has_folder = self.state.folder is not None
        self.table.setVisible(has_folder)
        self.empty.setVisible(not has_folder)
        scroll = self.table.verticalScrollBar().value()
        files = self.state.files()
        teams = self.state.teams()
        by_file = {t.filename.casefold(): t for t in teams}
        on_disk = {f.casefold() for f in files}
        self.rows = [Row(f, by_file.get(f.casefold())) for f in files]
        self.rows += [Row(t.filename, t, missing=True) for t in teams
                      if t.filename.casefold() not in on_disk]
        live_keys = {r.key for r in self.rows}
        self.pending = {k: v for k, v in self.pending.items() if k in live_keys}

        entries = self.state.snapshot.entries()
        self._feed = {e.key: e for e in entries}
        self._versions = (self.state.store.logo_version_counts(self.state.folder_id)
                          if self.state.folder_id else {})
        names = sorted({*(e.name for e in entries), *(t.name for t in teams)}, key=str.casefold)
        self.combos.clear()
        self.table.setRowCount(len(self.rows))
        folder = self.state.folder
        for r, row in enumerate(self.rows):
            path = None if row.missing else folder / row.filename
            self.table.setItem(r, COL_LOGO, thumb_item(path, row.filename))
            self.table.setItem(r, COL_FILE, item(row.filename, color=RED if row.missing else None))
            self.table.setItem(r, COL_ID, item(f"#{row.team.id}" if row.team else "",
                                               align_center=True))
            combo = QComboBox(editable=True)
            combo.setInsertPolicy(QComboBox.NoInsert)
            combo.setMinimumWidth(220)
            combo.addItems(names)
            combo.setCurrentIndex(-1)
            combo.setEditText(self.text_of(row))
            combo.lineEdit().setPlaceholderText("Choose or type the team name…")
            combo.setAcceptDrops(False)
            combo.lineEdit().setAcceptDrops(False)  # let dropped PNGs reach the table
            completer = combo.completer()
            completer.setCaseSensitivity(Qt.CaseInsensitive)
            completer.setFilterMode(Qt.MatchContains)
            combo.setEnabled(not row.missing)
            combo.currentTextChanged.connect(partial(self._edited, row))
            self.combos[row.key] = combo
            self.table.setCellWidget(r, COL_TEAM, combo)
            self._update_status(r, row)
        self.apply_filter()
        self._update_buttons()
        self.table.verticalScrollBar().setValue(scroll)

    def _edited(self, row: Row, text: str) -> None:
        if " ".join(text.split()) == row.saved_name:
            self.pending.pop(row.key, None)
        else:
            self.pending[row.key] = text
        self.suggested.pop(row.key, None)
        self._update_status(self.rows.index(row), row)
        self._update_buttons()

    def _update_status(self, r: int, row: Row) -> None:
        text = " ".join(self.text_of(row).split())
        feed = self._feed
        entry = feed.get(normalize_name(text)) if text else None
        sug = self.suggested.get(row.key)
        if row.missing:
            status, color = ("File not found: renamed or deleted outside LogoSync. Map its "
                             "new file to this team, or Forget it."), RED
        elif row.key in self.pending and not text:
            status, color = "Will be unmapped when you save", AMBER
        elif not text:
            status, color = "Not mapped", None
        elif sug is not None and sug.alternatives:
            status, color = f"Check this: could also be {', '.join(sug.alternatives)}", RED
        elif sug is not None:
            status, color = "Suggested; check it, then Save", AMBER
        elif row.key in self.pending:
            status, color = "Changed; not saved yet", AMBER
        else:
            status, color = "✓ Saved", GREEN
        if text and not row.missing and feed and entry is None:
            status += "  •  ⚠ no team with this name in the feed"
            color = color if color is RED else AMBER
        elif entry is not None and not row.missing:
            stem = split_ext(row.filename)[0]
            status += ("  •  feed: " + entry.abbr +
                       ("" if entry.abbr == stem else f"  (→ {entry.abbr}{split_ext(row.filename)[1]})"))
        earlier = self._versions.get(row.team.id, 0) if row.team else 0
        if earlier:
            status += f"  \u2022  {earlier} earlier logo{'s' if earlier > 1 else ''}"
        self.table.setItem(r, COL_STATUS, item(status, color=color))

    def _update_buttons(self) -> None:
        dirty = self.dirty()
        self.save_btn.setEnabled(dirty)
        self.discard_btn.setEnabled(dirty)
        self.save_btn.setText("Save mapping *" if dirty else "Save mapping")
        selected = self._selected_row()
        can_replace = bool(selected and selected.team and not selected.missing)
        self.replace_btn.setEnabled(can_replace)
        self.history_btn.setEnabled(bool(selected and selected.team))
        missing = sum(r.missing for r in self.rows)
        self.forget_btn.setVisible(missing > 0)
        mapped = sum(1 for r in self.rows if not r.missing and r.team)
        files = sum(1 for r in self.rows if not r.missing)
        parts = [f"{files} logo file(s)", f"{mapped} mapped", f"{files - mapped} not mapped"]
        if missing:
            parts.append(f"{missing} team(s) missing their file")
        if dirty:
            parts.append(f"{len(self.pending)} unsaved change(s)")
        self.summary.setText("  •  ".join(parts) if self.state.folder else "")

    def apply_filter(self) -> None:
        needle = self.filter.text().casefold().strip()
        for r, row in enumerate(self.rows):
            hay = f"{row.filename} {self.text_of(row)}".casefold()
            self.table.setRowHidden(r, bool(needle) and needle not in hay)

    # Actions

    def suggest_matches(self) -> None:
        entries = self.state.snapshot.entries()
        if not entries:
            QMessageBox.information(
                self, "LogoSync", "Suggestions come from the team names in the feed.\n\n"
                "Listen to the feed first (step 1), or use Load feed file, then try again.")
            return
        taken = {normalize_name(self.text_of(r)) for r in self.rows if self.text_of(r).strip()}
        candidates = [(e.name, e.abbr) for e in entries if e.key not in taken]
        open_rows = [r for r in self.rows if not r.missing and not self.text_of(r).strip()]
        found = suggest({r.filename: split_ext(r.filename)[0] for r in open_rows}, candidates)
        for row in open_rows:
            sug = found.get(row.filename)
            if sug:
                self.pending[row.key] = sug.name
                self.suggested[row.key] = sug
        self.rebuild()
        close = sum(1 for s in found.values() if s.alternatives)
        msg = f"Suggested a team for {len(found)} of {len(open_rows)} unmapped logo(s)."
        if close:
            msg += f"\n\n{close} are close calls (marked in red). Check those carefully."
        if found:
            msg += "\n\nReview the suggestions, fix any that are wrong, then click Save mapping."
        QMessageBox.information(self, "LogoSync", msg)

    def save(self) -> None:
        if not self.state.folder_id:
            return
        assignments = {r.filename: self.text_of(r).strip() for r in self.rows if not r.missing}
        unmapping = [r for r in self.rows if r.team and not r.missing and not assignments[r.filename]]
        if unmapping:
            names = "\n".join(f"  {r.filename}: {r.saved_name}" for r in unmapping[:15])
            if QMessageBox.question(self, "LogoSync", f"Remove the team from these logos?\n\n{names}"
                                    ) != QMessageBox.Yes:
                return
        try:
            save_mapping(self.state.store, self.state.folder_id, self.state.folder, assignments)
        except (MappingError, OSError) as exc:
            QMessageBox.warning(self, "LogoSync", str(exc))
            return
        self.pending.clear()
        self.suggested.clear()
        self.state.mappingChanged.emit()

    def discard(self) -> None:
        self.pending.clear()
        self.suggested.clear()
        self.rebuild()

    def forget_missing(self) -> None:
        missing = [r for r in self.rows if r.missing and r.team]
        names = "\n".join(f"  {r.saved_name} (was {r.filename})" for r in missing[:20])
        if QMessageBox.question(self, "LogoSync", "Forget these teams? Their files are gone, "
                                f"so LogoSync can't rename them.\n\n{names}") != QMessageBox.Yes:
            return
        for r in missing:
            self.state.store.delete_team(r.team.id)
        self.state.mappingChanged.emit()

    def export(self) -> None:
        if not self.state.folder_id:
            return
        path, _ = QFileDialog.getSaveFileName(self, "Export mapping",
                                              str(self.state.folder / "logosync-mapping.json"),
                                              "LogoSync mapping (*.json)")
        if path:
            n = export_mapping(self.state.store, self.state.folder_id, path)
            QMessageBox.information(self, "LogoSync", f"Exported {n} team(s).")

    def import_(self) -> None:
        if not self.state.folder_id:
            return
        path, _ = QFileDialog.getOpenFileName(self, "Import mapping", str(self.state.folder),
                                              "LogoSync mapping (*.json)")
        if not path:
            return
        try:
            proposed = import_mapping(self.state.store, self.state.folder_id, self.state.folder, path)
        except MappingError as exc:
            QMessageBox.warning(self, "LogoSync", str(exc))
            return
        for filename, name in proposed.items():
            self.pending[filename.casefold()] = name
        self.rebuild()
        QMessageBox.information(self, "LogoSync", f"Matched {len(proposed)} logo(s). "
                                "Check them, then click Save mapping.")

    # Logo replacement

    def _selected_row(self) -> Row | None:
        if not hasattr(self, "table"):
            return None
        rows = self.table.selectionModel().selectedRows() if self.table.selectionModel() else []
        return self.rows[rows[0].row()] if rows and rows[0].row() < len(self.rows) else None

    def _context_menu(self, pos) -> None:
        r = self.table.rowAt(pos.y())
        if r < 0:
            return
        self.table.selectRow(r)
        row = self.rows[r]
        menu = QMenu(self)
        replace = menu.addAction("Replace logo\u2026", lambda: self.replace_logo(row))
        history = menu.addAction("Logo history\u2026", lambda: self.show_history(row))
        replace.setEnabled(bool(row.team and not row.missing))
        history.setEnabled(bool(row.team))
        menu.exec(self.table.viewport().mapToGlobal(pos))

    def replace_logo(self, row: Row | None = None, source: Path | None = None) -> None:
        row = row or self._selected_row()
        if row is None or row.team is None or row.missing:
            QMessageBox.information(self, "LogoSync", "Select a team whose mapping is saved, "
                                    "then replace its logo. (Unmapped logos have no team yet: "
                                    "map and Save them first.)")
            return
        if source is None:
            path, _ = QFileDialog.getOpenFileName(self, f"New logo for {row.team.name}",
                                                  str(self.state.folder), "PNG images (*.png)")
            if not path:
                return
            source = Path(path)
        team = self.state.store.team(row.team.id)
        current = self.state.folder / row.filename
        if ReplaceDialog(self, team, current, source).exec() != QDialog.Accepted:
            return
        self.state.busy = True
        try:
            replace_logo(self.state.store, team, self.state.folder, source,
                         history_dir(self.state.data_dir, self.state.folder_id, team.id))
        except (ReplaceError, OSError) as exc:
            QMessageBox.warning(self, "LogoSync", str(exc))
            return
        finally:
            self.state.busy = False
            self.state.mappingChanged.emit()
        self._status(f"Replaced the logo of {team.name} ({row.filename}). "
                     "The previous image is in Logo history.")

    def show_history(self, row: Row | None = None) -> None:
        row = row or self._selected_row()
        if row is None or row.team is None:
            return
        if row.missing:
            QMessageBox.information(self, "LogoSync", f"{row.filename} is missing from the folder, "
                                    "so there's no current logo to swap with.")
            return
        dlg = HistoryDialog(self, self.state, row.team)
        dlg.exec()
        if dlg.changed:
            self.state.mappingChanged.emit()

    def _status(self, text: str) -> None:
        bar = self.window().statusBar() if hasattr(self.window(), "statusBar") else None
        if bar is not None:
            bar.showMessage(text, 8000)
