# Architecture — Correction des six erreurs de bootstrap PostgreSQL/Flyway

Date : 2026-10-20, Europe/Paris. Mission limitée au bootstrap des tests. **4B non commencé ; aucune clôture de 4A prononcée.**

## Autorités et état initial

Références lues avant modification : [AGENTS](../../AGENTS.md), [politique normative](../testing/Reactor_Verification_Policy.md), [plan Étape 4](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md), [diagnostic différentiel 4A](Event_Publication_Contracts_Step_4A_Differential_Diagnosis_2026-10-20.md), [diagnostic causal Consumption](Consumption_Ambient_Transaction_Five_Errors_Causal_Diagnosis_2026-10-20.md), [correction des cinq courses](Consumption_Ambient_Transaction_Five_Tests_Fix_2026-10-20.md), configurations de composition PRIMARY/READ et ressources runtime concernées. Les traces intégrales historiques et les nouvelles traces/probes sont conservées dans les [preuves JSON](../testing/evidence/Architecture_Postgres_Bootstrap_Six_Errors_Fix_2026-10-20.json).

Branche cible `feat/consumption-transaction-protocol`, worktree existant `/private/tmp/pocoma-consumption-transaction-protocol`. SHA initial attendu et réel : `74eda7030769d6a5acb270497eda2f055c178160`. Worktree propre ; divergence initiale **0/0 confirmée par fetch réel**. Aucun déplacement de branche, reset, stash, rebase, merge ou push forcé.

Inventaire des six worktrees effectué avant écriture. Le checkout principal reste `feat/runtime-monolith`, SHA `90926a5ec37d1ca841e3de017bec0d1f705d8839`, avec ses travaux Keycloak, README, Bruno, test Expense, études et preuves non suivies. Les autres worktrees restent aux SHA `8a79f06d` (baseline détachée), `2eef3ab9` (binding), `116f4e4a` (expense) et `2eef3ab9` (debt-wa6 détaché). Statuts et empreintes des fichiers modifiés/non suivis relevés avant et après ; aucun de ces travaux n'entre dans la livraison.

## Verification scope déclaré avant implémentation

- Impact production attendu et observé : **NONE** ; seulement trois fixtures Java dans `architecture-tests` et des documents/preuves.
- Slices fonctionnelles canoniques : **NONE** ; périmètre primaire de preuve **architecture-tests**, secondaires **NONE**. Aucun comportement WEB/COMMAND/EVENT/PROJECTION/BINDING/LKV modifié.
- Preuve locale : six méthodes isolées, trois classes complètes, voisins PostgreSQL pertinents et cinq courses Consumption précédemment corrigées.
- Gate architecture global : **REQUIRED par la mission**, commande normative `./mvnw -pl architecture-tests -am test`.
- Full reactor : **NOT REQUIRED, non exécuté**. Pas de clôture de Wave, intégration main, modification de dépendances ou infrastructure runtime.
- DB : **CURRENT**, PostgreSQL Testcontainers jetables uniquement. La politique indique `Trusted baseline: NONE` ; bootstrap existant PRIMARY V1–V29 et READ V1–V14 conservé, aucune campagne historique supplémentaire ni base partagée touchée.
- Interdictions : runtime métier, owner Consumption, TA/TE/TR/TH, fencing/CAS/retries, assertions fonctionnelles, protocole Event, contrats 4A et migrations de production.
- Escalade : arrêt pour arbitrage si une correction runtime, migration de production ou opération destructive partagée devient nécessaire. Aucun cas rencontré ; **aucun franchissement inattendu de slice**. Les défauts latents ci-dessous restent dans le bootstrap des mêmes fixtures.

## Cause commune démontrée des six erreurs initiales

Les six échecs initiaux partagent la chaîne exacte :

```text
BeanCreationException(entityManagerFactory)
  -> BeanCreationException(flywayInitializer)
  -> FlywaySqlUnableToConnectToDbException
  -> org.postgresql.util.PSQLException (SQLSTATE 08001, code 0)
  -> java.net.ConnectException: Connection refused
```

Destination effective `jdbc:postgresql://localhost:5432/pocoma`, connexion Hikari du contexte Spring PRIMARY. Message : `Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.` Le refus survient dans `JdbcUtils.openConnection:70`, pendant `FlywayMigrationInitializer.afterPropertiesSet` / `refresh`, avant l'EntityManagerFactory, le nettoyage, les seeds et les appels métier. Aucun script SQL n'est rejeté : **aucune migration appliquée par ces contextes en échec**, aucun historique Flyway consultable à la destination refusée.

