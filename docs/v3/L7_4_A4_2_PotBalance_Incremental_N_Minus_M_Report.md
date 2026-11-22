# Rapport L7.4-A4.2 — Implémentation et qualification fonctionnelle du calcul incrémental `N−m` de `PotBalance`

- **Date** : 19 novembre 2026
- **Branche courante** : `feat/l7-4-a3-potversion-incremental-nm` (parent HEAD `e8125bc9`)
- **Documents de référence** :
  - [`PotBalance_Incremental_Materialization_Design_Plan.md`](PotBalance_Incremental_Materialization_Design_Plan.md)
  - [`L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md`](L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md)
  - [`L7_4_A3_PotVersion_Incremental_N_Minus_M_Report.md`](L7_4_A3_PotVersion_Incremental_N_Minus_M_Report.md)
  - [`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Verdict final A4.2** : **`A4_2_QUALIFIED`**

---

## 1. Synthèse de l'implémentation (Décisions D1–D6)

L'ensemble des décisions D1–D6 a été appliqué sans dérogation :

| Décision | Implémentation réalisée |
|---|---|
| **D1 — Indices canoniques & dictionnaire explicite** | Codec [`PotBalanceAggregateJson.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/PotBalanceAggregateJson.java) (schéma JSONB v1) encodant `s` (dictionnaire trié par `SubobjectKey::compareUuid`) et `b` (buckets référençant les shareholders par indices entiers `0..S-1`). Décodage strict (détection de doublons, rejet des fractions non canoniques, validation complète des invariants de conservation). |
| **D2 — Extension de `PotBalanceStore`** | Ajout de `readLatestAggregateBefore`, `insertOrVerifyAggregate` et `requireAggregateStored` sur [`PotBalanceStore.java`](../../app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/PotBalanceStore.java) et implémentation dans [`JdbcPotBalanceStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcPotBalanceStore.java). |
| **D3 — Recalcul à partir de tous les buckets** | Évaluation autonome $\text{PotBalance}(N) = \text{Eval}(\mathcal{A}(N), w(N))$ via `PotBalanceAggregateCalculator.calculate(composed)` sans dépendance de lecture à `read_pot_balance(K)`. |
| **D4 — Fallback intégral sur `DeleteShareholder`** | `JdbcPotBalanceSource.financialDelta` détecte immédiatement toute révision `v3_shareholder_header.deleted = true` sur $(K, N]$ et retourne `shareholderDeleted = true` sans lire les dépenses ni participants ; `MaterializePotBalance` bascule vers le chemin intégral (`Reason.SHAREHOLDER_DELETED`) et persiste $\mathcal{A}(N)$ pour les versions suivantes. |
| **D5 — Seuils configurables (`64 / 64 / 256 / 10 000`)** | Record `MaterializePotBalance.Settings` câblé dans [`MaterializeSettings.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeSettings.java) via `pocoma.v3.worker.pot-balance.{max-delta-distance,max-changed-expenses,max-aggregate-buckets,max-aggregate-bucket-entries}` avec défauts `64 / 64 / 256 / 10 000`. |
| **D6 — Agrégats append-only et immuables** | Migration additive [`V2__pot_balance_aggregate.sql`](../../app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql) dans `v3_read` : table `read_pot_balance_aggregate` avec PK `(pot_id, pot_version)`, FK vers `read_pot_balance(pot_id, pot_version)`, triggers `ENABLE ALWAYS` interdisant `UPDATE`/`DELETE`/`TRUNCATE`, privilèges runtime stricts `SELECT, INSERT`, aucun accès `PUBLIC` ni `http-read`. |

---

## 2. Architecture et flux d'exécution

### 2.1 Persistance additive et reconstruction READ

