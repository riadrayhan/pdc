import { Op } from 'sequelize';
import { EMIService, } from '../services/emiService.js';
import { DeviceService } from '../services/deviceService.js';
import {
  EMIContract, EMIPayment, Customer, Device,
} from '../models/index.js';
import { serializeContract, serializePayment } from '../utils/serializers.js';
import { isoDate } from '../utils/time.js';
import { httpError, requireUser, requireAdmin } from '../middleware/auth.js';
import { toEnumName } from '../utils/enums.js';
import { ah } from './auth.js';

const CONTRACT_UPDATABLE = ['grace_period_days', 'notes', 'status'];

function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

function daysBetween(fromDate, toDate) {
  const a = new Date(`${fromDate}T00:00:00Z`).getTime();
  const b = new Date(`${toDate}T00:00:00Z`).getTime();
  return Math.round((b - a) / 86400000);
}

export default function registerEmiRoutes(router) {
  router.post('/emi/contracts', requireUser, ah(async (req, res) => {
    const contract = await EMIService.createContract(req.body || {}, req.user.id);
    res.status(201).json(serializeContract(contract));
  }));

  router.get('/emi/contracts', requireUser, ah(async (req, res) => {
    const skip = Math.max(0, parseInt(req.query.skip, 10) || 0);
    let limit = parseInt(req.query.limit, 10);
    limit = Number.isNaN(limit) ? 50 : Math.min(100, Math.max(1, limit));
    const where = {};
    if (req.query.status) where.status = toEnumName(req.query.status);
    if (req.query.customer_id) where.customer_id = req.query.customer_id;
    const { count, rows } = await EMIContract.findAndCountAll({
      where, order: [['created_at', 'DESC']], offset: skip, limit,
    });
    res.json({ total: count, contracts: rows.map(serializeContract) });
  }));

  router.get('/emi/contracts/:contractId', requireUser, ah(async (req, res) => {
    const contract = await EMIContract.findByPk(req.params.contractId);
    if (!contract) throw httpError(404, 'Contract not found');
    res.json(serializeContract(contract));
  }));

  router.put('/emi/contracts/:contractId', requireAdmin, ah(async (req, res) => {
    const contract = await EMIContract.findByPk(req.params.contractId);
    if (!contract) throw httpError(404, 'Contract not found');
    const data = req.body || {};
    for (const field of CONTRACT_UPDATABLE) {
      if (Object.prototype.hasOwnProperty.call(data, field)) {
        contract[field] = field === 'status' ? String(data[field]).toLowerCase() : data[field];
      }
    }
    await contract.save();
    res.json(serializeContract(contract));
  }));

  router.get('/emi/contracts/:contractId/payments', requireUser, ah(async (req, res) => {
    const payments = await EMIPayment.findAll({
      where: { contract_id: req.params.contractId },
      order: [['installment_number', 'ASC']],
    });
    res.json({ total: payments.length, payments: payments.map(serializePayment) });
  }));

  router.post('/emi/payments/:paymentId/record', requireUser, ah(async (req, res) => {
    const payment = await EMIService.recordPayment(req.params.paymentId, req.body || {}, req.user.id);
    if (!payment) throw httpError(404, 'Payment not found');

    if (payment.status === 'paid') {
      const contract = await EMIContract.findByPk(payment.contract_id);
      const overdue = await EMIPayment.count({
        where: {
          contract_id: contract.id,
          due_date: { [Op.lt]: todayStr() },
          status: { [Op.in]: [toEnumName('pending'), toEnumName('partial')] },
        },
      });
      if (overdue === 0) {
        await DeviceService.updateDeviceStatus(
          contract.device_id, 'active', req.user.id, 'Payment received - all dues cleared',
        );
      }
    }
    res.json(serializePayment(payment));
  }));

  router.get('/emi/overdue', requireUser, ah(async (req, res) => {
    const payments = await EMIService.getOverduePayments();
    const reports = [];
    for (const payment of payments) {
      const contract = await EMIContract.findByPk(payment.contract_id);
      if (!contract) continue;
      const customer = await Customer.findByPk(contract.customer_id);
      const device = await Device.findByPk(contract.device_id);
      reports.push({
        contract_id: contract.id,
        contract_number: contract.contract_number,
        customer_name: customer ? customer.full_name : '',
        customer_phone: customer ? customer.phone : '',
        device_imei: device && device.imei ? device.imei : 'N/A',
        due_date: isoDate(payment.due_date),
        days_overdue: daysBetween(isoDate(payment.due_date), todayStr()),
        amount_due: Number(payment.due_amount) - Number(payment.paid_amount),
        device_status: device ? device.status : 'unknown',
      });
    }
    res.json(reports);
  }));

  router.get('/emi/due-soon', requireUser, ah(async (req, res) => {
    let days = parseInt(req.query.days, 10);
    days = Number.isNaN(days) ? 3 : Math.min(30, Math.max(1, days));
    const payments = await EMIService.getPaymentsDueSoon(days);
    res.json(payments.map(serializePayment));
  }));

  router.delete('/emi/contracts/:contractId', requireAdmin, ah(async (req, res) => {
    const contract = await EMIContract.findByPk(req.params.contractId);
    if (!contract) throw httpError(404, 'Contract not found');
    await EMIPayment.destroy({ where: { contract_id: req.params.contractId } });
    await contract.destroy();
    res.status(204).end();
  }));

  // Aggregated stats for the dashboard landing page.
  router.get('/emi/dashboard/stats', requireUser, ah(async (req, res) => {
    const today = todayStr();
    const monthStart = `${today.slice(0, 7)}-01`;

    const [
      totalDevices, lockedDevices, onlineDevices,
      activeContracts, totalCustomers, overduePayments,
    ] = await Promise.all([
      Device.count(),
      Device.count({ where: { status: toEnumName('locked') } }),
      Device.count({ where: { is_online: true } }),
      EMIContract.count({ where: { status: toEnumName('active') } }),
      Customer.count(),
      EMIPayment.count({
        where: {
          due_date: { [Op.lt]: today },
          status: { [Op.in]: [toEnumName('pending'), toEnumName('partial')] },
        },
      }),
    ]);

    const paidThisMonth = await EMIPayment.findAll({
      where: {
        paid_date: { [Op.gte]: new Date(`${monthStart}T00:00:00Z`) },
        status: toEnumName('paid'),
      },
      attributes: ['paid_amount'],
      raw: true,
    });
    const monthlyCollection = paidThisMonth.reduce(
      (sum, p) => sum + Number(p.paid_amount || 0), 0,
    );

    res.json({
      total_devices: totalDevices,
      locked_devices: lockedDevices,
      online_devices: onlineDevices,
      active_contracts: activeContracts,
      total_customers: totalCustomers,
      overdue_payments: overduePayments,
      monthly_collection: monthlyCollection,
    });
  }));
}
