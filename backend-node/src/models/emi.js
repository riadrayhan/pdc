import { randomUUID } from 'crypto';
import { DataTypes } from 'sequelize';
import { sequelize } from '../db.js';
import { enumField, ContractStatus, PaymentStatus } from '../utils/enums.js';

export const EMIContract = sequelize.define(
  'EMIContract',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    contract_number: { type: DataTypes.STRING(50), allowNull: false, unique: true },
    customer_id: { type: DataTypes.STRING(36), allowNull: false },
    device_id: { type: DataTypes.STRING(36), allowNull: false },
    product_name: { type: DataTypes.STRING(255) },
    product_price: { type: DataTypes.DECIMAL(12, 2), allowNull: false },
    down_payment: { type: DataTypes.DECIMAL(12, 2), defaultValue: 0 },
    principal_amount: { type: DataTypes.DECIMAL(12, 2), allowNull: false },
    interest_rate: { type: DataTypes.DECIMAL(5, 2), defaultValue: 0 },
    tenure_months: { type: DataTypes.INTEGER, allowNull: false },
    emi_amount: { type: DataTypes.DECIMAL(10, 2), allowNull: false },
    total_amount: { type: DataTypes.DECIMAL(12, 2), allowNull: false },
    start_date: { type: DataTypes.DATEONLY, allowNull: false },
    end_date: { type: DataTypes.DATEONLY, allowNull: false },
    emi_due_day: { type: DataTypes.INTEGER, defaultValue: 1 },
    status: enumField({ field: 'status', defaultValue: 'active' }),
    grace_period_days: { type: DataTypes.INTEGER, defaultValue: 7 },
    total_paid: { type: DataTypes.DECIMAL(12, 2), defaultValue: 0 },
    emis_paid: { type: DataTypes.INTEGER, defaultValue: 0 },
    notes: { type: DataTypes.TEXT },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    updated_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    created_by: { type: DataTypes.STRING(36) },
  },
  { tableName: 'emi_contracts', timestamps: false },
);

export const EMIPayment = sequelize.define(
  'EMIPayment',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    contract_id: { type: DataTypes.STRING(36), allowNull: false },
    installment_number: { type: DataTypes.INTEGER, allowNull: false },
    due_amount: { type: DataTypes.DECIMAL(10, 2), allowNull: false },
    paid_amount: { type: DataTypes.DECIMAL(10, 2), defaultValue: 0 },
    late_fee: { type: DataTypes.DECIMAL(10, 2), defaultValue: 0 },
    due_date: { type: DataTypes.DATEONLY, allowNull: false },
    paid_date: { type: DataTypes.DATE },
    status: enumField({ field: 'status', defaultValue: 'pending' }),
    payment_method: { type: DataTypes.STRING(50) },
    payment_reference: { type: DataTypes.STRING(100) },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    updated_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    recorded_by: { type: DataTypes.STRING(36) },
  },
  { tableName: 'emi_payments', timestamps: false },
);

export { ContractStatus, PaymentStatus };
export default EMIContract;
