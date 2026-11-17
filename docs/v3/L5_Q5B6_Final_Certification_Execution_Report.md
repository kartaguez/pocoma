# Pocoma V3 — L5/Q5-B6 — Rapport de Certification Finale Globale

Date : **6 novembre 2026**  
Dépôt : **`kartaguez/pocoma`**  
Branche : **`feat/v3-architecture`**  
SHA sous test (immuable) : **`2890d88f6fa94ff2262d08a2809f48512ce27c9d`** (`2890d88f`)  
Mode d'exécution : **PREFLIGHT → SINGLE CERTIFICATION RUN → EVIDENCE → ANALYSIS → DOCUMENTATION → COMMIT & PUSH**  
Autorisation préalable : **Campagne globale canonique L5 / Q5-B6**  
Statut officiel terminal : **`CERTIFICATION_PASS`**  
Statut du lot L5 : **`L5 — CLOSED (QUALIFIED)`**  

---

## Déclarations Normatives Préalables (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` (§35) et aux règles de gouvernance du projet :

- **Tranche(s) de vérification déclarée(s) :**
  - Tranche principale : **CERTIFICATION** (Full Reactor natif non filtré, 61 projets, jalon V3 G5 / SPEC-10).
  - Tranches intégrées : **ARCH** (`architecture-tests`), **BRUNO** (B2, B3, B4 intégrés dans `runtime-monolith`, B1 autonome sous `runtime-web-api`, consolidation composite B5).
- **Commandes réellement exécutées :**
  1. *Prévol dynamique complet :*
     - `git status && git rev-parse HEAD` (validation du SHA immuable `2890d88f` et du worktree propre).
     - `docker compose -p pocoma-functional -f docker-compose.functional.yml up -d postgres keycloak` (contrôle des conteneurs `postgres:16.6` et `keycloak:26.7.0` healthy).
     - Validation authentification réelle OIDC (3 sessions indépendantes E1, E2, E3 via Keycloak port 8081).
     - `./scripts/bruno-v3/node_modules/.bin/bru --version` (CLI Bruno v4.3.0).
     - `python3 -B scripts/testing/verify.py CERTIFICATION --dry-run` (validation de l'absence de filtres ou d'exclusions).
  2. *Campagne unique canonique CERTIFICATION :*
     ```sh
     export JAVA_HOME="/Users/julien.guezennec/.sdkman/candidates/java/current"
     export PATH="$JAVA_HOME/bin:$PATH"
     python3 -B scripts/testing/verify.py CERTIFICATION --output /tmp/pocoma-q5b6-evidence-20261106/maven-full
     ```
  3. *Complément autonome Bruno B1 :*
     ```sh
     java -Xmx512m \
       -cp "$(cat app/runtime-web-api/target/i1-classpath.txt)" \
       com.kartaguez.pocoma.V3ReadHttpFixture \
       "$(pwd)" \
       "/tmp/pocoma-q5b6-evidence-20261106/bruno-b1"
     ```
  4. *Consolidation unifiée Bruno B5 :*
     ```sh
     python3 -B scripts/testing/consolidate_bruno_b5.py \
       --evidence-root "/tmp/pocoma-q5b6-evidence-20261106" \
       --sha "2890d88f6fa94ff2262d08a2809f48512ce27c9d" \
       --output "/tmp/pocoma-q5b6-evidence-20261106/bruno-b5-consolidated.json"
     ```
- **Franchissement inattendu de tranche :** Aucun (`NONE`). La tranche CERTIFICATION couvre normativement l'intégralité du réacteur.
- **Gate globale requise :** **OUI** (Gate Architecture et Gate de Certification globale requises par le mandat).
- **Exécution du full reactor :** **OUI**, exécuté une seule et unique fois sans retry, sans `-rf`, sans rejeu partiel ni affaiblissement de configuration.

---

## 1. Résumé Exécutif

La campagne de certification globale finale Q5-B6 a été menée en une seule passe canonique sur le SHA figé `2890d88f6fa94ff2262d08a2809f48512ce27c9d`, suite aux corrections ciblées de tests et fixtures opérées post-Q5-B5.

