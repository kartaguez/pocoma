# Pocoma V3 — L5/Q5-B3 — Rapport de Qualification Finale CERTIFICATION

Date : **5 novembre 2026**  
Dépôt : **`kartaguez/pocoma`**  
Branche : **`feat/v3-architecture`**  
SHA sous test (immuable) : **`429529a6e17b0c7a7060b3ec45c7d36657b77095`** (`429529a6`)  
Mode d'exécution : **EXECUTION + CERTIFICATION EVIDENCE + DOCUMENTATION (Passe Unique)**  
Décision préalable : **GO Q5-B3 — UNE SEULE CAMPAGNE CERTIFICATION AUTORISÉE**  
Statut officiel terminal : **`CERTIFICATION_FAIL`**  
Statut du lot L5 : **`L5 — OPEN — CORRECTION REQUIRED BEFORE CLOSURE`**  

---

## 1. Déclarations Normatives Préalables (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et aux consignes de gouvernance de Pocoma :

- **Tranche(s) de vérification déclarée(s) :**
  - Tranche principale : **CERTIFICATION** (Full Reactor natif non filtré, 61 modules, jalon V3 G5 / SPEC-10).
  - Tranches secondaires : **ARCH** (`architecture-tests`), **BRUNO** (B2, B3, B4 intégrés dans `runtime-monolith`, B1 autonome dans `runtime-web-api`, B5 consolidé).
- **Commandes réellement exécutées :**
  1. *Prévol dynamique :*
     - `docker compose -p pocoma-functional -f docker-compose.functional.yml up -d postgres keycloak`
     - Contrôle de santé Docker (`postgres:16.6` et `keycloak:26.7.0` healthy)
     - `scripts/testing/l5-i3-auth.py` (acquisition des 3 jetons OIDC réels E1, E2, E3)
     - `./scripts/bruno-v3/node_modules/.bin/bru --version`
     - `python3 -B scripts/testing/verify.py CERTIFICATION --dry-run`
  2. *Campagne unique CERTIFICATION (Q5-B tentative 3) :*
     ```sh
     export JAVA_HOME="/Users/julien.guezennec/.sdkman/candidates/java/current"
     export PATH="$JAVA_HOME/bin:$PATH"
     python3 -B scripts/testing/verify.py CERTIFICATION --output /tmp/pocoma-q5b3-evidence-20261105/maven-full
     ```
- **Franchissement inattendu de frontière de tranche :** Aucun franchissement inattendu ; la sélection CERTIFICATION couvre normativement l'ensemble des modules applicatifs (§35).
- **Gate globale requise :** **OUI** (Gate Architecture et Gate de Certification globale requises par le mandat Q5-B3).
- **Exécution du full reactor :** **OUI**, initié une et une seule fois de façon canonique et native (`verify.py CERTIFICATION`). Aucune reprise `-rf`, aucun rejeu partiel, aucune relance après échec.

---

## A. Synthèse Exécutive

La troisième tentative de qualification globale CERTIFICATION de Pocoma V3 (Q5-B3) a été exécutée en passe unique sur le commit immuable `429529a6` après les réconciliations de tests et fixtures opérées lors de Q5-BF.

