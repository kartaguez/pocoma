# Pocoma V3 — Dossier de qualification ciblé et rapport de clôture

## Gouvernance et attribution de toutes les identités d'objets

Branche : `feat/v3-identity-governance` (issue de `main` `1c5140fe`, non fusionnée avec `main`)  
Document d'audit et d'architecture : [`V3_Object_Identity_Governance_Audit_and_Plan.md`](V3_Object_Identity_Governance_Audit_and_Plan.md)

---

## 1. Rapport normatif de vérification (`Reactor_Verification_Policy.md` §30 & §35)

```
Start HEAD:
1c5140fe (main)
Final branch:
feat/v3-identity-governance (uncommitted working tree ready for review/commit, never merged to main)
Expected production impact:
- contract-write-admission (PotCommand, CommandAdmissionInput)
- domain-projection (CommandTerminalResult)
- engine-write-pot (ExecutePotCommand, PotCommandStore)
- infra-postgres-write (V2__server_assigned_pot_identities.sql, PotCommandJson, JdbcAdmissionStore, JdbcPotCommandStore, JdbcPotCommandObservation)
- infra-postgres-write-source-read (JdbcCommandTerminalSource)
- infra-postgres-read (V2__server_assigned_pot_identities.sql, JdbcCommandTerminalResultStore)
- supra-web-read (ReadDtos, ReadController)
- Client/qualification scripts (bruno-playground, bruno-v3, local/public_api.py, operations/run-demo-journey.py, k6/lib/v3-api.js)
Observed production impact:
IDENTICAL to expected production impact (0 module outside declared impact; 0 pom.xml modified).
Declared slices:
- Primary: COMMAND (runtime-worker-process)
- Secondary: WEB (runtime-web-write, runtime-web-read), PROJECTION (runtime-worker-materialize)
Verification commands executed:
1. JAVA_HOME=/home/antigravity/.local/jdk-21 ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-process,runtime-web-write,runtime-web-read,runtime-worker-materialize -am compile
   -> BUILD SUCCESS (41 modules compiled under JDK 21.0.13.1)
2. JAVA_HOME=/home/antigravity/.local/jdk-21 ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-process,runtime-web-write,runtime-web-read,runtime-worker-materialize,runtime-monolith,architecture-tests -am test-compile
   -> BUILD SUCCESS (42 modules main + test compiled)
3. JAVA_HOME=/home/antigravity/.local/jdk-21 ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-process,runtime-web-write,runtime-web-read,runtime-worker-materialize -am test -Dtest='!*PostgresTest,!*FoundationCompositionTest,!AdmissionHttpTest,!LinkRetirementHttpTest,!ReadHttpContractTest,!StageHandoffsHttpTest,!WorkerMetricsTest,!ConsumptionPilotIsolationTest,!L2ManifestProof' -Dsurefire.failIfNoSpecifiedTests=false
   -> BUILD SUCCESS (55 test classes, 259 tests run, 0 failures, 0 errors, 0 skips)
Unexpected slice crossings:
NONE
Global architecture gate:
NOT REQUIRED (aucune dépendance Maven, frontière de couche ou règle d'isolation runtime modifiée)
Full reactor:
NOT REQUIRED (politique locale ; tests PostgreSQL/Testcontainers non exécutables sans Docker dans cet environnement)
Database verification:
CURRENT V1 + V2 migrations authored for WRITE and READ; live PostgreSQL execution NOT RUN in this sandbox (Docker absent)
```

---

## 2. Preuves unitaires et contractuelles exécutées (PASS)

| Suite / Classe | Module | Cas | Garanties vérifiées |
|---|---|---|---|
| `ServerAssignedIdentityAdmissionTest` | `compose-web-write-postgres` | 4/4 PASS | Rejet HTTP 400 de `potId` client sur `CreatePot`, de `targets[].id` sur `AddShareholders`, de `payload.id` sur `CreateExpense` ; admission `CreatePot` avec `potId == null` ; idempotence S-03 (replay 202 / conflit 409) sans `potId` ; conservation de l'ordre d'entrée `targets[]` (y compris noms identiques) et des références existantes (`payerId`, `participants[].id`). |
| `AdmissionInputTest`, `AdmissionCanonicalizationTest`, `AdmissionTransportTest`, `D4AdmissionCalibrationTest` | `compose-web-write-postgres` | 18/18 PASS | Mapping des 11+2 opérations, canonicalisation exacte des rationnels, capacité transport, absence de régression admission. |
| `ServerAssignedPotExecutionTest` | `engine-write-pot` | 4/4 PASS | Génération exclusive au traitement WRITE pour `CreatePot`, `AddShareholders`, `CreateExpense` ; zéro `PotId` généré/publié sur `INVALID_BINDING` ; collision UUID technique sur `CreatePot` -> `Collision` (retry avec nouvel UUID, jamais `ALREADY_EXISTS`) ; correspondance exacte `inputIndex` (`0..N-1`) sur lot `AddShareholders` ; restitution via `Result.potId` et `Result.createdResources`. |
| `ExecutePotCommandTest`, `PotCommandIntentTest` | `engine-write-pot` | 14/14 PASS | Non-régression complète du moteur `ExecutePotCommand`, compatibilité des intentions historiques `AddShareholders.legacy` / `CreateExpense` legacy. |
| `ServerAssignedPotCommandJsonTest`, `PotCommandJsonTest`, `BindingTokenGeneratorTest` | `infra-postgres-write` | 9/9 PASS | Round-trip `CreatePot` avec `potId == null` ; enveloppe `payloadVersion: 3` pour `AddShareholders` et `CreateExpense` (fail-closed sur anciens workers) ; compatibilité descendante `v1`/`v2` ; round-trip `CreatedResources` (`potId`, `expenseId`, `shareholders`). |
| `CommandResultCreatedResourcesTest` + suite `supra-web-read` | `supra-web-read` | PASS | `CommandTerminalResult` (`potId == null` autorisé uniquement sur `CreatePot` rejeté ; `createdResources` autorisé uniquement sur `CHANGED`) ; sérialisation `ReadDtos.Command` sans fuite d'IDs privés (`userId`, `euiId`). |
| Suites domaine, identité, consumption, matérialisation | `domain-*`, `engine-*`, `orchestrator-consumption`, `runtime-*` | PASS | Zéro régression sur les protocoles `Consumption`, `Identity` (`Registration`/`Unlink`), `Invitation` et `READ` (total : **55 classes, 259 tests, 0 échec, 0 erreur, 0 skip**). |

