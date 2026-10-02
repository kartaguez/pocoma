# Audit architectural — Consumption, workers et runtimes

Date : 2026-10-02. Périmètre : checkout courant de `app/` ; aucune modification applicative.

## A. Baseline et méthode

- Branche : `v2-make-it-pull` ; HEAD : `1446628aafc1b3ca65f08b0039bc957081afbc6d`.
- Suivi : `origin/v2-make-it-pull`, divergence locale `0 ahead / 0 behind` au moment de l'audit. Aucun fetch réseau n'a été fait : l'état du serveur distant n'est pas établi.
- Working tree initial : 47 entrées modifiées, supprimées ou non suivies, principalement sous `app/` et `docs/steps/current/WRITE_ADMISSION/Step_Plan.md`. Ces changements préexistants, notamment les migrations V24/V14 et la contraction de `RecordedCommand`, sont inclus dans l'observation du checkout ; ils ne sont pas attribués au présent audit.
- Source du graphe : les `pom.xml` déclarés dans `app/pom.xml` ; 47 modules enfants (et un POM agrégateur), 198 arcs directs intermodules, scopes de test inclus. La documentation historique a servi à identifier les invariants, puis les classes, adapters et tests du checkout ont été inspectés pour valider les conclusions.
- Vérification déclarée : documentation seule ; slices applicatifs **aucun** ; impact de production interdit : tous les modules `app/` ; gate d'architecture **non requis** ; reactor complet **non requis** ; base de données **aucune**. Les étapes de migration futures devront déclarer leurs propres slices selon `docs/testing/Reactor_Verification_Policy.md`.

## B. Lecture du graphe actuel

Le graphe détaillé et la matrice exhaustive figurent en annexe. Il n'y a pas de cycle Maven direct dans le graphe déclaré. Les modules à 1–3 classes (`authentication-contracts`, `binding-pot-command-spring`, `infra-projection-json-schema`, `locator-consumption-event`, `locator-consumption-latest-known-version`) ne sont pas tous superflus : une petite taille ne prouve rien sans examiner la dépendance interdite qu'ils arrêtent.

Anomalies vérifiées :

1. `orchestrator-consumption → engine-projection-task` existe uniquement parce que `ProjectionTaskConsumptionOrchestrator` habite l'orchestrateur générique. Le déplacer côté Task supprime la connaissance d'une famille dans le noyau d'orchestration.
2. `engine-read-projection` contient deux capacités indépendantes, `read/binding` et `read/projection` (LatestKnownVersion et reconstruction historique Pot). Les consumers Binding et LKV partagent ainsi un module sans invariant commun.
3. `engine-core` porte à la fois `TransactionRunner`, les snapshots Pot, `RecordedEvent` et `WorkerSegment`. Il protège encore une direction framework-free, mais son nom masque plusieurs contrats. Une scission immédiate ajouterait surtout des modules ; stabiliser les packages et mesurer les dépendances d'abord.
4. Les runtimes ont des choix de correction dans la configuration : `PocomaProjectionMaterializationPolicy` est dans `runtime-event-consumption-worker`, et les sélections de producteurs et types sont dans le runtime Task. Ce sont des règles de traitement, pas seulement du wiring.
5. `infra-persistence-jpa` est un agrégat de 65 classes de production qui implémente Command, Event, Binding, Consumption et projections. Il est traversé par plusieurs slices et dépend même de `locator-consumption-command`. C'est le point de couplage physique le plus cher ; le diviser avant d'avoir stabilisé les ports ferait déplacer des transactions critiques sans bénéfice prouvé.
6. `JdbcProjectionTaskStoreAdapter.findCandidates` interroge `consumption_slots` pour exclure les tâches déjà terminées. Ce SQL est un filtre de découverte, pas une preuve d'autorité ; l'acquisition reste indispensable. Le contrat `ProjectionTaskStorePort` mélange néanmoins `ensure` (écriture) et `findCandidates` (discovery).

Classes de dépendances : `engine-consumption → domain-consumption/engine-core` et `runtime-* → supra/infra/engine` sont requises ; `orchestrator-consumption → engine-projection-task` et `infra-persistence-jpa → locator-consumption-command` sont induites par placement ; `runtime-web-api → supra-consumption-worker` et l'assemblage Pot dans le runtime Event paraissent historiques ou indirects et exigent une vérification de `dependency:tree` et des imports lors d'un lot dédié. Aucune dépendance d'un domaine ou engine vers Spring ou un runtime n'a été constatée dans les POM.

## C. Consumption et pipelines réels

`domain-consumption` possède `ConsumptionKey`, `ConsumableIdentity`, `ConsumerIdentity`, slot, claim, lease, outcome, failure et provenance. `engine-consumption` ajoute les use cases d'acquisition, d'exécution, de finalisation, de retry/failure et leurs wrappers `TransactionRunner`. Il est déjà proche du noyau souhaité : il ne dépend d'aucun Command, Event ou Task. Il faut conserver séparés le vocabulaire immutable (`domain-consumption`) et le protocole transactionnel (`engine-consumption`) : cette coupure interdit au domaine de dépendre des ports et transactions. `BusinessConsumptionOutcome.Rejected` transporte seulement un code, sans décision métier ; aucune extraction urgente. `FencedDurableEffect` est générique mais impose que tous ses effets soient rollbackables dans **la même** transaction que la finalisation.

