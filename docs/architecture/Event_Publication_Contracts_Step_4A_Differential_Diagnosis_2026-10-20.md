# 4A — Diagnostic différentiel du gate architecture global

Date : 2026-10-20 (Europe/Paris). Diagnostic uniquement ; aucune correction, modification de code/test/configuration, clôture automatique de 4A ou ouverture de 4B.

## Autorité, sécurité et périmètre

Références lues : [AGENTS](../../AGENTS.md), [politique normative](../testing/Reactor_Verification_Policy.md), [plan Étape 4](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md), [livraison 4A](Event_Publication_Contracts_Step_4A_2026-10-19.md). Historique consulté : [diagnostic différentiel 2C](Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md#diagnostic-différentiel-et-dette-du-gate-global), [preuve Spring/JPA 3C](Event_Terminal_Sequencing_Step_3C_Transactional_Integration_2026-10-19.md), journaux 4A et rapports Surefire disponibles. L'historique corrobore les observations ; les classifications ci-dessous reposent sur les deux nouvelles exécutions intégrales du gate demandé.

Dépôt `kartaguez/pocoma`, branche cible `feat/consumption-transaction-protocol`. Baseline exacte `a53999cb4dc7dac5973478b5ad50243710acba87` ; livraison exacte `3a2440326d227645da501a821ad73caad920db42` (un commit 4A, 20 fichiers, 844 insertions / 1 suppression). Le HEAD initial de la branche cible et son tracking sont tous deux `3a244032`, divergence 0/0, confirmée par fetch réel. Worktree cible propre.

Avant les opérations, le checkout principal est à `90926a5ec37d1ca841e3de017bec0d1f705d8839`, branche `feat/runtime-monolith`, tracking `origin/feat/runtime-monolith`, divergence 0/2. Travaux préexistants préservés : Keycloak, docs/README, collection Bruno, test Expense non suivi, études et preuves non suivies. Worktrees préexistants inventoriés : checkout principal ; `/private/tmp/pocoma-2c-differential-baseline` (`8a79f06d`, détaché), `pocoma-binding-lock-order-guard` (`2eef3ab9`), `pocoma-consumption-transaction-protocol` (`3a244032`), `pocoma-expense-context` (`116f4e4a`), et `/Users/julien.guezennec/.codex/worktrees/debt-wa6-integration/pocoma` (`2eef3ab9`, détaché). Aucun de leurs travaux n'est déplacé ou réécrit.

Deux worktrees temporaires **détachés et neufs** sont créés sous `/private/tmp/pocoma-diff4a-{delivered,baseline}` aux SHA complets ci-dessus. Le gate livré est exécuté en premier, la baseline ensuite, séquentiellement. Aucun reset, stash, rebase, merge, cherry-pick, exclusion de méthode, retry ajouté ou push forcé. Les essais initiaux de création des worktrees et d'accès Docker ont rencontré les restrictions de sandbox ; ils ont été repris avec l'accès autorisé, avant le démarrage de Maven. Ce sont des restrictions d'outillage, pas des résultats de test.

Déclaration préalable : impact production/tests/DB applicative **NONE** ; périmètre primaire **architecture-tests**, secondaires **NONE** ; fermeture `-am` normale, sans nouvelle slice fonctionnelle. Gate architecture global **REQUIRED** par la mission et le plan 4A. Reactor complet **interdit, non exécuté**, sans raison d'élargir ce diagnostic borné. Aucun franchissement inattendu de slice. DB **CURRENT**, bases Testcontainers jetables, aucune base partagée touchée. La politique indique `Trusted baseline: NONE` ; les suites déjà incluses conservent leur bootstrap historique existant (PRIMARY V1–V29, READ jusqu'à V14), sans campagne historique supplémentaire.

Les journaux, copies Surefire, comparaisons machine et scripts diagnostiques restent hors des worktrees dans `/private/tmp/pocoma-diff4a-20261020/`. Maven crée ses artefacts ignorés `target/` dans les worktrees temporaires ; ils sont copiés dans ce répertoire externe pour conservation. Le seul fichier livré dans Git est le présent rapport, avec les traces complètes utiles en annexe. Aucun fichier suivi préexistant n'est modifié.

## Verdict et recommandation

**PASS DIFFÉRENTIEL : aucune régression 4A démontrée, les 11 erreurs sont prouvées préexistantes.** Chacune est reproduite sur la baseline exacte avec le même statut ERROR, le même message, la même trace intégrale et le même premier point de défaillance, dans le même environnement logiciel/PostgreSQL. Les chemins responsables sont inchangés et les trois nouvelles gardes ne sont pas atteintes.

**Statut réel du gate : FAIL / BUILD FAILURE sur les deux SHA.** Les onze erreurs comprennent **six erreurs de bootstrap datasource/Flyway et cinq erreurs de composition transactionnelle de fixture**, et non onze erreurs de connexion.

Recommandation : accepter la non-régression 4A de ces onze erreurs et examiner une clôture explicite de 4A avec acceptation documentée des deux défauts préexistants, maintien visible du gate rouge et traitement séparé de ces défauts. Tant que cet arbitrage n'est pas rendu, conserver le statut de clôture non validée. Le présent diagnostic établit la non-régression de ces onze erreurs ; il n'accorde pas lui-même une dérogation au gate exigé par le plan. Aucune clôture 4A ni autorisation 4B n'est prononcée.

## Reproduction et environnement

Les deux commandes, sans filtre ni exclusion, sont strictement celles de la politique :

```bash
cd /private/tmp/pocoma-diff4a-delivered/app
./mvnw -pl architecture-tests -am test
cd /private/tmp/pocoma-diff4a-baseline/app
./mvnw -pl architecture-tests -am test
```

| Exécution | SHA | Début UTC | Fin UTC | Tests | Failures | Errors | Skips | Sortie |
|---|---|---|---|---:|---:|---:|---:|---|
| delivered | `3a244032` | Mon Oct 19 23:33:47 UTC 2026 | Mon Oct 19 23:37:20 UTC 2026 | 1257 | 0 | 11 | 1 | 1, BUILD FAILURE |
| baseline | `a53999cb` | Mon Oct 19 23:37:42 UTC 2026 | Mon Oct 19 23:41:12 UTC 2026 | 1239 | 0 | 11 | 1 | 1, BUILD FAILURE |

