# Serial Scope

A live dashboard for anything that talks over a serial port (Arduino, ESP32, GPS, sensors, test gear).
It's one HTML file: there's nothing to install and no server.

## Run it

1. Open `index.html` in **Chrome or Edge** on desktop. (Firefox and Safari don't have Web Serial.)
2. Pick a baud rate, click **Connect**, and choose your device.
3. Each value appears as a big tile with a sparkline, plus a lane on the live chart.

No device handy? Click **Demo**.

## What it understands

Every line is parsed automatically:

| Device prints              | Channels you get        |
|----------------------------|-------------------------|
| `23.4`                     | `value`                 |
| `12,45,0.7` or `12 45 0.7` | `ch1`, `ch2`, `ch3`     |
| `temp:23.4 hum=51`         | `temp`, `hum`           |
| `{"temp":23.4,"imu":{"x":1}}` | `temp`, `imu.x`      |

Lines that aren't numbers still show up in the raw console.

## Features

- Big-number tiles with min/max and sparklines
- Live chart: stacked lanes or overlay, with windows from 10 s to 15 min, and pause
- 🔔 per-channel alarm range: the tile pulses, the chart shades the band, and an optional beep sounds
- 👁 hide a channel from the chart
- Raw console with timestamps and a filter, plus a send box (LF / CRLF / CR)
- ● Rec saves a CSV of just the recording; you can also download the whole session as CSV or as a raw log
- Light and dark themes

## Arduino example

```cpp
void setup() { Serial.begin(9600); }
void loop() {
  Serial.print("temp:");  Serial.print(analogRead(A0) * 0.488);
  Serial.print(" light:"); Serial.println(analogRead(A1));
  delay(100);
}
```