Le protocole a deux formes observables. `SequentialConsumptionOrchestrator` ouvre un search, acquiert un claim, ferme le search, exécute dans `TransactionalExecuteConsumptionUseCase`, puis classe un échec après rollback dans une transaction distincte. `AcquireThenFinalizeConsumptionOrchestrator` découvre une page, acquiert, prépare hors transaction d'acquisition et appelle un finalizer atomique. Les deux implémentations ne sont pas interchangeables par simple renommage : le placement de l'effet durable et la durée de la préparation diffèrent. `supra-consumption-worker/ConsumptionPollingWorker` possède cadence, budgets, backoff et arrêt ; les runtimes fournissent `SmartLifecycle` et les propriétés. La séparation worker/processeur est donc déjà largement réelle, bien que les locators spécialisés assemblent parfois discovery, callback et classification.

### Command

Admission HTTP : `AsyncCommandController → SubmitRecordedCommandService → RecordedCommandPort`. Exécution : `CommandConsumptionLocator` découvre un `CommandId` via `CommandConsumptionDiscoveryPort`, crée la clé et un callback ; après acquisition `ExecuteRecordedCommandService` recharge `RecordedCommand`, observe le Binding exact, vérifie l'expiration d'authentification, décode, dispatch vers le use case Pot, écrit les événements, puis fence le Binding observé. `CommandConsumptionExecution` publie l'outcome et transforme les inputs/artifacts en provenance. `ExecuteConsumptionService` persiste provenance et fait le CAS final `status=PENDING AND current_claim_id=:claimId` dans la transaction englobante. `LostClaimException` rollbacke les effets. Le résultat public Command et son identité exacte doivent survivre à tout déplacement. Le nom `CommandProcessor` convient conceptuellement à `ExecuteRecordedCommandService` plus l'adapter `CommandConsumptionExecution`, mais leur séparation physique protège actuellement la pureté framework-free de `engine-command` et sa non-dépendance au protocole de polling.

### Event

Le runtime Event sélectionne les types de projection, applique `ProjectionMaterializationPolicy` (route EventType→ProjectionType), et utilise `JdbcProjectionMaterializationDiscoveryAdapter` pour découvrir des candidats de matérialisation. `ProjectionMaterializationConsumptionSource` est metadata-only ; `ProjectionMaterializationConsumptionService` construit une `ProjectionKey`, appelle `ProjectionTaskStorePort.ensure`, puis finalise la Consumption dans la même transaction. Ce chemin ne charge pas un `BusinessEvent` complet pour calculer des Tasks : l'outbox et ses métadonnées structurées servent de source. `EventPort`/`JpaEventPort` existent mais ne sont pas le loader de ce chemin canonique. Un `EventProcessor` générique qui imposerait le chargement d'un Event entier changerait donc la sémantique et le coût ; garder un matérialiseur spécialisé. `PocomaProjectionMaterializationPolicy` devrait quitter le runtime vers `engine-processing-event` après avoir conservé ses tests.

### ProjectionTask

`ProjectionTaskConsumptionOrchestrator` découvre via `ProjectionTaskStorePort.findCandidates`, acquiert, puis `ProjectionTaskConsumptionService` appelle `ProjectionEngineService`. Celui-ci choisit un producteur par `ProjectionProducerCatalog`, contrôle le type cible, charge l'input via `ProjectionInputLoader<I>`, appelle `ProjectionProjector<I>`, vérifie la clé retournée, puis `ProjectionValidator`. Le service finalise avec `ProjectionWritePort.publish` ou `recordFailure`, ou classe une préparation temporaire pour retry. Le dernier contrôle avant effet durable est la finalisation clôturée par claim ; la publication doit rejoindre cette transaction. Une Task découverte ne donne aucune autorité. `ProjectionTaskStorePort` mérite à terme deux interfaces nommées `ProjectionTaskDiscoveryPort` et `ProjectionTaskWriter`, sans imposer un `Loader` de la ligne Task : la `ProjectionKey` découverte est la demande sémantique et les loaders de producteur chargent les inputs autoritaires. Aucun ordre global entre versions Task ne doit être introduit.

## D. Ports Locator / Loader et fonctions pures

| Famille | Discovery/locator actuel | Rechargement autoritaire | Observation |
|---|---|---|---|
| Command | `CommandConsumptionDiscoveryPort`, `CommandConsumptionLocator` ; adapter JPA de discovery | `RecordedCommandPort.findById`, `JpaRecordedCommandAdapter` après claim | Le locator transporte déjà un callback ; la séparation logique existe, son API peut être clarifiée. |
| Event | `ProjectionMaterializationDiscoveryPort`, adapter JDBC ; `LatestKnownVersionEventDiscoveryPort` pour la capacité LKV distincte | `EventPort`/`JpaEventPort` existent, mais le chemin matérialisation consomme les métadonnées du candidat | Ne pas inventer un `EventLoader` obligatoire si le contrat de matérialisation est metadata-only. |
| ProjectionTask | `ProjectionTaskStorePort.findCandidates`, adapter JDBC | `ProjectionInputLoader<I>` charge les données autoritaires par type de projection | Séparer lecture de candidats et `ensure` ; ne pas confondre row id et `ProjectionKey`. |
| Binding | `BindingFactDiscoveryPort`, adapter JDBC et `BindingFactConsumptionLocator` | `ExternalIdentityBindingFactPort.findByEventId`, adapter JPA/JDBC | Le fact append-only est la source ; `CURRENT_BINDING` est une projection, non le loader autoritaire. |

Les SQL directs de discovery dans `JdbcProjectionMaterializationDiscoveryAdapter`, `JdbcProjectionTaskStoreAdapter` et `JdbcBindingFactDiscoveryAdapter` sont des implémentations de ports existants, non des contournements de leur caller. Le SQL qui lit `consumption_slots` depuis le store Task traverse toutefois la connaissance du protocole et doit rester un simple filtre optimisateur. Les adapters d'input `JpaAuthProjectionInputLoader`, `JpaReadPotProjectionInputLoader`, `JdbcCommandResultProjectionInputLoader` et `PotBalancesProjectionInputLoader` préparent des objets typés ; ils sont distincts de la découverte de travail. Ne créer ni `Locator<T>` ni `Loader<T>` communs : les clés, le niveau d'autorité et les garanties diffèrent.

