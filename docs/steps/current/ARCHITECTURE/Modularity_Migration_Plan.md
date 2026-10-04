# DEBT-MOD-01 — plan de migration Maven CURRENT → TARGET

**Statut : PLANNED, documentaire seulement.** Baseline de conception `704177a0`. Sources d'autorité : [audit CURRENT](Modularity_Current_State_Audit.md) et [topologie TARGET normative](Modularity_Target_Topology.md). La présente page ordonne la migration ; elle ne modifie ni la TARGET, ni Java, ni POM, ni SQL. Les 54 POM fermes, 147 arcs physiques et trois responsabilités LKV `TBD_PHYSICAL` sont ceux de la TARGET. `domain-pot-policy` reste un package de `domain-pot`.

## 1. Règle d'exécution d'un lot

Chaque `D.nn` est un commit/PR autonome : ajout avant retrait, bascule d'un consommateur après compilation du contrat stable, suppression des bridges nommés au lot prévu. Son implémenteur relit la [politique normative de vérification](../../../testing/Reactor_Verification_Policy.md) **avant** de toucher `app/`, consigne avant la première édition : impact de production attendu, slice primaire, slices secondaires, impact interdit, preuve locale, gate global, full reactor, preuve DB et conditions d'escalade. Il lit aussi le diff CURRENT réel : les noms de classes ci-dessous sont des points de départ, pas une dispense d'inventaire. Le lot échoue si une dépendance `new → old → new` apparaît, si un cycle Maven existe, si la sémantique change, ou si un bridge n'a pas d'étape de retrait. Un franchissement inattendu de slice suspend le lot : expliquer et réviser son périmètre avant de poursuivre.

Codes de preuve du tableau : `W` = `./mvnw -pl runtime-web-api -am test` ; `C` = `./mvnw -pl runtime-command-consumption-worker -am test` ; `E` = `./mvnw -pl runtime-event-consumption-worker -am test` ; `P` = `./mvnw -pl runtime-task-consumption-worker -am test` ; `B` = `./mvnw -pl runtime-binding-consumption-worker -am test` ; `L` = `./mvnw -pl runtime-latest-known-version-consumption-worker -am test` ; `R` = `./mvnw -pl runtime-registration-consumption-worker -am test` ; `CR` = `./mvnw -pl runtime-command-result-consumption-worker -am test` ; `RR` = `./mvnw -pl runtime-registration-result-consumption-worker -am test`. Pour plusieurs slices : `./mvnw -pl <ancres séparées par virgules> -am test`, avec les ancres indiquées, sans `-amd`. `A` = `./mvnw -pl architecture-tests -am test` ; `F` = `./mvnw test`. `A` est obligatoire à la clôture de **chaque** lot qui ajoute, renomme, retire un POM ou change ses arcs, conformément à la politique ; il n'est pas ajouté à la boucle interne. `F` est réservé aux jalons D.09, D.18 et D.27, qui ferment des restructurations larges, et à l'intégration finale. Un simple test de compilation (`./mvnw -pl <ancre> -am -DskipTests compile`) peut précéder le test, sans s'y substituer. `dependency:tree`, recherche d'imports interdits, tests ciblés et guards WA/PCL/CCR complètent la preuve indiquée. La preuve DB porte sur le schéma courant ; conserver les migrations historiques. Tant que la baseline V23 n'est pas certifiée `TRUSTED` dans la politique, ne pas prétendre utiliser une baseline fiable ni effacer V1–V23 ; ne lancer la preuve historique complète que si l'infrastructure de migration ou la compatibilité d'upgrade change.

### Lecture du graphe et ordre retenu

Les feuilles sans dépendance sont notamment `domain-authorization`, `domain-event`, `domain-user-identity`, `domain-consumption`, `domain-projection`, `contracts-observability`, `port-transaction`. Les faibles fan-out qui débloquent plusieurs chaînes sont `contracts-authentication` (1), `contracts-registration` (1), `port-binding-authority` (1), `port-projection` (1), `engine-consumption` (2), `orchestrator-consumption` (1), `orchestrator-poll-consumption` (2) et `projector-pot` (2). Leurs fan-in respectifs pour `port-transaction`, `engine-consumption`, `orchestrator-consumption`, `orchestrator-poll-consumption` sont 10, 8, 9, 7. Les huit runtimes sont des racines de fan-in nul ; `infra-persistence-primary-jpa` a 19 imports directs et doit être renommé tard, après stabilisation de ses ports. Aucun POM de fondation ne doit importer `engine-core`, `engine-registration`, `engine-projection-task` ou un locator entier : extraire d'abord les valeurs et interfaces nécessaires, puis changer les importeurs. Les bridges requis concernent ces quatre agrégats, les vieux projectors, l'ancienne lecture `engine-read-projection`, les locators et les configurations Spring.

Ordre de disponibilité logique : valeurs/ports → Consumption générique → projector pur → projection exacte et Task → Binding direct → Command → Registration → Results directs → HTTP → adapters/infra → racines runtime → destruction legacy → règles globales. Les résultats Registration peuvent suivre leur contrat dès que celui-ci existe ; ils ne doivent pas attendre Command. L'ordre du tableau respecte ces dépendances, et le jalon D.09 stabilise les chaînes Projection avant les splits suivants.

## 2. Lots exécutables

Un POM « créé » ci-dessous signifie ajouté au reactor seulement si la matrice de création le marque `CREATE`/`EXTRACT` ; `RENAME/REHOME` conserve l'implantation et remplace son identité Maven après bascule. `—` signifie aucun nouveau POM. Les responsabilités `infra-*` techniques peuvent être déplacées dans le lot métier, mais leurs changements d'identité Maven attendent les lots infra. Chaque ligne indique les imports à **ajouter puis retirer** ; la matrice des bridges précise leur fin.

