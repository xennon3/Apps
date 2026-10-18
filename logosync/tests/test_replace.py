import os
import struct
import zlib

import pytest

from conftest import make_logo
from logosync.core.library import file_digest, list_logos
from logosync.core.models import Move
from logosync.core.renamer import apply_moves, undo_last
from logosync.core.replace import (REPLACE_PREFIX, ReplaceError, cleanup_leftovers, compare,
                                   png_info, replace_logo, restore_version)


def real_png(path, width, height, alpha=True, trns=False, tag=b""):
    """A tiny valid PNG (solid color), built by hand so tests need no image library."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    color_type = 6 if alpha else 2
    px = 4 if alpha else 3
    raw = b"".join(b"\x00" + bytes([200, 30, 30, 255][:px]) * width for _ in range(height))
    body = chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0))
    if trns:
        body += chunk(b"tRNS", b"\x00\x00\x00\x00\x00\x00")
    if tag:
        body += chunk(b"tEXt", b"Comment\x00" + tag)
    body += chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + body)
    return path


@pytest.fixture
def team(store, folder):
    real_png(folder / "MIAM.png", 64, 64, tag=b"old art")
    fid = store.folder_id(folder)
    sha, size = file_digest(folder / "MIAM.png")
    tid = store.add_team(fid, "Miami", "MIAM.png", sha, size)
    return store.team(tid)


def test_png_info(tmp_path):
    assert png_info(real_png(tmp_path / "a.png", 64, 32)).__dict__ | {"size": 0} == \
        {"width": 64, "height": 32, "has_alpha": True, "size": 0}
    assert not png_info(real_png(tmp_path / "b.png", 8, 8, alpha=False)).has_alpha
    assert png_info(real_png(tmp_path / "c.png", 8, 8, alpha=False, trns=True)).has_alpha
    (tmp_path / "x.png").write_bytes(b"GIF89a....")
    with pytest.raises(ReplaceError, match="isn't a PNG"):
        png_info(tmp_path / "x.png")


def test_compare_warnings(tmp_path):
    old = png_info(real_png(tmp_path / "a.png", 64, 64))
    assert compare(old, png_info(real_png(tmp_path / "b.png", 64, 64))) == []
    w = compare(old, png_info(real_png(tmp_path / "c.png", 128, 64, alpha=False)))
    assert len(w) == 2


def test_replace_keeps_name_and_id_and_backs_up(store, folder, team, tmp_path):
    old_bytes = (folder / "MIAM.png").read_bytes()
    new = real_png(tmp_path / "new.png", 64, 64, tag=b"new art")
    backup = replace_logo(store, team, folder, new, tmp_path / "hist")
    assert (folder / "MIAM.png").read_bytes() == new.read_bytes()
    assert open(backup.backup_path, "rb").read() == old_bytes
    t = store.team(team.id)
    assert (t.id, t.name, t.filename) == (team.id, "Miami", "MIAM.png")
    assert t.sha256 == file_digest(new)[0]
    assert [v.id for v in store.logo_versions(team.id)] == [backup.id]
    assert list_logos(folder) == ["MIAM.png"]


def test_restore_previous_and_back(store, folder, team, tmp_path):
    original = (folder / "MIAM.png").read_bytes()
    hist = tmp_path / "hist"
    v1 = replace_logo(store, team, folder, real_png(tmp_path / "n.png", 64, 64, tag=b"2"), hist)
    replaced = (folder / "MIAM.png").read_bytes()
    restore_version(store, store.team(team.id), folder, v1, hist)
    assert (folder / "MIAM.png").read_bytes() == original
    # The restore backed up the replaced image, so it can be brought back too.
    latest = store.logo_versions(team.id)[0]
    assert open(latest.backup_path, "rb").read() == replaced
    assert latest.note.startswith("restored")


def test_new_logo_follows_team_through_sync_and_undo(store, folder, team, tmp_path):
    new = real_png(tmp_path / "new.png", 64, 64, tag=b"new art")
    replace_logo(store, team, folder, new, tmp_path / "hist")
    fid = store.folder_id(folder)
    apply_moves(store, fid, folder, [Move(team.id, "MIAM.png", "MIA.png")])
    assert (folder / "MIA.png").read_bytes() == new.read_bytes()
    undo_last(store, fid, folder)
    assert (folder / "MIAM.png").read_bytes() == new.read_bytes()


def test_refusals_leave_logo_intact(store, folder, team, tmp_path):
    before = (folder / "MIAM.png").read_bytes()
    hist = tmp_path / "hist"
    (tmp_path / "bad.png").write_bytes(b"not an image")
    with pytest.raises(ReplaceError, match="isn't a PNG"):
        replace_logo(store, team, folder, tmp_path / "bad.png", hist)
    with pytest.raises(ReplaceError, match="identical"):
        replace_logo(store, team, folder, real_png(tmp_path / "same.png", 64, 64, tag=b"old art"), hist)
    with pytest.raises(ReplaceError, match="already this team"):
        replace_logo(store, team, folder, folder / "MIAM.png", hist)
    assert (folder / "MIAM.png").read_bytes() == before
    assert store.logo_versions(team.id) == []


def test_failed_swap_rolls_back_backup(store, folder, team, tmp_path, monkeypatch):
    before = (folder / "MIAM.png").read_bytes()
    monkeypatch.setattr(os, "replace", lambda *a: (_ for _ in ()).throw(PermissionError("locked")))
    with pytest.raises(ReplaceError, match="old logo is unchanged"):
        replace_logo(store, team, folder, real_png(tmp_path / "n.png", 64, 64, tag=b"x"), tmp_path / "h")
    monkeypatch.undo()
    assert (folder / "MIAM.png").read_bytes() == before
    assert store.logo_versions(team.id) == []
    assert not list((tmp_path / "h").glob("*.png"))
    assert not [n for n in os.listdir(folder) if n.startswith(REPLACE_PREFIX)]


def test_cleanup_leftovers(folder):
    make_logo(folder, f"{REPLACE_PREFIX}3.tmp")
    make_logo(folder, ".logosync-1-0.tmp")  # a sync journal file: not ours to delete
    assert cleanup_leftovers(folder) == 1
    assert os.listdir(folder) == [".logosync-1-0.tmp"]
