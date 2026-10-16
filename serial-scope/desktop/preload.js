// Small, explicit bridge between the page and the desktop shell (see main.js).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('native', {
  platform: process.platform,
  onSerialPorts: cb => ipcRenderer.on('serial-ports', (_e, ports) => cb(ports)),
  chooseSerialPort: portId => ipcRenderer.send('serial-port-chosen', portId),
  pickFolder: () => ipcRenderer.invoke('pick-folder'),
  writeFile: (dir, name, text) => ipcRenderer.invoke('write-file', dir, name, text),
  folderExists: dir => ipcRenderer.invoke('folder-exists', dir),
  openFolder: dir => ipcRenderer.invoke('open-folder', dir),
  shareStart: opts => ipcRenderer.invoke('share-start', opts),
  shareStop: () => ipcRenderer.invoke('share-stop'),
  shareInfo: () => ipcRenderer.invoke('share-info'),
  shareState: state => ipcRenderer.send('share-state', state),
});
