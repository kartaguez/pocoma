import http from 'k6/http';
import { Gauge, Trend } from 'k6/metrics';

const TIMER_NAMES = [
  'pocoma_command_persist_latency_seconds',
  'pocoma_projection_event_start_latency_seconds',
  'pocoma_projection_processing_duration_seconds',
  'pocoma_projection_end_to_end_latency_seconds',
];

const timerTrends = {
  pocoma_command_persist_latency_seconds: new Trend('pocoma_observed_command_persist_latency', true),
  pocoma_projection_event_start_latency_seconds: new Trend('pocoma_observed_projection_event_start_latency', true),
  pocoma_projection_processing_duration_seconds: new Trend('pocoma_observed_projection_processing_duration', true),
  pocoma_projection_end_to_end_latency_seconds: new Trend('pocoma_observed_projection_end_to_end_latency', true),
};

export const projectionGapRatio = new Gauge('pocoma_observed_projection_gap_ratio');
export const projectionRetryTotal = new Gauge('pocoma_observed_projection_retry_total');
export const prometheusScrapeSuccess = new Gauge('pocoma_prometheus_scrape_success');

export function newPrometheusState() {
  return {
    timers: {},
  };
}

export function scrapePrometheus(baseUrl, state) {
  const response = http.get(`${baseUrl.replace(/\/$/, '')}/actuator/prometheus`, {
    tags: { kind: 'observability', operation: 'scrape_prometheus' },
  });
  const ok = response.status === 200 && response.body && response.body.indexOf('pocoma_') >= 0;
  prometheusScrapeSuccess.add(ok ? 1 : 0);
  if (!ok) {
    return false;
  }

  const samples = parsePrometheus(response.body);
  recordTimers(samples, state);
  recordGaps(samples);
  recordRetries(samples);
  return true;
}

export function parsePrometheus(text) {
  const samples = [];
  for (const line of text.split('\n')) {
    if (!line || line[0] === '#') {
      continue;
    }
    const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)$/);
    if (!match) {
      continue;
    }
    samples.push({
      name: match[1],
      labels: parseLabels(match[3] || ''),
      value: Number(match[4]),
    });
  }
  return samples;
}

function parseLabels(text) {
  const labels = {};
  if (!text) {
    return labels;
  }
  const regex = /([^=,\s]+)="((?:\\"|[^"])*)"/g;
  let match = regex.exec(text);
  while (match !== null) {
    labels[match[1]] = match[2].replace(/\\"/g, '"');
    match = regex.exec(text);
  }
  return labels;
}

function recordTimers(samples, state) {
  for (const timerName of TIMER_NAMES) {
    const grouped = groupTimerSamples(samples, timerName);
    for (const key of Object.keys(grouped)) {
      const current = grouped[key];
      const previous = state.timers[key];
      if (previous && current.count > previous.count) {
        const averageSeconds = (current.sum - previous.sum) / (current.count - previous.count);
        timerTrends[timerName].add(averageSeconds * 1000, current.labels);
      }
      if (current.max !== undefined) {
        timerTrends[timerName].add(current.max * 1000, { ...current.labels, aggregate: 'max' });
      }
      state.timers[key] = current;
    }
  }
}

function groupTimerSamples(samples, timerName) {
  const grouped = {};
  for (const sample of samples) {
    if (!sample.name.startsWith(timerName)) {
      continue;
    }
    const suffix = sample.name.slice(timerName.length);
    if (!['_sum', '_count', '_max'].includes(suffix)) {
      continue;
    }
    const key = `${timerName}|${stableLabelKey(sample.labels)}`;
    const current = grouped[key] || { labels: sample.labels };
    if (suffix === '_sum') {
      current.sum = sample.value;
    }
    if (suffix === '_count') {
      current.count = sample.value;
    }
    if (suffix === '_max') {
      current.max = sample.value;
    }
    grouped[key] = current;
  }
  return grouped;
}

function recordGaps(samples) {
  for (const sample of samples) {
    if (sample.name !== 'pocoma_projection_version_gap') {
      continue;
    }
    projectionGapRatio.add(sample.value, {
      gap_bucket: sample.labels.gap_bucket || sample.labels.gap || 'unknown',
    });
  }
}

function recordRetries(samples) {
  for (const sample of samples) {
    if (sample.name === 'pocoma_projection_retry_total') {
      projectionRetryTotal.add(sample.value, sample.labels);
    }
  }
}

function stableLabelKey(labels) {
  return Object.keys(labels)
    .sort()
    .map((key) => `${key}=${labels[key]}`)
    .join(',');
}