Toutes les ancres/runtime en amont sont SUCCESS ; seul `pocoma-architecture-tests` termine FAILURE. Les XML sont collectés depuis deux worktrees sans ancien `target/` : aucun rapport résiduel d'une autre commande n'entre dans les totaux. Le module architecture contient 138 tests après 4A contre 132 en baseline, 11 errors dans chaque cas et aucun skip. Les totaux sont calculés à partir des XML Surefire, pas par addition aveugle de lignes Maven.

Environnement commun : macOS 27.0 aarch64, Temurin OpenJDK 21+35 LTS, Maven wrapper 3.9.14, Spring Boot 4.0.5, Surefire 3.5.5, Docker client/serveur 29.8.2, Testcontainers 2.0.4, PostgreSQL 17.11 (`postgres:17-alpine`). Empreinte d'image identique avant/pendant/après les campagnes : `sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` (RepoDigest identique). Aucun override `POCOMA_`, `SPRING_`, `JAVA_TOOL_OPTIONS`, `MAVEN_OPTS`, `DOCKER_`, `TESTCONTAINERS_` dans les environnements relevés ; mêmes versions Maven/Java.

Prérequis PostgreSQL : Docker disponible et image identique ; conteneurs fraîchement créés par les mêmes fixtures, mêmes nom de base, paramètres et isolation. Aucun service n'écoute à `localhost:5432` avant et entre les campagnes ; les probes TCP hors sandbox renvoient ECONNREFUSED (61). Les ports PostgreSQL publiés sont dynamiques (ex. 63993 pour 4A, 49606 pour la baseline). Les horodatages progressent normalement ; à `2026-10-19T23:38:40Z`, hôte et conteneur de baseline concordent à la seconde. Aucun réglage d'horloge ni changement d'image/configuration n'est effectué. Les deux campagnes sont séquentielles ; elles ne partagent pas leurs données.

Les différences d'environnement constatées sont les chemins des deux checkouts, l'heure d'exécution, les UUID/PID/xid de test, identifiants/ports de conteneurs et durées. Ces différences sont attendues avec des bases jetables. Elles ne changent ni la destination erronée fixe 5432 de D, ni la règle d'ambient de T. Aucun changement influent de prérequis n'est constaté.

## Matrice différentielle des onze erreurs

Préfixes de classe : `ccr` = `com.kartaguez.pocoma.architecture.ccr`, `ept` = `com.kartaguez.pocoma.architecture.ept`. **D** = racine `java.net.ConnectException: Connection refused`, via Flyway/PSQL, SQLSTATE 08001. **T** = racine `java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization`. Les messages exacts et toutes les traces figurent plus bas.

« Chemin modifié » vise les fichiers au premier échec et leur chaîne causale, pas l'ensemble du graphe compilé : les writers gardés sont modifiés par 4A mais ne sont pas atteints ici. L'identité des traces est comparée sur le texte intégral de chaque élément XML `error`, après décodage XML, **sans masquage ni normalisation de ligne, frame, message ou nom de classe**.

| # | Classe / méthode | Baseline | 4A | Racine comparée | Stack trace | Phase et premier point de défaillance comparés | Chemin modifié par 4A | Classification |
|---|---|---|---|---|---|---|---|
| 1 | `ccr.CommandCompletionE2EPostgresTest.admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably` | ERROR | ERROR | D = D | Identique intégralement | Identique : refresh / Flyway : JdbcUtils.openConnection:70 ; commandContext:531 | Non | **PREEXISTING** |
| 2 | `ccr.CommandCompletionE2EPostgresTest.eventFeedsIndependentLkvAndExactProjectionPipelines` | ERROR | ERROR | D = D | Identique intégralement | Identique : refresh / Flyway : JdbcUtils.openConnection:70 ; cleanCommandContext:537 | Non | **PREEXISTING** |
| 3 | `ccr.CommandCompletionE2EPostgresTest.httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions` | ERROR | ERROR | D = D | Identique intégralement | Identique : refresh / Flyway : JdbcUtils.openConnection:70 ; webContext:598 | Non | **PREEXISTING** |
| 4 | `ccr.CommandCompletionE2EPostgresTest.registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration` | ERROR | ERROR | D = D | Identique intégralement | Identique : refresh / Flyway : JdbcUtils.openConnection:70 ; webContext:598 | Non | **PREEXISTING** |
| 5 | `ccr.CommandResultTakeoverRacePostgresTest.committedFinalCasMakesWaitingTakeoverObserveDone` | ERROR | ERROR | T = T | Identique intégralement | Identique : claim initial : requireNoAmbientTransaction:25 ; fixture acquire:307 | Non | **PREEXISTING** |
| 6 | `ccr.CommandResultTakeoverRacePostgresTest.inFlightPublicationLosesFenceAndUnblocksWinnerPublication` | ERROR | ERROR | T = T | Identique intégralement | Identique : claim initial : requireNoAmbientTransaction:25 ; fixture acquire:307 | Non | **PREEXISTING** |
| 7 | `ccr.CommandResultTakeoverRacePostgresTest.rolledBackFinalCasReleasesSlotAndAllowsTakeover` | ERROR | ERROR | T = T | Identique intégralement | Identique : claim initial : requireNoAmbientTransaction:25 ; fixture acquire:307 | Non | **PREEXISTING** |
| 8 | `ccr.CommandResultTakeoverRacePostgresTest.rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo` | ERROR | ERROR | T = T | Identique intégralement | Identique : claim initial : requireNoAmbientTransaction:25 ; fixture acquire:307 | Non | **PREEXISTING** |
| 9 | `ccr.CommandResultTakeoverRacePostgresTest.twoConcurrentTakeoversInstallOnlyOneCurrentClaim` | ERROR | ERROR | T = T | Identique intégralement | Identique : claim initial : requireNoAmbientTransaction:25 ; fixture acquire:307 | Non | **PREEXISTING** |
| 10 | `ept.DurableEventToProjectionPostgresTest.durableEventOutputIsTheIndependentProjectionTaskRuntimeInput` | ERROR | ERROR | D = D | Identique intégralement | Identique : refresh / Flyway : JdbcUtils.openConnection:70 ; eventContext:137 | Non | **PREEXISTING** |
| 11 | `ept.HistoricalEventRouteEvolutionPostgresTest.evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence` | ERROR | ERROR | D = D | Identique intégralement | Identique : refresh / Flyway : JdbcUtils.openConnection:70 ; eventContext:153 | Non | **PREEXISTING** |

