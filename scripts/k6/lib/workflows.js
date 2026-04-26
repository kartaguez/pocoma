import http from 'k6/http';
import {
  bodyOrNull,
  expectStatus,
  recordExpectedFailure,
  requireBody,
} from './checks.js';
import { PocomaClient, parseShareholders, potVersion } from './pocoma-client.js';
import { UNKNOWN_UUID, hotItems, label, pick, runId } from './scenario-data.js';

export function seedPots(cfg) {
  const client = new PocomaClient(cfg.baseUrl, cfg.userId);
  const id = runId();
  const pots = [];

  for (let index = 0; index < cfg.seedPots; index += 1) {
    const pot = createSeedPot(client, cfg, id, index);
    pots.push(pot);
  }

  return {
    cfg,
    runId: id,
    pots,
    hotPots: hotItems(pots, cfg.hotPots),
  };
}

export function createSeedPot(client, cfg, id, index) {
  const createdPot = requireBody(client.createPot(label(cfg.labelPrefix, 'pot', id, index)), 'create pot');
  let version = createdPot.version;
  const potId = createdPot.id;

  const addedShareholders = parseShareholders(client.addShareholders(potId, version));
  version = addedShareholders.version;

  const linkedShareholders = parseShareholders(client.linkShareholders(
    potId,
    addedShareholders.aliceShareholderId,
    addedShareholders.bobShareholderId,
    cfg.aliceUserId,
    cfg.bobUserId,
    version,
  ));
  version = linkedShareholders.version;

  const expenseResponse = requireBody(client.createExpense(
    potId,
    linkedShareholders.aliceShareholderId,
    linkedShareholders.aliceShareholderId,
    linkedShareholders.bobShareholderId,
    label(cfg.labelPrefix, 'expense', id, index),
    version,
  ), 'create expense');

  return {
    potId,
    expenseId: expenseResponse.expenseId,
    aliceShareholderId: linkedShareholders.aliceShareholderId,
    bobShareholderId: linkedShareholders.bobShareholderId,
    version: expenseResponse.version,
  };
}

export function runValidCommand(data) {
  const cfg = data.cfg;
  const client = new PocomaClient(cfg.baseUrl, cfg.userId);
  const target = pick(data.pots);
  const current = currentPotVersion(client, target);
  if (!current) {
    return;
  }

  const choice = ((__VU || 0) + (__ITER || 0)) % 4;
  if (choice === 0) {
    client.updatePotDetails(target.potId, label(cfg.labelPrefix, 'updated-pot', data.runId, __ITER), current);
  }
  else if (choice === 1) {
    client.updateExpenseDetails(
      target.expenseId,
      target.aliceShareholderId,
      label(cfg.labelPrefix, 'updated-expense', data.runId, __ITER),
      current,
      50 + ((__ITER || 0) % 20),
    );
  }
  else if (choice === 2) {
    client.updateExpenseShares(target.expenseId, target.aliceShareholderId, target.bobShareholderId, current);
  }
  else {
    client.createExpense(
      target.potId,
      target.aliceShareholderId,
      target.aliceShareholderId,
      target.bobShareholderId,
      label(cfg.labelPrefix, 'extra-expense', data.runId, __ITER),
      current,
      20 + ((__ITER || 0) % 30),
    );
  }
}

export function runConcurrentConflict(data) {
  const cfg = data.cfg;
  const target = pick(data.hotPots || data.pots, 7);
  const client = new PocomaClient(cfg.baseUrl, cfg.userId);
  const current = currentPotVersion(client, target);
  if (!current) {
    return;
  }

  const bodyA = JSON.stringify({
    label: label(cfg.labelPrefix, 'conflict-a', data.runId, __ITER),
    expectedVersion: current,
  });
  const bodyB = JSON.stringify({
    label: label(cfg.labelPrefix, 'conflict-b', data.runId, __ITER),
    expectedVersion: current,
  });
  const params = client.params({ kind: 'command', operation: 'concurrent_update_pot_details' });
  const responses = http.batch([
    ['PATCH', `${cfg.baseUrl}/api/pots/${target.potId}/details`, bodyA, params],
    ['PATCH', `${cfg.baseUrl}/api/pots/${target.potId}/details`, bodyB, params],
  ]);

  for (const response of responses) {
    if (response.status === 409) {
      recordExpectedFailure(response, 'concurrent_update_pot_details', 'version_conflict', [409]);
    }
    if (![200, 409].includes(response.status)) {
      recordExpectedFailure(response, 'concurrent_update_pot_details', 'unexpected', [200, 409]);
    }
  }
  expectStatus(responses[0], [200, 409], { operation: 'concurrent_update_pot_details' });
  expectStatus(responses[1], [200, 409], { operation: 'concurrent_update_pot_details' });
}

