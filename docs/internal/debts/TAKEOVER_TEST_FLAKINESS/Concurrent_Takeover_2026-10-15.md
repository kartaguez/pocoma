# Consumption — Courses transactionnelles de takeover

Baseline : branche `test/takeover-fencing`, HEAD `4a07b1117ed56f0f5a44ba6ae2a888de730b2d84`, upstream `origin/test/takeover-fencing`, divergence `0/0`, working tree propre et SHA distant confirmé. Branche de travail : `test/takeover-races`. Rapports relus : [audit](Audit_2026-10-15.md), [correctif](Implementation_2026-10-15.md), [validation](Validation_2026-10-15.md). Validation de cette campagne : **PASS — 515 tests, aucun échec, erreur ou skip**. Le diagnostic historique reste **INCONCLUSIVE** ; aucune anomalie runtime observée.

## Verification scope déclaré avant implémentation

Preuve locale CommandResult/Consumption ; secondaires fonctionnelles : NONE. Modifications limitées aux tests et à leur documentation. Impact runtime, schéma et contrats : NONE. Sélection Maven imposée : `-pl architecture-tests -am`, fermeture connue de 60/61 projets pour compiler/résoudre, mais tests nommés explicitement avec `-Dsurefire.failIfNoSpecifiedTests=false`. Aucun `install`, reactor complet ou gate global d'architecture. Bootstrap PostgreSQL existant (pas de baseline certifiée), pas de modification des migrations. Escalade : conserver toute anomalie runtime reproductible et arrêter l'élargissement du périmètre.

## Audit des points de sérialisation

Acquisition et exécution ont des transactions séparées : `TransactionalAcquireConsumptionUseCase` et `TransactionalExecuteConsumptionUseCase` délèguent à `SpringTransactionRunner`/`TransactionTemplate`. Le template de production utilise les valeurs par défaut : propagation REQUIRED et isolation de la base. Les tests vérifient READ COMMITTED et les identifiants réels de connexion/transaction.

- [JpaConsumptionLifecycleAdapter.acquire](../../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java), lignes 73–120 : INSERT slot idempotent, `findByKeyForUpdate`, vérification DONE/lease, invalidation de C1 et installation de C2 dans la même transaction.
- [JpaConsumptionSlotRepository](../../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java), lignes 36–51 et 79–92 : SELECT FOR UPDATE et CAS `PENDING AND current_claim_id=:claimId`. Un CAS réussi garde le verrou d'écriture jusqu'au commit/rollback.
- [ExecuteConsumptionService](../../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java), lignes 39–62 : callback, provenance, puis CAS final ; un refus lève `LostClaimException` et annule la transaction.
- [JdbcCommandResultStore](../../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandResultStore.java), lignes 23–36 : INSERT ON CONFLICT DO NOTHING puis comparaison du contenu. Une publication concurrente peut attendre la transaction qui a inséré la même clé, sans encore avoir atteint le CAS du slot.

Les transactions de test enveloppent les vrais ports/services avec un `TransactionTemplate` du même gestionnaire ; REQUIRED les fait participer à cette transaction appelante. Une barrière après retour du service est donc située après son SQL mais avant le commit réel. Aucun mock du CAS ou du verrouillage, aucune réimplémentation des décisions métier.

## Matrice et absence de duplication

