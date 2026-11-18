# Rapport L7.4-A — Banc de qualification locale, fiabilisation PREPARE/RESTORE et diagnostic des projections

- **Date** : 18 novembre 2026
- **Branche** : `feat/l7-4-a-baseline-qualification` (issue de `main` @ `1c5140fe`, post-PR #9 `v3-code-health-baseline-2026-11-18` ; note : le SHA attendu `8d63ccfb` correspond à la PR #8 antérieure à la PR #9)
- **Références normatives** : [`L7_SPEC09_Local_Qualification_Protocol.md`](L7_SPEC09_Local_Qualification_Protocol.md), [`L7_SPEC09_Compliance_Audit.md`](L7_SPEC09_Compliance_Audit.md), [`SPEC-09_Performance.md`](SPEC-09_Performance.md), [`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Impact productif sous `app/`** : **NUL** (aucun fichier de production modifié)
- **Verdict L7.4-A** : **`L7_4_A_BASELINE_QUALIFIED`** (banc reproductible avec `PREPARE`/`RESTORE` validé, séparation des 4 étages vérifiée, diagnostic factuel établi ; `SPEC-09` reste `NOT_RUN` en Configuration A/B)

---

## 1. Inventaire des outils réutilisés (Phase A1)

| Composant | Rôle existant | Limites identifiées | Réutilisation & adaptation minimale L7.4-A |
|---|---|---|---|
| [`scripts/performance/v3-seed.py`](../../scripts/performance/v3-seed.py) | Générateur déterministe (`small`, `medium`, `large`) et matérialisation HTTP | Matérialisation séquentielle par pot ; `await_complete_read` figé à `15 s` | Réutilisé tel quel via import ; parallélisation par pot + attente de drainage bornée dans le runner |
| [`scripts/performance/smoke_test_5_runtimes.py`](../../scripts/performance/smoke_test_5_runtimes.py) | Client HTTP V3 + oracle exact `fractions.Fraction` (`expected_balances`, `validate_complete`) | Boucle série mono-thread | Fonctions `request`, `initial_state`, `applied_version`, `expected_balances`, `validate_complete` réutilisées |
| [`scripts/performance/spec09_bench.py`](../../scripts/performance/spec09_bench.py) | Provenance SHA-256, `sql()` `READ ONLY`, `database_snapshot`, `fetch_metrics`, `validate_progress`, `reconcile` | Aucune pour le périmètre local | Réutilisé intégralement sans modification |
| [`scripts/performance/run-l7-spec09-e1.py`](../../scripts/performance/run-l7-spec09-e1.py) | Topologie jetable 5 runtimes (`pocoma.spec09.disposable=true`) | Couplage `/result` -> `/pots` -> `/balance` séquentiel ; pas de snapshot `PREPARE`/`RESTORE` | Patron repris dans [`run-l7-4a-baseline.py`](../../scripts/performance/run-l7-4a-baseline.py) avec sondage READ parallèle et `--mode prepare/restore-run` |
| [`scripts/performance/spec09-test-issuer.mjs`](../../scripts/performance/spec09-test-issuer.mjs) | Émetteur RSA/JWKS local isolé (`:28090`) | Aucune | Réutilisé tel quel (sujet déterministe `l74a-snapshot-user` pour `PREPARE`/`RESTORE`) |
| [`scripts/operations/setup-v3-database.py`](../../scripts/operations/setup-v3-database.py) | Bootstrap schémas `v3_write`/`v3_read`, rôles et `log_line_prefix` | Aucune | Réutilisé pour `full` et `prepare` |
| [`docker/v3-five-runtimes/*.properties`](../../docker/v3-five-runtimes/command-worker.properties) | Configuration explicite des 5 runtimes (`capacity=4`, `min-poll=2`, `max-poll=20`, `4` partitions) | Aucune | Lu directement et tracé dans chaque `manifest.json` |
| [`StageHandoffTracker.java`](../../app/infra-observability-micrometer/src/main/java/com/kartaguez/pocoma/infra/observability/micrometer/StageHandoffTracker.java) | Journalisation JSONL `/tmp/pocoma-stage-handoffs.jsonl` + timers Micrometer | Scope par JVM (pas de corrélation inter-JVM `T0->T0a` ni `T2->T3`) | Copié (`docker cp`) post-run et corrélé par `commandId` / `eventId` |

**Livrables du banc créés** :
- [`scripts/performance/l7-4a-profiles.json`](../../scripts/performance/l7-4a-profiles.json)
- [`scripts/performance/run-l7-4a-baseline.py`](../../scripts/performance/run-l7-4a-baseline.py)
- [`scripts/performance/test_l7_4a_baseline.py`](../../scripts/performance/test_l7_4a_baseline.py) (`4/4` tests unitaires Python `OK`)

---

## 2. Audit de l'instrumentation des latences (Phase A3 & §5)

L'inspection directe de [`ConsumptionWorker.java`](../../app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorker.java#L143-L153), [`StageHandoffTracker.java`](../../app/infra-observability-micrometer/src/main/java/com/kartaguez/pocoma/infra/observability/micrometer/StageHandoffTracker.java#L315-L629) et [`V1__initial_schema.sql`](../../app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql#L397-L453) établit la traçabilité exacte suivante :

| Métrique | Événement départ → arrivée | Source & horloge | Ce qu'elle inclut / exclut | Statut & biais |
|---|---|---|---|---|
| **1. Admission** (`stage1_admissionMs`) | Envoi `POST /api/v3/commands` → réponse `HTTP 202` | Client Python `time.monotonic_ns()` | Inclut sérialisation HTTP, auth JWT, validation & `INSERT v3_command` | **Mesurable** (monotone, précision `< 1 ms`) |
| **2a. Attente pré-discovery WRITE** | `HTTP 202` (`http-write`) → `T0a` (`command-worker`) | Aucune colonne `created_at` sur `v3_command` ; 2 JVM distinctes | — | **`NOT_MEASURABLE`** sans horloge partagée ou `created_at` sur `v3_command` (borné par `min-poll=2 ms..max-poll=20 ms`) |
| **2b. Discovery → Slot → Attempt WRITE** | `T0a→T0b` (`discoveryToSlotMs`), `T0b→T0c` (`slotAcquisitionWaitMs`), `T0c→T1` (`acquisitionToAttemptMs`) | `command-worker` `StageHandoffTracker` (`System.nanoTime()`) | Attente interne de création de slot, d'acquisition et de file d'exécution | **Mesurable** (monotone intra-JVM, corrélé par `commandId`) |
| **2c. Service WRITE** (`commandProcessingMs`) | `T1` (`onAttemptStarting`) → `T2` (`onAttemptFinished`) | `command-worker` `ConsumptionWorker.Envelope.run()` (`System.nanoTime()`) | Inclut `binding.engine().execute(handle)` : lecture état WRITE, exécution commande, écriture `v3_command_result` + `v3_pot_event` + `v3_command_completion_event` et commit TX | **Mesurable** (monotone intra-JVM, corrélé par `commandId`) |
| **3a. Attente pré-discovery READ** | `T2` (`command-worker`) → `T3` (`business-read-worker` / `results-worker`) | 2 JVM distinctes ; pas de timestamp READ en base `v3_read` | — | **`NOT_MEASURABLE`** sans horloge monotone partagée |
| **3b. Service Projections** (`t3ToT4Ms` pour `PotVersion`, `PotBalance`, `PotVisibility`, `CommandResult`) | `T3` (`onAttemptStarting`) → `T4` (`onAttemptFinished`) | Worker READ `ConsumptionWorker.Envelope.run()` (`System.nanoTime()`) | Inclut **tout `binding.engine().execute(handle)`** : lectures `v3_write` (`write-source-read`), lectures/vérifications `v3_read`, calcul métier, écritures `v3_read`, complétion du slot et commit TX. Exclut l'attente Discovery/slot/file | **Mesurable** (monotone intra-JVM, corrélé via `pot_event_id` / `completion_event_id`) |
| **4. Disponibilité READ & E2E** (`resultE2eMs`, `c03E2eMs`, `balanceE2eMs`) | Envoi `POST /api/v3/commands` → 1re réponse `HTTP 200` (`/result`, `/pots?minVersion=V`, `/versions/V/balance`) | Client Python `time.monotonic_ns()` (3 threads parallèles indépendants post-`202`) | Inclut bout en bout complet (`Admission + WRITE + Polling READ + Projection + HTTP READ`) | **Mesurable** ; biais de quantification du sondage `≤ 25 ms` |

---

## 3. État du banc — Mécanisme `PREPARE` / `RESTORE` (Section 8.A)

- **Fonctionnalités opérationnelles** :
  - `PREPARE` crée les pots via l'API HTTP réelle, attend le drainage complet (`0` commande `PENDING`, `0` `consumption_slot` sur WRITE et READ), arrête les 5 runtimes, vérifie l'absence de leases actifs incompatibles et `read_maintenance_state = READY`, puis produit `snapshot.sql.gz` (`pg_dumpall`) + `snapshot_meta.json` avec empreinte SHA-256.
  - `RESTORE` vérifie l'intégrité SHA-256 du snapshot, restaure dans un conteneur PostgreSQL **strictement jetable** (`label=pocoma.spec09.disposable=true`), réapplique `ALTER SYSTEM SET log_line_prefix` (non inclus dans `pg_dumpall`), démarre les 5 runtimes, puis contrôle `0` travail résiduel, compteurs, checkpoints, état `READY` et lecture HTTP + oracle `Fraction` de tous les pots avant toute injection.
- **Temps de préparation vs restauration mesurés** :
  - **Moyen (`2` pots `20/200`)** : `PREPARE` = `23.07 s` seeding ; `RESTORE` = **`0.50 s`** (`restore-smoke-medium`, `10/10` `PASS`).
  - **Grand (`2` pots `100/2000`)** : `PREPARE` = **`1 802.90 s`** (`30 min 3 s` seeding + drainage `PotVersion`), snapshot = `13.74 s` (`111.5 MB` gzip) ; `RESTORE` = **`13.71 s`** (`restore-smoke-large`) et **`13.06 s`** (`restore-large-1`) — gain de **> 130×** sur la préparation.
- **Limites restantes** :
  - En mono-hôte Docker (`10` vCPU, `7.75 GiB` RAM), `tmpfs` PostgreSQL est comptabilisé dans la `memLimit` du conteneur (`1024m` a provoqué un `ENVIRONMENT_LIMIT` à `96.04%` sur `restore-smoke-large` avant relèvement à `2048m`).
  - Les campagnes `large` ont alloué `2.0` CPU à `postgres` et `business-read-worker` (`1024m`) contre `1.5` CPU (`1024m`) et `0.5` CPU (`512m`) pour `medium`.

---

## 4. Registre des campagnes et comparaison des performances (Section 8.B)

### 4.1 Registre exhaustif des exécutions (toutes preuves conservées)

| Répertoire evidence | Campagne / Mode | Statut / Verdict | Détail factuel |
|---|---|---|---|
| [`smoke-medium`](../testing/evidence/L7-4A/smoke-medium/manifest.json) | `smoke-medium` (`full`) | **`PASS`** (validation) | `10/10` observés, `0` dropped, `0` erreur |
| [`med-1`](../testing/evidence/L7-4A/med-1/manifest.json) | `med-1` (`full`, `1 cmd/s`) | **`PASS`** | `60/60` observés (`1.015 cmd/s`), `0` dropped, `0` erreur |
| [`med-5`](../testing/evidence/L7-4A/med-5/manifest.json) | `med-5` (`full`, `5 cmd/s`) | **`PASS`** | `150/150` observés (`5.010 cmd/s`), `0` dropped, `0` erreur |
| [`smoke-large`](../testing/evidence/L7-4A/smoke-large/manifest.json) | `smoke-large` (`full`, `1` pot) | **`MEASUREMENT_INVALID`** (exploratoire) | `5/10` observés, `5` `DROPPED_OFFER` (`offer_lag_exceeded`, `producers=1` vs E2E `1.8 s`) |
| `large-1-interrupted-attempt2` | `large-1` (`full`, `2` pots) | **Interrompue** | Arrêtée proprement pendant le seeding suite à la décision L7.4-A (`PREPARE`/`RESTORE`) |
| [`prepare-medium`](../testing/evidence/L7-4A/prepare-medium/manifest.json) | `PREPARE` Moyen (`2` pots) | **`PASS`** | Snapshot validé (`23.07 s` seed) |
| [`restore-smoke-medium`](../testing/evidence/L7-4A/restore-smoke-medium/manifest.json) | `smoke-medium` (`restore-run`) | **`PASS`** | `RESTORE 0.50 s`, `10/10` observés, `0` dropped |
| [`prepare-large`](../testing/evidence/L7-4A/prepare-large/manifest.json) | `PREPARE` Grand (`2` pots) | **`PASS`** | Snapshot validé (`1 802.9 s` seed, `13.74 s` dump, `111.5 MB`) |
| [`restore-smoke-large`](../testing/evidence/L7-4A/restore-smoke-large/manifest.json) | `smoke-large` (`restore-run`) | **`MEASUREMENT_INVALID`** (exploratoire) | `RESTORE 13.71 s`, `8/10` observés (`0.783 cmd/s`), `2` `DROPPED_OFFER` (`pot_busy`) |
| [`restore-large-1`](../testing/evidence/L7-4A/restore-large-1/manifest.json) | `large-1` (`restore-run`, `1 cmd/s`) | **`MEASUREMENT_INVALID`** (marginal fiable sur `n=49`) | `RESTORE 13.06 s`, `49/60` observés (`0.829 cmd/s`), `11` `DROPPED_OFFER` (`pot_busy`), `0` censure, `0` violation |

### 4.2 Tableau comparatif des latences marginales (ms)

| Métrique | `med-1` (`20/200`, `1/s`, `n=60`) | `med-5` (`20/200`, `5/s`, `n=150`) | `restore-large-1` (`100/2000`, `0.83/s` eff., `n=49`) | Ratio `Large / Med-1` (p50) |
|---|---:|---:|---:|---:|
| **Admission** (`stage1_admissionMs`) | p50 `4.11` / p95 `5.92` / p99 `21.98` | p50 `3.63` / p95 `6.41` / p99 `16.34` | p50 `8.83` / p95 `18.17` / p99 `20.20` | `2.1×` |
| **WRITE service** (`commandProcessingMs`) | p50 `7.89` / p95 `12.78` / p99 `18.45` | p50 `8.14` / p95 `12.97` / p99 `22.42` | p50 `29.48` / p95 `95.73` / p99 `106.77` | `3.7×` |
| **Projection `PotVersion`** (`t3ToT4Ms`) | p50 **`39.73`** / p95 **`73.65`** / p99 `131.81` | p50 **`49.45`** / p95 **`91.98`** / p99 `147.18` | p50 **`1 463.92`** / p95 **`2 094.30`** / p99 `2 187.26` | **`36.8×`** |
| **Projection `PotBalance`** (`t3ToT4Ms`) | p50 **`8.10`** / p95 `17.29` / p99 `62.03` | p50 **`8.40`** / p95 `36.75` / p99 `48.21` | p50 **`488.82`** / p95 **`671.50`** / p99 `728.60` | **`60.3×`** |
| **Projection `PotVisibility`** (`t3ToT4Ms`) | p50 `2.50` / p95 `4.90` / p99 `13.58` | p50 `2.39` / p95 `22.31` / p99 `38.12` | p50 `3.82` / p95 `15.52` / p99 `71.24` | `1.5×` |
| **Projection `CommandResult`** (`t3ToT4Ms`) | p50 `1.53` / p95 `1.88` / p99 `2.61` | p50 `1.34` / p95 `1.92` / p99 `3.51` | p50 `2.15` / p95 `4.88` / p99 `19.38` | `1.4×` |
| **E2E `/result`** (`resultE2eMs`) | p50 `69.81` / p95 `121.25` / p99 `181.06` | p50 `69.62` / p95 `104.33` / p99 `178.60` | p50 `101.60` / p95 `193.03` / p99 `267.54` | `1.5×` |
| **E2E `C-03` READ** (`c03E2eMs`) | p50 **`125.11`** / p95 **`204.01`** / p99 `252.89` | p50 **`132.50`** / p95 **`210.19`** / p99 `305.04` | p50 **`1 651.56`** / p95 **`2 262.15`** / p99 `2 423.42` | **`13.2×`** |
| **E2E `Balance` READ** (`balanceE2eMs`) | p50 **`124.87`** / p95 **`202.15`** / p99 `251.31` | p50 **`130.01`** / p95 **`212.24`** / p99 `300.34` | p50 **`1 613.75`** / p95 **`2 230.99`** / p99 `2 410.20` | **`12.9×`** |
| **Ressources (`postgres` / `business-read-worker`)** | PG avg `69.3%` / BRW avg `35.7%` (lim `1.5`/`0.5` CPU) | PG avg `104.5%` / BRW avg `48.6%` (lim `1.5`/`0.5` CPU) | PG avg `132.5%` (max `195%`) / BRW avg `97.2%` (max `205.5%`, lim `2.0`/`2.0` CPU) | — |

**Décision sur `large-5`** : conformément à la consigne (§7), **`large-5` n'a pas été exécutée** : `large-1` perdait déjà `11/60` offres par contention de verrou client (`2` pots pour `1.65 s` p50 E2E) et `business-read-worker` consommait déjà `~1.95 s` de travail cumulé (`PotVersion + PotBalance`) par commande sur `2.0` CPU, rendant `5 cmd/s` physiquement insoutenable sur cette topologie.

**Comparaison indicative aux seuils SPEC-09 (non contractuelle, mono-hôte)** :
- `Moyen` (`1` et `5 cmd/s`) : `c03E2eMs` p95 `204–210 ms` (`≪ 500 ms` / `1 000 ms`), `PotBalance` p95 `17.3–36.8 ms` (`≤ 50 ms` candidat).
- `Grand` (`0.83 cmd/s` effectif) : `c03E2eMs` p95 `2 262 ms` (vs cible Config A `≤ 2 000 ms` à `1 cmd/s`).

---

## 5. Diagnostic technique du coût des projections (Section 8.C)

### 5.1 Faits mesurés et vérifiés dans le code

1. **`MaterializePotVersion` est le contributeur dominant** (`1 463.9 ms` p50 sur `Large`, soit **`88.6%`** de l'E2E `C-03` et **`3×` le coût de `MaterializePotBalance`**).
   L'inspection de [`MaterializePotVersion.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotVersion.java#L18-L32), [`JdbcSubobjectVersionStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcSubobjectVersionStore.java#L29-L76) et [`JdbcPotVersionStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcPotVersionStore.java#L86-L122) montre que chaque événement `CreateExpense` sur un Grand Pot (`100` participants, `2 000` dépenses = `6 302` sous-objets) exécute :
   - `source.composition(event)` : lecture de tous les en-têtes/identités de shareholders et dépenses dans `v3_write` (`PotHistoryRows`).
   - `subobjects.readAll(value.keys())` : **lecture SQL par paquets de 256 (`25` `SELECT`) et décodage/ré-encodage JSON complet des `6 302` lignes `read_subobject_version`** (dont les `2 000` `ExpenseFinancials` à `100` participants = `200 000` participations parsées) uniquement pour identifier les `3` clés manquantes.
   - `subobjects.insertOrVerifyAll(loaded)` : insertion + relecture de `3` lignes.
   - `versions.insertAndAdvance(value)` : sérialisation JSON de `6 302` clés dans `read_pot_version` + relecture/comparaison (`ReadJdbc.same`).
   - `requireCompletionReady()` → `subobjects.requireStored(all)` : **seconde relecture SQL complète (`25` `SELECT`) et second parsing JSON des `6 302` sous-objets (`200 000` participations)** avant commit, suivie de `versions.requireStored(value)`.
   - Total observé par événement `PotVersion` sur `100/2000` : **~50 requêtes `SELECT` de `read_subobject_version` et ~400 000 entrées de participation parsées en JSON**, expliquant directement les `1 464 ms` p50 et les `1 802.9 s` de drainage lors de `PREPARE`.
2. **`MaterializePotBalance`** (`488.8 ms` p50 sur `Large`, `60×` `Medium`) :
   D'après [`MaterializePotBalance.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalance.java#L21-L26) et [`PostgresPotHistoryReader.java`](../../app/infra-postgres-core/src/main/java/com/kartaguez/pocoma/infra/postgres/core/history/PostgresPotHistoryReader.java#L101-L150), chaque événement recharge les `2 000` dépenses et `200 000` participations depuis `v3_write`, recalcule les soldes en `Fraction` exactes, puis écrit et relit 1 ligne `read_pot_balance` (100 entrées).
3. **`PotVisibility` (`3.82 ms`) et `CommandResult` (`2.15 ms`)** sont quasi invariants à la taille du pot.
4. **Dépendances structurelles** : le coût de `PotVersion` et `PotBalance` dépend linéairement du nombre d'objets actifs (`S + E`) et des participations (`E × P`), **pas** de la profondeur de versions historiques archivées (confirmé par l'absence de lecture des versions `V < courant` dans `read_subobject_version` hors clés actives).

### 5.2 Hypothèse à confirmer si nécessaire
- La part exacte entre temps SQL PostgreSQL (`read_subobject_version` + `v3_expense_participation`) et temps CPU Java (désérialisation/validation JSON `SubobjectJson.decode` vs arithmétique `BigInteger` `Fraction`) n'est pas isolée sans profilage CPU/SQL ciblé (`pg_stat_statements` ou timers par phase dans `MaterializePotVersion`).

---

## 6. Options d'amélioration comparées (Section 8.D — sans implémentation)

| Option | Gain potentiel estimé | Risques sur les garanties fonctionnelles | Preuves nécessaires avant décision |
|---|---|---|---|
| **1. Optimisation SQL / réduction des relectures dans `MaterializePotVersion`** (ne lire/vérifier que les sous-objets manquants ou modifiés au lieu de relire `2×` les `6 302` lignes inchangées) | **Très élevé** sur `PotVersion` (suppression de ~`400 000` parsings JSON et `~50` `SELECT` par événement sur Grand Pot) | Vérifier que l'oracle d'intégrité `requireCompletionReady()` reste prouvé pour les sous-objets déjà présents | Profilage SQL (`pg_stat_statements`) + CPU Java de `MaterializePotVersion` |
| **2. Optimisation `PotBalanceSource` / réduction des lectures** (lecture SQL ciblée des montants/poids au lieu de matérialiser tous les objets domaine complets) | **Modéré à élevé** sur `PotBalance` (`489 ms` → potentiellement `< 150 ms`) | Risque faible si l'arithmétique `Fraction` et les filtres `deleted` restent strictement identiques | Profilage séparé SQL vs `PotBalanceCalculator` sur `100/2000` |
| **3. Mutualisation de lectures / cache immuable intra-JVM** (les `SubobjectVersion` sont immuables par `(pot_id, kind, object_id, local_version)`) | **Élevé** en régime stationnaire (quasi-zéro `SELECT` `read_subobject_version` pour les clés déjà connues) | Risque de divergence si la base READ est reconstruite (`rebuild`) sans invalidation du cache | Vérification du contrat d'invalidation lors de `v3-rebuild-read` |
| **4. Coalescence de versions** | **Élevé en rattrapage (`PREPARE` / `F01-G`)**, nul sur la latence marginale à `1 cmd/s` | **Élevé** : `SPEC-09 §4` exige `PotBalance(P,V)` exacte et durable pour **chaque** version `V` | Hors contrat `SPEC-09` actuel sans amendement normatif |
| **5. Projection incrémentale de `PotBalance`** | Réduit `PotBalance` (`489 ms` → quelques ms) ; **aucun impact sur `PotVersion` (`1 464 ms`)** | Complexité de reprise, CAS, et exactitude historique `V < D` (`L1-F`) | **Prématurée** tant que `MaterializePotVersion` (`3×` plus lent) n'est pas traité et mesuré en Config A |

---

## 7. Point d'arrêt et proposition d'étape suivante

1. **Mécanisme `PREPARE`/`RESTORE`** : validé (`0.50 s` Moyen, `13.06–13.71 s` Grand).
2. **Mesure marginale fiable** : obtenue sur `Moyen` (`med-1` `PASS`, `med-5` `PASS`) et caractérisée sur `Grand` (`restore-large-1`, `n=49` valides, `0` censure, `0` erreur fonctionnelle).
3. **Diagnostic documenté** : `MaterializePotVersion` (`1 463.9 ms` p50) est identifié comme le principal goulot devant `MaterializePotBalance` (`488.8 ms` p50).
4. **Prochaine étape proposée (après validation humaine)** :
   - Engager un lot ciblé d'investigation/optimisation de `MaterializePotVersion` (réduction des relectures `2×` de `read_subobject_version` et de la désérialisation JSON complète sur sous-objets inchangés), puis rejouer `large-1` sur `3` pots via `PREPARE`/`RESTORE` avant toute décision sur `PotBalance` ou L7.4-B.

**Verdict L7.4-A** : **`L7_4_A_BASELINE_QUALIFIED`**.
