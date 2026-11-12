const fs = require('fs');
const crypto = require('crypto');

exports.run = async function (scenario, bru, expect) {
  const read = process.env.POCOMA_L7_READ_URL;
  const write = process.env.POCOMA_L7_WRITE_URL;
  const owner = process.env.POCOMA_I2_bearerOwner;
  const receipt = process.env.POCOMA_L7_RECEIPTS;
  if (!read || !write || !owner || !receipt) throw new Error('Installation environment incomplete');
  const requests = [];
  async function check(base, method, path, token, status, data, binding) {
    let response;
    try {
      response = await bru.sendRequest({
        method, url: base + path, timeout: 5000, validateStatus: () => true,
        headers: { Connection: 'close', ...(token ? { Authorization: 'Bearer ' + token } : {}),
          ...(data === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(binding ? { 'X-Pocoma-Binding-Token': binding } : {}) },
        ...(data === undefined ? {} : { data })
      });
    } catch (_) {
      throw new Error('Transport failure for ' + method + ' ' + path);
    }
    requests.push({ method, path, status: response.status });
    expect(response.status, method + ' ' + path).to.equal(status);
    return response.data;
  }
  if (scenario === 'B501') {
    const binding = '/api/v3/me/binding';
    const own = await check(read, 'GET', binding, owner, 200);
    expect(own.active).to.equal(true);
    const invalidTokens = [null, 'invalid-bearer'];
    for (const name of ['POCOMA_L7_EXPIRED_BEARER', 'POCOMA_L7_WRONG_AUDIENCE_BEARER']) {
      const value = process.env[name];
      if (!value) throw new Error('Required Keycloak negative fixture missing: ' + name);
      invalidTokens.push(value);
    }
    for (const token of invalidTokens) await check(read, 'GET', binding, token, 401);
    const navigation = '/api/v3/me/pots?limit=1';
    const first = await check(read, 'GET', navigation, owner, 200, undefined, own.bindingToken);
    expect(first.potIds).to.have.length(1);
    expect(first.nextCursor).to.be.a('string');
    const second = await check(read, 'GET', navigation + '&cursor=' + encodeURIComponent(first.nextCursor),
      owner, 200, undefined, own.bindingToken);
    expect(second.potIds).to.have.length(1);
    expect(second.potIds[0]).not.to.equal(first.potIds[0]);
    const root = '/api/v3/identity/link-requests';
    const id = crypto.randomUUID();
    for (const target of [read, write]) {
      await check(target, 'POST', root, owner, 404, {});
      await check(target, 'GET', root + '/' + id, owner, 404);
      await check(target, 'POST', root + '/' + id + '/accept', owner, 404, {});
    }
  } else if (scenario === 'B502') {
    const api = '/api/v3/diagnostics/stage-handoffs';
    const actuator = '/actuator/stage-handoffs';
    await check(read, 'GET', api, null, 401);
    await check(read, 'GET', api, 'invalid-bearer', 401);
    for (const [route, bearer] of [[api, owner], [actuator, null], [actuator, owner]]) {
      const before = await check(read, 'GET', route + '?limit=1', bearer, 200);
      expect(before.records.length).to.be.at.most(1);
      expect(Object.keys(before.partitionStats)).to.have.length(4);
      expect(before.eventProjections).to.deep.equal({});
      const keys = JSON.stringify(before);
      for (const field of ['commandId', 'potId', 'eventId', 'bindingToken', 'subject']) {
        expect(keys.includes(field)).to.equal(false);
      }
      await check(read, 'GET', route + '?clear=true', bearer, 400);
      const after = await check(read, 'GET', route + '?limit=1', bearer, 200);
      expect(after.totalTracked).to.equal(before.totalTracked);
    }
  } else {
    throw new Error('Unknown installation scenario');
  }
  fs.appendFileSync(receipt, JSON.stringify({ scenario, verdict: 'PASS', requests }) + '\n');
};