| Scénario | Déjà couvert | Preuve complémentaire et barrière |
|---|---|---|
| S1 C1 commit avant takeover | `expiredClaimCanStillFinalizeBeforeTakeover` et finalisation générique après lease | Pas de nouveau test séquentiel. S1 est inclus dans S3 : C2 attend réellement, puis observe DONE après commit C1. |
| S2 C2 takeover pendant callback C1 | `TransactionalConsumptionExecutionPostgresTest.staleWorkerRollsBackEverythingAndTakeoverWinnerCommits` (271–303), analogue dans `CommandConsumptionPostgresTest` (607–659), Registration (130+) | Nouveau cas avec le vrai CommandResult déjà inséré par C1 mais non committé. C2 acquiert et committe, puis sa publication est constatée bloquée derrière l'INSERT C1. C1 reprend et perd son CAS ; rollback C1 libère la publication C2. Nouvelle preuve de contention publication/fence, pas duplication des effets de fixture génériques. |
| S3 C1 a réussi son CAS avant C2 | Finalisation courte générique (219–240) lance un concurrent mais ne constate pas son attente ; autre chemin qui verrouille avant effet | Barrière après retour d'Execute dans la transaction appelante ; PostgreSQL doit montrer C2 bloqué sur l'acquisition du slot par C1. Libérer C1, puis vérifier AlreadyDone, historique à un claim et commit unique. |
| S4 deux repreneurs d'une lease expirée | Création concurrente d'un nouveau slot, takeover séquentiel et allocation des tentatives | Deux transactions/connexions démarrées derrière une CyclicBarrier. Le premier Acquired reste avant commit ; observer l'autre bloqué sur son slot. Après commit, le second doit être Busy, sans nouvelle tentative ni claim. Le gagnant peut être C2 ou C3. |
| S5 rollback d'exécution en concurrence | Exceptions isolées et retry générique | Variante S3 : CAS, résultat et provenance préparés mais non committés ; C2 est observé bloqué. Rollback contrôlé C1, puis takeover C2 et publication normale. |
| S5 rollback de takeover en concurrence | Atomicité de takeover en séquentiel | C2 installe son claim dans sa transaction, C3 est observé bloqué ; rollback C2. C3 doit reprendre avec tentative 2, sans claim C2 ni révision persistante supplémentaire. |

Entrelacement interdit : après CAS réussi de C1 et avant sa résolution, C2 ne peut pas committer le takeover du même slot : il attend le verrou PostgreSQL. Le protocole ne demande donc jamais d'attendre la fin de C2 tout en retenant C1. Dans S2, C2 ne verrouille pas encore le slot quand sa publication attend C1 : libérer C1 permet son CAS refusé et son rollback, puis la progression de C2, sans cycle de verrous ajouté par le test.

Les attentes sont prouvées par `pg_blocking_pids`, le type d'attente Lock et le texte SQL réellement actif. Les barrières sont libérées en finally/close, les timeouts de 20 secondes servant uniquement à détecter un blocage. Aucun sleep ni expiration dépendant de l'ordonnancement.

## Protocoles et témoins transactionnels

Code ajouté : [CommandResultTakeoverRacePostgresTest](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultTakeoverRacePostgresTest.java). Les sept tests du chantier précédent restent inchangés. Les témoins génériques réutilisés sont dans [TransactionalConsumptionExecutionPostgresTest](../../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/TransactionalConsumptionExecutionPostgresTest.java), lignes 179–204, 219–240 et 271–303. Les analogues Command et Registration sont référencés dans [CommandConsumptionPostgresTest](../../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/CommandConsumptionPostgresTest.java), 607–659, et [RegistrationRuntimePostgresTest](../../../app/runtime-registration-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/registration/RegistrationRuntimePostgresTest.java), 130–159 ; ils ne sont ni modifiés ni répétés dans cette campagne.

Le nouvel helper `inTransaction` (280–299) crée une transaction réelle par appel, exige `isNewTransaction`, relève `pg_backend_pid()`, `txid_current()` et l'isolation, puis enregistre `afterCompletion`. Le callback d'Execute et la précondition d'Acquire vérifient qu'ils utilisent les mêmes PID/XID que leur transaction appelante (308–325). Les deux travailleurs actifs doivent avoir des PID et XID différents ; l'observateur d'attente doit utiliser un troisième PID (328–362). Une connexion peut être réutilisée par le pool après commit ; ce n'est pas une transaction concurrente sur une même connexion.

L'horloge nommée `commandResultClock` est remplacée uniquement dans le test (97–100, 525–532) : C1 est acquis à `t0`, lease de 30 secondes, puis l'instant passe explicitement à `t0+31s`. Les attentes de barrières et de verrous ne modifient pas ce temps. Aucun UPDATE de lease. L'observation PostgreSQL est une boucle de lectures bornée, sans sleep : elle attend un état de verrou précis, pas l'écoulement d'un délai pour provoquer une course.

### S2 — Publication en vol, takeover et rollback au fence

Méthode `inFlightPublicationLosesFenceAndUnblocksWinnerPublication` (107–138).

