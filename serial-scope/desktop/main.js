// Serial Scope desktop shell: runs index.html in its own window and fills the gaps
// Electron leaves in the web APIs the page uses:
//   - Web Serial has no built-in port chooser, so the page shows its own (select-serial-port)
//   - folders for the OBS overlay are written with Node fs instead of the File System Access API
//   - "Share" runs a small read-only web server so others can watch the scoreboard in a browser
const { app, BrowserWindow, dialog, ipcMain, session, shell } = require('electron');
const fs = require('fs/promises');
const path = require('path');
const http = require('http');
const os = require('os');
const crypto = require('crypto');

if (!app.requestSingleInstanceLock()) app.quit();

let win = null;
let pendingPortChoice = null;   // callback Electron is waiting on

function createWindow() {
  win = new BrowserWindow({
    width: 1360, height: 900, minWidth: 420, minHeight: 500,
    title: 'Serial Scope', backgroundColor: '#0d0f13', autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  win.loadFile(path.join(__dirname, '..', 'index.html'));

  // the overlay pop-up is opened with window.open(); give it a plain window without a menu
  // (share links open in the real browser instead)
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) { shell.openExternal(url); return { action: 'deny' }; }
    return { action: 'allow', overrideBrowserWindowOptions: { autoHideMenuBar: true, title: 'Scoreboard overlay', backgroundColor: '#00ff00' } };
  });
  // links (e.g. in the README) open in the real browser
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); shell.openExternal(url); } });
}

// =====================================================================
//  Network sharing: a read-only web server for the live scoreboard
// =====================================================================
//  GET /            viewer page (scoreboard, game log, status, recent packets)
//  GET /overlay     the OBS overlay, for a Browser Source on another computer
//  GET /events      live updates (Server-Sent Events)
//  GET /api/state   the latest snapshot as JSON
//  Every request needs ?key=<access code> when a code is set. Wrong codes are rate-limited per IP.
const share = { server: null, port: 0, code: '', overlay: '', latest: null, clients: new Set(), fails: new Map() };
let viewerHtml = null;

function shareAddresses() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal || a.address.startsWith('169.254.')) continue;
      const [x, y] = a.address.split('.').map(Number);
      out.push({ address: a.address, name, kind: x === 100 && y >= 64 && y <= 127 ? 'tailscale' : 'lan' });
    }
  }
  return out;
}
const sameText = (a, b) => { const A = Buffer.from(String(a)), B = Buffer.from(String(b)); return A.length === B.length && crypto.timingSafeEqual(A, B); };
const loginPage = wrong => `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Serial Scope</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0d0f13;color:#e8ebf0;font:16px system-ui,sans-serif}
form{background:#161a20;border:1px solid #262b33;border-radius:14px;padding:24px;display:grid;gap:12px;width:min(320px,90vw)}
input,button{font:inherit;padding:10px;border-radius:8px;border:1px solid #333;background:#0d0f13;color:inherit}button{background:#2563eb;border-color:#2563eb;color:#fff;cursor:pointer}
.bad{color:#f87171;font-size:14px}</style></head><body><form method="get"><b>⚡ Serial Scope</b><span>Enter the access code</span>
<input name="key" autocomplete="off" autofocus required>${wrong ? '<span class="bad">That code is not right.</span>' : ''}<button>Watch</button></form></body></html>`;