`AuthProjector`, `ReadPotProjector`, `PotBalancesProjector` et `CommandResultProjector` sont déterministes sur `(ProjectionKey, input)` et ne font ni SQL, ni transaction, ni accès Spring. `PotBalancesCalculator` est également un calcul pur ; `CalculatePotBalancesAtVersionService` et ses sources historiques sont sa préparation impure. `ProjectionValidator` vérifie type, cardinalité, unicité et schéma via `JsonSchemaValidator` injecté : quasi pur si cet adapter est déterministe, mais il n'est pas strictement une fonction sans dépendance ; `NetworkntJsonSchemaValidator` reste en infrastructure. Le nom demandé `ProjectionTaskValidator` serait trompeur pour cette classe : elle valide la projection produite, non la Task. Un validateur structurel de Task séparé ne se justifie que si des règles communes réelles apparaissent.

## E. Binding et runtimes

Binding couvre trois choses distinctes : autorité WRITE (`ExternalIdentityBindingPort`, stream, occurrence, facts append-only), source de consommation (`BindingFactDiscoveryPort` puis `ExternalIdentityBindingFactPort.findByEventId`) et projection READ (`CurrentBindingProjectionPort`). `JpaExternalIdentityBindingAdapter` verrouille le stream par `(issuer,subject)`, calcule la révision contiguë, réserve l'occurrence, met à jour l'autorité et append le fact dans une transaction `MANDATORY`. La commande observe `(user, bindingId, revision)` puis `fenceExact` avant le CAS Consumption. `JdbcCurrentBindingAdapter` n'applique qu'une révision strictement supérieure, reconnaît duplicata ou stale et rejette une charge divergente à révision égale. Il n'est pas raisonnable de déplacer l'autorité Binding dans `engine-consumption` ni de traiter `CURRENT_BINDING` comme source autoritaire.

Les six runtimes actuels correspondent à six processus/points de composition, pas à trois : WEB, COMMAND, EVENT, PROJECTION, BINDING et LKV. Garder ces six frontières de déploiement. Dans les runtimes worker, **STAY** : applications Spring, beans, datasource/transactions, propriétés, scheduling, lifecycle, métriques. **MOVE OUT** : `PocomaProjectionMaterializationPolicy` et les décisions de sélection qui définissent le comportement canonique ; les catalogues de producteur peuvent être construits au runtime mais les définitions et projectors restent hors runtime. **MERGE** : les petits locators Event, Binding et LKV dans leurs runtimes respectifs, après extraction éventuelle de leurs règles non techniques vers les engines propriétaires. **DELETE** : aucun runtime entier. Le `MeteredAdvanceLatestKnownVersionUseCase` reste un décorateur de métrique du runtime LKV.

## F. Architecture cible proposée

La cible **ne crée aucun module pour chaque boîte**. Les six runtimes sont gardés ; les trois locators à un seul consumer sont absorbés par ces runtimes, tandis que le locator Command reste partagé actuellement avec WEB et l'infrastructure, ce qui exige un lot séparé. `engine-read-projection` est scindé en `engine-binding-read` et `engine-lkv-read` ; `HistoricalPotSnapshotSource` va dans `engine-pot-read` si l'analyse des imports du lot confirme ce sens. `engine-projection-balance` fusionne dans `engine-projection-pot`, en gardant des packages et tests distincts. `authentication-contracts` fusionne dans `domain-user-identity` : son unique `AuthenticatedExternalPrincipal` est un contrat provider-neutral de l'identité et aucun framework n'est ajouté au domaine.

```text
domain-consumption -> engine-consumption -> orchestrator-consumption -> supra-consumption-worker
                                ^                      ^                         ^
                                |                      |                         |
                   Command execution adapter     Task/Event adapters       six runtimes
                   (locator-command)             in owning modules       + Spring/SQL

domain-pot, domain-user-identity, domain-projection
      -> engine-command / engine-processing-event / engine-projection-task
      -> projection producers (Pot, Command Result) / binding-read / lkv-read
      <- infrastructure adapters implementing their ports
```

Flèches `A -> B` : A dépend de B. Dans la branche centrale du schéma, les flèches verticales représentent la composition et non une dépendance de source Java. Dépendances autorisées : domaines vers JDK et autres domaines explicitement nécessaires ; engines vers domaines/ports purs ; orchestrateur générique vers `engine-consumption` seulement ; supra worker vers orchestrateur ; infrastructure vers ports propriétaires ; runtime vers les capacités qu'il compose. Interdites : domaine/engine vers Spring, JDBC, supra ou runtime ; orchestrateur générique vers Task/Command/Event ; discovery considéré comme chargement autoritaire ; publication de projection hors transaction fenced ; runtime comme propriétaire d'une policy métier. Les ports principaux sont `ConsumptionLifecyclePersistencePort`, `ConsumptionProvenancePersistencePort`, `RecordedCommandPort`, `CommandConsumptionDiscoveryPort`, `ProjectionMaterializationDiscoveryPort`, `ProjectionTaskStorePort` (à scinder), `ProjectionInputLoader<I>`, `ExternalIdentityBindingFactPort`, `BindingFactDiscoveryPort` et `ProjectionWritePort`.

