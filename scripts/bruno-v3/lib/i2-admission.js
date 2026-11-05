// B2 public protocol helpers. SQL atomicity/authority oracles remain in T2 Java fixtures.
exports.run = async function (scenario, bru, expect) {
  const fs=require('fs');
  const base=bru.getVar('baseUrl')+'/api/v3';
  const owner=process.env.POCOMA_I2_bearerOwner, other=process.env.POCOMA_I2_bearerOther;
  let requests=1;
  const id=n=>'00000000-0000-0000-0000-'+String(n).padStart(12,'0');
  const pot=id(700),share=id(701),expense=id(702),large=id(703);
  const token=()=>bru.getVar('i2Binding');
  const call=async(method,path,data,key,who=owner,extra={})=>{
    requests++;
    try {
      const r=await bru.sendRequest({method,url:base+path,headers:{Connection:'close',...(who?{Authorization:'Bearer '+who}:{}),...(data===undefined?{}:{'Content-Type':'application/json'}),...(key?{'Idempotency-Key':key}:{}),...extra},...(data===undefined?{}:{data}),timeout:5000,validateStatus:()=>true});
      expect(r.headers['cache-control']).to.equal('no-store');return r;
    } catch(error) { if(error.name==='AssertionError')throw error;throw new Error('B2 transport unavailable'); }
  };
  const accepted=r=>{expect(r.status).to.equal(202);expect(Object.keys(r.data)).to.deep.equal(['requestId']);expect(r.headers.location.endsWith('/result')).to.equal(true);return r.data.requestId;};
  const error=(r,status)=>{expect(r.status).to.equal(status);expect(Object.keys(r.data).sort()).to.deep.equal(['code','message','traceId']);expect(JSON.stringify(r.data).length).to.be.below(1025);};
  const poll=async(path,outcome='COMPLETED',who=owner)=>{
    const start=performance.now();let calls=0;
    while(calls++<200&&performance.now()-start<20000){const r=await call('GET',path,undefined,null,who);if(r.status===200){expect(r.data.outcome).to.equal(outcome);return r.data;}expect(r.status).to.equal(404);await bru.sleep(50);}
    throw new Error('B2 bounded terminal polling deadline');
  };
  const binding=async(minimumVersion)=>{
    const start=performance.now();let calls=0;
    while(calls++<200&&performance.now()-start<20000){const r=await call('GET','/me/binding');if(r.status===200&&r.data.active&&r.data.euiVersion>=minimumVersion){bru.setVar('i2Binding',r.data.bindingToken);return;}expect([200,404]).to.include(r.status);await bru.sleep(50);}
    throw new Error('B2 bounded binding polling deadline');
  };
  const fraction=(n,d)=>({numerator:String(n),denominator:String(d)});
  const body=(operation,payload,potId=pot)=>({operation,potId,observedVersion:operation==='CreatePot'?0:Number(bru.getVar('i2Version')),bindingToken:token(),payload});
  const command=async(operation,payload,key=scenario,potId=pot)=>{
    const b=body(operation,payload,potId),r=await call('POST','/commands',b,key);const requestId=accepted(r);const result=await poll('/commands/'+requestId+'/result');bru.setVar('i2Version',result.resultingPotVersion);return {requestId,body:b,result};
  };
  const participants=()=>[{id:share,weight:null}];
  switch(scenario){
    case 'B201': {
      const request=accepted(await call('POST','/identity/requests',{operation:'Registration'},scenario));const result=await poll('/identity/requests/'+request+'/result');await binding(result.targetVersion);bru.setVar('i2Registration',request);break;
    }
    case 'B202': {const c=await command('CreatePot',{label:'Bruno I2'},'original-create');bru.setVar('i2CreateBody',JSON.stringify(c.body));bru.setVar('i2CreateId',c.requestId);break;}
    case 'B203':await command('UpdatePotDetails',{label:'Edited'});break;
    case 'B204':await command('AddShareholders',{targets:[{id:share,name:'Owner',attachment:'SELF',weight:fraction(1,1)}]});break;
    case 'B205':await command('UpdateShareholderDetails',{targets:[{id:share,name:'Renamed',attachment:'KEEP'}]});break;
    case 'B206':await command('UpdateShareholderWeights',{targets:[{id:share,weight:fraction(2,1)}]});break;
    case 'B207':await command('CreateExpense',{id:expense,label:'Expense',date:'2026-11-04',amount:fraction(10,3),payerId:share,participants:participants()});break;
    case 'B208':await command('UpdateExpenseDetails',{id:expense,label:'Edited expense',date:'2026-11-05'});break;
    case 'B209':await command('UpdateExpenseFinancials',{id:expense,amount:fraction(20,3),payerId:share,participants:participants()});break;
    case 'B210':await command('DeleteExpense',{id:expense});break;
    case 'B211':await command('DeleteShareholder',{id:share});break;
    case 'B212':await command('DeletePot',{});break;
    case 'B213':expect(accepted(await call('POST','/commands',JSON.parse(bru.getVar('i2CreateBody')),'original-create'))).to.equal(bru.getVar('i2CreateId'));break;
    case 'B214':{const b=JSON.parse(bru.getVar('i2CreateBody'));b.payload.label='DIFFERENT-INTENT';error(await call('POST','/commands',b,'original-create'),409);break;}
    case 'B215':{const b=JSON.parse(bru.getVar('i2CreateBody'));const reversed=Object.fromEntries(Object.entries(b).reverse());expect(accepted(await call('POST','/commands',JSON.stringify(reversed,null,2),'original-create'))).to.equal(bru.getVar('i2CreateId'));break;}
    case 'B216':{const r=accepted(await call('POST','/commands',JSON.parse(bru.getVar('i2CreateBody')),'original-create',other));expect(r).to.not.equal(bru.getVar('i2CreateId'));await poll('/commands/'+r+'/result','REJECTED',other);error(await call('GET','/commands/'+r+'/result'),404);break;}
    case 'B217':{error(await call('POST','/commands',{...JSON.parse(bru.getVar('i2CreateBody')),requesterEui:{}},scenario),400);error(await call('POST','/identity/requests',{operation:'Registration'},scenario,null),401);break;}
    case 'B218':{const r=accepted(await call('POST','/commands',body('UpdatePotDetails',{label:'missing'},id(799)),scenario));const result=await poll('/commands/'+r+'/result','REJECTED');expect(result.rejection).to.equal('NOT_FOUND');break;}
    case 'B219':{
      await command('CreatePot',{label:'101 atomic targets'},'large-create',large);
      const targets=Array.from({length:101},(_,n)=>({id:id(1000+n),name:'Self '+n,attachment:'SELF',weight:fraction(2,2)}));const c=await command('AddShareholders',{targets},scenario,large);bru.setVar('i2LargeId',c.requestId);
      const start=performance.now();let got=false;for(let n=0;n<200&&performance.now()-start<20000;n++){const r=await call('GET','/pots/'+large+'?minVersion=2',undefined,null,owner,{'X-Pocoma-Binding-Token':token()});if(r.status===200){expect(r.data.shareholders.length).to.equal(101);expect(r.data.shareholders.every(s=>s.isFree===false)).to.equal(true);got=true;break;}expect([404,503]).to.include(r.status);await bru.sleep(50);}expect(got).to.equal(true);break;
    }
    case 'B220':{
      // Internal L3 Link fixture handshake, followed solely by public Unlink admissions.
      fs.writeFileSync(process.env.POCOMA_I2_LINK_SIGNAL,'link-fixture-needed');const ready=process.env.POCOMA_I2_LINK_SIGNAL+'.ready';const start=performance.now();while(!fs.existsSync(ready)&&performance.now()-start<20000)await bru.sleep(25);expect(fs.existsSync(ready)).to.equal(true);
      const initiator=token();for(const target of ['same','owner']){const r=accepted(await call('POST','/identity/requests',{operation:'Unlink',bindingToken:initiator,target:{issuer:process.env.POCOMA_I2_ISSUER,subject:target}},scenario+'-'+target));await poll('/identity/requests/'+r+'/result');}
      await poll('/commands/'+bru.getVar('i2LargeId')+'/result');const stale=body('UpdateShareholderDetails',{targets:[{id:id(1000),name:'Stale',attachment:'SELF'}]},large);stale.bindingToken=initiator;const r=accepted(await call('POST','/commands',stale,'stale-after-unlink'));expect((await poll('/commands/'+r+'/result','REJECTED')).rejection).to.equal('INVALID_BINDING');break;
    }
    case 'B221':for(const operation of ['Link','CreateInvitation'])error(await call('POST','/identity/requests',{operation},scenario),400);break;
    case 'B222':{error(await call('POST','/identity/requests',{operation:'Registration'},scenario,owner,{'Content-Type':'text/plain'}),415);error(await call('POST','/commands',' '.repeat(262145),scenario),413);break;}
    case 'B223':{const r=accepted(await call('POST','/identity/requests',{operation:'Registration'},scenario));const result=await poll('/identity/requests/'+r+'/result');await binding(result.targetVersion);break;}
    case 'B224':{await poll('/commands/'+bru.getVar('i2CreateId')+'/result');error(await call('GET','/commands/'+bru.getVar('i2CreateId')+'/result',undefined,null,other),404);await poll('/identity/requests/'+bru.getVar('i2Registration')+'/result');break;}
    default:throw new Error('Unknown B2 scenario');
  }
  fs.appendFileSync(process.env.POCOMA_I2_REQUEST_RECEIPTS,JSON.stringify({scenario,requests,verdict:'PASS'})+'\n');
};
