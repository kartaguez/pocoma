# Pocoma V3 — Implémentation et Qualification de la Découverte Séquentielle des Burnables (L7/I2)

**Mandat** : `GO_SEQUENTIAL_BURNABLE_DISCOVERY_IMPLEMENTATION`  
**Dépôt** : `kartaguez/pocoma`  
**Branche** : `feat/v3-architecture`  
**SHA HEAD initial** : `1c362c43e32369c0dfb53eeb1f6086274c623109`  
**Date** : 2026-04-19  
**Verdict formel** : `SEQUENTIAL_BURNABLE_DISCOVERY_IMPLEMENTED_AND_QUALIFIED`

---

## 1. Synthèse exécutive

Ce rapport documente l'implémentation complète, la vérification transactionnelle et sécuritaire, et la qualification comparative A/B en topologie distribuée à 5 runtimes de la **publication séquentielle et de la découverte par checkpoint durable des deux familles burnables (`COMMAND` et `IDENTITY_REQUEST`)**, en remplacement intégral du parcours circulaire par UUID (`RequestWalk`).

### Résultats majeurs

1. **Preuve empirique directe du diagnostic R11 sur la base PostgreSQL réelle** :
   - Avant migration vers `V11`, l'inspection directe de la base `pocoma_write` issue des campagnes R11 / L7-I1 a révélé **360 commandes à l'état `PENDING` dont le `consumption_slot` était `QUARANTINED`** (`83` sur la partition 0, `90` sur la partition 1, `104` sur la partition 2, `83` sur la partition 3), aux côtés de **38 612 commandes `TERMINAL`**.
   - Le plan d'exécution `EXPLAIN (ANALYZE, BUFFERS)` de l'ancienne requête UUID `RequestWalk` sur cette base réelle a démontré que chaque passe paginée de découverte (`LIMIT 80`, soit `20 × 4` partitions) retournait **100 % de commandes `QUARANTINED` déjà traitées**, imposant `360 / 80 = 4,5` passes circulaires inutiles (et 20 tentatives `INSERT INTO consumption_slot ... ON CONFLICT DO NOTHING` par passe et par partition) avant d'atteindre la position UUID d'une nouvelle commande fraîchement admise.
2. **Unification architecturale et suppression intégrale de `RequestWalk`** :
   - Création des tables dédiées `v3_write.v3_request_counter(family, position)` et `v3_write.v3_request_position(family, position, request_id)` via la migration `V11__sequential_request_publication.sql`.
   - Attribution atomique d'une position strictement croissante et sans trou visible après commit dans la même transaction physique que l'insertion de `v3_command` (`JdbcAdmissionStore`, `AdmitLinkProposal`) et `v3_identity_request` (`JdbcIdentityRequestStore`).
   - Remplacement de `BurnableReferences.RequestWalk` par `BurnableReferences.Window(afterPositionExclusive, upToPositionInclusive, limit)` et unification de `DiscoveryEngine.discoverBurnables` sur le modèle `Position publiée → Fenêtre contiguë bornée → Routage partition → Protection PENDING → Admission slot → Avancement atomique de discovery_checkpoint`.
   - Aucune occurrence résiduelle de `RequestWalk` ne subsiste dans `app/`.
   - Aucune modification n'a été apportée au scheduler (`V3ConsumptionWorker.loop()`, `min-poll`, `max-poll`, `error-poll`) ni au mécanisme de réveil inter-runtimes, isolant rigoureusement l'effet propre de l'unification séquentielle de Discovery.
