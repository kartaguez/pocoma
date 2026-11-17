# L7-LB/S1 — Stabilisation de la nomenclature Java

Date : 15 novembre 2026. Mandat : `GO_L7_LB_S1_JAVA_NAMING`.
Branche : `feat/l7-lb-local-playground`.
SHA initial : `a138a368dd3386e4b9e714e45d5fefbd2abab2e6` ; arbre propre avant intervention.
Cadrage : [audit de stabilisation](L7_LB_Distribution_Stabilization_Audit_and_Plan.md).

## 1. Périmètre déclaré avant modification

Impact attendu : noms Java uniquement, imports et références indispensables de composition, tests et tooling vivant. Aucun changement de comportement, de frontière de module ou de dépendance Maven. Classement et correspondance nominatifs : [plan de renommage](../testing/evidence/L7-LB-S1-java-naming/rename-plan.json), produit avant toute modification applicative.

- Slice principale : **COMMAND**.
- Slices secondaires : **WEB, PROJECTION, FOUNDATION**, car les packages de domaine, ports transactionnels, infra, engines, supra et compose sont partagés par ces consommateurs.
- Composition supplémentaire : **Monolith**.
- Gate architecture globale : **REQUIRED**, adaptation des références/gardes d'isolement et dépendances interdites (§17 de la politique).
- Full reactor natif `./mvnw test` : **NOT REQUIRED**, pas de restructuration de graphe ni de clôture globale. La fermeture transitive de la gate architecture est globale : 42 modules enfants et l'agrégateur ; elle n'est pas présentée comme une preuve locale.
- Niveau : **INTEGRATION**. Nettoyage de la fermeture pour empêcher les anciens bytecodes de packages déplacés de contribuer.
- Base : tests des schémas CURRENT ; historique éventuellement exécuté transitivement par les suites canoniques, sans nouveau bootstrap d'instance ou certification de baseline.
- Impacts interdits : SQL/migrations, HTTP/JSON et COMMAND/RESULT, métier, routing/UUID, leases/fencing, secrets, instances et fichiers Bruno préexistants ; aucune réorganisation documentaire.
- Escalade : nouvelle dépendance, différence de littéral externe ou changement de logique non expliqué par la correspondance impose une nouvelle analyse avant poursuite.

Commande composée prévue depuis `app/` :

```sh
./mvnw --batch-mode --no-transfer-progress \
  -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize,infra-tx-spring,runtime-monolith,architecture-tests \
  -am clean test
```

Les quatre anchors canoniques de slices, Monolith et architecture sont explicites ; `-am` compose leur fermeture sans `-amd`, filtre de tests ni skip. Le nettoyage est motivé par les déplacements de packages, pas par une règle de rebuild systématique.

## 2. Inventaire et correspondances

Inventaire initial : **54 packages**, **82 noms simples de types/fichiers** (33 productifs, 49 de tests), **379 noms qualifiés de types** changés par nom simple et/ou package. Les 461 fichiers Java sous `app/` sont examinés ; les sources Java de preuves historiques hors `app/` restent inchangées. Aucun alias de compatibilité ni nouvelle abstraction. Le nom JPA de la sonde de test `V3IdentityProbe` devient `IdentityProbe`, sans changer sa table ni ses colonnes.

La correspondance complète des packages, symboles, chemins et noms qualifiés est dans le plan JSON. Les deux implémentations d'admission deviennent `CommandAdmission` et `IdentityRequestAdmission` pour éviter de confondre leur rôle avec les interfaces contractuelles `AdmitCommand` et `AdmitIdentityRequest` conservées. Les autres types perdent le préfixe/suffixe de génération ; `v3commandfixture` devient `infra.postgres.fixture.command` pour préserver son exclusion du scan JPA récursif de `infra.postgres.write`. Les imports et références Java sont adaptés selon ce dictionnaire, hors commentaires et littéraux par défaut, et non par substitution globale de V3.

