# Consumption — Diagnostic causal des cinq refus de transaction ambiante

Date : 2026-10-20, Europe/Paris. **Diagnostic et proposition uniquement.** Aucun changement runtime, test, configuration, migration ou base ; 4B non commencé.

## Verdict

**SAFE FOR 4B : les cinq erreurs sont comprises et ne compromettent pas la conception de 4B.** Une seule cause racine démontrée, **TEST_SETUP** : la fixture de courses ouvre une transaction physique puis appelle des entrées publiques Consumption propriétaires, interdites sous ambient. Le refus est conforme au protocole. Aucun défaut runtime ou Spring wiring n'est démontré par ces cinq erreurs.

Ce verdict est limité à leur causalité et à la cohérence de conception. **Ce n'est ni un gate global PASS, ni une clôture 4A, ni une autorisation de commencer 4B.** Le gate différentiel reste rouge (11 erreurs historiques, dont six de bootstrap distinctes) ; le traitement ou l'arbitrage explicite des dettes et l'autorisation du lot restent requis par le plan. Les cinq courses n'ont pas été exercées au-delà de leur setup dans ces exécutions. La correction proposée reste à implémenter et à vérifier sur autorisation séparée.

## Autorités, baseline et périmètre déclaré

Branche cible : `feat/consumption-transaction-protocol`. SHA initial réel et attendu : `773b18e777d47155852ee3aea9705c725d131ca9`. Worktree cible : `/private/tmp/pocoma-consumption-transaction-protocol`, propre au départ. Fetch réel de la branche cible : divergence initiale **0/0**. Le checkout principal est sur `feat/runtime-monolith`, `90926a5ec37d1ca841e3de017bec0d1f705d8839`, avec travaux préexistants ; aucun de ses fichiers n'est écrit. Les autres worktrees sont conservés.

Références lues : [AGENTS](../../AGENTS.md), [politique normative de vérification](../testing/Reactor_Verification_Policy.md), [plan Étape 4](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md), [livraison 4A](Event_Publication_Contracts_Step_4A_2026-10-19.md), [diagnostic différentiel des onze erreurs](Event_Publication_Contracts_Step_4A_Differential_Diagnosis_2026-10-20.md), [protocole Consumption CURRENT](consumption-transactional-execution.md), [contrat WP1](../plans/2026-10-16-consumption-generic-transactional-protocol-wp1-contracts.md), notamment §§4–5, 8–10, [audit du protocole](Consumption_Generic_Transactional_Protocol_Audit_2026-10-16.md), notamment §F. Le rapport différentiel corrige l'attribution initiale trop large « onze erreurs PostgreSQL » de la livraison 4A : cinq sont des refus applicatifs sans cause SQL imbriquée.

Déclaration avant rédaction : impact production/tests/DB attendu **NONE** ; slices primaire/secondaires **NONE** (livraison documentaire). Sujet analysé : composition Consumption/CommandResult et sa fixture dans `architecture-tests` ; Results est une ancre complémentaire, pas une slice canonique inventée. Vérification documentaire et lecture des sources/traces existantes ; test ciblé seulement si une incertitude causale le nécessite. Global architecture gate **NOT REQUIRED** pour ce diagnostic ; full reactor **interdit / non exécuté** ; DB **NONE**, aucune base partagée ni migration historique. Aucun franchissement de slice attendu ou constaté.

## Matrice des cinq erreurs

Classe commune exacte : `com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest` ([source de la fixture][fixture]). « Owner effectif » désigne le propriétaire de la transaction physique existante, et non le propriétaire du Claim : aucun Claim n'est encore acquis. Dans toutes les lignes, le composant qui refuse est `ConsumptionOwnerTransactionRunner` du bean `commandResultTransactionRunner`, appelé par `commandResultAcquire`.

