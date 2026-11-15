# Audit final indépendant de l'architecture Modularity

Date d'audit : 2026-10-05

Commit audité : `109a334f9f832079ee6d8f91c9e3d89aba3b092b`

Nature : **AUDIT ONLY** — aucun code, POM, test, SQL, guard ou statut de dette n'a été modifié.

## 1. Verdict exécutif

**FAIL.** La tentative de falsification réussit. Les compteurs Maven annoncés sont reproductibles et les deux commandes Maven obligatoires passent, mais cela ne suffit pas à établir que CURRENT a atteint TARGET :

1. `domain-consumption`, présenté comme final, livre encore une couche de compatibilité explicitement legacy et `forRemoval` (`ClaimToken`, `Claim.compatibilityKey`, fabriques et transitions dépréciées, constructeur `ConsumptionKey` « awaiting migration »). Le bridge n'est donc pas à zéro : il a été incorporé dans un POM TARGET.
2. `runtime-event-consumption-worker` déclare en scope production `pocoma-engine-consume-command`, alors que cette dépendance n'est référencée que par un test de policy. L'arc Event → Command est absent du TARGET décidé et pollue le graphe de production.
3. Le test de journey LKV intitulé hors ordre insère V10,V12,V11, mais la discovery SQL trie par version et les consomme V10,V11,V12. Il prouve bien le résultat système demandé face à des arrivées durables hors ordre ; son nom suggère toutefois à tort une application hors ordre. La robustesse de l'upsert face à une application inférieure après une supérieure est prouvée séparément au niveau JDBC.

Ces trois faits réfutent l'affirmation « sans frontière legacy/provisoire cachée » et empêchent la clôture demandée. Aucun défaut n'a été corrigé pendant l'audit.

## 2. Baseline et méthode

La baseline a été contrôlée avant l'inspection :

| Contrôle | Résultat |
|---|---|
| `git fetch origin` | succès |
| `git status --short` | vide |
| `git rev-parse HEAD` | `109a334f9f832079ee6d8f91c9e3d89aba3b092b` |
| `git rev-parse origin/v2-make-it-pull` | même SHA |
| `git rev-list --left-right --count origin/v2-make-it-pull...HEAD` | `0 0` |

La politique `docs/testing/Reactor_Verification_Policy.md` a été lue avant toute vérification. Tranches déclarées : inspection des six responsabilités WEB, COMMAND, EVENT, PROJECTION, BINDING et LKV ; gate architecture global exigé par la nature finale de l'audit ; reactor complet exigé explicitement par la mission ; aucune modification SQL et aucun replay historique supplémentaire. Il n'y a eu aucun franchissement de tranche provoqué par une modification, puisque l'audit n'a modifié que ce rapport.

La vérité CURRENT a été reconstruite à partir de `app/pom.xml`, des 58 POM enfants, des dépendances XML, sources/packages/imports, configurations Spring, tests, requêtes et migrations. Les rapports WP1–WP6 ont servi de provenance et non de preuve suffisante.

## 3. Reactor recalculé indépendamment

Le calcul XML n'utilise pas les nombres du rapport WP6. Une dépendance interne directe est ici une dépendance Maven vers un artifact enfant, hors scopes `test` et `provided`.

| Mesure | Audit | WP6 | Écart |
|---|---:|---:|---:|
| POM enfants | 58 | 58 | 0 |
| POM production | 57 | 57 | 0 |
| POM verification/test | 1 (`architecture-tests`) | 1 implicite | 0 |
| Arcs internes directs de production | 234 | 234 | 0 |
| Cycles | 0 | 0 | 0 |
| Dépendances internes manquantes | 0 | 0 | 0 |

Le graphe est techniquement acyclique et résolu. Ce résultat ne qualifie pas la légitimité architecturale de chaque arc ; l'arc Event → Command décrit en B-02 en est le contre-exemple.

## 4. Réconciliation WP5 → WP6

La comparaison du parent WP6 `e9c55573bcc701b0aced0f6a575ad4b59675ddc4` avec le commit audité donne :

