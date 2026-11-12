#!/usr/bin/env node
// Configuration A uses a disposable RSA issuer while Pocoma verifies JWTs and applies normal authorization.
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: {
  host: { type: 'string', default: '127.0.0.1' },
  port: { type: 'string' },
  issuer: { type: 'string' },
  audience: { type: 'string' },
  subjects: { type: 'string' },
  'token-file': { type: 'string' },
  'ttl-seconds': { type: 'string' },
} });

const port = Number(values.port);
const ttl = Number(values['ttl-seconds']);
if (!values.issuer || !values.audience || !values.subjects || !values['token-file']
    || !Number.isSafeInteger(port) || port < 1 || port > 65_535
    || !Number.isSafeInteger(ttl) || ttl < 1) {
  throw new Error('Provide --port, --issuer, --audience, --subjects, --token-file and --ttl-seconds');
}
const issuer = new URL(values.issuer);
if (!['http:', 'https:'].includes(issuer.protocol) || issuer.username || issuer.password
    || issuer.search || issuer.hash) {
  throw new Error('Expected an HTTP(S) issuer without user info, query or fragment');
}
const subjects = JSON.parse(readFileSync(values.subjects, 'utf8'));
if (!Array.isArray(subjects) || !subjects.length
    || subjects.some(subject => typeof subject !== 'string' || !subject.trim())
    || new Set(subjects).size !== subjects.length) {
  throw new Error('Subjects must be a nonempty JSON array of distinct strings');
}

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: randomUUID(), use: 'sig', alg: 'RS256' };
const jwks = JSON.stringify({ keys: [jwk] });
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const header = encode({ alg: jwk.alg, kid: jwk.kid, typ: 'JWT' });

const server = createServer((request, response) => {
  if (request.method !== 'GET' || request.url !== '/jwks.json') {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json' }).end(jwks);
});

server.listen(port, values.host, () => {
  const issued = Math.floor(Date.now() / 1000);
  const tokens = Object.fromEntries(subjects.map(sub => {
    const claims = encode({ iss: values.issuer, sub, aud: values.audience, iat: issued, exp: issued + ttl });
    const message = `${header}.${claims}`;
    const signature = sign('RSA-SHA256', Buffer.from(message), privateKey).toString('base64url');
    return [sub, `${message}.${signature}`];
  }));
  writeFileSync(values['token-file'], JSON.stringify(tokens) + '\n', { flag: 'wx', mode: 0o600 });
  process.stdout.write('SPEC-09 test JWKS ready at /jwks.json\n');
});
