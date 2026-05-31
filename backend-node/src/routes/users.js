import { User } from '../models/index.js';
import { getPasswordHash } from '../utils/security.js';
import { serializeUser } from '../utils/serializers.js';
import { httpError, requireUser, requireAdmin } from '../middleware/auth.js';
import { ah } from './auth.js';

const USER_UPDATABLE = ['email', 'full_name', 'phone', 'role', 'is_active'];

export default function registerUserRoutes(router) {
  router.get('/users/me', requireUser, ah(async (req, res) => {
    res.json(serializeUser(req.user));
  }));

  router.get('/users', requireAdmin, ah(async (req, res) => {
    const skip = Math.max(0, parseInt(req.query.skip, 10) || 0);
    let limit = parseInt(req.query.limit, 10);
    limit = Number.isNaN(limit) ? 50 : Math.min(100, Math.max(1, limit));
    const users = await User.findAll({ offset: skip, limit });
    res.json(users.map(serializeUser));
  }));

  router.post('/users', requireAdmin, ah(async (req, res) => {
    const data = req.body || {};
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
      role: data.role || 'operator',
    });
    res.status(201).json(serializeUser(user));
  }));

  router.get('/users/:userId', requireAdmin, ah(async (req, res) => {
    const user = await User.findByPk(req.params.userId);
    if (!user) throw httpError(404, 'User not found');
    res.json(serializeUser(user));
  }));

  router.put('/users/:userId', requireAdmin, ah(async (req, res) => {
    const user = await User.findByPk(req.params.userId);
    if (!user) throw httpError(404, 'User not found');
    const data = req.body || {};
    for (const field of USER_UPDATABLE) {
      if (Object.prototype.hasOwnProperty.call(data, field)) {
        user[field] = field === 'role' && data[field] != null
          ? String(data[field]).toLowerCase() : data[field];
      }
    }
    await user.save();
    res.json(serializeUser(user));
  }));

  router.delete('/users/:userId', requireAdmin, ah(async (req, res) => {
    if (String(req.params.userId) === String(req.user.id)) {
      throw httpError(400, 'Cannot delete yourself');
    }
    const user = await User.findByPk(req.params.userId);
    if (!user) throw httpError(404, 'User not found');
    await user.destroy();
    res.status(204).end();
  }));
}