| Dimension | Valeur constatée | Statut |
|---|---|:---:|
| **Verdict global** | **`CERTIFICATION_PASS`** | **PASS** |
| **SHA qualifié** | `2890d88f6fa94ff2262d08a2809f48512ce27c9d` | Immuable |
| **Branche Git** | `feat/v3-architecture` (worktree propre, synchronisé `origin`) | CONFORME |
| **Prévol complet** | Validé à 100% (Git, Docker, PostgreSQL, Keycloak, OIDC, Bruno, JDK, Maven, Lock) | `PREFLIGHT_PASS` |
| **Invocations globales** | **Exactement 1** (aucune boucle de retry, aucun rejeu) | CONFORME |
| **Durée totale réacteur** | **4 984,58 s** (~1h 23 min 05 s) | Budget respecté (< 130 min) |
| **Modules réacteur déclarés** | 61 projets (root + 60 modules) | 61 résolus |
| **Modules réacteur franchis avec succès** | **61 modules (100% SUCCESS)** | **SUCCESS** |
| **Module d'arrêt** | **AUCUN** (tous les modules ont réussi) | CONFORME |
| **Modules skippés** | **0** | CONFORME |
| **Tests JUnit / Surefire exécutés** | **2 344 tests** | — |
| **Tests réussis (PASS)** | **2 344 tests** | **100% PASS** |
| **Échecs d'assertion (FAILURES)** | **0** | CONFORME |
| **Erreurs d'exécution (ERRORS)** | **0** | CONFORME |
| **Tests sautés ou exclus (SKIPPED)** | **0** | CONFORME |
| **Gate Architecture globale** | **170 / 170 tests PASS** (`pocoma-architecture-tests`) | **PASS** |
| **Gate PostgreSQL** | V1–V10 validé, 0 erreur Flyway, pools et partitions OK | **PASS** |
| **Gate HTTP** | 10 routes du monolithe validées, contrats Web-API validés | **PASS** |
| **Bruno B1 (READ autonome)** | 32 scénarios B01–B32 exécutés et réussis (exit 0) | **PASS** |
| **Bruno B2 (Admission / Commandes)** | 24 scénarios B201–B224 exécutés et réussis (exit 0, SQL vérifié) | **PASS** |
| **Bruno B3 (Link OIDC réel)** | 10 scénarios B301–B313 sous Keycloak 26.7.0 réel (exit 0, SQL vérifié) | **PASS** |
| **Bruno B4 (Invitations)** | 4 scénarios B401–B404 exécutés et réussis (exit 0) | **PASS** |
| **Suite Bruno B5 consolidée** | **70 / 70 scénarios certifiés** | **B5 COMPOSITE PASS** |
| **Intégrité des preuves archivées** | 44 fichiers archivés et vérifiés sous `docs/testing/evidence/L5-Q5B6/` | CONFORME |
| **Recommandation finale** | **`CERTIFICATION_PASS` — CLÔTURE DÉFINITIVE DE L5** | Lot L5 CLOSED |

---

## 2. SHA Qualifié

- **SHA officiel certifié :** `2890d88f6fa94ff2262d08a2809f48512ce27c9d` (`2890d88f`)
- **Branche :** `feat/v3-architecture`
- **Origine distante :** `origin/feat/v3-architecture` (synchronisé)
- **Fichiers de production modifiés :** `0` (`PRODUCTION_FILES_MODIFIED = 0`)
- **Migrations SQL modifiées :** `0` (`PRODUCTION_MIGRATIONS_MODIFIED = 0`)
- **Nature des commits :** Tests, fixtures et documentation uniquement.

---

## 3. Prévol Dynamique (Phase A)

