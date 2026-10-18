"""Small shared GUI helpers: colors, thumbnails, table items."""
from __future__ import annotations

import html
import sys
import time
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QIcon, QPixmap
from PySide6.QtWidgets import QHeaderView, QTableWidget, QTableWidgetItem

GREEN = QColor("#2e9d55")
AMBER = QColor("#c27c0e")
RED = QColor("#d64545")
BLUE = QColor("#3b82c4")
THUMB = 40

_thumbs: dict[tuple, QPixmap] = {}


def asset(name: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[2]))
    return base / "assets" / name


def app_icon() -> QIcon:
    return QIcon(str(asset("icon.png")))


def thumbnail(path: Path, size: int = THUMB) -> QPixmap | None:
    """A scaled preview. QPixmap only reads the file; it's never written back."""
    try:
        key = (str(path), path.stat().st_mtime_ns, size)
    except OSError:
        return None
    pix = _thumbs.get(key)
    if pix is None:
        full = QPixmap(str(path))
        if full.isNull():
            return None
        pix = full.scaled(size, size, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        _thumbs[key] = pix
    return pix


def image_tooltip(path: Path, caption: str = "") -> str:
    url = path.resolve().as_uri()
    text = f"<br>{html.escape(caption)}" if caption else ""
    return f'<img src="{url}" width="160">{text}'


def item(text: str = "", color: QColor | None = None, tooltip: str = "",
         align_center: bool = False, bold: bool = False) -> QTableWidgetItem:
    it = QTableWidgetItem(text)
    it.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable)
    if color is not None:
        it.setForeground(color)
    if tooltip:
        it.setToolTip(tooltip)
    if align_center:
        it.setTextAlignment(Qt.AlignCenter)
    if bold:
        f = it.font()
        f.setBold(True)
        it.setFont(f)
    return it


def thumb_item(path: Path | None, caption: str = "") -> QTableWidgetItem:
    it = item()
    if path is not None:
        pix = thumbnail(path)
        if pix is not None:
            it.setData(Qt.DecorationRole, pix)
            it.setToolTip(image_tooltip(path, caption))
    return it


def setup_table(table: QTableWidget, headers: list[str], stretch: int) -> None:
    table.setColumnCount(len(headers))
    table.setHorizontalHeaderLabels(headers)
    table.verticalHeader().setVisible(False)
    table.setSelectionBehavior(QTableWidget.SelectRows)
    table.setEditTriggers(QTableWidget.NoEditTriggers)
    table.setAlternatingRowColors(True)
    header = table.horizontalHeader()
    header.setSectionResizeMode(QHeaderView.ResizeToContents)
    header.setSectionResizeMode(stretch, QHeaderView.Stretch)
    table.verticalHeader().setDefaultSectionSize(THUMB + 8)


def ago(when: float | None) -> str:
    if when is None:
        return "never"
    s = max(0.0, time.time() - when)
    if s < 10:
        return f"{s:.1f} s ago"
    if s < 90:
        return f"{s:.0f} s ago"
    if s < 5400:
        return f"{s / 60:.0f} min ago"
    return time.strftime("%H:%M:%S", time.localtime(when))
