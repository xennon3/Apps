"""Team-name normalization and abbreviation (file name) validation."""
from __future__ import annotations

import re
import unicodedata

MAX_ABBR_LENGTH = 20

# Windows refuses these as file names, with or without an extension ("CON.png").
WINDOWS_RESERVED = frozenset(
    ["CON", "PRN", "AUX", "NUL"]
    + [f"COM{i}" for i in range(1, 10)]
    + [f"LPT{i}" for i in range(1, 10)]
)

_ABBR_OK = re.compile(r"^[A-Za-z0-9&_-]+$")


def fold(text: str) -> str:
    """Casefold and strip accents: 'Montréal' -> 'montreal'."""
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold()


def normalize_name(name: str) -> str:
    """The key used to match feed names to saved teams.

    Case, accents and extra spaces are ignored; punctuation is not, so
    'Miami (OH)' never matches 'Miami'.
    """
    return " ".join(fold(name).split())


def abbreviation_problem(abbr: str) -> str | None:
    """Why `abbr` can't be used as a file name, or None if it's fine."""
    if not abbr:
        return "the abbreviation is empty"
    if len(abbr) > MAX_ABBR_LENGTH:
        return f"longer than {MAX_ABBR_LENGTH} characters"
    if not _ABBR_OK.match(abbr):
        bad = sorted({c for c in abbr if not re.match(r"[A-Za-z0-9&_-]", c)})
        shown = " ".join("space" if c == " " else c for c in bad)
        return f"contains characters not allowed in a file name: {shown}"
    if abbr.upper() in WINDOWS_RESERVED:
        return f"{abbr.upper()} is a reserved name on Windows"
    return None
