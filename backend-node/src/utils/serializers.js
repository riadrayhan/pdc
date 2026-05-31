import { isoNaiveUtc, isoDate } from '../utils/time.js';

const num = (v) => (v === null || v === undefined ? v : Number(v));

/** Device → DeviceResponse (exact field order/shape of the FastAPI schema). */
export function serializeDevice(d) {
  if (!d) return null;
  return {
    imei: d.imei ?? null,
    imei2: d.imei2 ?? null,
    serial_number: d.serial_number ?? null,
    device_model: d.device_model ?? null,
    manufacturer: d.manufacturer ?? null,
    id: d.id,
    status: d.status,
    is_online: !!d.is_online,
    last_seen: isoNaiveUtc(d.last_seen),
    fcm_token: d.fcm_token ?? null,
    android_version: d.android_version ?? null,
    sdk_version: d.sdk_version ?? null,
    app_version: d.app_version ?? null,
    brand: d.brand ?? null,
    device_name: d.device_name ?? null,
    product: d.product ?? null,
    board: d.board ?? null,
    hardware: d.hardware ?? null,
    build_fingerprint: d.build_fingerprint ?? null,
    android_id: d.android_id ?? null,
    persistent_device_id: d.persistent_device_id ?? null,
    is_device_owner: d.is_device_owner ?? null,
    is_admin_active: d.is_admin_active ?? null,
    factory_reset_count: d.factory_reset_count ?? 0,
    enrolled_at: isoNaiveUtc(d.enrolled_at),
    customer_id: d.customer_id ?? null,
    last_latitude: d.last_latitude ?? null,
    last_longitude: d.last_longitude ?? null,
    last_location_time: isoNaiveUtc(d.last_location_time),
    last_location_address: d.last_location_address ?? null,
    camera_active: d.camera_active ?? false,
    last_photo_url: d.last_photo_url ?? null,
    last_photo_time: isoNaiveUtc(d.last_photo_time),
    is_app_hidden: d.is_app_hidden ?? false,
    is_app_disabled: d.is_app_disabled ?? false,
    battery_level: d.battery_level ?? null,
    is_charging: d.is_charging ?? null,
    network_type: d.network_type ?? null,
    created_at: isoNaiveUtc(d.created_at),
  };
}

/** Customer → CustomerResponse. */
export function serializeCustomer(c) {
  if (!c) return null;
  return {
    full_name: c.full_name,
    phone: c.phone,
    alternate_phone: c.alternate_phone ?? null,
    email: c.email ?? null,
    id_type: c.id_type ?? null,
    address: c.address ?? null,
    city: c.city ?? null,
    state: c.state ?? null,
    pincode: c.pincode ?? null,
    emergency_contact_name: c.emergency_contact_name ?? null,
    emergency_contact_phone: c.emergency_contact_phone ?? null,
    emergency_contact_relation: c.emergency_contact_relation ?? null,
    id: c.id,
    created_at: isoNaiveUtc(c.created_at),
  };
}

/** User → UserResponse (never leaks the password hash). */
export function serializeUser(u) {
  if (!u) return null;
  return {
    email: u.email,
    username: u.username,
    full_name: u.full_name ?? null,
    phone: u.phone ?? null,
    role: u.role,
    id: u.id,
    is_active: !!u.is_active,
    created_at: isoNaiveUtc(u.created_at),
    last_login: isoNaiveUtc(u.last_login),
  };
}

/** EMIContract → EMIContractResponse. */
export function serializeContract(c) {
  if (!c) return null;
  return {
    id: c.id,
    contract_number: c.contract_number,
    customer_id: c.customer_id,
    device_id: c.device_id,
    product_name: c.product_name ?? null,
    product_price: num(c.product_price),
    down_payment: num(c.down_payment),
    principal_amount: num(c.principal_amount),
    interest_rate: num(c.interest_rate),
    tenure_months: c.tenure_months,
    emi_amount: num(c.emi_amount),
    total_amount: num(c.total_amount),
    start_date: isoDate(c.start_date),
    end_date: isoDate(c.end_date),
    emi_due_day: c.emi_due_day,
    status: c.status,
    grace_period_days: c.grace_period_days,
    total_paid: num(c.total_paid),
    emis_paid: c.emis_paid,
    notes: c.notes ?? null,
    created_at: isoNaiveUtc(c.created_at),
  };
}

/** EMIPayment → EMIPaymentResponse. */
export function serializePayment(p) {
  if (!p) return null;
  return {
    id: p.id,
    contract_id: p.contract_id,
    installment_number: p.installment_number,
    due_amount: num(p.due_amount),
    paid_amount: num(p.paid_amount),
    late_fee: num(p.late_fee),
    due_date: isoDate(p.due_date),
    paid_date: isoNaiveUtc(p.paid_date),
    status: p.status,
    payment_method: p.payment_method ?? null,
    payment_reference: p.payment_reference ?? null,
    created_at: isoNaiveUtc(p.created_at),
  };
}

/** DeviceCommand → CommandResponse. */
export function serializeCommand(c) {
  if (!c) return null;
  return {
    id: c.id,
    device_id: c.device_id,
    command_type: c.command_type,
    payload: c.payload ?? {},
    status: c.status,
    fcm_message_id: c.fcm_message_id ?? null,
    created_at: isoNaiveUtc(c.created_at),
    sent_at: isoNaiveUtc(c.sent_at),
    delivered_at: isoNaiveUtc(c.delivered_at),
    executed_at: isoNaiveUtc(c.executed_at),
    error_message: c.error_message ?? null,
    reason: c.reason ?? null,
  };
}

export default {
  serializeDevice,
  serializeCustomer,
  serializeUser,
  serializeContract,
  serializePayment,
  serializeCommand,
};
