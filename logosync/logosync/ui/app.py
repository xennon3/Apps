"""Main window and application entry point."""
from __future__ import annotations

import logging
import sys
import time
import traceback
from logging.handlers import RotatingFileHandler
from pathlib import Path

from PySide6.QtCore import Qt, QTimer, QUrl
from PySide6.QtGui import QAction, QDesktopServices
from PySide6.QtWidgets import (QApplication, QFileDialog, QHBoxLayout, QLabel, QMainWindow,
                               QMessageBox, QPushButton, QTabWidget, QVBoxLayout, QWidget)

from .. import __version__
from .common import AMBER, GREEN, app_icon
from .demo import create_demo_folder
from .feed_tab import FRESH_SECONDS, FeedTab
from .history_tab import HistoryTab
from .mapping_tab import MappingTab
from .state import AppState, data_dir
from .sync_tab import SyncTab

log = logging.getLogger(__name__)

HOW_IT_WORKS = """<h3>How LogoSync works</h3>
<ol>
<li><b>Choose your logo folder</b>: the PNGs named with old abbreviations (MIA.png, FSU.png…).</li>
<li><b>Listen to the feed</b> (UDP port 20004). Every team and abbreviation received is listed.</li>
<li><b>Team mapping</b> (first time only): tie each logo to its team. <i>Suggest matches</i> does
most of it; you check and Save.</li>
<li><b>Preview &amp; sync</b>: see every old → new name, then click <i>Sync abbreviations</i>.</li>
</ol>
<p><b>New artwork?</b> On Team mapping, select the team and click <i>Replace logo…</i> (or drag
a PNG onto its row). The file name and team ID stay the same; the old image is kept in
<i>Logo history</i> and can be restored.</p>
<p>Next season: open LogoSync, let the feed arrive, check the preview, sync. That's it.</p>
<p><b>Safety:</b> files are only renamed, never re-saved, and each one is checked with SHA-256
afterwards. Nothing is ever overwritten. Every sync can be undone, and an interrupted sync is
rolled back automatically the next time the folder is opened.</p>"""


