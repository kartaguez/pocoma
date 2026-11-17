# WP0 — Baseline reproductible CURRENT

Date : 2026-10-16. Plan : [protocole générique Consumption](2026-10-16-consumption-generic-transactional-protocol.md), WP0 uniquement. **Verdict : PASS** sous les limites explicites ci-dessous ; aucun WP1/WP2 engagé.

## 1. Baseline Git et périmètre

- Branche conservée : `feat/runtime-monolith`.
- HEAD initial, upstream `origin/feat/runtime-monolith` et SHA serveur : `d0412fd4a35943e545b078a1fcd93f20396bd769`. Divergence initiale `0/0`. Vérification réelle par `git ls-remote`, après échec DNS en sandbox puis succès avec accès réseau autorisé.
- Préexistants exclus du staging : `docker/keycloak/pocoma-realm.json` modifié ; Bruno `01 Create Pot.bru` supprimé, `02`–`07` et `folder.bru` modifiés ; `001 Registrations.bru`, `002 RegistrationResult.bru`, `003 Me-Binding.bru`, `004 Create Pot.bru` non suivis. Ces fichiers sont préservés ; aucun staging global.
- Aucun changement de branche, merge, reset, stash ou force push. Livraison par commit WP0 et push normal si le SHA serveur demeure compatible ; SHA final et résultat de push dans la restitution pour éviter une autoréférence au commit contenant ce rapport.
- Références consultées : politique [Reactor](../testing/Reactor_Verification_Policy.md), [audit générique](../architecture/Consumption_Generic_Transactional_Protocol_Audit_2026-10-16.md), [étude](../architecture/Consumption_Finalization_Unification_Study_2026-10-16.md), [audit locks](../debts/BINDING_LOCK_ORDER_GUARD/Audit_2026-10-16.md), [diagnostics historiques](../debts/BINDING_LOCK_ORDER_GUARD/Verification_2026-10-16.txt). Leur campagne antérieure ne compte pas comme exécution WP0.

## 2. Vérification déclarée et commandes exécutées

Impact production attendu/observé : **NONE**. Primaire COMMAND, secondaire BINDING pour la fixture. Interdits : moteur, repositories, runners, migrations, dépendances et protocole de production. Aucun franchissement inattendu de slice. Gate global **NOT REQUIRED**, non exécuté. Full reactor **NOT REQUIRED**, non exécuté : étape bornée, sans intégration main ni clôture de Wave.

Java Temurin 21, Docker Desktop et PostgreSQL réel `postgres:17-alpine` via Testcontainers. READ COMMITTED attesté dans les deux participants. Politique : Trusted baseline NONE ; bootstrap Flyway existant conservé, jusqu'à primaire V29 / READ V14. Aucun baseline improvisé, DDL de production, `install` ou SNAPSHOT installé. Les fixtures isolées des tests existants peuvent créer leurs tables/triggers. La commande COMMAND canonique exigée par WP0 inclut aussi les tests de migrations déjà présents dans sa fermeture ; aucune campagne historique autonome ajoutée.

Commandes depuis `app/`, chacune avec log distinct ; aucun retry de test automatique :