`61 WP5 + 2 créations - 5 suppressions = 58 enfants WP6`.

| Module/responsabilité WP5 | Action WP6 | Owner final |
|---|---|---|
| 56 autres enfants WP5 | conservés | module TARGET correspondant |
| `engine-materialize-latest-known-version` | créé | matérialisation sémantique LKV |
| `supra-consume-lkv` | créé | adaptation Event durable → moteur LKV |
| `engine-processing-event` | supprimé | `engine-produce-projection-task` et supras/runtimes dédiés |
| `locator-consumption-latest-known-version` | supprimé | `supra-consume-lkv` + infra PRIMARY |
| `infra-read-persistence` | supprimé | `infra-persistence-read-jdbc` |
| `domain-pot-projection` | supprimé | `domain-projection` / `projector-pot` selon responsabilité |
| `domain-projection-balance` | supprimé | `domain-projection` / `projector-pot` |

Les anciens shells de domaine et les autres POM legacy annoncés supprimés ne sont plus des modules suivis. Des répertoires `target/` ignorés peuvent subsister localement ; ils ne constituent pas CURRENT, mais ont contaminé le comptage de tests du rapport WP6 (M-02).

## 5. Inventaire exhaustif des 57 POM de production

`Up` désigne les principaux appelants ; `down` les principales dépendances/capabilities appelées. Tous les POM ont une justification de frontière plausible (invariant, contrat, port, capability, pureté, orchestration, adaptation, technique ou composition). L'audit ne trouve pas de POM totalement gratuit ; il trouve en revanche un arc de production injustifié dans un runtime.