| Step | Goal | New POMs | Legacy modules touched | Main moves; dependency delta | Temporary bridges | Verification | Exit condition |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D.01 PLANNED | Contrats transverses | `contracts-authentication` RENAME, `contracts-observability` RENAME, `contracts-registration` EXTRACT | `authentication-contracts`, `observability`, `engine-registration` | Principal et métriques inchangés ; Request/Outcome Registration neutres sortis ; futurs clients → contrats, ancien engine cesse d'être source des types | B1 facade Registration jusqu'à D.16 | W,R,RR,A ; imports Spring interdits | Contrats purs, anciens clients compilent, aucun cycle |
| D.02 PLANNED | Ports sans mécanique | `port-transaction`, `port-binding-authority`, `port-projection` | `engine-core`, `domain-user-identity`, `engine-projection-contracts` | `TransactionRunner`, ports d'autorité Binding et projection exacte ; adapters/clients → ports ; aucun port → engine/infra | B2 forwarding transaction, B3 facade Binding, B4 facade projection jusqu'à D.21 | C,B,P,W,A ; dependency:tree | Ports sans legacy large, aucun import inverse |
| D.03 PLANNED | Valeurs de `engine-core` I | — | `engine-core`, `domain-consumption`, `domain-pot`, `infra-tx-spring` | `WorkerSegment`/`PartitionHash` → domain Consumption ; `RecordedEvent`/`EventTraceMetadata`/envelope Pot → `domain-pot` selon TARGET ; infra Spring → `port-transaction` | B5 forwarding des anciens packages jusqu'à D.24 | C,E,P,B,A ; forbidden-import grep | Valeurs pures et transaction sans dépendance `engine-core` des nouveaux ports |
| D.04 PLANNED | Noyau Consumption | — | `engine-consumption`, `orchestrator-consumption`, `engine-core` | Acquire/Execute/Finalize gardés ; transaction → port ; Sequential et AcquireThenFinalize restent génériques ; spécialisation `ProjectionTaskConsumptionOrchestrator` isolée en place avant D.08 | B6 facade Task dans ancien orchestrator jusqu'à D.08 | C,E,P,B,R,CR,RR,A ; WA/PCL/CCR | Noyau ne connaît aucun Command/Binding/Task/Result concret |
| D.05 PLANNED | Polling réutilisable | `orchestrator-poll-consumption` RENAME/REHOME | `supra-consumption-worker`, runtimes workers | Budget, attente, cycle de vie vers orchestrator ; workers → nouveau POM puis retirent supra ancien | B7 anciennes entrées worker jusqu'à D.23 | C,E,P,B,R,CR,RR,L,A ; bean scan | Polling sans capacité métier, ancien supra sans utilisateurs fermes |
| D.06 PLANNED | Projector Pot pur | `projector-pot` EXTRACT | `domain-pot-projection`, `domain-projection-balance`, `engine-projection-pot`, `engine-projection-balance`, `engine-read-projection` | AUTH/READ_POT/BALANCES et reconstruction pure ; définitions → `domain-projection`, valeurs → `domain-pot` ; projector → domaines seulement | B8 delegates anciens projectors jusqu'à D.08 | P,W,A ; tests déterminisme, grep Spring/JPA/SQL/runtime | Projector sans IO et sortie identique à corpus existant |
| D.07 PLANNED | Production Event→Task | `engine-produce-projection-task`, `supra-consume-event` | `engine-processing-event`, `locator-consumption-event`, `engine-projection-task`, runtime Event | ensure Task et policy de production → engine ; candidate/reload/issue → supra ; SQL reste PRIMARY ; Event runtime compose poll/supra | B9 facade locator Event jusqu'à D.23 | E,P,A ; PCL et retry | Metadata-only Event, Task unique et aucun Task spécifique dans Consumption générique |
| D.08 PLANNED | Exécution Task | `engine-consume-projection-task`, `supra-consume-projection-task` | `engine-projection-task`, `orchestrator-consumption`, `engine-projection-pot`, `engine-projection-balance`, runtime Task | Préparer/consommer/finaliser Task et orchestrateur spécifique → engine ; catalog/reload/issue → supra/runtime ; projector pur appelé via port, adapters loaders restent PRIMARY | B8 et B6 retirés ici ; B10 ancien Task facade jusqu'à D.23 | P,E,A ; PCL, exact @V, failure | Generic Consumption sans Task ; pas de SQL dans projector ; Task worker compose |
| D.09 PLANNED | Projection exacte stable, jalon | `infra-persistence-projection-jpa` RENAME, `infra-projection-validation-networknt` RENAME | `engine-projection-read`, `engine-read-projection` ancien, `infra-projection-persistence`, `infra-projection-json-schema`, runtime Web/Task | Valider/persister/lire @V via `port-projection` ; conserver provisoirement le nom CURRENT `engine-projection-read` car l'ancien `engine-read-projection` occupe encore l'identité cible | B11 identité exact-read transitoire jusqu'à D.11 ; B12 adapter projection jusqu'à D.21 | P,W,E,A,F ; schema courant, exact @V | Projection produit/consomme/valide/persiste/lit sans cycle ; jalon global vert |
| D.10 PLANNED | Current Binding direct | `engine-materialize-current-binding`, `engine-read-current-binding`, `supra-consume-binding` | `engine-read-projection` ancien, `locator-consumption-binding`, runtime Binding, HTTP read | Apply fact/R/tombstone → materialize ; GET self → read ; candidate/reload/issue → supra ; READ port sans authority WRITE | B13 anciennes APIs Binding jusqu'à D.11 ; B14 locator jusqu'à D.23 | B,W,A ; WA guards, faits append-only, divergence | CURRENT_BINDING direct, R contigu, DETACHED, même R divergent rejeté ; aucun Task |
| D.11 PLANNED | Libérer collision `engine-read-projection` | `engine-read-projection` RENAME/REHOME effectif | ancien `engine-read-projection`, `engine-projection-read`, `engine-processing-event`, `infra-read-persistence`, runtime LKV | `HistoricalPotSnapshotSource` → `port-projection` + adapter PRIMARY ; LKV encore indécis déplacé temporairement dans l'existant `engine-processing-event`/runtime LKV sans décider owner TARGET ; supprimer ancien module, renommer exact reader | B11 et B13 retirés ; B15 hébergement LKV provisoire jusqu'à D.TBD-LKV | P,B,W,L,A ; dependency:tree | Un seul `engine-read-projection`, dédié exact @V ; LKV courant continue à fonctionner |
| D.12 PLANNED | Pot WRITE | `engine-write-pot` RENAME/REHOME | `engine-pot-command`, `engine-core`, `domain-pot-policy`, `binding-pot-command-spring` | Snapshots/versions et `UserContext` selon rôle → domain Pot/engine WRITE ; use cases Pot → engine ; policy → package de domain Pot ; dispatch Spring reste temporairement composition Command | B16 dispatch ancien jusqu'à D.15 | C,W,A ; expected version, Pot E2E | WRITE indépendant du core pour ses valeurs, policy sans POM propre |
| D.13 PLANNED | Command execution | `engine-consume-command`, `supra-consume-command` | `engine-command`, `locator-consumption-command`, runtime Command | Exécution/outcome/fencing → engine ; reload/issue → supra ; SQL PRIMARY ; engine → binding port, Consumption et Pot WRITE | B17 facade engine Command jusqu'à D.19 ; B18 locator jusqu'à D.23 | C,B,A ; CCR/WA, claim/finalize, late commit | E+B, version, outcome unique et CAS inchangés |
| D.14 PLANNED | Admission Command | `engine-admit-command` RENAME/REHOME | `orchestrator-command-admission`, `engine-command`, HTTP WRITE | Evidence/transaction/admission → engine ; auth contract/Binding port ; HTTP → moteur, jamais SQL | B19 facade admission jusqu'à D.20 | W,C,A ; 202 vs outcome, E+B | Admission compilée sans l'ancien orchestrator comme dépendance amont |
| D.15 PLANNED | Câblage Command | — | `binding-pot-command-spring`, runtime Command, `engine-pot-command` | Dispatch concret Spring → runtime Command ; engine WRITE expose interface ; retirer dépendance runtime → ancien binding POM | B16 retiré | C,A ; Spring context, double-bean check | Un seul dispatch actif, runtime racine de composition |
| D.16 PLANNED | Registration Request/execution | `engine-admit-registration`, `engine-consume-registration`, `supra-consume-registration` | `engine-registration`, runtime Registration | Admission distincte du consume ; request dans contrat ; locator/retry runtime → supra ; authority Binding via port | B1 retiré ; B20 facade Registration jusqu'à D.19 | W,R,B,A ; conflit Binding/orphan User | Success Registration sans User orphelin ; request ≠ execution |
| D.17 PLANNED | Registration Result | `engine-materialize-registration-result`, `engine-read-registration-result`, `supra-consume-registration-result` | `engine-registration`, runtime Registration Result, HTTP READ | Résultat direct immuable/owner E historique ; locator/retry → supra ; GET → read engine | B20 reste jusqu'à D.19 ; B21 runtime facade jusqu'à D.23 | RR,W,A ; 0..1/404/owner | Result indépendant du Binding courant et de ProjectionTask |
| D.18 PLANNED | Command Result et jalon Results | `engine-materialize-command-result`, `engine-read-command-result`, `supra-consume-command-result` | `engine-command-result`, runtime Command Result, HTTP READ | Materialize/GET séparés ; locator/retry → supra ; conserver imports historiques `CommandId`/`CommandOutcome` du consume jusqu'à TBD contract | B22 facade Command Result jusqu'à D.19 ; B23 runtime facade jusqu'à D.23 | CR,W,C,RR,A,F ; CCR 0..1/404/owner | Deux Results directs fonctionnent ; jalon global vert |
| D.19 PLANNED | Détruire agrégats métier | — | `engine-command`, `engine-registration`, `engine-command-result`, `engine-projection-task`, `engine-projection-pot`, `engine-projection-balance`, `engine-pot-command` | Retirer façades/types restants, basculer tous importeurs et supprimer POM vides ; aucune résolution de TBD-COMMAND-CONTRACT imposée | B10, B17, B20, B22 retirés | C,P,R,RR,CR,W,A ; dependency:tree | Aucun nouveau POM → ces agrégats ; graphe sans cycle |
| D.20 PLANNED | HTTP READ/WRITE | `supra-http-write` RENAME, `supra-http-read` RENAME/REHOME, `engine-read-pot` RENAME/REHOME | `supra-http-write-command`, `supra-http-read-query`, `supra-authentication-spring-security`, runtime Web | Controllers/DTO/404 vers deux supras ; provider Security concret → runtime Web ; résolution E→U historique via `ExternalIdentityResolverPort` existant déplacée du controller vers `engine-read-pot`, adapter PRIMARY câblé par Web ; aucun nouveau contrat TBD-E2U | B19 retiré ; B24 HTTP anciens noms jusqu'à D.23 ; B25 E→U historique jusqu'à résolution TBD | W,C,A ; auth/opaque 404, scan Spring | Supras sans persistence concrète, Web compose les deux |
| D.21 PLANNED | Cinq infra fermes | `infra-persistence-primary-jpa` RENAME/REHOME, `infra-persistence-read-jpa` RENAME/REHOME | `infra-persistence-jpa`, `infra-read-persistence`, `infra-tx-spring`, projection infra et runtimes | PRIMARY garde WRITE/discovery/loaders/Results ; READ garde Current Binding ; projection exacte et NetworkNT déjà D.09 ; tx → port ; repositories/config par rôle, migrations restent à leur emplacement sauf preuve d'ownership | B2,B3,B4,B12 retirés après bascule ; B26 anciens configs jusqu'à D.23 | W,C,E,P,B,R,RR,CR,L,A ; DB schéma courant, bean scan | Cinq adapters fermes, zéro double bean, pas de migration déplacée sans preuve |
| D.22 PLANNED | `engine-core` final | — | `engine-core`, `infra-persistence-primary-jpa`, consommateurs restants | Wrappers SQL legacy → PRIMARY ; exceptions/value résiduelles → propriétaire TARGET ; effacer forwarding et POM quand `rg` imports et dependency:tree sont vides | B5 retiré | C,E,P,B,W,R,A ; imports, dependency:tree | Aucun source/test de production ne dépend de `engine-core` |
| D.23 PLANNED | Runtimes comme compositions | — | huit runtimes fermes, locators Event/Binding/Command, `supra-consumption-worker`, anciens HTTP | Retirer policy/locator/failure des runtimes vers supras ; câbler poll, moteurs et infra ; enlever old locators/HTTP/POM poll, configs doublées | B7,B9,B14,B18,B21,B23,B24,B26 retirés | W,C,E,P,B,R,CR,RR,A ; contexts, dependency:tree | Chaque runtime racine ; aucun runtime → runtime ; aucun bridge hors B15/B25/TBD |
| D.24 PLANNED | Détruire frontières domaine anciennes | — | `domain-pot-policy`, `domain-pot-projection`, `domain-projection-balance`, `engine-core` résiduel | Package policy dans domain Pot ; définitions/valeurs/projector chez owners ; enlever forwarding packages | B5/B8 résiduels retirés | C,P,W,A ; imports interdits | Aucun POM domaine obsolète ; `domain-pot-policy` package-only |
| D.25 PLANNED | Règles d'architecture progressives | — (`architecture-tests` existant) | `architecture-tests` | Activer règle seulement quand son propriétaire a fini : domaine D.24, projector D.06, Consumption D.08, Current Binding D.10, supra/infra D.21, runtimes D.23 | Aucun | A ; tests négatifs de règles | Règles rejettent un import interdit témoin |
| D.26 PLANNED | Documentation des frontières | — | documents d'architecture et policy, aucun `app/` | Mettre à jour catalogue CURRENT, diagrammes, README des modules, ownership tests/fixtures, commandes de preuve et registre de bridges ; citer le commit d'implémentation | Aucun | `git diff --check`, revue des liens et POM réels | Docs reflètent reactor livré, aucune TARGET présentée comme CURRENT prématurément |
| D.27 PLANNED | Gate final ferme | — | reactor entier | Vérifier le sous-graphe des 54 POM fermes et ses 147 arcs attendus ; inventorier séparément les modules CURRENT LKV provisoires, zero cycle, guards et invariants ; aucune promotion LKV | B15 et B25 explicitement suivis en TBD, aucun bridge ferme | W,C,E,P,B,R,CR,RR,A,F ; schéma courant | Migration ferme prouvée, dette TBD séparée |

