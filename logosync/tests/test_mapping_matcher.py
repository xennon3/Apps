import os

import pytest

from conftest import make_logo
from logosync.core.mapping import (MappingError, export_mapping, import_mapping, reconcile,
                                   save_mapping)
from logosync.core.matcher import score, suggest
from logosync.core.sender import DEMO_FEED


def test_mapping_survives_restart(tmp_path, folder):
    from logosync.core.store import Store
    make_logo(folder, "MIA.png")
    db = tmp_path / "x.db"
    s = Store(db)
    fid = s.folder_id(folder)
    save_mapping(s, fid, folder, {"MIA.png": "Miami"})
    team_id = s.teams(fid)[0].id
    s.close()
    s = Store(db)
    t = s.teams(s.folder_id(folder))
    assert [(x.id, x.name, x.filename) for x in t] == [(team_id, "Miami", "MIA.png")]
    s.close()


def test_rename_team_keeps_id_and_unassign(store, folder):
    make_logo(folder, "MIA.png")
    make_logo(folder, "FSU.png")
    fid = store.folder_id(folder)
    save_mapping(store, fid, folder, {"MIA.png": "Miami", "FSU.png": "Florida St"})
    ids = {t.filename: t.id for t in store.teams(fid)}
    save_mapping(store, fid, folder, {"MIA.png": "", "FSU.png": "Florida State"})
    teams = store.teams(fid)
    assert [(t.id, t.name) for t in teams] == [(ids["FSU.png"], "Florida State")]


def test_swap_names_between_files(store, folder):
    make_logo(folder, "A.png")
    make_logo(folder, "B.png")
    fid = store.folder_id(folder)
    save_mapping(store, fid, folder, {"A.png": "Alpha", "B.png": "Beta"})
    save_mapping(store, fid, folder, {"A.png": "Beta", "B.png": "Alpha"})
    assert {t.filename: t.name for t in store.teams(fid)} == {"A.png": "Beta", "B.png": "Alpha"}


def test_duplicate_team_rejected(store, folder):
    make_logo(folder, "A.png")
    make_logo(folder, "B.png")
    fid = store.folder_id(folder)
    with pytest.raises(MappingError):
        save_mapping(store, fid, folder, {"A.png": "Miami", "B.png": " MIAMI "})


def test_reconcile_follows_external_rename(store, folder):
    make_logo(folder, "MIA.png")
    fid = store.folder_id(folder)
    save_mapping(store, fid, folder, {"MIA.png": "Miami"})
    os.rename(folder / "MIA.png", folder / "Miami-old.png")
    messages = reconcile(store, fid, folder)
    assert store.teams(fid)[0].filename == "Miami-old.png"
    assert "renamed" in messages[0]


def test_missing_team_relinks_on_save(store, folder):
    make_logo(folder, "MIA.png")
    fid = store.folder_id(folder)
    save_mapping(store, fid, folder, {"MIA.png": "Miami"})
    tid = store.teams(fid)[0].id
    os.remove(folder / "MIA.png")
    make_logo(folder, "U.png", content="new art")
    save_mapping(store, fid, folder, {"U.png": "Miami"})
    assert [(t.id, t.filename) for t in store.teams(fid)] == [(tid, "U.png")]


def test_export_import(store, folder, tmp_path):
    make_logo(folder, "MIA.png")
    fid = store.folder_id(folder)
    save_mapping(store, fid, folder, {"MIA.png": "Miami"})
    out = tmp_path / "map.json"
    assert export_mapping(store, fid, out) == 1
    os.rename(folder / "MIA.png", folder / "renamed.png")
    assert import_mapping(store, fid, folder, out) == {"renamed.png": "Miami"}


@pytest.mark.parametrize("stem,name,minimum", [
    ("FSU", "Florida State", 0.9), ("NCST", "NC State", 0.8), ("CLEM", "Clemson", 0.8),
    ("DUKE", "Duke", 0.95), ("BC", "Boston College", 0.9), ("MOH", "Miami (OH)", 0.55),
])
def test_score_matches(stem, name, minimum):
    assert score(stem, name) >= minimum


def test_score_non_matches():
    assert score("MOH", "Miami") == 0
    assert score("FSU", "Clemson") == 0


def test_suggest_demo_folder():
    stems = {f"{s}.png": s for s in ["MIA", "FSU", "CLEM", "UNC", "DUKE", "UVA", "VT", "BC",
                                     "LOU", "PITT", "SYR", "WAKE", "GT", "NCST", "MOH"]}
    got = suggest(stems, DEMO_FEED)
    expected = {"MIA": "Miami", "FSU": "Florida State", "CLEM": "Clemson", "UNC": "North Carolina",
                "DUKE": "Duke", "UVA": "Virginia", "VT": "Virginia Tech", "BC": "Boston College",
                "LOU": "Louisville", "PITT": "Pittsburgh", "SYR": "Syracuse", "WAKE": "Wake Forest",
                "GT": "Georgia Tech", "NCST": "NC State", "MOH": "Miami (OH)"}
    assert {f[:-4]: s.name for f, s in got.items()} == expected
    assert "Miami (OH)" in got["MIA.png"].alternatives  # flagged as a close call
