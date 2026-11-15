# Référence de configuration des runtimes

Les propriétés par défaut des cinq services Java pour la distribution locale sont définies sous [`docker/v3-five-runtimes/`](../../docker/v3-five-runtimes/http-write.properties) et orchestrées par [`scripts/local/runtime.py`](../../scripts/local/runtime.py).

## Configuration des 5 runtimes JVM

Tous les runtimes JVM utilisent l'image **`eclipse-temurin:21.0.8_9-jre`**, la ligne JVM **`-Xms64m -Xmx384m -XX:+UseSerialGC`**, et des limites Docker de **`640m`** / **`1` CPU** par conteneur :

| Runtime | Module & Profil Spring | Ports internes (HTTP / Actuator) | Paramètres clés de concurrence et pools JDBC | Fichier source |
| :--- | :--- | :---: | :--- | :--- |
| `http-write` | `runtime-web-write`<br>`v3-http-admission` | `8080` / `18080` | Tomcat `maxThreads=4`, `pocoma.v3.http.admission.capacity=4` (sémaphore d'admission + pool Hikari `v3-http-admission` `maximumPoolSize=4` / `minimumIdle=4`) | [`http-write.properties`](../../docker/v3-five-runtimes/http-write.properties) |
| `http-read` | `runtime-web-read`<br>`v3-http-read` | `8082` / `18082` | Tomcat `maxThreads=4`, `http-permits=8` (`Q=8`), `sql-permits=8`, pool principal `maximum-pool-size=8` / `minimum-idle=8`, pool maintenance `1` | [`http-read.properties`](../../docker/v3-five-runtimes/http-read.properties) |
| `command-worker` | `runtime-worker-process`<br>`v3-consumption-worker-dev` | — / `18083` | **JDBC pur (sans JPA/Hibernate)** ; `worker-limit=2`, `Command=2`, `IdentityRequest=2`, `write-business=4`, `write-control=8` | [`command-worker.properties`](../../docker/v3-five-runtimes/command-worker.properties) |
| `results-worker` | `runtime-worker-materialize`<br>`v3-read-worker` | — / `18084` | `worker-limit=2`, `MaterializeCommandResult=2`, `MaterializeIdentityRequestResult=2`, `write-source-read=4`, `read-business=4`, `read-control=4` | [`results-worker.properties`](../../docker/v3-five-runtimes/results-worker.properties) |
| `business-read-worker` | `runtime-worker-materialize`<br>`v3-read-worker` | — / `18085` | `worker-limit=3`, `MaterializePotVersion=2`, `MaterializePotBalance=2`, `MaterializePotVisibility=2`, `MaterializeCurrentBinding=2`, `MaterializeInvitation=2`, `write-source-read=5`, `read-business=5`, pool `read-control` dédié | [`business-read-worker.properties`](../../docker/v3-five-runtimes/business-read-worker.properties) |

## Baux, concurrence et délais effectifs de reprise

Paramètres de coordination définis entre `ProcessSettings`, `MaterializeSettings` et les fichiers `.properties` :

| Paramètre | Valeur nominale | Unité / Rôle |
| :--- | :---: | :--- |
| `lease-duration-ms` | `30000` | Durée de validité (TTL) d'un bail SQL de partition (**30 s**) |
| `renewal-interval-ms` | `10000` | Périodicité du renouvellement groupé `renewBatch` (**10 s**) |
| `claim-duration-ms` | `60000` | Durée de réclamation d'un slot (**60 s**) |
| `batch-size` | `32` | Taille de lot maximale |
| `retry-max-failures` | `3` | Nombre maximal d'échecs avant report |
| `retry-delay-ms` | `30000` | Temporisation de réessai (**30 s**) |
| `drain-timeout` | `90000` | Délai de drainage à l'arrêt gracieux (**90 s**) |
| Polling workers (`min-poll` / `max-poll` / `error-poll`) | `2` / `20` / `50` | Intervalles de scrutation configurés dans les `.properties` locaux (**en millisecondes**, lus via `Duration.ofMillis(...)`) |

> [!IMPORTANT]
> **`30 s` = TTL du bail SQL, pas SLA de reprise de bout en bout** : la réduction des baux à `30000 ms` réduit l'attente maximale sur le bail expiré, mais le délai effectif de reprise après arrêt non gracieux dépend aussi des cycles de scrutation/acquisition des workers et de `claim-duration-ms=60000` si un slot était réclamé. Côté exploitation locale, `./pocoma-local` conserve un budget d'observation de récupération borné à **`270 s`**.

## Variables d'environnement, ports internes et conteneurs d'infrastructure

### Conteneurs d'infrastructure

| Service | Image | Limites Docker | Paramètres principaux |
| :--- | :--- | :--- | :--- |
| `postgres` | `postgres:17.6-alpine` | `1024m` / `1.5` CPU | Bases `pocoma_write` (`v3_write`) et `pocoma_read` (`v3_read`) ; **aucun port hôte publié** |
| `keycloak` | `quay.io/keycloak/keycloak:26.7.0` | `896m` / `1.5` CPU | JVM `-Xms128m -Xmx512m`, royaume `docker/keycloak/pocoma-realm.json` |
| `prometheus` | `prom/prometheus:v3.5.0` | `256m` / `0.5` CPU | `scrape_interval: 2s` sur `:18080`–`:18085` |
| `grafana` | `grafana/grafana:12.3.2` | `384m` (`128m` `tmpfs`) / `0.5` CPU | UID `472`, provisioning & dashboards en lecture seule |

### Restrictions d'environnement shell et Maven

Conformément à [`scripts/local/prerequisites.py`](../../scripts/local/prerequisites.py) :

- **Interdiction dans l'environnement** : `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`.
- **Interdiction dans `app/.mvn/`** : `maven.config`, `jvm.config`, `extensions.xml`.
- **Versions contrôlées (`scripts/local/toolchain.json`)** : Python ≥ `3.11`, Java `21`, Node `24`, npm ≥ `7`, Bruno CLI `4.3.0`.

## Nomenclature historique (identifiants techniques conservés)

Par choix de stabilité durant S4 (afin de ne pas rompre les contrats externes, les schémas SQL ni l'empreinte d'instance existante `demo`), les identifiants techniques suivants conservent délibérément le marqueur historique `v3` :

| Catégorie | Identifiants conservés | Raison du maintien |
| :--- | :--- | :--- |
| **Contrats HTTP** | Routes `/api/v3/...` | Contrat API public exposé aux clients et aux collections Bruno |
| **Identifiants SQL persistants** | Schémas `v3_write` / `v3_read`, tables `pocoma_v3_lease_local`, `pocoma_v3_worker_state` | Baselines SQL V1 actives et compatibilité runtime/observabilité |
| **Profils Spring & propriétés Java** | `v3-http-admission`, `v3-http-read`, `v3-consumption-worker-dev`, `v3-read-worker`, préfixes de configuration applicatifs | Chargés directement par les modules `runtime-*` et tests ciblés |
| **Chemins outillés** | `docker/v3-five-runtimes/`, `scripts/v3/`, `scripts/operations/setup-v3-database.py`, `scripts/bruno-v3/` | Référencés directement dans `runtime.py`, `prerequisites.py` et les tests |

Tout renommage de ces identifiants techniques nécessitera une étape dédiée avec adaptation coordonnée des scripts, des tests et des empreintes d'instance.
