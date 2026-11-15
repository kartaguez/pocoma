# Prérequis et préparation du poste

Cette page décrit l'environnement requis pour construire, démarrer et exploiter une instance locale de Pocoma via [`./pocoma-local`](../reference/commands.md).

## Outils et versions requises

Les versions attendues sont définies dans [`scripts/local/toolchain.json`](../../scripts/local/toolchain.json) et contrôlées automatiquement par `pocoma-local` avant toute action sur les conteneurs :

| Composant | Exigence contractuelle | Rôle dans Pocoma |
| :--- | :--- | :--- |
| **Docker** | Docker Engine ou Docker Desktop **démarré** | Exécution des 9 conteneurs locaux (PostgreSQL, Keycloak, Prometheus, Grafana, 5 JVM) |
| **Python** | **≥ 3.11** | Exécution de `./pocoma-local` et des scripts de bootstrap |
| **Java (JDK)** | Version majeure **21** | Compilation Maven (via `./mvnw` dans `app/`) et correspondance avec `eclipse-temurin:21.0.8_9-jre` |
| **Node.js & npm** | Node **24** (majeure `24`), `npm` **≥ 7** | Exécution de Bruno CLI (`4.3.0`) pour les parcours `bruno` / `gui` |
| **Bruno CLI** | Version **`4.3.0`** (`scripts/bruno-v3`) | Exécution automatisée des parcours `J01`, `J02` et `J03` |

> [!NOTE]
> La construction des artefacts Java utilise le wrapper Maven du dépôt (`app/mvnw`). Aucune installation globale de Maven n'est requise, à condition que le JDK 21 soit actif.

## Docker et ressources système

Une instance locale démarre **9 conteneurs** avec les limites suivantes configurées par l'orchestrateur :

- **Infrastructure (4 conteneurs)** :
  - `postgres` (`postgres:17.6-alpine`) : limite mémoire `1024m`, `1.5` CPU
  - `keycloak` (`quay.io/keycloak/keycloak:26.7.0`) : limite mémoire `896m`, `1.5` CPU (heap `-Xms128m -Xmx512m`)
  - `grafana` (`grafana/grafana:12.3.2`) : limite mémoire `384m` (`tmpfs` `128m`), `0.5` CPU
  - `prometheus` (`prom/prometheus:v3.5.0`) : limite mémoire `256m`, `0.5` CPU
- **Runtimes JVM (5 conteneurs `eclipse-temurin:21.0.8_9-jre`)** :
  - `http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker` : chacun `640m` mémoire, `1` CPU (heap `-Xms64m -Xmx384m -XX:+UseSerialGC`).

La somme des limites conteneurs est d'environ **5 760 MiB**. Prévoyez au moins **8 Go de RAM** alloués à Docker Desktop (et un espace disque suffisant pour les images, builds Maven et volumes PostgreSQL) afin d'éviter les terminaisons OOM lors du démarrage simultané ou du build initial.

## Environnement shell et restrictions Maven / Java

Pour garantir un build reproductible, `./pocoma-local` **refuse de démarrer** (`code de sortie 2`, erreur `PRE-*`) si l'environnement injecte des options globales non maîtrisées :

- **Variables d'environnement interdites** dans le shell actif :
  - `MAVEN_ARGS`
  - `MAVEN_OPTS`
  - `JAVA_TOOL_OPTIONS`
  - `JDK_JAVA_OPTIONS`
  - `_JAVA_OPTIONS`
- **Fichiers interdits dans `app/.mvn/`** :
  - `app/.mvn/maven.config`
  - `app/.mvn/jvm.config`
  - `app/.mvn/extensions.xml`

Si l'une de ces variables est définie dans votre shell (par exemple via `~/.zshrc`), annulez-la dans le terminal courant avant de lancer `./pocoma-local` :

```bash
unset MAVEN_ARGS MAVEN_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
```

## Checklist avant installation

1. Vérifier que Docker répond (`docker info` sans erreur).
2. Vérifier Python (`python3 --version` ≥ `3.11`), Java (`java -version` = `21`) et Node (`node -v` = `v24.x`, `npm -v` ≥ `7`).
3. Vérifier qu'aucune des variables Maven/JVM listées ci-dessus n'est exportée.
4. S'assurer qu'il n'existe pas déjà d'instance héritée incompatible sous le nom choisi (sinon utiliser un nom d'instance dédié avec `--instance <nom>`).

Étape suivante : [Démarrage rapide (Quickstart)](quickstart.md).
