import { randomUUID } from 'crypto';
import { DataTypes } from 'sequelize';
import { sequelize } from '../db.js';
import { enumField, UserRole } from '../utils/enums.js';

export const User = sequelize.define(
  'User',
  {
    id: { type: DataTypes.STRING(36), primaryKey: true, defaultValue: randomUUID },
    email: { type: DataTypes.STRING(255), allowNull: false, unique: true },
    username: { type: DataTypes.STRING(100), allowNull: false, unique: true },
    hashed_password: { type: DataTypes.STRING(255), allowNull: false },
    full_name: { type: DataTypes.STRING(255) },
    phone: { type: DataTypes.STRING(20) },
    role: enumField({ field: 'role', defaultValue: 'operator' }),
    is_active: { type: DataTypes.BOOLEAN, defaultValue: true },
    created_at: { type: DataTypes.DATE, defaultValue: DataTypes.NOW },
    // last_login is referenced by auth.login; column may be added lazily.
    last_login: { type: DataTypes.DATE, allowNull: true },
  },
  { tableName: 'users', timestamps: false },
);

export { UserRole };
export default User;
