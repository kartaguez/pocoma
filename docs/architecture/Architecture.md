# Architecture — CURRENT

Baseline `c14751f`, 2026-10-03. Cette synthèse décrit les chaînes effectivement câblées. Le [Functional Model](../product/Functional_Model.md) donne le contrat observable ; [System Guarantees](../guarantees/System_Guarantees.md) borne les preuves. Les noms des modules ci-dessous sont ceux du HEAD, sans anticiper leur refonte.

## Command

L'admission HTTP authentifie l'ExternalIdentity, reçoit le `BindingId`, valide l'enveloppe puis persiste un `RecordedCommand` avant `202`. La Consumption Command découvre et claim ce Command. Dans l'exécution fenced, elle réobserve et fence le Binding courant, applique la mutation métier, persiste l'outcome terminal et le terminal Event (`COMMAND_APPLIED`, `COMMAND_REJECTED` ou `COMMAND_FAILED`). Une application Pot peut produire un Business Event portant la nouvelle version. Le worker **Command Result** découvre la source terminale et matérialise directement un `command_results` immuable lié au propriétaire historique ; sa chaîne n'emprunte plus Event → ProjectionTask → artifact générique. Les Business Events Pot sont consommés séparément pour programmer les projections versionnées.

Sources : [admission](../../app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/SubmitRecordedCommandService.java), [exécution](../../app/engine-command/src/main/java/com/kartaguez/pocoma/engine/command/execution/ExecuteRecordedCommandService.java), [matérialisation Result](../../app/engine-command-result/src/main/java/com/kartaguez/pocoma/engine/command/result/MaterializeCommandResultService.java), [migration V25](../../app/infra-persistence-jpa/src/main/resources/db/migration/V25__immutable_command_result.sql), [retrait des anciennes données V26](../../app/infra-persistence-jpa/src/main/resources/db/migration/V26__retire_command_result_projection_data.sql).

## Registration

L'admission HTTP persiste un `RegistrationRequest`. La Consumption Registration arbitre l'acquisition du Binding et l'initialisation du User, crée le fait `UserCreated` et, en cas d'acquisition, le fait `ExternalIdentityAttached`, puis enregistre un outcome terminal dans sa transaction d'exécution. Un refus ne crée pas de User/Binding acquis par cette tentative. Le worker **Registration Result** découvre l'outcome, vérifie sa concordance avec la Request et matérialise directement `registration_results`, sans consulter CURRENT_BINDING. Le worker Binding publie cette vue courante sur une voie indépendante : Result et CURRENT_BINDING peuvent devenir visibles dans n'importe quel ordre.

Sources : [admission](../../app/engine-registration/src/main/java/com/kartaguez/pocoma/engine/registration/AdmitRegistrationService.java), [arbitrage](../../app/engine-registration/src/main/java/com/kartaguez/pocoma/engine/registration/ExecuteRegistrationService.java), [Result](../../app/engine-registration/src/main/java/com/kartaguez/pocoma/engine/registration/MaterializeRegistrationResultService.java), [migrations V27–V29](../../app/infra-persistence-jpa/src/main/resources/db/migration/V27__registration_requests.sql), [test worker Registration](../../app/runtime-registration-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/registration/RegistrationRuntimePostgresTest.java).

## Projections Pot versionnées

Un Business Event Pot durable est découvert par Event consumption. Celle-ci détermine les projections applicables et assure les `ProjectionTask` identifiées par type, objet et version. Task consumption prépare puis publie les artefacts/version roots des projections `AUTH@V`, `READ_POT@V` et `POT_BALANCES@V` dans le store de projections. Le résultat d'une lecture exacte dépend de la clé demandée et de son état READY/FAILED/non prêt. Ni `COMMAND_RESULT` ni `CURRENT_BINDING` ne sont actuellement des `ProjectionTask` de ce pipeline.

Sources : [Event → Task](../../app/locator-consumption-event/src/main/java/com/kartaguez/pocoma/locator/consumption/event/materialization/ProjectionMaterializationConsumptionService.java), [Task consumption](../../app/engine-projection-task/src/main/java/com/kartaguez/pocoma/engine/projection/task/ProjectionTaskConsumptionService.java), [READ_POT producer](../../app/engine-projection-pot/src/main/java/com/kartaguez/pocoma/engine/projection/pot/ReadPotProjector.java), [AUTH producer](../../app/engine-projection-pot/src/main/java/com/kartaguez/pocoma/engine/projection/pot/AuthProjector.java), [balances](../../app/engine-projection-balance/src/main/java/com/kartaguez/pocoma/engine/projection/balance/PotBalancesProjector.java).

