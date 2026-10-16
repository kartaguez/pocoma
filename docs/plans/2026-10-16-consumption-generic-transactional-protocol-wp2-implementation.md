# WP2 — Basculement générique et preuve PostgreSQL TARGET

Date : 2026-10-16. Plan : [protocole canonique](2026-10-16-consumption-generic-transactional-protocol.md). Contrat : [WP1, D01–D10](2026-10-16-consumption-generic-transactional-protocol-wp1-contracts.md). WP3–WP6 ne sont pas engagés. **Verdict WP2 : PASS borné**, moteur/tests livrés par `72687053d2d5ec2dd1c47f667e4f95403aa1f4d8`.

## Baseline et protection du travail local

Branche conservée : `feat/runtime-monolith`. SHA initial : `c1d60a90dcfb89b2caf38a8f00a286fdb5e206b2`. Upstream : `origin/feat/runtime-monolith`, divergence initiale `0/0`. `git ls-remote origin refs/heads/feat/runtime-monolith` confirme le SHA serveur après un échec DNS sandbox et une lecture réseau autorisée. WP0 (`2a9d8f3f`, `2bfa0f8e`) et WP1 (`c1d60a90`) sont présents dans l'ascendance.

Préexistants inventoriés et exclus : `docker/keycloak/pocoma-realm.json`, suppression Bruno `01 Create Pot.bru`, modifications `02`–`07` et `folder.bru`, nouveaux `001 Registrations.bru`, `002 RegistrationResult.bru`, `003 Me-Binding.bru`, `004 Create Pot.bru`. Empreintes SHA256 de tous les fichiers présents capturées avant édition ; contrôle final et statut de la suppression requis avant livraison. Aucun changement de branche, stash, reset, staging global, merge ou force push.

Références normatives : plan intégral, rapports WP0/WP1 et preuves WP0, audit générique, étude de finalisation, audit D17 et chronologie SQL, canon Consumption, garanties système, politique Reactor. Les preuves CURRENT demeurent dans [WP0](2026-10-16-consumption-generic-transactional-protocol-wp0-baseline.md) et son [archive](2026-10-16-consumption-generic-transactional-protocol-wp0-verification.txt) ; elles ne sont pas réécrites comme TARGET.

## Scope déclaré avant implémentation

Impact attendu : suffixe Consumption partagé, lifecycle JPA, owners et composition des huit familles, recovery Binding Command, traitement externe des erreurs et observation LKV. Primaire COMMAND ; secondaires EVENT, PROJECTION, BINDING, LKV ; compléments explicites Registration, Command Result, Registration Result. Les anchors sont les huit chemins du plan §7.2. WEB, admission, policies fonctionnelles, schéma/migrations, graphe Maven et frontières de modules sont hors changement.

Global architecture gate : **NOT REQUIRED** ; aucun changement de dépendance, module, frontière ou règle d'isolation runtime. Full reactor : **NOT REQUIRED** ; étape bornée ALL8, sans clôture de Wave ni intégration main. Aucun `-amd`, `install`, installation SNAPSHOT ou k6. DB CURRENT sur PostgreSQL réel 17.11 via Testcontainers `postgres:17-alpine`, Java Temurin 21. Docker 29.4.0 vérifié avec accès autorisé au socket. Trusted baseline NONE dans la politique : bootstrap existant conservé, aucune campagne historique autonome ni nouvelle migration.

Particularité confirmée du code : sept assemblages sont dans `composition-runtime-spring`, LKV reste dans son module runtime et n'a pas de dépendance vers ce module de composition. La garde owner de même sémantique est donc locale à chacun de ces deux lieux de composition, sans ajouter de dépendance ni déplacer un module. Cela précise l'emplacement de WP1 ; aucun invariant D01–D10 n'est modifié. La duplication locale de la garde ne duplique pas le suffixe terminal.

## Modifications et interfaces

