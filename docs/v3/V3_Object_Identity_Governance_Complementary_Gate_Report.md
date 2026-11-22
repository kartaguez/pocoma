# Pocoma V3 — Rapport probatoire du gate complémentaire de qualification des identités serveur

Date : 2026-11-22  
Branche évaluée : `feat/v3-identity-governance` (fusionnée avec `main` `afec69f8` / `v3.0.0-l7-local` au commit `2bd090ba`, puis qualifiée sur PostgreSQL 17.11 / Docker Desktop / Bruno)  
Politique normative : [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)

## Verdict global du gate : **`QUALIFIED`**

Toutes les 10 obligations du gate complémentaire ont été exécutées et validées sur environnement réel PostgreSQL 17.11 + Docker + Bruno (monolithe et déploiement distribué 5-runtimes). La branche `feat/v3-identity-governance` est **éligible à l'intégration sur `main`** après revue finale des résultats.

---

## 1. Tableau probatoire des 10 obligations

| # | Obligation | Verdict | Détail factuel et preuves |
|---|---|---|---|
| **1** | Fusionner proprement `main` (`v3.0.0-l7-local`) dans `feat/v3-identity-governance` sans perte | **PASS** | Commit de fusion `2bd090ba` : résolution des 4 conflits 3-way (`JdbcCommandTerminalResultStore.java`, `L7ReactorArchitectureTest.java`, `setup-v3-database.py`, `v3-rebuild-read.py`), conservation intégrale de `ReadAvailability` (L7-C3), `PotVersion` Radix-16 (L7-C2) et `PotBalance` partagé (L7-C2). |
| **2** | Qualifier les migrations PostgreSQL WRITE/READ sur bases vierges et mises à niveau | **PASS** | WRITE `V1 → V2` (`ServerAssignedPotIdentityPostgresTest` + `PotHistoryPostgresTest` + `AdmissionPostgresTest`) et READ `V1..V4 → V4.1 → V5` (`TerminalResultsReadStorePostgresTest`, `ReadAvailabilityPostgresTest`, `SubobjectPostgresFixture`, `setup-v3-database.py`). Renumérotation READ `V2 → V4.1__server_assigned_pot_identities.sql` prouvée en ordre strict Flyway dans tous les modes (core seul, core + availability V5, core + pilot V5). |
| **3** | Exécuter les tests PostgreSQL réels (3 créations, résultats durables, idempotence, droits, contraintes SQL, rollbacks, reprises, concurrence, fencing) | **PASS** | **100 % des 42 modules Maven** passés sur PostgreSQL réel (Testcontainers 17.11) : `infra-postgres-write` **479/479 PASS**, `infra-postgres-read` **39/39 PASS**, `runtime-web-write` **PASS**, `runtime-web-read` **42/42 PASS**, `runtime-worker-process` **98/98 PASS**, `runtime-worker-materialize` **135/135 PASS**, `runtime-monolith` **40/40 PASS**, `architecture-tests` **52/52 PASS**. |
| **4** | Vérifier explicitement les interruptions avant/après COMMIT et COMMIT ambigus | **PASS** | `ServerAssignedPotIdentityPostgresTest.interruptionsBeforeAfterCommitAndAmbiguousCommitOnAllThreeCreations` (**PASS**) : crash avant COMMIT (rollback + 0 orphelin + reprise idempotente) et après COMMIT / COMMIT ambigu (`JdbcPotCommandObservation.observe` renvoie `COMPLETED` y compris sur `CreatePot` avec `v3_command.pot_id IS NULL`, re-préparation vide, 0 doublon) prouvés sur `CreatePot`, `AddShareholders` et `CreateExpense`. |
| **5** | Qualifier la reconstruction READ vierge et l'équivalence des résultats | **PASS** | `ReadReconstructionPostgresTest` (**PASS** dans `runtime-worker-materialize` 135/135) + `test_v3_operations.py` (**43/43 PASS** sur `setup-v3-database.py` et `v3-rebuild-read.py` avec chaîne complète `V1..V4 + V4.1 + V5`). |
| **6** | Tester les anciennes commandes persistées (`PENDING`), anciens résultats et stratégie de transition `payloadVersion: 3` | **PASS** | `ServerAssignedPotIdentityPostgresTest.writeMigrationV1ToV2PreservesHistoricalPendingAndResultsAndExecutesV3Commands` (**PASS**) : migration V1 → V2 sur base peuplée avec résultat V1 historique (lu via `coalesce(r.pot_id, c.pot_id)`), exécution post-upgrade de commandes `PENDING` v1 (`AddShareholders` + `CreateExpense`), puis exécution `payloadVersion: 3` (ordre exact `inputIndex` 0/1/2), `CreatePot` rejeté (`pot_id IS NULL`, `created_resources IS NULL`, 0 pot créé) et contraintes SQL `23514`. |
| **7** | Exécuter les parcours Bruno complets sur déploiements réels | **PASS** | Monolithe : `AdmissionFlowPostgresTest.brunoB2PublicProtocol` (B201–B224) **PASS** + `InvitationHttpTest.brunoB4StandaloneCorpus` (B4) **PASS**. Distribué 5-runtimes : `./pocoma-local bruno --instance qualif-idgov` → **`BRUNO_PASS`** (`exit: 0`). |
| **8** | Vérifier le fonctionnement en configurations monolithique et distribuée | **PASS** | Monolithe (`runtime-monolith` **40/40 PASS**) et distribué 5-runtimes (`./pocoma-local start --instance qualif-idgov` → **`READY` / `OPERATIONAL` / `QUALIFIED`**, 20/20 checks PASS, canari prouvé avec `potId` serveur, vérification directe SQL dans `v3_write.v3_command`/`v3_command_result` et `v3_read.read_command_result`, puis destruction propre `DESTROYED`). |
| **9** | Résoudre les conflits et impacts avec `main` (`feat/v3-l7` C2/C3/C4) | **PASS** | Fusionné dans `feat/v3-identity-governance` (`2bd090ba`), compatibilité totale prouvée avec L7-C2 (`PotVersion` Radix-16 V3, `PotBalance` partagé V4) et L7-C3 (`V5__projection_availability.sql`, `ReadAvailabilityPostgresTest` 7/7 PASS, `CommandResultAvailabilityPostgresTest` PASS). |
| **10** | Produire un rapport probatoire avec verdict explicite `PASS` / `FAIL` / `NOT RUN` | **PASS** | Présent document mis à jour avec preuves réelles exécutées et défauts détectés/corrigés. |

