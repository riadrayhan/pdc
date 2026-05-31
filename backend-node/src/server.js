/**
 * RR Locker API — Node.js entry point.
 *
 * Express REST API (ported from FastAPI) + Socket.IO realtime layer
 * (replaces the old WebSocket hubs) + WebRTC signaling for live screen/audio.
 * Serves the built Vite dashboard as a SPA and exposes the same /api/v1 surface
 * the Android app and dashboard already use.
 */
process.env.TZ = 'UTC';

import http from 'http';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import express from 'express';
import cors from 'cors';
import morgan from 'morgan';

import { settings } from './config.js';
import { sequelize, syncTables, User } from './models/index.js';
import { getPasswordHash } from './utils/security.js';
import { initFirebase } from './services/fcmService.js';
import { initRealtime } from './services/realtime.js';
import { initStreamBridge } from './services/streamBridge.js';
import { startCron } from './services/cron.js';

import registerAuthRoutes from './routes/auth.js';
import registerDeviceRoutes from './routes/devices.js';
import registerCustomerRoutes from './routes/customers.js';
import registerEmiRoutes from './routes/emi.js';
import registerCommandRoutes from './routes/commands.js';
import registerUserRoutes from './routes/users.js';
import registerEnrollmentRoutes from './routes/enrollment.js';
import registerAppDistributionRoutes from './routes/appDistribution.js';
import registerAmapiRoutes from './routes/amapi.js';
import registerMetadataRoutes from './routes/metadata.js';
import registerScreenRoutes from './routes/screen.js';
import registerFileRoutes from './routes/files.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DASHBOARD_DIR = path.resolve(__dirname, '..', 'static');

const app = express();

// ── Middleware ───────────────────────────────────────────
app.use(cors({ origin: settings.CORS_ORIGINS, credentials: true }));
app.use(express.json({ limit: '25mb' }));
app.use(express.urlencoded({ extended: true, limit: '25mb' }));
if (settings.DEBUG) app.use(morgan('dev'));

// ── API router (/api/v1) ─────────────────────────────────
const api = express.Router();
registerAuthRoutes(api);
registerDeviceRoutes(api);
registerCustomerRoutes(api);
registerEmiRoutes(api);
registerCommandRoutes(api);
registerUserRoutes(api);
registerEnrollmentRoutes(api);
registerAppDistributionRoutes(api);
registerAmapiRoutes(api);
registerMetadataRoutes(api);
registerScreenRoutes(api);
registerFileRoutes(api);
app.use('/api/v1', api);

// ── Health ───────────────────────────────────────────────
app.get('/health', (req, res) => res.json({ status: 'healthy' }));
app.get('/api/v1/health', (req, res) => res.json({ status: 'healthy' }));

// ── Dashboard SPA ────────────────────────────────────────
const NO_CACHE = {
  'Cache-Control': 'no-cache, no-store, must-revalidate',
  Pragma: 'no-cache',
  Expires: '0',
};

function serveIndex(res) {
  res.set(NO_CACHE);
  res.sendFile(path.join(DASHBOARD_DIR, 'index.html'));
}

if (fs.existsSync(DASHBOARD_DIR)) {
  app.use('/assets', express.static(path.join(DASHBOARD_DIR, 'assets')));
  app.get('/', (req, res) => serveIndex(res));
  // SPA catch-all (Express 4 wildcard) — serve real files, else index.html.
  app.get(/^\/(?!api\/|assets\/|health$).*/, (req, res) => {
    const filePath = path.join(DASHBOARD_DIR, req.path);
    if (
      fs.existsSync(filePath)
      && fs.statSync(filePath).isFile()
      && path.basename(filePath) !== 'index.html'
    ) {
      return res.sendFile(filePath);
    }
    return serveIndex(res);
  });
} else {
  app.get('/', (req, res) => res.json({
    app: settings.APP_NAME,
    version: settings.VERSION,
    status: 'running',
  }));
  console.warn(`Dashboard static files not found at ${DASHBOARD_DIR}`);
}

// ── Error handler (mirrors FastAPI {detail} bodies) ──────
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  const status = err.status || err.statusCode || 500;
  if (status >= 500) console.error(err);
  res.status(status).json({ detail: err.detail ?? err.message ?? 'Internal Server Error' });
});

// ── Startup helpers ──────────────────────────────────────
async function createDefaultAdmin() {
  try {
    const existing = await User.findOne({ where: { username: 'admin' } });
    if (!existing) {
      await User.create({
        email: 'admin@emilocker.com',
        username: 'admin',
        hashed_password: getPasswordHash('admin123'),
        full_name: 'Administrator',
        role: 'admin',
        is_active: true,
      });
      console.log('Default admin user created: admin / admin123');
    } else {
      console.log('Admin user already exists');
    }
  } catch (e) {
    console.error('Error creating admin user:', e.message);
  }
}

function startKeepAlive() {
  const externalUrl = settings.APP_EXTERNAL_URL;
  if (!externalUrl) {
    console.log('APP_EXTERNAL_URL not set — keep-alive disabled');
    return;
  }
  const healthUrl = `${externalUrl}/health`;
  console.log(`Keep-alive started: pinging ${healthUrl} every 12 minutes`);
  const ping = () => {
    try {
      const mod = healthUrl.startsWith('https') ? 'https' : 'http';
      import(mod).then((m) => {
        m.get(healthUrl, (r) => { r.resume(); }).on('error', () => {});
      });
    } catch { /* ignore */ }
  };
  setTimeout(() => { ping(); setInterval(ping, 12 * 60 * 1000); }, 120 * 1000);
}

async function main() {
  console.log('Starting RR Locker API...');

  try {
    await sequelize.authenticate();
    await syncTables();
    console.log('Database tables created/verified');
  } catch (e) {
    console.error('Database initialization error:', e.message);
  }

  await createDefaultAdmin();

  try {
    initFirebase();
  } catch (e) {
    console.warn('Firebase initialization warning:', e.message);
  }

  const server = http.createServer(app);
  const io = initRealtime(server);
  initStreamBridge(server, io);

  try {
    startCron();
  } catch (e) {
    console.warn('Cron scheduler warning:', e.message);
  }

  startKeepAlive();

  server.listen(settings.PORT, '0.0.0.0', () => {
    console.log(`RR Locker API listening on :${settings.PORT}`);
  });
}

main();
