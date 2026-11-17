# L7-LB / S3 — Rapport de consolidation des baselines SQL WRITE et READ

Date : **15 novembre 2026**
Mandat humain : `GO_L7_LB_S3_SQL_BASELINE`
Branche : `feat/l7-lb-local-playground`

**Verdict : `L7_LB_S3_SQL_BASELINES_QUALIFIED_WITH_RESERVATIONS`**

> Les baselines SQL WRITE et READ sont consolidées et prouvées équivalentes (`pg_dump --schema-only`, catalogues système et données initiales) sur bases PostgreSQL 17.6 isolées (T1–T4 en 13,98 s au total). La réserve porte uniquement sur les vérifications volontairement exclues par le mandat (compilation/exécution des tests Java sous `app/` et démarrage complet `./pocoma-local start`). Ce rapport ne prononce pas la clôture de `L7-LB` et n'engage pas S4.

---

## 1. SHA initial

- `ace564c4c3019dea6b72724e66ce1e1fe46de616` (état post-S1 sur `feat/l7-lb-local-playground`).

## 2. Inventaire des migrations remplacées

### Chaîne WRITE remplacée (11 fichiers, `pocoma_write.v3_write`)

1. `app/infra-postgres-write/src/main/resources/db/v3-pot/migration/V1__pot_components.sql`
2. `app/infra-postgres-write/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql`
3. `app/infra-postgres-write/src/main/resources/db/v3-pot/migration/V3__point_history_indexes.sql`
4. `app/infra-postgres-write/src/main/resources/db/v3-pot/migration/V4__write_effect_indexes.sql`
5. `app/infra-postgres-write/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql`
6. `app/infra-postgres-write/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql`
7. `app/infra-postgres-write/src/main/resources/db/v3-admission/migration/V7__public_admission.sql`
8. `app/infra-postgres-write/src/main/resources/db/v3-admission/migration/V8__admission_column_reads.sql`
9. `app/infra-postgres-write/src/main/resources/db/v3-admission/migration/V9__link_proposal.sql`
10. `app/infra-postgres-write/src/main/resources/db/v3-invitation/migration/V10__invitation_history.sql`
11. `app/infra-postgres-write/src/main/resources/db/v3-identity/migration/V11__sequential_request_publication.sql`

### Chaîne READ remplacée (8 fichiers, `pocoma_read.v3_read`)

1. `app/infra-postgres-read/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql`
2. `app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V2__subobject_version.sql`
3. `app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V3__pot_version.sql`
4. `app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V4__pot_balance.sql`
5. `app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V5__pot_visibility.sql`
6. `app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V6__current_binding.sql`
7. `app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V7__terminal_results.sql`
8. `app/infra-postgres-read/src/main/resources/db/v3-read-invitation/migration/V10__current_invitation.sql`

Les scripts opérationnels/ACL/routage hors Flyway (`scripts/v3/*.sql`), les migrations historiques V2 (`db/migration/`, `db/read-store/migration/`) encore utilisées par leurs tests dédiés, et la fixture test `db/v3-foundation/migration/` ont été conservés inchangés.

## 3. Baselines créées

- **WRITE** : `app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql`
  - SHA-256 : `dac090231468fbbd8939e94862f2bc1eef658f84753a95e56b33891d69abd62d`
  - Inclut l'état final consolidé (37 tables, 221 colonnes, 271 contraintes, 116 index, 8 séquences d'identité, 38 fonctions dont `v3_require_command_unit` V10/V8 et `v3_identity_check_unit` V8 en `SECURITY INVOKER` et `consumption_row_guard` V11, 62 triggers, ACLs, compteurs `v3_event_counter` et `v3_request_counter` ; sans `v3_command_slot` ni `v3_request_privilege_probe`).
- **READ** : `app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql`
  - SHA-256 : `dcec6000655dc638ba220b620d451d6e21b1ac2bab42da27548f81820e6646ea`
  - Inclut l'état final consolidé (14 tables, 74 colonnes, 67 contraintes incluant `read_command_result_write_outcome_check` et `read_command_result_shape` de V10, 22 index, 5 fonctions, 16 triggers dont `ENABLE ALWAYS`, ACLs par table et par colonne).