`D.09` est un jalon de projection et `D.18` un jalon des deux Results ; leur `F` satisfait la politique de restructuration large. `D.27` est le gate d'intégration. Si un lot intermédiaire devient lui-même une restructuration large impossible à borner, déclarer `F` requis **avant** de l'implémenter ; l'échec d'un slice seul ne suffit pas à l'exiger. Le slice L reste utilisé pour prouver que la mécanique LKV CURRENT fonctionne, sans promouvoir les trois noms TBD.

## 3. Détails des bascules et anti-cycles

**Fondations.** D.01 ne déplace que des types sans Spring ; l'ancien `authentication-contracts` est renommé après mise à jour de ses clients, et le provider JWT ne suit pas. D.02 déplace l'interface `TransactionRunner` sans son adapter, les contrats d'autorité Binding sans facts, et les ports de lecture/publication exacte sans SQL. Les dépendants prioritaires sont `engine-consumption`, `infra-tx-spring`, les engines d'admission/Task/Binding, les adapters PRIMARY/Projection et Web. Le risque est la visibilité de packages et le sens des imports : garder les anciens types forwarding **dans le legacy qui dépend du nouveau port**, jamais faire dépendre le nouveau port du legacy. D.03 déplace les valeurs communes requises par les engines ; `engine-core` continue d'exister pour les autres valeurs jusqu'à D.22. `PotGlobalVersion`, snapshots Pot/Expense et `UserContext` sont inspectés un par un : valeur métier dans `domain-pot`, orchestration WRITE dans `engine-write-pot`, SQL/wrapping technique dans PRIMARY. Aucun type possédé par `domain-consumption` ne porte `CommandId`, `ProjectionTask` ou un autre type de capacité.

