"""End-to-end run of the demo through the real window (offscreen)."""
import pytest

pytest.importorskip("PySide6")

from PySide6.QtCore import QSettings  # noqa: E402
from PySide6.QtWidgets import QApplication, QMessageBox  # noqa: E402

from logosync.core.library import list_logos  # noqa: E402
from logosync.core.sender import DEMO_FEED  # noqa: E402


@pytest.fixture
def app(tmp_path, monkeypatch):
    QSettings.setDefaultFormat(QSettings.IniFormat)
    QSettings.setPath(QSettings.IniFormat, QSettings.UserScope, str(tmp_path / "settings"))
    qapp = QApplication.instance() or QApplication([])
    answers = []
    monkeypatch.setattr(QMessageBox, "information", lambda *a, **k: answers.append(a[2]))
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: answers.append(a[2]))
    monkeypatch.setattr(QMessageBox, "critical", lambda *a, **k: answers.append(a[2]))
    monkeypatch.setattr(QMessageBox, "question", lambda *a, **k: QMessageBox.Yes)
    yield qapp, answers


def test_demo_end_to_end(app, tmp_path):
    qapp, answers = app
    from logosync.ui.app import MainWindow
    from logosync.ui.demo import create_demo_folder
    from logosync.ui.state import AppState

    state = AppState(tmp_path / "data")
    win = MainWindow(state)
    folder = create_demo_folder(state.data_dir / "Demo logos", state.store)
    win.open_folder(folder)
    assert len(state.files()) == 15

    for name, abbr in DEMO_FEED:
        state.snapshot.ingest_text(f"( {name}, {abbr} )", "test")
    win.feed_tab.refresh()
    assert win.feed_tab.table.rowCount() == len(DEMO_FEED)

    win.mapping_tab.suggest_matches()
    assert "15 of 15" in answers[-1]
    win.mapping_tab.save()
    assert len(state.teams()) == 15 and not win.mapping_tab.dirty()

    win.sync_tab.refresh()
    renames = {r.old_name: r.new_name for r in win.sync_tab.plan.renames}
    assert renames == {"MIA.png": "MIAM.png", "VT.png": "VTU.png", "LOU.png": "LOUI.png",
                       "SYR.png": "CUSE.png", "WAKE.png": "WF.png", "NCST.png": "NCSU.png",
                       "MOH.png": "M-OH.png"}
    before = (folder / "MIA.png").read_bytes()
    win.sync_tab.sync()
    assert "Renamed 7" in answers[-1]
    files = list_logos(folder)
    assert "MIAM.png" in files and "MIA.png" not in files
    assert (folder / "MIAM.png").read_bytes() == before
    assert win.sync_tab.plan.renames == []

    win.sync_tab.undo()
    assert "MIA.png" in list_logos(folder)
    win.history_tab.refresh()
    assert win.history_tab.list.rowCount() == 2
    win.close()
