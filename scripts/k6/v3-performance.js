// ==============================================================================
// Pocoma V3 — Performance bench (scripts/k6/v3-performance.js)
//
// One composable bench, parameterised by environment variables:
//   SCENARIO   smoke | nominal | sustained | peak | contention | idle
//   RATE       offered commands/s (arrival-rate executors)
//   DURATION   evaluated window (e.g. 20s)
//   POTS       number of independent seed Pots (= max VUs, one owner VU per Pot)
//   SHAREHOLDERS / SEED_EXPENSES   seed profile dimensions (SPEC-09 small: 5/20)
//   POLL_INTERVAL_MS (25)  POLL_TIMEOUT_MS (8000)  IDLE_SECONDS (60)
//   POCOMA_TOKEN / POCOMA_BINDING_TOKEN   supplied by the orchestrator, never logged
//
// Timeline per applied command (SPEC-10 §24.4), all from the client-side admission
// response timestamp, observed in ONE polling loop so that branches are independent
// upper bounds (never summed):
//   admission HTTP | -> terminal result | -> Balance(P,V) exact | -> first complete Pot W>=V
// ==============================================================================

import { sleep } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { V3ApiClient, classify503, fraction } from './lib/v3-api.js';
import { V3Oracles as O } from './lib/v3-oracles.js';

const BASE_URL = __ENV.POCOMA_BASE_URL || 'http://127.0.0.1:8080';
const TOKEN = __ENV.POCOMA_TOKEN || null;
const BINDING = __ENV.POCOMA_BINDING_TOKEN || null;
const SCENARIO = __ENV.SCENARIO || 'smoke';
const RATE = Number(__ENV.RATE || 1);
const DURATION = __ENV.DURATION || '20s';
const POTS = Number(__ENV.POTS || (SCENARIO === 'smoke' || SCENARIO === 'idle' ? 1 : 4));
const SHAREHOLDERS = Number(__ENV.SHAREHOLDERS || 5);
const SEED_EXPENSES = Number(__ENV.SEED_EXPENSES || 20);
const POLL_INTERVAL_MS = Number(__ENV.POLL_INTERVAL_MS || 25);
const POLL_TIMEOUT_MS = Number(__ENV.POLL_TIMEOUT_MS || 8000);
const IDLE_SECONDS = Number(__ENV.IDLE_SECONDS || 60);
const CONTENTION_VUS = Number(__ENV.CONTENTION_VUS || 3);

// ---- metrics (no high-cardinality tags: no potId/requestId labels) ----
const tAdmission = new Trend('v3_admission_duration', true);
const tTerminal = new Trend('v3_command_result_duration', true);
const tBalance = new Trend('v3_balance_available_duration', true);
const tPot = new Trend('v3_pot_read_available_duration', true);
const cAdmitted = new Counter('v3_admissions_accepted');
const cAdmissionErrors = new Counter('v3_admission_errors');
const cApplied = new Counter('v3_commands_applied');
const cTerminal = new Counter('v3_terminal_results_count');
const cRejected = new Counter('v3_commands_rejected');
const cOracle = new Counter('v3_oracle_failures');
const cCensored = new Counter('v3_censored_timeouts');
const cCapacity503 = new Counter('v3_read_capacity_503');
const cMaintenance503 = new Counter('v3_read_maintenance_503');
const cProbeRequests = new Counter('v3_probe_requests');

function scenarioOptions() {
  const arrival = (rate, vus) => ({
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: vus, maxVUs: vus, gracefulStop: `${Math.ceil(POLL_TIMEOUT_MS / 1000) + 5}s`,
  });
  switch (SCENARIO) {
    case 'smoke': return { smoke: { executor: 'per-vu-iterations', vus: 1, iterations: 3, maxDuration: '90s' } };
    case 'idle': return { idle: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: `${IDLE_SECONDS + 60}s` } };
    case 'contention': return { contention: { executor: 'constant-vus', vus: CONTENTION_VUS, duration: DURATION } };
    case 'nominal': case 'sustained': case 'peak': return { [SCENARIO]: arrival(RATE, POTS) };
    default: throw new Error(`Unknown SCENARIO ${SCENARIO}`);
  }
}

export const options = {
  scenarios: scenarioOptions(),
  setupTimeout: '180s',
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Validity gates of the experiment (not SLO verdicts): any failure invalidates the run.
    v3_oracle_failures: ['count==0'],
    v3_read_maintenance_503: ['count==0'],
    dropped_iterations: ['count==0'],
  },
};

function uuid4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function client() {
  return new V3ApiClient(BASE_URL, TOKEN, BINDING, { pollIntervalMs: POLL_INTERVAL_MS, maxPollTimeoutMs: POLL_TIMEOUT_MS });
}

