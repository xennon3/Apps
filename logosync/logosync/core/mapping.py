"""Saving, repairing, exporting and importing the logo-to-team mapping."""
from __future__ import annotations

import json
from collections import Counter
from datetime import datetime
from pathlib import Path

from .library import file_digest, list_logos
from .names import normalize_name
from .store import Store


class MappingError(Exception):
    pass


def save_mapping(store: Store, folder_id: int, folder: str | Path,
                 assignments: dict[str, str]) -> None:
    """Apply the mapping tab: {filename: team name, or "" for no team}.

    Existing teams keep their permanent ID when renamed or relinked. Teams whose
    file is missing (and so aren't in `assignments`) are left alone.
    """
    folder = Path(folder)
    teams = store.teams(folder_id)
    by_file = {t.filename.casefold(): t for t in teams}

    wanted = {f: " ".join(n.split()) for f, n in assignments.items()}
    # A name may repeat a team whose file is missing: that team gets relinked.
    final_names = [normalize_name(n) for n in wanted.values() if n]
    dupes = sorted(k for k, c in Counter(final_names).items() if c > 1)
    if dupes:
        raise MappingError("Each team can have only one logo. Used more than once: " + ", ".join(dupes))

    # Unassign first, so names freed here can be reused below.
    for filename, name in wanted.items():
        team = by_file.get(filename.casefold())
        if team and not name:
            store.delete_team(team.id)

    orphans = {t.norm_name: t for t in teams if t.filename.casefold() not in
               {f.casefold() for f in wanted}}
    for filename, name in wanted.items():
        if not name:
            continue
        sha, size = file_digest(folder / filename)
        team = by_file.get(filename.casefold())
        if team:
            store.update_team(team.id, name=name, filename=filename, sha256=sha, size=size)
        elif normalize_name(name) in orphans:
            # A team whose old file vanished: point it at this file, keep its ID.
            store.update_team(orphans.pop(normalize_name(name)).id, name=name,
                              filename=filename, sha256=sha, size=size)
        else:
            store.add_team(folder_id, name, filename, sha, size)


def reconcile(store: Store, folder_id: int, folder: str | Path) -> list[str]:
    """Refresh fingerprints, and relink teams whose file was renamed outside LogoSync."""
    folder = Path(folder)
    files = list_logos(folder)
    on_disk = {f.casefold(): f for f in files}
    teams = store.teams(folder_id)
    mapped = {t.filename.casefold() for t in teams}
    unmapped_by_hash: dict[str, str] = {}
    for f in files:
        if f.casefold() not in mapped:
            try:
                unmapped_by_hash.setdefault(file_digest(folder / f)[0], f)
            except OSError:
                pass

    messages = []
    for t in teams:
        actual = on_disk.get(t.filename.casefold())
        if actual:
            try:
                sha, size = file_digest(folder / actual)
            except OSError:
                continue
            if (sha, size, actual) != (t.sha256, t.size, t.filename):
                store.update_team(t.id, filename=actual, sha256=sha, size=size)
        elif t.sha256 and t.sha256 in unmapped_by_hash:
            found = unmapped_by_hash.pop(t.sha256)
            store.update_team(t.id, filename=found)
            messages.append(f"{t.name}: {t.filename} was renamed to {found} outside LogoSync; "
                            f"the mapping now follows it.")
    return messages


def export_mapping(store: Store, folder_id: int, path: str | Path) -> int:
    teams = store.teams(folder_id)
    data = {
        "app": "LogoSync",
        "format": 1,
        "exported_at": datetime.now().isoformat(timespec="seconds"),
        "teams": [{"id": t.id, "name": t.name, "filename": t.filename, "sha256": t.sha256}
                  for t in teams],
    }
    Path(path).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    return len(teams)


def import_mapping(store: Store, folder_id: int, folder: str | Path,
                   path: str | Path) -> dict[str, str]:
    """Read an exported mapping and match its teams to files in this folder.

    Files are matched by image hash first (survives renames), then by file name.
    Returns proposed {filename: team name} for the mapping tab to show unsaved.
    """
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        entries = data["teams"]
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise MappingError(f"Not a LogoSync mapping file: {exc}") from exc
    folder = Path(folder)
    files = list_logos(folder)
    by_hash = {}
    for f in files:
        try:
            by_hash.setdefault(file_digest(folder / f)[0], f)
        except OSError:
            pass
    by_name = {f.casefold(): f for f in files}
    proposed: dict[str, str] = {}
    for e in entries:
        name = str(e.get("name", "")).strip()
        if not name:
            continue
        f = by_hash.get(e.get("sha256", "")) or by_name.get(str(e.get("filename", "")).casefold())
        if f and f not in proposed:
            proposed[f] = name
    return proposed