- `ConsumptionFinalization.Rejected(code)` ajouté, code non blanc ; `Success` et `TerminalFailure` conservés.
- `FinalizeConsumptionInput` reçoit inputs/results, copies immuables ; constructeur à quatre arguments conservé.
- `ConsumptionLifecyclePersistencePort.terminalize` et `scheduleRetry` sont des opérations participantes sans retour booléen susceptible d'être ignoré.
- `FinalizeConsumptionService` valide les slotIds, exécute l'effet une seule fois, append la provenance puis appelle le suffixe lifecycle. Plus de prélock terminal.
- `ExecuteConsumptionService` garde le callback et son résultat, adapte SUCCESS/REJECTED, délègue au Finalize participant avec effet vide ; aucune deuxième publication ni provenance.
- `HandleConsumptionFailureService` garde lecture du claim, attempt et policy ; Fail délègue au même mécanisme, RetryAfter utilise sa transition dédiée sans effet terminal.
- `JpaConsumptionLifecycleAdapter` possède un seul CAS terminal, puis `end` ou `fail`. Retry garde `scheduleRetry`, puis la même exigence de clôture. CAS zéro lève `LostClaimException` ; clôture autre que un ou SQL de clôture lève `ConsumptionClaimClosureException` avec cause/cardinalité/S/C.
- Anciennes APIs `tryTerminalize` et `handleFailure` délèguent au suffixe ; elles ne rendent plus une perte normalement. Surface `lockCurrentClaim`/`findByIdForUpdate` conservée pour le retrait prévu WP5, sans caller terminal de production. Acquire conserve son verrou par clé.
- Wrappers Finalize/Failure traduisent la perte après sortie exceptionnelle du runner. Execute propage. `ConsumptionOwnership` ne normalise que des SQL de contrainte/concurrence après rollback et relecture fraîche démontrant la perte ; clôture et résolution exclues.
- `ConsumptionOwnerTransactionRunner` et garde locale LKV refusent transaction/synchronisation ambiante avant delegate. Synchronisation enregistrée avant l'action, résultat seulement après COMMITTED ; erreur de résolution distincte `ConsumptionTransactionResolutionException` avec phase et issue ROLLED_BACK/COMMITTED/UNKNOWN.
- Orchestrateurs publics gardés avant scan/classification ; Sequential reconnaît les anomalies de clôture/résolution avant classifier. COMMITTED/UNKNOWN interdisent HandleFailure automatique. Relecture d'ownership après erreur SQL externe aussi autour de HandleFailure.
- `BindingFenceRecoveryExecuteUseCase` distingue Execute owner initial et Execute participant interne. Plus de lock S ; nouvelle transaction après rollback initial, relecture exacte E/B puis conflit technique si présent, rejet public si absent. Aucun handler Pot rejoué.
- LKV protège son callback d'observation afterCommit contre ses propres RuntimeException ; les erreurs de l'upsert restent propagées.

Les constructeurs compatibles restants instancient le même mécanisme, sans implémentation alternative de CAS. Les assemblages Execute/Failure partagent un participant interne explicite, porté par un holder qui n'est pas exposé comme use case public substituable.

## Séquences et ordre des verrous

CURRENT Finalize : `FOR UPDATE S → contrôle C → effet → CAS S → C → commit`. CURRENT recovery prenait aussi S avant outcome. Execute normal faisait déjà effets/provenance avant CAS.

TARGET commun :

```text
Acquire/takeover : bootstrap → S FOR UPDATE par clé → invalider/insérer C → installer C → COMMIT

Owner sans transaction ambiante
BEGIN effets
  effets de famille et autorités à leurs positions locales
  publications terminales éventuelles
  provenance validée éventuelle
  flush complet du contexte JPA par le repository CAS
  CAS S WHERE S/PENDING/C ; zéro = exception
  clôture conditionnelle C du même S ; exactement un
COMMIT
après résolution : résultat, ou perte traduite après rollback
```

Command conserve Pot/version avant DML temporel, Events `saveAllAndFlush`, puis fence Binding exact, outcome strict, terminal event, provenance, CAS S, C. Registration conserve stream Binding avant initializer User/facts/occurrence/actif/outcome. Binding READ et LKV conservent leurs upserts monotones ; aucun verrou Pot/WRITE étranger ajouté. Root Projection avant artifacts ; Results INSERT/compare immutable avant provenance/S. Uniques et FK peuvent attendre avant le CAS : le TARGET ne prétend pas supprimer tous les verrous sur S avant lui.