Les noms de beans implicites suivent les méthodes renommées ; les `Qualifier`, lectures par nom, gardes de composition, réflexion et chemins Java de tests suivent exactement la même correspondance. L'inventaire des littéraux modifiés est attaché au plan. Les gardes gardent leurs ensembles de dépendances interdites : la condition de génération `.contains(".v3")` d'AdmissionArchitectureTest devient inutile après déplacement, et la vérification continue de s'appliquer à tous ses mêmes propriétaires `engine.admit.*`.

### Correspondances nominatives

Packages (54) :

| Ancien | Nouveau |
| --- | --- |
| `com.kartaguez.pocoma.architecture.v3` | `com.kartaguez.pocoma.architecture` |
| `com.kartaguez.pocoma.architecture.v3.fixture.domain` | `com.kartaguez.pocoma.architecture.fixture.domain` |
| `com.kartaguez.pocoma.architecture.v3.fixture.engine` | `com.kartaguez.pocoma.architecture.fixture.engine` |
| `com.kartaguez.pocoma.architecture.v3.fixture.engine.sample.port.persistence` | `com.kartaguez.pocoma.architecture.fixture.engine.sample.port.persistence` |
| `com.kartaguez.pocoma.architecture.v3.fixture.port` | `com.kartaguez.pocoma.architecture.fixture.port` |
| `com.kartaguez.pocoma.compose.polling.materialize.v3` | `com.kartaguez.pocoma.compose.polling.materialize` |
| `com.kartaguez.pocoma.compose.polling.materialize.v3.maintenance` | `com.kartaguez.pocoma.compose.polling.materialize.maintenance` |
| `com.kartaguez.pocoma.compose.polling.process.v3` | `com.kartaguez.pocoma.compose.polling.process` |
| `com.kartaguez.pocoma.compose.polling.process.v3.dev` | `com.kartaguez.pocoma.compose.polling.process.dev` |
| `com.kartaguez.pocoma.compose.web.read.v3` | `com.kartaguez.pocoma.compose.web.read` |
| `com.kartaguez.pocoma.compose.web.write.v3` | `com.kartaguez.pocoma.compose.web.write` |
| `com.kartaguez.pocoma.domain.consumption.v3` | `com.kartaguez.pocoma.domain.consumption` |
| `com.kartaguez.pocoma.domain.identity.v3` | `com.kartaguez.pocoma.domain.identity` |
| `com.kartaguez.pocoma.domain.pot.v3` | `com.kartaguez.pocoma.domain.pot` |
| `com.kartaguez.pocoma.domain.projection.v3` | `com.kartaguez.pocoma.domain.projection` |
| `com.kartaguez.pocoma.engine.admit.command.v3` | `com.kartaguez.pocoma.engine.admit.command` |
| `com.kartaguez.pocoma.engine.admit.identity.v3` | `com.kartaguez.pocoma.engine.admit.identity` |
| `com.kartaguez.pocoma.engine.materialize.identity.v3` | `com.kartaguez.pocoma.engine.materialize.identity` |
| `com.kartaguez.pocoma.engine.materialize.pot.v3` | `com.kartaguez.pocoma.engine.materialize.pot` |
| `com.kartaguez.pocoma.engine.materialize.results.v3` | `com.kartaguez.pocoma.engine.materialize.results` |
| `com.kartaguez.pocoma.engine.read.execution.v3` | `com.kartaguez.pocoma.engine.read.execution` |
| `com.kartaguez.pocoma.engine.read.identity.v3` | `com.kartaguez.pocoma.engine.read.identity` |
| `com.kartaguez.pocoma.engine.read.pot.v3` | `com.kartaguez.pocoma.engine.read.pot` |
| `com.kartaguez.pocoma.engine.read.results.v3` | `com.kartaguez.pocoma.engine.read.results` |
| `com.kartaguez.pocoma.engine.write.identity.v3` | `com.kartaguez.pocoma.engine.write.identity` |
| `com.kartaguez.pocoma.engine.write.pot.port.execution.v3` | `com.kartaguez.pocoma.engine.write.pot.port.execution` |
| `com.kartaguez.pocoma.engine.write.pot.port.persistence.v3` | `com.kartaguez.pocoma.engine.write.pot.port.persistence` |
| `com.kartaguez.pocoma.engine.write.pot.v3` | `com.kartaguez.pocoma.engine.write.pot` |
| `com.kartaguez.pocoma.infra.observability.micrometer.v3` | `com.kartaguez.pocoma.infra.observability.micrometer` |
| `com.kartaguez.pocoma.infra.postgres.core.v3` | `com.kartaguez.pocoma.infra.postgres.core` |
| `com.kartaguez.pocoma.infra.postgres.read.v3` | `com.kartaguez.pocoma.infra.postgres.read` |
| `com.kartaguez.pocoma.infra.postgres.read.v3.consumption` | `com.kartaguez.pocoma.infra.postgres.read.consumption` |
| `com.kartaguez.pocoma.infra.postgres.read.v3.maintenance` | `com.kartaguez.pocoma.infra.postgres.read.maintenance` |
| `com.kartaguez.pocoma.infra.postgres.source.v3` | `com.kartaguez.pocoma.infra.postgres.source` |
| `com.kartaguez.pocoma.infra.postgres.source.v3.identity` | `com.kartaguez.pocoma.infra.postgres.source.identity` |
| `com.kartaguez.pocoma.infra.postgres.write.v3` | `com.kartaguez.pocoma.infra.postgres.write` |
| `com.kartaguez.pocoma.infra.postgres.write.v3.consumption` | `com.kartaguez.pocoma.infra.postgres.write.consumption` |
| `com.kartaguez.pocoma.infra.postgres.write.v3.identity` | `com.kartaguez.pocoma.infra.postgres.write.identity` |
| `com.kartaguez.pocoma.infra.postgres.write.v3commandfixture` | `com.kartaguez.pocoma.infra.postgres.fixture.command` |
| `com.kartaguez.pocoma.infra.security.spring.v3` | `com.kartaguez.pocoma.infra.security.spring` |
| `com.kartaguez.pocoma.infra.tx.spring.v3` | `com.kartaguez.pocoma.infra.tx.spring` |
| `com.kartaguez.pocoma.infra.tx.spring.v3.fixture` | `com.kartaguez.pocoma.infra.tx.spring.fixture` |
| `com.kartaguez.pocoma.orchestrator.consumption.v3` | `com.kartaguez.pocoma.orchestrator.consumption` |
| `com.kartaguez.pocoma.port.projection.v3` | `com.kartaguez.pocoma.port.projection` |
| `com.kartaguez.pocoma.port.projection.v3.maintenance` | `com.kartaguez.pocoma.port.projection.maintenance` |
| `com.kartaguez.pocoma.port.transaction.v3` | `com.kartaguez.pocoma.port.transaction` |
| `com.kartaguez.pocoma.runtime.command.v3` | `com.kartaguez.pocoma.runtime.command` |
| `com.kartaguez.pocoma.runtime.command.v3.dev` | `com.kartaguez.pocoma.runtime.command.dev` |
| `com.kartaguez.pocoma.runtime.consumption.v3.dev` | `com.kartaguez.pocoma.runtime.consumption.dev` |
| `com.kartaguez.pocoma.runtime.task.v3` | `com.kartaguez.pocoma.runtime.task` |
| `com.kartaguez.pocoma.supra.polling.consumption.v3` | `com.kartaguez.pocoma.supra.polling.consumption` |
| `com.kartaguez.pocoma.supra.web.read.v3` | `com.kartaguez.pocoma.supra.web.read` |
| `com.kartaguez.pocoma.supra.web.read.v3.maintenance` | `com.kartaguez.pocoma.supra.web.read.maintenance` |
| `com.kartaguez.pocoma.supra.web.write.v3` | `com.kartaguez.pocoma.supra.web.write` |