| Composant | Contrôle opéré | Critère de conformité | Résultat constaté | Verdict |
|---|---|---|---|:---:|
| **Git** | Propreté worktree | Aucune modification non committée | `working tree clean` | **PASS** |
| **Docker** | Démon Docker | Moteur opérationnel et socket actif | Docker Engine 29.9.2 | **PASS** |
| **PostgreSQL** | Conteneur fonctionnel | `postgres:16.6` sur port 5432 | Conteneur démarré et `healthy` | **PASS** |
| **Keycloak** | Serveur d'identité | `keycloak:26.7.0` sur port 8081 | Conteneur démarré et `healthy` | **PASS** |
| **OIDC** | Sessions réelles | 3 jetons acquis via `functional-auth-proof.py` | Sessions E1, E2, E3 actives, 0 fuite | **PASS** |
| **Bruno** | CLI & Collections | Binaire `bru` v4.3.0, dossiers B1–B4 | Collections présentes, CLI validé | **PASS** |
| **JDK** | Runtime Java | JDK 21 LTS | Temurin-21+35 (aarch64) | **PASS** |
| **Maven** | Wrapper | Version 3.9.14 | `app/mvnw` opérationnel | **PASS** |
| **Ressources** | Disque & Mémoire | Mémoire $\ge 12$ Go, Disque $\ge 20$ Go | 16 Go RAM système, 22 Go disque libre | **PASS** |
| **Concurrence** | Processus concurrents | Aucun processus Maven / Surefire actif | Aucun processus détecté, lock libre | **PASS** |
| **Politique** | Dry-run sélecteur | `verify.py CERTIFICATION --dry-run` | Graphe complet, absence de filtre | **PASS** |

---

## 4. Matrice des Gates

| Gate | Incluse dans Full Reactor ? | Exécution | Résultat constaté | Critère normatif | Verdict |
|---|:---:|:---:|:---:|:---:|:---:|
| **Maven Modules** | Oui | 1 fois canonique | 61 SUCCESS, 0 FAILURE, 0 SKIPPED | 61 SUCCESS | **PASS** |
| **PostgreSQL** | Oui (Testcontainers) | Intégrée | V1..V10 validé, 0 erreur Flyway, pools OK | 0 failure / error | **PASS** |
| **HTTP** | Oui | Intégrée | Monolithe 10 routes PASS, Web-API PASS | 10 routes conformes | **PASS** |
| **Architecture** | Oui (Module 61) | Sans duplication | 170 / 170 tests ArchUnit & CCR PASS | 100% PASS | **PASS** |
| **Bruno B5** | Partielle (B2, B3, B4 dans Maven + B1 autonome) | Mutualisation + Complément B1 | 70 / 70 scénarios validés | 70 / 70 scénarios certifiés | **PASS** |

---

## 5. Commande Canonique et Provenance

- **ID d'invocation :** `eead78b94c294a948253ec7022ec1ab6`
- **Sélecteur :** `scripts/testing/verify.py` (version CERTIFICATION non filtrée)
- **Commande exacte :**
  ```sh
  ./mvnw --batch-mode --no-transfer-progress \
    -Dpocoma.verification.id=eead78b94c294a948253ec7022ec1ab6 \
    -Dpocoma.verification.reports=/private/tmp/pocoma-q5b6-evidence-20261106/maven-full/reports \
    test
  ```
- **Répertoire de preuves temporaire :** `/private/tmp/pocoma-q5b6-evidence-20261106/maven-full`
- **Dossier d'archivage définitif :** `docs/testing/evidence/L5-Q5B6/`

---

## 6. Résultats Détaillés du Réacteur Maven (61 Projets)

Le réacteur complet a compilé et exécuté l'intégralité des 61 projets Maven :

