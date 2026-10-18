"""A test feed sender, so LogoSync can be tried without the real server.

    python -m logosync.core.sender                 # send the demo feed to 127.0.0.1:20004
    python -m logosync.core.sender --file teams.txt --host 192.168.1.50 --repeat 0
"""
from __future__ import annotations

import argparse
import socket
import time

from .udp import DEFAULT_PORT

# The demo folder's old logos are MIA.png, FSU.png, ... (see ui/demo.py).
DEMO_FEED = [
    ("Miami", "MIAM"),
    ("Florida State", "FSU"),
    ("Clemson", "CLEM"),
    ("North Carolina", "UNC"),
    ("Duke", "DUKE"),
    ("Virginia", "UVA"),
    ("Virginia Tech", "VTU"),
    ("Boston College", "BC"),
    ("Louisville", "LOUI"),
    ("Pittsburgh", "PITT"),
    ("Syracuse", "CUSE"),
    ("Wake Forest", "WF"),
    ("Georgia Tech", "GT"),
    ("NC State", "NCSU"),
    ("Miami (OH)", "M-OH"),
    ("Stanford", "STAN"),
    ("SMU", "SMU"),
    ("California", "CAL"),
]


def format_record(name: str, abbr: str) -> str:
    return f"( {name}, {abbr} )"


def send_records(records, host: str = "127.0.0.1", port: int = DEFAULT_PORT,
                 per_packet: int = 1, interval: float = 0.02) -> int:
    """Send records as UDP datagrams; returns the number of packets sent."""
    sent = 0
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        for i in range(0, len(records), per_packet):
            chunk = records[i:i + per_packet]
            payload = "".join(format_record(n, a) for n, a in chunk)
            sock.sendto(payload.encode("utf-8"), (host, port))
            sent += 1
            if interval:
                time.sleep(interval)
    return sent


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description="Send a test team feed over UDP.")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--file", help="text file with one 'Team Name, ABBR' per line")
    ap.add_argument("--per-packet", type=int, default=1, help="records per datagram")
    ap.add_argument("--interval", type=float, default=0.05, help="seconds between packets")
    ap.add_argument("--repeat", type=int, default=1, help="times to send the list (0 = forever)")
    args = ap.parse_args(argv)

    records = DEMO_FEED
    if args.file:
        with open(args.file, encoding="utf-8") as f:
            records = [tuple(p.strip() for p in line.rsplit(",", 1))
                       for line in f if "," in line]
    n = 0
    while args.repeat == 0 or n < args.repeat:
        sent = send_records(records, args.host, args.port, args.per_packet, args.interval)
        n += 1
        print(f"Pass {n}: sent {len(records)} teams in {sent} packets to {args.host}:{args.port}")


if __name__ == "__main__":
    main()