| Module | Famille | Responsabilité / raison de la frontière | Relations principales | Statut TARGET |
|---|---|---|---|---|
| `domain-authorization` | domain | invariants d'autorisation purs | up engines read/write ; down aucun framework | conforme |
| `domain-event` | domain | événements métier stables | up Command, projection, infra ; down Pot | conforme |
| `domain-user-identity` | domain | identités E/U/Binding | up contracts/engines/infra ; down pur | conforme |
| `domain-pot` | domain | agrégat Pot, invariants et policies | up write/projector/results ; down authorization/user | conforme |
| `domain-projection` | domain | valeurs/identités de projection exacte | up engines/projector/infra ; down Pot | conforme |
| `domain-consumption` | domain | primitives génériques slot/claim/lifecycle | up engine Consumption/infra | **non conforme : compatibilité legacy embarquée** |
| `contracts-authentication` | contracts | principal/evidence provider-neutral | up HTTP/admission/infra | conforme |
| `contracts-command` | contracts | intake Command durable stable | up admit/consume/infra/HTTP/results | conforme |
| `contracts-registration` | contracts | `RegistrationRequest` durable | up admit/consume/infra/results | conforme |
| `contracts-observability` | contracts | contrat transverse de métriques | up runtimes/adapters | conforme |
| `port-transaction` | port | démarcation transactionnelle | up engines/orchestrators ; down impl Spring | conforme |
| `port-binding-authority` | port | autorité Binding abstraite | up Command/Registration ; down PRIMARY | conforme |
| `port-projection` | port | lecture/écriture projection abstraite | up projection/read ; down projection JDBC | conforme |
| `engine-consumption` | engine | acquire/execute/finalize génériques | up supras/orchestrators/infra ; down domain/tx | conforme, hors API domain legacy |
| `engine-admit-command` | engine | insertion durable Command | up HTTP WRITE ; down contract/tx | conforme |
| `engine-consume-command` | engine | reload, fence E+B, dispatch, `CommandOutcome` | up supra/results/infra ; down binding/Consumption/write | conforme ; commentaire B16 mineur |
| `engine-write-pot` | engine | use cases WRITE Pot | up Command/runtime ; down domain/ports/event | conforme |
| `engine-admit-registration` | engine | admission Registration | up HTTP ; down contract/tx | conforme |
| `engine-consume-registration` | engine | exécution et `RegistrationOutcome` | up supra/result ; down binding/Consumption | conforme |
| `engine-materialize-command-result` | engine | Outcome → Result immuable | up supra result ; down consume/read result | conforme |
| `engine-read-command-result` | engine | store et GET Result owner E | up HTTP/materializer ; down contracts/identity | conforme |
| `engine-materialize-registration-result` | engine | Outcome → Result immuable | up supra result ; down consume/read result | conforme |
| `engine-read-registration-result` | engine | store et GET Result owner E | up HTTP/materializer | conforme |
| `engine-produce-projection-task` | engine | Event → `ProjectionTask` | up supra Event ; down event/projection/tx | conforme |
| `engine-consume-projection-task` | engine | exécution d'une task exacte | up supra task ; down projector/projection | conforme |
| `engine-read-projection` | engine | lecture exacte @V | up read Pot ; down port projection | conforme |
| `engine-read-current-binding` | engine | lecture convergente E→U | up GET Pot ; down READ store | conforme |
| `engine-read-pot` | engine | AUTH@V puis READ_POT@V | up HTTP ; down current binding/read projection | conforme |
| `engine-materialize-current-binding` | engine | règle C2 sur révision Binding | up supra Binding ; down READ port | conforme |
| `engine-materialize-latest-known-version` | engine | max durablement consommé | up supra LKV ; down READ port | conforme |
| `projector-pot` | projector | Event history → projection désirée pure | up consume task ; down domain projection | conforme |
| `orchestrator-consumption` | orchestrator | une tentative générique | up supras/tests ; down engine Consumption | conforme |
| `orchestrator-poll-consumption` | orchestrator | répétition/polling/lifecycle | up runtimes ; down one-treatment orchestration | conforme |
| `supra-consume-command` | supra | candidate/reload Command → engine | up runtime Command ; down consume Command/orchestrator | conforme |
| `supra-consume-command-result` | supra | terminal Command → materializer | up runtime Result ; down materialize/orchestrator | conforme |
| `supra-consume-registration` | supra | request Registration → engine | up runtime Registration ; down consume/orchestrator | conforme |
| `supra-consume-registration-result` | supra | terminal Registration → materializer | up runtime Result ; down materialize/orchestrator | conforme |
| `supra-consume-event` | supra | Event durable → ProjectionTask | up runtime Event ; down produce task/orchestrator | conforme |
| `supra-consume-projection-task` | supra | task durable → projector/engine | up runtime Task ; down consume task/orchestrator | conforme |
| `supra-consume-binding` | supra | Binding fact → Current Binding | up runtime Binding ; down materializer/orchestrator | conforme |
| `supra-consume-lkv` | supra | Event durable → LKV | up runtime LKV ; down LKV engine/orchestrator | conforme |
| `supra-http-write` | supra | HTTP concret → admission engines | up web runtime ; down admit Command/Registration | conforme |
| `supra-http-read` | supra | HTTP concret → read engines | up web runtime ; down Pot/Results | conforme |
| `infra-tx-spring` | infra | implémentation Spring du port transaction | up runtimes/engines | conforme |
| `infra-persistence-primary-jpa` | infra | PRIMARY JPA/JDBC, facts et discovery | up runtimes/supras ; down ports/engines | conforme |
| `infra-persistence-projection-jdbc` | infra | store exact de projection | up task/web runtimes ; down projection port | conforme |
| `infra-persistence-read-jdbc` | infra | stores READ Current Binding/LKV/Results | up runtimes/read engines | conforme |
| `infra-projection-validation-networknt` | infra | validation technique de projection | up task runtime ; down projection port | conforme |
| `runtime-web-api` | runtime | bootstrap HTTP unique read+write | down `supra-http-write/read` + adapters | conforme |
| `runtime-event-consumption-worker` | runtime | compose pipeline Event→Task | down poll/supra Event/infra ; **arc Command indu** | **non conforme B-02** |
| `runtime-task-consumption-worker` | runtime | compose pipeline Task→exact projection | down poll/supra task/projector/infra | conforme |
| `runtime-command-consumption-worker` | runtime | compose consommation Command | down poll/supra Command/write/infra | conforme |
| `runtime-registration-consumption-worker` | runtime | compose consommation Registration | down poll/supra Registration/infra | conforme |
| `runtime-command-result-consumption-worker` | runtime | compose Command Result | down poll/supra result/READ+PRIMARY | conforme |
| `runtime-registration-result-consumption-worker` | runtime | compose Registration Result | down poll/supra result/READ+PRIMARY | conforme |
| `runtime-binding-consumption-worker` | runtime | compose Binding→CURRENT_BINDING | down poll/supra Binding/READ+PRIMARY | conforme |
| `runtime-latest-known-version-consumption-worker` | runtime | compose Event→LKV | down poll/supra LKV/READ+PRIMARY | conforme ; libellé de test C2 imprécis |

