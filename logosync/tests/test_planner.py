from logosync.core.feed import FeedEntry
from logosync.core.models import Team
from logosync.core.names import normalize_name
from logosync.core.planner import (COLLISION, DUPLICATE, INVALID, MISSING_FILE, NOT_IN_FEED,
                                   RENAME, UNCHANGED, build_plan)


def team(i, name, filename):
    return Team(i, name, normalize_name(name), filename)


def feed(*pairs):
    return [FeedEntry(n, a, 0, 0) for n, a in pairs]


def statuses(plan):
    return {r.team.name: (r.status, r.new_name) for r in plan.rows}


def test_basic_statuses():
    teams = [team(1, "Miami", "MIA.png"), team(2, "Clemson", "CLEM.png"),
             team(3, "Duke", "DUKE.png"), team(4, "Pitt", "PITT.png")]
    plan = build_plan(teams, feed(("Miami", "MIAM"), ("Clemson", "CLEM"), ("Stanford", "STAN"),
                                  ("Pitt", "PT")),
                      ["MIA.png", "CLEM.png", "DUKE.png"])
    s = statuses(plan)
    assert s["Miami"] == (RENAME, "MIAM.png")
    assert s["Clemson"] == (UNCHANGED, "CLEM.png")
    assert s["Duke"][0] == NOT_IN_FEED
    assert s["Pitt"][0] == MISSING_FILE
    assert [e.name for e in plan.feed_only] == ["Stanford"]


def test_extension_case_preserved():
    plan = build_plan([team(1, "Miami", "mia.PNG")], feed(("Miami", "MIAM")), ["mia.PNG"])
    assert plan.rows[0].new_name == "MIAM.PNG"


def test_case_only_change_is_a_rename():
    plan = build_plan([team(1, "Miami", "mia.png")], feed(("Miami", "MIA")), ["mia.png"])
    assert plan.rows[0].status == RENAME and plan.rows[0].new_name == "MIA.png"


def test_invalid_abbreviation_blocked():
    plan = build_plan([team(1, "Connecticut", "UCONN.png")], feed(("Connecticut", "CON")),
                      ["UCONN.png"])
    assert plan.rows[0].status == INVALID
    assert "reserved" in plan.rows[0].detail


def test_duplicate_abbreviation_blocks_both():
    teams = [team(1, "Miami", "MIA.png"), team(2, "Miami (OH)", "MOH.png")]
    plan = build_plan(teams, feed(("Miami", "MIA2"), ("Miami (OH)", "mia2")), ["MIA.png", "MOH.png"])
    assert {r.status for r in plan.rows} == {DUPLICATE}


def test_duplicate_with_unchanged_team_blocks_only_the_rename():
    teams = [team(1, "Miami", "MIA.png"), team(2, "Miami (OH)", "MOH.png")]
    plan = build_plan(teams, feed(("Miami", "MIA"), ("Miami (OH)", "MIA")), ["MIA.png", "MOH.png"])
    assert statuses(plan) == {"Miami": (UNCHANGED, "MIA.png"), "Miami (OH)": (DUPLICATE, "MIA.png")}


def test_collision_with_unmapped_file():
    plan = build_plan([team(1, "Miami", "MIA.png")], feed(("Miami", "MIAM")),
                      ["MIA.png", "miam.png"])
    assert plan.rows[0].status == COLLISION
    assert "not mapped" in plan.rows[0].detail


def test_swap_is_allowed():
    teams = [team(1, "A Team", "A.png"), team(2, "B Team", "B.png")]
    plan = build_plan(teams, feed(("A Team", "B"), ("B Team", "A")), ["A.png", "B.png"])
    assert {r.status for r in plan.rows} == {RENAME}


def test_collision_cascades():
    # C stays (not in feed) so B can't become C, so B stays, so A can't become B.
    teams = [team(1, "A Team", "A.png"), team(2, "B Team", "B.png"), team(3, "C Team", "C.png")]
    plan = build_plan(teams, feed(("A Team", "B"), ("B Team", "C")), ["A.png", "B.png", "C.png"])
    s = statuses(plan)
    assert s["B Team"][0] == COLLISION and s["A Team"][0] == COLLISION
