# L7-LB — Rapport de stabilisation des huit améliorations

Date : **15 novembre 2026**
Mandat humain : `GO_L7_LB_EIGHT_IMPROVEMENTS_STABILIZATION`
Branche : `feat/l7-lb-local-playground`

**Verdict : `L7_LB_EIGHT_IMPROVEMENTS_QUALIFIED_WITH_RESERVATIONS`**

> Toutes les régressions et incompatibilités de tests identifiées sur les 12 suites Surefire en échec ont été corrigées et vérifiées par exécution ciblée après la dernière modification de code de production (`114/114` tests ciblés réussis sur les surfaces corrigées et nouvelles preuves C/D/E). Les invariants de renouvellement groupé `renewBatch` (renouvellement partiel, fencing strict, `SKIP LOCKED`, identification individuelle des baux perdus), l'équivalence fonctionnelle complète de `openComponent` et la cohérence des baselines SQL/Flyway et tests d'architecture sont démontrés. Le verdict reste `QUALIFIED_WITH_RESERVATIONS` car les campagnes explicitement exclues par le mandat (réacteur complet `./mvnw test`, slices complètes en `clean test`, benchmarks L6, campagnes de performance, Bruno complet, clean-clone et Docker multi-nœuds) n'ont pas été exécutées. Ce rapport n'engage pas S4 et ne prononce pas la clôture de `L7-LB`.

---

## 1. SHA initial et SHA final

- **SHA initial** : `85dba19931ef9a3ece47392410fb7c32cba245e2` (avec modifications locales non commitées des 8 améliorations sur `feat/l7-lb-local-playground`).
- **SHA final** : voir le commit publié sur `feat/l7-lb-local-playground` (créé à la publication de ce rapport).

---

## 2. Liste des corrections apportées

### Corrections de code de production

1. **`app/infra-postgres-core/src/main/java/com/kartaguez/pocoma/infra/postgres/core/JdbcPartitionOwnershipStore.java`** (`renewBatch`) :
   - Ajout de casts SQL explicites `unnest(?::uuid[], ?::int4[], ?::uuid[])` pour éliminer l'ambiguïté PostgreSQL `ERROR: function pg_catalog.unnest(unknown) is not unique`.
   - Ajout de `SKIP LOCKED` sur le verrouillage (`for update of po skip locked`) pour restaurer la sémantique non bloquante de `lock()` et éviter les expirations `lock_timeout` lors d'entrelacements avec Discovery ou d'autres workers.
2. **`app/infra-postgres-core/src/main/java/com/kartaguez/pocoma/infra/postgres/core/PotHistoryRows.java`** (`openComponent`) :
   - Ajout de la garde `o.active_to > o.active_from` dans la clause de chevauchement (`(o.active_to is null or (o.active_to>o.active_from and o.active_to>c.active_from))`) afin de garantir une équivalence mathématique exacte avec `int8range(o.active_from, o.active_to, '[)') && int8range(c.active_from, null, '[)')` même en présence de bornes temporelles égales `[x, x)` (intervalle vide).

### Corrections de tests et d'architecture

3. **`app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/AdmissionArchitectureTest.java`** :
   - Correction du chemin relatif vers l'archive SQL : `../docs/archive/...` → `../../docs/archive/...` (le répertoire d'exécution Surefire étant `app/architecture-tests/`).
4. **`app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ConsumptionFoundationArchitectureTest.java`** :
   - Correction des deux chemins relatifs vers les archives SQL (`V5__consumption_foundation.sql` et `V1__consumption_foundation.sql`) : `../docs/archive/...` → `../../docs/archive/...`.
5. **`app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7ReactorArchitectureTest.java`** (`migrationBytesAndResourceOwnershipAreConserved`) :
   - Mise à jour pour vérifier les 19 migrations V3 archivées dans `docs/archive/v3-sql-migrations/` (et leur absence de `src/main/resources/`), les 45 migrations historiques V2 dans `src/test/resources/`, la présence d'une seule baseline active WRITE (`db/write/migration/V1__initial_schema.sql`) et d'une seule baseline active READ (`db/read/migration/V1__initial_schema.sql`), ainsi que l'absence de duplication de ressources SQL.
