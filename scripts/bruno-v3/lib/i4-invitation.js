// B4 is an independent corpus. Receipts contain method/path/status, never bearer secrets or bodies.
exports.run = async function (scenario, bru, expect) {
  const fs = require('fs'), crypto = require('crypto');
  const base = bru.getVar('baseUrl') + '/api/v3';
  const owner = process.env.POCOMA_I4_BEARER_OWNER;
  const guest = process.env.POCOMA_I4_BEARER_GUEST;
  const second = process.env.POCOMA_I4_BEARER_SECOND;
  if (!owner || !guest || !second) throw new Error('B4 requires three independent authenticated sessions');
  const requests = [];
  const call = async (who, method, path, data, key, binding) => {
    let r;
    try {
      r = await bru.sendRequest({method, url: base + path, headers: {
        Connection: 'close', Authorization: 'Bearer ' + who,
        ...(data === undefined ? {} : {'Content-Type': 'application/json'}),
        ...(key ? {'Idempotency-Key': key} : {}),
        ...(binding ? {'X-Pocoma-Binding-Token': binding} : {})
      }, ...(data === undefined ? {} : {data}), timeout: 5000, validateStatus: () => true});
    } catch (_) { throw new Error('B4 transport unavailable'); }
    requests.push({method, path, status: r.status});
    expect(r.headers['cache-control']).to.equal('no-store');
    return r;
  };
  const accepted = r => { expect(r.status).to.equal(202); expect(Object.keys(r.data)).to.deep.equal(['requestId']); return r.data.requestId; };
  const wait = async (who, path, predicate, binding) => {
    const start = performance.now();
    for (let n = 0; n < 200 && performance.now() - start < 20000; n++) {
      const r = await call(who, 'GET', path, undefined, undefined, binding);
      if (r.status === 200 && predicate(r.data)) return r.data;
      expect([200,404,503]).to.include(r.status); await bru.sleep(50);
    }
    throw new Error('B4 bounded READ polling deadline');
  };
  const terminal = (who, id, category = 'commands', outcome = 'COMPLETED') =>
    wait(who, '/' + category + '/' + id + '/result', r => {expect(r.outcome).to.equal(outcome); return true;});
  const binding = async who => {
    const current = await call(who, 'GET', '/me/binding');
    if (current.status === 200 && current.data.active) return current.data.bindingToken;
    const id = accepted(await call(who, 'POST', '/identity/requests', {operation:'Registration'}, crypto.randomUUID()));
    const result = await terminal(who, id, 'identity/requests');
    const state = await wait(who, '/me/binding', r => r.active && r.euiVersion >= result.targetVersion);
    return state.bindingToken;
  };
  const ownerBinding = await binding(owner), guestBinding = await binding(guest), secondBinding = await binding(second);
  const pot = crypto.randomUUID(), share = crypto.randomUUID(), another = crypto.randomUUID();
  const command = async (who, token, operation, payload, version, key = crypto.randomUUID()) => {
    const body = {operation,potId:pot,observedVersion:version,bindingToken:token,payload};
    const id = accepted(await call(who, 'POST', '/commands', body, key));
    return {id, body, result:await terminal(who,id)};
  };
  await command(owner, ownerBinding, 'CreatePot', {label:'B4 ' + scenario}, 0);
  await command(owner, ownerBinding, 'AddShareholders', {targets:[share,another].map(id=>({id,name:'Free',attachment:'FREE',weight:{numerator:'1',denominator:'1'}}))}, 1);
  const expired = scenario === 'B404';
  const body = {operation:'CreateInvitation',potId:pot,observedVersion:0,bindingToken:ownerBinding,payload:{expiresAt:expired?'2000-01-01T00:00:00Z':new Date(Date.now()+60000).toISOString()}};
  const key = crypto.randomUUID(), id = accepted(await call(owner,'POST','/commands',body,key));
  const result = await terminal(owner,id,'commands',expired?'REJECTED':'COMPLETED');
  expect(result.resultingPotVersion == null).to.equal(true);
  expect(Object.keys(result).includes('link')).to.equal(false);
  expect(accepted(await call(owner,'POST','/commands',body,key))).to.equal(id);
  if (expired) {
    expect(result.rejection).to.equal('INVALID_INVITATION');
    expect((await call(owner,'GET','/invitations/by-request/'+id)).status).to.equal(404);
  } else {
    const value = await wait(owner,'/invitations/by-request/'+id,r=>typeof r.link==='string');
    expect(value.link.startsWith('pocoma://invitation/')).to.equal(true);
    const secret = value.link.slice('pocoma://invitation/'.length);
    const places = await call(guest,'POST','/invitations/places',{token:secret},undefined,guestBinding);
    expect(places.status).to.equal(200);expect(Object.keys(places.data)).to.deep.equal(['shareholders']);
    expect(places.data.shareholders.length).to.equal(2);
    for (const place of places.data.shareholders) expect(Object.keys(place).sort()).to.deep.equal(['name','shareholderId']);
    expect((await call(guest,'GET','/pots/'+pot,undefined,undefined,guestBinding)).status).to.equal(404);
    if (scenario === 'B402') {
      const foreign = await call(guest,'GET','/invitations/by-request/'+id);
      const missing = await call(guest,'GET','/invitations/by-request/'+crypto.randomUUID());
      expect(foreign.status).to.equal(404);expect(missing.status).to.equal(404);
      expect(foreign.data.code).to.equal(missing.data.code);
      expect(JSON.stringify(foreign.data).includes(secret)).to.equal(false);
      expect((await call(guest,'POST','/invitations/places',{token:secret+'x'},undefined,guestBinding)).status).to.equal(404);
      expect((await call(guest,'POST','/invitations/places',{token:secret,userId:crypto.randomUUID()},undefined,guestBinding)).status).to.equal(400);
    } else {
      const attached = await command(guest,guestBinding,'AttachShareholderViaInvitation',{invitationToken:secret,shareholderId:share},0);
      const version = attached.result.resultingPotVersion;
      await wait(guest,'/pots/'+pot,r=>r.potVersion>=version,guestBinding);
      if (scenario === 'B403') {
        expect((await call(guest,'POST','/invitations/places',{token:secret},undefined,guestBinding)).status).to.equal(404);
        const reused = await command(second,secondBinding,'AttachShareholderViaInvitation',{invitationToken:secret,shareholderId:another},0);
        expect(reused.result.outcome).to.equal('COMPLETED');
        const retained = await call(owner,'GET','/invitations/by-request/'+id);
        expect(retained.data.link === value.link).to.equal(true);
      } else {
        const departed = await command(guest,guestBinding,'UpdateShareholderDetails',{targets:[{id:share,name:'Free',attachment:'DETACH'}]},version);
        const current = await wait(owner,'/pots/'+pot,r=>r.potVersion>=departed.result.resultingPotVersion,ownerBinding);
        expect(current.shareholders.find(s=>s.id===share).isFree).to.equal(true);
        expect((await call(guest,'GET','/pots/'+pot,undefined,undefined,guestBinding)).status).to.equal(404);
        await wait(guest,'/pots/'+pot+'/versions/'+version,r=>r.potVersion===version,guestBinding);
      }
    }
  }
  if (process.env.POCOMA_I4_RECEIPTS) fs.appendFileSync(process.env.POCOMA_I4_RECEIPTS,JSON.stringify({scenario,verdict:'PASS',requests})+'\n');
};
