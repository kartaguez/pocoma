// ==============================================================================
// Pocoma V3 — Deep Performance Bench (scripts/k6/v3-performance.js)
//
// Composable performance and characterisation bench supporting:
//   SCENARIO: smoke | nominal | sustained | peak | ramp | contention | read | mixed | structural | idle
//   RATE: offered commands/s (arrival-rate executors)
//   DURATION: evaluated window (e.g. 20s, 120s)
//   POTS: number of independent seed Pots
//   SHAREHOLDERS / SEED_EXPENSES: profile dimensions (Small 5/20, Med 20/200, Large 100/2000)
//   CONTENTION_VUS / READ_VUS: concurrency levels
//   MAX_ADMISSION_RETRIES: bounded technical retries on 503 capacity refusal
//
// Complete end-to-end timeline:
//   T0: send command -> T2: admission 202 -> T5/terminal -> T8/read pot -> T10: complete read
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
const READ_VUS = Number(__ENV.READ_VUS || 2);
const MAX_ADMISSION_RETRIES = Number(__ENV.MAX_ADMISSION_RETRIES || 3);

// ---- Metrics Trends ----
const tAdmission = new Trend('v3_admission_duration', true);
const tAdmissionWithRetries = new Trend('v3_admission_duration_with_retries', true);
const tTerminal = new Trend('v3_command_result_duration', true);
const tBalance = new Trend('v3_balance_available_duration', true);
const tPot = new Trend('v3_pot_read_available_duration', true);
const tReadLag = new Trend('v3_read_propagation_lag', true);
const tE2ETotal = new Trend('v3_e2e_total_duration', true);
const tAdmissionRetriesPerCmd = new Trend('v3_admission_retries_per_command', true);
const tReadQueryDuration = new Trend('v3_read_query_http_duration', true);

// ---- Metrics Counters ----
const cAdmissionsOffered = new Counter('v3_admissions_offered');
const cAdmissionsAttempted = new Counter('v3_admissions_attempted');
const cAdmitted = new Counter('v3_admissions_accepted');
const cFirstAttemptSuccess = new Counter('v3_admissions_first_attempt_success');
const cRetriedSuccess = new Counter('v3_admissions_retried_success');
const cCapacityRejected = new Counter('v3_admissions_capacity_rejected');
const cAdmissionErrors = new Counter('v3_admission_errors');
const cAdmissionRetriesTotal = new Counter('v3_admission_retries_total');
const cWriteCapacity503 = new Counter('v3_write_capacity_503');
const cReadCapacity503 = new Counter('v3_read_capacity_503');
const cMaintenance503 = new Counter('v3_read_maintenance_503');
const cApplied = new Counter('v3_commands_applied');
const cTerminal = new Counter('v3_terminal_results_count');
const cRejected = new Counter('v3_commands_rejected');
const cOracle = new Counter('v3_oracle_failures');
const cCensored = new Counter('v3_censored_timeouts');
const cProbeRequests = new Counter('v3_probe_requests');
const cReadQueriesTotal = new Counter('v3_read_queries_total');
const cReadQueries200 = new Counter('v3_read_queries_200');
const cReadQueriesErrors = new Counter('v3_read_queries_errors');
const cStarvation = new Counter('v3_generator_pot_starvation');

function scenarioOptions() {
  const arrival = (rate, vus, maxVus = null) => {
    const isDecoupled = __ENV.DECOUPLED !== 'false';
    const preVUs = isDecoupled ? Math.max(rate, 10) : vus;
    const peakVUs = isDecoupled ? Math.max(rate * 2, 20) : (maxVus || Math.max(vus * 2, 8));
    return {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: preVUs,
      maxVUs: peakVUs,
      gracefulStop: isDecoupled ? '5s' : `${Math.ceil(POLL_TIMEOUT_MS / 1000) + 5}s`,
    };
  };

  switch (SCENARIO) {
    case 'smoke':
      return { smoke: { executor: 'per-vu-iterations', vus: 1, iterations: 3, maxDuration: '90s' } };
    case 'idle':
      return { idle: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: `${IDLE_SECONDS + 60}s` } };
    case 'contention':
      return { contention: { executor: 'constant-vus', vus: CONTENTION_VUS, duration: DURATION } };
    case 'read':
      return { read: { executor: 'constant-vus', vus: READ_VUS, duration: DURATION } };
    case 'mixed':
      return {
        mixed_write: { executor: 'constant-vus', vus: 2, duration: DURATION },
        mixed_read: { executor: 'constant-vus', vus: 2, duration: DURATION },
      };
    case 'nominal':
    case 'sustained':
    case 'peak':
    case 'ramp':
      return { [SCENARIO]: arrival(RATE, Math.max(POTS, 4), Math.max(POTS * 4, 24)) };
    case 'structural':
      return { structural: arrival(RATE, Math.max(POTS, 2), Math.max(POTS * 2, 6)) };
    default:
      if (SCENARIO.startsWith('m') || __ENV.WORKLOAD_MODE === 'realistic') {
        return { [SCENARIO]: arrival(RATE, Math.max(RATE, 10), Math.max(RATE * 2, 20)) };
      }
      throw new Error(`Unknown SCENARIO ${SCENARIO}`);
  }
}