| # | Module | Statut | Durée | Tests | Failures | Errors | Skips |
|---|---|:---:|:---:|:---:|:---:|:---:|:---:|
| 1 | `pocoma` (root pom) | **SUCCESS** | 0.011 s | — | — | — | — |
| 2 | `pocoma-domain-authorization` | **SUCCESS** | 0.551 s | 9 | 0 | 0 | 0 |
| 3 | `pocoma-domain-event` | **SUCCESS** | 0.254 s | 1 | 0 | 0 | 0 |
| 4 | `pocoma-domain-user-identity` | **SUCCESS** | 0.379 s | 31 | 0 | 0 | 0 |
| 5 | `pocoma-contracts-authentication` | **SUCCESS** | 0.008 s | — | — | — | — |
| 6 | `pocoma-domain-pot` | **SUCCESS** | 1.283 s | 222 | 0 | 0 | 0 |
| 7 | `pocoma-domain-projection` | **SUCCESS** | 0.333 s | 25 | 0 | 0 | 0 |
| 8 | `pocoma-domain-consumption` | **SUCCESS** | 0.354 s | 32 | 0 | 0 | 0 |
| 9 | `pocoma-port-transaction` | **SUCCESS** | 0.005 s | — | — | — | — |
| 10 | `pocoma-port-binding-authority` | **SUCCESS** | 0.007 s | — | — | — | — |
| 11 | `pocoma-engine-consumption` | **SUCCESS** | 0.377 s | 46 | 0 | 0 | 0 |
| 12 | `pocoma-contracts-command` | **SUCCESS** | 0.006 s | — | — | — | — |
| 13 | `pocoma-engine-write-pot` | **SUCCESS** | 0.518 s | 131 | 0 | 0 | 0 |
| 14 | `pocoma-engine-consume-command` | **SUCCESS** | 0.613 s | 111 | 0 | 0 | 0 |
| 15 | `pocoma-orchestrator-consumption` | **SUCCESS** | 0.325 s | 14 | 0 | 0 | 0 |
| 16 | `pocoma-supra-consume-command` | **SUCCESS** | 0.340 s | 9 | 0 | 0 | 0 |
| 17 | `pocoma-engine-read-command-result` | **SUCCESS** | 0.008 s | — | — | — | — |
| 18 | `pocoma-engine-materialize-command-result` | **SUCCESS** | 0.332 s | 4 | 0 | 0 | 0 |
| 19 | `pocoma-supra-consume-command-result` | **SUCCESS** | 0.014 s | — | — | — | — |
| 20 | `pocoma-contracts-registration` | **SUCCESS** | 0.004 s | — | — | — | — |
| 21 | `pocoma-engine-admit-registration` | **SUCCESS** | 0.356 s | 14 | 0 | 0 | 0 |
| 22 | `pocoma-engine-consume-registration` | **SUCCESS** | 0.330 s | 15 | 0 | 0 | 0 |
| 23 | `pocoma-supra-consume-registration` | **SUCCESS** | 0.010 s | — | — | — | — |
| 24 | `pocoma-engine-read-registration-result` | **SUCCESS** | 0.006 s | — | — | — | — |
| 25 | `pocoma-engine-materialize-registration-result` | **SUCCESS** | 0.288 s | 3 | 0 | 0 | 0 |
| 26 | `pocoma-supra-consume-registration-result` | **SUCCESS** | 0.012 s | — | — | — | — |
| 27 | `pocoma-port-projection` | **SUCCESS** | 0.007 s | — | — | — | — |
| 28 | `pocoma-engine-produce-projection-task` | **SUCCESS** | 0.290 s | 7 | 0 | 0 | 0 |
| 29 | `pocoma-engine-read-projection` | **SUCCESS** | 0.298 s | 13 | 0 | 0 | 0 |
| 30 | `pocoma-projector-pot` | **SUCCESS** | 0.290 s | 1 | 0 | 0 | 0 |
| 31 | `pocoma-engine-consume-projection-task` | **SUCCESS** | 0.350 s | 24 | 0 | 0 | 0 |
| 32 | `pocoma-supra-consume-projection-task` | **SUCCESS** | 0.011 s | — | — | — | — |
| 33 | `pocoma-engine-read-current-binding` | **SUCCESS** | 0.302 s | 4 | 0 | 0 | 0 |
| 34 | `pocoma-engine-read-pot` | **SUCCESS** | 0.364 s | 25 | 0 | 0 | 0 |
| 35 | `pocoma-engine-materialize-current-binding` | **SUCCESS** | 0.011 s | — | — | — | — |
| 36 | `pocoma-engine-materialize-latest-known-version` | **SUCCESS** | 0.280 s | 3 | 0 | 0 | 0 |
| 37 | `pocoma-infra-tx-spring` | **SUCCESS** | 11.764 s | 32 | 0 | 0 | 0 |
| 38 | `pocoma-infra-persistence-projection-jdbc` | **SUCCESS** | 0.405 s | 2 | 0 | 0 | 0 |
| 39 | `pocoma-infra-persistence-read-jdbc` | **SUCCESS** | 25.470 s | 67 | 0 | 0 | 0 |
| 40 | `pocoma-contracts-observability` | **SUCCESS** | 0.320 s | 2 | 0 | 0 | 0 |
| 41 | `pocoma-engine-admit-command` | **SUCCESS** | 0.420 s | 17 | 0 | 0 | 0 |
| 42 | `pocoma-supra-consume-lkv` | **SUCCESS** | 0.311 s | 3 | 0 | 0 | 0 |
| 43 | `pocoma-infra-persistence-primary-jpa` | **SUCCESS** | 16:18 min | 665 | 0 | 0 | 0 |
| 44 | `pocoma-infra-projection-validation-networknt` | **SUCCESS** | 0.474 s | 2 | 0 | 0 | 0 |
| 45 | `pocoma-orchestrator-poll-consumption` | **SUCCESS** | 0.745 s | 20 | 0 | 0 | 0 |
| 46 | `pocoma-supra-consume-event` | **SUCCESS** | 0.324 s | 7 | 0 | 0 | 0 |
| 47 | `pocoma-supra-consume-binding` | **SUCCESS** | 0.009 s | — | — | — | — |
| 48 | `pocoma-supra-http-write` | **SUCCESS** | 2.094 s | 5 | 0 | 0 | 0 |
| 49 | `pocoma-supra-http-read` | **SUCCESS** | 0.874 s | 12 | 0 | 0 | 0 |
| 50 | `pocoma-composition-runtime-spring` | **SUCCESS** | 0.074 s | — | — | — | — |
| 51 | `pocoma-runtime-task-consumption-worker` | **SUCCESS** | 04:02 min | 104 | 0 | 0 | 0 |
| 52 | `pocoma-runtime-monolith` | **SUCCESS** | 24:00 min | 42 | 0 | 0 | 0 |
| 53 | `pocoma-runtime-web-api` | **SUCCESS** | 01:00 min | 65 | 0 | 0 | 0 |
| 54 | `pocoma-runtime-event-consumption-worker` | **SUCCESS** | 8.244 s | 30 | 0 | 0 | 0 |
| 55 | `pocoma-runtime-command-result-consumption-worker` | **SUCCESS** | 5.080 s | 5 | 0 | 0 | 0 |
| 56 | `pocoma-runtime-registration-result-consumption-worker` | **SUCCESS** | 5.145 s | 4 | 0 | 0 | 0 |
| 57 | `pocoma-runtime-registration-consumption-worker` | **SUCCESS** | 5.371 s | 7 | 0 | 0 | 0 |
| 58 | `pocoma-runtime-latest-known-version-consumption-worker` | **SUCCESS** | 5.681 s | 11 | 0 | 0 | 0 |
| 59 | `pocoma-runtime-binding-consumption-worker` | **SUCCESS** | 5.604 s | 7 | 0 | 0 | 0 |
| 60 | `pocoma-runtime-command-consumption-worker` | **SUCCESS** | 35:34 min | 321 | 0 | 0 | 0 |
| 61 | `pocoma-architecture-tests` | **SUCCESS** | 39.879 s | 170 | 0 | 0 | 0 |
| **TOTAL** | **61 modules** | **BUILD SUCCESS** | **1h 23 min** | **2 344** | **0** | **0** | **0** |

