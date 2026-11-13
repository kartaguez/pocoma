"""Public HTTP playground seed and fresh WRITE -> READ canary. No SQL fixtures."""
from datetime import datetime, timedelta, timezone
from fractions import Fraction
import json
import time
import urllib.error
import urllib.request
from uuid import UUID, uuid4, uuid5


def require(value, control):
    if not value:
        raise RuntimeError(control)


class PublicAPI:
    def __init__(self, write, read, token):
        self.write, self.read, self.token = write, read, token
        self.trace = []

    def call(self, method, path, body=None, binding=None, key=None, token=True):
        base = self.write if method == 'POST' and path in ('/commands', '/identity/requests') else self.read
        headers = {'Accept': 'application/json', 'Connection': 'close'}
        if token:
            headers['Authorization'] = 'Bearer ' + self.token
        if binding:
            headers['X-Pocoma-Binding-Token'] = binding
        if key:
            headers['Idempotency-Key'] = key
        if body is not None:
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(base + '/api/v3' + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.code
            data = json.loads(response.read())
            require(response.headers.get('Cache-Control') == 'no-store', 'API-CACHE-CONTROL')
        # No response bodies, binding, invitation links, or credentials in evidence.
        self.trace.append(dict(method=method, path=path, status=status))
        return status, data

    def poll(self, path, binding=None, predicate=lambda data: True):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            status, data = self.call('GET', path, binding=binding)
            if status == 200 and predicate(data):
                return data
            require(status in (200, 404, 503), 'API-POLL-STATUS')
            time.sleep(.1)
        raise RuntimeError('API-POLL-TIMEOUT:' + path)

    def submit(self, path, body, key):
        status, data = self.call('POST', path, body, key=key)
        require(status == 202 and set(data) == {'requestId'}, 'API-ADMISSION:' + path + ':' + str(status) + ':' + str(data.get('code', '')))
        result = self.poll(path + '/' + data['requestId'] + '/result')
        require(result.get('outcome') == 'COMPLETED', 'API-BUSINESS-RESULT')
        return data['requestId'], result

    def binding(self):
        status, data = self.call('GET', '/me/binding')
        if status == 200 and data.get('active'):
            return data['bindingToken']
        _, result = self.submit('/identity/requests', {'operation': 'Registration'}, 'registration-' + str(uuid4()))
        return self.poll('/me/binding', predicate=lambda d: d.get('active') and
                         d['euiVersion'] >= result['targetVersion'])['bindingToken']

    def command(self, pot, binding, operation, version, payload, key):
        return self.submit('/commands', dict(operation=operation, potId=pot, bindingToken=binding,
                                            observedVersion=version, payload=payload), key)

    def pot(self, pot, binding, version):
        return self.poll('/pots/' + pot + '?minVersion=' + str(version), binding,
                         lambda d: d['potVersion'] >= version)


def exact_balances(data):
    result = {}
    for shareholder, value in data['balances'].items():
        require(set(value) == {'n', 'd'} and all(isinstance(v, str) for v in value.values()), 'BALANCE-ENCODING')
        require(int(value['d']) > 0, 'BALANCE-DENOMINATOR')
        result[shareholder] = Fraction(int(value['n']), int(value['d']))
    require(sum(result.values(), Fraction()) == 0, 'BALANCE-SUM')
    return result


def identifier(owner, name):
    return str(uuid5(UUID(owner), name))


def seed(owner_id, owner, guest, state, save):
    """Checkpointed seed. Stable intents replay after interruption; never overwrite user edits."""
    if state.get('complete'):
        return state
    b, g = owner.binding(), guest.binding()
    pot, deleted = identifier(owner_id, 'demo'), identifier(owner_id, 'deleted')
    first, second, expense = [identifier(owner_id, n) for n in ('first', 'second', 'expense')]
    if 'expiresAt' not in state:
        state['expiresAt'] = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat().replace('+00:00', 'Z')
        save(state)
    def cmd(operation, version, payload, name, target=pot):
        return owner.command(target, b, operation, version, payload, 'seed-v1-' + name)
    cmd('CreatePot', 0, {'label': 'Pocoma playground'}, 'create')
    weight = {'numerator': '1', 'denominator': '1'}
    cmd('AddShareholders', 1, {'targets': [dict(id=first, name='Owner', attachment='SELF', weight=weight),
        dict(id=second, name='Guest', attachment='FREE', weight=weight)]}, 'shares')
    cmd('CreateExpense', 2, dict(id=expense, label='Demo 10/3', date='2026-11-13',
        amount={'numerator': '10', 'denominator': '3'}, payerId=first,
        participants=[{'id': first, 'weight': None}, {'id': second, 'weight': None}]), 'expense')
    request, _ = cmd('CreateInvitation', 0, {'expiresAt': state['expiresAt']}, 'invite')
    invite = owner.poll('/invitations/by-creation-request/' + request)
    guest.command(pot, g, 'AttachShareholderViaInvitation', 0,
                  {'invitationToken': invite['link'].split('pocoma://invitation/')[1], 'shareholderId': second}, 'seed-v1-accept')
    view = owner.pot(pot, b, 4)
    require(view['potVersion'] == 4 and exact_balances(view) == {first: Fraction(5, 3), second: Fraction(-5, 3)}, 'DEMO-BALANCE')
    guest.pot(pot, g, 4)
    cmd('CreatePot', 0, {'label': 'Deleted demo history'}, 'deleted-create', deleted)
    cmd('DeletePot', 1, {}, 'deleted-delete', deleted)
    owner.poll('/pots/' + deleted + '/versions/1', b)
    state.update(complete=True, potId=pot, deletedPotId=deleted, expectedVersion=4,
                 balances={first: {'n': '5', 'd': '3'}, second: {'n': '-5', 'd': '3'}})
    save(state)
    return state


def canary(owner_id, api, run_id, state, save):
    binding = api.binding()
    pot = identifier(owner_id, 'canary')
    api.command(pot, binding, 'CreatePot', 0, {'label': 'Pipeline canary'}, 'canary-create-v1')
    view = api.pot(pot, binding, 1)
    if 'observed' not in state:
        state['observed'] = view['potVersion']
        save(state)
    label = 'canary-' + run_id
    _, result = api.command(pot, binding, 'UpdatePotDetails', state['observed'], {'label': label}, run_id)
    expected = state['observed'] + 1
    require(result['resultingPotVersion'] == expected, 'CANARY-NEW-VERSION')
    view = api.pot(pot, binding, expected)
    require(view['potVersion'] == expected and view['label'] == label and exact_balances(view) == {}, 'CANARY-CONTENT')
    balance = api.poll('/pots/' + pot + '/versions/' + str(expected) + '/balance', binding)
    require(balance['potVersion'] == expected and exact_balances(balance) == {}, 'CANARY-BALANCE')
    require(api.call('GET', '/me/binding', token=False)[0] == 401, 'CANARY-ANONYMOUS')
    return {'potId': pot, 'version': expected, 'runId': run_id, 'completedAt': time.time()}
