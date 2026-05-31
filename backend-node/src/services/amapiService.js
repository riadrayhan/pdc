/**
 * Android Management API (AMAPI) service — port of amapi_service.py using the
 * Node `googleapis` client. Used for Google Cloud DPC QR provisioning and
 * enterprise/device administration.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { google } from 'googleapis';
import { settings } from '../config.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCOPES = ['https://www.googleapis.com/auth/androidmanagement'];

const CLOUD_DPC_COMPONENT = 'com.google.android.apps.work.clouddpc/.receivers.CloudDeviceAdminReceiver';
const CLOUD_DPC_SIGNATURE = 'I5YvS0O5hXY46mb01BlRjq4oJJGs2kuUcHvVkAPEXlg';
const CLOUD_DPC_DOWNLOAD = 'https://play.google.com/managed/downloadManagingApp?identifier=setup';

function localServiceAccountPaths() {
  return [
    path.resolve(__dirname, '..', '..', 'service-account.json'),
    path.resolve(__dirname, '..', '..', '..', 'backend', 'service-account.json'),
    'service-account.json',
  ];
}

function loadCredentials() {
  const credValue = settings.AMAPI_SERVICE_ACCOUNT_JSON;
  for (const p of localServiceAccountPaths()) {
    if (fs.existsSync(p)) {
      return JSON.parse(fs.readFileSync(p, 'utf8'));
    }
  }
  if (!credValue) {
    throw new Error('AMAPI_SERVICE_ACCOUNT_JSON not configured.');
  }
  if (fs.existsSync(credValue)) {
    return JSON.parse(fs.readFileSync(credValue, 'utf8'));
  }
  // base64-encoded JSON
  try {
    return JSON.parse(Buffer.from(credValue, 'base64').toString('utf8'));
  } catch { /* fall through */ }
  // raw JSON
  try {
    return JSON.parse(credValue);
  } catch {
    throw new Error('AMAPI_SERVICE_ACCOUNT_JSON is not a valid file path, base64 JSON, or raw JSON.');
  }
}

async function getService() {
  const credentials = loadCredentials();
  const auth = new google.auth.GoogleAuth({ credentials, scopes: SCOPES });
  const authClient = await auth.getClient();
  return google.androidmanagement({ version: 'v1', auth: authClient });
}

export function isConfigured() {
  if (settings.AMAPI_SERVICE_ACCOUNT_JSON && settings.AMAPI_PROJECT_ID) return true;
  if (settings.AMAPI_PROJECT_ID) {
    return localServiceAccountPaths().some((p) => fs.existsSync(p));
  }
  return false;
}

export function getEnterpriseName() {
  return settings.AMAPI_ENTERPRISE_NAME || null;
}

export async function createEnterprise() {
  const service = await getService();
  const { data } = await service.enterprises.create({
    projectId: settings.AMAPI_PROJECT_ID,
    agreementAccepted: true,
    requestBody: { enterpriseDisplayName: 'RR Locker EMI Finance' },
  });
  return { enterprise_name: data.name || '', enterprise: data };
}

export async function createPolicy(enterpriseName, policyName = 'emi-locker-policy') {
  const service = await getService();
  const policy = {
    advancedSecurityOverrides: {
      untrustedAppsPolicy: 'ALLOW_INSTALL_DEVICE_WIDE',
      googlePlayProtectVerifyApps: 'VERIFY_APPS_USER_CHOICE',
      developerSettings: 'DEVELOPER_SETTINGS_ALLOWED',
    },
    factoryResetDisabled: true,
    safeBootDisabled: true,
    screenCaptureDisabled: true,
    addUserDisabled: true,
    removeUserDisabled: true,
    modifyAccountsDisabled: false,
    systemUpdate: { type: 'WINDOWED', startMinutes: 120, endMinutes: 300 },
    skipFirstUseHintsEnabled: true,
    adjustVolumeDisabled: false,
    funDisabled: true,
    networkEscapeHatchEnabled: true,
    playStoreMode: 'BLACKLIST',
  };
  const { data } = await service.enterprises.policies.patch({
    name: `${enterpriseName}/policies/${policyName}`,
    requestBody: policy,
  });
  return data;
}

export async function createEnrollmentToken(enterpriseName, policyName = 'emi-locker-policy') {
  const service = await getService();
  const { data } = await service.enterprises.enrollmentTokens.create({
    parent: enterpriseName,
    requestBody: {
      policyName: `${enterpriseName}/policies/${policyName}`,
      duration: '86400s',
      allowPersonalUsage: 'PERSONAL_USAGE_DISALLOWED',
      oneTimeOnly: false,
    },
  });
  return data;
}

export function generateQrPayload(tokenValue) {
  return {
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': CLOUD_DPC_COMPONENT,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM': CLOUD_DPC_SIGNATURE,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': CLOUD_DPC_DOWNLOAD,
    'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE': {
      'com.google.android.apps.work.clouddpc.EXTRA_ENROLLMENT_TOKEN': tokenValue,
    },
    'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': true,
    'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
  };
}

export function generateQrString(tokenValue) {
  return JSON.stringify(generateQrPayload(tokenValue));
}

export async function listDevices(enterpriseName) {
  const service = await getService();
  const { data } = await service.enterprises.devices.list({ parent: enterpriseName });
  return data.devices || [];
}

export async function deleteDevice(deviceName) {
  const service = await getService();
  try {
    await service.enterprises.devices.delete({ name: deviceName });
    return true;
  } catch {
    return false;
  }
}

export async function cleanupAllDevices(enterpriseName) {
  const devices = await listDevices(enterpriseName);
  let deleted = 0;
  let failed = 0;
  for (const device of devices) {
    if (device.name) {
      // eslint-disable-next-line no-await-in-loop
      if (await deleteDevice(device.name)) deleted += 1; else failed += 1;
    }
  }
  return { total: devices.length, deleted, failed };
}

export async function createNewEnterprise() {
  const service = await getService();
  const { data } = await service.enterprises.create({
    projectId: settings.AMAPI_PROJECT_ID,
    agreementAccepted: true,
    requestBody: { enterpriseDisplayName: 'RR Locker EMI Finance' },
  });
  const newName = data.name || '';
  settings.AMAPI_ENTERPRISE_NAME = newName;
  try {
    await createPolicy(newName);
  } catch { /* best effort */ }
  return { enterprise_name: newName, enterprise: data };
}

export default {
  isConfigured,
  getEnterpriseName,
  createEnterprise,
  createPolicy,
  createEnrollmentToken,
  generateQrPayload,
  generateQrString,
  listDevices,
  deleteDevice,
  cleanupAllDevices,
  createNewEnterprise,
};