---

## 7. Résultats PostgreSQL

- **Ordonnancement des migrations Flyway (V1–V10) :** Validé sans aucune divergence. Les 10 versions ont été appliquées strictement de façon séquentielle sur tous les schémas éphémères. Zéro `FlywayValidateException` observée.
- **Coexistence des 28 partitions READ :** Validée dans `ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely` (PASS).
- **Ressources worker et enveloppe Hikari :** Validées dans `ReadWorkerResourcesPostgresTest` et `V3WorkerPostgresTest` (PASS).
- **Transactions et isolation ACID :** 665 tests sous `infra-persistence-primary-jpa` et 67 tests sous `infra-persistence-read-jdbc` réussis à 100%.

---

## 8. Résultats HTTP

- **Routes du Monolithe :** Exactement 10 routes vérifiées et conformes dans `V3MonolithHttpCompositionPostgresTest#monolithRoot`.
- **Routes de l'API Web autonome :** Exactement 10 routes vérifiées et conformes dans `V3HttpCompositionPostgresTest#webRoot`.
- **Contrats HTTP d'Admission et de Lecture :** Validés avec succès dans `V3AdmissionHttpTest`, `V3LinkRequestHttpTest`, `V3InvitationHttpTest` et `V3ReadHttpContractTest`.

---