| Test | Owner attendu | Owner effectif | Origine de la transaction ambiante | Cause | Correction proposée |
|---|---|---|---|---|---|
| `inFlightPublicationLosesFenceAndUnblocksWinnerPublication` (107–138 ; appel 109) | Entrée `commandResultAcquire` / runner Consumption, TA autonome ; puis TE C1, TA C2, TE C2 distinctes | Fixture `inTransaction`, résolution physique par le manager JPA | `initialClaim:376 → inTransaction:281`, `new TransactionTemplate(transactionManager).execute` ; probe C1-acquire | **TEST_SETUP**, racine R1 ; rejet correct | **R1**, déplacer les probes/barrières dans le callback du véritable owner ; garder l'attente sur l'unique de publication et le rollback stale |
| `committedFinalCasMakesWaitingTakeoverObserveDone` (141–143 ; appel 142) | Même owner TA ; ensuite owner TE C1 jusqu'au commit, TA C2 concurrente | Même fixture | `resolveFinalCasBeforeTakeover:151 → initialClaim:376 → inTransaction:281` | **TEST_SETUP**, R1 ; rejet correct | **R1**, barrière après CAS/clôture mais avant retour de l'action au runner ; C2 doit attendre puis voir AlreadyDone |
| `rolledBackFinalCasReleasesSlotAndAllowsTakeover` (145–147 ; appel 146) | Même owner TA ; ensuite owner TE C1 jusqu'au rollback, TA C2 concurrente | Même fixture | Même helper partagé, même ligne 281 | **TEST_SETUP**, R1 ; rejet correct | **R1**, injecter RollbackMarker dans l'action de l'owner après la barrière ; vérifier annulation CAS/Claim/provenance/publication et reprise C2 |
| `twoConcurrentTakeoversInstallOnlyOneCurrentClaim` (194–227 ; appel 196) | Même owner TA ; puis une TA par contender C2/C3 | Même fixture | `initialClaim:376 → inTransaction:281` | **TEST_SETUP**, R1 ; rejet correct | **R1**, garder la barrière après installation non committée du gagnant, dans son owner ; un Acquired, un Busy après résolution |
| `rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo` (243–278 ; appel 245) | Même owner TA ; puis TA C2 rollbackée et TA C3 distincte | Même fixture | `initialClaim:376 → inTransaction:281` | **TEST_SETUP**, R1 ; rejet correct | **R1**, injecter rollback après installation C2 dans son owner ; C3 réutilise tentative 2 avec un ClaimId différent |

Les cinq exceptions surviennent **toutes au premier Acquire C1**, avant expiration simulée, lancement des pools, barrières et contending workers. Les phases ultérieures du tableau sont les intentions du code, pas des phases observées dans ces traces. TA n'est pas ouverte par le runner Consumption : une transaction de fixture qui prétend l'englober est déjà active à son entrée. Aucune TE/TR/TH n'est atteinte.

Exception exacte, sans `Caused by` ni SQLSTATE :

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
```

Les cinq traces intégrales sont conservées en annexe, sans suppression de frames. Leur point de lancement commun est `ConsumptionOwnerTransactionRunner.java:25`.

## Transactions réellement observées

Preuves réutilisées : XML Surefire du diagnostic différentiel, pour la baseline `a53999cb4dc7dac5973478b5ad50243710acba87` et la livraison `3a2440326d227645da501a821ad73caad920db42`. Les deux campagnes sont déjà exécutées et distinctes ; aucune n'est relancée ici. Le SHA initial `773b18e7` ajoute leur rapport documentaire, pas une nouvelle version des six sources causales contrôlées ci-dessous.

| Méthode | Nom de probe | PID PostgreSQL | XID | Isolation observée | Résolution |
|---|---|---:|---:|---|---|
| `twoConcurrentTakeoversInstallOnlyOneCurrentClaim` | C1-acquire | 61 | 803 | read committed | status=1, ROLLED_BACK |
| `inFlightPublicationLosesFenceAndUnblocksWinnerPublication` | C1-acquire | 61 | 809 | read committed | status=1, ROLLED_BACK |
| `rolledBackFinalCasReleasesSlotAndAllowsTakeover` | C1-acquire | 61 | 815 | read committed | status=1, ROLLED_BACK |
| `committedFinalCasMakesWaitingTakeoverObserveDone` | C1-acquire | 61 | 821 | read committed | status=1, ROLLED_BACK |
| `rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo` | C1-acquire | 61 | 827 | read committed | status=1, ROLLED_BACK |

Valeurs identiques dans les deux campagnes, sur des conteneurs différents : ces PID/XID sont locaux à chaque serveur, pas des identités globales. Dans la campagne livrée, Flyway établit la datasource `jdbc:postgresql://localhost:65175/pocoma?loggerLevel=OFF` (PostgreSQL 17.11), Hikari démarre et l'EMF `default` est initialisé avant les tests. La réutilisation du PID entre méthodes est compatible avec le pool ; les XID distincts identifient les transactions successives.

`C1-acquire` est **un libellé Java de TxProbe**, pas un nom transactionnel Spring ni une preuve de Claim C1 acquis. Le template n'appelle pas `setName` ; la définition Spring est sans nom, REQUIRED, isolation DEFAULT, readOnly=false. L'isolation READ COMMITTED est réellement interrogée par SQL (fixture 298–303), non inférée de DEFAULT.

Avant chaque action, la fixture prouve `status.isNewTransaction()` (282) et `isActualTransactionActive()` (299). Son enregistrement de synchronisation (286) réussit et produit `afterCompletion=1`. Il existe donc une vraie transaction **et** une synchronisation active au refus, pas seulement une synchronisation vide. Le `||` de la garde peut court-circuiter sa seconde lecture ; cela ne change pas ce constat. L'exception sort de `action.get()` et du template externe, qui rollbacke sa propre transaction. Le runner Consumption n'a pas enregistré son observateur de résolution et ne transforme pas cette exception en `ConsumptionTransactionResolutionException` : sa garde est placée avant son `try` et avant son délégué.

