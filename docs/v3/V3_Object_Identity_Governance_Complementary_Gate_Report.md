# Pocoma V3 — Rapport probatoire du gate complémentaire de qualification des identités serveur

Date : 2026-11-21  
Branche évaluée : `feat/v3-identity-governance` (base `main` `1c5140fec30cb9b6c01057875c9c45c1654b3a76`, working tree non committé préservé)  
Branche comparée : `origin/feat/v3-l7` (`181904cd02ffb7e3b9b60cf754cf365b99c2a3fa`, via `git fetch` lecture seule, sans fusion)  
Politique normative : [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)

## Verdict global du gate : **`NOT QUALIFIED`**

**Raison normative** : aucun démon Docker ni serveur PostgreSQL n'est disponible dans cet environnement d'exécution (`docker: NOT FOUND`, `podman: NOT FOUND`, `postgres: NOT FOUND`, `pg_ctl: NOT FOUND`, `initdb: NOT FOUND`, `/var/run/docker.sock: No such file or directory`). Conformément aux règles normatives et aux interdictions explicites du mandat, aucune preuve statique ni aucun test unitaire en mémoire n'est substitué aux preuves PostgreSQL réelles. Le chantier reste **non clos** et **non éligible à l'intégration** tant que les obligations `NOT RUN` ci-dessous n'ont pas été exécutées et validées sur un environnement doté de Docker/PostgreSQL.

---

## 1. Tableau probatoire des 10 obligations

| # | Obligation | Verdict | Détail factuel et preuves / blocages |
|---|---|---|---|
| **1** | Préserver intégralement l'état Git et les modifications existantes | **PASS** | Branche `feat/v3-identity-governance` intacte, aucune fusion avec `main` ni `feat/v3-l7`, aucun `reset`/`checkout` destructif. Trois corrections ciblées ajoutées suite aux constats du gate (`setup-v3-database.py`, `v3-rebuild-read.py`, `L7ReactorArchitectureTest.java`). |
| **2** | Qualifier les migrations PostgreSQL WRITE/READ V2 sur bases vierges et mises à niveau | **NOT RUN** | **Blocage environnemental** : Docker et PostgreSQL serveur absents de la VM. Défaut opérationnel détecté et corrigé en amont : `scripts/operations/setup-v3-database.py` et `scripts/operations/v3-rebuild-read.py` n'incluaient que `V1__initial_schema.sql` et ont été mis à jour pour appliquer `V2__server_assigned_pot_identities.sql` (WRITE et READ). |
| **3** | Exécuter les tests PostgreSQL réels (3 créations, résultats durables, idempotence, droits, contraintes SQL, rollbacks, reprises, concurrence, fencing) | **NOT RUN** | **Blocage environnemental** : Testcontainers échoue faute de `/var/run/docker.sock`. Constat critique identifié : 4 tests PostgreSQL/HTTP existants (`AdmissionPostgresTest.java`, `AdmissionFlowPostgresTest.java`, `InvitationFixture.java`, `InvitationHttpTest.java`) envoient encore des IDs clients dans leurs payloads JSON `CreatePot`/`AddShareholders`/`CreateExpense` et devront être alignés lors de l'exécution Docker. |
| **4** | Vérifier explicitement les interruptions avant/après COMMIT et COMMIT ambigus | **NOT RUN** | **Blocage environnemental** : nécessite PostgreSQL réel (`JdbcPotCommandObservation` + `ConsumptionEngine` sur `PostgreSQLContainer`). |
| **5** | Qualifier la reconstruction READ vierge et l'équivalence des résultats | **NOT RUN** | **Blocage environnemental** : nécessite PostgreSQL réel (`ReadReconstructionPostgresTest` + `scripts/operations/v3-rebuild-read.py`). |
| **6** | Tester les anciennes commandes persistées (`PENDING`), anciens résultats et stratégie de transition `payloadVersion: 3` | **NOT RUN** *(PostgreSQL)* / **PASS** *(unitaire codec/moteur)* | Tests unitaires `ServerAssignedPotCommandJsonTest` (4/4), `PotCommandJsonTest` (3/3), `ExecutePotCommandTest` (9/9) et `ServerAssignedPotExecutionTest` (4/4) **PASS** sur codec v1/v2/v3 et moteur. L'exécution PostgreSQL réelle de commandes `PENDING` v1/v2 migrées V1→V2 reste **`NOT RUN`** (Docker absent). |
| **7** | Exécuter les parcours Bruno complets sur un déploiement réel | **NOT RUN** | **Blocage environnemental** : pas de Docker/PostgreSQL/Keycloak pour démarrer le déploiement réel. Collections `scripts/bruno-playground/` et `scripts/bruno-v3/` déjà mises à jour pour consommer `potId` et `createdResources` depuis `CommandResult`. |
| **8** | Vérifier le fonctionnement en configurations monolithique et distribuée | **NOT RUN** | **Blocage environnemental** : pas de Docker/PostgreSQL pour lancer `runtime-monolith` ni `docker-compose.v3-five-runtimes.yml`. Compilation `compile` + `test-compile` validée sur les 42 modules y compris `runtime-monolith`. |
| **9** | Comparer `feat/v3-identity-governance` au HEAD actuel de `feat/v3-l7` (C2, C3, C4) — conflits et impacts sans intégration automatique | **PASS** | Comparaison complète exécutée contre `origin/feat/v3-l7` (`181904cd02ffb7e3b9b60cf754cf365b99c2a3fa`). **1 collision bloquante de version Flyway READ (`V2`)**, **4 conflits de fichiers directs**, **impacts sémantiques L7-C2/C3 analysés**, **0 commit C4 présent sur `origin/feat/v3-l7`**. Voir §2 ci-dessous. |
| **10** | Produire un rapport probatoire avec verdict explicite `PASS` / `FAIL` / `NOT RUN` | **PASS** | Présent document + exécution fraîche de `architecture-tests` (**50/50 PASS**). |

