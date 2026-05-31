import { randomUUID } from 'crypto';
import { DataTypes } from 'sequelize';
import { sequelize } from '../db.js';
import { enumField, ZTEEnrollmentStatus } from '../utils/enums.js';

export const ZTEConfig = sequelize.define(
  'ZTEConfig',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    config_key: { type: DataTypes.STRING(50), unique: true, defaultValue: 'default', allowNull: false },
    wifi_ssid: { type: DataTypes.STRING(64), defaultValue: '' },
    wifi_password: { type: DataTypes.STRING(128), defaultValue: '' },
    wifi_security: { type: DataTypes.STRING(10), defaultValue: 'WPA' },
    wifi_hidden: { type: DataTypes.BOOLEAN, defaultValue: false },
    default_lock_message: {
      type: DataTypes.TEXT,
      defaultValue: 'আপনার EMI বকেয়া আছে। অনুগ্রহ করে পরিশোধ করুন।',
    },
    default_contact_number: { type: DataTypes.STRING(20), defaultValue: '' },
    auto_enroll: { type: DataTypes.BOOLEAN, defaultValue: true },
    auto_lock_on_enroll: { type: DataTypes.BOOLEAN, defaultValue: false },
    skip_encryption: { type: DataTypes.BOOLEAN, defaultValue: true },
    leave_all_system_apps: { type: DataTypes.BOOLEAN, defaultValue: true },
    locale: { type: DataTypes.STRING(10), defaultValue: 'bn_BD' },
    time_zone: { type: DataTypes.STRING(50), defaultValue: 'Asia/Dhaka' },
    custom_apk_url: { type: DataTypes.STRING(500), defaultValue: '' },
    enabled: { type: DataTypes.BOOLEAN, defaultValue: true },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    updated_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
  },
  { tableName: 'zte_config', timestamps: false },
);

ZTEConfig.prototype.toDict = function toDict() {
  return {
    wifi_ssid: this.wifi_ssid || '',
    wifi_password: this.wifi_password || '',
    wifi_security: this.wifi_security || 'WPA',
    wifi_hidden: this.wifi_hidden || false,
    default_lock_message: this.default_lock_message || '',
    default_contact_number: this.default_contact_number || '',
    auto_enroll: this.auto_enroll == null ? true : this.auto_enroll,
    auto_lock_on_enroll: this.auto_lock_on_enroll || false,
    skip_encryption: this.skip_encryption == null ? true : this.skip_encryption,
    leave_all_system_apps: this.leave_all_system_apps == null ? true : this.leave_all_system_apps,
    locale: this.locale || 'bn_BD',
    time_zone: this.time_zone || 'Asia/Dhaka',
    custom_apk_url: this.custom_apk_url || '',
    enabled: this.enabled == null ? true : this.enabled,
  };
};

export const ZTEEnrollmentEvent = sequelize.define(
  'ZTEEnrollmentEvent',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    imei: { type: DataTypes.STRING(20) },
    imei2: { type: DataTypes.STRING(20) },
    serial_number: { type: DataTypes.STRING(100) },
    persistent_device_id: { type: DataTypes.STRING(64) },
    android_id: { type: DataTypes.STRING(64) },
    manufacturer: { type: DataTypes.STRING(100) },
    model: { type: DataTypes.STRING(100) },
    android_version: { type: DataTypes.STRING(20) },
    status: enumField({ field: 'status', defaultValue: 'provisioned' }),
    current_phase: { type: DataTypes.INTEGER, defaultValue: 0 },
    total_phases: { type: DataTypes.INTEGER, defaultValue: 11 },
    progress_percent: { type: DataTypes.INTEGER, defaultValue: 0 },
    started_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    completed_at: { type: DataTypes.DATE },
    elapsed_seconds: { type: DataTypes.FLOAT },
    device_id: { type: DataTypes.STRING(100) },
    fcm_token: { type: DataTypes.TEXT },
    enrollment_method: { type: DataTypes.STRING(20), defaultValue: 'zte' },
    retry_count: { type: DataTypes.INTEGER, defaultValue: 0 },
    last_error: { type: DataTypes.TEXT },
    failure_phase: { type: DataTypes.INTEGER },
    network_type: { type: DataTypes.STRING(20) },
    wifi_ssid: { type: DataTypes.STRING(64) },
    sim_operator: { type: DataTypes.STRING(100) },
    sim_country: { type: DataTypes.STRING(10) },
    phone_number: { type: DataTypes.STRING(20) },
    server_url: { type: DataTypes.STRING(500) },
    zte_version: { type: DataTypes.STRING(10), defaultValue: '2.0' },
    extra_data: { type: DataTypes.JSON },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    updated_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
  },
  { tableName: 'zte_enrollment_events', timestamps: false },
);

export const ZTEProvisioningLog = sequelize.define(
  'ZTEProvisioningLog',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    event_id: { type: DataTypes.STRING(36), allowNull: false },
    phase: { type: DataTypes.INTEGER, allowNull: false },
    phase_name: { type: DataTypes.STRING(50), allowNull: false },
    status: { type: DataTypes.STRING(20), allowNull: false },
    message: { type: DataTypes.TEXT },
    duration_ms: { type: DataTypes.INTEGER },
    timestamp: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
  },
  { tableName: 'zte_provisioning_logs', timestamps: false },
);

export { ZTEEnrollmentStatus };
export default ZTEConfig;
