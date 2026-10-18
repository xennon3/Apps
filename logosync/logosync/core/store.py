"""SQLite storage: teams per logo folder, and the sync journal."""
from __future__ import annotations

import os
import sqlite3
from datetime import datetime
from pathlib import Path

from .models import Move, SyncInfo, Team
from .names import normalize_name

SCHEMA = """
CREATE TABLE IF NOT EXISTS folders (
    id   INTEGER PRIMARY KEY,
    path TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS teams (
    id         INTEGER PRIMARY KEY,
    folder_id  INTEGER NOT NULL REFERENCES folders(id),
    name       TEXT NOT NULL,
    norm_name  TEXT NOT NULL,
    filename   TEXT NOT NULL,
    sha256     TEXT NOT NULL DEFAULT '',
    size       INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS syncs (
    id          INTEGER PRIMARY KEY,
    folder_id   INTEGER NOT NULL REFERENCES folders(id),
    kind        TEXT NOT NULL,
    started_at  TEXT NOT NULL,
    finished_at TEXT NOT NULL DEFAULT '',
    status      TEXT NOT NULL,
    message     TEXT NOT NULL DEFAULT '',
    undone_by   INTEGER
);
CREATE TABLE IF NOT EXISTS moves (
    sync_id   INTEGER NOT NULL REFERENCES syncs(id),
    seq       INTEGER NOT NULL,
    team_id   INTEGER NOT NULL,
    old_name  TEXT NOT NULL,
    tmp_name  TEXT NOT NULL,
    new_name  TEXT NOT NULL,
    feed_name TEXT NOT NULL DEFAULT '',
    abbr      TEXT NOT NULL DEFAULT '',
    sha256    TEXT NOT NULL,
    size      INTEGER NOT NULL,
    state     TEXT NOT NULL,
    PRIMARY KEY (sync_id, seq)
);
"""


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


def folder_key(path: str | Path) -> str:
    return os.path.normcase(os.path.abspath(str(path)))


class Store:
    def __init__(self, path: str | Path):
        self.db = sqlite3.connect(str(path))
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript(SCHEMA)
        self.db.commit()

    def close(self) -> None:
        self.db.close()

    # Folders and teams

    def folder_id(self, path: str | Path) -> int:
        key = folder_key(path)
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO folders(path) VALUES (?)", (key,))
        return self.db.execute("SELECT id FROM folders WHERE path = ?", (key,)).fetchone()[0]

    def teams(self, folder_id: int) -> list[Team]:
        rows = self.db.execute(
            "SELECT id, name, norm_name, filename, sha256, size FROM teams"
            " WHERE folder_id = ? ORDER BY norm_name", (folder_id,))
        return [Team(**dict(r)) for r in rows]

    def team(self, team_id: int) -> Team | None:
        r = self.db.execute("SELECT id, name, norm_name, filename, sha256, size FROM teams"
                            " WHERE id = ?", (team_id,)).fetchone()
        return Team(**dict(r)) if r else None

    def add_team(self, folder_id: int, name: str, filename: str,
                 sha256: str = "", size: int = 0) -> int:
        with self.db:
            cur = self.db.execute(
                "INSERT INTO teams(folder_id, name, norm_name, filename, sha256, size,"
                " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (folder_id, name, normalize_name(name), filename, sha256, size, _now(), _now()))
        return cur.lastrowid

    def update_team(self, team_id: int, *, name: str | None = None, filename: str | None = None,
                    sha256: str | None = None, size: int | None = None) -> None:
        fields: dict[str, object] = {}
        if name is not None:
            fields.update(name=name, norm_name=normalize_name(name))
        if filename is not None:
            fields["filename"] = filename
        if sha256 is not None:
            fields["sha256"] = sha256
        if size is not None:
            fields["size"] = size
        if not fields:
            return
        fields["updated_at"] = _now()
        sets = ", ".join(f"{k} = ?" for k in fields)
        with self.db:
            self.db.execute(f"UPDATE teams SET {sets} WHERE id = ?", (*fields.values(), team_id))

    def delete_team(self, team_id: int) -> None:
        with self.db:
            self.db.execute("DELETE FROM teams WHERE id = ?", (team_id,))

    # Sync journal

    def create_sync(self, folder_id: int, kind: str) -> int:
        with self.db:
            cur = self.db.execute(
                "INSERT INTO syncs(folder_id, kind, started_at, status) VALUES (?, ?, ?, 'running')",
                (folder_id, kind, _now()))
        return cur.lastrowid

    def add_moves(self, sync_id: int, moves: list[Move]) -> None:
        with self.db:
            self.db.executemany(
                "INSERT INTO moves(sync_id, seq, team_id, old_name, tmp_name, new_name,"
                " feed_name, abbr, sha256, size, state) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                [(sync_id, m.seq, m.team_id, m.old_name, m.tmp_name, m.new_name,
                  m.feed_name, m.abbr, m.sha256, m.size, m.state) for m in moves])

    def set_move_state(self, sync_id: int, seq: int, state: str) -> None:
        with self.db:
            self.db.execute("UPDATE moves SET state = ? WHERE sync_id = ? AND seq = ?",
                            (state, sync_id, seq))

    def moves(self, sync_id: int) -> list[Move]:
        rows = self.db.execute(
            "SELECT team_id, old_name, new_name, feed_name, abbr, seq, tmp_name, sha256, size,"
            " state FROM moves WHERE sync_id = ? ORDER BY seq", (sync_id,))
        return [Move(**dict(r)) for r in rows]

    def finish_sync(self, sync_id: int, status: str, message: str = "") -> None:
        with self.db:
            self.db.execute("UPDATE syncs SET status = ?, message = ?, finished_at = ? WHERE id = ?",
                            (status, message, _now(), sync_id))

    def complete_sync(self, sync_id: int, message: str = "") -> None:
        """Mark a sync done and point each team at its new file, in one transaction."""
        with self.db:
            for m in self.moves(sync_id):
                self.db.execute(
                    "UPDATE teams SET filename = ?, sha256 = ?, size = ?, updated_at = ? WHERE id = ?",
                    (m.new_name, m.sha256, m.size, _now(), m.team_id))
            self.db.execute("UPDATE syncs SET status = 'done', message = ?, finished_at = ?"
                            " WHERE id = ?", (message, _now(), sync_id))

    def mark_undone(self, sync_id: int, undone_by: int) -> None:
        with self.db:
            self.db.execute("UPDATE syncs SET undone_by = ? WHERE id = ?", (undone_by, sync_id))

    def syncs(self, folder_id: int, status: str | None = None) -> list[SyncInfo]:
        sql = ("SELECT s.id, s.kind, s.started_at, s.finished_at, s.status, s.message, s.undone_by,"
               " (SELECT COUNT(*) FROM moves m WHERE m.sync_id = s.id) AS moves"
               " FROM syncs s WHERE s.folder_id = ?")
        args: list[object] = [folder_id]
        if status:
            sql += " AND s.status = ?"
            args.append(status)
        return [SyncInfo(**dict(r)) for r in self.db.execute(sql + " ORDER BY s.id DESC", args)]

    def last_completed_sync(self, folder_id: int) -> SyncInfo | None:
        done = self.syncs(folder_id, "done")
        return done[0] if done else None
