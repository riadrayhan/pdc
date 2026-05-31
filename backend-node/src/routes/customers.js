import { CustomerService } from '../services/deviceService.js';
import { EMIContract, EMIPayment } from '../models/index.js';
import { serializeCustomer } from '../utils/serializers.js';
import { httpError, requireUser } from '../middleware/auth.js';
import { ah } from './auth.js';

const UPDATABLE = [
  'full_name', 'phone', 'alternate_phone', 'email', 'address', 'city', 'state',
  'pincode', 'emergency_contact_name', 'emergency_contact_phone', 'emergency_contact_relation',
];

export default function registerCustomerRoutes(router) {
  router.post('/customers', requireUser, ah(async (req, res) => {
    const data = req.body || {};
    const existing = await CustomerService.getCustomerByPhone(data.phone);
    if (existing) throw httpError(400, 'Customer with this phone number already exists');
    const customer = await CustomerService.createCustomer(data);
    res.status(201).json(serializeCustomer(customer));
  }));

  router.get('/customers', requireUser, ah(async (req, res) => {
    const skip = Math.max(0, parseInt(req.query.skip, 10) || 0);
    let limit = parseInt(req.query.limit, 10);
    limit = Number.isNaN(limit) ? 50 : Math.min(100, Math.max(1, limit));
    const search = req.query.search || null;
    const [customers, total] = await CustomerService.listCustomers({ skip, limit, search });
    res.json({ total, customers: customers.map(serializeCustomer) });
  }));

  router.get('/customers/:customerId', requireUser, ah(async (req, res) => {
    const customer = await CustomerService.getCustomerById(req.params.customerId);
    if (!customer) throw httpError(404, 'Customer not found');
    res.json(serializeCustomer(customer));
  }));

  router.put('/customers/:customerId', requireUser, ah(async (req, res) => {
    const customer = await CustomerService.getCustomerById(req.params.customerId);
    if (!customer) throw httpError(404, 'Customer not found');
    const data = req.body || {};
    for (const field of UPDATABLE) {
      if (Object.prototype.hasOwnProperty.call(data, field)) customer[field] = data[field];
    }
    await customer.save();
    res.json(serializeCustomer(customer));
  }));

  router.delete('/customers/:customerId', requireUser, ah(async (req, res) => {
    const customer = await CustomerService.getCustomerById(req.params.customerId);
    if (!customer) throw httpError(404, 'Customer not found');
    const contracts = await EMIContract.findAll({ where: { customer_id: customer.id } });
    for (const c of contracts) {
      await EMIPayment.destroy({ where: { contract_id: c.id } });
      await c.destroy();
    }
    await customer.destroy();
    res.status(204).end();
  }));
}
