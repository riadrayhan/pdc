/**
 * Realtime layer built on Socket.IO. Replaces the FastAPI WebSocket hubs:
 *
 *  1. Command wake — a device joins room `cmd:<deviceId>`; when the admin issues
 *     a command the server emits `command` so the device fetches instantly
 *     (the 2s poll remains as fallback).
 *
 *  2. WebRTC signaling — for live screen/audio. A device (publisher) and one or
 *     more dashboards (viewers) exchange SDP offers/answers and ICE candidates
 *     through rooms `rtc:<deviceId>`. Media flows peer-to-peer (or via TURN),
 *     never through this server.
 *
 *  3. Legacy frame relay — `screen-frame` / `audio-frame` binary events are
 *     still relayed device→viewers so older app builds keep working during the
 *     WebRTC rollout.
 *
 * Namespaces:
 *   /device     — authenticated-by-deviceId connections from Android app
 *   /dashboard  — browser dashboards (viewers / command issuers)
 */

import { Server } from 'socket.io';
import { settings } from '../config.js';

let io = null;

// Cache the last announced audio format per device so late viewers can sync,
// matching the old backend's behaviour.
const audioFormat = new Map();

// ── External (WebSocket bridge) hooks ────────────────────
// The Android app streams + receives command wakes over raw WebSocket. The
// streamBridge registers these so command wake, presence and the file-manager
// relay also reach WS-connected devices without a circular import.
let externalWake = null;
let externalDeviceOnline = null;
let externalFileRequest = null;
let externalStreamStatus = null;
export function setExternalWake(fn) { externalWake = fn; }
export function setExternalDeviceOnline(fn) { externalDeviceOnline = fn; }
export function setExternalFileRequest(fn) { externalFileRequest = fn; }
export function setExternalStreamStatus(fn) { externalStreamStatus = fn; }

export function getIo() {
  return io;
}

/** Wake every device socket in the command room (instant command pickup). */
export function wakeDevice(deviceId) {
  if (io) io.of('/device').to(`cmd:${deviceId}`).emit('command', { event: 'command' });
  // Also wake devices connected over the raw-WebSocket bridge (Android app).
  if (externalWake) {
    try { externalWake(deviceId); } catch { /* best effort */ }
  }
}

/** True when at least one device socket is connected for this id. */
export async function isDeviceOnline(deviceId) {
  if (io) {
    const sockets = await io.of('/device').in(`cmd:${deviceId}`).fetchSockets();
    if (sockets.length > 0) return true;
  }
  if (externalDeviceOnline) {
    try { return !!externalDeviceOnline(deviceId); } catch { /* ignore */ }
  }
  return false;
}

/**
 * Stream status for a device. With WebRTC the publisher (device) socket being
 * connected means it can stream video/audio on demand, so both flags mirror
 * the device's online state — matching the old hub's has_producer semantics
 * closely enough for the dashboard's UI.
 */
export async function getStreamStatus(deviceId) {
  if (externalStreamStatus) {
    try {
      const s = externalStreamStatus(deviceId);
      if (s && (s.video_streaming || s.audio_streaming)) return s;
    } catch { /* ignore */ }
  }
  const online = await isDeviceOnline(deviceId);
  return { video_streaming: online, audio_streaming: online };
}