export function runIncoherentRequest(data) {
  const cfg = data.cfg;
  const target = pick(data.hotPots || data.pots, 13);
  const client = new PocomaClient(cfg.baseUrl, cfg.userId);
  const choice = ((__VU || 0) + (__ITER || 0)) % 4;

  if (choice === 0) {
    const response = client.rawCommand('PATCH', `/api/pots/${target.potId}/details`, {
      label: label(cfg.labelPrefix, 'stale', data.runId, __ITER),
      expectedVersion: 1,
    }, { operation: 'stale_update_pot_details' });
    recordExpectedFailure(response, 'stale_update_pot_details', 'version_conflict', [409]);
  }
  else if (choice === 1) {
    const response = client.rawCommand('PATCH', `/api/pots/${UNKNOWN_UUID}/details`, {
      label: label(cfg.labelPrefix, 'unknown', data.runId, __ITER),
      expectedVersion: 1,
    }, { operation: 'unknown_pot_update' });
    recordExpectedFailure(response, 'unknown_pot_update', 'not_found', [404]);
  }
  else if (choice === 2) {
    const outsider = client.withUser(cfg.outsiderUserId);
    const current = currentPotVersion(client, target) || target.version;
    const response = outsider.rawCommand('PATCH', `/api/pots/${target.potId}/details`, {
      label: label(cfg.labelPrefix, 'forbidden', data.runId, __ITER),
      expectedVersion: Math.max(1, current),
    }, { operation: 'forbidden_pot_update' });
    recordExpectedFailure(response, 'forbidden_pot_update', 'forbidden', [403]);
  }
  else {
    const current = currentPotVersion(client, target) || target.version;
    const response = client.rawCommand('POST', `/api/pots/${target.potId}/expenses`, {
      payerId: target.aliceShareholderId,
      amountNumerator: 10,
      amountDenominator: 1,
      label: label(cfg.labelPrefix, 'invalid', data.runId, __ITER),
      shares: [],
      expectedVersion: Math.max(1, current),
    }, { operation: 'invalid_create_expense' });
    recordExpectedFailure(response, 'invalid_create_expense', 'bad_request', [400]);
  }
}

export function runQueriesAndBalances(data) {
  const cfg = data.cfg;
  const client = new PocomaClient(cfg.baseUrl, cfg.userId);
  const target = pick(data.pots, 3);

  expectStatus(client.listPots(), [200], { kind: 'query', operation: 'list_pots' });
  expectStatus(client.getPot(target.potId), [200], { kind: 'query', operation: 'get_pot' });
  expectStatus(client.listPotExpenses(target.potId), [200], { kind: 'query', operation: 'list_pot_expenses' });
  expectStatus(client.getExpense(target.expenseId), [200], { kind: 'query', operation: 'get_expense' });
  expectStatus(client.getPotBalances(target.potId), [200, 404], { kind: 'query', operation: 'get_pot_balances' });
  expectStatus(client.listMyPotBalances(), [200], { kind: 'query', operation: 'list_my_pot_balances' });
}

export function cleanup(data) {
  const cfg = data.cfg;
  const client = new PocomaClient(cfg.baseUrl, cfg.userId);

  for (const pot of data.pots || []) {
    tryDeletePotExpenses(client, pot);
    tryDeletePot(client, pot);
  }
}

function currentPotVersion(client, target) {
  const response = client.getPot(target.potId);
  if (response.status !== 200) {
    return undefined;
  }
  return potVersion(response);
}

function tryDeletePotExpenses(client, pot) {
  const listResponse = client.listPotExpenses(pot.potId);
  if (listResponse.status !== 200) {
    return;
  }
  const expenses = bodyOrNull(listResponse) || [];
  for (const expense of expenses) {
    if (!expense || !expense.id || expense.deleted) {
      continue;
    }
    const version = currentPotVersion(client, pot);
    if (!version) {
      continue;
    }
    client.deleteExpense(expense.id, version);
  }
}

function tryDeletePot(client, pot) {
  const version = currentPotVersion(client, pot);
  if (!version) {
    return;
  }
  client.deletePot(pot.potId, version);
}
