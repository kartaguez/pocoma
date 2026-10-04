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

Les cinq infra fermes sont `infra-tx-spring` (implémentation de `port-transaction`), `infra-persistence-primary-jpa` (ports PRIMARY/Consumption regroupés transactionnellement), `infra-persistence-read-jpa` (READ direct Current Binding ; LKV provisoire seulement sous décision ultérieure), `infra-persistence-projection-jpa` (root, artifact, failure, exact read/store) et `infra-projection-validation-networknt` (validation JSON Schema technique). Le cluster PRIMARY n'est pas redécoupé ici. La validation Networknt n'est pas fusionnée avec la persistence de projection.

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
| `infra-persistence-projection-jpa` | infra | KEEP_POM | `infra-persistence-projection-jpa` |
| `infra-persistence-read-jpa` | infra | KEEP_POM | `infra-persistence-read-jpa` |
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
| Pot GET | `runtime-web-api → supra-http-read → engine-read-pot → TBD-E2U port → engine-read-projection` |

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
| `infra-persistence-projection-jpa` | `domain-projection`, `port-projection` |
| `infra-persistence-read-jpa` | `engine-materialize-current-binding`, `engine-read-current-binding`, `engine-advance-pot-watermark` † |
| `runtime-web-api` | `contracts-observability`, `supra-http-write`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jpa`, `infra-persistence-read-jpa`, `infra-projection-validation-networknt`, `supra-http-read` |
| `runtime-command-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-command`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-event-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-event`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-task-consumption-worker` | `orchestrator-poll-consumption`, `projector-pot`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jpa`, `infra-persistence-read-jpa`, `supra-consume-projection-task` |
| `runtime-binding-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-binding`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-read-jpa` |
| `runtime-command-result-consumption-worker` | `orchestrator-poll-consumption`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `supra-consume-command-result` |
| `runtime-registration-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-registration`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-registration-result-consumption-worker` | `orchestrator-poll-consumption`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `supra-consume-registration-result` |
| `runtime-latest-known-version-consumption-worker` | `contracts-observability` †, `orchestrator-poll-consumption` †, `infra-tx-spring` †, `infra-persistence-primary-jpa` †, `infra-persistence-read-jpa` †, `supra-consume-lkv` † |
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
| `infra-persistence-projection-jpa` | `domain-projection`, `port-projection` |
| `infra-persistence-read-jpa` | `engine-materialize-current-binding`, `engine-read-current-binding` |
| `runtime-web-api` | `contracts-observability`, `supra-http-write`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jpa`, `infra-persistence-read-jpa`, `infra-projection-validation-networknt`, `supra-http-read` |
| `runtime-command-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-command`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-event-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-event`, `infra-tx-spring`, `infra-persistence-primary-jpa` |
| `runtime-task-consumption-worker` | `orchestrator-poll-consumption`, `projector-pot`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-projection-jpa`, `infra-persistence-read-jpa`, `supra-consume-projection-task` |
| `runtime-binding-consumption-worker` | `orchestrator-poll-consumption`, `supra-consume-binding`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `infra-persistence-read-jpa` |
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
| `infra-persistence-projection-jpa` | 2 | 2 | 2 |
| `infra-persistence-read-jpa` | 3 | 2 | 6 |
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