export const options = {
  scenarios: scenarioOptions(),
  setupTimeout: '300s',
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    v3_oracle_failures: ['count==0'],
    v3_read_maintenance_503: ['count==0'],
  },
};

function uuid4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function client() {
  return new V3ApiClient(BASE_URL, TOKEN, BINDING, {
    pollIntervalMs: POLL_INTERVAL_MS,
    maxPollTimeoutMs: POLL_TIMEOUT_MS,
  });
}

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
    id: uuid4(),
    label,
    date: '2026-11-06',
    amount: fraction(1 + Math.floor(Math.random() * 500), 3),
    payerId: shareholders[Math.floor(Math.random() * shareholders.length)],
    participants: shareholders.map((id) => ({ id, weight: null })),
  };
}

export function setup() {
  if (__ENV.WORKLOAD_MODE === 'realistic' || SCENARIO.startsWith('m')) {
    return { seededAt: Date.now() };
  }
  if (!TOKEN || !BINDING) {
    throw new Error('POCOMA_TOKEN and POCOMA_BINDING_TOKEN are required (set by v3-run.py)');
  }
  const c = client();
  const pots = [];

  const prePotId = __ENV.PRE_SEEDED_POT_ID || null;
  if (prePotId) {
    const shareholders = (__ENV.PRE_SEEDED_SHAREHOLDERS || '').split(',').filter(Boolean);
    const v = Number(__ENV.PRE_SEEDED_VERSION || 1);
    const expCount = Number(__ENV.PRE_SEEDED_EXPENSES || 0);
    const ready = c.pollPotCurrent(prePotId, v, (j) =>
      O.validatePotComplete(j, v, prePotId, { shareholders: shareholders.length, expenses: expCount }).ok
    );
    if (ready.outcome !== 'OK') {
      throw new Error(`pre-seeded Pot ${prePotId} not READ-complete at v${v}: ${ready.outcome}`);
    }
    pots.push({ potId: prePotId, shareholders, version: v, expenses: expCount });
    return { pots, seededAt: Date.now() };
  }

  const nPots = SCENARIO === 'contention' ? 1 : POTS;

  for (let i = 0; i < nPots; i++) {
    const potId = uuid4();
    const shareholders = Array.from({ length: SHAREHOLDERS }, () => uuid4());
    let v = seedCommand(c, 'CreatePot', potId, 0, { label: `Bench ${SCENARIO} ${i}` });
    v = seedCommand(c, 'AddShareholders', potId, v, {
      targets: shareholders.map((id, n) => ({
        id,
        name: `S${n}`,
        attachment: 'SELF',
        weight: fraction(1, 1),
      })),
    });
    for (let e = 0; e < SEED_EXPENSES; e++) {
      v = seedCommand(c, 'CreateExpense', potId, v, expensePayload(shareholders, `seed ${e}`));
    }
    const ready = c.pollPotCurrent(potId, v, (j) =>
      O.validatePotComplete(j, v, potId, { shareholders: SHAREHOLDERS, expenses: SEED_EXPENSES }).ok
    );
    if (ready.outcome !== 'OK') {
      throw new Error(`seed Pot ${i} not READ-complete at v${v}: ${ready.outcome}`);
    }
    pots.push({ potId, shareholders, version: v, expenses: SEED_EXPENSES });
  }
  return { pots, seededAt: Date.now() };
}

let own = null;
let lastTokenRefresh = Date.now();

function checkAndRefreshToken(c) {
  const authUrl = __ENV.POCOMA_AUTH_URL || null;
  if (!authUrl) return;
  if (Date.now() - lastTokenRefresh < 120000) return;
  try {
    const res = http.get(authUrl, { timeout: '3s' });
    if (res.status === 200) {
      const j = res.json();
      if (j && j.token) {
        c.token = j.token;
        lastTokenRefresh = Date.now();
      }
    }
  } catch (_) {}
}

