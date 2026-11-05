# Rapport de Correction Ciblée et Qualification Bornée L5/Q5-B4

**Date** : 5 novembre 2026  
**Dépôt** : `kartaguez/pocoma`  
**Branche** : `feat/v3-architecture`  
**SHA initial de référence (HEAD Q5-B3)** : `40c318a99479b1979fb65391c49610269f884144`  
**SHA applicatif sous qualification Q5-B3** : `429529a6e17b0c7a7060b3ec45c7d36657b77095`  
**Autorisation** : `GO_Q5_B4_TARGETED_CORRECTION`  
**Mode** : `AUDIT → TARGETED FIX → BOUNDED VERIFICATION → DOCUMENTATION → COMMIT & PUSH`  
**Verdict terminal Q5-B4** : **`READY_FOR_FINAL_CERTIFICATION`**  
**Statut du lot L5** : **`OPEN — PREPARATION FOR FINAL CERTIFICATION`** (non clos, jalon global réservé)  

---

## 1. Déclarations Normatives Préalables (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et aux consignes impératives de gouvernance :

- **Tranche(s) de vérification déclarée(s) :**
  - Tranche principale : **MONOLITH** (`app/runtime-monolith`)
  - Tranche secondaire : **WEB** (`app/runtime-web-api` — alignement d'oracle sur le contrat HTTP V3 réel)
  - Tranche associée : **PROJECTION** (`app/runtime-task-consumption-worker` — maintien de la stricte symétrie des fixtures)
- **Impact productif attendu :** **AUCUN** (`PRODUCTION_FILES_MODIFIED = 0`, `PRODUCTION_MIGRATIONS_MODIFIED = 0`). Seuls les tests et fixtures sous `src/test/java` sont modifiés.
- **Impact productif interdit :** Tout fichier productif sous `src/main/`, toute modification de migration SQL productive, de contrat HTTP, de sémantique métier ou de modèle de sécurité.
- **Commandes réellement exécutées :**
  1. *C1 (Compilation statique) :*
     `./mvnw -pl runtime-monolith,runtime-web-api,runtime-task-consumption-worker test-compile`
  2. *C2 (Vérification Flyway canonique & admission) :*
     `./mvnw -pl runtime-monolith -am -Dtest=V3AdmissionFlowPostgresTest#allElevenPotOperationsThroughWorker -Dsurefire.failIfNoSpecifiedTests=false test`
  3. *C3 (Vérification oracle HTTP Monolithe) :*
     `./mvnw -pl runtime-monolith -am -Dtest=V3MonolithHttpCompositionPostgresTest#monolithRoot -Dsurefire.failIfNoSpecifiedTests=false test`
  4. *C4 (Vérification coexistence 28 baux & takeover) :*
     `./mvnw -pl runtime-monolith -am -Dtest=ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely -Dsurefire.failIfNoSpecifiedTests=false test`
  5. *C5.1 (Vérification Invitation WRITE & READ) :*
     `./mvnw -pl runtime-monolith -am -Dtest=V3InvitationWritePostgresTest#oneGuestCannotTakeTwoPlaces,V3InvitationReadPostgresTest#reconstructOnFreshReadWithoutProducer -Dsurefire.failIfNoSpecifiedTests=false test`
  6. *C5.2 (Vérification Invitation HTTP & Topology) :*
     `./mvnw -pl runtime-monolith -am -Dtest=V3InvitationHttpTest#confidentialLinkAndRestrictedPlaces,V3InvitationTopologyPostgresTest#invitationPrivilegePreflightsRejectColumnEscalation -Dsurefire.failIfNoSpecifiedTests=false test`
  7. *C5.3 (Vérification oracle HTTP Web-API autonome) :*
     `./mvnw -pl runtime-web-api -am -Dtest=V3HttpCompositionPostgresTest#webRoot -Dsurefire.failIfNoSpecifiedTests=false test`
- **Franchissement inattendu de frontière de tranche :** Aucun (`NONE`). L'alignement de symétrie de fixture dans `runtime-task-consumption-worker` et l'oracle de `runtime-web-api` relèvent d'extensions déclarées et vérifiées.
- **Gate globale architecture requise :** **NON REQUISE** (aucun graphe de dépendances, frontière architecturale ou règle d'isolation interdite n'a été altérée).
- **Exécution du full reactor :** **NON** (strictement interdite par le mandat Q5-B4).

---

## 2. Synthèse Exécutive

La campagne Q5-B4 a résolu les trois anomalies de certification identifiées lors de la troisième tentative Q5-B3, en appliquant le principe de correction minimale et d'intégrité absolue des oracles :

| Indicateur | Valeur observée | Statut |
|---|:---:|:---:|
| **Verdict terminal Q5-B4** | **`READY_FOR_FINAL_CERTIFICATION`** | **CONFORME** |
| **SHA de départ** | `40c318a99479b1979fb65391c49610269f884144` | Figé |
| **Branche Git** | `feat/v3-architecture` | Propre, synchronisée |
| **Fichiers de production modifiés** | **0** (`PRODUCTION_FILES_MODIFIED = 0`) | CONFORME |
| **Migrations SQL productives modifiées** | **0** (`PRODUCTION_MIGRATIONS_MODIFIED = 0`) | CONFORME |
| **Fichiers de tests / fixtures modifiés** | **6 fichiers** (4 classes de tests, 2 fixtures) | CONFORME |
| **Tests JUnit exécutés en vérification** | **8 tests** | **8 PASS (100%)** |
| **Échecs d'assertion (FAILURES)** | **0** | CONFORME |
| **Erreurs d'exécution (ERRORS)** | **0** | CONFORME |
| **Tests sautés ou désactivés (SKIPS)** | **0** (aucun `@Disabled` introduit) | CONFORME |
| **Durée totale cumulée des commandes** | **379,9 s** (~6 min 20 s) | **Sous la cible (15 min)** et plafond (25 min) |
| **Plafond dynamique autorisé** | 25 minutes (1 500 s) | Respecté (25,3% consommé) |
| **Preuves archiver** | `docs/testing/evidence/L5-Q5B4/` (19 fichiers vérifiés) | Manifeste SHA-256 complet |

---

## 3. Analyse Indépendante des Trois Anomalies et Causes Racines

### 3.1 AUD-Q5B-03-A : Conflit Flyway V7–V10 dans les Fixtures (24 erreurs réconciliées)

- **Symptôme initial :**
  ```
  org.flywaydb.core.api.exception.FlywayValidateException: 
  Validate failed: Migrations have failed validation
  Detected resolved migration not applied to database: 7.
  Detected resolved migration not applied to database: 8.
  Detected resolved migration not applied to database: 9.
  To ignore this migration, set -ignoreMigrationPatterns='*:ignored'. To allow executing this migration, set -outOfOrder=true.
  ```
- **Diagnostic indépendant et mécanisme causal :**
  1. La chaîne de migrations versionnées pour le composant WRITE sous `app/infra-persistence-primary-jpa/src/main/resources/db/` est ordonnée canoniquement ainsi :
     - `db/v3-pot/migration` : V1, V3, V4
     - `db/v3-pot-execution/migration` : V2
     - `db/v3-consumption-write/migration` : V5
     - `db/v3-identity/migration` : V6
     - `db/v3-admission/migration` : V7 (`v3_admission_association`), V8 (`v3_require_command_unit`), V9 (`v3_link_proposal`)
     - `db/v3-invitation/migration` : V10 (`v3_invitation`, `v3_invitation_event`, adaptation de `v3_require_command_unit`)
  2. Lors de la mission Q5-BF (traitement d'AUD-Q5BR-04), `ReadMonolithI8Fixture.java` a été complété en ajoutant `classpath:db/v3-invitation/migration` (V10) immédiatement après `classpath:db/v3-identity/migration` (V6). Le dossier `classpath:db/v3-admission/migration` (V7, V8, V9) n'y figurait pas historiquement.
  3. À l'instanciation de `ReadMonolithI8Fixture`, Flyway appliquait les versions V1..V6 puis sautait directement à V10 sur le schéma éphémère `worker.schema`.
  4. Ensuite, dans la méthode `@BeforeEach setup()` de `V3AdmissionFlowPostgresTest`, une seconde configuration Flyway s'exécutait avec les emplacements incluant `classpath:db/v3-admission/migration` (V1..V9).
  5. Flyway constatait que des migrations locales de versions 7, 8 et 9 étaient présentes, alors que la table `flyway_schema_history` enregistrait déjà la version 10 comme version appliquée la plus élevée. Conformément à son invariant de validation strict par défaut, Flyway rejetait ces migrations comme non appliquées hors-ordre.
  6. Tous les tests héritant ou utilisant `V3AdmissionFlowPostgresTest#setup` échouaient au setup avant toute exécution de code métier.
- **Classification :** **`FIXTURE_DEFECT`** (composition incomplète et non déterministe des migrations dans la fixture monolithique).
- **Décision technique :**
  - **Rejet strict de `.outOfOrder(true)`** : conformément aux exigences normatives, l'ordonnancement canonique séquentiel V1..V10 de la production doit être strictement reproduit.
  - Ajout de `"classpath:db/v3-admission/migration"` dans `ReadMonolithI8Fixture` (et symétriquement dans `ReadRuntimeI8Fixture`) avant `"classpath:db/v3-invitation/migration"`.
  - Ajout de `"classpath:db/v3-invitation/migration"` dans `V3AdmissionFlowPostgresTest#setup` pour garantir que l'ensemble des locations résolues coïncide avec le schéma complet V1..V10.

### 3.2 AUD-Q5B-03-B : Acquisition des Partitions 27/28 (1 failure)

- **Symptôme initial :**
  ```
  org.opentest4j.AssertionFailedError: first started root owns the shared READ pairs ==> expected: <28> but was: <27>
  at com.kartaguez.pocoma.ReadRootsCoexistencePostgresTest.projectionAndMonolithSharePartitionsSafely(ReadRootsCoexistencePostgresTest.java:128)
  ```
- **Diagnostic indépendant et mécanisme causal :**
  1. Le test démarre un premier root monolithique (`i8-monolith`), qui acquiert les 28 baux de lecture (7 participants READ × 4 partitions).
  2. Le test démarre ensuite un second root concurrent (`i8-projection`), configuré sur les mêmes 28 partitions READ.
  3. Les threads du second root tentent continuellement d'acquérir les baux via `JdbcPartitionOwnershipStore#lock` (`SELECT ... FOR UPDATE SKIP LOCKED`).
  4. Simultanément, le service d'arrière-plan de renouvellement du premier root (`V3RenewalService#renewLeasesOnce`) tente de renouveler ses baux.
  5. Lorsque la requête de renouvellement rencontre une exclusion concurrente transitoire (`SKIP LOCKED`), la méthode marque l'entrée locale `entry.usable = false` et planifie un renouvellement immédiat sous `minPoll()` (quelques millisecondes).
  6. La méthode `worker.localLeases()` calcule le nombre de baux locaux en ne comptabilisant que les entrées où `usable == true`.
  7. À la ligne 128, le test effectuait une assertion instantanée directe `assertEquals(28, f.worker(monolith).localLeases().getOrDefault(READ, 0L))` sans attente d'état stable. Si un des 28 baux était en cours de cycle de réévaluation locale à cet instant précis (bien que contractuellement et durablement détenu dans PostgreSQL), la valeur observée valait transitoirement 27.
  8. Dans toutes les autres assertions du test (lignes 60, 73, 108, 137, 157), le test utilise systématiquement `await(...)` pour synchroniser l'observation sur l'état stable des workers asynchrones.
- **Classification :** **`TEMPORAL_TEST_FRAGILITY`** conjuguée à un **`OBSERVATION_ORACLE_DEFECT`** (assertion instantanée d'un état asynchrone concurrent sans condition d'attente stable).
- **Décision technique :**
  - Remplacement de l'assertion instantanée par :
    ```java
    await(
            () ->
                    f.worker(monolith)
                                    .localLeases()
                                    .getOrDefault(ConsumptionDefinition.Authority.READ, 0L)
                            == 28);
    assertEquals(
            28,
            f.worker(monolith)
                    .localLeases()
                    .getOrDefault(ConsumptionDefinition.Authority.READ, 0L),
            "first started root owns the shared READ pairs");
    ```
  - **Maintien intégral des invariants** : non-concurrence des baux, intégrité des 28 partitions, absence de double détention, délai d'attente d'expiration du bail et reprise ordonnée (takeover) après drain vérifiés sans affaiblissement.

### 3.3 AUD-Q5B-03-C : Cardinalité des Routes HTTP (1 failure)

- **Symptôme initial :**
  ```
  org.opentest4j.AssertionFailedError: expected: <8> but was: <10>
  at com.kartaguez.pocoma.V3MonolithHttpCompositionPostgresTest.monolithRoot(V3MonolithHttpCompositionPostgresTest.java:65)
  ```
- **Diagnostic indépendant et mécanisme causal :**
  1. Lors du jalon L5/I1, le contrôleur `V3ReadController` a été introduit sous le profil Spring `v3-http-read`, exposant exactement 8 routes de lecture. L'assertion de test `assertEquals(8, routes(http).size())` reflétait fidèlement cette surface initiale.
  2. Lors de l'incrément L5/I4, le contrôleur `V3InvitationController` a été ajouté sous le même profil `v3-http-read`, apportant 2 nouvelles routes légitimes :
     - `GET /api/v3/invitations/by-creation-request/{requestId}`
     - `POST /api/v3/invitations/available-shareholders/query`
  3. L'oracle historique du test monolithique n'avait pas été mis à niveau lors de l'intégration I4.
  4. Une situation identique existait dans `V3HttpCompositionPostgresTest` sous `runtime-web-api` (qui n'avait pas été exécuté lors de Q5-B3 en raison de l'arrêt anticipé au module 52).
- **Classification :** **`TEST_ORACLE_DEFECT`** (non-alignement de l'oracle de test sur l'évolution contractuelle légitime de l'API HTTP V3).
- **Décision technique :**
  - Mise à niveau de l'oracle vers un contrôle ensembliste strict et exhaustif des 10 routes attendues, interdisant toute route excédentaire non autorisée :
    ```java
    var expectedRoutes =
            Set.of(
                    "/api/v3/me/binding",
                    "/api/v3/pots/{potId}",
                    "/api/v3/pots/{potId}/versions/{version}",
                    "/api/v3/pots/{potId}/versions/{version}/balance",
                    "/api/v3/pots/{potId}/versions/{version}/expenses/query",
                    "/api/v3/me/pots",
                    "/api/v3/commands/{requestId}/result",
                    "/api/v3/identity/requests/{requestId}/result",
                    "/api/v3/invitations/by-creation-request/{requestId}",
                    "/api/v3/invitations/available-shareholders/query");
    assertEquals(expectedRoutes, routes(http));
    assertEquals(10, routes(http).size());
    assertEquals(1, context.getBeansOfType(V3ReadController.class).size());
    assertEquals(1, context.getBeansOfType(V3InvitationController.class).size());
    ```
  - Alignement symétrique dans `runtime-web-api` (`V3HttpCompositionPostgresTest`).

---

## 4. Réconciliation Exhaustive des 24 Erreurs Flyway

L'analyse contradictoire des rapports Surefire bruts XML et TXT de Q5-B3 sous `docs/testing/evidence/L5-Q5B/attempt-3-429529a6/surefire-reports/` confirme que l'ensemble des 24 erreurs observées dans `runtime-monolith` provient **exclusivement** de l'échec de `f.setup()` à la ligne 73 de `V3AdmissionFlowPostgresTest` (`FlywayValidateException`).

| N° | Classe Surefire | Méthode affectée | Type d'erreur | Cause primaire |
|:---:|---|---|:---:|---|
| 1 | `V3AdmissionFlowPostgresTest` | `allElevenPotOperationsThroughWorker` | `FlywayValidateException` | Setup Flyway V7..V9 après V10 |
| 2 | `V3AdmissionFlowPostgresTest` | `selfUsesWriteAndSurvivesBindingChange` | `FlywayValidateException` | Setup Flyway V7..V9 après V10 |
| 3 | `V3AdmissionFlowPostgresTest` | `resultsAndReadProgressIndependently` | `FlywayValidateException` | Setup Flyway V7..V9 après V10 |
| 4 | `V3AdmissionFlowPostgresTest` | `invalidBusinessIntentsAreAdmittedThenRejected` | `FlywayValidateException` | Setup Flyway V7..V9 après V10 |
| 5 | `V3AdmissionFlowPostgresTest` | `brunoB2PublicProtocol` | `FlywayValidateException` | Setup Flyway V7..V9 après V10 |
| 6 | `V3AdmissionFlowPostgresTest` | `voluntaryDeparturePreservesHistoryAndIdentity` | `FlywayValidateException` | Setup Flyway V7..V9 après V10 |
| 7 | `V3AdmissionFlowPostgresTest` | `registrationAndUnlinkThroughWorker` | `FlywayValidateException` | Setup Flyway V7..V9 après V10 |
| 8 | `V3InvitationHttpTest` | `confidentialLinkAndRestrictedPlaces` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 9 | `V3InvitationHttpTest` | `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 10 | `V3InvitationHttpTest` | `concurrentUnlinkAndAttachmentSerializeAtIdentity` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 11 | `V3InvitationHttpTest` | `unlinkCannotPreserveStaleInvitationAuthority` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 12 | `V3InvitationHttpTest` | `brunoB4StandaloneCorpus` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 13 | `V3InvitationReadPostgresTest` | `reconstructOnFreshReadWithoutProducer` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 14 | `V3InvitationReadPostgresTest` | `authenticatedEncryptionAndOldKeyRestoration` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 15 | `V3InvitationReadPostgresTest` | `monotoneDuplicateAndDivergence` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 16 | `V3InvitationTopologyPostgresTest` | `targetThreeFourPartitionsConnectionsRenewalAndRestart` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 17 | `V3InvitationTopologyPostgresTest` | `invitationPrivilegePreflightsRejectColumnEscalation` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 18 | `V3InvitationWritePostgresTest` | `oneGuestCannotTakeTwoPlaces` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 19 | `V3InvitationWritePostgresTest` | `twoGuestsOnePlaceAndReusableToken` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 20 | `V3InvitationWritePostgresTest` | `creatorOnlyAndExpiredCreation` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 21 | `V3InvitationWritePostgresTest` | `rollbackAfterInvitationPositionKeepsDensePublication` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 22 | `V3InvitationWritePostgresTest` | `expirationAfterPublicationCounterWait` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 23 | `V3InvitationWritePostgresTest` | `atomicCreationAndIdempotence` | `FlywayValidateException` | `setupInvitation()` via `f.setup()` |
| 24 | `V3LinkBrunoPostgresTest` | `realKeycloakLinkProtocol` | `FlywayValidateException` | Invoque `f.setup(testInfo)` |

**Bilan de réconciliation :**
- Somme exacte : $7 + 5 + 3 + 2 + 6 + 1 = \mathbf{24\text{ erreurs}}$.
- Erreurs primaires : 24 / 24 partagent la même cause primaire (`ReadMonolithI8Fixture` sautant V7..V9 avant V10).
- Erreurs secondaires ou non-Flyway : **0**. Aucune exception d'autre nature n'a été masquée.

---

## 5. Inventaire des Fichiers Modifiés

Les modifications sont strictement limitées à 6 fichiers de tests et fixtures :

| Fichier | Nature de la modification | Justification technique |
|---|---|---|
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/ReadMonolithI8Fixture.java` | Ajout de `"classpath:db/v3-admission/migration"` avant `"classpath:db/v3-invitation/migration"` | Ordonnancement canonique séquentiel V1..V10 sur la datasource d'écriture |
| `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/ReadRuntimeI8Fixture.java` | Ajout symétrique de `"classpath:db/v3-admission/migration"` | Respect du principe de synchronisation stricte des fixtures multi-participants (AUD-Q5BR-10) |
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3AdmissionFlowPostgresTest.java` | Ajout de `"classpath:db/v3-invitation/migration"` aux locations Flyway de `setup()` | Cohérence de validation Flyway avec le catalogue complet des migrations résolues |
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/ReadRootsCoexistencePostgresTest.java` | Remplacement de l'assertion instantanée par `await()` sur les 28 baux | Élimination de la fragilité temporelle liée aux micro-suspensions de baux lors de renouvellements concurrents |
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3MonolithHttpCompositionPostgresTest.java` | Mise à jour de l'oracle HTTP vers l'ensemble exact des 10 routes V3 | Prise en compte légitime de `V3InvitationController` (L5/I4) sous `v3-http-read` |
| `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/V3HttpCompositionPostgresTest.java` | Mise à jour symétrique de l'oracle HTTP vers l'ensemble exact des 10 routes V3 | Non-régression anticipée sur le module aval `runtime-web-api` |

---

## 6. Journal des Vérifications Dynamiques et Budget Consommé

Toutes les vérifications ont été exécutées en séquence stricte, avec capture des durées réelles de processus et des temps de test Surefire :

| Étape | Commande exécutée | Tests exécutés | Durée totale | Durée Surefire | Résultat |
|---|---|---|:---:|:---:|:---:|
| **C1** | `./mvnw -pl runtime-monolith,runtime-web-api,runtime-task-consumption-worker test-compile` | 0 (compilation) | 11,0 s | — | **SUCCESS** |
| **C2** | `./mvnw -pl runtime-monolith -am -Dtest=V3AdmissionFlowPostgresTest#allElevenPotOperationsThroughWorker ...` | 1 test | 25,5 s | 23,2 s | **PASS (1/1)** |
| **C3** | `./mvnw -pl runtime-monolith -am -Dtest=V3MonolithHttpCompositionPostgresTest#monolithRoot ...` | 1 test | 12,9 s | 10,6 s | **PASS (1/1)** |
| **C4** | `./mvnw -pl runtime-monolith -am -Dtest=ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely ...` | 1 test | 268,0 s | 266,7 s | **PASS (1/1)** |
| **C5.1** | `./mvnw -pl runtime-monolith -am -Dtest=V3InvitationWritePostgresTest#oneGuestCannotTakeTwoPlaces,V3InvitationReadPostgresTest#reconstructOnFreshReadWithoutProducer ...` | 2 tests | 29,0 s | 27,0 s | **PASS (2/2)** |
| **C5.2** | `./mvnw -pl runtime-monolith -am -Dtest=V3InvitationHttpTest#confidentialLinkAndRestrictedPlaces,V3InvitationTopologyPostgresTest#invitationPrivilegePreflightsRejectColumnEscalation ...` | 2 tests | 26,2 s | 23,8 s | **PASS (2/2)** |
| **C5.3** | `./mvnw -pl runtime-web-api -am -Dtest=V3HttpCompositionPostgresTest#webRoot ...` | 1 test | 7,4 s | 5,1 s | **PASS (1/1)** |
| **TOTAL** | — | **8 tests** | **379,9 s** (~6 min 20 s) | **356,4 s** | **100% PASS** |

### Suivi du Budget Normatif

- **Plafond absolu autorisé** : 25 minutes (1 500 secondes).
- **Cible indicative** : 15 minutes (900 secondes).
- **Temps dynamique cumulé consommé** : **379,9 secondes** (~6 minutes 20 secondes).
- **Consommation de la cible** : **42,2%** (marge restante : 520,1 secondes).
- **Consommation du plafond** : **25,3%** (marge restante : 1 120,1 secondes).
- **Arrêt des tests dynamiques (C6)** : déclenché immédiatement après l'obtention de preuves suffisantes, sans gaspillage du budget résiduel.

---

## 7. Préservation des Invariants et Non-Régression

1. **Intégrité de la production :**
   Aucune classe productive, aucune entité JPA, aucun adaptateur SQL, aucun contrôleur REST n'a été altéré. Les contrats d'admission, de projection, de commande et d'invitation restent rigoureusement intacts.
2. **Ordre canonique des migrations :**
   Aucune option `.outOfOrder(true)` n'a été injectée. La chaîne séquentielle de migrations V1 à V10 est validée et appliquée de manière déterministe dans toutes les fixtures monolithiques.
3. **Exclusion mutuelle des baux et non-concurrence :**
   Dans `ReadRootsCoexistencePostgresTest`, l'assertion vérifiant l'absence de double acquisition, l'unicité de possession sur les 28 baux de lecture dans PostgreSQL (`select count(*) from partition_ownership where owner_token is not null == 28`), ainsi que la temporisation stricte lors du takeover (`takeover >= remaining - 5000`) ont été conservées intégrales.
4. **Contrat d'exposition HTTP :**
   L'oracle HTTP ne s'est pas borné à un assouplissement de taille (`size() == 10`), mais valide l'exactitude de chaque URI (`Set.of(...)`), la présence conjointe de `V3ReadController` et `V3InvitationController`, et l'absence totale de contrôleurs ou de routes non autorisés.

---

## 8. Inventaire des Preuves et Empreintes SHA-256

Un espace de preuves dédié a été créé sous `docs/testing/evidence/L5-Q5B4/`. Il contient 19 fichiers traçables :

- `commands.json` (SHA-256: `6f9fa3587ca2aedc2e668e30febb6cd54ee5b8965877d23ca866859bab15d8e4`)
- `timings.json` (SHA-256: `e35d124c8956804bec6e7e53ebfebf29bb0b6912a0c8b57f49b6763d74f786a2`)
- `result.json` (SHA-256: `61d8a2c2948ec3b1c609e2c7ece2d829dc71f2be037d49d1f6d5bfef41e146d8`)
- `surefire-reports/TEST-com.kartaguez.pocoma.ReadRootsCoexistencePostgresTest.xml` (SHA-256: `588f5e6f46d51c767c4177d83d94b436c0e8314eeaaf23977919397e3959c2bb`)
- `surefire-reports/com.kartaguez.pocoma.ReadRootsCoexistencePostgresTest.txt` (SHA-256: `9a8264560a80e14cb7b4457e4e13589b940989f6cf3c1809ce035f299c824c04`)
- `surefire-reports/TEST-com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.xml` (SHA-256: `c1321afe015b532721a8f48eb47dbc9d21119b7655ec4c948ecd5b43a91050e6`)
- `surefire-reports/com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.txt` (SHA-256: `be6eb17e132ce4fa1e2b5b3aa77fa093c37326df0f8f8b80d0d8eb88f4b0051e`)
- `surefire-reports/TEST-com.kartaguez.pocoma.V3MonolithHttpCompositionPostgresTest.xml` (SHA-256: `a9cbf49bbcb3918b26002f2ee4e9f73f1d394ec6071efbe9a7702f232491bb4f`)
- `surefire-reports/com.kartaguez.pocoma.V3MonolithHttpCompositionPostgresTest.txt` (SHA-256: `18f782c5f1fa8519e4bc8351cc9ee6021f1e948c26bbbe631cc714d642445b20`)
- `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` (SHA-256: `d9426f42a5c531d054d86bfa3e82d33457cf68c4d284a7e780497dd0d6c1be74`)
- `surefire-reports/com.kartaguez.pocoma.V3InvitationWritePostgresTest.txt` (SHA-256: `41b4e3fbe3f629c48ea92c90c79ca33cb8e29a98ef1633519c72d622fcfa77e0`)
- `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationReadPostgresTest.xml` (SHA-256: `39703605bd2cdfa986038d605e79f78d10a3a7c3c0bc9d38c2d2aae8cdf32508`)
- `surefire-reports/com.kartaguez.pocoma.V3InvitationReadPostgresTest.txt` (SHA-256: `6909477e68cbbba652037920199e120da5d36e2ce563bc7523179fe9f337f44d`)
- `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` (SHA-256: `d9ae4a9645396fa2bfe7560bf68758223ceabc8ff21c74b9e424c40938b6bd9c`)
- `surefire-reports/com.kartaguez.pocoma.V3InvitationHttpTest.txt` (SHA-256: `d866635c94fa251d5c21df5c4cfc62788e00195e3474d28490a61fa70b991b1a`)
- `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.xml` (SHA-256: `32fad2ca95ed1e424c5d307f00e9d5ff3c0950783e950f7026105edfe0313ece`)
- `surefire-reports/com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.txt` (SHA-256: `ca782d4eb7f66a2b8eec4c27f6a7d5fb0d285b7e9f3b9dcfd6bc9f6368d184bf`)
- `surefire-reports/TEST-com.kartaguez.pocoma.V3HttpCompositionPostgresTest.xml` (SHA-256: `a521d32ee604900d466848c574645a7324ec2e21b38e6649e9bea1ae00dbd662`)
- `surefire-reports/com.kartaguez.pocoma.V3HttpCompositionPostgresTest.txt` (SHA-256: `a9386d9a1d1c81cb31e3ccb8df483b632ff492a00c6d267dd3f92dd2e01df3ae`)
- `manifest.json` récapitulatif indexant l'ensemble des fichiers ci-dessus.

---

## 9. État des Réserves

1. **Réserves résolues par Q5-B4 :**
   - `AUD-Q5B-03-A` (Flyway V7..V10) : **RÉSOLUE**.
   - `AUD-Q5B-03-B` (Acquisition 27/28) : **RÉSOLUE**.
   - `AUD-Q5B-03-C` (Routes HTTP 8 vs 10) : **RÉSOLUE**.
2. **Réserves historiques maintenues (intouchées) :**
   - Sensibilité temporelle Invitation (`AUD-Q5BR-09`) : oracle d'expiration d'invitation sous verrou.
   - Durée de lease non homologuée en production (`FIND-L2-01`) : 240 s comme configuration de test.
   - Sémaphore exact $Q=2$ pour le quota READ (`FIND-L4-01`) : réserve sous forte contention externe.
   - Coexistence des 60 modules (`FIND-TRANS-01`) : réservée au lot L7.
   - Absence de qualification globale d'architecture et E2E : oracles ArchUnit globaux (module 61) et suite Bruno B5 consolidée réservés à la certification finale.

---

## 10. Décision et Conclusion

Les trois anomalies identifiées lors de la troisième tentative Q5-B3 ont été auditées de manière indépendante, corrigées au plus juste niveau dans les tests et fixtures, et vérifiées dynamiquement par des tests discriminants sur PostgreSQL Testcontainers.

Le verdict technique terminal de la mission Q5-B4 est :

```
================================================================================
VERDICT Q5-B4 : READY_FOR_FINAL_CERTIFICATION
================================================================================
- Les trois causes racines sont démontrées et corrigées.
- Les 24 erreurs Flyway sont réconciliées et validées sans exception.
- Tous les tests discriminants requis sont à 100% PASS.
- Aucun défaut productif bloquant n'est découvert.
- Les invariants de concurrence, d'architecture et de données sont préservés.
- Les preuves complètes sont publiées et vérifiées sous docs/testing/evidence/L5-Q5B4/.
- Le budget dynamique est strictement respecté (379,9 s sur 1 500 s).
- La décision d'une ultime campagne globale de certification est autorisée.
================================================================================
```
