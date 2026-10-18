import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from logosync.core.store import Store  # noqa: E402

# Distinct bytes per logo, so hashes tell files apart. Real PNG decoding is never needed.
PNG_HEADER = b"\x89PNG\r\n\x1a\n"


def make_logo(folder: Path, name: str, content: str | None = None) -> Path:
    path = folder / name
    path.write_bytes(PNG_HEADER + (content or name).encode())
    return path


@pytest.fixture
def folder(tmp_path):
    d = tmp_path / "logos"
    d.mkdir()
    return d


@pytest.fixture
def store(tmp_path):
    s = Store(tmp_path / "logosync.db")
    yield s
    s.close()