// Synchronous seed command used only in setup (not measured): fails hard on any oracle error.
function seedCommand(c, op, potId, version, payload) {
  const adm = c.submitCommand(op, potId, version, payload);
  const a = O.validateAdmission(adm.status, adm.requestId);
  if (!a.ok) throw new Error(`seed ${op}: ${a.errors.join('; ')}`);
  const probe = c.pollCommandResult(adm.requestId, adm.admittedAt);
  if (probe.outcome !== 'OK') throw new Error(`seed ${op}: result ${probe.outcome}`);
  const r = O.validateCommandResult(probe.data, version);
  if (!r.ok) throw new Error(`seed ${op}: ${r.errors.join('; ')}`);
  return r.version;
}

function expensePayload(shareholders, label) {
  return {
    id: uuid4(), label, date: '2026-11-06', amount: fraction(1 + Math.floor(Math.random() * 500), 3),
    payerId: shareholders[Math.floor(Math.random() * shareholders.length)],
    participants: shareholders.map((id) => ({ id, weight: null })),
  };
}

export function setup() {
  if (!TOKEN || !BINDING) throw new Error('POCOMA_TOKEN and POCOMA_BINDING_TOKEN are required (set by v3-run.py)');
  const c = client();
  const pots = [];
  const nPots = SCENARIO === 'contention' ? 1 : POTS;
  for (let i = 0; i < nPots; i++) {
    const potId = uuid4();
    const shareholders = Array.from({ length: SHAREHOLDERS }, () => uuid4());
    let v = seedCommand(c, 'CreatePot', potId, 0, { label: `Bench ${SCENARIO} ${i}` });
    v = seedCommand(c, 'AddShareholders', potId, v, {
      targets: shareholders.map((id, n) => ({ id, name: `S${n}`, attachment: 'SELF', weight: fraction(1, 1) })),
    });
    for (let e = 0; e < SEED_EXPENSES; e++) v = seedCommand(c, 'CreateExpense', potId, v, expensePayload(shareholders, `seed ${e}`));
    // Seed must be READ-complete before measurement starts (dataset consistency gate).
    const ready = c.pollPotCurrent(potId, v, (j) => O.validatePotComplete(j, v, potId, { shareholders: SHAREHOLDERS, expenses: SEED_EXPENSES }).ok);
    if (ready.outcome !== 'OK') throw new Error(`seed Pot ${i} not READ-complete at v${v}: ${ready.outcome}`);
    pots.push({ potId, shareholders, version: v, expenses: SEED_EXPENSES });
  }
  return { pots, seededAt: Date.now() };
}

// VU-local state: each VU owns exactly one Pot in independent-Pot scenarios.
let own = null;

function measureMutation(c, pot) {
  const observed = pot.version;
  const expectedV = observed + 1; // sole writer of this Pot: an applied command yields observed+1
  const adm = c.submitCommand('CreateExpense', pot.potId, observed, expensePayload(pot.shareholders, `bench ${exec.scenario.iterationInTest}`));
  tAdmission.add(adm.duration);
  const a = O.validateAdmission(adm.status, adm.requestId);
  if (!a.ok) { cAdmissionErrors.add(1); cOracle.add(1); return; }
  cAdmitted.add(1);

  const t0 = adm.admittedAt;
  const deadline = t0 + POLL_TIMEOUT_MS;
  const base = `${BASE_URL}/api/v3`;
  const hdr = c.headers({}, true);
  const branches = {
    result: { url: `${base}/commands/${adm.requestId}/result`, at: null, data: null },
    balance: { url: `${base}/pots/${pot.potId}/versions/${expectedV}/balance`, at: null, data: null },
    pot: { url: `${base}/pots/${pot.potId}?minVersion=${expectedV}`, at: null, data: null },
  };
  let maintenance = false;
  let hardError = null;
  while (Date.now() < deadline && !maintenance && !hardError) {
    const pending = Object.entries(branches).filter(([, b]) => b.at === null);
    if (pending.length === 0) break;
    for (const [name, b] of pending) {
      cProbeRequests.add(1);
      const res = http.get(b.url, { headers: hdr, tags: { endpoint: `probe_${name}` } });
      if (res.status === 200) {
        let j = null;
        try { j = res.json(); } catch (_) { j = null; }
        const ok = j !== null && (name === 'result' ? typeof j.outcome === 'string'
          : name === 'balance' ? j.potVersion === expectedV
            : typeof j.potVersion === 'number' && j.potVersion >= expectedV);
        if (ok) { b.at = Date.now(); b.data = j; }
      } else if (res.status === 503) {
        if (classify503(res) === 'maintenance') { maintenance = true; break; }
        cCapacity503.add(1);
      } else if (res.status !== 404) {
        hardError = `${name}: HTTP ${res.status}`;
        break;
      }
      if (name === 'result' && b.at !== null) {
        // Stop early when the command is terminal but not applied (no V to wait for).
        const r = O.validateCommandResult(b.data, observed);
        if (!r.applied) break;
      }
    }
    if (branches.result.at !== null && !O.validateCommandResult(branches.result.data, observed).applied) break;
    sleep(POLL_INTERVAL_MS / 1000);
  }

  if (maintenance) { cMaintenance503.add(1); cOracle.add(1); return; }
  if (hardError) { cOracle.add(1); return; }
  if (branches.result.at === null) { cCensored.add(1); cOracle.add(1); return; }
  cTerminal.add(1);
  tTerminal.add(branches.result.at - t0);
  const r = O.validateCommandResult(branches.result.data, observed);
  if (!r.applied) { cRejected.add(1); cOracle.add(1); return; }
  if (r.version !== expectedV) { cOracle.add(1); pot.version = r.version; return; }
  cApplied.add(1);
  pot.version = r.version;
  pot.expenses += 1;

  // Censored branches stay in the sample as failures (never silently dropped).
  if (branches.balance.at === null) { cCensored.add(1); cOracle.add(1); }
  else {
    const vb = O.validateBalanceExact(branches.balance.data, expectedV, pot.potId, pot.shareholders);
    if (vb.ok) tBalance.add(branches.balance.at - t0); else cOracle.add(1);
  }
  if (branches.pot.at === null) { cCensored.add(1); cOracle.add(1); }
  else {
    const vp = O.validatePotComplete(branches.pot.data, expectedV, pot.potId, { shareholders: SHAREHOLDERS, expenses: pot.expenses });
    if (vp.ok) tPot.add(branches.pot.at - t0); else cOracle.add(1);
  }
}

