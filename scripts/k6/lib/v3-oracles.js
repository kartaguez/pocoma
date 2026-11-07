// ==============================================================================
// Pocoma V3 — Performance Bench Oracles (scripts/k6/lib/v3-oracles.js)
// Pure functions (no I/O) so they can be qualified offline by
// scripts/k6/v3-oracles-selftest.js with deterministic discriminating cases.
//
// C-03 (SPEC-09 §4/§5, SPEC-10 §24.4):
//  - Active Pot: first authenticated+authorized READ with minVersion=V returning a
//    complete Pot at W>=V with consistent components and balance of W.
//  - Balance(P,V): exact version V, independent from CurrentPot progression.
//  - DeletePot(D): internal READ observation CurrentPot(P)->PotVersion(P,D).deleted=true.
//    An HTTP 404 alone is NEVER accepted.
//  - Identity: CHANGED result with targetVersion, binding at >= targetVersion and an
//    actually authorized READ using the new bindingToken.
// ==============================================================================

const APPLIED_OUTCOMES = ['CHANGED', 'COMPLETED'];

export class V3Oracles {
  static validateAdmission(status, requestId) {
    if (status !== 202) return { ok: false, errors: [`Admission refused: HTTP ${status}`] };
    if (!requestId) return { ok: false, errors: ['Admission 202 without requestId'] };
    return { ok: true, errors: [] };
  }

  /** Probe outcome from V3ApiClient.poll -> oracle classification. */
  static classifyProbe(probe) {
    switch (probe && probe.outcome) {
      case 'OK': return { ok: true, klass: 'OBSERVED' };
      case 'TIMEOUT': return { ok: false, klass: 'CENSORED_TIMEOUT' };
      case 'MAINTENANCE_503': return { ok: false, klass: 'READ_MAINTENANCE' };
      case 'HTTP_ERROR': return { ok: false, klass: `HTTP_${probe.status}` };
      default: return { ok: false, klass: 'UNKNOWN' };
    }
  }

  /**
   * Terminal command result. An applied command must produce a NEW version
   * strictly greater than the observed one. UNCHANGED / REJECTED are terminal but
   * not applied (excluded from SLO populations, reported separately).
   */
  static validateCommandResult(result, observedVersion) {
    const errors = [];
    if (!result || typeof result !== 'object') return { ok: false, applied: false, errors: ['Command result is not an object'] };
    const outcome = result.outcome;
    if (APPLIED_OUTCOMES.includes(outcome)) {
      const v = result.resultingPotVersion;
      if (typeof v !== 'number' || v < 1) errors.push(`Applied outcome ${outcome} without valid resultingPotVersion (${v})`);
      else if (v <= observedVersion) errors.push(`Terminal result without new version: resultingPotVersion=${v} <= observedVersion=${observedVersion}`);
      return { ok: errors.length === 0, applied: errors.length === 0, version: v, errors };
    }
    if (outcome === 'UNCHANGED') return { ok: false, applied: false, errors: ['Terminal UNCHANGED: no new version produced'] };
    if (outcome === 'REJECTED') return { ok: false, applied: false, errors: [`Terminal REJECTED: ${result.rejection || 'n/a'}`] };
    return { ok: false, applied: false, errors: [`Unknown outcome: ${outcome}`] };
  }

  /**
   * Complete Pot at W>=V. `expected` = { shareholders: n, expenses: m } minimal counts
   * known by the bench for version V (the bench is the only writer of its own Pots).
   */
  static validatePotComplete(pot, minVersion, potId, expected = { shareholders: 0, expenses: 0 }) {
    const errors = [];
    if (!pot || typeof pot !== 'object') return { ok: false, errors: ['Pot response is not an object'] };
    if (potId && pot.id !== potId) errors.push(`Pot id mismatch: ${pot.id}`);
    if (typeof pot.potVersion !== 'number' || pot.potVersion < minVersion) errors.push(`READ lag: W=${pot.potVersion} < V=${minVersion}`);
    if (typeof pot.label !== 'string' || pot.label.length === 0) errors.push('Missing label');
    if (!Array.isArray(pot.shareholders)) errors.push('shareholders not an array');
    else if (pot.shareholders.length < expected.shareholders) errors.push(`Incomplete shareholders: ${pot.shareholders.length} < ${expected.shareholders}`);
    if (!Array.isArray(pot.expenses)) errors.push('expenses not an array');
    else if (pot.expenses.length < expected.expenses) errors.push(`Incomplete expenses: ${pot.expenses.length} < ${expected.expenses}`);
    if (!pot.balances || typeof pot.balances !== 'object' || Array.isArray(pot.balances)) errors.push('balances missing');
    else if (Array.isArray(pot.shareholders)) {
      // Balance of W must cover every shareholder of W (consistency of components).
      for (const sh of pot.shareholders) {
        const id = sh && (sh.id || sh.shareholderId);
        if (id && !(id in pot.balances)) errors.push(`Balance of W missing shareholder ${id}`);
      }
    }
    return { ok: errors.length === 0, errors, observedVersion: pot.potVersion };
  }

