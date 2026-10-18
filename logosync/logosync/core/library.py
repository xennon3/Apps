"""Reading the logo folder. Nothing here writes or decodes images."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path

TMP_PREFIX = ".logosync-"
LOGO_SUFFIX = ".png"


def list_logos(folder: str | Path) -> list[str]:
    """PNG file names in the folder (not subfolders), sorted case-insensitively."""
    names = []
    with os.scandir(folder) as it:
        for entry in it:
            if (entry.name.lower().endswith(LOGO_SUFFIX)
                    and not entry.name.startswith(TMP_PREFIX)
                    and entry.is_file()):
                names.append(entry.name)
    return sorted(names, key=str.casefold)


def file_digest(path: str | Path) -> tuple[str, int]:
    """SHA-256 and size, used to prove a rename left the image bytes untouched."""
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
            size += len(block)
    return h.hexdigest(), size


def find_actual(folder: str | Path, name: str) -> str | None:
    """The on-disk spelling of `name`, matched case-insensitively like Windows does."""
    wanted = name.casefold()
    for actual in os.listdir(folder):
        if actual.casefold() == wanted:
            return actual
    return None


def split_ext(filename: str) -> tuple[str, str]:
    stem, ext = os.path.splitext(filename)
    return stem, ext
