# L7-LB / S6 — Rapport de durcissement opérationnel final et qualification ciblée

- **Mandat** : `GO_L7_LB_S6_OPERATIONAL_HARDENING`
- **Date** : 15 novembre 2026
- **Branche** : `feat/l7-lb-local-playground`
- **SHA initial (base S5)** : `f9c0ef64eb2249955e7acc7b74b9e157bf2620ba`
- **Instance de qualification dédiée** : `l7lb-s6` (`pocoma-lb-l7lb-s6-fedc9d37`, ports `59686..59695`), créée, qualifiée puis détruite (`DESTROYED`, `exit=0`)
- **Instance protégée `demo`** : intouchée (`406` fichiers, `9` conteneurs, volume `pocoma-lb-demo-33dd8c1d-data` vérifiés identiques entre [`demo-integrity-before.json`](../testing/evidence/L7-LB-S6/demo-integrity-before.json) et [`demo-integrity-after.json`](../testing/evidence/L7-LB-S6/demo-integrity-after.json))

## Verdict S6 et recommandation `L7-LB`

- **Verdict S6** : **`L7_LB_S6_QUALIFIED_WITH_RESERVATIONS`** (critères de sortie tous satisfaits ; réserve non bloquante sur la saturation en latence READ visible à `50 cmd/s` avec `capacity=4`, confirmant `25 cmd/s` comme enveloppe nominale soutenable).
- **Recommandation `L7-LB`** : **`L7_LB_CLOSURE_RECOMMENDED`** (sous les bornes opérationnelles documentées ; les invariants métier, le protocole de consommation, le fencing et les baselines SQL V1 restent inchangés).

---

## 1. Diagnostic des anomalies S5

1. **S6.1 — Récupération après coupure PostgreSQL** :
   - En coupure PG prolongée, les workers (`command-worker`, `results-worker`, `business-read-worker`) passent fail-closed en état `draining` (`/actuator/health` → `503 DOWN`, `pocoma_v3_worker_state{state="draining"} = 1.0`), tandis que Spring `/actuator/health/readiness` (seul endpoint sondé par [`runtime.py`](../../scripts/local/runtime.py)) restait `200 UP`.
   - `./pocoma-local start` invoquait `docker compose up -d`, qui ne redémarre pas un conteneur déjà `Up` ; de même, une admission `UNRESOLVED` sur `http-write` retenait un jeton d'admission (`pocoma_v3_admission_capacity_available`) sans que `/actuator/health` passe `DOWN`.
2. **S6.2 — Configuration effective HTTP WRITE** :
   - [`http-write.properties`](../../docker/v3-five-runtimes/http-write.properties) définissait `pocoma.v3.http.admission.maximum=4` et `minimum-idle=1`, alors que [`AdmissionHttpConfiguration.java`](../../app/compose-web-write-postgres/src/main/java/com/kartaguez/pocoma/compose/web/write/AdmissionHttpConfiguration.java#L119) lit uniquement `pocoma.v3.http.admission.capacity` (défaut `1`) pour dimensionner à la fois le sémaphore `AdmissionTransport` et le pool Hikari `v3-http-admission` (`maximumPoolSize` / `minimumIdle`).
3. **S6.3 — Clarification `READY` et qualification** :
   - Après expiration du TTL de preuve (`MAX_AGE = 300 s`), `./pocoma-local status` retournait `NOT_READY` (`exit=1`) sans champ explicite distinguant une instance **opérationnelle à preuve périmée** d'une **panne applicative** (ou d'un worker en `draining`).
