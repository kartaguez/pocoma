# Rapport L7.4-A3 — Calcul incrémental `N−m` de `PotVersion`

- **Date** : 19 novembre 2026
- **Branche de référence (L7.4-A2)** : `feat/l7-4-a2-potversion-optimization` @ `f196b2fb`
- **Branche d'implémentation (L7.4-A3)** : `feat/l7-4-a3-potversion-incremental-nm`
- **Document de conception** : [`PotVersion_Incremental_Materialization_Design_Plan.md`](PotVersion_Incremental_Materialization_Design_Plan.md)
- **Verdict final** : **`N_MINUS_M_QUALIFIED`**

---

## 1. Vérification préalable : suppressions logiques en `v3_write`

L'inspection directe du code confirme les 5 invariants suivants avant implémentation :

1. **Suppressions exclusivement logiques** : [`JdbcPotRevisionAdapter.reviseDeletedHeader()`](../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/pot/JdbcPotRevisionAdapter.java#L316-L332) (appelé par `revisePotHeader`, `reviseShareholderHeader`, `reviseExpenseHeader`) clôt la révision ouverte via `close()` puis insère une nouvelle ligne avec `deleted = true`. Aucun `DELETE` SQL n'existe sur `v3_*`.
2. **Représentation `active_from` / `active_to`** : [`close()`](../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/pot/JdbcPotRevisionAdapter.java#L410-L426) met `active_to = N` sur l'ancienne ligne et insère une nouvelle ligne `local_version = old + 1`, `active_from = N`, `active_to = NULL`.
3. **Toute clôture produit une ligne de remplacement** : [`PotHistoryRows.validateCompleteChain()`](../../app/infra-postgres-core/src/main/java/com/kartaguez/pocoma/infra/postgres/core/history/PotHistoryRows.java#L426-L439) impose une chaîne contiguë `[birth, v2), [v2, v3), ..., [vk, null)` sans trou et avec exactement une révision ouverte.
4. **Représentation dans `PotVersion`** : [`JdbcPotVersionSource.loadShareholders()`](../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/JdbcPotVersionSource.java#L119-L131) et [`loadExpenses()`](../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/JdbcPotVersionSource.java#L135-L150) conservent les entités supprimées dans `PotVersion.shareholders()` et `PotVersion.expenses()` avec `deleted = true` ; seul `attachedActiveUserIds` exclut les shareholders `deleted == true`.
5. **Réactivation interdite** : [`JdbcPotRevisionAdapter.deletion()`](../../app/infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/pot/JdbcPotRevisionAdapter.java#L401-L408) et les transitions métier interdisent toute mutation ou réactivation après `deleted = true`. La requête SQL `(active_from > K AND active_from <= N AND (active_to IS NULL OR active_to > N))` résout néanmoins directement l'état final exact à `N` même en présence de remplacements successifs.

---

## 2. Architecture implémentée et arbitrages respectés

- **Modèle** : $P_N = P_K \oplus \Delta_{K \rightarrow N}$ avec $K = \max\{k \mid N - M_{\max} \le k < N,\ P_k \text{ commise dans } \texttt{read\_pot\_version}\}$.
- **D1 (Validation des références inchangées)** : `read_pot_version` et `read_subobject_version` sont strictement immuables (triggers `ENABLE ALWAYS`, privilèges runtime `SELECT, INSERT` uniquement). Sur le chemin incrémental, seules les clés nouvelles/modifiées (`changedKeys`) sont vérifiées via `existingKeys`, `requireStored(loaded)` et `requireKeysPresent(presentChangedKeys)`.
- **D2 (Mutations shareholder à effet global)** : toute modification de `SHAREHOLDER_HEADER` ou `SHAREHOLDER_DETAILS` sur un shareholder déjà actif et attaché dans $P_K$ (`!deleted && !isFree`) déclenche un fallback immédiat vers le chemin intégral (`GLOBAL_SHAREHOLDER_EFFECT`).
- **D3 (Décodages JSON)** : `JdbcPotVersionStore.insertAndAdvance` et `requireStored` laissés strictement inchangés.
- **D4 (`maxDeltaDistance`)** : paramètre configurable `pocoma.v3.worker.pot-version.max-delta-distance` (défaut `64`, `0` désactive l'incrémental).
- **Garde-fous** : aucune attente de $K$, aucun retry supplémentaire, aucune modification de `RoutingV1` / `JdbcSlotKeyNavigation` / schéma PostgreSQL / API READ ; toute corruption (`Corrupt PotVersion`, `InconsistentPotFactsException`, erreur SQL/TX) se propage sans fallback silencieux.

### Fichiers modifiés / créés

| Module | Fichier | Rôle |
|---|---|---|
| `port-projection` | [`PotVersionStore.java`](../../app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/PotVersionStore.java) | Ajout de `readLatestBefore(pot, beforeVersionExclusive, minVersionInclusive, context)`. |
| `infra-postgres-read` | [`JdbcPotVersionStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcPotVersionStore.java) | Implémentation `readLatestBefore` via `Index Scan Backward` sur `read_pot_version_pkey` (`LIMIT 1`), sans dépendance à `read_current_pot`. |
| `engine-materialize-pot` | [`PotVersionSource.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/PotVersionSource.java) | Ajout de `EventRef`, `StateDelta`, `eventRef(...)` et `stateDelta(...)`. |
| `engine-materialize-pot` | [`PotVersionObservation.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/PotVersionObservation.java) | Contrat d'instrumentation borné (hit/miss/corrupt, distance `N−K`, sous-objets affectés, chemin/motif, durées par étape). |
| `engine-materialize-pot` | [`PotVersionDeltaComposer.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/PotVersionDeltaComposer.java) | Composition pure $P_K \oplus \Delta_{K \rightarrow N}$, vérification de monotonie et détection D2. |
| `engine-materialize-pot` | [`MaterializePotVersion.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotVersion.java) | Algorithme deux chemins `N−m` + fallback intégral permanent ; `prepareRecovery()` inchangée. |
| `infra-postgres-write-source-read` | [`JdbcPotVersionSource.java`](../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/JdbcPotVersionSource.java) | Implémentation `eventRef` et `stateDelta` sur les 8 tables via les index existants `v3_<composant>_effect(pot_id, active_from)`. |
| `compose-polling-nonburnable-materialize-postgres` | [`MicrometerPotVersionObservation.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MicrometerPotVersionObservation.java), [`MaterializeSettings.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeSettings.java), [`ReadParticipants.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/ReadParticipants.java), [`MaterializeComposeConfiguration.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeComposeConfiguration.java) | Câblage de `maxDeltaDistance` et des métriques Micrometer `pocoma.v3.potversion.*`. |
| Tests | [`MaterializePotVersionIncrementalTest.java`](../../app/engine-materialize-pot/src/test/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotVersionIncrementalTest.java), [`PotVersionReadStorePostgresTest.java`](../../app/infra-postgres-read/src/test/java/com/kartaguez/pocoma/infra/postgres/read/PotVersionReadStorePostgresTest.java), [`PotVersionIncrementalPostgresTest.java`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/PotVersionIncrementalPostgresTest.java) | Tests unitaires et d'intégration PostgreSQL 17 contre l'oracle intégral. |

---

## 3. Qualification technique (`Reactor_Verification_Policy.md`)

- **Slice primaire déclarée** : `PROJECTION`
- **Slices secondaires** : `NONE`
- **Traversée de slice inattendue** : `NONE`
- **Global architecture gate** : exécuté sur `HexagonalArchitectureTest` + `ConsumptionEngineArchitectureTest` (demandé explicitement par le mandat §9)
- **Full reactor** : `NOT REQUIRED` (non exécuté)
- **Base de données** : `NONE` (aucune migration SQL)

| Commande exécutée (depuis `app/`) | Résultat | Détail |
|---|---|---|
| `./mvnw -pl engine-materialize-pot -am clean test` | **`PASS`** | `5/5` tests unitaires (`MaterializePotVersionTest`, `MaterializePotVersionIncrementalTest`). |
| `./mvnw -pl runtime-worker-materialize -am -Dtest=PotVersionIncrementalPostgresTest,PotVersionReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false clean test` | **`PASS`** | `4/4` (`PotVersionReadStorePostgresTest`) + `2/2` (`PotVersionIncrementalPostgresTest` : hors-ordre `V1,V6,V3,V9,V10,V11`, concurrence `V4/V5`, remplacements successifs, suppression logique, effet global shareholder, `maxDeltaDistance`, idempotence, `prepareRecovery`). |
| `./mvnw -pl runtime-worker-materialize -am clean test` | **`PASS`** | Slice canonique `PROJECTION` complète : **35 modules**, **`111/111`** tests dans `runtime-worker-materialize`, `0` échec, `0` erreur. |
| `./mvnw -pl architecture-tests -am -Dtest=HexagonalArchitectureTest,ConsumptionEngineArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false clean test` | **`PASS`** | `15/15` (`HexagonalArchitectureTest`) + `11/11` (`ConsumptionEngineArchitectureTest`), `0` violation de dépendance. |

---

## 4. Qualification de performance L7.4 (avant / après)

Conditions strictement identiques à L7.4-A et L7.4-A2 : topologie 5 runtimes Docker (`eclipse-temurin:21.0.8_9-jre`, `postgres:17-alpine`), mêmes snapshots `/private/tmp/pocoma-l74a-snapshots/{medium,large}` vérifiés par SHA-256.

*(Note de traçabilité : une première exécution `docs/testing/evidence/L7-4A3/restore-large-1` a échoué sur un timeout transitoire `docker exec psql` du collecteur après 8 s et est conservée telle quelle ; l'exécution qualifiée est `restore-large-1-run2`.)*

### 4.1 Grand Pot — Comparaison directe (`restore-large-1`, `100/2000`, `2` pots, `1.0 cmd/s`, `n=60`)

| Métrique | L7.4-A (baseline) | L7.4-A2 (optimisé) | L7.4-A3 (`N−m`) | Gain vs L7.4-A2 | Gain vs L7.4-A |
|---|---:|---:|---:|---:|---:|
| **Verdict / Offres observées** | `MEASUREMENT_INVALID` (`49/60`) | `PASS` (`60/60`) | **`PASS` (`60/60`, `0` drop)** | — | `-100%` drops |
| **Débit effectif terminé** | `0.829 cmd/s` | `0.998 cmd/s` | **`1.008 cmd/s`** | `+1.0%` | `+21.6%` |
| **PotVersion p50** | `1 463.92 ms` | `1 031.52 ms` | **`34.96 ms`** | **`-96.6%` (`29.5×`)** | **`-97.6%` (`41.9×`)** |
| **PotVersion p95** | `2 094.30 ms` | `1 376.17 ms` | **`50.63 ms`** | **`-96.3%` (`27.2×`)** | **`-97.6%` (`41.4×`)** |
| **PotVersion p99** | `2 187.26 ms` | `1 544.06 ms` | **`107.44 ms`** | **`-93.0%` (`14.4×`)** | **`-95.1%` (`20.4×`)** |
| **PotBalance p50 / p95** | `488.82` / `671.50 ms` | `455.51` / `573.08 ms` | `381.61` / `441.65 ms` | `-16.2%` / `-22.9%` | `-21.9%` / `-34.2%` |
| **E2E `C-03` READ p50** | `1 651.56 ms` | `1 234.93 ms` | **`511.03 ms`** | **`-58.6%`** | **`-69.1%`** |
| **E2E `C-03` READ p95** | `2 262.15 ms` | `1 549.65 ms` | **`613.79 ms`** | **`-60.4%`** | **`-72.9%`** |
| **E2E `Balance` READ p50 / p95** | `1 613.75` / `2 230.99 ms` | `1 179.53` / `1 536.51 ms` | `488.17` / `588.20 ms` | `-58.6%` / `-61.7%` | `-69.7%` / `-73.6%` |
| **CPU `postgres` (avg / max)** | `132.5%` / `195.0%` | `152.9%` / `174.2%` | **`86.6%` / `94.6%`** | **`-66.3` / `-79.6` pts** | **`-45.9` / `-100.4` pts** |
| **CPU `business-read-worker` (avg / max)** | `97.2%` / `205.5%` | `85.7%` / `147.9%` | **`77.2%` / `142.0%`** | `-8.5` / `-5.9` pts | `-20.0` / `-63.5` pts |
| **Convergence READ (`reconciliationComplete`)** | `True` (`0` violation) | `True` (`0` violation) | **`True` (`0` violation)** | — | — |

Décomposition interne Micrometer sur `L7-4A3/restore-large-1-run2` (`n=64` événements `PotVersion`) :
- `phase="prepare"` : `1.345 s / 64` = **`21.0 ms` avg** (vs `589.0 ms` en L7.4-A2, **`-96.4%`**) :
  - `reference_lookup` ($K$) : `13.5 ms` avg (`64/64` hit, `N − K = 1`)
  - `delta_resolution` : `2.1 ms` avg (`3.0` sous-objets/événement)
  - `compose` : `0.27 ms` avg (`max = 0.87 ms`)
- `phase="finalize"` : `1.977 s / 64` = **`30.9 ms` avg** (vs `433.3 ms` en L7.4-A2, **`-92.9%`**) :
  - `persist_publish` : `30.5 ms` avg (`max = 252.8 ms`, incluant les `3×` décodages `PotVersionJson` conservés par D3)

### 4.2 Pot Moyen — Régime séquentiel et charge 5× (`med-1`, `med-5`, `restore-smoke-medium`)

| Campagne | Métrique | L7.4-A | L7.4-A2 | L7.4-A3 (`N−m`) | Gain vs référence |
|---|---|---:|---:|---:|---:|
| **`med-1`** (`20/200`, `1.0 cmd/s`, `n=60`, `PASS`) | **PotVersion p50 / p95 / p99** | `39.73` / `73.65` / `131.81 ms` | `25.31` / `33.69` / `41.31 ms` | **`9.95` / `13.70` / `21.47 ms`** | **`-60.7%` / `-59.3%` / `-48.0%`** (vs A2) |
| **`med-1`** | **E2E `C-03` p50 / p95** | `125.11` / `204.01 ms` | `109.84` / `153.35 ms` | **`102.29` / `138.81 ms`** | `-6.9%` / `-9.5%` (vs A2) |
| **`med-5`** (`20/200`, `5.0 cmd/s`, `n=150`, `PASS`) | **PotVersion p50 / p95 / p99** | `49.45` / `91.98` / `147.18 ms` | — | **`9.72` / `15.43` / `32.45 ms`** | **`-80.3%` / `-83.2%` / `-78.0%`** (vs A) |
| **`med-5`** | **E2E `C-03` p50 / p95 / p99** | `132.50` / `210.19` / `305.04 ms` | — | **`100.58` / `169.81` / `198.64 ms`** | **`-24.1%` / `-19.2%` / `-34.9%`** (vs A) |

### 4.3 Campagne backlog / hors-ordre (seeding parallèle `med-1` et `med-5`)

Pendant les phases de seeding parallèle (`5` pots × `202` versions = `1 010` événements simultanés sur `med-1` ; `1 175` événements cumulés sur `med-5`), la concurrence inter-partitions produit des matérialisations hors ordre :

| Campagne (phase backlog / charge) | Événements `PotVersion` | Chemin `FULL` (`initial_version`) | Chemin `INCREMENTAL` (`incremental_hit`) | Distance `N − K = 1` | Distance `N − K = 2` | Autres fallbacks |
|---|---:|---:|---:|---:|---:|---:|
| **`med-1` (seeding parallèle)** | `1 010` | `5` (`N = 1`) | **`1 005` (`100%` hors `N=1`)** | `973` | **`32`** | `0` |
| **`med-5` (seeding + `5 cmd/s`)** | `1 175` | `5` (`N = 1`) | **`1 170` (`100%` hors `N=1`)** | `1 137` | **`33`** | `0` |
| **`restore-large-1-run2`** | `64` | `0` | **`64` (`100%`)** | `64` | `0` | `0` |
| **`restore-smoke-medium`** | `12` | `0` | **`12` (`100%`)** | `12` | `0` | `0` |

**Enseignement clé** : les `32` (`med-1`) et `33` (`med-5`) événements matérialisés hors ordre avec `N − K = 2` auraient tous basculé en calcul intégral sous un modèle `N−1` strict ; le modèle `N−m` les a tous absorbés sur le chemin incrémental (`0` fallback non initial).

---

## 5. Limites, risques résiduels et suite recommandée

1. **`MaterializePotBalance` est désormais le goulot dominant sur Grand Pot** (`381.61 ms` p50 vs `34.96 ms` pour `PotVersion`, soit `75%` de l'E2E `C-03` à `511 ms`).
2. **Coût résiduel de `PotVersion`** : `~30.9 ms` en `finalize` (`3×` décodages `PotVersionJson` non optimisés par arbitrage D3) et `~13.5 ms` en `reference_lookup` (`PotVersionJson.decode` sur $P_K$).
3. **Calibrage de `maxDeltaDistance = 64`** : suffisant sur toutes les campagnes testées (`max` observé `= 2`), reste un paramètre configurable à réévaluer lors d'une reconstruction complète (`v3-rebuild-read`) sur très grand historique.
4. **Conditions mono-hôte Docker** : mesures locales reproductibles, non contractuelles pour la certification multi-nœuds Config A/B.

---

## 6. Verdict final

**`N_MINUS_M_QUALIFIED`** — L'implémentation `N−m` respecte tous les arbitrages D1–D4 et les règles d'architecture hexagonale, passe intégralement la slice canonique `PROJECTION` (`111/111`) et l'oracle intégral sur PostgreSQL 17, et démontre un gain de **`-96.6%` (`29.5×`)** sur `PotVersion` p50 Grand Pot (`1 031.5 ms` → `34.96 ms`) et **`-60.4%`** sur E2E `C-03` p95 (`1 549.6 ms` → `613.8 ms`), avec `0` drop et `0` violation de convergence.