export function initRealtime(httpServer) {
  io = new Server(httpServer, {
    cors: { origin: settings.CORS_ORIGINS, credentials: true },
    maxHttpBufferSize: 1e8, // 100 MB — allow large screen frames / file chunks
    pingInterval: 25000,
    pingTimeout: 60000,
  });

  // ── Device namespace ─────────────────────────────────
  const deviceNs = io.of('/device');
  deviceNs.on('connection', (socket) => {
    const deviceId = String(socket.handshake.query.device_id || socket.handshake.auth.device_id || '');
    if (!deviceId) {
      socket.disconnect(true);
      return;
    }
    socket.data.deviceId = deviceId;
    socket.join(`cmd:${deviceId}`);
    socket.join(`rtc:${deviceId}`);
    socket.data.role = 'device';

    // Tell any waiting viewers the publisher is here.
    io.of('/dashboard').to(`rtc:${deviceId}`).emit('publisher-online', { device_id: deviceId });

    // ── WebRTC signaling (device = publisher) ──────────
    socket.on('webrtc-offer', (data) => {
      io.of('/dashboard').to(`rtc:${deviceId}`).emit('webrtc-offer', { device_id: deviceId, ...data });
    });
    socket.on('webrtc-answer', (data) => {
      // answer targeted at a specific viewer socket id
      if (data && data.target) {
        io.of('/dashboard').to(data.target).emit('webrtc-answer', { device_id: deviceId, ...data });
      } else {
        io.of('/dashboard').to(`rtc:${deviceId}`).emit('webrtc-answer', { device_id: deviceId, ...data });
      }
    });
    socket.on('webrtc-ice', (data) => {
      if (data && data.target) {
        io.of('/dashboard').to(data.target).emit('webrtc-ice', { device_id: deviceId, ...data });
      } else {
        io.of('/dashboard').to(`rtc:${deviceId}`).emit('webrtc-ice', { device_id: deviceId, ...data });
      }
    });

    // ── Legacy frame relay (binary) ────────────────────
    socket.on('screen-frame', (frame) => {
      io.of('/dashboard').to(`screen:${deviceId}`).emit('screen-frame', frame);
    });
    socket.on('audio-format', (fmt) => {
      audioFormat.set(deviceId, fmt);
      io.of('/dashboard').to(`audio:${deviceId}`).emit('audio-format', fmt);
    });
    socket.on('audio-frame', (frame) => {
      io.of('/dashboard').to(`audio:${deviceId}`).emit('audio-frame', frame);
    });

    // ── Remote file-manager relay (device = producer) ──
    socket.on('file-message', (text) => {
      io.of('/dashboard').to(`files:${deviceId}`).emit('file-message', text);
    });

    socket.on('disconnect', () => {
      io.of('/dashboard').to(`rtc:${deviceId}`).emit('publisher-offline', { device_id: deviceId });
      io.of('/dashboard').to(`files:${deviceId}`).emit('file-message', '{"event":"device_offline"}');
      audioFormat.delete(deviceId);
    });
  });

  // ── Dashboard namespace ──────────────────────────────
  const dashboardNs = io.of('/dashboard');
  dashboardNs.on('connection', (socket) => {
    socket.data.role = 'viewer';

    // A viewer subscribes to a device's streams / signaling.
    socket.on('watch', async ({ device_id: deviceId, mode } = {}) => {
      if (!deviceId) return;
      socket.join(`rtc:${deviceId}`);
      if (mode === 'screen' || mode === 'both') socket.join(`screen:${deviceId}`);
      if (mode === 'audio' || mode === 'both') socket.join(`audio:${deviceId}`);

      // Replay cached audio format so the viewer can init its audio context.
      const fmt = audioFormat.get(deviceId);
      if (fmt) socket.emit('audio-format', fmt);

      // Ask the publisher (if connected) to (re)negotiate for this viewer.
      const online = await isDeviceOnline(deviceId);
      socket.emit(online ? 'publisher-online' : 'publisher-offline', { device_id: deviceId });
      if (online) {
        io.of('/device').to(`rtc:${deviceId}`).emit('viewer-join', { viewer: socket.id, device_id: deviceId });
      }
    });

    socket.on('unwatch', ({ device_id: deviceId } = {}) => {
      if (!deviceId) return;
      socket.leave(`rtc:${deviceId}`);
      socket.leave(`screen:${deviceId}`);
      socket.leave(`audio:${deviceId}`);
    });

    // ── WebRTC signaling (viewer side) ─────────────────
    socket.on('webrtc-offer', ({ device_id: deviceId, ...rest } = {}) => {
      if (!deviceId) return;
      io.of('/device').to(`rtc:${deviceId}`).emit('webrtc-offer', { viewer: socket.id, ...rest });
    });
    socket.on('webrtc-answer', ({ device_id: deviceId, ...rest } = {}) => {
      if (!deviceId) return;
      io.of('/device').to(`rtc:${deviceId}`).emit('webrtc-answer', { viewer: socket.id, ...rest });
    });
    socket.on('webrtc-ice', ({ device_id: deviceId, ...rest } = {}) => {
      if (!deviceId) return;
      io.of('/device').to(`rtc:${deviceId}`).emit('webrtc-ice', { viewer: socket.id, ...rest });
    });

    // ── Remote file-manager (viewer = admin) ──
    socket.on('watch-files', async ({ device_id: deviceId } = {}) => {
      if (!deviceId) return;
      socket.join(`files:${deviceId}`);
      const online = await isDeviceOnline(deviceId);
      socket.emit('file-message', online ? '{"event":"device_online"}' : '{"event":"device_offline"}');
    });

    socket.on('unwatch-files', ({ device_id: deviceId } = {}) => {
      if (deviceId) socket.leave(`files:${deviceId}`);
    });

    socket.on('file-request', async ({ device_id: deviceId, text } = {}) => {
      if (!deviceId) return;
      // Prefer a Socket.IO device; fall back to the raw-WebSocket bridge.
      const sockets = io ? await io.of('/device').in(`cmd:${deviceId}`).fetchSockets() : [];
      if (sockets.length > 0) {
        io.of('/device').to(`cmd:${deviceId}`).emit('file-request', text);
      } else if (externalFileRequest && externalFileRequest(deviceId, text)) {
        // delivered over the WS bridge
      } else {
        socket.emit('file-message', '{"event":"device_offline"}');
      }
    });
  });

  console.log('Socket.IO realtime layer initialized (/device, /dashboard)');
  return io;
}

export default { initRealtime, getIo, wakeDevice, isDeviceOnline, getStreamStatus };
