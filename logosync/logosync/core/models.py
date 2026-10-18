from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Team:
    """A team's permanent identity. `id` never changes; `filename` follows the feed."""
    id: int
    name: str
    norm_name: str
    filename: str
    sha256: str = ""
    size: int = 0


@dataclass
class Move:
    """One file rename inside a sync, also its journal row."""
    team_id: int
    old_name: str
    new_name: str
    feed_name: str = ""
    abbr: str = ""
    seq: int = 0
    tmp_name: str = ""
    sha256: str = ""
    size: int = 0
    state: str = "planned"  # planned -> tmp -> done, or reverted


@dataclass
class SyncInfo:
    id: int
    kind: str           # "sync" or "undo"
    started_at: str
    finished_at: str
    status: str         # running, done, rolled_back, failed
    message: str
    undone_by: int | None
    moves: int