Il y a du SQL de setup : nettoyage (101–105), trois INSERT de `seed` (477–491), puis SELECT PID/XID de la probe. **Il n'y a aucun appel au delegate Acquire, aucune précondition appelée, aucun SQL d'acquisition ni CAS final par cette opération refusée.** Les fixtures seedent volontairement des données terminales sources déjà durables ; leur autocommit de setup n'est pas la transaction ambiante incriminée. Aucun compteur durable post-refus n'est inventé : l'absence d'entrée dans Acquire est une preuve de contrôle de flux.

## Chaîne causale complète et propagations effectives

### Configuration Spring et datasource

La fixture utilise `@SpringBootTest` (73–75), aucune annotation `@Transactional` sur classe ou méthodes, aucune transaction gérée automatiquement par le test listener. `@DynamicPropertySource` (83–87) donne l'URL/utilisateur/mot de passe Testcontainers. `TestApplication` (536–541) active l'auto-configuration JPA/repositories PRIMARY, importe `CommandResultRuntimeConfiguration`, la source et le store CommandResult, et **exclut les deux auto-configurations READ**. Aucun manager ni datasource secondaire n'est défini dans ce graphe. `@TestBean` ne remplace que l'horloge (97–99). `enabled=false` désactive le polling autonome, sans retirer les beans Acquire/Execute.

Le manager injecté sans qualifier dans la fixture (90) et dans la factory du runner (configuration 40) est le manager commun de cette configuration JPA. Boot 4.0.5, `org.springframework.boot.jpa.autoconfigure.JpaBaseConfiguration.transactionManager`, construit un `JpaTransactionManager` ; son EMF utilise la datasource PRIMARY injectée dans cette configuration. Le bytecode local Spring ORM 7.0.6 de `JpaTransactionManager.doBegin` montre le begin JPA et la liaison du `ConnectionHolder` JDBC à `getDataSource()`. JDBC et JPA participent ainsi à la même ressource. La configuration et les logs concordent ; les anciennes traces ne contiennent pas de dump d'identité Java du manager/ConnectionHolder, et aucune mesure nouvelle de cette identité n'est revendiquée.

La présence sur le classpath de `application.properties` monolith n'envoie pas cette fixture vers le port fixe 5432 : ici les propriétés dynamiques gagnent et le contexte démarre. C'est la différence pertinente avec les six autres erreurs. La garde owner examine l'état transactionnel du thread, **sans comparer les datasources** ; même une transaction de la bonne datasource reste incompatible avec une entrée propriétaire. Une datasource différente serait un autre refus légitime, pas le mécanisme constaté ici.

### Du test au refus, pour les cinq méthodes

```text
JUnit : méthode du tableau
  → seed (hors transaction englobante de test)
  → [resolveFinalCasBeforeTakeover pour les deux variantes CAS]
  → initialClaim : crée TxProbe("C1-acquire")
  → inTransaction
      → TransactionTemplate(manager JPA PRIMARY), REQUIRED sans ambient
          BEGIN transaction physique de FIXTURE
          status.isNewTransaction == true
          JDBC SELECT PID/XID/isolation ; enregistrer afterCompletion
          → action.get()
            → helper acquire(seed, "C1", probe)
              → bean commandResultAcquire
                TransactionalAcquireConsumptionUseCase.acquire
                  → commandResultTransactionRunner
                    ConsumptionOwnerTransactionRunner.runInTransaction
                      → requireNoAmbientTransaction : REFUS
          exception sort du callback
          ROLLBACK physique ; afterCompletion ROLLED_BACK
```

[CommandResultRuntimeConfiguration][wiring], 40–45, construit réellement :

```text
commandResultAcquire = TransactionalAcquireConsumptionUseCase(
    AcquireConsumptionService(lifecycle, clock),
    ConsumptionOwnerTransactionRunner(
        SpringTransactionRunner(TransactionTemplate(manager))))
```

Le wrapper [Acquire][acquire-wrapper], 23, appelle le runner ; il n'a pas d'annotation transactionnelle. Le [runner owner][owner], 22–28, refuse avant `delegate.runInTransaction`. Le [runner Spring][spring-runner], 23–26, utiliserait REQUIRED, mais **n'est jamais appelé ici**. Il n'existe donc pas deux transactions imbriquées effectives : une seule est ouverte, par la fixture. Dire « le runner Consumption rejoint une transaction REQUIRED et échoue ensuite » serait faux.

