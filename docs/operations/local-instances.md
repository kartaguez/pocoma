# Exploitation des instances locales (`pocoma-local`)

L'outil [`./pocoma-local`](../reference/commands.md) gère le cycle de vie complet d'instances locales persistantes et isolées par nom (`--instance <nom>`).

## Cycle de vie d'une instance

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

- **Nom d'instance** : par défaut `demo`. Tout nom personnalisé doit respecter l'expression régulière `[a-z][a-z0-9-]{0,30}`.
- **Confirmation explicite des opérations destructrices** : `reset` et `destroy` refusent d'agir sans `--confirm RESET:<instance>` ou `--confirm DESTROY:<instance>`. Utilisez `--dry-run` pour vérifier l'impact avant confirmation.
- **Ne jamais modifier une instance existante à la main** : évitez les suppressions manuelles partielles de conteneurs, volumes ou tables PostgreSQL ; utilisez exclusivement `stop`, `reset` ou `destroy`.

## Organisation sur disque (`.pocoma-local`)

Les données propres aux instances locales sont isolées sous `.pocoma-local/instances/<instance>/` :

- Métadonnées et empreintes d'instance permettant la détection de dérive (`PRE-INPUT-DRIFT`).
- Secrets locaux générés pour l'instance (contrôlés au démarrage via `PRE-SECRETS-MISSING`).
- Journaux par exécution (`run-id` au format `[0-9]{10}-[0-9a-f]{8}`), consultables notamment après dépassement de délai via `./pocoma-local status --instance <nom> --run <run-id>`.
- Volumes Docker dédiés pour PostgreSQL (`pocoma_write`, `pocoma_read`), Keycloak et Prometheus.

> [!NOTE]
> Les prérequis et l'isolation réseau des instances s'appuient également sur les helpers situés dans `scripts/local/` (dont `inventory.py` pour l'inventaire des ressources locales et `prerequisites.py` pour les contrôles d'environnement).

## Conservation des données, verrouillage de build et upgrade Grafana

### Conservation vs réinitialisation

- **`stop`** arrête les 9 conteneurs tout en conservant intégralement les bases `pocoma_write`/`pocoma_read`, l'état Keycloak et les secrets locaux.
- **`reset`** reconstruit une instance propre à partir des baselines SQL V1 actives (sans rejouer d'anciennes migrations V2/V3) après confirmation explicite.
- **`destroy`** supprime les conteneurs, volumes et fichiers locaux de l'instance.

### Verrous de concurrence

Trois types de verrous empêchent les opérations concurrentes incompatibles (code de retour CLI **`3`**) :

- `PRE-INSTANCE-LOCKED` : une autre commande s'exécute déjà sur la même instance.
- `PRE-LIFECYCLE-LOCKED` : verrou de cycle de vie actif sur l'instance.
- `PRE-BUILD-LOCKED` : une construction Maven/image est déjà en cours.

### Mise à jour Grafana sans perte de données

La commande `./pocoma-local upgrade-grafana --instance <nom>` met à niveau ou rafraîchit la configuration Grafana (`12.3.2`, dashboards en lecture seule *Pocoma runtime* et *Pocoma V3 - Architecture & Observability Overview*) sans réinitialiser PostgreSQL ni détruire vos données applicatives.

## Politique d'observation et budget de récupération

Lorsqu'une instance redémarre après un arrêt non gracieux (coupure Docker ou arrêt brutal de processus) :

- Le TTL SQL nominal des baux de partition est de **`30 s`** (`lease-duration-ms=30000`), avec renouvellement groupé (`renewBatch`) toutes les **`10 s`**.
- Toutefois, la reprise complète combine expiration des baux, intervalles de scrutation des workers (`min-poll=2`, `max-poll=20`), éventuel `claim-duration-ms=60000` et convergence des projections.
- Pour cette raison, `./pocoma-local` applique un **budget d'observation de récupération borné à `270 s`**.

Si une commande atteint un délai d'attente côté client, relancez `./pocoma-local status --instance <nom> --run <run-id>` avec l'identifiant d'exécution affiché pour lire le résultat final dès qu'il est disponible. Voir aussi [L7_Local_Playground_Recovery_and_Evidence.md](../v3/L7_Local_Playground_Recovery_and_Evidence.md).
