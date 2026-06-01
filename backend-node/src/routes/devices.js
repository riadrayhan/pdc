import { DeviceService } from '../services/deviceService.js';
import { Device, DeviceCommand, EMIContract, EMIPayment, Customer } from '../models/index.js';
import { serializeDevice } from '../utils/serializers.js';
import { isoNaiveUtc, utcNow } from '../utils/time.js';
import { httpError } from '../middleware/auth.js';
import { ah } from './auth.js';
import { Op } from 'sequelize';
import { emitCameraFrame } from '../services/realtime.js';

export default function registerDeviceRoutes(router) {
  // Enroll (NO AUTH) — called from Android app.
  router.post('/devices/enroll', ah(async (req, res) => {
    const data = req.body || {};
    if (data.imei) {
      const existing = await DeviceService.getDeviceByImei(data.imei);
      if (existing) {
        existing.fcm_token = data.fcm_token;
        existing.android_version = data.android_version;
        existing.sdk_version = data.sdk_version;
        existing.is_online = true;
        existing.last_seen = utcNow();
        await existing.save();
        return res.status(201).json(serializeDevice(existing));
      }
    }
    const device = await DeviceService.enrollDevice(data);
    return res.status(201).json(serializeDevice(device));
  }));

  // List (NO AUTH).
  router.get('/devices', ah(async (req, res) => {
    const skip = Math.max(0, parseInt(req.query.skip, 10) || 0);
    let limit = parseInt(req.query.limit, 10);
    limit = Number.isNaN(limit) ? 50 : Math.min(100, Math.max(1, limit));
    const status = req.query.status || null;
    const search = req.query.search || null;
    const [devices, total] = await DeviceService.listDevices({ skip, limit, status, search });
    res.json({ total, devices: devices.map(serializeDevice) });
  }));

  // Get by IMEI (NO AUTH). Registered before :deviceId so "imei" isn't matched as an id.
  router.get('/devices/imei/:imei', ah(async (req, res) => {
    const device = await DeviceService.getDeviceByImei(req.params.imei);
    if (!device) throw httpError(404, 'Device not found');
    res.json(serializeDevice(device));
  }));

  // Devices that were factory reset / re-flashed and need attention.
  // Registered before :deviceId so "factory-reset" isn't matched as an id.
  router.get('/devices/factory-reset', ah(async (req, res) => {
    const devices = await Device.findAll({
      where: { factory_reset_count: { [Op.gt]: 0 } },
      include: [{ model: Customer, as: 'customer' }],
      order: [['last_factory_reset', 'DESC']],
    });
    res.json(devices.map((d) => ({
      ...serializeDevice(d),
      last_factory_reset: d.last_factory_reset ? isoNaiveUtc(d.last_factory_reset) : null,
      customer: d.customer
        ? { id: d.customer.id, name: d.customer.full_name, phone: d.customer.phone }
        : null,
    })));
  }));

  // Get by id (NO AUTH).
  router.get('/devices/:deviceId', ah(async (req, res) => {
    const device = await DeviceService.getDeviceById(req.params.deviceId);
    if (!device) throw httpError(404, 'Device not found');
    res.json(serializeDevice(device));
  }));

  // Update status (NO AUTH).
  router.put('/devices/:deviceId/status', ah(async (req, res) => {
    const body = req.body || {};
    const [device] = await DeviceService.updateDeviceStatus(
      req.params.deviceId, String(body.status).toLowerCase(), null, body.reason ?? null,
    );
    if (!device) throw httpError(404, 'Device not found');
    res.json(serializeDevice(device));
  }));

  // Heartbeat.
  router.post('/devices/heartbeat', ah(async (req, res) => {
    const device = await DeviceService.updateHeartbeat(req.body || {});
    if (!device) throw httpError(404, 'Device not found. Please enroll first.');
    res.json({ status: device.status, message: 'Heartbeat received', device_id: String(device.id) });
  }));

  // Delete device + cascade commands/contracts/payments.
  router.delete('/devices/:deviceId', ah(async (req, res) => {
    const device = await DeviceService.getDeviceById(req.params.deviceId);
    if (!device) throw httpError(404, 'Device not found');

    await DeviceCommand.destroy({ where: { device_id: device.id } });
    const contracts = await EMIContract.findAll({ where: { device_id: device.id } });
    for (const c of contracts) {
      await EMIPayment.destroy({ where: { contract_id: c.id } });
      await c.destroy();
    }
    await device.destroy();
    res.status(204).end();
  }));

  // Report location (Android after GPS_TRACK).
  router.post('/devices/:deviceId/report-location', ah(async (req, res) => {
    const device = await DeviceService.getDeviceById(req.params.deviceId);
    if (!device) throw httpError(404, 'Device not found');
    const loc = req.body || {};
    device.last_latitude = String(loc.latitude ?? '');
    device.last_longitude = String(loc.longitude ?? '');
    device.last_location_time = utcNow();
    device.last_location_address = loc.address ?? '';
    await device.save();
    res.json({ status: 'location_updated' });
  }));

  // Report photo (Android after CAMERA_ON).
  router.post('/devices/:deviceId/report-photo', ah(async (req, res) => {
    const device = await DeviceService.getDeviceById(req.params.deviceId);
    if (!device) throw httpError(404, 'Device not found');
    const p = req.body || {};
    const photoUrl = p.photo_url ?? '';
    device.last_photo_url = photoUrl;
    device.last_photo_time = utcNow();
    // Fire-and-forget DB save — don't block the response
    device.save().catch((e) => console.error('photo save error', e));
    // Push to dashboard instantly via Socket.IO (no polling lag)
    if (photoUrl) {
      emitCameraFrame(req.params.deviceId, photoUrl);
    }
    res.json({ status: 'photo_updated' });
  }));

  // Get last location.
  router.get('/devices/:deviceId/location', ah(async (req, res) => {
    const device = await DeviceService.getDeviceById(req.params.deviceId);
    if (!device) throw httpError(404, 'Device not found');
    res.json({
      latitude: device.last_latitude,
      longitude: device.last_longitude,
      address: device.last_location_address,
      updated_at: device.last_location_time ? isoNaiveUtc(device.last_location_time) : null,
    });
  }));

  // Get last photo.
  router.get('/devices/:deviceId/photo', ah(async (req, res) => {
    const device = await DeviceService.getDeviceById(req.params.deviceId);
    if (!device) throw httpError(404, 'Device not found');
    res.json({
      photo_url: device.last_photo_url,
      camera_active: device.camera_active,
      updated_at: device.last_photo_time ? isoNaiveUtc(device.last_photo_time) : null,
    });
  }));
}