function injectDecoupled(c, pot) {
  checkAndRefreshToken(c);
  const observed = pot.version;
  const t0_send = Date.now();
  cAdmissionsOffered.add(1);

  const payload = expensePayload(pot.shareholders, `bench-${exec.scenario.iterationInTest}`);
  let adm = c.submitCommand('CreateExpense', pot.potId, observed, payload, null, { maxRetries: 0 });

  if (adm.status === 401 && __ENV.POCOMA_AUTH_URL) {
    // Immediate token refresh and retry on 401
    lastTokenRefresh = 0;
    checkAndRefreshToken(c);
    adm = c.submitCommand('CreateExpense', pot.potId, observed, payload, null, { maxRetries: 0 });
  }

  cAdmissionsAttempted.add(adm.attempts);
  cAdmissionRetriesTotal.add(adm.retries);
  tAdmissionRetriesPerCmd.add(adm.retries);
  tAdmission.add(adm.duration);
  tAdmissionWithRetries.add(adm.totalAdmissionDuration);

  if (adm.status === 202) {
    cAdmitted.add(1);
    cFirstAttemptSuccess.add(1);
    pot.version = observed + 1;
    pot.expenses += 1;
  } else if (adm.status === 503) {
    cWriteCapacity503.add(1);
    cCapacityRejected.add(1);
  } else {
    cAdmissionErrors.add(1);
    cOracle.add(1);
  }

  // Pure JSON line to console for correlation engine
  console.log(JSON.stringify({
    commandId: adm.requestId,
    potId: pot.potId,
    t_send: t0_send,
    t_admit: adm.admittedAt,
    status: adm.status,
    attempts: adm.attempts,
    retries: adm.retries,
    expectedVersion: observed + 1,
    observedVersion: observed,
  }));
}

function injectRealistic(c) {
  checkAndRefreshToken(c);

  const res = http.get('http://127.0.0.1:18083/next-command');
  if (res.status !== 200) {
    cStarvation.add(1);
    sleep(0.005);
    return;
  }

  let cmdResp = null;
  try {
    cmdResp = res.json();
  } catch (_) {
    cStarvation.add(1);
    sleep(0.005);
    return;
  }

  if (!cmdResp || !cmdResp.available || !cmdResp.command) {
    cStarvation.add(1);
    sleep(0.005);
    return;
  }

  const cmd = cmdResp.command;
  const t0_send = Date.now();
  cAdmissionsOffered.add(1);

  let adm = c.submitCommand(cmd.operation, cmd.potId, cmd.observedVersion, cmd.payload, null, { maxRetries: 0 });

  if (adm.status === 401 && __ENV.POCOMA_AUTH_URL) {
    // Immediate token refresh and retry on 401
    lastTokenRefresh = 0;
    checkAndRefreshToken(c);
    adm = c.submitCommand(cmd.operation, cmd.potId, cmd.observedVersion, cmd.payload, null, { maxRetries: 0 });
  }

  cAdmissionsAttempted.add(adm.attempts);
  cAdmissionRetriesTotal.add(adm.retries);
  tAdmissionRetriesPerCmd.add(adm.retries);
  tAdmission.add(adm.duration);
  tAdmissionWithRetries.add(adm.totalAdmissionDuration);

  if (adm.status === 202) {
    cAdmitted.add(1);
    cFirstAttemptSuccess.add(1);
  } else if (adm.status === 503) {
    cWriteCapacity503.add(1);
    cCapacityRejected.add(1);
  } else {
    cAdmissionErrors.add(1);
    cOracle.add(1);
  }

  // Pure JSON line to console for correlation engine
  console.log(JSON.stringify({
    commandId: adm.requestId,
    potId: cmd.potId,
    operation: cmd.operation,
    t_send: t0_send,
    t_admit: adm.admittedAt,
    status: adm.status,
    attempts: adm.attempts,
    retries: adm.retries,
    expectedVersion: cmd.observedVersion + 1,
    observedVersion: cmd.observedVersion,
    _meta: cmd._meta || null,
  }));
}

