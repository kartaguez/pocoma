# DEBT-MOD-01 — topologie cible C.1 : grammaire, supra et frontières

**Statut : TARGET / proposition non livrée.** Les noms et POM ci-dessous ne décrivent pas le reactor exécutable. Les décisions [BC-01 à BC-16](Modularity_Boundary_Challenge.md#p-boundary-decisions) bornent cette cible ; les trois zones `TBD` ne deviennent pas des décisions par leur présence au catalogue. La [dette](../../../debts/MODULE_TAXONOMY/Debt.md) reste OPEN.

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
| HTTP/JWT Command, Pot, Result, Binding, Registration | DTO/errors/principal et mapping IO | un POM `supra-http-api` MEDIUM ; cinq POM route refusés | admit/read engines | ports applicatifs ; pas de resolver PRIMARY concret |
| Command candidate/slot | discovery→authoritative reload→issue | POM `supra-consume-command` MEDIUM | consume Command + Consumption | discovery/source ports ; SQL infra |
| Event candidate metadata | metadata-only→ensure Task→issue | POM `supra-consume-event` MEDIUM | produce Task + Consumption | Event discovery/Task ensure ; pas EventLoader artificiel |
| ProjectionTask candidate | Task key→Task engine→issue | package `supra` dans runtime Task ; POM rejeté faute d’autre client | consume ProjectionTask | Task ports ; pure projector hors supra |
| Binding fact candidate | discovery→reload fact eventId→issue | POM `supra-consume-binding` MEDIUM | materialize Current + Consumption | fact ports ; R rule engine, SQL infra |
| Command Result terminal candidate | Event/outcome/Command reload→issue | package `supra` runtime Result ; POM séparé non démontré | materialize Command Result | source ports ; terminal policy engine |
| Registration Request candidate | request reload→issue | POM `supra-consume-registration` MEDIUM, réservé à Request | consume Registration | request ports ; arbitrage engine |
| Registration Outcome candidate | request/outcome reload→issue | package `supra` runtime Registration Result ; POM rejeté pour préserver sa closure | materialize Registration Result | outcome ports ; owner E engine |
| LKV Event candidate | Event metadata→max issue | package `supra` runtime LKV provisoire ; POM TBD, non créé | advance Pot watermark TBD | discovery/max ports ; aucune readiness |
| Tick/segment de worker | poll/cadence→Consumption generic | POM `supra-poll-consumption` STRONG | orchestrator Consumption | ni business policy ni SQL |

Le POM HTTP unique sépare traduction de protocole et composition Spring. Son MEDIUM demande de confirmer à la migration qu’un package Web ne donne pas la même protection. Les POM supra Command/Event/Binding/Registration Request isolent la glue en dehors du process donné et empêchent qu’un runtime en devienne l’owner par commodité ; leur score MEDIUM laisse ouverte une dissolution en package après mesure. Les supras Task/Results/LKV **existent conceptuellement** même sans POM.

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

## H. Matrice canonique des arcs TARGET

**Seule source de vérité des arcs structurels du catalogue.** `A → B` signifie que A importe B. `Required? yes` concerne la proposition ferme ou l’enveloppe actuelle compatible ; `TBD? yes` signifie que l’arc devra être revalidé avant migration. Les imports de bibliothèques externes sont omis. Les `Dependencies` et `Dependents` des fiches TM sont calculés à partir de cette matrice. Aucun arc runtime→runtime, engine→runtime, generic Consumption→business capability, projector pur→SQL/Spring. Le tri topologique de ces arcs est acyclique. Les chaînes fonctionnelles en section I décrivent un ordre d’exécution, **pas** un sens d’import Maven.

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

Chaque fiche est une **frontière Maven proposée ou enveloppe TBD**, pas une prescription de package. Les listes `Dependencies`/`Dependents` sont dérivées de H. `Verb` est obligatoire pour les engines hors cœur ; `Concrete IO adapted` pour les supras ; `Deployment-only` pour les runtimes. `STRONG` sur un engine protège la séparation application→adapter/runtime ; les splits internes de capacité restent discutables si un même POM peut protéger la même direction.

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
- Dependents: TM-04, TM-26.
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
- Dependents: TM-51.
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
- Dependencies: TM-03, TM-11, TM-14.
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
- Dependencies: TM-11, TM-12, TM-13, TM-14, TM-17, TM-18, TM-19, TM-20, TM-21, TM-22, TM-23, TM-24, TM-25, TM-26, TM-27, TM-30, TM-31, TM-33, TM-09.
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
- Dependencies: TM-34, TM-39, TM-40, TM-41, TM-42.
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

## L. Recalibrage des strengths, compte et zones TBD

Après la passe de rôles et supras : **52 emplacements**, dont **48 PROPOSED + 4 TBD** ; strengths **30 STRONG, 18 MEDIUM, 4 TBD**, zéro WEAK. Reactor CURRENT : 51 modules, neuf runtimes. Cible conditionnelle : six runtimes PROPOSED et trois runtimes TBD (les deux Results et LKV) ; les neuf loops restent indépendantes. Le compte peut diminuer si des MEDIUM sont ramenés à des packages, ou varier si les TBD sont résolus autrement. Il n’est pas un objectif.

Recalibrages notables : `port-transaction` STRONG→MEDIUM ; `domain-event`, `orchestrator-consumption`, `engine-produce-projection-task`, `infra-read-persistence`, `contracts-observability` restent MEDIUM ; admission Command est reclassée d’orchestrator MEDIUM en engine MEDIUM ; les quatre supra Consumption physiques sont MEDIUM ; les workers Command/Event/Task/Binding/Registration passent STRONG→MEDIUM car loop≠process ; les deux Result runtimes passent STRONG→TBD ; LKV work/runtime MEDIUM provisoire→TBD. Le POM HTTP supra est MEDIUM, Web runtime reste STRONG.

Zones non décidées : (1) **TBD-LKV** : owner, besoin aval, POM/processus final ; (2) **TBD-E2U** : owner/source et garanties PRIMARY→READ ; (3) **TBD-COMMAND-CONTRACT** : éventuel contrat Command neutre, sans `contracts-command` proposé ; (4) valeur finale des POM MEDIUM après mesure des imports et composition de process. La cible ferme n’utilise aucun choix implicite sur les trois premiers.

## M. Contraintes de migration et validation conceptuelle

Avant un plan détaillé : stabiliser valeurs/ports avant dissolution d’`engine-core` ; séparer policy sémantique, supra glue, SQL et backoff avant simplification des runtimes ; sortir la spécialisation Task du générique avant inversion de son arc ; conserver le scénario GET Pot→PRIMARY compatible derrière le port E→U ; déplacer les adapters par cluster sans casser l’atomicité effet durable+provenance+CAS ; conserver les neuf slots et chaque exécutable courant pendant les étapes intermédiaires. La rationalisation pure Pot/JSON/HTTP peut être instruite indépendamment, sous réserve de ses imports réels.

La cible préserve conceptuellement : Command E+B et fencing ; Registration sans User orphelin et outcome unique ; Results 0..1 immuables, owner E historique, indépendants du Binding courant ; Current Binding revision/tombstone/divergence et absence de délai borné ; Consumption claim séparé, retry et late-commit protection ; exact @V sans fallback ; projection input→projector pur→key→validator de sortie→persistence ; aucun Results/Current→ProjectionTask. Ces propriétés dépendent ensuite de la composition transactionnelle et des preuves CURRENT ; la topologie seule ne les exécute pas.

Vérification C.1 : audit documentaire/statique, aucun slice Maven déclaré, aucune commande Maven ni gate global ni reactor complet. La matrice H a été vérifiée sans cycle ; le mapping couvre les 51 modules CURRENT et les fiches reprennent exactement les arcs H. Aucun code, POM, SQL ou runtime n’est changé.