Les préfixes correspondent globalement aux rôles observés : domaines sans framework, projector pur, engines sémantiques, orchestrateurs génériques, supras adaptateurs, infra techniques et runtimes de composition. Les exceptions significatives sont la compatibilité de migration dans un `domain-*` et l'arc cross-capability du runtime Event.

## 6. Graphe, règles de direction et TARGET → legacy

Les scans POM/imports confirment :

- aucun domain → Spring/JPA/runtime/infra ;
- aucun projector → SQL/Spring/runtime/infra/engine ;
- aucun engine → runtime ;
- aucun supra → infra concrète ;
- aucun runtime → runtime ;
- Consumption générique ne dépend d'aucune capability métier concrète ;
- aucun cycle ni artifact interne manquant ;
- LKV ne dépend ni de `ProjectionTask` ni d'une lecture PRIMARY Pot ; ProjectionTask ne dépend pas de LKV ;
- les exact projections ne dépendent ni de LKV ni de CURRENT_BINDING.

Le compteur **TARGET → legacy = 0** n'est vrai qu'au niveau des noms de modules. En définissant indépendamment legacy comme « API explicitement marquée legacy/compatibility/awaiting migration et `forRemoval` », CURRENT en contient dans `domain-consumption`. Le legacy n'a donc pas entièrement disparu ; une partie a été renommée/embarquée dans TARGET. Il reste 0 POM legacy/provisoire autonome, mais au moins un bridge de code de production.

L'arc direct `runtime-event-consumption-worker → engine-consume-command` est également suspect sans créer de cycle : aucune source `main` du runtime ne l'utilise ; seul `PocomaProjectionMaterializationPolicyTest` importe `CommandTerminalEventTypes`. Le TARGET Event décidé ne contient pas cet arc.

## 7. Résidus historiques et bridges

Les noms demandés (`engine-core`, `engine-command`, `engine-command-result`, `engine-registration`, `engine-projection-task`, `engine-projection-pot`, `engine-projection-balance`, `engine-processing-event`, les quatre `locator-consumption-*`, `infra-read-persistence`, `infra-persistence-jpa`, `orchestrator-command-admission`, `binding-pot-command-spring`) ne sont plus des POM actifs. Leurs occurrences restantes dans les rapports historiques et dans les assertions d'absence des tests d'architecture sont légitimes.

Le registre B1…B26 se réconcilie ainsi :

| Bridges | Final attendu / constat |
|---|---|
| B1–B5, B7 | non créés ou évités ; aucune façade active |
| B6, B11, B13 | retirés en WP2 |
| B16 | ancien binding Spring retiré ; traducteur final conservé, mais commentaire production « Temporary B16 bridge » obsolète |
| B17–B20 | retirés/démolis entre WP3 et WP5 |
| B21–B24 | retirés en WP4 |
| B25 | retiré au checkpoint post-WP4 |
| B8–B10, B12, B14, B26 | retirés en WP5 |
| B15 | retiré en WP6 avec `infra-read-persistence` |
| Compatibilité Consumption non enregistrée | **survit** : `ClaimToken`, `Claim.compatibilityKey`, transitions/factories legacy, constructeur legacy de `ConsumptionKey` |

