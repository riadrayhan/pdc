import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import QRCode from 'qrcode';
import { settings } from '../config.js';
import { ah } from './auth.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const APK_DIR = settings.APK_DIR || path.resolve(__dirname, '..', '..', 'apk');
const APK_FILENAME = 'app-release.apk';
const APK_DOWNLOAD_URL = 'https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download';
const DEVICE_ADMIN_COMPONENT = 'com.riad.rrlkr/com.riad.rrlkr.admin.EMIDeviceAdminReceiver';

function apkPath() {
  return path.join(APK_DIR, APK_FILENAME);
}

function baseUrl(req) {
  const proto = req.headers['x-forwarded-proto'] || req.protocol;
  return `${proto}://${req.get('host')}`;
}

function installHtml(downloadUrl) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>RR Locker - Install</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{background:#fff;border-radius:24px;padding:32px 24px;max-width:400px;width:100%;box-shadow:0 25px 50px rgba(0,0,0,.25);text-align:center}
.logo{width:80px;height:80px;background:linear-gradient(135deg,#667eea,#764ba2);border-radius:20px;display:flex;align-items:center;justify-content:center;margin:0 auto 20px;font-size:36px;color:#fff}
h1{font-size:24px;color:#1a1a2e;margin-bottom:8px}
.subtitle{color:#666;font-size:14px;margin-bottom:24px}
.download-btn{display:block;width:100%;padding:16px;background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;border:none;border-radius:14px;font-size:18px;font-weight:600;cursor:pointer;text-decoration:none;margin-bottom:12px}
.status{padding:12px 16px;border-radius:12px;margin-bottom:20px;font-size:14px;font-weight:500}
.status.downloading{background:#e8f5e9;color:#2e7d32}
.status.waiting{background:#fff3e0;color:#e65100}
.hidden{display:none}
.steps{text-align:left;margin-top:24px;border-top:1px solid #eee;padding-top:20px}
.step{display:flex;align-items:flex-start;gap:12px;margin-bottom:14px}
.step-num{width:28px;height:28px;background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:700;flex-shrink:0}
.step-text{font-size:14px;color:#444;line-height:1.5;padding-top:3px}
</style>
</head>
<body>
<div class="card">
<div class="logo">&#128274;</div>
<h1>RR Locker</h1>
<p class="subtitle">Device Management &amp; Protection App</p>
<div id="statusWaiting" class="status waiting">&#9889; Ready to download. Tap the button below.</div>
<div id="statusDownloading" class="status downloading hidden">&#9989; Download started! Check your notification bar.</div>
<a id="downloadBtn" href="${downloadUrl}" class="download-btn" onclick="startDownload()"><span class="icon">&#11015;</span> Download RR Locker</a>
<div class="steps">
<div class="step"><div class="step-num">1</div><div class="step-text">Tap <strong>"Download RR Locker"</strong> above</div></div>
<div class="step"><div class="step-num">2</div><div class="step-text">When done, open the notification or the <strong>Downloads</strong> folder</div></div>
<div class="step"><div class="step-num">3</div><div class="step-text">If prompted, enable <strong>"Allow from this source"</strong>, then tap <strong>"Install"</strong></div></div>
<div class="step"><div class="step-num">4</div><div class="step-text">Open the app and enter your <strong>Server URL</strong> to enroll</div></div>
<div class="step"><div class="step-num">5</div><div class="step-text">Grant all permissions when asked &ndash; required for protection</div></div>
</div>
</div>
<script>
setTimeout(startDownload,1500);
function startDownload(){document.getElementById('statusWaiting').classList.add('hidden');document.getElementById('statusDownloading').classList.remove('hidden');window.location.href='${downloadUrl}';}
</script>
</body>
</html>`;
}

export default function registerAppDistributionRoutes(router) {
  // Direct APK download (GET + HEAD). Falls back to redirect if file missing.
  const serveDownload = (req, res) => {
    const p = apkPath();
    if (fs.existsSync(p) && fs.statSync(p).isFile()) {
      const size = fs.statSync(p).size;
      res.set({
        'Content-Disposition': `attachment; filename=${APK_FILENAME}`,
        'Accept-Ranges': 'bytes',
        'Cache-Control': 'public, max-age=3600',
        'Content-Type': 'application/vnd.android.package-archive',
      });
      if (req.method === 'HEAD') {
        res.set('Content-Length', String(size));
        return res.end();
      }
      return res.sendFile(p);
    }
    return res.redirect(302, APK_DOWNLOAD_URL);
  };
  router.get('/app/download', serveDownload);
  router.head('/app/download', serveDownload);

  router.get('/app/download-local', ah(async (req, res) => {
    const p = apkPath();
    if (!fs.existsSync(p)) return res.json({ error: 'APK file not found', path: p });
    return res.download(p, APK_FILENAME, {
      headers: { 'Content-Type': 'application/vnd.android.package-archive' },
    });
  }));

  router.get('/app/install', (req, res) => {
    const downloadUrl = `${baseUrl(req)}/api/v1/app/download`;
    res.type('html').send(installHtml(downloadUrl));
  });

  router.get('/app/qr-code', ah(async (req, res) => {
    const installUrl = `${baseUrl(req)}/api/v1/app/install`;
    const buf = await QRCode.toBuffer(installUrl, {
      errorCorrectionLevel: 'H',
      margin: 4,
      scale: 10,
      color: { dark: '#1a1a2e', light: '#ffffff' },
    });
    res.set({ 'Content-Type': 'image/png', 'Content-Disposition': 'inline; filename=rrlocker-qr.png', 'Cache-Control': 'no-cache' });
    res.end(buf);
  }));

  router.get('/app/info', (req, res) => {
    const p = apkPath();
    const exists = fs.existsSync(p);
    const size = exists ? fs.statSync(p).size : 0;
    res.json({
      app_name: 'RR Locker',
      apk_available: true,
      apk_size_mb: exists ? Math.round((size / (1024 * 1024)) * 100) / 100 : 9.6,
      download_url: APK_DOWNLOAD_URL,
      install_page_url: `${baseUrl(req)}/api/v1/app/install`,
      qr_code_url: `${baseUrl(req)}/api/v1/app/qr-code`,
      local_download_url: `${baseUrl(req)}/api/v1/app/download-local`,
      device_admin_component: DEVICE_ADMIN_COMPONENT,
    });
  });

  router.get('/app/check-update', (req, res) => {
    const p = apkPath();
    const currentVersion = parseInt(req.query.current_version, 10) || 0;
    const versionFile = path.join(APK_DIR, 'version.json');
    let latestVersion = 2;
    let latestVersionName = '2.0.0';
    let changelog = '';
    let forceUpdate = false;
    let minSupportedVersion = 1;
    if (fs.existsSync(versionFile)) {
      try {
        const info = JSON.parse(fs.readFileSync(versionFile, 'utf8'));
        latestVersion = info.version_code ?? latestVersion;
        latestVersionName = info.version_name ?? latestVersionName;
        changelog = info.changelog ?? '';
        forceUpdate = info.force_update ?? false;
        minSupportedVersion = info.min_supported_version ?? 1;
      } catch { /* ignore */ }
    }
    const hasUpdate = currentVersion < latestVersion;
    const apkAvailable = fs.existsSync(p);
    const apkSize = apkAvailable ? fs.statSync(p).size : 0;
    if (currentVersion < minSupportedVersion) forceUpdate = true;
    res.json({
      has_update: hasUpdate,
      latest_version: latestVersion,
      latest_version_name: latestVersionName,
      current_version: currentVersion,
      apk_url: APK_DOWNLOAD_URL,
      apk_size: apkSize,
      force_update: forceUpdate && hasUpdate,
      changelog,
      min_supported_version: minSupportedVersion,
    });
  });
}
