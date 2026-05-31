import { randomUUID } from 'crypto';
import { DataTypes } from 'sequelize';
import { sequelize } from '../db.js';
import { enumField, CommandType, CommandStatus } from '../utils/enums.js';

/**
 * Build a JSONB-compatible attribute. The old Python backend's JSONB type stored
 * JSON as TEXT on SQLite and native JSON/JSONB on Postgres/MySQL. We mirror that:
 * always (de)serialize through getters/setters so a TEXT column round-trips, and
 * native JSON columns also work (object passes through).
 */
function jsonbField(field, defaultValue) {
  return {
    type: DataTypes.TEXT,
    field,
    defaultValue: defaultValue === undefined ? undefined : JSON.stringify(defaultValue),
    get() {
      const raw = this.getDataValue(field);
      if (raw === null || raw === undefined) return raw;
      if (typeof raw === 'object') return raw; // native JSON column
      try {
        return JSON.parse(raw);
      } catch {
        return raw;
      }
    },
    set(val) {
      if (val === null || val === undefined) {
        this.setDataValue(field, val);
      } else if (typeof val === 'string') {
        this.setDataValue(field, val);
      } else {
        this.setDataValue(field, JSON.stringify(val));
      }
    },
  };
}

export const DeviceCommand = sequelize.define(
  'DeviceCommand',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    device_id: { type: DataTypes.STRING(36), allowNull: false },
    command_type: enumField({ field: 'command_type', allowNull: false }),
    payload: jsonbField('payload', {}),
    status: enumField({ field: 'status', defaultValue: 'pending' }),
    retry_count: { type: DataTypes.STRING(10), defaultValue: '0' },
    max_retries: { type: DataTypes.STRING(10), defaultValue: '3' },
    fcm_message_id: { type: DataTypes.STRING(255) },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    sent_at: { type: DataTypes.DATE },
    delivered_at: { type: DataTypes.DATE },
    executed_at: { type: DataTypes.DATE },
    error_message: { type: DataTypes.TEXT },
    issued_by: { type: DataTypes.STRING(36) },
    reason: { type: DataTypes.TEXT },
  },
  { tableName: 'device_commands', timestamps: false },
);

export const AuditLog = sequelize.define(
  'AuditLog',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    entity_type: { type: DataTypes.STRING(50), allowNull: false },
    entity_id: { type: DataTypes.STRING(36) },
    action: { type: DataTypes.STRING(100), allowNull: false },
    old_value: jsonbField('old_value'),
    new_value: jsonbField('new_value'),
    performed_by: { type: DataTypes.STRING(36) },
    ip_address: { type: DataTypes.STRING(50) },
    user_agent: { type: DataTypes.TEXT },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
  },
  { tableName: 'audit_logs', timestamps: false },
);

export { CommandType, CommandStatus };
export default DeviceCommand;
