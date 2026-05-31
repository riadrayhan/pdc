import { randomUUID } from 'crypto';
import { DataTypes } from 'sequelize';
import { sequelize } from '../db.js';

const idCol = () => ({ type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID });
const created = () => ({ type: DataTypes.DATE, defaultValue: DataTypes.NOW });
const dev = () => ({ type: DataTypes.STRING(64), allowNull: false });

function define(name, table, attrs) {
  return sequelize.define(
    name,
    { id: idCol(), device_id: dev(), ...attrs, created_at: created() },
    { tableName: table, timestamps: false },
  );
}

export const CallLogEntry = define('CallLogEntry', 'md_call_logs', {
  number: { type: DataTypes.STRING(64) },
  type: { type: DataTypes.STRING(16) },
  call_date: { type: DataTypes.STRING(32) },
  duration: { type: DataTypes.STRING(16) },
});

export const SmsEntry = define('SmsEntry', 'md_sms', {
  address: { type: DataTypes.STRING(64) },
  body: { type: DataTypes.TEXT },
  sms_date: { type: DataTypes.STRING(32) },
  type: { type: DataTypes.STRING(16) },
});

export const LocationEntry = define('LocationEntry', 'md_locations', {
  latitude: { type: DataTypes.FLOAT },
  longitude: { type: DataTypes.FLOAT },
  accuracy: { type: DataTypes.FLOAT },
  timestamp: { type: DataTypes.STRING(32) },
  address: { type: DataTypes.TEXT },
});

export const SimHistoryEntry = define('SimHistoryEntry', 'md_sim_history', {
  old_iccid: { type: DataTypes.STRING(64) },
  new_iccid: { type: DataTypes.STRING(64) },
  phone_number: { type: DataTypes.STRING(32) },
  carrier: { type: DataTypes.STRING(64) },
  timestamp: { type: DataTypes.STRING(32) },
});

export const MobileMoneyEntry = define('MobileMoneyEntry', 'md_mobile_money', {
  provider: { type: DataTypes.STRING(32) },
  txn_type: { type: DataTypes.STRING(32) },
  amount: { type: DataTypes.STRING(32) },
  balance: { type: DataTypes.STRING(32) },
  txn_id: { type: DataTypes.STRING(64) },
  counter_party: { type: DataTypes.STRING(32) },
  sender: { type: DataTypes.STRING(64) },
  raw_sms: { type: DataTypes.TEXT },
  timestamp: { type: DataTypes.STRING(32) },
});

export const TelecomUsageEntry = define('TelecomUsageEntry', 'md_telecom_usage', {
  operator: { type: DataTypes.STRING(32) },
  recharge_type: { type: DataTypes.STRING(32) },
  amount: { type: DataTypes.STRING(32) },
  balance: { type: DataTypes.STRING(32) },
  sender: { type: DataTypes.STRING(64) },
  raw_sms: { type: DataTypes.TEXT },
  timestamp: { type: DataTypes.STRING(32) },
});

export const RideHailingEntry = define('RideHailingEntry', 'md_ride_hailing', {
  provider: { type: DataTypes.STRING(32) },
  ride_type: { type: DataTypes.STRING(32) },
  amount: { type: DataTypes.STRING(32) },
  trip_details: { type: DataTypes.TEXT },
  sender: { type: DataTypes.STRING(64) },
  timestamp: { type: DataTypes.STRING(32) },
});

export const MetaDeviceInfo = define('MetaDeviceInfo', 'md_device_info', {
  android_id: { type: DataTypes.STRING(64) },
  brand: { type: DataTypes.STRING(64) },
  model: { type: DataTypes.STRING(64) },
  manufacturer: { type: DataTypes.STRING(64) },
  device: { type: DataTypes.STRING(64) },
  hardware: { type: DataTypes.STRING(64) },
  os_version: { type: DataTypes.STRING(32) },
  api_level: { type: DataTypes.STRING(8) },
  security_patch: { type: DataTypes.STRING(32) },
  build_fingerprint: { type: DataTypes.STRING(255) },
  first_install_time: { type: DataTypes.STRING(32) },
  uptime_days: { type: DataTypes.STRING(16) },
  is_rooted: { type: DataTypes.STRING(8) },
  sim_swap_count: { type: DataTypes.STRING(8) },
  factory_reset_indicator: { type: DataTypes.STRING(8) },
  screen_info: { type: DataTypes.STRING(64) },
  ram_info: { type: DataTypes.STRING(64) },
  storage_info: { type: DataTypes.STRING(64) },
  battery_info: { type: DataTypes.STRING(64) },
  network_type: { type: DataTypes.STRING(32) },
  timezone: { type: DataTypes.STRING(64) },
  language: { type: DataTypes.STRING(8) },
  country: { type: DataTypes.STRING(8) },
  timestamp: { type: DataTypes.STRING(32) },
});

