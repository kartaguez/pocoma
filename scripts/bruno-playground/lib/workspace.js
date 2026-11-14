// Only authentication, runtime variables, same-resource GET polling and assertions.
// Every business mutation is the visible .bru request. Never log bodies/credentials.
const crypto = require('crypto');
const sessions = require('./session.js');
const title = role => role === 'owner' ? 'Owner' : 'Guest';
const defaults = {actor:'owner',potVersion:0,identityOwnerVersion:0,identityGuestVersion:0,pageLimit:20,invitationTtlSeconds:600};
const scenarioVars = ['potId','potVersion','readVersion','historyVersion','deletedVersion','shareholderOwnerId','shareholderGuestId',
  'expenseId','expenseReferences','invitationId','invitationToken','invitationRequestId','commandRequestId','ownerCommandRequestId',
  'replayAdmission','nextCursor'];

function required(bru, name) {
  const value = bru.getVar(name);
  if (value === undefined || value === null || value === '')
    throw new Error('Prerequis absent : '+name+'. Voir Docs de la requete et le guide Bruno.');
  return value;
}
function roleOf(bru, option) {
  const role = option === 'actor' ? bru.getVar('actor') : option === 'command' ? required(bru,'commandRole') : option;
  if (!['none','owner','guest'].includes(role)) throw new Error('actor doit valoir owner ou guest.');
  return role;
}
function binding(bru, role) { return required(bru,'binding'+title(role)); }
function bodyOf(bru, req) {
  const raw = req.getBody({raw:true});
  return typeof raw === 'string' ? JSON.parse(bru.interpolate(raw)) : raw;
}
function trace(bru, method, url, status) {
  const u = new URL(url);
  // No query strings, bodies, OAuth URL, invitation token or binding.
  const values = bru.getVar('_pollTrace') || [];
  values.push({method,path:u.pathname,status});bru.setVar('_pollTrace',values);
}

exports.prepare = async function (bru, req, options) {
  for (const [key,value] of Object.entries(defaults)) if (bru.getVar(key) === undefined) bru.setVar(key,bru.getEnvVar(key) ?? value);
  // The selected actor is a public setting. A GUI environment edit takes effect
  // even if an earlier journey populated runtime defaults.
  if (bru.getEnvVar('actor')) bru.setVar('actor',bru.getEnvVar('actor'));
  bru.setVar('_assertions',0);bru.setVar('_pollTrace',[]);
  if (options.newPot) {
    for (const key of scenarioVars) bru.deleteVar(key);
    bru.setVar('potVersion',0);bru.setVar('historyVersion',1);
    for (const key of ['potId','shareholderOwnerId','shareholderGuestId','expenseId']) bru.setVar(key,crypto.randomUUID());
  }
  for (const name of options.requires || []) required(bru,name);
  const role = roleOf(bru,options.role);
  bru.setVar('_requestRole',role);
  if (role !== 'none') {
    const session = sessions.session(bru,process.env,role);
    // Changing an account/instance cannot silently retain a previous binding.
    const context = bru.getVar('issuerUrl')+'|'+session.subject;
    if (bru.getVar('_sessionContext'+title(role === 'owner' ? 'guest' : 'owner')) === context)
      throw new Error('Deux identites distinctes requises : 01 Login Guest > Auth > Clear Cache puis Get Access Token avec guest.');
    if (bru.getVar('_sessionContext'+title(role)) && bru.getVar('_sessionContext'+title(role)) !== context) {
      bru.deleteVar('binding'+title(role));
      throw new Error('Identite ou instance changee : relire I03 Owner Binding / I06 Guest Binding.');
    }
    bru.setVar('_sessionContext'+title(role),context);
    bru.setVar('actorSubject',session.subject);
    req.setHeader('Authorization','Bearer '+session.token);
    if (options.binding || (options.kind === 'commandAdmission') || options.operation === 'Unlink') {
      bru.setVar('bindingActor',binding(bru,role));
      if (options.binding) req.setHeader('X-Pocoma-Binding-Token',binding(bru,role));
    }
  }
  if (options.saveStaleBinding) bru.setVar('staleGuestBinding',binding(bru,'guest'));
  if (options.kind === 'replay' || options.kind === 'conflict') {
    const saved = required(bru,'replayAdmission');
    if (saved.role !== role) throw new Error('Rejouer avec la meme identite que l admission memorisee.');
    const data = JSON.parse(saved.body);
    if (options.kind === 'conflict') {
      if (!Object.hasOwn(data.payload,'label')) throw new Error('A05 requiert une derniere intention avec payload.label (P01 ou P02).');
      data.payload.label += ' conflict';
    }
    req.setBody(data);req.setHeader('Idempotency-Key',saved.key);
  } else if (options.kind.endsWith('Admission') || options.newKey) {
    bru.setVar('idempotencyKey',crypto.randomUUID());
    req.setHeader('Idempotency-Key',bru.getVar('idempotencyKey'));
  }
  if (options.newExpiry) {
    const seconds = Number(bru.getVar('invitationTtlSeconds'));
    if (!Number.isFinite(seconds) || seconds <= 0) throw new Error('invitationTtlSeconds doit etre positif.');
    bru.setVar('invitationExpiresAt',new Date(Date.now()+seconds*1000).toISOString());
  }
  // Poll only this same, visible GET, never an admission or another business step.
  if (options.wait) {
    if (req.getMethod() !== 'GET') throw new Error('Polling reserve aux GET.');
    const url = bru.interpolate(req.getUrl());
    if (url.includes('{{')) throw new Error('URL non resolue : consulter les prerequis.');
    const deadline = performance.now()+30000;
    let ready = false;
    while (performance.now() < deadline) {
      let r;
      try { r = await bru.sendRequest({method:'GET',url,headers:req.getHeaders(),timeout:Math.max(1,Math.min(5000,deadline-performance.now())),validateStatus:()=>true}); }
      catch (_) { throw new Error('Transport playground indisponible ; verifier ./pocoma-local status.'); }
      trace(bru,'GET',url,r.status);
      if (r.headers['cache-control'] !== 'no-store') throw new Error('Polling : no-store absent.');
      if (r.status === options.status) { ready=true;break; }
      if (![200,404,503].includes(r.status)) throw new Error('Lecture refusee pendant attente ; verifier session et prerequis.');
      await bru.sleep(100);
    }
    if (!ready) throw new Error('Attente READ bornee a 30 s ; conserver requestId, consulter status, ne pas dupliquer l intention.');
  }
};