Le ledger documenté peut donc afficher B1–B26 retirés tout en manquant un bridge réel. `docs/architecture/consumption-transactional-execution.md`, présenté comme documentation active, indique encore que `ClaimToken` sera supprimé lorsque ses consommateurs auront migré ; le code confirme que ce travail n'est pas achevé.

## 8. Chaînes fonctionnelles

### Command

La chaîne `contracts-command → engine-admit-command / engine-consume-command → engine-write-pot → supra-consume-command → runtime-command-consumption-worker` est réelle. L'intake durable est contract-owned ; insertion et reload ont des ports distincts ; `CommandOutcome` reste execution-owned ; E+B est vérifié lors de l'exécution ; admission ne dépend plus de consumption. L'ancien agrégat Maven `engine-command` n'est pas revenu. Le mapper B16 est une adaptation sémantique nécessaire, mais ses commentaires « Temporary bridge » et « legacy service » sont faux au regard du statut final.

### Registration

`RegistrationRequest` est dans `contracts-registration`, `RegistrationOutcome` dans `engine-consume-registration`; admission, consumption, supra et runtime sont séparés. Le journey Postgres vérifie qu'un conflit Binding ne laisse pas d'User orphelin. Aucun ancien POM Registration n'est actif.

### Direct Results

Command Result et Registration Result sont immuables, `0..1`, possédés par l'ExternalIdentity historique E, renvoient un 404 opaque au non-owner et ne dépendent ni de CURRENT_BINDING ni de ProjectionTask. Les couples `engine-materialize-*`, `engine-read-*`, `supra-consume-*`, `runtime-*-result-consumption-worker` sont distincts de l'Outcome d'exécution.

### Exact projections

La chaîne Event → `engine-produce-projection-task` → `ProjectionTask` → `engine-consume-projection-task` → `projector-pot` → projection exacte @V est présente. AUTH@V, READ_POT@V et POT_BALANCES@V sont lus à version exacte. Ces projections restent séparées des deux indexes convergents.

### CURRENT_BINDING

La chaîne Binding authority → fact immuable → discovery/claim/reload → `supra-consume-binding` → `engine-materialize-current-binding` → READ est réelle. Le store applique C2 : révision supérieure `applied`, inférieure `stale`, égale identique `duplicate`, égale divergente `invariant failure`. Aucun arc vers ProjectionTask.

### LKV

La chaîne Event durable → discovery/claim indépendante → `supra-consume-lkv` → `engine-materialize-latest-known-version` → READ JDBC est réelle. La valeur stockée est le maximum des versions sources rechargées et consommées avec succès. Le partage de la source Event avec la projection est correctement indépendant. La fonction de store résiste à une observation inférieure après une supérieure et à la concurrence. En revanche, le journey runtime V10,V12,V11 ne consomme pas cet ordre : `JpaLatestKnownVersionEventDiscoveryRepository` exécute `order by event.version, event.created_at, ...`, donc le moteur voit 10,11,12. La propriété C2 est prouvée au niveau store, pas au niveau du parcours annoncé.

### Famille convergente et Consumption

La documentation finale rapproche correctement CURRENT_BINDING et LKV comme spécialisations C2. Aucun POM artificiel commun n'a été créé ; le socle partagé reste Consumption. `domain-consumption`, `engine-consumption`, `orchestrator-consumption` et `orchestrator-poll-consumption` séparent primitives, une tentative et polling répété ; aucun Universal Processor métier n'apparaît. Cette conclusion ne blanchit pas les API de compatibilité restées dans le domaine.

### Supras et runtimes

Les dix supras adaptent chacun une capability identifiée, appellent engines/orchestrateurs et leurs ports, sans import d'infra concrète ni policy métier majeure. Les neuf runtimes sont des racines Spring de wiring, sélection d'adapters, lifecycle, activation, poll et métriques. Aucun runtime ne dépend d'un autre. `runtime-web-api` reste l'unique runtime Web et compose `supra-http-write` avec `supra-http-read`. L'exception est l'arc Maven inutile Event runtime → Command engine, non une policy Java runtime.

### GET Pot

