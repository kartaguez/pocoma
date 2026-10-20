# Lot 4B — Propriétaire PRIMARY et collecte transactionnelle dormante

Date : 2026-10-20. **4B CLOSED / PASS confirmé après audit indépendant ; hors production ; 4C NEXT ; 4D et 4E non implémentés.**
Référence normative : [plan détaillé §§3–6/4B et §8/V-B](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md).
Suivi : [current_plan](../steps/current/DISCOVERY/Step_Plan.md), [current_canon](../steps/current/DISCOVERY/Step_Canon.md).
Preuves exécutées : [résultats JSON](../testing/evidence/Event_Transaction_Protocol_Step_4B_2026-10-20.json),
[extraits des logs](../testing/evidence/Event_Transaction_Protocol_Step_4B_2026-10-20.txt).
Ce PASS ne clôt ni 4C–4E, ni la vague de séquencement, ni une intégration main.

## Référence Git et préservation

Travail exclusivement dans `/private/tmp/pocoma-consumption-transaction-protocol`, branche
`feat/consumption-transaction-protocol`. Start HEAD : `b3d6dbbe78b32f89dc545e9637c7e5aaa3865c34`.
Aucune modification suivie initiale. Seul non-suivi préexistant :
`docs/architecture/Architecture_Last_Skip_Audit_2026-10-20.md`, SHA-256
`e34013dbb00ea3f3ff5150a8ba9e850d49fb116b4d7f6703edb4db9a19af3092` ; conservé et exclu du commit.
Fetch ciblé effectué ; divergence initiale après fetch **0/0**. Checkout principal et autres worktrees
non modifiés. Aucun reset/stash/rebase/force push. Le commit de livraison 4B, objet de l'audit, est
`5640220b94f6208aa4c156e248d95e2eced1522f` ; ses preuves et commandes ci-dessous restent celles de
cette livraison. La clôture documentaire ultérieure est distinguée dans la dernière section.

## Verification scope déclaré avant implémentation

- Expected production impact : nouvelles classes techniques dormantes dans `infra-persistence-primary-jpa` ;
  configuration/writer/suffixe synthétiques et preuves dans les tests COMMAND seulement.
- Primary slice : **COMMAND** ; secondary slices : **NONE**.
- Forbidden production impact : aucun raccordement des producteurs/owners réels, aucun changement
  Consumption/P/B/S/TA/TE/TR/TH/retry, métier, Discovery, READ, schéma, dépendance/POM ou runtime-to-runtime.
- Required local proof : matrice 4B, PostgreSQL physique, cas négatifs et règles structurelles locales.
- Global architecture gate : **NOT REQUIRED, non exécuté** ; aucune condition §17.2 de la politique rencontrée.
- Full reactor : **NOT REQUIRED, non exécuté** ; lot borné, sans clôture majeure 4E ni intégration main.
- Database verification : **CURRENT + prototypes isolés**. Bootstrap CURRENT existant Flyway V1–V29,
  baseline trusted **NONE**. Les migrations historiques s'exécutent dans les bootstraps/tests déjà
  présents de la slice ; aucune campagne historique supplémentaire ni baseline certifiée inventée.
- Escalation conditions : owner supplémentaire hors inventaire, datasource/manager de production,
  POM/frontière nouvelle, autre comportement de slice, métier après E ou savepoint nécessaire.
  Aucune de ces conditions constatée. Prochain gate structurel obligatoire : **4D**, puis majeur **4E**.

## Mécanisme livré

[PrimaryEventTransactionRunner](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionRunner.java)
utilise directement `TransactionTemplate` et le `JpaTransactionManager` standard existant.
Sans ambient, il est propriétaire d'une nouvelle transaction physique. Sous lot OPEN compatible,
les appels imbriqués exécutent uniquement leur action participante : ni template supplémentaire,
ni flush à leur retour, ni fermeture ni suffixe. Un ambient inconnu est refusé avant l'action.

Le lot est une ressource `TransactionSynchronizationManager` au couple manager/EMF ; il mémorise
l'identité du holder EntityManager et du holder JDBC et les revalide. Il n'existe aucun ThreadLocal
libre. Le cleanup `afterCompletion` est enregistré avant exposition du lot ; un `finally` idempotent
couvre aussi installation et résolution échouées sans changer le statut observé par le wrapper.
La ressource est détachée, les collections sont vidées et le lot devient CLEANED.