function measureMutation(c, pot) {
  const observed = pot.version;
  const expectedV = observed + 1;
  const t0_send = Date.now();
  cAdmissionsOffered.add(1);

  const retryOpts = { maxRetries: MAX_ADMISSION_RETRIES, backoffMs: 15 };
  const adm = c.submitCommand(
    'CreateExpense',
    pot.potId,
    observed,
    expensePayload(pot.shareholders, `bench ${exec.scenario.iterationInTest}`),
    null,
    retryOpts
  );

  cAdmissionsAttempted.add(adm.attempts);
  cAdmissionRetriesTotal.add(adm.retries);
  tAdmissionRetriesPerCmd.add(adm.retries);
  tAdmission.add(adm.duration);
  tAdmissionWithRetries.add(adm.totalAdmissionDuration);

  if (adm.isMaintenance503) {
    cMaintenance503.add(1);
    cOracle.add(1);
    return;
  }

  if (adm.status === 202) {
    cAdmitted.add(1);
    if (adm.retries === 0) {
      cFirstAttemptSuccess.add(1);
    } else {
      cRetriedSuccess.add(1);
    }
  } else if (adm.status === 503) {
    cWriteCapacity503.add(adm.attempts);
    cCapacityRejected.add(1);
    return;
  } else {
    cAdmissionErrors.add(1);
    cOracle.add(1);
    return;
  }

  const a = O.validateAdmission(adm.status, adm.requestId);
  if (!a.ok) {
    cAdmissionErrors.add(1);
    cOracle.add(1);
    return;
  }

  const t0_admitted = adm.admittedAt;
  const deadline = t0_admitted + POLL_TIMEOUT_MS;
  const base = `${BASE_URL}/api/v3`;
  const hdr = c.headers({}, true);
  const branches = {
    result: { url: `${base}/commands/${adm.requestId}/result`, at: null, data: null },
    balance: { url: null, at: null, data: null },
    pot: { url: null, at: null, data: null },
  };

  let maintenance = false;
  let hardError = null;

  while (Date.now() < deadline && !maintenance && !hardError) {
    if (branches.result.at === null) {
      cProbeRequests.add(1);
      const res = http.get(branches.result.url, { headers: hdr, tags: { endpoint: 'probe_result' } });
      if (res.status === 200) {
        let j = null;
        try { j = res.json(); } catch (_) { j = null; }
        if (j !== null && typeof j.outcome === 'string') {
          branches.result.at = Date.now();
          branches.result.data = j;
          const r = O.validateCommandResult(j, observed);
          if (!r.applied) break;
          branches.balance.url = `${base}/pots/${pot.potId}/versions/${r.version}/balance`;
          branches.pot.url = `${base}/pots/${pot.potId}?minVersion=${r.version}`;
        }
      } else if (res.status === 503) {
        if (classify503(res) === 'maintenance') { maintenance = true; break; }
        cReadCapacity503.add(1);
        sleep(0.025);
      } else if (res.status !== 404) {
        hardError = `result: HTTP ${res.status}`;
        break;
      }
    }

    if (branches.result.at !== null && branches.balance.url) {
      if (branches.balance.at === null) {
        cProbeRequests.add(1);
        const res = http.get(branches.balance.url, { headers: hdr, tags: { endpoint: 'probe_balance' } });
        if (res.status === 200) {
          let j = null;
          try { j = res.json(); } catch (_) { j = null; }
          const targetV = branches.result.data.resultingPotVersion;
          if (j !== null && j.potVersion === targetV) {
            branches.balance.at = Date.now();
            branches.balance.data = j;
          }
        } else if (res.status === 503) {
          if (classify503(res) === 'maintenance') { maintenance = true; break; }
          cReadCapacity503.add(1);
          sleep(0.025);
        } else if (res.status !== 404) {
          hardError = `balance: HTTP ${res.status}`;
          break;
        }
      }

      if (branches.pot.at === null) {
        cProbeRequests.add(1);
        const res = http.get(branches.pot.url, { headers: hdr, tags: { endpoint: 'probe_pot' } });
        if (res.status === 200) {
          let j = null;
          try { j = res.json(); } catch (_) { j = null; }
          const targetV = branches.result.data.resultingPotVersion;
          if (j !== null && typeof j.potVersion === 'number' && j.potVersion >= targetV) {
            branches.pot.at = Date.now();
            branches.pot.data = j;
          }
        } else if (res.status === 503) {
          if (classify503(res) === 'maintenance') { maintenance = true; break; }
          cReadCapacity503.add(1);
          sleep(0.025);
        } else if (res.status !== 404) {
          hardError = `pot: HTTP ${res.status}`;
          break;
        }
      }

      if (branches.balance.at !== null && branches.pot.at !== null) break;
    }

    sleep(POLL_INTERVAL_MS / 1000);
  }

  if (maintenance) { cMaintenance503.add(1); cOracle.add(1); return; }
  if (hardError) { cOracle.add(1); return; }
  if (branches.result.at === null) { cCensored.add(1); return; }

  cTerminal.add(1);
  const durTerminal = branches.result.at - t0_admitted;
  tTerminal.add(durTerminal);

  const r = O.validateCommandResult(branches.result.data, observed);
  if (!r.applied) { cRejected.add(1); return; }
  if (r.version <= observed) { cOracle.add(1); pot.version = r.version; return; }

  cApplied.add(1);
  const actualV = r.version;
  pot.version = actualV;
  pot.expenses += 1;

  if (branches.balance.at === null) {
    cCensored.add(1);
  } else {
    const vb = O.validateBalanceExact(branches.balance.data, actualV, pot.potId, pot.shareholders);
    if (vb.ok) tBalance.add(branches.balance.at - t0_admitted); else cOracle.add(1);
  }

  if (branches.pot.at === null) {
    cCensored.add(1);
  } else {
    const vp = O.validatePotComplete(branches.pot.data, actualV, pot.potId, {
      shareholders: SHAREHOLDERS,
      expenses: pot.expenses,
    });
    if (vp.ok) {
      tPot.add(branches.pot.at - t0_admitted);
      tReadLag.add(branches.pot.at - branches.result.at);
      tE2ETotal.add(branches.pot.at - t0_send);
    } else {
      cOracle.add(1);
    }
  }
}