---

## 3. Commandes reproductibles à exécuter sur un environnement avec Docker / PostgreSQL

Les contrôles PostgreSQL réels (Testcontainers) et les parcours end-to-end Bruno/monolithe/distribué n'ont pas pu être exécutés dans cette VM dépourvue de démon Docker (`NOT RUN`). Pour lever ces réserves sur un poste ou runner équipé de Docker :

1. **Slice primaire `COMMAND` (unfiltered, incluant migrations V1+V2, triggers SQL, concurrence, fencing, recovery, crash/rollback)** :
   ```sh
   cd app && ./mvnw -pl runtime-worker-process -am test
   ```
2. **Slices secondaires `WEB` et `PROJECTION` (unfiltered, incluant isolation des rôles PostgreSQL, admission HTTP réelle, projection READ et reconstruction vierge)** :
   ```sh
   cd app && ./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-materialize -am test
   ```
3. **Composition monolithe et architecture** :
   ```sh
   cd app && ./mvnw -pl runtime-monolith,architecture-tests -am test
   ```
4. **Parcours Bruno Playground & Bruno V3** :
   - Relancer les journeys `J01`, `J02`, `J03` de `scripts/bruno-playground/` et les scénarios `I2`/`I4` de `scripts/bruno-v3/` sur le déploiement local (monolithe et 5 runtimes distribués).

---

## 4. Garanties acquises et réserves restantes

### Garanties acquises
1. **Inventaire exhaustif et classification complète** : 100 % des familles d'objets persistés (37 tables WRITE, 14+1 tables READ, 108 fichiers `domain-*`/`contract-*`, 15 opérations publiques, créations implicites et objets techniques) sont inventoriés et classifiés.
2. **Suppression de toute attribution cliente d'ID métier** : `CreatePot`, `AddShareholders` et `CreateExpense` rejettent désormais en HTTP `400` tout ID métier fourni par le client (`potId`, `targets[].id`, `payload.id`). Aucun autre chemin actif ne laisse le client choisir un ID interne.
3. **Attribution exclusivement au traitement WRITE** : `ExecutePotCommand` génère `PotId`, `ShareholderId` et `ExpenseId` dans l'attempt d'exécution (jamais à l'admission). Les rejets et rollbacks ne publient aucun ID. Les collisions techniques sur `CreatePot` déclenchent une reprise (`Collision`) et non un rejet métier `ALREADY_EXISTS`.
4. **Immuabilité des commandes & compatibilité historique** : `v3_command.pot_id` est nullable pour `CreatePot` sans jamais muter `v3_command` au traitement (`v3_command_intention` intact). Les formats `v1`/`v2` et les commandes/résultats historiques restent compatibles.
5. **Résultats terminaux durables et exacts** : `v3_command_result` et `read_command_result` portent le `pot_id` assigné et `created_resources` (`potId`, `expenseId`, `shareholders: [{inputIndex, shareholderId}]`) restitués via `GET /api/v3/commands/{requestId}/result`.
6. **Isolation des rôles PostgreSQL préservée** : le chemin `PENDING` du trigger `SECURITY INVOKER` `v3_require_command_unit()` n'ajoute aucun besoin de privilège à `admission_role`, et `source_role` conserve sa projection stricte sans accès à `binding_token` ni `payload`.

### Réserves restantes (explicites)
1. **Exécution PostgreSQL / Testcontainers (`NOT RUN`)** : les migrations Flyway `V2__server_assigned_pot_identities.sql` (WRITE et READ), les triggers PL/pgSQL (`v3_require_event_request`, `v3_require_command_unit`), les tests de concurrence/fencing/crash/recovery, la reconstruction READ vierge et les tests HTTP Testcontainers doivent être rejoués sur un environnement disposant de Docker via les commandes de la §3.
2. **Parcours Bruno end-to-end & déploiement distribué (`NOT RUN`)** : les collections `scripts/bruno-playground/` et `scripts/bruno-v3/` ainsi que `scripts/local/public_api.py` et `scripts/operations/run-demo-journey.py` ont été mis à jour mais restent à exécuter contre des runtimes vivants.
3. **Scripts de charge / benchmark secondaires** : `scripts/k6/lib/v3-api.js` a été adapté pour `CreatePot`, mais les scripts de seeding/charge spécialisés (`scripts/k6/v3-performance.js`, `scripts/performance/*.py`) et `scripts/bruno-playground/catalogue.json` devront être alignés avant toute nouvelle campagne SPEC-09.
4. **Constat préexistant hors périmètre (CR-02)** : `AttachShareholderViaInvitation` continue de référencer `potId` existant en entrée (conforme au principe 5 — référence d'objet existant, non création d'ID) ; la résolution autonome du `potId` pour un invité muni uniquement du lien reste un sujet UX distinct.
