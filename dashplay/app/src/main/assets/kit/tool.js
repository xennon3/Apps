// Shared top bar for store tools: a home button and the tool's name.
//   <script src="/assets/kit/tool.js"></script>
//   Tool.header('Calculator', '#4C8DF0');
window.Tool = (function () {
  // On the phone (the remote's WebView) tools go back to the remote; on the car, to home.
  function home() { location.href = window.DashplayRemote ? '/assets/remote.html' : '/assets/index.html'; }

  function header(title, color) {
    if (color) document.documentElement.style.setProperty('--accent', color);
    document.title = title;
    const bar = document.createElement('div');
    bar.className = 'hud tool-hud';
    bar.innerHTML = `<button class="chip" aria-label="Home">‹ Home</button><b class="tool-title"></b><div class="spacer"></div>`;
    bar.querySelector('b').textContent = title;
    bar.querySelector('button').onclick = home;
    document.body.prepend(bar);
    return bar;
  }

  // Small beep for alarms and button presses (no audio files needed).
  let audio = null;
  function beep(freq = 880, seconds = 0.15, volume = 0.25) {
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)();
      if (audio.state === 'suspended') audio.resume();
      const t = audio.currentTime, o = audio.createOscillator(), v = audio.createGain();
      o.frequency.value = freq; o.type = 'sine';
      v.gain.setValueAtTime(volume, t); v.gain.exponentialRampToValueAtTime(0.0001, t + seconds);
      o.connect(v).connect(audio.destination); o.start(t); o.stop(t + seconds + 0.02);
    } catch (e) {}
  }

  const store = {
    get(k, d) { try { const v = localStorage.getItem('dashplay.tool.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('dashplay.tool.' + k, JSON.stringify(v)); } catch (e) {} },
  };

  return { header, home, beep, store };
})();