## 4. Archives produites

Les 19 fichiers de migration V3 ont été déplacés hors de `src/main/resources/` (hors du classpath exécutable Flyway) vers `docs/archive/v3-sql-migrations/` en préservant leur contenu exact, leur structure d'origine, leur ordre et leur provenance :

- `docs/archive/v3-sql-migrations/infra-postgres-write/db/v3-*/migration/V*.sql` (11 fichiers)
- `docs/archive/v3-sql-migrations/infra-postgres-read/db/v3-*/migration/V*.sql` (8 fichiers)
- `docs/archive/v3-sql-migrations/MANIFEST.json` (SHA de référence `ace564c4c3019dea6b72724e66ce1e1fe46de616`, ordre, chemin d'origine, chemin archivé et SHA-256 de chaque fichier).

Aucun fichier `db/v3-*` ne subsiste sous `app/infra-postgres-write/src/main/resources/` ni `app/infra-postgres-read/src/main/resources/`.

## 5. Méthode de comparaison

Dans un conteneur PostgreSQL `postgres:17.6-alpine` isolé (`pocoma-s3-verify`, supprimé en fin de test, sans toucher `demo` ni aucun volume existant) :

1. Création de 4 bases vides : `write_old`, `write_new`, `read_old`, `read_new`.
2. Application des 11 migrations archivées WRITE + scripts `scripts/v3/*.sql` sur `write_old` ; application de `db/write/migration/V1__initial_schema.sql` + mêmes scripts sur `write_new`.
3. Application de `bootstrap-read-maintenance.sql` + 8 migrations archivées READ + scripts `scripts/v3/*.sql` sur `read_old` ; application de `bootstrap-read-maintenance.sql` + `db/read/migration/V1__initial_schema.sql` + mêmes scripts sur `read_new`.
4. Comparaison complète :
   - `pg_dump --schema-only --schema=<schema>` (avec propriétaires et ACLs, en excluant uniquement les jetons `\restrict`/`\unrestrict` de PostgreSQL 17).
   - Catalogues JSON triés : `information_schema.tables`, `information_schema.columns`, `pg_constraint` (définition + `condeferrable`/`condeferred`), `pg_indexes`, `pg_sequences`, `pg_proc` (`pg_get_functiondef`, arguments, volatilité, strictness, `prosecdef`, `proconfig`, `proacl`), `pg_trigger` (`pg_get_triggerdef`, `tgenabled`), `information_schema.views`, `pg_extension`, `pg_class.relacl` + owner, `pg_attribute.attacl`.
   - Données initiales : `v3_event_counter`, `v3_request_counter`, `consumption_routing_contract`.

## 6. Résultats WRITE

- **Écarts catalogue (`diffs`)** : `[]` (aucun écart).
- **`pg_dump --schema-only` identique** : `True`.
- **Objets vérifiés** : 37 tables, 221 colonnes, 271 contraintes, 116 index, 8 séquences, 38 fonctions, 62 triggers, 0 vue.
- **Données initiales** (`v3_event_counter`, `v3_request_counter`, `consumption_routing_contract`) : identiques (`True`).

## 7. Résultats READ

- **Écarts catalogue (`diffs`)** : `[]` (aucun écart).
- **`pg_dump --schema-only` identique** : `True`.
- **Objets vérifiés** : 14 tables, 74 colonnes, 67 contraintes, 22 index, 0 séquence, 5 fonctions, 16 triggers, 0 vue.
- **Données initiales** (`consumption_routing_contract`) : identiques (`True`).

## 8. Résultat du refus des anciennes instances

Protection implémentée dans `scripts/local/bootstrap.py` (`verify_compatible_state`) et `scripts/operations/setup-v3-database.py` (`verify_compatible_target`), exécutée **avant** toute création/modification de base, rôle, mot de passe ou schéma :

- **T3a (`bootstrap.initialize` avec `flyway_schema_history`)** : refus explicite (`INCOMPATIBLE-LEGACY-FLYWAY-HISTORY: database 'pocoma_write' contains flyway_schema_history in schema(s) [v3_write]; refusing implicit baseline migration without modifying data.`). Vérification post-refus : `pg_authid` (rôles et mots de passe), `flyway_schema_history`, `v3_event_counter` et absence de `public.pocoma_local_bootstrap` inchangés.
- **T3b (`bootstrap.initialize` avec schéma `v3_write` préexistant sans marqueur)** : refus explicite (`INCOMPATIBLE-EXISTING-SCHEMA: database 'pocoma_write' schema 'v3_write' already contains tables without matching baseline marker; refusing implicit migration without modifying data.`).
- **T3c (`setup-v3-database.py` autonome avec `flyway_schema_history`)** : code de retour non nul avec diagnostic `INCOMPATIBLE-LEGACY-FLYWAY-HISTORY`.

## 9. Tests exécutés et durée

| Test | Description | Durée | Résultat |
| --- | --- | ---: | --- |
| T1 | Application sur bases vierges (`write_old`, `write_new`, `read_old`, `read_new`) | 3,15 s | PASS |
| T2 | Équivalence des schémas et données initiales WRITE et READ | 3,81 s | PASS |
| T3 | Protection d'une ancienne instance (`T3a`, `T3b`, `T3c`) | 2,71 s | PASS |
| T4 | Provisioning neuf via `bootstrap.initialize` + idempotence + `prerequisites.sources` | 2,57 s | PASS |
| Unitaires locaux | `test_local.py` (21 tests) + `test_prerequisites.py` (40 tests) | 0,33 s | PASS |
| **Total** | **Suite SQL isolée (`13,65 s` incluant démarrage conteneur) + unitaires locaux (`0,33 s`)** | **13,98 s** | **PASS** |

## 10. Écarts résiduels et rapport de politique de vérification

- Les 29 fichiers de tests Java sous `app/*/src/test/java/` référençant `classpath:db/v3-*` ont été mis à jour vers `classpath:db/write/migration` / `classpath:db/read/migration` (et vers l'archive `docs/archive/v3-sql-migrations/` pour les tests d'historique/migration V1→V2 `SubobjectMigrationPostgresTest`, `SubobjectPostgresFixture`, `ConsumptionFoundationArchitectureTest`, `AdmissionArchitectureTest`). Conformément au mandat, ni le réacteur Maven ni la suite Java globale n'ont été exécutés.
- `scripts/operations/v3-rebuild-read.py` a été mis à jour pour référencer `app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql` ; la reconstruction READ complète n'a pas été exécutée (exclue par le mandat).

```text
Start HEAD: ace564c4c3019dea6b72724e66ce1e1fe46de616
Expected production impact: SQL baselines WRITE/READ uniquement (V1__initial_schema.sql) et archivage db/v3-* hors src/main/resources/
Observed production impact: Baselines SQL WRITE/READ, archivage des 19 migrations V3, mise à jour bootstrap/rebuild/setup et 29 fichiers de tests Java
Declared slices: COMMAND & PROJECTION SQL baselines (T1-T4 isolés), tooling local
Verification commands executed:
- python3 .../run_s3_tests.py (T1-T4 sur postgres:17.6-alpine isolé) -> PASS (13.65s)
- python3 -m unittest discover -s scripts/local -p "test_local.py" -> PASS (21 tests, 0.056s)
- python3 -m unittest discover -s scripts/local -p "test_prerequisites.py" -> PASS (40 tests, 0.270s)
Unexpected slice crossings: NONE
Global architecture gate: NOT REQUIRED (exclu par mandat GO_L7_LB_S3_SQL_BASELINE)
Full reactor: NOT REQUIRED (interdit par mandat GO_L7_LB_S3_SQL_BASELINE)
Database verification: BASELINE + HISTORICAL equivalence (T1-T4 PASS)
```

## 11. SHA final

Voir le commit publié sur `feat/l7-lb-local-playground`.
