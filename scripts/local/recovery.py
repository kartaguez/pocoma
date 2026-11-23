"""Generation and full-partition observations for the L7-LB distribution only."""
import hashlib
import json
import re
import time

# The reviewed T1A catalogue: all four partitions of every activated participant.
PARTICIPANTS = {
    'command-worker': ('write', ['98f7268d-d340-581c-b83d-010926223e14', '2999e75c-272d-5afe-a966-c9364ef021fe']),
    'critical-read-worker': ('read', [
        '63664303-d33e-5d42-8cce-c4dae8023311',
        '1d8ff246-358f-5ddd-a7f5-416efcfd7445',
        '7b8d2838-bf7a-5117-868a-e5c14b4c0221',
    ]),
    'pot-read-worker': ('read', [
        '3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a',
        '190a93c6-b8af-5ac7-8001-72e2ec53c2df',
        'b4d04cce-b5da-5d1b-9698-5ff9af9a46fa',
        '924cd9e3-435d-5804-9d1e-c6aecadb9ab4',
    ]),
}
BUDGET = 270  # 240s unchanged lease plus 30s observation/startup margin; no fixed sleep.


def identity(generation):
    if not generation or any(not v for v in generation.values()):
        return None
    keys = {name: {k: row[k] for k in ('containerId', 'createdAt', 'startedAt')}
            for name, row in generation.items()}
    return hashlib.sha256(json.dumps(keys, sort_keys=True).encode()).hexdigest()


def transitions(before, after):
    return {name: ('CREATED' if not before.get(name) else
                   'RECREATED' if before[name]['containerId'] != row['containerId'] else
                   'RESTARTED' if before[name]['startedAt'] != row['startedAt'] else 'PRESERVED')
            for name, row in after.items() if row}


def metric(text, name, **labels):
    values = []
    for line in text.splitlines():
        match = re.fullmatch(re.escape(name) + r'(?:\{([^}]*)\})?\s+(\S+)(?:\s+\S+)?', line)
        if match and all(dict(re.findall(r'(\w+)="([^"]*)"', match[1] or '')).get(k) == v
                         for k, v in labels.items()):
            values.append(float(match[2]))
    return values[0] if len(values) == 1 else None


def _evaluate_worker_coverage(name, spec, workers, rows):
    authority, participants = spec
    expected = {(p, i) for p in participants for i in range(4)}
    observed = rows.get(authority, [])
    matched = [r for r in observed if r['consumptionId'] in participants]
    pairs = {(r['consumptionId'], r['partitionId']) for r in matched}
    live = {(r['consumptionId'], r['partitionId']) for r in matched if r['live'] and r['partitionCount'] == 4}
    duplicate = len(matched) != len(pairs)
    text = workers.get(name, '')
    local = metric(text, 'pocoma_v3_lease_local', authority=authority)
    running = metric(text, 'pocoma_v3_worker_state', state='running') == 1
    qualified = running and local == len(expected) and pairs == expected and live == expected and not duplicate
    return dict(
        expectedLeases=len(expected), localUsableLeases=local, running=running,
        missingPartitions=[dict(consumptionId=p, partitionId=i) for p, i in sorted(expected - live)],
        qualified=qualified,
    )


def coverage(workers, rows):
    """A local usable lease for every pair, corroborated by live durable ownership.

    A total is sufficient only because the reviewed catalogue has disjoint participants,
    one process per role, and exactly four configured partitions for each participant.
    Missing/duplicate/wrong catalogue rows fail closed.
    """
    return {
        name: _evaluate_worker_coverage(name, spec, workers, rows)
        for name, spec in PARTICIPANTS.items()
    }


def _sample_workers(observe):
    observation = dict(observedAt=time.time())
    try:
        workers = observe()
        all_qualified = set(workers) == set(PARTICIPANTS) and all(v['qualified'] for v in workers.values())
        observation.update(workers=workers, qualified=all_qualified)
    except Exception as error:
        # No exception messages/URLs: only our stable diagnostic and the exception class.
        observation.update(qualified=False, control='RECOVERY-OBSERVATION-FAILED', errorClass=type(error).__name__)
    return observation


def _generation_preserved(generation, expected_id):
    current = generation()
    running = all(row and row['running'] for row in current.values())
    return identity(current) == expected_id and running


def _is_coverage_ready(observation, deadline, record):
    return bool(observation['qualified'] and time.monotonic() <= deadline and record['generationId'])


def await_coverage(observe, generation, save, before, *, budget=BUDGET):
    began, deadline = time.time(), time.monotonic() + budget
    after = generation()
    record = dict(startedAt=began, budgetSeconds=budget, before=before, after=after,
                  transitions=transitions(before, after), generationId=identity(after), observations=[], state='WAITING')
    save(record)
    while True:
        observation = _sample_workers(observe)
        record['observations'].append(observation)
        if _is_coverage_ready(observation, deadline, record):
            if not _generation_preserved(generation, record['generationId']):
                record.update(state='FAILED', control='RECOVERY-GENERATION-CHANGED', completedAt=time.time())
                save(record)
                raise RuntimeError(record['control'])
            record.update(state='RECOVERED', completedAt=time.time(), seconds=time.time() - began)
            save(record)
            return record
        if time.monotonic() >= deadline:
            record.update(state='FAILED', control='WORKER-RECOVERY-TIMEOUT', completedAt=time.time())
            save(record)
            raise RuntimeError(record['control'])
        save(record)
        time.sleep(min(1, max(0, deadline - time.monotonic())))


def proof_state(proof, current, now, max_age):
    generation_id = identity(current)
    qualified = bool(proof and generation_id and proof.get('generationId') == generation_id
                     and all(r['running'] for r in current.values()))
    age = None if not proof else now-proof['completedAt']
    return dict(generationId=generation_id, generationQualified=qualified, ageSeconds=age,
                fresh=bool(age is not None and 0 <= age <= max_age),
                demonstrated=bool(qualified and age is not None and 0 <= age <= max_age))