Le regroupement datasource/Flyway n'est pas la preuve causale. Une reproduction des trois classes ajoute temporairement un initializer **en lecture seule** pour relever les property sources après préparation de l'environnement et ouvrir indépendamment une connexion vers le conteneur attendu. Résultat : six erreurs identiques de connexion, conteneurs tous joignables, PostgreSQL **17.11**, base `pocoma`, schéma courant `public`, **zéro table dans public/pocoma_read** à chaque premier bootstrap échoué. Ports conteneurs : **53532** pour les quatre méthodes CommandCompletion, **53524** pour Durable et **53528** pour Historical. L'URL effective reste 5432 dans les six cas.

Le classpath Surefire place `runtime-monolith/target/classes/application.properties` avant les ressources `application.properties` des autres runtimes. La source effectivement observée est `Config resource 'class path resource [application.properties]' via location 'optional:classpath:/'`, contenant `spring.datasource.url=${POCOMA_POSTGRES_URL:jdbc:postgresql://localhost:5432/pocoma}`. La source `defaultProperties` contient pourtant la bonne URL dynamique de chaque conteneur. Même conflit pour `pocoma.event-consumption.enabled=false` de la fixture contre `true` du monolithe ; corriger uniquement l'URL laisserait les paramètres du scénario écrasés.

Les sources locales exactes Spring Boot **4.0.5** confirment le mécanisme : `SpringApplicationBuilder.properties(Map)` appelle `SpringApplication.setDefaultProperties` (ligne 491), puis `ConfigDataEnvironment` déplace ces defaults en dernier après chargement des ressources (`moveToEnd`, ligne 345). `StandardConfigDataLocationResolver` lit `spring.config.name` avant ce chargement. Le nom dédié permet donc aux propriétés explicites des fixtures de définir leur composition sans importer les valeurs d'un déploiement runtime.

| Classe / méthode exacte | Premier bootstrap fautif initial | Datasource effective / attendue | Migrations et état avant correction |
|---|---|---|---|
| `CommandCompletionE2EPostgresTest.admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably` | `commandContext:531`, appelé ligne 153 | Hikari PRIMARY 5432 / conteneur 53532 | PRIMARY V1–V29 attendues ; zéro appliquée ; conteneur vide |
| `CommandCompletionE2EPostgresTest.eventFeedsIndependentLkvAndExactProjectionPipelines` | `cleanCommandContext:537`, appelé ligne 406 | Hikari PRIMARY 5432 / 53532 | Même état ; READ prévu ultérieurement par LKV/Task |
| `CommandCompletionE2EPostgresTest.httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions` | `webContext:598`, appelé ligne 257 | Hikari PRIMARY 5432 / 53532 | PRIMARY puis READ V1–V14 attendues ; aucune appliquée |
| `CommandCompletionE2EPostgresTest.registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration` | `webContext:598`, appelé ligne 325 | Hikari PRIMARY 5432 / 53532 | PRIMARY puis READ attendues ; aucune appliquée |
| `DurableEventToProjectionPostgresTest.durableEventOutputIsTheIndependentProjectionTaskRuntimeInput` | `eventContext:137`, appelé ligne 83 | Hikari PRIMARY 5432 / 53524 | PRIMARY attendue avant seed ; READ dans Task ; aucune appliquée |
| `HistoricalEventRouteEvolutionPostgresTest.evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence` | `eventContext:153`, appelé ligne 67 | Hikari PRIMARY 5432 / 53528 | PRIMARY attendue ; READ exclu ; aucune appliquée |

Les numéros de ligne de cette table sont ceux des traces initiales, conservées intégralement dans les preuves. La sonde temporaire décale certaines lignes Java ; elle n'intercepte aucune exception, n'altère aucune propriété et ne change aucun scénario. Elle est retirée avant les vérifications de livraison.

### Différence avec les classes PostgreSQL qui passent

`CommandResultConsumptionChainPostgresTest` et `CommandResultTakeoverRacePostgresTest` utilisent `@SpringBootTest` / `@DynamicPropertySource`. Leurs propriétés dynamiques prennent priorité sur ConfigData ; le vrai conteneur, Flyway et JPA démarrent. Leurs configurations excluent READ lorsque la chaîne testée n'en a pas besoin. Les tests runtime voisins utilisent aussi un bootstrap test explicite. Les trois classes fautives sont les seules de `architecture-tests` à construire leurs contextes avec `SpringApplicationBuilder` et ces defaults.

Les paramètres attendus des fixtures sont la base `pocoma`, les identifiants jetables `pocoma/pocoma` fournis par le conteneur, le driver `org.postgresql.Driver`, `spring.jpa.hibernate.ddl-auto=validate`, Flyway activé et les emplacements ci-dessous. Aucun override `POCOMA_`, `SPRING_`, `JAVA_TOOL_OPTIONS`, `MAVEN_OPTS`, `DOCKER_` ou `TESTCONTAINERS_` n'est présent dans l'environnement relevé.