---

## 2. Comparaison exhaustive `feat/v3-identity-governance` vs `origin/feat/v3-l7` (Obligation #9)

### 2.1 Topologie Git observée
- `merge-base(main, origin/feat/v3-l7)` = `1c5140fec30cb9b6c01057875c9c45c1654b3a76` (`main` actuel).
- `origin/feat/v3-l7` (`181904cd`) comporte **21 commits** au-dessus de `main` :
  - **L7.4-A** (`b344e440` → `b8924f3d`) : matérialisation incrémentale N-m `PotVersion` & `PotBalance`, migration READ `V2__pot_balance_aggregate.sql`.
  - **L7-C2** (`288c3f72` → `d7b4b6d7`) : stockage Radix-16 `PotVersion` (READ `V3__pot_version_radix16.sql`), stockage partagé `PotBalance` (READ `V4__pot_balance_shared_storage.sql`), fast path `EMPTY_DELTA_REUSE`, corrections ARB-1b/3b/6.
  - **L7-C3** (`96513f91` → `2181bd21`) : publication de disponibilité READ (`db/read/availability/migration/V5__projection_availability.sql`, `db/read/pilot/migration/V5__command_result_availability.sql`, `ReadAvailability.java`, installation et reconstruction READ V1→V5).
  - **Docs** (`181904cd`) : `docs(graft): document billed token savings`.
  - **Statut C4** : **aucun commit C4 n'est présent sur `origin/feat/v3-l7` (`181904cd`)**. Toute évolution C4 locale ou sur une autre branche non poussée devra faire l'objet d'un rapprochement dédié lorsqu'elle sera publiée.

### 2.2 Collision bloquante de numérotation Flyway READ
- **WRITE (`db/write/migration/`)** : `origin/feat/v3-l7` ne contient que `V1__initial_schema.sql`. La migration `V2__server_assigned_pot_identities.sql` de `feat/v3-identity-governance` n'a **aucune collision** sur WRITE.
- **READ (`db/read/migration/`)** : `origin/feat/v3-l7` occupe déjà les versions **V1 à V5** :
  - `db/read/migration/V1__initial_schema.sql`
  - `db/read/migration/V2__pot_balance_aggregate.sql`
  - `db/read/migration/V3__pot_version_radix16.sql`
  - `db/read/migration/V4__pot_balance_shared_storage.sql`
  - `db/read/availability/migration/V5__projection_availability.sql` (et `pilot/migration/V5__command_result_availability.sql`)
- **Impact** : la migration `V2__server_assigned_pot_identities.sql` de `feat/v3-identity-governance` entre en **collision directe de numéro de version Flyway (`V2`)** avec `V2__pot_balance_aggregate.sql`.
- **Action requise lors de l'intégration sur `feat/v3-l7`** : renuméroter la migration READ en **`V6__server_assigned_pot_identities.sql`** (après V1..V5), mettre à jour les listes dans `setup-v3-database.py`, `v3-rebuild-read.py` et `L7ReactorArchitectureTest.java`.

### 2.3 Conflits directs de fichiers (4 fichiers)

