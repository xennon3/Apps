"""Replace-logo and logo-history dialogs."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSize, QUrl, Qt
from PySide6.QtGui import QColor, QDesktopServices, QImageReader, QPainter, QPixmap
from PySide6.QtWidgets import (QDialog, QDialogButtonBox, QGridLayout, QHBoxLayout, QLabel,
                               QMessageBox, QPushButton, QTableWidget, QVBoxLayout)

from ..core.models import Team
from ..core.replace import (PngInfo, ReplaceError, compare, history_dir, png_info,
                            restore_version)
from .common import AMBER, RED, item, setup_table, thumbnail
from .state import AppState

PREVIEW = 200


def checker_preview(path: Path, size: int = PREVIEW) -> tuple[QPixmap, str | None]:
    """The image over a checkerboard (shows transparency), or an error if Qt can't read it."""
    reader = QImageReader(str(path))
    image = reader.read()
    canvas = QPixmap(size, size)
    canvas.fill(QColor("#ffffff"))
    p = QPainter(canvas)
    tile = 12
    for y in range(0, size, tile):
        for x in range(0, size, tile):
            if (x // tile + y // tile) % 2:
                p.fillRect(x, y, tile, tile, QColor("#d9d9d9"))
    if not image.isNull():
        scaled = image.scaled(size, size, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        p.drawImage((size - scaled.width()) // 2, (size - scaled.height()) // 2, scaled)
    p.end()
    return canvas, (None if not image.isNull() else reader.errorString() or "unreadable image")


def _caption(title: str, name: str, info: PngInfo | None) -> QLabel:
    text = f"<b>{title}</b><br>{name}"
    if info:
        text += f"<br><span style='color: palette(mid)'>{info.describe()}</span>"
    label = QLabel(text)
    label.setAlignment(Qt.AlignCenter)
    label.setWordWrap(True)
    return label


class ReplaceDialog(QDialog):
    """Old and new side by side, checks, then confirm."""

    def __init__(self, parent, team: Team, current: Path, source: Path):
        super().__init__(parent)
        self.setWindowTitle(f"Replace logo: {team.name}")
        grid = QGridLayout()
        problems: list[str] = []
        warnings: list[str] = []

        old_info = new_info = None
        try:
            old_info = png_info(current)
        except ReplaceError:
            pass
        try:
            new_info = png_info(source)
        except ReplaceError as exc:
            problems.append(str(exc))
        for col, (title, path, info) in enumerate([("Now", current, old_info),
                                                   ("New", source, new_info)]):
            pix, error = checker_preview(path)
            if error and title == "New" and not problems:
                problems.append(f"{path.name} can't be read as an image: {error}")
            preview = QLabel()
            preview.setPixmap(pix)
            preview.setAlignment(Qt.AlignCenter)
            preview.setStyleSheet("border: 1px solid palette(mid)")
            grid.addWidget(preview, 0, col * 2)
            grid.addWidget(_caption(title, path.name, info), 1, col * 2)
        arrow = QLabel("→")
        arrow.setStyleSheet("font-size: 28px")
        grid.addWidget(arrow, 0, 1, Qt.AlignCenter)
        if old_info and new_info:
            warnings = compare(old_info, new_info)

        explain = QLabel(
            f"The file keeps its name, <b>{current.name}</b>, and {team.name} keeps team ID "
            f"#{team.id}, so syncs work as before. The image is copied exactly as it is "
            "(never resized or re-saved). The current logo is saved in <b>Logo history</b> "
            "and can be restored.")
        explain.setWordWrap(True)

        layout = QVBoxLayout(self)
        layout.addLayout(grid)
        layout.addWidget(explain)
        for text, color in [*((p, RED) for p in problems), *((w, AMBER) for w in warnings)]:
            note = QLabel(("✖ " if color is RED else "⚠ ") + text)
            note.setWordWrap(True)
            note.setStyleSheet(f"color: {color.name()}; font-weight: bold")
            layout.addWidget(note)

        buttons = QDialogButtonBox(QDialogButtonBox.Cancel)
        self.replace_btn = buttons.addButton("Replace logo", QDialogButtonBox.AcceptRole)
        self.replace_btn.setEnabled(not problems)
        self.replace_btn.setDefault(True)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.setMinimumWidth(520)


class HistoryDialog(QDialog):
    """Earlier logos of one team, newest first, with Restore."""

    def __init__(self, parent, state: AppState, team: Team):
        super().__init__(parent)
        self.state = state
        self.team = team
        self.changed = False
        self.setWindowTitle(f"Logo history: {team.name}")
        self.resize(720, 460)

        self.current_pix = QLabel()
        self.current = QLabel()
        head = QHBoxLayout()
        head.addWidget(self.current_pix)
        head.addWidget(self.current, 1)
        self.table = QTableWidget()
        setup_table(self.table, ["Logo", "Replaced on", "Size", "File name then", "Note"], stretch=4)
        self.table.setIconSize(QSize(40, 40))
        self.table.doubleClicked.connect(self.restore)
        self.empty = QLabel("No earlier logos yet. Each time this logo is replaced, the old "
                            "image is kept here.")
        self.empty.setWordWrap(True)

        restore = QPushButton("Restore selected version")
        restore.clicked.connect(self.restore)
        folder_btn = QPushButton("Open history folder")
        folder_btn.clicked.connect(self.open_folder)
        close = QPushButton("Close")
        close.clicked.connect(self.accept)
        bar = QHBoxLayout()
        bar.addWidget(restore)
        bar.addWidget(folder_btn)
        bar.addStretch()
        bar.addWidget(close)
        self.restore_btn = restore

        layout = QVBoxLayout(self)
        layout.addLayout(head)
        layout.addWidget(self.empty)
        layout.addWidget(self.table, 1)
        layout.addLayout(bar)
        self.refresh()

    def _dir(self) -> Path:
        return history_dir(self.state.data_dir, self.state.folder_id, self.team.id)

    def refresh(self) -> None:
        self.team = self.state.store.team(self.team.id)
        path = self.state.folder / self.team.filename
        pix = thumbnail(path, 64)
        info = ""
        try:
            info = png_info(path).describe()
        except ReplaceError:
            pass
        self.current_pix.setPixmap(pix if pix is not None else QPixmap())
        self.current.setText(f"<b>Current logo</b>: {self.team.filename}<br>{info}")
        self.versions = self.state.store.logo_versions(self.team.id)
        self.table.setRowCount(len(self.versions))
        for r, v in enumerate(self.versions):
            exists = Path(v.backup_path).is_file()
            thumb = item()
            vpix = thumbnail(Path(v.backup_path)) if exists else None
            if vpix is not None:
                thumb.setData(Qt.DecorationRole, vpix)
            self.table.setItem(r, 0, thumb)
            self.table.setItem(r, 1, item(v.replaced_at.replace("T", "  ")))
            self.table.setItem(r, 2, item(f"{v.width} × {v.height}"))
            self.table.setItem(r, 3, item(v.filename))
            self.table.setItem(r, 4, item(v.note if exists else "backup file missing",
                                          color=None if exists else RED))
        self.table.setVisible(bool(self.versions))
        self.empty.setVisible(not self.versions)
        self.restore_btn.setEnabled(bool(self.versions))
        if self.versions:
            self.table.selectRow(0)

    def restore(self) -> None:
        rows = self.table.selectionModel().selectedRows()
        if not rows:
            return
        version = self.versions[rows[0].row()]
        dlg = ReplaceDialog(self, self.team, self.state.folder / self.team.filename,
                            Path(version.backup_path))
        dlg.setWindowTitle(f"Restore logo: {self.team.name}")
        dlg.replace_btn.setText("Restore this version")
        if dlg.exec() != QDialog.Accepted:
            return
        self.state.busy = True
        try:
            restore_version(self.state.store, self.team, self.state.folder, version, self._dir())
        except (ReplaceError, OSError) as exc:
            QMessageBox.warning(self, "LogoSync", str(exc))
        else:
            self.changed = True
        finally:
            self.state.busy = False
        self.refresh()

    def open_folder(self) -> None:
        self._dir().mkdir(parents=True, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(self._dir())))