  /** Exact Balance(P,V). A Balance(P,V+1) never proves V. */
  static validateBalanceExact(b, version, potId, expectedShareholderIds = []) {
    const errors = [];
    if (!b || typeof b !== 'object') return { ok: false, errors: ['Balance absent'] };
    if (potId && b.potId !== potId) errors.push(`Balance potId mismatch: ${b.potId}`);
    if (b.potVersion !== version) errors.push(`Balance version mismatch: expected exactly ${version}, got ${b.potVersion}`);
    if (!b.balances || typeof b.balances !== 'object') errors.push('Balance map missing');
    else {
      for (const [k, v] of Object.entries(b.balances)) {
        if (!v || typeof v.n !== 'string' || typeof v.d !== 'string' || v.d === '0') errors.push(`Invalid rational for ${k}`);
      }
      for (const id of expectedShareholderIds) if (!(id in b.balances)) errors.push(`Balance missing shareholder ${id}`);
    }
    return { ok: errors.length === 0, errors };
  }

  /**
   * DeletePot internal oracle. `row` comes from the SQL probe:
   * { currentVersion, deleted } for CurrentPot(P) joined with PotVersion(P,current).
   * `httpStatus` is informative only.
   */
  static validateDeletePotInternal(row, terminalVersion, httpStatus = null) {
    const errors = [];
    if (!row) errors.push(`No internal READ observation (HTTP ${httpStatus} is not a proof)`);
    else {
      if (row.currentVersion !== terminalVersion) errors.push(`CurrentPot at ${row.currentVersion}, expected terminal D=${terminalVersion}`);
      if (row.deleted !== true) errors.push('PotVersion(P,D).deleted is not true');
    }
    return { ok: errors.length === 0, errors };
  }

  /** Identity: binding usable only after an actually authorized READ with the new token. */
  static validateIdentityUsable(identityResult, binding, authorizedReadStatus) {
    const errors = [];
    if (!identityResult || identityResult.outcome !== 'CHANGED') errors.push(`Identity outcome not CHANGED (${identityResult && identityResult.outcome})`);
    else if (typeof identityResult.targetVersion !== 'number' || identityResult.targetVersion < 1) errors.push('Invalid targetVersion');
    if (!binding || binding.active !== true || !binding.bindingToken) errors.push('Binding not active');
    else if (identityResult && typeof identityResult.targetVersion === 'number' && binding.euiVersion < identityResult.targetVersion) errors.push(`Binding euiVersion ${binding.euiVersion} < targetVersion ${identityResult.targetVersion}`);
    if (authorizedReadStatus !== 200) errors.push(`Authorized READ with new bindingToken failed: HTTP ${authorizedReadStatus}`);
    return { ok: errors.length === 0, errors };
  }

  /** READ fail-closed during maintenance: 503 + no-store (+ Retry-After). */
  static validateFailClosedRead(status, headers) {
    const errors = [];
    const h = headers || {};
    if (status !== 503) errors.push(`Fail-closed violation: HTTP ${status} during maintenance`);
    const cc = String(h['Cache-Control'] || h['cache-control'] || '').toLowerCase();
    if (!cc.includes('no-store')) errors.push('Missing Cache-Control: no-store');
    if (!(h['Retry-After'] || h['retry-after'])) errors.push('Missing Retry-After (maintenance 503 indistinguishable from capacity 503)');
    return { ok: errors.length === 0, errors };
  }

  /** Offered load integrity: any dropped iteration invalidates throughput claims. */
  static validateInjection(droppedIterations) {
    return droppedIterations > 0
      ? { ok: false, errors: [`dropped_iterations=${droppedIterations}: offered load not actually injected`] }
      : { ok: true, errors: [] };
  }
}
