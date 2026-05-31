import { Op } from 'sequelize';
import { User } from '../models/index.js';
import {
  verifyPassword, getPasswordHash, createAccessToken, createRefreshToken, decodeToken,
} from '../utils/security.js';
import { httpError } from '../middleware/auth.js';
import { serializeUser } from '../utils/serializers.js';
import { utcNow } from '../utils/time.js';

/** Wrap an async route so thrown {status,detail} errors reach the error handler. */
export function ah(fn) {
  return (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
}

export default function registerAuthRoutes(router) {
  router.post('/auth/login', ah(async (req, res) => {
    const { username, password } = req.body || {};
    const user = await User.findOne({
      where: { [Op.or]: [{ username }, { email: username }] },
    });
    if (!user || !verifyPassword(password, user.hashed_password)) {
      throw httpError(401, 'Invalid username or password');
    }
    if (!user.is_active) throw httpError(401, 'User account is inactive');

    user.last_login = utcNow();
    await user.save();

    const accessToken = createAccessToken({ sub: String(user.id) });
    const refreshToken = createRefreshToken({ sub: String(user.id) });
    res.json({ access_token: accessToken, refresh_token: refreshToken, token_type: 'bearer' });
  }));

  router.post('/auth/refresh', ah(async (req, res) => {
    const { refresh_token: token } = req.body || {};
    const payload = decodeToken(token);
    if (payload.type !== 'refresh') throw httpError(401, 'Invalid token type');

    const user = await User.findByPk(payload.sub);
    if (!user || !user.is_active) throw httpError(401, 'User not found or inactive');

    const accessToken = createAccessToken({ sub: String(user.id) });
    const refreshToken = createRefreshToken({ sub: String(user.id) });
    res.json({ access_token: accessToken, refresh_token: refreshToken, token_type: 'bearer' });
  }));

  router.post('/auth/register', ah(async (req, res) => {
    const data = req.body || {};
    const existing = await User.count();
    if (existing > 0) {
      throw httpError(403, 'Registration is disabled. Contact admin to create accounts.');
    }
    if (await User.findOne({ where: { username: data.username } })) {
      throw httpError(400, 'Username already registered');
    }
    if (await User.findOne({ where: { email: data.email } })) {
      throw httpError(400, 'Email already registered');
    }
    const user = await User.create({
      email: data.email,
      username: data.username,
      hashed_password: getPasswordHash(data.password),
      full_name: data.full_name ?? null,
      phone: data.phone ?? null,
      role: existing === 0 ? 'superadmin' : (data.role || 'operator'),
    });
    res.status(201).json(serializeUser(user));
  }));
}