**Consumption.** `domain-consumption` contient déjà key/slot/claim/lease/provenance ; `engine-consumption` contient déjà Acquire/Execute/Finalize et l'échec ; `orchestrator-consumption` garde les deux stratégies génériques Sequential et AcquireThenFinalize. D.04 retire leur import `engine-core` au profit du port transaction et des valeurs Consumption. D.05 renomme/rehome seulement polling, budget, wait, lifecycle depuis `supra-consumption-worker`. D.08 sort `ProjectionTaskConsumptionOrchestrator` de l'orchestrator générique ; sa présence transitoire est tolérée jusqu'alors, sans nouvel import de Task dans les nouveaux POM génériques. `supra-consumption-worker` est supprimé à D.23 après bascule des huit workers fermes **et** vérification du runtime LKV courant. La mécanique de retry générique peut rester au poll ; reload/discovery/classification propre à chaque capacité va dans son supra.

**Projector et Task.** D.06 suit le sens de données : définitions/ProjectionKey dans `domain-projection`, valeurs Pot/Balance dans `domain-pot`, calcul déterministe AUTH/READ_POT/BALANCES dans `projector-pot`. Les classes de `engine-projection-pot`, `engine-projection-balance` et `engine-read-projection` qui ouvrent une connexion, chargent l'historique, publient ou valident techniquement restent en dehors du projector. Test de pureté : POM projector ne dépend que de domain Pot/Projection ; `rg` des imports Spring/JPA/JDBC/SQL/runtime/infra et test de résultat déterministe à entrée identique. D.07 produit la Task à partir d'Event metadata-only ; D.08 consomme une Task, prépare l'input historique, projette en pur, valide via l'adapter NetworkNT, persiste via port et finalise fenced. Le poll vient de `orchestrator-poll-consumption`, la composition du runtime Task. La sélection du catalog reste en composition, pas dans Consumption générique. D.09 ferme lecture exacte `@V`, publication et revalidation, sans fallback.

**Collision de nom.** Le POM CURRENT `engine-read-projection` signifie Current Binding + LKV + reconstruction Pot. Le POM TARGET de ce nom signifie lecture exacte `@V` et provient du CURRENT `engine-projection-read`. Ne jamais brancher les deux au même artifactId. À D.10 extraire Binding et historique ; à D.11 déplacer les types/mécanique LKV non décidés vers une zone CURRENT existante (`engine-processing-event` et runtime LKV) comme hébergement provisoire, avec tests LKV inchangés. Supprimer l'ancien POM seulement quand son dernier client est basculé ; ensuite renommer `engine-projection-read` en `engine-read-projection`. Cette relocalisation technique est réversible et n'attribue aucun owner TARGET LKV. Si l'extraction de LKV révèle une dépendance inverse, déplacer une valeur/port commun dans un POM déjà ferme avant le renommage, ou garder le nom transitoire jusqu'à preuve ; ne jamais créer `engine-read-projection ↔ engine-processing-event`.

**Binding et Command.** D.10 sépare les facts autoritatifs append-only du Current Binding direct et de sa lecture. La matérialisation garantit révision contiguë, DETACHED tombstone et rejet de divergence au même R ; la lecture Current n'importe pas `port-binding-authority` et n'utilise aucune Task. D.13 conserve les transactions distinctes claim/effet, l'autorité E+B et expected version ; l'issue unique est publiée seulement par le CAS gagnant, y compris face au late commit. D.14 déplace admission/evidence sans faire remonter le SQL vers HTTP. D.15 replace le dispatch Spring dans la racine Command et retire le vieux module `binding-pot-command-spring`. S'il faut un adaptateur de compatibilité pour une API publique, il vit dans l'ancien module et délègue vers le nouveau ; aucun moteur TARGET ne dépend du vieux locator/dispatch.

**Registration et Results.** D.01 sort Request/Outcome neutres, D.16 dissocie admission de consume, D.17 matérialise et lit Result. Les deux transactions Registration doivent continuer d'arbitrer le conflit Binding avant qu'un User soit durablement orphelin. Command Result D.18 garde temporairement l'arc TARGET `engine-read-command-result → engine-consume-command` pour `CommandId`/`CommandOutcome`, tel que documenté ; il n'invente pas `contracts-command`. Les deux Results restent directs, `0..1`, immuables, owner E historique, 404 opaque et indépendants du Current Binding. Les locators de Result logés dans les runtimes migrent aux supras avant D.23.

**Infra.** `infra-persistence-primary-jpa` est principalement le renommage/rehome du cluster CURRENT `infra-persistence-jpa`, pas une création vide : repositories WRITE/authority, discovery Event/Binding/Command/Task, loaders historiques et stores Results y restent. `infra-persistence-read-jpa` renomme le store READ de `infra-read-persistence` après séparation Current Binding et hébergement LKV provisoire. `infra-persistence-projection-jpa` renomme le store exact de `infra-projection-persistence` ; `infra-projection-validation-networknt` renomme l'adapter NetworkNT de `infra-projection-json-schema`. `infra-tx-spring` reste le provider concret de `port-transaction`. Déplacer classes/repositories/config Spring seulement avec leur owner et un test de contexte évitant deux beans actifs. Ne déplacer aucune migration SQL par symétrie de nom : relever `Flyway`/scan, l'historique, le schéma propriétaire et les chemins de déploiement ; si une migration doit réellement bouger, ouvrir un lot de preuve DB distinct avec justification et gate historique selon la politique.

### Runtimes : sortie des responsabilités et composition finale