1. C1 démarre Execute dans E1 et insère le vrai CommandResult via `MaterializeCommandResultService`. Le callback vérifie son contenu et sa visibilité dans E1 ; la barrière `C1-insert-before-CAS` retient E1 avant provenance/CAS. L'observateur ne voit aucun résultat ou input committé.
2. Le contrôleur avance l'horloge et exécute l'acquisition C2 dans A2. A2 committe avant le lancement d'Execute C2 ; le slot courant C2 et l'invalidation de C1 sont relus hors E1.
3. E2 démarre et atteint le vrai INSERT du store. L'observateur exige que le PID E2 soit bloqué par le PID E1, type Lock, avec une requête `INSERT INTO command_results ... ON CONFLICT`. Le slot C2 reste PENDING, aucun résultat ou input n'est visible.
4. Le contrôleur libère E1. C1 fournit la provenance normale et un input diagnostic `STALE_CALLBACK` à identité distincte ; son CAS refuse, l'exception doit être précisément `LostClaimException` avec les IDs attendus. `afterCompletion` constate ROLLED_BACK.
5. Le rollback E1 libère l'INSERT E2. E2 publie et finalise, puis COMMITTED est constaté. Un seul résultat au contenu exact, une seule provenance normale et aucun marqueur stale persistent.

La contrainte unique sur CommandResult fait **attendre C2** ; elle n'est pas le motif de rejet de C1. C2 n'a pas encore écrit de provenance quand C1 reprend, et le marqueur possède une identité distincte. Une erreur d'unicité ou de divergence à la place de `LostClaimException` ferait échouer le test. La coexistence des transactions, le refus du fence et le rollback sont donc vérifiés séparément.

### S1/S3 et S5 — CAS effectué, résolution retenue

Méthodes `committedFinalCasMakesWaitingTakeoverObserveDone` et `rolledBackFinalCasReleasesSlotAndAllowsTakeover`, helper 149–191.

1. Après expiration contrôlée, C1 exécute le vrai service dans E1. Après retour du service, ses propres lectures doivent voir DONE, le claim SUCCESS et un input ; le CAS et le flush ont donc eu lieu. La barrière `C1-CAS-before-resolution` empêche le retour de la transaction appelante.
2. Un autre backend ne voit encore que le slot PENDING/C1, revision 1, le claim C1 ouvert et aucune publication/provenance. C2 démarre A2 et son attente sur E1 est constatée dans PostgreSQL. L'assertion accepte uniquement les deux commandes réelles d'acquisition : INSERT idempotent du slot ou SELECT FOR UPDATE ; elle conserve laquelle est active.
3. **Commit** : libérer E1 ; C1 committe, C2 retourne AlreadyDone/SUCCESS. Aucun claim C2, historique C1 SUCCESS seulement, revision 2, résultat/provenance uniques. C2 ne peut pas prendre l'autorité d'un slot DONE.
4. **Rollback** : après libération, un `RollbackMarker` est levé avant commit E1, et sa classe exacte est exigée. Publication, provenance, finalisation et clôture C1 sont annulées. A2 peut reprendre : C1 TAKEN_OVER, C2 tentative 2 courante, revision 2 et aucune publication. C2 exécute ensuite normalement et converge vers DONE/SUCCESS, revision 3.

Le rollback est une défaillance contrôlée de la transaction appelante ; aucun trigger, erreur SQL fabriquée ou modification du CAS n'est utilisé. L'attente n'est jamais libérée en attendant la fin du repreneur : le contrôleur résout d'abord E1, évitant un deadlock du protocole de test.

### S4 — Deux takeovers simultanés

Méthode `twoConcurrentTakeoversInstallOnlyOneCurrentClaim`, 194–241.

Deux transactions, chacune sur une connexion réservée et identifiée, attendent une `CyclicBarrier(3)` avec le contrôleur. Après libération commune, elles appellent le vrai Acquire sur le même slot expiré. Le premier résultat Acquired est enregistré par le contrôleur, sans prédéterminer C2 ou C3, puis retenu avant son commit. Une attente SQL de l'autre backend derrière ce gagnant est obligatoire avant libération.

