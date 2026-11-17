# Consumption — Correction du TEST_SETUP des cinq courses Results

Date : 2026-10-20, Europe/Paris. Mission bornée aux cinq scénarios ; **4B non commencé**.

## Autorités et état initial

Références lues avant modification : [AGENTS](../../AGENTS.md), [politique normative](../testing/Reactor_Verification_Policy.md), [rapport causal faisant autorité](Consumption_Ambient_Transaction_Five_Errors_Causal_Diagnosis_2026-10-20.md), [gate différentiel après 4A](Event_Publication_Contracts_Step_4A_Differential_Diagnosis_2026-10-20.md), [protocole Consumption CURRENT](consumption-transactional-execution.md).

Dépôt `kartaguez/pocoma`, branche `feat/consumption-transaction-protocol`, HEAD initial attendu et réel `376a77d71c169eff4b1d815dc0b492101655e1b8`. Worktree cible `/private/tmp/pocoma-consumption-transaction-protocol`, propre ; tracking `origin/feat/consumption-transaction-protocol`, divergence **0/0**, confirmée par fetch réel avant toute écriture. Inventaire des six worktrees effectué. Le checkout principal `feat/runtime-monolith` à `90926a5e`, divergence 0/2, contient des travaux préexistants ; il est préservé, comme les autres worktrees. Aucun reset, stash, rebase, merge ni push forcé.

## Verification scope déclaré avant implémentation

- Impact production attendu et observé : **NONE**. Un seul fichier Java de test modifié : [CommandResultTakeoverRacePostgresTest](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultTakeoverRacePostgresTest.java), plus ce rapport et ses preuves documentaires.
- Slices fonctionnelles canoniques : **NONE** ; périmètre primaire de preuve `architecture-tests`. Ancre complémentaire Results `runtime-command-result-consumption-worker`, sans inventer une slice canonique supplémentaire. Aucune modification de comportement COMMAND/BINDING/WEB/EVENT/PROJECTION/LKV.
- Preuve locale : cinq méthodes isolées, classe complète et chaîne voisine, ancre Results canonique, puis refus owner ciblé.
- Gate architecture global : **REQUIRED**, explicitement demandé pour constater la disparition des cinq erreurs du gate 4A.
- Full reactor : **NOT REQUIRED, non exécuté** ; aucune clôture de Wave, intégration main ou restructuration.
- DB : **CURRENT**, PostgreSQL Testcontainers jetables ; `Trusted baseline: NONE` dans la politique. Bootstrap existant des suites conservé ; aucune campagne de certification ou de migration historique supplémentaire, aucune base partagée ni modification du schéma.
- Franchissement inattendu : **NONE**. Toute nécessité de modifier le runtime imposait un arrêt pour arbitrage ; aucune n'a été rencontrée.

## Correction de l'ownership et des sondes

L'ancien `inTransaction` créait un `TransactionTemplate` extérieur avant chaque Acquire/Execute public : premier refus dès `initialClaim`, puis même défaut latent aux autres sites. Le template et l'injection de son manager sont retirés de la fixture. `observeOwner` installe uniquement un plan d'observation `ThreadLocal`, exige l'absence de transaction **et** de synchronisation avant l'entrée publique, et retire le plan en `finally`, y compris après échec sur un thread du pool.

Un `BeanPostProcessor` statique, imbriqué dans la seule configuration de cette fixture, décore le bean nommé `commandResultTransactionRunner`. Il exige que le bean original soit un `ConsumptionOwnerTransactionRunner`, puis transmet une action instrumentée à **cet original**. La garde, le template REQUIRED, la résolution physique et la traduction des erreurs restent ceux de production. Sans plan d'observation, la délégation est directe. Aucun hook ni runner de production n'est modifié.

Dans l'action de l'owner réel : la probe lit PID/XID/isolation, vérifie une transaction et une synchronisation actives, enregistre `afterCompletion`, puis exécute les participants réels. La précondition Acquire et le callback Execute retrouvent cette transaction ; après retour du service, la probe vérifie à nouveau son identité avant les assertions/barrières `afterOperation`. JDBC et la connexion JPA courante sont interrogés séparément et doivent rendre le même PID/XID/isolation. L'accès Hibernate `doReturningWork` n'effectue ni flush ORM ni verrou supplémentaire. La probe n'est démarrée qu'une fois par opération. Les observations durables et de résolution restent hors transaction après retour/exception public.

