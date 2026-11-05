const fs=require('node:fs');
const axios=require('axios');
exports.run=async function(scenario,bru,expect){
 const actors=JSON.parse(fs.readFileSync(process.env.POCOMA_I3_AUTH_FILE,'utf8'));
 const base=process.env.POCOMA_I2_BASE_URL+'/api/v3';
 const requests=[];
 const call=async(method,path,data,who='E1',key)=>{
  const headers={'Connection':'close'};
  if(who)headers.Authorization='Bearer '+actors[who].token;
  if(key)headers['Idempotency-Key']=key;
  if(data!==undefined)headers['Content-Type']='application/json';
  const r=await axios({method,url:base+path,data,headers,timeout:5000,proxy:false,validateStatus:()=>true});
  requests.push({method,path,status:r.status});
  expect(r.headers['cache-control']).to.equal('no-store');
  return r;
 };
 const route=()=>'/identity/link-requests/'+bru.getVar('i3Proposal');
 const target={issuer:actors.E2.issuer,subject:actors.E2.subject};
 const intention=()=>({bindingToken:bru.getVar('i3E1Binding'),target});
 const poll=async(path,who)=>{
  const end=performance.now()+20000;
  for(let n=0;n<100&&performance.now()<end;n++){
   const r=await call('GET',path,undefined,who);
   if(r.status===200)return r.data;
   expect(r.status).to.equal(404);await bru.sleep(50);
  }
  throw new Error('I3 bounded asynchronous READ deadline');
 };
 const error=(r,status)=>{expect(r.status).to.equal(status);expect(Object.keys(r.data).sort()).to.deep.equal(['code','message','traceId']);};
 switch(scenario){
 case 'B301':{
  const r=await call('POST','/identity/requests',{operation:'Registration'},'E1','i3-registration');expect(r.status).to.equal(202);
  expect((await poll('/identity/requests/'+r.data.requestId+'/result','E1')).outcome).to.equal('COMPLETED');
  const binding=await poll('/me/binding','E1');expect(binding.active).to.equal(true);bru.setVar('i3E1Binding',binding.bindingToken);break;
 }
 case 'B302':{
  const r=await call('POST','/identity/link-requests',intention(),'E1','i3-proposal');expect(r.status).to.equal(201);
  bru.setVar('i3Proposal',r.data.linkRequestId);
  const s=await call('GET',route(),undefined,'E2');expect(s.status).to.equal(200);
  expect(s.data.initiator).to.deep.equal({issuer:actors.E1.issuer,subject:actors.E1.subject});expect(s.data.target).to.deep.equal(target);
  expect(s.data.action).to.equal('LINK_TO_INITIATOR_ACCOUNT');expect(s.data.state).to.equal('PENDING');expect(s.data.requestId).to.equal(null);expect(s.data.potCount).to.equal(null);
  expect(Object.keys(s.data).sort()).to.deep.equal(['linkRequestId','initiator','target','action','createdAt','expiresAt','state','requestId','potCount','potCountAsOf'].sort());
  error(await call('GET','/identity/requests/'+r.data.linkRequestId+'/result'),404);break;
 }
 case 'B303':error(await call('GET',route(),undefined,'E3'),404);error(await call('POST',route()+'/accept',undefined,'E3'),404);break;
 case 'B304':error(await call('POST',route()+'/accept',undefined,'E1'),404);break;
 case 'B305':{
  error(await call('POST',route()+'/accept',undefined,null),401);
  const original=actors.E2.token;const parts=original.split('.');parts[2]=(parts[2][0]==='A'?'B':'A')+parts[2].slice(1);actors.E2.token=parts.join('.');
  error(await call('POST',route()+'/accept',undefined,'E2'),401);actors.E2.token=original;break;
 }
 case 'B307':{
  const r=await call('POST',route()+'/accept',undefined,'E2');expect(r.status).to.equal(202);expect(r.data.linkRequestId).to.equal(bru.getVar('i3Proposal'));expect(r.data.requestId).to.equal(bru.getVar('i3Proposal'));
  bru.setVar('i3Request',r.data.requestId);expect((await poll('/identity/requests/'+r.data.requestId+'/result','E1')).outcome).to.equal('COMPLETED');
  const replay=await call('POST',route()+'/accept',undefined,'E2');expect(replay.status).to.equal(202);expect(replay.data).to.deep.equal(r.data);
  const binding=await poll('/me/binding','E2');expect(binding.active).to.equal(true);bru.setVar('i3E2Binding',binding.bindingToken);break;
 }
 case 'B308':{
  error(await call('POST',route()+'/accept',{target:{issuer:actors.E3.issuer,subject:actors.E3.subject}},'E2'),400);
  error(await call('POST','/identity/link-requests',{...intention(),bindingToken:'substituted'},'E1','i3-proposal'),409);
  error(await call('POST','/identity/link-requests',{...intention(),expiresAt:'2099-01-01'},'E1','forged'),400);
  error(await call('POST','/identity/requests',{operation:'Link'},'E1','direct'),400);break;
 }
 case 'B309':{
  error(await call('GET','/me/binding?subject='+encodeURIComponent(actors.E2.subject)),400);
  const own=await call('GET','/me/binding');expect(own.status).to.equal(200);expect(own.data.bindingToken===bru.getVar('i3E1Binding')).to.equal(true);
  expect(own.data.bindingToken===bru.getVar('i3E2Binding')).to.equal(false);
  const summary=await call('GET',route());expect(JSON.stringify(summary.data).includes(bru.getVar('i3E2Binding'))).to.equal(false);break;
 }
 case 'B310':error(await call('GET','/identity/requests/'+bru.getVar('i3Request')+'/result',undefined,'E2'),404);expect((await poll('/identity/requests/'+bru.getVar('i3Request')+'/result','E1')).outcome).to.equal('COMPLETED');break;
 case 'B313':{
  const r=await call('POST','/identity/link-requests',{target,bindingToken:bru.getVar('i3E1Binding')},'E1','i3-proposal');expect(r.status).to.equal(201);expect(r.data.linkRequestId).to.equal(bru.getVar('i3Proposal'));
  error(await call('GET','/identity/link-requests/00000000-0000-0000-0000-000000000099',undefined,'E2'),404);break;
 }
 default:throw new Error('Unknown I3 scenario');
 }
 fs.appendFileSync(process.env.POCOMA_I3_RECEIPTS,JSON.stringify({scenario,requests,verdict:'PASS'})+'\n');
};