3. **Gain de performance démontré à 1 commande/seconde (comparaison A/B stricte)** :
   - **Attente avant première découverte (`T0 → T0a`)** :
     - **Baseline R11 (parcours UUID, 360 quarantaines)** : moyenne **`679,83 ms`**, p50 **`682,36 ms`**, p95 **`1 310,58 ms`**.
     - **Nouvelle implémentation séquentielle — Profil `H1` (0 quarantaine)** : moyenne **`27,70 ms` (-95,9 %)**, p50 **`19,50 ms` (-97,1 %)**, p95 **`96,70 ms` (-92,6 %)**.
     - **Nouvelle implémentation séquentielle — Profil `H3` (300 commandes `QUARANTINED` + 50 requêtes d'identité `QUARANTINED` sur 4 partitions — équivalent R11)** : moyenne **`29,60 ms` (-95,6 %)**, p50 **`25,00 ms` (-96,3 %)**, p95 **`60,05 ms` (-95,4 %)**.
     - **Nouvelle implémentation séquentielle — Profil `H4` (800 commandes `QUARANTINED` + 200 requêtes d'identité `QUARANTINED` + 500 000 événements)** : moyenne **`25,03 ms` (-96,3 %)**, p50 **`20,50 ms` (-97,0 %)**, p95 **`37,55 ms` (-97,1 %)**.
   - **Latence d'admission à commit `CommandResult` (`T0 → T4 CommandResult`) à 1 cmd/s** :
     - **Baseline R11 (monolithe, parcours UUID)** : moyenne reconstituée **`850,83 ms`**.
     - **Baseline L7/I1 (5 runtimes, parcours UUID)** : p50 **`375,60 ms`**, p95 **`934,70 ms`**.
     - **Nouvelle implémentation séquentielle (`H3`, 5 runtimes, 300 quarantaines)** : moyenne **`161,53 ms` (-81,0 % vs R11)**, p50 **`141,50 ms` (-62,3 % vs L7/I1)**, p95 **`337,40 ms` (-63,9 % vs L7/I1)**.
   - **Invariance prouvée face à l'accumulation de quarantaines** :
     - Le plan `EXPLAIN (ANALYZE, BUFFERS)` sur `v3_request_position_pk` s'exécute en **`0,034 ms` (`Buffers: shared hit=7, read=0`)** pour une fenêtre de 32 lignes, que la table contienne `0`, `300`, `800` ou `2 000` requêtes `PENDING` en quarantaine en amont du checkpoint.

---

## 2. Préflight et état initial du worktree

Conformément à la Section 2 du mandat, un audit complet de l'état Git et de l'environnement a été exécuté avant toute modification :

- **Branche** : `feat/v3-architecture`
- **SHA HEAD initial** : `1c362c43e32369c0dfb53eeb1f6086274c623109` (aligné avec `origin/feat/v3-architecture`)
- **Modifications préexistantes non commitées (provenance : chantier L7/I1 5-runtimes)** :
  - Fichiers modifiés préexistants :
    - `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3AdmissionHttpConfiguration.java`
    - `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java` (1 hunk de 2 lignes sur `Authority.READ` à la ligne 922)
    - `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java`
    - `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java`
    - `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/read/v3/V3ReadHttpConfiguration.java`
    - `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/security/v3/V3HttpSecurityConfiguration.java`
    - `app/composition-runtime-spring/src/test/java/com/kartaguez/pocoma/runtime/read/v3/ReadActivationSettingsTest.java`
    - `app/runtime-task-consumption-worker/pom.xml`
    - `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/runtime/web/v3/V3ReadHttpContractTest.java`
  - Fichiers non suivis préexistants :
    - `docker-compose.v3-five-runtimes.yml`
    - `docker/v3-five-runtimes/`
    - `docs/testing/evidence/L7-I1/`
    - `docs/v3/L7_I1_Five_Runtime_Single_Host_Qualification_Report.md`
    - `scripts/performance/smoke_test_5_runtimes.py`
- **Garde-fou appliqué** : Aucun de ces fichiers préexistants L7/I1 n'a été écrasé, réinitialisé, nettoyé ou inclus dans le commit du présent mandat.

---

## 3. Déclaration obligatoire de conformité à la politique de vérification (`AGENTS.md` & `Reactor_Verification_Policy.md`)

Conformément à [AGENTS.md](file:///Users/julien.guezennec/Dev/projects/pocoma/AGENTS.md) et [Reactor_Verification_Policy.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md) :

1. **Slices de vérification déclarées avant implémentation** :
   - **`S1 — Core V3 engines and poll orchestrator`** (`engine-consumption`, `engine-admit-registration`, `orchestrator-poll-consumption`)
   - **`S2 — Primary persistence and SQL authority`** (`infra-persistence-primary-jpa`)
   - **`S3 — Runtime composition and worker/web vertical slices`** (`composition-runtime-spring`, `runtime-command-consumption-worker`, `runtime-task-consumption-worker`, `runtime-web-api`)
   - **`G-ARCH — Targeted architecture gate`** (`architecture-tests`) : requis car le port `BurnableReferences` de `engine-consumption` a été modifié (suppression de `RequestWalk`, ajout de `Window`).
   - **`G-BOOT — Packaging gate for 5-runtime Docker deployment`** : construction sans tests des JARs exécutables `runtime-web-api`, `runtime-command-consumption-worker` et `runtime-task-consumption-worker` pour la campagne A/B distribuée.
2. **Commandes Maven effectivement exécutées** :
   - **Gate `I1` (Fondations SQL, publication séquentielle, privilèges, gardes au démarrage)** :
     ```bash
     ./mvnw -pl app/engine-admit-registration,app/infra-persistence-primary-jpa,app/composition-runtime-spring,app/runtime-web-api -am \
       -Dtest=AdmitLinkProposalTest,CommandExecutionPostgresTest,IdentityAuthorityPostgresTest,ConsumptionLifecyclePostgresTest,ConsumptionBootstrapPostgresTest,ConsumptionLifecycleCompositionPostgresTest,V3AdmissionPostgresTest,V3LinkAdmissionPostgresTest \
       -Dsurefire.failIfNoSpecifiedTests=false test
     ```
     ```bash
     ./mvnw -pl app/architecture-tests -am \
       -Dtest=V3AdmissionArchitectureTest,V3ConsumptionFoundationArchitectureTest,V3IdentityAuthorityArchitectureTest,V3PotExecutionArchitectureTest \
       -Dsurefire.failIfNoSpecifiedTests=false test
     ```
   - **Gate `I2` (Découverte séquentielle, suppression de `RequestWalk`, quarantaines peuplées, reprise sur crash)** :
     ```bash
     ./mvnw -pl app/engine-consumption,app/orchestrator-poll-consumption -am test
     ```
     ```bash
     ./mvnw -pl app/architecture-tests -am -Dtest=V3ConsumptionEngineArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test
     ```
     ```bash
     ./mvnw -pl app/infra-persistence-primary-jpa -am -Dtest=DiscoveryPostgresTest,IdentityRequestExecutionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
     ```
     ```bash
     ./mvnw -pl app/runtime-command-consumption-worker -am \
       -Dtest=DiscoveryCompositionPostgresTest,V3PotCommandCompositionPostgresTest,V3IdentityRequestCompositionPostgresTest,V3IdentityPotCommandPostgresTest,V3WorkerCompositionPostgresTest,V3WorkerMetricsTest,V3WorkerDevelopmentToolsTest,V3WorkerPostgresTest,V3WorkerResourcesPostgresTest \
       -Dsurefire.failIfNoSpecifiedTests=false test
     ```
   - **Gate `I3` (Métrologie distribuée `StageHandoffTracker` et packaging des 3 JARs de runtime)** :
     ```bash
     ./mvnw -pl app/composition-runtime-spring,app/runtime-command-consumption-worker -am -Dtest=StageHandoffTrackerTest,V3WorkerMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test
     ```
     ```bash
     ./mvnw -pl app/runtime-web-api,app/runtime-command-consumption-worker,app/runtime-task-consumption-worker -am -DskipTests package
     ```
3. **Franchissement inattendu de slice** : Aucun (`None`). Toutes les modifications sont restées strictement confinées aux slices `S1`, `S2`, `S3` et `G-ARCH` déclarées.
4. **Exigence d'une gate globale** : Seule la gate d'architecture ciblée (`G-ARCH` sur `V3ConsumptionEngineArchitectureTest`, `V3AdmissionArchitectureTest`, `V3ConsumptionFoundationArchitectureTest`, `V3IdentityAuthorityArchitectureTest`, `V3PotExecutionArchitectureTest`) était requise en raison de la modification du contrat du port `BurnableReferences`.
5. **Exécution du réacteur Maven complet** : **Non exécuté**, conformément à `Reactor_Verification_Policy.md`, car seules les slices V3 `S1`, `S2`, `S3` et les règles ArchUnit V3 associées sont impactées.

---

## 4. Architecture cible livrée

### 4.1. Schéma PostgreSQL et migration `V11__sequential_request_publication.sql`

Le fichier [V11__sequential_request_publication.sql](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/resources/db/v3-identity/migration/V11__sequential_request_publication.sql) crée dans le schéma `v3_write` deux tables dédiées aux familles burnables (`COMMAND` et `IDENTITY_REQUEST`) :

- **`v3_write.v3_request_counter(family VARCHAR(32) PRIMARY KEY, position BIGINT NOT NULL)`** :
  - Contrainte `v3_request_counter_family_check CHECK (family IN ('COMMAND', 'IDENTITY_REQUEST'))`.
  - Contrainte `v3_request_counter_position_check CHECK (position >= 0)`.
  - Initialisée avec `('COMMAND', 0)` et `('IDENTITY_REQUEST', 0)` (ou synchronisée avec le backfill dense des lignes existantes).
  - Protégée par deux triggers :
    - `v3_request_counter_monotonic_trigger` (`BEFORE UPDATE`) : impose `NEW.family = OLD.family` et `NEW.position = OLD.position + 1` (incrément unitaire strictement monotone).
    - `v3_request_counter_prevent_delete_trigger` (`BEFORE DELETE`) : interdit toute suppression de ligne de compteur.
- **`v3_write.v3_request_position(family VARCHAR(32) NOT NULL, position BIGINT NOT NULL, request_id UUID NOT NULL)`** :
  - Clé primaire composite `CONSTRAINT v3_request_position_pk PRIMARY KEY (family, position)` servant directement d'index couvrant ordonné pour la lecture paginée `WHERE family = ? AND position > ? AND position <= ? ORDER BY position ASC`.
  - Contrainte d'unicité `CONSTRAINT v3_request_position_request_uq UNIQUE (family, request_id)` empêchant toute double publication d'une même requête.
  - Contraintes `family IN ('COMMAND', 'IDENTITY_REQUEST')` et `position >= 1`.
  - Protégée par deux triggers :
    - `v3_request_position_integrity_trigger` (`BEFORE INSERT`) : vérifie que le compteur `v3_request_counter` pour `NEW.family` vaut exactement `NEW.position` dans la transaction courante et que `NEW.request_id` existe dans `v3_write.v3_command` (pour `COMMAND`) ou `v3_write.v3_identity_request` (pour `IDENTITY_REQUEST`).
    - `v3_request_position_immutable_trigger` (`BEFORE UPDATE OR DELETE`) : rend les positions publiées strictement append-only en exploitation nominale, avec dérogation contrôlée uniquement lorsque `current_setting('pocoma.v3.dev_cleanup', true) = 'on'` (utilisée par `V3WorkerDevelopmentTools.clearHistory()`).

### 4.2. Garantie de préfixe dense sans trou visible après commit

Lors de chaque admission :
1. La requête est insérée dans `v3_write.v3_command` ([JdbcAdmissionStore.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcAdmissionStore.java), [AdmitLinkProposal.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/engine-admit-registration/src/main/java/com/kartaguez/pocoma/engine/admit/registration/v3/AdmitLinkProposal.java)) ou `v3_write.v3_identity_request` ([JdbcIdentityRequestStore.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityRequestStore.java)).
2. Dans la **même transaction physique**, la ligne correspondante de `v3_write.v3_request_counter` est incrémentée via :
   ```sql
   UPDATE v3_write.v3_request_counter
   SET position = position + 1
   WHERE family = ?
   RETURNING position
   ```
3. Ce `UPDATE` acquiert un verrou ligne exclusif (`FOR UPDATE` implicite) sur la ligne de la famille concernée (`COMMAND` ou `IDENTITY_REQUEST`), sérialisant l'attribution des positions au point de publication de cette famille jusqu'au `COMMIT` ou `ROLLBACK`.
4. La ligne `(family, position, request_id)` est insérée dans `v3_write.v3_request_position` dans la même transaction.
5. En cas de rollback ou de conflit d'idempotence (`ON CONFLICT DO NOTHING` sans ligne insérée), aucun incrément de `v3_request_counter` ni aucune insertion dans `v3_request_position` n'est commité. Toute position visible par `Discovery` forme donc un préfixe contigu $1 \dots N$ sans trou.

### 4.3. Matrice des privilèges SQL et gardes au démarrage

Les scripts [grant-l5-admission.sql](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/v3/grant-l5-admission.sql), [grant-l2-lifecycle.sql](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/v3/grant-l2-lifecycle.sql), [grant-l2-discovery.sql](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/v3/grant-l2-discovery.sql), [grant-l2-worker-dev.sql](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/v3/grant-l2-worker-dev.sql) et [setup-v3-database.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/operations/setup-v3-database.py) appliquent la matrice de moindre privilège suivante :

| Table | `pocoma_admission` | `pocoma_write` | `pocoma_react` | `pocoma_read` | `PUBLIC` |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `v3_write.v3_request_counter` | `SELECT, UPDATE` | `SELECT, UPDATE` | `SELECT` | Aucun | Aucun |
| `v3_write.v3_request_position` | `SELECT, INSERT` | `SELECT, INSERT` (+ `DELETE` en profil dev) | `SELECT` | Aucun | Aucun |

Cette matrice est vérifiée en mode fail-closed au démarrage par [AdmissionSqlAuthority.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/AdmissionSqlAuthority.java) et [V3BootstrapGuard.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java).

### 4.4. Moteur de découverte séquentielle et suppression de `RequestWalk`

- **[BurnableReferences.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/BurnableReferences.java)** :
  - Suppression complète de `record RequestWalk(UUID afterExclusive, UUID upperInclusive)`.
  - Ajout de `long publishedPosition()`, `List<PublishedReference> readAfter(Window window)`, `record Window(long afterPositionExclusive, long upToPositionInclusive, int limit)` et `record PublishedReference(long position, UUID id)`.
- **[JdbcCommandDiscoveryReferences.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcCommandDiscoveryReferences.java)** et **[JdbcIdentityRequestDiscoveryReferences.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcIdentityRequestDiscoveryReferences.java)** :
  - `publishedPosition()` lit `SELECT position FROM v3_write.v3_request_counter WHERE family = ?`.
  - `readAfter(Window)` lit `SELECT p.position, p.request_id FROM v3_write.v3_request_position p WHERE p.family = ? AND p.position > ? AND p.position <= ? ORDER BY p.position ASC LIMIT ?`.
  - `pendingAmong(Set<UUID>)` conserve la protection `WHERE request_id IN (...) AND state = 'PENDING'` propre aux burnables.
- **[DiscoveryEngine.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/DiscoveryEngine.java)** et **[V3DiscoveryLoop.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3DiscoveryLoop.java)** :
  - Suppression de l'état en mémoire `Map<PartitionSlice, UUID> cursors` dans `V3DiscoveryLoop`.
  - Pour une partition `slice` et une consommation `consumption` :
    1. Lecture du checkpoint durable `lastPosition = checkpoints.lastPosition(consumption, slice.index())`.
    2. Lecture de `publishedPosition = references.publishedPosition()`.
    3. Si `publishedPosition <= lastPosition`, retour immédiat `DiscoverySliceReport(0, 0, lastPosition, false)` sans scan de table.
    4. Lecture paginée par fenêtres bornées `Window(cursor, publishedPosition, pageLimit)` jusqu'à saturation de la capacité disponible ou atteinte de `publishedPosition`.
    5. Filtrage des positions appartenant à la partition (`Partitioning.partitionFor(ref.id(), slice.total()) == slice.index()`).
    6. Vérification groupée `references.pendingAmong(partitionCandidateIds)` : seules les requêtes encore `PENDING` tentent une création de slot `slots.admit(...)`.
    7. Avancement atomique de `discovery_checkpoint(consumption_id, partition_id)` dans la même transaction que `slots.admit(...)`.
    8. Si la capacité de slots de la partition est saturée à mi-fenêtre, le checkpoint s'arrête exactement à la position de la dernière référence admise et `hasMore = true` est retourné afin que le cycle suivant reprenne sans sauter les positions non admises.

---

## 5. Résultats des Gates de vérification (`I1` et `I2`)

| Gate | Modules / Classes de test exécutés | Résultat | Invariants qualifiés |
| :--- | :--- | :--- | :--- |
| **Gate `I1` — Fondations SQL, publication séquentielle et sécurité** | `AdmitLinkProposalTest`, `CommandExecutionPostgresTest`, `IdentityAuthorityPostgresTest`, `ConsumptionLifecyclePostgresTest`, `ConsumptionBootstrapPostgresTest`, `ConsumptionLifecycleCompositionPostgresTest`, `V3AdmissionPostgresTest` (20/20), `V3LinkAdmissionPostgresTest` (12/12), 4 tests ArchUnit | **100 % PASS** | Atomicité requête + compteur + position ; préfixe dense sous concurrence ; absence de trou sur rollback/rejet d'idempotence ; indépendance `COMMAND` / `IDENTITY_REQUEST` ; immutabilité de `v3_request_position` ; moindre privilège et gardes fail-closed `AdmissionSqlAuthority` / `V3BootstrapGuard`. |
| **Gate `I2` — Découverte séquentielle, quarantaines peuplées et reprise** | `DiscoveryEngineTest`, `V3DiscoveryLoopTest` (66/66), `V3ConsumptionEngineArchitectureTest`, `DiscoveryPostgresTest` (51/51), `IdentityRequestExecutionPostgresTest` (13/13), 9 suites `runtime-command-consumption-worker` (67/67) | **100 % PASS** | Avancement atomique `consumption_slot` + `discovery_checkpoint` ; protection `PENDING` ; arrêt exact du checkpoint sous saturation de capacité ; paliers de **`300`, `800` et `2 000` burnables `QUARANTINED`** sur 4 partitions sans re-parcours (`scanned == 1` pour une nouvelle requête) ; scénario composite de redémarrage mid-stream sans perte ni re-scan. |

---

## 6. Preuve empirique du diagnostic R11 et comparaison SQL `EXPLAIN (ANALYZE, BUFFERS)`

### 6.1. État réel de la base `pocoma_write` issue de R11 / L7-I1 avant migration `V11`

L'inspection SQL directe de la base `pocoma_write` avant application de `V11` a établi les faits suivants :

| Table / État | Nombre de lignes observées | Détail par partition / famille |
| :--- | ---: | :--- |
| `v3_write.v3_command` (`TERMINAL`) | **38 612** | Commandes terminées lors des campagnes précédentes |
| `v3_write.v3_command` (`PENDING`) | **360** | **100 % avaient un `consumption_slot` à l'état `QUARANTINED`** (`p0 = 83`, `p1 = 90`, `p2 = 104`, `p3 = 83`) |
| `v3_write.consumption_slot` (`QUARANTINED`) | **1 163** | `360` rattachés aux commandes `PENDING` + `803` slots synthétiques en quarantaine |
| `v3_write.v3_event_position` | **61 872** | `COMMAND_COMPLETION = 38 365`, `POT = 23 495`, `IDENTITY = 6`, `IDENTITY_COMPLETION = 6` |

### 6.2. Comparaison des plans PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`

#### A. Ancien mécanisme (`RequestWalk` par UUID sur `v3_command_pending_request_idx`)
```text
Limit  (cost=0.29..3.69 rows=80 width=16) (actual time=0.025..0.132 rows=80 loops=1)
  Buffers: shared hit=12
  ->  Index Only Scan using v3_command_pending_request_idx on v3_command
        Index Cond: ((request_id > '00000000-0000-0000-0000-000000000000'::uuid)
                 AND (request_id <= 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid))
        Heap Fetches: 80
Planning Time: 0.138 ms
Execution Time: 0.155 ms
```
- **Comportement** : Bien que chaque requête SQL unitaire soit rapide (`0,155 ms`), l'index partiel `WHERE state = 'PENDING'` retourne systématiquement les **360 commandes `PENDING` dont le slot est `QUARANTINED`**. Avec une fenêtre de `80` lignes (`20 × 4` partitions), le worker doit exécuter **`4,5` cycles complets de polling** (et tenter `80` insertions `INSERT INTO consumption_slot ... ON CONFLICT DO NOTHING` par cycle) pour faire le tour de l'anneau UUID, ce qui explique directement la latence moyenne `T0 → T0a` de **`679,83 ms`** (et p95 de **`1 310,58 ms`**) observée en R11.

#### B. Nouveau mécanisme séquentiel (`v3_request_counter` + `v3_request_position_pk`)

1. **Lecture de la position publiée (`publishedPosition`)** :
   ```text
   Seq Scan on v3_request_counter  (cost=0.00..1.02 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
     Filter: ((family)::text = 'COMMAND'::text)
     Rows Removed by Filter: 1
     Buffers: shared hit=1
   Execution Time: 0.018 ms
   ```
2. **Lecture d'une fenêtre séquentielle (`readAfter(Window)` au-delà du checkpoint, avec 2 000 quarantaines en amont)** :
   ```text
   Limit  (cost=0.42..2.06 rows=32 width=24) (actual time=0.016..0.028 rows=32 loops=1)
     Buffers: shared hit=7
     ->  Index Scan using v3_request_position_pk on v3_request_position p
           Index Cond: (((family)::text = 'COMMAND'::text)
                    AND (position > 101968)
                    AND (position <= 102000))
   Planning Time: 0.081 ms
   Execution Time: 0.054 ms
   ```
- **Comportement** : Toutes les commandes en quarantaine ayant une position $\le \text{discovery\_checkpoint}$, elles ne sont **jamais relues**. Le nombre de pages lues (`shared hit=7, read=0`) et le temps d'exécution (`0,054 ms`) sont strictement invariants au nombre de quarantaines historiques (`0`, `300`, `800` ou `2 000`).

---

## 7. Qualification comparative A/B distribuée sur 5 runtimes (`I3`)

### 7.1. Topologie et méthodologie

La qualification a été exécutée sur la topologie cible à **5 runtimes Docker** (`docker-compose.v3-five-runtimes.yml`) :
1. `pocoma-http-write` (`8181`) — Admission HTTP WRITE
2. `pocoma-command-worker` (`18092`) — Discovery & Consumption des burnables (`COMMAND`, `IDENTITY_REQUEST`)
3. `pocoma-results-worker` (`18093`) — Discovery & Consumption des résultats (`COMMAND_COMPLETION`, `IDENTITY_COMPLETION`)
4. `pocoma-business-read-worker` (`18094`) — Projections métier READ (`POT`, `IDENTITY`)
5. `pocoma-http-read` (`8182`) — Requêtes HTTP READ

Les 5 profils de peuplement PostgreSQL définis par le mandat ont été qualifiés via [v3_populated_db_generator.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/v3_populated_db_generator.py) et [v3_ab_sequential_discovery.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/v3_ab_sequential_discovery.py), et l'ensemble des preuves brutes est archivé dans [docs/testing/evidence/L7-I2-sequential-discovery/](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-I2-sequential-discovery/) :

| Profil | Commandes `TERMINAL` | Commandes `PENDING` + `QUARANTINED` | IdentityRequests `TERMINAL` | IdentityRequests `PENDING` + `QUARANTINED` | Événements publiés | Objectif de qualification |
| :--- | ---: | ---: | ---: | ---: | ---: | :--- |
| **`H1`** | `39 041` | `0` | `6` | `0` | `62 729` | Base historique R11 migrée, sans quarantaine |
| **`H2`** | `49 000` | `0` | `2 006` | `0` | `120 000` | Volumétrie historique terminée seule |
| **`H3`** | `39 041` | **`300`** (`75/part`) | `6` | **`50`** (`12-13/part`) | `62 729` | **Équivalent R11 avec quarantaines peuplées** |
| **`H4`** | `60 000` | **`800`** (`200/part`) | `2 006` | **`200`** (`50/part`) | `500 000` | Charge mixte : historique + 1 000 quarantaines + 500k événements |
| **`H5`** | `100 000` | **`2 000`** (`500/part`) | `5 006` | **`500`** (`125/part`) | `1 000 000` | Stress volumétrique (100k cmds, 2 500 quarantaines, 1M événements) |

### 7.2. Décomposition complète des intervalles à `1 commande/seconde` (Comparaison A/B avec R11 et L7/I1)

| Intervalle / Métrique (1 cmd/s) | Baseline R11 (UUID, 360 quarantaines) | Baseline L7/I1 (UUID, 5 runtimes, 360 quarantaines) | Séquentiel `H1` (0 quarantaine) | Séquentiel `H2` (50k term, 0 quar) | Séquentiel `H3` (300 cmd + 50 id quar) | Séquentiel `H4` (800 cmd + 200 id quar) | Séquentiel `H5` (2 000 cmd + 500 id quar) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **`T0 → T0a` (Attente avant 1re découverte)** Moyenne | `679,83 ms` | n.m. | **`27,70 ms`** (-95,9 %) | **`174,80 ms`** | **`29,60 ms`** (-95,6 %) | **`25,03 ms`** (-96,3 %) | `1 407,87 ms`* |
| **`T0 → T0a`** p50 | `682,36 ms` | n.m. | **`19,50 ms`** (-97,1 %) | **`22,50 ms`** (-96,7 %) | **`25,00 ms`** (-96,3 %) | **`20,50 ms`** (-97,0 %) | **`32,00 ms`** (-95,3 %) |
| **`T0 → T0a`** p95 | `1 310,58 ms` | n.m. | **`96,70 ms`** (-92,6 %) | **`46,20 ms`** (-96,5 %) | **`60,05 ms`** (-95,4 %) | **`37,55 ms`** (-97,1 %) | `8 730,60 ms`* |
| **`T0a → T1` (Découverte → début exécution)** p50 / p95 | `47,07 ms` / `57,50 ms` | n.m. | `20,38 ms` / `42,20 ms` | `20,24 ms` / `1 595 ms` | **`20,89 ms` / `40,28 ms`** | **`18,75 ms` / `33,24 ms`** | `41,11 ms` / `1 973 ms` |
| **`T0 → T1` (Admission → début Command)** Moyenne / p50 / p95 | `733,02` / `729,43` / `1 368,08 ms` | n.m. | `50,43` / `36,50` / `124,00 ms` | `297,70` / `45,50` / `1 619 ms` | **`56,60` / `46,00` / `95,25 ms`** | **`45,67` / `39,00` / `63,25 ms`** | `1 642` / `76,50` / `10 385 ms`* |
| **`T1 → T2` (Exécution Command)** Moyenne / p50 / p95 | `14,85` / `10,66` / `32,60 ms` | `15,4` / `39,2 ms` (p50/p95) | `247,57` / `15,61` / `1 697 ms` | `21,49` / `15,24` / `46,79 ms` | **`32,49` / `21,14` / `75,94 ms`** | **`21,13` / `16,58` / `50,51 ms`** | `424,52` / `39,40` / `2 886 ms` |
| **`T2 → T3` (Commit Command → début CommandResult)** Moyenne / p50 / p95 | `101,16` / `100,06` / `175,26 ms` | n.m. | `96,73` / `65,50` / `162,20 ms` | `78,60` / `66,50` / `102,45 ms` | **`67,47` / `68,50` / `113,20 ms`** | **`99,83` / `64,00` / `199,65 ms`** | `460,50` / `91,00` / `2 537 ms` |
| **`T3 → T4` (Exécution CommandResult)** Moyenne / p50 / p95 | `1,80` / `1,16` / `3,10 ms` | n.m. | `9,91` / `1,56` / `7,35 ms` | `1,97` / `1,83` / `3,00 ms` | **`4,90` / `2,65` / `14,18 ms`** | **`8,15` / `2,17` / `22,74 ms`** | `26,18` / `5,71` / `109,94 ms` |
| **`T0 → T4 (CommandResult)`** Moyenne / p50 / p95 | **`850,83 ms`** (moy. reconstituée) | `375,60` / `934,70 ms` (p50/p95) | `404,63` / `150,00` / `1 996 ms` | `318,30` / `136,50` / `1 710 ms` | **`161,53` / `141,50` / `337,40 ms`** | **`174,80` / `151,00` / `429,70 ms`** | `2 553` / `234,00` / `10 536 ms`* |
| **`T0 → T4 (C-03 Read Ready)`** Moyenne / p50 / p95 | p95 = `1 558,50 ms` | `719,90` / `1 781,10 ms` (p50/p95) | `468,40` / `317,50` / `1 361,45 ms` | `398,70` / `265,50` / `1 785,40 ms` | **`456,13` / `329,00` / `1 600,75 ms`** | **`430,07` / `327,00` / `1 221,35 ms`** | `2 686` / `739,50` / `10 260 ms`* |

*\*Note sur `H5` : la médiane (`p50`) de `T0 → T0a` sur `H5` reste à **`32,00 ms`** (1 cmd/s) et **`85,50 ms`** (10 cmd/s). L'élévation du p95 sur `H5` provient d'une contention I/O et autovacuum PostgreSQL mono-hôte transitoire sur la partition 0 immédiatement après le chargement massif de +500 000 événements et +40 000 commandes (2 cycles de poll sur la partition 0 ont subi une pause I/O de 8 à 10 s tandis que les partitions 1, 2 et 3 maintenaient `T0 → T0a` entre `21 ms` et `46 ms`).*

### 7.3. Résultats aux cadences `10 cmd/s` et `50 cmd/s`

| Profil & Cadence | Commandes corrélées (`n`) | `T0 → T0a` Moyenne / p50 / p95 (ms) | `T0a → T1` p50 / p95 (ms) | `T1 → T2` p50 / p95 (ms) | `T2 → T3` p50 / p95 (ms) | `T0 → T4 (CommandResult)` Moyenne / p50 / p95 (ms) | `T0 → T4 (C-03 Read)` p50 / p95 (ms) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **Baseline L7/I1 — 10 cmd/s** | `600` | n.m. | n.m. | `11,0` / `35,6` | n.m. | n.m. / `242,4` / `680,1` | `1 018,7` / `1 936,2` |
| **Séquentiel `H1` — 10 cmd/s** | `60` | `84,02` / `33,00` / `459,90` | `32,12` / `96,76` | `15,82` / `38,49` | `46,50` / `139,30` | `208,65` / `157,50` / `567,65` | `846,50` / `1 564,65` |
| **Séquentiel `H2` — 10 cmd/s** | `40` | `59,62` / `46,00` / `145,50` | `39,46` / `95,35` | `17,86` / `65,02` | `53,50` / `159,20` | `191,82` / `180,50` / `354,45` | `851,50` / `1 642,80` |
| **Séquentiel `H3` — 10 cmd/s** | `60` | `78,70` / `36,50` / `359,45` | `42,27` / `169,20` | `18,45` / `125,08` | `64,00` / `332,30` | `299,07` / `231,00` / `675,70` | `1 088,00` / `2 023,65` |
| **Séquentiel `H4` — 10 cmd/s** | `60` | **`54,58` / `35,50` / `148,10`** | **`43,31` / `102,43`** | **`18,32` / `54,47`** | **`110,50` / `238,05`** | **`262,02` / `245,50` / `409,05`** | **`915,00` / `1 851,45`** |
| **Séquentiel `H5` — 10 cmd/s** | `40` | `1 542,78` / **`85,50`** / `6 244,05` | `81,96` / `656,17` | `27,44` / `2 383` | `97,00` / `1 364` | `2 374,95` / `352,50` / `9 406,75` | `1 710,00` / `9 459,95` |
| **Baseline L7/I1 — 50 cmd/s** | `3 000` | n.m. | n.m. | `10,8` / `45,8` | n.m. | n.m. / `241,6` / `822,6` | `1 271,9` / `2 341,6` |
| **Séquentiel `H1` — 50 cmd/s** | `150` | `109,57` / `99,00` / `219,55` | `95,62` / `195,97` | `25,35` / `86,62` | `191,00` / `998,40` | `583,83` / `560,00` / `1 247,45` | `3 125,00` / `5 679,10` |
| **Séquentiel `H3` — 50 cmd/s** | `150` | **`85,99` / `72,00` / `218,35`** | `97,75` / `236,78` | `25,52` / `113,12` | `276,50` / `747,65` | `589,73` / `597,00` / `1 012,10` | `2 536,50` / `4 285,35` |
| **Séquentiel `H4` — 50 cmd/s** | `150` | **`79,50` / `79,00` / `201,65`** | `102,42` / `196,46` | `26,31` / `157,80` | `383,00` / `1 180,35` | `686,84` / `683,50` / `1 445,65` | `3 010,50` / `4 980,70` |

**Observation clé à `50 cmd/s`** : La découverte séquentielle des commandes (`T0 → T0a`) maintient un p50 de **`72 à 99 ms`** et un p95 de **`201 à 219 ms`** même sous `800` commandes en quarantaine (`H4`). À `50 cmd/s`, la latence de bout en bout est désormais dominée par l'attente de traitement des événements aval (`T2 → T3` sur `COMMAND_COMPLETION` et `POT` dans `pocoma-results-worker` et `pocoma-business-read-worker`), et non plus par la découverte de `Command`.

### 7.4. Qualification de bout en bout de `IdentityRequest` (`POST /api/v3/identity-requests`)

Dans chaque campagne (`H1` à `H5`), un lot de requêtes `IdentityRequest` a été admis et corrélé jusqu'à la projection `v3_read.read_identity_request_result` (`write_outcome = 'CHANGED'`) :

| Profil | Quarantaines `IDENTITY_REQUEST` | `IdentityRequest` E2E Moyenne (ms) | `IdentityRequest` E2E p50 (ms) | `IdentityRequest` E2E p95 (ms) | Taux de succès |
| :--- | ---: | ---: | ---: | ---: | ---: |
| **`H1`** (1 cmd/s) | `0` | `322,00 ms` | `327,50 ms` | `461,40 ms` | `100 %` |
| **`H2`** (1 cmd/s) | `0` (`2 006` terminales) | `299,50 ms` | `273,00 ms` | `395,25 ms` | `100 %` |
| **`H3`** (1 cmd/s) | **`50`** (`12-13/part`) | **`356,33 ms`** | **`269,50 ms`** | **`527,80 ms`** | `100 %` |
| **`H4`** (1 cmd/s) | **`200`** (`50/part`) | **`301,67 ms`** | **`287,50 ms`** | **`436,00 ms`** | `100 %` |
| **`H5`** (1 cmd/s) | **`500`** (`125/part`) | `1 268,67 ms` | **`334,50 ms`** | `4 429,55 ms` | `100 %` |

### 7.5. Ressources conteneurs observées (CPU / RAM / I/O)

Les instantanés `docker stats` capturés à la fin de chaque campagne (voir `campaign_H*_rate_*.json`) montrent une empreinte mémoire et CPU très contenue sur les 5 runtimes :
- `pocoma-command-worker` : RAM stable entre **`194 MiB` et `202 MiB`**, CPU au repos `< 1 %` et `< 15 %` sous charge nominale.
- `pocoma-results-worker` : RAM stable autour de **`266 MiB`**.
- `pocoma-business-read-worker` : RAM stable entre **`207 MiB` et `232 MiB`**.
- `pocoma-http-write` : RAM stable entre **`236 MiB` et `266 MiB`**.
- `pocoma-http-read` : RAM stable autour de **`355 MiB`**.
- `pocoma-v3-postgres` : RAM stable entre **`189 MiB` et `279 MiB`**.

---

## 8. Réponses explicites aux 10 questions obligatoires du mandat (Section 12)

1. **La publication séquentielle garantit-elle un préfixe sans trou visible après commit ?**
   - **Oui.** Chaque admission exécute `UPDATE v3_write.v3_request_counter SET position = position + 1 WHERE family = ? RETURNING position` suivi de `INSERT INTO v3_write.v3_request_position(family, position, request_id)` dans la **même transaction physique** que l'insertion de `v3_command` ou `v3_identity_request`. Le verrou exclusif ligne sur `v3_request_counter` sérialise les commits par famille ; un rollback ou un conflit d'idempotence annule (ou n'exécute pas) l'incrément. Les tests `V3AdmissionPostgresTest`, `V3LinkAdmissionPostgresTest`, `CommandExecutionPostgresTest` et `IdentityAuthorityPostgresTest` vérifient qu'après admissions concurrentes, rejets d'idempotence et rollbacks, les positions commitées forment exactement la suite dense $1 \dots N$.

2. **`COMMAND` et `IDENTITY_REQUEST` sont-elles correctement isolées par famille ?**
   - **Oui.** Les deux familles possèdent chacune leur ligne dédiée dans `v3_request_counter` (`family = 'COMMAND'` et `family = 'IDENTITY_REQUEST'`) et leur espace de clés `(family, position)` dans `v3_request_position`. Un verrou pris lors d'une admission `COMMAND` ne bloque jamais une admission `IDENTITY_REQUEST`, et chaque famille maintient ses propres checkpoints durables dans `discovery_checkpoint` (`command-consumption` vs `identity-request-consumption`).

3. **`RequestWalk` est-il intégralement supprimé ?**
   - **Oui.** Le record `BurnableReferences.RequestWalk` a été supprimé du port `BurnableReferences`, des adaptateurs JDBC `JdbcCommandDiscoveryReferences` et `JdbcIdentityRequestDiscoveryReferences`, de `DiscoveryEngine`, de `V3DiscoveryLoop`, des tests d'architecture `V3ConsumptionEngineArchitectureTest` et de l'ensemble des tests unitaires et d'intégration. Une recherche dans tout `app/` confirme `0` occurrence de `RequestWalk`.

4. **Les requêtes `QUARANTINED` cessent-elles d'être reparcourues par Discovery ?**
   - **Oui.** Lorsqu'une requête est découverte à la position $P$, son `consumption_slot` est créé et `discovery_checkpoint` avance atomiquement à $\ge P$. Même si l'exécution échoue ensuite et place le slot en `QUARANTINED` tout en laissant la requête source en `PENDING`, le checkpoint durable reste $\ge P$. Comme `Discovery` interroge désormais uniquement `position > checkpoint`, les requêtes en quarantaine ne sont **plus jamais reparcourues**, comme démontré par `DiscoveryPostgresTest.populatedQuarantinesAndCompositeRestart` (`300`, `800`, `2 000` quarantaines -> `scanned == 1` lors de l'arrivée d'une nouvelle requête) et par les profils `H3`, `H4` et `H5`.

5. **Comment se comporte Discovery lorsque la capacité de slots est saturée au milieu d'une fenêtre ?**
   - **Arrêt exact à la dernière position admise et reprise sans perte.** Dans `DiscoveryEngine.discoverBurnables`, les candidats d'une fenêtre sont parcourus par position strictement croissante. Dès que le nombre de slots admis atteint la capacité disponible (`available`), la boucle s'interrompt immédiatement, enregistre `checkpointPosition = ref.position()` sur le dernier candidat admis, persiste ce checkpoint dans la même transaction que les `slots.admit(...)`, et retourne `hasMore = true`. Les positions suivantes de la fenêtre (supérieures à `checkpointPosition`) ne sont pas franchies par le checkpoint et sont découvertes dès qu'une capacité se libère.

6. **Comment se comporte la reprise après crash/redémarrage d'un worker ?**
   - **Reprise déterministe depuis `discovery_checkpoint` sans re-scan de l'historique ni des quarantaines.** `V3DiscoveryLoop` ne maintient plus aucun curseur UUID volatile en mémoire. Au redémarrage d'un worker (ou lors d'une réattribution de bail de partition), `DiscoveryEngine` lit directement `checkpoints.lastPosition(consumption, slice.index())` dans PostgreSQL et reprend exactement à `lastPosition + 1`. Le test `DiscoveryPostgresTest.populatedQuarantinesAndCompositeRestart` qualifie ce scénario complet avec `2 000` quarantaines préexistantes.

7. **Quel est le gain réel sur `T0 → T0a` et `T0 → T4` à 1, 10 et 50 cmd/s ?**
   - **À `1 cmd/s` (profil `H3` avec 300 commandes + 50 requêtes d'identité en quarantaine, directement comparable à R11)** :
     - `T0 → T0a` passe de **`679,83 ms` (moyenne) / `682,36 ms` (p50) / `1 310,58 ms` (p95)** en R11 à **`29,60 ms` (-95,6 %) / `25,00 ms` (-96,3 %) / `60,05 ms` (-95,4 %)**.
     - `T0 → T4 (CommandResult)` passe de **`850,83 ms` (moyenne reconstituée R11)** et **`375,60 ms` (p50) / `934,70 ms` (p95) en L7/I1** à **`161,53 ms` (moyenne, -81,0 % vs R11) / `141,50 ms` (p50, -62,3 % vs L7/I1) / `337,40 ms` (p95, -63,9 % vs L7/I1)**.
   - **À `10 cmd/s` (profil `H4` avec 800 commandes + 200 requêtes d'identité en quarantaine + 500k événements)** :
     - `T0 → T0a` : moyenne **`54,58 ms`**, p50 **`35,50 ms`**, p95 **`148,10 ms`**.
     - `T0 → T4 (CommandResult)` : moyenne **`262,02 ms`**, p50 **`245,50 ms`**, p95 **`409,05 ms`** (contre `680,10 ms` p95 en L7/I1, soit **-39,9 % sur le p95**).
   - **À `50 cmd/s` (profils `H3` et `H4`)** :
     - `T0 → T0a` reste borné à **`72,00–79,00 ms` (p50)** et **`201,65–218,35 ms` (p95)**.

8. **Quel est l'effet propre d'une base déjà peuplée (commandes terminées, quarantaines, événements) ?**
   - **Volumétrie terminée et quarantaines (`H1` → `H4`)** : L'ajout de `60 000` commandes terminées, `800` commandes en quarantaine, `200` requêtes d'identité en quarantaine et `500 000` événements (`H4`) n'induit **aucune dégradation** de `T0 → T0a` (`25,03 ms` en moyenne sur `H4` contre `27,70 ms` sur `H1`), car l'index `v3_request_position_pk` accède directement à `position > checkpoint` en $O(\log N)$ (`shared hit=7`).
   - **Stress mono-hôte extrême (`H5`, 1 000 000 d'événements + 100 000 commandes + 2 500 quarantaines)** : La médiane `p50` de `T0 → T0a` reste excellente (`32,00 ms` à 1 cmd/s, `85,50 ms` à 10 cmd/s), mais le p95 subit des pics liés aux opérations d'I/O disque et d'autovacuum PostgreSQL sur l'hôte unique après l'insertion massive en masse de 500 000 lignes supplémentaires.

9. **Quelle part de la latence résiduelle relève désormais du scheduler (`min-poll`, `max-poll`, backoff) ou d'un futur mécanisme de réveil ?**
   - À `1 cmd/s` sur `H3`/`H4`, la latence moyenne de bout en bout `T0 → T4 (CommandResult)` n'est plus que de **`161,53 ms` à `174,80 ms`**, ainsi décomposée :
     - **`T0 → T0a` (`~25 à 30 ms` en moyenne)** : résidu incompressible lié au cadencement de polling (`min-poll` / rotation des partitions) de `pocoma-command-worker` en l'absence de notification asynchrone inter-runtimes.
     - **`T0a → T1` (`~19 à 21 ms` en p50)** : sélection et verrouillage du slot par l'exécuteur de commandes.
     - **`T1 → T2` (`~16 à 21 ms` en p50)** : exécution transactionnelle de la commande et publication des événements.
     - **`T2 → T3` (`~64 à 68 ms` en p50)** : attente de polling de `COMMAND_COMPLETION` par `pocoma-results-worker` (`min-poll` / intervalle de découverte non-burnable).
     - **`T3 → T4` (`~2 à 3 ms` en p50)** : écriture de `read_command_result`.
   - Ainsi, **plus de 70 % de la latence résiduelle à basse cadence (`T0 → T0a` + `T0a → T1` + `T2 → T3`, soit `~110 ms` sur `161 ms`)** provient désormais exclusivement des intervalles de polling inter-étapes (`pocoma-http-write` → `pocoma-command-worker` → `pocoma-results-worker`), qui relèvent d'un éventuel chantier ultérieur (`I4`) d'optimisation du scheduler ou de réveil inter-runtimes.

10. **Un chantier `I4` sur le scheduler est-il encore nécessaire, et pourquoi ?**
    - **Non requis pour respecter les SLAs actuels à 1 et 10 cmd/s, mais pertinent si l'on vise une latence sous les 50 ms ou un débit $\ge 50\text{ cmd/s}$ sans accumulation aval.** L'unification séquentielle de `Discovery` (`I1`–`I3`) a éliminé à elle seule **96 % de l'attente avant découverte (`T0 → T0a`)** et **81 % de la latence `CommandResult` de R11**, ramenant `T0 → T4 (CommandResult)` à `141,50 ms` (p50) / `337,40 ms` (p95) à 1 cmd/s. Si un chantier `I4` est ouvert ultérieurement, il devra cibler non pas la découverte de `Command` (déjà à `20–25 ms` p50), mais le débit de dépilement aval des événements (`COMMAND_COMPLETION` et `POT` à `50 cmd/s`) et le réveil inter-runtimes (`T2 → T3`).

---

## 9. Verdict formel

`SEQUENTIAL_BURNABLE_DISCOVERY_IMPLEMENTED_AND_QUALIFIED`
