export const DEFAULT_BASE_URL = 'http://localhost:8080';
export const DEFAULT_USER_ID = '22222222-2222-2222-2222-222222222222';
export const DEFAULT_ALICE_USER_ID = '22222222-2222-2222-2222-222222222222';
export const DEFAULT_BOB_USER_ID = '33333333-3333-3333-3333-333333333333';
export const OUTSIDER_USER_ID = '44444444-4444-4444-4444-444444444444';
export const UNKNOWN_UUID = '99999999-9999-9999-9999-999999999999';

export function config(defaults = {}) {
  return {
    baseUrl: (__ENV.BASE_URL || DEFAULT_BASE_URL).replace(/\/$/, ''),
    userId: __ENV.USER_ID || DEFAULT_USER_ID,
    aliceUserId: __ENV.ALICE_USER_ID || DEFAULT_ALICE_USER_ID,
    bobUserId: __ENV.BOB_USER_ID || DEFAULT_BOB_USER_ID,
    outsiderUserId: __ENV.OUTSIDER_USER_ID || OUTSIDER_USER_ID,
    seedPots: numberEnv('SEED_POTS', defaults.seedPots || 4),
    hotPots: numberEnv('HOT_POTS', defaults.hotPots || 2),
    scrapeIntervalSeconds: numberEnv('SCRAPE_INTERVAL_SECONDS', defaults.scrapeIntervalSeconds || 10),
    labelPrefix: __ENV.LABEL_PREFIX || 'K6',
  };
}

export function numberEnv(name, fallback) {
  const value = Number(__ENV[name]);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

export function runId() {
  return `${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
}

export function label(prefix, type, id, iteration) {
  return `${prefix} ${type} ${id} ${iteration}`;
}

export function pick(items, salt = 0) {
  if (!items || items.length === 0) {
    throw new Error('Cannot pick from an empty collection');
  }
  const index = Math.abs((__VU || 0) + (__ITER || 0) + salt) % items.length;
  return items[index];
}

export function hotItems(items, hotCount) {
  return items.slice(0, Math.max(1, Math.min(hotCount, items.length)));
}
