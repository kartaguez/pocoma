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

const cfg = config({ seedPots: 3, hotPots: 1, scrapeIntervalSeconds: 5 });
const prometheusState = newPrometheusState();

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  scenarios: {
    valid_commands: {
      executor: 'constant-vus',
      vus: Number(__ENV.SMOKE_COMMAND_VUS || 2),
      duration: __ENV.SMOKE_DURATION || '30s',
      exec: 'valid_commands',
    },
    concurrent_conflicts: {
      executor: 'constant-vus',
      vus: 1,
      duration: __ENV.SMOKE_DURATION || '30s',
      exec: 'concurrent_conflicts',
      startTime: '5s',
    },
    incoherent_requests: {
      executor: 'constant-vus',
      vus: 1,
      duration: __ENV.SMOKE_DURATION || '30s',
      exec: 'incoherent_requests',
      startTime: '5s',
    },
    queries_and_balances: {
      executor: 'constant-vus',
      vus: 1,
      duration: __ENV.SMOKE_DURATION || '30s',
      exec: 'queries_and_balances',
    },
    scrape_metrics: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: `${cfg.scrapeIntervalSeconds}s`,
      duration: __ENV.SMOKE_DURATION || '30s',
      preAllocatedVUs: 1,
      exec: 'scrape_metrics',
    },
  },
  thresholds: {
    pocoma_unexpected_failure_rate: ['rate==0'],
    pocoma_prometheus_scrape_success: ['value>=1'],
    http_req_failed: ['rate<0.25'],
    'http_req_duration{kind:command}': ['p(95)<1500'],
  },
};

export function setup() {
  return seedPots(cfg);
}

export function valid_commands(data) {
  runValidCommand(data);
  sleep(0.2);
}

export function concurrent_conflicts(data) {
  runConcurrentConflict(data);
  sleep(0.5);
}

export function incoherent_requests(data) {
  runIncoherentRequest(data);
  sleep(0.5);
}

export function queries_and_balances(data) {
  runQueriesAndBalances(data);
  sleep(0.3);
}

export function scrape_metrics() {
  scrapePrometheus(cfg.baseUrl, prometheusState);
}

export function teardown(data) {
  cleanup(data);
}
