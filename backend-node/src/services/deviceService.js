import { Op } from 'sequelize';
import crypto from 'crypto';
import { Device, Customer } from '../models/index.js';
import { CommandService } from './commandService.js';
import { utcNow } from '../utils/time.js';
import { toEnumName } from '../utils/enums.js';

function enrollmentCode() {
  return `EMI-${crypto.randomUUID().replace(/-/g, '').slice(0, 12).toUpperCase()}`;
}

export const DeviceService = {
  generateEnrollmentCode: enrollmentCode,

  async enrollDevice(data) {
    return Device.create({
      imei: data.imei ?? null,
      imei2: data.imei2 ?? null,
      serial_number: data.serial_number ?? null,
      device_model: data.device_model ?? null,
      manufacturer: data.manufacturer ?? null,
      android_version: data.android_version ?? null,
      sdk_version: data.sdk_version ?? null,
      fcm_token: data.fcm_token ?? null,
      android_id: data.android_id ?? null,
      status: 'active',
      is_online: true,
      last_seen: utcNow(),
      enrolled_at: utcNow(),
      enrollment_code: enrollmentCode(),
    });
  },

  getDeviceByImei(imei) {
    return Device.findOne({ where: { imei } });
  },

  getDeviceById(id) {
    return Device.findByPk(id);
  },

  async updateDeviceStatus(deviceId, status, userId = null, reason = null) {
    const device = await this.getDeviceById(deviceId);
    if (!device) return [null, null];

    const oldStatus = device.status;
    device.status = status;
    device.updated_at = utcNow();

    let command = null;
    if (status === 'locked' && oldStatus !== 'locked') {
      command = await CommandService.createLockCommand(device, userId, reason);
    } else if (status === 'active' && oldStatus === 'locked') {
      command = await CommandService.createUnlockCommand(device, userId, reason);
    }

    await device.save();
    return [device, command];
  },

  async updateHeartbeat(hb) {
    let device = null;
    if (hb.android_id) {
      device = await Device.findOne({ where: { android_id: hb.android_id } });
    }
    if (!device && hb.imei) device = await this.getDeviceByImei(hb.imei);
    if (!device && hb.serial_number) {
      device = await Device.findOne({ where: { serial_number: hb.serial_number } });
    }
    if (!device) return null;

    device.fcm_token = hb.fcm_token;
    device.is_online = true;
    device.last_seen = utcNow();
    if (hb.app_version) device.app_version = hb.app_version;
    if (hb.device_name) device.device_name = hb.device_name;
    if (hb.brand) device.brand = hb.brand;
    if (hb.manufacturer) device.manufacturer = hb.manufacturer;
    if (hb.device_model) device.device_model = hb.device_model;
    if (hb.serial_number) device.serial_number = hb.serial_number;
    if (hb.imei2) device.imei2 = hb.imei2;
    if (hb.android_version) device.android_version = hb.android_version;
    if (hb.is_device_owner !== undefined && hb.is_device_owner !== null) device.is_device_owner = hb.is_device_owner;
    if (hb.is_admin_active !== undefined && hb.is_admin_active !== null) device.is_admin_active = hb.is_admin_active;
    if (hb.battery_level !== undefined && hb.battery_level !== null) device.battery_level = hb.battery_level;
    if (hb.is_charging !== undefined && hb.is_charging !== null) device.is_charging = hb.is_charging;
    if (hb.network_type) device.network_type = hb.network_type;

    await device.save();
    return device;
  },

  async listDevices({ skip = 0, limit = 50, status = null, search = null } = {}) {
    const where = {};
    if (status) where.status = toEnumName(status);
    if (search) {
      where[Op.or] = [
        { imei: { [Op.like]: `%${search}%` } },
        { device_model: { [Op.like]: `%${search}%` } },
        { serial_number: { [Op.like]: `%${search}%` } },
      ];
    }
    const { count, rows } = await Device.findAndCountAll({
      where,
      order: [['created_at', 'DESC']],
      offset: skip,
      limit,
    });
    return [rows, count];
  },
};

export const CustomerService = {
  hashIdNumber(idNumber) {
    return crypto.createHash('sha256').update(String(idNumber)).digest('hex');
  },

  createCustomer(data) {
    return Customer.create({
      full_name: data.full_name,
      phone: data.phone,
      alternate_phone: data.alternate_phone ?? null,
      email: data.email ?? null,
      id_type: data.id_type ?? null,
      id_hash: data.id_number ? this.hashIdNumber(data.id_number) : null,
      address: data.address ?? null,
      city: data.city ?? null,
      state: data.state ?? null,
      pincode: data.pincode ?? null,
      emergency_contact_name: data.emergency_contact_name ?? null,
      emergency_contact_phone: data.emergency_contact_phone ?? null,
      emergency_contact_relation: data.emergency_contact_relation ?? null,
    });
  },

  getCustomerById(id) {
    return Customer.findByPk(id);
  },

  getCustomerByPhone(phone) {
    return Customer.findOne({ where: { phone } });
  },

  async listCustomers({ skip = 0, limit = 50, search = null } = {}) {
    const where = {};
    if (search) {
      where[Op.or] = [
        { full_name: { [Op.like]: `%${search}%` } },
        { phone: { [Op.like]: `%${search}%` } },
        { email: { [Op.like]: `%${search}%` } },
      ];
    }
    const { count, rows } = await Customer.findAndCountAll({
      where,
      order: [['created_at', 'DESC']],
      offset: skip,
      limit,
    });
    return [rows, count];
  },
};

export default DeviceService;