Contrefactuel utile : sans le contrôle d'entrée, REQUIRED rejoindrait la transaction physique de fixture. Le service Acquire produirait un Claim provisoire avant le commit réel du caller ; le retour du template interne ne confirmerait aucun commit physique. Le runner owner CURRENT détecterait alors l'absence de COMMITTED et lèverait une erreur de résolution UNKNOWN, plutôt que de rendre ce Claim publiquement. Remplacer tout ce runner par le simple runner Spring permettrait un retour public prématuré. C'est précisément la composition interdite. Le fait que datasource, manager et niveau d'isolation soient bons ne rétablit pas la propriété des frontières.

Les participants ne sont pas interdits : [AcquireConsumptionService][acquire-service], 27–33, appelle sa précondition puis le lifecycle ; [JpaConsumptionLifecycleAdapter][lifecycle], 62 et 123, impose MANDATORY aux mutations ; [ExecuteConsumptionService][execute-service] et le finalizer sont participants internes. Ils rejoignent l'owner autorisé. Ils ne sont pas interchangeables avec les wrappers publics.

### Où est l'orchestration de production ?

La fixture orchestre directement les appels : elle n'invoque ni `ConsumptionPollingWorker`, ni `ConsumptionOwnerOrchestrator`, ni `SequentialConsumptionOrchestrator`. Insérer artificiellement ces frames dans les cinq chaînes serait incorrect. La configuration les construit, mais le premier point d'entrée effectif est `AcquireConsumptionUseCase.acquire` autowiré.

Le chemin de production composé est distinct : worker → [ConsumptionOwnerOrchestrator][orchestrator-guard] (garde avant discovery/classification) → [SequentialConsumptionOrchestrator][sequential], Acquire 92–98 → retour après TA commit → Execute 129 → retour après TE commit, ou rollback confirmé avant classification/HandleFailure 135–148. La fixture remplace cette coordination par ses helpers, pas le runner injecté. Elle fait donc rencontrer une orchestration de test « service participant » et un bean public « owner ».

### Premier point de divergence et origine historique

Le premier point de divergence **commun** est la composition `initialClaim:376` qui exécute l'entrée owner dans `inTransaction:281`. L'ouverture de ce template serait légitime pour un participant explicitement possédé ; elle devient incorrecte pour le bean injecté actuel. L'échec détecté est à 25 dans la garde ; la cause est antérieure, dans cette composition.

L'intention obsolète est visible dans le commentaire de classe (72) : « Real services join caller-owned transactions ». `git log --follow` situe cette fixture au commit `53fee43c` ; le câblage owner et sa garde sont introduits par `72687053` (unification transactionnelle). La fixture n'a aucun commit d'adaptation ultérieur dans son historique au SHA initial. La comparaison byte à byte de la fixture, du wiring, des deux wrappers et des deux runners entre `a53999cb`, `3a244032` et `773b18e7` établit leur identité ; 4A n'est pas l'origine de la divergence.

## Confrontation aux invariants et classification

| Invariant | Observation dans les cinq échecs | Conséquence pour la correction |
|---|---|---|
| Owner sans ambient incompatible ; résultat public après résolution physique | Garde déclenchée avant callback, correctement | Conserver garde et entrée owner ; ne pas permettre la participation silencieuse du wrapper |
| Participant autorisé sous son owner | Les vrais participants ne sont pas atteints ; la fixture appelle un wrapper owner | Déplacer l'instrumentation sous le bon owner, sans remplacer les beans publics par des delegates nus |
| TA séparée de TE ; TR/TH après rollback TE | La TA normale n'est même pas ouverte par Consumption ; TE/TR/TH non atteintes | Une transaction physique distincte par appel de phase ; aucun template englobant plusieurs phases |
| Effets PRIMARY / provenance / CAS / Claim atomiques | Aucun effet de consommation exécuté dans ces refus | Garder le même manager/connexion, les SQL réels et le rollback intégral ; ne pas assimiler ce refus à une preuve des courses |
| Fencing `PENDING AND current_claim_id=C`, flush avant CAS, clôture exactement une ligne | Chemin non atteint, ni modifié ; [repository Slot][slot-repository] 76–89 et lifecycle 135–167 | Garder CAS réel, flush, FK/uniques et vérifications durables ; pas de mock de résultat |
| Lease et revision ne remplacent pas le ClaimId | Setup arrêté avant `expire` ; aucune preuve contraire | Garder T0/T1, takeover et assertions attempt/ClaimId ; ne pas introduire un fence sur l'expiration |
| Retries ne masquent pas une violation d'ownership | Aucun classifier/failure/retry invoqué ; exception brute remontée | Aucun retry ou traitement LostClaim de ce refus ; assertion négative dédiée seulement dans un test de contrat |
| Commit/rollback/UNKNOWN et absence de faux succès | Rollback du template de fixture confirmé ; aucun succès Consumption rendu | Observation après résolution ; garder les signaux originaux et ne pas fabriquer COMMITTED |