Noms simples (82) ; les 379 changements de noms qualifiés et les 418 fichiers Java adaptés sont détaillés par chemin dans le plan JSON.

| Module / source | Ancien type | Nouveau type |
| --- | --- | --- |
| `architecture-tests` / test | `V3AdmissionArchitectureTest` | `AdmissionArchitectureTest` |
| `architecture-tests` / test | `V3ConsumptionEngineArchitectureTest` | `ConsumptionEngineArchitectureTest` |
| `architecture-tests` / test | `V3ConsumptionFoundationArchitectureTest` | `ConsumptionFoundationArchitectureTest` |
| `architecture-tests` / test | `V3FoundationArchitectureTest` | `FoundationArchitectureTest` |
| `architecture-tests` / test | `V3IdentityAuthorityArchitectureTest` | `IdentityAuthorityArchitectureTest` |
| `architecture-tests` / test | `V3PotExecutionArchitectureTest` | `PotExecutionArchitectureTest` |
| `architecture-tests` / test | `V3WorkerDevelopmentTools` | `WorkerDevelopmentTools` |
| `architecture-tests` / test | `V3WorkerDevelopmentToolsTest` | `WorkerDevelopmentToolsTest` |
| `compose-polling-burnable-write-postgres` / production | `V3ConsumptionDiscoveryComposition` | `ConsumptionDiscoveryComposition` |
| `compose-polling-burnable-write-postgres` / production | `V3ConsumptionLifecycleComposition` | `ConsumptionLifecycleComposition` |
| `compose-polling-burnable-write-postgres` / production | `V3IdentityRequestComposition` | `IdentityRequestComposition` |
| `compose-polling-burnable-write-postgres` / production | `V3PotCommandComposition` | `PotCommandComposition` |
| `compose-polling-burnable-write-postgres` / production | `V3FixturePotActorAuthority` | `FixturePotActorAuthority` |
| `compose-polling-burnable-write-postgres` / test | `V3ConsumptionWorkerTest` | `ConsumptionWorkerTest` |
| `compose-polling-nonburnable-materialize-postgres` / production | `V3ConsumptionDiscoveryComposition` | `ConsumptionDiscoveryComposition` |
| `compose-polling-nonburnable-materialize-postgres` / production | `V3ConsumptionLifecycleComposition` | `ConsumptionLifecycleComposition` |
| `compose-polling-nonburnable-materialize-postgres` / production | `V3ReadParticipants` | `ReadParticipants` |
| `compose-web-read-postgres` / production | `V3ReadFacadeComposition` | `ReadFacadeComposition` |
| `compose-web-read-postgres` / production | `V3ReadHttpConfiguration` | `ReadHttpConfiguration` |
| `compose-web-read-postgres` / production | `V3StageHandoffController` | `StageHandoffController` |
| `compose-web-write-postgres` / production | `V3AdmissionHttpConfiguration` | `AdmissionHttpConfiguration` |
| `compose-web-write-postgres` / test | `V3AdmissionCanonicalizationTest` | `AdmissionCanonicalizationTest` |
| `compose-web-write-postgres` / test | `V3AdmissionInputTest` | `AdmissionInputTest` |
| `compose-web-write-postgres` / test | `V3AdmissionTransportTest` | `AdmissionTransportTest` |
| `contract-read-query` / production | `ReadLimitsV3` | `ReadLimits` |
| `contract-read-query` / production | `ReadPotV3Result` | `ReadPotResult` |
| `engine-admit-command` / production | `AdmitCommandV3` | `CommandAdmission` |
| `engine-admit-identity` / production | `AdmitIdentityRequestV3` | `IdentityRequestAdmission` |
| `engine-admit-identity` / test | `V3IdentityAdmissionInputTest` | `IdentityAdmissionInputTest` |
| `engine-materialize-results` / test | `CommandResultV3ContractTest` | `CommandResultContractTest` |
| `engine-materialize-results` / test | `IdentityRequestResultV3ContractTest` | `IdentityRequestResultContractTest` |
| `engine-read-identity` / production | `GetCurrentBindingV3` | `GetCurrentBinding` |
| `engine-read-identity` / test | `CurrentBindingV3ContractTest` | `CurrentBindingContractTest` |
| `engine-read-pot` / production | `ReadNavigationV3` | `ReadNavigation` |
| `engine-read-pot` / production | `ReadPotV3` | `ReadPot` |
| `infra-observability-micrometer` / production | `MicrometerV3ConsumptionObservation` | `MicrometerConsumptionObservation` |
| `infra-postgres-core` / production | `V3BootstrapGuard` | `BootstrapGuard` |
| `infra-postgres-write` / test | `V3AdmissionPostgresTest` | `AdmissionPostgresTest` |
| `infra-security-spring` / production | `V3HttpSecurityConfiguration` | `HttpSecurityConfiguration` |
| `infra-security-spring` / production | `V3InvitationConfiguration` | `InvitationConfiguration` |
| `infra-tx-spring` / test | `V3FoundationCompositionTest` | `FoundationCompositionTest` |
| `infra-tx-spring` / test | `V3HistoricalIntervalPostgresTest` | `HistoricalIntervalPostgresTest` |
| `infra-tx-spring` / test | `V3SchemaIsolationPostgresTest` | `SchemaIsolationPostgresTest` |
| `infra-tx-spring` / test | `V3TransactionFoundationPostgresTest` | `TransactionFoundationPostgresTest` |
| `runtime-monolith` / test | `V3AdmissionFlowPostgresTest` | `AdmissionFlowPostgresTest` |
| `runtime-monolith` / test | `V3CompositionHttpFixture` | `CompositionHttpFixture` |
| `runtime-monolith` / test | `V3IdentityRequestMonolithPostgresTest` | `IdentityRequestMonolithPostgresTest` |
| `runtime-monolith` / test | `V3InvitationFixture` | `InvitationFixture` |
| `runtime-monolith` / test | `V3InvitationHttpTest` | `InvitationHttpTest` |
| `runtime-monolith` / test | `V3InvitationReadPostgresTest` | `InvitationReadPostgresTest` |
| `runtime-monolith` / test | `V3InvitationTopologyPostgresTest` | `InvitationTopologyPostgresTest` |
| `runtime-monolith` / test | `V3MonolithHttpCompositionPostgresTest` | `MonolithHttpCompositionPostgresTest` |
| `runtime-monolith` / test | `V3WorkerMonolithPostgresTest` | `WorkerMonolithPostgresTest` |
| `runtime-web-read` / test | `V3AdmissionHttpTest` | `AdmissionHttpTest` |
| `runtime-web-read` / test | `V3HttpCompositionPostgresTest` | `HttpCompositionPostgresTest` |
| `runtime-web-read` / test | `V3InvitationStandaloneWebPostgresTest` | `InvitationStandaloneWebPostgresTest` |
| `runtime-web-read` / test | `V3LinkRetirementHttpTest` | `LinkRetirementHttpTest` |
| `runtime-web-read` / test | `V3ReadHttpContractTest` | `ReadHttpContractTest` |
| `runtime-web-read` / test | `V3ReadHttpFixture` | `ReadHttpFixture` |
| `runtime-web-read` / test | `V3StageHandoffsHttpTest` | `StageHandoffsHttpTest` |
| `runtime-worker-materialize` / test | `V3QuotaHttpFixture` | `QuotaHttpFixture` |
| `runtime-worker-materialize` / test | `V3ReadQuotaPostgresTest` | `ReadQuotaPostgresTest` |
| `runtime-worker-materialize` / test | `V3ReadReconstructionPostgresTest` | `ReadReconstructionPostgresTest` |
| `runtime-worker-process` / test | `V3IdentityPotCommandPostgresTest` | `IdentityPotCommandPostgresTest` |
| `runtime-worker-process` / test | `V3IdentityRequestCompositionPostgresTest` | `IdentityRequestCompositionPostgresTest` |
| `runtime-worker-process` / test | `V3PotCommandCompositionPostgresTest` | `PotCommandCompositionPostgresTest` |
| `runtime-worker-process` / test | `V3WorkerAwaitTest` | `WorkerAwaitTest` |
| `runtime-worker-process` / test | `V3WorkerCompositionPostgresTest` | `WorkerCompositionPostgresTest` |
| `runtime-worker-process` / test | `V3WorkerMetricsTest` | `WorkerMetricsTest` |
| `runtime-worker-process` / test | `V3WorkerPostgresTest` | `WorkerPostgresTest` |
| `runtime-worker-process` / test | `V3WorkerResourcesPostgresTest` | `WorkerResourcesPostgresTest` |
| `runtime-worker-process` / test | `V3TechnicalReadParticipant` | `TechnicalReadParticipant` |
| `supra-polling-consumption` / production | `V3ConsumptionLoop` | `ConsumptionLoop` |
| `supra-polling-consumption` / production | `V3ConsumptionWorker` | `ConsumptionWorker` |
| `supra-polling-consumption` / production | `V3DiscoveryLoop` | `DiscoveryLoop` |
| `supra-polling-consumption` / production | `V3RenewalService` | `RenewalService` |
| `supra-web-read` / production | `V3InvitationController` | `InvitationController` |
| `supra-web-read` / production | `V3ReadController` | `ReadController` |
| `supra-web-read` / production | `V3ReadDtos` | `ReadDtos` |
| `supra-web-read` / production | `V3ReadTransport` | `ReadTransport` |
| `supra-web-write` / production | `V3AdmissionController` | `AdmissionController` |
| `supra-web-write` / production | `V3AdmissionTransport` | `AdmissionTransport` |

