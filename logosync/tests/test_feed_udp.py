import socket
import threading

from logosync.core.feed import FeedRecorder, FeedSnapshot, read_feed_file
from logosync.core.sender import DEMO_FEED, send_records
from logosync.core.udp import UdpListener


def test_snapshot_keeps_newest_abbreviation():
    snap = FeedSnapshot()
    snap.ingest(b"(Miami, MIA)", now=1)
    snap.ingest(b"(MIAMI, MIAM)", now=2)  # same team, different case
    snap.ingest(b"(Clemson, CLEM)", now=3)
    entries = {e.key: e for e in snap.entries()}
    assert entries["miami"].abbr == "MIAM"
    assert entries["miami"].earlier_abbrs == ["MIA"]
    assert entries["miami"].count == 2
    assert snap.packets == 3 and snap.last_packet_at == 3


def test_content_version_only_bumps_on_change():
    snap = FeedSnapshot()
    snap.ingest(b"(Miami, MIAM)")
    v = snap.content_version
    snap.ingest(b"(Miami, MIAM)")
    assert snap.content_version == v
    snap.ingest(b"(Miami, MIA)")
    assert snap.content_version == v + 1


def test_recording_round_trip(tmp_path):
    snap = FeedSnapshot(FeedRecorder(tmp_path))
    snap.ingest("(Montréal, MTL)\n(Miami, MIAM)".encode(), "10.0.0.5:5000")
    snap.ingest(b"(Miami, MIAM)")  # unchanged: not recorded again
    files = list(tmp_path.glob("feed-*.log"))
    assert len(files) == 1
    texts = read_feed_file(files[0])
    assert texts == ["(Montréal, MTL)\n(Miami, MIAM)"]


def test_read_plain_text_feed_file(tmp_path):
    p = tmp_path / "feed.txt"
    p.write_text("( Miami, MIAM )\n\n( Clemson, CLEM )\n", encoding="utf-8")
    assert read_feed_file(p) == ["( Miami, MIAM )", "( Clemson, CLEM )"]


def _free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_listener_receives_demo_feed():
    port = _free_port()
    snap = FeedSnapshot()
    done = threading.Event()

    def on_datagram(data, sender):
        snap.ingest(data, sender)
        if snap.packets >= 9:
            done.set()

    listener = UdpListener("127.0.0.1", port, on_datagram)
    listener.start()
    try:
        assert send_records(DEMO_FEED, "127.0.0.1", port, per_packet=2, interval=0) == 9
        assert done.wait(5)
    finally:
        listener.stop()
    assert len(snap.entries()) == len(DEMO_FEED)
    assert snap.last_sender.startswith("127.0.0.1:")


def test_listener_port_in_use_raises():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        listener = UdpListener("127.0.0.1", port, lambda d, a: None)
        try:
            listener.start()
        except OSError:
            pass
        else:
            listener.stop()
            raise AssertionError("expected the busy port to be refused")