export const LocationDwellEntry = define('LocationDwellEntry', 'md_location_dwell', {
  latitude: { type: DataTypes.FLOAT },
  longitude: { type: DataTypes.FLOAT },
  accuracy: { type: DataTypes.FLOAT },
  address: { type: DataTypes.TEXT },
  timestamp: { type: DataTypes.STRING(32) },
  dwell_minutes: { type: DataTypes.INTEGER, defaultValue: 0 },
  location_type: { type: DataTypes.STRING(32) },
  visit_count: { type: DataTypes.INTEGER, defaultValue: 1 },
  event_type: { type: DataTypes.STRING(16) },
});

export const BehaviorScoreEntry = define('BehaviorScoreEntry', 'md_behavior_scores', {
  total_calls: { type: DataTypes.INTEGER },
  incoming_calls: { type: DataTypes.INTEGER },
  outgoing_calls: { type: DataTypes.INTEGER },
  missed_calls: { type: DataTypes.INTEGER },
  total_duration: { type: DataTypes.INTEGER },
  night_calls: { type: DataTypes.INTEGER },
  weekend_calls: { type: DataTypes.INTEGER },
  unique_call_contacts: { type: DataTypes.INTEGER },
  call_regularity: { type: DataTypes.STRING(16) },
  in_out_ratio: { type: DataTypes.STRING(16) },
  night_ratio: { type: DataTypes.STRING(16) },
  weekend_ratio: { type: DataTypes.STRING(16) },
  avg_call_duration: { type: DataTypes.STRING(16) },
  contact_diversity: { type: DataTypes.STRING(16) },
  total_sms: { type: DataTypes.INTEGER },
  sent_sms: { type: DataTypes.INTEGER },
  received_sms: { type: DataTypes.INTEGER },
  unique_sms_contacts: { type: DataTypes.INTEGER },
  network_size: { type: DataTypes.INTEGER },
  unique_locations: { type: DataTypes.INTEGER },
  total_mfs_txns: { type: DataTypes.INTEGER },
  total_mfs_volume: { type: DataTypes.STRING(32) },
  total_recharges: { type: DataTypes.INTEGER },
  total_recharge_amount: { type: DataTypes.STRING(32) },
  mfs_activity_score: { type: DataTypes.STRING(16) },
  recharge_frequency: { type: DataTypes.STRING(16) },
  timestamp: { type: DataTypes.STRING(32) },
});

export const InstalledAppEntry = define('InstalledAppEntry', 'md_installed_apps', {
  package_name: { type: DataTypes.STRING(128) },
  app_name: { type: DataTypes.STRING(128) },
  category: { type: DataTypes.STRING(32) },
  version: { type: DataTypes.STRING(32) },
  install_date: { type: DataTypes.STRING(16) },
  last_update: { type: DataTypes.STRING(16) },
  status: { type: DataTypes.STRING(16) },
  timestamp: { type: DataTypes.STRING(32) },
});

export const ContactEntry = define('ContactEntry', 'md_contacts', {
  name: { type: DataTypes.STRING(128) },
  number: { type: DataTypes.STRING(64) },
  normalized_number: { type: DataTypes.STRING(64) },
  type: { type: DataTypes.STRING(16) },
  times_contacted: { type: DataTypes.STRING(16) },
  last_contacted: { type: DataTypes.STRING(32) },
  account_type: { type: DataTypes.STRING(64) },
  timestamp: { type: DataTypes.STRING(32) },
});

// Map JSON `type` value from the Android sync payload to the model.
export const METADATA_MODEL_MAP = {
  call_logs: CallLogEntry,
  sms: SmsEntry,
  location: LocationEntry,
  sim_history: SimHistoryEntry,
  mobile_money: MobileMoneyEntry,
  telecom_usage: TelecomUsageEntry,
  ride_hailing: RideHailingEntry,
  device_info: MetaDeviceInfo,
  location_dwell: LocationDwellEntry,
  behavior_scores: BehaviorScoreEntry,
  installed_apps: InstalledAppEntry,
  contacts: ContactEntry,
};

// Android column names that need renaming to dodge SQL reserved words.
export const COLUMN_RENAME = { date: 'call_date' };
