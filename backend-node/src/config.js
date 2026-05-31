import dotenv from 'dotenv';
dotenv.config();

const bool = (v, def = false) => {
  if (v === undefined || v === null || v === '') return def;
  return ['1', 'true', 'yes', 'on'].includes(String(v).toLowerCase());
};
const int = (v, def) => (v === undefined || v === '' ? def : parseInt(v, 10));
const list = (v, def = []) =>
  v ? String(v).split(',').map((s) => s.trim()).filter(Boolean) : def;

export const settings = {
  APP_NAME: process.env.APP_NAME || 'RR Locker API',
  VERSION: process.env.VERSION || '1.0.0',
  DEBUG: bool(process.env.DEBUG, true),
  PORT: int(process.env.PORT, 7860),

  DATABASE_URL: process.env.DATABASE_URL || 'sqlite:///./emi_locker.db',

  SECRET_KEY: process.env.SECRET_KEY || 'your-super-secret-key-change-in-production',
  ALGORITHM: process.env.ALGORITHM || 'HS256',
  ACCESS_TOKEN_EXPIRE_MINUTES: int(process.env.ACCESS_TOKEN_EXPIRE_MINUTES, 30),
  REFRESH_TOKEN_EXPIRE_DAYS: int(process.env.REFRESH_TOKEN_EXPIRE_DAYS, 7),

  FIREBASE_CREDENTIALS_PATH: process.env.FIREBASE_CREDENTIALS_PATH || 'service-account.json',
  FIREBASE_CREDENTIALS_JSON: process.env.FIREBASE_CREDENTIALS_JSON || '',

  DEFAULT_GRACE_PERIOD_DAYS: int(process.env.DEFAULT_GRACE_PERIOD_DAYS, 7),
  WARNING_DAYS_BEFORE_DUE: int(process.env.WARNING_DAYS_BEFORE_DUE, 3),

  APK_DOWNLOAD_URL:
    process.env.APK_DOWNLOAD_URL ||
    'https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download',
  APK_CHECKSUM: process.env.APK_CHECKSUM || '9pQNHmp25kjdJUjvb9wHIGlFBR3I2p9I2j8QJXlGdmI',
  APK_SIG_CHECKSUM: process.env.APK_SIG_CHECKSUM || 'M3cJdKiSRbG7UPF_EGalAIPWoFlc-86PsVrVtj6jDA4',
  APK_DIR: process.env.APK_DIR || '',
  APP_VERSION: process.env.APP_VERSION || '2.0.0',

  CORS_ORIGINS: list(process.env.CORS_ORIGINS, [
    'https://riadrayhan111-rr-locker-dashboard.static.hf.space',
    'https://riadrayhan111-rr-locker-api.hf.space',
    'http://localhost:3000',
    'http://localhost:5173',
  ]),

  AMAPI_SERVICE_ACCOUNT_JSON: process.env.AMAPI_SERVICE_ACCOUNT_JSON || '',
  AMAPI_PROJECT_ID: process.env.AMAPI_PROJECT_ID || 'blooger-project',
  AMAPI_ENTERPRISE_NAME: process.env.AMAPI_ENTERPRISE_NAME || 'enterprises/LC015xh0ii',

  APP_EXTERNAL_URL: process.env.APP_EXTERNAL_URL || '',
};

export default settings;
