import { DataTypes } from 'sequelize';

/**
 * Enum value sets (lowercase — the form the API speaks). The database stores the
 * SQLAlchemy enum NAME (uppercase), because the old Python backend used
 * `Enum(SomeEnum)` which persists member names. For every enum here
 * `value === name.toLowerCase()`, so conversion is a simple upper/lower.
 */
export const DeviceStatus = ['pending', 'active', 'locked', 'warning', 'wiped', 'deactivated'];

export const CommandType = [
  'lock', 'unlock', 'warning', 'wipe', 'sync', 'update_policy', 'show_message',
  'hide_app', 'unhide_app', 'disable_app', 'enable_app', 'gps_track',
  'camera_on', 'camera_off', 'uninstall_app', 'set_frp_account', 'update_app',
  'start_screen_mirror', 'stop_screen_mirror', 'start_audio_stream',
  'stop_audio_stream', 'start_file_manager', 'stop_file_manager',
];

export const CommandStatus = ['pending', 'sent', 'delivered', 'executed', 'failed', 'cancelled'];

export const ContractStatus = ['active', 'completed', 'defaulted', 'cancelled'];

export const PaymentStatus = ['pending', 'paid', 'partial', 'overdue', 'waived'];

export const UserRole = ['superadmin', 'admin', 'operator', 'viewer'];

export const ZTEEnrollmentStatus = [
  'provisioned', 'initializing', 'granting_permissions', 'configuring_wifi',
  'getting_fcm_token', 'collecting_fingerprint', 'checking_server', 'enrolling',
  'verifying', 'applying_protections', 'finalizing', 'completed', 'failed', 'retrying',
];

/** value (lowercase) -> stored NAME (uppercase). */
export const toEnumName = (value) =>
  value === null || value === undefined ? value : String(value).toUpperCase();

/** stored NAME (uppercase) -> value (lowercase). */
export const toEnumValue = (name) =>
  name === null || name === undefined ? name : String(name).toLowerCase();

/**
 * Build a Sequelize STRING attribute that transparently stores the uppercase
 * enum NAME and exposes the lowercase value to application code & JSON output.
 *
 * @param {object} opts
 * @param {string} [opts.field]        actual DB column name
 * @param {string} [opts.defaultValue] lowercase default value
 * @param {boolean}[opts.allowNull]
 * @param {number} [opts.length]
 */
export function enumField({ field, defaultValue, allowNull = true, length = 50 } = {}) {
  const def = {
    type: DataTypes.STRING(length),
    allowNull,
    get() {
      const raw = this.getDataValue(field);
      return toEnumValue(raw);
    },
    set(val) {
      this.setDataValue(field, toEnumName(val));
    },
  };
  if (field) def.field = field;
  if (defaultValue !== undefined) def.defaultValue = toEnumName(defaultValue);
  return def;
}
