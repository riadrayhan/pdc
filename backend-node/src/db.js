// Force UTC so naive-UTC timestamps written by the old Python backend are read
// back without timezone drift. MUST run before any Date parsing.
process.env.TZ = 'UTC';

import fs from 'fs';
import path from 'path';
import { Sequelize } from 'sequelize';
import { settings } from './config.js';

/**
 * Percent-encode username/password in a SQL connection URL so special chars in a
 * Supabase/Neon password (@ : / # ? …) don't break URL parsing. Mirrors the
 * Python `_sanitize_db_url`.
 */
function sanitizeDbUrl(url) {
  const m = url.match(/^([^:]+):\/\/(.+)$/);
  if (!m || !m[2].includes('@')) return url;
  const scheme = m[1];
  const body = m[2];
  const at = body.lastIndexOf('@');
  const userinfo = body.slice(0, at);
  const host = body.slice(at + 1);
  if (!userinfo.includes(':')) return url;
  const colon = userinfo.indexOf(':');
  const user = userinfo.slice(0, colon);
  const password = userinfo.slice(colon + 1);
  return `${scheme}://${encodeURIComponent(user)}:${encodeURIComponent(password)}@${host}`;
}

function buildSequelize() {
  let dbUrl = settings.DATABASE_URL;

  if (dbUrl.startsWith('postgres://')) {
    dbUrl = dbUrl.replace('postgres://', 'postgresql://');
  }

  // ── SQLite ──────────────────────────────────────────
  if (dbUrl.includes('sqlite')) {
    // Strip the SQLAlchemy "sqlite:///" prefix to a filesystem path.
    let filePath = dbUrl.replace(/^sqlite:\/\/\//, '').replace(/^sqlite:\/\//, '');
    if (filePath.startsWith('./')) filePath = filePath.slice(2);

    // Relocate the default relative DB onto a persistent /data mount when present
    // (Hugging Face Spaces / many PaaS), so data survives container rebuilds.
    if (filePath === 'emi_locker.db' || filePath === './emi_locker.db') {
      const persistentDir = '/data';
      try {
        if (fs.existsSync(persistentDir)) {
          fs.accessSync(persistentDir, fs.constants.W_OK);
          filePath = path.join(persistentDir, 'emi_locker.db');
        }
      } catch {
        /* not writable → keep local path */
      }
    }

    return new Sequelize({
      dialect: 'sqlite',
      storage: filePath,
      logging: false,
      define: { timestamps: false },
    });
  }

  // ── Postgres / MySQL ────────────────────────────────
  const sanitized = sanitizeDbUrl(dbUrl);
  const dialect = sanitized.startsWith('mysql') ? 'mysql' : 'postgres';
  return new Sequelize(sanitized, {
    dialect,
    logging: false,
    timezone: '+00:00',
    pool: { max: 10, min: 0, idle: 10000 },
    dialectOptions:
      dialect === 'postgres'
        ? { keepAlive: true, ...(process.env.PGSSL ? { ssl: { require: true, rejectUnauthorized: false } } : {}) }
        : {},
    define: { timestamps: false },
  });
}

export const sequelize = buildSequelize();

export default sequelize;
