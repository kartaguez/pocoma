# Persistance PostgreSQL et baselines SQL

Pocoma exécute une séparation physique des données entre le store d'écriture (`pocoma_write`) et le store de lecture (`pocoma_read`), hébergés sur une instance `postgres:17.6-alpine` locale sans port hôte exposé.

## Séparation stricte des bases WRITE et READ

| Aspect | Store WRITE | Store READ |
| :--- | :--- | :--- |
| Base PostgreSQL | `pocoma_write` | `pocoma_read` |
| Schéma principal | `v3_write` | `v3_read` (+ table `public.read_maintenance_state`) |
| Runtimes en écriture | `http-write`, `command-worker` | `results-worker`, `business-read-worker` |
| Runtimes en lecture seule | `results-worker`, `business-read-worker` (via droits `write-source-read`) | `http-read` |
| Chemin Flyway courant | `classpath:db/write/migration` | `classpath:db/read/migration` |

Les privilèges minimaux et la configuration des schémas locaux sont appliqués par [`scripts/operations/setup-v3-database.py`](../../scripts/operations/setup-v3-database.py) à l'aide des scripts SQL dédiés sous `scripts/v3/` :

- `configure-l3-identity-logging.sql`
- `bootstrap-read-maintenance.sql`
- `grant-l7-http-read.sql`
- `grant-l7-write-source-read.sql`
- `bootstrap-l2-routing.sql`

## Baselines SQL actives (V1)

Chaque store dispose d'une **baseline unique et active** `V1__initial_schema.sql` :

- **WRITE** : [`app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql`](../../app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql)
  - `37` tables, `221` colonnes, `271` contraintes, `116` index, `8` séquences, `38` fonctions, `62` triggers.
- **READ** : [`app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql`](../../app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql)
  - `14` tables, `74` colonnes, `67` contraintes, `22` index, `5` fonctions, `16` triggers.

Aucune autre migration SQL ne réside dans les répertoires `src/main/resources/` de production.

## Baselines consolidées et isolation des migrations historiques

Pour fournir une distribution neuve sans historique Flyway incrémental à rejouer au démarrage, les migrations antérieures ont été isolées hors du chemin de production :

- **19 migrations V3 archivées** (`11` fichiers WRITE et `8` fichiers READ) : conservées pour audit et traçabilité dans [`docs/archive/v3-sql-migrations/`](../archive/v3-sql-migrations/MANIFEST.json) avec leur manifeste `MANIFEST.json`. Voir [Index des archives](../archive/README.md).
- **45 migrations historiques V2 isolées dans les ressources de test** (non embarquées dans les JAR applicatifs de production) :
  - `app/infra-postgres-write/src/test/resources/db/migration/` : `31` fichiers SQL V2 ;
  - `app/infra-postgres-read/src/test/resources/db/read-store/migration/` : `14` fichiers SQL V2 (en complément de `app/infra-postgres-read/src/test/resources/db/primary-test-migration/V1__initialize_primary_test.sql` pour les tests).

Ces archives ne sont **jamais** nécessaires pour installer ou démarrer une instance locale de Pocoma.

## Contrôle d'intégrité au bootstrap et refus d'instances incompatibles

Lors du bootstrap de base par `./pocoma-local`, une empreinte est enregistrée dans la table de contrôle :

```sql
public.pocoma_local_bootstrap(fingerprint text PRIMARY KEY)
```

À chaque démarrage ultérieur, l'orchestrateur compare l'état existant en base avec les entrées courantes du dépôt :

| Code de refus | Condition détectée | Action attendue |
| :--- | :--- | :--- |
| `INCOMPATIBLE-LEGACY-FLYWAY-HISTORY` | Présence d'un ancien historique Flyway antérieur à la baseline V1 consolidée | Ne pas migrer manuellement ; utiliser `reset` ou `destroy` ou choisir un nouveau `--instance` |
| `INCOMPATIBLE-EXISTING-SCHEMA` | Schéma existant dépourvu du marqueur attendu ou structurellement divergent | Ne pas forcer sur place ; réinitialiser via `reset` ou isoler dans une autre instance |
| `DB-INPUT-DRIFT` | L'empreinte SQL/configuration courante diffère de `pocoma_local_bootstrap` | Réinitialiser explicitement (`reset --confirm RESET:<instance>`) si le changement est voulu |

## Tables de coordination et de routage

En complément des tables métier des schémas `v3_write` et `v3_read`, les runtimes et l'orchestrateur s'appuient notamment sur les tables techniques de suivi des baux et des workers :

- `pocoma_v3_lease_local` : détention courante et échéance des baux de partitions L2 (`lease-duration-ms=30000`, renouvelés par `renewBatch`).
- `pocoma_v3_worker_state` : état observé des workers et métadonnées de cycle de vie utilisées lors du démarrage et des vérifications de reprise ([Reprise après panne](../operations/recovery.md)).
