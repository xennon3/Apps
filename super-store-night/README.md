# Super Store Night

A first-person horror prototype set in a 24-hour big-box store. Walk in from the parking lot, find Aisle 14 (Hardware), survive the blackout, and pick a weapon off the pegboard. Then the store locks down. To get out you need your car keys (a zombie has them; listen for the jingle), the manager's key card from the back office, and power to the front doors from the breaker panel. Once you have all three, the Night Manager comes for you, hunting by your light, and you have to make it to the doors. Don't look back when you leave. You will anyway.

Open `index.html` in a browser. No build step. Three.js r128 loads from cdnjs; all sound is synthesized with the Web Audio API: HRTF 3D panning, a convolution reverb shaped like a big store, and formant-filtered voices for the screams, groans and growls. Headphones recommended.

- `index.html` is the full page.
- `game.html` is the same page without the document wrapper, used when publishing it as a Claude artifact.

Controls: WASD to move, Shift to run, mouse to look, click to swing (hold with the chainsaw), E to take a weapon, F to toggle the flashlight. Phones get an on-screen stick and buttons.