L'hypothèse de trois `Processor` distincts est utile en vocabulaire, mais ne justifie pas trois nouveaux modules. La projection possède déjà `ProjectionEngineService` et `ProjectionTaskConsumptionService` ; Command possède `ExecuteRecordedCommandService` plus l'adaptation Consumption ; Event possède un service de matérialisation metadata-only. La symétrie de noms ne doit ni imposer un chargement Event complet ni réunir les deux modèles de finalisation.

## G. Invariants, preuves et plan

| Propriété à préserver | Preuves existantes à garder et déplacer avec le code |
|---|---|
| Identité, claim, lease, retry et fencing | `ConsumptionKeyTest`, `ConsumptionSlotTest`, `AcquireConsumptionPreconditionTest`, `ExecuteConsumptionServiceTest`, `FinalizeConsumptionServiceTest`, `TransactionalConsumptionExecutionPostgresTest`, `JpaConsumptionSlotRepositoryFencingQueryTest` — protocol/concurrency/transaction. |
| Command mutation, result et concurrence | `ExecuteRecordedCommandServiceTest`, `CommandConsumptionExecutionTest`, `CommandConsumptionMultiWorkerPostgresTest`, `CommandConsumptionRuntimePostgresTest`, `CommandCompletionE2EPostgresTest`, `CommandResultProjectionChainPostgresTest` — domain/concurrency/runtime/compatibility. |
| Event→Task et retry | `ProjectionMaterializationPolicyTest`, `ProjectionMaterializationConsumptionServiceTest`, `ProjectionMaterializationConsumptionPostgresTest`, `DurableEventToProjectionPostgresTest` — domain/transaction/projection. |
| Projection exacte et finalisation | `ProjectionEngineServiceTest`, `ProjectionTaskConsumptionServiceTest`, `JdbcProjectionTaskStoreAdapterPostgresTest`, `CanonicalProjectionTaskRuntimePostgresTest`, `ProjectionValidatorTest` — projection/transaction/runtime. |
| Binding revisions, bootstrap, late commit | `JpaExternalIdentityBindingLifecycleAdapterPostgresTest`, `CurrentBindingPersistencePostgresTest`, `BindingRuntimePostgresTest`, `Wa67BindingArchitectureTest` — concurrency/transaction/runtime. |
| Dépendances interdites | `HexagonalArchitectureTest`, tests structuraux de `architecture-tests` — architecture/dependency. |

La migration incrémentale proposée :

| Lot | Déplacement et dépendances | Preuve de clôture et risque |
|---|---|---|
| 1. Contrats et graphe | Fusionner `AuthenticatedExternalPrincipal` dans `domain-user-identity`; supprimer `authentication-contracts` et réorienter ses consumers. | WEB + COMMAND et `architecture-tests` selon policy ; risque de couplage AuthN/identité. Compiler après le lot. |
| 2. Orchestration générique | Déplacer `ProjectionTaskConsumptionOrchestrator` et son test vers `engine-projection-task`; supprimer l'arc `orchestrator-consumption → engine-projection-task`. | PROJECTION + architecture gate ; risque de découverte/pagination et retour `LOST_CLAIM`. |
| 3. Producteurs | Fusionner `engine-projection-balance` dans `engine-projection-pot` sans toucher aux algorithmes ; conserver loaders/projectors et tests. | PROJECTION + architecture gate ; risque de calcul exact des balances. |
| 4. Event | Déplacer `PocomaProjectionMaterializationPolicy` hors runtime, puis fusionner `locator-consumption-event` dans le runtime Event en gardant le service et les tests. | EVENT + PROJECTION si contrat Task affecté, architecture gate ; risque de routes historiques et idempotence Task. |
| 5. Binding | Fusionner `locator-consumption-binding` dans runtime Binding sans changer ordre lock/append, relecture du fact ou transaction ; séparer `read/binding` du module mixte. | BINDING + COMMAND si le contrat Binding change, architecture gate ; risque revision/late commit/bootstrap. |
| 6. LKV | Fusionner `locator-consumption-latest-known-version` dans runtime LKV ; extraire `read/projection` vers `engine-lkv-read`, et la source historique Pot vers `engine-pot-read` après contrôle des imports. | LKV + PROJECTION/WEB seulement si leurs contrats changent, architecture gate ; risque avance monotone et exactitude historique. |
| 7. Ports Task et nettoyage | Scinder `ProjectionTaskStorePort` en discovery et écriture, sans changer SQL ni schéma ; vérifier et retirer les dépendances transverses réellement inutilisées. | EVENT + PROJECTION, architecture gate, reactor complet seulement si restructuration large à la clôture de Wave ; risque de confondre filtre SQL et autorité. |

Chaque lot doit compiler, déplacer ses tests avec sa responsabilité et déclarer son scope **avant** modification. Les lots 1–7 modifient des frontières de modules et requièrent donc le gate global d'architecture à leur clôture ; un reactor complet n'est pas un réflexe par lot mais devient requis si la Wave constitue une restructuration large ou lors de l'intégration dans `main`. Aucune migration historique n'est requise par ces déplacements sans changement de schéma. Ne supprimer aucun test de preuve pour faciliter une fusion.

## H. Questions ouvertes

1. Le consommateur réel de `locator-consumption-command` côté WEB et son import depuis `infra-persistence-jpa` sont-ils intentionnels ou des résidus de configuration ? La réponse se déduit d'une analyse d'imports et d'un test de wiring au lot correspondant, pas d'une préférence de nom.
2. L'extraction de `HistoricalPotSnapshotSource` vers `engine-pot-read` est-elle compatible avec le sens des dépendances des adapters et du runtime LKV ? La cible doit être ajustée si elle crée un arc inverse.
3. Faut-il diviser `infra-persistence-jpa` par capacités ? À décider seulement après les ports stabilisés et une preuve que la scission bloque une dépendance indésirable sans fracturer la transaction unique Command/Consumption ou Binding/facts.

