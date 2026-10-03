# DEBT-MOD-01 — topologie de modularisation proposée

**Statut : TARGET / proposition de frontières, non livrée.** Aucun nom, arc ou compte de modules ci-dessous ne décrit le reactor exécutable. Ce document transforme les décisions [BC-01 à BC-16](Modularity_Boundary_Challenge.md#p-boundary-decisions) en hypothèse de topologie à éprouver avant un plan de migration. Les zones `TBD` restent ouvertes ; la [dette](../../../debts/MODULE_TAXONOMY/Debt.md) demeure OPEN.

## A. Baseline

Branche `v2-make-it-pull`. Après `git fetch origin`, HEAD local et `origin/v2-make-it-pull` : `1e2aba6ff2b6fd1da4e09fef22b3d296e2e54bad` ; divergence `0/0` ; `git status --short` vide. Aucun commit depuis `1e2aba6f`. Le reactor observé reste celui de l'[audit factuel](Modularity_Current_State_Audit.md) : **51 enfants Maven, neuf runtimes**. Cette étape ne modifie ni Java, ni POM, ni SQL.

## B. Authority and BC inputs

Les sources **CURRENT** sont l'[inventaire de modularité](Modularity_Current_State_Audit.md), le [modèle fonctionnel](../../../product/Functional_Model.md), l'[architecture](../../../architecture/Architecture.md) et les [garanties](../../../guarantees/System_Guarantees.md), selon la [porte d'entrée documentaire](../../../README.md). Le [challenge B.1](Modularity_Boundary_Challenge.md) gouverne la traduction : `KEEP_TOGETHER` ne prescrit pas un POM commun ; `SEPARATE_CONCERNS` ne prescrit pas un split ; `MOVE_OWNERSHIP` ne déplace pas une classe entière ; `BOUNDARY_USEFUL` ne pérennise pas le processus actuel ; `UNRESOLVED` ne donne pas licence d'inventer un owner. Les anciennes cibles TARGET ne servent pas de plan par défaut. Les garanties CURRENT bornent l'évaluation, sans devenir des garanties de la topologie non implémentée.

## C. Target design principles

La conception suit quatre passes : (1) owner fonctionnel ; (2) mécanique transverse et adapters ; (3) barrière Maven si une dépendance indésirable serait autrement possible ; (4) runtime de composition. Chaque module de la section S indique l'import interdit qu'il protège et pourquoi un package seul serait moins fiable. Aucun POM `WEAK` n'est proposé. `MEDIUM` désigne une coupure défendable mais révisable après mesure des imports. Le compte n'est calculé qu'après la matrice et le catalogue.

Une capacité peut posséder admission, exécution, outcome, Result et GET tout en exposant plusieurs ports, commits et boucles. Les locators sont analysés en quatre morceaux : sémantique de capacité, glue vers Consumption, SQL, paramètres de déploiement. Les noms `engine-*` dans le catalogue sont provisoires ; un nom n'établit aucune famille de moteur.

## D. Functional ownership map

| Capacité | Owner fonctionnel cible | Orchestration technique / adapters / déploiement |
|---|---|---|
| Command et Pot mutation | Command pour identité, admission/exécution/outcome ; Pot pour règles de mutation | Glue Command dans son runtime ; protocole Consumption commun ; SQL primaire ; runtime Command. |
| Command Result | Command Result pour modèle, owner E, cohérence terminale, materializer et GET | Glue dans runtime Result ; source/store SQL ; boucle indépendante. Dépendance aux types Command maintenue provisoirement (BC-04). |
| Registration et Registration Result | **Registration commun** pour request, arbitrage Binding/User, outcome, Result, GET et policies sémantiques | Deux slots/boucles ; glue dans chaque runtime ; SQL primaire ; pas de Result générique. |
| Binding authority | Binding pour occurrence E+B, stream, facts et autorité | Ports d'autorité séparés des valeurs ; SQL primaire ; transactions partagées avec Registration/Command lorsque requis. |
| CURRENT_BINDING | Binding pour modèle convergent, application du fact, règle R et GET | Consumption directe de fact ; store READ ; glue dans runtime Binding. Même owner, pas même import d'autorité primaire. |
| Event→Task | Event/production de projections Pot pour la table EventType→ProjectionType et `ensure Task` | Glue Event et discovery SQL ; runtime Event. |
| ProjectionTask | Task pour identité, catalogue, préparation, contrôle de key et publication fenced | Deux orchestrateurs génériques restent en Consumption ; spécialisation Task côté Task ; runtime Task. |
| Projection Pot exacte | Pot projection pour définitions, loaders abstraits, projectors purs, BALANCES et validation du résultat | Sources historiques SQL ; store root/artifact exact ; runtime Task. |
| Pot read | Pot read et Authorization pour interprétation AUTH@V puis READ_POT@V | Facade de query hors HTTP ; E→U derrière un port neutre dont l'owner/source restent `TBD-E2U`. |
| Consumption protocol | Consumption pour key/claim/lease/provenance, acquire/finalize/retry et deux orchestrateurs génériques | Polling séparé ; chaque capacité fournit sémantique et intégration locale. |
| LKV | `TBD-LKV` ; seul le maximum observé est établi | Work et runtime provisoirement isolés ; options en M, sans assimilation à CURRENT_BINDING. |

## E. Consumption target

`domain-consumption` garde les valeurs de claim, lease, slot, provenance et segmentation de candidats. `engine-consumption` garde acquire/execute/finalize/failure et le contrat du commit d'effet. `orchestrator-consumption` garde **SequentialConsumptionOrchestrator** et **AcquireThenFinalizeConsumptionOrchestrator**, qui réutilisent le même protocole sans partager la même frontière de préparation. `supra-consumption-worker` garde la boucle de polling réutilisée par les huit runtimes workers : son POM bloque un import des capacités métier vers la cadence, les threads et `SmartLifecycle`. Un package dans un runtime commun rendrait les autres runtimes dépendants de ce runtime ; un package dans le protocole ferait dépendre le protocole du polling. Cette réutilisation et cette interdiction justifient la frontière physique (BC-06).

La spécialisation `ProjectionTaskConsumptionOrchestrator` appartient au côté **ProjectionTask** de la direction de dépendance : `engine-projection-task → orchestrator-consumption → engine-consumption → domain-consumption`. C'est la recomposition la plus courte : les interfaces génériques existantes restent génériques, la Task fournit sa stratégie de page/prepare/finalize, sans `Processor<T>` universel. La séparation des commits de claim et d'effet, puis l'atomicité effet+provenance+CAS gagnant, reste obligatoire (BC-07). Les locators Command, Event et Binding n'ont pas besoin de POM autonomes : la policy sémantique va à la capacité ; leur glue particulière peut vivre dans le runtime correspondant, qui importe capacité et protocole sans les faire dépendre l'un de l'autre. Les locators Results/Registration suivent la même règle. Les appels SQL restent adapters. Ce choix **ne** donne pas à la classe locator entière un owner métier (BC-05).

## F. Registration target

`engine-registration` reste une frontière fonctionnelle **unique proposée**, non parce que BC-01 l'impose, mais parce que request/outcome/Result et owner E partagent aujourd'hui un contrat stable, tandis qu'aucun import interdit supplémentaire n'est démontré par un POM Result séparé. Il contient les règles d'admission, l'exécution, l'arbitrage User/Binding, l'outcome, la cohérence request/outcome/Result, la matérialisation, le GET opaque et les classifications fonctionnelles. Il exclut claim/lease/CAS génériques, SQL, controllers, backoff et scheduling. Les adapters Registration/Binding/Result restent dans la frontière SQL primaire ; les deux runtimes gardent leurs locators **comme glue**, avec slots et finalisations indépendants. Cette cible peut être révisée si une dépendance interdite entre admission, exécution et query est démontrée avant migration ; `KEEP_TOGETHER` ne la rend pas obligatoire.

Chaîne proposée : HTTP admission → request durable ; runtime Registration → slot Request → `engine-registration` + autorité Binding/User → outcome dans commit fenced ; runtime Result → slot Outcome → `engine-registration` materializer → Result immutable dans son commit fenced ; HTTP GET → `engine-registration` query par E historique. Aucun appel à CURRENT_BINDING pour le Result. Le runtime n'héberge que glue, paramètres et composition ; la policy invariant/technique est fournie par Registration (BC-01/02/05).

## G. Command Result target

`engine-command-result` reste une capacité cohérente : modèle Result immuable, ownership E historique, validation terminal Event/outcome/Command, matérialisation et GET. Seul le materializer connaît Event/outcome comme sources ; le GET dépend du contrat Result. Le runtime Result conserve glue discovery→reload→execute et backoff, l'adapter SQL garde discovery/reload/store. Les classifications sémantiques vivent avec la capacité, sans imposer le déplacement entier du locator (BC-03/05).

Mesure `src/main/java` au HEAD : `CommandId` apparaît dans **7 modules** (`engine-command`, `engine-command-result`, `infra-persistence-jpa`, `locator-consumption-command`, `orchestrator-command-admission`, `runtime-web-api`, `supra-http-read-query`) ; `CommandOutcome` dans **5** (`engine-command`, `engine-command-result`, `infra-persistence-jpa`, `locator-consumption-command`, `runtime-command-consumption-worker`). La plupart de ces usages participent réellement au cycle Command ; le GET et le Result utilisent les mêmes types, mais la mesure ne démontre pas qu'un POM neutre de contrats apporterait une barrière supérieure. **Choix cible provisoire : conserver `engine-command-result → engine-command`**, marquer `TBD-COMMAND-CONTRACT` et n'ajouter aucun module de contrat. Avant migration, mesurer les imports Java exacts et l'effet sur les closures Web/Result (BC-04).

## H. Binding / Current Binding target

La frontière de valeurs `domain-user-identity` conserve E, U, B, R, occurrence et facts append-only. Une frontière de **ports d'autorité Binding** proposée reçoit acquire/detach, stream/fact authority et User authority : elle est justifiée parce que Current Binding et le GET self doivent pouvoir importer les valeurs/facts **sans** importer les ports de mutation/PRIMARY. C'est une coupure Maven concrète, pas un module par interface. `engine-binding-read` regroupe la règle de matérialisation directe du fact, la convergence R et le GET current, sous ownership fonctionnel Binding ; il dépend des valeurs et d'un port de store READ, pas de l'autorité primaire. Le runtime Binding compose la discovery SQL de facts, la glue Consumption et le store READ ; les adapters gardent les transactions/SQL. Une même capacité Binding a donc des frontières physiques distinctes pour protéger `read query → primary authority` (BC-08/16).

`ExternalIdentityResolverPort` utilisé aujourd'hui par GET Pot n'est pas un port de mutation Binding à imposer à `engine-binding-read` : la traduction E→U pour une query est traitée en `TBD-E2U` (L). Cette cible n'altère ni l'occurrence exacte E+B, ni l'ordre de locks, ni R, tombstone, divergence à même révision ou absence de délai borné.

## I. Projection target

`domain-projection` garde ProjectionKey, artifact et validation de **sortie**, sans SQL/Spring. `engine-projection-contracts` garde ports neutres de lecture/publication exactes **et les interfaces minimales `ProjectionInputLoader` / `ProjectionProjector` actuellement logées dans Task** ; ainsi les producers Pot n'importent pas toute l'orchestration Task. `engine-projection-read` garde lecture exacte/revalidation sans Task ; `engine-projection-task` garde Task, catalogue, exécution, retry/publication et la spécialisation de son orchestration Consumption. `projection-pot` regroupe définitions AUTH/READ_POT/BALANCES, modèles/calculs Balance, loaders abstraits et projectors purs. Il ne contient ni implémentation SQL ni worker. `engine-pot-read` interprète AUTH@V et READ_POT@V et autorise la query exacte, sans dépendance transitive à Task. `infra-projection-adapters` réunit store root/artifact/failure et validation JSON Schema concrète : les deux sont des adapters techniques des mêmes runtimes Web/Task ; un POM par bibliothèque n'interdit ici aucun import additionnel utile. Les sources historiques Pot restent adapters de la persistence primaire. `ProjectionValidator` valide le résultat du projector après contrôle de ProjectionKey, jamais la Task. Results et CURRENT_BINDING restent hors Event→Task (BC-09/10).

| Module courant | Disposition cible | Raison de frontière |
|---|---|---|
| `engine-projection-contracts` | `KEEP` + contrat producer minimal | Ports exacts et interfaces Loader/Projector neutres : adapters, producers et query n'importent pas Task. |
| `engine-projection-read` | `KEEP` | GET exact sans Task orchestration ; dépend des contrats, pas du worker. |
| `engine-projection-task` | `KEEP` + réattribution de la spécialisation Task | Task change avec scheduling/finalisation, pas avec chaque projector. |
| `engine-projection-pot` | `MERGE` dans `projection-pot` | Calcul et définitions Pot purs ; POM supplémentaire n'interdit pas d'import prouvé. |
| `engine-projection-balance` | `MERGE` dans `projection-pot` | Calcul BALANCES reste un package spécifique ; même barrière de pureté face à SQL/Spring. |

## J. engine-core disposition

`engine-core` n'est pas reconduit. Un seul nouveau port transverse stable est proposé ; le reste retrouve l'owner ou l'adapter qui l'utilise (BC-11).

| Famille actuelle | Owner / POM ou package cible | Raisons et consommateurs |
|---|---|---|
| `TransactionRunner` | `application-transaction-port` | Port autonome partagé par Consumption, Registration, Pot/Command admission et Spring adapter ; interdit `application → SpringTransactionRunner`. |
| Snapshots Pot/Expense, `PotGlobalVersion` | Packages de `engine-pot-command` (ou valeur Pot dans `domain-pot` si un second consommateur pur apparaît) | Snapshots actuellement consommés par Pot command ; version aussi par binding Spring/SQL. Aucun POM par snapshot. |
| `RecordedEvent`, `EventTraceMetadata` | Package de `domain-pot` | `RecordedEvent<E>` importe `domain-pot/BusinessEvent` ; le déplacer dans `domain-event` créerait le cycle `domain-event → domain-pot → domain-event`. Event processing, persistence et LKV l'utilisent. |
| `BusinessEventEnvelope` legacy | Package de `infra-persistence-jpa` tant que seule cette persistence l'utilise | Contrat legacy d'adapter, pas noyau partagé. |
| `WorkerSegment`, `PartitionHash` | Package de `domain-consumption` | Segmentation de travail utilisée par Event, LKV, discovery SQL et runtimes ; évite un POM de deux utilitaires. |
| `PotPartitioner` legacy | Package de `infra-persistence-jpa` | Usage observé uniquement dans cet adapter. |
| `UserContext` | Package de `engine-pot-command` | Seul consumer de production hors `engine-core` observé. |
| `BusinessEntityNotFoundException`, `VersionConflictException` | Packages de `engine-pot-command` ; l'adapter SQL importe le contrat qu'il implémente | Usages observés Pot command/persistence, sans motif de module d'exceptions global. |

## K. Persistence topology

La granularité physique proposée est conservatrice : **un POM SQL primaire** avec packages de clusters explicitement séparés, **un POM adapters de projection exacte** et **un POM SQL READ dérivé**. Les deux derniers restent distincts car exact root/artifact et current/watermark n'ont ni identité ni règle de mutation commune ; leurs runtimes/ports clients diffèrent. `infra-persistence-jpa` garde plusieurs clusters par choix de faible granularité physique : aucune interdiction Maven supplémentaire entre ses sous-packages n'a été démontrée, tandis que les commits multi-capacités utilisent le même transaction manager (BC-12/13). Ce choix n'assimile pas même transaction, datasource, JPA et POM.

| Cluster | Responsibilities | Must share transaction? | Same datasource only? | Suggested physical boundary |
|---|---|---|---|---|
| Command + Pot mutation + outcome + terminal Event | Mutation primaire, append Event, publication terminale | Oui, avec provenance/CAS gagnant | Non | `infra-persistence-jpa` + port Consumption ; composition transactionnelle inter-adapters. |
| Registration + User + Binding + outcome | Arbitrage, User/facts/Binding, outcome | Oui, avec provenance/CAS gagnant | Non | Même POM SQL primaire par prudence ; packages distincts. |
| Binding authority/facts | Stream/authority, locks, revision, append fact | Oui pour un changement Binding ; aussi partagé avec Registration gagnante | Non | Même POM SQL primaire. |
| Consumption slot/provenance/CAS | Claim séparé, finalisation du traitement | Oui avec **chaque** effet durable gagnant ; claim en commit séparé | Non | Adapter Consumption dans POM SQL primaire. |
| Command Result | Source terminale + insert immutable/GET | Oui avec sa propre finalisation ; pas avec commit Command initial | Oui avec Command source | Package Result dans POM SQL primaire ; aucun split POM sans interdiction mesurée. |
| Registration Result | Request/outcome + insert immutable/GET | Oui avec sa propre finalisation ; pas avec exécution Registration | Oui avec request/outcome | Package Result dans POM SQL primaire. |
| Event→ProjectionTask discovery/ensure | Event metadata, Task ensure | Oui avec finalisation Event | Oui avec primaire Event | Package discovery/Task dans POM SQL primaire. |
| Projection historical loaders | Snapshots Pot/Expense en lecture | Non avec mutation Command ; préparation puis publication fenced distincte | Oui | Package loader dans POM SQL primaire. |
| Projection exact root/artifact | Publication immutable, failure et GET exact | Oui avec finalisation Task | Peut utiliser même DB/manager sans même POM | `infra-projection-adapters`. |
| CURRENT_BINDING | Upsert R, tombstone, GET | Oui avec finalisation Binding | READ store distinct logiquement | `infra-read-persistence`. |
| LKV | Max-upsert du watermark | Oui avec finalisation LKV | READ store, sans lien transactionnel avec Binding | `infra-read-persistence` **provisoirement** ; scission physique TBD-LKV. |

Si le POM SQL primaire unique empêche ultérieurement une interdiction prouvée (ex. Web n'a besoin que Result mais hérite de toute l'autorité), une frontière plus fine pourra être justifiée par graphe/transactions ; ce rapport ne la présume pas. Les frontières de packages et tests structurels devront, elles, empêcher qu'un adapter SQL de résultat réutilise une mutation primaire par commodité.

## L. HTTP/query orchestration

`runtime-web-api` porte la traduction HTTP/auth et la composition ; un **point d'entrée applicatif Pot read** (hébergé provisoirement dans `engine-pot-read`) orchestre E→U, puis AUTH@V/READ_POT@V. Ce placement physique évite un POM `Identity Authorization Orchestrator` artificiel. Il ne décide pas l'owner fonctionnel final de la résolution d'identité : `TBD-E2U` désigne le port neutre appelé par ce point d'entrée, non un module cible. Les autres GET Results continuent de comparer E historique sans consulter Binding courant (BC-14).

- Scénario compatible CURRENT : l'adapter de `TBD-E2U` utilise le resolver PRIMARY existant. Le runtime Web le câble, mais le contrôleur ne connaît ni `JpaExternalIdentityResolverAdapter` ni son choix de source. Le franchissement PRIMARY est déclaré, pas dissimulé.
- Scénario futur READ : un adapter de représentation READ remplace ce port après définition des garanties de retard, révocation et absence ; le point d'entrée et le contrôleur restent identiques. Aucune représentation READ n'est supposée livrée ici.

Le port est placé à l'entrée applicative de query pour rendre le changement de source possible ; **l'owner exact, la sémantique de cohérence et la source restent TBD**. Un READ Pot strictement sans PRIMARY n'est donc pas encore une propriété de la cible certaine.

## M. LKV target options / TBD

| Option | Owner possible | Dependencies / runtime need | Migration impact | Reason to choose / missing evidence |
|---|---|---|---|---|
| A — capacité autonome | LKV Pot watermark | Event source, Consumption et store max ; runtime séparé possible | Isoler model/policy, garder loop et éventuellement processus | Choisir si un contrat aval indépendant apparaît ; aucun lecteur métier `src/main` connu. |
| B — préoccupation technique de projection/consumption | Pipeline de production des projections | Event, Task/Consumption selon usage ; loop indépendante nécessaire, processus éventuellement partagé | Recomposer worker sans confondre LKV et readiness Task | Choisir si une dépendance technique réelle au scheduling/projection est prouvée ; absente aujourd'hui. |
| C — observabilité/watermark | Exploitation et métriques de progression | Event, Consumption, store max ; processus éventuellement partagé | Conserver garanties de max/fencing et requalifier contrat | Choisir si LKV ne sert qu'au suivi ; absence de lecteur métier ne suffit pas à le prouver. |

**TARGET TBD-LKV :** le catalogue réserve une frontière provisoire pour son work et une composition runtime conditionnelle afin de ne pas le mêler à CURRENT_BINDING ou aux projectors. Ce ne sont ni un choix de l'option A, ni une justification définitive du processus autonome (BC-08/15). Une loop/slot indépendante demeure requise dans toutes les options.

## N. Domain boundaries

| `domain-*` actuel | Décision cible | Raison |
|---|---|---|
| `domain-authorization` | `KEEP` | Kernel pur partagé Pot read/command ; interdit Spring/JPA dans le calcul d'accès. |
| `domain-event` | `KEEP` | Contrat Event minimal partagé, sans importer `domain-pot` ; `RecordedEvent<BusinessEvent>` va plutôt côté Pot pour éviter un cycle. |
| `domain-user-identity` | `SPLIT RESPONSIBILITIES` | Valeurs/facts/occurrence restent purs ; ports PRIMARY séparés pour que Current Binding/READ ne les importe pas. |
| `domain-pot` | `KEEP` | Modèle Pot et BusinessEvent ; ajoute types Pot/recording cohérents, pas TransactionRunner. |
| `domain-pot-projection` | `MERGE` dans `projection-pot` | Définitions et projectors Pot partagent la barrière de pureté ; packages distincts. |
| `domain-projection-balance` | `MERGE` dans `projection-pot` | Calcul BALANCES pur et définitions Pot ; pas de POM par algorithme. |
| `domain-projection` | `KEEP` | Identity/key/validator exacts partagés query/Task, sans SQL/Spring. |
| `domain-pot-policy` | `KEEP` | Calcul d'autorisation commun à commande et lecture ; direction pure protégée. |
| `domain-consumption` | `KEEP` | Modèle de concurrence commun à plusieurs capacités, hors infrastructure Spring/SQL. |

La règle `domain → Spring/JPA` demeure interdite ; le nombre actuel des POM domain n'est pas sanctuarisé (BC-16).

## O. Runtime topology

`KEEP` ci-dessous signifie conservation proposée pour une migration exécutable et indépendante **aujourd'hui**, pas une nécessité éternelle d'un POM/processus. La boucle indépendante est la propriété plus forte que la forme de déploiement (BC-02/16).

| Runtime current | Target status | Capabilities composed | Why separate process / loop? |
|---|---|---|---|
| `runtime-web-api` | `KEEP` | HTTP/auth, Command/Registration admission, Pot/Results/Binding queries | Surface HTTP synchrone et cycle de déploiement distinct des workers. |
| `runtime-command-consumption-worker` | `KEEP` | Command + Pot mutation + Consumption | Slot Command et transaction primaire fenced. |
| `runtime-event-consumption-worker` | `KEEP` | Event→Task + Consumption | Slot Event et politique de matérialisation indépendante de Task. |
| `runtime-task-consumption-worker` | `KEEP` | Task + Pot projectors + exact store + Consumption | Préparation longue et publication exacte fenced. |
| `runtime-binding-consumption-worker` | `KEEP` | Binding facts→CURRENT_BINDING + Consumption | Slot Fact et convergence R ; loop indépendante de Registration. |
| `runtime-command-result-consumption-worker` | `KEEP` | Command Result + Consumption | Terminal Event→Result dans son commit et retry propres. |
| `runtime-registration-consumption-worker` | `KEEP` | Registration execution + Binding authority + Consumption | Slot Request et arbitrage User/Binding. |
| `runtime-registration-result-consumption-worker` | `KEEP` | Registration Result + Consumption | Slot Outcome distinct et publication ultérieure indépendante. |
| `runtime-latest-known-version-consumption-worker` | `TBD` | LKV max + Consumption | Loop indépendante prouvée ; processus/POM séparé seulement provisoire, selon option M. |

## P. Target dependency graph

Dans ce premier schéma, `A → B` signifie **A dépend de B** ; les chaînes du tableau suivant sont, elles, des séquences de traitement. Aucun module générique Consumption ne pointe vers une capacité.

```text
application capability ──────→ domain values ; transaction port if needed
Consumption use cases ──────→ domain Consumption model ; transaction port
generic orchestrators ──────→ Consumption use cases
polling lifecycle ───────────→ generic orchestrators
capability/Consumption glue ─→ capability semantics ; generic orchestrators
                              (runtime-local for Command/Event/Binding/Results/Registration)
HTTP/auth adapter ───────────→ Pot query entry ─→ exact Pot read ; TBD-E2U port
runtime composition ─────────→ capabilities ; glue ; polling ; SQL/JSON adapters
SQL/JSON adapters ───────────→ application/domain ports ; never the reverse
```

| Chaîne | Direction cible |
|---|---|
| Command | HTTP admission → Command request ; Command runtime → Command glue → Command/Pot mutation + Consumption → outcome/terminal Event. |
| Registration | HTTP admission → request ; Registration runtime → Registration glue → Binding/User/outcome + Consumption. |
| Command Result | Result runtime → glue → terminal Event/outcome/Command source → Command Result materializer + Consumption ; HTTP → Result GET. |
| Registration Result | Result runtime → glue → request/outcome → Registration Result materializer + Consumption ; HTTP → Registration GET. |
| Event→Task | Event runtime → Event glue/policy → Task `ensure` + Consumption. |
| Task→Projection | Task runtime → Task-specific orchestrator → input loader → pure projector → key check/output validator → exact store + fenced finalize. |
| Binding→Current | Binding runtime → fact discovery → Current Binding apply + Consumption → READ current store ; HTTP → current GET. |
| GET Pot | HTTP → Pot read entry → `TBD-E2U` → AUTH@V → READ_POT@V exact. |
| LKV | Event Pot → `TBD-LKV` loop → max-upsert + Consumption ; process owner TBD. |

## Q. Forbidden dependencies

| From | Must NOT depend on | Why / exception |
|---|---|---|
| Domain and pure projection | Spring/JPA/SQL adapters | Pure values and deterministic projectors; TM01-10 block these imports. |
| Generic Consumption model/services/orchestrators | Command, Registration, Binding, Event, Task, Results, LKV | Fencing/retry protocol must stay reusable ; Task specialization is on Task side (BC-06/07). |
| Polling lifecycle | Business semantics, JDBC stores | Loop invokes a generic orchestrator ; runtimes compose specifics. |
| Application capability | Runtime class or Spring composition | Same rule must survive process changes. |
| Pure projector and output validator | SQL, worker, transaction manager | `inputs@V → desired projection` plus validation must be independent. |
| HTTP controller | PRIMARY resolver implementation or transaction policy | HTTP translates ; Pot query entry owns orchestration, source via TBD port. |
| Pot exact query and Current Binding query | PRIMARY adapter implementation | Exact READ path stays behind READ ports ; **TBD-E2U** may temporarily call PRIMARY through an explicit query port, not hidden in controller. |
| Capability A | Entire capability B merely for one value type | Avoid accidental closure; **exception TBD-COMMAND-CONTRACT** retains Result→Command until a neutral contract is justified. |
| Runtime A | Runtime B | Deployment roots remain composable separately, even if loops could later cohabit a process. |

## R. Current→Target mapping for all 51 modules

`MERGE INTO` et `DISSOLVE AS MAVEN BOUNDARY` ne demandent aucun changement immédiat ; ils décrivent seulement la topologie proposée. Les lignes `TBD` ne sont pas des décisions cachées. `TM-xx` renvoie au catalogue S.

| Current module | Target disposition | Target owner/boundary | Notes |
|---|---|---|---|
| `domain-authorization` | KEEP | TM-01 | Kernel pur. |
| `domain-event` | KEEP | TM-02 | Event contract sans cycle Pot. |
| `domain-user-identity` | SPLIT RESPONSIBILITIES | TM-03, TM-04 ; E→U TBD | Valeurs/facts séparés des ports d'autorité primaire. |
| `authentication-contracts` | KEEP | TM-05 | Principal neutre sans Spring Security. |
| `domain-pot` | KEEP | TM-06 | Modèle Pot + RecordedEvent dépendant de BusinessEvent. |
| `domain-pot-projection` | MERGE INTO | TM-10 | Définitions exactes Pot. |
| `domain-projection-balance` | MERGE INTO | TM-10 | Modèle/calcul BALANCES. |
| `domain-projection` | KEEP | TM-09 | Key/artifact/validator pur. |
| `domain-pot-policy` | KEEP | TM-07 | Kernel Authorization Pot. |
| `domain-consumption` | KEEP | TM-08 | Modèle claim/lease + segmentation. |
| `engine-core` | DISSOLVE AS MAVEN BOUNDARY | TM-11, TM-06, TM-08, TM-17, TM-28 | Détail des familles en J ; aucun nouveau « misc core ». |
| `engine-consumption` | KEEP | TM-12 | Use cases et transaction Consumption. |
| `engine-command` | KEEP | TM-15 | Command identity/execution/outcome. |
| `engine-command-result` | KEEP | TM-18 | Contrat Result + matérialisation/GET. |
| `engine-registration` | KEEP | TM-19 | Capacité cohérente, boucles séparées. |
| `engine-processing-event` | SPLIT RESPONSIBILITIES | TM-20, TM-26 TBD | Event→Task vs LKV. |
| `engine-pot-command` | KEEP | TM-17 | Pot mutation + snapshots locaux. |
| `engine-projection-contracts` | KEEP | TM-21 | Ports exacts. |
| `engine-projection-read` | KEEP | TM-22 | Query exacte hors Task. |
| `engine-projection-task` | KEEP | TM-23 | Task + orchestration spécifique. |
| `engine-pot-read` | KEEP | TM-24 ; E→U TBD | Lecture Pot exacte et point d'entrée de query. |
| `engine-projection-balance` | MERGE INTO | TM-10 | Projector/calcul pur BALANCES. |
| `engine-projection-pot` | MERGE INTO | TM-10 | Projectors/loaders abstraits Pot. |
| `engine-read-projection` | SPLIT RESPONSIBILITIES | TM-25, TM-10, TM-26 TBD | Current Binding, historique Pot, LKV distincts. |
| `observability` | KEEP | TM-31 | Contrat trace technique neutre. |
| `infra-tx-spring` | KEEP | TM-27 | Adapter TransactionRunner. |
| `infra-persistence-jpa` | KEEP | TM-28 | Clusters séparés en packages ; pas de split POM présumé. |
| `infra-projection-persistence` | KEEP | TM-29 | Store exact, reçoit JSON Schema adapter. |
| `infra-read-persistence` | KEEP | TM-30 ; part LKV TBD | Binding current + LKV SQL provisoire. |
| `infra-projection-json-schema` | MERGE INTO | TM-29 | Adapter technique, POM autonome faible. |
| `orchestrator-consumption` | SPLIT RESPONSIBILITIES | TM-13, TM-23 | Deux orchestrateurs génériques vs spécialisation Task. |
| `orchestrator-command-admission` | KEEP | TM-16 | Admission AuthN distincte d'exécution Command. |
| `supra-consumption-worker` | KEEP | TM-14 | Polling partagé par huit runtimes. |
| `locator-consumption-event` | DISSOLVE AS MAVEN BOUNDARY | TM-20, TM-34 | Sémantique Event ; glue runtime Event. |
| `locator-consumption-latest-known-version` | DISSOLVE AS MAVEN BOUNDARY | TM-26 TBD, TM-40 TBD | Max/policy ; glue runtime LKV. |
| `locator-consumption-binding` | DISSOLVE AS MAVEN BOUNDARY | TM-25, TM-36 | Sémantique Current Binding ; glue runtime Binding. |
| `locator-consumption-command` | DISSOLVE AS MAVEN BOUNDARY | TM-15, TM-33 | Sémantique Command ; glue runtime Command. |
| `binding-pot-command-spring` | DISSOLVE AS MAVEN BOUNDARY | TM-33 | Binding Spring utilisé uniquement par runtime Command. |
| `supra-http-write-command` | DISSOLVE AS MAVEN BOUNDARY | TM-32 | Adapter HTTP local au runtime Web. |
| `supra-http-read-query` | SPLIT RESPONSIBILITIES | TM-32, TM-24 ; E→U TBD | HTTP vs orchestration Pot read. |
| `supra-authentication-spring-security` | DISSOLVE AS MAVEN BOUNDARY | TM-32 | Adapter Spring Security local Web. |
| `runtime-web-api` | KEEP | TM-32 | Composition + adapters HTTP locaux. |
| `runtime-event-consumption-worker` | KEEP | TM-34 | Glue Event et polling. |
| `runtime-command-result-consumption-worker` | KEEP | TM-37 | Glue Result et polling, policy sémantique dans TM-18. |
| `runtime-registration-result-consumption-worker` | KEEP | TM-39 | Glue Result et polling, policy dans TM-19. |
| `runtime-registration-consumption-worker` | KEEP | TM-38 | Glue Registration et polling, policy dans TM-19. |
| `runtime-latest-known-version-consumption-worker` | TBD | TM-40 TBD | Loop indépendante, processus à décider. |
| `runtime-binding-consumption-worker` | KEEP | TM-36 | Glue Fact→Current et polling. |
| `runtime-task-consumption-worker` | KEEP | TM-35 | Task, Pot producers, exact store. |
| `runtime-command-consumption-worker` | KEEP | TM-33 | Glue Command, Spring Binding, polling. |
| `architecture-tests` | KEEP | TM-41 | Gate global, pas dans les slices par défaut. |

## S. Target module catalog

`Owner` désigne l’owner fonctionnel. Les `Dependencies` et `Dependents` sont les **arcs structurants envisagés**, pas un POM exhaustif prêt à compiler. Les noms sont discutables ; le test « Why Maven rather than package? » porte sur la frontière, pas sur l'étiquette. `Status: TBD` réserve une zone sans choisir son owner final.

### TARGET MODULE TM-01

Name: `domain-authorization` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: kernel d'accès pur ; Owner: Authorization.
Responsibilities: capacités, permissions, traduction ; Excluded responsibilities: JWT, HTTP, SQL et décision sur une version Pot non chargée.
Dependencies: aucune interne ; Dependents: `domain-pot-policy`, Command, Pot read.
Forbidden dependency protected: calcul d'accès → Spring/JPA. Why Maven rather than package? Consumers métier partagent le calcul sans tirer le runtime Web ni persistence.
Supporting BC: BC-09, BC-16 ; Current sources: `domain-authorization`.

### TARGET MODULE TM-02

Name: `domain-event` ; Status: **PROPOSED** ; Boundary strength: **MEDIUM**.
Purpose: contrat Event minimal ; Owner: Event.
Responsibilities: identité/types Event indépendants de Pot ; Excluded responsibilities: `RecordedEvent<BusinessEvent>`, Event→Task, LKV et SQL.
Dependencies: aucune interne ; Dependents: `domain-pot`, Command, Event→Task.
Forbidden dependency protected: contrat Event de base → Pot ou moteur Event. Why Maven rather than package? Il évite le cycle `domain-event ↔ domain-pot` pour les consommateurs du contrat minimal.
Supporting BC: BC-08, BC-16 ; Current sources: `domain-event`.

### TARGET MODULE TM-03

Name: `domain-user-identity` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: valeurs Identity/Binding et facts purs ; Owner: Identity/Binding.
Responsibilities: E, U, B, R, occurrence, Attached/Detached facts ; Excluded responsibilities: ports de mutation PRIMARY, resolver HTTP, SQL.
Dependencies: aucune interne ; Dependents: TM-04, Command, Registration, TM-25, persistence.
Forbidden dependency protected: lecture Current Binding → ports d'autorité primaire. Why Maven rather than package? Un POM séparé permet à la query d'importer les faits/valeurs sans recevoir les ports WRITE.
Supporting BC: BC-08, BC-14, BC-16 ; Current sources: valeurs et facts de `domain-user-identity`.

### TARGET MODULE TM-04

Name: `binding-authority-contracts` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: contrats d'arbitrage/mutation Binding et User ; Owner: Binding authority.
Responsibilities: acquire/detach, stream/fact authority, User authority et résultats d'arbitrage ; Excluded responsibilities: Current Binding GET, PRIMARY SQL, source future E→U.
Dependencies: TM-03 ; Dependents: Command, Registration, runtime Binding et TM-28.
Forbidden dependency protected: TM-25/queries → ports PRIMARY. Why Maven rather than package? Cette coupure empêche précisément la dépendance READ vers l'autorité actuellement cohébergée avec les valeurs.
Supporting BC: BC-08, BC-14, BC-16 ; Current sources: ports/`BindingAcquireResult`/`BindingDetachResult` de `domain-user-identity`.

### TARGET MODULE TM-05

Name: `authentication-contracts` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: principal E attesté sans technologie de sécurité ; Owner: AuthN contract.
Responsibilities: `AuthenticatedExternalPrincipal` ; Excluded responsibilities: Spring Security, JWT, HTTP.
Dependencies: TM-03 ; Dependents: Command admission, Web adapters.
Forbidden dependency protected: admission applicative → Spring Security. Why Maven rather than package? Le même contrat traverse l'admission et le runtime Web sans dépendance technique inverse.
Supporting BC: BC-16 ; Current sources: `authentication-contracts`.

### TARGET MODULE TM-06

Name: `domain-pot` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: modèle Pot/Expense et BusinessEvent ; Owner: Pot.
Responsibilities: modèle métier, Event Pot, `RecordedEvent` et trace associée au BusinessEvent ; Excluded responsibilities: mutation SQL, projector, TransactionRunner.
Dependencies: TM-02 ; Dependents: Pot command, Pot projection, Event→Task, TM-28.
Forbidden dependency protected: modèle Pot/recording → JPA ou engine Event. Why Maven rather than package? Pot command et projectors réutilisent le modèle sans importer un runtime ni créer le cycle Event/Pot.
Supporting BC: BC-09, BC-11, BC-16 ; Current sources: `domain-pot`, `engine-core/RecordedEvent`, `EventTraceMetadata`.

### TARGET MODULE TM-07

Name: `domain-pot-policy` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: kernel de décision Pot pur partagé WRITE/READ ; Owner: Pot Authorization.
Responsibilities: règles/facts d'accès Pot ; Excluded responsibilities: AUTH store, contrôleur HTTP, SQL.
Dependencies: TM-01, TM-06 ; Dependents: Pot command, TM-24.
Forbidden dependency protected: politique d'accès → adapter PRIMARY/READ. Why Maven rather than package? Deux capacités de sens opposés réutilisent le même kernel sans importer leurs moteurs respectifs.
Supporting BC: BC-09, BC-16 ; Current sources: `domain-pot-policy`.

### TARGET MODULE TM-08

Name: `domain-consumption` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: modèle du protocole concurrent ; Owner: Consumption protocol.
Responsibilities: key, slot, claim, lease, provenance, `WorkerSegment`/`PartitionHash` ; Excluded responsibilities: transacteurs, polling et règles Command/Result.
Dependencies: aucune interne ; Dependents: TM-12/13, Event, TM-28, runtimes workers.
Forbidden dependency protected: modèle de concurrence → Spring/JPA et capacités. Why Maven rather than package? Les capacités et adapters partagent les identités/segments sans dépendre des use cases Consumption.
Supporting BC: BC-06, BC-11, BC-16 ; Current sources: `domain-consumption`, segmentation de `engine-core`.

### TARGET MODULE TM-09

Name: `domain-projection` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: identité et résultat exacts de projection ; Owner: exact projection contract.
Responsibilities: ProjectionKey, artifact, validator de sortie et valeurs JSON ; Excluded responsibilities: Task polling, SQL, Networknt.
Dependencies: aucune interne ; Dependents: TM-10/21/22/23/24, TM-29.
Forbidden dependency protected: identité/validation exactes → stores et bibliothèque de schema. Why Maven rather than package? Query et producer réutilisent la même key/validation sans importer Task ou adapters.
Supporting BC: BC-09, BC-10, BC-16 ; Current sources: `domain-projection`.

### TARGET MODULE TM-10

Name: `projection-pot` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: producteurs exacts Pot purs ; Owner: Pot projection.
Responsibilities: définitions AUTH/READ_POT/BALANCES, calcul Balance, loaders abstraits, projectors, contrat de reconstruction historique ; Excluded responsibilities: SQL loader concret, Task worker, publication et GET métier.
Dependencies: TM-06, TM-09, contrats producer minimaux de TM-21 ; Dependents: TM-20/24, runtimes Event/Task, TM-28 pour implémenter loaders.
Forbidden dependency protected: calcul `inputs@V → projection` → JPA/Spring. Why Maven rather than package? Le producer est utilisable et testable sans runtime ni adapter, contrairement à un package dans Task/runtime.
Supporting BC: BC-08, BC-09, BC-10 ; Current sources: `domain-pot-projection`, `domain-projection-balance`, `engine-projection-pot`, `engine-projection-balance`, source historique de `engine-read-projection`.

### TARGET MODULE TM-11

Name: `application-transaction-port` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: abstraction transactionnelle applicative stable ; Owner: cross-cutting application contract.
Responsibilities: `TransactionRunner` ; Excluded responsibilities: Spring, datasource, orchestration métier et autres contrats « core ».
Dependencies: aucune interne ; Dependents: TM-12, Registration, Command admission, runtimes et TM-27.
Forbidden dependency protected: use cases → `SpringTransactionRunner`. Why Maven rather than package? Port partagé par plusieurs capacités et neuf compositions sans imposer Spring ni un nouveau `engine-core` hétérogène.
Supporting BC: BC-11, BC-12 ; Current sources: `engine-core/TransactionRunner`.

### TARGET MODULE TM-12

Name: `engine-consumption` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: use cases acquire/execute/finalize/failure ; Owner: Consumption protocol.
Responsibilities: CAS, fencing, provenance, retries génériques et wrappers transactionnels ; Excluded responsibilities: Command, Task, Result, polling.
Dependencies: TM-08, TM-11 ; Dependents: TM-13/23, TM-28 et runtimes.
Forbidden dependency protected: protocole transactionnel → capacités concrètes. Why Maven rather than package? Les mêmes opérations sont réutilisées par huit workers et adapters sans importer leurs métiers.
Supporting BC: BC-06, BC-07 ; Current sources: `engine-consumption`.

### TARGET MODULE TM-13

Name: `orchestrator-consumption` ; Status: **PROPOSED** ; Boundary strength: **MEDIUM**.
Purpose: deux formes génériques d'orchestration ; Owner: Consumption protocol.
Responsibilities: Sequential et AcquireThenFinalize, contrats de locator/search ; Excluded responsibilities: `ProjectionTaskConsumptionOrchestrator`, SQL et capacité spécifique.
Dependencies: TM-08, TM-12 ; Dependents: TM-14/23 et runtimes workers.
Forbidden dependency protected: use cases bas niveau → stratégies de découverte/préparation. Why Maven rather than package? Le module permet aux use cases de rester indépendants des boucles/callbacks des orchestrateurs ; la valeur physique est à revérifier lors du POM cible.
Supporting BC: BC-06, BC-07 ; Current sources: parties génériques de `orchestrator-consumption`.

### TARGET MODULE TM-14

Name: `supra-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: moteur de polling/lifecycle commun ; Owner: technical worker lifecycle.
Responsibilities: boucle, waiter, budget, paramètres de polling ; Excluded responsibilities: identité métier consommable, SQL, policy fonctionnelle.
Dependencies: TM-13, TM-08 ; Dependents: huit runtimes workers.
Forbidden dependency protected: protocole/capacités → scheduling/threads. Why Maven rather than package? Recomposition dans un runtime ferait dépendre les sept autres de ce runtime ; colocation avec TM-12 ferait entrer polling dans le protocole.
Supporting BC: BC-06, BC-16 ; Current sources: `supra-consumption-worker`.

### TARGET MODULE TM-15

Name: `engine-command` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: capacité Command WRITE ; Owner: Command.
Responsibilities: RecordedCommand, CommandId/Outcome, exécution, exact E+B fence, sémantique de failure ; Excluded responsibilities: polling, SQL, HTTP et Result materializer.
Dependencies: TM-03/04, TM-01, TM-08 ; Dependents: TM-16/17/18, TM-28 et runtime Command.
Forbidden dependency protected: exécution Command → runtime/adapters. Why Maven rather than package? Admission, Pot dispatch, Result et persistence partagent ses contrats sans importer la composition du worker.
Supporting BC: BC-03, BC-04, BC-05, BC-16 ; Current sources: `engine-command`, sémantique de `locator-consumption-command`.

### TARGET MODULE TM-16

Name: `orchestrator-command-admission` ; Status: **PROPOSED** ; Boundary strength: **MEDIUM**.
Purpose: admission authentifiée Command ; Owner: Command.
Responsibilities: vérification E+B et persistence de request avant 202 ; Excluded responsibilities: exécution fenced, HTTP controller et Spring Security.
Dependencies: TM-05/03/04, TM-15, TM-11 ; Dependents: Web, TM-28.
Forbidden dependency protected: moteur d'exécution Command → contrat AuthN/admission. Why Maven rather than package? L'admission Web partage les types Command mais le worker d'exécution n'a pas besoin de la dépendance AuthN.
Supporting BC: BC-16 ; Current sources: `orchestrator-command-admission`.

### TARGET MODULE TM-17

Name: `engine-pot-command` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: mutation métier Pot sous Command ; Owner: Pot WRITE.
Responsibilities: use cases, snapshots/versions Pot, UserContext et erreurs Pot ; Excluded responsibilities: JPA, polling, READ exact.
Dependencies: TM-06/07/15, TM-11 si requis ; Dependents: TM-28, runtime Command.
Forbidden dependency protected: algorithmes Pot WRITE → SQL/HTTP. Why Maven rather than package? Command générique peut dispatcher Pot sans posséder chaque règle Pot ; Pot READ n'importe pas la mutation.
Supporting BC: BC-09, BC-11, BC-16 ; Current sources: `engine-pot-command`, familles Pot/UserContext/errors de `engine-core`.

### TARGET MODULE TM-18

Name: `engine-command-result` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: résultat terminal corrélé à Command ; Owner: Command Result.
Responsibilities: modèle immutable, owner E, cohérence terminale, materializer, GET et policy sémantique ; Excluded responsibilities: SQL, locator glue, backoff, ProjectionTask.
Dependencies: TM-15 (provisoirement, BC-04), TM-03 ; Dependents: runtime Result, Web, TM-28.
Forbidden dependency protected: contrat Result → worker/HTTP/SQL et Result → Current Binding. Why Maven rather than package? Deux processus + Web partagent résultat/ownership sans importer un runtime ; la dépendance Command reste une exception documentée.
Supporting BC: BC-03, BC-04, BC-05 ; Current sources: `engine-command-result`, sémantique de `CommandResultConsumptionLocator`.

### TARGET MODULE TM-19

Name: `engine-registration` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: protocole fonctionnel Registration entier ; Owner: Registration.
Responsibilities: request/admission, arbitrage Binding/User, outcome, Result immutable, materializer, GET et policies sémantiques des deux loops ; Excluded responsibilities: claim, polling, SQL, HTTP, backoff.
Dependencies: TM-03/04, TM-11 ; Dependents: Web, runtimes Registration/Result et TM-28.
Forbidden dependency protected: règles Registration/Result → implémentations de worker et SQL. Why Maven rather than package? Admission Web et deux workers utilisent une même capacité sans importer leurs runtimes entre eux ni une infrastructure READ générique.
Supporting BC: BC-01, BC-02, BC-05 ; Current sources: `engine-registration`, policies sémantiques des deux locators runtime.

### TARGET MODULE TM-20

Name: `engine-event-to-task` ; Status: **PROPOSED** ; Boundary strength: **MEDIUM**.
Purpose: politique Event→ProjectionTask Pot ; Owner: Event/production Pot READ.
Responsibilities: table EventType→ProjectionType, contrat metadata-only et sémantique `ensure Task` ; Excluded responsibilities: Event SQL, locator glue, LKV, projector et polling.
Dependencies: TM-02/06/09/10/23 ; Dependents: runtime Event, TM-28.
Forbidden dependency protected: décision Event→Task → Spring runtime et LKV. Why Maven rather than package? Le policy/contrat est utilisé par adapter et runtime sans faire dépendre le moteur Task de la composition Event.
Supporting BC: BC-05, BC-08, BC-09 ; Current sources: partie Event de `engine-processing-event`, `PocomaProjectionMaterializationPolicy`, sémantique de `locator-consumption-event`.

### TARGET MODULE TM-21

Name: `engine-projection-contracts` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: ports exacts neutres READ/publication ; Owner: exact projection contract.
Responsibilities: contrats de lecture/publication et interfaces minimales `ProjectionInputLoader`/`ProjectionProjector` ; Excluded responsibilities: Task scheduling, SQL, JSON Schema concret et GET Pot métier.
Dependencies: TM-09 ; Dependents: TM-10/22/23/29.
Forbidden dependency protected: query/adapters de projection → engine Task. Why Maven rather than package? Web/query et Task/store partagent les ports sans importer le traitement asynchrone.
Supporting BC: BC-09, BC-10 ; Current sources: `engine-projection-contracts`.

### TARGET MODULE TM-22

Name: `engine-projection-read` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: lecture/revalidation exacte générique ; Owner: exact projection read.
Responsibilities: résolution d'une ProjectionKey exacte et états READY/FAILED/non prêt ; Excluded responsibilities: scheduling Task, AUTH business interpretation, SQL.
Dependencies: TM-09/21 ; Dependents: TM-24 et Web.
Forbidden dependency protected: query exacte → Task/worker et fallback de version. Why Maven rather than package? Les queries génériques n'ont aucune raison d'importer le catalogue/consommateur Task.
Supporting BC: BC-09, BC-10 ; Current sources: `engine-projection-read`.

### TARGET MODULE TM-23

Name: `engine-projection-task` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: exécution et finalisation de ProjectionTask ; Owner: ProjectionTask.
Responsibilities: Task identity/store ports, catalogue des producteurs, load→project→key check→validate, retry, publication et orchestrateur Task spécialisé ; Excluded responsibilities: interfaces minimales Loader/Projector de TM-21, projectors Pot concrets, generic Consumption, SQL/JSON Schema adapter.
Dependencies: TM-09/21/12/13/08 ; Dependents: TM-10/20, runtime Task, TM-28.
Forbidden dependency protected: orchestrateurs Consumption génériques → ProjectionTask ; projectors purs → polling. Why Maven rather than package? Côté capacité, il peut importer le protocole sans inversion de dépendance dans le POM générique.
Supporting BC: BC-07, BC-09, BC-10 ; Current sources: `engine-projection-task`, `ProjectionTaskConsumptionOrchestrator` de `orchestrator-consumption`.

### TARGET MODULE TM-24

Name: `engine-pot-read` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: query Pot exacte et entrée applicative hors HTTP ; Owner: Pot read/Authorization pour la lecture, `TBD-E2U` pour résolution caller.
Responsibilities: interprétation AUTH@V/READ_POT@V, contrôle d'accès, facade qui appelle un port neutre E→U ; Excluded responsibilities: contrôleur, adapter PRIMARY/READ E→U, choix de source, worker Task.
Dependencies: TM-01/07/06/09/10/22, TM-03 pour types E/U ; Dependents: Web.
Forbidden dependency protected: HTTP controller → PRIMARY adapter/autorité Binding ; query exacte → Task runtime. Why Maven rather than package? La même orchestration applicative reste testable avec une autre source E→U et sans HTTP ; son owner final E→U demeure TBD.
Supporting BC: BC-09, BC-14 ; Current sources: `engine-pot-read`, partie non HTTP de `supra-http-read-query`.

### TARGET MODULE TM-25

Name: `engine-binding-read` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: CURRENT_BINDING convergent et GET self ; Owner: Binding.
Responsibilities: modèle courant, règle R/tombstone/divergence, apply fact et query READ ; Excluded responsibilities: mutation authority, PRIMARY resolver, SQL et polling.
Dependencies: TM-03 seulement pour valeurs/facts ; Dependents: runtime Binding, Web et TM-30.
Forbidden dependency protected: Binding READ → ports d'autorité primaire de TM-04. Why Maven rather than package? Le GET peut importer sa capacité READ sans prendre l'API d'acquisition/détachement Binding.
Supporting BC: BC-08, BC-16 ; Current sources: partie Current Binding de `engine-read-projection`, sémantique de `locator-consumption-binding`.

### TARGET MODULE TM-26

Name: `TBD-LKV-work` ; Status: **TBD** ; Boundary strength: **MEDIUM** (provisoire).
Purpose: isoler la règle max observé et sa consommation des autres capacités ; Owner: `TBD-LKV`.
Responsibilities: identité watermark, avance max, classification sémantique LKV ; Excluded responsibilities: Current Binding, readiness Task, règle de version Pot current.
Dependencies: TM-06/08/12/13 selon option M ; Dependents: runtime LKV conditionnel, TM-28/30.
Forbidden dependency protected: Binding READ et projection Pot → règles de watermark non prouvées comme communes. Why Maven rather than package? L'isolation évite de réintroduire `engine-read-projection` mixte ; la nécessité durable de ce POM reste TBD avec l'owner.
Supporting BC: BC-08, BC-15 ; Current sources: parties LKV de `engine-read-projection`/`engine-processing-event` et sémantique de `locator-consumption-latest-known-version`.

### TARGET MODULE TM-27

Name: `infra-tx-spring` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: adapter Spring du port transactionnel ; Owner: technical transaction adapter.
Responsibilities: `SpringTransactionRunner`/TransactionTemplate ; Excluded responsibilities: décision métier et boundary de commit propre à une capacité.
Dependencies: TM-11, Spring ; Dependents: les neuf compositions actuelles selon leurs transactions.
Forbidden dependency protected: application/Consumption → Spring transaction classes. Why Maven rather than package? Les use cases partagés ne tirent pas Spring, et chaque runtime choisit le manager concret.
Supporting BC: BC-11, BC-12 ; Current sources: `infra-tx-spring`.

### TARGET MODULE TM-28

Name: `infra-persistence-jpa` ; Status: **PROPOSED** ; Boundary strength: **STRONG** (frontière externe application→SQL ; granularité interne non décidée).
Purpose: adapters PRIMARY et discovery sur PostgreSQL/JPA/JDBC ; Owner: adapter technique, pas les capacités.
Responsibilities: clusters Command/Pot, Registration/Binding, Consumption CAS, Results directs, Event/Task discovery et loaders historiques ; Excluded responsibilities: policy métier, current READ store, JSON Schema, runtime scheduling.
Dependencies: ports TM-04/12/15/16/17/18/19/20/23/25/26, modèles/domain ; Dependents: neuf runtimes par composition.
Forbidden dependency protected: moteurs/domaines → JPA/JDBC. Why Maven rather than package? Les engines importent leurs ports sans classe SQL ; un sous-POM par cluster n'est pas démontré nécessaire pour préserver les transactions.
Supporting BC: BC-12, BC-13 ; Current sources: `infra-persistence-jpa`, wrappers legacy Pot/Event de `engine-core`.

### TARGET MODULE TM-29

Name: `infra-projection-adapters` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: adapters techniques de projection exacte ; Owner: projection infrastructure.
Responsibilities: persistence root/artifact/failure, validation JSON Schema Networknt ; Excluded responsibilities: projector Pot, validation de key, Task orchestration.
Dependencies: TM-09/21, SQL/JSON libraries ; Dependents: Web et runtime Task.
Forbidden dependency protected: projectors/domain → SQL et Networknt. Why Maven rather than package? Deux runtimes composent ces adapters sans faire entrer les bibliothèques dans le calcul pur ; deux POM adapters séparés ne protègent ici aucun import additionnel prouvé.
Supporting BC: BC-09, BC-10, BC-12 ; Current sources: `infra-projection-persistence`, `infra-projection-json-schema`.

### TARGET MODULE TM-30

Name: `infra-read-persistence` ; Status: **PROPOSED** ; Boundary strength: **MEDIUM**.
Purpose: adapters SQL des vues READ directes ; Owner: technical READ storage.
Responsibilities: current Binding upsert/query et LKV max-upsert provisoire ; Excluded responsibilities: autorité Binding, exact projection store, sémantique Current/LKV.
Dependencies: TM-25 et TM-26 TBD ; Dependents: Web, runtime Binding/Task/LKV selon wiring actuel.
Forbidden dependency protected: capacités READ → JDBC concret. Why Maven rather than package? Ports Current Binding et LKV restent sans SQL ; leur colocation physique ne signifie pas owner commun et sera revue après BC-15.
Supporting BC: BC-08, BC-12, BC-13, BC-15 ; Current sources: `infra-read-persistence`.

### TARGET MODULE TM-31

Name: `observability` ; Status: **PROPOSED** ; Boundary strength: **MEDIUM**.
Purpose: contrat de trace partagé sans provider ; Owner: technical observability.
Responsibilities: `TraceContext`/holder ; Excluded responsibilities: metrics runtime LKV, business Event trace semantics, SQL.
Dependencies: aucune interne ; Dependents: Web et TM-28.
Forbidden dependency protected: persistence trace → runtime Web/provider. Why Maven rather than package? Les deux couches partagent un petit contrat sans arc infra→runtime ; si cette interdiction est couverte autrement, POM révisable.
Supporting BC: BC-11, BC-16 ; Current sources: `observability`.

### TARGET MODULE TM-32

Name: `runtime-web-api` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: processus HTTP et composition ; Owner: deployment/Web adapters.
Responsibilities: Spring wiring, sécurité, controllers, DTO, adaptation HTTP locale ; Excluded responsibilities: owner historique des Results, E→U orchestration, Pot authorization policy.
Dependencies: TM-05/16/18/19/22/24/25/27/28/29/30/31 ; Dependents: aucun runtime.
Forbidden dependency protected: application → HTTP process/technologie. Why Maven rather than package? Racine exécutable déployable sans workers ; les anciens POM HTTP n'avaient que ce consommateur.
Supporting BC: BC-01, BC-03, BC-14, BC-16 ; Current sources: `runtime-web-api`, trois `supra-*` HTTP/auth.

### TARGET MODULE TM-33

Name: `runtime-command-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: composition du slot Command ; Owner: deployment Command worker.
Responsibilities: Spring lifecycle, backoff/segments, glue Command→Consumption, binding Spring Pot dispatch ; Excluded responsibilities: E+B fence policy, Pot mutation et claim générique.
Dependencies: TM-14/15/17/04/27/28 ; Dependents: aucun runtime.
Forbidden dependency protected: moteurs Command/Pot → processus worker/Spring. Why Maven rather than package? Processus exécutable et slice de preuve autonome ; glue locale ne requiert pas deux POM additionnels.
Supporting BC: BC-05, BC-06, BC-16 ; Current sources: runtime Command, `locator-consumption-command`, `binding-pot-command-spring`.

### TARGET MODULE TM-34

Name: `runtime-event-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: composition du slot Event→Task ; Owner: deployment Event worker.
Responsibilities: polling/lifecycle, glue metadata Event→Task et propriétés ; Excluded responsibilities: policy EventType→ProjectionType, LKV et projection calculée.
Dependencies: TM-14/20/23/27/28 ; Dependents: aucun runtime.
Forbidden dependency protected: politique Event→Task → Spring process. Why Maven rather than package? Event loop déployable et vérifiable sans runtime Task, même si Task contract est partagé.
Supporting BC: BC-05, BC-09, BC-16 ; Current sources: runtime Event, glue `locator-consumption-event`.

### TARGET MODULE TM-35

Name: `runtime-task-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: composition du slot ProjectionTask ; Owner: deployment Task worker.
Responsibilities: sélection de catalogue actif, properties, lifecycle, adapters/transaction wiring ; Excluded responsibilities: projection algorithm, key/output invariants et protocole CAS.
Dependencies: TM-14/23/10/27/28/29/30 ; Dependents: aucun runtime.
Forbidden dependency protected: Task/projectors → Spring process et SQL. Why Maven rather than package? Racine d'exécution indépendante de Web/Event, utile pour préparation longue et publication fenced.
Supporting BC: BC-07, BC-09, BC-16 ; Current sources: runtime Task.

### TARGET MODULE TM-36

Name: `runtime-binding-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: composition fact→CURRENT_BINDING ; Owner: deployment Binding worker.
Responsibilities: polling, discovery SQL, glue fact→apply, transaction wiring ; Excluded responsibilities: règle R/tombstone, authority mutation et GET self.
Dependencies: TM-14/25/03/04/27/28/30 ; Dependents: aucun runtime.
Forbidden dependency protected: capacité Current Binding → runtime/PRIMARY SQL. Why Maven rather than package? Slot Fact indépendant de Registration et processus vérifiable séparément.
Supporting BC: BC-06, BC-08, BC-16 ; Current sources: runtime Binding, glue `locator-consumption-binding`.

### TARGET MODULE TM-37

Name: `runtime-command-result-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: composition terminal Event→Command Result ; Owner: deployment Result worker.
Responsibilities: polling, glue locator, backoff, wiring source/store ; Excluded responsibilities: cohérence terminale, owner E, classification fonctionnelle.
Dependencies: TM-14/18/27/28 ; Dependents: aucun runtime.
Forbidden dependency protected: Command Result → Spring process/SQL. Why Maven rather than package? Result loop et commit séparés de Command execution ; POM exécutable sans importer runtime Command.
Supporting BC: BC-03, BC-05, BC-16 ; Current sources: runtime Command Result.

### TARGET MODULE TM-38

Name: `runtime-registration-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: composition du slot Registration Request ; Owner: deployment Registration worker.
Responsibilities: polling, glue locator, backoff, wiring Binding/User/persistence ; Excluded responsibilities: arbitrage Registration, policy fonctionnelle et Result GET.
Dependencies: TM-14/19/04/27/28 ; Dependents: aucun runtime.
Forbidden dependency protected: Registration → Spring process/SQL. Why Maven rather than package? Boucle Request indépendante et exécutable avec sa transaction gagnante ; pas d'ownership métier séparé.
Supporting BC: BC-01, BC-02, BC-05, BC-16 ; Current sources: runtime Registration.

### TARGET MODULE TM-39

Name: `runtime-registration-result-consumption-worker` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: composition du slot Registration Outcome→Result ; Owner: deployment Registration Result worker.
Responsibilities: polling, glue locator, backoff, wiring request/outcome/store ; Excluded responsibilities: Result invariants, owner E et règle de classification fonctionnelle.
Dependencies: TM-14/19/27/28 ; Dependents: aucun runtime.
Forbidden dependency protected: Registration Result → Spring process ; runtime Registration → runtime Result. Why Maven rather than package? Loop retry/finalisation indépendante à conserver pendant migration, sans imposer deux processus pour toujours.
Supporting BC: BC-01, BC-02, BC-05, BC-16 ; Current sources: runtime Registration Result.

### TARGET MODULE TM-40

Name: `runtime-latest-known-version-consumption-worker` ; Status: **TBD** ; Boundary strength: **MEDIUM** (provisoire).
Purpose: composition actuelle du slot LKV ; Owner: deployment TBD-LKV.
Responsibilities: loop, métriques wrapper, glue et wiring tant que processus séparé ; Excluded responsibilities: current Pot resolver, readiness projection, Current Binding semantics.
Dependencies: TM-14/26/27/28/30/31 ; Dependents: aucun runtime.
Forbidden dependency protected: autres runtimes → LKV worker. Why Maven rather than package? Aujourd'hui racine indépendante ; la nécessité future de son processus/POM dépend du besoin aval et de l'option M.
Supporting BC: BC-08, BC-15, BC-16 ; Current sources: runtime LKV, glue `locator-consumption-latest-known-version`.

### TARGET MODULE TM-41

Name: `architecture-tests` ; Status: **PROPOSED** ; Boundary strength: **STRONG**.
Purpose: preuve structurelle/globale séparée des slices locales ; Owner: engineering verification.
Responsibilities: tests d'architecture et intégration globale requis aux gates ; Excluded responsibilities: code de production et lancement automatique à chaque changement documentaire.
Dependencies: modules sous test au scope de test ; Dependents: aucun module de production.
Forbidden dependency protected: slices locales de build → gate global par défaut. Why Maven rather than package? La sélection Maven explicite du gate rend son coût et sa portée visibles.
Supporting BC: BC-16 ; Current sources: `architecture-tests`.

## T. Remaining unresolved zones

1. **TBD-LKV** : owner, fonction aval, app POM et processus final. TM-26/40 sont une enveloppe isolante, pas une décision de catégorie.
2. **TBD-E2U** : owner fonctionnel de la résolution du caller, source PRIMARY/READ et garanties de retard/révocation. TM-24 n'héberge que le point d'entrée applicatif et un port neutre provisoire.
3. **TBD-COMMAND-CONTRACT** : l'arc TM-18→TM-15 reste. Aucun POM de contrats créé sans preuve que `CommandId`/`CommandOutcome` seuls justifient la coupure.
4. **Persistence interne** : TM-28 garde plusieurs clusters ; un futur split doit démontrer un import interdit et tracer les transactions cross-adapter, pas seulement constater des packages distincts.
5. **Valeur MEDIUM** : TM-02/13/16/20/30/31 requièrent une mesure des imports et closures au plan de migration avant verrouillage physique. Leur dissolution éventuelle ne change pas les owners conceptuels.

## U. Migration ordering constraints

Sans plan de commits : (1) stabiliser/placer les valeurs et ports partagés, notamment TM-03/04/11, avant de dissoudre `engine-core` ; (2) séparer les policies sémantiques des locators avant d'enlever leurs POM ou de simplifier les runtimes ; (3) sortir la spécialisation Task de la frontière générique avant de fermer l'arc `orchestrator-consumption → engine-projection-task` ; (4) établir le point d'entrée Pot read et son port E→U avant de retirer l'orchestration du contrôleur, tout en gardant le scénario PRIMARY compatible ; (5) déplacer les adapters par cluster en vérifiant que transaction manager, datasource et effet+provenance+CAS restent atomiques ; (6) laisser chaque runtime exécutable à chaque étape et conserver les identités de slots. La rationalisation pure Pot/JSON Schema/HTTP peut être instruite indépendamment après stabilisation des contrats. Aucun Step d'implémentation ni séquence de commits détaillée n'est créé ici.

## V. Conclusion et validation conceptuelle

| Chaîne | Pourquoi la topologie permet de préserver l'invariant CURRENT |
|---|---|
| Command | TM-15 garde E+B et le fence ; TM-17/28 exécutent mutation, outcome et terminal Event sous le commit gagnant avec TM-12. La séparation Result ultérieure reste explicite. |
| Registration | TM-19 garde l'arbitrage ; TM-04/28 composent User, Binding, facts et outcome dans la transaction fenced, donc pas de User orphelin sur conflit. |
| Deux Results | TM-18/19 gardent `0..1`, immutabilité et owner E historique ; TM-28 garde contraintes/idempotence ; TM-37/39 gardent slots indépendants. Aucun Result ne passe par Task. |
| CURRENT_BINDING | TM-25 garde R, tombstone et divergence interdite ; TM-36/30 publient directement depuis fact et finalisent fenced. Aucun passage Event→Task. |
| Consumption | TM-08/12/13 maintiennent claim séparé, puis effet+provenance+CAS dans un seul commit ; TM-14 ne peut pas posséder la policy métier. |
| Projection Pot | TM-23 charge l'input @V, appelle TM-10 pur, contrôle key et sortie ; TM-29 publie root/artifact immuables dans la finalisation gagnante. |
| GET Pot | TM-24 exige AUTH@V puis READ_POT@V exact, sans fallback ; E→U est déclaré via port TBD, et le scénario PRIMARY reste explicitement compatible CURRENT. |
| LKV | TM-26/40 provisoires conservent max-upsert et slot fenced ; aucune promesse current/readiness ni suppression. |

**Comptage en conséquence :** 51 modules actuels ; **41 emplacements de POM proposés**, dont **39 PROPOSED et deux TBD (TM-26, TM-40)**. Neuf runtimes actuels ; **huit runtimes conservés dans la proposition ferme et un runtime LKV TBD**. Le compte ferme n'est donc pas « 41 décidés » : il est **39 + 2 enveloppes conditionnelles** ; si LKV garde son work et son processus isolés, 41 et neuf runtimes. Toute autre option LKV exige de recompter. La diminution éventuelle vient surtout de la disparition des POM locators/HTTP locaux et de la réunion des producteurs Pot purs, compensée par les barrières de contrat transactionnel et d'autorité Binding. Elle n'est pas un objectif de réduction.

Cette cible reste une **hypothèse architecturale testable**. L'étape suivante devra valider ses arcs réels et les zones TBD, puis seulement ordonner une migration. Vérification de cette étape : documentaire et statique ; aucun slice Maven déclaré, aucun test/base/reactor ou gate global exécuté ; impact production nul.
