# Étape 2C — Intégration du fence Binding partagé dans TE

Date : 2026-10-18. Verdict borné fence : **PASS**. Verdict différentiel et de livraison 2C : **PASS sur son périmètre**, gate global **FAIL — non vert**, dette préexistante indépendante démontrée. Baseline : `8a79f06d7a551b72e00f65cd77ec1a6c8bc118e0`, branche `feat/consumption-transaction-protocol`.

Cette intégration prolonge la [preuve expérimentale A–G](Pot_Command_Binding_Shared_Fence_PostgreSQL_Proof_Step_2C_2026-10-18.md), conservée sans modification des tests. Elle applique son protocole au vrai port utilisé par `ExecuteRecordedCommandService`. Les [frontières 2A](Pot_Command_Transaction_Boundaries_Audit_Step_2A_2026-10-18.md) et les [contrats CAS/retry 2B](Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md) restent les références historiques.

## Périmètre déclaré avant implémentation

Impact prévu et observé : primitive JDBC du fence Command, documentation du port existant, tests COMMAND. Slice primaire **COMMAND**, secondaire **NONE**. Aucun changement du comportement lifecycle, des dépendances, du modèle, du schéma ou des migrations. Interdits : réorganisation P → B → S, retry local, transaction autonome, modification des autres runtimes. Aucun franchissement inattendu de comportement production. Déclaration initiale : gate global NOT REQUIRED.
Révision explicite pendant la revue : `Wa67BindingArchitectureTest` imposait deux fois le SQL exclusif
`SET current_revision = s.current_revision`. Cette garde structurelle doit suivre le nouveau fence et
vérifier SHARE, la validation fraîche séparée et l'ordre lifecycle préservé. Élargissement de vérification
annoncé avant modification : gate architecture global **REQUIRED et exécuté** pour aligner ces gardes
avec le protocole livré. Aucune frontière de module ou dépendance modifiée. Reactor complet **NOT REQUIRED et non exécuté**. Prochain gate global : intégration majeure/Wave ou main selon la politique normative ; aucune clôture de Wave revendiquée ici.

Base **CURRENT** : PostgreSQL Testcontainers `postgres:17-alpine` (17.11 observé), isolation READ COMMITTED, Java 21. La politique indique `Trusted baseline: NONE` : bootstrap Flyway existant conservé, sans nouveau gate de migrations historiques.

## Ancien et nouveau SQL

Ancien `fenceExact`, exclusif entre commandes sur le même stream :

```sql
update external_identity_binding_streams as s
set current_revision = s.current_revision
where s.issuer = ? and s.subject = ? and s.current_revision = ?
  and exists (select 1 from external_identities as a
              where a.issuer = s.issuer and a.subject = s.subject
                and a.user_id = ? and a.binding_id = ?);
```

Nouveau SQL exact, deux appels JDBC séquentiels dans la transaction appelante :

```sql
select issuer, subject, current_revision
from external_identity_binding_streams
where issuer = ? and subject = ? for share;

select count(*)
from external_identity_binding_streams s
join external_identities a on a.issuer = s.issuer and a.subject = s.subject
where s.issuer = ? and s.subject = ? and s.current_revision = ?
  and a.user_id = ? and a.binding_id = ?;
```

Stream absent → false. Sinon, seul count=1 valide le fence. La révision retournée par la lecture verrouillante ne constitue pas la validation finale. Le second statement prend un snapshot frais après l'acquisition du verrou, y compris après une attente de detach/reattach. Fusionner les statements réintroduirait une dépendance injustifiée au snapshot commencé avant l'attente. Le test historique `BindingOptimisticCasSemanticsPostgresTest` conserve son contre-exemple de sous-requête UPDATE ; aucune conclusion sur d'autres niveaux d'isolation.

`JpaExternalIdentityBindingAdapter.fenceObservedBinding` reste `@Transactional(MANDATORY)` sans nouvelle frontière. JdbcTemplate utilise la connexion transactionnelle Spring commune aux écritures JPA. Le propriétaire reste `ConsumptionOwnerTransactionRunner` / `SpringTransactionRunner`. Aucun commit, suspension, retry ou release explicite du verrou n'est ajouté. Le verrou dure jusqu'à la résolution physique COMMIT/ROLLBACK, y compris après le retour du port ou un clear du persistence context.