Aucun callback métier après CAS gagnant ; seul UPDATE claim ensuite. Les SQL slot/claim et leurs prédicats restent inchangés. Lease, revision et nextClaimAt ne deviennent pas des tokens de validation. La lease expirée encore courante peut finir ; un takeover committé invalide l'ancien token.

## Flush, contraintes et résolution

Barrière finale existante conservée : `JpaConsumptionSlotRepository.terminalize/scheduleRetry`, `@Modifying(flushAutomatically=true, clearAutomatically=true)`. Provenance `saveAll` différée, JDBC immédiat, Events `saveAllAndFlush` et CAS Pot inchangés. Aucun flush global supplémentaire ajouté. Le flush de clôture ne doit rien découvrir de métier. Les FK inputs/results→S, composite S/C, uniques outcome et provenance, immutabilité des Results et schémas production restent intacts.

La campagne ciblée avec `logging.level.org.hibernate.SQL=DEBUG` consigne les INSERT provenance avant UPDATE slot puis UPDATE claim ; les publications JDBC sont attestées par le callback suspendu et l'observateur PostgreSQL D17. Une contrainte différée de fixture refuse réellement un commit après les opérations réussies ; les lectures indépendantes constatent l'annulation des effets et de S/C. Ce n'est ni une migration applicative ni une panne réseau reproduite.

La garde conserve les issues que Spring lui confirme. UNKNOWN n'est pas renommé rollback sur la seule base d'une exception. Les tests de données après résolution donnent la preuve physique de leur scénario, sans promettre une réconciliation distribuée générale. Une exception après COMMITTED conserve cette issue et ne devient pas un échec métier.

## Huit familles

| Famille | Intégration TARGET et garanties conservées |
|---|---|
| Command | Execute, Failure et recovery rejoignent le suffixe ; Pot/Binding, outcome strict et terminal event conservés |
| Registration | Execute partagé, User/facts/Binding/outcome atomiques ; failure technique vide, aucun outcome inventé |
| Binding | Execute partagé, CURRENT_BINDING monotone, tombstone et comparaison même révision conservés |
| Event | Finalize partagé, ensure Task provisoire avant CAS ; aucun FAILED/policy ajouté |
| Projection Task | Préparation pure conservée ; publish root/artifacts et recordFailure avant CAS ; Temporary reste retry sans budget terminal |
| Command Result | Execute partagé ; immutable/compare, owner historique et provenance conservés |
| Registration Result | Même mécanisme ; sources immuables, aucune autorité Binding courante ajoutée |
| Latest Known Version | Execute partagé ; watermark monotone et provenance, métriques après commit protégées |

Modules de production modifiés : `engine-consumption`, `engine-consume-command`, `infra-persistence-primary-jpa`, `orchestrator-consumption`, `composition-runtime-spring`, `runtime-latest-known-version-consumption-worker`. Tests seuls dans les sept autres modules runtime : Command, Event, Task, Binding, Registration, Command Result, Registration Result. Total : **13 modules, 47 fichiers Java** ; inventaire exact dans l'archive.

Les tests runtime des huit anchors sont nécessaires ; la compilation commune via COMMAND n'est pas comptée comme leur preuve. Les preuves approfondies famille par famille restent WP3.

## D17 TARGET et preuves critiques

Huit entrelacements déterministes : failure/recovery × sans provenance, inputs seuls, results seuls, inputs+results. C1 acquis, expiration contrôlée, takeover C2 committé lu indépendamment, C1 outcome/terminal event provisoires ; C2 Execute échoue puis rollbacke ; nouvelle transaction failure/recovery attend INSERT outcome. Observation `pg_stat_activity`, `pg_blocking_pids`, deux PID/XID et READ COMMITTED ; sonde KEY SHARE NOWAIT réussit pendant l'attente C2, puis rollback observateur. Libération C1, flush provenance puis CAS perdu/rollback ; C2 reprend et committe FAILED ou REJECTED exact.

