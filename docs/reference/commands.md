# Référence des commandes (`./pocoma-local`)

L'interface en ligne de commande `./pocoma-local` (implémentée par [`scripts/local/entrypoint.py`](../../scripts/local/entrypoint.py) et [`scripts/local/runtime.py`](../../scripts/local/runtime.py)) est le point d'entrée unique pour créer, vérifier, opérer et nettoyer une instance locale.

## Syntaxe générale et options

```text
usage: pocoma-local [-h] [--instance INSTANCE] [--confirm CONFIRM] [--dry-run]
                    [--interactive] [--run RUN]
                    {start,upgrade-grafana,status,stop,reset,destroy,bruno,login,gui}
```

| Option | Valeur par défaut / Format | Portée et comportement |
| :--- | :--- | :--- |
| `-h`, `--help` | — | Affiche l'aide CLI et quitte |
| `--instance INSTANCE` | `demo` (`[a-z][a-z0-9-]{0,30}`) | Cible l'instance locale nommée |
| `--confirm CONFIRM` | `RESET:<instance>` ou `DESTROY:<instance>` | Obligatoire pour exécuter `reset` ou `destroy` sans `--dry-run` |
| `--dry-run` | booléen | Simule `reset` ou `destroy` sans modifier ni supprimer de données ; explicitement rejetée (`code 2`) sur toutes les autres sous-commandes |
| `--interactive` | booléen | Option d'exécution interactive pour `bruno` |
| `--run RUN` | `[0-9]{10}-[0-9a-f]{8}` | Réservé à `status` : lit le résultat tardif d'une exécution expirée |

## Détail des sous-commandes

| Sous-commande | Effet réel | Exemples |
| :--- | :--- | :--- |
| `start` | Vérifie les prérequis et verrous, construit si besoin, initialise PostgreSQL (`pocoma_write`/`pocoma_read`), Keycloak, les 5 JVM, Prometheus et Grafana, puis exécute le contrôle `READY` et le canari. Sur une instance existante, vérifie que PostgreSQL est sain, redémarre de façon bornée les seuls runtimes restés fail-closed (`draining` ou capacité d'admission `http-write` entamée) et requalifie le canari ; en cas de dérive d'entrée ou de schéma incompatible, refuse proprement (`code 2`). | `./pocoma-local start --instance demo` |
| `status` | Inspecte la disponibilité des conteneurs, l'état des runtimes/workers (y compris `draining`), les URL exposées et le résultat du canari (ou d'un `--run` donné). Distingue explicitement `operationalState` / `operational` (services, workers, dépendances) et `qualificationState` / `qualification` (`QUALIFIED`, `STALE`, `GENERATION_MISMATCH`, `UNQUALIFIED`, `MAX_AGE=300 s`). | `./pocoma-local status --instance demo`<br>`./pocoma-local status --instance demo --run <run-id>` |
| `stop` | Arrête proprement les conteneurs de l'instance tout en conservant les volumes PostgreSQL, Keycloak, Prometheus et secrets locaux. | `./pocoma-local stop --instance demo` |
| `upgrade-grafana` | Met à jour la configuration et les dashboards Grafana de l'instance sans toucher aux données métier ni réinitialiser les bases. | `./pocoma-local upgrade-grafana --instance demo` |
| `reset` | Réinitialise les données et bases PostgreSQL de l'instance à partir des baselines V1 actives. Exige `--confirm RESET:<instance>` (ou `--dry-run`). | `./pocoma-local reset --instance demo --dry-run`<br>`./pocoma-local reset --instance demo --confirm RESET:demo` |
| `destroy` | Supprime complètement les conteneurs, volumes et répertoire local de l'instance. Exige `--confirm DESTROY:<instance>` (ou `--dry-run`). | `./pocoma-local destroy --instance demo --dry-run`<br>`./pocoma-local destroy --instance demo --confirm DESTROY:demo` |
| `login` | Exécute le parcours d'authentification OIDC locale (Code + PKCE S256 via Keycloak) pour l'instance. | `./pocoma-local login --instance demo` |
| `gui` | Ouvre l'interface graphique Bruno préconfigurée pour l'instance avec authentification OIDC sans copie manuelle de JWT. | `./pocoma-local gui --instance demo` |
| `bruno` | Lance la collection automatisée Bruno (`J01`, `J02`, `J03` — 118 requêtes, 236 tests nommés) via Bruno CLI `4.3.0`. Supporte `--interactive`. | `./pocoma-local bruno --instance demo` |

## Codes de sortie CLI

| Code | Signification |
| :---: | :--- |
| `0` | Succès (`READY`, opération terminée sans anomalie) |
| `1` | État `NOT_READY`, `STOPPED`, `STALE` ou échec d'exécution |
| `2` | Erreur d'arguments, refus de confirmation ou échec `PRE-*` / `DB-*` / `INCOMPATIBLE-*` |
| `3` | Conflit de verrou actif (`PRE-INSTANCE-LOCKED`, `PRE-LIFECYCLE-LOCKED`, `PRE-BUILD-LOCKED`) |
| `4` | État `DEGRADED` (cœur applicatif et canari OK, observabilité Prometheus dégradée) |
| `130` | Interruption par signal utilisateur (`SIGINT`) |

## Commandes associées (Bruno et vérification Maven)

Pour les développeurs modifiant le code Java sous `app/`, la vérification ne passe pas par `./mvnw test` sur l'ensemble du réacteur par défaut, mais par des **slices Maven ciblées** (`WEB`, `COMMAND`, `PROJECTION`, `FOUNDATION`, `Monolith`, `architecture-tests`).

Consultez impérativement la politique normative avant toute compilation ou test Maven :

- [docs/testing/Reactor_Verification_Policy.md](../testing/Reactor_Verification_Policy.md)
- [docs/v3/L7_Local_Playground_Bruno.md](../v3/L7_Local_Playground_Bruno.md)