Comparaison de tous les résultats : **1239 cas communs, aucun changement de statut, aucune erreur supplémentaire propre à la baseline, aucun test supprimé**. Les onze ERROR baseline sont exactement les onze ERROR après 4A. Les 18 cas supplémentaires de 4A sont tous PASS :

| Classe ajoutée ou enrichie | Nouveaux cas PASS |
|---|---:|
| `com.kartaguez.pocoma.architecture.EventPublicationContractArchitectureTest` | 6 |
| `com.kartaguez.pocoma.engine.consume.command.model.PublicationAcceptanceContractTest` | 6 |
| `com.kartaguez.pocoma.engine.consume.command.pot.dispatch.NonPersistentDispatchRelayTest` | 1 |
| `com.kartaguez.pocoma.engine.consume.registration.RegistrationPublicationContractTest` | 2 |
| `com.kartaguez.pocoma.engine.write.pot.service.NonPersistentEventPublicationTest` | 1 |
| `com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.RegistrationAuthorityPostgresTest` | 2 |

Le skip commun est `TerminalSequencingPostgresTest`, mesure 3B conditionnée par `step3b.measure=true` ; cette campagne n'est pas activée pour le diagnostic. Aucun skip ni filtre n'est ajouté. Les règles hexagonales et les six nouvelles preuves d'architecture 4A passent.

## Analyse causale

### D — Six échecs de bootstrap datasource/Flyway

Chaîne capturée : `BeanCreationException(entityManagerFactory) → BeanCreationException(flywayInitializer) → FlywaySqlUnableToConnectToDbException → PSQLException → java.net.ConnectException: Connection refused`. SQLSTATE `08001`, code `0`, connexion tentée à `localhost:5432`. Le message PostgreSQL exact est :

```text
Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
```

La cause racine exacte est `java.net.ConnectException: Connection refused`. Le composant qui propage l'erreur est l'initialiseur Flyway, bloquant la construction de l'EntityManagerFactory. Il ne s'agit pas d'une migration SQL rejetée : l'ouverture de connexion échoue dans `JdbcUtils.openConnection`, avant l'accès à l'historique et avant toute migration ou requête du scénario concerné. Aucune version de migration ne peut être imputée à ces six erreurs.

Mécanisme établi par les traces, les sources du dépôt et les sources locales exactes Spring Boot **4.0.5** (JAR de sources déjà présent, sans recherche réseau) :

1. Les helpers `application`/`eventContext`/`webContext` des trois classes fournissent l'URL Testcontainers via `SpringApplicationBuilder.properties(...)`. La méthode `properties(Map)` appelle `SpringApplication.setDefaultProperties` (`SpringApplicationBuilder.java:488–495`).
2. `ConfigDataEnvironment.applyToEnvironment` applique les ressources de configuration puis appelle `DefaultPropertiesPropertySource.moveToEnd` (`ConfigDataEnvironment.java:338–345`). Cette dernière retire puis ajoute les defaults en dernière position (`DefaultPropertiesPropertySource.java:127–131`). La configuration chargée prévaut donc sur les defaults du builder.
3. Le classpath de ces tests inclut `runtime-monolith/target/classes`, qui fournit `application.properties` : `spring.application.name=pocoma-runtime-monolith` et `spring.datasource.url=${POCOMA_POSTGRES_URL:jdbc:postgresql://localhost:5432/pocoma}`. Les logs de démarrage portent effectivement `[pocoma-runtime-monolith]`, puis la trace établit la tentative `localhost:5432`. Aucun override `POCOMA_`/`SPRING_` n'est présent dans l'environnement relevé.
4. Aucun PostgreSQL n'écoute à cette adresse ; les conteneurs ont démarré sur des ports dynamiques différents. L'absence du serveur local explique le refus TCP observé ; elle ne démontre pas une indisponibilité des conteneurs de test. Les autres tests démarrent PostgreSQL et exécutent Flyway avec succès dans les deux gates.

La priorité de configuration et la mauvaise destination sont ainsi étayées conjointement par le code de la bibliothèque effectivement utilisée, le fichier chargé et le résultat réseau. Aucun changement de configuration, serveur de contournement à 5432 ou instrumentation de test n'a été introduit pour cette preuve. La provenance exacte d'une ressource est aussi contrôlée par l'ordre du classpath conservé dans les preuves externes.

L'échec survient pendant `SpringApplicationBuilder.run` et `refresh`, avant le retour du helper et avant les `cleanAndSeed`/`cleanDatabase`, admissions, commandes, publications et initializers Registration du scénario. L'état métier de la base, son isolation ou les nouvelles gardes de writer ne participent pas à ce premier échec. L'initialisation des beans peut annoncer une dépendance JPA, mais aucun writer applicatif n'apparaît dans la chaîne causale capturée.

### T — Cinq erreurs de composition transactionnelle de la fixture takeover

La cause racine est ici **différente**, sans exception PostgreSQL imbriquée :

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
```

Le contexte Spring de `CommandResultTakeoverRacePostgresTest` utilise `@DynamicPropertySource` pour sa datasource. PostgreSQL est disponible, Flyway et JPA démarrent, le nettoyage et le seed sont exécutés. Les logs de probe montrent une transaction READ COMMITTED `C1-acquire` suivie d'une résolution `status=1` (rollback). L'exception est levée au **claim initial** et provoque ce rollback, avant d'atteindre les courses, publications, CAS finaux et verrous que chaque méthode voulait éprouver.

Le chemin est démontré par le corps de la fixture et par chacune des cinq traces :

```text
initialClaim:376
  → inTransaction:281 → new TransactionTemplate(transactionManager).execute(...)
    → action.get():294 → lambda$initialClaim$24:376 → acquire:307
      → TransactionalAcquireConsumptionUseCase.acquire:23
        → ConsumptionOwnerTransactionRunner.runInTransaction:28
          → requireNoAmbientTransaction:25 → IllegalStateException
```

`CommandResultRuntimeConfiguration.java:40,43` compose réellement `commandResultAcquire` avec `ConsumptionOwnerTransactionRunner(new SpringTransactionRunner(new TransactionTemplate(manager)))`. La fixture ouvre déjà la transaction externe ; `ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction` refuse une transaction active **ou** une synchronisation active avant d'appeler son délégué. La contradiction entre cette fixture participante et l'owner courant suffit à provoquer les cinq erreurs. Le nom `PostgresTest` ne leur donne pas la même cause qu'aux six erreurs D.

SQL de la probe avant le refus :

```sql
select pg_backend_pid() as pid, txid_current() as xid,
       current_setting('transaction_isolation') as isolation