## Ordre et writers

Ordre conservé : reload commande, observation Binding sans lock, relecture V/contextes, préconditions O/V, CAS **P**, DML temporel/metadata, append et flush BusinessEvents, fence **B**, outcome/TerminalEvent, provenance/flush, CAS **S**, clôture du claim, résolution physique TE. Les rejets métier passent aussi par B quand une occurrence a été observée. O durable reste immuable ; aucune invocation/contextes/Events d'une tentative rollbackée n'est réutilisée.

Inventaire des writers de production : `ExternalIdentityJdbcRepository` possède les INSERT/DELETE de l'actif ; `JpaExternalIdentityBindingAdapter.acquireWithInitializer` et `detach` les appellent seulement après `streams.lock` (`FOR UPDATE`). `acquire` délègue à ce chemin ; Registration utilise `acquireWithInitializer`. Les réservations d'occurrence, avances de révision et facts suivent la même autorité dans la même transaction. La façade `JpaExternalIdentityBindingStreamAdapter` expose seulement create/find, n'a pas de consommateur de production et ne permet pas de muter une occurrence. Le stream reste permanent. Aucun nouveau verrou global ou lock exclusif sur l'actif. Les writers externes qui contournent cette discipline restent hors garantie.

Matrice des verrous de ligne PostgreSQL (✓ compatible ; × conflit) :

| Demande / détenu | KEY SHARE | SHARE | NO KEY UPDATE | UPDATE |
|---|---|---|---|---|
| KEY SHARE | ✓ | ✓ | ✓ | × |
| SHARE | ✓ | ✓ | × | × |
| NO KEY UPDATE | ✓ | × | × | × |
| UPDATE | × | × | × | × |

La matrice est celle documentée dans la preuve expérimentale ; SHARE/SHARE et SHARE/UPDATE sont éprouvés ici dans TE. Les FK peuvent prendre KEY SHARE, compatible avec SHARE. Elles ne remplacent aucune autorité applicative. Le fence reste après le flush Pot/Events et avant les FK de provenance et le CAS S ; les mécanismes de flush automatique et les contraintes ne changent pas.

## Preuves de bout en bout

Classe : [PotCasConsumptionPostgresTest](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/PotCasConsumptionPostgresTest.java). Application COMMAND réelle, décodage/dispatch/factory Pot, proxies Spring/JPA, outbox, publication d'outcome, finalisation et recovery réelles. Le décorateur de test délègue le fence au vrai adaptateur ; il ajoute uniquement les barrières et assertions de transaction.

| Scénario | Interleaving et assertions |
|---|---|
| Deux Pots, même E/B, 5 répétitions | Deux commandes réelles arrêtées après leur vrai B, avant résolution. PID et XID distincts ; versions externes encore à 1. Premier commit rend seulement P1 visible. Deuxième commit rend P2 visible. Chaque commande laisse exactement 2 versions/header/metadata/Events au total, un outcome, un TerminalEvent, un input, un result et un claim clos. |
| Detach derrière les deux B, mêmes 5 répétitions | `pg_blocking_pids` prouve le blocage du vrai detach derrière A, puis derrière B après commit A. Detach termine après commit B. |
| Detach/reattach pendant l'attente, 5 répétitions × 2 branches | TE a déjà flushé ses mutations et son BusinessEvent ; arrêt avant B. Writer exclusif modifie l'autorité sans committer ; B est prouvé bloqué par son PID. Après commit lifecycle, ancien BindingId refusé même pour le même U. Rollback TE confirmé ; compteur/header/metadata/outbox restaurés ; aucune provenance ; recovery publie un unique rejet CALLER_IDENTITY_NOT_CURRENT dans un autre XID. |
| Lifecycle committé avant B, 2 branches | Même preuve de rollback et recovery, avec commit detach ou detach/reattach terminé avant de libérer B. |
| Takeover après B, 5 répétitions | Vrai takeover à l'expiration logique du lease pendant que TE tient SHARE. Detach bloqué par TE. Ancien claim perd CAS S ; rollback de tous ses effets et publications, aucune provenance durable ; detach libéré après rollback. Nouveau claim préservé puis exécuté, rejetant l'occurrence détachée. |
| Fence exact et propagation | Port hors transaction refusé ; stream absent, mauvais R, mauvais U ou mauvais BindingId refusés ; tuple exact accepté. |
| Non-régressions 2B, 9 tests conservés | CAS P perdu via le proxy JPA, rollback, nouveau claim et contexte à V frais, O immuable, succès/rejet obsolète ; budget quatre tentatives et 1/5/30 ; recovery/conflict Binding, classifications, anomalie temporelle, COMMITTED/UNKNOWN. |