| Runtime ferme | Doit quitter le runtime | Reste et composition finale |
| --- | --- | --- |
| `runtime-web-api` | Provider contractuel d'auth, controllers métier, E→U dans controller, logique de lecture | Bootstrap et Security provider concret ; `supra-http-write`/`supra-http-read`, engines admit/read, cinq infra utiles et observabilité |
| `runtime-command-consumption-worker` | Policy/locator Command, dispatch métier Pot | Bootstrap et dispatch Spring concret ; `supra-consume-command`, poll, `engine-consume-command`/`engine-write-pot`, PRIMARY et tx |
| `runtime-event-consumption-worker` | Table ensure Task et policy Event, SQL discovery | Bootstrap/catalog propre au déploiement ; `supra-consume-event`, poll, `engine-produce-projection-task`, PRIMARY et tx |
| `runtime-task-consumption-worker` | Exécution Task, projectors, reload/issue | Bootstrap/catalog et sélection ; `supra-consume-projection-task`, poll, `engine-consume-projection-task`, projector, PRIMARY/Projection/READ et tx |
| `runtime-binding-consumption-worker` | Fact apply, locator/classification | Bootstrap ; `supra-consume-binding`, poll, `engine-materialize-current-binding`, PRIMARY/READ et tx |
| `runtime-registration-consumption-worker` | Locator/retry et décision métier Registration | Bootstrap ; `supra-consume-registration`, poll, `engine-consume-registration`, PRIMARY et tx |
| `runtime-command-result-consumption-worker` | Locator/retry, matérialisation | Bootstrap ; `supra-consume-command-result`, poll, `engine-materialize-command-result`, PRIMARY et tx |
| `runtime-registration-result-consumption-worker` | Locator/retry, matérialisation | Bootstrap ; `supra-consume-registration-result`, poll, `engine-materialize-registration-result`, PRIMARY et tx |

La règle « racine de composition seulement » autorise bootstrap, configuration, sélection de providers et lifecycle propre au processus ; elle exclut les décisions métier, SQL et locators spécialisés. Aucune dépendance runtime→runtime. Le neuvième runtime CURRENT LKV reste dans la piste TBD.

## 4. Registre des compatibilités temporaires

Une façade est définie dans **l'ancien** module, qui importe le nouveau ; le nouveau n'importe jamais l'ancien. Un bridge Spring doit être conditionnel/exclusif, avec preuve qu'un seul bean de chaque contrat est actif. Les étapes de retrait sont obligatoires ; D.27 refuse tout bridge ferme restant.

| Bridge | Mécanisme | Introduit | Retrait explicite |
| --- | --- | --- | --- |
| B1 | deprecated forwarding Request/Outcome Registration | D.01 | D.16 |
| B2 | deprecated forwarding `TransactionRunner` | D.02 | D.21 |
| B3 | facade Binding authority du vieux domaine | D.02 | D.21 |
| B4 | facade ports projection ancienne API | D.02 | D.21 |
| B5 | deprecated forwarding des valeurs `engine-core` | D.03 | D.22, dernier résidu D.24 |
| B6 | facade spécialisée Task dans orchestrator ancien | D.04 | D.08 |
| B7 | entrée polling ancienne pour workers | D.05 | D.23 |
| B8 | delegates anciens projectors | D.06 | D.08, résidu D.24 |
| B9 | facade ancien locator Event | D.07 | D.23 |
| B10 | facade ancien engine Task | D.08 | D.19 |
| B11 | identité Maven `engine-projection-read` transitoire | D.09 | D.11 |
| B12 | anciens noms d'adapters projection | D.09 | D.21 |
| B13 | anciennes APIs Binding dans `engine-read-projection` | D.10 | D.11 |
| B14 | ancien locator Binding | D.10 | D.23 |
| B15 | hébergement CURRENT provisoire LKV | D.11 | D.TBD-LKV après décision ; suivi séparé |
| B16 | ancien dispatch Spring Pot | D.12 | D.15 |
| B17 | facade ancien engine Command | D.13 | D.19 |
| B18 | ancien locator Command | D.13 | D.23 |
| B19 | facade ancienne admission Command | D.14 | D.20 |
| B20 | facade ancien engine Registration | D.16 | D.19 |
| B21 | anciennes glue runtime Registration Result | D.17 | D.23 |
| B22 | facade ancien engine Command Result | D.18 | D.19 |
| B23 | ancienne glue runtime Command Result | D.18 | D.23 |
| B24 | anciens noms HTTP | D.20 | D.23 |
| B25 | résolution E→U PRIMARY historique via `ExternalIdentityResolverPort` existant | D.20 | D.TBD-E2U après contrat décidé ; suivi séparé |
| B26 | anciennes configurations Spring infra | D.21 | D.23 |

## 5. Pistes ouvertes et règles de documentation

**D.TBD-LKV — BLOCKED_BY_TBD.** Les noms logiques `engine-advance-pot-watermark`, `supra-consume-lkv`, `runtime-latest-known-version-consumption-worker` ne sont **pas** trois POM approuvés. Garder le consumer CURRENT fonctionnel et autonome, documenter le bridge B15, puis décider owner fonctionnel, besoin aval et frontière physique avant tout changement Maven irréversible. Ce blocage n'empêche pas D.27 pour les 54 POM fermes : celui-ci doit publier l'écart CURRENT LKV et sa preuve L, sans le masquer dans le compte 54.

**D.TBD-E2U — BLOCKED_BY_TBD.** Aujourd'hui `PotQueryController` dans `supra-http-read-query` appelle `ExternalIdentityResolverPort` de `domain-user-identity`, implémenté par `JpaExternalIdentityResolverAdapter` dans `infra-persistence-jpa`; le Web compose les deux. D.20 déplace cet appel historique derrière `engine-read-pot` via le port **existant** `ExternalIdentityResolverPort` (B25), avec câblage de l’adapter PRIMARY par Web, sans prétendre fixer délai/révocation ou nouveau port. La décision ultérieure définira si ce port existant suffit ou quel port devra le remplacer ; les modules `engine-read-pot` et adapter E→U sont les futurs dépendants ; aucun POM ou signature n'est inventé ici.

**D.TBD-COMMAND-CONTRACT — BLOCKED_BY_TBD.** Les types `CommandId`/`CommandOutcome` sont dans le CURRENT `engine-command` et le Result les importe. D.18 conserve explicitement les deux imports historiques de `engine-read-command-result` vers `domain-user-identity` et `engine-consume-command` représentés dans la TARGET. La future décision pourra déplacer une valeur partagée, mais D.18/D.27 n'ajoutent pas `contracts-command` et ne bloquent pas la migration ferme.

**D.OPT — OPTIONAL_CLEANUP.** Après D.27 seulement : harmonisation de packages, réduction de dépendances transitives non nécessaires, renommage de fixtures et réduction des POM infra trop larges si une nouvelle décision l'autorise. Aucun de ces nettoyages ne conditionne la clôture et aucun ne change la TARGET sans révision documentaire explicite.

