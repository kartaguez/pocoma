# Démarrer les runtimes V3

Java 21, Docker et un registre Maven accessible sont nécessaires. Le déploiement local à cinq
processus utilise quatre JARs. Les deux workers Materialize choisissent des participants
complémentaires.

```bash
docker compose -f docker-compose.v3-five-runtimes.yml up -d --wait postgres keycloak
python3 -B scripts/operations/setup-v3-database.py --postgres-container pocoma-v3-postgres
docker compose -f docker-compose.v3-five-runtimes.yml up -d --build --wait
```

| Processus | Module Maven | Profil |
|---|---|---|
| HTTP admission | `runtime-web-write` | `v3-http-admission` |
| HTTP consultation | `runtime-web-read` | `v3-http-read` |
| Command et IdentityRequest | `runtime-worker-process` | `v3-consumption-worker-dev` |
| Résultats | `runtime-worker-materialize` | `v3-read-worker` |
| Projections métier | `runtime-worker-materialize` | `v3-read-worker` |

Le provisionnement s'exécute avec l'administrateur PostgreSQL de la pile locale.
Les migrations restent dans le dépôt. Les applications ouvrent uniquement les pools sélectionnés
par leurs Composes. Materialize utilise `pocoma_write_source_read` pour les lectures WRITE et
`pocoma_read` pour la finalisation READ. Les deux pools HTTP READ emploient respectivement
`pocoma_read_http` et `pocoma_read_status`. Après une reconstruction READ, les grants de
consultation sont réappliqués par le script d'exploitation.

Pour produire les cinq artefacts, Monolith compris, depuis `app/` :

```bash
./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize,runtime-monolith -am package
```

Chaque module runtime livre un JAR `pocoma-<module>-0.0.1-SNAPSHOT-exec.jar` dans son
répertoire `target/`. Monolith prend une sélection explicite des profils V3, avec les paramètres
`pocoma.v3.process.*` et `pocoma.v3.materialize.*` indépendants lors du co-hébergement.
Le fichier [application-v3.properties](../../docker/monolith/application-v3.properties) est un exemple
pour le réseau PostgreSQL de la pile locale. Les fichiers `docker/monolith/configurations` sont
des snapshots de campagne L6.

Pour le banc DEV qui injecte des requêtes contrôlées, compiler explicitement le classpath
de qualification depuis `app/` :

```bash
./mvnw -pl architecture-tests -am install -DskipTests
./mvnw -pl architecture-tests dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=/tmp/pocoma-l7-qualification-classpath
```

Passer ce fichier à `scripts/v3/worker-dev.py` via `--qualification-classpath`.
La CLI utilise un fichier DEV explicite avec les paramètres de provisionnement des deux autorités.
Ses classes sont dans les sources de test du module de qualification. Le JAR Process sert au
contrôle d'identité de la commande `stop`.

Consulter la [politique de vérification](../testing/Reactor_Verification_Policy.md) et
le [rapport L7](../v3/L7_Modular_Refactoring_Implementation_and_Qualification_Report.md).