```

Cette requête réussit ; aucun SQL d'acquisition du claim n'est atteint via le délégué, aucune migration ni requête SQL n'est identifiée comme défaillante pour T. Les cinq traces ne contiennent ni `PrimaryWriteParticipation`, ni un des trois writers protégés par 4A. L'ancien owner qui lève l'exception et son câblage sont byte-identiques sur les deux SHA.

### Rôle de PrimaryWriteParticipation et impact réel de 4A

Le diff de production 4A ajoute l'appel de garde dans `JdbcUserCreatedFactAdapter.append`, `ExternalIdentityBindingFactJdbcRepository.append` et `JdbcRegistrationOutcomeStore.insert`. `PrimaryWriteParticipation.require` vérifie transaction/synchronisation actives, lecture-écriture, datasource liée à un `ConnectionHolder`, puis autocommit désactivé. Il n'a ni initialiseur statique à effet, ni annotation de configuration, ni ouverture de connexion, ni migration, ni modification du manager. Il n'est exécuté que depuis les corps de ces trois méthodes. Les nouvelles enveloppes/types sont des contrats ; ils ne sont pas raccordés aux producteurs CURRENT en 4A.

**Implication directe exclue pour les erreurs observées** : D échoue avant le scénario et l'appel des writers ; T échoue dans l'owner avant le délégué d'acquisition. Les classes et messages d'exception ne sont pas ceux de la nouvelle garde (`IllegalTransactionStateException`, messages `Durable PRIMARY write ...`).

**Implication indirecte examinée** : la seule dépendance POM nouvelle est `port-binding-authority → port-transaction`, déjà présent dans la fermeture de ce gate. Les POM racine/architecture, configuration runtime, migrations, fixture takeover, wrapper transactionnel et câblage Command Result sont inchangés ; les classpaths Surefire sont intégralement identiques après le seul remplacement du chemin de worktree par un marqueur commun (ordre des ressources et versions JAR conservés). La dépendance ajoutée ne modifie donc pas la sélection des ressources de configuration de ce gate. Le contrôle des résultats communs complète cet examen. Le même premier échec avec la même trace intégrale sur le SHA dépourvu de toute garde 4A constitue la preuve différentielle ; la seule absence du nom de garde dans une trace n'aurait pas suffi.

Les huit tests de `RegistrationAuthorityPostgresTest` passent après 4A, dont les deux nouvelles méthodes de refus des appels directs/new et de rollback physique commun ; les six tests structurels `EventPublicationContractArchitectureTest` passent aussi. Ces résultats bornent l'effet livré des gardes ; ils ne rendent pas verts les scénarios architecturaux bloqués.

## Historique, diagnostics séparés et limites

Le rapport 4A appelait les onze erreurs « erreurs de bootstrap PostgreSQL » et n'avait reproduit que `DurableEventToProjectionPostgresTest` sur une archive de baseline. **Cette attribution uniforme et cette preuve partielle sont remplacées, pour la décision de clôture, par la matrice individuelle du présent rapport.** Le document historique est conservé intact ; le diagnostic 2C distinguait déjà D et T.

L'ancien premier gate 4A avait aussi échoué dans LKV sur `ck_consumption_slots_next_claim_at` avec un écart d'horloges possible. C'est un événement distinct, hors des onze erreurs du second gate. Les huit tests `LatestKnownVersionRuntimePostgresTest` passent dans chacune des deux nouvelles exécutions. Ce diagnostic ne certifie pas rétrospectivement la cause de l'ancien incident d'horloge.

Les deux gates émettent dans PRIMARY le diagnostic Surefire `The exit has elapsed 30 seconds after System.exit(0)` ; le module PRIMARY termine néanmoins SUCCESS, sans failure/error dans ses rapports. Ce diagnostic de fermeture reste visible et non corrigé ; il n'est ni additionné aux onze erreurs de test, ni requalifié en nouvelle régression 4A.

La preuve porte sur les onze premiers échecs observés, dans l'environnement décrit. Les assertions métier et les courses situées après ces points ne sont pas exécutées jusqu'au bout : des défauts ultérieurs éventuels restent masqués. Aucun PASS universel du comportement 4A, de tous les environnements ou du gate global n'est déduit du résultat différentiel. Les ports/UUID, timestamps et identifiants de conteneur diffèrent naturellement entre campagnes jetables ; aucun état partagé n'est réutilisé. Aucune correction causale n'est éprouvée dans cette mission, qui l'interdit.

## Empreintes et traçabilité

Identité des sources responsables contrôlée par extraction `git show` à chaque SHA et comparaison des octets SHA-256 :

| Chemin | SHA-256 commun baseline / 4A |
|---|---|
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ept/DurableEventToProjectionPostgresTest.java` | `5ae35716166412a6d24776a276d9a73fc4cfd8b3ce32395b68f52c76926a9ffd` |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ept/HistoricalEventRouteEvolutionPostgresTest.java` | `4a7c4346a6b96c0fb01db6f94ba0395e78e797801bd52c264da783df816dc8a2` |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandCompletionE2EPostgresTest.java` | `9d4fd9741fc0e385ef2d95eac3b7afe184f514a03dc3361c4dde57505637e1b5` |
| `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultTakeoverRacePostgresTest.java` | `d72cf739bd3fb3de2cbb154e774d5f9789b68843fdcca6972a72d9e1d81b220c` |
| `app/runtime-monolith/src/main/resources/application.properties` | `f9ca4e6bbaa171a6f226fc3666d1b6af1bdff927c35d982c4d665a7311185880` |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java` | `b1a226d14f7861312cb118a3884ff426f4de5ba1d63c2da723ca21e27bc11dcc` |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/result/CommandResultRuntimeConfiguration.java` | `613ccf8331fdf745dededcfa4b75bfc08bb09fe9716f30181c6e186aba28dc31` |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalAcquireConsumptionUseCase.java` | `32922f58d97f6f3b2b0436c3c5dd799f8c2c9357b01715d5c3c35937ebe952f8` |
| `app/architecture-tests/pom.xml` | `a66fe0eb6ce6b819410cfcb48a589282e8fa7ed5561db246c5befa878cce18c0` |
| `app/pom.xml` | `e033ef6dad062d0e5f889a723c189dd79e1c2b328a363d65aab77f4a0e994e02` |

Empreintes du texte complet de trace XML décodé UTF-8 : chaque ligne vaut pour **les deux exécutions**, et référence la même ligne de matrice/annexe. Les XML complets ont aussi leurs empreintes propres dans `MANIFEST.sha256`, car ils contiennent timestamps et ports variables.

| Erreur | SHA-256 commun de la trace complète |
|---|---|
| 1 | `acbae39afd118a42091b84cf6924bfd33cb7592062e126188dced065e38917f8` |
| 2 | `1e4c218c336749db3beb20a0b560395fd17a7a908c29d3f574abb3df8832e9c2` |
| 3 | `9f22038060f6ab5c9de557f95684738b57e6d706890db5fe60cc7dbb224d8893` |
| 4 | `6bdae4822c1968fb291e8a75dfde73a8fb827993f2f7736f6eb7886df1b59ea2` |
| 5 | `8d38fd9ae8c492ce37816e6a7149adc6ae0aac197b251412d9c592caea30bcbe` |
| 6 | `00a13691ea816c88e27282d979eed6fa39c6827e3dcb0f703b80f0a57325bf2d` |
| 7 | `b54a6863c576f346dae812a3cc6e791f7c47efc86f5c8e843539b272baad3735` |
| 8 | `bca91536d59904c51e8d0f6f432e1b73e89fd12fbeba1a0947a5e68d93ec93cb` |
| 9 | `a27dd308d8cf532fa5f373a9a17742a79a102b08cf205eccc481eac26278f809` |
| 10 | `f1f17b7abb4fdad38218edbe963c9c7775ac4e87c2893deeabe7b85343a59217` |
| 11 | `5d52a65d7e41063d46ebcc01b7cd0ee05de73ff301b366ae975e83ab74d3a26b` |

Preuves externes dans `/private/tmp/pocoma-diff4a-20261020/` : `delivered-gate.log`, `baseline-gate.log`, codes de sortie et dates, `delivered/` et `baseline/` (copies XML/TXT/dumps Surefire de chaque module, `summary.json`), `comparison.json` (résultats et deux traces par erreur), `source-hashes.json`, `4a.diff`, fichiers `environment-*.json`, versions Maven, classpaths et sources Spring locales inspectées. Les quatre journaux historiques utilisés sont copiés avec préfixe `historical-`. `MANIFEST.sha256` permet de vérifier ces preuves sans dépendre des worktrees temporaires.

Commandes de collecte réellement exécutées :

```bash
git status --short --branch
git rev-parse HEAD
git branch -vv
git remote -v
git worktree list --porcelain
git rev-list --left-right --count HEAD...@{upstream}
git fetch origin feat/consumption-transaction-protocol
git worktree add --detach /private/tmp/pocoma-diff4a-baseline a53999cb4dc7dac5973478b5ad50243710acba87
git worktree add --detach /private/tmp/pocoma-diff4a-delivered 3a2440326d227645da501a821ad73caad920db42
git diff --stat a53999cb 3a244032
git diff a53999cb 3a244032
git diff --quiet a53999cb 3a244032 -- app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ept app/runtime-monolith/src/main/resources/application.properties app/composition-runtime-spring app/infra-persistence-primary-jpa/src/main/resources app/pom.xml
java -version
./mvnw -version
docker version --format '{{.Client.Version}} {{.Server.Version}}'
docker ps --format '{{.ID}} {{.Image}} {{.Ports}} {{.Names}}'
docker image inspect postgres:17-alpine --format '{{.Id}} {{json .RepoDigests}}'
lsof -nP -iTCP:5432 -sTCP:LISTEN
python3 /private/tmp/pocoma-diff4a-20261020/environment.py <fichier-environnement.json>
python3 /private/tmp/pocoma-diff4a-20261020/collect.py delivered /private/tmp/pocoma-diff4a-delivered
python3 /private/tmp/pocoma-diff4a-20261020/collect.py baseline /private/tmp/pocoma-diff4a-baseline
python3 /private/tmp/pocoma-diff4a-20261020/compare.py
```

Compléments en lecture seule : lectures `cat`/`sed`/`rg` des autorités, sources et journaux ; extractions Python `git show <SHA>:<chemin>`, SHA-256, parsing `xml.etree.ElementTree`, comparaison des statuts/messages/traces et classpaths ; extraction `zipfile` du JAR de sources Spring Boot 4.0.5 ; `javap -c -p` de `SpringApplicationBuilder` (une tentative sur l'ancien package de `DefaultPropertiesPropertySource` ne trouvait pas la classe ; son source local exact a ensuite été lu). `environment.py` teste TCP 127.0.0.1:5432, relève les versions/env et lance `docker exec <conteneur-test> date -u +%Y-%m-%dT%H:%M:%SZ`, sans commande SQL mutante. Aucun nouveau test n'est écrit.

## Annexe — Onze traces intégrales communes

Les textes ci-dessous sont les traces complètes émises par Surefire pour chaque méthode, communes aux deux SHA, sans retrait de frame ni de cause. Seuls les espaces de fin de ligne sont omis dans cet affichage Markdown ; la comparaison et les empreintes portent sur le texte XML original conservé. La notation JVM `... N more` est celle des rapports originaux et désigne des frames partagées avec la cause englobante. Les deux copies originales sont conservées dans les preuves externes.

### Erreur 1 — com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
org.springframework.beans.factory.BeanCreationException:
Error creating bean with name 'entityManagerFactory' defined in class path resource [org/springframework/boot/hibernate/autoconfigure/HibernateJpaConfiguration.class]: Failed to initialize dependency 'flywayInitializer' of LoadTimeWeaverAware bean 'entityManagerFactory': Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:322)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:201)
	at org.springframework.context.support.AbstractApplicationContext.finishBeanFactoryInitialization(AbstractApplicationContext.java:977)
	at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:621)
	at org.springframework.boot.SpringApplication.refresh(SpringApplication.java:756)
	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:445)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:321)
	at org.springframework.boot.builder.SpringApplicationBuilder.run(SpringApplicationBuilder.java:154)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.commandContext(CommandCompletionE2EPostgresTest.java:531)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably(CommandCompletionE2EPostgresTest.java:153)
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1817)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:603)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:525)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:333)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:371)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:331)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:196)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:309)
	... 9 more
Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:70)
	at org.flywaydb.core.internal.jdbc.JdbcConnectionFactory.<init>(JdbcConnectionFactory.java:76)
	at org.flywaydb.core.FlywayExecutor.execute(FlywayExecutor.java:142)
	at org.flywaydb.core.Flyway.migrate(Flyway.java:186)
	at org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer.afterPropertiesSet(FlywayMigrationInitializer.java:67)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.invokeInitMethods(AbstractAutowireCapableBeanFactory.java:1864)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1813)
	... 16 more
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:373)
	at org.postgresql.core.ConnectionFactory.openConnection(ConnectionFactory.java:57)
	at org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:290)
	at org.postgresql.Driver.makeConnection(Driver.java:448)
	at org.postgresql.Driver.connect(Driver.java:298)
	at com.zaxxer.hikari.util.DriverDataSource.getConnection(DriverDataSource.java:144)
	at com.zaxxer.hikari.pool.PoolBase.newConnection(PoolBase.java:373)
	at com.zaxxer.hikari.pool.PoolBase.newPoolEntry(PoolBase.java:210)
	at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:488)
	at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:576)
	at com.zaxxer.hikari.pool.HikariPool.<init>(HikariPool.java:97)
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:111)
	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:65)
	... 22 more
Caused by: java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:682)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:549)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:327)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at org.postgresql.core.PGStream.createSocket(PGStream.java:261)
	at org.postgresql.core.PGStream.<init>(PGStream.java:122)
	at org.postgresql.core.v3.ConnectionFactoryImpl.tryConnect(ConnectionFactoryImpl.java:146)
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:289)
	... 34 more
```