Pour chaque futur `Implement Step D.nn only`, produire **avant code** un mini-document/section de PR : (1) snapshot des POM et classes propriétaires actuels ; (2) déclaration de slices selon la politique avec la commande canonique exacte ; (3) delta de graphe `added/removed` et contrôle de cycle ; (4) liste des types/beans/repositories/migrations déplacés ; (5) bridge, dépendants et étape de retrait ; (6) invariant et test propriétaire ; (7) conditions de sortie, diff de documentation et preuve. Lors de la création ou modification d'un module : mettre à jour `app/pom.xml`, le POM enfant, l'owner des tests/fixtures, les règles `architecture-tests` **au moment correspondant**, les diagrammes et le catalogue CURRENT une fois le lot livré ; maintenir TARGET comme référence de destination, pas comme fait accompli. Les imports interdits doivent être testés au POM et au package. Un renommage de POM doit être un vrai rename/rehome dans le plan de commit, pas une création fictive suivie d'une copie. Le suivi du bridge ne peut pas être uniquement dans un commentaire de code : actualiser le registre ci-dessus ou son successeur d'exécution. Reporter à chaque lot : HEAD de départ, impact prévu/observé, commandes exécutées, franchissements imprévus, gate A, full reactor, DB, commit/push/divergence/arbre final.

## 6. Risques et preuves

| Risque | Steps | Mitigation | Preuve de sortie |
| --- | --- | --- | --- |
| Cycle temporaire `new → old → new` | D.01–D.23 | Port/valeur d'abord ; facade seulement dans legacy ; contrôle de graphe avant commit | `dependency:tree`, DFS Maven, A |
| Scan Spring cassé / beans doublés | D.05,D.15,D.20,D.21,D.23 | Configuration exclusive et scan par root explicite ; jamais deux providers actifs | Tests de contextes W/C/P/B et comptage beans |
| Transaction boundary drift | D.02,D.04,D.08,D.13,D.16,D.21 | Garder claim séparé de l'effet ; même TransactionRunner derrière port ; CAS final | WA/PCL/CCR, tests rollback/retry/late commit |
| Visibilité package-private | D.01–D.18 | Inventorier types non publics avant move ; façade minimale dans ancien package sans import inverse | Compilation du slice et tests ciblés |
| Ownership des fixtures | D.06–D.24 | Déplacer fixtures avec l'owner, ne pas faire dépendre un POM de tests d'un runtime | compilation tests et `dependency:tree` |
| Ownership migrations SQL | D.09,D.21 | Garder chemin historique ; preuve Flyway/schema avant tout déplacement | tests DB courants, historique seulement si changement d'upgrade |
| Ambiguïté runtime / composants | D.05,D.20,D.21,D.23 | Un seul wiring actif par interface ; supprimer ancienne config au retrait bridge | contexts de chaque runtime touché |
| Fuite capability-specific dans Consumption | D.04,D.08,D.23,D.25 | Task vers engine Task ; locators vers supras ; pas d'import métier générique | grep + règle A activée après D.08 |
| Dérive Current Binding / READ | D.10,D.11,D.21 | facts directs, port READ indépendant de WRITE, pas de Task | B,W, guards append-only/R/tombstone/404 |

L'activation des règles dans `architecture-tests` est progressive : D.06 pureté projector ; D.08 generic Consumption ; D.10 READ Current Binding ; D.21 supra sans infra concrète ; D.23 engines/runtimes ; D.24 domaine sans Spring/JPA ; D.25 rend ces règles bloquantes ensemble et vérifie qu'une mutation témoin échoue. `A` reste exécuté lors des changements Maven antérieurs selon la politique, avec les règles déjà valides seulement.

## 7. Matrices de traçabilité

La colonne « première utilisation » indique le premier consommateur de production à basculer dans le plan, pas le seul. La stratégie porte sur la responsabilité du POM TARGET ; `RENAME_IN_PLACE` signifie renommage si le nom diffère, conservation physique si le nom est déjà TARGET ; `MERGE_CURRENT` exige un vrai rehome, `CREATE_EMPTY_THEN_MOVE` est réservé à une coquille de compilation qui reçoit du code dans **le même lot**. Les gates utilisent les codes définis en §1. La matrice CURRENT couvre aussi les modules conservés : « conserve » signifie frontière maintenue mais imports revus ; « retire » signifie POM absent à D.27, hors LKV provisoire.

### Matrice de création des 54 POM TARGET fermes