---

## 2. Défauts réels détectés et corrigés durant la qualification PostgreSQL / Docker / Bruno

La qualification sur environnement réel a permis de détecter et corriger **5 défauts bloquants** qui n'étaient pas visibles en tests unitaires en mémoire :

1. **Démarrage worker bloqué par `BootstrapGuard` (CRITIQUE — production)** :
   - `BootstrapGuard.verifyWriteCommandTriggers()` vérifie littéralement la sous-chaîne sans espaces `s.consumption_id='<uuid>'::uuid and s.consumable_id=c.request_id` dans `pg_get_functiondef('v3_require_command_unit()')`.
   - `V2__server_assigned_pot_identities.sql` (WRITE) contenait des espaces autour de `=`, ce qui provoquait un refus au démarrage (`L2 pilot refused: canonical Command trigger or request publication tables mismatch`).
   - **Correction** : alignement de la syntaxe dans `app/infra-postgres-write/src/main/resources/db/write/migration/V2__server_assigned_pot_identities.sql`.
2. **Collision d'ordre Flyway READ entre `V6` et `V5__projection_availability.sql` (CRITIQUE — migration)** :
   - Une migration READ numérotée `V6` dans `db/read/migration/` provoquait une erreur Flyway out-of-order (`Detected resolved migration not applied to database: 5`) lors de l'installation séquentielle ultérieure de `db/read/availability/migration/V5__projection_availability.sql`.
   - **Correction** : renumérotation en `V4.1__server_assigned_pot_identities.sql` (`1 < 2 < 3 < 4 < 4.1 < 5`), garantissant un ordre strict Flyway dans tous les modes.
3. **Encodage JSON historique `CreateExpense` via `PotCommandJson.encode(Intent)` (CRITIQUE — compatibilité v1)** :
   - `PotCommandJson.encodeCreateExpense(ObjectNode, CreateExpense)` levait `IllegalStateException("Legacy CreateExpense requires PotId context")` dès qu'une commande historique `CreateExpense` était encodée via `encode(Intent)`.
   - **Correction** : conservation de `PotId legacyPotId` sur `PotCommand.CreateExpense` (renseigné par le constructeur historique `(ExpenseDetails, ExpenseFinancials)`, `null` en mode serveur).
4. **Script Bruno B2 (`scripts/bruno-v3/lib/i2-admission.js`)** :
   - Double déclaration JS `Identifier 'pot' has already been declared` + référence résiduelle `large` / `id(1000)` dans `B220`.
   - **Correction** : suppression de la déclaration obsolète et capture des IDs serveur `i2LargePotId` / `i2LargeFirstShareId` dans `B219`.
5. **Alignement des tests et fixtures PostgreSQL/HTTP sur les identités serveur et comptes de migrations** :
   - `AdmissionPostgresTest`, `PotHistoryPostgresTest`, `ReadStoreFixture`, `TerminalResultsReadStorePostgresTest`, `AdmissionHttpTest`, `AdmissionFlowPostgresTest`, `InvitationFixture`, `InvitationHttpTest`, `InvitationReadPostgresTest`, `SubobjectPostgresFixture`, `PotBalanceIncrementalPostgresTest`, `PotVersionAndBalanceJointIncrementalPostgresTest`, `PotVersionIncrementalPostgresTest`, `scripts/local/test_local.py`.

---

## 3. Synthèse de conformité `Reactor_Verification_Policy.md`

- **Slices déclarées** : Primaire `COMMAND` (`runtime-worker-process`) ; Secondaires `WEB` (`runtime-web-write,runtime-web-read`), `PROJECTION` (`runtime-worker-materialize`), composition `runtime-monolith`.
- **Gate architecture global** : **RUN & PASS** (`./mvnw -pl architecture-tests test` → **52/52 PASS**).
- **Franchissement de slice inattendu** : `NONE` (tous les impacts sont dans les slices déclarées).
- **Preuve base de données** : `CURRENT` + `UPGRADE` (`V1 → V2` WRITE, `V1..V4 → V4.1 → V5` READ) + reconstruction READ vierge (`ReadReconstructionPostgresTest` + `test_v3_operations.py`).
- **Preuve déploiement distribué + Bruno** : `./pocoma-local start --instance qualif-idgov` (**READY / QUALIFIED**) + `./pocoma-local bruno --instance qualif-idgov` (**BRUNO_PASS**) + `./pocoma-local destroy --instance qualif-idgov --confirm DESTROY:qualif-idgov` (**DESTROYED**).
- **Couverture complète des 42 modules** : chaque module du réacteur a été compilé, installé et testé sans filtre sur PostgreSQL 17.11 réel (`0` échec, `0` erreur).
