import { Op } from 'sequelize';
import crypto from 'crypto';
import {
  EMIContract, EMIPayment, Device,
} from '../models/index.js';
import { DeviceService } from './deviceService.js';
import { utcNow } from '../utils/time.js';

/** Add whole months to a "YYYY-MM-DD" date string, clamping the day. */
function addMonths(dateStr, months, dayOverride = null) {
  const d = new Date(`${dateStr}T00:00:00Z`);
  const targetMonth = d.getUTCMonth() + months;
  const year = d.getUTCFullYear() + Math.floor(targetMonth / 12);
  const month = ((targetMonth % 12) + 12) % 12;
  let day = dayOverride != null ? dayOverride : d.getUTCDate();
  const lastDay = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
  day = Math.min(day, lastDay);
  const mm = String(month + 1).padStart(2, '0');
  const dd = String(day).padStart(2, '0');
  return `${year}-${mm}-${dd}`;
}

function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}

export const EMIService = {
  generateContractNumber() {
    const ts = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    return `EMI-${ts}-${crypto.randomUUID().replace(/-/g, '').slice(0, 8).toUpperCase()}`;
  },

  calculateEmi(principal, rate, tenure) {
    const p = Number(principal);
    const r = Number(rate);
    if (r === 0) return p / tenure;
    const totalInterest = p * (r / 100) * (tenure / 12);
    const totalAmount = p + totalInterest;
    return round2(totalAmount / tenure);
  },

  async createContract(data, userId = null) {
    const principal = round2(Number(data.product_price) - Number(data.down_payment));
    const emiAmount = this.calculateEmi(principal, data.interest_rate, data.tenure_months);
    const totalAmount = round2(emiAmount * data.tenure_months);
    const endDate = addMonths(data.start_date, data.tenure_months);

    const contract = await EMIContract.create({
      contract_number: this.generateContractNumber(),
      customer_id: data.customer_id,
      device_id: data.device_id,
      product_name: data.product_name,
      product_price: data.product_price,
      down_payment: data.down_payment,
      principal_amount: principal,
      interest_rate: data.interest_rate,
      tenure_months: data.tenure_months,
      emi_amount: emiAmount,
      total_amount: totalAmount,
      start_date: data.start_date,
      end_date: endDate,
      emi_due_day: data.emi_due_day,
      grace_period_days: data.grace_period_days,
      notes: data.notes ?? null,
      created_by: userId,
    });

    const dueDay = Math.min(data.emi_due_day, 28);
    const payments = [];
    for (let i = 0; i < data.tenure_months; i += 1) {
      payments.push({
        contract_id: contract.id,
        installment_number: i + 1,
        due_amount: emiAmount,
        due_date: addMonths(data.start_date, i + 1, dueDay),
        status: 'pending',
      });
    }
    await EMIPayment.bulkCreate(payments);

    const device = await Device.findByPk(data.device_id);
    if (device) {
      device.customer_id = data.customer_id;
      await device.save();
    }

    return contract;
  },

  async recordPayment(paymentId, data, userId = null) {
    const payment = await EMIPayment.findByPk(paymentId);
    if (!payment) return null;

    payment.paid_amount = data.paid_amount;
    payment.late_fee = data.late_fee ?? 0;
    payment.payment_method = data.payment_method ?? null;
    payment.payment_reference = data.payment_reference ?? null;
    payment.paid_date = utcNow();
    payment.recorded_by = userId;

    payment.status = Number(payment.paid_amount) >= Number(payment.due_amount) ? 'paid' : 'partial';

    const contract = await EMIContract.findByPk(payment.contract_id);
    contract.total_paid = round2(Number(contract.total_paid) + Number(payment.paid_amount));
    if (payment.status === 'paid') contract.emis_paid += 1;

    if (contract.emis_paid >= contract.tenure_months) {
      contract.status = 'completed';
      const device = await Device.findByPk(contract.device_id);
      if (device && device.status === 'locked') {
        await DeviceService.updateDeviceStatus(device.id, 'active', userId, 'Contract completed');
      }
    }

    await contract.save();
    await payment.save();
    return payment;
  },

  getOverduePayments(asOfDate = null) {
    const cutoff = asOfDate || todayStr();
    return EMIPayment.findAll({
      where: {
        due_date: { [Op.lt]: cutoff },
        status: { [Op.in]: ['PENDING', 'PARTIAL'] },
      },
    });
  },

  getPaymentsDueSoon(days = 3) {
    const today = todayStr();
    const dueDate = addMonths(today, 0); // base
    const d = new Date(`${today}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + days);
    const future = d.toISOString().slice(0, 10);
    return EMIPayment.findAll({
      where: {
        due_date: { [Op.lte]: future, [Op.gte]: today },
        status: 'PENDING',
      },
    });
  },
};

export default EMIService;
