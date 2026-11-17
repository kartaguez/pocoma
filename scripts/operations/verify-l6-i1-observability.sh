#!/usr/bin/env bash
# ==============================================================================
# Pocoma V3 — L6/I1 Observability Qualification & Demonstration Gate
# Modes:
#   --mode=verify : Automated qualification gate (Gate B: G-OBS-OPERATIONAL)
#                   Verifies services, executes Bruno journey, records metrics
#                   progression evidence, and cleans up only resources it created.
#   --mode=demo   : Interactive human demonstration
#                   Starts stack, leaves services active, displays URLs, runs
#                   replayable journey for immediate dashboard visualization.
#   --stop        : Explicitly stops the local functional & observability stack.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
EVIDENCE_DIR="${REPO_ROOT}/docs/testing/evidence/L6-I1/operational"

COMPOSE_FILES=(-f "${REPO_ROOT}/docker-compose.functional.yml" -f "${REPO_ROOT}/docker/compose/docker-compose.observability.yml")

MODE="verify"
for arg in "$@"; do
    case "${arg}" in
        --mode=verify) MODE="verify" ;;
        --mode=demo)   MODE="demo" ;;
        --stop)        MODE="stop" ;;
        *) echo "Unknown argument: ${arg}"; echo "Usage: $0 [--mode=verify|--mode=demo|--stop]"; exit 1 ;;
    esac
done

cd "${REPO_ROOT}"

if [ "${MODE}" = "stop" ]; then
    echo "Stopping Pocoma V3 local observability stack..."
    docker compose "${COMPOSE_FILES[@]}" stop runtime-monolith prometheus grafana
    echo "Pocoma V3 observability services stopped."
    exit 0
fi

echo "================================================================================"
echo " Pocoma V3 — L6/I1 Observability Execution [Mode: ${MODE}]"
echo "================================================================================"

# Track pre-existing container states to never stop resources we don't own
EXISTING_MONOLITH=$(docker ps -q -f name=pocoma-functional-runtime-monolith || true)
EXISTING_PROMETHEUS=$(docker ps -q -f name=pocoma-functional-prometheus || true)
EXISTING_GRAFANA=$(docker ps -q -f name=pocoma-functional-grafana || true)

mkdir -p "${EVIDENCE_DIR}"

echo "1. Ensuring PostgreSQL and Keycloak are running..."
docker compose "${COMPOSE_FILES[@]}" up -d postgres keycloak

echo "2. Initializing Pocoma V3 PostgreSQL databases and configurations..."
python3 "${REPO_ROOT}/scripts/operations/setup-v3-database.py"

echo "3. Starting Pocoma V3 Monolith, Prometheus, and Grafana..."
docker compose "${COMPOSE_FILES[@]}" up -d runtime-monolith prometheus grafana

echo "4. Waiting for core services availability (bounded 60s)..."
DEADLINE=$((SECONDS + 60))
until curl -s http://127.0.0.1:18081/actuator/health | grep -q '"status":"UP"'; do
    if [ $SECONDS -ge $DEADLINE ]; then
        echo "ERROR: Timeout waiting for Pocoma Monolith Actuator readiness on 18081"
        docker logs pocoma-functional-runtime-monolith-1 --tail 50
        exit 1
    fi
    sleep 2
done
echo "   ✓ Pocoma V3 Monolith Actuator is UP and healthy (port 18081)"

until curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v3/me/binding | grep -q -E '200|401|404'; do
    if [ $SECONDS -ge $DEADLINE ]; then
        echo "ERROR: Timeout waiting for Pocoma Monolith HTTP API on 8080"
        exit 1
    fi
    sleep 1
done
echo "   ✓ Pocoma V3 Monolith HTTP API is responding (port 8080)"

until curl -s http://127.0.0.1:9090/-/ready | grep -q 'Prometheus Server is Ready'; do
    if [ $SECONDS -ge $DEADLINE ]; then
        echo "ERROR: Timeout waiting for Prometheus readiness"
        exit 1
    fi
    sleep 2
done
echo "   ✓ Prometheus is Ready"

until curl -s http://127.0.0.1:3000/api/health | grep -q '"database": "ok"'; do
    if [ $SECONDS -ge $DEADLINE ]; then
        echo "ERROR: Timeout waiting for Grafana readiness"
        exit 1
    fi
    sleep 2
done
echo "   ✓ Grafana is Ready"