L'orchestration appelle sa façade injectée `PublishedEvents` après l'acceptation. La façade du test
référence explicitement `runner::recordPublishedEvent` ; le writer ne déclare rien. Cette composition
respecte la règle 4A interdisant la collecte dans les adaptateurs/repositories, sans modifier le gate
architecture global ni sa règle. Un même couple famille/UUID est refusé deux fois ; un même UUID dans
deux familles est distinct. L'ordre des appels par famille est conservé, y compris entre participants.

L'action englobe la finalisation CURRENT S/Claim. À son retour, le propriétaire vérifie le lot et
rollback-only, flushe l'EntityManager PRIMARY, confirme ainsi les intentions JPA, vérifie à nouveau,
ferme un [snapshot immuable](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/ClosedPublishedEvents.java),
puis exécute une fois le suffixe technique seulement si le snapshot n'est pas vide. Tout cela reste
**dans le callback**, avant résolution physique par Spring. Aucune fermeture/flush/suffixe dans un
`finally` ni après le retour du template. Les erreurs de garde empoisonnent irréversiblement le lot
et le statut avant de lever, y compris si elles sont interceptées ; les erreurs participantes propagées
empoisonnent également. Une garde sous un template PRIMARY inconnu marque aussi sa transaction JPA
rollback-only, sans créer de contexte de secours.

[PrimaryEventInsert](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventInsert.java)
est la primitive locale `EntityManager.persist`, gardée OPEN, sans merge/upsert, collecte ou listener.
Le writer synthétique construit des entités neuves à UUID préassigné ; la collision PK est prouvée au
flush et ne remplace aucune ligne existante. Aucun writer réel n'adopte encore cette primitive.

Le suffixe reçoit uniquement les valeurs fermées, aucune entité/callback métier. Son implémentation
4B est exclusivement dans les tests : INSERT d'une trace d'accès par famille non vide et associations
strictes sur les acceptations synthétiques, ordre BUSINESS → TERMINAL → BINDING → USER. Ce n'est
**pas** le séquenceur 4C : aucun compteur, bloc, position dense, DDL livré ou mode PREPARED/ACTIVE.

Les méthodes `TransactionRunner` et les algorithmes de `ConsumptionOwnerTransactionRunner`,
`SpringTransactionRunner` et Consumption restent inchangés. Le wrapper CURRENT observe
`afterCompletion` et conserve COMMITTED/ROLLED_BACK/UNKNOWN. Le runner ne rend un résultat
propriétaire qu'après COMMITTED confirmé. Aucun retry/replay ni mutation FAILED ajouté.

## Matrice de preuves V-B

Sources : [tests PostgreSQL](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/PrimaryEventTransactionPostgresTest.java),
[règles locales](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/PrimaryEventProtocolStructureTest.java),
[dormance réelle](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/PrimaryEventProtocolDormancyPostgresTest.java),
[composition explicite](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/PrimaryEventProtocolTestConfiguration.java).

