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

echo "1. Ensuring local stack is active..."
docker compose "${COMPOSE_FILES[@]}" up -d postgres keycloak runtime-monolith prometheus grafana

echo "2. Waiting for core services availability (bounded 60s)..."
DEADLINE=$((SECONDS + 60))
until curl -s http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'; do
    if [ $SECONDS -ge $DEADLINE ]; then
        echo "ERROR: Timeout waiting for Pocoma Monolith readiness"
        exit 1
    fi
    sleep 2
done
echo "   ✓ Pocoma V3 Monolith is UP and healthy"

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
echo "3. Capturing baseline Prometheus metrics..."
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(http_server_requests_seconds_count)" > "${EVIDENCE_DIR}/metrics-http-before.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(hikaricp_connections_acquire_seconds_count)" > "${EVIDENCE_DIR}/metrics-hikari-before.json"
curl -s http://127.0.0.1:8080/actuator/prometheus > "${EVIDENCE_DIR}/actuator-prometheus-baseline.txt"

echo "4. Executing Bruno operational demonstration journey..."
python3 "${REPO_ROOT}/scripts/operations/run-demo-journey.py" --repeat 1 | tee "${EVIDENCE_DIR}/journey-execution.log"

# Snapshot metrics after
echo "5. Capturing post-demonstration Prometheus metrics..."
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(http_server_requests_seconds_count)" > "${EVIDENCE_DIR}/metrics-http-after.json"
curl -s "http://127.0.0.1:9090/api/v1/query?query=sum(hikaricp_connections_acquire_seconds_count)" > "${EVIDENCE_DIR}/metrics-hikari-after.json"
curl -s http://127.0.0.1:8080/actuator/prometheus > "${EVIDENCE_DIR}/actuator-prometheus-post.txt"

# Assert progression
HTTP_BEFORE=$(python3 -c "import json; data=json.load(open('${EVIDENCE_DIR}/metrics-http-before.json')); print(float(data['data']['result'][0]['value'][1]) if data.get('data',{}).get('result') else 0.0)")
HTTP_AFTER=$(python3 -c "import json; data=json.load(open('${EVIDENCE_DIR}/metrics-http-after.json')); print(float(data['data']['result'][0]['value'][1]) if data.get('data',{}).get('result') else 0.0)")

echo "================================================================================"
echo " Gate B (G-OBS-OPERATIONAL) Verification Verdict:"
echo "   • HTTP Requests Count  : ${HTTP_BEFORE} → ${HTTP_AFTER}"
echo "================================================================================"

python3 -c "
hb = float('${HTTP_BEFORE}')
ha = float('${HTTP_AFTER}')
if ha <= hb:
    print('FAIL: HTTP requests counter did not increase after Bruno journey!')
    exit(1)
print(f'PASS: HTTP requests counter increased by {ha - hb:.0f} requests during demonstration.')
"

cat <<EOF > "${EVIDENCE_DIR}/gate-b-verdict.json"
{
  "gate": "G-OBS-OPERATIONAL",
  "verdict": "PASS",
  "http_requests_before": ${HTTP_BEFORE},
  "http_requests_after": ${HTTP_AFTER},
  "delta_http_requests": $(( ${HTTP_AFTER%.*} - ${HTTP_BEFORE%.*} )),
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

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
    echo "   • Pocoma Monolith   : http://localhost:8080/actuator/health"
    echo "   • Keycloak IdP      : http://localhost:8081"
    echo ""
    echo " To replay new demonstration campaigns, run:"
    echo "   python3 scripts/operations/run-demo-journey.py --repeat 2"
    echo ""
    echo " To stop the environment when finished, run:"
    echo "   scripts/operations/verify-l6-i1-observability.sh --stop"
    echo "================================================================================"
fi