L'observateur voit encore C1, tentative 1, revision 1. Après commit du gagnant, l'autre résultat doit être **Busy** avec la lease du gagnant, pas une violation SQL. Il ne doit exister que deux claims, tentatives 1 et 2, et un seul claim courant. C1 ne peut pas publier, même sans résultat ni provenance préexistants ; le gagnant finalise ensuite seul. Les rôles C2/C3 sont symétriques, l'ordre du scheduler ne décide pas de l'expiration ni de l'existence de la contention.

### S5 — Takeover annulé et tentative réutilisée

Méthode `rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo`, 243–278.

C2 appelle Acquire dans A2, vérifie sa propre installation tentative 2 et l'invalidation de C1, puis reste à `C2-install-before-rollback`. A3 est constatée bloquée derrière A2 ; l'observateur voit toujours C1 non invalidé, revision 1. Après rollback contrôlé A2, A3 acquiert un ID distinct en **tentative 2**, pas 3. Le claim C2 doit être absent. Son token retourné avant rollback ne peut pas publier : Execute doit lever `LostClaimException` et rollbacker ses effets, sans modifier le slot ni les claims C1/C3. C3 publie et finalise normalement.

### Publication concurrente et entrelacements interdits

Si C2 a déjà committé le takeover, C1 ne peut pas committer sa publication avec son ancien claim : S2 vérifie le refus de son CAS, même avec un INSERT préparé. Si C1 a déjà effectué son CAS réussi, C2 ne peut pas committer le takeover tant que E1 n'a pas été résolue : S1/S3 et S5 constatent l'attente SQL et les deux issues commit/rollback. Il serait erroné de forcer un test où C2 termine cette acquisition pendant que C1 garde ce verrou ; ce serait un blocage du contrôleur, pas un entrelacement runtime admissible.

## Vérification Maven et campagne

Commandes réellement utilisées, depuis `app/`, avec le wrapper du projet :

```sh
# Compilation, une fois, sans tests.
./mvnw -o -pl architecture-tests -am -DskipTests test

# Premier passage, sans skip de compilation.
./mvnw -o -pl architecture-tests -am -Dtest=CommandResultTakeoverRacePostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 test

# Répétitions 1–20, puis 21–100, strictement en série.
./mvnw -o -pl architecture-tests -am -Dtest=CommandResultTakeoverRacePostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 \
  -Dmaven.main.skip=true -Dmaven.compiler.useIncrementalCompilation=false test

# Tests existants complémentaires, une seule passe.
./mvnw -o -pl architecture-tests -am \
  '-Dtest=CommandResultConsumptionChainPostgresTest,TransactionalConsumptionExecutionPostgresTest#successfulExecutionBecomesVisibleAsOneCommit+shortFinalizationLocksBeforeTheEffectAndAnExpiredCurrentClaimMayFinish+staleWorkerRollsBackEverythingAndTakeoverWinnerCommits' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 \
  -Dmaven.main.skip=true -Dmaven.compiler.useIncrementalCompilation=false test
```

Le premier passage est vert : 5/5. Les deux paliers sont terminés : 20/20 puis 100/100 par nouveau cas. La passe de non-régression est verte : sept tests CommandResult et trois tests génériques. Total de cette intervention : **515 réussites, 0 échec, 0 erreur, 0 skip**. Les anciennes campagnes de 321 exécutions ne sont ni répétées ni additionnées à ce total.

Chaque lancement crée un nouveau processus et conteneur PostgreSQL. Le lanceur conserve XML/TXT et log Maven dans un dossier numéroté, exige exactement les cinq noms de méthodes, zéro skip et cinq témoins BLOCK associés à cinq barrières. Il s'arrête au premier lancement non PASS, sans retry. Les fichiers de rapport de la classe sont retirés avant chaque passage pour éviter un rapport périmé. Les sources sont gelées pendant les répétitions et leur empreinte SHA-256 a été vérifiée après la campagne ; les 100 répétitions ont effectué zéro recompilation.

## Résultats et preuves conservées

Environnement : JDK Eclipse Adoptium 21, wrapper Maven 3.9.14, Docker Engine 29.4.0, PostgreSQL **17.11** (`postgres:17-alpine`, digest conservé), isolation **READ COMMITTED** vérifiée dans chaque transaction. Aucun blocage d'environnement pendant cette campagne. Les quatre artefacts SNAPSHOT runtime restent absents du repository local ; aucune installation artificielle effectuée.