function contentionIteration(c, data) {
  const pot = data.pots[0];
  const cur = c.pollPotCurrent(pot.potId, 1);
  if (cur.outcome !== 'OK') { cOracle.add(1); return; }
  const observed = cur.data.potVersion;
  const t0_send = Date.now();
  cAdmissionsOffered.add(1);

  const retryOpts = { maxRetries: MAX_ADMISSION_RETRIES, backoffMs: 20 };
  const adm = c.submitCommand(
    'CreateExpense',
    pot.potId,
    observed,
    expensePayload(pot.shareholders, `contention-${exec.scenario.iterationInTest}`),
    null,
    retryOpts
  );

  cAdmissionsAttempted.add(adm.attempts);
  cAdmissionRetriesTotal.add(adm.retries);
  tAdmissionRetriesPerCmd.add(adm.retries);
  tAdmission.add(adm.duration);
  tAdmissionWithRetries.add(adm.totalAdmissionDuration);

  if (adm.status === 503) {
    cWriteCapacity503.add(adm.attempts);
    cCapacityRejected.add(1);
    sleep(0.05);
    return;
  }
  if (!O.validateAdmission(adm.status, adm.requestId).ok) {
    cAdmissionErrors.add(1);
    cOracle.add(1);
    return;
  }
  cAdmitted.add(1);
  if (adm.retries === 0) cFirstAttemptSuccess.add(1); else cRetriedSuccess.add(1);

  const probe = c.pollCommandResult(adm.requestId, adm.admittedAt);
  if (probe.outcome === 'TIMEOUT') { cCensored.add(1); return; }
  if (probe.outcome === 'MAINTENANCE_503') { cMaintenance503.add(1); cOracle.add(1); return; }
  if (probe.outcome !== 'OK') { cOracle.add(1); return; }
  cTerminal.add(1);
  tTerminal.add(probe.elapsed);
  tE2ETotal.add(Date.now() - t0_send);

  if (probe.data.outcome === 'REJECTED') {
    cRejected.add(1);
    sleep(0.02);
    return;
  }
  const r = O.validateCommandResult(probe.data, observed);
  if (r.applied) cApplied.add(1); else cOracle.add(1);
  sleep(0.02);
}

function readIteration(c, data) {
  const pot = data.pots[Math.floor(Math.random() * data.pots.length)];
  cReadQueriesTotal.add(1);
  const qType = Math.floor(Math.random() * 3);
  let res = null;

  if (qType === 0) {
    res = c.getPotCurrent(pot.potId);
  } else if (qType === 1) {
    res = c.getPotBalance(pot.potId, pot.version);
  } else {
    res = c.queryExpenses(pot.potId, 20);
  }

  tReadQueryDuration.add(res.duration);
  if (res.status === 200) {
    cReadQueries200.add(1);
  } else if (res.isCapacity503) {
    cReadCapacity503.add(1);
  } else if (res.isMaintenance503) {
    cMaintenance503.add(1);
    cOracle.add(1);
  } else {
    cReadQueriesErrors.add(1);
  }
  sleep(0.02);
}

