// Shared shell for Dashplay games: start, pause and game-over cards, scores and best
// scores, input from car taps and the phone gamepad, sound, and the render loop.
//
//   const game = Arcade.create({
//     id: 'lane-racer',            // matches kit/games.js; title, emoji and color come from there
//     canvas: true,                // false gives a plain <div> in game.stage instead
//     keys: [['◀ ▶', 'change lane']],
//     start() {},                  // reset for a new round
//     tap(x, y) {},                // a tap while playing (CSS pixels)
//     key(k) {},                   // 'up' 'down' 'left' 'right' 'a' 'b' while playing
//     update(dt, t) {},            // seconds since the last frame, while playing
//     draw(g, W, H, t) {},         // every frame, canvas games only
//     resume() {},                 // after the pause card closes
//   });
//   game.add(1); game.set(10); game.end('Crashed!');
(function () {
  const GAMES = window.DASHPLAY_GAMES || [];
  const KEYS = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    w: 'up', s: 'down', a: 'left', d: 'right', ' ': 'a', Enter: 'b', Escape: 'pause',
  };

  const store = {
    get(k) { try { return JSON.parse(localStorage.getItem('dashplay.' + k)); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem('dashplay.' + k, JSON.stringify(v)); } catch (e) {} },
  };

  // Tiny synth so games need no audio files.
  let audio = null, muted = !!store.get('muted');
  const SOUNDS = {
    tap: [520, 760, 0.06, 'sine', 0.18],
    point: [660, 1320, 0.1, 'triangle', 0.2],
    big: [440, 1760, 0.22, 'triangle', 0.22],
    flip: [300, 420, 0.05, 'square', 0.06],
    bad: [220, 90, 0.25, 'sawtooth', 0.14],
    over: [440, 110, 0.6, 'sawtooth', 0.14],
    start: [330, 990, 0.18, 'triangle', 0.2],
  };
  function sfx(name, pitch = 1) {
    if (muted) return;
    const s = SOUNDS[name] || (typeof name === 'number' ? [name, name, 0.25, 'sine', 0.2] : null);
    if (!s) return;
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)();
      if (audio.state === 'suspended') audio.resume();
      const t = audio.currentTime, o = audio.createOscillator(), v = audio.createGain();
      o.type = s[3];
      o.frequency.setValueAtTime(s[0] * pitch, t);
      o.frequency.exponentialRampToValueAtTime(s[1] * pitch, t + s[2]);
      v.gain.setValueAtTime(s[4], t);
      v.gain.exponentialRampToValueAtTime(0.0001, t + s[2]);
      o.connect(v).connect(audio.destination);
      o.start(t); o.stop(t + s[2] + 0.02);
    } catch (e) {}
  }

  function el(tag, cls, html) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function create(opts) {
    const meta = GAMES.find(g => g.id === opts.id) || { title: opts.id, emoji: '🎮', color: '#FFC24B', blurb: '' };
    const format = opts.format || (n => String(Math.round(n)));
    const lowerIsBetter = !!opts.lowerIsBetter;
    document.title = meta.title;
    document.documentElement.style.setProperty('--accent', meta.color);

    const stage = el('div'); stage.id = 'stage';
    document.body.appendChild(stage);
    let canvas = null, g = null;
    if (opts.canvas !== false) {
      canvas = el('canvas');
      stage.appendChild(canvas);
      g = canvas.getContext('2d');
    }

    const hud = el('div', 'hud', `
      <button class="chip" data-act="home" aria-label="All games">‹ Games</button>
      <div class="score num"><b>0</b><span></span></div>
      <div class="spacer"></div>
      <button class="chip icon" data-act="mute" aria-label="Sound"></button>
      <button class="chip icon" data-act="pause" aria-label="Pause">❚❚</button>`);
    document.body.appendChild(hud);
    const scoreEl = hud.querySelector('.score b'), bestEl = hud.querySelector('.score span');
    const muteBtn = hud.querySelector('[data-act=mute]'), pauseBtn = hud.querySelector('[data-act=pause]');

    const overlay = el('div', 'overlay');
    document.body.appendChild(overlay);

    const game = {
      meta, stage, canvas, g, W: 0, H: 0, score: 0, state: 'ready', sfx, store,
      best: store.get('best.' + opts.id),
      add(n) { game.set(game.score + n); },
      set(n) { game.score = n; scoreEl.textContent = format(n); },
      end, float, start, resize,
    };

    function renderBest() {
      bestEl.textContent = game.best == null ? '' : 'best ' + format(game.best);
    }
    function renderMute() { muteBtn.textContent = muted ? '🔇' : '🔊'; }
    renderBest(); renderMute(); game.set(0);

    // Cards. Gamepad: A takes the primary button, B the secondary.
    let primary = null, secondary = null;
    function card({ big, title, sub, ribbon, primaryLabel, secondaryLabel, onPrimary, onSecondary }) {
      overlay.innerHTML = '';
      const c = el('div', 'card');
      c.append(el('div', 'badge', meta.emoji));
      const head = el('div');
      if (big != null) head.append(el('div', 'big num', big));
      head.append(el('h1', '', title));
      if (sub) head.append(el('p', 'sub', sub));
      if (ribbon) head.append(el('span', 'ribbon', ribbon));
      c.append(head);
      if (opts.keys && game.state === 'ready') {
        const k = el('div', 'keys');
        opts.keys.forEach(([key, what]) => k.append(el('span', '', `<kbd>${key}</kbd>${what}`)));
        c.append(k);
      }
      const row = el('div', 'row');
      const b1 = el('button', 'btn', secondaryLabel), b2 = el('button', 'btn primary', primaryLabel);
      b1.onclick = onSecondary; b2.onclick = onPrimary;
      row.append(b1, b2);
      c.append(row);
      overlay.append(c);
      overlay.classList.add('show');
      primary = onPrimary; secondary = onSecondary;
    }
    function hideCard() { overlay.classList.remove('show'); primary = secondary = null; }

    // On the phone (the remote's WebView) games go back to the remote; on the car, to the home screen.
    function home() { location.href = window.DashplayRemote ? '../remote.html' : '../index.html'; }

    function showReady() {
      game.state = 'ready';
      card({ title: meta.title, sub: meta.blurb, primaryLabel: '▶ Play', secondaryLabel: 'Games',
        onPrimary: start, onSecondary: home });
    }

    function start() {
      hideCard();
      game.set(0);
      game.state = 'playing';
      store.set('last', opts.id);
      opts.start && opts.start();
      sfx('start');
      last = performance.now();
    }

    function pause() {
      if (game.state !== 'playing') return;
      game.state = 'paused';
      card({ title: 'Paused', sub: meta.title, primaryLabel: '▶ Resume', secondaryLabel: 'Games',
        onPrimary: () => { hideCard(); game.state = 'playing'; last = performance.now(); opts.resume && opts.resume(); },
        onSecondary: home });
    }

    function end(line, opt = {}) {
      if (game.state !== 'playing') return;
      game.state = 'over';
      const s = game.score;
      const counts = opt.counts !== false;
      const better = counts && (game.best == null || (lowerIsBetter ? s < game.best : s > game.best));
      if (better) {
        game.best = s; renderBest();
        store.set('best.' + opts.id, s);
        store.set('bestText.' + opts.id, format(s)); // shown on the home screen
      }
      sfx(better ? 'big' : 'over');
      setTimeout(() => card({
        big: counts ? format(s) : null, title: line,
        sub: better ? '' : (game.best != null ? 'Best: ' + format(game.best) : ''),
        ribbon: better && s ? 'NEW BEST' : '', primaryLabel: '↻ Again', secondaryLabel: 'Games',
        onPrimary: start, onSecondary: home,
      }), opt.delay ?? 450);
    }

    // Floating labels: drawn on the canvas for canvas games, as DOM for the rest.
    const floats = [];
    function float(text, x, y, color = '#fff') {
      if (canvas) { floats.push({ text, x, y, color, t: performance.now() }); return; }
      const f = el('div', 'float', text);
      f.style.left = x + 'px'; f.style.top = y + 'px'; f.style.color = color;
      document.body.append(f);
      setTimeout(() => f.remove(), 800);
    }

    function resize() {
      game.W = innerWidth; game.H = innerHeight;
      if (canvas) {
        const d = window.devicePixelRatio || 1;
        canvas.width = game.W * d; canvas.height = game.H * d;
        g.setTransform(d, 0, 0, d, 0, 0);
      }
      opts.resize && opts.resize(game.W, game.H);
    }
    addEventListener('resize', resize);

    hud.addEventListener('click', e => {
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'home') home();
      if (act === 'mute') { muted = !muted; store.set('muted', muted); renderMute(); }
      if (act === 'pause') game.state === 'paused' ? primary && primary() : pause();
    });

    stage.addEventListener('pointerdown', e => {
      if (game.state !== 'playing' || !opts.tap) return;
      e.preventDefault();
      opts.tap(e.clientX, e.clientY, e);
    });

    addEventListener('keydown', e => {
      const k = KEYS[e.key] || KEYS[e.code] || (e.code === 'Space' ? 'a' : null);
      if (!k) return;
      e.preventDefault();
      if (k === 'pause') return pause();
      if (game.state === 'playing') { opts.key && opts.key(k); return; }
      if (k === 'a' && primary) primary();
      else if (k === 'b' && secondary) secondary();
    });

    // Pause when the page is hidden (phone call, screen change, driving lock).
    document.addEventListener('visibilitychange', () => { if (document.hidden) pause(); });

    let last = performance.now();
    function frame(t) {
      const dt = Math.min(0.05, (t - last) / 1000); last = t;
      if (game.state === 'playing' && opts.update) opts.update(dt, t);
      if (canvas) {
        g.clearRect(0, 0, game.W, game.H);
        opts.draw && opts.draw(g, game.W, game.H, t);
        for (let i = floats.length - 1; i >= 0; i--) {
          const f = floats[i], k = (t - f.t) / 800;
          if (k >= 1) { floats.splice(i, 1); continue; }
          g.globalAlpha = 1 - k; g.fillStyle = f.color;
          g.font = `800 ${Math.max(18, game.H * 0.045)}px system-ui`;
          g.textAlign = 'center'; g.textBaseline = 'middle';
          g.fillText(f.text, f.x, f.y - k * game.H * 0.08);
          g.globalAlpha = 1;
        }
      }
      requestAnimationFrame(frame);
    }

    resize();
    showReady();
    requestAnimationFrame(frame);
    return game;
  }

  // Helpers games share.
  // Touch delay. A tap on the car screen reaches the phone late (Android Auto, wireless link,
  // and the video of the screen travelling back). Fast games rewind to where things were when
  // the player actually touched. "Touch timing" measures it; until then, a typical delay.
  // DashplayCar only exists on the car screen, so phone play has no delay.
  const onCar = !!window.DashplayCar;
  const measured = store.get('touchLag');
  const lag = onCar ? (typeof measured === 'number' ? measured : 0.35) : 0;

  // Remembers a value over the last couple of seconds, so a late tap can look it up.
  //   const t = Arcade.trail(); t.push(now, {x, y}); t.at(now - Arcade.lag * 1000)
  function trail(keepMs = 2000) {
    const pts = [];
    return {
      push(t, v) { pts.push([t, v]); while (pts.length && t - pts[0][0] > keepMs) pts.shift(); },
      // The value at time t (the latest one recorded at or before it).
      at(t) {
        for (let i = pts.length - 1; i >= 0; i--) if (pts[i][0] <= t) return pts[i][1];
        return pts.length ? pts[0][1] : undefined;
      },
      between(t0, t1) { return pts.filter(p => p[0] >= t0 && p[0] <= t1).map(p => p[1]); },
      clear() { pts.length = 0; },
    };
  }

  const rand = (a, b) => a + Math.random() * (b - a);
  const pick = list => list[Math.floor(Math.random() * list.length)];
  function roundRect(g, x, y, w, h, r) { g.beginPath(); g.roundRect(x, y, w, h, r); }
  function emoji(g, ch, x, y, size) {
    g.font = `${size}px "Noto Color Emoji", "Apple Color Emoji", "Segoe UI Emoji", sans-serif`;
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText(ch, x, y);
  }

  window.Arcade = { create, sfx, store, rand, pick, roundRect, emoji, trail, lag, onCar, games: GAMES };
})();
