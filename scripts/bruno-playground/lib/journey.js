// Public consumer only. Evidence records method/path/status and assertions, no bodies or tokens.
exports.run = async function (bru, expect) {
  const crypto = require('crypto'), fs = require('fs');
  const owner = bru.getVar('bearerOwner'), guest = bru.getVar('bearerGuest');
  const trace = [], checks = [];
  if (!owner || !guest || owner === guest) throw new Error('Two distinct real sessions required');
  const call = async (who, method, path, data, binding, key) => {
    const write = method === 'POST' && ['/commands','/identity/requests'].includes(path);
    let r;
    try {
      r = await bru.sendRequest({method, url: bru.getVar(write ? 'writeUrl' : 'readUrl') + '/api/v3' + path,
        headers: {Connection:'close', ...(who ? {Authorization:'Bearer '+who} : {}),
          ...(data === undefined ? {} : {'Content-Type':'application/json'}),
          ...(binding ? {'X-Pocoma-Binding-Token':binding} : {}), ...(key ? {'Idempotency-Key':key} : {})},
        ...(data === undefined ? {} : {data}), timeout:5000, validateStatus:()=>true});
    } catch (_) { throw new Error('Playground HTTP transport failed'); }
    trace.push({method,path,status:r.status});
    expect(r.headers['cache-control']).to.equal('no-store');
    return r;
  };
  const poll = async (who, path, binding, predicate=()=>true) => {
    const deadline = performance.now()+30000;
    while(performance.now()<deadline) {
      const r=await call(who,'GET',path,undefined,binding);
      if(r.status===200 && predicate(r.data)) return r.data;
      expect([200,404,503]).to.include(r.status);
      await bru.sleep(100);
    }
    throw new Error('Playground bounded polling deadline: '+path);
  };
  const accepted = r => {expect(r.status).to.equal(202);expect(Object.keys(r.data)).to.deep.equal(['requestId']);return r.data.requestId;};
  const submit = async (who, path, body, key=crypto.randomUUID(), outcome='COMPLETED') => {
    const id = accepted(await call(who,'POST',path,body,undefined,key));
    const result = await poll(who,path+'/'+id+'/result');
    expect(result.outcome).to.equal(outcome);
    return {id,result};
  };
  const binding = async who => {
    const current = await call(who,'GET','/me/binding');
    const alreadyBound = current.status===200 && current.data.active;
    const r = await submit(who,'/identity/requests',{operation:'Registration'},crypto.randomUUID(),alreadyBound?'REJECTED':'COMPLETED');
    if(alreadyBound) {expect(r.result.rejection).to.equal('TARGET_ALREADY_BOUND');return current.data.bindingToken;}
    return (await poll(who,'/me/binding',undefined,d=>d.active && d.euiVersion>=r.result.targetVersion)).bindingToken;
  };
  const b = await binding(owner), g = await binding(guest);
  checks.push('Registration and current binding');
  expect((await call(null,'GET','/me/binding')).status).to.equal(401);
  const pot=crypto.randomUUID(), first=crypto.randomUUID(), second=crypto.randomUUID(), expense=crypto.randomUUID();
  const command = (who, token, operation, version, payload, key) =>
    submit(who,'/commands',{operation,potId:pot,observedVersion:version,bindingToken:token,payload},key);
  const read = (who,token,v) => poll(who,'/pots/'+pot+'?minVersion='+v,token,d=>d.potVersion>=v);
  const exact = (data,amount) => {
    expect(data.potVersion).to.be.a('number');
    // Independently calculated equal shares of 10/3 or 20/3: +5/3,-5/3 and +10/3,-10/3.
    expect(data.balances).to.deep.equal({[first]:{n:String(amount),d:'3'},[second]:{n:String(-amount),d:'3'}});
  };
  const key=crypto.randomUUID();
  const created=await command(owner,b,'CreatePot',0,{label:'Bruno playground'},key);
  expect(created.result.resultingPotVersion).to.equal(1);
  expect((await command(owner,b,'CreatePot',0,{label:'Bruno playground'},key)).id).to.equal(created.id);
  let view=await read(owner,b,1);expect(view.potVersion).to.equal(1);expect(view.balances).to.deep.equal({});
  checks.push('CreatePot async result READ and idempotent replay');
  const weight={numerator:'1',denominator:'1'};
  const added=await command(owner,b,'AddShareholders',1,{targets:[{id:first,name:'Owner',attachment:'SELF',weight},{id:second,name:'Guest',attachment:'FREE',weight}]});
  expect(added.result.resultingPotVersion).to.equal(2);
  view=await read(owner,b,2);expect(view.shareholders).to.have.length(2);
  expect((await call(guest,'GET','/pots/'+pot,undefined,g)).status).to.equal(404);
  const invitation=await command(owner,b,'CreateInvitation',0,{expiresAt:new Date(Date.now()+60000).toISOString()});
  expect(invitation.result.resultingPotVersion == null).to.equal(true);
  const link=await poll(owner,'/invitations/by-creation-request/'+invitation.id);
  expect(link.link.startsWith('pocoma://invitation/')).to.equal(true);
  const secret=link.link.slice('pocoma://invitation/'.length);
  const foreign=await call(guest,'GET','/invitations/by-creation-request/'+invitation.id);
  expect(foreign.status).to.equal(404);
  const places=await call(guest,'POST','/invitations/available-shareholders/query',{token:secret},g);
  expect(places.status).to.equal(200);expect(places.data.shareholders.map(s=>s.shareholderId)).to.deep.equal([second]);
  expect(Object.keys(places.data.shareholders[0]).sort()).to.deep.equal(['name','shareholderId']);
  const attached=await command(guest,g,'AttachShareholderViaInvitation',0,{invitationToken:secret,shareholderId:second});
  expect(attached.result.resultingPotVersion).to.equal(3);await read(guest,g,3);
  checks.push('Invitation confidentiality available shares acceptance and guest visibility');
  const financial={id:expense,amount:{numerator:'10',denominator:'3'},payerId:first,participants:[{id:first,weight:null},{id:second,weight:null}]};
  const charged=await command(owner,b,'CreateExpense',3,{...financial,label:'Exact 10/3',date:'2026-11-13'});
  expect(charged.result.resultingPotVersion).to.equal(4);
  view=await read(owner,b,4);exact(view,5);
  const balance=await poll(owner,'/pots/'+pot+'/versions/4/balance',b);exact(balance,5);
  const changed=await command(owner,b,'UpdateExpenseFinancials',4,{...financial,amount:{numerator:'20',denominator:'3'}});
  expect(changed.result.resultingPotVersion).to.equal(5);
  view=await read(owner,b,5);exact(view,10);
  const historical=await poll(owner,'/pots/'+pot+'/versions/4',b);expect(historical.potVersion).to.equal(4);exact(historical,5);
  const reference=historical.expenses.find(e=>e.id===expense);
  const batch=await call(owner,'POST','/pots/'+pot+'/versions/4/expenses/query',{references:[reference]},b);
  expect(batch.status).to.equal(200);expect(batch.data.expenses[0].amount).to.deep.equal({n:'10',d:'3'});
  checks.push('CreateExpense update exact independent balance and immutable history');
  const renamed=await command(owner,b,'UpdatePotDetails',5,{label:'Mutation verified'});
  expect(renamed.result.resultingPotVersion).to.equal(6);expect((await read(owner,b,6)).label).to.equal('Mutation verified');
  const removed=await command(owner,b,'DeletePot',6,{});expect(removed.result.resultingPotVersion).to.equal(7);
  // A 404 alone is never the deletion oracle: terminal version 7 is proven first.
  await poll(owner,'/pots/'+pot+'/versions/6',b,d=>d.potVersion===6);
  const deadline=performance.now()+30000;let deleted=false;
  while(performance.now()<deadline) {
    const current=await call(owner,'GET','/pots/'+pot,undefined,b);
    const terminal=await call(owner,'GET','/pots/'+pot+'/versions/7',undefined,b);
    if(current.status===404 && terminal.status===404){deleted=true;break;}
    expect([200,404,503]).to.include(current.status);await bru.sleep(100);
  }
  expect(deleted).to.equal(true);exact(await poll(owner,'/pots/'+pot+'/versions/6',b),10);
  checks.push('Delete terminal result current and terminal 404 retained history');
  const record={scenario:'PG01',verdict:'PASS',checks,requests:trace};
  if(process.env.POCOMA_PG_RECEIPT)fs.appendFileSync(process.env.POCOMA_PG_RECEIPT,JSON.stringify(record)+'\n');
};
