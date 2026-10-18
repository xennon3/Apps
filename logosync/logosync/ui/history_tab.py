"""Every sync and undo, with the exact file moves."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QLabel, QSplitter, QTableWidget, QVBoxLayout, QWidget

from .common import AMBER, GREEN, RED, item, setup_table
from .state import AppState

STATUS = {
    "done": ("Done", GREEN),
    "rolled_back": ("Failed, all files put back", AMBER),
    "failed": ("Failed, needs attention", RED),
    "running": ("In progress", AMBER),
}


class HistoryTab(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.state = state
        self.syncs = []
        self.list = QTableWidget()
        setup_table(self.list, ["When", "Action", "Files", "Result", "Details"], stretch=4)
        self.list.verticalHeader().setDefaultSectionSize(26)
        self.list.itemSelectionChanged.connect(self.show_moves)
        self.moves = QTableWidget()
        setup_table(self.moves, ["Team", "Feed abbreviation", "From", "To", "State"], stretch=0)
        self.moves.verticalHeader().setDefaultSectionSize(26)

        split = QSplitter(Qt.Vertical)
        split.addWidget(self.list)
        split.addWidget(self.moves)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Every sync and undo for this folder. Select one to see its file moves."))
        layout.addWidget(split, 1)
        for sig in (state.folderChanged, state.mappingChanged):
            sig.connect(self.refresh)
        self.refresh()

    def refresh(self) -> None:
        self.syncs = self.state.store.syncs(self.state.folder_id) if self.state.folder_id else []
        self.list.setRowCount(len(self.syncs))
        for r, s in enumerate(self.syncs):
            label, color = STATUS.get(s.status, (s.status, None))
            action = "Undo" if s.kind == "undo" else "Sync"
            if s.undone_by:
                action += " (undone)"
            self.list.setItem(r, 0, item(s.started_at.replace("T", "  ")))
            self.list.setItem(r, 1, item(action))
            self.list.setItem(r, 2, item(str(s.moves), align_center=True))
            self.list.setItem(r, 3, item(label, color=color))
            self.list.setItem(r, 4, item(s.message, tooltip=s.message))
        self.moves.setRowCount(0)
        if self.syncs:
            self.list.selectRow(0)

    def show_moves(self) -> None:
        rows = self.list.selectionModel().selectedRows()
        if not rows:
            return
        sync = self.syncs[rows[0].row()]
        moves = self.state.store.moves(sync.id)
        names = {t.id: t.name for t in self.state.teams()}
        self.moves.setRowCount(len(moves))
        for r, m in enumerate(moves):
            self.moves.setItem(r, 0, item(names.get(m.team_id, m.feed_name or f"#{m.team_id}")))
            self.moves.setItem(r, 1, item(m.abbr))
            self.moves.setItem(r, 2, item(m.old_name))
            self.moves.setItem(r, 3, item(m.new_name))
            self.moves.setItem(r, 4, item(m.state))