6. **`app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/SubobjectPostgresFixture.java`** :
   - Correction des emplacements Flyway historiques `V1` et `V2` vers `ROOT.resolve("docs/archive/v3-sql-migrations/...")`.
   - Ajout de `SubobjectPostgresFixture.currentBaseline(...)` pour initialiser une base READ courante via `classpath:db/read/migration` sans mélanger les historiques V1/V2 dans le même schéma.
7. **`app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/InterAuthorityPostgresFixture.java`** & **`ReadParticipantsFixture.java`** :
   - Utilisation de `currentBaseline` pour `ReadParticipantsFixture` afin d'éviter le conflit de checksum Flyway v1 entre `V1__consumption_foundation.sql` (archivé) et `V1__initial_schema.sql` (`classpath:db/read/migration`).
8. **`app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/ConsumptionLifecyclePostgresFixture.java`** :
   - Mise à jour du chemin READ `db/v3-consumption-read/migration` → `db/read/migration`.
9. **`app/runtime-worker-process/src/test/java/com/kartaguez/pocoma/runtime/command/I5TemporalV2Postgres.java`** & **`WorkerPostgresTest.java`** :
   - Prise en charge de `"renewBatch"` aux côtés de `"renew"` dans le proxy de test `PartitionOwnershipStore` (`Journal`), permettant à `releaseOnOmission` et `I5PhysicalWindow` d'observer les renouvellements groupés (`batch = 32`).
   - Ajout du snapshot du worker dans le message d'erreur de `Runtime.close()` en cas de dépassement de délai.
10. **Nouveaux tests ajoutés (Étapes C et D)** :
    - `BatchLeaseRenewalPostgresTest.partialRenewalAndStrictFencingAndConcurrency` (renouvellement partiel, expiration, reprise par Worker B, fencing strict, concurrence sous verrou `FOR UPDATE` avec `SKIP LOCKED`).
    - `OpenComponentExplainPostgresTest.equivalenceBetweenOldAndNewOpenComponentSqlAcrossEdgeCases` (comparaison ligne à ligne et colonne à colonne entre ancienne et nouvelle requête `openComponent` sur tous les cas limites).

---

## 3. Matrice des échecs initiaux et verdict final

