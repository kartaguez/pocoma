// B2 public protocol helpers. SQL atomicity/authority oracles remain in T2 Java fixtures.
exports.run = async function (scenario, bru, expect) {
  const fs=require('fs'), crypto=require('crypto');
  const fresh=process.env.POCOMA_L7_FRESH==='1';
  const base=bru.getVar('baseUrl')+'/api/v3';
  const owner=process.env.POCOMA_I2_bearerOwner, other=process.env.POCOMA_I2_bearerOther;
  let requests=1;
  const trace=[];
  const began=performance.now();
  const id=n=>{
    if(!fresh) return '00000000-0000-0000-0000-'+String(n).padStart(12,'0');
    const key='i2ScenarioId'+n;
    if(!bru.getVar(key)) bru.setVar(key,crypto.randomUUID());
    return bru.getVar(key);
  };
  const pot=id(700),share=id(701),expense=id(702),large=id(703);
  const token=()=>bru.getVar('i2Binding');
  const writePaths=new Set(['/commands','/identity/requests']);
  const call=async(method,path,data,key,who=owner,extra={})=>{
    requests++;
    try {
      const url=fresh&&writePaths.has(path)&&method==='POST'?process.env.POCOMA_L7_WRITE_URL+'/api/v3':base;
      const r=await bru.sendRequest({method,url:url+path,headers:{Connection:'close',...(who?{Authorization:'Bearer '+who}:{}),...(data===undefined?{}:{'Content-Type':'application/json'}),...(key?{'Idempotency-Key':key}:{}),...extra},...(data===undefined?{}:{data}),timeout:5000,validateStatus:()=>true});
      if(fresh) trace.push({method,path,status:r.status,elapsedMs:Math.round(performance.now()-began)});
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
    case 'B209': {
      const c=await command('UpdateExpenseFinancials',{id:expense,amount:fraction(20,3),payerId:share,participants:participants()});
      if(fresh) {
        const v=c.result.resultingPotVersion;
        const headers={'X-Pocoma-Binding-Token':token()};
        const read=async(path,method='GET',body)=>{
          const start=performance.now();
          while(performance.now()-start<20000) {
            const response=await call(method,path,body,null,owner,headers);
            if(response.status===200)return response.data;
            expect([404,503]).to.include(response.status);
            await bru.sleep(50);
          }
          throw new Error('B209 READ availability deadline: '+path);
        };
        const path='/pots/'+pot;
        const current=await read(path+'?minVersion='+v);
        expect(current.potVersion).to.equal(v);
        expect(current.expenses.map(e=>e.id)).to.include(expense);
        const history=await read(path+'/versions/'+(v-1));
        expect(history.potVersion).to.equal(v-1);
        expect(history.expenses.map(e=>e.id)).to.include(expense);
        for(const [manifest,amount] of [[current,{n:'20',d:'3'}],[history,{n:'10',d:'3'}]]) {
          const reference=manifest.expenses.find(e=>e.id===expense);
          const batch=await read(path+'/versions/'+manifest.potVersion+'/expenses/query','POST',{references:[reference]});
          expect(batch.expenses).to.have.length(1);
          expect(batch.expenses[0].reference).to.deep.equal(reference);
          expect(batch.expenses[0].amount).to.deep.equal(amount);
        }
        const balance=await read(path+'/versions/'+v+'/balance');
        expect(balance.balances).to.deep.equal(current.balances);
        const foreign=await call('GET',path,undefined,null,other,headers);
        const absent=await call('GET','/pots/'+crypto.randomUUID(),undefined,null,other,headers);
        expect(foreign.status).to.equal(404);
        expect(absent.status).to.equal(404);
        expect(foreign.data.code).to.equal(absent.data.code);
      }
      break;
    }
    case 'B210':await command('DeleteExpense',{id:expense});break;
    case 'B211':await command('DeleteShareholder',{id:share});break;
    case 'B212':await command('DeletePot',{});break;
    case 'B213':expect(accepted(await call('POST','/commands',JSON.parse(bru.getVar('i2CreateBody')),'original-create'))).to.equal(bru.getVar('i2CreateId'));break;
    case 'B214':{const b=JSON.parse(bru.getVar('i2CreateBody'));b.payload.label='DIFFERENT-INTENT';error(await call('POST','/commands',b,'original-create'),409);break;}
    case 'B215':{const b=JSON.parse(bru.getVar('i2CreateBody'));const reversed=Object.fromEntries(Object.entries(b).reverse());expect(accepted(await call('POST','/commands',JSON.stringify(reversed,null,2),'original-create'))).to.equal(bru.getVar('i2CreateId'));break;}
    case 'B216':{if(fresh){
      const registration=accepted(await call('POST','/identity/requests',{operation:'Registration'},scenario,other));
      await poll('/identity/requests/'+registration+'/result','COMPLETED',other);
      error(await call('GET','/identity/requests/'+registration+'/result'),404);
    }const r=accepted(await call('POST','/commands',JSON.parse(bru.getVar('i2CreateBody')),'original-create',other));expect(r).to.not.equal(bru.getVar('i2CreateId'));await poll('/commands/'+r+'/result','REJECTED',other);error(await call('GET','/commands/'+r+'/result'),404);break;}
    case 'B217':{error(await call('POST','/commands',{...JSON.parse(bru.getVar('i2CreateBody')),requesterEui:{}},scenario),400);error(await call('POST','/identity/requests',{operation:'Registration'},scenario,null),401);if(fresh)error(await call('POST','/identity/requests',{operation:'Registration'},'invalid key'),400);break;}
    case 'B218':{const r=accepted(await call('POST','/commands',body('UpdatePotDetails',{label:'missing'},id(799)),scenario));const result=await poll('/commands/'+r+'/result','REJECTED');expect(result.rejection).to.equal('NOT_FOUND');break;}
    case 'B219':{
      await command('CreatePot',{label:'101 atomic targets'},'large-create',large);
      const targets=Array.from({length:101},(_,n)=>({id:id(1000+n),name:'Self '+n,attachment:'SELF',weight:fraction(2,2)}));const c=await command('AddShareholders',{targets},scenario,large);bru.setVar('i2LargeId',c.requestId);
      const start=performance.now();let got=false;for(let n=0;n<200&&performance.now()-start<20000;n++){const r=await call('GET','/pots/'+large+'?minVersion=2',undefined,null,owner,{'X-Pocoma-Binding-Token':token()});if(r.status===200){expect(r.data.shareholders.length).to.equal(101);expect(r.data.shareholders.every(s=>s.isFree===false)).to.equal(true);got=true;break;}expect([404,503]).to.include(r.status);await bru.sleep(50);}expect(got).to.equal(true);break;
    }
    case 'B220':{
      if(!fresh) {
        // Fixture-only scenario seeds a historical affiliation before public Unlink.
        fs.writeFileSync(process.env.POCOMA_I2_LINK_SIGNAL,'link-fixture-needed');const ready=process.env.POCOMA_I2_LINK_SIGNAL+'.ready';const start=performance.now();while(!fs.existsSync(ready)&&performance.now()-start<20000)await bru.sleep(25);expect(fs.existsSync(ready)).to.equal(true);
      }
      const initiator=token();
      if(fresh) {
        const target={issuer:process.env.POCOMA_I2_ISSUER,subject:process.env.POCOMA_L7_OTHER_SUBJECT};
        const r=accepted(await call('POST','/identity/requests',{operation:'Unlink',bindingToken:initiator,target},scenario+'-other'));
        await poll('/identity/requests/'+r+'/result','REJECTED');
      }
      const targets=fresh?[process.env.POCOMA_L7_OWNER_SUBJECT]:['same','owner'];
      for(const target of targets){const r=accepted(await call('POST','/identity/requests',{operation:'Unlink',bindingToken:initiator,target:{issuer:process.env.POCOMA_I2_ISSUER,subject:target}},scenario+'-'+target));const result=await poll('/identity/requests/'+r+'/result');
        if(fresh) {
          const begin=performance.now();
          let revoked=false;
          while(performance.now()-begin<20000) {
            const state=await call('GET','/me/binding');
            expect(state.status).to.equal(200);
            if(state.data.euiVersion>=result.targetVersion) {
              expect(state.data.active).to.equal(false);
              expect(state.data.bindingToken==null).to.equal(true);
              revoked=true;break;
            }
            await bru.sleep(50);
          }
          if(!revoked) throw new Error('B220 revoked READ binding deadline');
        }
      }
      await poll('/commands/'+bru.getVar('i2LargeId')+'/result');const stale=body('UpdateShareholderDetails',{targets:[{id:id(1000),name:'Stale',attachment:'SELF'}]},large);stale.bindingToken=initiator;const r=accepted(await call('POST','/commands',stale,'stale-after-unlink'));expect((await poll('/commands/'+r+'/result','REJECTED')).rejection).to.equal('INVALID_BINDING');break;
    }
    case 'B221':for(const operation of ['Link','CreateInvitation'])error(await call('POST','/identity/requests',{operation},scenario),400);break;
    case 'B222':{error(await call('POST','/identity/requests',{operation:'Registration'},scenario,owner,{'Content-Type':'text/plain'}),415);error(await call('POST','/commands',' '.repeat(262145),scenario),413);break;}
    case 'B223':{const r=accepted(await call('POST','/identity/requests',{operation:'Registration'},scenario));const result=await poll('/identity/requests/'+r+'/result');await binding(result.targetVersion);break;}
    case 'B224':{await poll('/commands/'+bru.getVar('i2CreateId')+'/result');error(await call('GET','/commands/'+bru.getVar('i2CreateId')+'/result',undefined,null,other),404);await poll('/identity/requests/'+bru.getVar('i2Registration')+'/result');break;}
    default:throw new Error('Unknown B2 scenario');
  }
  fs.appendFileSync(process.env.POCOMA_I2_REQUEST_RECEIPTS,JSON.stringify({scenario,requests,verdict:'PASS',...(fresh?{trace}:{})})+'\n');
};
