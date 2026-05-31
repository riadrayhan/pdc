import { randomUUID } from 'crypto';
import { DataTypes } from 'sequelize';
import { sequelize } from '../db.js';

export const Customer = sequelize.define(
  'Customer',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    full_name: { type: DataTypes.STRING(255), allowNull: false },
    phone: { type: DataTypes.STRING(20), allowNull: false },
    alternate_phone: { type: DataTypes.STRING(20) },
    email: { type: DataTypes.STRING(255) },
    id_type: { type: DataTypes.STRING(50) },
    id_hash: { type: DataTypes.STRING(255) },
    address: { type: DataTypes.TEXT },
    city: { type: DataTypes.STRING(100) },
    state: { type: DataTypes.STRING(100) },
    pincode: { type: DataTypes.STRING(10) },
    emergency_contact_name: { type: DataTypes.STRING(255) },
    emergency_contact_phone: { type: DataTypes.STRING(20) },
    emergency_contact_relation: { type: DataTypes.STRING(50) },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    updated_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
  },
  { tableName: 'customers', timestamps: false },
);

export default Customer;
