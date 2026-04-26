import http from 'k6/http';
import { bodyOrNull, recordCommandResponse, requireBody } from './checks.js';

export class PocomaClient {
  constructor(baseUrl, userId) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.userId = userId;
  }

  withUser(userId) {
    return new PocomaClient(this.baseUrl, userId);
  }

  createPot(label) {
    return this.command('POST', '/api/pots', { label }, 'create_pot', [201]);
  }

  updatePotDetails(potId, label, expectedVersion) {
    return this.command(
      'PATCH',
      `/api/pots/${potId}/details`,
      { label, expectedVersion },
      'update_pot_details',
      [200, 409],
    );
  }

  deletePot(potId, expectedVersion) {
    return this.command('DELETE', `/api/pots/${potId}`, { expectedVersion }, 'delete_pot', [200, 409]);
  }

  addShareholders(potId, expectedVersion) {
    return this.command(
      'POST',
      `/api/pots/${potId}/shareholders`,
      {
        shareholders: [
          { name: 'Alice', weightNumerator: 1, weightDenominator: 2 },
          { name: 'Bob', weightNumerator: 1, weightDenominator: 2 },
        ],
        expectedVersion,
      },
      'add_pot_shareholders',
      [201],
    );
  }

  linkShareholders(potId, aliceShareholderId, bobShareholderId, aliceUserId, bobUserId, expectedVersion) {
    return this.command(
      'PATCH',
      `/api/pots/${potId}/shareholders/details`,
      {
        shareholders: [
          { shareholderId: aliceShareholderId, name: 'Alice', userId: aliceUserId },
          { shareholderId: bobShareholderId, name: 'Bob', userId: bobUserId },
        ],
        expectedVersion,
      },
      'link_pot_shareholders',
      [200],
    );
  }

  createExpense(potId, payerId, aliceShareholderId, bobShareholderId, label, expectedVersion, amount = 42) {
    return this.command(
      'POST',
      `/api/pots/${potId}/expenses`,
      {
        payerId,
        amountNumerator: amount,
        amountDenominator: 1,
        label,
        shares: [
          { shareholderId: aliceShareholderId, weightNumerator: 1, weightDenominator: 2 },
          { shareholderId: bobShareholderId, weightNumerator: 1, weightDenominator: 2 },
        ],
        expectedVersion,
      },
      'create_expense',
      [201, 409],
    );
  }

  updateExpenseDetails(expenseId, payerId, label, expectedVersion, amount = 45) {
    return this.command(
      'PATCH',
      `/api/expenses/${expenseId}/details`,
      {
        payerId,
        amountNumerator: amount,
        amountDenominator: 1,
        label,
        expectedVersion,
      },
      'update_expense_details',
      [200, 409],
    );
  }

  updateExpenseShares(expenseId, aliceShareholderId, bobShareholderId, expectedVersion) {
    return this.command(
      'PATCH',
      `/api/expenses/${expenseId}/shares`,
      {
        shares: [
          { shareholderId: aliceShareholderId, weightNumerator: 2, weightDenominator: 5 },
          { shareholderId: bobShareholderId, weightNumerator: 3, weightDenominator: 5 },
        ],
        expectedVersion,
      },
      'update_expense_shares',
      [200, 409],
    );
  }

  deleteExpense(expenseId, expectedVersion) {
    return this.command('DELETE', `/api/expenses/${expenseId}`, { expectedVersion }, 'delete_expense', [200, 409]);
  }

  listPots() {
    return this.get('/api/pots', { kind: 'query', operation: 'list_pots' });
  }

  getPot(potId, version) {
    const query = version ? `?version=${version}` : '';
    return this.get(`/api/pots/${potId}${query}`, { kind: 'query', operation: 'get_pot' });
  }

  listPotExpenses(potId, version) {
    const query = version ? `?version=${version}` : '';
    return this.get(`/api/pots/${potId}/expenses${query}`, { kind: 'query', operation: 'list_pot_expenses' });
  }

  getExpense(expenseId, version) {
    const query = version ? `?version=${version}` : '';
    return this.get(`/api/expenses/${expenseId}${query}`, { kind: 'query', operation: 'get_expense' });
  }

  getPotBalances(potId, version) {
    const query = version ? `?version=${version}` : '';
    return this.get(`/api/pots/${potId}/balances${query}`, { kind: 'query', operation: 'get_pot_balances' });
  }

  listMyPotBalances(version) {
    const query = version ? `?version=${version}` : '';
    return this.get(`/api/pots/balances/me${query}`, { kind: 'query', operation: 'list_my_pot_balances' });
  }

  command(method, path, body, operation, expectedStatuses) {
    const response = http.request(method, `${this.baseUrl}${path}`, JSON.stringify(body), this.params({
      kind: 'command',
      operation,
    }));
    recordCommandResponse(response, operation, expectedStatuses);
    return response;
  }

  rawCommand(method, path, body, tags = {}) {
    return http.request(method, `${this.baseUrl}${path}`, JSON.stringify(body), this.params({
      kind: 'command',
      ...tags,
    }));
  }

  get(path, tags = {}) {
    return http.get(`${this.baseUrl}${path}`, this.params(tags));
  }

  params(tags = {}) {
    return {
      headers: {
        'X-User-Id': this.userId,
        'Content-Type': 'application/json',
        'X-Trace-Id': `k6-${vuId()}-${iterationId()}-${Date.now()}`,
      },
      tags,
    };
  }
}

export function parseShareholders(response) {
  const body = requireBody(response, 'shareholders');
  const alice = (body.shareholders || []).find((shareholder) => shareholder.name === 'Alice');
  const bob = (body.shareholders || []).find((shareholder) => shareholder.name === 'Bob');
  if (!alice || !bob) {
    throw new Error('Missing Alice or Bob shareholder in response');
  }
  return {
    aliceShareholderId: alice.id,
    bobShareholderId: bob.id,
    version: body.version,
  };
}

export function potVersion(response) {
  const body = bodyOrNull(response);
  return body && body.header ? body.header.version : undefined;
}

function vuId() {
  return typeof __VU === 'undefined' ? 0 : __VU;
}

function iterationId() {
  return typeof __ITER === 'undefined' ? 0 : __ITER;
}
