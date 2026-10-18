import os

import pytest

from conftest import PNG_HEADER, make_logo
from logosync.core import renamer
from logosync.core.library import file_digest, list_logos
from logosync.core.models import Move
from logosync.core.renamer import (SyncError, apply_moves, recover_interrupted, rollback,
                                   undo_last)


def setup_teams(store, folder, mapping):
    fid = store.folder_id(folder)
    ids = {}
    for name, filename in mapping.items():
        make_logo(folder, filename, content=name)
        sha, size = file_digest(folder / filename)
        ids[name] = store.add_team(fid, name, filename, sha, size)
    return fid, ids


def content(folder, filename):
    return (folder / filename).read_bytes()[len(PNG_HEADER):].decode()


def test_rename_preserves_bytes_and_updates_teams(store, folder):
    fid, ids = setup_teams(store, folder, {"Miami": "MIA.png", "Clemson": "CLEM.png"})
    before = (folder / "MIA.png").read_bytes()
    apply_moves(store, fid, folder, [Move(ids["Miami"], "MIA.png", "MIAM.png")])
    assert list_logos(folder) == ["CLEM.png", "MIAM.png"]
    assert (folder / "MIAM.png").read_bytes() == before
    assert store.team(ids["Miami"]).filename == "MIAM.png"
    assert store.last_completed_sync(fid).status == "done"


def test_swap_and_cycle(store, folder):
    fid, ids = setup_teams(store, folder, {"A": "A.png", "B": "B.png", "C": "C.png"})
    apply_moves(store, fid, folder, [Move(ids["A"], "A.png", "B.png"),
                                     Move(ids["B"], "B.png", "C.png"),
                                     Move(ids["C"], "C.png", "A.png")])
    assert content(folder, "B.png") == "A"
    assert content(folder, "C.png") == "B"
    assert content(folder, "A.png") == "C"
    assert not [n for n in os.listdir(folder) if n.endswith(".tmp")]


def test_case_only_rename(store, folder):
    fid, ids = setup_teams(store, folder, {"Miami": "mia.png"})
    apply_moves(store, fid, folder, [Move(ids["Miami"], "mia.png", "MIA.png")])
    assert os.listdir(folder) == ["MIA.png"]


def test_refuses_to_overwrite(store, folder):
    fid, ids = setup_teams(store, folder, {"Miami": "MIA.png"})
    make_logo(folder, "MIAM.png", content="someone else")
    with pytest.raises(SyncError, match="overwritten"):
        apply_moves(store, fid, folder, [Move(ids["Miami"], "MIA.png", "MIAM.png")])
    assert content(folder, "MIAM.png") == "someone else"
    assert content(folder, "MIA.png") == "Miami"


def test_failure_mid_sync_rolls_back(store, folder, monkeypatch):
    fid, ids = setup_teams(store, folder, {"A": "A.png", "B": "B.png", "C": "C.png"})
    real = renamer._rename
    calls = {"n": 0}

    def flaky(folder_, src, dst):
        calls["n"] += 1
        if calls["n"] == 5:  # phase 2, second file
            raise PermissionError("file is locked by another program")
        real(folder_, src, dst)

    monkeypatch.setattr(renamer, "_rename", flaky)
    with pytest.raises(SyncError, match="put back"):
        apply_moves(store, fid, folder, [Move(ids["A"], "A.png", "B.png"),
                                         Move(ids["B"], "B.png", "C.png"),
                                         Move(ids["C"], "C.png", "A.png")])
    monkeypatch.setattr(renamer, "_rename", real)
    assert [content(folder, f) for f in ("A.png", "B.png", "C.png")] == ["A", "B", "C"]
    assert store.team(ids["A"]).filename == "A.png"
    assert store.syncs(fid)[0].status == "rolled_back"


def test_recover_after_crash(store, folder, monkeypatch):
    fid, ids = setup_teams(store, folder, {"A": "A.png", "B": "B.png"})
    real = renamer._rename
    calls = {"n": 0}

    class PowerCut(BaseException):
        pass

    def crash(folder_, src, dst):
        calls["n"] += 1
        if calls["n"] == 4:
            raise PowerCut()
        real(folder_, src, dst)

    monkeypatch.setattr(renamer, "_rename", crash)
    monkeypatch.setattr(renamer, "rollback", lambda *a: [])  # the "crash" skips rollback
    with pytest.raises(PowerCut):
        apply_moves(store, fid, folder, [Move(ids["A"], "A.png", "B.png"),
                                         Move(ids["B"], "B.png", "A.png")])
    monkeypatch.undo()
    # Mid-sync state: one file at its new name, one at a temp name.
    assert any(n.endswith(".tmp") for n in os.listdir(folder))
    messages = recover_interrupted(store, fid, folder)
    assert len(messages) == 1 and "put back" in messages[0]
    assert content(folder, "A.png") == "A" and content(folder, "B.png") == "B"
    assert not [n for n in os.listdir(folder) if n.endswith(".tmp")]


def test_undo_last_sync(store, folder):
    fid, ids = setup_teams(store, folder, {"A": "A.png", "B": "B.png"})
    sid = apply_moves(store, fid, folder, [Move(ids["A"], "A.png", "B.png"),
                                           Move(ids["B"], "B.png", "A.png")])
    undo_last(store, fid, folder)
    assert content(folder, "A.png") == "A" and content(folder, "B.png") == "B"
    assert store.team(ids["A"]).filename == "A.png"
    assert [s.undone_by is not None for s in store.syncs(fid) if s.id == sid] == [True]
    with pytest.raises(SyncError, match="no sync to undo"):
        undo_last(store, fid, folder)


def test_rollback_reports_lost_file(store, folder):
    fid, ids = setup_teams(store, folder, {"A": "A.png"})
    sid = store.create_sync(fid, "sync")
    sha, size = file_digest(folder / "A.png")
    store.add_moves(sid, [Move(ids["A"], "A.png", "AA.png", seq=0, tmp_name=".logosync-x.tmp",
                               sha256=sha, size=size)])
    os.remove(folder / "A.png")
    assert rollback(store, sid, folder) == ["A.png"]
