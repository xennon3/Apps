# Super Store Night

A first-person horror prototype set in a 24-hour big-box store. Walk in from the parking lot, find Aisle 14 (Hardware), survive the blackout, and pick a weapon off the pegboard. Then the store locks down. To get out you need your car keys (a zombie has them; listen for the jingle), the manager's key card from the back office, and power to the front doors from the breaker panel. Once you have all three, the Night Manager comes for you, hunting by your light, and you have to make it to the doors. Don't look back when you leave. You will anyway.

Serve the folder (for example `python3 -m http.server`) and open `index.html`. No build step. Three.js r128 loads from cdnjs, and its GLTFLoader, SkeletonUtils and post-processing passes from jsdelivr; all sound is synthesized with the Web Audio API: HRTF 3D panning, a convolution reverb shaped like a big store, and formant-filtered voices for the screams, groans and growls. Headphones recommended.

- `index.html` is the full page.
- Graphics: bloom on the tubes and signs, a film pass (lens fringing, colour grade, vignette, grain), flashlight shadows, glossy scuffed tile, block walls, open joists and ductwork, light shafts under each fixture, drifting dust, price-tag strips and 64 made-up product labels on boxes and cans. Phones get smaller shadow maps and no multisampling.
- `game.html` is the same page without the document wrapper, used when publishing it as a Claude artifact.
- `models/` holds the zombies, fleeing shoppers, props and two weapons as glTF files (saved with a `.json` extension), trimmed from Quaternius' Zombie Apocalypse Kit (CC0). At load the zombies are repainted into grey, bruised, blood-soaked corpses, given pinprick eyes and stretched taller, and their walk, run, crawl, attack, flinch and death animations are driven by the game logic with a stuttering clock. If the models can't load (for example when the page is opened straight from disk), the game falls back to its hand-built figures.

Controls: WASD to move, Shift to run, mouse to look, click to swing (hold with the chainsaw), E to take a weapon, F to toggle the flashlight. Phones get an on-screen stick and buttons.