## CURRENT_BINDING et LATEST_KNOWN_VERSION

L'autorité Binding écrit des facts `ExternalIdentityAttached`/detached, avec occurrence et révision. Consumption Binding lit chaque fact durable et applique directement une ligne par ExternalIdentity à `current_external_identity_binding`. La mise à jour n'avance que vers une révision supérieure ; un même rang divergent échoue ; DETACHED conserve une tombstone. Cette voie n'est pas Event → ProjectionTask standard. `GET /me/binding` lit cette vue.

Consumption LKV lit les Events Pot et avance un repère de **dernière version connue**. Ce repère renseigne la découverte/progression ; il ne sélectionne pas la version de `GET /pots/{id}` et ne prouve pas que `AUTH@V` ou `READ_POT@V` sont prêtes. Il n'est pas une route Query/CURRENT ni une garantie de « serving ».

Sources : [Binding locator](../../app/locator-consumption-binding/src/main/java/com/kartaguez/pocoma/locator/consumption/binding/BindingFactConsumptionLocator.java), [store courant](../../app/infra-read-persistence/src/main/java/com/kartaguez/pocoma/infra/read/persistence/JdbcCurrentBindingAdapter.java), [LKV](../../app/locator-consumption-latest-known-version/src/main/java/com/kartaguez/pocoma/locator/consumption/latestknownversion/LatestKnownVersionConsumptionLocator.java), [GET Pot](../../app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/PotQueryController.java).

## Protocole Consumption et transactions

Les workers découvrent un candidat, acquièrent par CAS un claim de slot avec lease et `claimId`, exécutent l'effet métier et finalisent avec un CAS fenced. Claim et effet ne constituent pas un commit unique : l'acquisition est durable avant l'exécution ; l'effet, la provenance et la terminalisation gagnante partagent la transaction d'exécution. Un claim perdu déclenche une exception et rollback de cet effet. Les échecs classés retry peuvent être repris après délai/expiration ; takeover et restart retrouvent le travail durable. Une lease expirée ne révoque pas à elle seule un effet déjà en cours : le fencing à la finalisation tranche. Le protocole n'impose aucun temps maximal de convergence.

Sources : [acquisition](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/AcquireConsumptionService.java), [transaction d'effet](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalExecuteConsumptionUseCase.java), [finalisation](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java), [preuve PostgreSQL ciblée](../../app/infra-persistence-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/consumption/TransactionalConsumptionExecutionPostgresTest.java).

## Modules et processus

Le POM agrégateur déclare **neuf** modules `runtime-*` : `runtime-web-api`, `runtime-command-consumption-worker`, `runtime-command-result-consumption-worker`, `runtime-event-consumption-worker`, `runtime-task-consumption-worker`, `runtime-binding-consumption-worker`, `runtime-latest-known-version-consumption-worker`, `runtime-registration-consumption-worker`, `runtime-registration-result-consumption-worker`. Les domaines, engines, locators, projections et adaptateurs `infra-*` sont des modules distincts ; chaque runtime compose sa chaîne. Voir le [POM agrégateur](../../app/pom.xml).

Le contrôleur Pot actuel résout encore l'ExternalIdentity en User sur le primaire avant de lire AUTH et READ_POT. Une frontière READ pure sur cette route est donc **TARGET**, pas CURRENT.

## TARGET séparée

La refonte des trois familles de moteurs/modules et une éventuelle conversion de CURRENT_BINDING en ProjectionTask standard restent [un Step actif TARGET](../steps/current/ARCHITECTURE/Three_Engine_Families_Revision.md). Ses nombres de modules cibles, noms futurs et règles de dépendance proposées ne décrivent pas les modules exécutés aujourd'hui. [authorization-kernel-contracts](authorization-kernel-contracts.md) et [read-side-target](read-side-target.md) conservent aussi des éléments de cible à confronter au code avant toute promotion en CURRENT.
