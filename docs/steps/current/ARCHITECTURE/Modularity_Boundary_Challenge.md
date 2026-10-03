# DEBT-MOD-01 — challenge des frontières actuelles

**Statut : analyse de frontières, sans architecture cible.** Ce document qualifie les responsabilités du HEAD `054df6e5f33a60fdea8e00dd1da2e63e800d3380`. Les décisions `BC-xx` portent sur la cohésion, l'ownership et la valeur des frontières ; elles ne prescrivent ni nouveau module Maven, ni déplacement, ni refonte. L'[audit factuel courant](Modularity_Current_State_Audit.md) reste l'inventaire exhaustif des 51 modules et de leurs 175 dépendances internes directes de production.

## A. Baseline

`git fetch origin` a précédé la lecture. Branche `v2-make-it-pull` ; HEAD local et `origin/v2-make-it-pull` : `054df6e5f33a60fdea8e00dd1da2e63e800d3380` ; divergence distante/locale `0/0` ; `git status --short` vide. Aucun commit depuis la référence `054df6e5`, aucune correction de l'audit factuel nécessitée par un changement de HEAD. Aucune modification de code ou POM pendant l'analyse.

## B. Inputs and authority

Les documents [README](../../../README.md), [Functional Model](../../../product/Functional_Model.md), [Architecture](../../../architecture/Architecture.md) et [System Guarantees](../../../guarantees/System_Guarantees.md) décrivent **CURRENT**. La [dette MOD-01](../../../debts/MODULE_TAXONOMY/Debt.md) est **OPEN / UNRATED**. L'[audit de l'état courant](Modularity_Current_State_Audit.md) est l'inventaire factuel de départ. La [révision des trois familles](Three_Engine_Families_Revision.md), la [cible READ](../../../architecture/read-side-target.md) et les [contrats d'autorisation ciblés](../../../architecture/authorization-kernel-contracts.md) sont **TARGET** ; leurs hypothèses et nombres historiques ne deviennent pas CURRENT. Les Steps et audits [completed](../../completed/) sont des preuves datées. Les dettes [WA6-01](../../../debts/BINDING_LOCK_ORDER_GUARD/Debt.md) et [WA6-02](../../../debts/SQL_APPEND_ONLY_GUARDS/Debt.md) restent distinctes : limites des guards, pas diagnostic d'un bug runtime.

## C. Challenge method

Un rôle technique différent ne suffit pas à séparer deux responsabilités ; un même nom métier ne suffit pas à les regrouper. Pour chaque groupe, l'analyse compare raisons de changer, direction de dépendance, cycle de vie, transaction, réutilisation indépendante et propriétaire du contrat. Les statuts sont : `KEEP_TOGETHER` (cohésion démontrée), `SEPARATE_CONCERNS` (raisons ou contraintes distinctes), `MOVE_OWNERSHIP` (règle placée chez un assembleur ou adapter), `BOUNDARY_USEFUL` (interdiction effectivement protégée), `BOUNDARY_WEAK` (valeur physique non démontrée), `UNRESOLVED` (preuve insuffisante). `SEPARATE_CONCERNS` ne signifie pas « créer deux POM » ; `BOUNDARY_WEAK` ne signifie pas « fusionner ».