| Fichier | Nature du conflit avec `origin/feat/v3-l7` | Résolution attendue à l'intégration |
|---|---|---|
| `app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcCommandTerminalResultStore.java` | **Conflit 3-way direct** : `feat/v3-l7` (L7-C3) ajoute `ReadAvailability`, `verifyEvidence` et `closePublication(UUID, boolean)` ; `feat/v3-identity-governance` ajoute `created_resources` (`?::jsonb`) et `pot_id` nullable. | Fusion manuelle propre : conserver intégralement `ReadAvailability` (C3) et étendre `load()` / `insertOrVerify()` avec `created_resources` et `pot_id` nullable. |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7ReactorArchitectureTest.java` | **Conflit 3-way direct** : les deux branches mettent à jour la liste stricte des `.sql` de `infra-postgres-read/src/main/resources` (et `feat/v3-identity-governance` met à jour `infra-postgres-write`). | Fusionner les deux listes en enregistrant WRITE `V1..V2` et READ `V1..V4 + V5 availability/pilot + V6__server_assigned_pot_identities.sql`. |
| `scripts/operations/setup-v3-database.py` | **Conflit 3-way direct** : `feat/v3-l7` applique READ `V1..V5` ; `feat/v3-identity-governance` ajoute WRITE `V2` et READ `V2`. | Fusionner : WRITE `V1 + V2`, READ `V1..V5 + V6`. |
| `scripts/operations/v3-rebuild-read.py` | **Conflit 3-way direct** : idem pour la séquence de reconstruction READ. | Fusionner : READ `V1..V5 + V6`. |

### 2.4 Compatibilité sémantique avec L7-C2 et L7-C3
- **L7-C2 (`PotVersion` Radix-16 V3 & `PotBalance` partagé V4)** : aucune interaction avec `read_command_result` ni avec `CreatePot`/`AddShareholders`/`CreateExpense` sans ID client (les matérializers `PotVersion` et `PotBalance` consomment uniquement les `v3_pot_event` portant déjà les IDs définitifs assignés par WRITE).
- **L7-C3 (`V5__projection_availability.sql`)** : vérifié par inspection directe du DDL (`FETCH_HEAD`). La vue `read_availability_effects` et le trigger `read_effect_availability` sur `read_command_result` utilisent **`request_id`** comme `projection_key` et `logical_version = NULL` (jamais `pot_id`). Le passage de `read_command_result.pot_id` en nullable pour `(operation = 'CreatePot' AND write_outcome = 'REJECTED')` est donc **100 % compatible** avec L7-C3.

---

## 3. Preuves exécutées durant ce gate complémentaire

### 3.1 Gate d'architecture (`architecture-tests`) — **PASS**
- **Commande exécutée** (depuis `app/`, JDK `21.0.13.1`) :
  ```sh
  JAVA_HOME=/home/antigravity/.local/jdk-21 ./mvnw --batch-mode --no-transfer-progress -pl architecture-tests -am test -Dtest='com.kartaguez.pocoma.architecture.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Résultat** : **BUILD SUCCESS — 9 classes, 50 tests exécutés, 0 échec, 0 erreur, 0 skip** (`L7ReactorArchitectureTest`, `L7DeploymentArchitectureTest`, `AdmissionArchitectureTest`, `ConsumptionEngineArchitectureTest`, `ConsumptionFoundationArchitectureTest`, `FoundationArchitectureTest`, `HexagonalArchitectureTest`, `IdentityAuthorityArchitectureTest`, `PotExecutionArchitectureTest`).
- **Correction déclenchée par ce gate** : `L7ReactorArchitectureTest` vérifiait l'inventaire exact des fichiers `.sql` de `infra-postgres-write` et `infra-postgres-read` ; il a été mis à jour pour déclarer `V2__server_assigned_pot_identities.sql` sur WRITE et READ.

### 3.2 Preuves unitaires/contractuelles réutilisées (code production inchangé depuis l'exécution précédente)
- **Slices déclarées** : Primaire `COMMAND` (`runtime-worker-process`) ; Secondaires `WEB` (`runtime-web-write,runtime-web-read`), `PROJECTION` (`runtime-worker-materialize`).
- **Résultat** : **55 classes, 259 tests exécutés, 0 échec, 0 erreur, 0 skip** (non-Docker uniquement).
- **Franchissement de slice inattendu** : `NONE`.
- **Full reactor** : `NOT RUN` (politique locale + absence de Docker).

---

## 4. Plan d'exécution requis sur poste/runner Docker pour lever `NOT QUALIFIED`

1. **Aligner les 4 tests PostgreSQL/HTTP existants qui envoient encore des IDs clients en admission HTTP** :
   - `app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/AdmissionPostgresTest.java` (L185)
   - `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/AdmissionFlowPostgresTest.java` (L208, 258, 261, 295, 381, 384, 411, 732, 733, 837)
   - `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/InvitationFixture.java` (L97, 99)
   - `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/InvitationHttpTest.java` (L318, 380, 387)
2. **Exécuter les slices canoniques non filtrées avec PostgreSQL réel (Testcontainers)** :
   ```sh
   cd app
   ./mvnw -pl runtime-worker-process -am test
   ./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-materialize -am test
   ./mvnw -pl runtime-monolith,architecture-tests -am test
   ```
3. **Ajouter et exécuter les scénarios PostgreSQL dédiés aux obligations #2, #4, #5, #6** :
   - Migration V1 → données historiques (`v3_command` `PENDING` v1/v2 + `v3_command_result` historique) → V2 → exécution des `PENDING` historiques et des nouvelles commandes `payloadVersion: 3`.
   - Crash avant COMMIT, après COMMIT et COMMIT ambigu sur `CreatePot` (`pot_id IS NULL`), `AddShareholders` et `CreateExpense`.
   - Reconstruction READ vierge (`scripts/operations/v3-rebuild-read.py`) et équivalence bit-à-bit de `read_command_result` (dont `created_resources` et `pot_id IS NULL` sur `CreatePot` rejeté).
4. **Exécuter les parcours Bruno réels (`scripts/bruno-playground/` J01–J03 et `scripts/bruno-v3/` I2/I4)** sur :
   - configuration monolithique (`runtime-monolith`),
   - configuration distribuée (`docker-compose.v3-five-runtimes.yml`).
