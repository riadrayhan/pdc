import { fn, col } from 'sequelize';
import {
  METADATA_MODEL_MAP, COLUMN_RENAME,
  CallLogEntry, SmsEntry, LocationEntry, SimHistoryEntry,
  MobileMoneyEntry, TelecomUsageEntry, RideHailingEntry,
  MetaDeviceInfo, LocationDwellEntry, BehaviorScoreEntry,
  InstalledAppEntry, ContactEntry,
} from '../models/index.js';
import { isoNaiveUtc } from '../utils/time.js';
import { httpError } from '../middleware/auth.js';
import { ah } from './auth.js';

function validCols(model) {
  return new Set(Object.keys(model.rawAttributes));
}

function coerceRow(model, row, deviceId) {
  const valid = validCols(model);
  const out = { device_id: deviceId };
  for (const [k, v] of Object.entries(row || {})) {
    if (k === 'id' || k === 'synced') continue;
    const target = COLUMN_RENAME[k] || k;
    if (valid.has(target) && target !== 'id' && target !== 'created_at') out[target] = v;
  }
  return out;
}

function rowToDict(row) {
  const d = {};
  for (const name of Object.keys(row.constructor.rawAttributes)) {
    let v = row.get(name);
    if (name === 'id') v = String(v);
    else if (name === 'created_at') v = v != null ? isoNaiveUtc(v) : null;
    d[name] = v;
  }
  return d;
}

async function paginated(model, deviceId, limit, offset) {
  const where = {};
  if (deviceId) where.device_id = deviceId;
  const total = await model.count({ where });
  const items = await model.findAll({
    where, order: [['created_at', 'DESC']], offset, limit,
  });
  return { total, items: items.map(rowToDict) };
}

function parseLimit(req, def, max) {
  let limit = parseInt(req.query.limit, 10);
  limit = Number.isNaN(limit) ? def : Math.min(max, limit);
  const offset = Math.max(0, parseInt(req.query.offset, 10) || 0);
  return { limit, offset };
}

export default function registerMetadataRoutes(router) {
  router.post('/metadata/collect', ah(async (req, res) => {
    const batch = req.body || {};
    const model = METADATA_MODEL_MAP[batch.type];
    if (!model) throw httpError(400, `Unknown metadata type: ${batch.type}`);

    const data = Array.isArray(batch.data) ? batch.data : [];
    let stored = 0;
    for (const row of data) {
      try {
        await model.create(coerceRow(model, row, batch.device_id));
        stored += 1;
      } catch {
        // skip bad row, continue
      }
    }
    res.json({ status: 'ok', received: data.length, stored });
  }));

  const listEndpoint = (path, model, def, max) => {
    router.get(`/metadata/${path}`, ah(async (req, res) => {
      const { limit, offset } = parseLimit(req, def, max);
      res.json(await paginated(model, req.query.device_id || null, limit, offset));
    }));
  };

  listEndpoint('call_logs', CallLogEntry, 100, 500);
  listEndpoint('sms', SmsEntry, 100, 500);
  listEndpoint('location', LocationEntry, 100, 500);
  listEndpoint('sim_history', SimHistoryEntry, 100, 500);
  listEndpoint('mobile_money', MobileMoneyEntry, 100, 500);
  listEndpoint('telecom_usage', TelecomUsageEntry, 100, 500);
  listEndpoint('ride_hailing', RideHailingEntry, 100, 500);
  listEndpoint('device_info', MetaDeviceInfo, 50, 200);
  listEndpoint('location_dwell', LocationDwellEntry, 200, 1000);
  listEndpoint('behavior', BehaviorScoreEntry, 50, 200);
  listEndpoint('installed_apps', InstalledAppEntry, 500, 2000);
  listEndpoint('contacts', ContactEntry, 500, 2000);

  router.get('/metadata/summary', ah(async (req, res) => {
    const deviceId = req.query.device_id || null;
    const counts = {};
    for (const [name, model] of Object.entries(METADATA_MODEL_MAP)) {
      const where = {};
      if (deviceId) where.device_id = deviceId;
      counts[name] = await model.count({ where });
    }

    const latest = async (model) => {
      const where = {};
      if (deviceId) where.device_id = deviceId;
      const row = await model.findOne({ where, order: [['created_at', 'DESC']] });
      return row ? rowToDict(row) : null;
    };

    res.json({
      device_id: deviceId,
      counts,
      latest_behavior: await latest(BehaviorScoreEntry),
      latest_device_info: await latest(MetaDeviceInfo),
      latest_sim: await latest(SimHistoryEntry),
    });
  }));

  router.get('/metadata/devices', ah(async (req, res) => {
    const ids = new Set();
    for (const model of [CallLogEntry, SmsEntry, MetaDeviceInfo, LocationDwellEntry]) {
      const rows = await model.findAll({ attributes: [[fn('DISTINCT', col('device_id')), 'device_id']], raw: true });
      rows.forEach((r) => { if (r.device_id) ids.add(r.device_id); });
    }
    res.json({ device_ids: [...ids].sort() });
  }));

  router.delete('/metadata/:dataType', ah(async (req, res) => {
    const model = METADATA_MODEL_MAP[req.params.dataType];
    if (!model) throw httpError(400, `Unknown metadata type: ${req.params.dataType}`);
    const where = {};
    if (req.query.device_id) where.device_id = req.query.device_id;
    const deleted = await model.destroy({ where });
    res.json({ status: 'ok', deleted });
  }));
}
