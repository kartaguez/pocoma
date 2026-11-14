"""Reject false identity readiness and guard the L7-LB-only credential boundary."""
import base64
import json
from pathlib import Path
import sys
import tempfile
import time
from io import StringIO
import unittest
from unittest.mock import patch
from urllib.error import URLError, HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parent))
import keycloak as kc
import runtime

ISSUER = 'http://localhost:12345/realms/pocoma'


def token(subject):
    return 'header.' + base64.urlsafe_b64encode(json.dumps({'sub': subject}).encode()).decode() + '.signature'


class KeycloakContract(unittest.TestCase):
    def users(self, url, bearer):
        name = url.split('username=')[1].split('&')[0]
        return [dict(id=name + '-id', username=name, enabled=True, requiredActions=[])]

    def proof(self, fetch=None, pkce=None):
        records = []
        with patch.object(kc, 'discovery'), patch.object(kc, 'administrator', return_value=('private-admin-token', 'admin-id')), \
             patch.object(kc, 'fetch', side_effect=fetch or self.users), \
             patch.object(kc, 'prove_pkce', side_effect=pkce or (lambda issuer, name, password: token(name + '-id'))):
            result = kc.identities(ISSUER, records.append)
        return result, records

    def test_all_enabled_and_pkce_subjects_required_without_secret_evidence(self):
        (checks, tokens), records = self.proof()
        self.assertEqual(set(kc.LOCAL_USERS), set(tokens))
        self.assertEqual(9, len(checks))
        text = json.dumps(records)
        self.assertNotIn('private-admin-token', text)
        self.assertNotIn('signature', text)
        self.assertNotIn('password', text)

    def test_missing_disabled_duplicate_required_action_accounts_cannot_pass(self):
        for name in ('admin', *kc.LOCAL_USERS):
            for mutation in ('missing', 'disabled', 'duplicate', 'action'):
                def bad(url, bearer):
                    users = self.users(url, bearer)
                    if users[0]['username'] == name:
                        if mutation == 'missing': return []
                        if mutation == 'duplicate': return users * 2
                        if mutation == 'disabled': users[0]['enabled'] = False
                        if mutation == 'action': users[0]['requiredActions'] = ['UPDATE_PASSWORD']
                    return users
                with self.subTest(name=name, mutation=mutation), self.assertRaisesRegex(kc.KeycloakFailure, 'NOT_PROVISIONED'):
                    self.proof(fetch=bad)

    def test_wrong_credentials_or_wrong_subject_cannot_pass(self):
        for name in kc.LOCAL_USERS:
            for wrong_subject in (True, False):
                def bad(issuer, username, password):
                    if username == name:
                        if wrong_subject: return token('other-user')
                        raise RuntimeError('private-password-in-underlying-error')
                    return token(username + '-id')
                with self.subTest(name=name), self.assertRaisesRegex(kc.KeycloakFailure, 'KEYCLOAK_USER_' + name.upper() + '_AUTH_FAILED') as failure:
                    self.proof(pkce=bad)
                self.assertNotIn('private-password', json.dumps(failure.exception.diagnostic))

    def test_admin_browser_failure_is_safe_and_recorded(self):
        records = []
        with patch.object(kc, 'discovery'), patch.object(kc, 'provider') as provider:
            provider.return_value.authorize.side_effect = URLError('https://private/?code=sensitive')
            with self.assertRaisesRegex(kc.KeycloakFailure, 'KEYCLOAK_ADMIN_AUTH_FAILED'):
                kc.identities(ISSUER, records.append)
        self.assertIn('diagnostic', records[-1])
        self.assertNotIn('sensitive', json.dumps(records))
        self.assertEqual('security-admin-console', provider.return_value.CLIENT)
        self.assertEqual('http://localhost:12345/admin/master/console/', provider.return_value.REDIRECT)

    def test_console_lightweight_access_token_requires_valid_oidc_id_token(self):
        def jwt(claims):
            return 'header.' + base64.urlsafe_b64encode(json.dumps(claims).encode()).decode() + '.signature'
        issuer = ISSUER.replace('/pocoma', '/master')
        identity = dict(iss=issuer, sub='admin-id', aud='security-admin-console', exp=time.time()+60)
        lightweight = dict(iss=issuer, azp='security-admin-console', exp=time.time()+60)
        for corrupt in (None, 'iss', 'aud', 'sub', 'exp'):
            current = dict(identity)
            if corrupt: current[corrupt] = 0 if corrupt == 'exp' else ''
            response = StringIO(json.dumps(dict(access_token=jwt(lightweight), id_token=jwt(current))))
            with patch.object(kc, 'provider') as provider, patch.object(kc, 'urlopen', return_value=response) as exchange:
                provider.return_value.ISSUER = issuer
                provider.return_value.authorize.return_value = ({'code':['private-code']}, 'private-verifier')
                if corrupt:
                    with self.assertRaisesRegex(kc.KeycloakFailure, 'ADMIN_AUTH_FAILED'):
                        kc.administrator(ISSUER)
                else:
                    bearer, subject = kc.administrator(ISSUER)
                    self.assertEqual('admin-id', subject)
                    body = exchange.call_args.args[0].data.decode()
                    self.assertIn('grant_type=authorization_code', body)
                    self.assertIn('code_verifier=private-verifier', body)
                    self.assertNotIn('password', body)

    def test_discovery_unavailable_or_missing_realm_is_explicit(self):
        for exception, control in ((URLError('private'), 'DISCOVERY_UNAVAILABLE'),
                (HTTPError(ISSUER, 404, 'missing', {}, None), 'REALM_NOT_READY')):
            with patch.object(kc, 'fetch', side_effect=exception), self.assertRaisesRegex(kc.KeycloakFailure, control):
                kc.discovery(ISSUER)

    def test_new_credentials_and_render_isolate_technical_secrets_and_network(self):
        original = (runtime.ROOT / 'docker/keycloak/pocoma-realm.json').read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            credentials = runtime.private(path)
            self.assertEqual(dict.fromkeys(kc.LOCAL_USERS, 'pocoma'), credentials['users'])
            self.assertTrue(all(len(p) >= 32 and p != 'pocoma' for p in credentials['passwords'].values()))
            m = dict(owner='id', project='test-project', ports=dict(issuer=12345, write=12346, read=12347, metrics=12348,
                         **dict.fromkeys(runtime.RUNTIMES, 12349)),
                     images={role: dict(digest=role) for role in runtime.IMAGES})
            runtime.render(path, m, credentials)
            compose = runtime.read(path / 'compose.json')
            env = compose['services']['keycloak']['environment']
            self.assertEqual('admin', env['KC_BOOTSTRAP_ADMIN_USERNAME'])
            self.assertEqual('pocoma', env['KC_BOOTSTRAP_ADMIN_PASSWORD'])
            self.assertNotEqual('pocoma', env['KC_DB_PASSWORD'])
            self.assertNotIn('ports', compose['services']['postgres'])
            self.assertTrue(all(p.startswith('127.0.0.1:') for s in compose['services'].values() for p in s.get('ports', [])))
            realm = runtime.read(path / 'realm.json')
            self.assertEqual(set(kc.LOCAL_USERS), {u['username'] for u in realm['users']})
            for user in realm['users']:
                self.assertTrue(user['enabled'])
                self.assertEqual([], user['requiredActions'])
                self.assertEqual([dict(type='password', value='pocoma', temporary=False)], user['credentials'])
            client = realm['clients'][0]
            self.assertFalse(client['directAccessGrantsEnabled'])
            self.assertEqual('S256', client['attributes']['pkce.code.challenge.method'])
            self.assertEqual(['http://127.0.0.1:8765/callback'], client['redirectUris'])
        self.assertEqual(original, (runtime.ROOT / 'docker/keycloak/pocoma-realm.json').read_bytes())

    def test_existing_private_credentials_are_never_silently_rewritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            runtime.save(path / 'secrets.json', {'users': {'guest': 'old-private'}})
            before = (path / 'secrets.json').read_bytes()
            runtime.private(path)
            self.assertEqual(before, (path / 'secrets.json').read_bytes())

    def test_status_cannot_be_ready_when_guest_authentication_fails(self):
        error = kc.failure('KEYCLOAK_USER_GUEST_AUTH_FAILED', 'guest PKCE', 'Credentials rejected.')
        def failed(issuer, record):
            record(dict(checks={'KEYCLOAK_USER_GUEST_AUTH_FAILED': 'FAIL'}, diagnostic=error.diagnostic))
            raise error
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'ownership'), \
             patch.object(runtime, 'current', return_value={'JVM': 'PASS'}), \
             patch.object(runtime, 'identities', side_effect=failed), patch.object(runtime, 'observability', return_value='AVAILABLE'):
            path = Path(directory)
            runtime.save(path / 'canary.json', {'completedAt': __import__('time').time()})
            result = runtime.status(path, {'issuer': ISSUER})
            self.assertEqual('NOT_READY', result['state'])
            self.assertEqual('KEYCLOAK_USER_GUEST_AUTH_FAILED', result['diagnostic']['control'])


if __name__ == '__main__':
    unittest.main()
