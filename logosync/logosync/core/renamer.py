"""Executing a sync: journaled, two-phase renames with verification and rollback.

Phase 1 moves every source file to a unique temporary name; phase 2 moves each
temporary file to its final name. That makes swaps (A->B, B->A), longer
cycles and case-only renames (mia.png -> MIA.png) all the same simple case.
Each step is written to the journal first, so an interrupted sync (crash,
power cut) is rolled back the next time the folder is opened.

Only os.rename is used: image bytes are never read into an image library or
rewritten, and the SHA-256 check after each move proves it.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

from .library import TMP_PREFIX, file_digest, find_actual
from .models import Move
from .store import Store

log = logging.getLogger(__name__)


class SyncError(Exception):
    def __init__(self, message: str, sync_id: int | None = None, stranded: list[str] | None = None):
        super().__init__(message)
        self.sync_id = sync_id
        self.stranded = stranded or []


def _rename(folder: Path, src: str, dst: str) -> None:
    # Windows' os.rename refuses to replace an existing file. POSIX would
    # silently replace it, so check there too (a case-only rename is fine).
    if src.casefold() != dst.casefold() and (folder / dst).exists():
        raise FileExistsError(f"{dst} already exists")
    os.rename(folder / src, folder / dst)


def _matches(path: Path, move: Move) -> bool:
    try:
        if path.stat().st_size != move.size:
            return False
        return file_digest(path)[0] == move.sha256
    except OSError:
        return False


def apply_moves(store: Store, folder_id: int, folder: str | Path, moves: list[Move],
                kind: str = "sync") -> int:
    """Rename files as planned. Returns the sync id, or raises SyncError after rolling back."""
    folder = Path(folder)
    if not moves:
        raise SyncError("Nothing to rename.")

    # Pre-flight: everything is checked before the first file is touched.
    listing = {n.casefold(): n for n in os.listdir(folder)}
    sources = {m.old_name.casefold() for m in moves}
    targets = [m.new_name.casefold() for m in moves]
    if len(set(targets)) != len(targets):
        raise SyncError("Two files would get the same name.")
    for m in moves:
        actual = listing.get(m.old_name.casefold())
        if actual is None:
            raise SyncError(f"{m.old_name} is missing from the folder.")
        m.old_name = actual
        if m.new_name.casefold() in listing and m.new_name.casefold() not in sources:
            raise SyncError(f"{m.new_name} already exists and would be overwritten.")
        try:
            m.sha256, m.size = file_digest(folder / actual)
        except OSError as exc:
            raise SyncError(f"Can't read {actual}: {exc}") from exc

    sync_id = store.create_sync(folder_id, kind)
    for seq, m in enumerate(moves):
        m.seq = seq
        m.tmp_name = f"{TMP_PREFIX}{sync_id}-{seq}.tmp"
        m.state = "planned"
    store.add_moves(sync_id, moves)

    try:
        for m in moves:
            _rename(folder, m.old_name, m.tmp_name)
            store.set_move_state(sync_id, m.seq, "tmp")
        for m in moves:
            _rename(folder, m.tmp_name, m.new_name)
            if not _matches(folder / m.new_name, m):
                raise SyncError(f"{m.new_name} doesn't match the original file after renaming.")
            store.set_move_state(sync_id, m.seq, "done")
    except Exception as exc:
        log.exception("Sync %s failed; rolling back", sync_id)
        stranded = rollback(store, sync_id, folder)
        reason = str(exc) or exc.__class__.__name__
        if stranded:
            message = (f"Failed ({reason}). Could not restore: {', '.join(stranded)}. "
                       f"Look for {TMP_PREFIX}*.tmp files in the folder.")
            store.finish_sync(sync_id, "failed", message)
        else:
            message = f"Failed ({reason}). All files were put back."
            store.finish_sync(sync_id, "rolled_back", message)
        raise SyncError(message, sync_id, stranded) from exc

    verb = "Undid" if kind == "undo" else "Renamed"
    store.complete_sync(sync_id, f"{verb} {len(moves)} file(s); image bytes verified unchanged.")
    log.info("Sync %s: %s %d file(s)", sync_id, verb.lower(), len(moves))
    return sync_id


def _locate(folder: Path, m: Move) -> str | None:
    """Where this move's file is now: its temp, new or old name (checked by hash)."""
    for name in (m.tmp_name, m.new_name, m.old_name):
        actual = find_actual(folder, name)
        if actual and _matches(folder / actual, m):
            return actual
    return None


def rollback(store: Store, sync_id: int, folder: str | Path) -> list[str]:
    """Put every file of a sync back under its old name. Returns files it couldn't restore."""
    folder = Path(folder)
    stranded: list[str] = []
    staged: list[Move] = []
    for m in store.moves(sync_id):
        where = _locate(folder, m)
        if where is None:
            stranded.append(m.old_name)
            continue
        if where == m.old_name:
            store.set_move_state(sync_id, m.seq, "reverted")
            continue
        try:
            if where != m.tmp_name:
                _rename(folder, where, m.tmp_name)
            staged.append(m)
        except OSError:
            log.exception("Rollback: couldn't move %s aside", where)
            stranded.append(m.old_name)
    for m in staged:
        try:
            _rename(folder, m.tmp_name, m.old_name)
            store.set_move_state(sync_id, m.seq, "reverted")
        except OSError:
            log.exception("Rollback: couldn't restore %s", m.old_name)
            stranded.append(f"{m.old_name} (now {m.tmp_name})")
    return stranded


def recover_interrupted(store: Store, folder_id: int, folder: str | Path) -> list[str]:
    """Roll back syncs that never finished (the app or PC stopped mid-sync)."""
    messages = []
    for info in store.syncs(folder_id, "running"):
        stranded = rollback(store, info.id, folder)
        if stranded:
            msg = (f"A sync was interrupted. These files could not be restored: "
                   f"{', '.join(stranded)}. Look for {TMP_PREFIX}*.tmp files in the folder.")
            store.finish_sync(info.id, "failed", msg)
        else:
            msg = "A sync was interrupted; every file was put back under its previous name."
            store.finish_sync(info.id, "rolled_back", msg)
        messages.append(msg)
    return messages


def undo_last(store: Store, folder_id: int, folder: str | Path) -> int:
    """Reverse the most recent completed sync. Returns the new (undo) sync id."""
    last = store.last_completed_sync(folder_id)
    if last is None or last.kind != "sync" or last.undone_by is not None:
        raise SyncError("There's no sync to undo.")
    reverse = []
    for m in store.moves(last.id):
        team = store.team(m.team_id)
        if team is None or team.filename != m.new_name:
            raise SyncError(f"{m.new_name} has changed since that sync, so it can't be undone safely.")
        reverse.append(Move(m.team_id, m.new_name, m.old_name, m.feed_name, m.abbr))
    undo_id = apply_moves(store, folder_id, folder, reverse, kind="undo")
    store.mark_undone(last.id, undo_id)
    return undo_id
