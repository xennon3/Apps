"""Shared application state: the open folder, the database, the feed and the listener."""
from __future__ import annotations

import logging
import os
import sys
from pathlib import Path

from PySide6.QtCore import QFileSystemWatcher, QObject, QSettings, QTimer, Signal

from ..core.feed import FeedRecorder, FeedSnapshot
from ..core.library import list_logos
from ..core.mapping import reconcile
from ..core.models import Team
from ..core.renamer import recover_interrupted
from ..core.replace import cleanup_leftovers
from ..core.store import Store
from ..core.udp import UdpListener

log = logging.getLogger(__name__)


def data_dir() -> Path:
    """%APPDATA%\\LogoSync on Windows (never next to the .exe)."""
    base = os.environ.get("APPDATA") if sys.platform == "win32" else None
    root = Path(base) if base else Path.home() / ".local" / "share"
    path = root / "LogoSync"
    path.mkdir(parents=True, exist_ok=True)
    return path


class AppState(QObject):
    folderChanged = Signal()
    mappingChanged = Signal()
    filesChanged = Signal()
    listeningChanged = Signal()
    listenFailed = Signal(str)

    def __init__(self, directory: Path | None = None):
        super().__init__()
        self.data_dir = directory or data_dir()
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.recordings_dir = self.data_dir / "recordings"
        self.store = Store(self.data_dir / "logosync.db")
        self.snapshot = FeedSnapshot(FeedRecorder(self.recordings_dir))
        self.settings = QSettings("LogoSync", "LogoSync")
        self.listener: UdpListener | None = None
        self.folder: Path | None = None
        self.folder_id: int | None = None
        self.busy = False  # set while LogoSync itself renames files

        self._watcher = QFileSystemWatcher(self)
        self._debounce = QTimer(self, singleShot=True, interval=400)
        self._watcher.directoryChanged.connect(lambda _: self._debounce.start())
        self._debounce.timeout.connect(self._folder_touched)
        self.listenFailed.connect(lambda _: self.stop_listening())

    # Folder

    def open_folder(self, path: str | Path) -> list[str]:
        """Open a logo folder; returns notes about recovered syncs or relinked files."""
        path = Path(path)
        if self._watcher.directories():
            self._watcher.removePaths(self._watcher.directories())
        self.folder = path
        self.folder_id = self.store.folder_id(path)
        messages = recover_interrupted(self.store, self.folder_id, path)
        cleanup_leftovers(path)
        messages += reconcile(self.store, self.folder_id, path)
        self._watcher.addPath(str(path))
        self.settings.setValue("folder", str(path))
        log.info("Opened folder %s", path)
        self.folderChanged.emit()
        return messages

    def teams(self) -> list[Team]:
        return self.store.teams(self.folder_id) if self.folder_id else []

    def files(self) -> list[str]:
        if not self.folder:
            return []
        try:
            return list_logos(self.folder)
        except OSError:
            return []

    def _folder_touched(self) -> None:
        if self.busy or not self.folder:
            return
        if self.folder_id and reconcile(self.store, self.folder_id, self.folder):
            self.mappingChanged.emit()
        self.filesChanged.emit()

    # Listening

    @property
    def listening(self) -> bool:
        return bool(self.listener and self.listener.running)

    def start_listening(self, host: str, port: int, share_port: bool) -> None:
        self.stop_listening()
        listener = UdpListener(host, port, self.snapshot.ingest,
                               on_error=lambda exc: self.listenFailed.emit(str(exc)),
                               share_port=share_port)
        listener.start()  # raises OSError, e.g. port already in use
        self.listener = listener
        self.listeningChanged.emit()

    def stop_listening(self) -> None:
        if self.listener:
            self.listener.stop()
            self.listener = None
            self.listeningChanged.emit()

    def shutdown(self) -> None:
        self.stop_listening()
        self.store.close()
