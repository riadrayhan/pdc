/**
 * Scheduled background jobs — replaces the Celery beat tasks in
 * backend/app/tasks/emi_tasks.py. Implemented with node-cron.
 *
 * Schedule (matching the original Celery beat config):
 *   - check overdue payments        every hour
 *   - auto-lock overdue devices     every hour
 *   - send payment reminders        every day
 *   - sync stale device status      every 30 minutes
 *   - retry failed commands         every 15 minutes
 *   - retry undelivered commands    every 15 minutes
 */
import cron from 'node-cron';
import { Op } from 'sequelize';
import {
  Device, EMIContract, EMIPayment, DeviceCommand,
} from '../models/index.js';
import { toEnumName } from '../utils/enums.js';
import { isoDate, utcNow } from '../utils/time.js';
import { CommandService } from './commandService.js';
import { FCMService } from './fcmService.js';

function dateMinusDays(days) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - days);
  return isoDate(d);
}

function datePlusDays(days) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return isoDate(d);
}

export async function checkOverduePayments() {
  const today = isoDate(new Date());
  const [count] = await EMIPayment.update(
    { status: toEnumName('overdue') },
    { where: { due_date: { [Op.lt]: today }, status: toEnumName('pending') } },
  );
  console.log(`[cron] Marked ${count} payments as overdue`);
  return { marked_overdue: count };
}

export async function sendPaymentReminders() {
  const reminderDate = datePlusDays(3);
  const payments = await EMIPayment.findAll({
    where: { due_date: reminderDate, status: toEnumName('pending') },
  });
  let sent = 0;
  for (const payment of payments) {
    // eslint-disable-next-line no-await-in-loop
    const contract = await EMIContract.findByPk(payment.contract_id);
    if (!contract) continue;
    // eslint-disable-next-line no-await-in-loop
    const device = await Device.findByPk(contract.device_id);
    if (device && device.fcm_token) {
      // eslint-disable-next-line no-await-in-loop
      await CommandService.createWarningCommand(
        device,
        'EMI Payment Reminder',
        `Your EMI payment of ₹${payment.due_amount} is due on ${isoDate(payment.due_date)}. Please pay on time to avoid device lock.`,
        String(isoDate(payment.due_date)),
        String(payment.due_amount),
      );
      sent += 1;
    }
  }
  console.log(`[cron] Sent ${sent} payment reminders`);
  return { reminders_sent: sent };
}

export async function autoLockOverdueDevices() {
  const today = new Date();
  let locked = 0;
  const contracts = await EMIContract.findAll({ where: { status: toEnumName('active') } });
  for (const contract of contracts) {
    const graceDays = parseInt(contract.grace_period_days, 10) || 0;
    const graceCutoff = isoDate(new Date(today.getTime() - graceDays * 86400000));
    // eslint-disable-next-line no-await-in-loop
    const overdue = await EMIPayment.findOne({
      where: {
        contract_id: contract.id,
        due_date: { [Op.lt]: graceCutoff },
        status: { [Op.in]: [toEnumName('pending'), toEnumName('overdue'), toEnumName('partial')] },
      },
    });
    if (overdue) {
      // eslint-disable-next-line no-await-in-loop
      const device = await Device.findByPk(contract.device_id);
      if (device && device.status !== 'locked') {
        // eslint-disable-next-line no-await-in-loop
        await CommandService.createLockCommand(
          device,
          null,
          `Auto-lock: Payment overdue since ${isoDate(overdue.due_date)}`,
          `Device locked due to overdue payment. Amount due: ₹${overdue.due_amount}. Contact support to unlock.`,
          '',
        );
        device.status = 'locked';
        // eslint-disable-next-line no-await-in-loop
        await device.save();
        locked += 1;
        console.log(`[cron] Auto-locked device ${device.imei} for contract ${contract.contract_number}`);
      }
    }
  }
  console.log(`[cron] Auto-locked ${locked} devices`);
  return { devices_locked: locked };
}

