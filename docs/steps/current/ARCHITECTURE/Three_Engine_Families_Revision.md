# Révision ciblée — WRITE, READ et CONSUMPTION

> **Cible partiellement remplacée (2026-10-03).** Le [plan global des matérialisations READ](Read_Materialization_Gap_and_Migration_Plan.md) conserve les constats de code ci-dessous, mais abandonne la migration de CURRENT_BINDING vers Event→ProjectionTask et fait sortir COMMAND_RESULT du modèle Projection. Les lots et le graphe cible de ce document ne doivent pas être exécutés tels quels pour ces deux capacités.

Date : 2026-10-02. Complément à [l'audit initial](Consumption_Workers_Runtimes_Audit.md). Les constats et le graphe **actuels** de cet audit restent sa baseline ; la présente révision remplace **sa cible de 43 modules et son ordre de migration**. Branche `v2-make-it-pull`, HEAD de la révision `3a4ba96195db28686fdc5c3ba8dceab8157c7282`, working tree propre au départ. Aucun code applicatif n'est modifié. Les noms ci-dessous sont des destinations de migration, pas des noms déjà présents dans le reactor. Vérification déclarée pour ce document : aucun slice Maven, aucune base de données, aucun gate global et aucun reactor complet ; impact de production interdit dans `app/`.

## 1. Décision sur la taxonomie

Les trois familles suffisent aux **capacités applicatives exécutables** : WRITE applique une intention au primaire, READ répond depuis le store READ, CONSUMPTION garantit le traitement d'un consommable. Elles ne couvrent volontairement pas les modèles, contrats, projections, policies, workers, adapters et runtimes. Aucune quatrième famille d'engine n'est démontrée. `engine-core` n'est pas une capacité exécutable ; `engine-projection-*` mélange actuellement lecture, production et consommation de Task. Le préfixe `engine-` doit être retiré des simples modèles et des producteurs.

Une famille conceptuelle ne commande pas un module par use case. La cible physique proposée compte **47 modules**, comme l'état actuel, pour protéger des directions de dépendance utiles. La hausse par rapport à la cible précédente de 43 vient surtout de la séparation du READ Command Result, de l'identité Command et du chemin futur `CURRENT_BINDING`. Ce chiffre est une hypothèse de graphe à valider lot par lot.

## 2. Points du code qui décident la cible

### Command : WRITE appelé sous une transaction CONSUMPTION

`CommandConsumptionLocator` découvre un `CommandId` ; `CommandConsumptionExecution` construit le callback. Dans `TransactionalExecuteConsumptionUseCase`, `ExecuteRecordedCommandService` recharge `RecordedCommand`, observe/fence le Binding exact, décode, dispatch les use cases Pot et append les événements. Le callback publie le résultat Command, puis `ExecuteConsumptionService` persiste la provenance et effectue le CAS final sur `current_claim_id`. Les écritures rollbackables et le CAS partagent une transaction ; une perte de claim rollbacke aussi la mutation WRITE. La transaction de classification d'échec démarre après le rollback.

La frontière conceptuelle est claire : `engine-consumption-command` sélectionne/recharge/adapter la Command et utilise le protocole générique ; `engine-write-command` comprend l'exécution d'une Command ; `engine-write-pot` applique les mutations Pot. La frontière de commit appartient au protocole CONSUMPTION. Le WRITE ne doit ni terminer son propre commit ni publier un effet irréversible avant le CAS. Deux modules physiques sont justifiés ici par les dépendances : le WRITE Command reste ignorant de l'orchestrateur de polling, tandis que `engine-consumption-command` peut dépendre du WRITE. Le module `domain-command` proposé ne porte que les identités/contrats partagés indispensables pour éviter `engine-read-command-result → engine-write-command`.

### Event : deux consommations d'Event différentes

Pour Event→Task, `ProjectionMaterializationConsumptionSource` utilise seulement les métadonnées découvertes ; `ProjectionMaterializationConsumptionService` appelle `ProjectionTaskStorePort.ensure` sous finalisation fenced. Il n'y a pas de `EventLoader` nécessaire à ce chemin. `PocomaProjectionMaterializationPolicy`, actuellement dans le runtime Event, est la table canonique de routage EventType→ProjectionType ; elle doit rejoindre la capacité `engine-consumption-event`, avec ses tests, car elle décide le travail à produire. Le runtime garde le choix technique des types/segments activés.

LKV est un **autre consommateur** du même Event : `LatestKnownVersionConsumptionLocator` découvre l'ID puis `EventPort.findById` recharge l'Event autoritaire afin d'avancer le watermark dans READ. Il mérite `engine-consumption-lkv`, et son rechargement n'impose aucun loader au chemin metadata-only Event→Task. `AdvanceLatestKnownVersionService` est une action du consommateur LKV sur le read store, pas un engine READ de requête.

### ProjectionTask : production sous finalisation fenced

`ProjectionTaskConsumptionOrchestrator` découvre par `ProjectionTaskStorePort.findCandidates`, acquiert, puis `ProjectionTaskConsumptionService` demande à `ProjectionEngineService` de choisir le producteur, charger les **inputs autoritaires**, calculer, contrôler la clé, valider et préparer le résultat. `FinalizeConsumptionUseCase` publie la projection ou le failure de façon atomique avec la finalisation. Une préparation temporaire va au retry. Aucun `ProjectionTaskLoader` de convenance n'est requis : la `ProjectionKey` identifie la demande, tandis que `ProjectionInputLoader<I>` porte les données du calcul. `ProjectionTaskStorePort` doit séparer à terme discovery et `ensure` sans changer la sémantique du SQL qui filtre les slots terminés.

Le futur `engine-consumption-projection-task` contient ce pipeline commun ; `projection-pot`, `projection-command-result` et `projection-current-binding` contiennent calculs et contrats propres aux projections. Un projector reste `(clé, input autoritaire) → projection` sans SQL, Spring ni claim. `ProjectionValidator` valide la sortie et reste dans `projection-core`, son port `JsonSchemaValidator` étant implémenté en infrastructure.

### `CURRENT_BINDING` : divergence majeure avec l'hypothèse

**Chemin réel vérifié :** `JpaExternalIdentityBindingAdapter` verrouille le stream, avance la révision et append un fact Binding. `JdbcBindingFactDiscoveryAdapter` lit `external_identity_binding_facts`, joint les slots dont le consumer est `CURRENT_BINDING_PROJECTOR`, puis `BindingFactConsumptionLocator` recharge le fact par `ExternalIdentityBindingFactPort.findByEventId`. Son callback construit directement `CurrentBinding` et appelle `CurrentBindingProjectionPort.apply`. `JdbcCurrentBindingAdapter` fait un upsert monotone dans `current_external_identity_binding`. Le tout s'exécute dans `TransactionalExecuteConsumptionUseCase` et se termine par le CAS Consumption. Le runtime Binding câble ce locator et le store READ. `PocomaProjectionMaterializationPolicy` ne route actuellement que les événements Pot et terminaux Command ; aucun `CURRENT_BINDING` n'y figure. Il n'existe ni `ProjectionTask(CURRENT_BINDING)` ni projector standard sur ce chemin.

Il faut préserver la preuve actuelle : fact append-only, révision contiguë, lock ordering, bootstrap, anti-skip late commit, `sourceEventId`, rejet d'une charge divergente à révision égale, et fencing du Binding observé par Command. La cible **peut** faire de `CURRENT_BINDING` une projection standard, mais ce n'est pas un déplacement de classes. Elle requiert un événement ou une route durable vers Task, une identité `ProjectionKey` stable, un loader de fact autoritaire, un projector, une publication qui conserve l'API READ et la règle monotone de révision, ainsi qu'une stratégie de migration des données existantes. Les valeurs `CurrentBinding` et `CurrentBindingStatus` partagées entre production et requête doivent alors vivre dans `projection-contracts`, afin que `engine-read-binding` ne dépende pas du producteur ou de Consumption. `ProjectionKey.targetVersion >= 1` alors que le fact Binding accepte une révision 0 de baseline : l'encodage de cette baseline doit être décidé sans fabriquer de faux facts historiques. Il faut aussi démontrer qu'un calcul de Task ancien arrivant après un nouveau ne peut pas régresser la vue courante. Jusqu'à cette preuve et au cutover, `engine-consumption-binding` conserve l'effet direct, sans appeler cette voie « projection standard ».

## 3. Classification des treize modules `engine-*` actuels

Une seule classification principale par ligne. « Hors engine » désigne une capacité de calcul, un contrat ou un mélange à décomposer, pas une quatrième famille.

| Module actuel | Responsabilité réelle | WRITE | READ | CONSUMPTION | Hors engine | Nom/module cible et justification |
|---|---|:---:|:---:|:---:|:---:|---|
| `engine-core` | TransactionRunner, snapshots Pot, RecordedEvent, segmentation, exceptions | | | | ✓ | `application-contracts` : contrats transverses, aucun use case exécutable ; réévaluer la distribution interne. |
| `engine-consumption` | Claim, lease, retry, finalisation fenced | | | ✓ | | garder `engine-consumption`, noyau sans famille spécifique. |
| `engine-command` | RecordedCommand, décodeur, dispatch, exécution et port discovery | ✓ | | | | `engine-write-command`; déplacer `CommandId` vers `domain-command` et discovery vers la spécialisation Consumption. |
| `engine-command-result` | projector/loader, définition et requête READ | | | | ✓ | scinder en `projection-command-result` et `engine-read-command-result`; définition dans `projection-contracts`. |
| `engine-processing-event` | modèles discovery, policy Event→Task, contrat de reload LKV | | | ✓ | | `engine-consumption-event` pour matérialisation ; les contrats LKV spécifiques vont à `engine-consumption-lkv`. |
| `engine-pot-command` | use cases métier de mutation Pot | ✓ | | | | `engine-write-pot`, sans lifecycle worker. |
| `engine-projection-contracts` | ports projection READ/WRITE | | | | ✓ | `projection-contracts`, frontière neutre évitant READ→Task. |
| `engine-projection-read` | lecture exacte du store de projections | | ✓ | | | `engine-read-projection-exact`, aucune lecture primaire. |
| `engine-projection-task` | Task, catalog, préparation, finalisation | | | ✓ | | `engine-consumption-projection-task`; garder catalog et service communs, placer le simple contrat `Projection` hors du service. |
| `engine-pot-read` | requête Pot et autorisation sur projections AUTH/READ_POT | | ✓ | | | `engine-read-pot`, conserver les ports exacts READ. |
| `engine-projection-balance` | loader, calculateur d'input et projector BALANCES | | | | ✓ | fusion dans `projection-pot`, sous packages séparés. |
| `engine-projection-pot` | loaders/projectors AUTH et READ_POT | | | | ✓ | `projection-pot`, producteur sans worker ni runtime. |
| `engine-read-projection` | requête CURRENT_BINDING, watermark LKV, source historique Pot | | | | ✓ | scinder en `engine-read-binding`, `engine-consumption-lkv`, `projection-current-binding`; source historique vers `projection-pot`. |

### Analyse inverse : capacités cachées hors `engine-*`

| Source actuelle | Capacité réelle | Cible conceptuelle et bénéfice |
|---|---|---|
| `locator-consumption-command` | découverte, callback et classification de consommation Command | `engine-consumption-command` physique : ownership du traitement, sans Spring. |
| `locator-consumption-event` | source metadata-only et finaliseur Event→Task | fusionner dans `engine-consumption-event`, au lieu de le pousser dans le runtime. |
| `locator-consumption-latest-known-version` | reload Event, avance LKV, classification | fusionner dans `engine-consumption-lkv` ; le runtime ne garde que composition/métriques. |
| `locator-consumption-binding` | reload fact et effet direct CURRENT_BINDING | `engine-consumption-binding` physique ; lors du cutover, produire/suivre la Task standard si les preuves le permettent. |
| `orchestrator-consumption/ProjectionTaskConsumptionOrchestrator` | sélection et invocation spécialisée Task | déplacer dans `engine-consumption-projection-task` ; le module générique perd sa dépendance Task. |
| `orchestrator-command-admission` | admission de Command au primaire | WRITE conceptuel, mais garder le module physique : sa frontière avec le runtime HTTP et l'exécution asynchrone reste utile. |
| `supra-consumption-worker` | cadence/polling, non traitement unitaire | worker, conserver hors engine. |

## 4. Responsabilités conceptuelles et modules physiques

| Responsabilité conceptuelle | Module physique cible | Package cible indicatif |
|---|---|---|
| WRITE Command | `engine-write-command` | `...engine.write.command` |
| WRITE Pot | `engine-write-pot` | `...engine.write.pot` |
| WRITE admission Command | `orchestrator-command-admission` | `...orchestrator.command.admission` |
| READ Pot | `engine-read-pot` | `...engine.read.pot` |
| READ Command Result | `engine-read-command-result` | `...engine.read.commandresult` |
| READ Binding | `engine-read-binding` | `...engine.read.binding` |
| READ projection exacte | `engine-read-projection-exact` | `...engine.read.projection.exact` |
| CONSUMPTION générique | `engine-consumption` | `...engine.consumption.protocol` |
| CONSUMPTION Command | `engine-consumption-command` | `...engine.consumption.command` |
| CONSUMPTION Event→Task | `engine-consumption-event` | `...engine.consumption.event.materialization` |
| CONSUMPTION ProjectionTask | `engine-consumption-projection-task` | `...engine.consumption.projectiontask` |
| CONSUMPTION Binding fact | `engine-consumption-binding` | `...engine.consumption.binding` |
| CONSUMPTION LKV Event | `engine-consumption-lkv` | `...engine.consumption.lkv` |
| AUTH, READ_POT, BALANCES producer | `projection-pot` | `...projection.pot.{auth,read,balance}` |
| COMMAND_RESULT producer | `projection-command-result` | `...projection.commandresult` |
| CURRENT_BINDING producer cible | `projection-current-binding` | `...projection.currentbinding` |
| Worker générique | `supra-consumption-worker` | `...worker.consumption` |
| Composition des six processus | les six `runtime-*` | `...runtime.<capability>.{configuration,lifecycle,adapter}` |

Les packages proposés décrivent l'ownership ; leur graphie exacte peut suivre la convention Java du lot. Les locators Event/LKV ne fusionnent plus dans leurs runtimes : ils portent du traitement applicatif. Le runtime Binding ne reçoit pas non plus son locator ; le module spécialisé reste une vraie frontière contre une dépendance à Spring. Si un adapter technique local est finalement placé dans un runtime, la règle est `adapter → ports/domain`, jamais `adapter → configuration/lifecycle` ; `configuration → adapter` est autorisé.

## 5. Deux graphes cibles

### Conceptuel

```text
                          domaines / contrats
                    ┌──────────┼───────────┐
                    ↓          ↓           ↓
               WRITE        READ       CONSUMPTION
          primary mutations  READ only   claim/lease/fence/retry
          Command, Pot       Pot,        générique
                             Result,          ↓
                             Binding       Command → WRITE Command → WRITE Pot
                             exact read    Event metadata → policy → Tasks
                                           ProjectionTask → projection-* → READ store
                                           Binding fact → CURRENT_BINDING direct (actuel)
                                                        → Task standard (cible conditionnelle)
                                           LKV Event reload → watermark READ

        worker = cadence et arrêt ; runtime = composition Spring/SQL/processus
```

`CURRENT_BINDING` et LKV produisent une donnée READ, mais sont classés par ce qu'ils consomment. Les engines READ de requête ne doivent jamais retomber sur le primaire en cas de projection absente.

### Physique : dépendances de production autorisées

Le schéma nomme uniquement les **modules Maven cibles** ; une flèche `A → B` signifie que A peut dépendre de B. Il décrit les arcs structurants autorisés, pas l'adjacence Maven exhaustive de l'audit initial. Aucun scope de test, `architecture-tests` ou arc runtime→runtime n'y figure.

```text
runtime-web-api → supra-http-write-command, supra-http-read-query,
                  supra-authentication-spring-security, orchestrator-command-admission,
                  engine-read-pot, engine-read-command-result, engine-read-binding,
                  infra-persistence-jpa, infra-read-persistence, infra-projection-persistence
runtime-command-consumption-worker → supra-consumption-worker, engine-consumption-command,
                                     binding-pot-command-spring, infra-persistence-jpa, infra-tx-spring
runtime-event-consumption-worker → supra-consumption-worker, engine-consumption-event,
                                   infra-persistence-jpa, infra-tx-spring
runtime-task-consumption-worker → supra-consumption-worker, engine-consumption-projection-task,
                                  projection-pot, projection-command-result, projection-current-binding,
                                  infra-persistence-jpa, infra-projection-persistence, infra-read-persistence
runtime-binding-consumption-worker → supra-consumption-worker, engine-consumption-binding,
                                     infra-persistence-jpa, infra-read-persistence, infra-tx-spring
runtime-latest-known-version-consumption-worker → supra-consumption-worker,
                                                   engine-consumption-lkv,
                                                   infra-persistence-jpa, infra-read-persistence, infra-tx-spring

supra-consumption-worker → orchestrator-consumption → engine-consumption →
                            domain-consumption, application-contracts
engine-consumption-command → orchestrator-consumption, engine-write-command,
                             engine-consumption, domain-command
engine-consumption-event → orchestrator-consumption, engine-consumption,
                           engine-consumption-projection-task, projection-contracts
engine-consumption-projection-task → orchestrator-consumption, engine-consumption,
                                     projection-contracts, projection-core
engine-consumption-binding → orchestrator-consumption, engine-consumption,
                             domain-user-identity, projection-current-binding
engine-consumption-lkv → orchestrator-consumption, engine-consumption,
                         domain-pot, application-contracts
engine-write-command → domain-command, domain-user-identity, application-contracts
engine-write-pot → engine-write-command, domain-pot, domain-pot-policy
engine-read-pot → engine-read-projection-exact, projection-pot-contracts, domain-pot-policy
engine-read-command-result → engine-read-projection-exact, projection-contracts,
                             domain-command, domain-user-identity
engine-read-binding → projection-contracts, domain-user-identity
engine-read-projection-exact → projection-contracts, projection-core
projection-pot → projection-core, projection-pot-contracts,
                 engine-consumption-projection-task, domain-projection-balance
projection-command-result → projection-core, projection-contracts,
                            engine-consumption-projection-task, engine-write-command
projection-current-binding → projection-core, projection-contracts,
                             engine-consumption-projection-task, domain-user-identity
projection-pot-contracts → projection-core
projection-contracts → projection-core
infra-persistence-jpa → engine-write-command, engine-write-pot, engine-consumption,
                        engine-consumption-command, engine-consumption-event,
                        engine-consumption-projection-task, projection-pot, projection-command-result
infra-read-persistence → engine-read-binding, engine-consumption-lkv, projection-contracts
infra-projection-persistence → projection-contracts
infra-tx-spring → application-contracts
```

Deux précisions maintiennent ce graphe acyclique. `engine-write-pot` implémente les interfaces de dispatch de `engine-write-command` ; `binding-pot-command-spring` compose les deux, donc aucun arc Maven `engine-write-command → engine-write-pot` n'est permis. Les producteurs dépendent actuellement de l'interface `ProjectionProjector<I>` dans le module Task. Garder cette direction et empêcher un arc Task→producteurs ; le runtime assemble le catalog.

## 6. Règles vérifiables

| Règle de dépendance | Portée et preuve proposée |
|---|---|
| `domain-*` n'importe ni `engine-*`, ni `runtime-*`, ni infra | POM + ArchUnit ; les définitions de projection renommées sont des contrats hors engine. |
| `engine-read-*` n'importe ni engine WRITE, ni adapter PRIMARY | POM + ArchUnit, plus tests READ où NotReady/NotFound ne déclenche aucune lecture primaire. `domain-command` fournit `CommandId`. |
| `engine-write-*`, `engine-consumption-*`, `projection-*` n'importent aucun runtime/Spring/SQL | POM + ArchUnit ; l'infrastructure implémente leurs ports. |
| `engine-consumption` générique n'importe aucun Command, Event, Task, Binding ou LKV | POM + ArchUnit ; `orchestrator-consumption` perd son arc Task. |
| `runtime-*` n'importe aucun autre runtime | POM ; chaque processus garde son composition root. |
| READ et projection producer ne lisent pas le primaire via un adapter caché | Tests de ports + inspection des beans/datasources ; une règle POM seule ne peut pas prouver le comportement d'un port. |
| WRITE durable et publication projection sont finalisés dans la transaction fenced | Tests PostgreSQL de concurrence, rollback et late commit ; ArchUnit ne suffit pas. |

`application-contracts` est la seule exception nominale temporaire : c'est un module partagé **sans** préfixe `engine-`, contenant encore plusieurs types hétérogènes. Il faut déplacer progressivement `TransactionRunner` vers un contrat transactionnel neutre, les snapshots/`PotGlobalVersion` et `UserContext` vers les contrats WRITE/domain appropriés, `RecordedEvent` vers un contrat Event, `WorkerSegment`/`PartitionHash` vers la couche CONSUMPTION. `BusinessEventEnvelope` et `PotPartitioner` legacy exigent d'abord un audit des consommateurs. Un split physique immédiat n'est pas requis. `infra-persistence-jpa` reste **KEEP FOR NOW — REASSESS AFTER ENGINE/PORT NORMALIZATION** : ses futurs splits possibles sont Command/Binding/Consumption/Projection, seulement si les transactions et migrations ne sont pas fracturées.

## 7. Révision des lots et preuves

Chaque lot doit compiler et déclarer avant implémentation ses slices selon `docs/testing/Reactor_Verification_Policy.md`. Tout changement de module ou de frontière exige le gate `./mvnw -pl architecture-tests -am test` ; un full reactor est réservé à la clôture d'une restructuration large/Wave et à l'intégration. Aucun de ces tests n'a été lancé pour la présente révision documentaire.

| Lot | Changement de dépendances et classes | Slice canonique / preuve ; risque et clôture |
|---|---|---|
| 0. Tests de cible | Ajouter ultérieurement les règles de dépendance et preuves de transaction/READ sans déplacer la production. | Gate architecture seulement si règles globales changent ; fermer quand les tests décrivent le graphe actuel et les écarts connus. |
| 1. Contrats partagés | Fusion `authentication-contracts → domain-user-identity`; extraire `CommandId` de `engine-command` vers le nouveau `domain-command`; renommer `engine-core → application-contracts` sans changer les signatures. | WEB + COMMAND + architecture ; risque de dépendance cachée à WRITE depuis READ. |
| 2. Noms sans sémantique | Renommer `engine-pot-command → engine-write-pot`, `engine-command → engine-write-command`, `engine-pot-read → engine-read-pot`, `engine-projection-read → engine-read-projection-exact`, `engine-projection-contracts → projection-contracts`. | COMMAND + WEB + PROJECTION + architecture ; fermer sur graphe sans cycles et tests de compilation. |
| 3. Consumption Command et Task | `locator-consumption-command → engine-consumption-command`; `engine-projection-task → engine-consumption-projection-task`; déplacer `ProjectionTaskConsumptionOrchestrator` hors orchestrateur générique. | COMMAND + PROJECTION + architecture ; risque CAS final, retry et pagination. |
| 4. Producteurs et résultat | Fusion `engine-projection-balance → projection-pot`, renommage `engine-projection-pot → projection-pot`, scission Command Result en READ/producteur, définition dans `projection-contracts`. | PROJECTION + WEB + EVENT si routes impactées, architecture ; préserver visibilité exacte et compatibilité résultat. |
| 5. Event et LKV | Fusion locator Event dans `engine-consumption-event`; déplacer la policy hors runtime. Scinder LKV hors du module mixte READ et fusionner son locator dans `engine-consumption-lkv`. | EVENT + LKV + PROJECTION si Task contract change, architecture ; préserver metadata-only, watermark monotone et rechargement LKV. |
| 6. Binding READ actuel | `locator-consumption-binding → engine-consumption-binding`; scinder `GetCurrentBindingService` vers `engine-read-binding`; déplacer `HistoricalPotSnapshotSource`/exception vers `projection-pot`. Garder l'effet direct actuel. | BINDING + WEB + COMMAND si contrat de Binding touche le fence, architecture ; préserver revisions, lock ordering, bootstrap, late commit. |
| 7. `CURRENT_BINDING` standard | Concevoir et prouver l'identité Task (dont baseline rev 0), événement/route durable, loader et projector, publication monotone, migration du read store et cutover sans double effet. Créer `projection-current-binding` seulement à ce lot. | EVENT + PROJECTION + BINDING + WEB, COMMAND si contrat d'autorité/fencing modifié, architecture et gate full à la clôture de Wave ; risque maximal : régression READ ou saut de révision. Ce lot reste conditionnel à une conception validée par preuves. |
| 8. Nettoyage | Scinder discovery/ensure du port Task, retirer dépendances indirectes inutiles, réévaluer `application-contracts` et `infra-persistence-jpa` sans les éclater par défaut. | Slices selon comportement touché + architecture, full si restructuration large ; supprimer les anciens modules seulement quand leurs imports/tests sont partis. |

Les tests de preuve cités dans l'audit initial restent requis. Ajouter au lot 7 des preuves explicites multi-worker/restart/retry, même révision à payload divergent, commit ancien après nouvelle révision, bootstrap concurrent et identité exacte du fact source. Ne remplacer aucun test de Binding direct avant que la voie standard ait démontré la même garantie.

## 8. Questions non résolues par les invariants actuels

1. La forme exacte de l'événement durable Binding→Event et de la clé `ProjectionTask(CURRENT_BINDING)` n'existe pas dans le code. La révision 0 et l'absence de facts synthétiques rendent un simple `targetVersion = bindingRevision` invalide pour la baseline.
2. Le store actuel `current_external_identity_binding` expose une vue courante monotone. Le store générique publie des projections exactes par clé. Leur relation, le cutover et la non-régression sous achèvement désordonné doivent être dessinés puis prouvés avant de qualifier la nouvelle voie de « standard ».
3. Le partage de `CommandResultProjectionDefinition` entre READ et projector sans dépendance READ→WRITE réclame un emplacement neutre ; `projection-contracts` est proposé, à confirmer par le graphe Maven de la migration.

## 9. Synthèse compacte

**ENGINE TAXONOMY**
WRITE : `engine-write-command`, `engine-write-pot` (admission WRITE dans `orchestrator-command-admission`).
READ : `engine-read-pot`, `engine-read-command-result`, `engine-read-binding`, `engine-read-projection-exact`.
CONSUMPTION : `engine-consumption`, `engine-consumption-command`, `engine-consumption-event`, `engine-consumption-projection-task`, `engine-consumption-binding`, `engine-consumption-lkv`.
NOT ENGINES : projections `projection-core`, `projection-contracts`, `projection-pot`, `projection-command-result`, `projection-current-binding` ; worker `supra-consumption-worker` ; les six runtimes ; adapters `infra-*` ; domaines `domain-*`.

CURRENT MODULE COUNT: 47
PREVIOUS TARGET: 43
REVISED TARGET: 47

### TARGET PHYSICAL MODULES

La liste numérotée exacte et la classification exhaustive des 47 modules actuels figurent dans l'annexe ci-dessous.

### CONCEPTUAL ENGINES

WRITE : Command et Pot ; READ : Pot, Command Result, Binding et lecture exacte de projection ; CONSUMPTION : protocole commun, Command, Event→Task, ProjectionTask, Binding fact et LKV Event.

### Contradiction à ne pas masquer

La voie actuelle `CURRENT_BINDING` contourne Event→ProjectionTask. Sa migration vers une projection standard est le seul changement fonctionnel majeur proposé, conditionné à la résolution de l'identité rev 0, du store courant monotone et des preuves de concurrence. Le rapport initial reste exact sur la voie directe qu'il avait observée ; sa cible de fusion des locators dans les runtimes est remplacée ici par une ownership applicative explicite.

## Annexe — matrice de transformation physique

Une classification principale est attribuée à chaque module actuel. `RENAME` peut inclure un déplacement limité de types ; `SPLIT` énumère toutes les destinations créées. `KEEP FOR NOW — REASSESS` est une catégorie explicite de prudence, pas une approbation définitive du module.

| Module actuel | Classification | Destination physique | Motif / classes concernées |
|---|---|---|---|
| `domain-authorization` | KEEP | `domain-authorization` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `domain-event` | KEEP | `domain-event` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `domain-user-identity` | KEEP | `domain-user-identity` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `authentication-contracts` | MERGE | `domain-user-identity` | AuthenticatedExternalPrincipal est un contrat identité neutre. |
| `domain-pot` | KEEP | `domain-pot` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `domain-pot-projection` | RENAME | `projection-pot-contracts` | Définitions de projections Pot, hors domaine autoritaire. |
| `domain-projection-balance` | KEEP | `domain-projection-balance` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `domain-projection` | RENAME | `projection-core` | Modèle et validation des sorties de projection, hors engine exécutable. |
| `domain-pot-policy` | KEEP | `domain-pot-policy` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `domain-consumption` | KEEP | `domain-consumption` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `engine-core` | RENAME | `application-contracts` | TransactionRunner, snapshots, RecordedEvent et WorkerSegment ne forment pas une capacité. |
| `engine-consumption` | KEEP | `engine-consumption` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `engine-command` | RENAME | `engine-write-command` | Exécution Command; CommandId va dans domain-command, discovery vers Consumption. |
| `engine-command-result` | SPLIT | `engine-read-command-result` + `projection-command-result` | Lecture READ et production de projection ont des dépendances opposées; définition vers projection-contracts. |
| `engine-processing-event` | RENAME | `engine-consumption-event` | Policy et modèles de matérialisation Event→Task. |
| `engine-pot-command` | RENAME | `engine-write-pot` | Mutations métier autoritaires Pot. |
| `engine-projection-contracts` | RENAME | `projection-contracts` | Ports de projection sans traitement exécutable. |
| `engine-projection-read` | RENAME | `engine-read-projection-exact` | Lecture exacte du READ store. |
| `engine-projection-task` | RENAME | `engine-consumption-projection-task` | Préparation et finalisation de Task sous protocole Consumption. |
| `engine-pot-read` | RENAME | `engine-read-pot` | Lecture et autorisation Pot sur AUTH/READ_POT. |
| `engine-projection-balance` | MERGE | `projection-pot` | Producteur BALANCES distinct par package, même frontière physique Pot. |
| `engine-projection-pot` | RENAME | `projection-pot` | Producteurs AUTH et READ_POT; accueille BALANCES et historique Pot. |
| `engine-read-projection` | SPLIT | `engine-read-binding` + `engine-consumption-lkv` + `projection-current-binding` | Requête READ, avance LKV et future production CURRENT_BINDING séparées. |
| `observability` | KEEP | `observability` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `infra-tx-spring` | KEEP | `infra-tx-spring` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `infra-persistence-jpa` | KEEP FOR NOW — REASSESS | `infra-persistence-jpa` | Hub SQL primaire; revoir après normalisation ports et transactions. |
| `infra-projection-persistence` | KEEP | `infra-projection-persistence` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `infra-read-persistence` | KEEP | `infra-read-persistence` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `infra-projection-json-schema` | KEEP | `infra-projection-json-schema` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `orchestrator-consumption` | KEEP | `orchestrator-consumption` | Déplacer seulement ProjectionTaskConsumptionOrchestrator vers le module Task. |
| `orchestrator-command-admission` | KEEP | `orchestrator-command-admission` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `supra-consumption-worker` | KEEP | `supra-consumption-worker` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `locator-consumption-event` | MERGE | `engine-consumption-event` | Service applicatif Event→Task, pas simple wiring runtime. |
| `locator-consumption-latest-known-version` | MERGE | `engine-consumption-lkv` | Reload et avance LKV appartiennent à la capacité Consumption. |
| `locator-consumption-binding` | RENAME | `engine-consumption-binding` | Consommation autoritaire du fact; effet direct conservé jusqu’au cutover. |
| `locator-consumption-command` | RENAME | `engine-consumption-command` | Adapter exécutable de Command au protocole commun. |
| `binding-pot-command-spring` | KEEP | `binding-pot-command-spring` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `supra-http-write-command` | KEEP | `supra-http-write-command` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `supra-http-read-query` | KEEP | `supra-http-read-query` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `supra-authentication-spring-security` | KEEP | `supra-authentication-spring-security` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `runtime-web-api` | KEEP | `runtime-web-api` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `runtime-event-consumption-worker` | KEEP | `runtime-event-consumption-worker` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `runtime-latest-known-version-consumption-worker` | KEEP | `runtime-latest-known-version-consumption-worker` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `runtime-binding-consumption-worker` | KEEP | `runtime-binding-consumption-worker` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `runtime-task-consumption-worker` | KEEP | `runtime-task-consumption-worker` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `runtime-command-consumption-worker` | KEEP | `runtime-command-consumption-worker` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |
| `architecture-tests` | KEEP | `architecture-tests` | Frontière décrite dans l’audit initial ; aucun changement requis par la taxonomie. |

### Liste numérotée exacte des 47 modules physiques cibles

1. `domain-authorization`
2. `domain-event`
3. `domain-user-identity`
4. `domain-pot`
5. `projection-pot-contracts`
6. `domain-projection-balance`
7. `projection-core`
8. `domain-pot-policy`
9. `domain-consumption`
10. `application-contracts`
11. `engine-consumption`
12. `engine-write-command`
13. `engine-read-command-result`
14. `projection-command-result`
15. `engine-consumption-event`
16. `engine-write-pot`
17. `projection-contracts`
18. `engine-read-projection-exact`
19. `engine-consumption-projection-task`
20. `engine-read-pot`
21. `projection-pot`
22. `engine-read-binding`
23. `engine-consumption-lkv`
24. `projection-current-binding`
25. `observability`
26. `infra-tx-spring`
27. `infra-persistence-jpa`
28. `infra-projection-persistence`
29. `infra-read-persistence`
30. `infra-projection-json-schema`
31. `orchestrator-consumption`
32. `orchestrator-command-admission`
33. `supra-consumption-worker`
34. `engine-consumption-binding`
35. `engine-consumption-command`
36. `binding-pot-command-spring`
37. `supra-http-write-command`
38. `supra-http-read-query`
39. `supra-authentication-spring-security`
40. `runtime-web-api`
41. `runtime-event-consumption-worker`
42. `runtime-latest-known-version-consumption-worker`
43. `runtime-binding-consumption-worker`
44. `runtime-task-consumption-worker`
45. `runtime-command-consumption-worker`
46. `architecture-tests`
47. `domain-command`

Le module `domain-command` est **ajouté** pour `CommandId` et les contrats d’identité partagés ; aucun autre nouveau module n’est créé hors destinations de split. Comptage : 47 actuels − 4 MERGE + 3 sorties nettes des deux SPLIT + 1 ajout = 47 cibles.