**Classification principale pour chacune : TEST_SETUP.** Le comportement observé est aussi un rejet attendu **par le protocole** ; cela ne transforme pas ces tests de course en tests négatifs d'owner. Leur intention est d'exercer des courses, et aucune assertion ne demande ici le refus. Remplacer leur corps par `assertThrows(IllegalStateException)` les rendrait verts en supprimant leur objet. **EXPECTED_REJECTION** décrit donc le caractère correct du runtime, mais pas la cause racine à corriger dans cette fixture.

**SPRING_WIRING** est écarté pour les cinq : le bean fourni est précisément le wrapper prévu par le contrat ; la fixture ne doit pas recevoir un participant à la place. **RUNTIME_DEFECT** n'est pas étayé. **INCONCLUSIVE** ne s'applique pas à la causalité des refus, suffisamment prouvée par code et traces ; le résultat des courses après correction reste non vérifié. Nombre de causes racines distinctes : **1**, R1 (et non cinq, ni une confusion avec la cause des six bootstrap).

## R1 — Correction minimale proposée, exclusivement test

Fichier nécessaire : [CommandResultTakeoverRacePostgresTest.java][fixture], y compris ses classes/helper de test imbriqués. Aucun changement requis dans `CommandResultRuntimeConfiguration`, `ConsumptionOwnerTransactionRunner`, `SpringTransactionRunner`, lifecycle, repositories ou stores. Le commentaire de classe doit être aligné sur l'ownership actuel.

**Solution recommandée : conserver les wrappers réels et décorer leur runner uniquement dans la configuration de cette fixture pour observer l'action à l'intérieur du véritable owner.** Un décorateur local de test du bean nommé `commandResultTransactionRunner` (par exemple installé par un post-processeur de la configuration de test, sans modifier sa factory de production) transmet une action instrumentée au runner gardé original :

```text
observeOwner(probe, operation, afterOperation)
  installer un plan de probe privé à cet appel/thread, sans ouvrir de transaction
  appeler le bean public Acquire ou Execute
    runner de test : originalGuardedRunner.runInTransaction(() →
      relever PID/XID et synchronisation, signaler started
      résultat = actionConsumption.get()
      assertions / barrière de test après action si demandées
      éventuellement lever RollbackMarker pour la variante rollback
      retourner résultat au runner original
    )
  retour/exception seulement après résolution du runner original
  retirer le plan de probe dans tous les cas
```

Ce schéma est une proposition, pas du code livré. La décoration s'effectue **autour de l'action donnée à l'owner**, et non après le retour de son `runInTransaction`. La garde originale s'exécute avant le template ; la probe ne crée pas d'ambient. Le corps instrumenté reste dans l'action observée par la garde : une injection RollbackMarker est alors une erreur d'action traversant le rollback physique, pas un suffixe ajouté après le commit. Sans plan de probe, le décorateur délègue sans intervention. Aucun bean public n'est remplacé par un service nu.

Conséquences concrètes dans la fixture :

1. Remplacer `inTransaction` (280–295) par une coordination de probe sans `TransactionTemplate` extérieur ; déplacer lecture PID/XID et enregistrement afterCompletion dans l'action instrumentée du runner. Vérifier l'absence d'ambient avant l'appel public. La preuve de nouvelle transaction vient de cette absence + REQUIRED + état actif/ressource PRIMARY dans l'action ; ne pas garder une assertion `status.isNewTransaction()` sur un statut de template externe supprimé.
2. Appliquer cette correction à **tous** les sites : initialClaim 376 ; Execute/acquire 113–123 ; finalisation/takeover 155–170 ; contenders 229–240 ; rollback/takeover 250–260 ; stale-execute 429–430 ; finish 441. Corriger seulement initialClaim conduirait au prochain refus sous la même cause.
3. Laisser `afterInsert` (320) dans le callback métier, avant provenance/CAS. Déplacer les assertions et barrières actuellement **après** Acquire/Execute dans les transactions externes (155–164, 231–239, 250–256) dans `afterOperation`, à l'intérieur de l'action de l'owner, après retour du service mais avant retour au template. Aucune écriture métier supplémentaire dans cette instrumentation.
4. Garder l'observateur SQL indépendant, les vérifications d'attente serveur, la séparation des XID et les observations durables après résolution. Isoler/nettoyer les plans de probes sur chaque thread de pool, même en exception ; une probe ne doit jamais migrer d'un contender à un autre.

C'est le plus petit périmètre de fichiers qui conserve la preuve de **composition réelle** et les cinq intentions concurrentes. Une simple suppression du template sans déplacement des barrières perdrait les fenêtres avant commit/rollback et ne serait pas une correction complète.