4. **S6.4 — Cohérence CLI (`--dry-run`) et documentation** :
   - `--dry-run` n'était géré que sur `reset` et `destroy`, mais accepté silencieusement sur `start`, `status`, `stop`, `bruno`, `login`, `gui`, `upgrade-grafana`.
   - [`configuration.md`](../reference/configuration.md#L30) indiquait `min-poll`/`max-poll`/`error-poll` en « secondes » au lieu de millisecondes (`Duration.ofMillis(...)`).

---

## 2. Corrections effectuées

- **Supervision & récupération CLI (`S6.1`)** — [`scripts/local/runtime.py`](../../scripts/local/runtime.py) :
  - `current()` vérifie `/actuator/health` (en plus de `/actuator/health/readiness`) sur les 3 workers (`WORKERS`) et rejette `http-write` si `pocoma_v3_admission_capacity_available == 0.0`.
  - `start_distribution()` exécute `recover_fail_closed_runtimes()` **uniquement après validation de la disponibilité PostgreSQL/Keycloak** : tout worker encore `draining`/`DOWN` ou `http-write` ayant perdu des jetons (`available < configured_admission_capacity`) est journalisé (`runtime-recovery.json`) et redémarré **une seule fois** de manière bornée (aucune boucle de redémarrage ; zéro redémarrage pendant une panne PG active).
- **Configuration HTTP WRITE (`S6.2`)** — [`docker/v3-five-runtimes/http-write.properties`](../../docker/v3-five-runtimes/http-write.properties) & [`AdmissionHttpConfigurationTest.java`](../../app/compose-web-write-postgres/src/test/java/com/kartaguez/pocoma/compose/web/write/AdmissionHttpConfigurationTest.java) :
  - Remplacement des clés obsolètes `admission.maximum=4` / `minimum-idle=1` par `pocoma.v3.http.admission.capacity=4` (portant le sémaphore et le pool `v3-http-admission` à `4`, soit ~`70` connexions PG max cumulées sur les 5 runtimes vs `max_connections=100`).
  - Ajout de `AdmissionHttpConfigurationTest` vérifiant le défaut `1`, la lecture de `capacity=4`, le rejet des clés obsolètes et l'initialisation `availableCapacity() == 4`.
- **Statut opérationnel vs qualification (`S6.3`)** — [`scripts/local/runtime.py`](../../scripts/local/runtime.py) :
  - Ajout additif (sans rupture des champs historiques) de `operationalState` (`OPERATIONAL` / `DEGRADED_OBSERVABILITY` / `UNAVAILABLE`) + objet `operational`, et de `qualificationState` (`QUALIFIED` / `STALE` / `GENERATION_MISMATCH` / `UNQUALIFIED`) + objet `qualification`.
- **Contrat CLI `--dry-run` & documentation (`S6.4`)** :
  - `main()` rejette explicitement `--dry-run` (`exit=2`) hors `reset`/`destroy`.
  - Mise à jour de [`configuration.md`](../reference/configuration.md), [`commands.md`](../reference/commands.md), [`troubleshooting.md`](../operations/troubleshooting.md) et [`L7_LB_Distribution_Qualification_and_Guarantees.md`](L7_LB_Distribution_Qualification_and_Guarantees.md).

---

## 3. Tests exécutés (conformément à `Reactor_Verification_Policy.md`)

- **Slice Python CLI (`129` tests)** : `python3 -B -m unittest discover -s scripts/local -p 'test_*.py' -v` → **`129/129 OK`** en `1.354 s`, incluant [`test_s6_hardening.py`](../../scripts/local/test_s6_hardening.py) (`9` nouveaux tests couvrant `draining`, `http-write` épuisé, non-redémarrage à chaud sain, preuve fraîche, preuve périmée `STALE`, worker non opérationnel, génération modifiée / redémarrage partiel, retour `QUALIFIED` après `start`, et rejet `--dry-run` sur les 7 sous-commandes non supportées).
- **Slice Maven WRITE ciblée (`WEB`)** : `./mvnw -pl compose-web-write-postgres -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AdmissionHttpConfigurationTest,AdmissionTransportTest` → **`4/4 OK`** en `2.290 s` (`AdmissionTransportTest`: `2`, `AdmissionHttpConfigurationTest`: `2`).
  - *Note de politique* : les exécutions `-am test` complètes incluant `infra-postgres-write` / `infra-postgres-read` échouent sur les tests historiques Flyway V3 archivés en S3 (`docs/archive/v3-sql-migrations/`), qui restent exclus par la politique de baseline consolidée.

---

## 4. Résultats dynamiques `B6` (Coupure PostgreSQL et récupération CLI)

Preuve : [`s6-b6-recovery.json`](../testing/evidence/L7-LB-S6/s6-b6-recovery.json)

| Étape | Observation factuelle |
| :--- | :--- |
| **Avant panne** | `state=READY`, `operationalState=OPERATIONAL`, `qualificationState=QUALIFIED`, `admission_capacity_available=4.0`, `36/36` baux actifs, `9` commandes. |
| **Pendant pause PG** | Sonde `POST /api/v3/commands` → `HTTP 503` (`5.036 s`, `admission_capacity_available=3.0/4.0`, `0` ligne partielle en base). Les 3 workers passent à `/actuator/health=503`, `/actuator/health/readiness=200`, `draining=1.0`. |
| **CLI pendant panne** | `./pocoma-local status` → `exit=1`, `state=NOT_READY`, `operationalState=UNAVAILABLE`, `JVM-{command,results,business-read}-worker=FAIL`, `DB-CURRENT=FAIL`. `./pocoma-local start` échoue proprement (`exit=1`) avec **`0` redémarrage inutile** des runtimes. |
| **Récupération par `./pocoma-local start` après `unpause`** | **`exit=0` en `23.031 s`**, sans aucune commande Docker manuelle : `runtime-recovery.json` détecte `http-write` (`ADMISSION_CAPACITY_DEPLETED:3.0/4`) + les 3 workers (`WORKER_HEALTH_DOWN`), les redémarre une fois, réacquiert `36/36` baux (**`36/36` nouveaux `owner_token`**, `fencing=PASS`), restaure `capacity=4.0` et valide le canari (`10` commandes). |

---

## 5. Résultats `P2` / `P3` et comparaison avec S5

Preuve : [`s6-p2-p3-performance.json`](../testing/evidence/L7-LB-S6/s6-p2-p3-performance.json)

| Mesure | S5 (`capacity=1`) | S6 (`capacity=4`) | Analyse |
| :--- | :---: | :---: | :--- |
| **Capacité d'admission effective** | `1` | **`4`** | Vérifiée au démarrage (`hikaricp_connections_max=4.0`, `pocoma_v3_admission_capacity_available=4.0`) |
| **Débit admis à `25 cmd/s` (`P2`)** | `25,005 cmd/s` (`2250/2250`) | **`25,001 cmd/s` (`2250/2250`)** | Nominal soutenu sans erreur ni quarantaine |
| **Débit admis à `50 cmd/s` (`P3`)** | `41,37 cmd/s` (`4964/6000`) | **`50,005 cmd/s` (`6000/6000`)** | Suppression complète du plafond mono-permis |
| **Rejets HTTP `503` à `50 cmd/s` (`P3`)** | `1 036` | **`0`** | Zéro rejet fail-fast à l'admission |
| **HTTP WRITE `p95` (`P2` / `P3`)** | `19,25 ms` / `28,25 ms` | **`13,91 ms`** / **`43,78 ms`** | Amélioré en `P2` ; plus élevé en `P3` car toutes les requêtes entrent au lieu d'être rejetées |
| **E2E CMD `p95` (`P2` / `P3`)** | `50,0 ms` / `96,0 ms` | **`57,0 ms`** / **`196,0 ms`** | Saturation progressive côté worker/PG à `50 cmd/s` |
| **RESULT `p95` (`P2` / `P3`)** | `80,0 ms` / `156,0 ms` | **`95,55 ms`** / **`295,0 ms`** | Contention accrue à `50 cmd/s` |
| **READ visible `p95` (`P2` / `P3`)** | `196,55 ms` / `674,7 ms` | **`230,65 ms`** / **`25 176,75 ms`** | **File d'attente `business-read-worker`** à `50 cmd/s` continus (`drain=6,284 s`, zéro perte) |

**Décision sur `capacity=4`** : **`capacity=4` est conservé** car la sûreté (zéro perte, zéro duplication, fail-closed `UNRESOLVED` + récupération CLI) est préservée et `P2` (`25 cmd/s`) s'améliore (`http_p95` `19,25 → 13,91 ms`), tout en documentant explicitement que **`25 cmd/s` reste l'enveloppe nominale soutenable** (à `50 cmd/s`, le goulot se déplace vers PostgreSQL / `business-read-worker`).

---

## 6. Comportement `READY` final et `--dry-run`

- **Preuve expirée** ([`s6-status-stale.json`](../testing/evidence/L7-LB-S6/s6-status-stale.json), `proofAgeSeconds=537.58 s > 300 s`) : `./pocoma-local status --instance l7lb-s6` retourne `exit=1`, `state=NOT_READY`, `technical=AVAILABLE`, **`operationalState=OPERATIONAL`**, **`qualificationState=STALE`**, `proofFreshness=STALE_OR_MISSING`.
- **Canari final** ([`s6-final-canary-start.json`](../testing/evidence/L7-LB-S6/s6-final-canary-start.json)) : `./pocoma-local start --instance l7lb-s6` retourne `exit=0`, `state=READY`, `operationalState=OPERATIONAL`, `qualificationState=QUALIFIED`, `proofFreshness=FRESH`.
- **Vérification `--dry-run`** : `./pocoma-local start --dry-run` échoue immédiatement (`exit=2`) avec `pocoma-local: error: --dry-run is only valid with reset or destroy`.

---

## 7. Réserves restantes et SHA final

1. **Enveloppe WRITE à `25 cmd/s`** : à `50 cmd/s` soutenus avec `capacity=4`, toutes les commandes sont admises (`0` rejet `503`) sans perte, mais la latence READ visible `p95` atteint `25,18 s`.
2. **Topologie mono-worker et PG partagé** : hors-périmètre de basculement multi-nœuds.
3. **SHA de base qualifié** : `f9c0ef64eb2249955e7acc7b74b9e157bf2620ba` ; **SHA publié S6** : commit S6 immédiatement suivant sur `feat/l7-lb-local-playground` (cf. journal Git).