Tous les sites affectés par R1 sont adaptés : claim initial, exécutions/publications, finalisation, contenders Acquire, rollback Acquire, stale-execute et finish. Le callback `afterInsert` reste avant la provenance et le CAS. Aucun callback métier ni DML ajouté ; aucun sleep, retry artificiel, exclusion ou retrait d’assertion métier/concurrente. L’assertion `status.isNewTransaction()` du template supprimé est remplacée par absence d’ambient + runner REQUIRED original + transaction active et identité physique JDBC/JPA dans l’action. Les seules actions ajoutées après les services sont des lectures/assertions/barrières de test, et l'injection de `RollbackMarker` des variantes rollback.

## Correspondance avant/après des cinq preuves

Dans la colonne « avant », il s'agit de l'intention de la fixture : les anciennes exécutions s'arrêtaient toutes au premier Acquire, avant ces entrelacements. Les opérations critiques et les assertions SQL réelles sont conservées.

| Méthode exacte | Entrelacement et synchronisation avant → après | Owner réel / opérations concurrentes / fence | Résultat et assertions conservés |
|---|---|---|---|
| `inFlightPublicationLosesFenceAndUnblocksWinnerPublication` | C1 publie provisoirement puis attend dans `afterInsert`, avant provenance/CAS ; cette barrière reste à cet endroit. Seule la probe quitte le template extérieur et entre dans l'action owner. C2 acquiert après expiration simulée puis tente la même publication pendant la pause C1. | TE C1 ; TA C2 committée distincte ; TE C2. Unicité `command_results(command_id)` bloque TE C2 derrière C1, puis CAS `PENDING/current_claim_id=C1` refuse C1. | `awaitBlocked(...,"publication")` exige une attente PostgreSQL réelle sur INSERT/ON CONFLICT et des PID/XID distincts, observateur tiers ; slot déjà à C2 ; aucune publication visible avant résolution ; LostClaim exact, rollback C1, commit C2 ; `assertFinished` exige résultat unique, provenance gagnante seule et absence du marqueur stale. |
| `committedFinalCasMakesWaitingTakeoverObserveDone` | Assertions DONE/Claim SUCCESS/provenance puis barrière anciennement après Execute dans le template extérieur → `afterOperation` après service/CAS/clôture, dans l'action TE avant son retour. C2 commence Acquire pendant cette pause. | TE C1 garde les verrous du CAS/clôture non committés ; TA C2 attend sur le slot. | Observateur extérieur voit encore PENDING/C1 et aucune publication ; `awaitBlocked(...,"slot")` ; après libération, commit C1 confirmé puis C2 rend AlreadyDone SUCCESS ; historique sans takeover, résultat et provenance exacts via `assertFinished`. |
| `rolledBackFinalCasReleasesSlotAndAllowsTakeover` | Même fenêtre après CAS/clôture ; `RollbackMarker`, auparavant dans le template extérieur, est lancé dans `afterOperation` après libération de la barrière. | TE C1 rollbackée physiquement ; TA C2, déjà en attente sur le slot, poursuit après rollback ; TE gagnante ultérieure distincte. | Rollback probe C1 et exception originale ; commit TA C2 ; `assertTakeover` exige révision 2, tentative 2, nouveau ClaimId, invalidation C1 ; publication/provenance/CAS/clôture de C1 annulés ; aucune publication avant `finish`, puis état final exact. |
| `twoConcurrentTakeoversInstallOnlyOneCurrentClaim` | Départ cyclique après ouverture des deux transactions, avant opérations slot : déplacé dans `beforeOperation`. Assertions d'installation non committée et barrière du gagnant, auparavant après Acquire externe → `afterOperation` dans sa TA. | Une TA autonome par C2/C3 ; Acquire verrouille réellement le slot, un contender attend le gagnant non committé. Puis CAS stale Execute vérifié. | `compareAndSet` du détenteur de barrière ; `awaitBlocked` sur slot ; observateur voit encore C1/tentative 1 ; après commit, exactement un Acquired et un Busy à la lease gagnante ; deux commits, historique de takeover unique ; stale Execute rollbacke ses écritures ; finish gagnant exact. |
| `rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo` | Installation C2, assertions tentative 2/invalidation C1, pause puis `RollbackMarker` : de l'après-Acquire externe vers `afterOperation` dans TA C2. C3 est lancé pendant la pause. | TA C2 provisoire garde le slot ; TA C3 distincte attend puis reprend après rollback C2 ; TE stale du Claim abandonné puis TE gagnante. | `awaitBlocked` sur slot ; observateur voit encore C1 et aucun effet ; rollback C2, commit C3 ; tentative 2 réutilisée, ClaimId différent, Claim abandonné absent ; invalidation C1 exacte ; stale Execute lève LostClaim et ne laisse aucune publication/provenance ; finish exact. |