| Invariant | Preuve observée |
|---|---|
| Transaction physique JPA/JDBC unique | SELECT JPA et JDBC avec PID/xid identiques ; triggers PostgreSQL des écritures/suffixe ont un seul couple distinct ; manager effectif exactement `JpaTransactionManager`, unique, nested=false |
| Propriétaire/participants | Deux participants proxy REQUIRED/MANDATORY et plusieurs appels runner ; références cumulées, pas de flush participant (INSERT JPA encore absent), suffixe unique après tous leurs retours ; MANDATORY hors transaction refusé |
| Grammaire | Trace DB demande → acceptation → déclaration → INSERT JPA → accès E → association ; statistiques Hibernate prouvent le flush après retour d'action et avant suffixe fermé |
| S/Claim avant suffixe | Vrai `TransactionalExecuteConsumptionUseCase`/finalizer CURRENT ; UPDATE S puis clôture Claim avant premier accès E, même PID/xid ; vérification DONE et ended_at ; pas de migration des producteurs Command |
| Commit après suffixe | Connexion indépendante : aucune ligne pendant action, ligne visible dans observation afterCommit et après retour ; aucune réussite anticipée |
| Lot vide | Suffixe non appelé ; table représentant toutes les E synthétiques tenue ACCESS EXCLUSIVE sur autre connexion ; lock_timeout=300ms, commit réussi et zéro lock E du propriétaire |
| Ordre et fermeture | UUID opposés à l'ordre déclaré, appels/familles entrelacés et participants ; snapshot exact, listes non modifiables, snapshot conservé intact après cleanup/transaction suivante ; ordre fixe des familles |
| Doublon/poisoning | Double couple et déclaration null interceptés, refus du lot ultérieur et rollback physique ; exception participante interceptée par son appelant reste empoisonnante |
| Ambient inconnu | Refus runner/writer/déclaration avant effet ; erreur interceptée sous template PRIMARY inconnu laisse rollback-only, observé ROLLED_BACK ; synchronisation seule refusée |
| Publication tardive | Injection adversariale exclusivement test après fermeture : writer/déclaration/participant refusés, même exceptions interceptées ; rollback de tous les effets |
| Action en échec | Cause identique propagée après ROLLED_BACK, aucune ligne JPA/JDBC/association durable |
| Flush en échec | Collision PK différée réelle ; ancien payload/row inchangé, aucune association ; phase COMMIT, issue ROLLED_BACK confirmée |
| Suffixe en échec | Faute après INSERT association : phase COMMIT avec ROLLED_BACK ; effets/acceptations/associations/trace annulés ; vrai S restauré PENDING et Claim ended_at null malgré finalisation provisoire réussie |
| Begin / installation | Refus begin injecté : aucune action, aucun lot ni suffixe ; UNKNOWN/ROLLBACK conservé. Synchronisation désactivée dans fixture : installation refusée et cleanup de secours vérifié ; transaction suivante saine |
| COMMITTED avec exception | Observation afterCommit lève ; wrapper classe COMMITTED/COMMIT, ligne durable et une seule tentative |
| UNKNOWN | Fixtures de manager **test-only**, non enregistrées : exception Java après vrai commit ou rollback ; afterCompletion UNKNOWN reste UNKNOWN, phase COMMIT ou ROLLBACK conservée ; cause d'action conservée quand rollback remplacé ; aucun replay ; ressources nettoyées et transaction suivante saine |
| Cleanup / aucune fuite | Resource map vide après chaque cas ; six transactions succès/échec/vide sur le même thread d'executor, snapshots distincts et aucun ancien lot ; cleanup aussi sur UNKNOWN et installation échouée |
| Callbacks écrivains exclus | Règles bytecode locales sur writer/suffixe synthétiques : callback, résolution autonome, savepoint/merge et retour métier interdits ; fixtures beforeCompletion écrivain et mutation réfléchie après fermeture rejetées sans invocation |
| Deux compositions | Test existant Pot/Expense publie en mémoire sans runner/registre/E ; contrats d'acceptation/Registration rejoués ; fixture transport non transactionnel rejetée structurellement avant envoi ; faux acquittement ne prouve pas atomicité |
| Dormance | Démarrage du vrai root Command sans import : aucun bean runner/suffixe/PublishedEvents/config 4B, un seul manager standard |

La trace finale canonique porte notamment PID/xid identiques pour INSERT JPA, UPDATE S, UPDATE Claim et
suffixe (valeurs et ordre complets dans les extraits/JSON). Les compteurs de tests ne remplacent pas ces assertions.

**Limites de preuve précises.** Le schéma public CURRENT V29 et le finalizer sont réels. Les trois familles
non Business et leurs acceptations/associations sont représentées par des INSERT dans `experiment_4b`,
pas par une migration de leurs vrais producteurs. Les INSERT Business utilisent le mapping JPA et les
contraintes CURRENT, via le seul writer synthétique. Le suffixe E est une sonde isolée, pas une preuve du
DDL/compteur/complétude ACTIVE. Les fautes UNKNOWN sont des injections Java après résolution physique ;
elles ne simulent ni ne prouvent une vraie perte d'accusé réseau. La relecture indépendante des données
dans le test ne réécrit pas l'issue UNKNOWN rapportée par le protocole.

## Inventaire des propriétaires et writers à migrer en 4D

Recherche dans tous les `app/*/src/main` par tables, entités, ports, annotations, templates et usages ;
lecture des implémentations, initializer/recovery et constructeurs `new`. Aucun owner producteur
supplémentaire hors §5 du plan identifié. Inventaire achevé pour la clôture 4B, à revalider au HEAD 4D.

