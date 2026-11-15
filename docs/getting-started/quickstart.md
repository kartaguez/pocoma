# Démarrage rapide (Quickstart)

Ce guide décrit le parcours complet pour installer, démarrer, vérifier et exploiter une instance locale neuve de Pocoma avec `./pocoma-local`.

> [!NOTE]
> Les anciennes migrations V2 et V3 ont été consolidées en deux baselines initiales `V1__initial_schema.sql` (WRITE et READ). **Aucune migration historique manuelle n'est à exécuter** lors de l'installation. Voir [Persistance PostgreSQL et baselines SQL](../architecture/persistence.md).

## 1. Préparer le poste et vérifier les prérequis

Consultez d'abord [Prérequis et préparation du poste](prerequisites.md) et vérifiez que Docker Desktop / Docker Engine est démarré et que les variables d'environnement JVM/Maven globales sont vides :

```bash
docker info >/dev/null && python3 --version && java -version && node -v && npm -v
```

Vous pouvez valider la chaîne d'outillage sans créer ni détruire d'instance en inspectant l'aide CLI :

```bash
./pocoma-local --help
```

Les prérequis (`toolchain.json`, socket Docker, absence de variables interdites et absence de verrou actif) sont vérifiés automatiquement au tout début de `./pocoma-local start`. En cas d'échec préalable, la commande s'arrête immédiatement avec le **code de retour `2`** (ou `3` en cas de verrou actif) et un code explicite `PRE-*`.

## 2. Créer et démarrer une instance locale

Depuis la racine du dépôt, lancez l'instance par défaut (`demo`) ou précisez un nom d'instance conforme à `[a-z][a-z0-9-]{0,30}` :

```bash
./pocoma-local start --instance demo
```

Lors d'une création d'instance neuve, `./pocoma-local start` enchaîne de manière déterministe :

1. **Contrôle des prérequis et génération des secrets locaux** (conservés localement dans l'espace d'instance et masqués dans les journaux).
2. **Construction des artefacts Maven** et des images locales si nécessaire (protégée par un verrou de build).
3. **Démarrage de PostgreSQL (`postgres:17.6-alpine`)** et initialisation des deux bases isolées :
   - `pocoma_write` (schéma `v3_write`, baseline active V1) ;
   - `pocoma_read` (schéma `v3_read`, baseline active V1).
4. **Initialisation du routage L2** : création des 36 partitions dans la table de routage (`8` sur `command-worker`, `8` sur `results-worker`, `20` sur `business-read-worker`) et inscription de l'empreinte de bootstrap (`public.pocoma_local_bootstrap`).
5. **Démarrage de Keycloak (`26.7.0`)** avec import du royaume local, puis **démarrage des 5 runtimes JVM**, de **Prometheus** (`2s`) et de **Grafana** (`12.3.2`).
6. **Contrôle de disponibilité (`READY`) et exécution du canari fonctionnel**.

## 3. Vérifier l'état READY et le canari

À tout moment, consultez l'état consolidé de l'instance et le résultat du canari :

```bash
./pocoma-local status --instance demo
```

Pour cibler une exécution précise (format `[0-9]{10}-[0-9a-f]{8}`) :

```bash
./pocoma-local status --instance demo --run <run-id>
```

Interprétation du code de sortie de `status` :

| Code | État | Signification |
| :---: | :--- | :--- |
| `0` | `READY` | Les 9 conteneurs sont sains et le canari a réussi. |
| `4` | `DEGRADED` | Les services métier et le canari fonctionnent, mais l'observabilité Prometheus est indisponible (sans échec bloquant Grafana). |
| `1` | `NOT_READY` / `STOPPED` / `STALE` | Instance arrêtée, en cours de démarrage, ou en échec de disponibilité/canari. |
| `2` / `3` | `PRE-*` / verrou | Prérequis manquant ou opération concurrente verrouillée sur la même instance. |

## 4. Accéder aux services et à l'API

La sortie de `./pocoma-local status --instance demo` affiche les URL locales exposées pour votre instance :

- **API publique HTTP** : routes contractuelles `/api/v3` réparties entre `http-write` (port interne `8080`, admission des commandes) et `http-read` (port interne `8082`, lecture seule). Voir [Référence API publique V3](../v3/L7_Local_Playground_Public_API.md).
- **Keycloak (`26.7.0`)** :
  - Console d'administration : identifiants locaux `admin` / `pocoma`.
  - Comptes applicatifs du playground : `third`, `guest` et `bruno` (mot de passe commun `pocoma`).
- **Grafana (`12.3.2`)** :
  - Connexion locale : `admin` / `pocoma`.
  - Tableaux de bord provisionnés en lecture seule : **`Pocoma runtime`** et **`Pocoma V3 - Architecture & Observability Overview`**. Voir [Observabilité locale](../v3/L7_Local_Playground_Observability.md).