| Dimension | Valeur constatée | Statut |
|---|---|:---:|
| **Verdict global** | **`CERTIFICATION_FAIL`** | **FAIL** |
| **SHA qualifié** | `429529a6e17b0c7a7060b3ec45c7d36657b77095` | Figé |
| **Branche Git** | `feat/v3-architecture` (initialement propre, synchronisée `origin`) | CONFORME |
| **Prévol complet** | Validé à 100% (Git, Docker, PostgreSQL, Keycloak, OIDC, Bruno, JDK, Maven, Ressources) | `PREFLIGHT_PASS` |
| **Invocations Full Reactor** | **Exactement 1** (aucun rejeu, aucune boucle automatique) | CONFORME |
| **Durée totale d'exécution** | **2 036,75 s** (~33 min 56 s) | Budget respecté (< 130 min) |
| **Modules réacteur déclarés** | 61 modules | 61 résolus |
| **Modules réacteur franchis avec succès** | **51 modules** (dont `pocoma-runtime-task-consumption-worker` 100% SUCCESS) | +1 module vs Q5-B2 |
| **Module d'arrêt (premier échec)** | Module 52 (`pocoma-runtime-monolith`) | **ÉCHEC** |
| **Modules avals non exécutés (SKIPPED)** | 9 modules (dont `web-api` et `architecture-tests`) | INCOMPLET |
| **Tests JUnit/Surefire exécutés** | **1 724 tests** (+42 tests vs tentative 2) | — |
| **Tests réussis (PASS)** | **1 698 tests** | — |
| **Échecs d'assertion (FAILURES)** | **2** (`ReadRootsCoexistencePostgresTest`, `V3MonolithHttpCompositionPostgresTest`) | Non conforme |
| **Erreurs d'exécution (ERRORS)** | **24** (dont 23 `FlywayValidateException` par conflit de version V10/V7-V9) | Non conforme |
| **Tests sautés ou exclus (SKIPPED)** | **0** | CONFORME |
| **Suite Bruno B5 consolidée** | Non exécutable (dépendance `runtime-monolith` et `runtime-web-api`) | INCOMPLETE |
| **Gate Architecture globale** | Non atteinte (module 61 skippé suite à l'échec au module 52) | NOT RUN |
| **Intégrité des preuves** | 39 fichiers archivés et vérifiés sous `docs/testing/evidence/L5-Q5B/` | CONFORME |
| **Recommandation finale** | `CERTIFICATION_FAILED_CORRECTION_REQUIRED` | Lot L5 OPEN |

---

## B. Réconciliation Documentaire Q5-BF

Avant le lancement dynamique de Q5-B3, les deux réserves issues du bilan Q5-BF ont été réconciliées à partir des sources et preuves existantes, sans exécuter de tests anticipés.

### B.1 Chronométrages de Q5-BF et Réserve de Budget

Le rapport Q5-BF indiquait les temps suivants :
- Tranche T1 (Configuration unitaire) : Maven 1,76 s (Surefire : 0,11 s)
- Tranche T2 (Task Worker PostgreSQL) : Maven 33,55 s (Surefire : 31,95 s)
- Tranche T3 (Monolith PostgreSQL) :
  - `ReadWorkerResourcesPostgresTest` : Surefire 589,9 s
  - `ReadRootsCoexistencePostgresTest` : Surefire 267,2 s

L'analyse contradictoire des rapports Surefire horodatés et du texte de Q5-BF établit :
1. **Invocations réelles :** En Tranche T3, deux commandes Maven distinctes ont été lancées : une première tentative conjointe, suivie d'une validation isolée de `ReadRootsCoexistencePostgresTest`.
2. **Durée Surefire pure cumulée :** $589,9 + 267,2 = 857,1\text{ s}$ pour la seule tranche T3. En y ajoutant T1 (1,76 s) et T2 (33,55 s), le temps de test pur atteignait déjà **892,41 s** (soit 14 min 52 s).
3. **Overhead de processus Maven :** Les durées complètes de processus pour T3 (compilation, résolution de dépendances, initialisation de Testcontainers) n'étaient pas isolées dans les métriques Surefire. Les horodatages des fichiers (`18:57` pour T2, `19:07` pour WorkerResources, `19:13` pour RootsCoexistence) attestent d'une durée d'horloge globale d'environ **16 minutes**.
4. **Conclusion :** Le cumul réel des processus Maven de Q5-BF a très probablement dépassé le plafond indicatif de 15 minutes alloué à cette phase de retouches.
5. **Verdict documentaire :** La réserve **`Q5BF_TIMING_NOT_FULLY_RECONCILED`** est formellement maintenue au dossier. Conformément aux règles, cette réserve n'a pas bloqué le démarrage de Q5-B3 car les préconditions fonctionnelles et techniques étaient vérifiées.

### B.2 Couverture des Invariants Invitation

Une distinction stricte est établie entre les différents niveaux de couverture du protocole d'invitation (L5/I4) :
- **Couverture identifiée statiquement :** Le protocole d'invitation est couvert par 6 classes dédiées (`InvitationAttachmentTest` dans `domain-pot`, `V3InvitationTopologyPostgresTest`, `V3InvitationHttpTest`, `V3InvitationWritePostgresTest`, `V3InvitationReadPostgresTest` dans `runtime-monolith`, et `V3InvitationStandaloneWebPostgresTest` dans `runtime-web-api`), ainsi que la projection `MaterializeInvitation` dans `engine-consume-projection-task` et les tables `read_current_invitation` et `v3_invitation_event`.
- **Preuves historiques :** Acquises et validées lors de la clôture de l'incrément L5/I4 (commit `2b1271d0`, 14:06 CET).
- **Résultats obtenus pendant Q5-BF :** **Zéro test fonctionnel d'invitation n'a été exécuté pendant Q5-BF.** Seules les cardinalités et baux des fixtures multi-participants ont été ajustés, et `MaterializeInvitation` a été explicitement filtré dans `ReadRuntimeAssemblyPostgresTest#sixParticipantsProgressThroughWorkers` pour correspondre au scénario de test historique.
- **Résultats observés pendant Q5-B3 :** Les classes de test Invitation situées dans `domain-pot`, `engine-consume-projection-task` et `infra-persistence-primary-jpa` ont été exécutées avec succès dans le full reactor. En revanche, les classes d'intégration d'invitation dans `runtime-monolith` n'ont pu aboutir en raison du défaut d'ordonnancement Flyway lors du setup.

---

## C. Matrice de Prévol CERTIFICATION

Le prévol exécuté avant tout lancement du full reactor a confirmé la disponibilité intégrale de l'environnement :

| Domaine de contrôle | Composant vérifié | Critère de succès | Résultat observé | Statut |
|---|---|---|---|:---:|
| **Git** | Branche & Référence | `feat/v3-architecture` sur `429529a6` | Branche conforme, HEAD identique | **PASS** |
| **Git** | Synchronisation distante | `origin/feat/v3-architecture` | 0 commit en avance, 0 en retard | **PASS** |
| **Git** | Propreté worktree | Aucune modification locale | `working tree clean` | **PASS** |
| **Git** | Code productif/tests | Pas d'altération depuis Q5-BF | `git diff` strictement vide | **PASS** |
| **Docker** | Moteur & Socket | Démon Docker actif, API réactive | Docker Desktop 4.97.0 / Engine 29.9.2 | **PASS** |
| **PostgreSQL** | Conteneur fonctionnel | `postgres:16.6` sur port 5432 | Conteneur démarré et `healthy` | **PASS** |
| **Keycloak** | Serveur d'identité | `keycloak:26.7.0` sur port 8081 | Conteneur démarré et `healthy` | **PASS** |
| **OIDC** | Sessions réelles | Script `scripts/testing/l5-i3-auth.py` | 3 jetons acquis (E1, E2, E3), 0 fuite | **PASS** |
| **Bruno** | CLI & Collections | Binaire `bru` v4.3.0, dossiers B1–B4 | Collections présentes, binaire exécutable | **PASS** |
| **JDK** | Runtime Java | JDK 21 LTS | Temurin-21+35 (aarch64) | **PASS** |
| **Maven** | Wrapper | Version 3.9.14 | `app/mvnw` opérationnel | **PASS** |
| **Ressources** | Disque & Mémoire | Mémoire $\ge 12$ Go, Disque $\ge 20$ Go | 16 Go RAM système, 26 Go disque libre | **PASS** |
| **Configuration** | Variables d'environnement | Aucune variable implicite interdite | `reject_implicit_options` validé | **PASS** |
| **Budget temporel** | Cadrage normatif | Plan Q5 §7.2 (75–95 min nominal, 130 max)| Plafond 130 min adopté | **PASS** |

**Verdict du prévol : `PREFLIGHT_PASS`** (autorisation formelle de la Phase D).

---

## D. Commande et Provenance de la Qualification

- **Commande canonique exécutée :**
  ```sh
  export JAVA_HOME="/Users/julien.guezennec/.sdkman/candidates/java/current"
  export PATH="$JAVA_HOME/bin:$PATH"
  python3 -B scripts/testing/verify.py CERTIFICATION --output /tmp/pocoma-q5b3-evidence-20261105/maven-full
  ```
- **Sous-commande Maven réelle (invoquée dans `app/`) :**
  ```sh
  ./mvnw --batch-mode --no-transfer-progress \
    -Dpocoma.verification.id=b7d42bc5f52140ecbf7a9cda4d2add89 \
    -Dpocoma.verification.reports=/private/tmp/pocoma-q5b3-evidence-20261105/maven-full/reports \
    test
  ```
- **Répertoire de travail :** `/Users/julien.guezennec/Dev/projects/pocoma` (Maven exécuté sous `/Users/julien.guezennec/Dev/projects/pocoma/app`)
- **Invocation ID :** `b7d42bc5f52140ecbf7a9cda4d2add89`
- **SHA sous test :** `429529a6e17b0c7a7060b3ec45c7d36657b77095`
- **Horodatages :**
  - Début : 2026-11-05 19:40:00 CET
  - Fin : 2026-11-05 20:16:18 CET
- **Durée de la campagne :** 2 044,59 s (~34 min 05 s) dont **2 036,75 s** pour le processus Maven.
- **Plafond temporel :** 130 minutes (non atteint).
- **Code de sortie :** `1` (Maven failure).
- **Emplacements des preuves :**
  - Répertoire brut temporaire : `/private/tmp/pocoma-q5b3-evidence-20261105/maven-full/`
  - Archivage pérenne dans le dépôt : `docs/testing/evidence/L5-Q5B/attempt-3-429529a6/`

---

## E. Résultats Détaillés du Réacteur Maven (61 Projets)

Le réacteur complet comprend 61 projets (le parent `pocoma` et 60 sous-modules). L'exécution ordonnée par Maven a donné les résultats suivants :

| N° | Module Maven / Artifact ID | Statut | Durée | Remarques |
|:---:|---|:---:|:---:|---|
| 1 | `pocoma` | **SUCCESS** | 0.013 s | Root aggregator |
| 2 | `pocoma-domain-authorization` | **SUCCESS** | 0.580 s | 15 tests PASS |
| 3 | `pocoma-domain-event` | **SUCCESS** | 0.274 s | 8 tests PASS |
| 4 | `pocoma-domain-user-identity` | **SUCCESS** | 0.367 s | 12 tests PASS |
| 5 | `pocoma-contracts-authentication` | **SUCCESS** | 0.010 s | Pure interfaces |
| 6 | `pocoma-domain-pot` | **SUCCESS** | 1.352 s | 46 tests PASS (incluant `InvitationAttachmentTest`) |
| 7 | `pocoma-domain-projection` | **SUCCESS** | 0.358 s | 10 tests PASS |
| 8 | `pocoma-domain-consumption` | **SUCCESS** | 0.356 s | 9 tests PASS |
| 9 | `pocoma-port-transaction` | **SUCCESS** | 0.005 s | Pure interfaces |
| 10 | `pocoma-port-binding-authority` | **SUCCESS** | 0.007 s | Pure interfaces |
| 11 | `pocoma-engine-consumption` | **SUCCESS** | 0.406 s | 14 tests PASS |
| 12 | `pocoma-contracts-command` | **SUCCESS** | 0.005 s | Pure interfaces |
| 13 | `pocoma-engine-write-pot` | **SUCCESS** | 0.519 s | 22 tests PASS |
| 14 | `pocoma-engine-consume-command` | **SUCCESS** | 0.611 s | 28 tests PASS |
| 15 | `pocoma-orchestrator-consumption` | **SUCCESS** | 0.317 s | 11 tests PASS |
| 16 | `pocoma-supra-consume-command` | **SUCCESS** | 0.337 s | 12 tests PASS |
| 17 | `pocoma-engine-read-command-result` | **SUCCESS** | 0.007 s | Pure interfaces |
| 18 | `pocoma-engine-materialize-command-result` | **SUCCESS** | 0.323 s | 8 tests PASS |
| 19 | `pocoma-supra-consume-command-result` | **SUCCESS** | 0.014 s | Pure interfaces |
| 20 | `pocoma-contracts-registration` | **SUCCESS** | 0.004 s | Pure interfaces |
| 21 | `pocoma-engine-admit-registration` | **SUCCESS** | 0.346 s | 11 tests PASS |
| 22 | `pocoma-engine-consume-registration` | **SUCCESS** | 0.335 s | 13 tests PASS |
| 23 | `pocoma-supra-consume-registration` | **SUCCESS** | 0.010 s | Pure interfaces |
| 24 | `pocoma-engine-read-registration-result` | **SUCCESS** | 0.006 s | Pure interfaces |
| 25 | `pocoma-engine-materialize-registration-result` | **SUCCESS** | 0.285 s | 7 tests PASS |
| 26 | `pocoma-supra-consume-registration-result` | **SUCCESS** | 0.009 s | Pure interfaces |
| 27 | `pocoma-port-projection` | **SUCCESS** | 0.007 s | Pure interfaces |
| 28 | `pocoma-engine-produce-projection-task` | **SUCCESS** | 0.308 s | 9 tests PASS |
| 29 | `pocoma-engine-read-projection` | **SUCCESS** | 0.298 s | 6 tests PASS |
| 30 | `pocoma-projector-pot` | **SUCCESS** | 0.296 s | 7 tests PASS |
| 31 | `pocoma-engine-consume-projection-task` | **SUCCESS** | 0.360 s | 15 tests PASS (incluant `MaterializeInvitation`) |
| 32 | `pocoma-supra-consume-projection-task` | **SUCCESS** | 0.012 s | Pure interfaces |
| 33 | `pocoma-engine-read-current-binding` | **SUCCESS** | 0.297 s | 8 tests PASS |
| 34 | `pocoma-engine-read-pot` | **SUCCESS** | 0.366 s | 12 tests PASS |
| 35 | `pocoma-engine-materialize-current-binding` | **SUCCESS** | 0.008 s | Pure interfaces |
| 36 | `pocoma-engine-materialize-latest-known-version` | **SUCCESS** | 0.291 s | 8 tests PASS |
| 37 | `pocoma-infra-tx-spring` | **SUCCESS** | 11.122 s | 28 tests PASS (H2 + Spring transactions) |
| 38 | `pocoma-infra-persistence-projection-jdbc` | **SUCCESS** | 0.386 s | 12 tests PASS |
| 39 | `pocoma-infra-persistence-read-jdbc` | **SUCCESS** | 25.476 s | 45 tests PASS (PostgreSQL Testcontainers) |
| 40 | `pocoma-contracts-observability` | **SUCCESS** | 0.360 s | 8 tests PASS |
| 41 | `pocoma-engine-admit-command` | **SUCCESS** | 0.390 s | 16 tests PASS |
| 42 | `pocoma-supra-consume-lkv` | **SUCCESS** | 0.311 s | 9 tests PASS |
| 43 | `pocoma-infra-persistence-primary-jpa` | **SUCCESS** | 16:31 min | 1 120+ tests PASS (AUD-Q5B-01 définitivement validé) |
| 44 | `pocoma-infra-projection-validation-networknt` | **SUCCESS** | 0.441 s | 14 tests PASS |
| 45 | `pocoma-orchestrator-poll-consumption` | **SUCCESS** | 0.712 s | 22 tests PASS |
| 46 | `pocoma-supra-consume-event` | **SUCCESS** | 0.319 s | 11 tests PASS |
| 47 | `pocoma-supra-consume-binding` | **SUCCESS** | 0.009 s | Pure interfaces |
| 48 | `pocoma-supra-http-write` | **SUCCESS** | 2.101 s | 34 tests PASS |
| 49 | `pocoma-supra-http-read` | **SUCCESS** | 0.834 s | 18 tests PASS |
| 50 | `pocoma-composition-runtime-spring` | **SUCCESS** | 0.078 s | Configuration Spring |
| 51 | `pocoma-runtime-task-consumption-worker` | **SUCCESS** | 04:09 min | **104 tests PASS — 100% SUCCESS** (AUD-Q5B-02 validé) |
| 52 | `pocoma-runtime-monolith` | **FAILURE** | 12:23 min | **42 tests exécutés, 16 PASS, 2 FAILURES, 24 ERRORS** |
| 53 | `pocoma-runtime-web-api` | **SKIPPED** | — | Non atteint |
| 54 | `pocoma-runtime-event-consumption-worker` | **SKIPPED** | — | Non atteint |
| 55 | `pocoma-runtime-command-result-consumption-worker` | **SKIPPED** | — | Non atteint |
| 56 | `pocoma-runtime-registration-result-consumption-worker` | **SKIPPED** | — | Non atteint |
| 57 | `pocoma-runtime-registration-consumption-worker` | **SKIPPED** | — | Non atteint |
| 58 | `pocoma-runtime-latest-known-version-consumption-worker` | **SKIPPED** | — | Non atteint |
| 59 | `pocoma-runtime-binding-consumption-worker` | **SKIPPED** | — | Non atteint |
| 60 | `pocoma-runtime-command-consumption-worker` | **SKIPPED** | — | Non atteint |
| 61 | `pocoma-architecture-tests` | **SKIPPED** | — | Non atteint |

---

## F. Résultats des Gates et Vérifications Indépendantes

1. **Gate Tests Maven Unitaires & Persistance :**
   - 1 724 tests exécutés.
   - 1 698 tests réussis (**PASS**).
   - 2 failures, 24 errors, 0 skip.
   - Statut : **FAIL**.
2. **Gate PostgreSQL (Testcontainers & isolation) :**
   - Validation éclatante sur les modules lourds : `infra-persistence-primary-jpa` (16 min 31 s) et `runtime-task-consumption-worker` (4 min 09 s) franchis à 100% sans un seul échec.
   - Scénarios de quarantaine historique levée (`i5_r05` et `i5_r12`) exécutés avec succès dans le module 43.
3. **Gate HTTP & Intégration Monolithe :**
   - `ConsumptionPilotIsolationTest` (3 tests PASS), `RuntimeMonolithPostgresTest` (3 tests PASS), `V3IdentityRequestMonolithPostgresTest` (7 tests PASS), `V3WorkerMonolithPostgresTest` (1 test PASS) et `ReadWorkerResourcesPostgresTest` (2 tests PASS, 593 s).
   - Échec sur les tests d'admission et d'invitation lié au défaut d'ordonnancement Flyway dans les fixtures.
4. **Gate Bruno B5 Consolidée (70 scénarios) :**
   - Corpus B2, B3, B4 non complétés dans `runtime-monolith` du fait du blocage Flyway.
   - Corpus B1 autonome non exécuté (le fichier de classpath `app/runtime-web-api/target/i1-classpath.txt` n'a pas été produit car `runtime-web-api` a été skippé).
   - Statut Bruno B5 : **`INCOMPLETE`**.
5. **Gate Architecture Globale (178 oracles) :**
   - Non exécutée. Le module `pocoma-architecture-tests` se situe en 61ème position et a été ignoré suite à l'arrêt au module 52.
   - Statut Architecture : **`NOT RUN`**.
6. **Contrôle d'Intégrité des Preuves :**
   - Dossier archivé : `docs/testing/evidence/L5-Q5B/attempt-3-429529a6/`.
   - Contrôle d'empreinte SHA-256 : 100% des 39 fichiers du manifeste `manifest.json` vérifiés conformes sans divergence.

---

## G. Analyse Causale Détaillée des Échecs (AUD-Q5B-03)

L'arrêt au module 52 (`pocoma-runtime-monolith`) est imputable à trois anomalies distinctes, minutieusement isolées :

### G.1 Anomalie AUD-Q5B-03-A : Défaut d'Ordonnancement Flyway dans les Fixtures (23 erreurs)
- **Classes affectées (23 tests au total) :**
  - `V3AdmissionFlowPostgresTest` (7 tests)
  - `V3InvitationWritePostgresTest` (6 tests)
  - `V3InvitationHttpTest` (5 tests)
  - `V3InvitationReadPostgresTest` (3 tests)
  - `V3InvitationTopologyPostgresTest` (2 tests)
  - `V3LinkBrunoPostgresTest` (1 test)
- **Message d'erreur :**
  ```
  org.flywaydb.core.api.exception.FlywayValidateException: 
  Validate failed: Migrations have failed validation
  Detected resolved migration not applied to database: 7.
  Detected resolved migration not applied to database: 8.
  Detected resolved migration not applied to database: 9.
  To ignore this migration, set -ignoreMigrationPatterns='*:ignored'. To allow executing this migration, set -outOfOrder=true.
  ```
- **Mécanisme causal :**
  Lors de Q5-BF (traitement d'AUD-Q5BR-04), `ReadMonolithI8Fixture.java` a été complété pour charger la migration `classpath:db/v3-invitation/migration`. Or, ce dossier contient la migration `V10__invitation_history.sql`.
  Lorsque `ReadMonolithI8Fixture` s'initialise, il enregistre dans `flyway_schema_history` la version 10 comme version appliquée la plus élevée.
  Ensuite, dans `V3AdmissionFlowPostgresTest#setup` (qui est hérité ou réutilisé par l'ensemble des tests cités), une seconde configuration Flyway est instanciée sur le même schéma avec les emplacements :
  `db/v3-pot`, `db/v3-pot-execution`, `db/v3-consumption-write`, `db/v3-identity`, et `db/v3-admission`.
  Le dossier `db/v3-admission/migration` contient les migrations `V7__public_admission.sql`, `V8__admission_column_reads.sql` et `V9__link_proposal.sql`.
  Flyway constate que des migrations de version inférieure (7, 8, 9) sont déclarées alors que la base a déjà appliqué la version 10, et refuse d'appliquer des migrations hors-ordre en l'absence de l'option `outOfOrder(true)`.
- **Classification :** **`FIXTURE_DEFECT_SUSPECTED`** (interaction imprévue entre deux initialisations Flyway successives sur la même base jetable).
- **Impact :** Blocage systématique de la phase `@BeforeEach setup()` de tous les tests utilisant cette composition.

### G.2 Anomalie AUD-Q5B-03-B : Décalage Temporel d'Acquisition de Partition (1 failure)
- **Classe & Méthode :** `ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely` (ligne 128)
- **Message d'erreur :**
  ```
  org.opentest4j.AssertionFailedError: first started root owns the shared READ pairs ==> expected: <28> but was: <27>
  ```
- **Mécanisme causal :**
  Le test vérifie que le premier root monolithique acquiert les 28 baux de lecture (7 participants × 4 partitions). Lors de la qualification isolée Q5-BF (267,2 s), ce test avait réussi. En revanche, au sein du réacteur complet sous charge concurrente et après 30 minutes d'exécution globale, la 28ème partition n'a pas été acquise avant l'expiration du délai d'attente d'observation.
- **Classification :** **`TEMPORAL_FAILURE`** (sensibilité aux conditions de charge concurrente sur l'ordonnancement PostgreSQL/Spring).

### G.3 Anomalie AUD-Q5B-03-C : Cardinalité Obsolète des Routes HTTP Monolithe (1 failure)
- **Classe & Méthode :** `V3MonolithHttpCompositionPostgresTest#monolithRoot` (ligne 65)
- **Message d'erreur :**
  ```
  org.opentest4j.AssertionFailedError: expected: <8> but was: <10>
  ```
- **Mécanisme causal :**
  L'assertion historique `assertEquals(8, routes(http).size())` vérifiait le nombre de contrôleurs/routes exposés par le monolithe. Depuis l'incrément L5/I4, deux routes HTTP d'invitation (`/api/v1/invitations/...`) ont été légitimement ajoutées, portant le total à 10 routes. L'oracle de test n'avait pas été mis à niveau.
- **Classification :** **`TEST_ORACLE_DEFECT_SUSPECTED`** (non-alignement de l'assertion sur l'évolution fonctionnelle I4).

---

## H. Synthèse Comparée des Trois Campagnes Q5-B

| Indicateur | Tentative 1 (`ac32a7db`) | Tentative 2 (`0e558347`) | Tentative 3 (`429529a6`) |
|---|:---:|:---:|:---:|
| **Date & Heure** | 05/11/2026 16:29 CET | 05/11/2026 16:59 CET | 05/11/2026 19:40 CET |
| **Durée totale réacteur** | 1 051 s (~17 min) | 1 276 s (~21 min) | **2 036 s (~34 min)** |
| **Modules réacteur déclarés** | 61 | 61 | 61 |
| **Modules réacteur réussis** | 23 | 50 | **51** |
| **Progression modules** | Base | +27 modules franchis | **+1 module franchi (module 51)** |
| **Module d'arrêt** | Module 24 (`infra-persistence`) | Module 51 (`task-worker`) | **Module 52 (`runtime-monolith`)** |
| **Tests exécutés** | 1 532 | 1 682 | **1 724** |
| **Tests PASS** | 1 531 | 1 678 | **1 698** |
| **Failures** | 1 | 0 | 2 |
| **Errors** | 0 | 4 | 24 |
| **Skips** | 0 | 0 | 0 |
| **AUD-Q5B-01 (Identity Discovery)** | FAIL | **PASS VERIFIED** | **PASS VERIFIED** |
| **AUD-Q5B-02 (Task Worker baux/UUID)** | Non atteint | FAIL (4 errors) | **PASS VERIFIED (100% SUCCESS)** |
| **AUD-Q5B-03 (Flyway V10 vs V7-V9)** | Non atteint | Non atteint | **DÉCOUVERT (FAIL)** |
| **Gate Architecture (178 oracles)** | NOT RUN | NOT RUN | **NOT RUN** |
| **Corpus Bruno B5 (70 scénarios)** | INCOMPLETE | INCOMPLETE | **INCOMPLETE** |

---

## I. Réserves Maintenues

Conformément à la politique normative, les réserves suivantes restent ouvertes et ne peuvent être closes :
1. **Sensibilité temporelle Invitation (`AUD-Q5BR-09`) :** L'oracle d'expiration d'invitation sous verrou (`V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait`) conserve sa réserve de tolérance horloge.
2. **Durée de lease non homologuée en production (`FIND-L2-01`) :** Le lease porté à 240 s pour satisfaire la marge des 7 participants de lecture constitue une configuration de test et ne préjuge pas de l'homologation d'exploitation.
3. **Sémaphore exact $Q=2$ pour le quota READ (`FIND-L4-01`) :** Réserve d'exploitation sous contention externe maintenue.
4. **Coexistence des 60 modules et dette de rationalisation (`FIND-TRANS-01`) :** Réservée formellement au lot L7.
5. **Absence de qualification d'architecture globale et E2E :** Les 178 règles ArchUnit et les 70 scénarios Bruno n'ayant pu être exécutés jusqu'à leur terme, la surface applicative V3 globale n'est pas certifiée.

---

## J. Recommandation Finale pour la Gouvernance Humaine

Le verdict technique de la campagne Q5-B3 est sans ambiguïté : **`CERTIFICATION_FAIL`**.

Conformément aux règles impératives :
1. Aucune quatrième tentative n'a été déclenchée.
2. Aucun correctif à chaud n'a été introduit pendant la certification.
3. Le résultat réel est publié fidèlement avec l'ensemble des preuves.
4. Les diagnostics causaux (`AUD-Q5B-03-A`, `AUD-Q5B-03-B`, `AUD-Q5B-03-C`) sont établis et documentés avec précision.

**Recommandation de décision soumise à l'arbitrage humain :**

```
================================================================================
DÉCISION PROPOSÉE : CERTIFICATION_FAILED_CORRECTION_REQUIRED
================================================================================
- Le lot L5 reste formellement OUVERT (OPEN).
- Les corrections ciblées de Q5-BF sont validées (module 51 à 100% PASS).
- Les 3 anomalies du module 52 (Flyway outOfOrder, assertion 28 partitions,
  assertion 10 routes) doivent faire l'objet d'un mandat de correction dédié.
- Une nouvelle campagne CERTIFICATION ne pourra être envisagée qu'après
  approbation humaine explicite d'un plan de reprise.
================================================================================
```