export async function syncDeviceStatus() {
  const cutoff = new Date(utcNow().getTime() - 6 * 3600 * 1000);
  const stale = await Device.findAll({
    where: {
      status: { [Op.in]: [toEnumName('active'), toEnumName('locked'), toEnumName('warning')] },
      last_seen: { [Op.lt]: cutoff },
      fcm_token: { [Op.ne]: null },
    },
  });
  let synced = 0;
  for (const device of stale) {
    if (device.fcm_token) {
      // eslint-disable-next-line no-await-in-loop
      await FCMService.sendSyncCommand({ fcmToken: device.fcm_token, commandId: String(device.id) });
      device.is_online = false;
      // eslint-disable-next-line no-await-in-loop
      await device.save();
      synced += 1;
    }
  }
  console.log(`[cron] Sent sync requests to ${synced} stale devices`);
  return { sync_requests_sent: synced };
}

export async function retryFailedCommands() {
  const cutoff = new Date(utcNow().getTime() - 24 * 3600 * 1000);
  const failed = await DeviceCommand.findAll({
    where: { status: toEnumName('failed'), created_at: { [Op.gt]: cutoff } },
  });
  let retried = 0;
  for (const command of failed) {
    const current = parseInt(command.retry_count || '0', 10);
    const max = parseInt(command.max_retries || '3', 10);
    if (current >= max) continue;
    // eslint-disable-next-line no-await-in-loop
    const device = await Device.findByPk(command.device_id);
    if (device && device.fcm_token) {
      // eslint-disable-next-line no-await-in-loop
      const messageId = await FCMService.sendCommand({
        fcmToken: device.fcm_token,
        commandType: String(command.command_type).toUpperCase(),
        payload: command.payload || {},
        commandId: String(command.id),
      });
      if (messageId) {
        command.fcm_message_id = messageId;
        command.status = toEnumName('sent');
        command.sent_at = utcNow();
        command.error_message = null;
        retried += 1;
      }
      command.retry_count = String(current + 1);
      // eslint-disable-next-line no-await-in-loop
      await command.save();
    }
  }
  console.log(`[cron] Retried ${retried} failed commands`);
  return { commands_retried: retried };
}

export async function retryUndeliveredCommands() {
  const staleCutoff = new Date(utcNow().getTime() - 30 * 60 * 1000);
  const maxAge = new Date(utcNow().getTime() - 24 * 3600 * 1000);
  const stale = await DeviceCommand.findAll({
    where: {
      status: { [Op.in]: [toEnumName('pending'), toEnumName('sent')] },
      created_at: { [Op.lt]: staleCutoff, [Op.gt]: maxAge },
    },
  });
  let resent = 0;
  for (const command of stale) {
    // eslint-disable-next-line no-await-in-loop
    const device = await Device.findByPk(command.device_id);
    if (device && device.fcm_token) {
      const current = parseInt(command.retry_count || '0', 10);
      const max = parseInt(command.max_retries || '3', 10);
      if (current >= max) {
        command.status = toEnumName('failed');
        command.error_message = 'Max retries exceeded (undelivered)';
        // eslint-disable-next-line no-await-in-loop
        await command.save();
        continue;
      }
      // eslint-disable-next-line no-await-in-loop
      const messageId = await FCMService.sendCommand({
        fcmToken: device.fcm_token,
        commandType: String(command.command_type).toUpperCase(),
        payload: command.payload || {},
        commandId: String(command.id),
      });
      if (messageId) {
        command.fcm_message_id = messageId;
        command.status = toEnumName('sent');
        command.sent_at = utcNow();
        resent += 1;
      }
      command.retry_count = String(current + 1);
      // eslint-disable-next-line no-await-in-loop
      await command.save();
    }
  }
  console.log(`[cron] Re-sent ${resent}/${stale.length} stale commands`);
  return { commands_resent: resent, stale_total: stale.length };
}

async function safe(fn) {
  try {
    await fn();
  } catch (e) {
    console.error(`[cron] ${fn.name} failed:`, e.message);
  }
}

export function startCron() {
  // Every hour
  cron.schedule('0 * * * *', () => { safe(checkOverduePayments); safe(autoLockOverdueDevices); });
  // Every day at 09:00 UTC
  cron.schedule('0 9 * * *', () => safe(sendPaymentReminders));
  // Every 30 minutes
  cron.schedule('*/30 * * * *', () => safe(syncDeviceStatus));
  // Every 15 minutes
  cron.schedule('*/15 * * * *', () => { safe(retryFailedCommands); safe(retryUndeliveredCommands); });
  console.log('[cron] Scheduled background jobs started');
}

export default { startCron };
