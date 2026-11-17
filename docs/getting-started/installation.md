# Installation et prérequis

Ce guide explique comment préparer votre poste, installer et démarrer une instance locale de Pocoma avec [`./pocoma-local`](../reference/cli.md), vérifier son état `READY`, puis l'arrêter ou la supprimer proprement.

## 1. Prérequis système

Les versions requises sont définies dans [`scripts/local/toolchain.json`](../../scripts/local/toolchain.json) et contrôlées automatiquement par `./pocoma-local` avant toute action sur les conteneurs :

| Composant | Exigence | Rôle |
| :--- | :--- | :--- |
| **Docker** | Docker Engine ou Docker Desktop **démarré** | Exécution des 9 conteneurs locaux (PostgreSQL, Keycloak, Prometheus, Grafana, 5 JVM) |
| **Python** | **≥ 3.11** | Exécution de `./pocoma-local` et des scripts d'initialisation |
| **Java (JDK)** | Version majeure **21** | Compilation via le wrapper Maven (`app/mvnw`) |
| **Node.js & npm** | Node **24** (majeure `24`), `npm` **≥ 7** | Exécution de Bruno CLI (`4.3.0`) |
| **Bruno CLI** | Version **`4.3.0`** (installé dans `scripts/bruno-v3`) | Exécution des collections et parcours automatisés |

> [!NOTE]
> Aucune installation globale de Maven n'est nécessaire : `./pocoma-local` utilise le wrapper `app/mvnw` du dépôt avec votre JDK 21.

### Ressources Docker recommandées

Une instance locale démarre **9 conteneurs** (4 services d'infrastructure et 5 runtimes JVM `eclipse-temurin:21.0.8_9-jre`), pour une somme de limites mémoire d'environ **5 760 MiB**. Allouez au moins **8 Go de RAM** à Docker Desktop afin d'éviter toute terminaison OOM lors du build ou du démarrage simultané.

### Environnement shell et restrictions Maven / Java

Pour garantir un build reproductible, `./pocoma-local` **refuse de démarrer** (`code 2`, erreur `PRE-*`) si des options globales non maîtrisées sont présentes :

- **Variables d'environnement interdites** : `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`.
- **Fichiers interdits dans `app/.mvn/`** : `maven.config`, `jvm.config`, `extensions.xml`.

Si besoin, nettoyez votre terminal courant avant de lancer `./pocoma-local` :

```bash
unset MAVEN_ARGS MAVEN_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
```

### Vérification rapide du poste

```bash
docker info >/dev/null && python3 --version && java -version && node -v && npm -v
./pocoma-local --help
```

## 2. Installation et premier démarrage

Depuis la racine du dépôt, créez et démarrez l'instance par défaut (`demo`) ou une instance nommée (`[a-z][a-z0-9-]{0,30}`) :

```bash
./pocoma-local start --instance demo
```

Lors de la création d'une instance neuve, `./pocoma-local start` exécute de façon déterministe :

1. Le contrôle des prérequis et la génération de secrets locaux isolés sous `.pocoma-local/instances/demo/`.
2. La compilation des artefacts Java (`app/mvnw`) et la préparation des images Docker si nécessaire.
3. Le démarrage de **PostgreSQL 17.6** et l'initialisation des bases `pocoma_write` (schéma `v3_write`) et `pocoma_read` (schéma `v3_read`) à partir des baselines SQL V1 actives, sans aucune migration manuelle à rejouer.
4. L'initialisation des **36 partitions** de routage et du marqueur de bootstrap `public.pocoma_local_bootstrap`.
5. Le démarrage de **Keycloak 26.7.0**, des **5 runtimes JVM**, de **Prometheus** et de **Grafana**.
6. La vérification de disponibilité (`READY`) et l'exécution du canari fonctionnel de bout en bout.

## 3. Vérifier l'état `READY` et accéder aux services

Consultez l'état consolidé de l'instance et les URL locales exposées :

```bash
./pocoma-local status --instance demo
```

| Code retour | État | Signification |
| :---: | :--- | :--- |
| `0` | `READY` | Les 9 conteneurs sont sains, les 5 runtimes sont opérationnels et le canari a réussi. |
| `4` | `DEGRADED` | Les services métier et le canari fonctionnent, mais Prometheus est indisponible. |
| `1` | `NOT_READY` / `STOPPED` / `STALE` | Instance arrêtée, en échec de santé, ou preuve canari expirée (`> 300 s`). |
| `2` / `3` | `PRE-*` / verrou | Prérequis manquant, dérive détectée ou opération concurrente verrouillée. |

La sortie de `status` (et de `start`) affiche les points d'accès locaux de l'instance :

- **API HTTP WRITE (`WRITE_URL`)** : admission des commandes `/api/v3` (`http-write`, port interne `8080`).
- **API HTTP READ (`READ_URL`)** : requêtes de lecture `/api/v3` (`http-read`, port interne `8082`).
- **Keycloak (`ISSUER`)** : console admin `admin` / `pocoma` ; comptes applicatifs locaux `third`, `guest`, `bruno` (mot de passe `pocoma`).
- **Grafana** : connexion `admin` / `pocoma`, avec deux dashboards provisionnés en lecture seule (*Pocoma runtime*, *Pocoma V3 - Architecture & Observability Overview*).
- **PostgreSQL** : volontairement isolé sur le réseau Docker interne, sans port hôte publié.

## 4. Arrêter, réinitialiser et supprimer une instance

- **Arrêter en conservant les données** (bases PostgreSQL, état Keycloak, secrets et volumes intacts) :

  ```bash
  ./pocoma-local stop --instance demo
  ```

- **Redémarrer** une instance existante :

  ```bash
  ./pocoma-local start --instance demo
  ```

- **Réinitialiser les données** (repartir sur des bases WRITE/READ neuves) :

  ```bash
  ./pocoma-local reset --instance demo --dry-run
  ./pocoma-local reset --instance demo --confirm RESET:demo
  ```

- **Supprimer complètement l'instance** (conteneurs, volumes, état et secrets) :

  ```bash
  ./pocoma-local destroy --instance demo --dry-run
  ./pocoma-local destroy --instance demo --confirm DESTROY:demo
  ```

## 5. Premières pistes de dépannage

- **`PRE-*` au lancement** : vérifiez que Docker Desktop est démarré et qu'aucune variable Maven/JVM interdite n'est exportée.
- **`PRE-INPUT-DRIFT` ou `DB-INPUT-DRIFT` sur une instance existante** : le code ou les schémas SQL ont changé depuis la création de l'instance ; utilisez un autre `--instance <nom>` ou réinitialisez avec `reset`.
- **`qualificationState=STALE` (`code 1`)** : la preuve canari a dépassé `300 s` alors que les services sont sains (`operationalState=OPERATIONAL`) ; relancez `./pocoma-local start --instance demo` pour rafraîchir le canari.
- Pour le catalogue complet des erreurs et procédures : [Diagnostic et dépannage](../operations/troubleshooting.md) et [Reprise après panne](../operations/recovery.md).

Étape suivante : [Premiers pas](first-steps.md).