| TARGET POM | Création / rehome | Stratégie | Source CURRENT principale | Premier utilisateur production | Gate final |
| --- | --- | --- | --- | --- | --- |
| `domain-authorization` | BASELINE | RENAME_IN_PLACE | même nom CURRENT | `engines de sa capacité` | D.24 A |
| `domain-event` | BASELINE | RENAME_IN_PLACE | même nom CURRENT | `engines de sa capacité` | D.24 A |
| `domain-user-identity` | D.02 (POM déjà présent) | RENAME_IN_PLACE | même nom CURRENT | `engines de sa capacité` | D.24 A |
| `domain-pot` | D.24 (POM déjà présent) | RENAME_IN_PLACE | même nom CURRENT | `engines de sa capacité` | D.24 A |
| `domain-consumption` | D.03 (POM déjà présent) | RENAME_IN_PLACE | même nom CURRENT | `engines de sa capacité` | D.24 A |
| `domain-projection` | D.06 (POM déjà présent) | RENAME_IN_PLACE | même nom CURRENT | `engines de sa capacité` | D.24 A |
| `contracts-authentication` | D.01 | RENAME_IN_PLACE | authentication-contracts | `engine-admit-command` | D.20 W+A |
| `contracts-registration` | D.01 | EXTRACT_FROM_CURRENT | engine-registration | `engine-admit-registration` | D.18 R+RR+A |
| `contracts-observability` | D.01 | RENAME_IN_PLACE | observability | `infra-persistence-primary-jpa` | D.23 A |
| `port-binding-authority` | D.02 | EXTRACT_FROM_CURRENT | domain-user-identity | `engine-consume-command` | D.21 B+C+A |
| `port-transaction` | D.02 | EXTRACT_FROM_CURRENT | engine-core | `engine-consumption` | D.22 A |
| `port-projection` | D.02 | RENAME_IN_PLACE | engine-projection-contracts | `engine-produce-projection-task` | D.09 P+W+A |
| `engine-consumption` | D.04 (POM déjà présent) | RENAME_IN_PLACE | engine-consumption | `orchestrator-consumption` | D.08 A |
| `orchestrator-consumption` | D.04 | SPLIT_CURRENT | orchestrator-consumption | `supra-consume-event` | D.08 A |
| `orchestrator-poll-consumption` | D.05 | RENAME_IN_PLACE | supra-consumption-worker | `runtime-command-consumption-worker` | D.23 A |
| `engine-consume-command` | D.13 | SPLIT_CURRENT | engine-command + locator-consumption-command | `supra-consume-command` | D.19 C+A |
| `engine-admit-command` | D.14 | RENAME_IN_PLACE | orchestrator-command-admission | `supra-http-write` | D.20 W+A |
| `engine-write-pot` | D.12 | RENAME_IN_PLACE | engine-pot-command | `engine-consume-command` | D.15 C+A |
| `engine-read-command-result` | D.18 | SPLIT_CURRENT | engine-command-result | `supra-http-read` | D.20 W+A |
| `engine-materialize-command-result` | D.18 | SPLIT_CURRENT | engine-command-result | `supra-consume-command-result` | D.18 CR+A |
| `engine-admit-registration` | D.16 | EXTRACT_FROM_CURRENT | engine-registration | `supra-http-write` | D.20 W+R+A |
| `engine-consume-registration` | D.16 | EXTRACT_FROM_CURRENT | engine-registration | `supra-consume-registration` | D.19 R+A |
| `engine-materialize-registration-result` | D.17 | EXTRACT_FROM_CURRENT | engine-registration | `supra-consume-registration-result` | D.18 RR+A |
| `engine-read-registration-result` | D.17 | EXTRACT_FROM_CURRENT | engine-registration | `supra-http-read` | D.20 W+A |
| `engine-produce-projection-task` | D.07 | EXTRACT_FROM_CURRENT | engine-processing-event + engine-projection-task + locator-consumption-event | `supra-consume-event` | D.09 E+P+A |
| `engine-consume-projection-task` | D.08 | SPLIT_CURRENT | engine-projection-task + orchestrator-consumption | `supra-consume-projection-task` | D.09 P+A |
| `engine-read-projection` | D.11 | RENAME_IN_PLACE | engine-projection-read ; ancien homonyme retiré | `engine-read-pot` | D.11 P+W+A |
| `projector-pot` | D.06 | EXTRACT_FROM_CURRENT | domain-pot-projection + domain-projection-balance + engine-projection-pot + engine-projection-balance | `engine-consume-projection-task` | D.06 P+A |
| `engine-read-pot` | D.20 | RENAME_IN_PLACE | engine-pot-read | `supra-http-read` | D.20 W+A |
| `engine-materialize-current-binding` | D.10 | EXTRACT_FROM_CURRENT | engine-read-projection + locator-consumption-binding | `supra-consume-binding` | D.10 B+A |
| `engine-read-current-binding` | D.10 | EXTRACT_FROM_CURRENT | engine-read-projection | `supra-http-read` | D.20 W+B+A |
| `supra-http-write` | D.20 | RENAME_IN_PLACE | supra-http-write-command | `runtime-web-api` | D.20 W+A |
| `supra-consume-command` | D.13 | RENAME_IN_PLACE | locator-consumption-command | `runtime-command-consumption-worker` | D.23 C+A |
| `supra-consume-event` | D.07 | EXTRACT_FROM_CURRENT | locator-consumption-event | `runtime-event-consumption-worker` | D.23 E+A |
| `supra-consume-binding` | D.10 | RENAME_IN_PLACE | locator-consumption-binding | `runtime-binding-consumption-worker` | D.23 B+A |
| `supra-consume-registration` | D.16 | EXTRACT_FROM_CURRENT | runtime-registration-consumption-worker | `runtime-registration-consumption-worker` | D.23 R+A |
| `infra-tx-spring` | D.02 (POM déjà présent) | RENAME_IN_PLACE | infra-tx-spring | `runtime-command-consumption-worker` | D.21 A |
| `infra-persistence-primary-jpa` | D.21 | MERGE_CURRENT | infra-persistence-jpa | `runtime-web-api` | D.21 W+C+E+P+B+R+RR+CR+A |
| `infra-persistence-projection-jpa` | D.09 | RENAME_IN_PLACE | infra-projection-persistence | `runtime-task-consumption-worker` | D.21 P+A |
| `infra-persistence-read-jpa` | D.21 | RENAME_IN_PLACE | infra-read-persistence | `runtime-binding-consumption-worker` | D.21 B+W+A |
| `runtime-web-api` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | runtime-web-api | `racine Web` | D.23 W+A |
| `runtime-command-consumption-worker` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | même nom CURRENT | `racine Command` | D.23 C+A |
| `runtime-event-consumption-worker` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | même nom CURRENT | `racine Event` | D.23 E+A |
| `runtime-task-consumption-worker` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | même nom CURRENT | `racine Task` | D.23 P+A |
| `runtime-binding-consumption-worker` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | même nom CURRENT | `racine Binding` | D.23 B+A |
| `runtime-command-result-consumption-worker` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | même nom CURRENT | `racine Command Result` | D.23 CR+A |
| `runtime-registration-consumption-worker` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | même nom CURRENT | `racine Registration` | D.23 R+A |
| `runtime-registration-result-consumption-worker` | BASELINE → D.23 composition | FINAL_COMPOSITION_ONLY | même nom CURRENT | `racine Registration Result` | D.23 RR+A |
| `architecture-tests` | BASELINE → D.25 règles | GATE_ONLY | architecture-tests | `gate global` | D.27 A |
| `supra-consume-projection-task` | D.08 | EXTRACT_FROM_CURRENT | engine-projection-task + runtime-task-consumption-worker | `runtime-task-consumption-worker` | D.23 P+A |
| `supra-consume-command-result` | D.18 | EXTRACT_FROM_CURRENT | runtime-command-result-consumption-worker | `runtime-command-result-consumption-worker` | D.23 CR+A |
| `supra-consume-registration-result` | D.17 | EXTRACT_FROM_CURRENT | runtime-registration-result-consumption-worker | `runtime-registration-result-consumption-worker` | D.23 RR+A |
| `infra-projection-validation-networknt` | D.09 | RENAME_IN_PLACE | infra-projection-json-schema | `runtime-task-consumption-worker` | D.21 P+A |
| `supra-http-read` | D.20 | RENAME_IN_PLACE | supra-http-read-query | `runtime-web-api` | D.20 W+A |

### Matrice de destruction / rétention des 51 modules CURRENT