| Owner / surface autonome | Writers et responsabilité de migration 4D |
|---|---|
| Command TA/TE/TR/TH — `CommandConsumptionRuntimeConfiguration.commandConsumptionTransactionRunner` | Substitution sous garde existante, sans nouvelle frontière. TE Business + Terminal, TR recovery Terminal, TH FAILED Terminal ; TA/retry vides. Déclarants `ExecuteRecordedCommandService`, `CommandConsumptionExecution` et `BindingFenceRecoveryExecuteUseCase` |
| Registration TA/TE/TH — `RegistrationRuntimeConfiguration.registrationTransactionRunner` | TE initializer UserCreated puis ATTACHED et finalisation ; aucune déclaration pour existing/conflit/retry. Déclarant `ExecuteRegistrationService`, manager PRIMARY commun à vérifier en 4D |
| Append Business auxiliaire — `JpaBusinessEventOutboxAdapter.append` REQUIRED | **Seul writer recensé actuellement propriétaire annoté autonome**, également participant sous ambient. Transférer l'autonomie vers façade applicative explicite ; writer participant, acceptation stable puis déclaration applicative. Aucun appelant productif retrouvé ; surface à couvrir néanmoins |
| Binding acquire/acquireWithInitializer/detach et fact auxiliaire | Participants MANDATORY : tout usage autonome autorisé exigera owner explicite englobant, sans transaction par sous-port. `JpaExternalIdentityBindingAdapter` construit le repository fact par `new`, en plus du chemin bean `JpaExternalIdentityBindingFactAdapter` ; garde à couvrir dans le corps commun |
| Autres owners annotés/templates | Aucun droit de publication couvert gagné par le runner dormant. Admission, Results, Event/Binding/Task/LKV/READ ne produisent pas ces quatre sources au HEAD ; aucun raccordement à réaliser dans 4B |

Six adaptateurs, cinq points d'écriture CURRENT : `JpaPotCommandEventAppendAdapter`,
`JpaBusinessEventOutboxAdapter`, `JdbcCommandOutcomeAdapter`, `JpaExternalIdentityBindingAdapter`,
`JpaExternalIdentityBindingFactAdapter`, `JdbcUserCreatedFactAdapter` ; Binding partage
`ExternalIdentityBindingFactJdbcRepository`. Deux writers JPA outbox, Terminal JDBC, Binding JDBC
commun, UserCreated JDBC. `JdbcRegistrationOutcomeStore` participe à la même TE mais son outcome
n'est pas une cinquième famille. V22 est un writer **historique SQL**, pas un owner runtime ; aucun
backfill/réécriture livré ici. Runtimes distribués et monolithe restent à certifier ensemble en 4D.

## Commandes réellement exécutées et résultats

Depuis `app/` :

```bash
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=PrimaryEventTransactionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=PrimaryEventTransactionPostgresTest,PrimaryEventProtocolStructureTest,PrimaryEventProtocolDormancyPostgresTest,NonPersistentEventPublicationTest,PublicationAcceptanceContractTest,RegistrationPublicationContractTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test
```

Les exécutions ont redirigé stdout/stderr vers `/private/tmp/pocoma-4b-targeted.log`,
`/private/tmp/pocoma-4b-targeted-final.log`, `/private/tmp/pocoma-4b-command.log` respectivement ;
les extraits versionnés et les SHA-256 des logs finaux sont fournis dans les preuves.
Boucle initiale : erreur de compilation d'une API Spring protégée, corrigée par l'API JPA publique ;
bootstrap de fixture corrigé pour injecter le type déclaré `PlatformTransactionManager` puis vérifier
le type JPA effectif ; une assertion de type d'exception corrigée (garde POISONED refuse avant effet).
Aucune assertion d'atomicité, de rollback, d'ordre ou d'absence de fuite retirée/affaiblie.
Un lancement sans privilèges supplémentaire a échoué sur cette même compilation avant tests ;
Docker/Testcontainers a ensuite été utilisé avec l'autorisation d'exécution nécessaire.

Dernière campagne ciblée : **37 tests, 0 failure, 0 error, 0 skip**, dont 28 tests 4B et 9 tests de
séparation/contrats 4A. La dernière fixture structurelle de rejet d'un transport externe a ensuite été
ajoutée et exécutée par la commande canonique : **29 tests 4B** (23 PostgreSQL + 5 structure + 1 dormance).

Commande canonique : **BUILD SUCCESS, 1 047 tests déclarés, 0 failure, 0 error, 1 skip**,
201 classes de rapport. Le skip préexistant est
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, condition
`step3b.measure=true` ; aucune preuve 4B n'est sautée. Reactor de la slice : 51 modules, tous SUCCESS.
Ce nombre de modules est la fermeture Maven `-am`, pas 51 capacités modifiées.

Autres contrôles : inventaires `rg --files`/`rg -n`, lectures normatives/sources, `java -version`
(Java 21), `docker info` (Docker 29.8.2), `git worktree list --porcelain`, `git status --short --branch`,
`git rev-parse HEAD`, fetch ciblé, divergence `git rev-list --left-right --count`, `git diff --check`,
revue des nouveaux fichiers/diff, validation des liens documentaires touchés et intégrité du non-suivi.
Staging explicite du lot, commit cohérent et push non forcé sur la branche cible, après ces contrôles.

## Fichiers livrés, restrictions et verdict