## Annexe — inventaire et graphe Maven

Le tableau suivant donne pour chacun des 47 modules : responsabilité observée, dépendances directes principales, consommateurs directs principaux, frontière protégée, anomalie éventuelle et transformation **unique**. Les listes complètes d'arcs suivent le tableau.

| Module | Responsabilité réelle | Dépendances principales | Consommateurs principaux | Frontière protégée | Problème éventuel | Cible proposée |
|---|---|---|---|---|---|---|
| `domain-authorization` | Permissions et traduction autorités | JDK / externe | `domain-pot-policy`, `engine-core`, `engine-command`, `engine-pot-command` (+3) | isole les permissions du Pot | traduction externe dans le domaine | KEEP |
| `domain-event` | BusinessEvent et EventType | JDK / externe | `domain-pot`, `engine-command`, `engine-processing-event`, `engine-pot-command` (+1) | événement générique sans Pot | minuscule mais partagé | KEEP |
| `domain-user-identity` | User, Binding, facts et ports autoritaires | JDK / externe | `authentication-contracts`, `engine-command`, `engine-command-result`, `engine-read-projection` (+5) | identité sans runtime | fait et autorité cohabitent | KEEP |
| `authentication-contracts` | principal externe authentifié | `domain-user-identity` | `orchestrator-command-admission`, `supra-http-write-command`, `supra-http-read-query`, `supra-authentication-spring-security` (+1) | contrat sans Spring | un seul type voisin du domaine identité | MERGE → domain-user-identity |
| `domain-pot` | agrégats et événements Pot | `domain-event` | `domain-projection-balance`, `domain-pot-policy`, `engine-core`, `engine-processing-event` (+7) | modèle Pot autonome | large mais cohérent | KEEP |
| `domain-pot-projection` | définitions AUTH/READ_POT/BALANCES | `domain-projection` | `engine-pot-read`, `engine-projection-balance`, `engine-projection-pot`, `infra-projection-json-schema` (+3) | schémas Pot sans engine | petit mais partagé par producteurs et validation | KEEP |
| `domain-projection-balance` | Balance, PotBalances, calcul pur | `domain-pot` | `engine-projection-balance`, `infra-persistence-jpa` | calcul sans infra | séparation domaine pertinente | KEEP |
| `domain-projection` | clés, artifacts, définition et validation | JDK / externe | `domain-pot-projection`, `engine-command-result`, `engine-processing-event`, `engine-projection-contracts` (+6) | contrat de projection sans infra | validateur dépend d’un port JSON | KEEP |
| `domain-pot-policy` | autorisation métier Pot | `domain-authorization`, `domain-pot` | `engine-pot-command`, `engine-pot-read`, `binding-pot-command-spring` | policy sans persistence | aucun | KEEP |
| `domain-consumption` | clé, slot, claim, lease, provenance | JDK / externe | `engine-consumption`, `engine-command`, `engine-pot-command`, `engine-projection-task` (+2) | état de protocole sans ports | aucun | KEEP |
| `engine-core` | transactions, snapshots Pot, Event, segmentation | `domain-authorization`, `domain-pot` | `engine-consumption`, `engine-processing-event`, `engine-pot-command`, `infra-tx-spring` (+3) | contrats framework-free | plusieurs responsabilités | KEEP |
| `engine-consumption` | acquisition, finalisation, échec, wrappers transactionnels | `engine-core`, `domain-consumption` | `engine-projection-task`, `infra-persistence-jpa`, `orchestrator-consumption`, `architecture-tests` | protocole sans famille consommée | aucun majeur | KEEP |
| `engine-command` | recorded command, décodeur, dispatch, exécution | `domain-user-identity`, `domain-authorization`, `domain-event`, `domain-consumption` | `engine-command-result`, `engine-pot-command`, `infra-persistence-jpa`, `orchestrator-command-admission` (+4) | Command sans worker/infra | port discovery et exécution réunis conceptuellement | KEEP |
| `engine-command-result` | projection et lecture du résultat Command | `domain-user-identity`, `engine-command`, `domain-projection`, `engine-projection-task` (+1) | `infra-persistence-jpa`, `infra-projection-json-schema`, `supra-http-read-query`, `runtime-web-api` (+2) | résultat hors runtime | dépend de Task et lecture | KEEP |
| `engine-processing-event` | discovery Event, policy matérialisation | `domain-event`, `domain-pot`, `domain-projection`, `engine-core` | `infra-persistence-jpa`, `locator-consumption-event`, `locator-consumption-latest-known-version`, `runtime-event-consumption-worker` (+1) | Event sans runtime | policy concrète encore au runtime | KEEP |
| `engine-pot-command` | use cases et codecs Command Pot | `domain-authorization`, `engine-command`, `domain-event`, `domain-consumption` (+3) | `infra-persistence-jpa`, `binding-pot-command-spring`, `architecture-tests` | Pot hors Command générique | module volumineux mais domaine métier | KEEP |
| `engine-projection-contracts` | ports de lecture/écriture projection | `domain-projection` | `engine-projection-read`, `engine-projection-task`, `infra-projection-persistence`, `architecture-tests` | ports communs sans producteur | petit mais évite read→task | KEEP |
| `engine-projection-read` | lecture exacte et invariants | `domain-projection`, `engine-projection-contracts` | `engine-command-result`, `engine-pot-read`, `runtime-web-api`, `runtime-task-consumption-worker` (+1) | requêtes hors HTTP/SQL | aucun | KEEP |
| `engine-projection-task` | Task, catalog, préparation et finalisation | `domain-projection`, `domain-consumption`, `engine-consumption`, `engine-projection-contracts` | `engine-command-result`, `engine-projection-balance`, `engine-projection-pot`, `infra-persistence-jpa` (+5) | traitement commun hors producteur | discovery et ensure même port | KEEP |
| `engine-pot-read` | interprétation et requêtes Pot READ | `domain-authorization`, `domain-pot-policy`, `domain-pot-projection`, `domain-pot` (+2) | `supra-http-read-query`, `runtime-web-api`, `runtime-task-consumption-worker`, `architecture-tests` | read Pot hors HTTP | accueillir source historique si viable | KEEP |
| `engine-projection-balance` | loader et projector BALANCES | `domain-pot`, `domain-projection-balance`, `domain-projection`, `domain-pot-projection` (+1) | `infra-persistence-jpa`, `runtime-task-consumption-worker` | projection balance sans runtime | même frontière utile que Pot producer | MERGE → engine-projection-pot |
| `engine-projection-pot` | loaders et projectors AUTH/READ_POT | `domain-pot`, `domain-projection`, `domain-pot-projection`, `engine-projection-task` | `infra-persistence-jpa`, `runtime-task-consumption-worker`, `architecture-tests` | producteurs Pot sans infra | ajouter BALANCES par package | KEEP |
| `engine-read-projection` | CURRENT_BINDING, LKV, reconstruction Pot | `domain-pot`, `domain-user-identity` | `infra-persistence-jpa`, `infra-read-persistence`, `locator-consumption-latest-known-version`, `locator-consumption-binding` (+1) | read hors runtime | capacités Binding/LKV sans lien | SPLIT → engine-binding-read + engine-lkv-read |
| `observability` | contexte de trace | JDK / externe | `infra-persistence-jpa`, `runtime-web-api` | trace sans framework | petit mais partagé | KEEP |
| `infra-tx-spring` | TransactionRunner Spring | `engine-core` | `infra-persistence-jpa`, `runtime-web-api`, `runtime-event-consumption-worker`, `runtime-latest-known-version-consumption-worker` (+3) | Spring hors engine | aucun | KEEP |
| `infra-persistence-jpa` | adapters primaire JPA/JDBC et migrations | `domain-user-identity`, `orchestrator-command-admission`, `domain-authorization`, `engine-command` (+15) | `runtime-web-api`, `runtime-event-consumption-worker`, `runtime-latest-known-version-consumption-worker`, `runtime-binding-consumption-worker` (+3) | SQL primaire hors engines | 65 classes, dépend d’un locator | KEEP |
| `infra-projection-persistence` | store exact projection JDBC | `engine-projection-contracts` | `infra-read-persistence`, `runtime-web-api`, `runtime-task-consumption-worker`, `architecture-tests` | persistance projection hors engine | aucun | KEEP |
| `infra-read-persistence` | read store, binding courant, LKV, migration | `infra-projection-persistence`, `engine-read-projection` | `runtime-web-api`, `runtime-latest-known-version-consumption-worker`, `runtime-binding-consumption-worker`, `runtime-task-consumption-worker` (+1) | store READ distinct du primaire | plusieurs stores dans même artifact | KEEP |
| `infra-projection-json-schema` | validator JSON Schema Networknt | `domain-projection`, `domain-pot-projection`, `engine-command-result` | `runtime-web-api`, `runtime-task-consumption-worker` | librairie tierce hors domaine | un seul adapter mais frontière utile | KEEP |
| `orchestrator-consumption` | pull générique, budgets, finalize | `engine-consumption`, `engine-projection-task` | `supra-consumption-worker`, `locator-consumption-event`, `locator-consumption-latest-known-version`, `locator-consumption-binding` (+2) | orchestration hors polling | dépend de Task par une classe | KEEP (déplacer la classe Task) |
| `orchestrator-command-admission` | capture et insert Command admis | `authentication-contracts`, `domain-user-identity`, `engine-command`, `engine-core` | `infra-persistence-jpa`, `supra-http-write-command`, `supra-http-read-query`, `supra-authentication-spring-security` (+2) | admission hors HTTP | aucun | KEEP |
| `supra-consumption-worker` | polling, cadence, backoff, attente | `orchestrator-consumption` | `runtime-web-api`, `runtime-event-consumption-worker`, `runtime-latest-known-version-consumption-worker`, `runtime-binding-consumption-worker` (+2) | loop hors engine | aucun | KEEP |
| `locator-consumption-event` | source metadata Event et finaliseur Task | `orchestrator-consumption`, `engine-processing-event`, `engine-projection-task` | `runtime-event-consumption-worker` | adapter Event vers protocole | un seul runtime consommateur | MERGE → runtime-event-consumption-worker |
| `locator-consumption-latest-known-version` | locator et classification LKV | `orchestrator-consumption`, `engine-processing-event`, `engine-read-projection` | `runtime-latest-known-version-consumption-worker`, `architecture-tests` | adapter LKV vers protocole | un seul runtime consommateur | MERGE → runtime-latest-known-version-consumption-worker |
| `locator-consumption-binding` | locator, reload fact et classification | `orchestrator-consumption`, `engine-read-projection`, `domain-user-identity` | `runtime-binding-consumption-worker`, `architecture-tests` | adapter Binding vers protocole | un seul runtime consommateur | MERGE → runtime-binding-consumption-worker |
| `locator-consumption-command` | locator, outcome, failure Command | `engine-command`, `orchestrator-consumption` | `infra-persistence-jpa`, `runtime-web-api`, `runtime-command-consumption-worker`, `architecture-tests` | Command vers protocole sans Spring | partagé avec WEB et infra | KEEP |
| `binding-pot-command-spring` | composition des handlers Pot | `engine-command`, `engine-pot-command`, `domain-pot-policy` | `runtime-web-api`, `runtime-command-consumption-worker`, `architecture-tests` | Spring hors engine Pot | un seul bean mais partagé WEB/COMMAND | KEEP |
| `supra-http-write-command` | contrôleur admission Command | `authentication-contracts`, `orchestrator-command-admission` | `runtime-web-api`, `architecture-tests` | HTTP WRITE hors engine | aucun | KEEP |
| `supra-http-read-query` | contrôleurs READ Pot, Binding, Result | `authentication-contracts`, `domain-user-identity`, `orchestrator-command-admission`, `engine-command-result` (+3) | `runtime-web-api`, `architecture-tests` | HTTP READ hors engine | plusieurs endpoints, même frontière | KEEP |
| `supra-authentication-spring-security` | JWT et adaptation principal | `authentication-contracts`, `orchestrator-command-admission` | `runtime-web-api`, `architecture-tests` | Spring Security hors contrat | aucun | KEEP |
| `runtime-web-api` | composition application HTTP | `orchestrator-command-admission`, `supra-authentication-spring-security`, `infra-tx-spring`, `infra-persistence-jpa` (+12) | `architecture-tests` | processus WEB | dépend du worker générique à vérifier | KEEP |
| `runtime-event-consumption-worker` | composition Event→Tasks | `engine-processing-event`, `engine-command`, `engine-command-result`, `domain-pot` (+6) | `architecture-tests` | processus Event | policy métier dans runtime | KEEP |
| `runtime-latest-known-version-consumption-worker` | composition consommation LKV | `locator-consumption-latest-known-version`, `supra-consumption-worker`, `infra-persistence-jpa`, `infra-read-persistence` (+1) | aucun module applicatif | processus LKV | aucun | KEEP |
| `runtime-binding-consumption-worker` | composition facts→CURRENT_BINDING | `locator-consumption-binding`, `supra-consumption-worker`, `infra-persistence-jpa`, `infra-read-persistence` (+1) | `architecture-tests` | processus Binding | aucun | KEEP |
| `runtime-task-consumption-worker` | composition producteurs et polling Task | `infra-projection-persistence`, `infra-read-persistence`, `supra-consumption-worker`, `orchestrator-consumption` (+10) | `architecture-tests` | processus projection | catalogue canonique en config | KEEP |
| `runtime-command-consumption-worker` | composition Command worker et métriques | `binding-pot-command-spring`, `locator-consumption-command`, `supra-consumption-worker`, `infra-persistence-jpa` (+1) | `architecture-tests` | processus Command | aucun | KEEP |
| `architecture-tests` | tests structuraux et E2E transverses | `domain-user-identity`, `authentication-contracts`, `domain-pot-projection`, `orchestrator-command-admission` (+27) | aucun module applicatif | preuve globale de frontières | gros graphe test seulement | KEEP |

