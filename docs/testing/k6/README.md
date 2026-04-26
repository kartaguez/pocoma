# Pocoma k6 Load Tests

Suite de tests de charge pour le serveur HTTP Pocoma. Les scripts créent leurs propres données de test avec des labels préfixés `K6`, exécutent des commandes valides, des conflits concurrents et des requêtes incohérentes, puis scrutent `/actuator/prometheus`.

## Prérequis

- k6 installé localement.
- Monolith Pocoma lancé, de préférence avec PostgreSQL :

```bash
cd pocoma
docker compose -f docker-compose.postgres.yml up -d
./mvnw -pl runtime-monolith -am install -DskipTests
./mvnw -pl runtime-monolith spring-boot:run -Dspring-boot.run.profiles=postgres
```

- Actuator Prometheus disponible sur `http://localhost:8080/actuator/prometheus`.

## Smoke

Profil court pour vérifier que les scénarios, le scraping Prometheus et les conflits attendus fonctionnent.

```bash
cd pocoma
k6 run ../docs/testing/k6/smoke.js
```

Variables utiles :

```bash
BASE_URL=http://localhost:8080 \
SEED_POTS=3 \
SMOKE_DURATION=30s \
k6 run ../docs/testing/k6/smoke.js
```

## Stress

Profil plus long avec montée progressive. La répartition par défaut vise environ 70% de commandes valides, 20% de conflits concurrents et 10% de requêtes incohérentes.

```bash
cd pocoma
k6 run ../docs/testing/k6/stress.js
```

Variables utiles :

```bash
BASE_URL=http://localhost:8080 \
SEED_POTS=12 \
HOT_POTS=3 \
STRESS_COMMAND_VUS=24 \
STRESS_CONFLICT_VUS=7 \
STRESS_INCOHERENT_VUS=3 \
STRESS_QUERY_VUS=4 \
STRESS_RAMP_UP=1m \
STRESS_PLATEAU=4m \
STRESS_RAMP_DOWN=1m \
SCRAPE_INTERVAL_SECONDS=10 \
k6 run ../docs/testing/k6/stress.js
```

## Utilisateurs

Par défaut, les UUIDs sont alignés avec la collection Bruno :

- `USER_ID=22222222-2222-2222-2222-222222222222`
- `ALICE_USER_ID=22222222-2222-2222-2222-222222222222`
- `BOB_USER_ID=33333333-3333-3333-3333-333333333333`
- `OUTSIDER_USER_ID=44444444-4444-4444-4444-444444444444`

## Métriques k6

Les scripts exposent :

- `pocoma_command_http_duration` : durée HTTP des commandes, taguée par `operation`.
- `pocoma_command_failure_total` : réponses inattendues ou échecs de commandes.
- `pocoma_expected_failure_total` : erreurs attendues des scénarios incohérents/concurrents.
- `pocoma_unexpected_failure_rate` : taux d’échecs non prévus.
- `pocoma_prometheus_scrape_success` : succès du scraping actuator.

Le scraper transforme aussi les métriques applicatives Prometheus en métriques k6 :

- `pocoma_observed_command_persist_latency`
- `pocoma_observed_projection_event_start_latency`
- `pocoma_observed_projection_processing_duration`
- `pocoma_observed_projection_end_to_end_latency`
- `pocoma_observed_projection_gap_ratio`
- `pocoma_observed_projection_retry_total`

Les timers Prometheus sont échantillonnés par delta `sum/count` entre deux scrapes, en millisecondes. Les métriques `*_max` sont ajoutées avec le tag `aggregate=max`.

## Scénarios

- `valid_commands` : rafraîchit la version courante du pot puis exécute `PATCH pot details`, `PATCH expense details`, `PATCH expense shares` ou `POST expense`.
- `concurrent_conflicts` : envoie deux `PATCH pot details` avec la même version via `http.batch` et attend un mix `200/409`.
- `incoherent_requests` : génère version obsolète, pot inconnu, user interdit et payload invalide.
- `queries_and_balances` : intercale les lectures pots/dépenses/balances pendant la charge.
- `scrape_metrics` : interroge `/actuator/prometheus` périodiquement.

## Nettoyage

`teardown()` essaie de supprimer les dépenses puis les pots créés. Les erreurs de cleanup sont ignorées pour ne pas masquer le résultat du test de charge. Les scripts ne supposent pas une base vide.
