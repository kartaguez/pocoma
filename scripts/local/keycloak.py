"""L7-LB-only identity contract and secret-free, real browser-flow proofs.

No password grant, admin client registration, realm mutation or token persistence.
The stock security-admin-console client is used for the master browser flow.
"""
import base64
import json
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from auth import provider, prove_pkce

LOCAL_PASSWORD = 'pocoma'
LOCAL_USERS = ('third', 'guest', 'bruno')


class KeycloakFailure(RuntimeError):
    def __init__(self, control, check, cause, action):
        super().__init__(control)
        self.diagnostic = dict(component='keycloak', control=control, check=check,
                               probableCause=cause, recommendedAction=action)


def failure(control, check, cause):
    return KeycloakFailure(control, check, cause,
        'Inspect keycloak-auth.json and private containers.log; for an old installation, '
        'preserve its data and use a new instance or an explicitly confirmed reset. '
        'Realm import and bootstrap admin do not update an existing database.')


def fetch(url, token=None):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    with urlopen(Request(url, headers=headers), timeout=15) as response:
        return json.load(response)


def discovery(issuer):
    try:
        data = fetch(issuer + '/.well-known/openid-configuration')
    except HTTPError as error:
        code = 'KEYCLOAK_REALM_NOT_READY' if error.code == 404 else 'KEYCLOAK_OIDC_DISCOVERY_UNAVAILABLE'
        raise failure(code, 'OIDC discovery', 'Discovery returned HTTP ' + str(error.code)) from None
    except Exception:
        raise failure('KEYCLOAK_OIDC_DISCOVERY_UNAVAILABLE', 'OIDC discovery',
                      'Keycloak discovery endpoint is unreachable or returned invalid JSON.') from None
    if data.get('issuer') != issuer or 'S256' not in data.get('code_challenge_methods_supported', []):
        raise failure('KEYCLOAK_REALM_NOT_READY', 'OIDC discovery issuer/S256',
                      'Unexpected issuer or missing S256 support.')
    return data


def administrator(issuer):
    """Authenticate the stock console client with Code + S256 in master."""
    base = issuer.rsplit('/realms/', 1)[0]
    auth = provider(base + '/realms/master')
    auth.CLIENT = 'security-admin-console'
    auth.REDIRECT = base + '/admin/master/console/'
    try:
        values, verifier = auth.authorize(['openid'], username='admin', password=LOCAL_PASSWORD)
        if 'code' not in values or 'error' in values:
            raise ValueError('Missing authorization code')
        data = urlencode(dict(grant_type='authorization_code', client_id=auth.CLIENT,
                              redirect_uri=auth.REDIRECT, code=values['code'][0], code_verifier=verifier)).encode()
        with urlopen(Request(auth.ISSUER + '/protocol/openid-connect/token', data=data), timeout=15) as response:
            session = json.load(response)
        token = session['access_token']
        claims = json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '==='))
        identity = json.loads(base64.urlsafe_b64decode(session['id_token'].split('.')[1] + '==='))
        audience = identity.get('aud', [])
        audience = [audience] if isinstance(audience, str) else audience
        # 26.7 console access tokens are lightweight: sub is in the OIDC ID token.
        # Both tokens come directly from the owned loopback token endpoint.
        if (claims.get('iss') != auth.ISSUER or claims.get('azp') != auth.CLIENT
                or claims.get('exp', 0) <= time.time() or identity.get('iss') != auth.ISSUER
                or auth.CLIENT not in audience or not identity.get('sub') or identity.get('exp', 0) <= time.time()):
            raise ValueError('Invalid admin claims')
        return token, identity['sub']
    except Exception:
        raise failure('KEYCLOAK_ADMIN_AUTH_FAILED', 'master console Code + PKCE S256',
                      'Expected local admin credentials did not complete the console browser flow.') from None


def identities(issuer, record):
    """Prove all identities, persisting only allowlisted checks via record()."""
    checks = {}
    def passed(name):
        checks[name] = 'PASS'
        record(dict(checks=checks.copy(), observedAt=time.time()))
    try:
        discovery(issuer)
        passed('KEYCLOAK-OIDC-DISCOVERY')
        admin_token, admin_id = administrator(issuer)
        passed('KEYCLOAK-ADMIN-AUTH')
        base = issuer.rsplit('/realms/', 1)[0]
        def user(realm, name):
            control = 'KEYCLOAK_ADMIN_NOT_PROVISIONED' if realm == 'master' else 'KEYCLOAK_USER_' + name.upper() + '_NOT_PROVISIONED'
            try:
                users = fetch(base + '/admin/realms/' + realm + '/users?' +
                              urlencode(dict(username=name, exact='true')), admin_token)
            except Exception:
                raise failure(control, realm + '/' + name + ' account inspection',
                              'Admin REST inspection unavailable or administrator lacks permission.') from None
            if (len(users) != 1 or users[0].get('username') != name or users[0].get('enabled') is not True
                    or users[0].get('requiredActions')):
                raise failure(control, realm + '/' + name + ' enabled/no required actions',
                              'Account missing, disabled, ambiguous or requires an initial action.')
            passed('KEYCLOAK-' + name.upper() + '-ENABLED')
            return users[0]['id']
        if user('master', 'admin') != admin_id:
            raise failure('KEYCLOAK_ADMIN_AUTH_FAILED', 'master subject', 'Authenticated subject does not match admin.')
        tokens, subjects = {}, set()
        for name in LOCAL_USERS:
            subject = user('pocoma', name)
            try:
                token = prove_pkce(issuer, name, LOCAL_PASSWORD)
                claims = json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '==='))
                if claims.get('sub') != subject or subject in subjects:
                    raise ValueError('Unexpected identity')
            except Exception:
                raise failure('KEYCLOAK_USER_' + name.upper() + '_AUTH_FAILED',
                              'pocoma/' + name + ' Code + PKCE S256 (missing/plain rejected)',
                              'Expected credentials, subject or enforced PKCE contract failed.') from None
            subjects.add(subject)
            tokens[name] = token
            passed('KEYCLOAK-' + name.upper() + '-PKCE')
        return checks, tokens
    except KeycloakFailure as error:
        checks[error.diagnostic['control']] = 'FAIL'
        record(dict(checks=checks, diagnostic=error.diagnostic, observedAt=time.time()))
        raise


def access(issuer):
    return dict(keycloakAdmin=dict(url=issuer.rsplit('/realms/', 1)[0] + '/admin/',
                                  realm='master', username='admin'),
                demoUsers=dict(realm='pocoma', owner='third', guest='guest', bruno='bruno'),
                credentialsConvention='L7-LB local only: password pocoma for admin/third/guest/bruno; never production or L8.')
