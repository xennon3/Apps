"""Preview: decide, for every mapped team, what a sync would do. Touches no files."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

from .feed import FeedEntry
from .library import split_ext
from .models import Team
from .names import abbreviation_problem

RENAME = "rename"
UNCHANGED = "unchanged"
NOT_IN_FEED = "not_in_feed"
MISSING_FILE = "missing_file"
INVALID = "invalid"
DUPLICATE = "duplicate"
COLLISION = "collision"

BLOCKED = (INVALID, DUPLICATE, COLLISION, MISSING_FILE)


@dataclass
class PlanRow:
    team: Team
    old_name: str
    entry: FeedEntry | None
    new_name: str
    status: str
    detail: str = ""


@dataclass
class Plan:
    rows: list[PlanRow] = field(default_factory=list)
    feed_only: list[FeedEntry] = field(default_factory=list)

    @property
    def renames(self) -> list[PlanRow]:
        return [r for r in self.rows if r.status == RENAME]

    def count(self, *statuses: str) -> int:
        return sum(r.status in statuses for r in self.rows)


def build_plan(teams: list[Team], entries: list[FeedEntry], files: list[str]) -> Plan:
    on_disk = {f.casefold(): f for f in files}
    feed = {e.key: e for e in entries}
    owner = {t.filename.casefold(): t for t in teams}
    plan = Plan()

    for team in teams:
        actual = on_disk.get(team.filename.casefold())
        entry = feed.get(team.norm_name)
        if actual is None:
            plan.rows.append(PlanRow(team, team.filename, entry, "", MISSING_FILE,
                                     "Logo file not found in the folder"))
            continue
        if entry is None:
            plan.rows.append(PlanRow(team, actual, None, "", NOT_IN_FEED,
                                     "Not received from the feed; file left as is"))
            continue
        problem = abbreviation_problem(entry.abbr)
        if problem:
            plan.rows.append(PlanRow(team, actual, entry, "", INVALID,
                                     f"Can't use “{entry.abbr}”: {problem}"))
            continue
        new_name = entry.abbr + split_ext(actual)[1]
        if new_name == actual:
            plan.rows.append(PlanRow(team, actual, entry, new_name, UNCHANGED))
            continue
        detail = "Case change only" if new_name.casefold() == actual.casefold() else ""
        if entry.earlier_abbrs:
            note = f"Feed changed it while listening (was {', '.join(entry.earlier_abbrs)})"
            detail = f"{detail}; {note}" if detail else note
        plan.rows.append(PlanRow(team, actual, entry, new_name, RENAME, detail))

    # Two teams can't end up with the same file name.
    targets: dict[str, list[PlanRow]] = defaultdict(list)
    for row in plan.rows:
        if row.status in (RENAME, UNCHANGED):
            targets[row.new_name.casefold()].append(row)
    for group in targets.values():
        if len(group) < 2:
            continue
        for row in group:
            if row.status == RENAME:
                others = ", ".join(r.team.name for r in group if r is not row)
                row.status = DUPLICATE
                row.detail = f"{row.entry.abbr} is also the abbreviation for {others}"

    # Never overwrite a file that stays put. Blocking one rename can keep its
    # old file in place and block another, so repeat until nothing changes.
    changed = True
    while changed:
        changed = False
        vacated = {r.old_name.casefold() for r in plan.rows if r.status == RENAME}
        for row in plan.rows:
            if row.status != RENAME:
                continue
            target = row.new_name.casefold()
            if target == row.old_name.casefold() or target not in on_disk or target in vacated:
                continue
            holder = owner.get(target)
            whose = f"the logo of {holder.name}" if holder else "a file not mapped to any team"
            row.status = COLLISION
            row.detail = f"{on_disk[target]} already exists ({whose}); it won't be overwritten"
            changed = True

    mapped = {t.norm_name for t in teams}
    plan.feed_only = [e for e in entries if e.key not in mapped]
    return plan