Le code suit E → `engine-read-current-binding` → U → AUTH@V → READ_POT@V. `engine-read-pot` ne dépend ni de PRIMARY ni de `port-binding-authority`; l'ancien resolver E→U PRIMARY n'est pas revenu.

### Infra

Les cinq frontières sont cohérentes : transaction Spring, PRIMARY JPA, projection JDBC, READ JDBC, validation NetworkNT. La responsabilité précède la technologie et les adapters sont placés selon le store/port servi. Le partage éventuel d'une datasource n'est pas utilisé comme argument de fusion.

## 9. SQL et migrations

Recalcul depuis le parent WP6 :

| Population | Avant | Après | Conclusion |
|---|---:|---:|---|
| SQL sous `app/` | 44 | 44 | stable |
| migrations PRIMARY | 29 | 29 | V1–V29, stable |
| migrations READ | 14 | 14 | V1–V14, déplacées |
| SQL de test sous `app/` | 1 | 1 | déplacé avec son owner |
| SQL opérationnels sous `docs/` | 3 | 3 | inchangés |
| total repository | 47 | 47 | stable |

Le diff WP6 contient 15 renames SQL `R100` : les 14 migrations READ annoncées et un SQL de test PRIMARY. Pour chacun, `numstat` vaut `0/0`; les blobs avant/après sont identiques. Bilan contenu : ajouté 0, supprimé 0, modifié 0, path-only moves 15. Les versions et noms sont conservés, sans collision dans leurs emplacements Flyway séparés, sans migration oubliée. Les suites Postgres ont validé 29 migrations PRIMARY et 14 READ. Conclusion : **SQL/migrations sémantiquement préservés**.

## 10. Guards architecturaux

Les 20 classes / 119 tests du module `architecture-tests` couvrent notamment : graph acyclique, modules attendus/absents, familles interdites, ownership post-WP4, frontières Command/Registration, absence de schéma/base legacy, C2 Binding, séparations LKV/ProjectionTask, GET Pot et journeys Postgres.

| Invariant | Guard / preuve | Qualification |
|---|---|---|
| cycles/missing/modules retirés | analyse XML `Wp5TargetTopologyTest` et tests PCL | adéquat |
| domain/projector/engine/supra/runtime directions | ArchUnit + graphe | adéquat pour règles de famille |
| Direct Results indépendants | source/dependency guards + E2E | adéquat |
| GET Pot via Current Binding | dependency/source guards + E2E | adéquat |
| C2 Current Binding | tests Postgres dédiés | adéquat |
| LKV séparé de ProjectionTask | graph/source + journey E | adéquat |
| absence de compatibilité legacy dans les APIs TARGET | aucun ; le guard ne prohibe que l'usage de `ClaimToken` par certains adapters | **BLOCKING car défaut réel non détecté** |
| allowlist exacte des dépendances de chaque runtime | aucune ; les règles larges autorisent Event → Command | **BLOCKING car défaut réel non détecté** |
| résultat C2 pour arrivées V10,V12,V11 | journey runtime ; robustesse high→low prouvée séparément par le store JDBC | adéquat, nom de test imprécis |
| commentaire B16/fraicheur documentaire | documentaire | MINOR |

Le gate qui passe prouve donc les invariants qu'il encode, pas l'absence des deux contre-preuves structurelles.

## 11. Final Architectural Journeys et qualité E2E

Les tests inspectés utilisent de vrais contextes Spring, PostgreSQL/Testcontainers, migrations et workers/orchestrateurs. Ils évitent les mocks sur les frontières revendiquées ; les attentes asynchrones sont bornées et diagnostiquées. Les boucles de polling courtes ne servent pas de substitut arbitraire à une assertion de persistance.