function contentionIteration(c, data) {
  const pot = data.pots[0];
  const cur = c.pollPotCurrent(pot.potId, 1);
  if (cur.outcome !== 'OK') { cOracle.add(1); return; }
  const observed = cur.data.potVersion;
  const adm = c.submitCommand('CreateExpense', pot.potId, observed, expensePayload(pot.shareholders, 'contention'));
  tAdmission.add(adm.duration);
  if (adm.status === 503) {
    cCapacity503.add(1);
    sleep(0.05);
    return;
  }
  if (!O.validateAdmission(adm.status, adm.requestId).ok) { cAdmissionErrors.add(1); cOracle.add(1); return; }
  cAdmitted.add(1);
  const probe = c.pollCommandResult(adm.requestId, adm.admittedAt);
  if (probe.outcome !== 'OK') { cCensored.add(1); cOracle.add(1); return; }
  cTerminal.add(1);
  tTerminal.add(probe.elapsed);
  if (probe.data.outcome === 'REJECTED') { cRejected.add(1); return; } // legitimate CAS/business rejection
  const r = O.validateCommandResult(probe.data, observed);
  if (r.applied) cApplied.add(1); else cOracle.add(1);
}

export default function (data) {
  const c = client();
  if (SCENARIO === 'contention') { contentionIteration(c, data); return; }
  if (SCENARIO === 'idle' && exec.scenario.iterationInTest === 0) sleep(IDLE_SECONDS);
  if (own === null) {
    const idx = (exec.vu.idInTest - 1) % data.pots.length;
    own = { ...data.pots[idx], shareholders: data.pots[idx].shareholders.slice() };
  }
  measureMutation(c, own);
}

export function handleSummary(data) {
  const fmt = (m, k) => {
    const x = data.metrics[m];
    return x && x.values && x.values[k] !== undefined ? Number(x.values[k]).toFixed(1) : 'N/A';
  };
  const cnt = (m) => {
    const x = data.metrics[m];
    return x && x.values && x.values.count !== undefined ? x.values.count : 0;
  };
  const line = (label, m) => `  ${label.padEnd(26)} n=${cnt(m)} p50=${fmt(m, 'p(50)')} p95=${fmt(m, 'p(95)')} p99=${fmt(m, 'p(99)')} max=${fmt(m, 'max')} ms`;
  const out = {};
  out[__ENV.SUMMARY_PATH || 'k6-summary.json'] = JSON.stringify(data, null, 2);
  out.stdout = [
    '', `== Pocoma V3 bench summary [${SCENARIO}] rate=${RATE}/s pots=${POTS} ==`,
    `  admitted=${cnt('v3_admissions_accepted')} applied=${cnt('v3_commands_applied')} terminal=${cnt('v3_terminal_results_count')}`
      + ` rejected=${cnt('v3_commands_rejected')} admissionErrors=${cnt('v3_admission_errors')} oracleFailures=${cnt('v3_oracle_failures')}`
      + ` censored=${cnt('v3_censored_timeouts')} dropped=${cnt('dropped_iterations')}`
      + ` capacity503=${cnt('v3_read_capacity_503')} maintenance503=${cnt('v3_read_maintenance_503')} probeRequests=${cnt('v3_probe_requests')}`,
    line('admission HTTP', 'v3_admission_duration'),
    line('admission->terminal', 'v3_command_result_duration'),
    line('admission->Balance(P,V)', 'v3_balance_available_duration'),
    line('admission->Pot W>=V', 'v3_pot_read_available_duration'),
    '',
  ].join('\n');
  return out;
}
