import crypto from 'crypto';
import { Op } from 'sequelize';
import {
  Device, Customer, EMIContract, EMIPayment,
} from '../models/index.js';
import { settings } from '../config.js';
import { httpError } from '../middleware/auth.js';
import { utcNow } from '../utils/time.js';
import { toEnumName } from '../utils/enums.js';

function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Device identification & re-enrollment after factory reset.
 * Ported from fingerprint_service.py. The overdue/lock logic is implemented
 * against the real EMIPayment schedule (the Python version referenced a
 * non-existent EMIContract.has_overdue_payment attribute).
 */
export const FingerprintService = {
  async findDeviceByFingerprint(req) {
    const tryFind = async (where) => Device.findOne({ where });
    if (req.imei) {
      const d = await tryFind({ imei: req.imei });
      if (d) return d;
    }
    if (req.imei2) {
      const d = await tryFind({ imei2: req.imei2 });
      if (d) return d;
    }
    if (req.persistent_device_id) {
      const d = await tryFind({ persistent_device_id: req.persistent_device_id });
      if (d) return d;
    }
    if (req.serial_number) {
      const d = await tryFind({ serial_number: req.serial_number });
      if (d) return d;
    }
    if (req.android_id) {
      const d = await tryFind({ android_id: req.android_id });
      if (d) return d;
    }
    return null;
  },

  checkNeedsReEnrollment(device, req) {
    if (req.android_id && device.android_id && req.android_id !== device.android_id) return true;
    if (device.status === 'wiped') return true;
    return false;
  },

  async checkShouldLock(device) {
    if (device.status === 'locked') {
      return [true, 'Device locked due to overdue payment. Contact EMI provider.'];
    }
    const contract = await EMIContract.findOne({
      where: { device_id: device.id, status: toEnumName('active') },
    });
    if (contract) {
      const overdue = await EMIPayment.findAll({
        where: {
          contract_id: contract.id,
          due_date: { [Op.lt]: todayStr() },
          status: { [Op.in]: [toEnumName('pending'), toEnumName('partial'), toEnumName('overdue')] },
        },
      });
      if (overdue.length) {
        const amount = overdue.reduce(
          (sum, p) => sum + (Number(p.due_amount) - Number(p.paid_amount)), 0,
        );
        return [true, `EMI payment overdue. Amount due: ₹${amount}`];
      }
    }
    return [false, null];
  },

  async checkDeviceStatus(req) {
    const device = await this.findDeviceByFingerprint(req);
    if (!device) {
      return { known_device: false, needs_re_enrollment: false, should_lock: false };
    }
    const needsReEnrollment = this.checkNeedsReEnrollment(device, req);
    const [shouldLock, lockMessage] = await this.checkShouldLock(device);

    let customerName = null;
    if (device.customer_id) {
      const customer = await Customer.findByPk(device.customer_id);
      if (customer) customerName = customer.full_name;
    }

    return {
      known_device: true,
      needs_re_enrollment: needsReEnrollment,
      should_lock: shouldLock,
      lock_message: lockMessage ?? null,
      device_id: String(device.id),
      customer_name: customerName,
      apk_url: needsReEnrollment ? settings.APK_DOWNLOAD_URL : null,
    };
  },

  generateDeviceToken() {
    return crypto.randomBytes(32).toString('base64url');
  },

  async enrollDeviceV2(req) {
    const existing = await this.findDeviceByFingerprint({
      imei: req.imei,
      imei2: req.imei2,
      serial_number: req.serial_number,
      android_id: req.android_id,
      persistent_device_id: req.persistent_device_id,
    });
    if (existing) return this.reEnrollDevice(existing, req);
    return this.newEnrollment(req);
  },

  async reEnrollDevice(device, req) {
    if (device.status === 'deactivated') {
      throw httpError(403, 'DEVICE_BLACKLISTED: This device has been deactivated by the EMI provider. Contact support.');
    }
    device.factory_reset_count = (device.factory_reset_count || 0) + 1;
    device.last_factory_reset = utcNow();
    device.android_id = req.android_id;
    device.fcm_token = req.fcm_token;
    device.app_version = req.app_version;
    device.is_device_owner = req.is_device_owner;
    device.is_admin_active = req.is_admin_active;
    device.build_fingerprint = req.build_fingerprint;
    device.is_online = true;
    device.last_seen = utcNow();
    if (device.status === 'wiped') device.status = 'active';
    await device.save();

    return {
      success: true,
      device_id: String(device.id),
      device_token: this.generateDeviceToken(),
      message: 'Device re-enrolled successfully after factory reset',
    };
  },

  async newEnrollment(req) {
    const device = await Device.create({
      imei: req.imei ?? null,
      imei2: req.imei2 ?? null,
      serial_number: req.serial_number ?? null,
      persistent_device_id: req.persistent_device_id ?? null,
      android_id: req.android_id ?? null,
      device_model: req.model ?? null,
      manufacturer: req.manufacturer ?? null,
      brand: req.brand ?? null,
      device_name: req.device ?? null,
      product: req.product ?? null,
      board: req.board ?? null,
      hardware: req.hardware ?? null,
      android_version: req.android_version ?? null,
      sdk_version: req.sdk_version != null ? String(req.sdk_version) : null,
      build_fingerprint: req.build_fingerprint ?? null,
      fcm_token: req.fcm_token ?? null,
      app_version: req.app_version ?? null,
      is_device_owner: req.is_device_owner ?? false,
      is_admin_active: req.is_admin_active ?? false,
      status: 'active',
      is_online: true,
      last_seen: utcNow(),
      enrolled_at: utcNow(),
    });

    return {
      success: true,
      device_id: String(device.id),
      device_token: this.generateDeviceToken(),
      message: 'Device enrolled successfully',
    };
  },
};

export default FingerprintService;