| Journey | Ce qui est réellement traversé | Verdict de preuve |
|---|---|---|
| A Registration → Binding → CURRENT_BINDING | HTTP, admission/consumption Registration, fact Binding, worker Binding, READ | valide |
| B Command → Event → Task → exact projection → GET Pot | HTTP, durable Command, worker Command, Event, worker Event, Task, worker Task, READ autorisée | valide |
| C Command → Command Result → owner E | workers réels, PRIMARY+READ, détachement/rebind | valide |
| D Registration → Registration Result → owner E | workers réels, PRIMARY+READ | valide |
| E même Event → pipelines LKV et Projection indépendants | LKV exécuté sans Task puis pipeline Projection | valide |
| C2 V10,V12,V11 → 12 | arrivées durables 10,12,11, discovery normalisée 10,11,12, résultat 12 ; store testé high→low | valide par preuves composées ; nom du test runtime imprécis |

Les journeys A+B composés prouvent bien le parcours utilisateur externe registration → identité/binding → write → traitements async → read model → lecture autorisée. Le résultat C2 est également prouvé, en distinguant l'ordre d'arrivée durable de l'ordre d'application normalisé par la discovery.

## 12. Politique de vérification et exécution indépendante

Les changements WP6 croisaient structure Maven, runtime LKV, infra READ/PRIMARY, migrations déplacées et documentation. Architecture gate, suites Postgres/journeys puis reactor complet étaient proportionnés ; aucun replay additionnel des migrations historiques n'était requis puisque les suites reconstruisent et valident les deux schémas depuis les migrations de confiance.

Commandes réellement exécutées pendant cet audit :

| Commande | Résultat |
|---|---|
| `./mvnw -pl architecture-tests -am test` depuis la racine repository | exit 127 : wrapper absent à ce niveau, aucune preuve produite |
| `./mvnw -pl architecture-tests -am test` depuis `app/` | PASS ; `architecture-tests` 119 tests, 0 échec/erreur/skip |
| `./mvnw test -q` depuis `app/` | PASS ; reactor complet courant 1065 tests, 0 échec/erreur/skip |

Les Final Architectural Journey Proofs sont dans `architecture-tests` et ont été exécutés par les deux commandes Maven utiles ; aucune commande séparée n'était nécessaire. Docker a d'abord refusé `/var/run/docker.sock`, puis Testcontainers a utilisé le socket utilisateur et toutes les suites ont réussi.

Le total indépendant courant est **1065**, non 1087. La différence de 22 correspond exactement aux rapports Surefire ignorés de `app/domain-pot-policy/target`, module supprimé du reactor. Le succès Maven WP6 reste plausible et est reproduit ; son comptage « 1087 » n'était pas limité aux modules CURRENT.

## 13. TBD, documentation et dettes

La recherche `TBD|TODO|FIXME|provisional|temporary|bridge|compatibility|legacy|follow-up|unresolved` a été classée par contexte :

- les occurrences dans plans/rapports WP historiques sont des preuves historiques acceptables lorsque leur phase est explicite ;
- `TBD-COMMAND-CONTRACT`, `TBD-E2U` et `TBD-LKV` sont explicitement résolus et leurs décisions sont matérialisées ;
- les mentions SQL « compatibility » décrivent des noms physiques conservés, pas un POM migratoire ;
- les exceptions « temporary » du runtime décrivent des erreurs transitoires opérationnelles ;
- le TODO de policy de création Pot porte une extension produit/permissions, pas une frontière Modularity ;
- le commentaire B16 et `AcquireResult` « alongside legacy » sont des formulations actives obsolètes (MINOR) ;
- les API `domain-consumption` explicitement compatibility/legacy/awaiting migration et `forRemoval` sont un TBD architectural implicite réel (BLOCKING) ;
- le document actif `docs/architecture/consumption-transactional-execution.md` décrit encore `locator-consumption-command` et la future suppression de `ClaimToken` : il mélange CURRENT disparu et migration inachevée (MINOR documentaire, preuve corroborante du blocking).

Le registre marque actuellement `DEBT-MOD-01` RESOLVED sur la seule base WP6. Il n'a pas été modifié. Les preuves de cet audit imposent la recommandation **DEBT-MOD-01: KEEP OPEN** jusqu'à disparition ou décision finale explicite des APIs de compatibilité et alignement du graphe Event. `DEBT-WA6-01` et `DEBT-WA6-02` restent des dettes techniques LOW de complétude des guards lock/SQL ; aucune preuve nouvelle ne justifie de changer leur statut. Les améliorations futures/operational concerns restent distinctes des TBD architecturaux.