### Erreur 2 — com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.eventFeedsIndependentLkvAndExactProjectionPipelines

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
org.springframework.beans.factory.BeanCreationException:
Error creating bean with name 'entityManagerFactory' defined in class path resource [org/springframework/boot/hibernate/autoconfigure/HibernateJpaConfiguration.class]: Failed to initialize dependency 'flywayInitializer' of LoadTimeWeaverAware bean 'entityManagerFactory': Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:322)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:201)
	at org.springframework.context.support.AbstractApplicationContext.finishBeanFactoryInitialization(AbstractApplicationContext.java:977)
	at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:621)
	at org.springframework.boot.SpringApplication.refresh(SpringApplication.java:756)
	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:445)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:321)
	at org.springframework.boot.builder.SpringApplicationBuilder.run(SpringApplicationBuilder.java:154)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.cleanCommandContext(CommandCompletionE2EPostgresTest.java:537)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.eventFeedsIndependentLkvAndExactProjectionPipelines(CommandCompletionE2EPostgresTest.java:406)
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1817)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:603)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:525)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:333)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:371)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:331)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:196)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:309)
	... 9 more
Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:70)
	at org.flywaydb.core.internal.jdbc.JdbcConnectionFactory.<init>(JdbcConnectionFactory.java:76)
	at org.flywaydb.core.FlywayExecutor.execute(FlywayExecutor.java:142)
	at org.flywaydb.core.Flyway.migrate(Flyway.java:186)
	at org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer.afterPropertiesSet(FlywayMigrationInitializer.java:67)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.invokeInitMethods(AbstractAutowireCapableBeanFactory.java:1864)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1813)
	... 16 more
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:373)
	at org.postgresql.core.ConnectionFactory.openConnection(ConnectionFactory.java:57)
	at org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:290)
	at org.postgresql.Driver.makeConnection(Driver.java:448)
	at org.postgresql.Driver.connect(Driver.java:298)
	at com.zaxxer.hikari.util.DriverDataSource.getConnection(DriverDataSource.java:144)
	at com.zaxxer.hikari.pool.PoolBase.newConnection(PoolBase.java:373)
	at com.zaxxer.hikari.pool.PoolBase.newPoolEntry(PoolBase.java:210)
	at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:488)
	at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:576)
	at com.zaxxer.hikari.pool.HikariPool.<init>(HikariPool.java:97)
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:111)
	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:65)
	... 22 more
