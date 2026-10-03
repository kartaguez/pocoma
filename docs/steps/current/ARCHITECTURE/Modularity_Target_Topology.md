# DEBT-MOD-01 — topologie cible C.2 : rôles logiques et validation Maven

**Statut : TARGET / proposition non livrée.** Les rôles logiques et les POM physiques sont distingués en section N. Les noms ci-dessous ne décrivent pas le reactor exécutable. Les décisions [BC-01 à BC-16](Modularity_Boundary_Challenge.md#p-boundary-decisions) bornent cette cible ; les trois zones `TBD` ne deviennent pas des décisions par leur présence au catalogue. La [dette](../../../debts/MODULE_TAXONOMY/Debt.md) reste OPEN.

## A. Baseline et autorité

Après `git fetch origin`, branche `v2-make-it-pull` : HEAD local = `d52f4539022b18d0fcd09e01b9b4a1a6cff7a936`, `origin/v2-make-it-pull` = même SHA, divergence `0/0`, `git status --short` vide ; aucun changement depuis `d52f4539`. L’[audit CURRENT](Modularity_Current_State_Audit.md) compte 51 enfants Maven et neuf runtimes. Le [challenge B.1](Modularity_Boundary_Challenge.md), l’[architecture CURRENT](../../../architecture/Architecture.md) et les [garanties CURRENT](../../../guarantees/System_Guarantees.md) gouvernent les invariants. Cette révision est documentaire : aucun Java, POM, SQL ou runtime modifié.

## B. Architectural Module Grammar

Le préfixe annonce le **rôle**, puis le sujet. Il n’autorise pas à créer un POM sans import interdit ou autonomie prouvée. `domain-*` = concepts/valeurs/invariants purs ; `contracts-*` = langage stable partagé au-delà d’un SPI ; `port-*` = interfaces appelées selon une direction applicative→adapter ; `engine-*` = traitement applicatif, avec **verbe** sauf protocole cœur `engine-consumption` ; `orchestrator-*` = séquence de traitements sans ownership des règles ; `supra-*` = adaptation d’un protocole concret vers un engine/orchestrator ; `infra-*` = implémentation de ports ; `runtime-*` = composition, activation et déploiement. `architecture-tests` est une exception hors production, non une neuvième couche.

Une même capacité peut traverser plusieurs rôles. `KEEP_TOGETHER` dans les BC garde l’owner fonctionnel, pas un POM commun. La règle de score physique est : **STRONG** protège un import interdit ou une autonomie de processus établie ; **MEDIUM** protège une direction utile mais pourrait être un package ; **TBD** réserve une décision sans la masquer ; aucun nouveau POM WEAK. Les runtimes conservés MEDIUM ne sont pas sanctuarisés comme processus futurs.

### Matrice des rôles

| Responsibility | Engine | Orchestrator | Supra | Runtime |
| --- | --- | --- | --- | --- |
| Mutation Command | `engine-consume-command` exécute E+B | Consumption coordonne claim/finalize | `supra-consume-command` adapte candidate/reload/issue | `runtime-command-consumption-worker` câble/poll |
| Admission Command | `engine-admit-command` persiste la request | aucun orchestrator distinct démontré | `supra-http-api` traduit HTTP/principal | `runtime-web-api` compose |
| ProjectionTask | `engine-consume-projection-task` charge/projette/valide | Task spécialise la séquence dans son engine; générique reste Consumption | glue candidate→Task en package supra du runtime Task | runtime Task active le catalogue |
| Result terminal | `engine-materialize-command-result` décide la cohérence | Consumption gère le protocole fenced | glue terminal/reload en package supra du runtime Result | runtime Result règle backoff et activation |
| GET Pot | `engine-read-pot` orchestre E→U et exact @V | pas de POM orchestrator prouvé | `supra-http-api` adapte route, DTO et réponse | Web choisit adapter E→U |

### Normalisation de tous les noms TARGET C antérieurs

| Current TARGET name | Architectural type | Proposed normalized name / reason |
| --- | --- | --- |
| binding-authority-contracts | port | port-binding-authority — interfaces de mutation/authority, non langage partagé autonome |
| authentication-contracts | contracts | contracts-authentication — principal stable échangé avec Web |
| projection-pot | engine | engine-project-pot — calcul projecteur pur avec verbe |
| application-transaction-port | port | port-transaction — SPI transaction; score STRONG→MEDIUM |
| engine-projection-contracts | port | port-projection — interfaces exactes et producer SPI |
| engine-projection-read | engine | engine-read-projection — GET/revalidation exacte |
| engine-projection-task | engine | engine-consume-projection-task — Task treatment, plus interfaces minimales réassignées au port |
| engine-pot-read | engine | engine-read-pot — query Pot exacte |
| engine-binding-read | split | engine-materialize-current-binding + engine-read-current-binding — matérialisation directe et GET ont des dépendances distinctes |
| engine-command | engine | engine-consume-command — exécution Command |
| orchestrator-command-admission | engine | engine-admit-command — un use case, pas plusieurs engines orchestrés |
| engine-pot-command | engine | engine-write-pot — mutation Pot |
| engine-command-result | split | engine-materialize-command-result + engine-read-command-result — sources terminales distinctes de GET |
| engine-registration | split | quatre engine-*-registration + contracts-registration — admit/consume/materialize/read partagent owner, pas closure |
| engine-event-to-task | engine | engine-produce-projection-task — production Task depuis Event metadata |
| engine-read-projection (CURRENT) | engine | engine-project-pot / current / LKV TBD — agrégat CURRENT démêlé |
| TBD-LKV-work | engine | engine-advance-pot-watermark (TBD) — verbe du traitement actuel; owner final ouvert |
| supra-consumption-worker | supra | supra-poll-consumption — tick/segment adapté au protocole Consumption |
| observability | contracts | contracts-observability — contrat de trace sans provider |
| runtime-command / event / task / binding / result / registration / LKV | runtime | runtime-*-consumption-worker — noms explicites de composition/loop |

Les noms non cités gardent déjà un préfixe de rôle correct. La séparation d’un nom en deux moteurs reflète ici une frontière de dépendance exposée dans le catalogue, pas une règle « un verbe = un POM ».

## C. Ownership fonctionnel et moteurs

| Capacité | Owner fonctionnel | Découpage de traitement / motif physique |
| --- | --- | --- |
| Command / Pot WRITE | Command ; Pot pour la mutation | admission, execution Command et write Pot gardent closures distinctes |
| Command Result | Command Result | materialize connaît Event/outcome/Command ; read connaît owner E et résultat; BC-04 garde l’arc transitoire vers Command |
| Registration / Registration Result | Registration unique | contracts durables partagés ; admit Web, consume Request, materialize Outcome et read GET évitent les closures Binding/worker dans Web |
| Binding / CURRENT_BINDING | Binding | authority ports séparés des valeurs, materialize fact et read self séparés ; pas de Task |
| Event / ProjectionTask / Pot projection | Event, Task, Pot projection | Event metadata→Task ; Task→projector pur ; GET exact hors Task |
| Consumption | protocole transverse | engine cœur, deux orchestrateurs génériques, polling supra ; aucun générique→capacité |
| LKV | TBD-LKV | max observé distinct de current/readiness ; owner et autonomie TBD |

`engine-read-command-result → engine-consume-command` reste un **arc provisoire BC-04** : le modèle immutable actuel expose `CommandOutcome`/`CommandId` et la mesure de leurs consommateurs ne prouve pas un `contracts-command`. Le split Result protège la closure GET contre la matérialisation terminale, mais ne promet pas encore une closure GET sans Command. Aucun contrat Command neutre n’est créé. Pour Registration, `contracts-registration` contient le langage durable partagé par Web et les deux loops ; il ne remplace pas les engines de traitement ni la séparation des commits. Le POM `infra-persistence-jpa` restant agrégé, ces splits limitent d’abord les **imports applicatifs directs** ; ils ne réduisent pas encore toute la closure transitive des runtimes. Une réduction physique de cette closure dépendrait d’une preuve ultérieure de frontière adapter, selon BC-13.

## D. Supra Integration Boundaries

Un supra adapte une **entrée/sortie concrète** et peut porter le séquencement discovery→reload par ports puis la traduction de l’issue vers Consumption. Il ne porte ni SQL, ni sémantique métier profonde, ni claim/fencing générique, ni process lifecycle. Les policies sémantiques des locators vont aux engines concernés ; une classe locator mêlée peut être redistribuée, elle n’est pas déplacée en bloc (BC-05).

| Protocole concret | Supra responsibility | POM or package | Invoked engine/orchestrator | Ports / excluded |
| --- | --- | --- | --- | --- |
| HTTP/JWT Command, Pot, Result, Binding, Registration | DTO/errors/principal et mapping IO | C.1 : POM MEDIUM ; C.2 : voir N | admit/read engines | ports applicatifs ; pas de resolver PRIMARY concret |
| Command candidate/slot | discovery→authoritative reload→issue | C.1 : POM MEDIUM ; C.2 : voir N | consume Command + Consumption | discovery/source ports ; SQL infra |
| Event candidate metadata | metadata-only→ensure Task→issue | C.1 : POM MEDIUM ; C.2 : voir N | produce Task + Consumption | Event discovery/Task ensure ; pas EventLoader artificiel |
| ProjectionTask candidate | Task key→Task engine→issue | package `supra` dans runtime Task ; POM rejeté faute d’autre client | consume ProjectionTask | Task ports ; pure projector hors supra |
| Binding fact candidate | discovery→reload fact eventId→issue | C.1 : POM MEDIUM ; C.2 : voir N | materialize Current + Consumption | fact ports ; R rule engine, SQL infra |
| Command Result terminal candidate | Event/outcome/Command reload→issue | package `supra` runtime Result ; POM séparé non démontré | materialize Command Result | source ports ; terminal policy engine |
| Registration Request candidate | request reload→issue | C.1 : POM MEDIUM pour Request ; C.2 : voir N | consume Registration | request ports ; arbitrage engine |
| Registration Outcome candidate | request/outcome reload→issue | package `supra` runtime Registration Result ; POM rejeté pour préserver sa closure | materialize Registration Result | outcome ports ; owner E engine |
| LKV Event candidate | Event metadata→max issue | package `supra` runtime LKV provisoire ; POM TBD, non créé | advance Pot watermark TBD | discovery/max ports ; aucune readiness |
| Tick/segment de worker | poll/cadence→Consumption generic | POM `supra-poll-consumption` STRONG | orchestrator Consumption | ni business policy ni SQL |

La proposition C.1 de POM HTTP sépare traduction de protocole et composition Spring ; C.2 statue en section N. Son MEDIUM demande de confirmer à la migration qu’un package Web ne donne pas la même protection. Les propositions C.1 de POM supra Command/Event/Binding/Registration Request portaient cette direction ; C.2 les requalifie physiquement en section N sans changer leur rôle. Les supras Task/Results/LKV **existent conceptuellement** même sans POM.

## E. Contracts, ports, domaines et `engine-core`

`contracts-authentication` et `contracts-registration` portent des messages/valeurs partagés ; `contracts-observability` reste MEDIUM. `port-binding-authority` isole les interfaces PRIMARY des valeurs E/U/B/R. `port-projection` porte SPI exact et les interfaces minimales InputLoader/Projector ; la validation porte sur **la projection produite**, après contrôle de ProjectionKey. `port-transaction` ne contient que `TransactionRunner` : port partagé par plusieurs engines et adapter Spring, score **MEDIUM** parce qu’un POM pour une interface reste une frontière coûteuse. Aucun `contracts-common`, `port-common` ni nouveau core.

`engine-core` disparaît comme agrégat : TransactionRunner→port-transaction ; snapshots/version Pot, UserContext et erreurs Pot→engine-write-pot ou valeur domain-pot selon usage ; RecordedEvent/EventTraceMetadata Pot→domain-pot pour éviter le cycle domain-event↔domain-pot ; WorkerSegment/PartitionHash→domain-consumption ; wrappers legacy seulement SQL→infra-persistence-jpa. Les `domain-*` restent sans Spring/JPA ; leur nombre n’est pas déduit de DDD. `domain-event` MEDIUM garde la séparation du contrat minimal et du BusinessEvent Pot ; `domain-pot-policy` évite le couplage WRITE/READ. Les modèles Registration et Result ne deviennent pas des domaines supplémentaires faute d’import interdit prouvé.

## F. Projection, Binding, persistence, GET Pot et TBD

Pot exact : Event metadata→Task→`engine-consume-projection-task`→`engine-project-pot` (input autoritaire→projection désirée)→contrôle key→validator de sortie→store root/artifact exact. `engine-read-projection` lit @V sans Task ; `engine-read-pot` interprète AUTH@V puis READ_POT@V. Results et CURRENT_BINDING restent directs, sans ProjectionTask. `infra-projection-adapters` garde persistence exacte et JSON Schema comme adapters, non comme calcul projecteur. `infra-persistence-jpa` reste un POM PRIMARY/Consumption à clusters de packages : même transaction, même datasource, même JPA et même POM sont quatre propriétés distinctes ; aucun split SQL interne sans preuve (BC-12/13). `infra-read-persistence` héberge current et max LKV provisoirement, sans prétendre un owner commun.

Binding authority et Current Binding ont le même owner fonctionnel mais non la même closure. Fact append-only→supra Binding→materialize Current→READ store ; GET self→read Current→READ store. Les révisions monotones, tombstone DETACHED et divergence interdite à révision égale demeurent dans le moteur de matérialisation. Aucun délai borné promis. Le GET Pot suit HTTP supra→entrée `engine-read-pot`→port E→U→adapter PRIMARY actuel ou future READ ; l’owner final et la source E→U sont **TBD-E2U**. Le runtime Web choisit l’adapter mais n’exécute pas cette orchestration. La lecture Pot actuelle franchit donc toujours PRIMARY au travers de ce port explicite ; une future source READ exige sa propre sémantique de retard/révocation.

| LKV option | Owner possible | Runtime need | Migration impact / missing evidence |
| --- | --- | --- | --- |
| A capacité autonome | LKV Pot | loop indépendante; processus TBD | contrat aval indépendant à démontrer |
| B primitive technique de projection/consumption | pipeline de production | loop indépendante; processus éventuellement cohabité | lien technique à Task/projection non prouvé |
| C observability/watermark | exploitation | loop indépendante; processus éventuellement cohabité | aucun lecteur métier `src/main` identifié historiquement; cela ne prouve pas l’owner |

La mécanique actuelle LKV se classe : engine d’avance de maximum (`TM-33`, **TBD**), glue supra en package runtime, ports de discovery/max dans les frontières applicatives correspondantes, implémentation SQL infra, composition runtime `TM-51` **TBD**. Elle n’est ni version métier courante, ni readiness projection, ni candidate à suppression par défaut. `TBD-COMMAND-CONTRACT` demeure également ouvert.

## G. Runtimes : cinq propriétés distinctes

Chaque runtime actuel a une Spring application et un POM. Cela ne démontre ni un besoin durable de process distinct ni une frontière STRONG. Les neuf slots/loops restent séparés dans cette proposition ; toute cohabitation ultérieure doit conserver identité, retry et finalisation propres.

| Runtime cible | Slot indépendant | Loop indépendante | Spring app actuelle | Déploiement autonome requis ? | POM cible / strength |
| --- | --- | --- | --- | --- | --- |
| Web | sans objet | HTTP synchrone | oui | oui, surface HTTP distincte | TM-43 PROPOSED / STRONG |
| Command | oui | oui | oui | non démontré durablement | TM-44 PROPOSED / MEDIUM |
| Event | oui | oui | oui | non démontré durablement | TM-45 PROPOSED / MEDIUM |
| Task | oui | oui | oui | non démontré durablement | TM-46 PROPOSED / MEDIUM |
| Binding | oui | oui | oui | non démontré durablement | TM-47 PROPOSED / MEDIUM |
| Command Result | oui | oui | oui | TBD | TM-48 TBD / TBD |
| Registration Request | oui | oui | oui | non démontré durablement | TM-49 PROPOSED / MEDIUM |
| Registration Result | oui | oui | oui | TBD | TM-50 TBD / TBD |
| LKV | oui | oui | oui | TBD-LKV | TM-51 TBD / TBD |

Dans tous les cas, le runtime ne possède que Spring configuration, activation, cadence/backoff, metrics de process, transactions et choix des implémentations. Les packages supra locaux Task/Results/LKV sont des responsabilités distinctes du runtime même lorsqu’ils y cohabitent physiquement.

## H. Matrice canonique des arcs logiques TARGET

**Source de vérité des arcs logiques C.1 ; la matrice Maven contractée et canonique est en N.** `A → B` signifie que A importe B. `Required? yes` concerne la proposition ferme ou l’enveloppe actuelle compatible ; `TBD? yes` signifie que l’arc devra être revalidé avant migration. Les imports de bibliothèques externes sont omis. Trois arcs C.1 manquants sont rétablis sur preuve CURRENT : Command→domain-event (EventAppendPort/CommandTerminalEventTypes), persistence→contracts-observability (TraceContextHolder), Web→contracts-observability (TraceCorrelationFilter). Leur maintien TARGET est prudent tant que ces usages existent ; ce n’est pas un nouveau rôle. Les `Dependencies` et `Dependents` des fiches TM sont calculés à partir de cette matrice. Aucun arc runtime→runtime, engine→runtime, generic Consumption→business capability, projector pur→SQL/Spring. Le tri topologique de ces arcs est acyclique. Les chaînes fonctionnelles en section I décrivent un ordre d’exécution, **pas** un sens d’import Maven.

| From TM | To TM | Reason | Required? | TBD? |
| --- | --- | --- | --- | --- |
| TM-04 | TM-02 | utilise domain-event pour identité event minimale | yes | no |
| TM-05 | TM-01 | utilise domain-authorization pour règles et valeurs pures d’accès | yes | no |
| TM-05 | TM-04 | utilise domain-pot pour modèle primaire pur | yes | no |
| TM-08 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | yes | no |
| TM-09 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | yes | no |
| TM-11 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | yes | no |
| TM-13 | TM-07 | utilise domain-projection pour identité et sortie exactes | yes | no |
| TM-14 | TM-06 | utilise domain-consumption pour modèle de concurrence | yes | no |
| TM-14 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-15 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | yes | no |
| TM-16 | TM-15 | adapte vers orchestrator-consumption | yes | no |
| TM-16 | TM-06 | adapte vers domain-consumption | yes | no |
| TM-17 | TM-02 | utilise domain-event pour identité event minimale | yes | no |
| TM-17 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | yes | no |
| TM-17 | TM-11 | utilise port-binding-authority pour spi de mutation/arbitrage primaire | yes | no |
| TM-17 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | yes | no |
| TM-18 | TM-08 | utilise contracts-authentication pour principal attesté neutre | yes | no |
| TM-18 | TM-17 | utilise engine-consume-command pour exécuter une command autoritaire | yes | no |
| TM-18 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-19 | TM-04 | utilise domain-pot pour modèle primaire pur | yes | no |
| TM-19 | TM-05 | utilise domain-pot-policy pour politique d’accès pure | yes | no |
| TM-19 | TM-17 | utilise engine-consume-command pour exécuter une command autoritaire | yes | no |
| TM-19 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-20 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | conditional | yes |
| TM-20 | TM-17 | CommandId/Outcome actuels ; BC-04 non résolu | conditional | yes |
| TM-21 | TM-20 | utilise engine-read-command-result pour lire un result opaque par e historique | yes | no |
| TM-21 | TM-17 | utilise engine-consume-command pour exécuter une command autoritaire | yes | no |
| TM-21 | TM-04 | utilise domain-pot pour modèle primaire pur | yes | no |
| TM-21 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | yes | no |
| TM-22 | TM-09 | utilise contracts-registration pour langage durable commun aux deux loops et get | yes | no |
| TM-22 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-23 | TM-09 | utilise contracts-registration pour langage durable commun aux deux loops et get | yes | no |
| TM-23 | TM-11 | utilise port-binding-authority pour spi de mutation/arbitrage primaire | yes | no |
| TM-23 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | yes | no |
| TM-23 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-24 | TM-09 | utilise contracts-registration pour langage durable commun aux deux loops et get | yes | no |
| TM-24 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | yes | no |
| TM-24 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-25 | TM-09 | utilise contracts-registration pour langage durable commun aux deux loops et get | yes | no |
| TM-26 | TM-02 | utilise domain-event pour identité event minimale | yes | no |
| TM-26 | TM-04 | utilise domain-pot pour modèle primaire pur | yes | no |
| TM-26 | TM-07 | utilise domain-projection pour identité et sortie exactes | yes | no |
| TM-26 | TM-13 | utilise port-projection pour ports exacts et spi de producer | yes | no |
| TM-27 | TM-07 | utilise domain-projection pour identité et sortie exactes | yes | no |
| TM-27 | TM-13 | utilise port-projection pour ports exacts et spi de producer | yes | no |
| TM-27 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | yes | no |
| TM-27 | TM-15 | utilise orchestrator-consumption pour coordonner les deux séquences génériques | yes | no |
| TM-27 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-28 | TM-07 | utilise domain-projection pour identité et sortie exactes | yes | no |
| TM-28 | TM-13 | utilise port-projection pour ports exacts et spi de producer | yes | no |
| TM-29 | TM-04 | utilise domain-pot pour modèle primaire pur | yes | no |
| TM-29 | TM-07 | utilise domain-projection pour identité et sortie exactes | yes | no |
| TM-29 | TM-13 | utilise port-projection pour ports exacts et spi de producer | yes | no |
| TM-30 | TM-01 | utilise domain-authorization pour règles et valeurs pures d’accès | yes | no |
| TM-30 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | yes | no |
| TM-30 | TM-04 | utilise domain-pot pour modèle primaire pur | yes | no |
| TM-30 | TM-05 | utilise domain-pot-policy pour politique d’accès pure | yes | no |
| TM-30 | TM-28 | utilise engine-read-projection pour lire/revalider un artifact @v | yes | no |
| TM-31 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | yes | no |
| TM-31 | TM-12 | utilise port-transaction pour exécution transactionnelle abstraite | yes | no |
| TM-31 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | yes | no |
| TM-32 | TM-03 | utilise domain-user-identity pour valeurs et facts purs | yes | no |
| TM-33 | TM-04 | utilise domain-pot pour modèle primaire pur | conditional | yes |
| TM-33 | TM-06 | utilise domain-consumption pour modèle de concurrence | conditional | yes |
| TM-33 | TM-14 | utilise engine-consumption pour traiter le protocole acquire/finalize | conditional | yes |
| TM-34 | TM-08 | adapte vers contracts-authentication | yes | no |
| TM-34 | TM-18 | adapte vers engine-admit-command | yes | no |
| TM-34 | TM-22 | adapte vers engine-admit-registration | yes | no |
| TM-34 | TM-25 | adapte vers engine-read-registration-result | yes | no |
| TM-34 | TM-30 | adapte vers engine-read-pot | yes | no |
| TM-34 | TM-32 | adapte vers engine-read-current-binding | yes | no |
| TM-34 | TM-20 | adapte vers engine-read-command-result | yes | no |
| TM-35 | TM-15 | adapte vers orchestrator-consumption | yes | no |
| TM-35 | TM-17 | adapte vers engine-consume-command | yes | no |
| TM-36 | TM-15 | adapte vers orchestrator-consumption | yes | no |
| TM-36 | TM-26 | adapte vers engine-produce-projection-task | yes | no |
| TM-37 | TM-15 | adapte vers orchestrator-consumption | yes | no |
| TM-37 | TM-31 | adapte vers engine-materialize-current-binding | yes | no |
| TM-38 | TM-15 | adapte vers orchestrator-consumption | yes | no |
| TM-38 | TM-23 | adapte vers engine-consume-registration | yes | no |
| TM-39 | TM-12 | implémente le contrat de port-transaction | yes | no |
| TM-40 | TM-10 | implémente le contrat de contracts-observability | yes | no |
| TM-40 | TM-11 | implémente le contrat de port-binding-authority | yes | no |
| TM-40 | TM-12 | implémente le contrat de port-transaction | yes | no |
| TM-40 | TM-13 | implémente le contrat de port-projection | yes | no |
| TM-40 | TM-14 | implémente le contrat de engine-consumption | yes | no |
| TM-40 | TM-17 | implémente le contrat de engine-consume-command | yes | no |
| TM-40 | TM-18 | implémente le contrat de engine-admit-command | yes | no |
| TM-40 | TM-19 | implémente le contrat de engine-write-pot | yes | no |
| TM-40 | TM-20 | implémente le contrat de engine-read-command-result | yes | no |
| TM-40 | TM-21 | implémente le contrat de engine-materialize-command-result | yes | no |
| TM-40 | TM-22 | implémente le contrat de engine-admit-registration | yes | no |
| TM-40 | TM-23 | implémente le contrat de engine-consume-registration | yes | no |
| TM-40 | TM-24 | implémente le contrat de engine-materialize-registration-result | yes | no |
| TM-40 | TM-25 | implémente le contrat de engine-read-registration-result | yes | no |
| TM-40 | TM-26 | implémente le contrat de engine-produce-projection-task | yes | no |
| TM-40 | TM-27 | implémente le contrat de engine-consume-projection-task | yes | no |
| TM-40 | TM-30 | implémente le contrat de engine-read-pot | yes | no |
| TM-40 | TM-31 | implémente le contrat de engine-materialize-current-binding | yes | no |
| TM-40 | TM-33 | implémente le contrat de engine-advance-pot-watermark | conditional | yes |
| TM-40 | TM-09 | implémente le contrat de contracts-registration | yes | no |
| TM-41 | TM-07 | implémente le contrat de domain-projection | yes | no |
| TM-41 | TM-13 | implémente le contrat de port-projection | yes | no |
| TM-42 | TM-31 | implémente le contrat de engine-materialize-current-binding | yes | no |
| TM-42 | TM-32 | implémente le contrat de engine-read-current-binding | yes | no |
| TM-42 | TM-33 | implémente le contrat de engine-advance-pot-watermark | conditional | yes |
| TM-43 | TM-10 | compose contracts-observability | yes | no |
| TM-43 | TM-34 | compose supra-http-api | yes | no |
| TM-43 | TM-39 | compose infra-tx-spring | yes | no |
| TM-43 | TM-40 | compose infra-persistence-jpa | yes | no |
| TM-43 | TM-41 | compose infra-projection-adapters | yes | no |
| TM-43 | TM-42 | compose infra-read-persistence | yes | no |
| TM-44 | TM-16 | compose supra-poll-consumption | yes | no |
| TM-44 | TM-35 | compose supra-consume-command | yes | no |
| TM-44 | TM-39 | compose infra-tx-spring | yes | no |
| TM-44 | TM-40 | compose infra-persistence-jpa | yes | no |
| TM-45 | TM-16 | compose supra-poll-consumption | yes | no |
| TM-45 | TM-36 | compose supra-consume-event | yes | no |
| TM-45 | TM-39 | compose infra-tx-spring | yes | no |
| TM-45 | TM-40 | compose infra-persistence-jpa | yes | no |
| TM-46 | TM-16 | compose supra-poll-consumption | yes | no |
| TM-46 | TM-27 | compose engine-consume-projection-task | yes | no |
| TM-46 | TM-29 | compose engine-project-pot | yes | no |
| TM-46 | TM-39 | compose infra-tx-spring | yes | no |
| TM-46 | TM-40 | compose infra-persistence-jpa | yes | no |
| TM-46 | TM-41 | compose infra-projection-adapters | yes | no |
| TM-46 | TM-42 | compose infra-read-persistence | yes | no |
| TM-47 | TM-16 | compose supra-poll-consumption | yes | no |
| TM-47 | TM-37 | compose supra-consume-binding | yes | no |
| TM-47 | TM-39 | compose infra-tx-spring | yes | no |
| TM-47 | TM-40 | compose infra-persistence-jpa | yes | no |
| TM-47 | TM-42 | compose infra-read-persistence | yes | no |
| TM-48 | TM-16 | compose supra-poll-consumption | conditional | yes |
| TM-48 | TM-21 | compose engine-materialize-command-result | conditional | yes |
| TM-48 | TM-39 | compose infra-tx-spring | conditional | yes |
| TM-48 | TM-40 | compose infra-persistence-jpa | conditional | yes |
| TM-49 | TM-16 | compose supra-poll-consumption | yes | no |
| TM-49 | TM-38 | compose supra-consume-registration | yes | no |
| TM-49 | TM-39 | compose infra-tx-spring | yes | no |
| TM-49 | TM-40 | compose infra-persistence-jpa | yes | no |
| TM-50 | TM-16 | compose supra-poll-consumption | conditional | yes |
| TM-50 | TM-24 | compose engine-materialize-registration-result | conditional | yes |
| TM-50 | TM-39 | compose infra-tx-spring | conditional | yes |
| TM-50 | TM-40 | compose infra-persistence-jpa | conditional | yes |
| TM-51 | TM-16 | compose supra-poll-consumption | conditional | yes |
| TM-51 | TM-33 | compose engine-advance-pot-watermark | conditional | yes |
| TM-51 | TM-39 | compose infra-tx-spring | conditional | yes |
| TM-51 | TM-40 | compose infra-persistence-jpa | conditional | yes |
| TM-51 | TM-42 | compose infra-read-persistence | conditional | yes |
| TM-51 | TM-10 | compose contracts-observability | conditional | yes |

## I. Chaînes cibles, frontières interdites et invariants

| Chaîne | Traitement et ports/adapters | Invariant préservé |
| --- | --- | --- |
| Command | runtime44→supra35→engine17/19→ports11/12/Consumption14→infra40 | admission 18 séparée ; E+B fenced ; outcome+terminal Event dans commit gagnant |
| Registration | runtime49→supra38→engine23→ports11/12→infra40 | arbitrage Binding/User, pas de User orphelin, outcome unique |
| Command Result | runtime48→supra package→engine21→source/store infra40 ; Web43→supra34→engine20 | terminal cohérent, 0..1 immutable, owner E historique, pas de Binding current |
| Registration Result | runtime50→supra package→engine24→infra40 ; Web43→supra34→engine25 | loop Outcome distincte, Result lisible après detach/rebind |
| Event→Task | runtime45→supra36→engine26→Task ensure infra40 | metadata-only, pas d’EventLoader artificiel |
| Task→Projection | runtime46→supra package→engine27→engine29 pur→port13→infra41 | key check puis validation sortie, exact @V, finalisation fenced |
| Binding→Current | runtime47→supra37→engine31→port/store infra42 | facts append-only, R monotone, tombstone, pas Task |
| GET Pot | Web43→supra34→engine30→port E→U TBD + exact read28→infra40/41 | AUTH@V puis READ_POT@V exact, aucun fallback silencieux |
| LKV | runtime51 TBD→supra package→engine33 TBD→max store infra42 | maximum observé fenced, pas current/readiness |

| From | Must NOT depend on | Reason / limit |
| --- | --- | --- |
| domain et projector pur | JPA/Spring/SQL | valeurs et calcul indépendants |
| Consumption générique (TM-06/14/15) | Command/Registration/Task/Binding/Results/LKV | protocole transverse sans ownership métier |
| supra polling TM-16 | business semantics, SQL | tick/segments seulement |
| engine applicatif | runtime ou HTTP | règle indépendante du déploiement |
| runtime | runtime pair | racines composables |
| HTTP supra/controller | adapter PRIMARY E→U concret | source derrière port applicatif TBD |
| Pot/Binding read | PRIMARY mutation adapter | GET Pot franchit provisoirement PRIMARY via port E→U explicite |
| Result read | entire Command engine pour un seul type, à terme | exception actuelle BC-04 maintenue, solution TBD |

## J. Mapping CURRENT → TARGET (51 modules)

`SPLIT RESPONSIBILITIES` indique une réattribution conceptuelle ; ce n’est pas un déplacement de classe prescrit. Les modules courants restent inchangés.

| Current module | Disposition | Target owner / boundary | Note |
| --- | --- | --- | --- |
| `domain-authorization` | KEEP | TM-01 | Règles pures. |
| `domain-event` | KEEP | TM-02 | Contrat minimal, MEDIUM. |
| `domain-user-identity` | SPLIT RESPONSIBILITIES | TM-03, TM-11 | Valeurs/facts vs ports d’autorité; E→U TBD. |
| `authentication-contracts` | RENAME ROLE | TM-08 | Principal neutre partagé. |
| `domain-pot` | KEEP | TM-04 | BusinessEvent/RecordedEvent Pot. |
| `domain-pot-projection` | MERGE INTO | TM-29 | Définitions exactes pures. |
| `domain-projection-balance` | MERGE INTO | TM-29 | Calcul Balance pur. |
| `domain-projection` | KEEP | TM-07 | Identité et validator de sortie. |
| `domain-pot-policy` | KEEP | TM-05 | AUTH policy pure. |
| `domain-consumption` | KEEP | TM-06 | Claim/lease/provenance. |
| `engine-core` | DISSOLVE AS MAVEN BOUNDARY | TM-04, TM-06, TM-12, TM-19, TM-40 | TransactionRunner→12; Pot snapshots→19; RecordedEvent→04; segments→06; legacy SQL→40. |
| `engine-consumption` | KEEP | TM-14 | Moteur cœur du protocole. |
| `engine-command` | RENAME ROLE | TM-17 | Exécution et sémantique Command. |
| `engine-command-result` | SPLIT RESPONSIBILITIES | TM-20, TM-21 | GET vs terminal materialization; dépendance Command BC-04 ouverte. |
| `engine-registration` | SPLIT RESPONSIBILITIES | TM-09, TM-22, TM-23, TM-24, TM-25 | Même owner Registration; frontières par dépendance/loop, pas par esthétique. |
| `engine-processing-event` | SPLIT RESPONSIBILITIES | TM-26, TM-33 | Event→Task vs LKV TBD. |
| `engine-pot-command` | RENAME ROLE | TM-19 | Mutation Pot. |
| `engine-projection-contracts` | RENAME ROLE | TM-13 | SPI de projection exacte. |
| `engine-projection-read` | RENAME ROLE | TM-28 | Query exacte générique. |
| `engine-projection-task` | SPLIT RESPONSIBILITIES | TM-13, TM-27 | Interfaces producer minimales dans port; Task processing dans engine. |
| `engine-pot-read` | RENAME ROLE | TM-30 | Query Pot; E→U source/owner TBD. |
| `engine-projection-balance` | MERGE INTO | TM-29 | Projector pur. |
| `engine-projection-pot` | MERGE INTO | TM-29 | Projector et input abstraction purs. |
| `engine-read-projection` | SPLIT RESPONSIBILITIES | TM-29, TM-31, TM-32, TM-33 | Pot historique, Current materialize/read et LKV distincts. |
| `observability` | RENAME ROLE | TM-10 | Contrat de trace; frontière MEDIUM. |
| `infra-tx-spring` | KEEP | TM-39 | Adapter transaction. |
| `infra-persistence-jpa` | KEEP | TM-40 | Clusters SQL en packages; pas de split imposé. |
| `infra-projection-persistence` | MERGE INTO | TM-41 | Store exact. |
| `infra-read-persistence` | KEEP | TM-42 | Current et LKV provisoire. |
| `infra-projection-json-schema` | MERGE INTO | TM-41 | Adapter schema. |
| `orchestrator-consumption` | SPLIT RESPONSIBILITIES | TM-15, TM-27 | Deux séquences génériques vs spécialisation Task. |
| `orchestrator-command-admission` | RENAME ROLE | TM-18 | Un use case d’admission, donc engine. |
| `supra-consumption-worker` | RENAME ROLE | TM-16 | Adaptateur tick/segment de polling générique. |
| `locator-consumption-event` | SPLIT RESPONSIBILITIES | TM-26, TM-36, TM-40 | Sémantique→engine, glue→supra, SQL→infra, cadence→runtime. |
| `locator-consumption-latest-known-version` | SPLIT RESPONSIBILITIES | TM-33, TM-40, TM-51 | Sémantique LKV TBD, glue package supra du runtime, SQL infra. |
| `locator-consumption-binding` | SPLIT RESPONSIBILITIES | TM-31, TM-37, TM-40 | Règle R→engine, fact glue→supra, SQL→infra. |
| `locator-consumption-command` | SPLIT RESPONSIBILITIES | TM-17, TM-35, TM-40 | E+B/fail→engine, discovery/reload glue→supra, SQL→infra. |
| `binding-pot-command-spring` | DISSOLVE AS MAVEN BOUNDARY | TM-44 | Adapter local de composition Command, pas moteur. |
| `supra-http-write-command` | MERGE INTO | TM-34 | Package write-command du supra HTTP commun. |
| `supra-http-read-query` | SPLIT RESPONSIBILITIES | TM-34, TM-30 | HTTP mapping→supra; orchestration E→U→Pot read; source TBD. |
| `supra-authentication-spring-security` | MERGE INTO | TM-34 | Package principal/JWT du supra HTTP. |
| `runtime-web-api` | KEEP | TM-43 | Composition seulement. |
| `runtime-event-consumption-worker` | KEEP | TM-45 | Loop Event, POM MEDIUM. |
| `runtime-command-result-consumption-worker` | TBD | TM-48 | Loop indépendante; supra glue package local; POM/processus TBD. |
| `runtime-registration-result-consumption-worker` | TBD | TM-50 | Loop indépendante; supra glue package local; POM/processus TBD. |
| `runtime-registration-consumption-worker` | KEEP | TM-49, TM-38 | Composition Request ; Result glue reste package local de son runtime. |
| `runtime-latest-known-version-consumption-worker` | TBD | TM-51 | Loop conservée; process/POM TBD. |
| `runtime-binding-consumption-worker` | KEEP | TM-47 | Composition, glue dans supra Binding. |
| `runtime-task-consumption-worker` | KEEP | TM-46 | Composition; Task supra glue en package runtime. |
| `runtime-command-consumption-worker` | KEEP | TM-44 | Composition, glue dans supra Command. |
| `architecture-tests` | KEEP | TM-52 | Gate global test-only. |

## K. Target module catalog

Chaque fiche conserve la **proposition logique C.1** ; les décisions physiques C.2 de la section N prévalent sur ses champs `Status`, `Boundary strength` et `Why Maven` lorsqu’un POM devient package. Les listes `Dependencies`/`Dependents` sont dérivées de H. `Verb` est obligatoire pour les engines hors cœur ; `Concrete IO adapted` pour les supras ; `Deployment-only` pour les runtimes. `STRONG` sur un engine protège la séparation application→adapter/runtime ; les splits internes de capacité restent discutables si un même POM peut protéger la même direction.

### TARGET MODULE TM-01

- Name: `domain-authorization`
- Architectural type: `domain`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Règles et valeurs pures d’accès.
- Owner: Authorization.
- Responsibilities: permissions et capacités.
- Excluded responsibilities: Spring/JPA, HTTP, versions chargées.
- Dependencies: aucune interne.
- Dependents: TM-05, TM-30.
- Forbidden dependency protected: kernel partagé sans importer un moteur.
- Supporting BC: BC-09, BC-16.
- Current sources: `domain-authorization`.
- Why Maven rather than package? kernel partagé sans importer un moteur; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-02

- Name: `domain-event`
- Architectural type: `domain`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Identité Event minimale.
- Owner: Event.
- Responsibilities: types Event indépendants de Pot.
- Excluded responsibilities: RecordedEvent Pot, production Task.
- Dependencies: aucune interne.
- Dependents: TM-04, TM-17, TM-26.
- Forbidden dependency protected: évite le cycle Pot/Event.
- Supporting BC: BC-08, BC-16.
- Current sources: `domain-event`.
- Why Maven rather than package? évite le cycle Pot/Event; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-03

- Name: `domain-user-identity`
- Architectural type: `domain`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Valeurs et facts purs.
- Owner: Identity/Binding.
- Responsibilities: E, U, B, R, occurrence E+B, Attached/Detached.
- Excluded responsibilities: ports PRIMARY, SQL.
- Dependencies: aucune interne.
- Dependents: TM-08, TM-09, TM-11, TM-17, TM-20, TM-30, TM-31, TM-32.
- Forbidden dependency protected: les queries peuvent importer les valeurs sans autorité primaire.
- Supporting BC: BC-08, BC-14, BC-16.
- Current sources: `domain-user-identity`.
- Why Maven rather than package? les queries peuvent importer les valeurs sans autorité primaire; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-04

- Name: `domain-pot`
- Architectural type: `domain`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Modèle primaire pur.
- Owner: Pot.
- Responsibilities: Pot, Expense, BusinessEvent, RecordedEvent Pot.
- Excluded responsibilities: SQL, transaction, projector.
- Dependencies: TM-02.
- Dependents: TM-05, TM-19, TM-21, TM-26, TM-29, TM-30, TM-33.
- Forbidden dependency protected: Command et projectors partagent Pot sans adapter.
- Supporting BC: BC-09, BC-11, BC-16.
- Current sources: `domain-pot; engine-core`.
- Why Maven rather than package? Command et projectors partagent Pot sans adapter; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-05

- Name: `domain-pot-policy`
- Architectural type: `domain`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Politique d’accès pure.
- Owner: Pot Authorization.
- Responsibilities: règles AUTH partagées WRITE/READ.
- Excluded responsibilities: store, HTTP, SQL.
- Dependencies: TM-01, TM-04.
- Dependents: TM-19, TM-30.
- Forbidden dependency protected: WRITE/READ réutilisent le calcul sans se dépendre.
- Supporting BC: BC-09, BC-16.
- Current sources: `domain-pot-policy`.
- Why Maven rather than package? WRITE/READ réutilisent le calcul sans se dépendre; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-06

- Name: `domain-consumption`
- Architectural type: `domain`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Modèle de concurrence.
- Owner: Consumption.
- Responsibilities: key, slot, claim, lease, provenance, WorkerSegment.
- Excluded responsibilities: worker, SQL, business policy.
- Dependencies: aucune interne.
- Dependents: TM-14, TM-16, TM-33.
- Forbidden dependency protected: protocole réutilisable sans Spring ni capacités.
- Supporting BC: BC-06, BC-11, BC-16.
- Current sources: `domain-consumption; engine-core`.
- Why Maven rather than package? protocole réutilisable sans Spring ni capacités; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-07

- Name: `domain-projection`
- Architectural type: `domain`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Identité et sortie exactes.
- Owner: Projection exacte.
- Responsibilities: ProjectionKey, artifact, validator de sortie.
- Excluded responsibilities: Task polling, SQL, JSON Schema concret.
- Dependencies: aucune interne.
- Dependents: TM-13, TM-26, TM-27, TM-28, TM-29, TM-41.
- Forbidden dependency protected: identité @V partagée par producer et query.
- Supporting BC: BC-09, BC-10, BC-16.
- Current sources: `domain-projection`.
- Why Maven rather than package? identité @V partagée par producer et query; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-08

- Name: `contracts-authentication`
- Architectural type: `contracts`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Principal attesté neutre.
- Owner: Authentication.
- Shared contract rationale: stable data crossing capability/process boundaries without technology provider.
- Responsibilities: AuthenticatedExternalPrincipal.
- Excluded responsibilities: JWT/Spring Security.
- Dependencies: TM-03.
- Dependents: TM-18, TM-34.
- Forbidden dependency protected: admission sans technologie de sécurité.
- Supporting BC: BC-16.
- Current sources: `authentication-contracts`.
- Why Maven rather than package? admission sans technologie de sécurité; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-09

- Name: `contracts-registration`
- Architectural type: `contracts`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Langage durable commun aux deux loops et GET.
- Owner: Registration.
- Shared contract rationale: stable data crossing capability/process boundaries without technology provider.
- Responsibilities: request, outcome, Result, owner E historique.
- Excluded responsibilities: exécution, SQL, polling.
- Dependencies: TM-03.
- Dependents: TM-22, TM-23, TM-24, TM-25, TM-40.
- Forbidden dependency protected: évite que Web et workers importent un moteur réciproque.
- Supporting BC: BC-01, BC-02.
- Current sources: `engine-registration`.
- Why Maven rather than package? évite que Web et workers importent un moteur réciproque; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-10

- Name: `contracts-observability`
- Architectural type: `contracts`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Contexte de trace transversal.
- Owner: Observability.
- Shared contract rationale: stable data crossing capability/process boundaries without technology provider.
- Responsibilities: TraceContext et holder.
- Excluded responsibilities: provider, métriques LKV.
- Dependencies: aucune interne.
- Dependents: TM-40, TM-43, TM-51.
- Forbidden dependency protected: persistence et HTTP échangent une trace sans arc infra→runtime.
- Supporting BC: BC-11, BC-16.
- Current sources: `observability`.
- Why Maven rather than package? persistence et HTTP échangent une trace sans arc infra→runtime; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-11

- Name: `port-binding-authority`
- Architectural type: `port`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: SPI de mutation/arbitrage primaire.
- Owner: Binding authority.
- Port direction: application contract → concrete adapter.
- Responsibilities: acquire/detach, stream/fact et User authority.
- Excluded responsibilities: GET current, SQL.
- Dependencies: TM-03.
- Dependents: TM-17, TM-23, TM-40.
- Forbidden dependency protected: queries Binding n’importent pas les ports PRIMARY.
- Supporting BC: BC-08, BC-14, BC-16.
- Current sources: `domain-user-identity`.
- Why Maven rather than package? queries Binding n’importent pas les ports PRIMARY; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-12

- Name: `port-transaction`
- Architectural type: `port`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Exécution transactionnelle abstraite.
- Owner: Application transverse.
- Port direction: application contract → concrete adapter.
- Responsibilities: TransactionRunner.
- Excluded responsibilities: Spring, core fourre-tout.
- Dependencies: aucune interne.
- Dependents: TM-14, TM-18, TM-19, TM-22, TM-23, TM-24, TM-27, TM-31, TM-39, TM-40.
- Forbidden dependency protected: plusieurs engines importent le port sans Spring; un POM reste révisable.
- Supporting BC: BC-11, BC-12.
- Current sources: `engine-core`.
- Why Maven rather than package? plusieurs engines importent le port sans Spring; un POM reste révisable; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-13

- Name: `port-projection`
- Architectural type: `port`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Ports exacts et SPI de producer.
- Owner: Projection exacte.
- Port direction: application contract → concrete adapter.
- Responsibilities: read/publish @V, ProjectionInputLoader, ProjectionProjector.
- Excluded responsibilities: Task worker, SQL, JSON Schema concret.
- Dependencies: TM-07.
- Dependents: TM-26, TM-27, TM-28, TM-29, TM-40, TM-41.
- Forbidden dependency protected: query et producer n’importent pas Task.
- Supporting BC: BC-09, BC-10.
- Current sources: `engine-projection-contracts; engine-projection-task`.
- Why Maven rather than package? query et producer n’importent pas Task; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-14

- Name: `engine-consumption`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Traiter le protocole acquire/finalize.
- Owner: Consumption.
- Verb: consume / coordinate.
- Responsibilities: claim/fence/provenance/retry génériques.
- Excluded responsibilities: Command, Task, Result, polling.
- Dependencies: TM-06, TM-12.
- Dependents: TM-15, TM-17, TM-21, TM-23, TM-24, TM-27, TM-31, TM-33, TM-40.
- Forbidden dependency protected: opérations communes sans business capability.
- Supporting BC: BC-06, BC-07.
- Current sources: `engine-consumption`.
- Why Maven rather than package? opérations communes sans business capability; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-15

- Name: `orchestrator-consumption`
- Architectural type: `orchestrator`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Coordonner les deux séquences génériques.
- Owner: Consumption.
- Responsibilities: Sequential et AcquireThenFinalize.
- Excluded responsibilities: ProjectionTask specialization, SQL.
- Dependencies: TM-14.
- Dependents: TM-16, TM-27, TM-35, TM-36, TM-37, TM-38.
- Forbidden dependency protected: séquences réutilisées sans Task; POM à revalider.
- Supporting BC: BC-06, BC-07.
- Current sources: `orchestrator-consumption`.
- Why Maven rather than package? séquences réutilisées sans Task; POM à revalider; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-16

- Name: `supra-poll-consumption`
- Architectural type: `supra`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Adapter tick/segments à la consommation générique.
- Owner: Worker lifecycle.
- Concrete IO adapted: adapter tick/segments à la consommation générique.
- Responsibilities: ConsumptionPollingWorker, cadence, activation.
- Excluded responsibilities: policy métier, SQL.
- Dependencies: TM-15, TM-06.
- Dependents: TM-44, TM-45, TM-46, TM-47, TM-48, TM-49, TM-50, TM-51.
- Forbidden dependency protected: huit runtimes réutilisent la loop sans dépendre d’un autre runtime.
- Supporting BC: BC-06, BC-16.
- Current sources: `supra-consumption-worker`.
- Why Maven rather than package? huit runtimes réutilisent la loop sans dépendre d’un autre runtime; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-17

- Name: `engine-consume-command`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Exécuter une Command autoritaire.
- Owner: Command.
- Verb: consume.
- Responsibilities: identity, outcome, E+B fence, fail policy.
- Excluded responsibilities: SQL, polling, HTTP, Result.
- Dependencies: TM-02, TM-03, TM-11, TM-14.
- Dependents: TM-18, TM-19, TM-20, TM-21, TM-35, TM-40.
- Forbidden dependency protected: moteur exécutable sans runtime/adapters.
- Supporting BC: BC-03, BC-04, BC-05.
- Current sources: `engine-command`.
- Why Maven rather than package? moteur exécutable sans runtime/adapters; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-18

- Name: `engine-admit-command`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Admettre une Command authentifiée.
- Owner: Command.
- Verb: admit.
- Responsibilities: evidence, occurrence, request durable.
- Excluded responsibilities: HTTP, exécution async.
- Dependencies: TM-08, TM-17, TM-12.
- Dependents: TM-34, TM-40.
- Forbidden dependency protected: Web peut admettre sans importer le worker Pot.
- Supporting BC: BC-16.
- Current sources: `orchestrator-command-admission`.
- Why Maven rather than package? Web peut admettre sans importer le worker Pot; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-19

- Name: `engine-write-pot`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Appliquer une mutation Pot.
- Owner: Pot WRITE.
- Verb: write.
- Responsibilities: snapshot, version, règles de mutation.
- Excluded responsibilities: JPA, READ exact.
- Dependencies: TM-04, TM-05, TM-17, TM-12.
- Dependents: TM-40.
- Forbidden dependency protected: algorithme Pot sans SQL ni Web.
- Supporting BC: BC-09, BC-11, BC-16.
- Current sources: `engine-pot-command; engine-core`.
- Why Maven rather than package? algorithme Pot sans SQL ni Web; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-20

- Name: `engine-read-command-result`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Lire un Result opaque par E historique.
- Owner: Command Result.
- Verb: read.
- Responsibilities: modèle immutable, GET, ownership 0..1.
- Excluded responsibilities: terminal source, polling, CURRENT_BINDING.
- Dependencies: TM-03, TM-17.
- Dependents: TM-21, TM-34, TM-40.
- Forbidden dependency protected: GET sans moteur de matérialisation; arc Command transitoire BC-04.
- Supporting BC: BC-03, BC-04.
- Current sources: `engine-command-result`.
- Why Maven rather than package? GET sans moteur de matérialisation; arc Command transitoire BC-04; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-21

- Name: `engine-materialize-command-result`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Matérialiser un Result terminal.
- Owner: Command Result.
- Verb: materialize.
- Responsibilities: cohérence Event/outcome/Command, classification sémantique.
- Excluded responsibilities: GET HTTP, locator SQL.
- Dependencies: TM-20, TM-17, TM-04, TM-14.
- Dependents: TM-40, TM-48.
- Forbidden dependency protected: source terminale absente de la closure GET.
- Supporting BC: BC-03, BC-04, BC-05.
- Current sources: `engine-command-result`.
- Why Maven rather than package? source terminale absente de la closure GET; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-22

- Name: `engine-admit-registration`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Admettre une request.
- Owner: Registration.
- Verb: admit.
- Responsibilities: contrôle admission, request durable.
- Excluded responsibilities: arbitrage Binding, GET, HTTP.
- Dependencies: TM-09, TM-12.
- Dependents: TM-34, TM-40.
- Forbidden dependency protected: Web sans authority Binding/worker.
- Supporting BC: BC-01, BC-02.
- Current sources: `engine-registration`.
- Why Maven rather than package? Web sans authority Binding/worker; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-23

- Name: `engine-consume-registration`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Exécuter request Registration.
- Owner: Registration.
- Verb: consume.
- Responsibilities: arbitrage Binding, User, fact, outcome unique.
- Excluded responsibilities: Result GET, polling, SQL.
- Dependencies: TM-09, TM-11, TM-14, TM-12.
- Dependents: TM-38, TM-40.
- Forbidden dependency protected: loop primaire fenced sans closure GET.
- Supporting BC: BC-01, BC-02, BC-05.
- Current sources: `engine-registration`.
- Why Maven rather than package? loop primaire fenced sans closure GET; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-24

- Name: `engine-materialize-registration-result`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Matérialiser le Result ultérieur.
- Owner: Registration.
- Verb: materialize.
- Responsibilities: request/outcome consistency, owner E, immutable 0..1.
- Excluded responsibilities: exécution Binding, GET HTTP.
- Dependencies: TM-09, TM-14, TM-12.
- Dependents: TM-40, TM-50.
- Forbidden dependency protected: loop Outcome sans autorité Binding.
- Supporting BC: BC-01, BC-02, BC-05.
- Current sources: `engine-registration`.
- Why Maven rather than package? loop Outcome sans autorité Binding; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-25

- Name: `engine-read-registration-result`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Lire Result par E historique.
- Owner: Registration.
- Verb: read.
- Responsibilities: GET opaque même après detach/rebind.
- Excluded responsibilities: Binding courant, polling, PRIMARY mutation.
- Dependencies: TM-09.
- Dependents: TM-34, TM-40.
- Forbidden dependency protected: Web Result sans authority/exécution.
- Supporting BC: BC-01, BC-02.
- Current sources: `engine-registration`.
- Why Maven rather than package? Web Result sans authority/exécution; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-26

- Name: `engine-produce-projection-task`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Produire Task depuis Event metadata.
- Owner: Event/Task.
- Verb: produce.
- Responsibilities: EventType→ProjectionType, ensure Task.
- Excluded responsibilities: Event reload complet, LKV, projector.
- Dependencies: TM-02, TM-04, TM-07, TM-13.
- Dependents: TM-36, TM-40.
- Forbidden dependency protected: Event policy sans LKV ni runtime.
- Supporting BC: BC-05, BC-08, BC-09.
- Current sources: `engine-processing-event`.
- Why Maven rather than package? Event policy sans LKV ni runtime; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-27

- Name: `engine-consume-projection-task`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Traiter Task et publier projection @V.
- Owner: ProjectionTask.
- Verb: consume.
- Responsibilities: load→project→key check→validate→persist; orchestration Task spécialisée.
- Excluded responsibilities: projectors SQL, polling générique.
- Dependencies: TM-07, TM-13, TM-14, TM-15, TM-12.
- Dependents: TM-40, TM-46.
- Forbidden dependency protected: générique Consumption ne dépend pas de Task.
- Supporting BC: BC-07, BC-09, BC-10.
- Current sources: `engine-projection-task; orchestrator-consumption`.
- Why Maven rather than package? générique Consumption ne dépend pas de Task; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-28

- Name: `engine-read-projection`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Lire/revalider un artifact @V.
- Owner: Projection exacte.
- Verb: read.
- Responsibilities: query exacte, validation de résultat.
- Excluded responsibilities: Task scheduling, PRIMARY fallback.
- Dependencies: TM-07, TM-13.
- Dependents: TM-30.
- Forbidden dependency protected: GET exact indépendant de Task.
- Supporting BC: BC-09, BC-10.
- Current sources: `engine-projection-read`.
- Why Maven rather than package? GET exact indépendant de Task; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-29

- Name: `engine-project-pot`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Calculer les projections Pot pures.
- Owner: Pot projection.
- Verb: project.
- Responsibilities: AUTH, READ_POT, BALANCES, reconstruction historique.
- Excluded responsibilities: SQL, worker, GET HTTP.
- Dependencies: TM-04, TM-07, TM-13.
- Dependents: TM-46.
- Forbidden dependency protected: calcul pur sans SQL/Spring.
- Supporting BC: BC-08, BC-09, BC-10.
- Current sources: `domain-pot-projection; domain-projection-balance; engine-projection-pot; engine-projection-balance; engine-read-projection`.
- Why Maven rather than package? calcul pur sans SQL/Spring; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-30

- Name: `engine-read-pot`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Orchestrer query Pot exacte.
- Owner: Pot READ.
- Verb: read.
- Responsibilities: E→U via port TBD, AUTH@V puis READ_POT@V.
- Excluded responsibilities: HTTP, source E→U concrète, Task.
- Dependencies: TM-01, TM-03, TM-04, TM-05, TM-28.
- Dependents: TM-34, TM-40.
- Forbidden dependency protected: query non possédée par HTTP, source E→U remplaçable.
- Supporting BC: BC-09, BC-14.
- Current sources: `engine-pot-read; supra-http-read-query`.
- Why Maven rather than package? query non possédée par HTTP, source E→U remplaçable; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-31

- Name: `engine-materialize-current-binding`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Appliquer fact au current convergent.
- Owner: Binding.
- Verb: materialize.
- Responsibilities: révision, tombstone, divergence égale R.
- Excluded responsibilities: GET, authority mutation, polling.
- Dependencies: TM-03, TM-12, TM-14.
- Dependents: TM-37, TM-40, TM-42.
- Forbidden dependency protected: matérialisation directe distincte de GET et PRIMARY.
- Supporting BC: BC-08, BC-16.
- Current sources: `engine-read-projection`.
- Why Maven rather than package? matérialisation directe distincte de GET et PRIMARY; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-32

- Name: `engine-read-current-binding`
- Architectural type: `engine`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Lire CURRENT_BINDING self.
- Owner: Binding.
- Verb: read.
- Responsibilities: query E→U/B/R courante.
- Excluded responsibilities: Binding authority, projector Task.
- Dependencies: TM-03.
- Dependents: TM-34, TM-42.
- Forbidden dependency protected: GET sans port primaire ni loop.
- Supporting BC: BC-08, BC-16.
- Current sources: `engine-read-projection`.
- Why Maven rather than package? GET sans port primaire ni loop; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-33

- Name: `engine-advance-pot-watermark`
- Architectural type: `engine`
- Status: **TBD**
- Boundary strength: **TBD**
- Purpose: Avancer maximum observé.
- Owner: TBD-LKV.
- Verb: advance.
- Responsibilities: max-upsert, idempotence, classification LKV.
- Excluded responsibilities: readiness, current version, Binding current.
- Dependencies: TM-04, TM-06, TM-14.
- Dependents: TM-40, TM-42, TM-51.
- Forbidden dependency protected: enveloppe isolante provisoire, POM/owner final non décidés.
- Supporting BC: BC-08, BC-15.
- Current sources: `engine-processing-event; engine-read-projection`.
- Why Maven rather than package? enveloppe isolante provisoire, POM/owner final non décidés; score tbd à revérifier pour toute frontière non forte.

### TARGET MODULE TM-34

- Name: `supra-http-api`
- Architectural type: `supra`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Adapter HTTP/JWT aux entrées applicatives.
- Owner: Web protocol.
- Concrete IO adapted: adapter http/jwt aux entrées applicatives.
- Responsibilities: DTO, mapping, errors, principal extraction.
- Excluded responsibilities: orchestration E→U, SQL, policy métier.
- Dependencies: TM-08, TM-18, TM-22, TM-25, TM-30, TM-32, TM-20.
- Dependents: TM-43.
- Forbidden dependency protected: HTTP n’entre pas dans les engines; un seul POM évite cinq POM administratifs.
- Supporting BC: BC-05, BC-14.
- Current sources: `supra-http-write-command; supra-http-read-query; supra-authentication-spring-security`.
- Why Maven rather than package? HTTP n’entre pas dans les engines; un seul POM évite cinq POM administratifs; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-35

- Name: `supra-consume-command`
- Architectural type: `supra`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Adapter candidate Command à l’exécution.
- Owner: Command integration.
- Concrete IO adapted: adapter candidate command à l’exécution.
- Responsibilities: discovery→reload via ports, issue→Consumption.
- Excluded responsibilities: SQL, fence générique, backoff.
- Dependencies: TM-15, TM-17.
- Dependents: TM-44.
- Forbidden dependency protected: glue réutilisable/testable sans runtime Spring.
- Supporting BC: BC-05, BC-06.
- Current sources: `locator-consumption-command`.
- Why Maven rather than package? glue réutilisable/testable sans runtime Spring; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-36

- Name: `supra-consume-event`
- Architectural type: `supra`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Adapter candidate Event metadata à Task.
- Owner: Event integration.
- Concrete IO adapted: adapter candidate event metadata à task.
- Responsibilities: metadata discovery, ensure, issue mapping.
- Excluded responsibilities: EventLoader artificiel, SQL, polling.
- Dependencies: TM-15, TM-26.
- Dependents: TM-45.
- Forbidden dependency protected: garde metadata-only hors runtime et hors protocole générique.
- Supporting BC: BC-05, BC-07.
- Current sources: `locator-consumption-event`.
- Why Maven rather than package? garde metadata-only hors runtime et hors protocole générique; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-37

- Name: `supra-consume-binding`
- Architectural type: `supra`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Adapter fact candidate à current.
- Owner: Binding integration.
- Concrete IO adapted: adapter fact candidate à current.
- Responsibilities: discovery→fact reload par eventId, issue mapping.
- Excluded responsibilities: SQL, R rule, polling.
- Dependencies: TM-15, TM-31.
- Dependents: TM-47.
- Forbidden dependency protected: glue fact/Consumption hors runtime et moteur pur.
- Supporting BC: BC-05, BC-08.
- Current sources: `locator-consumption-binding`.
- Why Maven rather than package? glue fact/Consumption hors runtime et moteur pur; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-38

- Name: `supra-consume-registration`
- Architectural type: `supra`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Adapter les deux consumables Registration.
- Owner: Registration integration.
- Concrete IO adapted: adapter les deux consumables registration.
- Responsibilities: request reload, issue mapping for Request slot.
- Excluded responsibilities: SQL, arbitrage, backoff.
- Dependencies: TM-15, TM-23.
- Dependents: TM-49.
- Forbidden dependency protected: glue Request hors runtime; Result glue reste package local pour éviter closure Binding.
- Supporting BC: BC-01, BC-02, BC-05.
- Current sources: `runtime-registration-consumption-worker; runtime-registration-result-consumption-worker`.
- Why Maven rather than package? glue Request hors runtime; Result glue reste package local pour éviter closure Binding; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-39

- Name: `infra-tx-spring`
- Architectural type: `infra`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Implémenter TransactionRunner.
- Owner: Transaction adapter.
- Responsibilities: Spring transaction manager.
- Excluded responsibilities: boundary métier.
- Dependencies: TM-12.
- Dependents: TM-43, TM-44, TM-45, TM-46, TM-47, TM-48, TM-49, TM-50, TM-51.
- Forbidden dependency protected: engines indépendants de Spring.
- Supporting BC: BC-11, BC-12.
- Current sources: `infra-tx-spring`.
- Why Maven rather than package? engines indépendants de Spring; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-40

- Name: `infra-persistence-jpa`
- Architectural type: `infra`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Implémenter ports SQL primaire et discovery.
- Owner: Primary/Consumption SQL.
- Responsibilities: clusters Command/Registration/Binding/Result/Task/Consumption.
- Excluded responsibilities: business policy, READ current store.
- Dependencies: TM-10, TM-11, TM-12, TM-13, TM-14, TM-17, TM-18, TM-19, TM-20, TM-21, TM-22, TM-23, TM-24, TM-25, TM-26, TM-27, TM-30, TM-31, TM-33, TM-09.
- Dependents: TM-43, TM-44, TM-45, TM-46, TM-47, TM-48, TM-49, TM-50, TM-51.
- Forbidden dependency protected: frontière application→JPA; split interne non prouvé.
- Supporting BC: BC-12, BC-13.
- Current sources: `infra-persistence-jpa`.
- Why Maven rather than package? frontière application→JPA; split interne non prouvé; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-41

- Name: `infra-projection-adapters`
- Architectural type: `infra`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Implémenter store exact et schema validation.
- Owner: Projection adapter.
- Responsibilities: root/artifact/failure, JSON Schema.
- Excluded responsibilities: projector, Task orchestration.
- Dependencies: TM-07, TM-13.
- Dependents: TM-43, TM-46.
- Forbidden dependency protected: calcul pur sans SQL/Networknt.
- Supporting BC: BC-09, BC-10, BC-12.
- Current sources: `infra-projection-persistence; infra-projection-json-schema`.
- Why Maven rather than package? calcul pur sans SQL/Networknt; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-42

- Name: `infra-read-persistence`
- Architectural type: `infra`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Implémenter current et LKV stores.
- Owner: Derived READ adapter.
- Responsibilities: upsert/query current, max-upsert LKV provisoire.
- Excluded responsibilities: Binding authority, exact store, semantics.
- Dependencies: TM-31, TM-32, TM-33.
- Dependents: TM-43, TM-46, TM-47, TM-51.
- Forbidden dependency protected: ports READ sans JDBC; colocation Current/LKV provisoire.
- Supporting BC: BC-08, BC-12, BC-13, BC-15.
- Current sources: `infra-read-persistence`.
- Why Maven rather than package? ports READ sans JDBC; colocation Current/LKV provisoire; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-43

- Name: `runtime-web-api`
- Architectural type: `runtime`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Composer le processus HTTP.
- Owner: Web deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring Spring, activation, choix adapters.
- Excluded responsibilities: DTO, policy, E→U orchestration.
- Dependencies: TM-10, TM-34, TM-39, TM-40, TM-41, TM-42.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: racine HTTP exécutable distincte des workers.
- Supporting BC: BC-14, BC-16.
- Current sources: `runtime-web-api`.
- Why Maven rather than package? racine HTTP exécutable distincte des workers; score strong à revérifier pour toute frontière non forte.

### TARGET MODULE TM-44

- Name: `runtime-command-consumption-worker`
- Architectural type: `runtime`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Composer la loop Command.
- Owner: Command deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring, backoff, segments, activation.
- Excluded responsibilities: locator semantics, SQL, fence métier.
- Dependencies: TM-16, TM-35, TM-39, TM-40.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: exécutable actuel autonome; déploiement futur à confirmer.
- Supporting BC: BC-05, BC-06, BC-16.
- Current sources: `runtime-command-consumption-worker; binding-pot-command-spring`.
- Why Maven rather than package? exécutable actuel autonome; déploiement futur à confirmer; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-45

- Name: `runtime-event-consumption-worker`
- Architectural type: `runtime`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Composer la loop Event→Task.
- Owner: Event deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring, activation, cadence.
- Excluded responsibilities: policy Event→Task, SQL.
- Dependencies: TM-16, TM-36, TM-39, TM-40.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: exécutable actuel, loop indépendante.
- Supporting BC: BC-05, BC-09, BC-16.
- Current sources: `runtime-event-consumption-worker`.
- Why Maven rather than package? exécutable actuel, loop indépendante; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-46

- Name: `runtime-task-consumption-worker`
- Architectural type: `runtime`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Composer la loop Task.
- Owner: Task deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: catalogue actif, transaction wiring, polling.
- Excluded responsibilities: projector ou validation métier.
- Dependencies: TM-16, TM-27, TM-29, TM-39, TM-40, TM-41, TM-42.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: exécutable actuel pour préparation longue.
- Supporting BC: BC-07, BC-09, BC-16.
- Current sources: `runtime-task-consumption-worker`.
- Why Maven rather than package? exécutable actuel pour préparation longue; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-47

- Name: `runtime-binding-consumption-worker`
- Architectural type: `runtime`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Composer la loop fact→current.
- Owner: Binding deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring, polling, cadence.
- Excluded responsibilities: R rule, SQL.
- Dependencies: TM-16, TM-37, TM-39, TM-40, TM-42.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: exécutable actuel, loop fact indépendante.
- Supporting BC: BC-06, BC-08, BC-16.
- Current sources: `runtime-binding-consumption-worker`.
- Why Maven rather than package? exécutable actuel, loop fact indépendante; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-48

- Name: `runtime-command-result-consumption-worker`
- Architectural type: `runtime`
- Status: **TBD**
- Boundary strength: **TBD**
- Purpose: Composer la loop terminal Result.
- Owner: Command Result deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring, polling, cadence.
- Excluded responsibilities: terminal consistency, locator policy.
- Dependencies: TM-16, TM-21, TM-39, TM-40.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: loop indépendante prouvée, besoin POM/processus autonome non prouvé.
- Supporting BC: BC-02, BC-03, BC-05.
- Current sources: `runtime-command-result-consumption-worker`.
- Why Maven rather than package? loop indépendante prouvée, besoin POM/processus autonome non prouvé; score tbd à revérifier pour toute frontière non forte.

### TARGET MODULE TM-49

- Name: `runtime-registration-consumption-worker`
- Architectural type: `runtime`
- Status: **PROPOSED**
- Boundary strength: **MEDIUM**
- Purpose: Composer la loop Request.
- Owner: Registration deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring, polling, cadence.
- Excluded responsibilities: Binding arbitration, SQL.
- Dependencies: TM-16, TM-38, TM-39, TM-40.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: exécutable actuel, loop Request indépendante.
- Supporting BC: BC-01, BC-02, BC-05.
- Current sources: `runtime-registration-consumption-worker`.
- Why Maven rather than package? exécutable actuel, loop Request indépendante; score medium à revérifier pour toute frontière non forte.

### TARGET MODULE TM-50

- Name: `runtime-registration-result-consumption-worker`
- Architectural type: `runtime`
- Status: **TBD**
- Boundary strength: **TBD**
- Purpose: Composer la loop Outcome→Result.
- Owner: Registration Result deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring, polling, cadence.
- Excluded responsibilities: owner E, Result semantics.
- Dependencies: TM-16, TM-24, TM-39, TM-40.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: loop indépendante prouvée, besoin POM/processus autonome non prouvé.
- Supporting BC: BC-01, BC-02, BC-05.
- Current sources: `runtime-registration-result-consumption-worker`.
- Why Maven rather than package? loop indépendante prouvée, besoin POM/processus autonome non prouvé; score tbd à revérifier pour toute frontière non forte.

### TARGET MODULE TM-51

- Name: `runtime-latest-known-version-consumption-worker`
- Architectural type: `runtime`
- Status: **TBD**
- Boundary strength: **TBD**
- Purpose: Composer la loop LKV.
- Owner: TBD-LKV deployment.
- Deployment-only: Spring wiring, activation, cadence, telemetry and implementation choice.
- Responsibilities: wiring, polling, cadence, metrics.
- Excluded responsibilities: max semantics, readiness.
- Dependencies: TM-16, TM-33, TM-39, TM-40, TM-42, TM-10.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: POM/processus final dépend du besoin aval LKV.
- Supporting BC: BC-08, BC-15, BC-16.
- Current sources: `runtime-latest-known-version-consumption-worker`.
- Why Maven rather than package? POM/processus final dépend du besoin aval LKV; score tbd à revérifier pour toute frontière non forte.

### TARGET MODULE TM-52

- Name: `architecture-tests`
- Architectural type: `verification`
- Status: **PROPOSED**
- Boundary strength: **STRONG**
- Purpose: Exécuter gate structurel global.
- Owner: Verification.
- Responsibilities: architecture tests hors prod.
- Excluded responsibilities: code de production.
- Dependencies: aucune interne.
- Dependents: aucun module de production cible.
- Forbidden dependency protected: gate Maven séparé des slices locales.
- Supporting BC: BC-16.
- Current sources: `architecture-tests`.
- Why Maven rather than package? gate Maven séparé des slices locales; score strong à revérifier pour toute frontière non forte.

## L. Scores C.1 avant validation physique C.2

Comptage provisoire C.1, remplacé pour Maven par la section N : **52 emplacements**, dont **48 PROPOSED + 4 TBD** ; strengths **30 STRONG, 18 MEDIUM, 4 TBD**, zéro WEAK. Reactor CURRENT : 51 modules, neuf runtimes. Cible conditionnelle : six runtimes PROPOSED et trois runtimes TBD (les deux Results et LKV) ; les neuf loops restent indépendantes. Le compte peut diminuer si des MEDIUM sont ramenés à des packages, ou varier si les TBD sont résolus autrement. Il n’est pas un objectif.

Recalibrages notables : `port-transaction` STRONG→MEDIUM ; `domain-event`, `orchestrator-consumption`, `engine-produce-projection-task`, `infra-read-persistence`, `contracts-observability` restent MEDIUM ; admission Command est reclassée d’orchestrator MEDIUM en engine MEDIUM ; les quatre supra Consumption physiques sont MEDIUM ; les workers Command/Event/Task/Binding/Registration passent STRONG→MEDIUM car loop≠process ; les deux Result runtimes passent STRONG→TBD ; LKV work/runtime MEDIUM provisoire→TBD. Le POM HTTP supra est MEDIUM, Web runtime reste STRONG.

Zones non décidées : (1) **TBD-LKV** : owner, besoin aval, POM/processus final ; (2) **TBD-E2U** : owner/source et garanties PRIMARY→READ ; (3) **TBD-COMMAND-CONTRACT** : éventuel contrat Command neutre, sans `contracts-command` proposé ; (4) valeur finale des POM MEDIUM après mesure des imports et composition de process. La cible ferme n’utilise aucun choix implicite sur les trois premiers.

## M. Contraintes de migration et validation conceptuelle

Avant un plan détaillé : stabiliser valeurs/ports avant dissolution d’`engine-core` ; séparer policy sémantique, supra glue, SQL et backoff avant simplification des runtimes ; sortir la spécialisation Task du générique avant inversion de son arc ; conserver le scénario GET Pot→PRIMARY compatible derrière le port E→U ; déplacer les adapters par cluster sans casser l’atomicité effet durable+provenance+CAS ; conserver les neuf slots et chaque exécutable courant pendant les étapes intermédiaires. La rationalisation pure Pot/JSON/HTTP peut être instruite indépendamment, sous réserve de ses imports réels.

La cible préserve conceptuellement : Command E+B et fencing ; Registration sans User orphelin et outcome unique ; Results 0..1 immuables, owner E historique, indépendants du Binding courant ; Current Binding revision/tombstone/divergence et absence de délai borné ; Consumption claim séparé, retry et late-commit protection ; exact @V sans fallback ; projection input→projector pur→key→validator de sortie→persistence ; aucun Results/Current→ProjectionTask. Ces propriétés dépendent ensuite de la composition transactionnelle et des preuves CURRENT ; la topologie seule ne les exécute pas.

Vérification C.1 : audit documentaire/statique, aucun slice Maven déclaré, aucune commande Maven ni gate global ni reactor complet. La matrice H a été vérifiée sans cycle ; le mapping couvre les 51 modules CURRENT et les fiches reprennent exactement les arcs H. Aucun code, POM, SQL ou runtime n’est changé.

## N. Physical Boundary Validation (C.2)

**Cette section gouverne la forme Maven cible.** Les 52 fiches K restent la topologie **logique** C.1 (52 responsabilités nommées) ; leurs scores et arguments de POM sont des hypothèses antérieures lorsqu’une ligne ci-dessous conclut `PACKAGE_ONLY` ou `TBD_PHYSICAL`. `KEEP_POM` signifie que Maven protège une direction ou une racine concrète, pas que la séparation doit durer éternellement. `PACKAGE_ONLY` garde le rôle et un package explicite. `TBD_PHYSICAL` ne préjuge ni le besoin fonctionnel LKV, ni le nombre final de processus. La mesure ci-dessous porte sur les **arcs TARGET H**, pas sur le `dependency:tree` Maven CURRENT ni sur la taille en classes.

### N.1 Baseline, méthode et limites de preuve

Pour C.2 : `git fetch origin` réussi ; HEAD local et `origin/v2-make-it-pull` = `65137b7eec2dd278350ff83b284d3a5552cbbfc1`, divergence `0/0`, `git status --short` vide. Aucun commit depuis `65137b7e` à l’ouverture. Trois arcs C.1 ont été complétés sur imports CURRENT : TM-17→02 ([EventAppendPort](../../../../app/engine-command/src/main/java/com/kartaguez/pocoma/engine/command/port/out/EventAppendPort.java), [CommandTerminalEventTypes](../../../../app/engine-command/src/main/java/com/kartaguez/pocoma/engine/command/model/CommandTerminalEventTypes.java)), TM-40→10 ([adapter JPA](../../../../app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/command/JdbcCommandOutcomeAdapter.java) utilisant `TraceContextHolder`), TM-43→10 ([TraceCorrelationFilter](../../../../app/runtime-web-api/src/main/java/com/kartaguez/pocoma/TraceCorrelationFilter.java)). Ils corrigent une omission d’inventaire, sans changer le rôle de ces composants ni les BC.

La closure transitive est l’ensemble des TM accessibles par parcours des arcs H. Le fan-in direct compte les consommateurs immédiats ; le fan-in transitif compte tous les modules dont la closure contient le TM. Une faible valeur ne condamne pas un POM : un port peut protéger une direction cruciale. Inversement, beaucoup de consommateurs ne prouvent pas un POM. `infra-persistence-jpa` a 20 imports directs et entraîne une closure de 30 TM ; tout runtime qui l’importe reçoit cette closure. Un split d’engine protège souvent son **API directe** mais pas encore la closure complète du runtime.

### N.2 Matrice de décision physique complète

`Direct deps`/`Transitive deps` comptent les nœuds TM logiques avant contraction. `Direct consumers` est un fan-in direct ; les preuves et parents sont détaillés en N.3/N.4. Le statut C.1 est repris tel quel, sans le confondre avec la décision physique.

| TM | Role | Current C.1 status | Direct deps | Transitive deps | Direct consumers | Protection Maven / parent naturel | Decision |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TM-01 `domain-authorization` | domain | PROPOSED/STRONG | 0 | 0 | 2 | dans Pot/Command : Authorization→moteur ou SQL | KEEP_POM |
| TM-02 `domain-event` | domain | PROPOSED/MEDIUM | 0 | 0 | 3 | dans domain-pot : Command→Pot pour BusinessEvent/EventType | KEEP_POM |
| TM-03 `domain-user-identity` | domain | PROPOSED/STRONG | 0 | 0 | 8 | dans authority Binding : READ→ports de mutation PRIMARY | KEEP_POM |
| TM-04 `domain-pot` | domain | PROPOSED/STRONG | 1 | 1 | 7 | dans Pot engine : projection/event→mutation Pot | KEEP_POM |
| TM-05 `domain-pot-policy` | domain | PROPOSED/STRONG | 2 | 3 | 2 | package dans TM-04 ; guard N.4 | PACKAGE_ONLY |
| TM-06 `domain-consumption` | domain | PROPOSED/STRONG | 0 | 0 | 3 | dans engine Consumption : capacités→transactions/engine | KEEP_POM |
| TM-07 `domain-projection` | domain | PROPOSED/STRONG | 0 | 0 | 6 | dans Task : GET exact→worker/Task | KEEP_POM |
| TM-08 `contracts-authentication` | contracts | PROPOSED/MEDIUM | 1 | 1 | 2 | package dans TM-18 ; guard N.4 | PACKAGE_ONLY |
| TM-09 `contracts-registration` | contracts | PROPOSED/STRONG | 1 | 1 | 5 | dans admit/consume Registration : un autre processus→ce moteur | KEEP_POM |
| TM-10 `contracts-observability` | contracts | PROPOSED/MEDIUM | 0 | 0 | 3 | dans Web : JPA→runtime, ou dans JPA : Web→JPA | KEEP_POM |
| TM-11 `port-binding-authority` | port | PROPOSED/STRONG | 1 | 1 | 3 | dans domain-user-identity : READ→authority PRIMARY | KEEP_POM |
| TM-12 `port-transaction` | port | PROPOSED/MEDIUM | 0 | 0 | 10 | dans Consumption : Registration/Pot→Consumption sans motif | KEEP_POM |
| TM-13 `port-projection` | port | PROPOSED/STRONG | 1 | 1 | 6 | dans Task : exact GET/projector→worker | KEEP_POM |
| TM-14 `engine-consumption` | engine | PROPOSED/STRONG | 2 | 2 | 9 | dans capability : autre capability→capacité hôte | KEEP_POM |
| TM-15 `orchestrator-consumption` | orchestrator | PROPOSED/MEDIUM | 1 | 3 | 6 | package dans TM-14 ; guard N.4 | PACKAGE_ONLY |
| TM-16 `supra-poll-consumption` | supra | PROPOSED/STRONG | 2 | 4 | 8 | dans runtime worker : sept runtimes→runtime pair | KEEP_POM |
| TM-17 `engine-consume-command` | engine | PROPOSED/STRONG | 4 | 6 | 6 | dans runtime Command : Result/Pot→process worker | KEEP_POM |
| TM-18 `engine-admit-command` | engine | PROPOSED/MEDIUM | 3 | 8 | 2 | dans consume Command : Web→worker/Binding/Consumption | KEEP_POM |
| TM-19 `engine-write-pot` | engine | PROPOSED/STRONG | 4 | 10 | 1 | dans Command générique : Command→règles Pot | KEEP_POM |
| TM-20 `engine-read-command-result` | engine | PROPOSED/STRONG | 2 | 7 | 3 | dans materializer : Web GET→terminal source | KEEP_POM |
| TM-21 `engine-materialize-command-result` | engine | PROPOSED/STRONG | 4 | 9 | 2 | dans read Result : HTTP GET→terminal source/Consumption | KEEP_POM |
| TM-22 `engine-admit-registration` | engine | PROPOSED/STRONG | 2 | 3 | 2 | dans consume Registration : Web→Binding authority | KEEP_POM |
| TM-23 `engine-consume-registration` | engine | PROPOSED/STRONG | 4 | 6 | 2 | dans admit Registration : Web→Binding authority | KEEP_POM |
| TM-24 `engine-materialize-registration-result` | engine | PROPOSED/STRONG | 3 | 5 | 2 | dans consume Registration : Result worker→Binding authority | KEEP_POM |
| TM-25 `engine-read-registration-result` | engine | PROPOSED/STRONG | 1 | 2 | 2 | package dans TM-22 ; guard N.4 | PACKAGE_ONLY |
| TM-26 `engine-produce-projection-task` | engine | PROPOSED/MEDIUM | 4 | 4 | 2 | dans Task engine : Event worker→Task processing | KEEP_POM |
| TM-27 `engine-consume-projection-task` | engine | PROPOSED/STRONG | 5 | 6 | 2 | dans generic Consumption : protocole→Task | KEEP_POM |
| TM-28 `engine-read-projection` | engine | PROPOSED/STRONG | 2 | 2 | 1 | dans Task : exact GET→Task worker | KEEP_POM |
| TM-29 `engine-project-pot` | engine | PROPOSED/STRONG | 3 | 4 | 1 | dans Task runtime : projector→Spring/SQL | KEEP_POM |
| TM-30 `engine-read-pot` | engine | PROPOSED/STRONG | 5 | 8 | 2 | dans HTTP supra : query→Web/PRIMARY concret | KEEP_POM |
| TM-31 `engine-materialize-current-binding` | engine | PROPOSED/STRONG | 3 | 4 | 3 | dans Binding authority : current→WRITE | KEEP_POM |
| TM-32 `engine-read-current-binding` | engine | PROPOSED/STRONG | 1 | 1 | 2 | dans materialize Current : GET→Consumption/transaction | KEEP_POM |
| TM-33 `engine-advance-pot-watermark` | engine | TBD/TBD | 3 | 5 | 3 | forme process/POM ou owner non établie | TBD_PHYSICAL |
| TM-34 `supra-http-api` | supra | PROPOSED/MEDIUM | 7 | 21 | 1 | dans runtime Web : HTTP/JWT supra→JPA PRIMARY concret | KEEP_POM |
| TM-35 `supra-consume-command` | supra | PROPOSED/MEDIUM | 2 | 8 | 1 | package dans TM-44 ; guard N.4 | PACKAGE_ONLY |
| TM-36 `supra-consume-event` | supra | PROPOSED/MEDIUM | 2 | 9 | 1 | package dans TM-45 ; guard N.4 | PACKAGE_ONLY |
| TM-37 `supra-consume-binding` | supra | PROPOSED/MEDIUM | 2 | 6 | 1 | package dans TM-47 ; guard N.4 | PACKAGE_ONLY |
| TM-38 `supra-consume-registration` | supra | PROPOSED/MEDIUM | 2 | 8 | 1 | package dans TM-49 ; guard N.4 | PACKAGE_ONLY |
| TM-39 `infra-tx-spring` | infra | PROPOSED/STRONG | 1 | 1 | 9 | dans engine : application→Spring transactions | KEEP_POM |
| TM-40 `infra-persistence-jpa` | infra | PROPOSED/STRONG | 20 | 30 | 9 | dans engine : engine→JPA ; dans runtime : runtimes pairs→runtime | KEEP_POM |
| TM-41 `infra-projection-adapters` | infra | PROPOSED/STRONG | 2 | 2 | 2 | dans Task runtime : Web→runtime Task ; dans projector : calcul→SQL | KEEP_POM |
| TM-42 `infra-read-persistence` | infra | PROPOSED/MEDIUM | 3 | 9 | 4 | dans Binding read : query→JDBC | KEEP_POM |
| TM-43 `runtime-web-api` | runtime | PROPOSED/STRONG | 6 | 36 | 0 | dans worker runtime : HTTP→worker lifecycle | KEEP_POM |
| TM-44 `runtime-command-consumption-worker` | runtime | PROPOSED/MEDIUM | 4 | 34 | 0 | dans autre runtime : runtime→runtime | KEEP_POM |
| TM-45 `runtime-event-consumption-worker` | runtime | PROPOSED/MEDIUM | 4 | 34 | 0 | dans autre runtime : runtime→runtime | KEEP_POM |
| TM-46 `runtime-task-consumption-worker` | runtime | PROPOSED/MEDIUM | 7 | 37 | 0 | dans autre runtime : runtime→runtime | KEEP_POM |
| TM-47 `runtime-binding-consumption-worker` | runtime | PROPOSED/MEDIUM | 5 | 36 | 0 | dans autre runtime : runtime→runtime | KEEP_POM |
| TM-48 `runtime-command-result-consumption-worker` | runtime | TBD/TBD | 4 | 33 | 0 | forme process/POM ou owner non établie | TBD_PHYSICAL |
| TM-49 `runtime-registration-consumption-worker` | runtime | PROPOSED/MEDIUM | 4 | 34 | 0 | dans autre runtime : runtime→runtime | KEEP_POM |
| TM-50 `runtime-registration-result-consumption-worker` | runtime | TBD/TBD | 4 | 33 | 0 | forme process/POM ou owner non établie | TBD_PHYSICAL |
| TM-51 `runtime-latest-known-version-consumption-worker` | runtime | TBD/TBD | 6 | 35 | 0 | forme process/POM ou owner non établie | TBD_PHYSICAL |
| TM-52 `architecture-tests` | verification | PROPOSED/STRONG | 0 | 0 | 0 | dans production : slices locales→gate global | KEEP_POM |

### N.3 KEEP_POM : import interdit réellement protégé

Le tableau rend explicites les quatre tests demandés. « Si fusion » désigne le parent naturel ou le déplacement naïf qui ferait entrer l’import ; ce n’est pas une opération à implémenter. Le dernier champ indique pourquoi une simple convention de package ne suffit pas pour cette frontière. Pour les runtimes workers, `KEEP_POM` protège surtout une **racine Spring exécutable actuelle** ; l’autonomie de déploiement future n’est pas démontrée.

| TM | Si fusion : import devenu possible | Danger | Consommateurs bénéficiaires | Pourquoi package/test seul insuffisant |
| --- | --- | --- | --- | --- |
| TM-01 | dans Pot/Command : Authorization→moteur ou SQL | calcul d’accès devient couplé au provider | Pot WRITE/READ et Command | frontière pure partagée à trois capacités |
| TM-02 | dans domain-pot : Command→Pot pour BusinessEvent/EventType | Command générique importe Pot seulement pour Event | Command, Event discovery, SQL | interdiction transitive de capacité visible par Maven |
| TM-03 | dans authority Binding : READ→ports de mutation PRIMARY | GET Current peut invoquer authority | Registration, Command, READ Binding | Maven empêche l’import de l’API WRITE |
| TM-04 | dans Pot engine : projection/event→mutation Pot | modèle partage une closure WRITE | Command, projectors, Event | pureté du modèle garantie hors moteurs |
| TM-06 | dans engine Consumption : capacités→transactions/engine | modèle claim/lease perd sa pureté | tous consumers et adapters | partage transverse sans engine/runtime |
| TM-07 | dans Task : GET exact→worker/Task | identity @V et validator dépendent de Task | producer, query, adapter | Maven protège le contrat exact multi-process |
| TM-09 | dans admit/consume Registration : un autre processus→ce moteur | Web/Result worker importent authority ou Consumption | Web, deux workers, persistence | contrat durable partagé sans moteur propriétaire |
| TM-10 | dans Web : JPA→runtime, ou dans JPA : Web→JPA | trace traverse deux couches sans dépendance inverse | Web et persistence SQL | ThreadLocal/trace sont partagés entre modules non parent-enfant |
| TM-11 | dans domain-user-identity : READ→authority PRIMARY | Current Binding query voit acquire/detach | Command, Registration, Binding READ | séparation physique valeur vs ports WRITE |
| TM-12 | dans Consumption : Registration/Pot→Consumption sans motif | TransactionRunner partagé créerait faux owner | plusieurs engines et Spring adapter | aucun parent neutre existant ; petit POM légitime |
| TM-13 | dans Task : exact GET/projector→worker | ports exacts couplés au polling | GET, Task, producer, adapters | Maven bloque Task closure côté query |
| TM-14 | dans capability : autre capability→capacité hôte | protocole transverse perd neutralité | huit loops et adapters | module sans business imports vérifiable |
| TM-16 | dans runtime worker : sept runtimes→runtime pair | runtime→runtime et Spring wiring croisé | huit runtimes | shared polling a plusieurs racines réelles |
| TM-17 | dans runtime Command : Result/Pot→process worker | Command semantics prennent Spring/process | Command, Result, Pot, SQL | application réutilisée hors runtime |
| TM-18 | dans consume Command : Web→worker/Binding/Consumption | admission synchrone prend closure worker | Web et SQL admission | Maven coupe la direction Web→execution |
| TM-19 | dans Command générique : Command→règles Pot | dispatch générique devient Pot-specific | Command et persistence | séparation capacité générique vs mutation Pot |
| TM-20 | dans materializer : Web GET→terminal source | GET importe Event/outcome/policy de matérialisation | supra HTTP, SQL Result | barrière directe réelle malgré closure Web SQL encore large |
| TM-21 | dans read Result : HTTP GET→terminal source/Consumption | lecture importe work Result | Result worker, persistence | barrière application directe ; closure runtime demeure à réduire |
| TM-22 | dans consume Registration : Web→Binding authority | admission importe arbitrage et Consumption | Web, SQL admission | Maven évite Binding dans module d’entrée |
| TM-23 | dans admit Registration : Web→Binding authority | Web importe traitement fenced | worker Request, SQL | Maven isole authority/transaction complexe |
| TM-24 | dans consume Registration : Result worker→Binding authority | Result terminal importe mutation primaire | worker Outcome, SQL | boucles indépendantes et imports différents |
| TM-26 | dans Task engine : Event worker→Task processing | producer metadata tire projector/worker Task | Event supra et SQL discovery | aucun autre parent ne garde metadata-only et ports SQL |
| TM-27 | dans generic Consumption : protocole→Task | générique importe capability | Task worker, projection adapter | BC-07 impose direction à la frontière |
| TM-28 | dans Task : exact GET→Task worker | READ exact importe orchestration Task | Pot read et Web | Maven garde query sans Task direct |
| TM-29 | dans Task runtime : projector→Spring/SQL | calcul pur pourrait lire DB | Task et source loaders | moteur pur partagé hors runtime |
| TM-30 | dans HTTP supra : query→Web/PRIMARY concret | orchestration E→U revient au contrôleur | Web et exact read | port source E→U reste testable sans HTTP |
| TM-31 | dans Binding authority : current→WRITE | materializer reçoit API mutation primaire | Binding worker, READ adapter | port READ/current indépendant des ports authority |
| TM-32 | dans materialize Current : GET→Consumption/transaction | query self importe worker et authority | Web, READ adapter | Maven protège READ de la production |
| TM-34 | dans runtime Web : HTTP/JWT supra→JPA PRIMARY concret | régression E→U direct controller | Web routes et admission/query | Maven exclut SQL du compile classpath du supra |
| TM-39 | dans engine : application→Spring transactions | contrat transaction devient Spring | neuf runtimes | provider interchangeable hors engines |
| TM-40 | dans engine : engine→JPA ; dans runtime : runtimes pairs→runtime | adapter SQL devient dépendance applicative ou croise les processus | neuf runtimes | Maven sépare application et provider; split interne non prouvé |
| TM-41 | dans Task runtime : Web→runtime Task ; dans projector : calcul→SQL | pureté calcul/validation ou racines séparées perdues | Web et Task | deux consommateurs, adapter interchangeable |
| TM-42 | dans Binding read : query→JDBC | READ current et LKV collés au store | Web, Binding, Task, LKV | provider partagé par quatre processus |
| TM-43 | dans worker runtime : HTTP→worker lifecycle | process synchrone et worker couplés | déploiement Web | racine exécutable indépendante |
| TM-44 | dans autre runtime : runtime→runtime | composition Command propagée | process Command actuel | racine Spring exécutable actuelle |
| TM-45 | dans autre runtime : runtime→runtime | composition Event propagée | process Event actuel | racine Spring exécutable actuelle |
| TM-46 | dans autre runtime : runtime→runtime | composition Task propagée | process Task actuel | racine Spring exécutable actuelle |
| TM-47 | dans autre runtime : runtime→runtime | composition Binding propagée | process Binding actuel | racine Spring exécutable actuelle |
| TM-49 | dans autre runtime : runtime→runtime | composition Request propagée | process Registration actuel | racine Spring exécutable actuelle |
| TM-52 | dans production : slices locales→gate global | preuve globale lancée par défaut | validation architecturale | sélection Maven explicite du gate |

Points de prudence : les deux moteurs Command Result TM-20/21 protègent le GET contre un **import applicatif direct** du materializer, mais `TM-20→TM-17` (BC-04) et `TM-43→TM-40` maintiennent une closure Web large. Cette limite interdit de prétendre que le runtime Web n’embarque plus Command/terminal tant que BC-04 et le cluster SQL n’ont pas été traités. Les trois moteurs Registration TM-22/23/24 gardent des imports réellement différents ; le GET TM-25 est requalifié package N.4. `contracts-registration` TM-09 évite que Web, worker Request, worker Outcome et SQL importent un moteur Registration juste pour request/outcome/Result. `port-transaction` TM-12 reste un petit POM : aucun parent naturel neutre ne le reçoit sans faire dépendre Registration/Pot/Command de Consumption ou de Spring. `contracts-authentication` TM-08, au contraire, est consommé seulement par admission Command et supra HTTP, qui importe déjà cette admission.

`contracts-observability` TM-10 est conservé malgré son apparence de micro-POM : CURRENT prouve `TraceCorrelationFilter` côté Web et plusieurs adapters JPA côté SQL sur `TraceContextHolder`. Son parent Web ferait importer un runtime par JPA ; son parent JPA ferait importer le provider SQL par Web. C.1 avait omis ces deux arcs TARGET ; les ignorer aurait produit un faux `PACKAGE_ONLY`. `domain-event` TM-02 reste distinct car Command utilise `BusinessEvent`/`EventType` génériques sans devenir Pot-specific ; l’insérer dans `domain-pot` introduirait cet import métier.

### N.4 PACKAGE_ONLY : rôle préservé et guard

Chaque rôle reste dans un package dédié au sein du POM indiqué. **La responsabilité reste `supra` lorsqu’elle est physiquement dans un runtime ; cette colocation ne rend pas le runtime propriétaire de sa sémantique.** Les tests de package sont une contrainte cible à ajouter lors de la migration, non des gates déjà livrées. Pour les packages `supra`, le runtime peut câbler infra et engine, mais la classe supra ne doit pas importer l’implémentation SQL ni les classes de configuration Spring ; le guard doit vérifier les imports de ces packages, pas seulement les POM.

| TM / rôle préservé | Natural parent POM | Target package | Forbidden dependency still protected how? |
| --- | --- | --- | --- |
| TM-05 `domain` | TM-04 `domain-pot` | `com.kartaguez.pocoma.domain.pot.policy` | domain-pot reste pur ; package policy ne peut importer infra via règle structurelle |
| TM-08 `contracts` | TM-18 `engine-admit-command` | `com.kartaguez.pocoma.engine.command.admit.authentication` | admission reste neutre Spring ; supra HTTP importe déjà admission |
| TM-15 `orchestrator` | TM-14 `engine-consumption` | `com.kartaguez.pocoma.engine.consumption.orchestration` | aucun import business dans protocole générique ; package guard |
| TM-25 `engine` | TM-22 `engine-admit-registration` | `com.kartaguez.pocoma.engine.registration.readresult` | GET ne peut importer Binding authority, Consumption ou SQL ; guard package |
| TM-35 `supra` | TM-44 `runtime-command-consumption-worker` | `com.kartaguez.pocoma.runtime.command.supra` | supra n’importe pas infra SQL/Spring config ; règle import de package |
| TM-36 `supra` | TM-45 `runtime-event-consumption-worker` | `com.kartaguez.pocoma.runtime.event.supra` | supra reste metadata-only et n’importe pas SQL ; règle package et tests |
| TM-37 `supra` | TM-47 `runtime-binding-consumption-worker` | `com.kartaguez.pocoma.runtime.binding.supra` | supra n’importe pas SQL ni ne possède la règle R ; règle package |
| TM-38 `supra` | TM-49 `runtime-registration-consumption-worker` | `com.kartaguez.pocoma.runtime.registration.supra` | supra Request n’importe pas SQL concret ; résultat garde sa glue locale |

La contraction n’efface pas les différences sémantiques : `orchestrator-consumption` garde Sequential et AcquireThenFinalize en package de `engine-consumption`, sans ProjectionTask ; `engine-read-registration-result` reste un engine GET dans le POM d’admission, sans importer Binding authority ni Consumption ; `domain-pot-policy` reste une politique pure ; les quatre supras de consommation gardent discovery/reload/adaptation, tandis que SQL reste infra et backoff runtime. Leurs consommateurs directs C.1 sont chacun **un seul runtime** ; le POM n’évite aucune closure entre processus. Les règles ArchUnit/`architecture-tests` proposées doivent bloquer les imports du package `supra` vers `infra.*`, Spring config et les autres runtimes, et bloquer tout import `engine.registration.readresult` vers `port.binding.authority` ou `engine.consumption`. Aucun gate global n’est déclenché pour cette édition documentaire.

### N.5 Supra HTTP : comparaison des deux formes

| Option | Closure et imports | Réutilisation/testabilité | Décision |
| --- | --- | --- | --- |
| A — TM-43 runtime→TM-34 supra POM | TM-34 : 7 deps directs, 21 transitifs, 1 consommateur ; aucun arc vers infra SQL/JPA ; Web reste à 36 TM transitifs via TM-40 | un seul runtime consomme, mais routes/DTO/principal compilent sans implémentation PRIMARY | **KEEP_POM** : interdit à la compilation HTTP supra→adapter E→U PRIMARY concret |
| B — package supra.http dans TM-43 | même code exécuté et closure Web totale inchangée ; imports JPA/PRIMARY deviennent légaux dans les classes HTTP | package/ArchUnit pourrait détecter ensuite, mais n’empêche pas à la compilation une régression du franchissement E→U | écarté à ce stade ; protection Maven concrète de BC-14 |

Le POM HTTP unique reste distinct de la composition Web ; aucun POM par route. En revanche, les quatre POM supra Consumption TM-35/36/37/38 sont `PACKAGE_ONLY` : chacun n’a qu’un runtime consommateur, et leur éventuel import SQL doit être gardé au niveau package. Le supra polling TM-16 reste `KEEP_POM` avec **huit** consommateurs : l’absorber dans un runtime créerait des arcs runtime→runtime, l’absorber dans le moteur Consumption ferait entrer cadence et lifecycle Spring dans le protocole.

### N.6 Registration, Command Result, infra et runtimes

**Registration.** TM-09 contracts a cinq consommateurs directs (les quatre engines Registration et l’adapter SQL) ; Web et les deux loops l’utilisent transitivement et protège un langage durable neutre. TM-22 admit et TM-23 consume restent POM : Web ne doit pas importer l’arbitrage Binding. TM-24 materialize reste POM : le Result worker ne doit pas importer l’authority Binding de TM-23. TM-25 read a seulement `contracts-registration` comme dépendance directe ; ses deux consommateurs (supra HTTP et SQL) importent déjà TM-22. Son package dans TM-22 ajoute l’abstraction de transaction TM-12 au GET, mais ni Binding authority ni Consumption. Le guard interdit explicitement ces deux dernières dépendances. TM-38 supra Request a un seul runtime consommateur ; package dans TM-49. La glue Outcome reste un package supra distinct dans TM-50, même si le statut physique de ce runtime reste TBD.

**Command Result.** TM-20 read dépend encore de TM-17 Command pour le modèle actuel (BC-04) ; TM-21 materialize ajoute Pot/Event/Consumption. Les deux POM restent conservés pour bloquer un import direct du terminal côté GET ; la mesure **ne** leur attribue pas une réduction de la closure Web totale : TM-43 a 36/52 TM transitifs et TM-40 importe TM-21. Une décision ultérieure sur BC-04 ou les adapters pourrait rendre cette barrière plus efficace, sans autoriser ici un `contracts-command` arbitraire.

**Infra.** TM-39 Spring transaction, TM-40 SQL PRIMARY/Consumption, TM-41 exact projection/JSON et TM-42 READ current/LKV restent quatre providers distincts. TM-40 a une large closure mais son split interne n’est pas décidé en C.2 : même transaction, même datasource, même JPA et même POM restent différents. TM-41 a deux consommateurs, TM-42 en a quatre ; leur fusion avec TM-40 exposerait des implémentations mutuellement inutiles aux queries/projectors et brouillerait exact @V, current et watermark.

**Runtimes.** Web et cinq workers Command/Event/Task/Binding/Registration Request sont `KEEP_POM` comme racines Spring exécutable CURRENT, sans conclure à des déploiements autonomes éternels. Command Result, Registration Result et LKV restent `TBD_PHYSICAL` : chacun garde son slot et sa loop indépendante ; la nécessité d’une Spring application, d’un POM et d’un déploiement séparés dans la cible finale n’est pas établie. Neuf loops subsistent même si un futur processus en compose plusieurs. Aucun rapprochement de loops n’est prescrit.

| Runtime | Slot distinct ? | Loop distincte ? | Spring app indépendante CURRENT ? | POM cible | Déploiement autonome TARGET |
| --- | --- | --- | --- | --- | --- |
| runtime-web-api | sans objet | HTTP | oui | KEEP_POM | oui : surface HTTP |
| runtime-command-consumption-worker | oui | oui | oui | KEEP_POM | non démontré |
| runtime-event-consumption-worker | oui | oui | oui | KEEP_POM | non démontré |
| runtime-task-consumption-worker | oui | oui | oui | KEEP_POM | non démontré |
| runtime-binding-consumption-worker | oui | oui | oui | KEEP_POM | non démontré |
| runtime-command-result-consumption-worker | oui | oui | oui | TBD_PHYSICAL | non démontré |
| runtime-registration-consumption-worker | oui | oui | oui | KEEP_POM | non démontré |
| runtime-registration-result-consumption-worker | oui | oui | oui | TBD_PHYSICAL | non démontré |
| runtime-latest-known-version-consumption-worker | oui | oui | oui | TBD_PHYSICAL | non démontré |

### N.7 Matrice canonique des arcs Maven physiques

Projection de H en contractant exactement les huit TM `PACKAGE_ONLY` vers leur parent ; arcs internes supprimés et doublons dédupliqués. La colonne origine renvoie aux arcs logiques H (et donc à leur raison explicite). Les quatre nœuds `TBD_PHYSICAL` et leurs arcs sont conservés **conditionnellement** pour préserver la compatibilité CURRENT. Si un TBD devient package, la contraction devra être recalculée avant migration. Cette matrice est la source unique des arcs Maven cibles C.2 ; H demeure la source des arcs **logiques**. `A→B` signifie A dépend de B.

| From POM | To POM | Origine logique / justification | Conditional? |
| --- | --- | --- | --- |
| TM-04 | TM-01 | TM-05→TM-01 | no |
| TM-04 | TM-02 | TM-04→TM-02 | no |
| TM-09 | TM-03 | TM-09→TM-03 | no |
| TM-11 | TM-03 | TM-11→TM-03 | no |
| TM-13 | TM-07 | TM-13→TM-07 | no |
| TM-14 | TM-06 | TM-14→TM-06 | no |
| TM-14 | TM-12 | TM-14→TM-12 | no |
| TM-16 | TM-06 | TM-16→TM-06 | no |
| TM-16 | TM-14 | TM-16→TM-15 | no |
| TM-17 | TM-02 | TM-17→TM-02 | no |
| TM-17 | TM-03 | TM-17→TM-03 | no |
| TM-17 | TM-11 | TM-17→TM-11 | no |
| TM-17 | TM-14 | TM-17→TM-14 | no |
| TM-18 | TM-03 | TM-08→TM-03 | no |
| TM-18 | TM-12 | TM-18→TM-12 | no |
| TM-18 | TM-17 | TM-18→TM-17 | no |
| TM-19 | TM-04 | TM-19→TM-04, TM-19→TM-05 | no |
| TM-19 | TM-12 | TM-19→TM-12 | no |
| TM-19 | TM-17 | TM-19→TM-17 | no |
| TM-20 | TM-03 | TM-20→TM-03 | no |
| TM-20 | TM-17 | TM-20→TM-17 | no |
| TM-21 | TM-04 | TM-21→TM-04 | no |
| TM-21 | TM-14 | TM-21→TM-14 | no |
| TM-21 | TM-17 | TM-21→TM-17 | no |
| TM-21 | TM-20 | TM-21→TM-20 | no |
| TM-22 | TM-09 | TM-22→TM-09, TM-25→TM-09 | no |
| TM-22 | TM-12 | TM-22→TM-12 | no |
| TM-23 | TM-09 | TM-23→TM-09 | no |
| TM-23 | TM-11 | TM-23→TM-11 | no |
| TM-23 | TM-12 | TM-23→TM-12 | no |
| TM-23 | TM-14 | TM-23→TM-14 | no |
| TM-24 | TM-09 | TM-24→TM-09 | no |
| TM-24 | TM-12 | TM-24→TM-12 | no |
| TM-24 | TM-14 | TM-24→TM-14 | no |
| TM-26 | TM-02 | TM-26→TM-02 | no |
| TM-26 | TM-04 | TM-26→TM-04 | no |
| TM-26 | TM-07 | TM-26→TM-07 | no |
| TM-26 | TM-13 | TM-26→TM-13 | no |
| TM-27 | TM-07 | TM-27→TM-07 | no |
| TM-27 | TM-12 | TM-27→TM-12 | no |
| TM-27 | TM-13 | TM-27→TM-13 | no |
| TM-27 | TM-14 | TM-27→TM-14, TM-27→TM-15 | no |
| TM-28 | TM-07 | TM-28→TM-07 | no |
| TM-28 | TM-13 | TM-28→TM-13 | no |
| TM-29 | TM-04 | TM-29→TM-04 | no |
| TM-29 | TM-07 | TM-29→TM-07 | no |
| TM-29 | TM-13 | TM-29→TM-13 | no |
| TM-30 | TM-01 | TM-30→TM-01 | no |
| TM-30 | TM-03 | TM-30→TM-03 | no |
| TM-30 | TM-04 | TM-30→TM-04, TM-30→TM-05 | no |
| TM-30 | TM-28 | TM-30→TM-28 | no |
| TM-31 | TM-03 | TM-31→TM-03 | no |
| TM-31 | TM-12 | TM-31→TM-12 | no |
| TM-31 | TM-14 | TM-31→TM-14 | no |
| TM-32 | TM-03 | TM-32→TM-03 | no |
| TM-33 | TM-04 | TM-33→TM-04 | yes |
| TM-33 | TM-06 | TM-33→TM-06 | yes |
| TM-33 | TM-14 | TM-33→TM-14 | yes |
| TM-34 | TM-18 | TM-34→TM-08, TM-34→TM-18 | no |
| TM-34 | TM-20 | TM-34→TM-20 | no |
| TM-34 | TM-22 | TM-34→TM-22, TM-34→TM-25 | no |
| TM-34 | TM-30 | TM-34→TM-30 | no |
| TM-34 | TM-32 | TM-34→TM-32 | no |
| TM-39 | TM-12 | TM-39→TM-12 | no |
| TM-40 | TM-09 | TM-40→TM-09 | no |
| TM-40 | TM-10 | TM-40→TM-10 | no |
| TM-40 | TM-11 | TM-40→TM-11 | no |
| TM-40 | TM-12 | TM-40→TM-12 | no |
| TM-40 | TM-13 | TM-40→TM-13 | no |
| TM-40 | TM-14 | TM-40→TM-14 | no |
| TM-40 | TM-17 | TM-40→TM-17 | no |
| TM-40 | TM-18 | TM-40→TM-18 | no |
| TM-40 | TM-19 | TM-40→TM-19 | no |
| TM-40 | TM-20 | TM-40→TM-20 | no |
| TM-40 | TM-21 | TM-40→TM-21 | no |
| TM-40 | TM-22 | TM-40→TM-22, TM-40→TM-25 | no |
| TM-40 | TM-23 | TM-40→TM-23 | no |
| TM-40 | TM-24 | TM-40→TM-24 | no |
| TM-40 | TM-26 | TM-40→TM-26 | no |
| TM-40 | TM-27 | TM-40→TM-27 | no |
| TM-40 | TM-30 | TM-40→TM-30 | no |
| TM-40 | TM-31 | TM-40→TM-31 | no |
| TM-40 | TM-33 | TM-40→TM-33 | yes |
| TM-41 | TM-07 | TM-41→TM-07 | no |
| TM-41 | TM-13 | TM-41→TM-13 | no |
| TM-42 | TM-31 | TM-42→TM-31 | no |
| TM-42 | TM-32 | TM-42→TM-32 | no |
| TM-42 | TM-33 | TM-42→TM-33 | yes |
| TM-43 | TM-10 | TM-43→TM-10 | no |
| TM-43 | TM-34 | TM-43→TM-34 | no |
| TM-43 | TM-39 | TM-43→TM-39 | no |
| TM-43 | TM-40 | TM-43→TM-40 | no |
| TM-43 | TM-41 | TM-43→TM-41 | no |
| TM-43 | TM-42 | TM-43→TM-42 | no |
| TM-44 | TM-14 | TM-35→TM-15 | no |
| TM-44 | TM-16 | TM-44→TM-16 | no |
| TM-44 | TM-17 | TM-35→TM-17 | no |
| TM-44 | TM-39 | TM-44→TM-39 | no |
| TM-44 | TM-40 | TM-44→TM-40 | no |
| TM-45 | TM-14 | TM-36→TM-15 | no |
| TM-45 | TM-16 | TM-45→TM-16 | no |
| TM-45 | TM-26 | TM-36→TM-26 | no |
| TM-45 | TM-39 | TM-45→TM-39 | no |
| TM-45 | TM-40 | TM-45→TM-40 | no |
| TM-46 | TM-16 | TM-46→TM-16 | no |
| TM-46 | TM-27 | TM-46→TM-27 | no |
| TM-46 | TM-29 | TM-46→TM-29 | no |
| TM-46 | TM-39 | TM-46→TM-39 | no |
| TM-46 | TM-40 | TM-46→TM-40 | no |
| TM-46 | TM-41 | TM-46→TM-41 | no |
| TM-46 | TM-42 | TM-46→TM-42 | no |
| TM-47 | TM-14 | TM-37→TM-15 | no |
| TM-47 | TM-16 | TM-47→TM-16 | no |
| TM-47 | TM-31 | TM-37→TM-31 | no |
| TM-47 | TM-39 | TM-47→TM-39 | no |
| TM-47 | TM-40 | TM-47→TM-40 | no |
| TM-47 | TM-42 | TM-47→TM-42 | no |
| TM-48 | TM-16 | TM-48→TM-16 | yes |
| TM-48 | TM-21 | TM-48→TM-21 | yes |
| TM-48 | TM-39 | TM-48→TM-39 | yes |
| TM-48 | TM-40 | TM-48→TM-40 | yes |
| TM-49 | TM-14 | TM-38→TM-15 | no |
| TM-49 | TM-16 | TM-49→TM-16 | no |
| TM-49 | TM-23 | TM-38→TM-23 | no |
| TM-49 | TM-39 | TM-49→TM-39 | no |
| TM-49 | TM-40 | TM-49→TM-40 | no |
| TM-50 | TM-16 | TM-50→TM-16 | yes |
| TM-50 | TM-24 | TM-50→TM-24 | yes |
| TM-50 | TM-39 | TM-50→TM-39 | yes |
| TM-50 | TM-40 | TM-50→TM-40 | yes |
| TM-51 | TM-10 | TM-51→TM-10 | yes |
| TM-51 | TM-16 | TM-51→TM-16 | yes |
| TM-51 | TM-33 | TM-51→TM-33 | yes |
| TM-51 | TM-39 | TM-51→TM-39 | yes |
| TM-51 | TM-40 | TM-51→TM-40 | yes |
| TM-51 | TM-42 | TM-51→TM-42 | yes |

**Contrôle graphe : 136 arcs Maven physiques conditionnels (117 entre POM fermes), 44 nœuds si les quatre TBD gardent un POM ; aucun cycle** (parcours DFS). Les cinq interdictions structurelles restent vraies dans les arcs POM : pas de générique Consumption→capability, engine→runtime, runtime→runtime, projector→SQL/Spring ni Current Binding query→Binding authority. Les règles de package N.4 sont nécessaires pour la même interdiction à l’intérieur des POM fusionnés. GET Pot E→U demeure derrière un port applicatif TBD ; Results restent hors ProjectionTask.

### N.8 Comptes distincts, limites et vérification

**Architecture logique : 52 entrées TM (51 responsabilités de production + `architecture-tests` hors production). Topologie Maven ferme : 40 POM `KEEP_POM` (39 de production + le gate de test) ; 8 rôles `PACKAGE_ONLY` ; 4 zones `TBD_PHYSICAL`.** La cible de continuité maximale serait **44 POM** et **136 arcs** si tous les TBD gardent leur POM (117 arcs entre POM fermes) ; la cible ferme contient 40 POM, et les TBD pourraient être contractés différemment. Le nombre 51 du reactor CURRENT n’est pas un objectif à battre. Le nombre de classes et la closure de code d’un runtime ne diminuent pas automatiquement quand deux POM sont réunis : seule la granularité du graphe change.

Limites à lever avant un plan de migration : tester les imports réels de chaque `PACKAGE_ONLY` contre les guards proposés ; confirmer que la cohabitation TM-22/TM-25 ne tire pas un port WRITE par un import Java oublié ; vérifier la closure Command Result après BC-04 ; décider LKV fonctionnellement hors C.2 ; préciser la source/owner E→U hors C.2 ; décider la forme de process des trois runtimes TBD. La cible garde append-only Binding, R/tombstone/divergence, E+B fencing, outcomes/Results immuables et owner historique, commits Consumption fenced et exact @V ; aucun nouveau chemin Results/Current via ProjectionTask.

Vérification de cette édition : lecture statique des documents et imports ciblés, calcul automatisé des closures et fan-in sur H, contraction des huit nœuds package et DFS des arcs physiques. Aucun slice Maven déclaré pour une édition documentaire ; aucun Maven, test, gate global ou reactor complet exécuté. Aucun code, POM, SQL ou runtime modifié.