READ qualifie l'information exposée ; Consumption est une mécanique possible de production. Les projections exactes Pot, les deux Results, CURRENT_BINDING et LKV ont des identités, cardinalités, ordres et durabilités différents. Leur production peut traverser plusieurs rôles sans créer une seule « famille READ » homogène. Les chaînes détaillées et leurs sources sont dans l'[audit](Modularity_Current_State_Audit.md#f-chaînes-dexécution-actuelles).

## D. Cohesion by capability

### Registration

La [request admise](../../../../app/engine-registration/src/main/java/com/kartaguez/pocoma/engine/registration/AdmitRegistrationService.java), l'[exécution](../../../../app/engine-registration/src/main/java/com/kartaguez/pocoma/engine/registration/ExecuteRegistrationService.java), l'outcome, la [matérialisation](../../../../app/engine-registration/src/main/java/com/kartaguez/pocoma/engine/registration/MaterializeRegistrationResultService.java) et le [GET](../../../../app/engine-registration/src/main/java/com/kartaguez/pocoma/engine/registration/GetRegistrationResultService.java) partagent requestId, owner E historique et sémantique terminale. Le GET appartient naturellement au contrat Registration : un lecteur HTTP demande le résultat de *cette* request, sans primitive READ générique capable d'en déduire l'ownership. Request, outcome et Result ont donc une cohésion fonctionnelle forte. Les trois commits (admission, exécution, Result) et les deux processus workers sont distincts : rester conceptuellement ensemble n'implique pas même transaction ni même processus. Le Result dépend de request/outcome, non de CURRENT_BINDING. La matérialisation est une règle Registration exécutée par Consumption ; le protocole de claim ne possède pas le Result. Les deux runtimes de workers sont des frontières de déploiement, sans preuve d'une frontière métier Registration/Result. Les locators, classifications et décisions invariant→fail / technique→retry se trouvent actuellement dans leurs [runtimes Registration](../../../../app/runtime-registration-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/registration/RegistrationConsumptionLocator.java) et [Result](../../../../app/runtime-registration-result-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/registrationresult/RegistrationResultConsumptionLocator.java) : ces règles survivraient à une autre composition de processus.

### Command Result

`engine-command-result` possède le Result immuable, sa cohérence avec Event/outcome/Command, son ownership E historique et le GET. Ce sont des variations d'un même contrat de résultat corrélé ; matérialisation et lecture peuvent légitimement rester proches. La dépendance Maven à `engine-command` est concrètement tirée par `CommandId` et `CommandOutcome`, mais elle importe aussi la frontière de toute la capacité Command. Un contrat minimal partagé est **plausible, pas démontré comme nécessaire** : il faut mesurer qui construit et qui consomme ces types avant toute cible. Le terminal Event est une preuve/source de production confrontée à l'outcome, pas l'identité publique du Result ; sa découverte/recharge réside dans [JdbcCommandResultSource](../../../../app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/command/JdbcCommandResultSource.java) et le [locator du runtime](../../../../app/runtime-command-result-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/result/CommandResultConsumptionLocator.java). Le retry et la classification de ce locator expriment une policy de capacité. Le store Result partage PostgreSQL avec Command, mais son insertion a lieu dans un **commit de Consumption Result ultérieur** ; elle n'a pas besoin de cohabiter physiquement avec l'adapter d'outcome pour être atomique avec l'outcome initial. Sa publication est atomique avec sa propre finalisation fenced. L'[HTTP](../../../../app/supra-http-read-query/src/main/java/com/kartaguez/pocoma/supra/http/read/query/CommandResultController.java) est une traduction d'adapter, pas propriétaire de l'ownership historique.

### Domain modules

La direction `domain-*` sans Spring/JPA est utile. Elle ne prouve pas chaque unité physique. `domain-user-identity` regroupe E, U, occurrence B, authority/stream/facts et leurs ports : continuité de Binding et arbitrage Registration/Command lui donnent une cohésion observable, tandis que les ports de résolution READ/WRITE méritent un examen distinct de leur direction d'usage. `domain-projection` regroupe identité exacte, artifact et validation de sortie ; ces types sont co-utilisés par le moteur de Task et le lecteur exact, mais identité de projection et algorithme de validation n'ont pas automatiquement la même raison de changer. `domain-pot-projection` décrit les définitions contractuelles AUTH/READ_POT/BALANCES : elles changent avec les formats et règles de ces projections, pas avec le polling. `domain-projection-balance` contient modèle et calcul pur propres à BALANCES ; sa différence fonctionnelle par rapport aux définitions Pot est réelle. `domain-consumption` définit keys, claim, lease et provenance communs aux consommateurs : sa cohésion est celle d'un protocole, non d'un domaine métier Command/Event/Binding. Aucun de ces constats n'établit une correspondance « un concept = un POM ».

## E. Ownership by architectural role

| Responsabilité observée | Owner conceptuel qualifié | Preuve et limite |
|---|---|---|
| Validation du Result et owner E historique | Capacité Command Result ou Registration | Les services de matérialisation/GET consultent Command ou request/outcome ; aucune logique générique de polling ne peut décider l'owner. |
| Claim, lease, fencing, CAS et provenance | Protocole Consumption | [Services](../../../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/) indépendants des types de Result. |
| Découverte SQL et rechargement de sources | Adapter technique, avec contrat de sélection appartenant à la capacité | `Jdbc*Discovery/Source` connaît les tables ; le choix de l'identité consommable appartient au protocole de capacité. |
| Échec invariant ou technique, choix fail/retry | Policy de la capacité, exécutée par Consumption | Les locators Result/Registration codent ces catégories ; la durée de retry peut rester paramètre de déploiement. |
| Poll interval, workerId, segments, lifecycle Spring | Runtime et polling lifecycle | Changer de processus ou de scheduler change ces paramètres sans changer le Result. |
| EventType→ProjectionType Pot | Production des projections Pot/Event | [Table explicite](../../../../app/runtime-event-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/event/consumption/PocomaProjectionMaterializationPolicy.java) : elle exprime quelles données produire, pas comment lancer Spring. |
| Choix des producteurs/locator types actifs | Mixte : catalogue contractuel de projection + sélection déployée | [Configuration Task](../../../../app/runtime-task-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/task/consumption/CanonicalProjectionTaskRuntimeConfiguration.java) associe les déclarations et vérifie la sélection ; la liste activée est configuration, l'existence/contrat d'un producteur est applicatif. |
| `E→U` requis par GET Pot | Orchestration de query entre Identity et Authorization ; source cible ouverte | Le contrôleur effectue actuellement la résolution primaire avant `ReadPotService`. La traduction HTTP ne possède pas cette décision. |

## F. Runtime ownership analysis

La question appliquée à chaque classe est : existerait-elle encore si la même capacité changeait de processus ou de technologie de composition ? Les neuf POM `runtime-*` restent des racines de déploiement utiles. Les classes ci-dessous sont qualifiées dans leur **emplacement actuel** ; « capability-owned » n'est pas une instruction de déplacement.

| Runtime | Éléments observés et ownership | Raison |
|---|---|---|
| `runtime-web-api` | Bean wiring, sécurité et configuration : `runtime-owned` ; contrôleurs Registration/Result : `adapter-owned` | Contrôleurs traduisent HTTP ; use cases et ownership sont ailleurs. |
| `runtime-command-consumption-worker` | Composition/`CommandConsumptionWorkerLifecycle` : `runtime-owned` ; locator/exécution dans `locator-consumption-command` : `capability-owned` pour la sélection et la classification, `adapter-owned` pour discovery SQL | Le runtime assemble la boucle sans définir l'issue métier. |
| `runtime-event-consumption-worker` | Composition/polling : `runtime-owned` ; `PocomaProjectionMaterializationPolicy` : `capability-owned` | Sa table EventType→ProjectionType demeure valide hors de ce processus. |
| `runtime-task-consumption-worker` | Lifecycle, propriétés, sélection de producers : `runtime-owned` ; définitions du catalogue et contrat de ProjectionKey : `capability-owned` ; validator JSON : `adapter-owned` ; Task retry : `capability-owned` avec délai configurable au runtime | Même configuration mélange assemblage et cohérence de catalogue ; le module Task porte déjà sa policy. |
| `runtime-binding-consumption-worker` | Composition/lifecycle : `runtime-owned` ; discovery/reload/apply dans `locator-consumption-binding` : `capability-owned` pour la séquence et les erreurs, `adapter-owned` pour SQL | Le choix fact→current est propre à Binding. |
| `runtime-latest-known-version-consumption-worker` | Composition/lifecycle : `runtime-owned` ; locator/max dans `locator-consumption-latest-known-version` : `capability-owned` ; wrapper métrique : `adapter-owned`/observabilité | Le maximum mémorisé ne dépend pas du scheduler. |
| `runtime-command-result-consumption-worker` | Composition/lifecycle : `runtime-owned` ; `CommandResultConsumptionLocator` identité, reload, invariant/technique et policy : `capability-owned` ; `JdbcCommandResultSource` : `adapter-owned` | Le locator et sa policy survivraient à un autre runtime ; la durée de backoff seule peut être `runtime-owned`. |
| `runtime-registration-consumption-worker` | Composition/lifecycle : `runtime-owned` ; `RegistrationConsumptionLocator` identité, issue et classification/retry : `capability-owned` ; discovery JDBC : `adapter-owned` | Rejected/Success et échec invariant sont des décisions de Registration. |
| `runtime-registration-result-consumption-worker` | Composition/lifecycle : `runtime-owned` ; `RegistrationResultConsumptionLocator` identité, reload et classification/retry : `capability-owned` ; discovery JDBC : `adapter-owned` | Le Result garde une identité distincte de l'exécution Registration. |

Les délais de retry codés en dur dans les locators et leurs catégories ont deux raisons de changer : politique d'échec de capacité et cadence opérationnelle. Leur ligne de partage exacte est à préciser avant déplacement. `runtime A→runtime B` n'existe pas dans le graphe POM de production ; la séparation physique des processus est donc réelle, indépendamment de la pureté de leur composition.

## G. Consumption boundary

`domain-consumption` fournit key/slot/claim/lease/provenance ; `engine-consumption` fournit acquire, execute, finalize et failure sous transactions délimitées ; [SequentialConsumptionOrchestrator](../../../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java) et [AcquireThenFinalizeConsumptionOrchestrator](../../../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/AcquireThenFinalizeConsumptionOrchestrator.java) sont deux formes d'un protocole commun, justifiées par des frontières de préparation différentes. Le premier exécute candidat par candidat après acquisition ; le second découvre par pages, acquiert, prépare hors transaction d'acquisition, puis finalise sous fencing. Dans les deux cas le claim est committé séparément de l'effet durable et la finalisation gagnante associe effet, provenance et CAS. Le classifieur d'erreur intervient après rollback de l'essai raté. La coexistence de ces formes dans une frontière générique est défendable ; aucune nécessité d'un `Processor<T>` universel.

Le [ProjectionTaskConsumptionOrchestrator](../../../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/ProjectionTaskConsumptionOrchestrator.java) dépend de `engine-projection-task` : c'est une spécialisation légitime du protocole mais une fuite de capacité dans le POM d'orchestration générique. `supra-consumption-worker/ConsumptionPollingWorker` porte la boucle, les budgets et l'attente sans dépendre directement de Command, Binding ou Registration ; sa coupure contre la logique métier est utile. La classification invariant/technique vient des capacités ; le protocole générique applique `Fail` ou `RetryAfter` et assure le fencing. Les cas Command, Event, Binding, Task et Results montrent des sources/identités non uniformes : Event→Task peut utiliser les métadonnées, Command/Binding rechargent l'autorité, Results confrontent leurs sources, Task a déjà une ProjectionKey. Ces asymétries sont des contraintes, pas une dette de nommage.

## H. Projection boundary

La chaîne actuelle est `ProjectionInputLoader<I> → Projector<I,O> → contrôle de ProjectionKey → ProjectionValidator<O> → publication exacte`. Le [moteur](../../../../app/engine-projection-task/src/main/java/com/kartaguez/pocoma/engine/projection/task/engine/ProjectionEngineService.java) orchestre les contrats ; `ProjectionValidator` valide **la projection produite**, non la Task. `domain-projection` garde les types/validation hors Spring/SQL ; `domain-pot-projection` fixe les définitions AUTH/READ_POT/BALANCES ; `engine-projection-pot` et `engine-projection-balance` gardent calcul et loaders abstraits hors JPA ; `engine-projection-contracts` fournit les ports exacts neutres ; `infra-projection-persistence` et `infra-projection-json-schema` réalisent respectivement SQL et JSON Schema. Ces directions sont utiles lorsque l'interdiction concerne effectivement projector→SQL/Spring ou contrat→implémentation.

`engine-projection-contracts` / `engine-projection-read` protègent aujourd'hui une coupure réelle : les ports de publication exacts ne requièrent pas la query/revalidation et la lecture exacte ne requiert pas l'orchestration Task. Mais l'utilité de **deux POM plutôt que deux packages** n'est pas entièrement démontrée : l'import interdit supplémentaire et le coût de leurs consommateurs restent à mesurer. La séparation Pot/Balances reflète des entrées et calculs différents ; leurs deux POM ne sont justifiés physiquement que si cette séparation empêche un couplage concret. `engine-projection-task` regroupe identité/scheduling, catalogue, préparation, retry et publication : la cohésion par ProjectionTask existe, la mécanique de Consumption est néanmoins un axe distinct. La spécialisation Task placée dans `orchestrator-consumption` est le point de fuite précis. `infra-projection-json-schema` protège le calcul pur de la bibliothèque Networknt ; la valeur de son POM autonome, au-delà de cette direction d'adapter, n'est pas prouvée. Les Results directs et CURRENT_BINDING n'entrent pas dans cette chaîne.

## I. READ derived boundary

| Responsabilité dans `engine-read-projection` | Identité / source | Cardinalité / mutabilité / ordre | Consommateurs / raison de changer |
|---|---|---|---|
| CURRENT_BINDING | E ; Binding facts append-only | Une ligne courante par E, révision monotone, tombstone DETACHED, même R divergent interdit | GET self et worker Binding ; change avec les règles d'occurrence/révision. |
| LKV | PotId ; Business Event Pot@V | Maximum observé, max-upsert, sans preuve de readiness | Worker LKV, store et métriques ; change avec le sens du watermark. Aucun lecteur métier aval observé. |
| Reconstruction Pot historique | PotId et version ; snapshots/authority Pot | Source historique demandée par loaders, pas un current convergent | Projections Pot ; change avec les modèles/sources Pot. |

Ces trois éléments ne partagent ni identité, ni source, ni ordre, ni garantie métier, ni lecteur. « READ dérivé » décrit seulement leur destination générale. Aucun invariant commun assez fort n'est démontré pour la **cohésion physique** d'`engine-read-projection` ; `SEPARATE_CONCERNS` est justifié conceptuellement, sans préjuger de POM futurs. `infra-read-persistence` agrège Current Binding et LKV parce que leurs tables sont READ dans la même technologie ; ce n'est pas, seul, une raison fonctionnelle de changer ensemble. La séparation exacte Pot/Results/current/watermark reste importante : Results `0..1` terminaux immuables, CURRENT_BINDING convergent mutable, LKV maximum observé non current, projections Pot immuables @V.

## J. engine-core

| Famille concrète | Classe(s) | Qualification | Proximité à préserver / challenge |
|---|---|---|---|
| Transactions | `TransactionRunner` | `cross-cutting application contract` | Port sans Spring utile à Consumption, Registration, Pot et adapter `infra-tx-spring`. |
| État Pot/Expense | `PotHeaderSnapshot`, `PotShareholdersSnapshot`, `ExpenseHeaderSnapshot`, `ExpenseSharesSnapshot`, `PotGlobalVersion` | `capability-specific` / concepts de reconstruction | Proches des lectures/version Pot ; leur cohabitation avec TransactionRunner n'est pas justifiée par un invariant. |
| Event | `RecordedEvent`, `EventTraceMetadata`, `BusinessEventEnvelope` legacy | contrats Event et trace | Le contrat durable peut être transversal ; la trace et l'envelope legacy ne changent pas nécessairement ensemble. |
| Segmentation | `WorkerSegment`, `PartitionHash`, `PotPartitioner` legacy | `runtime concern` / `utility` et contrat de discovery | La segmentation est utilisée par locators ; elle n'est pas un snapshot Pot. |
| Identité d'appel | `UserContext` | contrat Authorization/application | Cohérent avec use cases authentifiés, indépendant de transaction et de version Pot. |
| Exceptions | `BusinessEntityNotFoundException`, `VersionConflictException` | `utility` de use cases | Transversalité possible, mais pas une capacité « core » démontrée. |

Le module empêche les consommateurs de tirer Spring/JPA pour ces types, mais sa seule frontière Maven est **partielle** : vrai noyau de ports transverses et dépôt de contrats hétérogènes coexistent. Le graphe ne prouve pas que toutes ces familles doivent évoluer ensemble. Il ne prouve pas non plus qu'un découpage complet réduirait le couplage ; certains imports des snapshots/RecordedEvent restent partagés par Pot, Event, persistence et HTTP. Verdict : mélange A+B, avec `TransactionRunner` comme direction de dépendance particulièrement utile.

## K. Persistence transactional clusters

La [persistence JPA/JDBC](../../../../app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/) sert directement les neuf runtimes et implémente des ports de presque toutes les capacités. Une transaction dépend du transaction manager, de la connexion et des appels sous ce contexte, **pas de la colocation des classes dans le même POM**. La matrice ci-dessous distingue donc le besoin d'un commit commun et le simple partage de tables/technologie.

| Responsibility | Tables / repositories actuels | Port implemented / adaptation | Capacity | Transactionally coupled to | Runtime users / proximité |
|---|---|---|---|---|---|
| Pot mutation + Event outbox | tables Pot/Expense + Event/outbox, adapters `core`/`outbox` | ports de mutation Pot/Event | Command/Pot WRITE | Mutation, Events, outcome terminal et finalisation du claim gagnant lors de l'exécution : `transactional coupling` | Command ; Web admission n'est pas ce commit. |
| Command request/outcome/terminal | `recorded_commands`, `command_outcomes`, `command_terminal_events` ; `JdbcCommandOutcomeAdapter` | admission, source Command, publication outcome | Command | Outcome et terminal Event dans le même commit ; exécution métier + provenance/finalisation : `transactional coupling` | Web, Command ; discovery Result ultérieure : `shared database only`. |
| Binding authority/stream/facts | `external_identities`, binding stream/occurrences/facts ; `JpaExternalIdentityBindingAdapter` | authority, resolver, fact append/discovery | Binding, Registration, Command | Lock stream→authority, révision et fact append ; Registration winning path + User/`UserCreated` + outcome : `transactional coupling` | Web, Binding, Registration, Command ; resolver GET Pot : lecture du même PRIMARY, pas même commit. |
| Registration request/outcome/fact | `registration_requests`, `registration_outcomes`, `user_created_facts` ; adapters `registration` | admission, execution, discovery | Registration | Request admission est un commit distinct ; exécution User/Binding/fact/outcome et Consumption final : `transactional coupling` | Web, Registration ; Result ultérieur : `shared database only`. |
| Command/Registration Result | `command_results`, `registration_results` ; result stores/sources | `ensureResult`, GET, discovery | Results | Chaque insert immutable + provenance/CAS de son propre worker : `transactional coupling` ; avec outcome source antérieur : `shared database only` | Web et deux Result runtimes. |
| Consumption lifecycle | slots/claims/provenance ; `JpaConsumptionLifecycleAdapter` | acquire/execute/finalize/failure ports | Protocole Consumption | Claim séparé ; effet métier + provenance/CAS final en un commit : `transactional coupling` par consumer | Huit worker runtimes ; **même adapter** transversal, pas owner des capacités. |
| Event/Task/LKV discovery | Events, `projection_tasks`, curseurs ; adapters `processing`/`projection` | discovery/reload/Task store | Event, Task, LKV | Event→Task ensure et finalisation ; LKV max-upsert et finalisation ; Task exact publish et finalisation : `transactional coupling` dans chaque chaîne, pas entre les trois chaînes | Event, Task, LKV ; `shared repository implementation` pour certains readers d'Event. |
| Projection input loaders | snapshots Pot/Expense historiques ; adapters `projection` | loaders/source historiques | AUTH/READ_POT/BALANCES | Préparation lit l'autorité ; publication exacte est ultérieure et fenced : pas de même commit d'écriture avec Command | Task ; proximité `shared database only` / `shared JPA technology only`. |

La [publication exacte](../../../../app/infra-projection-persistence/src/main/java/com/kartaguez/pocoma/infra/projection/persistence/) réside d'ailleurs dans un **autre** POM et peut participer à la finalisation transactionnelle via le même runtime/transaction manager : contre-exemple concret à « même transaction ⇒ même module ». Inversement, Result et outcome se trouvent dans `infra-persistence-jpa` mais sont produits par deux commits séparés : « même module ⇒ même transaction » est faux. Le module protège la direction application→ports←SQL, mais son agrégation de WRITE, discovery, loaders et Results n'est pas entièrement justifiée par les clusters atomiques. La granularité physique demeure `UNRESOLVED` tant que l'on n'a pas tracé les ports et la configuration transactionnelle de chaque chemin gagnant ; aucun découpage n'est prescrit.

## L. GET Pot E→U

Le [PotQueryController](../../../../app/supra-http-read-query/src/main/java/com/kartaguez/pocoma/supra/http/read/query/PotQueryController.java) appelle `ExternalIdentityResolverPort`, dont l'[adapter JPA](../../../../app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/identity/JpaExternalIdentityResolverAdapter.java) lit l'autorité `external_identities`, avant [ReadPotService](../../../../app/engine-pot-read/src/main/java/com/kartaguez/pocoma/engine/pot/read/ReadPotService.java) et ses projections exactes `AUTH@V` / `READ_POT@V`. Le besoin « identifier le caller pour autoriser cette lecture » appartient à une orchestration applicative entre Identity (sens de E→U) et Authorization (décision d'accès Pot) ; il n'est pas intrinsèquement HTTP. L'emplacement actuel dans l'adapter HTTP est donc `MOVE_OWNERSHIP` conceptuellement. Le **choix de source** PRIMARY ou future représentation READ, et les garanties de retard/révocation qui en découleraient, restent `UNRESOLVED` et TARGET. Aucun fallback de version Pot n'est observé ; l'accès primaire a lieu *avant* le service de lecture exacte.

## M. LKV

LKV appartient aujourd'hui à un pipeline spécialisé de suivi du **maximum de version Pot observée** : Event Pot@V → locator/reload → `AdvanceLatestKnownVersionService` → max-upsert `source_version_watermarks` → finalisation fenced. Ce n'est ni la version métier courante, ni une preuve de readiness, ni une projection exacte. Le modèle de maximum et sa règle monotone forment un contrat technique autonome vis-à-vis de CURRENT_BINDING et des Task ; Consumption exécute ce contrat sans le posséder. Aucun lecteur métier/HTTP aval n'a été trouvé en `src/main` dans l'[audit factuel](Modularity_Current_State_Audit.md#h-sémantiques-read-et-lkv) ; les usages connus sont writer, tests, métriques et documentation d'exploitation. LKV pourrait donc être une primitive de suivi/observabilité, mais son besoin aval n'est pas démontré. Le runtime autonome est une frontière de déploiement réelle ; un contrat fonctionnel exigeant *ce* processus indépendant n'est pas établi. Son ownership et la valeur de cette autonomie physique restent `UNRESOLVED`. Aucune conclusion de suppression.

## N. Reason-to-change matrix

`●` = raison directe ; `○` = changement possible de configuration/adaptation sans changement de contrat ; `—` = aucune raison propre démontrée. Les lignes portent des responsabilités conceptuelles, pas des POM.

| Responsibility | Business rule | Persistence | Worker protocol | HTTP | Deployment | Lecture |
|---|---:|---:|---:|---:|---:|---|
| Registration request/execution/outcome | ● | ○ | ○ | — | — | Même protocole fonctionnel, commits distincts. |
| Registration Result materialization/GET | ● | ○ | ○ | ○ | — | Owner E et cohérence request/outcome communs ; représentation HTTP distincte. |
| Command Result materialization/GET | ● | ○ | ○ | ○ | — | Contrat immuable/owner commun ; Event source seulement côté production. |
| CURRENT_BINDING | ● | ○ | ○ | ○ | — | Ordre R/tombstone/déviation propre à Binding. |
| LKV | ● | ○ | ○ | — | ○ | Maximum observé ; motif business aval non démontré. |
| ProjectionTask scheduling/execution | ● | ○ | ● | — | ○ | Identité/catalogue et protocole de finalisation ont deux axes de changement. |
| Projection Pot loaders/projectors/validation | ● | ○ | ○ | — | — | Contrat exact et calcul pur ; SQL dans adapter. |
| Consumption core | — | ○ | ● | — | — | Claim/lease/fencing communs aux capacités. |
| Runtime policies/classification | ● | — | ● | — | ○ | Règle invariant/technique vs délai opérationnel. |
| Polling/lifecycle des runtimes | — | — | ○ | — | ● | Cadence, threads et activation déployée. |

## O. Forbidden-dependency matrix

| Boundary | Dependency it prevents today | Valuable? | Evidence / qualification |
|---|---|---|---|
| `domain-*` → Spring/JPA | Domaine vers framework et adapters | Oui | POM/domain Java purs ; ce constat vaut pour la direction, pas chaque taille de module. |
| `engine-consumption` → capacités Command/Binding/Result | Protocole générique vers règles métiers | Oui | Ses dépendances internes sont `engine-core` et `domain-consumption` ; spécialisation Task est plus haut dans `orchestrator-consumption`. |
| `supra-consumption-worker` → capacités métier | Polling lifecycle vers types métier | Oui | Dépend d'`orchestrator-consumption` ; composition métier dans runtimes. |
| Projectors Pot/Balance → SQL/Spring | Calcul de projection vers technique | Oui | Engines projectors sans JPA ; loaders sont des ports, implémentés en infra. |
| `engine-projection-contracts` / `engine-projection-read` | Lecture exacte → orchestration Task | Oui comme direction ; valeur de **deux POM** non entièrement établie | `engine-projection-read` ne dépend pas d'`engine-projection-task`. |
| HTTP adapters → moteur de query | Adapter HTTP n'est pas le use case | Oui, partiellement | `supra-http-read-query` dépend des services ; E→U primaire y ajoute une orchestration applicative. |
| Read query → WRITE/PRIMARY | Une query publique ne doit pas tirer l'autorité primaire | **Non protégée actuellement** | `PotQueryController` utilise `ExternalIdentityResolverPort`/adapter PRIMARY. Une interdiction cible ne peut être attribuée au graphe courant. |
| Application → implémentations persistence | Use cases sans JPA concret | Oui | `infra-persistence-jpa`, `infra-projection-persistence`, `infra-read-persistence` implémentent les ports. |
| Runtime A → Runtime B | Composition/déploiement indépendants | Oui comme processus | Aucun arc runtime→runtime dans l'inventaire des POM ; des modules applicatifs/infra sont partagés. |
| `engine-read-projection` Current Binding/LKV/Pot | Couplage entre trois contrats dérivés | **Non** | Même POM ; aucune interdiction entre ces responsabilités n'est protégée. |
| `infra-persistence-jpa` adapters de capacités | Import croisé SQL entre capacités | **Non démontrée** | 72 classes/18 dépendances internes, neuf runtimes clients ; seule la direction ports←adapter est claire. |
| `infra-projection-json-schema` → domaine | Domaine sans bibliothèque Networknt | Oui comme direction ; POM autonome `UNRESOLVED` | Adapter concret utilisé par Task/Web ; domaine ne dépend pas de Networknt. |

## P. Boundary decisions

### DECISION BC-01

**Subject:** Cohésion fonctionnelle Registration (request, exécution, outcome, Result, GET).<br>
**Status:** `KEEP_TOGETHER`<br>
**Evidence:** Les quatre services `engine-registration` partagent requestId et E historique ; le Result est dérivé de request/outcome et lu sans CURRENT_BINDING.<br>
**Reasoning:** Plusieurs rôles et commits existent, mais la cohérence du protocole et de l'ownership prime sur une séparation mécanique producer/query.<br>
**Constraints to preserve:** Admission distincte, User non orphelin sur conflit, outcome unique, Result `0..1` immuable, 404 opaque, indépendance du Binding courant.<br>
**What this does NOT decide:** Taille du POM, packages, nombres de processus ou colocations futures.

### DECISION BC-02

**Subject:** Deux runtimes Registration/Registration Result.<br>
**Status:** `BOUNDARY_USEFUL`<br>
**Evidence:** Deux boucles et deux identités consommables, Request puis outcome ; le Result est produit après l'exécution.<br>
**Reasoning:** La frontière de déploiement/lifecycle est observable, sans être une séparation de propriété métier.<br>
**Constraints to preserve:** Retry/restart indépendants, finalisation fenced pour chaque slot, READ Result sans dépendance à CURRENT_BINDING.<br>
**What this does NOT decide:** Déploiement définitif en deux processus.

### DECISION BC-03

**Subject:** Cohésion Command Result matérialisation et GET.<br>
**Status:** `KEEP_TOGETHER`<br>
**Evidence:** `ImmutableCommandResult`, matérialisation et GET partagent commandId, owner E et immutabilité ; seul le producer confronte terminal Event/outcome/Command.<br>
**Reasoning:** La différence entre écriture du Result et query ne démontre pas deux raisons métier de changer ; SQL, HTTP et Consumption restent des rôles externes.<br>
**Constraints to preserve:** `0..1`, immutable, owner historique, 404 opaque, indépendance du Binding courant et cohérence Event/outcome.<br>
**What this does NOT decide:** Emplacement physique des contrats CommandId/Outcome ou forme du locator.

### DECISION BC-04

**Subject:** Dépendance `engine-command-result → engine-command`.<br>
**Status:** `UNRESOLVED`<br>
**Evidence:** Les imports de `CommandId` et `CommandOutcome` imposent aujourd'hui cet arc ; le GET importe ainsi une frontière Command plus large que les deux types utilisés.<br>
**Reasoning:** Un contrat neutre minimal pourrait réduire l'arc, mais les usages et la propriété de ces types doivent être évalués avant de dire si la dépendance est indésirable.<br>
**Constraints to preserve:** Même identité Command et outcome terminal sans duplication divergente.<br>
**What this does NOT decide:** Nouveau module de contrats ou déplacement de type.

### DECISION BC-05

**Subject:** Ownership des locators et policies de Result/Registration dans les runtimes.<br>
**Status:** `MOVE_OWNERSHIP`<br>
**Evidence:** Les locators codent identité consommable, success/rejected, catégories invariant/technique et `Fail`/`RetryAfter(5s)` ; ces règles ne sont pas du Spring wiring.<br>
**Reasoning:** Le contrat de traitement survivrait à un autre processus. La cadence et les propriétés de polling restent propres au déploiement ; le SQL concret reste adapter.<br>
**Constraints to preserve:** Classification après rollback, retry idempotent et finalisation fenced ; ne pas transformer tout délai configuré en règle métier.<br>
**What this does NOT decide:** Module ou classe destinataire, ni valeur finale du backoff.

### DECISION BC-06

**Subject:** Protocole Consumption vs capacités et polling.<br>
**Status:** `BOUNDARY_USEFUL`<br>
**Evidence:** `domain-consumption`/`engine-consumption` ne dépendent pas des capacités ; `supra-consumption-worker` fournit la boucle ; runtimes injectent les locators.<br>
**Reasoning:** Cette direction protège claim/lease/fencing communs sans rendre Consumption propriétaire de Command/Binding/Results.<br>
**Constraints to preserve:** Commit de claim distinct, effet + provenance + CAS final gagnant, retry après rollback, late commit protection, multi-worker/restart.<br>
**What this does NOT decide:** Fusion des deux orchestrateurs ou `Processor<T>` universel.

### DECISION BC-07

**Subject:** Orchestration ProjectionTask spécifique dans `orchestrator-consumption`.<br>
**Status:** `MOVE_OWNERSHIP`<br>
**Evidence:** Le POM générique dépend d'`engine-projection-task` pour `ProjectionTaskConsumptionOrchestrator`.<br>
**Reasoning:** La spécialisation Task est légitime, mais elle inverse la direction souhaitée du protocole générique vers une capacité ; les deux orchestrateurs génériques peuvent rester proches.<br>
**Constraints to preserve:** Préparation hors acquisition, page discovery, finalisation fenced et idempotence exacte.<br>
**What this does NOT decide:** Nouvel emplacement physique ni suppression de cette orchestration.

### DECISION BC-08

**Subject:** Cohésion `engine-read-projection` Current Binding/LKV/reconstruction Pot.<br>
**Status:** `SEPARATE_CONCERNS`<br>
**Evidence:** E/revision/tombstone, PotId/max-upsert et snapshots Pot@V ont trois sources, ordres, cardinalités et lecteurs distincts.<br>
**Reasoning:** « READ dérivé » ne fournit pas d'invariant commun assez fort pour justifier cette cohabitation comme une seule capacité.<br>
**Constraints to preserve:** Current Binding monotone et divergence interdite ; LKV maximum observé ; Pot exact @V.<br>
**What this does NOT decide:** Nombre ou nom de POM, migration, suppression LKV.

### DECISION BC-09

**Subject:** Production pure et publication exacte Pot.<br>
**Status:** `BOUNDARY_USEFUL`<br>
**Evidence:** Projectors/validator/contrats sont distincts des adapters SQL/Spring ; `ProjectionEngineService` vérifie ProjectionKey puis la sortie.<br>
**Reasoning:** La direction calcul pur→ports←adapters protège le contrat exact et rend les algorithmes testables indépendamment du worker.<br>
**Constraints to preserve:** Input historique @V, contrôle de key, validation de sortie, root/artifact immuables, GET exact sans fallback.<br>
**What this does NOT decide:** Si chaque `engine-projection-*` requiert son POM actuel.

### DECISION BC-10

**Subject:** Granularité physique des modules projection Pot/Balance/ports/read/schema.<br>
**Status:** `BOUNDARY_WEAK`<br>
**Evidence:** Les directions pureté/adapters sont observées, mais l'import supplémentaire interdit par *chacune* des petites coupures n'est pas établi ; Pot et Balance ont des calculs distincts.<br>
**Reasoning:** Une raison de changer distincte ne prouve pas qu'un POM distinct soit nécessaire ; la valeur de chaque barrière doit être mesurée au graphe et aux consumers.<br>
**Constraints to preserve:** Aucune dépendance SQL/Spring depuis projector/domain, validation de résultat et séparation query exacte/Task.<br>
**What this does NOT decide:** Fusion, suppression ou nouveaux noms.

### DECISION BC-11

**Subject:** Cohésion `engine-core`.<br>
**Status:** `SEPARATE_CONCERNS`<br>
**Evidence:** TransactionRunner, snapshots Pot, RecordedEvent/trace, segmentation, UserContext, erreurs et legacy n'ont pas une seule raison de changer.<br>
**Reasoning:** Le module combine noyau de contrats transverses et contrats sans domicile clair ; seule la direction sans Spring/JPA est établie pour le groupe entier.<br>
**Constraints to preserve:** Contrats neutres utilisés par les capacités, notamment port transactionnel et identité Event/version.<br>
**What this does NOT decide:** Découpage complet, emplacement de chaque type ou nombre de POM.

### DECISION BC-12

**Subject:** `infra-persistence-jpa` comme frontière d'implémentation SQL.<br>
**Status:** `BOUNDARY_USEFUL`<br>
**Evidence:** Neuf runtimes la composent ; moteurs et domaines n'importent pas ses implémentations.<br>
**Reasoning:** Application→ports←SQL est une direction réelle, même si l'adapter agrège plusieurs capacités.<br>
**Constraints to preserve:** Transactions gagnantes Command, Registration, Binding, Task et Results ; locks/fencing et append-only.<br>
**What this does NOT decide:** Cohésion interne ou granulation future des adapters.

### DECISION BC-13

**Subject:** Agrégation interne de `infra-persistence-jpa`.<br>
**Status:** `SEPARATE_CONCERNS`<br>
**Evidence:** Les loaders historiques, discovery Event/LKV, stores Results et autorités WRITE ont des cycles de changement distincts ; plusieurs partagent seulement PostgreSQL/JPA.<br>
**Reasoning:** Les clusters atomiques identifiés imposent un même commit, pas nécessairement un POM unique ; le module actuel n'empêche pas les imports croisés entre adapters de capacités.<br>
**Constraints to preserve:** Même transaction physique pour effet+provenance+CAS et pour Binding/User/outcome ; ne pas inférer qu'un split est sans coût de wiring.<br>
**What this does NOT decide:** Scission, emplacement des repositories communs, granularité des modules.

### DECISION BC-14

**Subject:** Besoin E→U du GET Pot placé dans le contrôleur HTTP.<br>
**Status:** `MOVE_OWNERSHIP`<br>
**Evidence:** `PotQueryController` appelle le resolver primaire avant `ReadPotService`; la vérification AUTH@V/READ_POT@V suit.<br>
**Reasoning:** L'identité du caller pour autoriser une query est une orchestration applicative Identity/Authorization, pas une règle de transport HTTP.<br>
**Constraints to preserve:** Owner/capabilities exacts, AUTH@V puis READ_POT@V, aucune version de repli.<br>
**What this does NOT decide:** Source READ future de E→U, garanties de retard/révocation ou API cible.

### DECISION BC-15

**Subject:** Ownership et autonomie de LKV.<br>
**Status:** `UNRESOLVED`<br>
**Evidence:** Maximum observé et runtime autonome existent ; aucun usage métier aval en `src/main` n'est documenté par l'audit courant.<br>
**Reasoning:** Un contrat technique de watermark est réel, mais ni autonomie métier ni nécessité d'un processus distinct n'est prouvée par son seul fonctionnement.<br>
**Constraints to preserve:** Monotonie du max, idempotence, ordre arbitraire des Events, fencing, absence de promesse current/readiness.<br>
**What this does NOT decide:** Suppression, rapprochement avec Current Binding ou futur lecteur.

### DECISION BC-16

**Subject:** Frontière des domaines et des runtimes.<br>
**Status:** `BOUNDARY_USEFUL`<br>
**Evidence:** Domain sans Spring/JPA, neuf POM runtime sans arcs runtime→runtime, capacités composées par dépendance vers ports/adapters.<br>
**Reasoning:** Pureté des concepts et indépendance des processus sont des protections distinctes de la cohésion de chaque POM.<br>
**Constraints to preserve:** Runtime composition/lifecycle ; policies applicatives hors du rôle de composition conceptuel.<br>
**What this does NOT decide:** Nombre de domain POM ou de runtimes cibles.

## Q. Unresolved questions

1. Les imports de `CommandId`/`CommandOutcome` justifient-ils l'arc complet Result→Command, ou seulement un contrat neutre réellement réutilisé ? Mesurer tous les producteurs/consommateurs avant de décider.
2. Pour chacune des petites frontières projection, quel import interdit disparaîtrait effectivement avec le POM ? Un package et des tests offriraient-ils la même garantie ?
3. Parmi les durées de retry/classifications actuellement codées dans les locators, quelles décisions sont des invariants de capacité et lesquelles dépendent du déploiement ?
4. Pour chaque cluster persistence, quels adapters sont effectivement invoqués sous un même `PlatformTransactionManager` et une même connexion ? La matrice distingue les besoins mais ne prouve pas toutes les configurations en exécution.
5. Quel contrat fonctionnel aval, s'il existe, exige LKV et son déploiement indépendant ? Aucun lecteur métier n'est établi aujourd'hui.
6. Quelle source et quelles garanties de révocation/retard devront soutenir E→U sur le chemin GET Pot ? La [cible READ](../../../architecture/read-side-target.md) reste TARGET.
7. La [politique normative de vérification](../../../testing/Reactor_Verification_Policy.md) liste six slices alors que neuf runtimes et les ancres Registration/Results existent. L'omission est-elle volontaire ou documentaire ? Ce challenge ne la corrige pas.

## R. Preconditions for target architecture

Une proposition de modularisation cible devra partir des décisions `BC-xx`, puis vérifier pour chaque frontière envisagée (1) la dépendance interdite effectivement bloquée, (2) le propriétaire du contrat, (3) les transactions et effets fenced, (4) le cycle de déploiement et (5) la raison de changer. Elle devra résoudre les `UNRESOLVED` qui conditionnent la direction d'import, notamment Result→Command, E→U et LKV, avant de nommer des POM. Les décisions `KEEP_TOGETHER` portent sur la cohésion **conceptuelle**, pas sur l'obligation de réunir toutes les classes dans un même POM. La future preuve d'implémentation devra déclarer ses slices selon la politique normative et ses gates ; cette étape n'en franchit aucun.

**Vérification de cette étape :** audit documentaire/statique, impact production nul, slices déclarées : aucune ; aucun Maven, test ou base exécuté ; aucun franchissement de slice ; gate `architecture-tests` et reactor complet non requis.