Le JdbcTemplate de fixture utilise un `DriverManagerDataSource` distinct, avec l'URL explicite Testcontainers ; le simple fait qu'il existe ne configure pas le HikariDataSource Spring. Le migrateur READ, lorsqu'activé, réutilise la datasource Spring, `pocoma_read`, sa propre table `flyway_schema_history` et `classpath:db/read-store/migration`. PRIMARY utilise `public.flyway_schema_history` et `classpath:db/migration`. Ni les données métier ni un conflit de checksum/migration ne causent le refus TCP initial.

## Correction minimale et deux défauts latents de bootstrap

1. Les quatre points de construction des trois fixtures sélectionnent `spring.config.name=pocoma-architecture-test`. Aucune ressource portant ce nom n'est ajoutée : les propriétés de test déjà présentes décrivent intégralement leur composition. Les fichiers runtime restent inchangés. La sonde après correction confirme l'URL effective égale à l'URL attendue, l'absence de la source monolithe et la valeur `false` attendue pour le polling Event.
2. Après cette correction, trois scénarios CommandCompletion progressent jusqu'aux contextes Read/Web puis échouent avec `UnsatisfiedDependencyException(projectionValidator) -> NoSuchBeanDefinitionException`, qualificateur `webApiObjectMapper`. Leurs beans locaux n'offraient que le nom `objectMapper`. La configuration de production requiert déjà le qualificateur ; les workers importent déjà `PocomaObjectMapperConfiguration`. La fixture importe cette même configuration dans Read/Web et supprime ses deux définitions locales identiques. Le mapper conserve `new ObjectMapper().findAndRegisterModules()`, avec les alias canoniques existants. Aucun nouveau mapper ni comportement JSON n'est inventé.
3. Le feedback en classe passe ensuite **6/6**, mais l'exécution isolée du scénario d'admission révèle `BadSqlGrammarException -> PSQLException: relation "pocoma_read.projection_root" does not exist` lors de l'assertion ligne 198. Il lit READ et y insère son binding avant son premier `readContext()`. En classe complète, `eventFeedsIndependentLkvAndExactProjectionPipelines` a déjà créé READ via LKV/Task. Un `@BeforeEach` ouvre et ferme désormais le `readContext()` existant pour initialiser explicitement le schéma avant chaque méthode. Les assertions et seeds restent à leur place ; aucune dépendance à un ordre de méthodes n'est ajoutée.

La cause des **six erreurs initiales** est donc commune et prouvée ; les deux défauts suivants étaient masqués par ce premier échec. Le troisième apparaît seulement en isolation. Tous sont des défauts de bootstrap/configuration des tests. Aucun n'appelle une modification runtime, un assouplissement de contrat ou une migration de production.

La sonde après isolation/mapper relève `success=true` pour **toutes les versions PRIMARY 1–29**, et **READ 1–14** dans les contextes qui l'activent, via lecture des tables d'historique après refresh ; les premières ouvertures Event sans READ trouvent l'historique READ absent. Une fois READ initialisé, les contextes suivants partagent naturellement ce schéma dans le même conteneur. Les logs des méthodes isolées finales confirment aussi la création READ sur leurs bases neuves avant les scénarios. L'image reste `postgres:17-alpine`, identifiant `sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` ; Docker 29.8.2, Java Temurin 21+35, Maven 3.9.14, Boot 4.0.5 et Surefire 3.5.5.

## Vérifications de livraison et comparaison du gate

Toutes les commandes sont exécutées depuis `/private/tmp/pocoma-consumption-transaction-protocol/app`. Journaux, scripts et copies Surefire : `/private/tmp/pocoma-datasource-fix-20261020/`. Les XML de chaque étape sont copiés avant la suivante et sélectionnés par date de production, pour exclure les rapports résiduels.

```bash
for method in \
  admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably \
  eventFeedsIndependentLkvAndExactProjectionPipelines \
  httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions \
  registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration
do
  ./mvnw -pl architecture-tests -am test \
    "-Dtest=CommandCompletionE2EPostgresTest#$method" \
    -Dsurefire.failIfNoSpecifiedTests=false
done
./mvnw -pl architecture-tests -am test \
  '-Dtest=DurableEventToProjectionPostgresTest#durableEventOutputIsTheIndependentProjectionTaskRuntimeInput' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test \
  '-Dtest=HistoricalEventRouteEvolutionPostgresTest#evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence' \
  -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -pl architecture-tests -am test \
  -Dtest=CommandCompletionE2EPostgresTest,DurableEventToProjectionPostgresTest,HistoricalEventRouteEvolutionPostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -pl architecture-tests -am test \
  -Dtest=CommandResultConsumptionChainPostgresTest,CommandResultTakeoverRacePostgresTest,EventConsumptionRuntimePostgresTest,ProjectionMaterializationConsumptionPostgresTest,CanonicalProjectionTaskRuntimePostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -pl architecture-tests -am test
```

