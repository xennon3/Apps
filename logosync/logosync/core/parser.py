"""Turn UDP datagrams into (team name, abbreviation) records.

The documented format is ``( Miami, MIAM )``. Because the exact packet layout
isn't known yet, the parser also accepts several records per datagram:

    (Miami, MIAM)(Clemson, CLEM)        any number of parenthesized records
    Miami, MIAM <newline> Clemson, CLEM  one record per line (or ; or |)
    {"name": "Miami", "abbr": "MIAM"}    JSON objects, or a list of them

Each record is split on its *last* comma, so names like "Washington, D.C."
keep their comma. Only structurally broken records are rejected here; whether
an abbreviation is usable as a file name is decided later, so the preview can
show the reason next to the team.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field

from .names import abbreviation_problem


@dataclass(frozen=True)
class Record:
    name: str
    abbr: str


@dataclass
class ParseResult:
    records: list[Record] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


_LINES = re.compile(r"[\r\n;|]+")
_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")

NAME_KEYS = ("name", "team", "team_name", "teamname", "school", "full_name")
ABBR_KEYS = ("abbr", "abbreviation", "abbrev", "short", "short_name", "code", "tricode")


def decode(data: bytes) -> str:
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("cp1252", errors="replace")


def parse_datagram(data: bytes) -> ParseResult:
    return parse_text(decode(data))


def parse_text(text: str) -> ParseResult:
    text = _CONTROL.sub(" ", text).strip()
    result = ParseResult()
    if not text:
        return result
    if text[0] in "{[":
        try:
            obj = json.loads(text)
        except ValueError:
            pass
        else:
            _parse_json(obj, result)
            return result
    chunks = _top_level_groups(text) if text[0] == "(" else _LINES.split(text)
    for chunk in chunks:
        record, error = parse_record(chunk)
        if record:
            result.records.append(record)
        elif error:
            result.errors.append(error)
    return result


def _top_level_groups(text: str) -> list[str]:
    """Contents of each outermost (...), so '( Miami (OH), M-OH )' stays one record."""
    groups, depth, start = [], 0, 0
    for i, c in enumerate(text):
        if c == "(":
            if depth == 0:
                start = i + 1
            depth += 1
        elif c == ")" and depth:
            depth -= 1
            if depth == 0:
                groups.append(text[start:i])
    if depth:
        groups.append(text[start:])  # unclosed: still try to read it
    return groups


def parse_record(chunk: str) -> tuple[Record | None, str | None]:
    chunk = chunk.strip()
    if not chunk:
        return None, None
    sep = "," if "," in chunk else "\t" if "\t" in chunk else None
    if sep is None:
        return None, f"no comma between team name and abbreviation: {chunk!r}"
    name, abbr = (_clean(part) for part in chunk.rsplit(sep, 1))
    if _looks_swapped(name, abbr):
        name, abbr = abbr, name
    if not name:
        return None, f"missing team name: {chunk!r}"
    if not abbr:
        return None, f"missing abbreviation: {chunk!r}"
    return Record(" ".join(name.split()), abbr), None


def _clean(part: str) -> str:
    return part.strip().strip("\"'").strip()


def _looks_swapped(name: str, abbr: str) -> bool:
    """'(MIAM, Miami)': an all-caps code first and a mixed-case name second."""
    return (
        abbreviation_problem(name) is None
        and name == name.upper()
        and any(c.isalpha() for c in name)
        and any(c.islower() for c in abbr)
    )


def _parse_json(obj, result: ParseResult) -> None:
    items = obj if isinstance(obj, list) else [obj]
    for item in items:
        if not isinstance(item, dict):
            result.errors.append(f"unexpected JSON value: {item!r}")
            continue
        lowered = {str(k).lower(): v for k, v in item.items()}
        name = next((lowered[k] for k in NAME_KEYS if k in lowered), None)
        abbr = next((lowered[k] for k in ABBR_KEYS if k in lowered), None)
        if not isinstance(name, str) or not isinstance(abbr, str) or not name.strip() or not abbr.strip():
            result.errors.append(f"JSON record without a team name and abbreviation: {item!r}")
            continue
        result.records.append(Record(" ".join(name.split()), abbr.strip()))
