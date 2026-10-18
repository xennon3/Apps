"""Suggest which feed team an existing logo file belongs to.

Suggestions only: the user confirms them on the Team mapping tab, and close
calls (MIA.png -> Miami or Miami (OH)?) are flagged instead of guessed.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .names import fold, normalize_name

STOP_WORDS = {"of", "the", "and", "at", "&"}
MIN_SCORE = 0.5
CLOSE_CALL = 0.05


@dataclass
class Suggestion:
    name: str
    score: float
    alternatives: list[str] = field(default_factory=list)


def _alnum(text: str) -> str:
    return re.sub(r"[^0-9a-z]", "", fold(text))


def _is_subsequence(short: str, long: str) -> bool:
    it = iter(long)
    return all(c in it for c in short)


def score(stem: str, name: str, abbr: str = "") -> float:
    """How well a file stem (e.g. 'FSU') fits a team name, 0..1."""
    s, n = _alnum(stem), _alnum(name)
    if not s or not n:
        return 0.0
    if abbr and s == _alnum(abbr):
        return 1.0                                   # feed still sends this abbreviation
    if s == n:
        return 0.97                                  # DUKE.png -> Duke
    words = re.findall(r"[0-9a-z]+", fold(name))
    initials = "".join(w[0] for w in words if w not in STOP_WORDS)
    all_initials = "".join(w[0] for w in words)
    if len(s) >= 2 and s in {initials, all_initials, initials + "u", all_initials + "u"}:
        return 0.9                                   # FSU -> Florida State (University)
    if len(s) >= 2 and n.startswith(s):
        return 0.8 + 0.05 * len(s) / len(n)          # CLEM -> Clemson
    if s[0] == n[0] and _is_subsequence(s, n):
        return 0.55 + 0.2 * len(s) / len(n)          # NCST -> NC State, MOH -> Miami (OH)
    return 0.0


def suggest(stems: dict[str, str], candidates: list[tuple[str, str]]) -> dict[str, Suggestion]:
    """Best team for each file, each team used at most once.

    stems: filename -> stem. candidates: (team name, current feed abbreviation).
    """
    pairs = []
    per_file: dict[str, list[tuple[float, str]]] = {}
    for filename, stem in stems.items():
        scored = sorted(((score(stem, name, abbr), name) for name, abbr in candidates), reverse=True)
        scored = [(sc, name) for sc, name in scored if sc >= MIN_SCORE]
        per_file[filename] = scored
        pairs.extend((sc, filename, name) for sc, name in scored)

    result: dict[str, Suggestion] = {}
    used: set[str] = set()
    for sc, filename, name in sorted(pairs, key=lambda p: -p[0]):
        if filename in result or normalize_name(name) in used:
            continue
        used.add(normalize_name(name))
        close = [n for s2, n in per_file[filename] if n != name and s2 >= sc - CLOSE_CALL]
        result[filename] = Suggestion(name, sc, close)
    return result