## 3. Conservation et reports

**S2** : profils `v3-*`, clés `pocoma.v3.*`, namespaces indépendants process/materialize, noms de pools et threads, métriques/PromQL, diagnostics/logs, chemins des scripts/collections et templates Docker. Seules les références directes aux noms Java nécessaires aux tests/outils vivants sont adaptées.

**Contrats conservés** : `/api/v3` et ses Locations/filtres ; clés et formes HTTP/JSON ; `RoutingV1`, `sha256-v1`, `pocoma-consumable-v1` et UUID ; versions des codecs/HMAC/chiffrement ; provenance persistée telle que `pocoma-v3-http` ; noms de migrations V3/V31, qui désignent des versions SQL réelles. Les variables locales `v3` représentant réellement la troisième version métier restent ainsi nommées. Le test `nonApiV3EndpointsAreNotFiltered` garde sa référence à la version HTTP ; les méthodes de migration V1ThroughV31 restent nommées ainsi. L'absence de l'ancien type retiré `V3LinkProposalController` reste contrôlée comme absence historique.

**S3** : tous les fichiers SQL, schémas `v3_write`/`v3_read`, tables, colonnes, fonctions, contraintes, search_path et fixtures SQL restent inchangés. Aucune baseline n'est construite dans S1.

**Historique** : rapports, manifestes, reçus, preuves et scripts de campagnes datées restent inchangés. Ils s'exécutent sur leur ancien SHA ; leurs anciens sélecteurs ne sont pas présentés comme des commandes du nouveau checkout. Les correspondances de noms restent retrouvables dans le plan S1. Le [registre des références conservées](../testing/evidence/L7-LB-S1-java-naming/retained-v3-references.json) distingue contrats, SQL, configurations et histoire ; il ne reste aucun ancien nom qualifié Java ni chemin de package Java dans les littéraux vivants.