```sh
# A : CURRENT initial intact, 52 tests
./mvnw -pl runtime-command-consumption-worker -am -Dtest=CommandConsumptionPostgresTest,TransactionalConsumptionExecutionPostgresTest,JpaConsumptionLifecycleAdapterPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
# B : Binding avant puis après qualifier, deux exécutions
./mvnw -pl runtime-binding-consumption-worker -am -Dtest=BindingRuntimePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
# C : COMMAND canonique après modification, correction de sonde, puis correction des fixtures Command
./mvnw -pl runtime-command-consumption-worker -am test
# D : reprise ciblée après correction de la sonde, 52 tests
./mvnw -pl runtime-command-consumption-worker -am -Dtest=CommandConsumptionPostgresTest,TransactionalConsumptionExecutionPostgresTest,JpaConsumptionLifecycleAdapterPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Ordre réel : A ; B avant ; B après ; C première tentative ; D ; C deuxième tentative ; C clôture après qualification des fixtures Command. B après et C première tentative ont compilé la fermeture commune ; D a commencé alors que C terminait ses tests de migration dans des conteneurs distincts. Aucun partage de base entre ces campagnes. Aucun test d'un runtime EVENT/PROJECTION/LKV/Registration/Results n'est revendiqué par la seule compilation de sa dépendance.

A et D : **52 tests, 0 failure, 0 error, 0 skip** : Command 28 + transactionnels 12 + lifecycle 12. B avant : **5 tests, 0 failure, 5 errors, 0 skip**, injection fixture. B après : **5 tests, 0 failure, 0 error, 0 skip**. C première tentative : arrêt dans infra-primary, **178 tests, 0 failure, 3 errors, 0 skip dans ce module** : erreur ajoutée par WP0 `value()` sur un UUID dans la sonde, corrigée en passant directement `slotId()`. Ce n'est ni un défaut CURRENT ni un deadlock absent ; D repasse les trois scénarios. C deuxième tentative : **857 tests, 0 failure, 5 errors, 0 skip**, D17 et témoins PASS, mais cinq erreurs d’injection dans les fixtures runtime Command (4 + 1). C clôture : **857 tests dans 185 classes, 0 failure, 0 error, 0 skip, BUILD SUCCESS** ; durée 1 min 38 s. Les cinq tests runtime Command supplémentaires sont débloqués.

Les [diagnostics WP0](2026-10-16-consumption-generic-transactional-protocol-wp0-verification.txt) conservent résultats par classe, hashes SHA256 des logs locaux, PID/XID, états persistés, contraintes et journal serveur. Aucun build vide n'est pris pour preuve : les trois méthodes de concurrence sont nommées dans les tests exécutés, avec leurs traces SQL.

## 3. D17 reproduit sur CURRENT

Test retrouvé dans `c839e780ffe6767b50b4e72ba85143fbe36e86bc`, encore présent au HEAD de référence : `CommandConsumptionPostgresTest.terminalFailureAndStaleProvenanceFormARealPostgresDeadlock`, helper `characterizeOutcomeSlotWait(true, true)`. A l'a exécuté avant toute modification de cette classe : exactement un `40P01`, victime C2. D conserve le même entrelacement et le même runtime, avec assertions/diagnostics supplémentaires.

| Phase | Observation et assertion WP0 (campagne D) |
|---|---|
| 1. Expiration C1 | Horloge contrôlée de `08:00:00Z` à `08:00:30Z`, égalité explicite avec `leaseUntil` ; aucune attente murale ajoutée |
| 2. Takeover C2 committé | Après retour du runner Acquire, lecture séparée du slot courant C2 et invalidation persistée C1 ; trace `takeover=committed` |
| 3. Outcome C1 provisoire | Vrai INSERT JDBC outcome + terminal event, latch avant retour ; lecture observatrice voit zéro résolution |
| 4. Failure C2 | Execute C2 entre dans le callback puis lève NPE contrôlée ; wrapper rollbacke avant classifier/HandleFailure terminal réel |
| 5. Slot détenu | Finalize CURRENT prend `FOR UPDATE` ; pendant l'attente outcome, une connexion observatrice `FOR KEY SHARE NOWAIT` sur S reçoit `55P03`, puis rollback immédiat |
| 6. Attente outcome C2 | `pg_blocking_pids`, `pg_stat_activity`, SQL `INSERT command_outcomes`, attente Lock ; C2 PID 61/XID 1056 attend C1 PID 62/XID 1055 |
| 7. Provenance C1 | Libération du latch seulement après observation de l'attente réelle ; flush JPA `INSERT consumption_inputs` |
| 8. Attente réciproque | Journal serveur : PID 61 attend transaction 1055 détenue par 62 ; PID 62 attend transaction 1056 détenue par 61 ; les deux INSERT sont consignés |
| 9. Détection | Exception JDBC `SQLSTATE=40P01 ERROR: deadlock detected`, exactement un participant victime, ici C2 ; C1 finit par `LostClaimException` |

La sonde NOWAIT est purement observatrice : elle ne rejoint pas la file d'attente, ne détient aucun verrou de ligne du cycle, et rollbacke avant de libérer C1. Aucun nouveau lock bloquant, retry ou sleep ne fabrique la concurrence. Le cycle est déjà reproduit dans A avant ajout de cette sonde.

```text
LOCK_AUDIT concurrent=[{pid=61, xid=1056, wait_event_type=Lock}, {pid=62, xid=1055, wait_event_type=Client}]
LOCK_AUDIT slot KEY SHARE NOWAIT=55P03 while C2 waits for C1 outcome
LOCK_AUDIT SQLSTATE=40P01 ERROR: deadlock detected
Process 61 waits for ShareLock on transaction 1055; blocked by process 62.
Process 62 waits for ShareLock on transaction 1056; blocked by process 61.
Process 61: insert into command_outcomes ...
Process 62: insert into consumption_inputs ...
LOCK_AUDIT scenario=terminal-failure provenance=true ... deadlocks=1 victim=current
```

Le journal identifie l'insertion de provenance ; le catalogue atteste sa FK immédiate vers S, la sonde atteste l'incompatibilité KEY SHARE sur S, et le code CURRENT identifie le détenteur FOR UPDATE. Ces preuves combinées établissent l'arête FK ; le diagnostic serveur de cette victime ne nomme pas lui-même le trigger FK.

Contraintes cataloguées et validées : `fk_consumption_inputs_slot`, `fk_consumption_results_slot`, `uk_consumption_inputs_identity`, PK input/result, `command_outcomes_pkey`, FK outcome→recorded_command et checks. `session_replication_role=origin` ; aucune désactivation des FK ou de l'unicité.

## 4. États persistés après rollback

Campagne D : slot `d2f9b617-225f-4717-bd80-1dfde371da71`, C1 `76188222-0193-4b02-aaa5-c09c732ef2f3`, C2 `b4f465ac-b001-4326-ad84-bbab6d9b51e0`.

- S : revision 2, PENDING, C2 courant, aucun terminalOutcome/terminalReason/doneAt.
- C1 : tentative 1, invalidation à `08:00:30Z`, raison TAKEN_OVER, aucun endedAt/failure.
- C2 : tentative 2, lease jusqu'à `08:01:00Z`, toujours ouvert, aucun endedAt/failure.
- Aucun outcome ou terminal event de Q ; zéro ligne dans la table d'effets de fixture, `consumption_inputs` et `consumption_results`.

Rollback de C2 victime : son échec terminal ne laisse aucune clôture ni publication partielle. Rollback de C1 après perte du CAS : outcome APPLIED, event, effet de fixture et provenance disparaissent ensemble. Le takeover committé reste intact. CURRENT protège ici l'intégrité mais nécessite une reprise ultérieure ; aucun retry de reprise n'a été ajouté à D17. Si PostgreSQL choisit C1 victime lors d'une autre exécution, le test accepte C2 gagnant seulement avec résolution terminale unique/cohérente ; la campagne archivée observe C2 victime.

## 5. Tests témoins exécutés, distincts de D17

| Garantie témoin | Méthodes actuelles exécutées dans A et D |
|---|---|
| D16 recovery sans provenance | `bindingRecoveryWaitingOnStaleOutcomeDoesNotDeadlock` : deux XID, vraie attente outcome, zéro 40P01, C1 LostClaim, C2 DONE/REJECTED, raison CALLER_IDENTITY_NOT_CURRENT |
| D18 Execute stale sans provenance | `terminalFailureWaitingOnStaleOutcomeDoesNotDeadlock` : zéro 40P01, C1 LostClaim, C2 DONE/FAILED, claim PROCESSING_FAILURE |
| Recovery après perte du claim | `lostClaimDuringPostRollbackCheckNeverTerminalizes`, `lostFenceWithStillCurrentExactBindingIsTechnicalRetryAndNeverPublicRejection` |
| Takeover/effets stale | `takeoverRollsBackTheLosingCommandAndOnlyTheWinnerCommits`, `staleWorkerRollsBackEverythingAndTakeoverWinnerCommits`, `takeoverIsAtomicAndFencesThePreviousClaim`, `concurrentLazyCreationProducesOneSlot` |
| Atomicité/visibilité | `successfulExecutionBecomesVisibleAsOneCommit`, `shortFinalizationRollsBackTheEffectWhenCompletionCannotCommit`, `terminalFailureCommitsFailedAfterBusinessRollback`, `staleClaimCannotPublishATerminalFailureOutcomeOrEvent` |
| Provenance | `provenanceJoinsTheCallingTransactionAndRollsBack`, `provenancePersistenceExceptionRollsBackBusinessAndProvenance`, `crossSlotProvenanceRollsBackBusinessEffects` |
| Transactions | `technicalExceptionRollsBackThenRetryPolicyRunsInASeparateTransaction`, assertions READ COMMITTED et XID concurrents D16/D17/D18 |

L'exactitude des contenus outcome/event est contrôlée par `assertTerminalResolution` ; les contraintes de schéma restent actives. Les rapports par classe et résultats supplémentaires de la fermeture canonique figurent dans les diagnostics. Aucun résultat historique ajouté aux compteurs WP0.

## 6. Diagnostic et résolution Binding

Erreur actuelle reproduite : `NoUniqueBeanDefinitionException` au champ de test `@Autowired TransactionRunner transactions`. Deux candidats :

1. `transactionRunner`, déclaré par `SpringTransactionRunnerConfiguration`, configuration scannée dans le contexte de l'application ; `@ConditionalOnMissingBean(TransactionRunner.class)` n'est pas une règle de priorité et ne retire pas rétroactivement une définition déjà enregistrée.
2. `bindingTransactionRunner`, déclaré par `BindingRuntimeConfiguration` importée, explicitement sélectionné par `bindingAcquire`, `bindingExecute`, `bindingFailure`.

Les deux construisent `SpringTransactionRunner(new TransactionTemplate(manager))`. Le type seul est ambigu ; le nom de champ `transactions` ne correspond à aucun candidat, aucun qualifier/Primary ne tranche. L'application démarre ; c'est l'injection de la fixture qui échoue. Runner voulu sans ambiguïté : **bindingTransactionRunner**, identique à celui des use cases Binding exercés.

Correction Binding : ajout d'un import et de `@Qualifier("bindingTransactionRunner")` sur ce seul champ de test. Aucun bean, runner, propagation ou annotation globale de production modifié. Les cinq méthodes passent réellement sur PostgreSQL :

- `attachDetachReattachTraversesAuthorityFactConsumptionAndCurrentBinding`
- `lateCommitBeforeTheEphemeralCursorIsFoundOnTheNextScan`
- `baselineFactsRebuildAttachedDetachedAndReattachedWithoutPrimaryBootstrap`
- `technicalFailureRetriesAfterAReconstructedScanAndThenFinalizesIdempotently`
- `lostClaimRollsBackProjectionBeforeWinnerAndMultipleWorkersConverge`

Limite préexistante signalée : la dernière méthode utilise déjà `Thread.sleep(10)` pour dépasser une lease de 1 ms ; WP0 n'ajoute aucun délai et n'utilise pas cette méthode comme preuve déterministe du cycle D17. D17 et ses deux témoins utilisent horloge contrôlée, latches et observation SQL.

### Défaut de fixture également révélé par la clôture COMMAND

La deuxième commande canonique atteint `CommandConsumptionRuntimePostgresTest` (4 tests) et `CommandConsumptionMultiWorkerPostgresTest` (1). Même échec sur leur champ `transactions` : candidats `transactionRunner` et `commandConsumptionTransactionRunner`. Le wiring `CommandConsumptionRuntimeConfiguration` qualifie déjà ce dernier pour ses wrappers. Deux qualifiers de champ et leurs imports sont ajoutés aux seules fixtures ; aucune production modifiée, aucun changement de slice. La troisième exécution canonique vérifie cette correction et clôture COMMAND. Ce défaut local supplémentaire n'est pas imputé au cycle D17.

## 7. Matrice des garanties CURRENT

| Domaine | Preuve actuelle | Portée / limite |
|---|---|---|
| D17 CURRENT | Deadlock réel unique `40P01`, journal croisé outcome/input, sonde S | Cas input de provenance, victime C2 observée |
| Takeover | C2 committé visible, C1 TAKEN_OVER ; tests de fencing/concurrence | Pas preuve générale de fairness |
| Stale Execute | Zéro effet persistant après rollback, perte CAS réelle | Invocation provisoire permise en CURRENT Execute |
| Outcome | PK active, resolution unique outcome/event et rollback | Pas de nouvelle preuve de toutes les erreurs tardives TARGET |
| Provenance | FK immédiates actives, input impliqué dans D17 ; rollback input/result témoins | D17 results seuls / les deux non exécutés |
| Binding | Cinq tests PostgreSQL exécutables et PASS après qualifier fixture | Autorité WRITE et projection READ restent distinctes |
| Transactions | REQUIRED/DEFAULT au wiring, READ COMMITTED observé ; rollback puis failure séparée ; JDBC/JPA atomiques dans témoins | Pas de transaction ambiante dans cette campagne ; garde owner/participant future à définir |
| Tests témoins | D16/D18 zéro deadlock, 49 autres tests ciblés hors D17 | Les scénarios D17 attendus ne sont pas des failures inattendues |

## 8. Inventaire CURRENT des huit assemblages et préparation WP1

Inventaire de conception recoupé avec configurations, moteur et références ; aucune campagne runtime des six autres familles revendiquée ici. Toutes les configurations utilisent le manager injecté et TransactionTemplate REQUIRED/DEFAULT ; wrappers participants, aucun REQUIRES_NEW introduit.

| Assemblage | Callback / ordre d'effets CURRENT | Anchor / preuve future |
|---|---|---|
| Command | `CommandConsumptionExecution` : métier Pot/Events → fence Binding → outcome/event → provenance → CAS/claim ; failure et recovery prennent S avant outcome | COMMAND, T01/T03/T05/T08 |
| Registration | `ExecuteRegistrationService` : stream → initializer User/fact → occurrence/actif/fact → outcome → input → CAS/claim | complément runtime-registration-consumption-worker, T02/T09/T10 |
| Binding | `MaterializeCurrentBindingService` : fact immutable → UPSERT READ monotone → input → CAS/claim | BINDING, T02/T09/T10 |
| Event | `ProjectionMaterializationConsumptionService` : préparation puis Finalize S → ensure tasks → CAS/claim | EVENT, T06/T11 |
| ProjectionTask | `ProjectionTaskConsumptionService` : préparation pure hors finale ; Finalize S → root/artifacts ou failure → CAS/claim ; Temporary vers retry | PROJECTION, T04/T06/T11 |
| Command Result | locator : source immutable → ensureResult comparé → input event → CAS/claim | complément runtime-command-result-consumption-worker, T05/T09 |
| Registration Result | locator : source immutable → ensureResult comparé → input outcome → CAS/claim | complément runtime-registration-result-consumption-worker, T05/T09 |
| LKV | locator : UPSERT monotone → input Event → CAS/claim ; succès métrique afterCommit | LKV, T09/T10/T11 |

Les tests effectivement exécutés WP0 couvrent T01 CURRENT et les témoins transactionnels/rollback/lifecycle existants ; ils ne clôturent pas T01 TARGET ni les extensions T02–T11 des WP suivants. Les anchors complémentaires ne sont pas de nouvelles slices canoniques.

## 9. Points non prouvés et préconditions ouvertes pour WP1

WP0 n'établit ni l'absence universelle de deadlock, ni la progression bornée, ni le coût en charge, ni un E2E HTTP Pot. Variantes D17 results seuls / inputs+results, recovery négative avec provenance, files/upgrades et erreurs tardives restent dans les WP prévus. Aucune garantie TARGET n'est acquise. La baseline PostgreSQL expérimentale ici n'est pas une certification de baseline de migrations.

Pour engager WP1 lors d'une mission séparée : conserver ce témoin CURRENT et ses témoins sans provenance ; formaliser D01–D10 du plan, API terminale/provenance et cardinalité de clôture ; définir owner/participant et garde ambiante sans casser la recovery imbriquée REQUIRED ; inventorier les substitutions d'assertions Event/Projection et les barrières flush avant CAS. Ne pas migrer le runtime avant clôture WP1 et les preuves prévues WP2. Aucun blocage environnemental ou Binding restant pour commencer cette conception.

## 10. Verdict et livraison

**PASS — baseline CURRENT suffisante pour comparaison expérimentale : D17 explicitement reproduit, rollback des perdants observé, témoins distincts, cinq tests Binding débloqués.** Ce verdict clôt exclusivement WP0. WP1/WP2 restent non commencés. Production, schéma, audits historiques et statuts de dettes inchangés.

Commit des tests : `2a9d8f3f1a1d0830e01c4f42a9f338b4361bc0c8` (production identique au HEAD initial). Le commit documentaire suivant archive le présent rapport et les preuves.

Modifications livrées : diagnostics/assertions de la caractérisation existante ; qualifiers des fixtures Binding et Command ; présent rapport, diagnostics archivés et lien de suivi dans le plan. Aucun gate global ni full reactor ; aucun franchissement de slice. Commit/push normal sur la branche courante, fichiers préexistants exclus.