class MainWindow(QMainWindow):
    def __init__(self, state: AppState):
        super().__init__()
        self.state = state
        self.setWindowTitle("LogoSync")
        self.setWindowIcon(app_icon())

        # Folder bar
        self.folder_label = QLabel()
        self.folder_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        choose = QPushButton("Choose folder…")
        choose.clicked.connect(self.choose_folder)
        self.open_btn = QPushButton("Open in Explorer")
        self.open_btn.clicked.connect(self.open_in_explorer)
        self.pill = QLabel()
        self.pill.setStyleSheet("padding: 3px 10px; border-radius: 10px; font-weight: bold")
        bar = QHBoxLayout()
        title = QLabel("Logo folder:")
        title.setStyleSheet("font-weight: bold")
        bar.addWidget(title)
        bar.addWidget(self.folder_label, 1)
        bar.addWidget(choose)
        bar.addWidget(self.open_btn)
        bar.addSpacing(16)
        bar.addWidget(self.pill)

        self.feed_tab = FeedTab(state)
        self.mapping_tab = MappingTab(state)
        self.sync_tab = SyncTab(state)
        self.history_tab = HistoryTab(state)
        self.tabs = QTabWidget()
        self.tabs.addTab(self.feed_tab, "1 · Listen to feed")
        self.tabs.addTab(self.mapping_tab, "2 · Team mapping")
        self.tabs.addTab(self.sync_tab, "3 · Preview && sync")
        self.tabs.addTab(self.history_tab, "History")
        self.tabs.currentChanged.connect(self._tab_changed)

        central = QWidget()
        layout = QVBoxLayout(central)
        layout.addLayout(bar)
        layout.addWidget(self.tabs, 1)
        self.setCentralWidget(central)
        self._build_menus()

        state.folderChanged.connect(self._show_folder)
        QTimer(self, interval=500, timeout=self._update_pill).start()
        self._show_folder()
        self._update_pill()
        self.resize(1100, 760)
        geometry = state.settings.value("window/geometry")
        if geometry is not None:
            self.restoreGeometry(geometry)

    def _build_menus(self) -> None:
        file_menu = self.menuBar().addMenu("&File")
        for text, slot in [("Choose logo folder…", self.choose_folder),
                           ("Export mapping…", self.mapping_tab.export),
                           ("Import mapping…", self.mapping_tab.import_)]:
            file_menu.addAction(text, slot)
        file_menu.addSeparator()
        file_menu.addAction("Exit", self.close)
        tools = self.menuBar().addMenu("&Tools")
        tools.addAction("Try the demo", lambda: self.open_demo(reset=False))
        tools.addAction("Reset the demo", lambda: self.open_demo(reset=True))
        tools.addAction("Send test feed", self.feed_tab.send_test_feed)
        tools.addSeparator()
        tools.addAction("Open data folder (database, recordings, log)",
                        lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.state.data_dir))))
        help_menu = self.menuBar().addMenu("&Help")
        help_menu.addAction("How it works", lambda: QMessageBox.information(self, "LogoSync", HOW_IT_WORKS))
        about = QAction(f"About LogoSync {__version__}", self)
        about.triggered.connect(lambda: QMessageBox.about(
            self, "LogoSync", f"<b>LogoSync {__version__}</b><br>Renames team logo files to the "
            "abbreviations sent by a UDP feed.<br><br>Data folder:<br>" + str(self.state.data_dir)))
        help_menu.addAction(about)

    # Folder

    def choose_folder(self) -> None:
        if not self._confirm_leave_mapping():
            return
        start = str(self.state.folder or Path.home())
        path = QFileDialog.getExistingDirectory(self, "Choose the folder with your logo PNGs", start)
        if path:
            self.open_folder(Path(path))

    def open_folder(self, path: Path) -> None:
        try:
            messages = self.state.open_folder(path)
        except OSError as exc:
            QMessageBox.warning(self, "LogoSync", f"Couldn't open {path}:\n{exc}")
            return
        if messages:
            QMessageBox.information(self, "LogoSync", "\n\n".join(messages))
        if not self.state.teams() and self.state.files():
            self.tabs.setCurrentWidget(self.mapping_tab if self.state.snapshot.entries()
                                       else self.feed_tab)

    def open_demo(self, reset: bool) -> None:
        if not self._confirm_leave_mapping():
            return
        folder = create_demo_folder(self.state.data_dir / "Demo logos", self.state.store, reset)
        self.open_folder(folder)
        self.feed_tab.send_test_feed()
        QMessageBox.information(
            self, "LogoSync demo",
            "The demo folder has 15 made-up logos named with last season's abbreviations, and a "
            "test feed is being sent to this PC now.\n\n"
            "Next: go to 2 · Team mapping, click ✨ Suggest matches, check them, Save. "
            "Then 3 · Preview & sync.\n\nTools → Reset the demo starts over.")
        self.tabs.setCurrentWidget(self.feed_tab)

    def open_in_explorer(self) -> None:
        if self.state.folder:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.state.folder)))

    def _show_folder(self) -> None:
        folder = self.state.folder
        self.folder_label.setText(str(folder) if folder else "<i>none chosen yet</i>")
        self.open_btn.setEnabled(folder is not None)

    def _confirm_leave_mapping(self) -> bool:
        if not self.mapping_tab.dirty():
            return True
        answer = QMessageBox.question(
            self, "LogoSync", "The team mapping has unsaved changes. Save them first?",
            QMessageBox.Save | QMessageBox.Discard | QMessageBox.Cancel)
        if answer == QMessageBox.Save:
            self.mapping_tab.save()
            return not self.mapping_tab.dirty()
        return answer == QMessageBox.Discard

    # Status

    def _tab_changed(self, index: int) -> None:
        if self.tabs.widget(index) is self.sync_tab:
            self.sync_tab.refresh()

    def _update_pill(self) -> None:
        snap = self.state.snapshot
        if not self.state.listening:
            self.pill.setText("○ Not listening")
            self.pill.setStyleSheet("padding: 3px 10px; color: palette(mid)")
            return
        fresh = snap.last_packet_at is not None and time.time() - snap.last_packet_at < FRESH_SECONDS
        color = GREEN if fresh else AMBER
        word = "LIVE" if fresh else "Waiting for packets"
        self.pill.setText(f"● {word} · :{self.state.listener.port} · "
                          f"{len(snap.entries())} teams")
        self.pill.setStyleSheet(f"padding: 3px 10px; border-radius: 10px; font-weight: bold;"
                                f" color: white; background: {color.name()}")

    def closeEvent(self, event) -> None:
        if not self._confirm_leave_mapping():
            event.ignore()
            return
        self.state.settings.setValue("window/geometry", self.saveGeometry())
        self.state.shutdown()
        event.accept()


def _setup_logging(directory: Path) -> None:
    handler = RotatingFileHandler(directory / "logosync.log", maxBytes=1_000_000, backupCount=3,
                                  encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    logging.basicConfig(level=logging.INFO, handlers=[handler])


def _excepthook(kind, value, tb) -> None:
    log.error("Unexpected error", exc_info=(kind, value, tb))
    text = "".join(traceback.format_exception(kind, value, tb))
    QMessageBox.critical(None, "LogoSync", f"Something went wrong:\n\n{value}\n\n"
                         f"Details are in logosync.log in the data folder.\n\n{text[-1500:]}")


def main() -> None:
    directory = data_dir()
    _setup_logging(directory)
    if sys.platform == "win32":
        try:  # so Windows shows LogoSync's own taskbar icon
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("LogoSync.LogoSync")
        except Exception:
            pass
    app = QApplication(sys.argv)
    app.setApplicationName("LogoSync")
    app.setStyle("Fusion")
    app.setWindowIcon(app_icon())
    sys.excepthook = _excepthook

    state = AppState(directory)
    window = MainWindow(state)
    window.show()

    last = state.settings.value("folder")
    if last and Path(last).is_dir():
        window.open_folder(Path(last))
    if state.settings.value("listen/autostart", True, type=bool):
        window.feed_tab.start_listening(quiet=True)
    log.info("LogoSync %s started", __version__)
    sys.exit(app.exec())
