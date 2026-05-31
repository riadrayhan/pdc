import { isDeviceOnline } from '../services/realtime.js';
import { ah } from './auth.js';

/**
 * Remote file-manager status. The browse/download stream itself runs over
 * Socket.IO (see services/realtime.js: file-message / file-request events).
 */
export default function registerFileRoutes(router) {
  router.get('/files/status/:deviceId', ah(async (req, res) => {
    const online = await isDeviceOnline(req.params.deviceId);
    res.json({ device_id: req.params.deviceId, online });
  }));
}