| Méthode | Premier palier | Campagne finale (inclut le palier) | Attentes SQL constatées |
|---|---:|---:|---|
| `inFlightPublicationLosesFenceAndUnblocksWinnerPublication` | 20/20 | 100/100 | 100 INSERT ON CONFLICT sur CommandResult |
| `committedFinalCasMakesWaitingTakeoverObserveDone` | 20/20 | 100/100 | 100 INSERT ON CONFLICT sur slot |
| `rolledBackFinalCasReleasesSlotAndAllowsTakeover` | 20/20 | 100/100 | 100 INSERT ON CONFLICT sur slot |
| `twoConcurrentTakeoversInstallOnlyOneCurrentClaim` | 20/20 | 100/100 | 100 SELECT FOR UPDATE sur slot |
| `rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo` | 20/20 | 100/100 | 100 INSERT ON CONFLICT sur slot |

Les cinq premiers passages sont supplémentaires aux 500 de la campagne, ainsi que les dix tests voisins. Dans S4, C2 a gagné 82 fois et C3 18 fois ; le test accepte les deux ordres et vérifie les mêmes invariants. Aucun essai relancé pour obtenir cette distribution.

Les **500 témoins** ont été recoupés après exécution avec les événements TX, BARRIER et RESOLVED de chaque méthode : PID et XID distincts pour les deux transactions actives, observateur sur un troisième backend, barrière atteinte avant observation du verrou, aucune résolution avant ce témoin. Le détenteur committe dans S1/S3 et S4, rollbacke dans S2 et les deux S5 ; le demandeur finit par committer dans les cinq cas. L'INSERT idempotent du slot est donc bien un point de sérialisation observé : il serait inexact d'attribuer ces 300 attentes au seul SELECT FOR UPDATE.

Preuves livrées dans [races-20261015](races-20261015/) :

- [Résultats individuels des 100 lancements](races-20261015/results.csv), cinq résultats par lancement ; [synthèse chiffrée](races-20261015/summary.json).
- [500 témoins de verrous et résolutions](races-20261015/witnesses.csv), avec PID, XID, SQL actif et statut final.
- [Environnement et empreinte du test](races-20261015/environment.json) ; [diff exact du test ajouté](races-20261015/Tests.patch).
- [Archive des preuves brutes](races-20261015/raw-evidence.tar.gz) : logs Maven, rapports Surefire XML/TXT de chaque lancement, premier passage et non-régression, lanceur séquentiel et versions/digest.
- [Empreintes SHA-256](races-20261015/SHA256SUMS) des livrables de preuve.

## États persistants et invariants

Les assertions communes (test, lignes 376–473) vérifient les identités et l'historique complet des claims, les tentatives, le statut/révision du slot, l'absence de claim courant sur DONE, une seule provenance canonique et le contenu exact du résultat. Le replay d'acquisition sur DONE doit laisser ces données inchangées. Les assertions `assertLostWithoutPublication` (425–437) et le scénario S2 (107–138) exigent précisément `LostClaimException`, les IDs attendus et l'absence du marqueur stale ; une contrainte unique préalable ne satisfait pas ces assertions.

| Scénario | Historique final | Slot final | Publication / provenance |
|---|---|---|---|
| S1/S3 commit | C1 SUCCESS uniquement ; C2 AlreadyDone | DONE/SUCCESS, révision 2, aucun claim courant | 1 résultat exact, 1 input canonique |
| S2 | C1 TAKEN_OVER, C2 SUCCESS, tentatives 1/2 | DONE/SUCCESS, révision 3 | INSERT C1 et marqueur annulés ; 1 résultat C2, 1 input canonique |
| S4 | C1 TAKEN_OVER, gagnant C2 ou C3 SUCCESS, tentatives 1/2 ; perdant Busy sans claim ajouté | DONE/SUCCESS, révision 3 | C1 rejeté avant publication gagnante ; 1 résultat, 1 input canonique |
| S5 rollback exécution | Finalisation C1 annulée ; C1 TAKEN_OVER, C2 SUCCESS | Après rollback/takeover PENDING C2 révision 2, puis DONE révision 3 | 0 résultat/input avant exécution C2, puis 1/1 |
| S5 rollback acquisition | Claim C2 absent ; C1 TAKEN_OVER, C3 SUCCESS ; C3 tentative 2 | Installation C2 annulée ; DONE révision 3 après C3 | Token C2 rejeté avec rollback ; 1 résultat, 1 input canonique |