Caused by: java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:682)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:549)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:327)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at org.postgresql.core.PGStream.createSocket(PGStream.java:261)
	at org.postgresql.core.PGStream.<init>(PGStream.java:122)
	at org.postgresql.core.v3.ConnectionFactoryImpl.tryConnect(ConnectionFactoryImpl.java:146)
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:289)
	... 34 more
```

### Erreur 3 — com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
org.springframework.beans.factory.BeanCreationException:
Error creating bean with name 'entityManagerFactory' defined in class path resource [org/springframework/boot/hibernate/autoconfigure/HibernateJpaConfiguration.class]: Failed to initialize dependency 'flywayInitializer' of LoadTimeWeaverAware bean 'entityManagerFactory': Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:322)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:201)
	at org.springframework.context.support.AbstractApplicationContext.finishBeanFactoryInitialization(AbstractApplicationContext.java:977)
	at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:621)
	at org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext.refresh(ServletWebServerApplicationContext.java:143)
	at org.springframework.boot.SpringApplication.refresh(SpringApplication.java:756)
	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:445)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:321)
	at org.springframework.boot.builder.SpringApplicationBuilder.run(SpringApplicationBuilder.java:154)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.webContext(CommandCompletionE2EPostgresTest.java:598)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions(CommandCompletionE2EPostgresTest.java:257)
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1817)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:603)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:525)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:333)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:371)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:331)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:196)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:309)
	... 10 more
Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:70)
	at org.flywaydb.core.internal.jdbc.JdbcConnectionFactory.<init>(JdbcConnectionFactory.java:76)
	at org.flywaydb.core.FlywayExecutor.execute(FlywayExecutor.java:142)
	at org.flywaydb.core.Flyway.migrate(Flyway.java:186)
	at org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer.afterPropertiesSet(FlywayMigrationInitializer.java:67)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.invokeInitMethods(AbstractAutowireCapableBeanFactory.java:1864)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1813)
	... 17 more
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:373)
	at org.postgresql.core.ConnectionFactory.openConnection(ConnectionFactory.java:57)
	at org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:290)
	at org.postgresql.Driver.makeConnection(Driver.java:448)
	at org.postgresql.Driver.connect(Driver.java:298)
	at com.zaxxer.hikari.util.DriverDataSource.getConnection(DriverDataSource.java:144)
	at com.zaxxer.hikari.pool.PoolBase.newConnection(PoolBase.java:373)
	at com.zaxxer.hikari.pool.PoolBase.newPoolEntry(PoolBase.java:210)
	at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:488)
	at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:576)
	at com.zaxxer.hikari.pool.HikariPool.<init>(HikariPool.java:97)
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:111)
	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:65)
	... 23 more
Caused by: java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:682)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:549)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:327)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at org.postgresql.core.PGStream.createSocket(PGStream.java:261)
	at org.postgresql.core.PGStream.<init>(PGStream.java:122)
	at org.postgresql.core.v3.ConnectionFactoryImpl.tryConnect(ConnectionFactoryImpl.java:146)
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:289)
	... 35 more
```

