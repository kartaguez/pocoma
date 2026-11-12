import assert from 'node:assert/strict';
import { createPublicKey, verify } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';

const reserved = createServer().listen(0, '127.0.0.1');
await once(reserved, 'listening');
const port = reserved.address().port;
await new Promise(resolve => reserved.close(resolve));
const directory = mkdtempSync(join(tmpdir(), 'pocoma-spec09-test-issuer-'));
const subjects = join(directory, 'subjects.json');
const output = join(directory, 'tokens.json');
const address = `http://127.0.0.1:${port}`;
const issuer = address + '/spec09-test';
const audience = 'pocoma-local-certification';
writeFileSync(subjects, JSON.stringify(['sample-a', 'sample-b']));
const child = spawn('node', ['scripts/performance/spec09-test-issuer.mjs', '--port', String(port),
  '--issuer', issuer, '--audience', audience, '--subjects', subjects, '--token-file', output,
  '--ttl-seconds', '3600']);
try {
  const [message] = await Promise.race([once(child.stdout, 'data', { signal: AbortSignal.timeout(5000) }),
    once(child, 'exit').then(([code]) => { throw new Error(`test issuer exited ${code}`); })]);
  assert.match(message.toString(), /test JWKS ready/);
  const response = await fetch(address + '/jwks.json', { signal: AbortSignal.timeout(2000) });
  assert.equal(response.status, 200);
  const { keys: [jwk] } = await response.json();
  assert.equal(jwk.d, undefined);
  assert.equal((await fetch(address + '/token', { signal: AbortSignal.timeout(2000) })).status, 404);
  assert.equal(statSync(output).mode & 0o777, 0o600);
  const tokens = JSON.parse(readFileSync(output, 'utf8'));
  for (const [sub, token] of Object.entries(tokens)) {
    const [header, claims, signature] = token.split('.');
    assert(verify('RSA-SHA256', Buffer.from(`${header}.${claims}`),
      createPublicKey({ key: jwk, format: 'jwk' }), Buffer.from(signature, 'base64url')));
    const headerValue = JSON.parse(Buffer.from(header, 'base64url'));
    const claimsValue = JSON.parse(Buffer.from(claims, 'base64url'));
    assert.equal(headerValue.kid, jwk.kid);
    assert.equal(claimsValue.iss, issuer);
    assert.equal(claimsValue.aud, audience);
    assert.equal(claimsValue.sub, sub);
    assert.equal(claimsValue.auth_time, claimsValue.iat);
    assert(Number.isSafeInteger(claimsValue.auth_time) && claimsValue.auth_time >= 0);
    assert.equal(claimsValue.exp - claimsValue.iat, 3600);
  }
  assert.equal(Object.keys(tokens).length, 2);
  console.log(JSON.stringify({ status: 'PASS', scope: 'loopback issuer/JWKS fixture, no application calls or load',
    node: process.version, signedClaimsVerified: 2, privateTokenFileMode: '0600',
    tokenEndpointAbsent: true, issuedTokensArchived: false }));
} finally {
  if (child.exitCode === null && child.signalCode === null) {
    const exited = once(child, 'exit');
    child.kill();
    await exited;
  }
  rmSync(directory, { recursive: true, force: true });
}
