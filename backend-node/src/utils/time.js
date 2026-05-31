/**
 * Format a Date (or ISO string) the way FastAPI/Pydantic serialized naive-UTC
 * datetimes: "YYYY-MM-DDTHH:MM:SS.ffffff" — microsecond precision, no timezone
 * suffix. The dashboard & Android app parse this exact shape today.
 */
export function isoNaiveUtc(value) {
  if (value === null || value === undefined) return null;
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  const pad = (n, w = 2) => String(n).padStart(w, '0');
  const yyyy = d.getUTCFullYear();
  const MM = pad(d.getUTCMonth() + 1);
  const DD = pad(d.getUTCDate());
  const hh = pad(d.getUTCHours());
  const mm = pad(d.getUTCMinutes());
  const ss = pad(d.getUTCSeconds());
  const micro = pad(d.getUTCMilliseconds() * 1000, 6);
  return `${yyyy}-${MM}-${DD}T${hh}:${mm}:${ss}.${micro}`;
}

/** Format a Date as a plain date "YYYY-MM-DD" (Pydantic date fields). */
export function isoDate(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value.slice(0, 10);
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/** A new naive-UTC Date for "now" (DB writes). */
export function utcNow() {
  return new Date();
}