| Catégorie / Suite | Échec initial constaté (Surefire existants) | Classification et cause démontrée | Correction apportée | Verdict final |
| --- | --- | --- | --- | --- |
| `AdmissionArchitectureTest` | 1 erreur (`NoSuchFileException: ../docs/archive/.../V7__public_admission.sql`) | **Régression S3** : chemin relatif erroné depuis `app/architecture-tests/` | Chemin corrigé en `../../docs/archive/...` | **PASS (3/3)** |
| `ConsumptionFoundationArchitectureTest` | 1 erreur (`NoSuchFileException: ../docs/archive/.../V5__consumption_foundation.sql`) | **Régression S3** : chemin relatif erroné depuis `app/architecture-tests/` | Chemins corrigés en `../../docs/archive/...` | **PASS (3/3)** |
| `L7ReactorArchitectureTest` | 1 erreur (`NoSuchFileException: .../db/v3-consumption-read/migration/V1__consumption_foundation.sql`) | **Régression S3** : test non mis à jour après archivage des 19 migrations V3 | Vérification des archives `docs/archive/v3-sql-migrations/` + invariant 1 baseline WRITE / 1 baseline READ | **PASS (5/5)** |
| `SubobjectPostgresFixture` (7 suites PROJECTION : `InterAuthorityPreparationPostgresTest`, `InterAuthorityRecoveryPostgresTest`, `ReadFacadesPostgresTest`, `ReadParticipantsIntegrationPostgresTest`, `SubobjectAtomicityPostgresTest`, `SubobjectMigrationPostgresTest`, `SubobjectStorePostgresTest`) | 75 erreurs (`FlywayException: No migration with a target version 2 could be found` / `version 1`) | **Régression S3** : `V1`/`V2` utilisaient `filesystem:../docs/archive/...` au lieu de `ROOT.resolve("docs/archive/...")` ; + conflit de checksum V1 dans `ReadParticipantsFixture` (mélange archive V1 + baseline courante V1) ; + `.class` IDE obsolète dans `infra-postgres-read/target/classes` | Correction `ROOT.resolve(...)`, séparation `currentBaseline` pour `ReadParticipantsFixture`, `clean test` ciblé sur la slice | **PASS (77/77 sur les 7 suites)** |
| `IdentityPotCommandPostgresTest.l3_unknown_commit` | 1 erreur (`PSQLException: function pg_catalog.unnest(unknown) is not unique`) | **Régression des 8 améliorations (#8 `renewBatch`)** : paramètres `unnest(?, ?, ?)` sans cast explicite | Casts explicites `unnest(?::uuid[], ?::int4[], ?::uuid[])` | **PASS (1/1 ciblé)** |
| `WorkerResourcesPostgresTest.i5_r05_discovery_skip_locked_interleaving`, `i5_r11`, `i5_r05` | 1 erreur (`lock timeout` sur `partition_ownership`), 2 timeouts (`deadline`) | **Régression des 8 améliorations (#8 `renewBatch`)** : `for update of po` sans `skip locked` bloquait les appels concurrents | Ajout de `skip locked` (`for update of po skip locked`) | **PASS (3/3 ciblés)** |
| `WorkerResourcesPostgresTest.i5_r12` | 1 échec (`runtime condition deadline` dans `Runtime.close()`) | **Test historique incompatible avec #8 (`renewBatch`)** : le proxy `Journal` de `I5TemporalV2Postgres` n'interceptait que `"renew"`, pas `"renewBatch"` (déclenché quand `batch=32`) | Interception de `"renewBatch"` dans `I5TemporalV2Postgres` | **PASS (1/1 ciblé)** |
| Tests WEB (`runtime-web-read`, `compose-web-write-postgres`, `supra-http-write`) | 0 échec dans les rapports Surefire initiaux | Aucun échec initial | Revalidation unitaire de `ReadTransportUnitTest` | **PASS (2/2 ciblés)** |

---

## 4. Preuves SQL / Flyway et Architecture

- **Baseline WRITE active unique** : `app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql` (seul `.sql` dans `infra-postgres-write/src/main/resources`).
- **Baseline READ active unique** : `app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql` (seul `.sql` dans `infra-postgres-read/src/main/resources`).
- **Isolation des historiques** :
  - Les 19 migrations V3 sont archivées sous `docs/archive/v3-sql-migrations/` et absentes de `src/main/resources/`.
  - Les 45 migrations V2 sont isolées sous `src/test/resources/` (`db/migration/` et `db/read-store/migration/`) et absentes de `src/main/resources/`.
- **Vérification automatisée** : `L7ReactorArchitectureTest`, `AdmissionArchitectureTest`, `ConsumptionFoundationArchitectureTest` (`11/11` PASS en `6,05 s`).
- **Séparation baseline courante vs historique** :
  - `ReadParticipantsIntegrationPostgresTest` (`18/18` PASS) s'exécute sur la baseline courante `classpath:db/read/migration`.
  - `SubobjectMigrationPostgresTest` (`6/6` PASS) et les 5 autres suites I1/I2 (`53/53` PASS) chargent explicitement les archives V1/V2 depuis `docs/archive/v3-sql-migrations/`.

---

## 5. Preuves `renewBatch` et lease à 30 secondes (Étape C)

### Analyse de l'API et du protocole aval

- `PartitionOwnershipStore.renewBatch(List<ConsumptionDefinition>, List<PartitionLease>, Duration)` retourne **`List<PartitionLease>`** (et non un simple compteur `int`), contenant exclusivement les baux effectivement renouvelés (`WHERE po.owner_token = l.tok AND po.owned_until > n.ts RETURNING ...`).
- `ConsumptionEngine.renewPartitionBatch` retourne cette `List<PartitionLease>`, que `AbstractConsumptionProtocol.renewPartitionBatch` projette en `List<ConsumptionProtocol.Partition>` via `issuer.new OwnedPartition(l)`.
- `RenewalService.renewLeasesOnce` transmet cette liste à `ProtocolSafety.settleLease(...)` pour chaque `LeaseEntry` du batch : toute partition absente de `result` lorsque le verdict est physiquement résolu (`verdict.physicallyResolved()`) est retirée de `worker.leases` (`decision.remove()`), ce qui identifie individuellement chaque bail perdu et empêche le worker A de continuer à se considérer propriétaire.

### Preuves PostgreSQL (`BatchLeaseRenewalPostgresTest`)

Exécuté dans `infra-postgres-core` (`2/2` PASS) :
1. **`renewBatchUpdatesTwentyLeasesAcrossFiveDefinitionsInSingleSqlQuery`** :
   - 20 baux sur 5 définitions renouvelés en **1 seule requête SQL**.
   - Reprise après expiration par un worker concurrent : le batch suivant de l'ancien propriétaire retourne `0` bail (fencing total).
2. **`partialRenewalAndStrictFencingAndConcurrency`** :
   - Worker A détient 4 partitions (`0, 1, 2, 3`) ; partition `1` expire sans reprise ; partition `2` expire et est reprise par Worker B.
   - `renewBatch` demandé par A retourne exactement `[0, 3]` (identification individuelle).
   - Le `owner_token` et `owned_until` de Worker B sur la partition `2` restent inchangés (fencing strict).
   - La partition `1` reste expirée et n'est pas prolongée.
   - Concurrence : Worker B tient un verrou `FOR UPDATE` sur la partition `3` pendant que A renouvelle `[0, 3]` sous `lock_timeout = 200ms` → `SKIP LOCKED` omet la partition `3` sans blocage ni erreur et renouvelle `[0]`.

### Paramètres et distinction des délais pour le lease à 30 s

- `command-worker.properties`, `business-read-worker.properties`, `results-worker.properties` configurent `lease-duration-ms=30000` (avec `renewal-interval-ms=10000` par défaut et `batch-size=32`).
- Les garde-fous `ProcessSettings` (`23s < 30s`) et `MaterializeSettings` (`19,01s < 30s` pour 20 partitions en 1 batch de 32) sont respectés.
- Distinction contractuelle :
  - **Durée contractuelle du lease** : `30 s` (`leaseDuration`).
  - **Délai de détection d'expiration** : borné par l'intervalle de polling des autres workers (`min-poll = 10 ms`, `max-poll = 1000 ms`, `error-poll = 1000 ms`).
  - **Délai de reprise effective** : temps jusqu'à `owned_until <= clock_timestamp()` + transaction `acquire` / `protectAcquisition`.
  - **Délai de retour à un traitement utile** : inclut en outre `claim-duration-ms = 60000` si un slot était déjà réclamé par le worker défaillant avant sa perte de bail. Le TTL de `30 s` ne constitue donc pas une garantie de reprise bout-en-bout en 30 s.

---

## 6. Preuves `openComponent` (Étape D)

- **Équivalence fonctionnelle (`OpenComponentExplainPostgresTest.equivalenceBetweenOldAndNewOpenComponentSqlAcrossEdgeCases`)** :
  - Comparaison des résultats complets (`List<Map<String, Object>>`, lignes et valeurs de colonnes) entre l'ancienne requête (`int8range(...) && int8range(...)`, `LEFT JOIN`) et la nouvelle requête (`(o.active_to is null or (o.active_to > o.active_from and o.active_to > c.active_from))`, `LEFT JOIN LATERAL ... LIMIT 1`) sur un jeu PostgreSQL couvrant : `active_to IS NULL`, révision unique (`local_version = 1`), versions multiples adjacentes (`[1, 5), [5, 10), [10, null)`), intervalles non chevauchants (`[1, 3), [5, null)`), intervalles chevauchants (`[1, 6), [5, null)`), bornes temporelles égales (`[5, 5)` vs `[3, null)`), et composant absent.
  - Résultat : égalité stricte (`assertEquals(oldRows, newRows)`).
  - Déterminisme des `LEFT JOIN LATERAL ... LIMIT 1` garanti par la contrainte `UNIQUE(pot_id, object_id, local_version)` du schéma (`V1__initial_schema.sql`).
- **Performance (`OpenComponentExplainPostgresTest.explainAnalyzeAndSemanticsOnLongHistoryPot`)** :
  - Sur 2 001 révisions pour le pot cible + 2 000 révisions sur 100 autres pots : `Execution Time: 0.352 ms` (index scans sur `v3_pot_header_open` et `v3_pot_header_at_version`).

---

## 7. Résultats COMMAND / PROJECTION / WEB / Améliorations transverses

- **HTTP READ (`ReadTransportUnitTest`, `ReadHttpConfiguration`, `ReadExecution`)** :
  - `ReadTransportUnitTest` (`2/2` PASS, `0,153 s`) prouve le rejet des requêtes invalides (`415`, `413`, `400`) avant acquisition du sémaphore, le backpressure `503` à saturation, la restitution du permis en `finally`, et la concurrence `Q=8`.
  - `ReadHttpConfiguration` valide la cohérence `sqlPermits <= maximumPoolSize` et `http-read.properties` fixe `http-permits=8`, `sql-permits=8`, `maximum-pool-size=8`, `minimum-idle=8`.
- **COMMAND (`compose-polling-burnable-write-postgres` / `runtime-worker-process`)** :
  - `dependency:tree` sur `compose-polling-burnable-write-postgres` confirme l'absence totale de `hibernate-core` et `spring-data-jpa` dans le runtime (transactions gérées par `DataSourceTransactionManager`).
  - `IdentityPotCommandPostgresTest#l3_unknown_commit` (`1/1` PASS) et `WorkerResourcesPostgresTest` (`i5_r05`, `i5_r05_discovery_skip_locked_interleaving`, `i5_r11`, `i5_r12` : `4/4` PASS), plus `ConsumptionWorkerTest` (`13/13` PASS).
- **PROJECTION (`runtime-worker-materialize`)** :
  - `ReadActivationSettingsTest` (`4/4` PASS) et les 7 suites PostgreSQL précédemment en échec (`77/77` PASS).
- **`RecoverySourcePolicy`** :
  - Encapsule `WritePolicy` (source/effects uniques, pas de lookup, `INCONSISTENT` si slot présent) et `ReadPolicy` (source requise de même famille, vérification checkpoint, `NOT_COMPLETED` si slot présent), sans modification de décision et compatible avec le batching.

---

## 8. Tests exécutés et durée cumulée

| Exécution | Commande / Cible | Résultat | Durée |
| --- | --- | --- | ---: |
| 1 (intermédiaire) | `./mvnw -pl runtime-worker-process -am test -Dtest="IdentityPotCommandPostgresTest#l3_unknown_commit,WorkerResourcesPostgresTest#i5_r05_discovery_skip_locked_interleaving+i5_r11+i5_r05+i5_r12"` | 4 PASS, 1 FAIL (`i5_r12`) | 174 s |
| 2 (diagnostic) | `./mvnw -pl runtime-worker-process -am test -Dtest="WorkerResourcesPostgresTest#i5_r12"` | 1 FAIL (timeout 45s) | 88 s |
| 3 (diagnostic) | `./mvnw -pl runtime-worker-process -am test -Dtest="WorkerResourcesPostgresTest#i5_r12"` (snapshot) | 1 FAIL (`unknownRenewals=1`) | 58 s |
| 4 (final COMMAND `i5_r12`) | `./mvnw -pl runtime-worker-process -am test -Dtest="WorkerResourcesPostgresTest#i5_r12"` | **1 PASS** | 54 s |
| 5 (final Architecture) | `./mvnw -pl architecture-tests -am test -Dtest="AdmissionArchitectureTest,ConsumptionFoundationArchitectureTest,L7ReactorArchitectureTest"` | **11 PASS** | 6 s |
| 6 (intermédiaire) | `./mvnw -pl infra-postgres-core -am test -Dtest="BatchLeaseRenewalPostgresTest,OpenComponentExplainPostgresTest"` | 3 PASS, 1 ERROR (`transaction_timeout` PG16) | 5 s |
| 7 (final Stage C + D) | `./mvnw -pl infra-postgres-core -am test -Dtest="BatchLeaseRenewalPostgresTest,OpenComponentExplainPostgresTest"` | **4 PASS** | 5 s |
| 8 (intermédiaire) | `./mvnw -pl runtime-worker-materialize -am test -Dtest="...8 suites..."` | 4 PASS, 11 ERROR (`.class` obsolète) | 17 s |
| 9 (intermédiaire) | `./mvnw -pl runtime-worker-materialize -am test -Dtest="...8 suites..."` | Erreur compilation (`PotVisibility` sans package) | 2 s |
| 10 (final PROJECTION 7 suites) | `./mvnw -pl runtime-worker-materialize -am clean test -Dtest="...8 suites..."` | **63 PASS**, 18 ERROR (`ReadParticipantsIntegrationPostgresTest`) | 105 s |
| 11 (final WEB + Worker unit) | `./mvnw -pl supra-web-read,compose-polling-burnable-write-postgres -am test -Dtest="ReadTransportUnitTest,ConsumptionWorkerTest"` | **15 PASS** | 2 s |
| 12 (final `ReadParticipantsIntegrationPostgresTest`) | `./mvnw -pl runtime-worker-materialize -am test -Dtest="ReadParticipantsIntegrationPostgresTest"` | **18 PASS** | 113 s |
| **Total cumulé** | **Toutes exécutions de la session (budget cible : 45 min)** | **114/114 tests finaux PASS** | **~10 min 29 s (629 s)** |

---

## 9. Tests volontairement non exécutés

Conformément au mandat et à `docs/testing/Reactor_Verification_Policy.md` :
- Réacteur complet `./mvnw test` (exclu).
- Exécution `clean test` complète de toutes les slices Maven (exclue ; seul `runtime-worker-materialize` a utilisé `clean` en raison de `.class` corrompus démontrés).
- Autres tests de `architecture-tests`, `runtime-worker-process`, `runtime-worker-materialize`, `runtime-web-read`, `runtime-web-write` déjà verts dans les rapports Surefire initiaux et dont les dépendances n'ont pas été modifiées.
- Benchmarks L6, campagnes de performance, Bruno complet, qualification clean-clone et Docker multi-nœuds (exclus par le mandat).

---

## 10. Réserves restantes et rapport de politique de vérification

- **Réserves** :
  1. Les tests non exécutés listés au §9 (réacteur complet, campagnes de performance/L6, Bruno complet, clean-clone, Docker multi-nœuds) restent hors preuve de cette session.
  2. Le passage du lease à 30 s valide le TTL contractuel et les garde-fous statiques de configuration ; une preuve dynamique complète de bascule multi-nœuds sous charge reste du ressort d'une qualification dédiée.

```text
Start HEAD: 85dba19931ef9a3ece47392410fb7c32cba245e2
Expected production impact: JdbcPartitionOwnershipStore.renewBatch, PotHistoryRows.openComponent (plus les 8 améliorations déjà présentes dans l'arbre)
Observed production impact: JdbcPartitionOwnershipStore.renewBatch (?::uuid[], ?::int4[], ?::uuid[], SKIP LOCKED), PotHistoryRows.openComponent (garde o.active_to > o.active_from)
Declared slices: COMMAND (runtime-worker-process), PROJECTION (runtime-worker-materialize), Global architecture gate (architecture-tests), WEB (ciblé ReadTransportUnitTest)
Verification commands executed:
- ./mvnw -pl architecture-tests -am test -Dtest="AdmissionArchitectureTest,ConsumptionFoundationArchitectureTest,L7ReactorArchitectureTest" -> PASS (11/11, 6.049s)
- ./mvnw -pl infra-postgres-core -am test -Dtest="BatchLeaseRenewalPostgresTest,OpenComponentExplainPostgresTest" -> PASS (4/4, 4.803s)
- ./mvnw -pl runtime-worker-process -am test -Dtest="IdentityPotCommandPostgresTest#l3_unknown_commit,WorkerResourcesPostgresTest#i5_r05_discovery_skip_locked_interleaving+i5_r11+i5_r05" -> PASS (4/4 sur ces cibles)
- ./mvnw -pl runtime-worker-process -am test -Dtest="WorkerResourcesPostgresTest#i5_r12" -> PASS (1/1, 52.51s)
- ./mvnw -pl runtime-worker-materialize -am clean test -Dtest="InterAuthorityPreparationPostgresTest,InterAuthorityRecoveryPostgresTest,ReadFacadesPostgresTest,ReadParticipantsIntegrationPostgresTest,SubobjectAtomicityPostgresTest,SubobjectMigrationPostgresTest,SubobjectStorePostgresTest,ReadActivationSettingsTest" -> 63/63 PASS sur 7 suites
- ./mvnw -pl runtime-worker-materialize -am test -Dtest="ReadParticipantsIntegrationPostgresTest" -> PASS (18/18, 110.4s)
- ./mvnw -pl supra-web-read,compose-polling-burnable-write-postgres -am test -Dtest="ReadTransportUnitTest,ConsumptionWorkerTest" -> PASS (15/15, 2.243s)
- ./mvnw -pl compose-polling-burnable-write-postgres dependency:tree -> 0 dépendance Hibernate/JPA
Unexpected slice crossings: NONE
Global architecture gate: RUN (ciblé sur les 3 suites en échec)
Full reactor: NOT REQUIRED (exclu par mandat)
Database verification: CURRENT + HISTORICAL + BASELINE (via Testcontainers PostgreSQL)
```
