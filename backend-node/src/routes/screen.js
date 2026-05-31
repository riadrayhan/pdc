import { getStreamStatus } from '../services/realtime.js';
import { ah } from './auth.js';

/**
 * Live screen / audio mirror status. The actual media flows over WebRTC with
 * Socket.IO signaling (see services/realtime.js). This REST endpoint mirrors
 * the old /screen/status/{device_id} contract for the dashboard.
 */
export default function registerScreenRoutes(router) {
  router.get('/screen/status/:deviceId', ah(async (req, res) => {
    const status = await getStreamStatus(req.params.deviceId);
    res.json({ device_id: req.params.deviceId, ...status });
  }));
}
