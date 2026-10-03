# Serial Scope

A live dashboard for serial port data. It's one HTML file: there's nothing to install and no server.
It's built first for the **HOME/GUEST scoreboard feed on COM1**, and it also handles generic text data.

## Run it

1. **Close Tera Term.** Only one program at a time can open COM1.
2. Open `index.html` in **Chrome or Edge** on desktop. (Firefox and Safari don't have Web Serial.)
3. Set the baud rate to the one Tera Term uses (*Setup → Serial port…*), click **Connect**, and pick COM1.

No device handy? Click **Demo**, or **Paste / Load log**. That box starts with the exact data from the COM1 screenshot.

## The scoreboard format (decoded)

The device sends fixed 43-character records with no line breaks, which is why Tera Term shows one wrapped wall of text:

```
97:42HOME      GUEST     23 81       0:33BF
|    |         |         |       |   |   └ checksum: sum of the 41 bytes before it, mod 256, in hex
|    |         |         |       |   └──── "Clock 2"
|    |         |         |       └──────── (blank; reserved for more fields)
|    |         |         └──────────────── home score, guest score
|    |         └────────────────────────── guest team name (10 chars)
|    └──────────────────────────────────── home team name (10 chars)
└───────────────────────────────────────── game clock
```

The checksum matches every record in the screenshot. The app uses it to lock onto record boundaries
and to reject corrupted records (✗ count). Field names are best guesses: click ✏ on any tile to rename one.
Any extra values that appear in the blank area show up as "Extra 1", "Extra 2", and so on.

## Features

- Big scoreboard view: team names, scores, clocks, a LIVE / NO DATA indicator, and checksum counts
- One tile per field: rename it, add units, set an alarm range (optional beep), or ✕ hide it
- Live chart of scores and clocks over time (10 s to 1 h)
- Events log: every score change, with how much it changed and the clock time
- Console shows one record per line, color-coded by field, with an option to reveal spaces and hidden control characters
- ● Rec and Download CSV export the decoded values; you can also download a raw log
- An **Auto** format for ordinary text devices (`23.4`, `a,b,c`, `temp:23 hum=51`, JSON).
  If scoreboard records show up while in Auto, the app switches format by itself.

Settings (format, baud rate, field names, alarms) are saved in the browser.
