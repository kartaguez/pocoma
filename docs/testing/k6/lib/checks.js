import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const commandHttpDuration = new Trend('pocoma_command_http_duration', true);
export const commandFailureTotal = new Counter('pocoma_command_failure_total');
export const unexpectedFailureRate = new Rate('pocoma_unexpected_failure_rate');
export const expectedFailureTotal = new Counter('pocoma_expected_failure_total');

export function expectStatus(response, expectedStatuses, tags = {}) {
  const expected = Array.isArray(expectedStatuses) ? expectedStatuses : [expectedStatuses];
  const ok = expected.includes(response.status);
  check(response, {
    [`status is ${expected.join(' or ')}`]: () => ok,
  }, tags);
  if (tags.kind !== 'query' && tags.kind !== 'observability') {
    unexpectedFailureRate.add(!ok, tags);
  }
  return ok;
}

export function recordCommandResponse(response, operation, expectedStatuses = [200, 201]) {
  const tags = {
    kind: 'command',
    operation,
  };
  commandHttpDuration.add(response.timings.duration, tags);
  const ok = expectStatus(response, expectedStatuses, tags);
  if (ok && response.status >= 400) {
    commandFailureTotal.add(1, {
      ...tags,
      status: String(response.status),
      failure_type: failureType(response.status),
    });
    expectedFailureTotal.add(1, {
      ...tags,
      status: String(response.status),
      failure_type: failureType(response.status),
    });
  }
  else if (!ok) {
    commandFailureTotal.add(1, {
      ...tags,
      status: String(response.status),
      failure_type: 'unexpected',
    });
  }
  return ok;
}

export function recordExpectedFailure(response, operation, failureType, expectedStatuses) {
  const tags = {
    kind: 'command',
    operation,
    failure_type: failureType,
    status: String(response.status),
  };
  commandHttpDuration.add(response.timings.duration, tags);
  const ok = expectStatus(response, expectedStatuses, tags);
  if (ok) {
    expectedFailureTotal.add(1, tags);
  }
  else {
    commandFailureTotal.add(1, tags);
  }
  return ok;
}

export function recordUnexpectedCondition(operation, failureType, tags = {}) {
  unexpectedFailureRate.add(true, {
    kind: 'command',
    operation,
    failure_type: failureType,
    ...tags,
  });
  commandFailureTotal.add(1, {
    kind: 'command',
    operation,
    failure_type: failureType,
    status: 'n/a',
    ...tags,
  });
}

export function bodyOrNull(response) {
  if (!response || !response.body) {
    return null;
  }
  try {
    return response.json();
  }
  catch (error) {
    return null;
  }
}

export function requireBody(response, context) {
  const body = bodyOrNull(response);
  if (!body) {
    throw new Error(`Missing JSON body for ${context}; status=${response && response.status}`);
  }
  return body;
}

function failureType(status) {
  if (status === 400) {
    return 'bad_request';
  }
  if (status === 403) {
    return 'forbidden';
  }
  if (status === 404) {
    return 'not_found';
  }
  if (status === 409) {
    return 'version_conflict';
  }
  if (status >= 500) {
    return 'server_error';
  }
  return 'http_error';
}
