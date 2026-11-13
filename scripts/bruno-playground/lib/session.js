// Native Bruno 4.2.1 OAuth cache in GUI; private process environment in CLI.
// Tokens remain local variables: never setEnvVar, setVar, console, files or URLs.
const CLIENT = 'pocoma-functional-tests';
const IDS = {owner:'playground-owner',guest:'playground-guest'};
const FLOW = {owner:'00 Login Owner',guest:'01 Login Guest'};

exports.configure = function (bru, processEnv = {}) {
  for (const [name, env] of [['writeUrl','POCOMA_PG_WRITE'],['readUrl','POCOMA_PG_READ'],['issuerUrl','POCOMA_PG_ISSUER']]) {
    const value = processEnv[env] || bru.getEnvVar(name);
    if (!value) throw new Error('Environnement playground absent : lancer ./pocoma-local gui puis choisir Instance-<id>.');
    const url = new URL(value);
    if (!['localhost','127.0.0.1'].includes(url.hostname) || url.protocol !== 'http:' || url.username || url.password || url.search || url.hash)
      throw new Error('Playground requires exact local public URLs.');
    bru.setVar(name,value.replace(/\/$/,'')); // Only public configuration, never sessions.
  }
};

function session(bru, env, role) {
  const cli = Boolean(env.POCOMA_PG_OWNER || env.POCOMA_PG_GUEST);
  const token = cli ? env[role==='owner'?'POCOMA_PG_OWNER':'POCOMA_PG_GUEST']
    : bru.getOauth2CredentialVar('$oauth2.'+IDS[role]+'.access_token');
  const action = 'Session '+role+' absente, expiree ou invalide : ouvrir '+FLOW[role]+', Auth > Get Access Token, puis relancer PG01.';
  try {
    if (typeof token!=='string' || token.split('.').length!==3) throw new Error();
    const claims=JSON.parse(Buffer.from(token.split('.')[1],'base64url').toString('utf8'));
    const audience=typeof claims.aud==='string'?[claims.aud]:claims.aud;
    if (claims.iss!==bru.getVar('issuerUrl') || claims.azp!==CLIENT || !Array.isArray(audience) || !audience.includes('pocoma-api') ||
        typeof claims.sub!=='string' || !claims.sub || !Number.isFinite(claims.exp) || claims.exp<=Date.now()/1000+5 ||
        !Number.isFinite(claims.iat) || claims.iat>Date.now()/1000+60 || !(claims.scope||'').split(' ').includes('openid')) throw new Error();
    // This is a cache/usability guard. The API still validates signature/AuthN/AuthZ.
    return {token,subject:claims.sub};
  } catch (_) { throw new Error(action); }
}

exports.tokens = function (bru, env = {}) {
  const owner=session(bru,env,'owner'), guest=session(bru,env,'guest');
  if (owner.subject===guest.subject) throw new Error('Deux identites distinctes requises : reconnecter 01 Login Guest avec guest (Auth > Clear Cache puis Get Access Token).');
  return {owner:owner.token,guest:guest.token};
};