exports.check = function (bru, expect, condition, message) {
  bru.setVar('_assertions',(bru.getVar('_assertions') || 0)+1);
  expect(Boolean(condition),message).to.equal(true);
};
exports.transport = function (bru,res,expect,options) {
  const check = (ok,message) => exports.check(bru,expect,ok,message);
  check(res.status === (options.status || 202),'HTTP status attendu');
  if (!['discovery','health','prometheus'].includes(options.kind)) check(res.headers['cache-control'] === 'no-store','Cache-Control no-store');
};
exports.verify = function (bru,req,res,expect,options) {
  const check = (ok,message) => exports.check(bru,expect,ok,message);
  const d = res.body, role = bru.getVar('_requestRole'), suffix = title(role);
  // The second test independently refuses a response which failed transport.
  check(res.status === (options.status || 202),'Statut requis avant propagation de variables');
  const keys = (value,names) => value && JSON.stringify(Object.keys(value).sort()) === JSON.stringify(names.slice().sort());
  const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
  if (options.kind === 'error' || options.kind === 'conflict') {
    check(keys(d,['code','message','traceId']),'Erreur publique minimale');
    check(typeof d.code === 'string' && typeof d.message === 'string' && uuid(d.traceId),'Erreur publique typee');
    return;
  }
  if (options.kind === 'discovery') {
    check(d.issuer === bru.getVar('issuerUrl'),'Discovery issuer exact');
    check(d.code_challenge_methods_supported.includes('S256'),'PKCE S256 disponible');
    check(d.response_types_supported.includes('code'),'Authorization Code disponible');return;
  }
  if (options.kind === 'commandAdmission' || options.kind === 'identityAdmission') {
    check(keys(d,['requestId']) && uuid(d.requestId),'Admission durable : requestId seul');
    const body = bodyOf(bru,req);
    check(body.operation === options.operation,'Operation contractuelle');
    const category = options.kind === 'commandAdmission' ? 'commands' : 'identity/requests';
    check(res.headers.location === '/api/v3/'+category+'/'+d.requestId+'/result','Location correlee');
    if (options.kind === 'identityAdmission') {
      bru.setVar('identity'+suffix+'RequestId',d.requestId);
      bru.setVar('identity'+suffix+'Operation',body.operation);
    } else {
      bru.setVar('commandRequestId',d.requestId);bru.setVar('commandRole',role);
      if (role === 'owner') bru.setVar('ownerCommandRequestId',d.requestId);
      bru.setVar('commandOperation',body.operation);
      bru.setVar('commandOutcome',options.outcome || 'COMPLETED');
      bru.setVar('commandRejection',options.rejection || null);
      bru.setVar('replayAdmission',{body:JSON.stringify(body),key:req.getHeader('Idempotency-Key'),role,requestId:d.requestId});
      if (body.operation === 'CreatePot') {
        bru.setVar('potId',body.potId);
        if (role === 'guest') bru.setVar('historicalCommandRequestId',d.requestId);
      }
      if (body.operation === 'CreateInvitation' && !options.rejection) bru.setVar('invitationRequestId',d.requestId);
    }
  } else if (options.kind === 'replay') {
    check(keys(d,['requestId']) && d.requestId === bru.getVar('replayAdmission').requestId,'Meme intention : meme requestId');
  } else if (options.kind === 'identityResult') {
    check(d.requestId === bru.getVar('identity'+suffix+'RequestId'),'Identity requestId correle');
    const operation = bru.getVar('identity'+suffix+'Operation');
    check(d.operation === operation.toUpperCase(),'Identity operation correlee (REGISTRATION/UNLINK)');
    check(d.outcome === 'COMPLETED' || (operation === 'Registration' && d.outcome === 'REJECTED' && d.rejection === 'TARGET_ALREADY_BOUND'),'Identity terminal attendu');
    if (d.outcome === 'COMPLETED') {
      check(Number.isInteger(d.targetVersion) && d.targetVersion > 0,'Identity targetVersion positive');
      bru.setVar('identity'+suffix+'Version',d.targetVersion);
    }
    bru.setVar('identity'+suffix+'Active',operation === 'Registration');
  } else if (options.kind === 'binding') {
    const active = bru.getVar('identity'+suffix+'Active') !== false;
    check(d.active === active && Number.isInteger(d.euiVersion) && d.euiVersion >= bru.getVar('identity'+suffix+'Version'),'Occurrence propre a la version attendue');
    if (active) {check(typeof d.bindingToken === 'string' && d.bindingToken.length > 0,'Binding actif present');bru.setVar('binding'+suffix,d.bindingToken);}
    else {check(d.bindingToken == null,'Aucun binding actif apres Unlink');bru.deleteVar('binding'+suffix);}
  } else if (options.kind === 'historicalCommandResult') {
    check(d.requestId === bru.getVar('historicalCommandRequestId') && d.operation === 'CreatePot' && d.outcome === 'COMPLETED','Resultat historique prive conserve apres Unlink');
  } else if (options.kind === 'commandResult') {
    check(d.requestId === bru.getVar('commandRequestId') && d.operation === bru.getVar('commandOperation'),'Command requestId/operation correles');
    check(d.outcome === bru.getVar('commandOutcome'),'Command outcome attendu');
    if (d.outcome === 'REJECTED') check(d.rejection === bru.getVar('commandRejection'),'Raison metier attendue');
    else if (d.resultingPotVersion != null) {
      check(Number.isInteger(d.resultingPotVersion) && d.resultingPotVersion > 0,'Version Pot terminale positive');
      bru.setVar('potVersion',d.resultingPotVersion);
      if (d.operation === 'DeletePot') bru.setVar('deletedVersion',d.resultingPotVersion);
    } else check(d.operation === 'CreateInvitation' && !Object.hasOwn(d,'link'),'Invitation sans version Pot ni lien secret dans result');
  } else if (options.kind === 'pot' || options.kind === 'history') {
    check(d.id === bru.getVar('potId'),'Pot id correle');
    check(options.kind === 'history' ? d.potVersion === Number(bru.getVar('historyVersion')) : d.potVersion >= Number(bru.getVar('potVersion')),'Version READ attendue');
    check(Array.isArray(d.shareholders) && Array.isArray(d.expenses) && d.balances && typeof d.label === 'string','Facade Pot complete');
    check(!JSON.stringify(d).includes('userId') && !Object.hasOwn(d,'authorizedUserIds'),'Confidentialite DTO Pot');
    bru.setVar('readVersion',d.potVersion);
    bru.setVar('expenseReferences',JSON.stringify(d.expenses.filter(e=>!e.deleted).slice(0,50)));
  } else if (options.kind === 'balance') {
    check(d.potId === bru.getVar('potId') && d.potVersion === Number(bru.getVar('readVersion')),'Balance au meme Pot@version');
    exports.zeroSum(bru,expect,d.balances);
  } else if (options.kind === 'expenses') {
    check(d.potId === bru.getVar('potId') && d.potVersion === Number(bru.getVar('readVersion')),'Batch au meme Pot@version');
    const refs = bodyOf(bru,req).references;
    check(Array.isArray(d.expenses) && d.expenses.length === refs.length,'Batch complet sans troncature');
    check(d.expenses.every(e=>refs.some(r=>JSON.stringify(r)===JSON.stringify(e.reference)) && typeof e.label==='string' && typeof e.amount.n==='string' && typeof e.amount.d==='string'),'References opaques et DTO Expense');
  } else if (options.kind === 'navigation') {
    check(Array.isArray(d.potIds) && d.potIds.every(uuid) && d.potIds.length <= Number(bru.getVar('pageLimit')),'Page bornee propre');
    check(d.nextCursor === null || typeof d.nextCursor === 'string','Curseur opaque');bru.setVar('nextCursor',d.nextCursor);
  } else if (options.kind === 'invitation') {
    check(d.potId === bru.getVar('potId') && uuid(d.invitationId),'Invitation du Pot attendu');
    check(typeof d.link === 'string' && d.link.startsWith('pocoma://invitation/'),'Lien invitation prive');
    bru.setVar('invitationId',d.invitationId);bru.setVar('invitationToken',d.link.slice('pocoma://invitation/'.length));
  } else if (options.kind === 'places') {
    check(keys(d,['shareholders']) && Array.isArray(d.shareholders) && d.shareholders.length > 0,'Places eligibles sans donnees Pot');
    check(d.shareholders.every(s=>keys(s,['shareholderId','name']) && uuid(s.shareholderId)),'Places : shareholderId/name seuls');
    bru.setVar('shareholderGuestId',d.shareholders[0].shareholderId);
  } else if (options.kind === 'diagnostics') {
    check(d && typeof d === 'object','Diagnostics JSON expurges');
  } else if (options.kind === 'health') {
    check(d.status === 'UP','Health UP (preuve technique seulement)');
  } else if (options.kind === 'prometheus') {
    check(typeof d === 'string' && d.includes('pocoma_v3_'),'Metriques V3 presentes');
  } else throw new Error('Contrat Bruno inconnu.');
};

