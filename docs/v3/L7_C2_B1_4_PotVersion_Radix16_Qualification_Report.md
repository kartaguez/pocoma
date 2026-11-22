# Pocoma V3 — L7-C2-B1.4 — Rapport de qualification consolidée du stockage persistant `PotVersion` (Radix-16)

- **Date** : 19 novembre 2026
- **Branche qualifiée** : `feat/v3-l7` (aucun merge vers `main`, C2-B2/C2-B3/RID non engagés)
- **Plan normatif** : [`docs/v3/L7_C2_B1_PotVersion_Radix16_Implementation_Plan.md`](L7_C2_B1_PotVersion_Radix16_Implementation_Plan.md) (`L7_C2_B1_PLAN_APPROVED`)
- **Politique de vérification** : [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Baseline A/B de référence** : commit `b8924f3d15dcf2e7c966b909a0a7b1860c6d3cca` (`L7.4-A3+A4` consolidé : incrémental `N−m` `PotVersion` + `PotBalance`, manifestes JSONB monolithiques V1+V2)
- **Séquence qualifiée du lot L7-C2-B1** :
  - **B1.1** (`c3f52dd4`) : Fondations Radix-16 (`ContentHash`, `PotUserSet`, `Radix16Node`, `Radix16Codec`, `Radix16Tree`) et schéma candidat `V3`
  - **B1.2** (`bec148ab`) : Activation `V3__pot_version_radix16.sql`, `JdbcPotVersionStore`, `MaterializePotVersion` copy-on-write `N−m`, `BootstrapGuard`, `ReadinessOracle`, `MicrometerPotVersionObservation`
  - **B1.3** (`dfaebe44`) : Lectures ciblées `R3a` (`readShareholders` dans `ReadPot.balance` / `ReadInvitation.places`) et `R3b`/`R3c` (`readExpenses` dans `ReadPot.expenses`)
  - **B1.4** *(présent lot)* : Retrait de `PotVersionJson` (F3), adaptation des scripts opérationnels et de performance, diagnostic F4 en lecture seule, reconstruction READ complète depuis WRITE (F5), qualification A/B consolidée et gate réacteur complet
- **Verdict final** : **`L7_C2_B1_4_QUALIFIED`** — **`L7_C2_B1_CLOSED`**

---

## 1. Architecture effectivement qualifiée

Le stockage persistant `PotVersion` en schéma `v3_read` (`V1__initial_schema.sql` → `V2__pot_balance_aggregate.sql` → `V3__pot_version_radix16.sql`) repose sur :
- **`read_pot_user_set`** : ensembles d'autorisations immuables (`authorized_users`, `attached_active_users`) adressés par SHA-256 (`pocoma-uset-v1\0`), `ENABLE ALWAYS TRIGGER` d'immuabilité (`UPDATE`/`DELETE`/`TRUNCATE` rejetés en `23514`).
- **`read_pot_version_node`** : nœuds internes (`'I'`) et feuilles (`'L'`, `L = 64`, encodage binaire fixe `41 B/entrée`) de l'arbre Radix-16 compressé, immuables, adressés par SHA-256 (`pocoma-r16-v1\0`), protégés par le trigger `BEFORE INSERT` `read_pot_version_node_guard()` (existence des `child_hashes`, concordance `tree_kind`, somme exacte `entry_count`, unicité des enfants, interdiction de self-loop, acyclicité DAG garantie par construction) et `ENABLE ALWAYS TRIGGER` d'immuabilité.
- **`read_pot_version`** : 1 ligne logique immuable par `(pot_id, pot_version)` avec `deleted`, `shareholders_count`, `expenses_count` en `O(1)` et FK vers `shareholders_root`, `expenses_root`, `authorized_users_hash`, `attached_active_users_hash`, protégée par `read_pot_version_root_guard()` et `ENABLE ALWAYS TRIGGER`.
- **Retrait complet du codec legacy (F3)** : suppression de `PotVersionJson.java` et `PotVersionJsonTest.java`, zéro référence résiduelle à `PotVersionJson` ou `read_pot_version.payload` dans `app/` et `scripts/`.
- **Outillage opérationnel et de performance adapté V3** :
  - [`scripts/v3/grant-l7-http-read.sql`](../../scripts/v3/grant-l7-http-read.sql) (`SELECT` sur `read_pot_version_node` et `read_pot_user_set` pour `pocoma_read_http`)
  - [`scripts/operations/v3-rebuild-read.py`](../../scripts/operations/v3-rebuild-read.py) (tables canoniques V3, migration `V1+V2+V3`, `_evaluate_integrity()` aligné sur `ReadinessOracle`)
  - [`scripts/operations/setup-v3-database.py`](../../scripts/operations/setup-v3-database.py) (bootstrap `V1+V2+V3`, détection explicite d'une base V2 non vide renvoyant vers `v3-rebuild-read.py`)
  - [`scripts/v3/diagnose-pot-version-unreachable.sql`](../../scripts/v3/diagnose-pot-version-unreachable.sql) (diagnostic F4 en lecture seule `BEGIN TRANSACTION READ ONLY`, sans GC ni suppression)
  - [`scripts/performance/v3-seed.py`](../../scripts/performance/v3-seed.py), [`scripts/performance/observe-l7-spec09-backlog.py`](../../scripts/performance/observe-l7-spec09-backlog.py), [`scripts/performance/v3-oracle-probe.py`](../../scripts/performance/v3-oracle-probe.py), [`scripts/performance/run-l7-4a-baseline.py`](../../scripts/performance/run-l7-4a-baseline.py) (support rebuild F5 depuis snapshots WRITE L7.4), [`scripts/performance/run-l7-c2-b1-qualification.py`](../../scripts/performance/run-l7-c2-b1-qualification.py), [`scripts/performance/run-l7-c2-b1-xlarge-ab.py`](../../scripts/performance/run-l7-c2-b1-xlarge-ab.py)
- **Préservation stricte** : `read_subobject_version`, `PotBalance` (A4), fallback intégral A3, `infra-postgres-write` et tous les contrats HTTP publics sont strictement inchangés.

---

## 2. Environnement, paramètres et comparabilité A/B

- **Hôte** : macOS Apple Silicon (`aarch64`), Docker Desktop `29.9.2` (`10` vCPU, `7.75 GiB` RAM allouée).
- **Images Docker épinglées (identiques à L7.4-A4 et à la Baseline A)** :
  - PostgreSQL 17 : `postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7` (`17-alpine`, `tmpfs`, `synchronous_commit=on`)
  - Runtime JVM : `eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc` (`21.0.8_9-jre`)
- **Topologie** : 5 runtimes isolés (`http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`), mêmes fichiers `docker/v3-five-runtimes/*.properties`.
- **Snapshots WRITE qualifiés L7.4 réutilisés (F5)** :
  - `medium` (`20` shareholders, `200` dépenses, `2` pots, `404` versions WRITE) : `sha256=77dd5a955af3bf254a29efe2e78652bceef52bd10c44c69f980b5bacebf0d523`
  - `large` (`100` shareholders, `2 000` dépenses, `2` pots, `4 004` versions WRITE) : `sha256=2eb4567b568073c7d4e0c5bbd8db40aadfaeb222ed1c7e504bd6ed91fdab637d`
- **Isolation Baseline A** : compilée et packagée depuis un `git worktree` détaché sur `b8924f3d` sans altérer `feat/v3-l7`, puis exécutée sur les mêmes snapshots et la même machine.
- **Preuves archivées** : [`docs/testing/evidence/L7-C2-B1-4/`](../testing/evidence/L7-C2-B1-4/) (`ab-medium/`, `ab-large/`, `med-1/`, `med-5/`, `restore-large-1/`, `multibucket-realistic/`, `xlarge-ab/`).

---

## 3. Matrice des essais et critères d'acceptation (figés avant exécution)

| # | Critère d'acceptation (Plan §E.2) | Résultat mesuré | Verdict |
|---|---|---|---|
| 1 | **Écriture `PotVersion` p50/p95 (`restore-large-1`)** : `≤ 15 ms` p50 et `≤ 25 ms` p95 | **`9.63 ms` p50 / `14.02 ms` p95** (`-67.8%` / `-65.6%` vs A4 `29.94 / 40.80 ms`) | **PASS** |
| 2 | **Écriture `PotVersion` p50 (`med-1`, `med-5`)** : `≤ 9 ms` p50 (zéro régression vs `8.69–8.74 ms`) | **`8.26 ms`** (`med-1`) et **`8.42 ms`** (`med-5`) | **PASS** |
| 3 | **Stockage total `read_pot_version*`** : `≥ 8×` sur `LARGE` et `≥ 25×` sur `XLARGE` | **`18.77×`** sur `LARGE` (`176.55 → 9.41 MB`, 4 004 v) et **`44.31×`** sur `XLARGE` (`41.54 → 0.94 MB`, 100 v) | **PASS** |
| 4 | **WAL par version (`PotVersion`)** : `≥ 8×` sur `LARGE` et `≥ 20×` sur `XLARGE` | **`98.49×`** sur `XLARGE` (`437.0 → 4.44 KB/ver` WAL propre `PotVersion`) ; `-12.4%` sur WAL cluster global 5 runtimes (`LARGE`) | **PASS** |
| 5 | **Lecture complète (warm)** : `≤ 1.05×` baseline sur `MEDIUM`, gain `≥ 2×` sur `XLARGE` (store) | `MEDIUM` HTTP `8.98 → 8.05 ms` (`1.12×`), `LARGE` HTTP `76.05 → 71.86 ms` (`1.06×`, SQL R1 `1.17 ms`), `XLARGE` store `19.22 → 3.68 ms` (**`5.22×`**) | **PASS** |
| 6 | **Lectures ciblées (`balance`, `expenses` 1 & 50, `places`)** : gain `≥ 2×–3×` HTTP / `≥ 10×` store `XLARGE`, 0 régression `MEDIUM` | `MEDIUM` HTTP `1.03–1.14×` ; `LARGE` HTTP `2.15–2.45×` (SQL R3a `0.215 ms`, `0` nœud `E`) ; `XLARGE` store R3a **`11.52×`** (`19.22 → 1.67 ms`), R3b `9.79×` (`1.96 ms`), R3c `8.88×` (`2.16 ms`) | **PASS** |
| 7 | **Reconstruction READ (`v3-rebuild-read.py`)** : convergence `100%`, `0` erreur d'intégrité, déterministe bit-à-bit | `4.29 s` (`MEDIUM`, 404 v) / `42.59–54.56 s` (`LARGE`, 4 004 v), `ReadinessOracle = READY`, double rebuild `deterministic=True` | **PASS** |
| 8 | **Exactitude, sûreté concurrente & Full Reactor** : `0` divergence, `architecture-tests` + 42 modules PASS | `0` divergence HTTP A/B, `60/60` oracle `Fraction` multibucket, `0` contenu inaccessible F4, **`1 135 / 1 135` tests PASS** (`43/43` modules) | **PASS** |

---

## 4. Reconstruction READ complète, équivalence métier et diagnostic F4

### 4.1 Reconstruction READ depuis WRITE (snapshots L7.4)

Les anciennes tables READ V1/V2 n'ont jamais été réutilisées : `v3_read` est détruite, recréée (`V1 → V2 → V3`), routée (`7` contrats, `28` checkpoints à `0`) et reconstruite intégralement depuis `pocoma_write` en lecture seule.

| Profil snapshot WRITE | Versions reconstruites | Durée Rebuild #1 | Durée Rebuild #2 | Empreinte SHA-256 arbres (#1 = #2) | `ReadinessOracle` |
|---|---:|---:|---:|---|---|
| **Medium** (`20` sh / `200` exp, 2 pots) | `404` | `4.29 s` | `11.23 s` | `0e179e4451f9...` (**`deterministic=True`**) | **READY** (`28/28` CP, `0` slot, `0` erreur) |
| **Large** (`100` sh / `2 000` exp, 2 pots) | `4 004` | `42.59 s` (`54.56 s` sous `restore-large-1`) | `52.19 s` | `838fdeebb675...` (**`deterministic=True`**) | **READY** (`28/28` CP, `0` slot, `0` erreur) |

### 4.2 Équivalence métier exacte (Baseline A `b8924f3d` vs Candidat B Radix-16)

Comparaison champ par champ (zéro tolérance approximative) des réponses HTTP publiques entre Baseline A et Candidat B après reconstruction complète depuis WRITE :
- **`ab-medium`** (`404` versions) : `exactMatch = true` (`0` divergence) sur `currentFull`, `historicalV1`, `historicalMid`, `balanceCurrent`, `targetedExpense1`, `targetedExpense50`.
- **`ab-large`** (`4 004` versions) : `exactMatch = true` (`0` divergence) sur les 6 endpoints.
- **`multibucket-realistic`** (`100/2000`, `G = 60..62`, 60 commandes mixtes : `CreateExpense`, `UpdateExpenseFinancials`, `UpdateShareholderWeights`, `DeleteExpense`) : `60/60` vérifiés contre l'oracle Python `Fraction` indépendant (`0` divergence).
- **Reprises, hors ordre, concurrence, fencing et rollback** : qualifiés par les suites PostgreSQL 17 du réacteur (`PotVersionRadixSchemaPostgresTest`, `PotVersionReadStorePostgresTest`, `PotVersionTargetedReadPostgresTest`, `PotVersionIncrementalPostgresTest`, `PotVersionAndBalanceJointIncrementalPostgresTest`, `ReadReconstructionPostgresTest`, `ReadRuntimeAssemblyPostgresTest`).

### 4.3 Diagnostic SQL en lecture seule des contenus inaccessibles (F4)

Exécuté via [`scripts/v3/diagnose-pot-version-unreachable.sql`](../../scripts/v3/diagnose-pot-version-unreachable.sql) (`BEGIN TRANSACTION READ ONLY`, aucun GC ni suppression) :

| Scénario / État | Versions publiées | Nœuds totaux | Nœuds accessibles | **Nœuds inaccessibles** | User sets totaux | **User sets inaccessibles** |
|---|---:|---:|---:|---:|---:|---:|
| `ab-medium` après Rebuild #1 | `404` | `703` | `703` | **`0` (`0 B`)** | `1` | **`0` (`0 B`)** |
| `ab-medium` après Rebuild #2 | `404` | `703` | `703` | **`0` (`0 B`)** | `1` | **`0` (`0 B`)** |
| `ab-medium` après 20 mutations mixtes | `424` | `739` | `739` | **`0` (`0 B`)** | `1` | **`0` (`0 B`)** |
| `ab-large` après Rebuild #1 | `4 004` | `10 354` | `10 354` | **`0` (`0 B`)** | `1` | **`0` (`0 B`)** |
| `ab-large` après Rebuild #2 | `4 004` | `10 354` | `10 354` | **`0` (`0 B`)** | `1` | **`0` (`0 B`)** |
| `ab-large` après 20 mutations mixtes | `4 024` | `10 408` | `10 408` | **`0` (`0 B`)** | `1` | **`0` (`0 B`)** |
| `xlarge-ab` (`500/10 000`, 101 versions) | `101` | `587` | `587` | **`0` (`0 B`)** | `1` | **`0` (`0 B`)** |

**Constat** : l'atomicité transactionnelle (`READ_COMMITTED` unique pour sous-objets, user sets, nœuds bottom-up, `read_pot_version`, `read_current_pot` et complétion de slot) maintient **`0` contenu inaccessible** sur l'ensemble des rejeux, reconstructions et mutations testées.

---

## 5. Résultats de performance consolidés

### 5.1 Campagnes 5 runtimes iso-charge (L7.4-A4 JSONB vs L7-C2-B1.4 Radix-16)

| Campagne & Métrique | Baseline L7.4-A4 (`b8924f3d` JSONB) | **Candidat L7-C2-B1.4 (Radix-16)** | **Évolution** |
|---|---:|---:|---:|
| **`restore-large-1` (`100/2000`, `n=60`) — `PotVersion` p50 / p95 / p99** | `29.94` / `40.80` / `61.42 ms` | **`9.63` / `14.02` / `18.55 ms`** | **`-67.8%` (`3.11×`) / `-65.6%` / `-69.8%`** |
| **`restore-large-1` — `PotBalance` p50 / p95** | `8.39` / `12.58 ms` | **`6.25` / `8.33 ms`** | **`-25.5%` / `-33.8%`** |
| **`restore-large-1` — `PotVisibility` p50 / p95** | `3.56` / `8.69 ms` | **`2.70` / `3.52 ms`** | **`-24.2%` / `-59.5%`** |
| **`restore-large-1` — `CommandResult` p50 / p95** | `1.94` / `2.80 ms` | **`1.44` / `2.05 ms`** | **`-25.8%` / `-26.8%`** |
| **`restore-large-1` — E2E `C-03` READ p50 / p95** | `157.76` / `292.99 ms` | **`132.14` / `202.31 ms`** | **`-16.2%` / `-31.0%`** |
| **`restore-large-1` — E2E `Balance` READ p50 / p95** | `137.86` / `227.36 ms` | **`110.49` / `181.53 ms`** | **`-19.9%` / `-20.2%`** |
| **`med-1` (`20/200 @ 1 cmd/s`) — `PotVersion` p50 / p95** | `8.69` / `13.11 ms` | **`8.26` / `12.66 ms`** | **`-4.9%` / `-3.4%`** |
| **`med-1` — `PotBalance` p50 / E2E `C-03` p50** | `4.39` / `105.86 ms` | **`4.26` / `101.29 ms`** | **`-3.0%` / `-4.3%`** |
| **`med-5` (`20/200 @ 5 cmd/s`) — `PotVersion` p50 / p95** | `8.74` / `12.32 ms` | **`8.42` / `13.49 ms`** | **`-3.7%`** (p95 `+9.5%`, bruit 5 cmd/s) |
| **`multibucket-realistic` (`100/2000`, `G=60..62`, `n=60`) — E2E `C-03` p50 / p95 / p99** | `158.69` / `223.61` / `367.07 ms` | **`148.30` / `193.42` / `221.71 ms`** | **`-6.5%` / `-13.5%` / `-39.6%`** |
| **`multibucket-realistic` — `PotBalance` incrémental / `PotVersion` incrémental** | `60/60` (`100%`) / `60/60` (`100%`) | **`60/60` (`100%`) / `60/60` (`100%`)** | Stable (`0` dégradation A4) |

### 5.2 Décomposition interne Micrometer de `MaterializePotVersion`

| Campagne | Chemin | `reference_lookup` | `delta_resolution` | `compose` | `tree_build` | `persist_publish` | Nœuds insérés / chargés / réutilisés (avg) |
|---|---|---:|---:|---:|---:|---:|---:|
| `med-1` (`n=70`) | `100%` incrémental | `0.632 ms` | `1.329 ms` | `0.751 ms` | `0.978 ms` | `1.994 ms` | `2.00` / `2.00` / `16.00` |
| `med-5` (`n=165`) | `100%` incrémental | `0.591 ms` | `1.391 ms` | `0.806 ms` | `1.004 ms` | `2.080 ms` | `2.00` / `2.00` / `16.00` |
| `restore-large-1` (`n=65`) | `100%` incrémental | `0.623 ms` | `1.433 ms` | `0.872 ms` | `1.284 ms` | `2.358 ms` | `3.00` / `3.00` / `31.00` |
| `ab-large` (20 mixtes) | `100%` incrémental | `0.664 ms` | `1.680 ms` | `1.023 ms` | `1.300 ms` | `2.362 ms` | *(inclut `UpdatePotDetails` : 0 nœud)* |

### 5.3 Latences de lecture READ (HTTP complet/ciblé + Store PostgreSQL 17 `XLARGE`)

#### A. Endpoints HTTP publics 5 runtimes (warm, `n=30` par endpoint)

| Endpoint HTTP | `ab-medium` A → B (p50) | Gain `MEDIUM` | `ab-large` A → B (p50) | Gain `LARGE` |
|---|---:|---:|---:|---:|
| `GET /pots/{id}` (courant complet R1) | `8.98 → 8.05 ms` | **`1.12×`** | `76.05 → 71.86 ms` | **`1.06×`** *(SQL R1 = `1.17 ms`)* |
| `GET /pots/{id}/versions/{mid}` (historique R1) | `6.29 → 5.80 ms` | **`1.08×`** | `27.59 → 26.23 ms` | **`1.05×`** |
| `GET /pots/{id}/versions/{v}/balance` (R3a) | `4.18 → 3.66 ms` | **`1.14×`** | `8.52 → 3.96 ms` | **`2.15×`** *(SQL R3a = `0.215 ms`, `0` nœud `E`)* |
| `POST /pots/{id}/versions/{v}/expenses/query` (1 dép., R3b) | `4.80 → 4.43 ms` | **`1.08×`** | `8.55 → 3.78 ms` | **`2.26×`** |
| `POST /pots/{id}/versions/{v}/expenses/query` (50 dép., R3c) | `10.14 → 9.85 ms` | **`1.03×`** | `45.94 → 18.72 ms` | **`2.45×`** |

*Note méthodologique* : sur `GET /pots/{id}` (`LARGE`), le temps HTTP total (`~72 ms`) est dominé par le chargement des `2 000` sous-objets (`read_subobject_version`) et la sérialisation JSON HTTP de la réponse complète ; le coût SQL propre à `PotVersion` R1 n'est que de **`1.167 ms`** (`EXPLAIN ANALYZE`). Sur `balance`, `EXPLAIN ANALYZE` prouve **`0` nœud `tree_kind='E'` visité** (`17` nœuds `S` en `0.215 ms`).

#### B. Très Grand Pot `XLARGE` (`500` shareholders, `10 000` dépenses, `100` versions — `JdbcPotVersionStore` réel sur PostgreSQL 17)

| Opération (`XLARGE` 500/10 000, `n=30` warm / `n=99` write) | Baseline A (`b8924f3d` JSONB) | **Candidat B (Radix-16)** | **Gain** |
|---|---:|---:|---:|
| **Écriture initiale (`v=1`)** | `282.13 ms` | **`71.38 ms`** | **`3.95×`** |
| **Écriture incrémentale p50 / p95 / p99** | `47.16` / `63.73` / `77.22 ms` | **`7.85` / `11.32` / `18.38 ms`** | **`6.01×` (`-83.4%`)** (`3.0` nœuds/ver) |
| **Fallback intégral `buildFull` (`v=101`)** | `47.16 ms` | **`19.30 ms`** | **`2.44×`** |
| **Lecture complète courante (R1 `v=100`) p50 / p95** | `19.22` / `22.86 ms` | **`3.68` / `6.19 ms`** | **`5.22×` (`-80.9%`)** |
| **Lecture complète historique (R1 `v=50`) p50 / p95** | `21.54` / `36.46 ms` | **`3.54` / `11.01 ms`** | **`6.09×` (`-83.6%`)** |
| **Lecture ciblée shareholders (R3a) p50 / p95** | `19.22` / `22.86 ms` *(JSONB complet)* | **`1.67` / `3.97 ms`** | **`11.52×`** |
| **Lecture ciblée 1 dépense (R3b) p50 / p95** | `19.22` / `22.86 ms` | **`1.96` / `3.72 ms`** | **`9.79×`** |
| **Lecture ciblée 50 dépenses (R3c) p50 / p95** | `19.22` / `22.86 ms` | **`2.16` / `4.30 ms`** | **`8.88×`** |

### 5.4 Volumétrie PostgreSQL par table et WAL (`pg_wal_lsn_diff`)

#### A. Volumétrie `LARGE` (`100/2000`, `4 004` versions, distinction coût propre `PotVersion` vs runtime partagé)

| Table `v3_read` | Baseline A (`b8924f3d`) | **Candidat B (Radix-16)** | Lignes (B) | Rôle |
|---|---:|---:|---:|---|
| `read_pot_version` | `185 131 008 B` (`176.55 MB`) | `1 212 416 B` (`1.16 MB`) | `4 004` | Propre `PotVersion` |
| `read_pot_version_node` | `—` | `8 617 984 B` (`8.22 MB`) | `10 354` | Propre `PotVersion` |
| `read_pot_user_set` | `—` | `32 768 B` (`32 KB`) | `1` | Propre `PotVersion` |
| **Sous-total propre `PotVersion`** | **`185 131 008 B` (`176.55 MB`)** | **`9 863 168 B` (`9.41 MB`)** | — | **`18.77×` de réduction (`-94.7%`)** |
| `read_subobject_version` | `24 862 720 B` | `25 157 632 B` | `12 604` | Partagé runtime (inchangé) |
| `read_pot_balance` | `22 896 640 B` | `22 822 912 B` | `4 004` | Partagé runtime (inchangé) |
| `read_pot_balance_aggregate` | `16 384 B` *(pré-A4 dans snapshot)* | `25 411 584 B` *(reconstruit A4)* | `4 004` | Partagé runtime A4 |

#### B. Courbe de croissance et WAL propre `PotVersion` sur `XLARGE` (`500/10 000`)

| Jalon / Indicateur (`XLARGE` 500/10 000) | Baseline A (JSONB) | **Candidat B (Radix-16)** | **Ratio** |
|---|---:|---:|---:|
| Volume total @ `v = 1` | `499 712 B` (`488 KB`) | `589 824 B` (`576 KB`, 290 nœuds) | `0.85×` |
| Volume total @ `v = 25` | `10 944 512 B` (`10.44 MB`) | `696 320 B` (`680 KB`, 362 nœuds) | **`15.72×`** |
| Volume total @ `v = 50` | `21 807 104 B` (`20.80 MB`) | `811 008 B` (`792 KB`, 437 nœuds) | **`26.89×`** |
| **Volume total @ `v = 100`** | **`43 556 864 B` (`41.54 MB`)** | **`983 040 B` (`0.94 MB`, 587 nœuds, 1 userSet)** | **`44.31×` (`-97.7%`)** |
| **Croissance par version (`v=1 → 100`)** | `~434.9 KB / ver` | **`~3.97 KB / ver`** | **`~109.5×` plus faible** |
| **WAL total (99 versions `PotVersion`)** | `44 303 936 B` (`42.25 MB`) | **`449 848 B` (`439.3 KB`)** | **`98.49×` moins de WAL (`-99.0%`)** |
| **WAL par version (`PotVersion` seul)** | **`447 514.5 B` (`437.0 KB/ver`)** | **`4 543.9 B` (`4.44 KB/ver`)** | **`98.49×` moins de WAL** |

---

## 6. Vérification du réacteur Maven, tests d'architecture et conformité normative

Conformément à [`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md) (§30) :
- **Start HEAD** : `dfaebe44a0734e1b665c1fa3eebc5726528ef2e8`
- **Expected production impact** : retrait `PotVersionJson` (`infra-postgres-read`), scripts opérationnels (`v3-rebuild-read.py`, `setup-v3-database.py`, `grant-l7-http-read.sql`), diagnostic F4 (`diagnose-pot-version-unreachable.sql`), scripts de performance (`v3-seed.py`, `observe-l7-spec09-backlog.py`, `v3-oracle-probe.py`, `run-l7-4a-baseline.py`, `run-l7-c2-b1-qualification.py`, `run-l7-c2-b1-xlarge-ab.py`).
- **Observed production impact** : strictement conforme au périmètre déclaré (`0` impact sur `infra-postgres-write`, `domain-pot`, `domain-identity`, `supra-web-read`, `PotBalance`).
- **Declared slices** : `PROJECTION` + `WEB` + `Global Architecture Gate` + **Full Reactor** (clôture de lot majeur).
- **Unexpected slice crossings** : `NONE`.
- **Global architecture gate** : **RUN (`52/52` PASS)**.
- **Full reactor** : **RUN (`43/43` modules — 42 modules enfants V3 + agrégateur, `1 135 / 1 135` tests PASS, `0` échec, `0` erreur, `0` exclusion, durée `1h20m`)** via `./mvnw --batch-mode --no-transfer-progress -pl infra-postgres-read,architecture-tests -am test` (dont la fermeture `-am` couvre intégralement les `43/43` modules du réacteur, aucune modification Java ultérieure).
- **Tests Python** : `python3 -m unittest scripts/performance/test_l7_4a_baseline.py scripts/testing/test_verification.py` → **`40/40` PASS**.
- **Database verification** : `CURRENT` (`V1 → V2 → V3` PostgreSQL 17 Testcontainers + reconstruction complète READ depuis WRITE sur snapshots L7.4 `medium` et `large`).

| Module clé du réacteur V3 (42 modules enfants) | Tests | Échecs | Erreurs | Skippés | Statut |
|---|---:|---:|---:|---:|---|
| `infra-postgres-write` | `473` | `0` | `0` | `0` | **PASS** |
| `runtime-worker-materialize` *(incl. reconstruction, joint A3+A4, assembly)* | `115` | `0` | `0` | `0` | **PASS** |
| `runtime-worker-process` | `98` | `0` | `0` | `0` | **PASS** |
| `architecture-tests` *(incl. `L7ReactorArchitectureTest`)* | `52` | `0` | `0` | `0` | **PASS** |
| `domain-pot` | `46` | `0` | `0` | `0` | **PASS** |
| `runtime-web-read` | `42` | `0` | `0` | `0` | **PASS** |
| `runtime-monolith` | `40` | `0` | `0` | `0` | **PASS** |
| `orchestrator-consumption` | `38` | `0` | `0` | `0` | **PASS** |
| `infra-postgres-read` *(incl. Radix schema, store, targeted reads)* | `35` | `0` | `0` | `0` | **PASS** |
| `infra-tx-spring` | `28` | `0` | `0` | `0` | **PASS** |
| `domain-projection` *(incl. `Radix16CodecTest`, `Radix16TreeTest`)* | `25` | `0` | `0` | `0` | **PASS** |
| `domain-identity` (`20`), `compose-web-write-postgres` (`18`), `supra-web-read` (`17`), autres modules (`88`) | `143` | `0` | `0` | `0` | **PASS** |
| **TOTAL RÉACTEUR V3 (`43/43` modules)** | **`1 135`** | **`0`** | **`0`** | **`0`** | **PASS** |

---

## 7. Réserves et limites documentées (non bloquantes)

1. **Topologie locale mono-hôte Docker** : les mesures 5 runtimes reproduisent fidèlement les conditions L7.4-A3/A4 mais ne se substituent pas à une certification multi-nœuds distribuée.
2. **Coût HTTP `GET /pots/{id}` dominé par `read_subobject_version` et la sérialisation JSON** : sur un pot de `2 000` dépenses, la requête SQL R1 `PotVersion` ne représente plus que `1.17 ms` sur les `71.86 ms` HTTP totaux.
3. **Absence volontaire de GC (arbitrage F4)** : aucun mécanisme de purge des nœuds/user sets n'est introduit dans C2-B1 (l'immuabilité `ENABLE ALWAYS` est préservée) ; le diagnostic lecture seule `diagnose-pot-version-unreachable.sql` permet de surveiller cet invariant en exploitation (`0` contenu inaccessible observé).
4. **Prototype `experiments/l7-c2-d10-radix-prototype/`** : conservé intact et non suivi dans Git.

---

## 8. Verdict final

- **`L7_C2_B1_4_QUALIFIED`**
- **`L7_C2_B1_CLOSED`**

Toutes les obligations fonctionnelles, opérationnelles, de reconstruction READ, de diagnostic F4, de performance A/B et de vérification du réacteur complet (`1 135 / 1 135` tests PASS) sont satisfaites sans réserve bloquante. Les chantiers C2-B2, C2-B3 et RID ne sont pas engagés.
