# Archives documentaires et preuves historiques

Cet espace centralise et indexe les documents historiques, anciennes migrations SQL et preuves de qualification antérieures des jalons **V2** et **L1–L7**.

## Statut des archives

> [!NOTE]
> **Conservation sans suppression** : ces documents et artefacts de preuve ne sont pas nécessaires pour installer, démarrer ou utiliser une distribution locale de Pocoma (voir [Démarrage rapide](../getting-started/quickstart.md)). Ils sont conservés sur leurs chemins d'origine pour préserver la traçabilité d'audit et la compatibilité des tests et scripts automatisés existants.

## Baselines SQL et migrations archivées

L'installation courante utilise uniquement les deux baselines `V1__initial_schema.sql` actives ([Persistance PostgreSQL](../architecture/persistence.md)). Les migrations antérieures ont été isolées :

- **Migrations V3 archivées (`19` fichiers — 11 WRITE, 8 READ)** : conservées hors classpath de production dans `docs/archive/v3-sql-migrations/` (`infra-postgres-write/` et `infra-postgres-read/`) avec le manifeste de contrôle [`MANIFEST.json`](v3-sql-migrations/MANIFEST.json). Voir le [rapport de consolidation S3](../v3/L7_LB_S3_SQL_Baseline_Consolidation_Report.md).
- **Migrations V2 historiques (`45` fichiers)** : isolées strictement dans les ressources de test Maven (`31` fichiers dans `app/infra-postgres-write/src/test/resources/db/migration/` et `14` dans `app/infra-postgres-read/src/test/resources/db/read-store/migration/`), sans aucune inclusion dans les JAR applicatifs. Voir le [rapport de stabilisation des 8 améliorations](../v3/L7_LB_Eight_Improvements_Stabilization_Report.md).

## Architecture V2 et spécifications historiques

Documents historiques V2 ou plans d'étapes antérieurs conservés pour référence technique et historique :

- **V2 fonctionnel, architecture et garanties** :
  - [Functional_Model.md](../product/Functional_Model.md) *(modèle V2 `/api/v1`)*
  - [Architecture.md](../architecture/Architecture.md) *(architecture V2 après WP6 — archivée)*
  - [System_Guarantees.md](../guarantees/System_Guarantees.md) *(garanties V2)*
- **Plans V2 archivés dans `docs/archive/`** :
  - [20260828-000000-v2-plan.md](20260828-000000-v2-plan.md)
  - [20260828-234057-step-3-plus-roadmap.md](20260828-234057-step-3-plus-roadmap.md)
  - [lot-7-read-side-implementation-plan.md](lot-7-read-side-implementation-plan.md)
  - [lot-7.2-read-store-boundary-plan.md](lot-7.2-read-store-boundary-plan.md)
  - [lot-7.9.2-query-version-resolution-design.md](lot-7.9.2-query-version-resolution-design.md)
  - [pipeline-event-materialization-plan.md](pipeline-event-materialization-plan.md)
- **Études, dettes et journaux d'architecture antérieurs** : conservés dans `docs/architecture/`, `docs/debts/`, `docs/plans/` et `docs/steps/`.
- **Fixtures et lancement manuel antérieurs** :
  - [cmd-start-runtimes.md](../operations/cmd-start-runtimes.md) *(câblage manuel / dev-bench des runtimes)*
  - [Functional_Local_Stack.md](../testing/Functional_Local_Stack.md) *(stack de test fonctionnel monolithique)*

## Rapports, plans et preuves de qualification L1–L7

- **Programme V3 / L7 et stabilisation L7-LB** :
  - [Matrice et architecture normatives V3](../v3/L7_Target_Modular_Architecture_and_Migration_Plan.md)
  - [Audit et plan de stabilisation L7-LB](../v3/L7_LB_Distribution_Stabilization_Audit_and_Plan.md)
  - [Rapport S1 — Stabilisation nommage Java](../v3/L7_LB_S1_Java_Naming_Stabilization_Report.md)
  - [Rapport S3 — Consolidation baselines SQL](../v3/L7_LB_S3_SQL_Baseline_Consolidation_Report.md)
  - [Rapport — Consolidation des huit améliorations](../v3/L7_LB_Eight_Improvements_Stabilization_Report.md)
  - [Registre des réserves locales connues](../v3/L7_Local_Playground_Reservations.md)
- **Spécifications, audits et preuves** :
  - Spécifications `SPEC-01` à `SPEC-10`, rapports d'itérations et dossiers de preuves sous `docs/v3/` et `docs/v3/evidence/`.
  - Rapports d'audit documentaire : [Documentation_Rationalization_Audit.md](../Documentation_Rationalization_Audit.md) et [Documentation_Truth_Validation.md](../Documentation_Truth_Validation.md).
  - Preuves et scripts SQL de référence sous `docs/testing/evidence/` et `docs/operations/sql/`.

Pour parcourir la liste chronologique complète des rapports L1–L7 et V2, consultez la section [Archives et historique des jalons L1-L7 et V2](../README.md#archives-et-historique-des-jalons-l1-l7-et-v2).