Le déplacement ne traverse aucune opération critique : les trois barrières après service restent après installation/CAS/clôture et avant résolution physique. La barrière avant publication/provenance/CAS et le départ simultané gardent leur position relative. Les attentes sont établies par `pg_stat_activity`, `pg_blocking_pids` et `wait_event_type='Lock'`, pas par le temps écoulé. L'isolation READ COMMITTED, la distinction owner/waiter/observateur, les états non résolus pendant l'attente et les résolutions physiques restent vérifiés.

## Protection négative réutilisée

[CommandResultRuntimePostgresTest.publicConsumptionEntrypointsRejectPhysicalAmbientTransaction](../../app/runtime-command-result-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/result/CommandResultRuntimePostgresTest.java) existe déjà et reste inchangé. Sous un vrai `TransactionTemplate` PRIMARY, il exige `IllegalStateException` pour les entrées publiques Acquire/Execute/Finalize/HandleFailure/orchestrator présentes, et vérifie que le caller n'est pas marqué rollback-only. Les acquisitions sont explicitement non vides. Cette preuve du runtime réel est réutilisée et exécutée séparément après les classes voisines ; aucun refus attendu n'est transformé en succès.

## Exécutions et résultats

Les commandes suivantes sont exécutées depuis `app/`, séquentiellement et dans l'ordre demandé. Tous les journaux et copies Surefire sont conservés dans `/private/tmp/pocoma-consumption-fix-20261020/` ; les [preuves JSON versionnées](../testing/evidence/Consumption_Ambient_Transaction_Five_Tests_Fix_2026-10-20.json) reprennent les résultats, empreintes XML individuelles, transactions/barrières/attentes et comparaison du gate.

```bash
for method in \
  inFlightPublicationLosesFenceAndUnblocksWinnerPublication \
  committedFinalCasMakesWaitingTakeoverObserveDone \
  rolledBackFinalCasReleasesSlotAndAllowsTakeover \
  twoConcurrentTakeoversInstallOnlyOneCurrentClaim \
  rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo
do
  ./mvnw -pl architecture-tests -am test \
    "-Dtest=CommandResultTakeoverRacePostgresTest#$method" \
    -Dsurefire.failIfNoSpecifiedTests=false
done

./mvnw -pl architecture-tests -am test \
  -Dtest=CommandResultTakeoverRacePostgresTest,CommandResultConsumptionChainPostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -pl runtime-command-result-consumption-worker -am test

./mvnw -pl runtime-command-result-consumption-worker -am test \
  '-Dtest=CommandResultRuntimePostgresTest#publicConsumptionEntrypointsRejectPhysicalAmbientTransaction' \
  -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -pl architecture-tests -am test
```

Le filtre des étapes ciblées n'exclut aucun test du gate final. `surefire.failIfNoSpecifiedTests=false` permet seulement aux modules amont sans méthode correspondante de terminer cette sélection. Un premier feedback du scénario publication a également été exécuté et passé avant l'ajout de la comparaison physique JDBC/JPA ; les cinq méthodes ont ensuite toutes été exécutées individuellement sur la version finale. Aucune relance destinée à masquer une course instable.

| Étape | Résultat |
|---|---|
| Cinq méthodes isolées | **5 × PASS**, une méthode par commande, zéro failure/error/skip |
| Classe corrigée complète | **5 PASS** |
| Voisine `CommandResultConsumptionChainPostgresTest` | **7 PASS** ; total sélection **12 PASS** |
| Ancre Results canonique et fermeture `-am` | **926 tests, 0 failures, 0 errors, 0 skips — BUILD SUCCESS** |
| Refus d'ambient physique ciblé | **1 PASS**, sans modification du test négatif |
| Gate architecture sans filtre | **1257 tests, 0 failures, 6 errors, 1 skip — BUILD FAILURE (sortie 1)** ; module architecture seul : 138 tests, 0 failures, 6 errors, 0 skips |

Lectures SQL observées lors des cinq exécutions individuelles :