Les nouvelles barrières sont des CountDownLatch avec échéance dix secondes, les transactions sont indépendantes. Les Future.get et terminaisons de pool ont des délais bornés ; les attentes SQL sont observées par PID, sans Thread.sleep. `statement_timeout` local vaut douze secondes sur les TE coordonnées. Le XID et le PID sont identiques avant/après le vrai fence ; le XID de la barrière est celui des lignes temporelles et de l'outbox réellement flushées (`xmin`). Deux lignes header portent ce XID : fermeture de l'ancienne version et insertion de la nouvelle. Les callbacks afterCompletion confirment COMMITTED ou ROLLED_BACK. Le takeover est placé après B et avant provenance, sans supposer que les FK empêchent tout takeover.

## Résultats et commandes

Commandes exécutées depuis `app/` :

```bash
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=BindingSharedFencePostgresTest,JpaExternalIdentityBindingLifecycleAdapterPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=PotCasConsumptionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl architecture-tests -am test
# Diagnostic initial dans une extraction git archive, puis confirmé dans un worktree Git isolé au même SHA :
./mvnw -pl architecture-tests -am test -Dtest=DurableEventToProjectionPostgresTest,HistoricalEventRouteEvolutionPostgresTest,CommandResultTakeoverRacePostgresTest,CommandCompletionE2EPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

| Passe | Tests | Échecs | Erreurs | Skips |
|---|---:|---:|---:|---:|
| Fence ciblé + lifecycle | 30 | 0 | 0 | 0 |
| E2E final avec assertions xmin | 26 | 0 | 0 | 0 |
| COMMAND canonique — 191 classes, 51 modules, 1 min 48 s | 981 | 0 | 0 | 0 |
| Gate architecture et fermeture Maven — 239 classes, 61 modules | 1214 | 0 | 11 | 0 |
| Reproduction au SHA initial — quatre classes ciblées | 11 | 0 | 11 | 0 |

Les 26 E2E et 28 expérimentaux sont inclus dans les 981 tests ; ces nombres ne s'additionnent pas
comme tests distincts. Les trois nouveaux scénarios sensibles ont chacun dix répétitions réussies
sur E2E final + COMMAND ; la branche attente detach/reattach est exercée pour les deux variantes.

Extrait de la passe COMMAND :

```text
Real TE PostgreSQL wait: pid=63 blocked by pid=61
Real TE PostgreSQL wait: pid=63 blocked by pid=62
Real TE shared B: pid=61 xid=1167 / pid=62 xid=1168; detach=63 waited for both
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
```

Les assertions, et non ces seules traces, prouvent que les deux commandes ont franchi le vrai B
avant tout commit, puis que le même detach attend chaque résolution. Les observations détaillées,
les résultats par classe, les passes intermédiaires et la liste exacte des fichiers figurent dans
la [preuve machine](../testing/evidence/Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.json).
Logs locaux non versionnés : `/private/tmp/pocoma-2c-integration-{fence,e2e,e2e-final,e2e-corrected,command,architecture}.log`.

Incident de vérification : première invocation sandbox sans accès Docker échoue avant preuve PostgreSQL ; même commande autorisée avec Docker : 30 PASS. Première passe E2E : 24 PASS. Une assertion xmin ajoutée ensuite comptait initialement un header au lieu des deux lignes fermeture/insertion ; elle empêchait la barrière et contaminait les fixtures suivantes. Correction du comptage, sans changement runtime ni affaiblissement du protocole ; les passes suivantes font foi.

## Diagnostic différentiel et dette du gate global

Le gate global reste **FAIL**, jamais PASS global : 1214 exécutions, 1203 réussites,
0 assertion échouée, 11 erreurs, 0 skip ; architecture-tests : 132 tests, 121 réussites,
11 erreurs. `Wa67BindingArchitectureTest` (5) et `BindingLockOwnershipTest` (4) passent.
Cette mission autorise la clôture **PASS du périmètre 2C** si les erreurs sont préexistantes
et indépendantes ; cette condition est maintenant démontrée. Le verdict INCONCLUSIVE du
rapport initial est donc levé pour la livraison 2C, sans rendre le gate global vert.

Baseline fraîche : véritable worktree Git détaché `/private/tmp/pocoma-2c-differential-baseline`,
HEAD `8a79f06d7a551b72e00f65cd77ec1a6c8bc118e0`, sources non modifiées et statut Git propre.
La commande ciblée des quatre fixtures ci-dessus y a été exécutée une fois : code Maven 1,
11 tests, 0 failure, 11 errors, 0 skip, 25,109 s. L'ancien diagnostic git archive reste une
preuve supplémentaire, pas le substitut du worktree demandé. Côté intégration : réutilisation
du gate canonique déjà exécuté sur ce même HEAD + les 12 fichiers 2C non indexés.
Aucun nouveau gate global ni nouvelle slice COMMAND complète : aucun code ou test modifié
pendant ce diagnostic. Les hashes des quatre sources app modifiées et des logs figurent dans
la preuve machine ; leurs dernières modifications précèdent les passes retenues.

La comparaison porte sur l'ensemble exact `(classe, méthode)` et le **texte intégral de chaque
exception et stack trace**, strictement égal sans normalisation. Les quatre fixtures et les sources
responsables (owner, wrapper acquire, configuration datasource) sont identiques octet par octet
au SHA initial. Aucune erreur nouvelle, différente, disparue ou non déterminée.

Codes de cause / condition de reproduction :

- **D** : `BeanCreationException entityManagerFactory → flywayInitializer →
  FlywaySqlUnableToConnectToDbException → PSQLException → java.net.ConnectException: Connection refused`,
  SQLSTATE `08001`, tentative `localhost:5432`. Le builder fournit l'URL du conteneur via
  `.properties` (propriétés par défaut), mais la configuration chargée utilise la datasource locale.
  Reproduit avec la configuration existante et aucun serveur à cette adresse. Le contexte échoue
  au démarrage, avant l'exécution Command et tout appel au fence. Aucun SQL du fence ne peut
  causer cette initialisation. La preuve est limitée à cet environnement de reproduction.
- **T** : `IllegalStateException: Consumption owner requires no ambient transaction or synchronization`.
  `initialClaim:376 → inTransaction:281 → TransactionTemplate.execute:137 → lambda$inTransaction$18:294
  → lambda$initialClaim$24:376 → acquire:307 → TransactionalAcquireConsumptionUseCase.acquire:23
  → ConsumptionOwnerTransactionRunner.runInTransaction:28 → requireNoAmbientTransaction:25`.
  La fixture ouvre une transaction puis invoque l'owner qui interdit toute transaction/synchronisation
  ambiante. Échec pendant le claim initial, avant le travail testé et tout fence Binding.

Préfixes de package : `ccr` = `com.kartaguez.pocoma.architecture.ccr`,
`ept` = `com.kartaguez.pocoma.architecture.ept`. Chaque ligne ci-dessous référence sa trace
pertinente ; les deux traces complètes et leurs empreintes de rapport sont conservées dans
`differentialDiagnosis.matrix` de la preuve machine.

| # | Classe | Méthode | Cause / frame de fixture | Baseline | Intégration | Classification |
|---|---|---|---|---|---|---|
| 1 | `ccr.CommandCompletionE2EPostgresTest` | `admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably` | D — `commandContext(:531) ; admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably(:153)` | ERROR | ERROR | identique |
| 2 | `ccr.CommandCompletionE2EPostgresTest` | `eventFeedsIndependentLkvAndExactProjectionPipelines` | D — `cleanCommandContext(:537) ; eventFeedsIndependentLkvAndExactProjectionPipelines(:406)` | ERROR | ERROR | identique |
| 3 | `ccr.CommandCompletionE2EPostgresTest` | `httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions` | D — `webContext(:598) ; httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions(:257)` | ERROR | ERROR | identique |
| 4 | `ccr.CommandCompletionE2EPostgresTest` | `registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration` | D — `webContext(:598) ; registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration(:325)` | ERROR | ERROR | identique |
| 5 | `ccr.CommandResultTakeoverRacePostgresTest` | `committedFinalCasMakesWaitingTakeoverObserveDone` | T — `acquire(:307) ; committedFinalCasMakesWaitingTakeoverObserveDone(:142)` | ERROR | ERROR | identique |
| 6 | `ccr.CommandResultTakeoverRacePostgresTest` | `inFlightPublicationLosesFenceAndUnblocksWinnerPublication` | T — `acquire(:307) ; inFlightPublicationLosesFenceAndUnblocksWinnerPublication(:109)` | ERROR | ERROR | identique |
| 7 | `ccr.CommandResultTakeoverRacePostgresTest` | `rolledBackFinalCasReleasesSlotAndAllowsTakeover` | T — `acquire(:307) ; rolledBackFinalCasReleasesSlotAndAllowsTakeover(:146)` | ERROR | ERROR | identique |
| 8 | `ccr.CommandResultTakeoverRacePostgresTest` | `rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo` | T — `acquire(:307) ; rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo(:245)` | ERROR | ERROR | identique |
| 9 | `ccr.CommandResultTakeoverRacePostgresTest` | `twoConcurrentTakeoversInstallOnlyOneCurrentClaim` | T — `acquire(:307) ; twoConcurrentTakeoversInstallOnlyOneCurrentClaim(:196)` | ERROR | ERROR | identique |
| 10 | `ept.DurableEventToProjectionPostgresTest` | `durableEventOutputIsTheIndependentProjectionTaskRuntimeInput` | D — `eventContext(:137) ; durableEventOutputIsTheIndependentProjectionTaskRuntimeInput(:83)` | ERROR | ERROR | identique |
| 11 | `ept.HistoricalEventRouteEvolutionPostgresTest` | `evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence` | D — `eventContext(:153) ; evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence(:67)` | ERROR | ERROR | identique |

Les six erreurs D sont réparties dans trois fixtures ; les cinq erreurs T dans la quatrième.
Il ne s'agit donc pas de cinq violations dans quatre fixtures : les quatre fixtures sont le total
des deux catégories. Aucun test de concurrence n'est retiré, modifié ou désactivé pour ce diagnostic.

Les deux assertions qui imposaient `SET current_revision = s.current_revision` ont été revues :
la première impose `for share`, l'absence de l'ancien UPDATE et les prédicats U/B ; la seconde
impose `SHARE = FIND + " for share"`, l'ordre `query(SHARE, issuer, subject)` puis
`jdbc.queryForObject(FENCE_EXACT`, l'absence de l'UPDATE et les prédicats R/U/B.
Les gardes d'ordre lifecycle `streams.lock` avant acquire/detach sont conservées.
Les 5 tests Wa67 sont verts dans le gate d'intégration existant. Le contre-exemple PostgreSQL
historique UPDATE reste volontairement conservé ; il ne représente pas le fence livré.

Dettes hors 2C, référencées ici sans création d'un ticket externe :

- **ARCH-GATE-DATASOURCE** : corriger la priorité de configuration des contextes Spring des
  trois fixtures D (6 erreurs), puis les rejouer avec la datasource Testcontainers attendue.
- **ARCH-GATE-AMBIENT-OWNER** : adapter le protocole des fixtures CommandResultTakeoverRace
  à l'owner transactionnel (5 erreurs), en préservant leurs scénarios de concurrence.

Aucune de ces dettes n'est corrigée dans 2C. Le diagnostic prouve l'indépendance des **11 erreurs
observées** ; il ne revendique pas la réussite des scénarios métier qu'elles empêchent d'atteindre.
Le gate global devra être rejoué après correction des dettes, dans leur chantier propre.
Le diagnostic Surefire d'arrêt tardif d'un fork reste visible dans les logs des passes larges.

## Limites et contention

L'effet structurel prouvé est la coexistence de deux fences B dans deux TE non résolues. Le no-op UPDATE applicatif disparaît ; aucun débit, latence, coût WAL/MultiXact, fairness ou starvation lifecycle sous flux continu n'est mesuré. Les commandes sur le même Pot restent arbitrées par P ; unicités, FK et autres ressources partagées peuvent toujours attendre ou provoquer des deadlocks. SHARE ne rend pas tout le moteur parallèle.

Pas de preuve nouvelle sur crash réseau, failover, issue UNKNOWN réelle, niveaux REPEATABLE READ/SERIALIZABLE, writers hors protocole ou suppression/recréation du stream. COMMITTED/UNKNOWN restent distingués par le contrat existant ; les scénarios d'erreur de résolution injectée de 2B ne deviennent pas des tests de panne réseau. La répétition recherche la flakiness sans explorer tous les ordres. Une commande qui voudrait faire du lifecycle après B introduirait une promotion de verrou nécessitant une nouvelle analyse ; aucun chemin de ce type n'est ajouté.

## Git

SHA initial : `8a79f06d7a551b72e00f65cd77ec1a6c8bc118e0`, branche
`feat/consumption-transaction-protocol`, worktree `/private/tmp/pocoma-consumption-transaction-protocol`.
Les 12 fichiers présents avant le diagnostic sont exactement ceux listés ci-dessous, tous dans
2C, non indexés à l'arrivée. Le diagnostic modifie uniquement ce rapport et sa preuve JSON.
Diff production : une primitive JDBC ; port : documentation seulement ; tests : adaptation des
gardes et ajout des preuves COMMAND ; autres fichiers : documentation de ce même protocole.
Aucun POM, schéma, migration ou autre runtime changé. Aucun franchissement inattendu de slice.

Le checkout principal `feat/runtime-monolith` à `90926a5e` porte les travaux étrangers préexistants :
Keycloak, docs/README, scripts Bruno (dont suppression et quatre créations), test non suivi
UpdateExpenseSharesConcurrencyPostgresTest, quatre études architecture et docs/testing/evidence.
Tous sont conservés. Les autres worktrees existants ne sont pas modifiés. Aucun reset ni stash.

Fetch origin réussi avant livraison : HEAD initial = origin, divergence 0/0. La livraison autorisée
par le PASS différentiel comprend uniquement les 12 fichiers ci-dessous, après revue du diff,
des fichiers non suivis et `git diff --check`. SHA final : commit portant ce rapport, résoluble
par `git rev-parse HEAD` ; le compte rendu de livraison donne sa valeur exacte et confirme le push
sans force, l'égalité HEAD/origin, la divergence 0/0 et le statut final du worktree.
Le worktree baseline isolé est conservé propre et détaché au SHA initial pour audit.

## Fichiers modifiés ou créés

- [Wa67BindingArchitectureTest.java](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/Wa67BindingArchitectureTest.java)
- [ExternalIdentityBindingStreamJdbcRepository.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingStreamJdbcRepository.java)
- [ExternalIdentityBindingPort.java](../../app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/ExternalIdentityBindingPort.java)
- [PotCasConsumptionPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/PotCasConsumptionPostgresTest.java)
- [Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md](../../docs/architecture/Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md)
- [Pot_Command_Binding_Shared_Fence_PostgreSQL_Proof_Step_2C_2026-10-18.md](../../docs/architecture/Pot_Command_Binding_Shared_Fence_PostgreSQL_Proof_Step_2C_2026-10-18.md)
- [command-consumption-runtime.md](../../docs/architecture/command-consumption-runtime.md)
- [consumption-transactional-execution.md](../../docs/architecture/consumption-transactional-execution.md)
- [transactional-protocol-studies.md](../../docs/architecture/transactional-protocol-studies.md)
- [2026-10-16-consumption-generic-transactional-protocol.md](../../docs/plans/2026-10-16-consumption-generic-transactional-protocol.md)
- [Step_Plan.md](../../docs/steps/completed/WRITE_ADMISSION/Step_Plan.md)
- [Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.json](../../docs/testing/evidence/Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.json)