- **Migration additive** : [`V2__pot_balance_aggregate.sql`](../../app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql) s'applique proprement tant sur une base vierge (`V1 → V2`) que sur une base déjà peuplée de lignes `read_pot_balance` sans agrégats.
- **Validation des privilèges au démarrage** : `read_pot_balance_aggregate` enregistrée dans `READ_UPDATES` (colonnes modifiables = `Set.of()`) de [`BootstrapGuard.java`](../../app/infra-postgres-core/src/main/java/com/kartaguez/pocoma/infra/postgres/core/bootstrap/BootstrapGuard.java).
- **Reconstruction READ** : `read_pot_balance_aggregate` ajoutée à `CANONICAL_MIGRATION_TABLES` dans [`v3-rebuild-read.py`](../../scripts/operations/v3-rebuild-read.py).
- **Atomicité & fencing** : en `FINALIZE` (transaction `READ_COMMITTED` sous `slots.protectFinal`), `store.insertOrVerify(balance)` précède `store.insertOrVerifyAggregate(aggregate)` (respect de la FK), suivi de `completion.complete()` (`requireCompletionReady()` vérifiant balance et agrégat avant suppression du slot) puis `store.closePublication()`.

### 2.2 Algorithme incrémental `N−m` et fallback

Dans [`MaterializePotBalance.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalance.java) et [`JdbcPotBalanceSource.java`](../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/JdbcPotBalanceSource.java) :

1. `eventRef(eventId)` résout `(potId, N, potDeleted)` dans `v3_write`. Si `potDeleted == true` (version terminale `D`), court-circuit immédiat (`Optional.empty()`, aucune balance ni agrégat).
2. Recherche du dernier $K \in [\max(1, N - M_{\max}), N)$ dans `read_pot_balance_aggregate` par `Index Scan Backward` (`LIMIT 1`). Aucune attente de `N−1`, aucune modification de `RoutingV1` ni `JdbcSlotKeyNavigation`.
3. `financialDelta(potId, K, N, maxChangedExpenses)` ne lit que les 4 tables financières (`v3_shareholder_header`, `v3_shareholder_weight`, `v3_expense_header`, `v3_expense_financials`) sur $(K, N]$.
   - Si aucune dépense n'a changé (`UpdateShareholderWeights`, `AddShareholders`, `Update*Details`), **0 requête** sur l'état à $K$ et **0 requête** sur `v3_expense_participant`.
   - Si $1 \le |E_\Delta| \le \text{maxChangedExpenses}$, chargement de l'état à $K$ uniquement pour les `object_id` modifiés puis **1 requête groupée** sur `v3_expense_participant` (`financial_revision_id = ANY(:ids)`).
   - Les révisions intermédiaires (`active_to <= N`) sont exclues par les bornes SQL.
4. `PotBalanceAggregateCalculator.applyDelta(...)` retire les contributions de $K$, ajoute celles de $N$, applique les poids courants et ré-indexe les nouveaux shareholders.
5. Si $G(N) \le 256$ et entrées $\le 10\,000$, évaluation de `PotBalance(N)` depuis les agrégats et publication atomique balance + agrégat.
6. **Fallback intégral** : déclenché sur `N == 1`, `maxDeltaDistance == 0`, absence de $K$ dans la fenêtre, `shareholderDeleted` (D4), $|E_\Delta| > 64$, ou dépassement des bornes de cardinalité (`BUCKET_CAP`, auquel cas seule `read_pot_balance` est persistée).
7. **Reprise (`prepareRecovery`)** : conservée intégralement sur l'oracle `PotBalanceCalculator` sans dépendance à `read_pot_balance_aggregate`.
8. **Corruption / erreurs** : toute `IllegalStateException("Corrupt PotBalanceAggregate")`, `InconsistentPotFactsException` ou erreur SQL/TX se propage explicitement, sans jamais déclencher de fallback silencieux.

### 2.3 Observabilité (`MicrometerPotBalanceObservation`)

Instrumentation légère à faible cardinalité enregistrée dans [`MicrometerPotBalanceObservation.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MicrometerPotBalanceObservation.java) :
- `pocoma.v3.potbalance.aggregate.lookup` (`outcome=hit|miss|corrupt`) et `.duration`
- `pocoma.v3.potbalance.reference.distance` (`N − K`)
- `pocoma.v3.potbalance.delta.expenses` et `pocoma.v3.potbalance.delta.duration`
- `pocoma.v3.potbalance.aggregate.buckets`, `pocoma.v3.potbalance.aggregate.entries` et `pocoma.v3.potbalance.compose.duration`
- `pocoma.v3.potbalance.evaluate.duration` (`path=incremental|full`)
- `pocoma.v3.potbalance.persist.duration` (`target=balance|aggregate`)
- `pocoma.v3.potbalance.materialize.path` (`path=incremental|full`, `reason=...`)