### Erreur 4 — com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
org.springframework.beans.factory.BeanCreationException:
Error creating bean with name 'entityManagerFactory' defined in class path resource [org/springframework/boot/hibernate/autoconfigure/HibernateJpaConfiguration.class]: Failed to initialize dependency 'flywayInitializer' of LoadTimeWeaverAware bean 'entityManagerFactory': Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:322)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:201)
	at org.springframework.context.support.AbstractApplicationContext.finishBeanFactoryInitialization(AbstractApplicationContext.java:977)
	at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:621)
	at org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext.refresh(ServletWebServerApplicationContext.java:143)
	at org.springframework.boot.SpringApplication.refresh(SpringApplication.java:756)
	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:445)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:321)
	at org.springframework.boot.builder.SpringApplicationBuilder.run(SpringApplicationBuilder.java:154)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.webContext(CommandCompletionE2EPostgresTest.java:598)
	at com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest.registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration(CommandCompletionE2EPostgresTest.java:325)
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1817)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:603)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:525)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:333)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:371)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:331)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:196)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:309)
	... 10 more
Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:70)
	at org.flywaydb.core.internal.jdbc.JdbcConnectionFactory.<init>(JdbcConnectionFactory.java:76)
	at org.flywaydb.core.FlywayExecutor.execute(FlywayExecutor.java:142)
	at org.flywaydb.core.Flyway.migrate(Flyway.java:186)
	at org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer.afterPropertiesSet(FlywayMigrationInitializer.java:67)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.invokeInitMethods(AbstractAutowireCapableBeanFactory.java:1864)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1813)
	... 17 more
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:373)
	at org.postgresql.core.ConnectionFactory.openConnection(ConnectionFactory.java:57)
	at org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:290)
	at org.postgresql.Driver.makeConnection(Driver.java:448)
	at org.postgresql.Driver.connect(Driver.java:298)
	at com.zaxxer.hikari.util.DriverDataSource.getConnection(DriverDataSource.java:144)
	at com.zaxxer.hikari.pool.PoolBase.newConnection(PoolBase.java:373)
	at com.zaxxer.hikari.pool.PoolBase.newPoolEntry(PoolBase.java:210)
	at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:488)
	at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:576)
	at com.zaxxer.hikari.pool.HikariPool.<init>(HikariPool.java:97)
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:111)
	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:65)
	... 23 more
Caused by: java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:682)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:549)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:327)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at org.postgresql.core.PGStream.createSocket(PGStream.java:261)
	at org.postgresql.core.PGStream.<init>(PGStream.java:122)
	at org.postgresql.core.v3.ConnectionFactoryImpl.tryConnect(ConnectionFactoryImpl.java:146)
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:289)
	... 35 more
```

### Erreur 5 — com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.committedFinalCasMakesWaitingTakeoverObserveDone

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.resolveFinalCasBeforeTakeover(CommandResultTakeoverRacePostgresTest.java:151)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.committedFinalCasMakesWaitingTakeoverObserveDone(CommandResultTakeoverRacePostgresTest.java:142)
```

### Erreur 6 — com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inFlightPublicationLosesFenceAndUnblocksWinnerPublication

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inFlightPublicationLosesFenceAndUnblocksWinnerPublication(CommandResultTakeoverRacePostgresTest.java:109)
```

### Erreur 7 — com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.rolledBackFinalCasReleasesSlotAndAllowsTakeover

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.resolveFinalCasBeforeTakeover(CommandResultTakeoverRacePostgresTest.java:151)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.rolledBackFinalCasReleasesSlotAndAllowsTakeover(CommandResultTakeoverRacePostgresTest.java:146)
```

### Erreur 8 — com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo(CommandResultTakeoverRacePostgresTest.java:245)
```

### Erreur 9 — com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.twoConcurrentTakeoversInstallOnlyOneCurrentClaim

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.twoConcurrentTakeoversInstallOnlyOneCurrentClaim(CommandResultTakeoverRacePostgresTest.java:196)
```

### Erreur 10 — com.kartaguez.pocoma.architecture.ept.DurableEventToProjectionPostgresTest.durableEventOutputIsTheIndependentProjectionTaskRuntimeInput

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
org.springframework.beans.factory.BeanCreationException:
Error creating bean with name 'entityManagerFactory' defined in class path resource [org/springframework/boot/hibernate/autoconfigure/HibernateJpaConfiguration.class]: Failed to initialize dependency 'flywayInitializer' of LoadTimeWeaverAware bean 'entityManagerFactory': Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:322)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:201)
	at org.springframework.context.support.AbstractApplicationContext.finishBeanFactoryInitialization(AbstractApplicationContext.java:977)
	at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:621)
	at org.springframework.boot.SpringApplication.refresh(SpringApplication.java:756)
	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:445)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:321)
	at org.springframework.boot.builder.SpringApplicationBuilder.run(SpringApplicationBuilder.java:154)
	at com.kartaguez.pocoma.architecture.ept.DurableEventToProjectionPostgresTest.eventContext(DurableEventToProjectionPostgresTest.java:137)
	at com.kartaguez.pocoma.architecture.ept.DurableEventToProjectionPostgresTest.durableEventOutputIsTheIndependentProjectionTaskRuntimeInput(DurableEventToProjectionPostgresTest.java:83)
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1817)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:603)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:525)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:333)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:371)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:331)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:196)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:309)
	... 9 more
Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:70)
	at org.flywaydb.core.internal.jdbc.JdbcConnectionFactory.<init>(JdbcConnectionFactory.java:76)
	at org.flywaydb.core.FlywayExecutor.execute(FlywayExecutor.java:142)
	at org.flywaydb.core.Flyway.migrate(Flyway.java:186)
	at org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer.afterPropertiesSet(FlywayMigrationInitializer.java:67)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.invokeInitMethods(AbstractAutowireCapableBeanFactory.java:1864)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1813)
	... 16 more
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:373)
	at org.postgresql.core.ConnectionFactory.openConnection(ConnectionFactory.java:57)
	at org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:290)
	at org.postgresql.Driver.makeConnection(Driver.java:448)
	at org.postgresql.Driver.connect(Driver.java:298)
	at com.zaxxer.hikari.util.DriverDataSource.getConnection(DriverDataSource.java:144)
	at com.zaxxer.hikari.pool.PoolBase.newConnection(PoolBase.java:373)
	at com.zaxxer.hikari.pool.PoolBase.newPoolEntry(PoolBase.java:210)
	at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:488)
	at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:576)
	at com.zaxxer.hikari.pool.HikariPool.<init>(HikariPool.java:97)
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:111)
	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:65)
	... 22 more
