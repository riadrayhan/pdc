import fs from 'fs';
import admin from 'firebase-admin';
import { settings } from '../config.js';

let firebaseInitialized = false;

export function initFirebase() {
  if (firebaseInitialized) return;
  try {
    let cred = null;
    if (settings.FIREBASE_CREDENTIALS_JSON) {
      // Allow raw JSON or base64-encoded JSON in the env var.
      let raw = settings.FIREBASE_CREDENTIALS_JSON;
      if (!raw.trim().startsWith('{')) {
        raw = Buffer.from(raw, 'base64').toString('utf8');
      }
      cred = admin.credential.cert(JSON.parse(raw));
    } else if (settings.FIREBASE_CREDENTIALS_PATH && fs.existsSync(settings.FIREBASE_CREDENTIALS_PATH)) {
      const json = JSON.parse(fs.readFileSync(settings.FIREBASE_CREDENTIALS_PATH, 'utf8'));
      cred = admin.credential.cert(json);
    }
    if (cred) {
      admin.initializeApp({ credential: cred });
      firebaseInitialized = true;
      console.log('Firebase initialized successfully');
    } else {
      console.warn(`Firebase credentials not found at ${settings.FIREBASE_CREDENTIALS_PATH}`);
    }
  } catch (e) {
    console.error('Failed to initialize Firebase:', e.message);
  }
}

export const FCMService = {
  /**
   * Send a data-only command to a device. Returns the FCM message id or null.
   * Mirrors the Python FCMService.send_command (high priority, 24h TTL, direct
   * boot OK, all payload values stringified).
   */
  async sendCommand({ fcmToken, commandType, payload = {}, commandId }) {
    try {
      initFirebase();
      if (!firebaseInitialized) return null;

      const data = { command_type: commandType, command_id: String(commandId) };
      for (const [k, v] of Object.entries(payload)) data[k] = String(v);

      const message = {
        data,
        token: fcmToken,
        android: {
          priority: 'high',
          ttl: 86400000, // ms
          directBootOk: true,
        },
      };

      const response = await admin.messaging().send(message);
      console.log(`FCM message sent successfully: ${response}`);
      return response;
    } catch (e) {
      if (e.code === 'messaging/registration-token-not-registered') {
        console.warn('FCM token is invalid or unregistered');
        return null;
      }
      console.error('Failed to send FCM message:', e.message);
      return null;
    }
  },

  /** Ask a device to report its current status (data-only SYNC message). */
  async sendSyncCommand({ fcmToken, commandId }) {
    return this.sendCommand({ fcmToken, commandType: 'SYNC', payload: {}, commandId });
  },

  async sendBulkCommand({ fcmTokens, commandType, payload, commandIds }) {
    const results = { success_count: 0, failure_count: 0, responses: [] };
    for (let i = 0; i < fcmTokens.length; i += 1) {
      const cmdId = commandIds[i];
      // eslint-disable-next-line no-await-in-loop
      const response = await this.sendCommand({
        fcmToken: fcmTokens[i],
        commandType,
        payload,
        commandId: cmdId,
      });
      if (response) {
        results.success_count += 1;
        results.responses.push({ command_id: cmdId, message_id: response });
      } else {
        results.failure_count += 1;
        results.responses.push({ command_id: cmdId, error: 'Failed to send' });
      }
    }
    return results;
  },
};

export default FCMService;
