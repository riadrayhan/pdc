import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { settings } from '../config.js';

// passlib bcrypt hashes ($2b$/$2a$) are fully compatible with bcryptjs.
export function verifyPassword(plain, hashed) {
  if (!hashed) return false;
  try {
    return bcrypt.compareSync(plain, hashed);
  } catch {
    return false;
  }
}

export function getPasswordHash(password) {
  return bcrypt.hashSync(password, 12);
}

function sign(data, expiresInSeconds, type) {
  const payload = { ...data, type };
  return jwt.sign(payload, settings.SECRET_KEY, {
    algorithm: settings.ALGORITHM,
    expiresIn: expiresInSeconds,
  });
}

export function createAccessToken(data, expiresMinutes = settings.ACCESS_TOKEN_EXPIRE_MINUTES) {
  return sign(data, expiresMinutes * 60, 'access');
}

export function createRefreshToken(data) {
  return sign(data, settings.REFRESH_TOKEN_EXPIRE_DAYS * 24 * 60 * 60, 'refresh');
}

/** Returns the decoded payload or throws { status, detail }. */
export function decodeToken(token) {
  try {
    return jwt.verify(token, settings.SECRET_KEY, { algorithms: [settings.ALGORITHM] });
  } catch {
    const err = new Error('Could not validate credentials');
    err.status = 401;
    err.detail = 'Could not validate credentials';
    throw err;
  }
}

export default {
  verifyPassword,
  getPasswordHash,
  createAccessToken,
  createRefreshToken,
  decodeToken,
};
