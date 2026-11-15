# Runtimes JVM et parallélisme

Les cinq services Java de Pocoma tournent sur `eclipse-temurin:21.0.8_9-jre` dans des conteneurs bornés à `640m` de mémoire et `1` CPU, avec les options JVM `-Xms64m -Xmx384m -XX:+UseSerialGC`.

## Runtimes HTTP (admission WRITE et lecture READ)

### `http-write` (`runtime-web-write`)

- **Profil Spring** : `v3-http-admission` (`docker/v3-five-runtimes/http-write.properties`)
- **Ports internes** : HTTP `8080`, Actuator `18080`
- **Concurrence et pool** : Tomcat `maxThreads=4`, `admission.maximum=4`, pool JDBC `minimum-idle=1`
- **Responsabilité** : porter la classe de configuration `AdmissionHttpConfiguration`, valider l'authentification OIDC, vérifier l'idempotence et admettre les commandes dans `pocoma_write`.

### `http-read` (`runtime-web-read`)

- **Profil Spring** : `v3-http-read` (`docker/v3-five-runtimes/http-read.properties`)
- **Ports internes** : HTTP `8082`, Actuator `18082`
- **Validation pré-sémaphore et concurrence `Q=8`** :
  - `ReadHttpConfiguration`, `ReadFacadeComposition` et `ReadTransport` valident la requête HTTP et les jetons **avant** d'acquérir un permis de concurrence.
  - Les limites de concurrence READ sont alignées sur **`Q=8`** : `http-permits=8`, `sql-permits=8`, Tomcat `maxThreads=4`, pool JDBC principal `maximum-pool-size=8` / `minimum-idle=8` (plus un pool maintenance de taille `1`).
- **Optimisation SQL** : la requête `openComponent` (historique des cagnottes dans `PotHistoryRows`) a été optimisée lors de la phase de consolidation pour limiter le coût de lecture.

## Workers de consommation et de matérialisation

| Worker | Module Maven | Profil Spring | Actuator | Concurrence (`worker-limit`) | Périmètre |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `command-worker` | `runtime-worker-process` | `v3-consumption-worker-dev` | `18083` | `2` (`Command=2`, `IdentityRequest=2`) | **JDBC pur (sans JPA/Hibernate)** ; exécution métier sur `pocoma_write` |
| `results-worker` | `runtime-worker-materialize` | `v3-read-worker` | `18084` | `2` (`MaterializeCommandResult=2`, `MaterializeIdentityRequestResult=2`) | Matérialisation des résultats vers `pocoma_read` |
| `business-read-worker` | `runtime-worker-materialize` | `v3-read-worker` | `18085` | `3` (5 participants à `2` chacun) | Matérialisation des vues métier dans `pocoma_read` |

La suppression complète de JPA/Hibernate sur `command-worker` (`ProcessComposeConfiguration`, `ProcessSettings`) réduit l'empreinte mémoire de ce runtime et aligne son accès aux données sur JDBC pur, à l'image des mécanismes de coordination de baux.

## Partitionnement (36 partitions) et politique de récupération

La table de routage L2 initialisée par [`scripts/v3/bootstrap-l2-routing.sql`](../../scripts/v3/bootstrap-l2-routing.sql) distribue le travail sur **36 partitions** (`0` à `3` par participant) :

- **`command-worker` (8 partitions WRITE)** : `Command` (0–3), `IdentityRequest` (0–3).
- **`results-worker` (8 partitions READ)** : `MaterializeCommandResult` (0–3), `MaterializeIdentityRequestResult` (0–3).
- **`business-read-worker` (20 partitions READ)** : `MaterializePotVersion` (0–3), `MaterializePotBalance` (0–3), `MaterializePotVisibility` (0–3), `MaterializeCurrentBinding` (0–3), `MaterializeInvitation` (0–3).

La sélection et les règles de récupération des sources ont été extraites de `ConsumptionEngine` vers un composant dédié **`RecoverySourcePolicy`** afin de découpler la stratégie de reprise de la boucle d'exécution des partitions.

## Baux SQL (30 s), renouvellement groupé (`renewBatch`) et reprise

Les workers s'appuient sur des baux stockés en base (`pocoma_v3_lease_local`, `pocoma_v3_worker_state`) avec les paramètres par défaut suivants :

| Paramètre | Valeur nominale | Signification |
| :--- | :---: | :--- |
| `lease-duration-ms` | `30000` (30 s) | Durée de validité (TTL) d'un bail de partition |
| `renewal-interval-ms` | `10000` (10 s) | Périodicité du renouvellement groupé |
| `claim-duration-ms` | `60000` (60 s) | Durée de réclamation d'un slot |
| `batch-size` | `32` | Taille maximale d'un lot traité |
| `retry-max-failures` | `3` | Nombre maximal de tentatives avant report |
| `retry-delay-ms` | `30000` (30 s) | Délai de réessai en cas d'échec |
| `drain-timeout` | `90000` (90 s) | Délai accordé au drainage lors d'un arrêt gracieux |
| Polling (`min-poll` / `max-poll` / `error-poll`) | `2` / `20` / `50` | Intervalles de scrutation en secondes configurés dans les `.properties` locaux |

### Fonctionnement de `renewBatch`

Le renouvellement groupé (`renewBatch` sur `PartitionOwnershipStore`, `JdbcPartitionOwnershipStore`, `ConsumptionProtocol`, `AbstractConsumptionProtocol`, `ConsumptionEngine` et `RenewalService`) maintient un **fencing strict** :

- Seuls les baux encore détenus par le worker appelant voient leur échéance prolongée.
- Un bail expiré puis repris par un autre worker n'est jamais écrasé ni modifié.
- Les baux perdus sont retournés et identifiés **individuellement**, afin que le worker retire immédiatement ces partitions de son ensemble actif.

> [!IMPORTANT]
> Le TTL de bail de **30 s** (`lease-duration-ms=30000`) ne signifie pas qu'une reprise complète de bout en bout s'achève toujours en 30 s. La reprise effective combine l'expiration du bail (`30 s`), les intervalles de scrutation/acquisition des workers et, le cas échéant, `claim-duration-ms=60000` lorsqu'un slot a été réclamé. Lors d'une reprise après arrêt non gracieux, `pocoma-local` applique un budget d'observation borné à **270 s**. Voir [Exploitation des instances locales](../operations/local-instances.md) et [Référence de configuration](../reference/configuration.md).

## Dimensionnement des pools JDBC et mémoire

Les pools HikariCP des trois workers sont explicitement calibrés dans `docker/v3-five-runtimes/*.properties` :

- **`command-worker`** : `write-business=4`, `write-control=8`.
- **`results-worker`** : `write-source-read=4`, `read-business=4`, `read-control=4`.
- **`business-read-worker`** : `write-source-read=5`, `read-business=5`, et un pool dédié `read-control`.

Ce séquençage évite qu'un pic de traitement métier ne bloque le renouvellement des baux de contrôle (`*-control`) ou la validation HTTP amont.
