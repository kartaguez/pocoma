// Installed GUI 4.2.1 parser and actual script VM. No rendered GUI claim.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..'),collection=root+'/scripts/bruno-playground';
const asar=process.env.POCOMA_TEST_BRUNO_ASAR||'/Applications/Bruno.app/Contents/Resources/app.asar';
const pkg=require(asar+'/package.json');assert.equal(pkg.version,'4.2.1');
const Bru=require(asar+'/node_modules/@usebruno/js/src/bru');
const Request=require(asar+'/node_modules/@usebruno/js/src/bruno-request');
const lang=require(asar+'/node_modules/@usebruno/lang');
const session=require(collection+'/lib/session'),work=require(collection+'/lib/workspace');
const results=[];
function check(name,fn){fn();results.push({name,verdict:'PASS'});}
const issuer='http://localhost:57825/realms/pocoma';
const envVariables={issuerUrl:issuer,readUrl:'http://127.0.0.1:57824',writeUrl:'http://127.0.0.1:57823'};
const claims=sub=>({iss:issuer,azp:'pocoma-functional-tests',aud:['pocoma-api'],sub,preferred_username:sub==='guest'?'guest':'third',exp:Date.now()/1000+60,iat:Math.floor(Date.now()/1000),scope:'openid'});
const token=(sub,changes={})=>'eyJhbGciOiJub25lIn0.'+Buffer.from(JSON.stringify({...claims(sub),...changes})).toString('base64url')+'.synthetic';
const cache=(a=token('owner'),b=token('guest'))=>({'$oauth2.playground-owner.access_token':a,'$oauth2.playground-guest.access_token':b});
function bru(credentials={}){const b=new Bru({runtime:'nodevm',envVariables:{...envVariables},oauth2CredentialVariables:credentials});session.configure(b);return b;}
check('Cold collection does not require a bearer before native OAuth',()=>assert.equal(bru().getVar('issuerUrl'),issuer));
check('Owner request does not require guest login',()=>assert.ok(session.session(bru(cache(token('owner'),null)),{},'owner').token));
check('Cold request gives actionable owner login',()=>assert.throws(()=>session.session(bru(),{},'owner'),/00 Login Owner/));
check('Guest missing gives actionable guest login',()=>assert.throws(()=>session.session(bru(cache(token('owner'),null)),{},'guest'),/01 Login Guest/));
for(const [name,changes] of Object.entries({expired:{exp:1},wrongIssuer:{iss:'http://localhost:1/realms/pocoma'},wrongAudience:{aud:['foreign']},wrongClient:{azp:'foreign'},missingSubject:{sub:''},missingOpenid:{scope:''},futureIssued:{iat:Date.now()/1000+120}})){
 check('Reject '+name,()=>assert.throws(()=>session.tokens(bru(cache(token('owner',changes)))),/00 Login Owner/));
}
check('Two caches with the same subject are refused',()=>assert.throws(()=>session.tokens(bru(cache(token('same'),token('same')))),/Deux identites/));
check('Malformed token never appears in error',()=>assert.throws(()=>session.tokens(bru(cache('private-malformed'))),e=>!e.message.includes('private-malformed')&&e.message.includes('00 Login Owner')));
check('Sessions are read without copying tokens into runtime variables',()=>{const b=bru(cache());session.tokens(b);assert.deepEqual(Object.keys(b.getAllVars()).sort(),['issuerUrl','readUrl','writeUrl']);});
check('Legacy bearerOwner cannot bypass native login',()=>{const b=bru();b.envVariables.bearerOwner=token('owner');assert.throws(()=>session.tokens(b),/00 Login Owner/);});
check('CLI and GUI session sources never mix',()=>assert.throws(()=>session.tokens(bru(cache()),{POCOMA_PG_OWNER:token('owner')}),/01 Login Guest/));
check('Remote URLs refused',()=>{const b=bru();b.envVariables.writeUrl='https://example.com';assert.throws(()=>session.configure(b),/local public URLs/);});
check('Changing instance clears sensitive scenario variables',()=>{const b=bru(cache());b.setVar('bindingOwner','private-binding');b.setVar('invitationToken','private-invitation');b.envVariables.issuerUrl='http://localhost:57826/realms/pocoma';session.configure(b);assert.equal(b.getVar('bindingOwner'),undefined);assert.equal(b.getVar('invitationToken'),undefined);});
const files=[];
function walk(folder){for(const p of fs.readdirSync(folder,{withFileTypes:true})){const f=path.join(folder,p.name);if(p.isDirectory())walk(f);else if(p.name.endsWith('.bru')&& !['folder.bru','collection.bru'].includes(p.name)&& !f.includes('/environments/'))files.push(f);}}
walk(collection);
for(const f of files) check('Native 4.2.1 parser '+path.relative(collection,f),()=>{
 const parsed=lang.bruToJsonV2(fs.readFileSync(f,'utf8'));assert.ok(parsed.meta.name);assert.ok(parsed.http.url);
 if(f.includes('Login ')){
  const o=parsed.auth.oauth2;assert.equal(o.grantType,'authorization_code');assert.equal(o.pkce,true);assert.equal(o.state,'');
  assert.equal(o.clientId,'pocoma-functional-tests');assert.equal(o.clientSecret,'');assert.equal(o.scope,'openid');
  assert.equal(o.callbackUrl,'http://127.0.0.1:8765/callback');
  assert.equal(o.credentialsId,f.includes('Owner')?'playground-owner':'playground-guest');
  assert.ok(o.authorizationUrl.includes('prompt=login&login_hint='));
 } else {assert.ok(parsed.script.req.includes('workspace.js'));assert.ok(parsed.tests.includes('work.verify'));}
});
async function main(){
 const Runtime=require(asar+'/node_modules/@usebruno/js/src/runtime/script-runtime');
 const runtime=new Runtime({runtime:'nodevm'});
 const collectionScript=lang.collectionBruToJson(fs.readFileSync(collection+'/collection.bru','utf8')).script.req;
 const primary=collection+'/01 - Identity and Registration/I01 Register Owner.bru';
 const script=lang.bruToJsonV2(fs.readFileSync(primary,'utf8')).script.req;
 const makeRequest=values=>({method:'POST',url:envVariables.writeUrl+'/api/v3/identity/requests',headers:{'Content-Type':'application/json'},data:'{"operation":"Registration"}',body:{mode:'json'},oauth2CredentialVariables:values,pathname:primary});
 const values=cache(),request=makeRequest(values);
 const vm=await runtime.runRequestScript(collectionScript+'\n'+script,request,envVariables,{},collection,()=>{throw new Error('Unexpected script logging');},{},{},undefined,{});
 assert.equal(request.headers.Authorization,'Bearer '+values['$oauth2.playground-owner.access_token']);
 assert.ok(!JSON.stringify(vm.runtimeVariables).includes(values['$oauth2.playground-owner.access_token']));assert.equal(vm.envVariables,null);
 results.push({name:'Actual 4.2.1 VM : explicit Registration pre-request, OAuth header only',verdict:'PASS'});
 await assert.rejects(()=>runtime.runRequestScript(collectionScript+'\n'+script,makeRequest({}),envVariables,{},collection,undefined,{},{},undefined,{}),/00 Login Owner/);
 results.push({name:'Actual 4.2.1 VM : cold native cache gives login instructions',verdict:'PASS'});
 await assert.rejects(()=>work.prepare(bru(cache()),new Request({method:'GET',url:envVariables.readUrl+'/api/v3/pots/abc',headers:{}}),{kind:'pot',role:'guest',binding:true}),/Prerequis absent : bindingGuest/);
 results.push({name:'No hidden Registration to repair missing binding',verdict:'PASS'});
 let protocol;
 if(process.env.POCOMA_GUI_TEST_OWNER){
  const TestRuntime=require(asar+'/node_modules/@usebruno/js/src/runtime/test-runtime');
  const tests=new TestRuntime({runtime:'nodevm'});
  const inventory=JSON.parse(fs.readFileSync(collection+'/inventory.json','utf8'));
  const actualEnv={writeUrl:process.env.POCOMA_GUI_TEST_WRITE,readUrl:process.env.POCOMA_GUI_TEST_READ,issuerUrl:process.env.POCOMA_GUI_TEST_ISSUER};
  const values=cache(process.env.POCOMA_GUI_TEST_OWNER,process.env.POCOMA_GUI_TEST_GUEST);
  let variables={};const executed=[];let namedTests=0,assertions=0;
  for(const item of inventory.requests){
   const file=collection+'/'+item.file;
   const parsed=lang.bruToJsonV2(fs.readFileSync(file,'utf8'));
   const request={method:parsed.http.method.toUpperCase(),url:parsed.http.url,headers:Object.fromEntries(parsed.headers.filter(h=>h.enabled).map(h=>[h.name,h.value])),
    data:parsed.body?.json,body:{mode:parsed.http.body},oauth2CredentialVariables:values,pathname:file,
    certsAndProxyConfig:{options:{shouldVerifyTls:true},clientCertificates:[],collectionPath:collection}};
   const pre=await runtime.runRequestScript(collectionScript+'\n'+parsed.script.req,request,actualEnv,variables,collection,()=>{throw new Error('Unexpected logging');},{},{},undefined,{});
   variables=pre.runtimeVariables || variables;
   const b=new Bru({runtime:'nodevm',collectionPath:collection,envVariables:actualEnv,runtimeVariables:variables,oauth2CredentialVariables:values,certsAndProxyConfig:request.certsAndProxyConfig});
   request.url=b.interpolate(request.url);
   for(const key of Object.keys(request.headers))request.headers[key]=b.interpolate(request.headers[key]);
   if(typeof request.data==='string')request.data=b.interpolate(request.data);
   const response=await b.sendRequest({method:request.method,url:request.url,headers:request.headers,...(request.data===undefined?{}:{data:request.data}),timeout:5000,validateStatus:()=>true});
   const post=await tests.runTests(parsed.tests,request,response,actualEnv,variables,collection,()=>{throw new Error('Unexpected logging');},{},{},undefined,{});
   assert.deepEqual(post.results.map(r=>r.description),item.tests);
   assert.ok(post.results.every(r=>r.status==='pass'),item.id+' installed GUI engine assertions');
   variables=post.runtimeVariables || variables;
   assertions+=variables._assertions;namedTests+=post.results.length;
   executed.push({id:item.id,method:request.method,path:new URL(request.url).pathname,status:response.status,tests:post.results.length,assertions:variables._assertions});
   // Allowlist only: no response body, headers or runtime variables are written.
   fs.writeFileSync(process.env.POCOMA_GUI_TEST_OUTPUT+'.requests.json',JSON.stringify(executed,null,2)+'\n');
  }
  protocol={requests:executed.length,namedTests,assertions,journeys:inventory.journeys,transport:'Installed Bruno4.2.1 Bru.sendRequest',sessions:'Real Code+PKCE sessions read through native OAuth-cache API; no rendered login claim'};
  results.push({name:'All delivered journeys: installed 4.2.1 request VM, real HTTP and native test VM',verdict:'PASS'});
 }
 const receipt={verdict:'PASS',bruno:pkg.version,electron:process.versions.electron,asarSha256:crypto.createHash('sha256').update(require('original-fs').readFileSync(asar)).digest('hex'),requestFiles:files.length,checks:results,
  protocol,
  limitation:'Parser and installed script VM only. No browser login, rendered responses, GUI runner or human acceptance implied.'};
 fs.writeFileSync(process.env.POCOMA_GUI_TEST_OUTPUT||'/tmp/pocoma-bruno-gui-controls.json',JSON.stringify(receipt,null,2)+'\n');
 console.log(results.length+' Bruno '+pkg.version+' parser/VM controls PASS ('+files.length+' HTTP files).');
}
main().catch(e=>{fs.writeFileSync(process.env.POCOMA_GUI_TEST_OUTPUT||'/tmp/pocoma-bruno-gui-controls.json',JSON.stringify({verdict:'FAIL',checks:results,errorClass:e.name,frames:(e.stack||'').split('\n').slice(1,6)},null,2)+'\n');console.error('Bruno GUI control FAIL; inspect sanitized receipt.');process.exitCode=1;});