| CURRENT module | Dernier step utilisateur / modification | Deletion / rename / retention | Mapping TARGET normatif |
| --- | --- | --- | --- |
| `domain-authorization` | D.24 | conserve | `domain-authorization` |
| `domain-event` | D.07 | conserve ; contrat Event | `domain-event` |
| `domain-user-identity` | D.02 | conserve ; ports authority extraits | `domain-user-identity`, `port-binding-authority` |
| `authentication-contracts` | D.01 | rename → contracts-authentication | `contracts-authentication` |
| `domain-pot` | D.24 | conserve ; reçoit policy/valeurs | `domain-pot` |
| `domain-pot-projection` | D.24 | retire après définitions/projector | `domain-projection`, `projector-pot` |
| `domain-projection-balance` | D.24 | retire après valeurs/projector | `projector-pot`, `domain-pot` |
| `domain-projection` | D.06 | conserve ; définitions intégrées | `domain-projection` |
| `domain-pot-policy` | D.24 | retire POM ; package domain-pot | `domain-pot` |
| `domain-consumption` | D.03 (POM déjà présent) | conserve ; reçoit valeurs core | `domain-consumption` |
| `engine-core` | D.22 | retire POM après dernier import ; forwarding résiduel D.24 | `port-transaction`, `domain-consumption`, `engine-write-pot`, `domain-pot`, `infra-persistence-primary-jpa` |
| `engine-consumption` | D.04 (POM déjà présent) | conserve ; transaction par port | `engine-consumption` |
| `engine-command` | D.19 | retire POM | `engine-consume-command`, `engine-admit-command`, `domain-event` |
| `engine-command-result` | D.19 | retire POM | `engine-materialize-command-result`, `engine-read-command-result`, `supra-consume-command-result`, `infra-persistence-primary-jpa` |
| `engine-registration` | D.19 | retire POM | `contracts-registration`, `engine-admit-registration`, `engine-consume-registration`, `engine-materialize-registration-result`, `engine-read-registration-result`, `supra-consume-registration`, `supra-consume-registration-result`, `infra-persistence-primary-jpa` |
| `engine-processing-event` | D.TBD-LKV | retient provisoirement LKV ; retrait/rename selon décision | `engine-produce-projection-task`, `domain-event` ; — (TBD_PHYSICAL LKV) |
| `engine-pot-command` | D.19 | rename/rehome → engine-write-pot | `engine-write-pot`, `domain-pot` |
| `engine-projection-contracts` | D.21 | rename/rehome → port-projection ; facade retirée | `port-projection` |
| `engine-projection-read` | D.11 | rename/rehome → engine-read-projection | `engine-read-projection` |
| `engine-projection-task` | D.19 | retire POM | `engine-produce-projection-task`, `engine-consume-projection-task`, `supra-consume-projection-task`, `port-projection` |
| `engine-pot-read` | D.20 | rename/rehome → engine-read-pot | `engine-read-pot`, `domain-pot` |
| `engine-projection-balance` | D.19 | retire POM après projector/loader | `projector-pot`, `engine-consume-projection-task`, `port-projection`, `infra-persistence-primary-jpa` |
| `engine-projection-pot` | D.19 | retire POM après projector/loader | `projector-pot`, `engine-consume-projection-task`, `port-projection`, `infra-persistence-primary-jpa` |
| `engine-read-projection` | D.11 | retire ancien POM homonyme ; LKV hébergé CURRENT | `engine-materialize-current-binding`, `engine-read-current-binding`, `port-projection`, `projector-pot` ; — (TBD_PHYSICAL LKV) |
| `observability` | D.01 | rename → contracts-observability | `contracts-observability` |
| `infra-tx-spring` | D.21 | conserve ; implémente port-transaction | `infra-tx-spring` |
| `infra-persistence-jpa` | D.21 | rename/rehome → infra-persistence-primary-jpa | `infra-persistence-primary-jpa` |
| `infra-projection-persistence` | D.09 | rename → infra-persistence-projection-jpa | `infra-persistence-projection-jpa` |
| `infra-read-persistence` | D.21 | rename/rehome → infra-persistence-read-jpa ; LKV provisoire | `infra-persistence-read-jpa` ; — (TBD_PHYSICAL LKV) |
| `infra-projection-json-schema` | D.09 | rename → infra-projection-validation-networknt | `infra-projection-validation-networknt` |
| `orchestrator-consumption` | D.08 | conserve ; spécialisation Task sortie | `orchestrator-consumption`, `engine-consume-projection-task` |
| `orchestrator-command-admission` | D.20 | rename/rehome → engine-admit-command ; facade retirée | `engine-admit-command`, `port-binding-authority` |
| `supra-consumption-worker` | D.23 | rename/rehome → orchestrator-poll-consumption ; retirer ancien POM | `orchestrator-poll-consumption` |
| `locator-consumption-event` | D.23 | retire après supra Event | `engine-produce-projection-task`, `supra-consume-event`, `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` |
| `locator-consumption-latest-known-version` | D.TBD-LKV | conserve CURRENT provisoire ; retrait selon décision | `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` ; — (TBD_PHYSICAL LKV) |
| `locator-consumption-binding` | D.23 | retire après supra Binding | `engine-materialize-current-binding`, `supra-consume-binding`, `infra-persistence-primary-jpa`, `infra-persistence-read-jpa`, `orchestrator-poll-consumption` |
| `locator-consumption-command` | D.23 | retire après supra Command | `engine-consume-command`, `supra-consume-command`, `infra-persistence-primary-jpa`, `orchestrator-poll-consumption` |
| `binding-pot-command-spring` | D.15 | retire ; wiring dans runtime Command | `runtime-command-consumption-worker`, `engine-write-pot`, `domain-pot` |
| `supra-http-write-command` | D.23 | rename/rehome → supra-http-write ; facade retirée | `supra-http-write`, `engine-admit-command` |
| `supra-http-read-query` | D.23 | rename/rehome → supra-http-read ; facade retirée | `supra-http-read`, `engine-read-pot`, `engine-read-command-result`, `engine-read-current-binding`, `domain-user-identity` |
| `supra-authentication-spring-security` | D.20 | retire ; provider dans runtime Web | `contracts-authentication`, `runtime-web-api` |
| `runtime-web-api` | D.23 | conserve ; composition seulement | `runtime-web-api`, `supra-http-write`, `supra-http-read`, `engine-admit-command`, `engine-admit-registration`, `engine-read-pot`, `engine-read-command-result`, `engine-read-registration-result`, `engine-read-current-binding` |
| `runtime-event-consumption-worker` | D.23 | conserve ; composition seulement | `runtime-event-consumption-worker`, `supra-consume-event`, `engine-produce-projection-task`, `orchestrator-poll-consumption` |
| `runtime-command-result-consumption-worker` | D.23 | conserve ; composition seulement | `runtime-command-result-consumption-worker`, `supra-consume-command-result`, `engine-materialize-command-result`, `orchestrator-poll-consumption` |
| `runtime-registration-result-consumption-worker` | D.23 | conserve ; composition seulement | `runtime-registration-result-consumption-worker`, `supra-consume-registration-result`, `engine-materialize-registration-result`, `orchestrator-poll-consumption` |
| `runtime-registration-consumption-worker` | D.23 | conserve ; composition seulement | `runtime-registration-consumption-worker`, `supra-consume-registration`, `engine-consume-registration`, `orchestrator-poll-consumption` |
| `runtime-latest-known-version-consumption-worker` | D.TBD-LKV | conserve CURRENT provisoire ; physique TARGET indécis | `orchestrator-poll-consumption` ; — (TBD_PHYSICAL LKV) |
| `runtime-binding-consumption-worker` | D.23 | conserve ; composition seulement | `runtime-binding-consumption-worker`, `supra-consume-binding`, `engine-materialize-current-binding`, `orchestrator-poll-consumption` |
| `runtime-task-consumption-worker` | D.23 | conserve ; composition seulement | `runtime-task-consumption-worker`, `supra-consume-projection-task`, `engine-consume-projection-task`, `projector-pot`, `orchestrator-poll-consumption` |
| `runtime-command-consumption-worker` | D.23 | conserve ; composition seulement | `runtime-command-consumption-worker`, `supra-consume-command`, `engine-consume-command`, `engine-write-pot`, `orchestrator-poll-consumption` |
| `architecture-tests` | D.25 | conserve ; règles progressives | `architecture-tests` |

**Contrôle avant chaque suppression :** rechercher imports Java, dépendances POM de production et de test, références Spring scan, fixtures et chemins de migration ; aucun `rm` fondé sur la seule absence de dépendant direct. Les modules CURRENT LKV marqués D.TBD-LKV sont conservés comme dette explicite et ne deviennent pas des POM fermes par cette table.
