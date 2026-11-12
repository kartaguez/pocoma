// ==============================================================================
// Pocoma V3 — Performance Bench API Client (scripts/k6/lib/v3-api.js)
// Asynchronous V3 admission, terminal results and READ probes (C-03).
//
// Polling classification (never silently merged):
//   200 matching predicate      -> success (first observed upper bound)
//   404                         -> not yet materialized, continue
//   503 + Retry-After           -> READ maintenance (fail-closed) -> abort probe
//   503 without Retry-After     -> READ HTTP transport capacity refusal
//                                  (HTTP_PERMITS=2, non-blocking) -> counted, continue
//   other status                -> hard failure -> abort probe
//   deadline reached            -> censored timeout (kept in the sample)
// No token or binding token is ever logged.
// ==============================================================================

import http from 'k6/http';
import { sleep } from 'k6';

export function classify503(res) {
  const h = res.headers || {};
  const retryAfter = h['Retry-After'] || h['retry-after'];
  return retryAfter ? 'maintenance' : 'capacity';
}

export function fraction(n, d) {
  return { numerator: String(n), denominator: String(d) };
}

export class V3ApiClient {
  constructor(baseUrl, token, bindingToken, options = {}) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.writeUrl = (options.writeUrl || baseUrl).replace(/\/$/, '');
    this.token = token;
    this.bindingToken = bindingToken;
    this.pollIntervalMs = options.pollIntervalMs || 25;
    this.maxPollTimeoutMs = options.maxPollTimeoutMs || 8000;
  }

  headers(extra = {}, withBinding = false) {
    const h = { 'Content-Type': 'application/json', ...extra };
    if (this.token) h.Authorization = `Bearer ${this.token}`;
    if (withBinding && this.bindingToken) h['X-Pocoma-Binding-Token'] = this.bindingToken;
    return h;
  }

  // ---- Admission -------------------------------------------------------------
  submitCommand(operation, potId, observedVersion, payload, idempotencyKey, retryOptions = {}) {
    const key = idempotencyKey || `perf-${operation}-${potId}-${observedVersion}-${Math.random().toString(36).slice(2, 10)}`;
    const body = JSON.stringify({ operation, potId, observedVersion, bindingToken: this.bindingToken, payload });
    const maxRetries = typeof retryOptions.maxRetries === 'number' ? retryOptions.maxRetries : 0;
    const baseBackoffMs = typeof retryOptions.backoffMs === 'number' ? retryOptions.backoffMs : 10;

    let attempts = 0;
    let retries = 0;
    let firstDuration = 0;
    const t0 = Date.now();
    let res = null;
    let requestId = null;

    while (attempts <= maxRetries) {
      attempts++;
      res = http.post(`${this.writeUrl}/api/v3/commands`, body, {
        headers: this.headers({ 'Idempotency-Key': key }),
        tags: { endpoint: 'admission_command', operation },
      });
      if (attempts === 1) {
        firstDuration = res.timings.duration;
      }
      if (res.status === 202) {
        try { requestId = res.json().requestId || null; } catch (_) { requestId = null; }
        break;
      }
      if (res.status === 503 && classify503(res) === 'capacity' && attempts <= maxRetries) {
        retries++;
        sleep((baseBackoffMs * attempts) / 1000);
        continue;
      }
      break;
    }

    const admittedAt = Date.now();
    return {
      status: res ? res.status : 0,
      duration: firstDuration,
      totalAdmissionDuration: admittedAt - t0,
      requestId,
      admittedAt,
      attempts,
      retries,
      wasRetried: retries > 0,
      isCapacity503: res ? (res.status === 503 && classify503(res) === 'capacity') : false,
      isMaintenance503: res ? (res.status === 503 && classify503(res) === 'maintenance') : false,
    };
  }

  // ---- Pure Read Queries -----------------------------------------------------
  getPotCurrent(potId) {
    const start = Date.now();
    const res = http.get(`${this.baseUrl}/api/v3/pots/${potId}`, {
      headers: this.headers({}, true),
      tags: { endpoint: 'read_pot_current' },
    });
    let data = null;
    if (res.status === 200) {
      try { data = res.json(); } catch (_) { data = null; }
    }
    return {
      status: res.status,
      duration: res.timings.duration,
      elapsed: Date.now() - start,
      data,
      isCapacity503: res.status === 503 && classify503(res) === 'capacity',
      isMaintenance503: res.status === 503 && classify503(res) === 'maintenance',
    };
  }

  getPotVersion(potId, version) {
    const start = Date.now();
    const res = http.get(`${this.baseUrl}/api/v3/pots/${potId}/versions/${version}`, {
      headers: this.headers({}, true),
      tags: { endpoint: 'read_pot_version' },
    });
    let data = null;
    if (res.status === 200) {
      try { data = res.json(); } catch (_) { data = null; }
    }
    return {
      status: res.status,
      duration: res.timings.duration,
      elapsed: Date.now() - start,
      data,
      isCapacity503: res.status === 503 && classify503(res) === 'capacity',
      isMaintenance503: res.status === 503 && classify503(res) === 'maintenance',
    };
  }

  getPotBalance(potId, version) {
    const start = Date.now();
    const res = http.get(`${this.baseUrl}/api/v3/pots/${potId}/versions/${version}/balance`, {
      headers: this.headers({}, true),
      tags: { endpoint: 'read_pot_balance' },
    });
    let data = null;
    if (res.status === 200) {
      try { data = res.json(); } catch (_) { data = null; }
    }
    return {
      status: res.status,
      duration: res.timings.duration,
      elapsed: Date.now() - start,
      data,
      isCapacity503: res.status === 503 && classify503(res) === 'capacity',
      isMaintenance503: res.status === 503 && classify503(res) === 'maintenance',
    };
  }

  queryExpenses(potId, limit = 50) {
    const start = Date.now();
    const res = http.get(`${this.baseUrl}/api/v3/pots/${potId}/expenses/query?limit=${limit}`, {
      headers: this.headers({}, true),
      tags: { endpoint: 'read_expenses_query' },
    });
    let data = null;
    if (res.status === 200) {
      try { data = res.json(); } catch (_) { data = null; }
    }
    return {
      status: res.status,
      duration: res.timings.duration,
      elapsed: Date.now() - start,
      data,
      isCapacity503: res.status === 503 && classify503(res) === 'capacity',
      isMaintenance503: res.status === 503 && classify503(res) === 'maintenance',
    };
  }

  // ---- Generic bounded poll --------------------------------------------------
  poll(url, withBinding, accept, endpointTag, startedAt = null) {
    const start = startedAt || Date.now();
    const deadline = start + this.maxPollTimeoutMs;
    const stats = { attempts: 0, notFound: 0, capacity503: 0 };
    while (Date.now() < deadline) {
      stats.attempts++;
      const res = http.get(url, { headers: this.headers({}, withBinding), tags: { endpoint: endpointTag } });
      if (res.status === 200) {
        let json = null;
        try { json = res.json(); } catch (_) { json = null; }
        if (json !== null && accept(json)) {
          return { outcome: 'OK', data: json, elapsed: Date.now() - start, ...stats };
        }
        // 200 not satisfying predicate (e.g. W<V not possible with minVersion, or balance V mismatch)
      } else if (res.status === 404) {
        stats.notFound++;
      } else if (res.status === 503) {
        if (classify503(res) === 'maintenance') {
          return { outcome: 'MAINTENANCE_503', elapsed: Date.now() - start, ...stats };
        }
        stats.capacity503++;
      } else {
        return { outcome: 'HTTP_ERROR', status: res.status, elapsed: Date.now() - start, ...stats };
      }
      sleep(this.pollIntervalMs / 1000);
    }
    return { outcome: 'TIMEOUT', elapsed: Date.now() - start, ...stats };
  }

  pollCommandResult(requestId, startedAt) {
    return this.poll(`${this.baseUrl}/api/v3/commands/${requestId}/result`, false,
      (j) => typeof j.outcome === 'string', 'command_result', startedAt);
  }

  pollPotCurrent(potId, minVersion, accept, startedAt) {
    return this.poll(`${this.baseUrl}/api/v3/pots/${potId}?minVersion=${minVersion}`, true,
      accept || ((j) => typeof j.potVersion === 'number' && j.potVersion >= minVersion), 'read_pot_current', startedAt);
  }

  pollPotBalance(potId, version, startedAt) {
    return this.poll(`${this.baseUrl}/api/v3/pots/${potId}/versions/${version}/balance`, true,
      (j) => j.potVersion === version, 'read_pot_balance', startedAt);
  }

  getPotCurrentStatus(potId) {
    const res = http.get(`${this.baseUrl}/api/v3/pots/${potId}`, { headers: this.headers({}, true), tags: { endpoint: 'read_pot_current' } });
    return res.status;
  }
}
