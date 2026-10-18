"""A demo folder of made-up logos, named with last season's abbreviations."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QFont, QImage, QPainter, QPen

from ..core.library import list_logos
from ..core.store import Store

# Old file name stem, background color, text color. The demo feed (core/sender.py)
# sends this season's names and abbreviations for the same teams.
DEMO_LOGOS = [
    ("MIA", "#005030", "#f47321"), ("FSU", "#782f40", "#ceb888"), ("CLEM", "#f56600", "#522d80"),
    ("UNC", "#7bafd4", "#13294b"), ("DUKE", "#003087", "#ffffff"), ("UVA", "#232d4b", "#f84c1e"),
    ("VT", "#630031", "#cf4420"), ("BC", "#8a100b", "#bc9b6a"), ("LOU", "#ad0000", "#000000"),
    ("PITT", "#003594", "#ffb81c"), ("SYR", "#f76900", "#000e54"), ("WAKE", "#000000", "#9e7e38"),
    ("GT", "#b3a369", "#003057"), ("NCST", "#cc0000", "#ffffff"), ("MOH", "#c3142d", "#ffffff"),
]


def draw_logo(path: Path, text: str, bg: str, fg: str, size: int = 256) -> None:
    img = QImage(size, size, QImage.Format_ARGB32)
    img.fill(Qt.transparent)
    p = QPainter(img)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(QColor(bg))
    p.setPen(QPen(QColor(fg), size * 0.04))
    margin = size * 0.06
    p.drawEllipse(QRectF(margin, margin, size - 2 * margin, size - 2 * margin))
    font = QFont("Arial")
    font.setBold(True)
    font.setPixelSize(int(size * (0.36 if len(text) <= 3 else 0.28)))
    p.setFont(font)
    p.setPen(QColor(fg))
    p.drawText(QRectF(0, 0, size, size), Qt.AlignCenter, text)
    p.end()
    img.save(str(path), "PNG")


def create_demo_folder(folder: Path, store: Store | None = None, reset: bool = False) -> Path:
    """Create (or, with reset, recreate) the demo logos. Returns the folder."""
    folder.mkdir(parents=True, exist_ok=True)
    if reset:
        for name in list_logos(folder):
            (folder / name).unlink()
        if store:
            fid = store.folder_id(folder)
            for t in store.teams(fid):
                store.delete_team(t.id)
    if not list_logos(folder):
        for stem, bg, fg in DEMO_LOGOS:
            draw_logo(folder / f"{stem}.png", stem, bg, fg)
    return folder