## 4. Exécution, preuves et résultats

Les argv, dossiers de travail, résultats et artefacts de chaque phase sont conservés dans le [registre des commandes exécutées](../testing/evidence/L7-LB-S1-java-naming/verification-commands.json).

La compilation isolée `clean test-compile` a réussi sur **43 projets (42 modules enfants + agrégateur)** en 12,754 s. Elle suit deux diagnostics conservés : une référence qualifiée Java coupée sur plusieurs lignes (corrigée), puis un classfile contenant un stub `Unresolved compilation problems` observé par `javap`. Aucune erreur de source analogue dans le checkout isolé ; les bytecodes du workspace ne constituent pas la preuve.

La gate normative composée est exécutée dans `/private/tmp/pocoma-s1/source` par le collecteur officiel :

```sh
python3 -B scripts/testing/verify.py INTEGRATION COMMAND,WEB,PROJECTION,FOUNDATION,ARCHITECTURE \
  --output /private/tmp/pocoma-s1/gate
```

Elle résout `-pl runtime-worker-process,runtime-web-write,runtime-web-read,runtime-worker-materialize,infra-tx-spring,architecture-tests,runtime-monolith -am test` et ajoute uniquement les deux propriétés de provenance documentées. L'anchor ARCHITECTURE inclut Monolith dans la registry existante. La fermeture réelle est **globale : tous les 42 modules enfants**, bien que la commande native sans `-pl` ne soit pas exécutée. La couverture atteint donc de fait l’intégralité du reactor par fermeture transitive ; sa justification est la combinaison des quatre slices réellement touchées et de la gate globale des gardes, sans exécution full supplémentaire. Aucun filtre, skip ou changement de seuil. Découverte effective : **142 classes, 1 069 invocations attendues, zéro problème de découverte**.

