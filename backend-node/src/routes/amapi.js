import * as amapi from '../services/amapiService.js';
import { httpError } from '../middleware/auth.js';
import { ah } from './auth.js';

export default function registerAmapiRoutes(router) {
  router.get('/amapi/status', ah(async (req, res) => {
    const configured = amapi.isConfigured();
    const enterprise = amapi.getEnterpriseName();
    res.json({
      configured,
      has_enterprise: !!enterprise,
      enterprise_name: enterprise,
      message: configured && enterprise ? 'AMAPI ready'
        : configured ? 'AMAPI configured but no enterprise'
          : 'AMAPI not configured - set AMAPI_SERVICE_ACCOUNT_JSON and AMAPI_PROJECT_ID',
    });
  }));

  router.post('/amapi/setup', ah(async (req, res) => {
    if (!amapi.isConfigured()) {
      throw httpError(400, 'AMAPI not configured. Set AMAPI_SERVICE_ACCOUNT_JSON and AMAPI_PROJECT_ID environment variables.');
    }
    try {
      const result = await amapi.createEnterprise();
      res.json({
        configured: true,
        enterprise_name: result.enterprise_name,
        message: `Enterprise created! Save this as AMAPI_ENTERPRISE_NAME: ${result.enterprise_name}`,
      });
    } catch (e) {
      throw httpError(500, String(e.message || e));
    }
  }));

  router.post('/amapi/policy', ah(async (req, res) => {
    const enterprise = amapi.getEnterpriseName();
    if (!enterprise) throw httpError(400, 'No enterprise configured. Run /amapi/setup first.');
    try {
      const result = await amapi.createPolicy(enterprise);
      res.json({
        policy_name: result.name || '',
        message: 'Policy created/updated. EMI Locker will be force-installed on enrolled devices.',
      });
    } catch (e) {
      throw httpError(500, String(e.message || e));
    }
  }));

  router.post('/amapi/enrollment-token', ah(async (req, res) => {
    const enterprise = amapi.getEnterpriseName();
    if (!enterprise) throw httpError(400, 'No enterprise configured. Run /amapi/setup first.');
    try {
      const token = await amapi.createEnrollmentToken(enterprise);
      const tokenValue = token.value || '';
      const qrString = token.qrCode || amapi.generateQrString(tokenValue);
      res.json({
        token_value: tokenValue,
        token_name: token.name || '',
        qr_string: qrString,
        expiry: token.expirationTimestamp ?? null,
      });
    } catch (e) {
      throw httpError(500, String(e.message || e));
    }
  }));

  router.get('/amapi/qr', ah(async () => {
    throw httpError(410, {
      error: 'AMAPI QR is permanently disabled for EMI use cases',
      reason: "Google's Permissible Usage Policy bans EMI/device financing from using Android Management API. Enterprise quota = 0.",
      solution: 'Use Custom DPC QR from dashboard (Device Setup → QR Code tab) or USB/ADB method. These have NO Google limits.',
      custom_dpc_qr: '/api/v1/zte/provisioning-qr',
      custom_dpc_data: '/api/v1/zte/provisioning-data',
    });
  }));

  router.get('/amapi/devices', ah(async (req, res) => {
    const enterprise = amapi.getEnterpriseName();
    if (!enterprise) throw httpError(400, 'No enterprise configured.');
    try {
      const devices = await amapi.listDevices(enterprise);
      res.json({ devices, count: devices.length });
    } catch (e) {
      throw httpError(500, String(e.message || e));
    }
  }));

  router.delete('/amapi/devices/*', ah(async (req, res) => {
    const deviceId = req.params[0];
    try {
      const success = await amapi.deleteDevice(deviceId);
      if (success) return res.json({ status: 'deleted', device: deviceId });
      throw httpError(500, `Failed to delete device: ${deviceId}`);
    } catch (e) {
      if (e.status) throw e;
      throw httpError(500, String(e.message || e));
    }
  }));

  router.post('/amapi/cleanup', ah(async (req, res) => {
    const enterprise = amapi.getEnterpriseName();
    if (!enterprise) throw httpError(400, 'No enterprise configured.');
    try {
      const result = await amapi.cleanupAllDevices(enterprise);
      res.json({
        status: 'cleanup_complete',
        total_devices: result.total,
        deleted: result.deleted,
        failed: result.failed,
        message: `Deleted ${result.deleted}/${result.total} devices. You can now enroll new devices.`,
      });
    } catch (e) {
      throw httpError(500, String(e.message || e));
    }
  }));

  router.post('/amapi/new-enterprise', ah(async (req, res) => {
    if (!amapi.isConfigured()) throw httpError(400, 'AMAPI not configured.');
    try {
      const result = await amapi.createNewEnterprise();
      res.json({
        status: 'new_enterprise_created',
        enterprise_name: result.enterprise_name,
        message: `New enterprise created: ${result.enterprise_name}. Update AMAPI_ENTERPRISE_NAME env var.`,
      });
    } catch (e) {
      throw httpError(500, String(e.message || e));
    }
  }));

  // Callback URL for the enterprise signup flow (used internally by Google).
  router.get('/amapi/callback', (req, res) => {
    res.json({ status: 'ok', token: req.query.enterpriseToken || '' });
  });
}
