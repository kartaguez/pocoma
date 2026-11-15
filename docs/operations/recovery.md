# Reprise après panne et cycle de vie

Ce guide décrit le cycle de vie complet d'une instance locale [`./pocoma-local`](../reference/cli.md) et les procédures de récupération après arrêt brutal ou coupure PostgreSQL.

## 1. Cycle de vie d'une instance

```text
   (absente)
       │
       ▼  ./pocoma-local start --instance <nom>
   [ READY ] ◀──────────────────────────────┐
       │                                    │ start (même empreinte)
       ├── stop ──▶ [ STOPPED (données conservées) ]
       │
       ├── upgrade-grafana ──▶ mise à jour configuration Grafana uniquement
       │
       ├── reset --confirm RESET:<nom> ──▶ réinitialisation bases/données depuis V1
       │
       └── destroy --confirm DESTROY:<nom> ──▶ suppression complète (conteneurs, volumes, état)
```

- **Confirmation obligatoire** : `reset` et `destroy` refusent d'agir sans `--confirm RESET:<instance>` ou `--confirm DESTROY:<instance>`. Utilisez `--dry-run` pour simuler sans effet destructeur (`--dry-run` est rejeté avec `code 2` sur toutes les autres sous-commandes).
- **Ne jamais modifier une instance à la main** : évitez les suppressions manuelles partielles de conteneurs, volumes ou tables PostgreSQL ; utilisez exclusivement `stop`, `reset` ou `destroy`.

## 2. Redémarrage après arrêt gracieux (`stop` → `start`)

`./pocoma-local stop --instance <nom>` arrête proprement les 9 conteneurs tout en conservant les bases `pocoma_write` / `pocoma_read`, l'état Keycloak, les métriques Prometheus et les secrets locaux sous `.pocoma-local/instances/<nom>/`.

Pour redémarrer :

```bash
./pocoma-local start --instance <nom>
```

## 3. Récupération après coupure PostgreSQL

Lors d'une coupure prolongée de PostgreSQL, les trois workers (`command-worker`, `results-worker`, `business-read-worker`) basculent en mode **fail-closed** (`draining`) :
- `/actuator/health` passe à **`503 DOWN`** (`pocoma_v3_worker_state{state="draining"} = 1.0`), tandis que `/actuator/health/readiness` peut rester `200 UP`.
- Une admission `http-write` restée `UNRESOLVED` pendant la coupure peut également consommer un permis (`pocoma_v3_admission_capacity_available < 4.0`).

### Procédure de récupération

1. Assurez-vous que le service PostgreSQL est de nouveau accessible.
2. Lancez simplement :

   ```bash
   ./pocoma-local start --instance <nom>
   ```

3. `./pocoma-local start` vérifie d'abord la disponibilité de PostgreSQL et de Keycloak (si PostgreSQL est encore down, `start` échoue proprement sur `DB-INIT-TIMEOUT` sans redémarrer inutilement les runtimes).
4. Une fois PostgreSQL sain, l'orchestrateur détecte automatiquement les workers en état `draining` et l'éventuelle saturation résiduelle de `http-write`, les redémarre **une seule fois** de manière bornée (journalisé dans `runtime-recovery.json`), attend la réacquisition des **36 baux** avec de nouveaux `owner_token` (fencing), puis exécute le canari.
5. Vérifiez le retour à l'état nominal :

   ```bash
   ./pocoma-local status --instance <nom>
   ```

   Attendu : `exit=0`, `state=READY`, `operationalState=OPERATIONAL`, `qualificationState=QUALIFIED`.

## 4. Reprise après arrêt brutal (crash Docker / kill)

Après un arrêt non gracieux :

- Le TTL SQL nominal des baux de partition est de **`30 s`** (`lease-duration-ms=30000`), avec renouvellement groupé (`renewBatch`) toutes les **`10 s`**.
- La reprise complète combine l'expiration des baux (`30 s`), les cycles de scrutation des workers (`min-poll=2 ms`, `max-poll=20 ms`), l'éventuel `claim-duration-ms=60000` si un slot était réclamé, et la convergence des projections.
- `./pocoma-local` applique un **budget d'observation de récupération borné à `270 s`**.

Si une commande atteint un délai d'attente côté client, consultez le résultat final avec l'identifiant d'exécution affiché :

```bash
./pocoma-local status --instance <nom> --run <run-id>
```

## 5. Remise à zéro ou suppression complète

- **Mettre à jour uniquement Grafana** (configuration et dashboards en lecture seule) :

  ```bash
  ./pocoma-local upgrade-grafana --instance <nom>
  ```

- **Réinitialiser les bases depuis les baselines V1** (toutes les données métier locales sont effacées) :

  ```bash
  ./pocoma-local reset --instance <nom> --dry-run
  ./pocoma-local reset --instance <nom> --confirm RESET:<nom>
  ```

- **Supprimer complètement l'instance** (conteneurs, volumes et répertoire `.pocoma-local/instances/<nom>/`) :

  ```bash
  ./pocoma-local destroy --instance <nom> --dry-run
  ./pocoma-local destroy --instance <nom> --confirm DESTROY:<nom>
  ```