export default function (data) {
  const c = client();

  if (__ENV.WORKLOAD_MODE === 'realistic' || SCENARIO.startsWith('m')) {
    injectRealistic(c);
    return;
  }

  if (SCENARIO === 'contention') {
    contentionIteration(c, data);
    return;
  }

  if (SCENARIO === 'read') {
    readIteration(c, data);
    return;
  }

  if (SCENARIO === 'mixed') {
    const isReader = exec.scenario.name === 'mixed_read';
    if (isReader) {
      readIteration(c, data);
    } else {
      if (own === null) {
        const idx = (exec.vu.idInTest - 1) % data.pots.length;
        own = { ...data.pots[idx], shareholders: data.pots[idx].shareholders.slice() };
      }
      measureMutation(c, own);
    }
    return;
  }

  if (SCENARIO === 'idle' && exec.scenario.iterationInTest === 0) {
    sleep(IDLE_SECONDS);
  }

  if (own === null) {
    const idx = (exec.vu.idInTest - 1) % data.pots.length;
    own = { ...data.pots[idx], shareholders: data.pots[idx].shareholders.slice() };
  }

  const isDecoupled = __ENV.DECOUPLED !== 'false';
  if (isDecoupled && ['nominal', 'sustained', 'peak', 'ramp'].includes(SCENARIO)) {
    injectDecoupled(c, own);
    return;
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
  const line = (label, m) =>
    `  ${label.padEnd(30)} n=${cnt(m)} p50=${fmt(m, 'p(50)')} p95=${fmt(m, 'p(95)')} p99=${fmt(m, 'p(99)')} max=${fmt(m, 'max')} ms`;

  const out = {};
  out[__ENV.SUMMARY_PATH || 'k6-summary.json'] = JSON.stringify(data, null, 2);

  const offered = cnt('v3_admissions_offered');
  const admitted = cnt('v3_admissions_accepted');
  const firstSuccess = cnt('v3_admissions_first_attempt_success');
  const retriedSuccess = cnt('v3_admissions_retried_success');
  const capRej = cnt('v3_admissions_capacity_rejected');
  const retriesTotal = cnt('v3_admission_retries_total');

  out.stdout = [
    '',
    `== Pocoma V3 Deep Bench Summary [${SCENARIO}] offered_rate=${RATE}/s pots=${POTS} ==`,
    `  Throughput & Admissions: offered=${offered} attempted=${cnt('v3_admissions_attempted')} admitted=${admitted}`
      + ` (1st_try=${firstSuccess}, retried=${retriedSuccess}, cap_rejected=${capRej}) retries_total=${retriesTotal}`,
    `  Terminal & Read: applied=${cnt('v3_commands_applied')} terminal=${cnt('v3_terminal_results_count')}`
      + ` rejected_cas=${cnt('v3_commands_rejected')} admission_errors=${cnt('v3_admission_errors')}`
      + ` oracle_failures=${cnt('v3_oracle_failures')} starvation=${cnt('v3_generator_pot_starvation')}`
      + ` censored=${cnt('v3_censored_timeouts')} dropped_iters=${cnt('dropped_iterations')}`,
    `  Capacity & Semaphores: write_503=${cnt('v3_write_capacity_503')} read_503=${cnt('v3_read_capacity_503')}`
      + ` maintenance_503=${cnt('v3_read_maintenance_503')} probe_reqs=${cnt('v3_probe_requests')}`
      + ` read_queries=${cnt('v3_read_queries_total')} read_200=${cnt('v3_read_queries_200')}`,
    '  --- Latency Breakdown (Client-Side) ---',
    line('Admission HTTP (1st try)', 'v3_admission_duration'),
    line('Admission HTTP (w/ retries)', 'v3_admission_duration_with_retries'),
    line('Admission -> Terminal', 'v3_command_result_duration'),
    line('Admission -> Balance(P,V)', 'v3_balance_available_duration'),
    line('Admission -> Pot W>=V', 'v3_pot_read_available_duration'),
    line('READ Propagation Lag', 'v3_read_propagation_lag'),
    line('Total E2E (T0 -> T10)', 'v3_e2e_total_duration'),
    line('Read Query HTTP', 'v3_read_query_http_duration'),
    line('Retries per Command', 'v3_admission_retries_per_command'),
    '',
  ].join('\n');

  return out;
}