| Scénario | Transactions et observation indépendantes | Résolution |
|---|---|---|
| Publication en vol | TE C1 PID 61/XID 804 ; TA C2 PID 62/XID 805 ; TE C2 PID 62/XID 806 ; observateur PID 63, attente publication | C1 ROLLED_BACK, C2 COMMITTED |
| CAS committé | TE C1 61/804 ; TA C2 62/805 ; observateur 63, attente slot | Deux COMMITTED ; C2 AlreadyDone |
| CAS rollbacké | TE C1 61/804 ; TA C2 62/805 ; observateur 63, attente slot ; finish 63/806 | C1 ROLLED_BACK, C2 et finish COMMITTED |
| Deux takeovers | TA C3 60/804 gagnante ; TA C2 61/805 ; observateur 62, attente SELECT FOR UPDATE slot | Deux COMMITTED ; un Acquired/un Busy ; stale 62/806 rollbacké ; finish 62/807 committé |
| Takeover rollbacké | TA C2 61/804 ; TA C3 62/805 ; observateur 63, attente slot | C2 ROLLED_BACK, C3 COMMITTED ; stale 63/806 rollbacké ; finish 63/807 committé |

Ces identifiants sont locaux aux serveurs jetables ; ils ne désignent pas des transactions communes entre méthodes. La réutilisation d'un PID par deux transactions successives n'est pas une participation : leurs XID diffèrent. Dans chaque probe, les lectures JDBC et JPA doivent coïncider ; dans chaque attente, owner et waiter ont des PID/XID distincts et restent non résolus.

## Gate différentiel et limites

Référence avant : gate après 4A au SHA `3a2440326d227645da501a821ad73caad920db42`, **1257 tests, 0 failures, 11 errors, 1 skip**, déjà exécuté et conservé par le diagnostic différentiel. Le diagnostic causal établit l'identité des sources responsables jusqu'au SHA documentaire initial `376a77d7`. Cette campagne historique n'est pas relancée ni présentée comme un nouveau gate du SHA initial.

Après correction : **1257 tests, 0 failures, 6 errors, 1 skip**, gate toujours **FAIL**. Les 1257 cas sont comparés par classe/méthode : exactement **cinq changements ERROR → PASS**, correspondant aux cinq méthodes autorisées ; **aucun autre changement de statut, aucun ajout, aucune suppression**. Les cinq erreurs TEST_SETUP disparaissent ; aucune régression nouvelle n'est observée sur ce périmètre. Le skip préexistant est `TerminalSequencingPostgresTest`, conditionné par `step3b.measure=true` ; aucune exclusion n'est ajoutée.

Les six erreurs résiduelles sont identifiées individuellement :

| Classe | Méthode | Diagnostic |
|---|---|---|
| `CommandCompletionE2EPostgresTest` | `eventFeedsIndependentLkvAndExactProjectionPipelines` | PREEXISTING, datasource/Flyway, SQLSTATE 08001 |
| `CommandCompletionE2EPostgresTest` | `httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions` | PREEXISTING, datasource/Flyway, SQLSTATE 08001 |
| `CommandCompletionE2EPostgresTest` | `admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably` | PREEXISTING, datasource/Flyway, SQLSTATE 08001 |
| `CommandCompletionE2EPostgresTest` | `registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration` | PREEXISTING, datasource/Flyway, SQLSTATE 08001 |
| `DurableEventToProjectionPostgresTest` | `durableEventOutputIsTheIndependentProjectionTaskRuntimeInput` | PREEXISTING, datasource/Flyway, SQLSTATE 08001 |
| `HistoricalEventRouteEvolutionPostgresTest` | `evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence` | PREEXISTING, datasource/Flyway, SQLSTATE 08001 |

Elles échouent au bootstrap `entityManagerFactory → flywayInitializer → FlywaySqlUnableToConnectToDbException → PSQLException → ConnectException`, destination fixe `localhost:5432`, SQLSTATE `08001`, avant les scénarios métier. Aucun serveur de contournement, override de datasource ou masquage n'est introduit. Les six messages et traces d’erreur complets sont identiques à ceux de la campagne historique après 4A, comparaison sans normalisation ; les preuves JSON les conservent.

**Aucun PASS global n'est revendiqué.** Ces six erreurs restent une dette distincte et ne sont pas corrigées par cette mission. Le protocole ne fournit pas une preuve générale d'absence de deadlock ; seules les fenêtres déterministes de ces cinq scénarios sont éprouvées.

## Livraison et état Git

Le diff exact est revu avant staging nominatif et commit. Les fichiers de production, runners, garde, TA/TE/TR/TH, ordre P → B → S, CAS/fencing/retries, POM et migrations sont inchangés. Aucun travail du checkout principal ou des autres worktrees n'entre dans le commit. Le SHA final et la confirmation du push non forcé/divergence 0/0/worktree cible propre sont fournis dans la restitution après commit, sans autoréférence circulaire.

**PASS : les cinq erreurs sont corrigées avec conservation des preuves.** Gate architecture global : **FAIL**, six erreurs datasource/Flyway préexistantes. **4B non commencé.**