Le premier candidat rencontre une erreur JPA dans `PotHistoryPostgresTest#b14SharesJpaPhysicalTransactionAndRollback` : la fusion vers `infra.postgres.write` inclut la sonde `CommandProbe` dans un scan récursif qui l'excluait auparavant ; sa table de fixture n'existe volontairement pas dans ce scénario.

Correction déclarée : placer cette sonde dans `infra.postgres.fixture.command`, adapter ses références et garder le scan, le SQL et l'oracle de rollback inchangés. Aucun franchissement de slice : fixture COMMAND. Ce couplage de scan récursif est un constat architectural interne à la frontière déclarée ; aucune table de fixture n’a été ajoutée pour masquer l’erreur. Le plan candidat est conservé séparément (`initial-rename-plan.json`). Le FAIL est conservé : **691 tests / 0 failure / 1 error / 0 skip, 1 181,534 s** ; 378 invocations attendues restent NOT RUN après l’arrêt sur le module en échec (modules aval SKIPPED, distincts d’un skip JUnit).

Après correction, le feedback borné suivant passe : **2/0/0/0**, 10,799 s de Maven.

```sh
./mvnw --batch-mode --no-transfer-progress -pl infra-postgres-write -am clean test \
  '-Dtest=PotHistoryPostgresTest#b14SharesJpaPhysicalTransactionAndRollback,CommandExecutionPostgresTest#c17JpaJdbcOnePhysicalUnitAndCaughtErrorsPoisonBothOrders' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Ce filtrage est un diagnostic causal, pas une fermeture de slice. Le nettoyage de la fermeture propriétaire supprime le bytecode de l'ancien package de sonde. La même commande canonique composée via le collecteur est ensuite relancée avec `--output /private/tmp/pocoma-s1/gate-final`, nouvelle identité producteur ; les résultats précédents ne peuvent pas contribuer.

Ce second passage est **FAIL**, après perte d'accès au daemon Docker pendant Monolith : premier appel sans réponse, puis socket absent. Les huit erreurs observées concernent l'accès Docker/démarrage Testcontainers, sans assertion métier en échec. Compteur XML valide : **1 001 tests / 0 failure / 8 errors / 0 skip**, 4 056,855 s. Le collecteur refuse aussi sept identités synthétiques d'initialisation et un XML tronqué d'`InvitationTopologyPostgresTest` ; son résumé Maven de deux tests réussis ne remplace pas ce rapport invalide. **75 identités attendues n'ont pas d'exécution fraîche valide** ; la gate architecture n'est pas exécutée. [Reçu FAIL conservé](../testing/evidence/L7-LB-S1-java-naming/corrected-gate-result.json) et archive brute inchangée ; aucun résultat manquant n'est converti en PASS.

L'utilisateur indique avoir relancé Docker. Après vérification en lecture seule de sa disponibilité, la même gate est réexécutée avec `--output /private/tmp/pocoma-s1/gate-restored`. Cette nouvelle exécution est justifiée par le changement d'environnement explicite ; aucune modification de source, de sélection, de délai ou d'oracle entre les deux passages. Résultat final en cours.

Contrôles ciblés hors Maven exécutés depuis le checkout principal :

```sh
python3 -B -m unittest discover -s scripts/testing -p test_verification.py
python3 -B -m unittest discover -s scripts/testing -p test_read_campaigns.py
python3 -B -m unittest discover -s scripts/local -p test_bruno_qualification.py
```

Résultats : **36 + 10 + 10 = 56 tests PASS**, zéro failure/error/skip. Leurs messages FAIL/INCONCLUSIVE simulés appartiennent aux contrôles négatifs du collecteur, pas à un verdict applicatif. Aucun parcours Bruno ni campagne d'installation n'est exécuté.

Commande statique exécutée : `python3 docs/testing/evidence/L7-LB-S1-java-naming/verify_preservation.py`. Aucun lien Javadoc vers un ancien nom Java V3 n’a été trouvé.

[Contrôle de préservation](../testing/evidence/L7-LB-S1-java-naming/preservation.json) : comparaison indépendante des flux de tokens avant/après (hors correspondance), des commentaires et de tous les littéraux non autorisés ; 461 fichiers Java, 23 886 littéraux dont 3 902 productifs, 107 changements de littéraux internes explicitement listés, 5 131 fichiers préexistants protégés et **85 SQL identiques**. Registry : noms exécutables seuls modifiés, structure, méthodes, statuts et seuils conservés. Le sous-arbre `archived_campaigns` reste exactement celui du SHA initial. Le snapshot isolé de la première gate contient encore une correspondance de noms dans ce seul sous-arbre inactif ; sa restauration dans le livrable est attestée par [comparaison](../testing/evidence/L7-LB-S1-java-naming/registry-history-preservation.json), configuration active strictement identique. Les tests de sélecteurs sont rejoués après cette restauration ; aucun input Java/SQL ni sélection de la gate n’en dépend. La gate finale utilise ensuite le registre final, identique au checkout principal. Les empreintes d'inputs applicatifs du collecteur dans le worktree isolé correspondent aux sources du checkout principal. Le script de contrôle et les reçus sont conservés dans le nouveau dossier de preuves S1.

## 5. Préservation, risques et réserves

Vérification DB observée : **CURRENT + HISTORICAL transitif**. Les tests de migration historiques sont tirés par la fermeture canonique non filtrée ; aucune campagne historique supplémentaire, aucune baseline TRUSTED et aucun schéma d’instance existante ne sont modifiés. [Inventaire de ces contributions](../testing/evidence/L7-LB-S1-java-naming/transitive-historical-migration-tests.json).

Les fichiers `.bru`, catalogues, collections, helpers et les dix fichiers préexistants du commit `221ff22c` sont inchangés. Seul `scripts/local/test_bruno_qualification.py`, hors de ces dix fichiers, adapte trois chemins de contrôleurs Java nécessaires à son oracle de couverture ; ses dix tests passent.

Aucune action agent de gestion du playground, d'arrêt/redémarrage Docker ou de demo. Le [contrôle en lecture seule](../testing/evidence/L7-LB-S1-java-naming/demo-preservation.json) retrouve avant l'incident les neuf mêmes conteneurs actifs, mêmes démarrages et compteurs de redémarrage ; les contenus et permissions des 25 fichiers privés hors runs/lock sont identiques. Le daemon devient ensuite indisponible ; après sa relance par l'utilisateur, aucun conteneur demo n'apparaît actif. L'agent ne les redémarre pas. La continuité de service de demo sur toute la mission n'est donc pas démontrée ; elle ne doit pas être déclarée PASS. Aucun secret n'est publié. La comparaison des fichiers suivis au SHA initial confirme la conservation SQL, Docker, Bruno, des ressources et documents historiques. Cette preuve n'est pas un audit exhaustif des données métier d'une instance active.

Les packages changent l'empreinte d'entrées de la distribution : une ancienne instance ne doit pas être adoptée en modifiant son marqueur ou ses fichiers privés. La qualification de provisioning/compatibilité S2/S3 et l'installation propre finale ne sont pas réalisées par S1. Les écarts fonctionnels COMMAND/RESULT restent reportés ; L7-LB reste ouvert.

## 6. Publication et verdict

Verdict et SHA final à compléter après qualification. Attendre un nouveau mandat humain avant S2.