Caused by: java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:682)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:549)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:327)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at org.postgresql.core.PGStream.createSocket(PGStream.java:261)
	at org.postgresql.core.PGStream.<init>(PGStream.java:122)
	at org.postgresql.core.v3.ConnectionFactoryImpl.tryConnect(ConnectionFactoryImpl.java:146)
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:289)
	... 34 more
```

### Erreur 11 — com.kartaguez.pocoma.architecture.ept.HistoricalEventRouteEvolutionPostgresTest.evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence

Baseline : ERROR ; 4A : ERROR ; trace identique ; classification PREEXISTING.

```text
org.springframework.beans.factory.BeanCreationException:
Error creating bean with name 'entityManagerFactory' defined in class path resource [org/springframework/boot/hibernate/autoconfigure/HibernateJpaConfiguration.class]: Failed to initialize dependency 'flywayInitializer' of LoadTimeWeaverAware bean 'entityManagerFactory': Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:322)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:201)
	at org.springframework.context.support.AbstractApplicationContext.finishBeanFactoryInitialization(AbstractApplicationContext.java:977)
	at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:621)
	at org.springframework.boot.SpringApplication.refresh(SpringApplication.java:756)
	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:445)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:321)
	at org.springframework.boot.builder.SpringApplicationBuilder.run(SpringApplicationBuilder.java:154)
	at com.kartaguez.pocoma.architecture.ept.HistoricalEventRouteEvolutionPostgresTest.eventContext(HistoricalEventRouteEvolutionPostgresTest.java:153)
	at com.kartaguez.pocoma.architecture.ept.HistoricalEventRouteEvolutionPostgresTest.evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence(HistoricalEventRouteEvolutionPostgresTest.java:67)
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'flywayInitializer' defined in class path resource [org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class]: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1817)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:603)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:525)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:333)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:371)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:331)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:196)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:309)
	... 9 more
Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
SQL State  : 08001
Error Code : 0
Message    : Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.

	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:70)
	at org.flywaydb.core.internal.jdbc.JdbcConnectionFactory.<init>(JdbcConnectionFactory.java:76)
	at org.flywaydb.core.FlywayExecutor.execute(FlywayExecutor.java:142)
	at org.flywaydb.core.Flyway.migrate(Flyway.java:186)
	at org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer.afterPropertiesSet(FlywayMigrationInitializer.java:67)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.invokeInitMethods(AbstractAutowireCapableBeanFactory.java:1864)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.initializeBean(AbstractAutowireCapableBeanFactory.java:1813)
	... 16 more
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:373)
	at org.postgresql.core.ConnectionFactory.openConnection(ConnectionFactory.java:57)
	at org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:290)
	at org.postgresql.Driver.makeConnection(Driver.java:448)
	at org.postgresql.Driver.connect(Driver.java:298)
	at com.zaxxer.hikari.util.DriverDataSource.getConnection(DriverDataSource.java:144)
	at com.zaxxer.hikari.pool.PoolBase.newConnection(PoolBase.java:373)
	at com.zaxxer.hikari.pool.PoolBase.newPoolEntry(PoolBase.java:210)
	at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:488)
	at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:576)
	at com.zaxxer.hikari.pool.HikariPool.<init>(HikariPool.java:97)
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:111)
	at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection(JdbcUtils.java:65)
	... 22 more
Caused by: java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:682)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:549)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:327)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at org.postgresql.core.PGStream.createSocket(PGStream.java:261)
	at org.postgresql.core.PGStream.<init>(PGStream.java:122)
	at org.postgresql.core.v3.ConnectionFactoryImpl.tryConnect(ConnectionFactoryImpl.java:146)
	at org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:289)
	... 34 more
```

## Livraison documentaire et contrôle final

Un seul fichier documentaire est créé : `docs/architecture/Event_Publication_Contracts_Step_4A_Differential_Diagnosis_2026-10-20.md`. Les documents historiques, le code, les tests, les POM et les migrations restent intacts. Les liens locaux, les onze annexes contre les deux copies XML, les empreintes et les totaux sont contrôlés ; `git diff --cached --check` et le périmètre documentaire seul sont revus avant commit.

Les deux worktrees temporaires sont retirés par `git worktree remove` **sans force**, après copie des rapports. La dernière commande d'inventaire lancée depuis le répertoire venant d'être supprimé n'avait plus de cwd ; l'inventaire est aussitôt repris depuis le checkout principal et confirme le retrait des deux seuls worktrees créés ici. L'état `git status --porcelain=v1` du checkout principal est identique à l'inventaire initial. Les worktrees préexistants sont préservés.

Livraison autorisée : commit documentaire sur la branche cible, précédé d'un nouveau fetch/contrôle 0/0, puis push non forcé. Commandes de livraison :

```bash
git diff --cached --check
git diff --cached --stat
git diff --cached --name-only
git commit -m "docs: diagnose step 4A architecture gate differentially"
git push origin HEAD:refs/heads/feat/consumption-transaction-protocol
git rev-parse HEAD
git rev-list --left-right --count HEAD...origin/feat/consumption-transaction-protocol
git status --short --branch
```

HEAD cible initial : `3a2440326d227645da501a821ad73caad920db42` ; HEAD baseline de comparaison : `a53999cb4dc7dac5973478b5ad50243710acba87`. Le SHA documentaire final, le résultat effectif du push et l'état Git final sont donnés dans la restitution de livraison et le reçu externe `git-final.json`, sans autoréférence circulaire dans ce commit. Le checkout principal reste à `90926a5ec37d1ca841e3de017bec0d1f705d8839` avec ses travaux préexistants.

Vérification déclarée/exécutée : primaire **architecture-tests**, secondaires **NONE**, aucun franchissement inattendu ; gate global **RUN, FAIL sur les deux SHA** ; full reactor **NOT REQUIRED pour ce diagnostic, interdit et non exécuté**. Verdict différentiel **PASS DIFFÉRENTIEL** ; décision de clôture 4A laissée au responsable ; aucune correction ni suite 4B engagée.