Assertions : zéro `40P01` dans chacun, exception LostClaim C1, zéro effet de fixture/input/result stale, une résolution outcome/event cohérente, S DONE sans token, C1 TAKEN_OVER, C2 clos. FK/unique cataloguées et validées, `session_replication_role=origin`. Aucun retry, suppression de contrainte ou sleep de race. Les callbacks Command de fixture sont contrôlés, avec les vrais services, wrappers, repositories, contraintes et transactions. La provenance de fixture est explicitement attachée au résultat stale pour les variantes inputs/results, y compris recovery : après détachement, le rejet précoce réel n'aurait naturellement aucun input Pot. On exerce ainsi réellement la FK avant le CAS dans les variantes annoncées, sans prétendre exécuter un handler Pot supplémentaire. Ce n'est pas une preuve E2E Pot ni un changement de la provenance de production. Le témoin CURRENT exigeant `40P01` est remplacé dans le test actif ; l'archive WP0 reste la preuve historique.

Autres preuves minimales :

- CAS perdu après vrais writes ; takeover committé pendant effet provisoire ; lease expirée courante acceptée.
- Vrai CAS terminal gagnant puis clôture refusée : exception d'intégrité et rollback des effets, CAS et modification contrôlée du claim.
- Contrainte différée refusant réellement le commit ; aucun APPLIED retourné.
- Event stale ensure réellement invoqué, aucune Task durable ; Projection stale publish avec root/artifact et stale recordFailure, aucune publication durable.
- `23505` réelle : current conserve l'erreur, stale établi traduit après rollback, publication préexistante inchangée.
- Relecture Binding positive = conflit technique ; négative = CALLER_IDENTITY_NOT_CURRENT ; D17 recovery avec provenance ; tests de fence pendant métier et après fence conservés.
- REQUIRED participant dans owner : même XID, perte traverse le owner, effets annulés ; entrées publiques sous transaction ambiante refusées avant callback dans les huit configurations.
- Erreur d'observation après commit : issue COMMITTED et ligne durable ; erreur de commit : aucun résultat de succès et données annulées.
- Suites existantes : retry sans terminal, calendrier et historique, Temporary Projection, immutabilité Results, monotonies Binding/LKV, Acquire/Takeover.

## Campagnes et résultats

Commandes exactes, résultats et empreintes des logs : [archive WP2](2026-10-16-consumption-generic-transactional-protocol-wp2-verification.txt). Tous les Maven sont lancés depuis `app/`. Les échecs intermédiaires sont conservés séparément des PASS finaux : compilation/imports de nouvelles fixtures ; attentes CURRENT à adapter ; test de graphe délégué masqué par une lambda ; capture masquant l'erreur de garde avec input null. Aucun test désactivé ni assertion durable retirée.

**ALL8 final : BUILD SUCCESS, 949 tests dans 203 classes, 949 réussis, 0 failure, 0 error, 0 skip**, durée 2 min 27 s. Puis renforcement final des assertions D17 (codes/reasons/clôture, noms de contraintes et provenance explicitement forcée dans la fixture recovery), sans modification de production : **COMMAND ciblé 34/34 PASS**, avec trace SQL, durée 10,780 s. Ces 34 méthodes sont des réexécutions de la classe déjà présente dans ALL8 ; ne pas annoncer 983 tests distincts. Les deux dernières campagnes font **983 exécutions réussies**, zéro échec/erreur/skip.

Preuves finales représentatives : infra-primary transactionnels **16/16**, lifecycle **12/12**, Command **34/34** ; Event materialization **8/8** et runtime Event **4/4** ; Projection canonique **14/14** ; Binding **6/6** ; Registration **5/5** ; Command Result **3/3** ; Registration Result **4/4** ; LKV runtime **8/8** et métriques **3/3** ; Command runtime **8/8**, multiworker **1/1**, injection de résolution owner **2/2**. Les 949 incluent les autres suites de la fermeture Maven ; aucun autre runtime n'est déduit de sa seule compilation.