Ajout de quatre classes PRIMARY dans `transaction/event/` : `PrimaryEventTransactionRunner`,
`PrimaryEventInsert`, `ClosedPublishedEvents`, `PrimaryEventTransactionSuffix` (quatre fichiers).
Ajout de quatre fichiers de tests COMMAND : configuration synthétique, tests PostgreSQL, règles
structurelles locales et test de dormance. Les fichiers documentaires modifiés/ajoutés sont
inventoriés exactement dans le JSON de preuve ; le rapport d'audit non suivi initial en est exclu.

**PASS** : V-B vert, rollback physique observé (y compris restauration S/Claim), suffixe unique dans
callback après S et avant commit, aucune fuite, poisoning intercepté irréversible, résolutions
conservées et inventaire des owners autonomes terminé. Aucun écart normatif bloquant ni franchissement
inattendu. La règle structurelle 4A et la réalisation du collecteur sont conciliées par la façade
explicitement câblée, sans collecte implicite ni modification des règles globales.

4C garde DDL, séquenceur, compteurs, blocs, associations définitives, overflow, droits et complétude.
4D garde les signatures/acceptations/déclarations réelles, la façade Business autonome, les gardes
owner/OPEN des vrais writers, PREPARED et les preuves des compositions distribuées/monolithe.
4E garde activation, gel, historique/backfill, restauration et gates globaux. Discovery et READ
restent hors scope. Aucun de ces sujets n'est anticipé. Le code 4B dormant se retire sans opération DB.

## Clôture documentaire après audit indépendant

Verdict indépendant communiqué par le responsable du dépôt, consigné le 2026-10-20 : **PASS confirmé**
pour la livraison `5640220b94f6208aa4c156e248d95e2eced1522f`, avec deux réserves non bloquantes
relevant de la certification 4D. **4B CLOSED / PASS conformément à son périmètre.** La revue est
statique et favorable ; l'auditeur n'a réexécuté ni Maven ni PostgreSQL. Les preuves transactionnelles
PostgreSQL et les tests V-B de la livraison, détaillés plus haut, avaient déjà été réalisés.
Les fichiers de preuves JSON/TXT restent inchangés ; aucune exécution supplémentaire n'est attribuée
à l'audit.

Les garanties prouvées sur le runner/collecteur dormant et le writer synthétique restent bornées
à la matrice V-B. Le poisoning des erreurs de garde interceptées et des erreurs participantes
propagées ne prouve pas que le runner 4B intercepte toutes les erreurs SQL des writers réels.
L'identité physique prouvée dans la composition de test COMMAND ne certifie pas les compositions
Command, Registration et auxiliaires futures. Runner et collecteur sont **dormants, non raccordés
en production** ; aucun producteur réel migré ni séquencement E actif.

Les deux réserves sont rattachées aux critères existants **4D/V-D** de la
[spécification, §6/4D, conditions A et B](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md#4d--raccordement-complet-production-prepared) :
**A — poisoning des writers réels, JDBC compris**, et **B — identité de transaction physique sous
le même manager PRIMARY**. Le [Step Plan, §4D](../steps/current/DISCOVERY/Step_Plan.md#4d--producteurs-et-orchestrations-réels-prepared-complet)
en porte le suivi, le [canon](../steps/current/DISCOVERY/Step_Canon.md) les limites CURRENT/TARGET.
Elles ne créent aucune dette technique 4B. **4C NEXT**, sans lancement d'implémentation ni changement
de périmètre, invariants ou critères de clôture ; **4D et 4E non implémentés**.

Vérification de cette seule clôture documentaire :

- Start HEAD : SHA de livraison 4B ci-dessus ; même branche et même worktree. Divergence initiale
  après fetch ciblé : **0/0** ; fichier non suivi préexistant conservé avec le même SHA-256.
- Impact attendu/observé : quatre documents uniquement (canon, plan, spécification Étape 4, présent
  rapport). Slices déclarées : **NONE**, aucune slice applicative ; franchissement inattendu : **NONE**.
- Contrôles : état Git/branche/worktree, fetch ciblé, `git rev-list --left-right --count`,
  `git diff --check`, revue du diff ; `python3 /private/tmp/pocoma-4b-doc-closure-verify.py`
  pour la validation des références/liens/ancres,
  des sections 4C inchangées, des preuves JSON/TXT inchangées et du non-suivi préservé.
- Aucun Maven ni PostgreSQL relancé. Gate architecture global : **NOT REQUIRED, non exécuté**.
  Full reactor : **NOT REQUIRED, non exécuté** ; DB : **NONE**, aucune migration ou changement de schéma.
- Commit et push documentaire non forcé : SHA final, résultat du push et divergence finale fournis
  dans la livraison de cette clôture, distincts du SHA audité.
