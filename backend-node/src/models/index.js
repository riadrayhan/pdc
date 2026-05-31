import { sequelize } from '../db.js';
import { User } from './user.js';
import { Customer } from './customer.js';
import { Device } from './device.js';
import { EMIContract, EMIPayment } from './emi.js';
import { DeviceCommand, AuditLog } from './command.js';
import { ZTEConfig, ZTEEnrollmentEvent, ZTEProvisioningLog } from './zte.js';
import * as metadata from './metadata.js';

// ── Associations ────────────────────────────────────────
Customer.hasMany(Device, { foreignKey: 'customer_id', as: 'devices' });
Device.belongsTo(Customer, { foreignKey: 'customer_id', as: 'customer' });

Customer.hasMany(EMIContract, { foreignKey: 'customer_id', as: 'contracts' });
EMIContract.belongsTo(Customer, { foreignKey: 'customer_id', as: 'customer' });

Device.hasMany(EMIContract, { foreignKey: 'device_id', as: 'contracts' });
EMIContract.belongsTo(Device, { foreignKey: 'device_id', as: 'device' });

EMIContract.hasMany(EMIPayment, { foreignKey: 'contract_id', as: 'payments' });
EMIPayment.belongsTo(EMIContract, { foreignKey: 'contract_id', as: 'contract' });

Device.hasMany(DeviceCommand, { foreignKey: 'device_id', as: 'commands' });
DeviceCommand.belongsTo(Device, { foreignKey: 'device_id', as: 'device' });

/** Create any missing tables (additive only — never drops/alters existing data). */
export async function syncTables() {
  await sequelize.sync();
}

export {
  sequelize,
  User,
  Customer,
  Device,
  EMIContract,
  EMIPayment,
  DeviceCommand,
  AuditLog,
  ZTEConfig,
  ZTEEnrollmentEvent,
  ZTEProvisioningLog,
  metadata,
};

export const {
  CallLogEntry,
  SmsEntry,
  LocationEntry,
  SimHistoryEntry,
  MobileMoneyEntry,
  TelecomUsageEntry,
  RideHailingEntry,
  MetaDeviceInfo,
  LocationDwellEntry,
  BehaviorScoreEntry,
  InstalledAppEntry,
  ContactEntry,
  METADATA_MODEL_MAP,
  COLUMN_RENAME,
} = metadata;