### Adjacence complète (dépendances directes déclarées)

- `domain-authorization` → ∅
- `domain-event` → ∅
- `domain-user-identity` → ∅
- `authentication-contracts` → `domain-user-identity`
- `domain-pot` → `domain-event`
- `domain-pot-projection` → `domain-projection`
- `domain-projection-balance` → `domain-pot`
- `domain-projection` → ∅
- `domain-pot-policy` → `domain-authorization`, `domain-pot`
- `domain-consumption` → ∅
- `engine-core` → `domain-authorization`, `domain-pot`
- `engine-consumption` → `engine-core`, `domain-consumption`
- `engine-command` → `domain-user-identity`, `domain-authorization`, `domain-event`, `domain-consumption`
- `engine-command-result` → `domain-user-identity`, `engine-command`, `domain-projection`, `engine-projection-task`, `engine-projection-read`
- `engine-processing-event` → `domain-event`, `domain-pot`, `domain-projection`, `engine-core`
- `engine-pot-command` → `domain-authorization`, `engine-command`, `domain-event`, `domain-consumption`, `engine-core`, `domain-pot`, `domain-pot-policy`
- `engine-projection-contracts` → `domain-projection`
- `engine-projection-read` → `domain-projection`, `engine-projection-contracts`
- `engine-projection-task` → `domain-projection`, `domain-consumption`, `engine-consumption`, `engine-projection-contracts`
- `engine-pot-read` → `domain-authorization`, `domain-pot-policy`, `domain-pot-projection`, `domain-pot`, `domain-projection`, `engine-projection-read`
- `engine-projection-balance` → `domain-pot`, `domain-projection-balance`, `domain-projection`, `domain-pot-projection`, `engine-projection-task`
- `engine-projection-pot` → `domain-pot`, `domain-projection`, `domain-pot-projection`, `engine-projection-task`
- `engine-read-projection` → `domain-pot`, `domain-user-identity`
- `observability` → ∅
- `infra-tx-spring` → `engine-core`
- `infra-persistence-jpa` → `domain-user-identity`, `orchestrator-command-admission`, `domain-authorization`, `engine-command`, `engine-command-result`, `domain-consumption`, `engine-consumption`, `engine-processing-event`, `domain-pot`, `engine-core`, `engine-pot-command`, `engine-read-projection`, `engine-projection-balance`, `engine-projection-pot`, `engine-projection-task`, `domain-projection-balance`, `observability`, `infra-tx-spring`, `locator-consumption-command`
- `infra-projection-persistence` → `engine-projection-contracts`
- `infra-read-persistence` → `infra-projection-persistence`, `engine-read-projection`
- `infra-projection-json-schema` → `domain-projection`, `domain-pot-projection`, `engine-command-result`
- `orchestrator-consumption` → `engine-consumption`, `engine-projection-task`
- `orchestrator-command-admission` → `authentication-contracts`, `domain-user-identity`, `engine-command`, `engine-core`
- `supra-consumption-worker` → `orchestrator-consumption`
- `locator-consumption-event` → `orchestrator-consumption`, `engine-processing-event`, `engine-projection-task`
- `locator-consumption-latest-known-version` → `orchestrator-consumption`, `engine-processing-event`, `engine-read-projection`
- `locator-consumption-binding` → `orchestrator-consumption`, `engine-read-projection`, `domain-user-identity`
- `locator-consumption-command` → `engine-command`, `orchestrator-consumption`
- `binding-pot-command-spring` → `engine-command`, `engine-pot-command`, `domain-pot-policy`
- `supra-http-write-command` → `authentication-contracts`, `orchestrator-command-admission`
- `supra-http-read-query` → `authentication-contracts`, `domain-user-identity`, `orchestrator-command-admission`, `engine-command-result`, `engine-read-projection`, `engine-pot-read`, `engine-core`
- `supra-authentication-spring-security` → `authentication-contracts`, `orchestrator-command-admission`
- `runtime-web-api` → `orchestrator-command-admission`, `supra-authentication-spring-security`, `infra-tx-spring`, `infra-persistence-jpa`, `engine-command-result`, `engine-projection-read`, `infra-projection-persistence`, `infra-read-persistence`, `infra-projection-json-schema`, `supra-http-write-command`, `supra-http-read-query`, `engine-pot-read`, `observability`, `binding-pot-command-spring`, `locator-consumption-command`, `supra-consumption-worker`
- `runtime-event-consumption-worker` → `engine-processing-event`, `engine-command`, `engine-command-result`, `domain-pot`, `domain-pot-projection`, `locator-consumption-event`, `supra-consumption-worker`, `infra-persistence-jpa`, `engine-projection-task`, `infra-tx-spring`
- `runtime-latest-known-version-consumption-worker` → `locator-consumption-latest-known-version`, `supra-consumption-worker`, `infra-persistence-jpa`, `infra-read-persistence`, `infra-tx-spring`
- `runtime-binding-consumption-worker` → `locator-consumption-binding`, `supra-consumption-worker`, `infra-persistence-jpa`, `infra-read-persistence`, `infra-tx-spring`
- `runtime-task-consumption-worker` → `infra-projection-persistence`, `infra-read-persistence`, `supra-consumption-worker`, `orchestrator-consumption`, `infra-persistence-jpa`, `infra-tx-spring`, `engine-projection-task`, `engine-projection-balance`, `engine-projection-pot`, `engine-command-result`, `domain-pot-projection`, `infra-projection-json-schema`, `engine-projection-read`, `engine-pot-read`
- `runtime-command-consumption-worker` → `binding-pot-command-spring`, `locator-consumption-command`, `supra-consumption-worker`, `infra-persistence-jpa`, `infra-tx-spring`
- `architecture-tests` → `domain-user-identity`, `authentication-contracts`, `domain-pot-projection`, `orchestrator-command-admission`, `supra-authentication-spring-security`, `domain-authorization`, `domain-event`, `runtime-event-consumption-worker`, `locator-consumption-command`, `locator-consumption-latest-known-version`, `locator-consumption-binding`, `runtime-task-consumption-worker`, `binding-pot-command-spring`, `runtime-command-consumption-worker`, `runtime-binding-consumption-worker`, `domain-consumption`, `engine-consumption`, `engine-command`, `engine-pot-command`, `engine-processing-event`, `engine-projection-contracts`, `engine-projection-read`, `engine-projection-task`, `engine-projection-pot`, `engine-pot-read`, `infra-persistence-jpa`, `infra-read-persistence`, `infra-projection-persistence`, `supra-http-write-command`, `supra-http-read-query`, `runtime-web-api`

