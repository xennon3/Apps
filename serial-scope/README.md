# Serial Scope

A live dashboard for serial data. It's one HTML file: there's nothing to install and no server.
It's built for the **Daktronics Football TV Feed** (spec ED-16098 Rev 1) coming in through a Moxa RS-232 adapter.
It also handles generic text data.

## Run it

1. Plug in the Moxa adapter and make sure its driver is installed (it shows up as a COM port in Device Manager).
2. **Close Tera Term.** Only one program at a time can open a COM port.
3. Open `index.html` in **Chrome or Edge** on desktop. (Firefox and Safari don't have Web Serial.)
4. Leave the baud rate at **9600** (the app always uses 8 data bits, no parity, 1 stop bit, per the spec). Click **Connect** and pick the Moxa COM port.

No hardware handy? Click **Demo** (a simulated game), or **Paste / Load log**. That box starts with the exact data from the COM1 screenshot.

## The feed (ED-16098 Rev 1)

Each packet is 45 bytes: `SOH` + 41 data bytes + 2-character hex checksum + `EOT`. Tera Term doesn't draw
SOH/EOT, so you see 43 characters per packet, all run together.

| Bytes | Field | Bytes | Field |
|---|---|---|---|
| 0–4 | Game clock (MM:SS) | 32 | Down |
| 5–14 | Home team name | 33–34 | To go |
| 15–24 | Guest team name | 35 | Home possession |
| 25–26 | Home score | 36 | Guest possession |
| 27–28 | Guest score | 37–38 | Play clock |
| 29 | Quarter | 39 | Home timeouts left |
| 30–31 | Ball on | 40 | Guest timeouts left |

The checksum is the sum of the 41 data bytes, mod 256, as 2 hex characters. The app frames packets on SOH/EOT,
rejects any packet whose checksum fails (the ✗ count), and accepts Rev 0 packets (37 data bytes, no checksum) from older equipment.
For pasted Tera Term text, where SOH/EOT are gone, it finds packet boundaries using the checksum.

The screenshot's data decodes as: clock **97:42**, **HOME 23 – GUEST 8**, **Q1**, play clock `0:`, **3 timeouts each**.

## Features

- Football scoreboard view: scores, quarter, game clock, play clock, down & distance, ball on,
  🏈 possession, timeout dots, and a LIVE / NO DATA light. The feed repeats about once a second, so going quiet means a problem.
- One tile per field: rename it, add units, set an alarm range (optional beep), or ✕ hide it
- Game log: scores (with a guess at the play, e.g. +3 = field goal), quarters, possession changes and timeouts
- Live chart (10 s to 1 h), CSV recording and export, raw log download
- Console shows one packet per line, color-coded by field, with an option to reveal spaces and SOH/EOT
- An **Auto** format for ordinary text devices. It switches to the Daktronics format by itself if packets show up.

Settings (format, baud rate, field names, alarms) are saved in the browser.
