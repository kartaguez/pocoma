// Native Bruno 4.2.1 OAuth cache in GUI; private process environment in CLI.
// Tokens remain local variables: never setEnvVar, setVar, console, files or URLs.
const CLIENT = 'pocoma-functional-tests';
const IDS = {owner:'playground-owner',guest:'playground-guest'};
const FLOW = {owner:'00 - Getting Started / 00 Login Owner',guest:'00 - Getting Started / 01 Login Guest'};

function validateLocalUrl(rawUrl) {
  const url = new URL(rawUrl);
  const invalidHost = !['localhost', '127.0.0.1'].includes(url.hostname);
  const hasForbiddenParts = Boolean(url.username || url.password || url.search || url.hash);
  if (invalidHost || url.protocol !== 'http:' || hasForbiddenParts) {
    throw new Error('Playground requires exact local public URLs.');
  }
  return rawUrl.replace(/\/$/, '');
}

exports.configure = function (bru, processEnv = {}) {
  const issuer = processEnv.POCOMA_PG_ISSUER || bru.getEnvVar('issuerUrl');
  if (bru.getVar('issuerUrl') && bru.getVar('issuerUrl') !== issuer) bru.deleteAllVars();
  for (const [name, env] of [['writeUrl', 'POCOMA_PG_WRITE'], ['readUrl', 'POCOMA_PG_READ'], ['issuerUrl', 'POCOMA_PG_ISSUER']]) {
    const value = processEnv[env] || bru.getEnvVar(name);
    if (!value) throw new Error('Environnement playground absent : lancer ./pocoma-local gui puis choisir Instance-<id>.');
    bru.setVar(name, validateLocalUrl(value)); // Only public configuration, never sessions.
  }
  const management = processEnv.POCOMA_PG_MANAGEMENT || bru.getEnvVar('managementReadUrl');
  if (management) {
    bru.setVar('managementReadUrl', validateLocalUrl(management));
  }
};

function decodeJwtClaims(token) {
  if (typeof token !== 'string') throw new Error();
  const parts = token.split('.');
  if (parts.length !== 3) throw new Error();
  return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
}

function hasValidClaims(claims, issuerUrl) {
  const audience = typeof claims.aud === 'string' ? [claims.aud] : claims.aud;
  const nowSeconds = Date.now() / 1000;
  const validIdentity = claims.iss === issuerUrl && claims.azp === CLIENT && typeof claims.sub === 'string' && Boolean(claims.sub);
  const validAudience = Array.isArray(audience) && audience.includes('pocoma-api');
  const validTiming = Number.isFinite(claims.exp) && claims.exp > nowSeconds + 5 && Number.isFinite(claims.iat) && claims.iat <= nowSeconds + 60;
  const hasOpenId = (claims.scope || '').split(' ').includes('openid');
  return validIdentity && validAudience && validTiming && hasOpenId;
}

function session(bru, env, role) {
  if (!IDS[role]) throw new Error('Identite attendue : owner ou guest.');
  const cli = Boolean(env.POCOMA_PG_OWNER || env.POCOMA_PG_GUEST);
  const token = cli ? env[role === 'owner' ? 'POCOMA_PG_OWNER' : 'POCOMA_PG_GUEST']
    : bru.getOauth2CredentialVar('$oauth2.' + IDS[role] + '.access_token');
  const action = 'Session ' + role + ' absente, expiree ou invalide : ouvrir ' + FLOW[role] + ', Auth > Get Access Token, puis relancer la requete.';
  try {
    const claims = decodeJwtClaims(token);
    if (!hasValidClaims(claims, bru.getVar('issuerUrl'))) throw new Error();
    // This is a cache/usability guard. The API still validates signature/AuthN/AuthZ.
    return { token, subject: claims.sub };
  } catch (_) { throw new Error(action); }
}

exports.session = session;

exports.tokens = function (bru, env = {}) {
  const owner=session(bru,env,'owner'), guest=session(bru,env,'guest');
  if (owner.subject===guest.subject) throw new Error('Deux identites distinctes requises : reconnecter 01 Login Guest avec guest (Auth > Clear Cache puis Get Access Token).');
  return {owner:owner.token,guest:guest.token};
};