**Alternative contractuellement possible, moins probante pour le wiring :** créer des participants privés au test (`AcquireConsumptionService`, `ExecuteConsumptionService` avec le même finalizer/lifecycle/provenance/clock), garder la fixture comme owner physique explicite par phase et laisser toute exception traverser sa résolution avant toute traduction. Le contrat WP1 §4 autorise un participant sous un autre owner explicite conforme. Cette alternative devrait être présentée comme preuve des participants/SQL, avec preuve séparée des wrappers réels ; elle ne justifie ni d'exposer ces services comme beans publics, ni de retirer la garde. Elle n'est pas la solution recommandée pour ces tests d'architecture de runtime.

**Aucune correction runtime proposée.** REQUIRES_NEW suspendrait l'ambient et ferait committer l'opération avant les barrières/rollback du caller ; les assertions transactionnelles perdraient leur sens. NOT_SUPPORTED/NESTED/savepoints, neutralisation des indicateurs Spring, retrait de garde ou runner non gardé à la place du bean original ne rétabliraient pas le contrat. Il n'y a pas d'annotation transactionnelle à supprimer dans cette fixture. Changer le runtime pour joindre l'ambient compromettrait les résultats après commit et la séparation TE/TH/TR.

### Tests à adapter ou compléter lors de la correction autorisée

- Les **cinq méthodes de la matrice**, sans exclusion : retrouver leurs barrières/attentes et assertions durables complètes ; aucun PASS revendiqué ici après correction.
- Un contrat négatif local dans la même fixture : vrai ambient PRIMARY → chaque entrée owner refuse avant la précondition/callback/SQL de consommation ; état de l'ambient inchangé tant que le caller n'a pas décidé sa résolution. Un compteur de précondition/callback et observations SQL peuvent compléter l'assertion de message. Conserver aussi la couverture synchronisation seule au niveau approprié si absente.
- La probe doit constater le même PID/XID pour owner, JDBC de publication, provenance et lifecycle, puis COMMITTED/ROLLED_BACK avant résultat externe ; deux transactions successives et deux contenders ne partagent pas de contexte. Après perte, aucune ligne stale (y compris le marqueur STALE_CALLBACK) ; CAS et clôture gardent leurs cardinalités.
- Garder les preuves runtime déjà existantes de [CommandResultRuntimePostgresTest][runtime-test], notamment `publicConsumptionEntrypointsRejectPhysicalAmbientTransaction` (53–69), au lieu d'en modifier l'attente. Les XML historiques donnent 5 tests PASS pour cette classe, dont ce refus explicite, sur baseline et livré. C'est une corroboration historique, pas une nouvelle exécution.

Future vérification, **non exécutée par cette mission** : feedback limité à la fixture (`./mvnw -pl architecture-tests -am test -Dtest=CommandResultTakeoverRacePostgresTest -Dsurefire.failIfNoSpecifiedTests=false`, depuis `app/`) ; le filtre n'établit pas le gate global. Pour une correction restant exclusivement test, ancre complémentaire Results `./mvnw -pl runtime-command-result-consumption-worker -am test` pour sa composition, et fermeture du gate architecture requis 4A `./mvnw -pl architecture-tests -am test` après traitement/arbitrage des autres erreurs. Ne pas lancer des slices COMMAND/BINDING/WEB ni le full reactor par réflexe pour une fixture seule ; re-déclarer si la future implémentation modifie réellement leur comportement.

## Conséquences pour le futur owner PRIMARY de 4B

Le plan §§3.3, 4.2, 4.4 et lot 4B est cohérent avec ce diagnostic :

```text
hors ambient : coordination
  TA : Consumption guard → template PRIMARY → acquire → COMMIT (lot vide)
  TE : Consumption guard → owner PRIMARY → template
         participants / publications → provenance → flush CAS S → clôture C
         [action Consumption revenue]
         flush PRIMARY → fermer lot → suffixe expérimental → résolution
  échec TE : rollback confirmé, sortie de la transaction
  TR ou TH : nouveau guard / nouveau owner / nouveau lot / nouvelle transaction
```

Il faut distinguer deux niveaux : l'entrée publique **Consumption reste owner-only**, même si l'ambient appartient déjà à PRIMARY ; le **runner PRIMARY participant** et les services internes peuvent rejoindre leur owner sous contexte OPEN reconnu. La faculté de participation PRIMARY ne donne pas au wrapper Consumption le droit de rejoindre silencieusement cette transaction. La recovery utilise l'Execute participant prévu, pas un nouvel appel au wrapper owner.

