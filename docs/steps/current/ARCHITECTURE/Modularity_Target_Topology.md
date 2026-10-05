# DEBT-MOD-01 — topologie TARGET courante

**Statut : TARGET documentaire, non livrée.** Cette page est la vue normative de la topologie visée après C.2. Elle ne décrit ni les POM actuellement créés ni un plan de migration. Les décisions [BC-01 à BC-16](Modularity_Boundary_Challenge.md#p-boundary-decisions), la distinction [CURRENT](Modularity_Current_State_Audit.md)/TARGET et les invariants métier restent acquis. La [dette de modularité](../../../debts/MODULE_TAXONOMY/Debt.md) reste OPEN.

## 1. Baseline et portée

Après `git fetch origin` : HEAD local et `origin/v2-make-it-pull` = `ae802b5c845aeee2c4dd8af3aa84bce70a619302` ; divergence `0/0` ; working tree vide ; aucun commit depuis `ae802b5c`. Cette révision ne modifie que la présente TARGET. Aucun Java, POM, SQL, runtime ou test de production n'est modifié. Le reactor CURRENT reste celui de l'audit ; les chiffres ci-dessous sont ceux de la TARGET, sans optimisation du nombre de modules.

## 2. Grammaire et responsabilités

Grammaire officielle : `domain-*`, `contracts-*`, `port-*`, `engine-*`, `projector-*`, `orchestrator-*`, `supra-*`, `infra-*`, `runtime-*`, `architecture-tests`.

| Famille | Règle TARGET |
| --- | --- |
| domain | Modèle, valeurs et invariants purs. |
| contracts | Langage stable partagé, indépendant d'un moteur métier. |
| port | Direction applicative vers adapter ; interface, pas implémentation. |
| engine | Traitement applicatif ; un verbe est requis sauf moteur cœur tel que `engine-consumption`. Un calcul pur n'est pas un engine. |
| projector | Calcul pur et déterministe : inputs explicites → projection désirée. Aucun SQL, transaction, retry, polling, lifecycle, dépendance runtime ou implémentation infra concrète. |
| orchestrator | Coordination de traitements ; ni règle métier profonde ni provider concret. |
| supra | Adaptation d'un protocole concret d'entrée/sortie ou d'un consumable concret vers un engine/orchestrator spécifique. Aucun SQL concret, lifecycle complet, polling générique, claim/fencing générique ou règle métier profonde. Un runtime élémentaire potentiel dispose d'un supra spécifique lorsqu'il existe une glue protocolaire propre. |
| infra | Implémentation technologique d'un port ou d'une responsabilité. Nom : `infra-<port/responsabilité implémentée>-<sous-responsabilité éventuelle>-<technologie>` ; le rôle précède la technologie. |
| runtime | Racine élémentaire de composition explicitement isolée ; POM autonome ne signifie pas processus/pod séparé pour toujours. |
| architecture-tests | Gate global de règles architecturales, hors production. |

`projector-pot` calcule AUTH, READ_POT et POT_BALANCES à partir d'inputs historiques `@V` explicites ; `engine-consume-projection-task` orchestre ce calcul, la vérification de clé, la validation de sortie et la persistance exacte. `orchestrator-consumption` porte `SequentialConsumptionOrchestrator` et `AcquireThenFinalizeConsumptionOrchestrator` sans spécialisation métier. `orchestrator-poll-consumption` porte poll, segment, wait, repeat et appelle la coordination générique ; le polling n'est pas un supra métier.

`contracts-authentication` porte le principal authentifié transverse et reste indépendant de Spring Security/JWT ; Command admission, Registration admission et de futurs consumers peuvent l'utiliser sans importer un engine métier. `contracts-registration` porte le langage durable partagé des quatre engines Registration. `contracts-observability` porte le contrat de trace partagé. `domain-pot-policy` demeure le seul rôle `PACKAGE_ONLY`, dans `domain-pot`.

Les cinq infra fermes sont `infra-tx-spring` (implémentation de `port-transaction`), `infra-persistence-primary-jpa` (ports PRIMARY/Consumption regroupés transactionnellement), `infra-persistence-read-jdbc` (READ direct Current Binding ; LKV provisoire seulement sous décision ultérieure), `infra-persistence-projection-jdbc` (root, artifact, failure, exact read/store) et `infra-projection-validation-networknt` (validation JSON Schema technique). Le cluster PRIMARY n'est pas redécoupé ici. La validation Networknt n'est pas fusionnée avec la persistence de projection.

**Règle de namespace (WP2.1).** Le package Java exprime la même responsabilité architecturale que son POM propriétaire. Les deux noms JDBC reflètent `spring-jdbc` et leurs adapters `Jdbc*`; aucune technologie JPA n’est introduite. `port-projection` porte les contrats de la projection historique exacte `@V` et de son travail de matérialisation. `CURRENT_BINDING` est une vue courante mutable/convergente issue directement des Binding facts : son modèle et ses invariants sont dans `domain-user-identity`, `CurrentBindingWritePort` dans `engine-materialize-current-binding` et `CurrentBindingReadPort` dans `engine-read-current-binding`. `infra-persistence-read-jdbc` fournit un adapter concret aux deux ports. Les moteurs Current Binding ne dépendent pas de `port-projection`, et le moteur de lecture Current Binding ne dépend pas du WRITE de Binding authority. Ce checkpoint ne change ni les 58 responsabilités logiques, ni les 54 POM fermes, ni `PACKAGE_ONLY`/`TBD_PHYSICAL`.

## 3. Table finale des frontières

Une ligne représente une responsabilité logique, même lorsque son nom est aussi celui d'un POM. `—` désigne un POM encore indécis ; ces lignes ne sont pas des POM fermes.

| Logical responsibility | Architectural family | Physical decision | Physical Maven POM |
| --- | --- | --- | --- |
| `domain-authorization` | domain | KEEP_POM | `domain-authorization` |
| `domain-consumption` | domain | KEEP_POM | `domain-consumption` |
| `domain-event` | domain | KEEP_POM | `domain-event` |
| `domain-pot` | domain | KEEP_POM | `domain-pot` |
| `domain-pot-policy` | domain | PACKAGE_ONLY | `domain-pot` |
| `domain-projection` | domain | KEEP_POM | `domain-projection` |
| `domain-user-identity` | domain | KEEP_POM | `domain-user-identity` |
| `contracts-authentication` | contracts | KEEP_POM | `contracts-authentication` |
| `contracts-observability` | contracts | KEEP_POM | `contracts-observability` |
| `contracts-registration` | contracts | KEEP_POM | `contracts-registration` |
| `port-binding-authority` | port | KEEP_POM | `port-binding-authority` |
| `port-projection` | port | KEEP_POM | `port-projection` |
| `port-transaction` | port | KEEP_POM | `port-transaction` |
| `engine-admit-command` | engine | KEEP_POM | `engine-admit-command` |
| `engine-admit-registration` | engine | KEEP_POM | `engine-admit-registration` |
| `engine-advance-pot-watermark` | engine | TBD_PHYSICAL | — |
| `engine-consume-command` | engine | KEEP_POM | `engine-consume-command` |
| `engine-consume-projection-task` | engine | KEEP_POM | `engine-consume-projection-task` |
| `engine-consume-registration` | engine | KEEP_POM | `engine-consume-registration` |
| `engine-consumption` | engine | KEEP_POM | `engine-consumption` |
| `engine-materialize-command-result` | engine | KEEP_POM | `engine-materialize-command-result` |
| `engine-materialize-current-binding` | engine | KEEP_POM | `engine-materialize-current-binding` |
| `engine-materialize-registration-result` | engine | KEEP_POM | `engine-materialize-registration-result` |
| `engine-produce-projection-task` | engine | KEEP_POM | `engine-produce-projection-task` |
| `engine-read-command-result` | engine | KEEP_POM | `engine-read-command-result` |
| `engine-read-current-binding` | engine | KEEP_POM | `engine-read-current-binding` |
| `engine-read-pot` | engine | KEEP_POM | `engine-read-pot` |
| `engine-read-projection` | engine | KEEP_POM | `engine-read-projection` |
| `engine-read-registration-result` | engine | KEEP_POM | `engine-read-registration-result` |
| `engine-write-pot` | engine | KEEP_POM | `engine-write-pot` |
| `projector-pot` | projector | KEEP_POM | `projector-pot` |
| `orchestrator-consumption` | orchestrator | KEEP_POM | `orchestrator-consumption` |
| `orchestrator-poll-consumption` | orchestrator | KEEP_POM | `orchestrator-poll-consumption` |
| `supra-consume-binding` | supra | KEEP_POM | `supra-consume-binding` |
| `supra-consume-command` | supra | KEEP_POM | `supra-consume-command` |
| `supra-consume-command-result` | supra | KEEP_POM | `supra-consume-command-result` |
| `supra-consume-event` | supra | KEEP_POM | `supra-consume-event` |
| `supra-consume-lkv` | supra | TBD_PHYSICAL | — |
| `supra-consume-projection-task` | supra | KEEP_POM | `supra-consume-projection-task` |
| `supra-consume-registration` | supra | KEEP_POM | `supra-consume-registration` |
| `supra-consume-registration-result` | supra | KEEP_POM | `supra-consume-registration-result` |
| `supra-http-read` | supra | KEEP_POM | `supra-http-read` |
| `supra-http-write` | supra | KEEP_POM | `supra-http-write` |
| `infra-persistence-primary-jpa` | infra | KEEP_POM | `infra-persistence-primary-jpa` |
| `infra-persistence-projection-jdbc` | infra | KEEP_POM | `infra-persistence-projection-jdbc` |
| `infra-persistence-read-jdbc` | infra | KEEP_POM | `infra-persistence-read-jdbc` |
| `infra-projection-validation-networknt` | infra | KEEP_POM | `infra-projection-validation-networknt` |
| `infra-tx-spring` | infra | KEEP_POM | `infra-tx-spring` |
| `runtime-binding-consumption-worker` | runtime | KEEP_POM | `runtime-binding-consumption-worker` |
| `runtime-command-consumption-worker` | runtime | KEEP_POM | `runtime-command-consumption-worker` |
| `runtime-command-result-consumption-worker` | runtime | KEEP_POM | `runtime-command-result-consumption-worker` |
| `runtime-event-consumption-worker` | runtime | KEEP_POM | `runtime-event-consumption-worker` |
| `runtime-latest-known-version-consumption-worker` | runtime | TBD_PHYSICAL | — |
| `runtime-registration-consumption-worker` | runtime | KEEP_POM | `runtime-registration-consumption-worker` |
| `runtime-registration-result-consumption-worker` | runtime | KEEP_POM | `runtime-registration-result-consumption-worker` |
| `runtime-task-consumption-worker` | runtime | KEEP_POM | `runtime-task-consumption-worker` |
| `runtime-web-api` | runtime | KEEP_POM | `runtime-web-api` |
| `architecture-tests` | verification | KEEP_POM | `architecture-tests` |

**Comptage recalculé : 58 responsabilités logiques, 54 POM fermes `KEEP_POM` (53 de production et le gate), 1 `PACKAGE_ONLY`, 3 `TBD_PHYSICAL`.** Les trois TBD sont `engine-advance-pot-watermark`, `supra-consume-lkv` et `runtime-latest-known-version-consumption-worker`. Aucun POM LKV n'est compté comme ferme.

## 3 bis. CURRENT → TARGET Migration Traceability

Cette table répond au niveau des **responsabilités de module** et non des classes ou des commits. Sa colonne CURRENT reprend exactement les 51 modules de la section C de l'[audit CURRENT](Modularity_Current_State_Audit.md). `KEEP / RENAME` conserve principalement la frontière, `SPLIT` répartit les responsabilités, `MERGE` rejoint une frontière plus large, `DISSOLVE AS MAVEN BOUNDARY` retire la frontière autonome, et `TBD` signale une dépendance réelle à une décision LKV ouverte. Les noms de la colonne « responsabilités » désignent des **rôles logiques TARGET**, ceux de la colonne POM des **frontières physiques fermes** ; `domain-pot-policy` est un package de `domain-pot`. `— (TBD_PHYSICAL)` signifie qu'aucun POM LKV n'est décidé. Les POM runtime sont des frontières de composition, sans décision ici sur les pods futurs.

| CURRENT module | CURRENT role summary | TARGET disposition | TARGET logical responsibilities | TARGET physical POM(s) | Migration notes |
| --- | --- | --- | --- | --- | --- |
| `domain-authorization` | Capacités, permissions, traduction | KEEP / RENAME | `domain-authorization` | `domain-authorization` | Vocabulaire d'autorisation pur. |
| `domain-event` | Contrat Event transversal | KEEP / RENAME | `domain-event` | `domain-event` | Contrat Event partagé. |
| `domain-user-identity` | E, U, B, facts et ports d'autorité | SPLIT | `domain-user-identity`, `port-binding-authority` | `domain-user-identity`, `port-binding-authority` | Modèle/facts d'identité séparés du port d'autorité Binding. |
| `authentication-contracts` | Principal externe attesté | KEEP / RENAME | `contracts-authentication` | `contracts-authentication` | Contrat authentifié indépendant du provider Spring/JWT. |
| `domain-pot` | Pot, Expense, valeurs et BusinessEvent | KEEP / RENAME | `domain-pot` | `domain-pot` | Modèle Pot et événement métier. |
| `domain-pot-projection` | Définitions AUTH/READ_POT/BALANCES | SPLIT | `domain-projection`, `projector-pot` | `domain-projection`, `projector-pot` | Définitions/identifiants de projection et calcul Pot déterministe séparés. |
| `domain-projection-balance` | Modèle et calcul Balance | SPLIT | `projector-pot`, `domain-pot` | `projector-pot`, `domain-pot` | Calcul BALANCES dans le projector Pot ; valeurs métier Pot dans le domaine. |
| `domain-projection` | ProjectionKey, artifact, validator | KEEP / RENAME | `domain-projection` | `domain-projection` | Contrat et validation pure de projection exacte. |
| `domain-pot-policy` | Kernel et facts d'autorisation Pot | DISSOLVE AS MAVEN BOUNDARY | `domain-pot-policy` | `domain-pot` | Rôle logique conservé en `PACKAGE_ONLY` ; aucun POM policy autonome. |
| `domain-consumption` | Key, slot, claim, lease, provenance | KEEP / RENAME | `domain-consumption` | `domain-consumption` | Modèle commun Consumption. |
| `engine-core` | Transactions, snapshots, Event, segments, erreurs | SPLIT | `port-transaction`, `domain-consumption`, `engine-write-pot`, `domain-pot`, `infra-persistence-primary-jpa` | `port-transaction`, `domain-consumption`, `engine-write-pot`, `domain-pot`, `infra-persistence-primary-jpa` | `TransactionRunner` → port ; WorkerSegment/PartitionHash → domaine Consumption ; snapshots/versions Pot et UserContext → moteur WRITE/domaine Pot selon leur rôle ; RecordedEvent/EventTraceMetadata Pot → domaine Pot ; wrappers SQL legacy → adapter PRIMARY. Aucun core TARGET. |
| `engine-consumption` | Acquire, Execute, Finalize, failure | KEEP / RENAME | `engine-consumption` | `engine-consumption` | Mécanique transactionnelle de consommation. |
| `engine-command` | Command, dispatch, exécution, outcome | SPLIT | `engine-consume-command`, `engine-admit-command`, `domain-event` | `engine-consume-command`, `engine-admit-command`, `domain-event` | Langage/exécution Command et outcome vers consume ; admission vers admit ; Event partagé vers domaine. Le contrat Command Result reste soumis à `TBD-COMMAND-CONTRACT`, sans cible inventée. |
| `engine-command-result` | Result, materializer, GET, owner | SPLIT | `engine-materialize-command-result`, `engine-read-command-result`, `supra-consume-command-result`, `infra-persistence-primary-jpa` | `engine-materialize-command-result`, `engine-read-command-result`, `supra-consume-command-result`, `infra-persistence-primary-jpa` | Matérialisation et GET/owner séparés ; intégration candidate/issue et store PRIMARY. `TBD-COMMAND-CONTRACT` reste ouvert. |
| `engine-registration` | Admission, exécution, outcome, Result GET | SPLIT | `contracts-registration`, `engine-admit-registration`, `engine-consume-registration`, `engine-materialize-registration-result`, `engine-read-registration-result`, `supra-consume-registration`, `supra-consume-registration-result`, `infra-persistence-primary-jpa` | `contracts-registration`, `engine-admit-registration`, `engine-consume-registration`, `engine-materialize-registration-result`, `engine-read-registration-result`, `supra-consume-registration`, `supra-consume-registration-result`, `infra-persistence-primary-jpa` | Langage Request/Outcome partagé ; admission, exécution, matérialisation et lecture distinctes ; glue des deux workers et adapters PRIMARY. |
| `engine-processing-event` | Discovery/policy Event et LKV | TBD | `engine-produce-projection-task`, `domain-event`, `engine-advance-pot-watermark` | `engine-produce-projection-task`, `domain-event` ; — (TBD_PHYSICAL LKV) | Production de Task et contrat Event fermes ; avance du watermark partiellement TBD, owner fonctionnel non tranché. |
| `engine-pot-command` | Use cases de mutation Pot | KEEP / RENAME | `engine-write-pot`, `domain-pot-policy` | `engine-write-pot`, `domain-pot` | Mutation WRITE ; policy Pot utilisée comme rôle package-only. |
| `engine-projection-contracts` | Ports lecture/publication exactes | KEEP / RENAME | `port-projection` | `port-projection` | Contrats applicatifs de lecture et publication exacte. |
| `engine-projection-read` | Lecture exacte et revalidation | KEEP / RENAME | `engine-read-projection` | `engine-read-projection` | Lecture générique d'une projection exacte `@V`, distincte de l'ancien module CURRENT `engine-read-projection`. |
| `engine-projection-task` | Task, catalog, préparation, retry, publication | SPLIT | `engine-produce-projection-task`, `engine-consume-projection-task`, `supra-consume-projection-task`, `port-projection` | `engine-produce-projection-task`, `engine-consume-projection-task`, `supra-consume-projection-task`, `port-projection` | Création/consommation de Task séparées ; catalog/reload/issue vers glue Task ; publication exacte par port. |
| `engine-pot-read` | Interprétation AUTH/READ_POT et autorisation | KEEP / RENAME | `engine-read-pot`, `domain-pot-policy` | `engine-read-pot`, `domain-pot` | GET Pot exact et policy package-only ; dépendance au port E→U encore `TBD-E2U`. |
| `engine-projection-balance` | Source historique, calcul, loader, projector | SPLIT | `projector-pot`, `engine-consume-projection-task`, `port-projection`, `infra-persistence-primary-jpa` | `projector-pot`, `engine-consume-projection-task`, `port-projection`, `infra-persistence-primary-jpa` | Calcul pur BALANCES vers projector ; orchestration Task, ports de chargement et adapter historique séparés. |
| `engine-projection-pot` | Loaders et projectors AUTH/READ_POT | SPLIT | `projector-pot`, `engine-consume-projection-task`, `port-projection`, `infra-persistence-primary-jpa` | `projector-pot`, `engine-consume-projection-task`, `port-projection`, `infra-persistence-primary-jpa` | Calcul pur AUTH/READ_POT vers projector ; coordination Task, ports et loaders PRIMARY distincts. |
| `engine-read-projection` | Current Binding, LKV, reconstruction Pot | TBD | `engine-materialize-current-binding`, `engine-read-current-binding`, `port-projection`, `projector-pot`, `engine-advance-pot-watermark` | `engine-materialize-current-binding`, `engine-read-current-binding`, `port-projection`, `projector-pot` ; — (TBD_PHYSICAL LKV) | Ancien agrégat CURRENT : production et GET Current Binding directs ; `HistoricalPotSnapshotSource` fournit l'input historique via le port de projection au projector Pot ; avance LKV partiellement TBD. Il **ne** devient **pas** le `engine-read-projection` TARGET (lecture générique `@V`). |
| `observability` | Compteurs et contrat métrique | KEEP / RENAME | `contracts-observability` | `contracts-observability` | Contrat de trace partagé ; instrumentation concrète dans les compositions/adapters. |
| `infra-tx-spring` | TransactionRunner Spring | KEEP / RENAME | `infra-tx-spring` | `infra-tx-spring` | Implémentation Spring du `port-transaction`. |
| `infra-persistence-jpa` | Adapters WRITE, discovery, Result, loaders | MERGE | `infra-persistence-primary-jpa` | `infra-persistence-primary-jpa` | Les adapters PRIMARY/Consumption, loaders historiques Pot/Task et stores Command/Registration restent dans le cluster PRIMARY ; les stores READ direct et projection exacte sont déjà dans deux autres modules CURRENT. |
| `infra-projection-persistence` | Store exact root/artifact/failure | KEEP / RENAME | `infra-persistence-projection-jdbc` | `infra-persistence-projection-jdbc` | Persistance exacte de projection. |
| `infra-read-persistence` | Store Current Binding, LKV, migrations READ | TBD | `infra-persistence-read-jdbc`, `engine-advance-pot-watermark` | `infra-persistence-read-jdbc` ; — (TBD_PHYSICAL LKV) | Store Current Binding ferme ; persistance LKV sous décision fonctionnelle/physique encore ouverte. |
| `infra-projection-json-schema` | Validation JSON Schema | KEEP / RENAME | `infra-projection-validation-networknt` | `infra-projection-validation-networknt` | Adapter de validation technique distinct de la persistence. |
| `orchestrator-consumption` | Deux orchestrateurs et orchestration Task | SPLIT | `orchestrator-consumption`, `engine-consume-projection-task` | `orchestrator-consumption`, `engine-consume-projection-task` | Sequential/AcquireThenFinalize génériques conservés ; spécialisation Task vers engine Task. |
| `orchestrator-command-admission` | Admission Command E+B et evidence | KEEP / RENAME | `engine-admit-command`, `port-binding-authority` | `engine-admit-command`, `port-binding-authority` | Admission et preuve E+B ; autorité Binding derrière le port. |
| `supra-consumption-worker` | Polling, budget, wait, lifecycle | KEEP / RENAME | `orchestrator-poll-consumption` | `orchestrator-poll-consumption` | Polling générique, sans supra polling TARGET. |
| `locator-consumption-event` | Discovery metadata et ensure Task | SPLIT | `engine-produce-projection-task`, `supra-consume-event`, `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` | `engine-produce-projection-task`, `supra-consume-event`, `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` | Sémantique ensure Task → engine ; candidate/reload/issue → supra ; SQL/discovery → PRIMARY ; cadence/config → poll/runtime Event. |
| `locator-consumption-latest-known-version` | Discovery/reload Event, advance LKV, failure | TBD | `engine-advance-pot-watermark`, `supra-consume-lkv`, `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` | `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` ; — (TBD_PHYSICAL LKV) | Sémantique advance et glue candidate/reload/issue LKV restent TBD ; SQL de discovery PRIMARY séparé du store LKV physique encore indécis ; polling/config dans orchestrator/runtime LKV TBD. |
| `locator-consumption-binding` | Discovery/reload fact, current apply, failure | SPLIT | `engine-materialize-current-binding`, `supra-consume-binding`, `infra-persistence-primary-jpa`, `infra-persistence-read-jdbc`, `orchestrator-poll-consumption` | `engine-materialize-current-binding`, `supra-consume-binding`, `infra-persistence-primary-jpa`, `infra-persistence-read-jdbc`, `orchestrator-poll-consumption` | Sémantique apply Current Binding → engine ; candidate/reload/issue → supra ; SQL fact/READ → adapters ; polling/config → poll/runtime Binding. |
| `locator-consumption-command` | Discovery/reload Command, callback, fence, failure | SPLIT | `engine-consume-command`, `supra-consume-command`, `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` | `engine-consume-command`, `supra-consume-command`, `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` | Sémantique Command → engine ; candidate/reload/issue → supra ; SQL → PRIMARY ; polling/config → poll/runtime Command. |
| `binding-pot-command-spring` | Binding Spring du dispatch Pot | MERGE | `runtime-command-consumption-worker`, `engine-write-pot`, `domain-pot-policy` | `runtime-command-consumption-worker`, `engine-write-pot`, `domain-pot` | Câblage provider dans la composition Command ; dispatch Pot au moteur WRITE ; policy reste package-only. |
| `supra-http-write-command` | HTTP admission Command | KEEP / RENAME | `supra-http-write`, `engine-admit-command` | `supra-http-write`, `engine-admit-command` | Adapter HTTP vers moteur d'admission Command. |
| `supra-http-read-query` | HTTP Pot, Result Command, Current Binding | SPLIT | `supra-http-read`, `engine-read-pot`, `engine-read-command-result`, `engine-read-current-binding`, `domain-user-identity` | `supra-http-read`, `engine-read-pot`, `engine-read-command-result`, `engine-read-current-binding`, `domain-user-identity` | DTO/erreurs/GET vers supra ; lectures exactes vers engines ; identité E/U dans le domaine, résolution E→U primaire derrière un port encore `TBD-E2U`. |
| `supra-authentication-spring-security` | AuthN Spring et principal JWT | SPLIT | `contracts-authentication`, `runtime-web-api` | `contracts-authentication`, `runtime-web-api` | Contrat principal attesté transverse ; provider Spring Security/JWT concret composé par Web. |
| `runtime-web-api` | Compose HTTP, stores, auth et use cases | KEEP / RENAME | `runtime-web-api`, `supra-http-write`, `supra-http-read`, `engine-admit-command`, `engine-admit-registration`, `engine-read-pot`, `engine-read-command-result`, `engine-read-registration-result`, `engine-read-current-binding` | `runtime-web-api`, `supra-http-write`, `supra-http-read`, `engine-admit-command`, `engine-admit-registration`, `engine-read-pot`, `engine-read-command-result`, `engine-read-registration-result`, `engine-read-current-binding` | Racine Web et câblage des moteurs admit/read pertinents ; provider Security concret ici. |
| `runtime-event-consumption-worker` | Compose Event→Task, policy et polling | KEEP / RENAME | `runtime-event-consumption-worker`, `supra-consume-event`, `engine-produce-projection-task`, `orchestrator-poll-consumption` | `runtime-event-consumption-worker`, `supra-consume-event`, `engine-produce-projection-task`, `orchestrator-poll-consumption` | Racine Event ; policy de matérialisation Task et composition, polling générique via orchestrator. |
| `runtime-command-result-consumption-worker` | Compose Result direct, locator et retry | SPLIT | `runtime-command-result-consumption-worker`, `supra-consume-command-result`, `engine-materialize-command-result`, `orchestrator-poll-consumption` | `runtime-command-result-consumption-worker`, `supra-consume-command-result`, `engine-materialize-command-result`, `orchestrator-poll-consumption` | Locator/classification/issue vers supra ; matérialisation vers engine ; retry/cadence générique vers poll ; racine conservée. |
| `runtime-registration-result-consumption-worker` | Compose Registration Result, locator et retry | SPLIT | `runtime-registration-result-consumption-worker`, `supra-consume-registration-result`, `engine-materialize-registration-result`, `orchestrator-poll-consumption` | `runtime-registration-result-consumption-worker`, `supra-consume-registration-result`, `engine-materialize-registration-result`, `orchestrator-poll-consumption` | Locator/classification/issue vers supra ; matérialisation vers engine ; retry/cadence générique vers poll ; racine conservée. |
| `runtime-registration-consumption-worker` | Compose Registration, locator et retry | SPLIT | `runtime-registration-consumption-worker`, `supra-consume-registration`, `engine-consume-registration`, `orchestrator-poll-consumption` | `runtime-registration-consumption-worker`, `supra-consume-registration`, `engine-consume-registration`, `orchestrator-poll-consumption` | Locator/classification/issue vers supra ; exécution vers engine ; retry/cadence générique vers poll ; racine conservée. |
| `runtime-latest-known-version-consumption-worker` | Compose LKV, métriques et polling | TBD | `runtime-latest-known-version-consumption-worker`, `supra-consume-lkv`, `engine-advance-pot-watermark`, `orchestrator-poll-consumption` | `orchestrator-poll-consumption` ; — (TBD_PHYSICAL LKV) | Racine, glue et moteur LKV tous partiellement TBD ; métriques/polling connus mais composition physique et owner fonctionnel non décidés. |
| `runtime-binding-consumption-worker` | Compose Binding fact consumer | KEEP / RENAME | `runtime-binding-consumption-worker`, `supra-consume-binding`, `engine-materialize-current-binding`, `orchestrator-poll-consumption` | `runtime-binding-consumption-worker`, `supra-consume-binding`, `engine-materialize-current-binding`, `orchestrator-poll-consumption` | Racine Binding, glue fact et matérialisation Current ; polling générique. |
| `runtime-task-consumption-worker` | Compose catalog, Task, producers, retry | SPLIT | `runtime-task-consumption-worker`, `supra-consume-projection-task`, `engine-consume-projection-task`, `projector-pot`, `orchestrator-poll-consumption` | `runtime-task-consumption-worker`, `supra-consume-projection-task`, `engine-consume-projection-task`, `projector-pot`, `orchestrator-poll-consumption` | Catalog/selection de composition dans runtime ; reload/issue vers supra ; traitement et calcul pur séparés ; polling générique. |
| `runtime-command-consumption-worker` | Compose Command, Pot et consumption | KEEP / RENAME | `runtime-command-consumption-worker`, `supra-consume-command`, `engine-consume-command`, `engine-write-pot`, `orchestrator-poll-consumption` | `runtime-command-consumption-worker`, `supra-consume-command`, `engine-consume-command`, `engine-write-pot`, `orchestrator-poll-consumption` | Racine Command, dispatch Pot et wiring ; polling générique. |
| `architecture-tests` | Gate de frontières et E2E | KEEP / RENAME | `architecture-tests` | `architecture-tests` | Gate global conservé ; aucune vérification Maven pour cette modification documentaire. |

Pour les workers Result et Registration, la glue locator aujourd'hui logée dans les runtimes rejoint le supra spécifique ; les huit POM runtime fermes demeurent des racines. Aucune ligne ci-dessus ne décide `TBD-LKV`, `TBD-E2U` ou `TBD-COMMAND-CONTRACT`.

### Vue inverse compacte

Chaque entrée nomme les sources CURRENT principales du rôle/POM TARGET ; les simples dépendances de composition runtime sont omises, sauf lorsque le runtime porte aujourd’hui la glue ou la policy concernée. Les trois lignes LKV sont des responsabilités `TBD_PHYSICAL` ; `domain-pot-policy` est `PACKAGE_ONLY` dans `domain-pot`.

| TARGET POM / responsibility | Main CURRENT sources |
| --- | --- |
| `domain-authorization` | `domain-authorization` |
| `domain-consumption` | `domain-consumption`, `engine-core` |
| `domain-event` | `domain-event`, `engine-command`, `engine-processing-event` |
| `domain-pot` | `domain-pot`, `domain-projection-balance`, `engine-core` |
| `domain-pot-policy` (PACKAGE_ONLY) | `domain-pot-policy`, `engine-pot-command`, `engine-pot-read`, `binding-pot-command-spring` |
| `domain-projection` | `domain-pot-projection`, `domain-projection` |
| `domain-user-identity` | `domain-user-identity`, `supra-http-read-query` |
| `contracts-authentication` | `authentication-contracts`, `supra-authentication-spring-security` |
| `contracts-observability` | `observability` |
| `contracts-registration` | `engine-registration` |
| `port-binding-authority` | `domain-user-identity`, `orchestrator-command-admission` |
| `port-projection` | `engine-projection-contracts`, `engine-projection-task`, `engine-projection-balance`, `engine-projection-pot`, `engine-read-projection` |
| `port-transaction` | `engine-core` |
| `engine-admit-command` | `engine-command`, `orchestrator-command-admission`, `supra-http-write-command` |
| `engine-admit-registration` | `engine-registration` |
| `engine-advance-pot-watermark` (TBD_PHYSICAL) | `engine-processing-event`, `engine-read-projection`, `infra-read-persistence`, `locator-consumption-latest-known-version` |
| `engine-consume-command` | `engine-command`, `locator-consumption-command` |
| `engine-consume-projection-task` | `engine-projection-task`, `engine-projection-balance`, `engine-projection-pot`, `orchestrator-consumption` |
| `engine-consume-registration` | `engine-registration` |
| `engine-consumption` | `engine-consumption` |
| `engine-materialize-command-result` | `engine-command-result` |
| `engine-materialize-current-binding` | `engine-read-projection`, `locator-consumption-binding` |
| `engine-materialize-registration-result` | `engine-registration` |
| `engine-produce-projection-task` | `engine-processing-event`, `engine-projection-task`, `locator-consumption-event`, `runtime-event-consumption-worker` |
| `engine-read-command-result` | `engine-command-result`, `supra-http-read-query` |
| `engine-read-current-binding` | `engine-read-projection`, `supra-http-read-query` |
| `engine-read-pot` | `engine-pot-read`, `supra-http-read-query` |
| `engine-read-projection` | `engine-projection-read` |
| `engine-read-registration-result` | `engine-registration` |
| `engine-write-pot` | `engine-core`, `engine-pot-command`, `binding-pot-command-spring` |
| `projector-pot` | `domain-pot-projection`, `domain-projection-balance`, `engine-projection-balance`, `engine-projection-pot`, `engine-read-projection` |
| `orchestrator-consumption` | `orchestrator-consumption` |
| `orchestrator-poll-consumption` | `supra-consumption-worker`, `locator-consumption-event`, `locator-consumption-latest-known-version`, `locator-consumption-binding`, `locator-consumption-command` |
| `supra-consume-binding` | `locator-consumption-binding` |
| `supra-consume-command` | `locator-consumption-command` |
| `supra-consume-command-result` | `engine-command-result`, `runtime-command-result-consumption-worker` |
| `supra-consume-event` | `locator-consumption-event` |
| `supra-consume-lkv` (TBD_PHYSICAL) | `locator-consumption-latest-known-version`, `runtime-latest-known-version-consumption-worker` |
| `supra-consume-projection-task` | `engine-projection-task`, `runtime-task-consumption-worker` |
| `supra-consume-registration` | `engine-registration`, `runtime-registration-consumption-worker` |
| `supra-consume-registration-result` | `engine-registration`, `runtime-registration-result-consumption-worker` |
| `supra-http-read` | `supra-http-read-query` |
| `supra-http-write` | `supra-http-write-command` |
| `infra-persistence-primary-jpa` | `engine-core`, `engine-command-result`, `engine-registration`, `engine-projection-balance`, `engine-projection-pot`, `infra-persistence-jpa`, `locator-consumption-event`, `locator-consumption-latest-known-version`, `locator-consumption-binding`, `locator-consumption-command` |
| `infra-persistence-projection-jdbc` | `infra-projection-persistence` |
| `infra-persistence-read-jdbc` | `infra-read-persistence`, `locator-consumption-binding` |
| `infra-projection-validation-networknt` | `infra-projection-json-schema` |
| `infra-tx-spring` | `infra-tx-spring` |
| `runtime-binding-consumption-worker` | `runtime-binding-consumption-worker` |
| `runtime-command-consumption-worker` | `binding-pot-command-spring`, `runtime-command-consumption-worker` |
| `runtime-command-result-consumption-worker` | `runtime-command-result-consumption-worker` |
| `runtime-event-consumption-worker` | `runtime-event-consumption-worker` |
| `runtime-latest-known-version-consumption-worker` (TBD_PHYSICAL) | `runtime-latest-known-version-consumption-worker` |
| `runtime-registration-consumption-worker` | `runtime-registration-consumption-worker` |
| `runtime-registration-result-consumption-worker` | `runtime-registration-result-consumption-worker` |
| `runtime-task-consumption-worker` | `runtime-task-consumption-worker` |
| `runtime-web-api` | `supra-authentication-spring-security`, `runtime-web-api` |
| `architecture-tests` | `architecture-tests` |

## 4. Supra et chaînes d'exécution

Les deux supras HTTP sont des POM. `supra-http-write` adapte principal, DTO/request, admission/write et erreurs/réponses HTTP vers `engine-admit-command` et `engine-admit-registration`, sans SQL concret. `supra-http-read` adapte GET Pot, Command Result, Registration Result et Current Binding, DTO/read response et 404 opaque vers les moteurs read ; il n'importe pas l'adapter PRIMARY E→U. `runtime-web-api` compose les deux ; il n'y a pas de runtime Web read/write séparé.

| Supra Consumption | Consumable/candidate, reload par port, traduction et issue Consumption | Runtime élémentaire |
| --- | --- | --- |
| `supra-consume-command` | candidate/slot Command → reload autoritaire E+B → `engine-consume-command` → issue fenced | `runtime-command-consumption-worker` |
| `supra-consume-event` | metadata Event candidate → reload metadata utile → `engine-produce-projection-task` → issue ensure Task | `runtime-event-consumption-worker` |
| `supra-consume-projection-task` | candidate Task/key → reload input historique par ports → `engine-consume-projection-task` → issue exacte | `runtime-task-consumption-worker` |
| `supra-consume-binding` | candidate fact Binding → reload fact par eventId → `engine-materialize-current-binding` → issue | `runtime-binding-consumption-worker` |
| `supra-consume-registration` | candidate Request → reload par ports → `engine-consume-registration` → issue | `runtime-registration-consumption-worker` |
| `supra-consume-command-result` | candidate terminal Event/outcome/Command → reload par ports → `engine-materialize-command-result` → issue | `runtime-command-result-consumption-worker` |
| `supra-consume-registration-result` | candidate Outcome → reload Request/Outcome par ports → `engine-materialize-registration-result` → issue | `runtime-registration-result-consumption-worker` |
| `supra-consume-lkv` | candidate Event LKV → reload/maximum par ports à préciser → `engine-advance-pot-watermark` → issue ; tout ceci reste TBD-LKV | `runtime-latest-known-version-consumption-worker` TBD |

Tous ces supras sont exempts de SQL concret et de polling générique. Les sept premiers sont des POM fermes ; LKV reste une responsabilité logique conditionnelle. Les flèches suivantes décrivent **l'ordre d'exécution et le câblage** ; elles ne prétendent pas que chaque flèche est un import Maven direct. Les imports effectifs sont en sections 5 et 6.

| Capacité | Chaîne TARGET |
| --- | --- |
| Command admission | `runtime-web-api → supra-http-write → engine-admit-command` |
| Command consumption | `runtime-command-consumption-worker → orchestrator-poll-consumption → supra-consume-command → orchestrator-consumption → engine-consume-command → engine-write-pot` |
| Registration admission | `runtime-web-api → supra-http-write → engine-admit-registration` |
| Registration consumption | `runtime-registration-consumption-worker → orchestrator-poll-consumption → supra-consume-registration → orchestrator-consumption → engine-consume-registration` |
| Command Result materialize | `runtime-command-result-consumption-worker → orchestrator-poll-consumption → supra-consume-command-result → orchestrator-consumption → engine-materialize-command-result` |
| Command Result GET | `runtime-web-api → supra-http-read → engine-read-command-result` |
| Registration Result materialize | `runtime-registration-result-consumption-worker → orchestrator-poll-consumption → supra-consume-registration-result → orchestrator-consumption → engine-materialize-registration-result` |
| Registration Result GET | `runtime-web-api → supra-http-read → engine-read-registration-result` |
| Event → Task | `runtime-event-consumption-worker → orchestrator-poll-consumption → supra-consume-event → orchestrator-consumption → engine-produce-projection-task` |
| ProjectionTask | `runtime-task-consumption-worker → orchestrator-poll-consumption → supra-consume-projection-task → orchestrator-consumption → engine-consume-projection-task → projector-pot` |
| Binding → Current | `runtime-binding-consumption-worker → orchestrator-poll-consumption → supra-consume-binding → orchestrator-consumption → engine-materialize-current-binding` |
| Current Binding GET | `runtime-web-api → supra-http-read → engine-read-current-binding` |
| Pot GET | `runtime-web-api → supra-http-read → engine-read-pot → engine-read-current-binding + engine-read-projection` |

## 5. Matrice canonique des arcs logiques

`A → B` signifie **A dépend de B**, et non l'ordre d'exécution. La liste d'adjacence ci-dessous est la matrice canonique exhaustive ; chaque paire séparée par une virgule est un arc distinct. Les arcs touchant LKV et les deux imports historiques Command Result marqués `†` restent conditionnels. Un port E→U reste à définir ; il n'est pas inventé comme POM.

| From logical responsibility | To logical responsibilities (direct imports) |
| --- | --- |
| `domain-authorization` | — |
| `domain-event` | — |
| `domain-user-identity` | — |
| `domain-pot` | `domain-event` |
| `domain-pot-policy` | `domain-authorization`, `domain-pot` |
| `domain-consumption` | — |
| `domain-projection` | — |
| `contracts-authentication` | `domain-user-identity` |
| `contracts-registration` | `domain-user-identity` |
| `contracts-observability` | — |
| `port-binding-authority` | `domain-user-identity` |
| `port-transaction` | — |
| `port-projection` | `domain-projection` |
| `engine-consumption` | `domain-consumption`, `port-transaction` |
| `orchestrator-consumption` | `engine-consumption` |
| `orchestrator-poll-consumption` | `domain-consumption`, `orchestrator-consumption` |
| `engine-consume-command` | `domain-event`, `domain-user-identity`, `port-binding-authority`, `engine-consumption`, `engine-write-pot` |
| `engine-admit-command` | `contracts-authentication`, `port-transaction`, `engine-consume-command` |
| `engine-write-pot` | `domain-pot`, `domain-pot-policy`, `port-transaction` |
| `engine-read-command-result` | `domain-user-identity` †, `engine-consume-command` † |
| `engine-materialize-command-result` | `domain-pot`, `engine-consumption`, `engine-consume-command`, `engine-read-command-result` |
| `engine-admit-registration` | `contracts-authentication`, `contracts-registration`, `port-transaction` |
| `engine-consume-registration` | `contracts-registration`, `port-binding-authority`, `port-transaction`, `engine-consumption` |
| `engine-materialize-registration-result` | `contracts-registration`, `port-transaction`, `engine-consumption` |
| `engine-read-registration-result` | `contracts-registration` |
| `engine-produce-projection-task` | `domain-event`, `domain-pot`, `domain-projection`, `port-projection` |
| `engine-consume-projection-task` | `domain-projection`, `port-transaction`, `port-projection`, `engine-consumption`, `orchestrator-consumption`, `projector-pot` |
| `engine-read-projection` | `domain-projection`, `port-projection` |
| `projector-pot` | `domain-pot`, `domain-projection` |
| `engine-read-pot` | `domain-authorization`, `domain-user-identity`, `domain-pot`, `domain-pot-policy`, `engine-read-projection` |
| `engine-materialize-current-binding` | `domain-user-identity`, `port-transaction`, `engine-consumption` |
| `engine-read-current-binding` | `domain-user-identity` |
| `engine-advance-pot-watermark` | `domain-pot` †, `domain-consumption` †, `engine-consumption` † |
| `supra-http-write` | `contracts-authentication`, `engine-admit-command`, `engine-admit-registration` |
| `supra-consume-command` | `orchestrator-consumption`, `engine-consume-command` |
| `supra-consume-event` | `orchestrator-consumption`, `engine-produce-projection-task` |
| `supra-consume-binding` | `orchestrator-consumption`, `engine-materialize-current-binding` |
| `supra-consume-registration` | `orchestrator-consumption`, `engine-consume-registration` |
| `infra-tx-spring` | `port-transaction` |
| `infra-persistence-primary-jpa` | `contracts-registration`, `contracts-observability`, `port-binding-authority`, `port-transaction`, `port-projection`, `engine-consumption`, `engine-consume-command`, `engine-admit-command`, `engine-write-pot`, `engine-read-command-result`, `engine-materialize-command-result`, `engine-admit-registration`, `engine-consume-registration`, `engine-materialize-registration-result`, `engine-read-registration-result`, `engine-produce-projection-task`, `engine-consume-projection-task`, `engine-read-pot`, `engine-materialize-current-binding`, `engine-advance-pot-watermark` † |
| `infra-persistence-projection-jdbc` | `domain-projection`, `port-projection` |
| `infra-persistence-read-jdbc` | `engine-materialize-current-binding`, `engine-read-current-binding`, `engine-advance-pot-watermark` † |
| `runtime-web-api` | `contracts-observability`, `supra-http-write`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jdbc`, `infra-persistence-read-jdbc`, `infra-projection-validation-networknt`, `supra-http-read` |
| `runtime-command-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-command`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-event-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-event`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-task-consumption-worker` | `orchestrator-poll-consumption`, `projector-pot`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jdbc`, `infra-persistence-read-jdbc`, `supra-consume-projection-task` |
| `runtime-binding-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-binding`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-read-jdbc` |
| `runtime-command-result-consumption-worker` | `orchestrator-poll-consumption`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `supra-consume-command-result` |
| `runtime-registration-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-registration`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-registration-result-consumption-worker` | `orchestrator-poll-consumption`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `supra-consume-registration-result` |
| `runtime-latest-known-version-consumption-worker` | `contracts-observability` †, `orchestrator-poll-consumption` †, `infra-tx-spring` †, `infra-persistence-primary-jpa` †, `infra-persistence-read-jdbc` †, `supra-consume-lkv` † |
| `architecture-tests` | — |
| `supra-consume-projection-task` | `orchestrator-consumption`, `engine-consume-projection-task` |
| `supra-consume-command-result` | `orchestrator-consumption`, `engine-materialize-command-result` |
| `supra-consume-registration-result` | `orchestrator-consumption`, `engine-materialize-registration-result` |
| `infra-projection-validation-networknt` | `domain-projection`, `port-projection` |
| `supra-http-read` | `engine-read-command-result`, `engine-read-registration-result`, `engine-read-pot`, `engine-read-current-binding` |
| `supra-consume-lkv` | `orchestrator-consumption` †, `engine-advance-pot-watermark` † |

**163 arcs logiques dans l'enveloppe conditionnelle ; 148 arcs hors conditions explicites ; zéro cycle.** Les dépendances de bibliothèque externes ne sont pas représentées. Le calcul des closures et du fan-in utilise les arcs directs ci-dessus, sans confondre responsabilité logique et POM.

## 6. Matrice Maven physique

La seule contraction ferme est `domain-pot-policy → domain-pot` ; ses arcs internes sont retirés, puis les doublons dédupliqués. La matrice suivante contient uniquement les 54 POM fermes. Elle conserve les deux imports provisoires de `engine-read-command-result` tant que `TBD-COMMAND-CONTRACT` est ouvert ; le caractère ferme qualifie les POM, pas ces deux arcs. Les trois responsabilités LKV restent en dehors du comptage ferme ; une enveloppe conditionnelle est mesurée séparément après la table.

| From POM | To POMs (dépendances directes) |
| --- | --- |
| `domain-authorization` | — |
| `domain-event` | — |
| `domain-user-identity` | — |
| `domain-pot` | `domain-authorization`, `domain-event` |
| `domain-consumption` | — |
| `domain-projection` | — |
| `contracts-authentication` | `domain-user-identity` |
| `contracts-registration` | `domain-user-identity` |
| `contracts-observability` | — |
| `port-binding-authority` | `domain-user-identity` |
| `port-transaction` | — |
| `port-projection` | `domain-projection` |
| `engine-consumption` | `domain-consumption`, `port-transaction` |
| `orchestrator-consumption` | `engine-consumption` |
| `orchestrator-poll-consumption` | `domain-consumption`, `orchestrator-consumption` |
| `engine-consume-command` | `domain-event`, `domain-user-identity`, `port-binding-authority`, `engine-consumption`, `engine-write-pot` |
| `engine-admit-command` | `contracts-authentication`, `port-transaction`, `engine-consume-command` |
| `engine-write-pot` | `domain-pot`, `port-transaction` |
| `engine-read-command-result` | `domain-user-identity`, `engine-consume-command` |
| `engine-materialize-command-result` | `domain-pot`, `engine-consumption`, `engine-consume-command`, `engine-read-command-result` |
| `engine-admit-registration` | `contracts-authentication`, `contracts-registration`, `port-transaction` |
| `engine-consume-registration` | `contracts-registration`, `port-binding-authority`, `port-transaction`, `engine-consumption` |
| `engine-materialize-registration-result` | `contracts-registration`, `port-transaction`, `engine-consumption` |
| `engine-read-registration-result` | `contracts-registration` |
| `engine-produce-projection-task` | `domain-event`, `domain-pot`, `domain-projection`, `port-projection` |
| `engine-consume-projection-task` | `domain-projection`, `port-transaction`, `port-projection`, `engine-consumption`, `orchestrator-consumption`, `projector-pot` |
| `engine-read-projection` | `domain-projection`, `port-projection` |
| `projector-pot` | `domain-pot`, `domain-projection` |
| `engine-read-pot` | `domain-authorization`, `domain-user-identity`, `domain-pot`, `engine-read-projection` |
| `engine-materialize-current-binding` | `domain-user-identity`, `port-transaction`, `engine-consumption` |
| `engine-read-current-binding` | `domain-user-identity` |
| `supra-http-write` | `contracts-authentication`, `engine-admit-command`, `engine-admit-registration` |
| `supra-consume-command` | `orchestrator-consumption`, `engine-consume-command` |
| `supra-consume-event` | `orchestrator-consumption`, `engine-produce-projection-task` |
| `supra-consume-binding` | `orchestrator-consumption`, `engine-materialize-current-binding` |
| `supra-consume-registration` | `orchestrator-consumption`, `engine-consume-registration` |
| `infra-tx-spring` | `port-transaction` |
| `infra-persistence-primary-jpa` | `contracts-registration`, `contracts-observability`, `port-binding-authority`, `port-transaction`, `port-projection`, `engine-consumption`, `engine-consume-command`, `engine-admit-command`, `engine-write-pot`, `engine-read-command-result`, `engine-materialize-command-result`, `engine-admit-registration`, `engine-consume-registration`, `engine-materialize-registration-result`, `engine-read-registration-result`, `engine-produce-projection-task`, `engine-consume-projection-task`, `engine-read-pot`, `engine-materialize-current-binding` |
| `infra-persistence-projection-jdbc` | `domain-projection`, `port-projection` |
| `infra-persistence-read-jdbc` | `engine-materialize-current-binding`, `engine-read-current-binding` |
| `runtime-web-api` | `contracts-observability`, `supra-http-write`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jdbc`, `infra-persistence-read-jdbc`, `infra-projection-validation-networknt`, `supra-http-read` |
| `runtime-command-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-command`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-event-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-event`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-task-consumption-worker` | `orchestrator-poll-consumption`, `projector-pot`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jdbc`, `infra-persistence-read-jdbc`, `supra-consume-projection-task` |
| `runtime-binding-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-binding`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-read-jdbc` |
| `runtime-command-result-consumption-worker` | `orchestrator-poll-consumption`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `supra-consume-command-result` |
| `runtime-registration-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-registration`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-registration-result-consumption-worker` | `orchestrator-poll-consumption`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `supra-consume-registration-result` |
| `architecture-tests` | — |
| `supra-consume-projection-task` | `orchestrator-consumption`, `engine-consume-projection-task` |
| `supra-consume-command-result` | `orchestrator-consumption`, `engine-materialize-command-result` |
| `supra-consume-registration-result` | `orchestrator-consumption`, `engine-materialize-registration-result` |
| `infra-projection-validation-networknt` | `domain-projection`, `port-projection` |
| `supra-http-read` | `engine-read-command-result`, `engine-read-registration-result`, `engine-read-pot`, `engine-read-current-binding` |

**Graphe ferme : 54 nœuds, 147 arcs physiques, zéro cycle.** En réintroduisant conditionnellement les trois responsabilités LKV comme POM potentiels, l'enveloppe contient 57 nœuds et 160 arcs, zéro cycle ; cela ne statue pas leur statut Maven. `TBD-E2U` et `TBD-COMMAND-CONTRACT` ne sont ni nœuds ni arcs inventés.

### Fan-in / fan-out et closures utiles

Fan-in/out ci-dessous = nombre d'arcs directs du graphe physique ferme. Closure = nombre de POM **transitivement** accessibles, hors racine elle-même. Les closures reflètent les imports conservateurs de l'adapter PRIMARY groupé ; elles ne promettent pas une réduction du classpath réel avant migration.

| POM | Fan-in | Fan-out | Closure transitive |
| --- | ---: | ---: | ---: |
| `engine-consumption` | 8 | 2 | 2 |
| `orchestrator-consumption` | 9 | 1 | 3 |
| `orchestrator-poll-consumption` | 7 | 2 | 4 |
| `projector-pot` | 2 | 2 | 4 |
| `contracts-authentication` | 3 | 1 | 1 |
| `contracts-registration` | 5 | 1 | 1 |
| `contracts-observability` | 2 | 0 | 0 |
| `port-binding-authority` | 3 | 1 | 1 |
| `port-transaction` | 10 | 0 | 0 |
| `port-projection` | 6 | 1 | 1 |
| `engine-consume-command` | 5 | 5 | 9 |
| `engine-consume-projection-task` | 2 | 6 | 10 |
| `engine-materialize-current-binding` | 3 | 3 | 4 |
| `infra-persistence-primary-jpa` | 8 | 19 | 29 |
| `infra-persistence-projection-jdbc` | 2 | 2 | 2 |
| `infra-persistence-read-jdbc` | 3 | 2 | 6 |
| `infra-projection-validation-networknt` | 1 | 2 | 2 |
| `supra-http-write` | 1 | 3 | 14 |
| `supra-http-read` | 1 | 4 | 18 |
| `runtime-web-api` | 0 | 8 | 37 |
| `runtime-command-consumption-worker` | 0 | 4 | 33 |
| `runtime-event-consumption-worker` | 0 | 4 | 33 |
| `runtime-task-consumption-worker` | 0 | 7 | 36 |
| `runtime-binding-consumption-worker` | 0 | 5 | 35 |
| `runtime-command-result-consumption-worker` | 0 | 4 | 33 |
| `runtime-registration-consumption-worker` | 0 | 4 | 33 |
| `runtime-registration-result-consumption-worker` | 0 | 4 | 33 |

## 7. Invariants, guards et décisions ouvertes

- Command conserve le fencing E+B, la séparation claim Consumption / effet, la provenance et le CAS gagnant ; le commit tardif est protégé. L'outcome est unique et la finalisation ne contourne pas le claim.
- Registration partage un owner fonctionnel tout en gardant quatre engines physiques. Son arbitrage empêche un User orphelin. Le GET Registration Result utilise E historique, reste opaque et indépendant du Binding courant ; son POM propre n'importe pas l'admission pour l'hébergement physique.
- Command Result et Registration Result sont `0..1`, immuables et possédés par E historique, même après detach/rebind. Aucun Result ne passe par ProjectionTask. `engine-read-command-result → engine-consume-command` est l'arc provisoire BC-04 ; `TBD-COMMAND-CONTRACT` reste ouvert, sans `contracts-command` créé.
- CURRENT_BINDING vient directement des facts Binding, sans ProjectionTask. Révisions monotones, tombstone DETACHED et divergence interdite à révision égale restent dans la matérialisation. Le GET Current Binding ne dépend pas de `port-binding-authority`.
- La projection exacte `@V` valide la clé puis la sortie et stocke root/artifact/failure exacts ; aucun fallback silencieux. `projector-pot` reste pur. GET Pot fait AUTH@V puis READ_POT@V via le port E→U à préciser et `engine-read-projection` ; `TBD-E2U` reste ouvert.
- `TBD-LKV` reste distinct de CURRENT_BINDING, de la version Pot courante et de la readiness projection. Owner fonctionnel, moteur, supra, runtime et choix READ provisoire ne sont pas décidés au fond ici.
- Le gate `architecture-tests` doit à terme vérifier `domain-* !→ Spring/JPA`, `projector-* !→ SQL/Spring/runtime`, `engine-* !→ runtime-*`, Consumption générique `!→` capacité métier, `supra-* !→` infra concrète non autorisée, `runtime-* !→ runtime-*` et Current Binding read `!→` Binding authority. Aucun gate global n'est requis pour cette édition documentaire.

## 8. Historique et vérification de cette révision

C, C.1 et C.2 expliquaient les frontières initiales ; leurs anciens choix de colocation et leurs anciens comptages ne sont plus normatifs. Les noms remplacés dans cette révision ne sont pas des cibles supplémentaires : le projecteur pur passe dans la famille `projector-*`, le polling générique dans `orchestrator-*`, HTTP se sépare en write/read, les sept supras Consumption ont leur POM, et les providers infra expriment rôle puis technologie. Le reactor n'est pas minimisé pour lui-même : les POM matérialisent les frontières choisies.

Vérification déclarée : édition documentaire seule, aucun slice Maven applicatif ; contrôle statique du mapping, des noms, des arcs, des cycles, des fan-in/out et des closures ; `git diff --check`. Aucun Maven, architecture gate ou full reactor nécessaire.

Les matrices et les comptes 54 POM / 147 arcs précédents sont des snapshots de conception **pré-checkpoint** ; ils ne décrivent plus le graphe courant après POST-WP4.A/B/C. Le graphe Maven actuel contient 73 POM et 354 arcs internes directs, legacy inclus, sans cycle. Le delta ci-dessous prévaut sur les arcs provisoires et les statuts TBD antérieurs.

## Delta normatif POST-WP4.A/B/C livré et vérifié (2026-10-04)

`contracts-command` possède uniquement CommandId, CommandType, RecordedCommand, TargetCommandEnvelope et CommandAuthenticationEvidence, valeurs de l’intake durable. `engine-admit-command` possède l’insertion, `engine-consume-command` la relecture et CommandOutcome. `engine-read-command-result` possède PublishedCommandResult et son Result immuable sans dépendance vers consume. RegistrationRequest reste dans `contracts-registration` ; RegistrationOutcome appartient à `engine-consume-registration`, et PublishedRegistrationResult à `engine-read-registration-result`. Les deux materializers convertissent après validation des sources. `engine-read-pot → engine-read-current-binding` remplace la résolution E→U PRIMARY ; le GET lit l’identité projetée convergente avant AUTH@V et READ_POT@V. Les tableaux et décomptes antérieurs décrivent le TARGET avant ce delta ; leurs arcs provisoires Command/E2U sont remplacés par ces arcs. La matérialisation CURRENT_BINDING suit C2 (maximum R), documenté dans [l’audit des révisions](Modularity_Post_WP4_Current_Binding_Revision_Audit.md). `TBD-COMMAND-CONTRACT` et `TBD-E2U` sont RESOLVED après preuves. `TBD-LKV` reste OPEN.

## Matérialisation WP5

L'identité Maven PRIMARY est `infra-persistence-primary-jpa`. Les packages Java suivent cette identité ; les chemins classpath et le contenu des migrations PRIMARY restent inchangés. Les ports/loaders historiques de BALANCES, AUTH et READ_POT issus des deux anciens engines de projection sont hébergés par `engine-consume-projection-task.input`, tandis que les projectors purs restent dans `projector-pot` et les adapters SQL dans PRIMARY. `engine-core` et les façades métier/locators fermes D.19/D.23 n'ont plus de POM. Les huit runtimes fermes conservent leur identité et composent les capacités via `orchestrator-poll-consumption` et les supras spécialisés. Les modules mixtes `engine-processing-event` et `infra-read-persistence`, ainsi que le locator/runtime LKV, restent des propriétaires CURRENT provisoires de la seule famille `TBD-LKV`. Les deux anciens POM domaine vides sont réservés à D.24/WP6. Ce delta décrit la matérialisation physique ; il ne change aucune frontière fonctionnelle TARGET ni la décision LKV.