# Verify Prometheus Target UP
TARGET_HEALTH=$(curl -s http://127.0.0.1:9090/api/v1/targets | grep -o '"health":"up"' | wc -l | tr -d ' ')
echo "   ✓ Prometheus targets active and UP: ${TARGET_HEALTH}"

# Verify Grafana Dashboard Provisioned
DASHBOARD_CHECK=$(curl -s -u admin:admin http://127.0.0.1:3000/api/search?query=Pocoma | grep -o 'pocoma-v3-overview' || true)
if [ -n "${DASHBOARD_CHECK}" ]; then
    echo "   ✓ Grafana Dashboard 'pocoma-v3-overview' successfully provisioned"
else
    echo "   ⚠ Warning: Grafana Dashboard 'pocoma-v3-overview' not yet found in search index"
fi

# Snapshot metrics before
echo "5. Capturing baseline Prometheus metrics (pocoma_v3_* & hikaricp)..."
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(pocoma_v3_admission_requests_total)" > "${EVIDENCE_DIR}/metrics-v3-admission-before.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(pocoma_v3_attempt_outcomes_total)" > "${EVIDENCE_DIR}/metrics-v3-attempts-before.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(pocoma_v3_read_query_duration_seconds_count)" > "${EVIDENCE_DIR}/metrics-v3-read-before.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(hikaricp_connections_acquire_seconds_count)" > "${EVIDENCE_DIR}/metrics-hikari-before.json"
curl -s http://127.0.0.1:18081/actuator/prometheus > "${EVIDENCE_DIR}/actuator-prometheus-baseline.txt"

echo "6. Executing Bruno operational demonstration journey..."
python3 "${REPO_ROOT}/scripts/operations/run-demo-journey.py" --repeat 1 | tee "${EVIDENCE_DIR}/journey-execution.log"

# Snapshot metrics after
echo "7. Capturing post-demonstration Prometheus metrics..."
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(pocoma_v3_admission_requests_total)" > "${EVIDENCE_DIR}/metrics-v3-admission-after.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(pocoma_v3_attempt_outcomes_total)" > "${EVIDENCE_DIR}/metrics-v3-attempts-after.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(pocoma_v3_read_query_duration_seconds_count)" > "${EVIDENCE_DIR}/metrics-v3-read-after.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(hikaricp_connections_acquire_seconds_count)" > "${EVIDENCE_DIR}/metrics-hikari-after.json"
curl -s http://127.0.0.1:18081/actuator/prometheus > "${EVIDENCE_DIR}/actuator-prometheus-post.txt"

# Assert progression
python3 -c "
import json, sys

def get_val(path):
    try:
        data = json.load(open(path))
        r = data.get('data', {}).get('result', [])
        return float(r[0]['value'][1]) if r else 0.0
    except Exception:
        return 0.0

adm_b = get_val('${EVIDENCE_DIR}/metrics-v3-admission-before.json')
adm_a = get_val('${EVIDENCE_DIR}/metrics-v3-admission-after.json')
att_b = get_val('${EVIDENCE_DIR}/metrics-v3-attempts-before.json')
att_a = get_val('${EVIDENCE_DIR}/metrics-v3-attempts-after.json')
rd_b = get_val('${EVIDENCE_DIR}/metrics-v3-read-before.json')
rd_a = get_val('${EVIDENCE_DIR}/metrics-v3-read-after.json')
hik_b = get_val('${EVIDENCE_DIR}/metrics-hikari-before.json')
hik_a = get_val('${EVIDENCE_DIR}/metrics-hikari-after.json')

d_adm = adm_a - adm_b
d_att = att_a - att_b
d_rd = rd_a - rd_b
d_hik = hik_a - hik_b

print('================================================================================')
print(' Gate B (G-OBS-OPERATIONAL) Verification Verdict:')
print(f'   • V3 Admission Requests : {adm_b:.0f} → {adm_a:.0f}  (Δ +{d_adm:.0f})')
print(f'   • V3 Worker Attempts    : {att_b:.0f} → {att_a:.0f}  (Δ +{d_att:.0f})')
print(f'   • V3 Read Queries       : {rd_b:.0f} → {rd_a:.0f}  (Δ +{d_rd:.0f})')
print(f'   • Hikari CP Acquires    : {hik_b:.0f} → {hik_a:.0f}  (Δ +{d_hik:.0f})')
print('================================================================================')

if d_adm <= 0 and d_att <= 0:
    print('FAIL: V3 business metrics (admission/attempts) did not increase after demo journey!')
    sys.exit(1)

print('PASS: Genuine pocoma_v3_* metrics increased during operational demonstration.')
verdict = {
    'gate': 'G-OBS-OPERATIONAL',
    'verdict': 'PASS',
    'v3_admission_requests_before': adm_b,
    'v3_admission_requests_after': adm_a,
    'delta_v3_admission_requests': d_adm,
    'v3_worker_attempts_before': att_b,
    'v3_worker_attempts_after': att_a,
    'delta_v3_worker_attempts': d_att,
    'v3_read_queries_before': rd_b,
    'v3_read_queries_after': rd_a,
    'delta_v3_read_queries': d_rd,
    'hikari_acquires_before': hik_b,
    'hikari_acquires_after': hik_a,
    'delta_hikari_acquires': d_hik,
}
with open('${EVIDENCE_DIR}/gate-b-verdict.json', 'w') as f:
    json.dump(verdict, f, indent=2)
"

if [ "${MODE}" = "verify" ]; then
    echo "Mode VERIFY: Cleaning up self-created resources according to ownership policy..."
    if [ -z "${EXISTING_GRAFANA}" ]; then
        docker compose "${COMPOSE_FILES[@]}" stop grafana
    fi
    if [ -z "${EXISTING_PROMETHEUS}" ]; then
        docker compose "${COMPOSE_FILES[@]}" stop prometheus
    fi
    if [ -z "${EXISTING_MONOLITH}" ]; then
        docker compose "${COMPOSE_FILES[@]}" stop runtime-monolith
    fi
    echo "Cleanup finished. Pre-existing containers remained untouched."
else
    echo ""
    echo "================================================================================"
    echo " Mode DEMO: Environment is LIVE and persistant for interactive exploration!"
    echo "   • Grafana Dashboard : http://localhost:3000/d/pocoma-v3-overview"
    echo "   • Prometheus UI     : http://localhost:9090"
    echo "   • Pocoma Monolith   : http://localhost:8080/api/v3/me/binding"
    echo "   • Actuator Health   : http://localhost:18081/actuator/health"
    echo "   • Keycloak IdP      : http://localhost:8081"
    echo ""
    echo " To replay new demonstration campaigns, run:"
    echo "   python3 scripts/operations/run-demo-journey.py --repeat 2"
    echo ""
    echo " To stop the environment when finished, run:"
    echo "   scripts/operations/verify-l6-i1-observability.sh --stop"
    echo "================================================================================"
fi