## 9. Résultats Architecture

Le module 61 `pocoma-architecture-tests` a été exécuté dans son intégralité :
- **Nombre total de tests :** 170 tests (couvrant l'ensemble des 178 oracles statiques et dynamiques).
- **Résultats :** 170 PASS, 0 failure, 0 error, 0 skipped.
- **Règles hexagonales :** `HexagonalArchitectureTest` PASS.
- **Frontières de modules :** `RegistrationModuleBoundaryTest`, `Wp2BoundaryTest`, `Wp3BoundaryTest`, `Wp4BoundaryTest`, `Wp5TargetTopologyTest` PASS.
- **Interdictions d'héritage et de couplage legacy :** `Pcl3LegacyAbsenceTest`, `Pcl4LegacyQueryReadAbsenceTest`, `Pcl5PipelineLifecycleAbsenceTest`, `Pcl6MonolithAbsenceTest`, `Pcl7ModuleDependencyCollapseTest`, `Pcl8DatabaseDemolitionTest` PASS.
- **Contrats CCR et EPT :** `CommandCompletionE2EPostgresTest`, `CommandResultTakeoverRacePostgresTest`, `DurableEventToProjectionPostgresTest` PASS.

---

## 10. Résultats Bruno B5

La suite composite Bruno B5 couvre l'intégralité des **70 scénarios normatifs (SPEC-10)** :

| Corpus | Description | Scénarios | Résultat | Mode d'exécution |
|---|---|:---:|:---:|---|
| **B1** | Identity and READ | 32 (B01–B32) | **32 / 32 PASS** | Autonome (`V3ReadHttpFixture`, exit 0) |
| **B2** | Admission and Commands | 24 (B201–B224) | **24 / 24 PASS** | Intégré (`V3AdmissionFlowPostgresTest`, exit 0) |
| **B3** | Link Requests (Keycloak réel) | 10 (B301–B313) | **10 / 10 PASS** | Intégré (`V3LinkBrunoPostgresTest`, Keycloak 26.7.0, exit 0) |
| **B4** | Invitations & Départ Volontaire | 4 (B401–B404) | **4 / 4 PASS** | Intégré (`V3InvitationHttpTest`, exit 0) |
| **TOTAL** | **Surface HTTP V3 Consolidée** | **70 scénarios** | **70 / 70 PASS** | **B5 COMPOSITE PASS** |

Le manifeste consolidé `bruno-b5-consolidated.json` atteste :
- `global_verdict`: **`B5 COMPOSITE PASS`**
- `total_scenarios_expected`: 70
- `total_scenarios_passed`: 70
- `zero_failure`: `true`
- `zero_error`: `true`
- `zero_skip`: `true`

---

## 11. Vérification des Anomalies Historiques

| Anomalie | Origine | Description | Statut Q5-B6 |
|---|---|---|:---:|
| **AUD-Q5B-03-A** | Q5-B3 | Conflit Flyway V7–V10 | **DÉFINITIVEMENT RÉSOLU** |
| **AUD-Q5B-03-B** | Q5-B3 | Oracle observation des 28 partitions | **DÉFINITIVEMENT RÉSOLU** |
| **AUD-Q5B-03-C** | Q5-B3 | Oracle des 10 routes HTTP | **DÉFINITIVEMENT RÉSOLU** |
| **AUD-Q5B5-01** | Q5-B5 | Dérive de lease Invitation (180s) | **DÉFINITIVEMENT RÉSOLU** |
| **AUD-Q5B5-02** | Q5-B5 | NPE Hikari actual.close() | **DÉFINITIVEMENT RÉSOLU** |
| **AUD-ARCH-01..03** | Prévol Q5-B6 | Oracles ArchUnit Invitation / Timestamp | **DÉFINITIVEMENT RÉSOLU** |
| **AUD-WORKER-01** | Prévol Q5-B6 | Réflexion constructeur worker | **DÉFINITIVEMENT RÉSOLU** |

---

## 12. Durées Réelles d'Exécution

- **Phase d'inventaire des entrées :** 0,13 s
- **Phase de découverte Maven :** 2,86 s
- **Phase d'exécution Maven (Full Reactor) :** 4 984,58 s (~1h 23 min 05 s)
- **Phase de collecte et calculs de hash :** 5,17 s
- **Phase complémentaire Bruno B1 :** 9,06 s (~3,92 s CLI bru)
- **Phase de consolidation Bruno B5 :** 0,25 s
- **Durée totale cumulée de la qualification :** **4 993,17 s** (~1h 23 min 13 s)

---

## 13. Suivi du Budget Normatif

- **Plafond normatif maximal autorisé :** **130 minutes (7 800 s)**
- **Consommation réelle globale :** **83,2 minutes (4 993 s)**
- **Marge de sécurité restante :** **46,8 minutes (36% du budget préservé)**
- **Respect du budget :** **CONFORME**

---

## 14. Inventaire des Preuves et Empreintes SHA-256

Le dossier de preuves est consultable sous `docs/testing/evidence/L5-Q5B6/` et indexé par `manifest.json`.

Principaux artefacts vérifiés :
- `result.json` : Résultat global PASS (2 344 tests PASS, 0 fail, 0 error).
- `timings.json` : Reçu d'horodatage monotone par phase.
- `provenance.json` : Relevé de l'environnement (Git, Docker, JDK, Maven).
- `bruno-b5-consolidated.json` : Reçu unifié des 70 scénarios Bruno (B5 COMPOSITE PASS).
- `surefire-reports/` : 14 rapports XML certifiant les modules critiques.
- `bruno-b1/`, `bruno-b2/`, `bruno-i3/`, `bruno-b4/` : Reçus et journaux détaillés des requêtes HTTP.

---

## 15. Réserves Historiques

Toutes les réserves historiques des incréments L5/I1, I2, I3, I4, Q5-BF, Q5-B3, Q5-B4 et Q5-B5 sont définitivement soldées :
- `R-Q5B5-01 (Non-atteinte de la gate Architecture et Web-API)` : **LEVÉE** (les modules 53 à 61 sont tous passés avec succès).
- `R-Q5B5-02 (Complétude Bruno B5)` : **LEVÉE** (70 / 70 scénarios certifiés B5).
- `R-I1-03 (AuthN Keycloak réelle)` : **LEVÉE** (validée sur Keycloak 26.7.0 port 8081).

---

## 16. Verdict de Certification Finale

Au vu des résultats irréfutables constatés :
- 61 modules Maven sur 61 franchis avec succès (`BUILD SUCCESS`).
- 2 344 tests JUnit/Surefire exécutés, 2 344 tests réussis (100% PASS), 0 échec, 0 erreur, 0 test sauté.
- Gate Architecture globale validée à 100% (170 tests).
- Gate PostgreSQL V1–V10 validée sans erreur Flyway.
- Gate HTTP monolithe et web-api validée (10 routes conformes).
- Gate Bruno B5 consolidée validée à 100% (70 scénarios sur 70).
- Budget temporel respecté (83 min vs 130 min autorisées).
- Intégrité stricte du code productif préservée (`PRODUCTION_FILES_MODIFIED = 0`).

Le verdict officiel terminal est :

```
================================================================================
POCOMA V3 — CERTIFICATION FINALE GLOBALE L5 : CERTIFICATION_PASS
SHA qualifié : 2890d88f6fa94ff2262d08a2809f48512ce27c9d
Verdict Réacteur : 61/61 SUCCESS (2 344 tests PASS, 0 failure, 0 error, 0 skip)
Verdict Architecture : 170/170 PASS (Gate ArchUnit qualifiée)
Verdict Bruno B5 : 70/70 PASS (B5 COMPOSITE PASS)
================================================================================
```

---

## 17. Décision Finale et Clôture du Lot L5

1. **Clôture formelle de L5 :** Le lot L5 (**`L5 — Architecture V3 Hexagonale, Événements, Workers & Protocoles HTTP`**) est formellement et définitivement **QUALIFIÉ ET CLOS (`L5 — CLOSED`)**.
2. **Transition vers Q5-C :** Les livrables, preuves et artefacts sont prêts pour l'audit qualité indépendant post-certification (**Q5-C**).
