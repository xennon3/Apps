// Serial Scope desktop shell: runs index.html in its own window and fills the gaps
// Electron leaves in the web APIs the page uses:
//   - Web Serial has no built-in port chooser, so the page shows its own (select-serial-port)
//   - folders for the OBS overlay are written with Node fs instead of the File System Access API
const { app, BrowserWindow, dialog, ipcMain, session, shell } = require('electron');
const fs = require('fs/promises');
const path = require('path');

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
  win.webContents.setWindowOpenHandler(() => ({
    action: 'allow',
    overrideBrowserWindowOptions: { autoHideMenuBar: true, title: 'Scoreboard overlay', backgroundColor: '#00ff00' },
  }));
  // links (e.g. in the README) open in the real browser
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); shell.openExternal(url); } });
}

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
