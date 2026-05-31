import { randomUUID } from 'crypto';
import { DataTypes } from 'sequelize';
import { sequelize } from '../db.js';
import { enumField, DeviceStatus } from '../utils/enums.js';

export const Device = sequelize.define(
  'Device',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    imei: { type: DataTypes.STRING(20), allowNull: true, unique: true },
    imei2: { type: DataTypes.STRING(20) },
    serial_number: { type: DataTypes.STRING(100) },
    persistent_device_id: { type: DataTypes.STRING(64), unique: true },
    android_id: { type: DataTypes.STRING(64) },
    device_model: { type: DataTypes.STRING(100) },
    manufacturer: { type: DataTypes.STRING(100) },
    brand: { type: DataTypes.STRING(100) },
    device_name: { type: DataTypes.STRING(100) },
    product: { type: DataTypes.STRING(100) },
    board: { type: DataTypes.STRING(100) },
    hardware: { type: DataTypes.STRING(100) },
    build_fingerprint: { type: DataTypes.STRING(255) },
    android_version: { type: DataTypes.STRING(20) },
    sdk_version: { type: DataTypes.STRING(10) },
    fcm_token: { type: DataTypes.TEXT },
    status: enumField({ field: 'status', defaultValue: 'pending' }),
    is_online: { type: DataTypes.BOOLEAN, defaultValue: false },
    last_seen: { type: DataTypes.DATE },
    factory_reset_count: { type: DataTypes.INTEGER, defaultValue: 0 },
    last_factory_reset: { type: DataTypes.DATE },
    is_rooted: { type: DataTypes.BOOLEAN, defaultValue: false },
    safety_net_passed: { type: DataTypes.BOOLEAN },
    is_device_owner: { type: DataTypes.BOOLEAN, defaultValue: false },
    is_admin_active: { type: DataTypes.BOOLEAN, defaultValue: false },
    app_version: { type: DataTypes.STRING(20) },
    enrollment_code: { type: DataTypes.STRING(50), unique: true },
    enrolled_at: { type: DataTypes.DATE },
    enrolled_by: { type: DataTypes.STRING(36) },
    last_latitude: { type: DataTypes.STRING(50) },
    last_longitude: { type: DataTypes.STRING(50) },
    last_location_time: { type: DataTypes.DATE },
    last_location_address: { type: DataTypes.TEXT },
    camera_active: { type: DataTypes.BOOLEAN, defaultValue: false },
    last_photo_url: { type: DataTypes.TEXT },
    last_photo_time: { type: DataTypes.DATE },
    is_app_hidden: { type: DataTypes.BOOLEAN, defaultValue: false },
    is_app_disabled: { type: DataTypes.BOOLEAN, defaultValue: false },
    battery_level: { type: DataTypes.INTEGER },
    is_charging: { type: DataTypes.BOOLEAN },
    network_type: { type: DataTypes.STRING(20) },
    customer_id: { type: DataTypes.STRING(36) },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    updated_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
  },
  { tableName: 'devices', timestamps: false },
);

export { DeviceStatus };
export default Device;