Les sélections ciblées ne filtrent pas le gate final. `surefire.failIfNoSpecifiedTests=false` permet uniquement aux modules amont sans test correspondant de terminer la sélection. Aucun test désactivé, aucune assertion affaiblie, aucun retry/sleep ajouté.

| Étape | Résultat exact |
|---|---|
| Six méthodes isolées | **6 × BUILD SUCCESS**, une méthode par commande, chaque fois 1 test, 0 failure/error/skip |
| Trois classes complètes | **6 tests, 0 failures, 0 errors, 0 skips — BUILD SUCCESS** |
| Cinq classes PostgreSQL voisines | **40 tests, 0 failures, 0 errors, 0 skips — BUILD SUCCESS** ; inclut les 5 courses Consumption PASS |
| Gate architecture global sans filtre | **1257 tests, 0 failures, 0 errors, 1 skip — BUILD SUCCESS**, sortie 0 |
| Module architecture dans ce gate | **138 tests, 0 failures, 0 errors, 0 skips** |

**Verdict : gate architecture global PASS.**

La comparaison par classe/méthode avec le gate à six erreurs conservé par la livraison `74eda703` (exécuté avant son commit sur les mêmes sources corrigées Consumption) porte sur **1257 cas communs** : exactement **six ERROR → PASS**, les six méthodes de la mission ; aucun autre changement de statut, aucun cas ajouté ou supprimé, aucune erreur résiduelle. Les cinq courses Consumption restent PASS dans la sélection voisine et dans le gate global. Le skip préexistant unique est `TerminalSequencingPostgresTest`, conditionné par `step3b.measure=true` ; aucune exclusion n'est ajoutée. Le gate demandé passe avec ce skip préexistant, sans revendiquer la mesure 3B optionnelle. Le journal contient aussi le message Surefire `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).` après les 228 tests PRIMARY JPA (0 failure/error). Il figure déjà, identique, dans les gates précédents ; le dumpstream est conservé. Ce signal de fermeture préexistant reste hors périmètre : il ne provoque ni nouveau statut de test en erreur ni échec Maven dans le gate final.

Exécutions de diagnostic supplémentaires, distinctes des preuves finales : même sélection des trois classes que ci-dessus, trois fois, avec une sonde de lecture temporaire : **6 erreurs** avant correction ; **3 erreurs** après seule isolation ; **6 PASS** après import du mapper. Puis première méthode isolée : **1 erreur** READ avant le `@BeforeEach`. Les traces sont conservées. Un premier lancement sandboxé a aussi terminé en échec, sans servir de preuve causale (son journal a été remplacé avant archivage) ; un lancement de la sonde a échoué à la compilation sur l'ancien type générique `PostgreSQLContainer<?>` (API 2 non générique) ; la sonde a été corrigée avant toute conclusion causale. Ni ces échecs d'outillage ni le feedback intermédiaire ne sont présentés comme PASS de livraison. Les commandes finales sont toutes exécutées après retrait de cette instrumentation et après la dernière correction.

## Diff et livraison

Trois fichiers Java modifiés : [CommandCompletionE2EPostgresTest.java](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandCompletionE2EPostgresTest.java), [DurableEventToProjectionPostgresTest.java](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ept/DurableEventToProjectionPostgresTest.java), [HistoricalEventRouteEvolutionPostgresTest.java](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ept/HistoricalEventRouteEvolutionPostgresTest.java). Seulement imports, configuration et initialisation de fixture. Les six corps de méthodes `@Test` sont identiques à ceux du SHA initial. Aucun fichier `src/main`, POM ou migration modifié ; aucun changement d'owner, transaction, fence, CAS, retry, protocole Event ou contrat 4A. Les preuves des courses Consumption, assertions SQL d'isolation des lectures, rollback/finalisation, projections exactes et traversée HTTP restent exécutées.

Documents : présent rapport, preuves JSON liées et addendum daté au diagnostic différentiel initial. Diff exact et whitespace contrôlés avant staging nominatif, commit puis push normal sur la branche cible. SHA final et état après push donnés dans la restitution, pour éviter une autoréférence du commit documentaire. Préservation des autres worktrees vérifiée par comparaison des SHA, statuts et empreintes de leurs travaux préexistants.

**Aucune décision de clôture 4A ni autorisation 4B n'est prise par ce rapport.**