Les 198 arcs comprennent ceux de `architecture-tests` (scope de test) ; ils ne sont pas tous des dépendances de production. Aucun cycle n’a été trouvé dans cette liste. Le tableau et l’adjacence décrivent le checkout présent et peuvent différer de HEAD à cause du working tree.

### Décompte et liste cible exacte

Current module count: 47

Target module count: 43

KEEP: 41

MERGE: 5

MOVE: 0

DELETE: 0

SPLIT: 1

Modules cibles (43) : `domain-authorization`, `domain-event`, `domain-user-identity`, `domain-pot`, `domain-pot-projection`, `domain-projection-balance`, `domain-projection`, `domain-pot-policy`, `domain-consumption`, `engine-core`, `engine-consumption`, `engine-command`, `engine-command-result`, `engine-processing-event`, `engine-pot-command`, `engine-projection-contracts`, `engine-projection-read`, `engine-projection-task`, `engine-pot-read`, `engine-projection-pot`, `observability`, `infra-tx-spring`, `infra-persistence-jpa`, `infra-projection-persistence`, `infra-read-persistence`, `infra-projection-json-schema`, `orchestrator-consumption`, `orchestrator-command-admission`, `supra-consumption-worker`, `locator-consumption-command`, `binding-pot-command-spring`, `supra-http-write-command`, `supra-http-read-query`, `supra-authentication-spring-security`, `runtime-web-api`, `runtime-event-consumption-worker`, `runtime-latest-known-version-consumption-worker`, `runtime-binding-consumption-worker`, `runtime-task-consumption-worker`, `runtime-command-consumption-worker`, `architecture-tests`, `engine-binding-read`, `engine-lkv-read`.