Toutes les tentatives Maven de la mission, répétitions incluses : **3859 tests rapportés**, **3848 réussis**, **2 échecs d'assertion**, **9 erreurs**, **0 ignoré**. Ces échecs intermédiaires sont corrigés : deux échecs d’assertion (graphe masqué et garde input nul), deux attentes lifecycle CURRENT, trois erreurs de campagne avec artefacts invalides de fixtures et quatre erreurs d'injection Registration Result. Les autres arrêts sont des erreurs de compilation des nouvelles fixtures, comptées zéro test. L'archive conserve chaque commande, résultat et hash ; aucun arrêt n'est effacé du bilan.

Les fixtures Registration et Registration Result avaient comme Binding/Command WP0 une injection TransactionRunner non qualifiée ; qualifier le runner propre au runtime débloque leurs tests, sans modifier les beans globaux. ALL8 conserve un avertissement Surefire de fermeture JVM infra-primary à 30 s ; le même message est présent dans le log WP0 de clôture (`/tmp/pocoma-wp0-command-slice-closure.log`, ligne 5181 puis BUILD SUCCESS ligne 5768). Le dump situe l'attente dans le shutdown Spring/Hikari/DDL après arrêt des conteneurs. Aucun timeout ni skip n'a été ajouté pour le masquer.

Slices effectivement franchies : C/E/P/B/L et compléments R/CR/RR, exactement déclarés. Franchissement inattendu : **NONE**. Gate global **NOT REQUIRED/non exécuté** ; full reactor **NOT REQUIRED/non exécuté**. La fermeture `-am` compile et teste certains supports HTTP sans ajouter l'anchor WEB ni modifier ses comportements. Aucune migration de production ajoutée ou changée. Empreintes Keycloak/Bruno inchangées et suppression originale préservée, tous exclus du commit moteur/tests.

Contrôles Git/documentaires : `git diff --check` ciblé, inventaire zéro caller terminal `lockCurrentClaim`, absence de changement POM/SQL/infra-tx-spring, revue du diff stagé, validation des liens documentaires ; staging par manifeste explicite des seuls fichiers WP2. Le SHA et la divergence après le commit documentaire/push sont donnés dans la restitution, sans autoréférence de ce rapport à son propre commit.

## Limites, risques et verdict

Les ordres d'attente triples FK/CAS/takeover, tous les ordres de double publication, erreurs réseau/rollback incertain, contraintes/listeners futurs, multiclés, charge, fairness, délai maximal et coût du travail stale restent hors de cette preuve bornée. T07 approfondi et variantes adverses T02/T03/T04/T08/T11 restent WP4 ; validation fonctionnelle exhaustive des huit préfixes T09 et policies détaillées reste WP3. Ces reports ne dispensent pas des preuves minimales ci-dessus. Aucune dette officielle clôturée ; retrait des anciennes APIs réservé WP5 et clôture globale WP6 non commencés.

Déploiement mixte ancien/nouveau conserve le risque D17 : tout déploiement futur doit drainer/aligner les workers concernés. Aucun déploiement autorisé ou réalisé ici. Aucune absence universelle de deadlock ni mesure de performance revendiquée.

**PASS WP2 — bascule cohérente des huit familles et preuves minimales immédiates satisfaites.** Un seul suffixe terminal, retry distinct, owners gardés, participants internes, aucun prélock terminal actif, contraintes et fences métier conservés. D17 TARGET progresse avec gagnant durable et aucun effet stale. CAS perdu, clôture terminale/retry refusée et commit refusé annulent les effets concernés. Aucun résultat public APPLIED avant commit confirmé.

Aucun changement d'invariant WP1 ; seule précision d'emplacement de la garde LKV liée au graphe réel existant. La panne rollback/commit non confirmé est injectée à la frontière runner pour tester UNKNOWN et l'interdiction de classification ; ce test ne prétend pas reproduire une panne distribuée PostgreSQL. Les scénarios de rollback physique sont prouvés séparément sur PostgreSQL réel. Les approfondissements ci-dessus restent WP3/WP4 ; aucun de ces WP n'est commencé ou déclaré couvert intégralement.