## 14. Findings

### BLOCKING

**B-01 — Bridge legacy caché dans un module TARGET.** `domain-consumption` expose 17 éléments `@Deprecated(forRemoval = true)` autour de `Claim`, `ClaimToken`, `ConsumptionSlot` et `ConsumptionKey`. Les commentaires disent explicitement « compatibility », « legacy engine/context » et « callers awaiting migration ». `Claim` conserve même `Optional<ConsumptionKey> compatibilityKey` dans sa forme courante. L'affirmation « migration bridges = 0 » est fausse.

**B-02 — Dépendance de production Event → Command non TARGET.** `runtime-event-consumption-worker/pom.xml` déclare `pocoma-engine-consume-command`; aucune source main du runtime ne l'utilise et son unique justification locale est un test important `CommandTerminalEventTypes`. Une dépendance de test est ainsi devenue un arc de production cross-capability, absent de la topologie Event décidée.

### MINOR

**M-01 — Documentation/commentaires actifs obsolètes.** Le document Consumption actif décrit un locator supprimé et une suppression future encore non réalisée ; le mapper Command reste étiqueté « Temporary B16 bridge » alors que la décision WP3 le qualifie d'adapter final.

**M-02 — Comptage de tests WP6 contaminé.** 1087 = 1065 tests des modules CURRENT + 22 rapports du `target/` ignoré d'un ancien module supprimé. Le reactor passe, mais ce décompte du rapport n'est pas reproductible sur le seul reactor courant.

**M-03 — Nom de test LKV plus fort que sa preuve.** Le test runtime persiste V10,V12,V11 mais la discovery les applique V10,V11,V12. Avec le test JDBC high→low, la propriété C2 est suffisamment prouvée ; le libellé `consumesOutOfOrder...` reste trompeur.

## 15. Recommandations de clôture

- **MODULARITY MIGRATION: KEEP OPEN.** CURRENT ne matérialise pas encore entièrement TARGET sans frontière cachée et une preuve finale obligatoire est insuffisante.
- **DEBT-MOD-01: KEEP OPEN.** Condition précise : résoudre explicitement B-01 et B-02, puis refaire gate, journeys et reactor conformément à la politique.
- Les corrections ne font pas partie de cet audit et n'ont pas été effectuées.

## 16. État Git du livrable

Ce passage ajoute uniquement `docs/steps/current/ARCHITECTURE/Modularity_Final_Independent_Audit.md`. Les contrôles `git diff --check`, commit, push, divergence finale et propreté sont consignés dans le rapport final de commande qui accompagne ce document.

---

FINAL MODULARITY AUDIT: FAIL

CURRENT/TARGET: mismatch proven by embedded legacy compatibility and one non-TARGET production arc
POMs: 58 children / 57 production / 1 verification
INTERNAL ARCS: 234
CYCLES: 0
MISSING INTERNAL DEPS: 0
TARGET→LEGACY: 0 module arcs, but legacy code is embedded inside TARGET `domain-consumption`
LEGACY/PROVISIONAL POMs: 0
MIGRATION BRIDGES: at least 1 hidden production compatibility cluster
ARCHITECTURAL TBDs: at least 2 implicit closure items

ARCHITECTURE GATE: PASS — 119/119
FINAL JOURNEYS: PASS — A–E and C2 result proven; runtime test name overstates application order
FULL REACTOR: PASS — 1065/1065 CURRENT tests
SQL/MIGRATIONS: PASS — 47 repository SQL files; 15 byte-identical path-only moves; no semantic change

MODULARITY MIGRATION: KEEP OPEN
DEBT-MOD-01: KEEP OPEN

BLOCKING FINDINGS:
B-01 embedded legacy Consumption compatibility; B-02 Event runtime production dependency on Command engine.

MINOR FINDINGS:
M-01 stale active documentation/comments; M-02 WP6 test total includes 22 stale reports from a deleted module; M-03 LKV runtime test name overstates application order.