| Invariant | Garantie et preuve apportée | Limite |
|---|---|---|
| INV-1 Fencing | Le CAS conditionne l'autorité à PENDING/current_claim_id ; ExecuteConsumptionService transforme son refus en LostClaimException. S2 montre le refus après vraie concurrence, sans unicité préalable bloquant C1, et le rollback de son résultat/provenance. S4 et S5 acquisition vérifient aussi les tokens invalidés. | Effets SQL du même gestionnaire transactionnel ; aucun effet externe non transactionnel revendiqué. |
| INV-2 Takeover | L'acquisition sérialise sur le slot, contrôle la lease avec l'horloge injectée et installe atomiquement le repreneur. S2, S4 et S5 prouvent les claims/tentatives attendus. S1/S3 prouve le refus de reprendre DONE après attente. | La lease expirée ne retire pas seule l'autorité : C1 peut gagner avant takeover committé. |
| INV-3 Atomicité | Résultat, provenance, CAS et clôture participent à la transaction Spring commune. Les lectures d'un autre backend ne voient aucun fragment avant résolution ; S2 et S5 exécution montrent le rollback, S1/S3 le commit unique. PostgreSQL fournit l'isolation et la résolution atomique. | Chemin CommandResult et isolation READ COMMITTED testés. |
| INV-4 Unicité | Verrou d'acquisition et CAS assurent un claim autoritaire ; S4 montre un seul Acquired, l'autre Busy, sans tentative supplémentaire. S2 constate séparément l'attente d'unicité du résultat et le rejet métier du stale par le CAS. Tous les états finaux contiennent un résultat au contenu exact. | Les callbacks produisent le résultat canonique du même événement ; pas d'exploration de tout contenu divergent arbitraire. |
| INV-5 Recovery | S5 exécution libère les verrous et annule les effets préparés ; S5 acquisition annule l'installation et permet au demandeur bloqué de reprendre en tentative 2. Chacun termine normalement avec le repreneur valide. | Rollbacks contrôlés, sans crash de processus ni coupure réseau. |

## Conclusion et recommandation

**Aucune anomalie runtime découverte.** Sur PostgreSQL 17.11 / READ COMMITTED, les entrelacements représentatifs testés préservent le fencing, l'atomicité et une seule publication autoritaire. Les preuves incluent des transactions réellement concurrentes, leurs attentes SQL et leurs issues commit/rollback ; elles ne reposent pas sur le seul succès final ou sur une exception d'unicité.

Ces résultats renforcent la confiance dans les garanties existantes ; ils ne constituent pas une exploration exhaustive des ordonnancements, niveaux d'isolation ou pannes d'infrastructure. Ils n'identifient pas rétrospectivement la cause de l'échec intermittent historique : ce diagnostic reste **INCONCLUSIVE**.

Recommandation : **maintenir DEBT-TEST-01 OPEN**, soumettre ces preuves à validation du diagnostic et des critères de clôture de la dette. Le registre `Debt.md` n'est pas modifié. La campagne satisfait les critères complémentaires de concurrence demandés ; elle ne transforme pas l'absence d'échec en explication de l'incident historique.

Livraison sur `test/takeover-races`, depuis `test/takeover-fencing` à `4a07b111`, sans merge. Seuls une nouvelle classe de test et ses documents/preuves sont ajoutés. Aucun changement runtime, schéma, contrat, ancien test ou rapport historique. Aucun franchissement inattendu de slice ; aucun gate global requis ou exécuté ; aucun reactor complet exécuté. La fermeture `-am` de 60/61 projets résout les dépendances depuis les sources, avec les seuls tests nommés ci-dessus. Les commandes réellement exécutées figurent dans ce rapport et les logs bruts.