async function handleShare(req, res) {
  const url = new URL(req.url, 'http://localhost');
  const ip = req.socket.remoteAddress || '?';
  const send = (status, type, body, extra = {}) => {
    res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', ...extra });
    res.end(body);
  };
  if (req.method !== 'GET') return send(405, 'text/plain', 'read-only');
  if (share.code) {
    const now = Date.now(), f = share.fails.get(ip);
    const recent = f && now - f.t < 10 * 60e3 ? f : null;
    if (recent && recent.count >= 20) return send(429, 'text/plain', 'Too many wrong access codes. Try again in 10 minutes.');
    const key = url.searchParams.get('key');
    if (!sameText(key || '', share.code)) {
      if (key) share.fails.set(ip, { count: (recent ? recent.count : 0) + 1, t: recent ? recent.t : now });
      if (url.pathname === '/' || url.pathname === '/overlay') return send(401, 'text/html; charset=utf-8', loginPage(!!key));
      return send(401, 'text/plain', 'access code required');
    }
  }
  switch (url.pathname) {
    case '/':
      viewerHtml ??= await fs.readFile(path.join(__dirname, 'viewer.html'), 'utf8');
      return send(200, 'text/html; charset=utf-8', viewerHtml);
    case '/overlay':
      return send(200, 'text/html; charset=utf-8', share.overlay || 'The overlay is not ready yet.');
    case '/api/state':
      return send(200, 'application/json', JSON.stringify(share.latest || {}));
    case '/events':
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
      res.write('retry: 2000\n\n');
      if (share.latest) res.write(`data: ${JSON.stringify(share.latest)}\n\n`);
      share.clients.add(res);
      req.on('close', () => share.clients.delete(res));
      return;
    default:
      return send(404, 'text/plain', 'not found');
  }
}
async function stopShare() {
  if (!share.server) return;
  const srv = share.server; share.server = null;
  for (const c of share.clients) c.end();
  share.clients.clear();
  srv.closeAllConnections?.();
  await new Promise(r => srv.close(() => r()));
}
ipcMain.handle('share-start', async (_e, opts) => {
  await stopShare();
  share.code = String(opts?.code || '');
  share.overlay = String(opts?.overlay || '');
  share.fails.clear();
  const port = Math.min(65535, Math.max(1, Number(opts?.port) || 8080));
  return new Promise(resolve => {
    const srv = http.createServer((req, res) => handleShare(req, res).catch(e => { try { res.writeHead(500); res.end(String(e.message)); } catch {} }));
    srv.once('error', err => resolve({ ok: false, error: err.code === 'EADDRINUSE' ? `Port ${port} is already in use. Pick another port.` : err.message }));
    srv.listen(port, '0.0.0.0', () => { share.server = srv; share.port = port; resolve({ ok: true, port, addresses: shareAddresses() }); });
  });
});
ipcMain.handle('share-stop', () => stopShare());
ipcMain.handle('share-info', () => ({ running: !!share.server, port: share.port, viewers: share.clients.size, addresses: shareAddresses() }));
ipcMain.on('share-state', (_e, state) => {
  share.latest = state;
  if (!share.clients.size) return;
  const msg = `data: ${JSON.stringify(state)}\n\n`;
  for (const c of share.clients) c.write(msg);
});

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

app.whenReady().then(() => {
  const ses = session.defaultSession;

  ses.on('select-serial-port', (event, portList, webContents, callback) => {
    event.preventDefault();
    if (pendingPortChoice) pendingPortChoice('');
    pendingPortChoice = callback;
    webContents.send('serial-ports', portList.map(p => ({
      portId: p.portId, portName: p.portName, displayName: p.displayName,
      vendorId: p.vendorId, productId: p.productId,
    })));
  });
  ipcMain.on('serial-port-chosen', (_e, portId) => {
    if (pendingPortChoice) { pendingPortChoice(portId || ''); pendingPortChoice = null; }
  });
  // the page only asks for serial access when the user clicks Connect, so allow it
  ses.setPermissionCheckHandler((_wc, permission) => permission === 'serial' || permission === 'fullscreen' || permission === 'clipboard-sanitized-write');
  ses.setDevicePermissionHandler(details => details.deviceType === 'serial');

  ipcMain.handle('pick-folder', async () => {
    const r = await dialog.showOpenDialog(win, {
      title: 'Choose a folder for the OBS overlay files',
      properties: ['openDirectory', 'createDirectory', 'promptToCreate'],
    });
    return r.canceled ? null : r.filePaths[0];
  });
  ipcMain.handle('write-file', async (_e, dir, name, text) => {
    if (typeof dir !== 'string' || typeof name !== 'string' || name !== path.basename(name)) throw new Error('bad file name');
    const target = path.join(dir, name), tmp = target + '.tmp';
    await fs.writeFile(tmp, String(text), 'utf8');
    await fs.rename(tmp, target);   // atomic swap so OBS never reads a half-written file
  });
  ipcMain.handle('folder-exists', async (_e, dir) => {
    try { return (await fs.stat(dir)).isDirectory(); } catch { return false; }
  });
  ipcMain.handle('open-folder', (_e, dir) => shell.openPath(dir));

  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => app.quit());
