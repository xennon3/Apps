// Helpers shared by the browser start page and the phone remote.
window.Browser = (function () {
  function host(url) {
    try { return new URL(url).host.replace(/^www\.|^m\./, ''); } catch (e) { return url; }
  }
  // A stable color per site, for letter tiles when there is no icon.
  function hue(text) {
    let h = 0;
    for (const ch of text) h = (h * 31 + ch.charCodeAt(0)) % 360;
    return `hsl(${h},70%,60%)`;
  }
  // Site icon tile: the real favicon, falling back to the site's first letter offline.
  function icon(url, color, name) {
    const h = host(url), letter = (name || h).replace(/^www\./, '')[0].toUpperCase();
    const src = `https://www.google.com/s2/favicons?domain=${encodeURIComponent(h)}&sz=128`;
    return `<span class="icon" style="--c:${color || hue(h)}"><img src="${src}" alt="" onerror="this.replaceWith(Object.assign(document.createElement('i'),{textContent:'${letter}'}))"></span>`;
  }
  return { host, icon };
})();