Les tests 4B devront : refuser l'ambient Spring sans contexte propriétaire autorisé, même avec bonne datasource ; prouver les participants REQUIRED/MANDATORY dans le même lot/manager/connexion ; fermer et exécuter un seul suffixe au propriétaire ; séparer TA/TE/TR/TH ; préserver COMMITTED/ROLLED_BACK/UNKNOWN, poisoning et cleanup. Aucun contexte de secours sous ambient inconnu, aucune déduction « transaction active = owner compatible ». Le suffixe reste dans le callback du template, après la finalisation et avant résolution physique.

Attention à la différence entre la probe proposée et le suffixe 4B : les barrières de ces courses observent le **service Consumption dans son action** ; le suffixe PRIMARY 4B est **après le retour de l'action observée par la garde**, tout en restant dans le template. Une erreur de ce suffixe peut être classée phase COMMIT avec rollback confirmé selon CURRENT. Les tests de probe ne doivent pas déplacer ce suffixe dans l'action et changer artificiellement sa classification.

CommandResult matérialise `command_results`, ne produit aucune des quatre familles d'Events couvertes par E (plan §5). Ces cinq refus ne découvrent donc pas un producteur autonome oublié ni un nouvel owner producteur à migrer en 4B. Aucun élargissement aux Results ni remplacement global du manager n'est requis. L'owner PRIMARY reste à prouver hors production sur son périmètre autorisé, et la dette des cinq courses doit garder son statut visible jusqu'à correction/vérification séparée.

## Vérification et livraison documentaire

Commandes réellement utilisées : inspections `pwd`, `git status`, `branch`, `rev-parse`, `worktree list`, `remote -v`, `fetch origin feat/consumption-transaction-protocol`, `rev-list`, `log --follow`, `git show`, recherches/lectures `rg`, `cat`, `sed`, `nl`, `cmp` ; Python standard (`pathlib`, `hashlib`, `xml.etree.ElementTree`, `zipfile`, `subprocess`) pour extraire les traces, comparer les six sources aux trois SHA, relever les probes, vérifier les liens et préserver les empreintes ; `jar tf` et `javap -p -c` sur les JAR locaux exacts Spring Boot/ORM ; `git diff --check`, revue du diff, staging explicite de ce rapport, commit et push normal de la branche cible. Le fetch initial a rencontré l'interdiction sandbox d'écrire le FETCH_HEAD commun ; sa reprise autorisée a réussi, divergence 0/0. Une recherche initiale de la classe Boot dans le JAR Hibernate a été corrigée vers son vrai JAR `spring-boot-jpa` ; elle n'est pas une observation de runtime.

Sources Spring locales, aucune consultation web nécessaire : `spring-tx/7.0.6/spring-tx-7.0.6-sources.jar`, `DefaultTransactionDefinition` 81–89 (defaults), `TransactionTemplate` 134–150 (begin/action/rollback/commit), `AbstractPlatformTransactionManager` 373–405 et 575–584 (création/synchronisation) ; bytecode des JAR `spring-boot-jpa/4.0.5` et `spring-orm/7.0.6` (manager et connexion liée). Leur lecture n'ouvre aucune transaction.

**Tests réellement exécutés : NONE. Commandes Maven exécutées : NONE.** Les traces existantes et la garde avant delegate lèvent l'incertitude causale sans instrumentation ni test nouveau. Impact observé production/test/DB NONE ; slices exécutées NONE ; franchissement inattendu NONE ; global architecture gate NOT REQUIRED/non exécuté pour le rapport ; full reactor interdit/non exécuté, diagnostic borné ; base/migrations NONE. Le gate historique garde son état FAIL. Aucun runtime/test modifié pour produire un PASS.

Un seul fichier livré : le présent rapport. Contrôles documentaires PASS : 20 liens locaux valides, blocs Markdown fermés, identité exacte des cinq traces avec les deux XML historiques et des sources causales, préservation par statuts/diffs/empreintes des cinq autres worktrees. Contrôles de livraison complémentaires : whitespace et diff limité au document. Les empreintes et sorties de contrôle de cette session sont dans `/private/tmp/pocoma-consumption-causal-20261020/` ; les extraits nécessaires à la revue sont intégrés ici. Le SHA final du commit contenant ce document, la confirmation du push, la divergence finale 0/0 et le worktree cible propre sont rendus après commit dans la restitution, afin d'éviter une autoréférence Git.

## Annexe — Preuves historiques intégrales et empreintes

XML utilisés, déjà produits par le diagnostic différentiel :

- `/private/tmp/pocoma-diff4a-20261020/baseline/architecture-tests/TEST-com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.xml` ; SHA-256 `f52bbbed2167ca99f38c4e509cd5eb424cfd0f04c20031de85aaf02914f03e89`.
- `/private/tmp/pocoma-diff4a-20261020/delivered/architecture-tests/TEST-com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.xml` ; SHA-256 `a4fb3654895c48f953930cc475770add1a70ab0e99642c7377bede7ad339e853`.