- **PostgreSQL (`17.6-alpine`)** : volontairement isolé sans aucun port hôte publié.

## 5. Exécuter une première opération (Bruno)

Pour effectuer vos premières requêtes manuelles via l'interface Bruno avec authentification OIDC automatique :

```bash
./pocoma-local gui --instance demo
```

Pour ouvrir ou renouveler une session OIDC en ligne de commande :

```bash
./pocoma-local login --instance demo
```

Pour exécuter la collection de parcours automatisés (`J01`, `J02`, `J03`, soit 118 requêtes et 236 tests nommés) :

```bash
./pocoma-local bruno --instance demo
```

L'option `--interactive` permet d'exécuter Bruno en mode interactif lorsque cela est nécessaire. Pour le détail des parcours et des comptes, consultez [Guide Bruno](../v3/L7_Local_Playground_Bruno.md).

## 6. Diagnostiquer un problème courant

En cas d'erreur au démarrage ou d'état `NOT_READY` / `DEGRADED` :

1. Relancer `./pocoma-local status --instance demo` et noter le code d'erreur retourné (ex. `PRE-*`, `KEYCLOAK_*`, `GRAFANA-*`, `DB-INPUT-DRIFT`, `INCOMPATIBLE-*`).
2. Inspecter le répertoire de logs de l'exécution mentionné par `status` sous `.pocoma-local/instances/<instance>/`.
3. Consulter le catalogue des diagnostics et remédiations dans [Diagnostic et résolution des incidents locaux](../operations/troubleshooting.md) et [Diagnostics Keycloak](../v3/L7_Local_Playground_Keycloak_Diagnostics.md).

## 7. Arrêter, redémarrer, réinitialiser ou supprimer l'instance

- **Arrêter en conservant les données** (bases PostgreSQL, état Keycloak, secrets et volumes intacts) :

  ```bash
  ./pocoma-local stop --instance demo
  ```

- **Redémarrer** après un `stop` ou un redémarrage de Docker Desktop :

  ```bash
  ./pocoma-local start --instance demo
  ```

  Les baux de partition SQL ont une durée nominale de **30 s** (`lease-duration-ms=30000`). Après un arrêt brutal ou non gracieux, `pocoma-local` applique un budget d'observation borné à **270 s** pour la récupération complète des workers et du canari.

- **Mettre à jour uniquement Grafana** (configuration / dashboards) sans réinitialiser les bases :

  ```bash
  ./pocoma-local upgrade-grafana --instance demo
  ```

- **Réinitialiser les données** (repartir sur des bases WRITE/READ neuves depuis les baselines V1) :

  ```bash
  # Simulation préalable sans effet destructeur :
  ./pocoma-local reset --instance demo --dry-run

  # Exécution effective :
  ./pocoma-local reset --instance demo --confirm RESET:demo
  ```

- **Supprimer complètement l'instance** (conteneurs, volumes, état et secrets locaux) :

  ```bash
  # Simulation préalable :
  ./pocoma-local destroy --instance demo --dry-run

  # Exécution effective :
  ./pocoma-local destroy --instance demo --confirm DESTROY:demo
  ```

## Comportement en présence d'une instance existante

Lorsqu'une instance `<instance>` existe déjà, `./pocoma-local start --instance <instance>` adopte un comportement conservateur afin de ne jamais écraser ni corrompre vos données silencieusement :

- **Empreinte d'entrée inchangée** : les conteneurs existants sont redémarrés, les bases et secrets sont réutilisés en l'état, puis la santé (`READY`) et le canari sont vérifiés.
- **Code applicatif, schéma SQL ou configuration modifiés (`PRE-INPUT-DRIFT` / `DB-INPUT-DRIFT`)** : le démarrage est **refusé** (`code 2`) plutôt que d'appliquer des modifications incompatibles sur des volumes existants. Vous devez explicitement réinitialiser (`reset --confirm RESET:<instance>`) ou utiliser un autre `--instance <nom>`.
- **Schéma ou historique Flyway hérité incompatible (`INCOMPATIBLE-LEGACY-FLYWAY-HISTORY` / `INCOMPATIBLE-EXISTING-SCHEMA`)** : si le marqueur `public.pocoma_local_bootstrap(fingerprint)` ne correspond pas à la baseline V1 active (par exemple une ancienne instance antérieure à S3), `pocoma-local` refuse l'accès et vous invite à recréer une instance saine via `reset` ou `destroy` plutôt que de tenter une migration hasardeuse.
- **Absence des secrets locaux attendus (`PRE-SECRETS-MISSING`)** : une instance existante sans ses secrets privés ne peut pas être redémarrée silencieusement ; utilisez `reset` ou `destroy` selon le besoin.

Pour approfondir l'exploitation courante, consultez [Exploitation des instances locales](../operations/local-instances.md).
