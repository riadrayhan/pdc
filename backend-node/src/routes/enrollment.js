import { FingerprintService } from '../services/fingerprintService.js';
import { settings } from '../config.js';
import { ah } from './auth.js';

export default function registerEnrollmentRoutes(router) {
  router.post('/enrollment/check-status', ah(async (req, res) => {
    res.json(await FingerprintService.checkDeviceStatus(req.body || {}));
  }));

  router.post('/enrollment/enroll', ah(async (req, res) => {
    res.json(await FingerprintService.enrollDeviceV2(req.body || {}));
  }));

  router.get('/enrollment/apk-url', ah(async (req, res) => {
    res.json({
      url: settings.APK_DOWNLOAD_URL,
      version: settings.APP_VERSION,
      checksum: settings.APK_CHECKSUM,
    });
  }));
}