exports.zeroSum = function (bru,expect,balances) {
  let n=0n,d=1n;
  for (const value of Object.values(balances)) {
    exports.check(bru,expect,typeof value.n === 'string' && /^-?\d+$/.test(value.n) && typeof value.d === 'string' && /^[1-9]\d*$/.test(value.d),'Rationnel exact n/d');
    const a=BigInt(value.n),b=BigInt(value.d);n=n*b+a*d;d*=b;
  }
  exports.check(bru,expect,n===0n,'Somme exacte des soldes nulle');
};
exports.exact = function (bru,expect,balances,ownerN,guestN,denominator) {
  const expected = {[bru.getVar('shareholderOwnerId')]:{n:String(ownerN),d:String(denominator)},[bru.getVar('shareholderGuestId')]:{n:String(guestN),d:String(denominator)}};
  exports.check(bru,expect,JSON.stringify(Object.entries(balances).sort())===JSON.stringify(Object.entries(expected).sort()),'Soldes calcules independamment');
  exports.zeroSum(bru,expect,balances);
};
exports.record = function (bru,req,res,options) {
  if (!process.env.POCOMA_PG_RECEIPT) return;
  const url=bru.interpolate(req.getUrl());
  trace(bru,req.getMethod(),url,res.status);
  require('fs').appendFileSync(process.env.POCOMA_PG_RECEIPT,JSON.stringify({request:options.id,verdict:'PASS',role:bru.getVar('_requestRole'),
    assertions:bru.getVar('_assertions'),operation:options.operation || null,requests:bru.getVar('_pollTrace')})+'\n');
};
