import { decodeToken } from '../utils/security.js';
import { User } from '../models/index.js';

/** Express error helper that mirrors FastAPI's {detail} 4xx body. */
export function httpError(status, detail) {
  const err = new Error(detail);
  err.status = status;
  err.detail = detail;
  return err;
}

function extractToken(req) {
  const header = req.headers.authorization || '';
  if (!header.startsWith('Bearer ')) return null;
  return header.slice(7).trim();
}

/** Resolve the current user from a Bearer access token, or throw 401. */
export async function resolveUser(req) {
  const token = extractToken(req);
  if (!token) throw httpError(401, 'Not authenticated');

  const payload = decodeToken(token);
  if (payload.type !== 'access') throw httpError(401, 'Invalid token type');

  const userId = payload.sub;
  if (!userId) throw httpError(401, 'Could not validate credentials');

  const user = await User.findByPk(userId);
  if (!user) throw httpError(401, 'User not found');
  return user;
}

/** Middleware: require a valid logged-in user (any role). */
export function requireUser(req, res, next) {
  resolveUser(req)
    .then((user) => {
      req.user = user;
      next();
    })
    .catch(next);
}

/** Middleware: require an admin or superadmin. */
export function requireAdmin(req, res, next) {
  resolveUser(req)
    .then((user) => {
      if (!['admin', 'superadmin'].includes(user.role)) {
        throw httpError(403, 'Not enough permissions');
      }
      req.user = user;
      next();
    })
    .catch(next);
}

export default { requireUser, requireAdmin, resolveUser, httpError };
