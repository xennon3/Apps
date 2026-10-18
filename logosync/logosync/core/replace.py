"""Replacing a team's logo image, with a backup of every earlier version.

This is the only place LogoSync changes image content, and only when the user
asks. The team keeps its ID and its current (abbreviation) file name; only the
bytes behind that name change. The new image is copied byte for byte (never
decoded, resized or re-saved), written to a temporary file first and swapped
in with one atomic os.replace, so a failure leaves the old logo intact.
"""
from __future__ import annotations

import os
import shutil
import struct
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from .library import TMP_PREFIX, file_digest, find_actual
from .models import LogoVersion, Team
from .store import Store

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
REPLACE_PREFIX = f"{TMP_PREFIX}replace-"


class ReplaceError(Exception):
    pass


@dataclass
class PngInfo:
    width: int
    height: int
    has_alpha: bool
    size: int

    def describe(self) -> str:
        alpha = "transparent background" if self.has_alpha else "no transparency"
        kb = self.size / 1024
        return f"{self.width} × {self.height} px, {alpha}, {kb:,.0f} KB"


def png_info(path: str | Path) -> PngInfo:
    """Read a PNG's header (no decoding). Raises ReplaceError if it isn't a PNG."""
    path = Path(path)
    try:
        size = path.stat().st_size
        with open(path, "rb") as f:
            if f.read(8) != PNG_SIGNATURE:
                raise ReplaceError(f"{path.name} isn't a PNG image.")
            length, kind = struct.unpack(">I4s", f.read(8))
            if kind != b"IHDR" or length < 13:
                raise ReplaceError(f"{path.name} is a damaged PNG (no IHDR header).")
            width, height, _depth, color_type = struct.unpack(">IIBB", f.read(10))
            f.seek(length - 10 + 4, os.SEEK_CUR)  # rest of IHDR + CRC
            has_alpha = color_type in (4, 6)
            # A tRNS chunk before the image data also means transparency.
            while not has_alpha:
                header = f.read(8)
                if len(header) < 8:
                    break
                length, kind = struct.unpack(">I4s", header)
                if kind in (b"IDAT", b"IEND"):
                    break
                has_alpha = kind == b"tRNS"
                f.seek(length + 4, os.SEEK_CUR)
    except (OSError, struct.error) as exc:
        raise ReplaceError(f"Can't read {path.name}: {exc}") from exc
    if not width or not height:
        raise ReplaceError(f"{path.name} has no pixels.")
    return PngInfo(width, height, has_alpha, size)


def compare(old: PngInfo, new: PngInfo) -> list[str]:
    """Warnings worth showing before a replace. They never block it."""
    warnings = []
    if (old.width, old.height) != (new.width, new.height):
        warnings.append(f"Different size: {old.width} × {old.height} now, "
                        f"{new.width} × {new.height} new. It won't be resized.")
    if old.has_alpha and not new.has_alpha:
        warnings.append("The new image has no transparency; the old one did.")
    return warnings


def history_dir(data_dir: Path, folder_id: int, team_id: int) -> Path:
    return Path(data_dir) / "logo-history" / str(folder_id) / str(team_id)


def _backup(store: Store, team: Team, current: Path, backup_dir: Path, note: str) -> LogoVersion:
    sha, size = file_digest(current)
    info = png_info(current)
    backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    dest = backup_dir / f"{stamp}-{sha[:8]}.png"
    shutil.copyfile(current, dest)
    if file_digest(dest) != (sha, size):
        dest.unlink(missing_ok=True)
        raise ReplaceError("The backup copy of the current logo didn't match it; nothing was changed.")
    return store.add_logo_version(team.id, str(dest), current.name, sha, size,
                                  info.width, info.height, note)


def replace_logo(store: Store, team: Team, folder: str | Path, source: str | Path,
                 backup_dir: Path, note: str = "replaced") -> LogoVersion:
    """Put `source`'s bytes behind the team's current file. Returns the backup of the old logo."""
    folder, source = Path(folder), Path(source)
    actual = find_actual(folder, team.filename)
    if actual is None:
        raise ReplaceError(f"{team.filename} isn't in the folder, so there's nothing to replace.")
    target = folder / actual
    try:
        if source.resolve() == target.resolve():
            raise ReplaceError("That file is already this team's logo.")
    except OSError as exc:
        raise ReplaceError(f"Can't read {source.name}: {exc}") from exc
    png_info(source)
    new_sha, new_size = file_digest(source)
    if new_sha == file_digest(target)[0]:
        raise ReplaceError("The new image is identical to the current logo.")

    backup = _backup(store, team, target, backup_dir, note)
    tmp = folder / f"{REPLACE_PREFIX}{team.id}.tmp"
    try:
        shutil.copyfile(source, tmp)
        if file_digest(tmp) != (new_sha, new_size):
            raise ReplaceError("The copied image didn't match the original; nothing was changed.")
        os.replace(tmp, target)  # atomic: readers see the old logo or the new one, never half
    except Exception as exc:
        tmp.unlink(missing_ok=True)
        store.delete_logo_version(backup.id)
        Path(backup.backup_path).unlink(missing_ok=True)
        if isinstance(exc, ReplaceError):
            raise
        raise ReplaceError(f"Couldn't replace {actual}: {exc}. The old logo is unchanged.") from exc
    store.update_team(team.id, filename=actual, sha256=new_sha, size=new_size)
    return backup


def restore_version(store: Store, team: Team, folder: str | Path, version: LogoVersion,
                    backup_dir: Path) -> LogoVersion:
    """Bring back an earlier logo. The current one is backed up first, so this can be reversed too."""
    if not Path(version.backup_path).is_file():
        raise ReplaceError("That saved version's file is missing from the data folder.")
    if file_digest(version.backup_path)[0] != version.sha256:
        raise ReplaceError("That saved version's file has been changed; it won't be restored.")
    when = version.replaced_at.replace("T", " ")
    return replace_logo(store, team, folder, version.backup_path, backup_dir,
                        note=f"restored the version from {when}")


def cleanup_leftovers(folder: str | Path) -> int:
    """Remove temp files from a replace that was interrupted (the old logo is still in place)."""
    removed = 0
    for name in os.listdir(folder):
        if name.startswith(REPLACE_PREFIX) and name.endswith(".tmp"):
            try:
                os.remove(Path(folder) / name)
                removed += 1
            except OSError:
                pass
    return removed