---

## 3. Qualification fonctionnelle et politique Reactor

### Portée déclarée (`Reactor_Verification_Policy.md`)

- **Slice primaire** : `PROJECTION` (`./mvnw -pl runtime-worker-materialize -am clean test`)
- **Slices secondaires** : `NONE`
- **Traversée de slice inattendue** : `NONE` (`BootstrapGuard.READ_UPDATES` dans `infra-postgres-core` n'est évalué que pour `Authority.READ` et fait partie de la fermeture amont de `PROJECTION`)
- **Global architecture gate** : `RUN` (`HexagonalArchitectureTest` + `ConsumptionEngineArchitectureTest`)
- **Full reactor** : `NOT REQUIRED` (non exécuté)
- **Vérification base de données** : `CURRENT` (PostgreSQL 17 via Testcontainers : installation vierge `V1+V2`, migration additive `V1→V2` sur données existantes, contraintes FK/CHECK, triggers `ENABLE ALWAYS`, privilèges minimaux, reconstruction READ complète)

### Commandes exécutées et résultats

| Commande exécutée (depuis `app/`) | Résultat | Détail |
|---|---|---|
| `./mvnw -pl engine-materialize-pot -am clean test` | **`PASS`** | `9/9` modules ; `engine-materialize-pot` `7/7` (dont `MaterializePotBalanceIncrementalTest` `2/2` : `N−m`, changements de poids, seuils D5, D4, `BUCKET_CAP`, corruption sans fallback, `prepareRecovery`). |
| `./mvnw -pl runtime-worker-materialize -am -Dtest=PotBalanceReadStorePostgresTest,PotVersionReadStorePostgresTest,PotBalanceIncrementalPostgresTest -Dsurefire.failIfNoSpecifiedTests=false clean test` | **`PASS`** | `PotBalanceReadStorePostgresTest` `4/4` (FK, triggers `UPDATE/DELETE/TRUNCATE`, privilèges runtime, codec JSONB, migration additive `V1→V2`) ; `PotVersionReadStorePostgresTest` `4/4` ; `PotBalanceIncrementalPostgresTest` `2/2` (14 versions WRITE réelles hors ordre et concurrentes, poids rétroactifs à `0` dépense relue, D1 ré-indexation, D4 `DeleteShareholder`, terminal `D`, idempotence, `BUCKET_CAP`, équivalence stricte `PotBalanceCalculator`). |
| `./mvnw -pl runtime-worker-materialize -am clean test` | **`PASS`** | Slice canonique `PROJECTION` complète : **`35/35`** modules, **`113/113`** tests dans `runtime-worker-materialize` (incluant `ReadReconstructionPostgresTest`, `ReadRuntimeAssemblyPostgresTest`, `ReadParticipantsIntegrationPostgresTest`, `SubobjectMigrationPostgresTest`, `ReadFacadesPostgresTest`), `0` échec, `0` erreur. |
| `./mvnw -pl architecture-tests -am -Dtest=HexagonalArchitectureTest,ConsumptionEngineArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false clean test` | **`PASS`** | **`43/43`** modules compilés ; `ConsumptionEngineArchitectureTest` `11/11`, `HexagonalArchitectureTest` `15/15`, `0` violation. |

---

## 4. Fichiers ajoutés / modifiés en A4.2

| Module / Dossier | Fichier | Action |
|---|---|---|
| `docs/v3` | [`PotBalance_Incremental_Materialization_Design_Plan.md`](PotBalance_Incremental_Materialization_Design_Plan.md) | Suivi dans Git (§13) |
| `docs/v3` | [`L7_4_A4_2_PotBalance_Incremental_N_Minus_M_Report.md`](L7_4_A4_2_PotBalance_Incremental_N_Minus_M_Report.md) | Créé |
| `port-projection` | [`PotBalanceStore.java`](../../app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/PotBalanceStore.java) | Étendu (D2) |
| `engine-materialize-pot` | [`PotBalanceSource.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/PotBalanceSource.java) | Étendu (`EventRef`, `FinancialDelta`) |
| `engine-materialize-pot` | [`PotBalanceObservation.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/PotBalanceObservation.java) | Créé |
| `engine-materialize-pot` | [`MaterializePotBalance.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalance.java) | Algorithme deux chemins `N−m` |
| `infra-postgres-core` | [`BootstrapGuard.java`](../../app/infra-postgres-core/src/main/java/com/kartaguez/pocoma/infra/postgres/core/bootstrap/BootstrapGuard.java) | Enregistrement `read_pot_balance_aggregate` dans `READ_UPDATES` |
| `infra-postgres-read` | [`V2__pot_balance_aggregate.sql`](../../app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql) | Créé (migration additive `v3_read`) |
| `infra-postgres-read` | [`PotBalanceAggregateJson.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/PotBalanceAggregateJson.java) | Créé (codec JSONB D1) |
| `infra-postgres-read` | [`JdbcPotBalanceStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcPotBalanceStore.java) | Étendu (D2/D6) |
| `infra-postgres-write-source-read` | [`JdbcPotBalanceSource.java`](../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/JdbcPotBalanceSource.java) | Implémentation `eventRef` & `financialDelta` |
| `compose-polling-nonburnable-materialize-postgres` | [`MicrometerPotBalanceObservation.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MicrometerPotBalanceObservation.java), [`MaterializeSettings.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeSettings.java), [`ReadParticipants.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/ReadParticipants.java), [`MaterializeComposeConfiguration.java`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeComposeConfiguration.java) | Câblage D5 & Micrometer |
| `scripts/operations` | [`v3-rebuild-read.py`](../../scripts/operations/v3-rebuild-read.py) | Ajout `read_pot_balance_aggregate` à `CANONICAL_MIGRATION_TABLES` |
| Tests | [`MaterializePotBalanceIncrementalTest.java`](../../app/engine-materialize-pot/src/test/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalanceIncrementalTest.java), [`PotBalanceReadStorePostgresTest.java`](../../app/infra-postgres-read/src/test/java/com/kartaguez/pocoma/infra/postgres/read/PotBalanceReadStorePostgresTest.java), [`ReadStoreFixture.java`](../../app/infra-postgres-read/src/test/java/com/kartaguez/pocoma/infra/postgres/read/ReadStoreFixture.java), [`PotVersionReadStorePostgresTest.java`](../../app/infra-postgres-read/src/test/java/com/kartaguez/pocoma/infra/postgres/read/PotVersionReadStorePostgresTest.java), [`SubobjectPostgresFixture.java`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/SubobjectPostgresFixture.java), [`PotBalanceIncrementalPostgresTest.java`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/PotBalanceIncrementalPostgresTest.java) | Tests unitaires, PostgreSQL 17 et fixtures migration |

---

## Verdict final A4.2

**`A4_2_QUALIFIED`** — Tous les critères de sortie de A4.2 sont satisfaits sans réserve : équivalence rationnelle exacte contre `PotBalanceCalculator`, persistance immuable additive `V2__pot_balance_aggregate.sql` vérifiée sur PostgreSQL 17, atomicité et fencing préservés, gestion complète des versions hors ordre, des changements rétroactifs de poids (`0` dépense relue), des suppressions logiques et de la reconstruction READ, slice `PROJECTION` (`35/35`, `113/113`) et tests d'architecture (`26/26`) intégralement verts. Poursuite autonome vers la phase **A4.3 (Qualification de performance)**.
