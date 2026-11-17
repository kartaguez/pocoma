// ==============================================================================
// Pocoma V3 — Offline qualification of the bench oracles (no network I/O).
// Usage: k6 run --quiet scripts/k6/v3-oracles-selftest.js
// Each case pairs a discriminating negative with a positive control so that an
// oracle that always fails (or always passes) is detected.
// ==============================================================================
import { check } from 'k6';
import { V3Oracles as O } from './lib/v3-oracles.js';
import { classify503 } from './lib/v3-api.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

const P = '11111111-1111-4111-8111-111111111111';
const S1 = 'aaaaaaaa-0000-4000-8000-000000000001';
const S2 = 'aaaaaaaa-0000-4000-8000-000000000002';
const goodPot = { id: P, potVersion: 4, label: 'x', shareholders: [{ id: S1 }, { id: S2 }], expenses: [{ id: 'e1' }],
  balances: { [S1]: { n: '1', d: '3' }, [S2]: { n: '-1', d: '3' } } };

// Each assertion is a k6 check; threshold checks rate==1 makes k6 exit non-zero on any failure.
function expect(name, actual, expected) {
  const pass = actual === expected;
  check(null, { [name]: () => pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${name}`);
}

export default function () {
  // 1. Admission refused
  expect('01 admission 403 rejected', O.validateAdmission(403, null).ok, false);
  expect('01 admission 202 w/o requestId rejected', O.validateAdmission(202, null).ok, false);
  expect('01 control admission 202 accepted', O.validateAdmission(202, 'r1').ok, true);
  // 2. Accepted but not terminal (poll deadline)
  expect('02 non-terminal -> censored', O.classifyProbe({ outcome: 'TIMEOUT' }).klass, 'CENSORED_TIMEOUT');
  // 3. Terminal without new version
  expect('03 CHANGED v=observed rejected', O.validateCommandResult({ outcome: 'CHANGED', resultingPotVersion: 3 }, 3).ok, false);
  expect('03 UNCHANGED not applied', O.validateCommandResult({ outcome: 'UNCHANGED', resultingPotVersion: 3 }, 3).applied, false);
  expect('03 control CHANGED v=obs+1', O.validateCommandResult({ outcome: 'CHANGED', resultingPotVersion: 4 }, 3).ok, true);
  // 4. Balance(P,V) absent / wrong version
  expect('04 balance absent', O.validateBalanceExact(null, 4, P).ok, false);
  expect('04 balance V+1 does not prove V', O.validateBalanceExact({ potId: P, potVersion: 5, balances: {} }, 4, P).ok, false);
  expect('04 balance missing shareholder', O.validateBalanceExact({ potId: P, potVersion: 4, balances: { [S1]: { n: '1', d: '1' } } }, 4, P, [S1, S2]).ok, false);
  expect('04 control exact balance', O.validateBalanceExact({ potId: P, potVersion: 4, balances: goodPot.balances }, 4, P, [S1, S2]).ok, true);
  // 5. Pot incomplete
  expect('05 missing expense', O.validatePotComplete({ ...goodPot, expenses: [] }, 4, P, { shareholders: 2, expenses: 1 }).ok, false);
  expect('05 balance of W misses shareholder', O.validatePotComplete({ ...goodPot, balances: { [S1]: { n: '0', d: '1' } } }, 4, P, { shareholders: 2, expenses: 1 }).ok, false);
  expect('05 control complete pot', O.validatePotComplete(goodPot, 4, P, { shareholders: 2, expenses: 1 }).ok, true);
  // 6. READ lag
  expect('06 W<V rejected', O.validatePotComplete({ ...goodPot, potVersion: 3 }, 4, P).ok, false);
  expect('06 control W>V accepted', O.validatePotComplete({ ...goodPot, potVersion: 6 }, 4, P).ok, true);
  // 7. READ maintenance 503
  const maint = { status: 503, headers: { 'Cache-Control': 'no-store', 'Retry-After': '30' } };
  const capa = { status: 503, headers: { 'Cache-Control': 'no-store' } };
  expect('07 maintenance 503 classified', classify503(maint), 'maintenance');
  expect('07 capacity 503 not maintenance', classify503(capa), 'capacity');
  expect('07 fail-closed control', O.validateFailClosedRead(maint.status, maint.headers).ok, true);
  expect('07 200 during maintenance is a violation', O.validateFailClosedRead(200, { 'Cache-Control': 'no-store', 'Retry-After': '30' }).ok, false);
  expect('07 maintenance probe aborts', O.classifyProbe({ outcome: 'MAINTENANCE_503' }).klass, 'READ_MAINTENANCE');
  // 8. Identity binding unusable
  const idr = { outcome: 'CHANGED', targetVersion: 2 };
  expect('08 binding inactive', O.validateIdentityUsable(idr, { active: false, bindingToken: 't', euiVersion: 2 }, 200).ok, false);
  expect('08 binding older than target', O.validateIdentityUsable(idr, { active: true, bindingToken: 't', euiVersion: 1 }, 200).ok, false);
  expect('08 authorized read refused', O.validateIdentityUsable(idr, { active: true, bindingToken: 't', euiVersion: 2 }, 403).ok, false);
  expect('08 control usable binding', O.validateIdentityUsable(idr, { active: true, bindingToken: 't', euiVersion: 2 }, 200).ok, true);
  // 9. DeletePot not materialized (HTTP 404 alone insufficient)
  expect('09 404 without internal row', O.validateDeletePotInternal(null, 5, 404).ok, false);
  expect('09 current not terminal', O.validateDeletePotInternal({ currentVersion: 4, deleted: false }, 5, 404).ok, false);
  expect('09 control deleted=true at D', O.validateDeletePotInternal({ currentVersion: 5, deleted: true }, 5, 404).ok, true);
  // 10. Async branch timeout
  expect('10 timeout not success', O.classifyProbe({ outcome: 'TIMEOUT' }).ok, false);
  expect('10 control observed', O.classifyProbe({ outcome: 'OK' }).ok, true);
  // 12. dropped iterations
  expect('12 dropped iterations invalidate', O.validateInjection(3).ok, false);
  expect('12 control no drop', O.validateInjection(0).ok, true);
  // Mutation control: proves that a wrong oracle verdict makes the self-test fail.
  if (__ENV.SELFTEST_INJECT_FAULT === '1') {
    expect('FAULT injected: control complete pot declared incomplete', O.validatePotComplete(goodPot, 4, P).ok, false);
  }
}

export function handleSummary(data) {
  const out = {};
  const checks = (data.root_group && data.root_group.checks) || [];
  out[__ENV.SELFTEST_OUT || 'docs/testing/evidence/L6-I3/oracle-selftest.json'] = JSON.stringify({
    total: checks.length,
    failed: checks.filter((c) => c.fails > 0).map((c) => c.name),
    cases: checks.map((c) => ({ name: c.name, pass: c.fails === 0 })),
  }, null, 2);
  out.stdout = '';
  return out;
}