Ces hashes portent sur les fichiers XML complets, incluant leurs différences normales de logs/métadonnées ; les éléments `error` des cinq cas sont comparés intégralement après décodage XML, sans normalisation.

### `twoConcurrentTakeoversInstallOnlyOneCurrentClaim`

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.twoConcurrentTakeoversInstallOnlyOneCurrentClaim(CommandResultTakeoverRacePostgresTest.java:196)
```

```text
TX name=C1-acquire pid=61 xid=803 isolation=read committed
RESOLVED name=C1-acquire pid=61 xid=803 status=1
```

### `inFlightPublicationLosesFenceAndUnblocksWinnerPublication`

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inFlightPublicationLosesFenceAndUnblocksWinnerPublication(CommandResultTakeoverRacePostgresTest.java:109)
```

```text
TX name=C1-acquire pid=61 xid=809 isolation=read committed
RESOLVED name=C1-acquire pid=61 xid=809 status=1
```

### `rolledBackFinalCasReleasesSlotAndAllowsTakeover`

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.resolveFinalCasBeforeTakeover(CommandResultTakeoverRacePostgresTest.java:151)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.rolledBackFinalCasReleasesSlotAndAllowsTakeover(CommandResultTakeoverRacePostgresTest.java:146)
```

```text
TX name=C1-acquire pid=61 xid=815 isolation=read committed
RESOLVED name=C1-acquire pid=61 xid=815 status=1
```

### `committedFinalCasMakesWaitingTakeoverObserveDone`

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.resolveFinalCasBeforeTakeover(CommandResultTakeoverRacePostgresTest.java:151)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.committedFinalCasMakesWaitingTakeoverObserveDone(CommandResultTakeoverRacePostgresTest.java:142)
```

```text
TX name=C1-acquire pid=61 xid=821 isolation=read committed
RESOLVED name=C1-acquire pid=61 xid=821 status=1
```

### `rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo`

```text
java.lang.IllegalStateException: Consumption owner requires no ambient transaction or synchronization
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction(ConsumptionOwnerTransactionRunner.java:25)
	at com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner.runInTransaction(ConsumptionOwnerTransactionRunner.java:28)
	at com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase.acquire(TransactionalAcquireConsumptionUseCase.java:23)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.acquire(CommandResultTakeoverRacePostgresTest.java:307)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$initialClaim$24(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.lambda$inTransaction$18(CommandResultTakeoverRacePostgresTest.java:294)
	at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:137)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.inTransaction(CommandResultTakeoverRacePostgresTest.java:281)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.initialClaim(CommandResultTakeoverRacePostgresTest.java:376)
	at com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest.rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo(CommandResultTakeoverRacePostgresTest.java:245)
```

```text
TX name=C1-acquire pid=61 xid=827 isolation=read committed
RESOLVED name=C1-acquire pid=61 xid=827 status=1
```

### Empreintes des sources causales au SHA initial

Identité byte à byte contrôlée pour chacune entre baseline avant 4A, livraison 4A et SHA initial de ce diagnostic :

| Source | SHA-256 |
|---|---|
| `CommandResultTakeoverRacePostgresTest.java` | `d72cf739bd3fb3de2cbb154e774d5f9789b68843fdcca6972a72d9e1d81b220c` |
| `CommandResultRuntimeConfiguration.java` | `613ccf8331fdf745dededcfa4b75bfc08bb09fe9716f30181c6e186aba28dc31` |
| `ConsumptionOwnerTransactionRunner.java` | `b1a226d14f7861312cb118a3884ff426f4de5ba1d63c2da723ca21e27bc11dcc` |
| `SpringTransactionRunner.java` | `ca9d803b593c0faba8f3da062502895a107f2759fc297515e9c57acb79171504` |
| `TransactionalAcquireConsumptionUseCase.java` | `32922f58d97f6f3b2b0436c3c5dd799f8c2c9357b01715d5c3c35937ebe952f8` |
| `TransactionalExecuteConsumptionUseCase.java` | `38cd602b23551e9866793029d97ded508ebb0eab19c9c773b19e743a4218ddd8` |

[fixture]: ../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultTakeoverRacePostgresTest.java
[wiring]: ../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/result/CommandResultRuntimeConfiguration.java
[owner]: ../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java
[spring-runner]: ../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java
[acquire-wrapper]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalAcquireConsumptionUseCase.java
[acquire-service]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/AcquireConsumptionService.java
[execute-service]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java
[lifecycle]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java
[orchestrator-guard]: ../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerOrchestrator.java
[sequential]: ../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java
[slot-repository]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java
[runtime-test]: ../../app/runtime-command-result-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/result/CommandResultRuntimePostgresTest.java
