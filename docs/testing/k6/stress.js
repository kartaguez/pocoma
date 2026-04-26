import { sleep } from 'k6';
import http from 'k6/http';
import { config } from './lib/scenario-data.js';
import { newPrometheusState, scrapePrometheus } from './lib/prometheus-scraper.js';
import {
  cleanup,
  runConcurrentConflict,
  runIncoherentRequest,
  runQueriesAndBalances,
  runValidCommand,
  seedPots,
} from './lib/workflows.js';

const cfg = config({ seedPots: 12, hotPots: 3, scrapeIntervalSeconds: 10 });
const prometheusState = newPrometheusState();

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  scenarios: {
    valid_commands: {
      executor: 'ramping-vus',
      stages: [
        { duration: __ENV.STRESS_RAMP_UP || '1m', target: Number(__ENV.STRESS_COMMAND_VUS || 24) },
        { duration: __ENV.STRESS_PLATEAU || '4m', target: Number(__ENV.STRESS_COMMAND_VUS || 24) },
        { duration: __ENV.STRESS_RAMP_DOWN || '1m', target: 0 },
      ],
      exec: 'valid_commands',
    },
    concurrent_conflicts: {
      executor: 'ramping-vus',
      stages: [
        { duration: __ENV.STRESS_RAMP_UP || '1m', target: Number(__ENV.STRESS_CONFLICT_VUS || 7) },
        { duration: __ENV.STRESS_PLATEAU || '4m', target: Number(__ENV.STRESS_CONFLICT_VUS || 7) },
        { duration: __ENV.STRESS_RAMP_DOWN || '1m', target: 0 },
      ],
      exec: 'concurrent_conflicts',
    },
    incoherent_requests: {
      executor: 'ramping-vus',
      stages: [
        { duration: __ENV.STRESS_RAMP_UP || '1m', target: Number(__ENV.STRESS_INCOHERENT_VUS || 3) },
        { duration: __ENV.STRESS_PLATEAU || '4m', target: Number(__ENV.STRESS_INCOHERENT_VUS || 3) },
        { duration: __ENV.STRESS_RAMP_DOWN || '1m', target: 0 },
      ],
      exec: 'incoherent_requests',
    },
    queries_and_balances: {
      executor: 'ramping-vus',
      stages: [
        { duration: __ENV.STRESS_RAMP_UP || '1m', target: Number(__ENV.STRESS_QUERY_VUS || 4) },
        { duration: __ENV.STRESS_PLATEAU || '4m', target: Number(__ENV.STRESS_QUERY_VUS || 4) },
        { duration: __ENV.STRESS_RAMP_DOWN || '1m', target: 0 },
      ],
      exec: 'queries_and_balances',
    },
    scrape_metrics: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: `${cfg.scrapeIntervalSeconds}s`,
      duration: totalStressDuration(),
      preAllocatedVUs: 1,
      exec: 'scrape_metrics',
    },
  },
  thresholds: {
    pocoma_unexpected_failure_rate: ['rate<0.02'],
    pocoma_prometheus_scrape_success: ['value>=1'],
    'http_req_duration{kind:command}': ['p(95)<3000'],
    'pocoma_command_http_duration{operation:update_pot_details}': ['p(95)<3000'],
  },
};

export function setup() {
  return seedPots(cfg);
}

export function valid_commands(data) {
  runValidCommand(data);
  sleep(Number(__ENV.STRESS_COMMAND_SLEEP || 0.05));
}

export function concurrent_conflicts(data) {
  runConcurrentConflict(data);
  sleep(Number(__ENV.STRESS_CONFLICT_SLEEP || 0.1));
}

export function incoherent_requests(data) {
  runIncoherentRequest(data);
  sleep(Number(__ENV.STRESS_INCOHERENT_SLEEP || 0.2));
}

export function queries_and_balances(data) {
  runQueriesAndBalances(data);
  sleep(Number(__ENV.STRESS_QUERY_SLEEP || 0.2));
}

export function scrape_metrics() {
  scrapePrometheus(cfg.baseUrl, prometheusState);
}

export function teardown(data) {
  cleanup(data);
}

function totalStressDuration() {
  const rampUp = __ENV.STRESS_RAMP_UP || '1m';
  const plateau = __ENV.STRESS_PLATEAU || '4m';
  const rampDown = __ENV.STRESS_RAMP_DOWN || '1m';
  return `${durationSeconds(rampUp) + durationSeconds(plateau) + durationSeconds(rampDown)}s`;
}

function durationSeconds(value) {
  const match = String(value).match(/^(\d+)(ms|s|m|h)$/);
  if (!match) {
    return 60;
  }
  const amount = Number(match[1]);
  const unit = match[2];
  if (unit === 'ms') {
    return Math.max(1, Math.ceil(amount / 1000));
  }
  if (unit === 's') {
    return amount;
  }
  if (unit === 'm') {
    return amount * 60;
  }
  return amount * 3600;
}
