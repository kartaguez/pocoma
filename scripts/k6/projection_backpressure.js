import { sleep } from 'k6';
import http from 'k6/http';
import { config } from './lib/scenario-data.js';
import { newPrometheusState, scrapePrometheus } from './lib/prometheus-scraper.js';
import {
  cleanup,
  runQueriesAndBalances,
  runValidCommand,
  seedPots,
} from './lib/workflows.js';

const cfg = config({
  seedPots: Number(__ENV.SEED_POTS || 6),
  hotPots: Number(__ENV.HOT_POTS || 2),
  scrapeIntervalSeconds: Number(__ENV.SCRAPE_INTERVAL_SECONDS || 2),
});
const prometheusState = newPrometheusState();

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  scenarios: {
    command_burst: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.BACKPRESSURE_START_RATE || 5),
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.BACKPRESSURE_PREALLOCATED_VUS || 30),
      maxVUs: Number(__ENV.BACKPRESSURE_MAX_VUS || 120),
      stages: [
        { target: Number(__ENV.BACKPRESSURE_PEAK_RATE || 40), duration: __ENV.BACKPRESSURE_RAMP_UP || '20s' },
        { target: Number(__ENV.BACKPRESSURE_PEAK_RATE || 40), duration: __ENV.BACKPRESSURE_PLATEAU || '40s' },
        { target: 0, duration: __ENV.BACKPRESSURE_RAMP_DOWN || '20s' },
      ],
      exec: 'command_burst',
    },
    queries_and_balances: {
      executor: 'constant-vus',
      vus: Number(__ENV.BACKPRESSURE_QUERY_VUS || 2),
      duration: totalDuration(),
      exec: 'queries_and_balances',
      startTime: '5s',
    },
    scrape_metrics: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: `${cfg.scrapeIntervalSeconds}s`,
      duration: totalDuration(),
      preAllocatedVUs: 1,
      exec: 'scrape_metrics',
    },
  },
  thresholds: {
    pocoma_unexpected_failure_rate: ['rate==0'],
    pocoma_prometheus_scrape_success: ['value>=1'],
    pocoma_observed_projection_outbox_pending: ['max>=0'],
    pocoma_observed_projection_tasks_pending: ['max>=0'],
    http_req_failed: ['rate<0.35'],
    'http_req_duration{kind:command}': ['p(95)<2000'],
  },
};

export function setup() {
  return seedPots(cfg);
}

export function command_burst(data) {
  runValidCommand(data);
  sleep(Number(__ENV.BACKPRESSURE_COMMAND_SLEEP_SECONDS || 0.02));
}

export function queries_and_balances(data) {
  runQueriesAndBalances(data);
  sleep(0.2);
}

export function scrape_metrics() {
  scrapePrometheus(cfg.baseUrl, prometheusState);
}

export function teardown(data) {
  cleanup(data);
}

function totalDuration() {
  return __ENV.BACKPRESSURE_DURATION || '90s';
}
