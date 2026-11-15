# WP1 — Contrat commun et frontière de rollback décidés

Date : 2026-10-16. Plan de rattachement : [protocole générique Consumption](2026-10-16-consumption-generic-transactional-protocol.md). **Verdict WP1 : PASS contractuel. D01–D10 CLOSED.** WP2 n'est pas commencé ; aucun comportement TARGET n'est déclaré exécuté ou prouvé par ce rapport.

## 1. Executive summary

Une seule destination est retenue pour Command, Registration, Binding, Event, Projection Task, Command Result, Registration Result et Latest Known Version : le moteur possède la finalisation transactionnelle ; les familles fournissent leurs effets SQL et leurs politiques. Aucun protocole pessimiste terminal par famille, aucun nouveau framework de callbacks.

Les décisions nécessaires à WP2 sont arrêtées : signal interne `LostClaimException`, rollback physique avant traduction, entrées propriétaires interdites sous transaction ambiante, delegates internes participants sans capture, terminalisation commune Success/Rejected/TerminalFailure et provenance explicite, clôture exactement une ligne, barrière ORM complète avant CAS. Pot conserve son autorité précoce et Binding son fence à sa position actuelle. Retry reste une transition distincte sans publication terminale. Recovery Binding utilise le même suffixe après rollback de l'Execute échoué.

**READY FOR WP2 au titre de la conception seulement.** Les preuves expérimentales TARGET restent des obligations de WP2 et des approfondissements WP3/WP4. WP0 prouve D17 CURRENT avec inputs et FK actives, ses témoins et des rollbacks bornés ; il ne prouve ni D17 TARGET ni une absence universelle de deadlock. Les recommandations d'exemption Event/Projection de l'étude initiale ne sont pas retenues.

## 2. Baseline, références et vérification déclarée

Branche initiale et conservée : `feat/runtime-monolith`. HEAD initial : `2bfa0f8e793035849e67e5927e26b8583236e401`. Upstream : `origin/feat/runtime-monolith`, divergence initiale `0/0`. `git ls-remote origin refs/heads/feat/runtime-monolith` confirme ce même SHA serveur avant rédaction. Les commits WP0 `2a9d8f3f1a1d0830e01c4f42a9f338b4361bc0c8` et `2bfa0f8e` sont présents. `git diff --stat 2bfa0f8e -- app` est vide avant intervention.

Préexistants exclus du livrable : `docker/keycloak/pocoma-realm.json` ; Bruno `01 Create Pot.bru` supprimé, `02`–`07` et `folder.bru` modifiés, quatre fichiers non suivis `001 Registrations.bru`, `002 RegistrationResult.bru`, `003 Me-Binding.bru`, `004 Create Pot.bru`. Empreintes SHA256 capturées avant édition, à comparer avant commit/push. Ni stash, ni reset, ni staging global.

Références obligatoires consultées :

| Référence | Portée retenue |
|---|---|
| [Plan canonique](2026-10-16-consumption-generic-transactional-protocol.md), notamment §4–§10 | Source exacte des D01–D10 et des T01–T11 ; ce rapport arrête les choix WP1, sans modifier la DoD du chantier |
| [Rapport WP0](2026-10-16-consumption-generic-transactional-protocol-wp0-baseline.md) et [preuves PostgreSQL](2026-10-16-consumption-generic-transactional-protocol-wp0-verification.txt) | CURRENT : 52 tests ciblés, Binding 5/5, COMMAND canonique 857/857 à clôture ; pas une nouvelle campagne WP1 ni preuve des six autres runtimes |
| [Audit générique](../architecture/Consumption_Generic_Transactional_Protocol_Audit_2026-10-16.md) | Inventaire et argument de suppression d'arête D17 ; CONDITIONAL GO, pas résultat TARGET |
| [Étude de finalisation](../architecture/Consumption_Finalization_Unification_Study_2026-10-16.md) | Variantes A/B/C et risques pré-CAS ; réserves d'invocation explicitées par D04 |
| [Canon Consumption](../architecture/consumption-transactional-execution.md) | Autorité S/PENDING/C, atomicité et séparation Execute/failure ; sa formulation « ouvre la transaction » doit être lue sous la restriction REQUIRED explicitée ici |
| [Garanties système](../guarantees/System_Guarantees.md) | Garanties bornées ; ne pas généraliser « publication divergente échoue » au root Projection first wins, ni « idempotence » à l'INSERT strict Command |
| [Politique Maven](../testing/Reactor_Verification_Policy.md) | Lue avant toute analyse sous app ; scope documentaire ci-dessous |
| [Canon Projection](../architecture/projection-task-execution.md), [audit locks](../debts/BINDING_LOCK_ORDER_GUARD/Audit_2026-10-16.md) | Préparation pure et autorités locales ; aucune modification de statut officiel des dettes |

**Verification scope WP1, déclaré avant édition :** production attendue NONE ; slice primaire NONE, secondaires NONE ; analyse Consumption partagé et COMMAND/EVENT/PROJECTION/BINDING/LKV + compléments Registration/Results. Forbidden production impact : tout runtime, test, schéma, dépendance et configuration sous `app/`. Database verification NONE ; aucune migration. Global architecture gate NOT REQUIRED et non exécuté. Full reactor NOT REQUIRED et non exécuté : mission documentaire, sans intégration main ni clôture de Wave. Aucun Maven, installation SNAPSHOT ou test lancé. Aucun franchissement de slice attendu ou observé. Les commandes futures du §12 ne sont pas des commandes exécutées WP1.

## 3. Décisions D01–D10

Les intitulés ci-dessous reproduisent exactement la colonne « ID / échéance » du plan §10.2. Toutes sont **CLOSED**, y compris leurs conditions d'échec ; une preuve expérimentale future n'est pas assimilée à une décision ouverte. Les composants cités sont localisés au §11, les obligations de preuve au §12.

### D01 — WP1 : LOST_CLAIM public

- **Options et conséquences :** retour normal du service : peut committer les effets ; exception partout : sûre mais rompt inutilement les résultats Event/Projection ; exception interne puis traduction après rollback : sûre si l'entrée possède la transaction physique.
- **Choix recommandé, arrêté :** troisième option. `LostClaimException` existante reste un signal interne non classifiable. Execute la propage ; les wrappers owner Finalize/HandleFailure la traduisent après retour exceptionnel du runner et rollback confirmé. Sequential l'absorbe après cette sortie. Aucun participant ne la traduit.
- **Justification code/invariant :** `SpringTransactionRunner.runInTransaction` appelle `TransactionTemplate.execute` sans capture ; les wrappers actuels ne distinguent pas les appels physiques et participants. REQUIRED peut rejoindre un appelant. Le seul placement syntaxique d'un catch après le runner est insuffisant.
- **Contrat observable :** LOST_CLAIM n'est rendu qu'après rollback intégral confirmé ; APPLIED seulement après commit confirmé. Un owner appelé sous transaction ambiante échoue avant callback (§4). Aucun retry automatique.
- **WP2 :** T04/T05/T10, wrapper réel, ambient refusé et participant REQUIRED perdant ; observer les états persistés.
- **Impacts :** wrappers engine-consumption, garde Spring en composition-runtime-spring, Sequential et assemblages Event/Projection ; aucun changement de signature publique des use cases.

### D02 — WP1 : CAS perdu

- **Options et conséquences :** `false` sans contrôle laisse committer ; rollback-only capturé permet de poursuivre avec résultat trompeur/UnexpectedRollback ; exception propagée jusqu'à owner provoque une sortie atomique explicite.
- **Choix recommandé, arrêté :** CAS zéro lève `LostClaimException(S,C)` depuis l'opération lifecycle commune ; pas de savepoint, compensation partielle ou poursuite. Toute la transaction d'effets est annulée, pas l'acquisition déjà committée.
- **Justification :** Execute lève déjà après `tryTerminalize=false` ; Finalize CURRENT retourne LOST avant callback grâce au prélock, garantie incompatible avec effets avant CAS. Le prédicat S/PENDING/C reste l'autorité ; lease expirée courante reste admissible.
- **Contrat observable :** effet provisoire → CAS zéro → exception → rollback → sortie physique → éventuelle traduction. Même contrat pour retry et recovery ; aucune écriture gagnante du takeover touchée.
- **WP2 :** T01/T02/T04/T06/T09 ; pertes après vrais SQL, vérification métier/outbox/provenance/S/C.
- **Impacts :** lifecycle port/adapter, Execute/Finalize/Failure, recovery et wrappers ; ancienne API booléenne seulement délégante transitoire (§11).

### D03 — WP1 : clôture refusée

- **Options et conséquences :** tolérer/réparer après coup laisse DONE ou retry incohérent ; LOST_CLAIM banal masque une anomalie après CAS gagnant ; anomalie rollbackante conserve l'atomicité.
- **Choix recommandé, arrêté :** exiger exactement une ligne ; conserver une anomalie non checked, avec un sous-type technique `ConsumptionClaimClosureException extends IllegalStateException` portant opération/cardinalité/S/C. Il permet de distinguer ce point d’échec des invariants des callbacks dans les captures externes. Ce n’est pas un type de domaine ni une perte de claim.
- **Justification :** `claims.end/fail` filtrent C, S, ended_at NULL et invalidated_at NULL ; l'adapter exige déjà une ligne. La FK composite ne garantit ni l'ouverture ni une clôture correcte.
- **Contrat observable :** zéro, négatif ou supérieur à un annule effets, CAS et clôture. Aucun APPLIED, aucune réparation indépendante ni conversion en perte sur simple relecture DONE de sa propre transaction annulée.
- **WP2 :** T04, vrai CAS gagnant puis end/fail refusé, terminal et retry ; test double pour cardinalité impossible >1, PostgreSQL pour zéro.
- **Impacts :** JpaConsumptionLifecycleAdapter, JpaConsumptionClaimRepository (SQL conservé), finalizer, captures externes avant classifier, et tests locaux futurs.

### D04 — WP1 : stale callbacks

- **Options et conséquences :** exclusion préalable par famille conserve deux protocoles ; zéro effet durable autorise du calcul/SQL perdu et exige des callbacks rollbackables.
- **Choix recommandé, arrêté :** zéro effet métier durable perdant pour les huit familles. L'invocation stale, y compris concurrente, est permise. Aucune exemption Event/Projection.
- **Justification :** Task.ensure, root/artifacts/failure et les six callbacks Execute inspectés écrivent dans PostgreSQL ; les compteurs des doubles de tests ne constituent pas un invariant métier. Préparation Projection pure et validation Binding restent nécessaires pour leurs propres invariants.
- **Contrat observable :** aucune Task/root/artifact/failure/outcome/provenance nouvelle du perdant après rollback ; les données préexistantes ou du gagnant subsistent. Logs/métriques de tentative peuvent subsister.
- **WP2 :** T06 stale Event et publish/recordFailure Projection obligatoires ; T09 pour autres familles ; substitutions exactes au §6.2.
- **Impacts :** FencedDurableEffect/ConsumptionExecution (sémantique), callbacks et stores du §8, contrats des tests listés ; aucune signature de callback nouvelle.

### D05 — WP1 : flush

- **Options et conséquences :** ordre Java supposé égal SQL laisse des verrous tardifs ; flush à chaque étape coûte et peut déplacer les acquisitions ; barrières minimales déclarées permettent de contrôler le SQL réel.
- **Choix recommandé, arrêté :** conserver les barrières Pot/Events et utiliser **la barrière complète déjà commune** `JpaConsumptionSlotRepository.terminalize/scheduleRetry`, `@Modifying(flushAutomatically=true)`, avant l'envoi de leur UPDATE. Pas de second flush explicite systématique dans le moteur ou la provenance.
- **Justification :** provenance utilise saveAll différé ; le CAS repository flushe l'EntityManager partagé avant SQL, puis clearAutomatically. Events utilise saveAllAndFlush ; les écritures JDBC sont déjà exécutées. Le flush de claim.end ne doit rien découvrir de métier.
- **Contrat observable :** tous les DML effets/provenance requis sont envoyés avant CAS ; seul DML claim ensuite. Aucune mutation de managed entity ni callback métier après barrière. Si WP2 révèle un autre persistence context, le faire participer au contexte unique ou garantir son flush avant CAS ; ne jamais accepter le write tardif (§7).
- **WP2 :** T01/T04/T07/T08 avec et sans Events, trace SQL et injection d'échec de flush ; vérifier absence de DML métier au commit.
- **Impacts :** repository slots, adapter lifecycle, provenance, JPA Pot/outbox et configurations JDBC/JPA ; aucune nouvelle méthode de flush dans un port moteur.

### D06 — WP1 : autorités métier

- **Options et conséquences :** hiérarchie totale artificielle change les domaines ; fences tous finaux invalident Pot ; ordres locaux composés conservent les protections justifiées.
- **Choix recommandé, arrêté :** CAS Pot avant mutations protégées ; métier/Events puis fence Binding positif à sa position courante ; stream avant initializer/actif/facts ; publications/provenance flushées avant CAS S puis C.
- **Justification :** `updateIfActive`, `ExecuteRecordedCommandService` et `JpaExternalIdentityBindingAdapter.acquireWithInitializer` portent des autorités différentes. CAS Consumption ne protège pas deux Commands distinctes sur le même Pot, ni une identité détachée.
- **Contrat observable :** mêmes versions et rejets, locks d'autorité gardés jusqu'au commit, aucune nouvelle arête S→publication. Plusieurs clés partageables imposent un ordre local stable commun à leurs writers.
- **WP2 :** T07/T08 ; détachement pendant/après fence, mêmes E/Pots différents et même Pot/E différentes ; ordre SQL et états durables.
- **Impacts :** callbacks Command/Registration, adapters Pot/Binding (contrats conservés), suffixe moteur ; relation partielle complète §8.

### D07 — WP1 : uniques pré-CAS

- **Options et conséquences :** DO NOTHING universel masque les divergences ; toute SQL convertie en perte masque un défaut du claim courant ; rollback puis lecture fraîche distingue les cas.
- **Choix recommandé, arrêté :** après rollback confirmé d'une erreur SQL pré-CAS, contrôler S/C dans une nouvelle lecture READ COMMITTED via ConsumptionQueryPort à l'extérieur de l'owner. Normaliser une erreur SQL de concurrence/contrainte seulement si token remplacé ou slot DONE ; conserver SQLSTATE et erreur originale dans les diagnostics. Une divergence fonctionnelle explicite reste diagnostiquée, jamais un succès idempotent.
- **Justification :** INSERT Command strict peut lever 23505 sans atteindre CAS ; Results comparent le contenu après ON CONFLICT, Projection root ne compare pas. Aucune de ces règles ne se déduit de l'ownership.
- **Contrat observable :** encore PENDING/C → erreur d'origine à classifier selon famille ; slot absent, lecture échouée ou état indéterminé → RuntimeFailure d'infrastructure, pas perte présumée. Supplanté/DONE → abandon après rollback, aucun nouvel Execute ni succès fabriqué. Ne pas appliquer cette normalisation aux anomalies de clôture, aux erreurs de frontière ou aux exceptions postcommit.
- **WP2 :** T05, 23505 claim stale contre claim courant ; T03/T06 deux ordres de publication. 40P01 et 40001 restent identifiés avant décision éventuelle d'abandon stale, pas confondus avec CAS zéro.
- **Impacts :** traitement externe Sequential (autour d’Execute **et** de HandleFailure) et adaptateurs Event/Projection, ConsumptionQueryPort existant sans nouvelle signature ; stores Command/Results conservent leur SQL et comparaisons.

### D08 — WP1 : recovery

- **Options et conséquences :** prélock spécial garde l'inversion ; effets en transaction autonome cassent l'atomicité ; nouvelle transaction après rollback avec suffixe commun préserve décision et rollback.
- **Choix recommandé, arrêté :** troisième option. Recovery possède sa transaction physique séparée, relit E/B exact puis appelle **Execute participant brut**, jamais le wrapper public owner. Retirer son prélock S au WP2 ; aucun rejeu des handlers Pot.
- **Justification :** recovery CURRENT appelle le wrapper Execute REQUIRED dans son runner ; il rejoint déjà sa transaction. La séparation d'injection permet la garde publique sans interdire cette participation. B permanent et non réutilisable justifie la stabilité de l'absence exacte.
- **Contrat observable :** B encore présent → BindingFenceConflictException technique ; absent → REJECTED(CALLER_IDENTITY_NOT_CURRENT), outcome/event puis suffixe ; perte C → rollback recovery et propagation. Pas de traduction interne, pas de rejet public déduit du seul fence perdu.
- **WP2 :** T01 recovery avec provenance ancienne ; T08/T10 XID Execute échoué distinct de recovery, même XID recovery/participant/S/C.
- **Impacts :** BindingFenceRecoveryExecuteUseCase (constructor/wiring), CommandConsumptionRuntimeConfiguration, services participants ; Binding authority inchangée.

### D09 — WP1 : technique/retry/postcommit

- **Options et conséquences :** policies universelles changent les produits ; retry en FAILED publie trop tôt ; policies conservées et résolution transactionnelle distinguée préservent leur sens.
- **Choix recommandé, arrêté :** policies existantes, retry dédié S/PENDING/C → clôture C.fail, sans terminal ; classification uniquement après rollback confirmé. Échec rollback/commit non résolu = RuntimeFailure, pas HandleFailure automatique. Observations de succès après commit isolées de la classification métier (§9).
- **Justification :** Sequential capture actuellement toute RuntimeException hors perte ; `MeteredAdvanceLatestKnownVersionUseCase.afterCommit` peut lever après commit. Ce chemin doit être distingué au WP2 ; succès durable ne peut pas devenir FAILED.
- **Contrat observable :** calendrier, budget, historique et takeover conservés ; erreur SQL actuelle ≠ perte ≠ erreur d'observation. Commit incertain exige lecture de réconciliation sans republication aveugle.
- **WP2 :** T04/T09/T10/T11, retry sans terminal, commit refusé, rollback défaillant, afterCommit qui lève, préparation Temporary à forte tentative.
- **Impacts :** FailureService/port/adapter, orchestrateurs, frontière Spring, métriques LKV ; pas de modification de policy fonctionnelle.

### D10 — WP1 : suppression prélocks/APIs

- **Options et conséquences :** retrait immédiat de surface casse les callers ; legacy par famille conserve deux protocoles ; migration atomique puis suppression sans callers permet la convergence.
- **Choix recommandé, arrêté :** comportements prélock terminaux supprimés ensemble au WP2 ; anciennes signatures éventuelles ne sont que délégations au suffixe unique. Suppression de surface WP5 après recherche de tous usages production/tests. Aucun flag legacy.
- **Justification :** conserver une seule branche S→outcome concurrente d'outcome→provenance S conserve D17. Acquire/takeover et stream Binding assurent d'autres arbitrages.
- **Contrat observable :** huit familles sous suffixe commun dès sortie WP2 ; acquire `findByKeyForUpdate` et locks stream préservés ; pas de rollout de protocoles mixtes revendiqué sûr.
- **WP2 :** T01, T10, inventaire de tous callers et ALL8 ; suppression définitive et recherches usages WP5.
- **Impacts :** lifecycle port/adapter, Finalize/Recovery et configurations des huit familles ; `findByIdForUpdate` candidat WP5 uniquement si usages zéro.

## 4. Transaction owner et transaction participant

**Owner** : entrée responsable de l'ouverture de la transaction physique, de sa résolution commit/rollback, de la sortie et éventuellement de la traduction. Il ne peut annoncer APPLIED avant commit effectif. **Participant** : rejoint cette transaction, exécute les SQL et laisse toute exception de fencing/SQL traverser ; pas de commit indépendant, pas de capture pour poursuivre, pas de conversion en retour normal autorisant le commit.

[REQUIRED Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html) peut joindre une transaction physique existante ; un rollback-only interne affecte cette transaction et peut provoquer UnexpectedRollbackException à la sortie externe. `TransactionTemplate.execute` n'est donc pas une garantie de nouvelle transaction physique. Les configurations inspectées construisent `new TransactionTemplate(manager)`, sans REQUIRES_NEW ni isolation explicite ; la référence WP0 observe READ COMMITTED. WP2 doit vérifier le manager et l'isolation effectifs, pas inférer une transaction neuve du retour du template.

| Composant | Rôle TARGET décidé et capture autorisée |
|---|---|
| TransactionRunner / SpringTransactionRunner | Mécanisme transactionnel générique, REQUIRED conservé ; n'est owner physique que sans transaction active. Port global inchangé, pas de modification sémantique pour admission/WEB |
| Wrappers TransactionalAcquire/Execute/Finalize/HandleConsumptionFailureUseCase | Entrées owner uniquement, runner Consumption gardé sans ambient. Acquire rend un claim acquis après commit ; Execute rend son résultat après commit ou propage perte ; Finalize/Failure traduisent uniquement la perte après rollback confirmé |
| ExecuteConsumptionService | Participant : callback une fois puis délégation du suffixe, aucun catch ; utilisé brut dans recovery |
| FinalizeConsumptionService | Participant et propriétaire **du protocole**, pas de la transaction physique : effet, provenance, terminalisation commune ; pas de prélock ni catch de perte |
| HandleConsumptionFailureService | Participant : lecture claim/attempt, policy ; Fail → finalizer participant injecté ; RetryAfter → transition dédiée. Jamais wrapper owner interne |
| SequentialConsumptionOrchestrator | Coordinateur hors transaction. Acquire, Execute, puis éventuel HandleFailure ont des résolutions physiques séparées ; perte ignorée sans classifier, erreurs de résolution remontées RuntimeFailure |
| AcquireThenFinalizeConsumptionOrchestrator | Coordinateur hors transaction ; préparation Event/Projection hors transaction finale, puis owner Finalize/Failure ; aucune fusion des orchestrateurs nécessaire |
| BindingFenceRecoveryExecuteUseCase | Coordinateur extérieur à Execute owner ; catch BindingFenceLost uniquement après rollback confirmé ; owner de la recovery via runner gardé ; délègue Execute participant brut dans cette recovery, perte traverse son runner |
| Ports et adaptateurs SQL | Participants ; lifecycle/provenance/outcome/Binding gardent MANDATORY. JDBC via connexion liée ; Task REQUIRED et template Projection REQUIRED doivent rejoindre le même owner, jamais ouvrir une seconde ressource indépendante |
| Callbacks des huit familles | Participants ; ne touchent pas lifecycle, aucune transaction indépendante, pas d'effet externe requis avant commit ; conformité §6/§8 |

### 4.1 Garde concrète à implémenter au WP2

Choix arrêté : un **petit décorateur Spring du runner propre aux entrées Consumption**, local à `composition-runtime-spring`, implémentant le port TransactionRunner existant. À `runInTransaction`, avant délégation au SpringTransactionRunner REQUIRED, vérifier `TransactionSynchronizationManager.isActualTransactionActive()` ; rejeter aussi une synchronisation ambiante qui pourrait brouiller les callbacks de résolution. Échec = IllegalStateException de composition avant callback, sans ouvrir/suspendre de transaction. `runAfterCommit` délègue ; aucun changement du runner global. Ce décorateur est une garde d'entrée, pas un framework de transactions.

Les deux orchestrateurs et la façade recovery reçoivent également une garde à leur entrée publique en composition, avant scan/try/catch/classification. Ainsi un appel orchestrateur sous ambient est refusé avant Acquire et n'est pas transformé en ProcessingFailure. Les wrappers sont sûrs même appelés directement. Les services participants restent des objets internes explicitement injectés/assemblés, non exposés comme entrées publiques substituables aux wrappers.

Les quatre wrappers sont owner-only, **y compris Execute et Acquire** ; limiter la garde à Finalize/Failure ne suffirait pas à séparer Execute échoué et HandleFailure. Recovery reçoit deux dépendances Execute distinctes : owner pour la première tentative, participant pour la transaction recovery. HandleFailure reçoit Finalize participant, pas son wrapper. Les stores internes REQUIRED ne reçoivent pas cette garde : ils doivent pouvoir joindre. Aucune utilisation de REQUIRES_NEW, NOT_SUPPORTED ou NESTED pour cacher un appelant incompatible.

Test ambiant : appel public sous transaction active → exception avant callback/SQL, état de l'appelant inchangé par Consumption ; le caller décide ensuite sa propre résolution. Test participant : callback déjà sous owner → stores REQUIRED et delegate recovery même PID/XID ; perte traverse tout jusqu'au rollback owner. Si un participant est appelé directement sous une transaction contrôlée par un autre owner explicite, cet owner doit respecter tout le protocole et ne peut traduire qu'après sa résolution physique. Aucun résultat APPLIED interne n'est une preuve de commit : les valeurs intermédiaires restent privées à l'owner.

### 4.2 Frontière Execute échoué / HandleFailure

Séquence imposée : Acquire commit A ; Execute transaction E ; exception sort des participants ; rollback E confirmé ; aucune transaction active ; classifier ; transaction F distincte pour HandleFailure ; résolution F avant résultat. Vérifier PID/XID et visibilité indépendante. Recovery remplace F seulement pour BindingFenceLost selon §9, dans une nouvelle transaction R après rollback E. Une erreur de résolution E interdit d'engager F/R automatiquement.

## 5. Fencing, perte et clôture

```text
BEGIN transaction d'effets
  effets SQL provisoires dans leurs ordres locaux
  fencings métier nécessaires à leurs positions locales
  publications SQL puis provenance validée
  FLUSH COMPLET DES EFFETS ORM (barrière pré-CAS)
  CAS slot WHERE slot_id=S AND status=PENDING AND current_claim_id=C
    0 : LostClaimException → sortie exceptionnelle → ROLLBACK intégral
    autre que 1 : anomalie → ROLLBACK intégral
  clôture conditionnelle C du même S, ouverte et non invalidée
    autre que 1 : anomalie → ROLLBACK intégral y compris CAS
COMMIT
sortie réussie seulement : APPLIED / résultat Execute
sortie perte avec rollback confirmé seulement : LOST_CLAIM ou propagation Execute
```

Aucun `return LOST_CLAIM` depuis un participant après writes ; aucun catch local suivi d'un retour normal, même sous REQUIRED imbriqué. Le signal non checked `LostClaimException` existant traverse les interceptors Spring et le runner owner. Un caller qui le capture à l'intérieur de la transaction viole le contrat ; rollback-only ne donne pas le droit d'annoncer le résultat avant résolution. Aucune relance d'Execute automatique sur cette perte.

Le CAS ne teste ni lease, ni revision, ni nextClaimAt : ils ne sont pas des tokens d'autorité. Après un CAS gagnant, le verrou S est conservé jusqu'au commit/rollback. La clôture reste `UPDATE claims WHERE claim_id=C AND slot_id=S AND ended_at IS NULL AND invalidated_at IS NULL`, cardinalité exactement un. `end` conserve SUCCESS/REJECTED ; `fail` conserve PROCESSING_FAILURE avec code/catégorie/message/date pour FAILED et retry. Les primitives actuelles satisfont ce contrat de clôture ; conserver leur SQL et le contrôle exactly-one, dont le refus devient ConsumptionClaimClosureException. Une erreur SQL de end/fail est encapsulée avec sa cause dans cette même anomalie de clôture, afin de ne pas la normaliser comme une erreur pré-CAS. Leur orchestration doit désormais être appelée depuis une seule opération terminale commune (§11).

Takeover prend S avant invalidation C : s'il committe d'abord, le CAS ancien perd ; si CAS gagne d'abord, takeover attend puis voit DONE, ou le PENDING éligible après retry. En cas de rollback du finalizer, takeover réévalue l'état antérieur. FK claim→S et composite `(S,current_claim_id)`→`(S,C)` empêchent un token étranger mais n'impliquent pas que C soit ouvert ; clôture conditionnelle indispensable. Pas de réinstallation d'un ancien C, pas d'ABA. Une clôture zéro après CAS n'est donc pas traitée comme une simple course normale.

**Échec de résolution :** si rollback lui-même lève, ne pas traduire la LostClaimException initiale en LOST_CLAIM confirmé ; conserver exception principale et causes/suppressed, remonter une erreur d'infrastructure. Si commit lève, aucun APPLIED ; différencier rollback connu, commit connu (erreur d'observation ensuite) et issue inconnue (§9). Une simple exception Java ne prouve aucune de ces trois issues.

## 6. Contrat des callbacks et observabilités

Le contrat s'applique à ConsumptionExecution, FencedDurableEffect, initializer Registration et extensions/projectors effectivement assemblés : même datasource/manager/transaction que S/C, SQL rollbackable, aucun commit autonome, autocommit, REQUIRES_NEW ou effet externe irréversible avant commit. Aucun envoi asynchrone direct ; Events requis via l'outbox SQL. `afterCommit` ne garantit pas la livraison et ne remplace pas l'outbox. Préparation Projection pure, historique exact, validation avant publication conservés.

Les callbacks peuvent être stale dès leur entrée ; aucune promesse de zéro invocation, aucune mutation de cache métier partagé. Listes Events/inputs et résultats locaux à l'invocation. Ils respectent les ordres locaux, laissent remonter les erreurs SQL/fencing et n'appellent aucun acquire/finalizer d'un autre slot. Les `ON CONFLICT` déjà justifiés font partie des contrats des stores ; intercepter une SQLException d'une transaction PostgreSQL avortée puis poursuivre est interdit. Les contrôles préalables liés à l'autorisation Binding, à Pot ou à la préparation restent obligatoires : retirer un prélock Consumption ne supprime pas ces invariants distincts.

### 6.1 Conformité inspectée

Aucun effet externe irréversible requis trouvé sur les chaînes présentes. Le callback Event produit une Task via JdbcProjectionTaskStoreAdapter REQUIRED ; Projection publie root puis artifacts ou failure dans le template REQUIRED du store ; Command publie Pot/Events outbox puis outcome/terminal event ; Registration crée User/fact/occurrence/Binding/outcome via SQL ; Binding et LKV font des upserts monotones ; les Results font INSERT/compare immutable. Voir séquences et classes §8/§11.

LKV enregistre advanced/unchanged via TransactionSynchronization.afterCommit ; `errors.increment()`/warn immédiats sont des observabilités de tentative et peuvent survivre au rollback. Logs, traces, UUID, durées et compteurs d'invocation n'ont pas une atomicité métier. Le chemin afterCommit peut aujourd'hui lever : obligation de sûreté WP2 de l'isoler (§9), pas conformité postcommit déjà prouvée. Aucun contrat de pureté d'une extension future n'est déduit de cette inspection ; toute nouvelle implémentation doit déclarer ses SQL et son ordre.

### 6.2 Substitutions de tests au WP2, sans modification WP1

| Tests existants | Décision précise |
|---|---|
| FinalizeConsumptionServiceTest : `locksAndFencesBeforeApplyingTheDurableEffect`, `staleClaimCannotApplyItsDurableEffect`, `anImpossiblePostLockFenceLossFailsTheTransaction` | Remplacer ordre lock/effect par effet/provenance/CAS ; stale lève après effet. Remplacer « perte impossible après lock » par perte normale rollbackante. Ajouter wrapper réel et lecture durable ; les mocks seuls ne prouvent pas le rollback |
| Même classe : `terminalFailureAppliesItsEffectBetweenTheFenceAndSlotTermination` | Retirer attente du prélock ; conserver failure exacte et effet avant transition FAILED commune |
| TransactionalConsumptionExecutionPostgresTest : `shortFinalizationLocksBeforeTheEffectAndAnExpiredCurrentClaimMayFinish`, `takeoverBeforeShortFinalizationPreventsAnyDurableEffect` | Renommer selon propriété cible ; conserver lease expirée et absence d'effet durable ; forcer SQL provisoire stale puis rollback. Ne pas supprimer les assertions slot/claim |
| Event PostgreSQL : `expiredCurrentClaimMayFinalizeButTakeoverFencesTheOldClaimBeforeEnsure` | Remplacer `counting.calls=0` par invocation stale réelle avec Task SQL annulée ; conserver LOST après rollback, C2 courant et C1 TAKEN_OVER |
| Event PostgreSQL : `twoWorkersNeverInvokeTheEffectConcurrentlyForTheSameCandidate` | Conserver cas sans expiration, qui vérifie Acquire sous lease active ; limiter le nom/la portée à cette condition. Ajouter takeover pendant callback avec concurrence provisoire permise, une seule Task durable |
| ProjectionMaterializationConsumptionServiceTest : finalizer double LOST sans effet | Conserver comme test de délégation ; compléter avec double invoquant et preuve SQL, sans lui attribuer une garantie d'exclusion stale |
| ProjectionTaskConsumptionServiceTest / ProjectionTaskConsumptionOrchestratorTest | Pas d'ancien test stale explicite trouvé ; `writer.called=false`/finalizer non appelé pour Temporary et préparation restent inchangés. Ajouter stale publish **et** stale recordFailure |
| CanonicalProjectionTaskRuntimePostgresTest : `canonicalPublishAndConsumptionFinalizationShareOneLocalTransaction`, `canonicalPublishRollsBackWhenFinalizationTransactionFails` | Conserver et étendre root/artifacts/failure/clôture ; aucune substitution par compteur d'appel |
| CommandConsumptionPostgresTest : D17 CURRENT | Remplacer le test actif exigeant 40P01 par non-régression TARGET une fois protocole changé ; preuve CURRENT archivée WP0 et historique Git préservés. Témoins sans provenance conservés |

## 7. Flush : contrat physique et barrières minimales

L'ordre Java indique les appels ; Hibernate peut différer les INSERT et flusher avant requête ; PostgreSQL acquiert les verrous quand les SQL/contraintes s'exécutent. Ces trois ordres ne sont pas interchangeables.

| Primitive / emplacement | Effet réel pertinent et décision |
|---|---|
| JpaConsumptionProvenanceAdapter.appendInputs/appendResults : saveAll | Enregistre les entités, ne garantit pas l'envoi SQL immédiat. FK/uniques peuvent attendre au flush pré-CAS ; conserver les FK et saveAll |
| Acquire : claims.saveAndFlush | Flush du contexte avant installClaim ; nouveau C doit exister pour la FK composite. Conserver dans transaction Acquire courte |
| JpaPotCommandEventAppendAdapter.appendAll : saveAllAndFlush | Envoie Events et autres writes ORM en attente avant retour, donc avant fence Binding positif. Même une liste vide traverse cet appel sur la branche succès actuelle ; ne pas retirer ce flush |
| saveAndFlush métier éventuel | Flush complet du persistence context, pas seulement de l'objet passé ; peut avancer des acquisitions. Tout changement nécessite revue des mêmes clés et du préfixe Pot/Binding |
| JpaPotGlobalVersionRepository.updateIfActive/insertVersionMetadata | flushAutomatically avant SQL ; CAS Pot précède les mutations temporelles protégées. Ne pas présenter ce flush comme autorisant des mutations anticipées |
| Slot terminalize/scheduleRetry | flushAutomatically=true **avant UPDATE** : barrière finale complète du contexte commun, pour toutes familles et provenance. clearAutomatically=true après SQL détache, ne committe ni ne libère les locks |
| Claim end/fail | flushAutomatically=true reste ; doit être vide de writes métier. Sinon la barrière finale ou le contrat de participant est violé |
| JDBC outcome/task/Registration/Results/READ/Projection | SQL exécuté lors de l'appel ; aucun flush JPA implicite garanti par un JdbcTemplate. Les writes ORM en attente restent couverts par la barrière pré-CAS ; même connexion transactionnelle exigée |
| Template interne Projection / transactions REQUIRED | Rejoint l'owner, ne ferme pas la transaction physique ni ne vide nécessairement l'EntityManager. Son retour n'est pas un commit de root/artifacts |
| Auto-flush avant query | Peut avancer SQL et locks ; jamais utilisé comme unique garantie implicite d'envoi de tous les writes. Après CAS aucun callback/query métier/dirty checking nouveau |

**Décision de flush close :** pas de flush explicite commun supplémentaire à ce HEAD : le repository CAS possède déjà la barrière commune requise. Conserver cette annotation lors de la mutualisation. Il n'est pas permis d'attendre une trace pour décider s'il faut flusher : le contrat « complet avant CAS » est obligatoire maintenant ; la trace vérifie que la barrière choisie le réalise. Si elle échoue, WP2 corrige localement l'adapter par flush explicite avant CAS (et retire tout doublon), ou l'assemblage fautif, avant acceptation ; aucun retour au protocole pessimiste.

WP2 doit capturer ordre SQL et PID/XID pour : Pot avec/sans Events, inputs seuls/results seuls/les deux, root/artifacts et failure JDBC, retry. Injection d'erreur de flush → aucun CAS SQL envoyé ; injection après CAS → aucun DML métier ultérieur, seulement claim puis commit. Des listeners JPA ou synchronisations beforeCommit ne doivent pas créer de nouveau write après barrière ; tout ajout futur de ce type exige une preuve et une révision d'ordre. Contraintes différées et commit peuvent encore refuser la transaction malgré flush réussi.

## 8. Relation d'ordre partielle et conformité des huit familles

Notation : P=autorité Pot, E=stream Binding WRITE, O=outcome, T=terminal event, I/R=provenance, S=slot, C=claim. Le suffixe **Σ** vaut : provenance éventuelle validée → flush complet → CAS S/PENDING/C → C.end/fail exactement un → commit. Les FK de provenance peuvent prendre KEY SHARE S avant le CAS : « CAS après effets » n'affirme pas l'absence de tout verrou sur S auparavant.

Acquire/takeover pour toutes familles : bootstrap S via unique clé → S FOR UPDATE par clé → invalidation ancien C si lease expirée → nouveau C saveAndFlush → install token/FK composite → commit court. Aucun outcome, root ou autorité métier demandé sous ce S. Cet ordre S→C reste distinct du prélock terminal supprimé.

| Famille et conformité de conception | Séquence canonique TARGET des acquisitions / effets | Conditions et adaptations WP2 |
|---|---|---|
| Command — conforme sous contrat | reload Q/Binding MVCC ; P INSERT ou CAS version → metadata/DML temporel → Events SQL flushés → fence exact E/U/B/R → O strict → T (FK O) → Σ | Success/rejet conservent préfixe ; Binding absent : pas de fence positif ni métier ; rejet autorisation/métier avec Binding observé garde son fence. Fail et recovery déplacent leur publication avant S via suffixe commun. Aucun DO NOTHING outcome |
| Registration — conforme | stream E bootstrap/unique puis FOR UPDATE → initializer User et UserCreatedFact → occurrence permanente B/actif/révision/fait → O(request) → I(request) → Σ | E occupé : pas d'initializer/User ; O existant : réutiliser décision immutable puis provenance/Σ. Failure technique effet vide, aucun nouvel outcome Registration FAILED |
| Binding — conforme | fait immutable MVCC → UPSERT READ CURRENT_BINDING(E) → comparaison si même R → I(fact) → Σ | Pas de lock E WRITE ; R supérieur seulement, tombstone, divergence à R égale inchangés ; failure vide |
| Event — conforme sous contrat | préparation/routage hors finale ; Task.ensure unique ProjectionKey → Σ sans provenance ajoutée | Retirer prélock ; stale ensure autorisé mais rollback. Erreur SQL → RuntimeFailure après résolution, aucun HandleFailure/FAILED inventé ; claim ouvert jusqu'à takeover |
| Projection Task — conforme sous contrat | préparation historique/validation pure hors finale ; root unique ProjectionKey → artifacts FK root **ou** failure_id et comparaison → Σ | Retirer prélock ; root ALREADY_EXISTS conserve first wins sans comparaison payload ; Temporary → retry, pas de publication ; success/failure stale annulés |
| Command Result — conforme | sources terminal event/outcome/command immuables → ensureResult unique Q + comparaison → I(terminal event) → Σ | Owner historique et contenu immutable conservés ; slot distinct de Command ; sa Consumption SUCCESS peut matérialiser une source FAILED ; failure propre vide |
| Registration Result — conforme | sources request/outcome immuables → ensureResult unique request + comparaison → I(outcome) → Σ | Pas de nouveau fence Binding courant ; contenu et owner historiques conservés ; failure propre vide |
| Latest Known Version — conforme sous contrat | Event MVCC → UPSERT watermark(PotId) monotone → I(Event) → Σ → advanced/unchanged afterCommit | Aucun lock Pot WRITE ajouté ; même candidat sans avancement peut attendre l'unique. Erreurs métriques de tentative permises ; isolation de l'observation postcommit à assurer |

Ce tableau atteste une compatibilité par inspection, **pas huit PASS TARGET**. Les callbacks cités au §11 ont tous une destination SQL rollbackable ; Event/Projection requièrent changement du contrat d'invocation, Command changement failure/recovery, LKV protection postcommit, toutes familles adoption du owner et du suffixe commun.

### 8.1 FK, uniques et inversions

- Outcome Command strict puis terminal event unique Q/FK outcome ; unicité peut attendre le XID du holder avant CAS. Results ON CONFLICT + comparaison et root first wins restent différents. Ne pas introduire de publication après S.
- Provenance FK immédiates vers S : KEY SHARE attendu ; CAS non-clé S : NO KEY UPDATE attendu, compatible KEY SHARE ; Acquire FOR UPDATE incompatible KEY SHARE. Vérifier modes réels, clés référencées et file de takeover en troisième backend T07, pas seulement les noms des ressources.
- Claim FK vers S et unique(S,attempt)/(S,C), slot FK composite vers C : conserver intégrité et ordre S→C. Ni FK ni lease ne remplacent la clôture conditionnelle.
- Parents User/request/command/occurrence/root immuables peuvent recevoir KEY SHARE par FK. Aucun writer inspecté de stream E ne réclame P après E ; le préfixe Command P→E ne crée donc pas à lui seul de cycle avec Registration E→User. Aucun ordre total User/Pot/Binding/Projection imposé.
- Inversion connue CURRENT : C2 S FOR UPDATE→O pendant que C1 O→provenance KEY SHARE S ; TARGET retire S→O. D17 WP0 avec inputs est la preuve CURRENT ; inputs/results/recovery TARGET restent à exécuter.
- Déplacer Binding après O créerait potentiellement O→E face à E→O, notamment rejet autorisation sans sérialisation Pot ; déplacer CAS Consumption avant publications réintroduirait S→effet. Les deux déplacements sont interdits.
- Plusieurs slots peuvent partager une Task/root/watermark sans demander le slot adverse. Tester holder commit/rollback ; pas d'inférence universelle d'absence de cycle. Deux KEY SHARE puis CAS et un takeover en file exigent T07.

Un futur callback doit déclarer les clés partageables, verrous explicites, FK/uniques, barrières ORM et SQL des autres writers des mêmes clés. S'il acquiert plusieurs Pots/E/roots, adopter un ordre stable local pour ces clés chez tous les writers concernés ; résoudre une inversion avant intégration. Aucun acquire/finalizer croisé depuis callback, aucune autorité ou publication après Σ.CAS. Un cycle entièrement métier n'est pas corrigé par le CAS Consumption ; le traiter dans son domaine, sans inventer un ordre total artificiel.

## 9. Retries, recovery et résolution des erreurs

| Situation | Conduite obligatoire |
|---|---|
| Finalisation terminale Success/Rejected/TerminalFailure | Callback spécialisé ou vide puis Σ ; métadonnées validées, outcome fonctionnel distinct d'une erreur technique |
| RetryAfter | Après rollback Execute, policy sur attempt acquis ; `scheduleRetry(S,C,now+duration)` PENDING sans C, sans terminalReason/doneAt/outcome/event/provenance d'Execute ; claim.fail exactement un avec ProcessingFailure complet ; commit commun |
| Perte C | Signal interne, rollback, abandon sans retry automatique ni failure enregistrée sur le gagnant |
| BindingFenceLost | Rollback E confirmé puis nouvelle R ; relecture exacte E/B ; absent → rejet via participant/Σ sans Pot ; présent → BindingFenceConflictException technique selon policy ; aucun prélock S |
| SQL avant CAS, dont 23505 | Rollback avant toute relecture ; D07 : ownership frais prouvé perdu → abandon ; sinon classifier erreur d'origine. Transaction PostgreSQL avortée jamais réutilisée pour vérifier ownership |
| Erreur flush/contrainte/40P01/40001/timeout | Rollback confirmé puis classification spécialisée, sauf perte fraîche prouvée dans normalisation externe ; conserver SQLSTATE, causes et diagnostics. 25P02 serait une poursuite illégale après erreur, pas un signal de perte |
| Clôture refusée | Anomalie distincte ; rollback complet, RuntimeFailure/diagnostic technique, pas simple LOST_CLAIM par relecture ni réparation REQUIRES_NEW |
| Commit refusé et rollback confirmé | Aucun APPLIED ; erreur technique après fin physique, policy autorisée dans nouvelle transaction si l'issue et l'ownership sont établis |
| Rollback défaillant / commit issue inconnue | RuntimeFailure d'infrastructure ; pas de classifier de métier ni de nouvelle finalisation dans ce tour. Relire durablement S/C/publications sur connexion fraîche avant toute reprise ; si DB inaccessible, rester indéterminé |
| afterCommit d'observation échoue | Commit reste acquis, ne jamais lancer HandleFailure ou rejouer le métier. Observer/loguer séparément ; aucune notification requise confiée à ce callback |

Policies inchangées : Command transitoire 1 s/5 s/30 s puis Fail à tentative 4, inconnue terminale ; Registration/Results invariants terminaux et autres 5 s ; Binding policy existante terminal/autres 5 s ; LKV policy bornée actuelle ; Projection Temporary ne devient jamais terminal par épuisement ; Event pas de retry planifié ajouté. Les tentatives continuent d'être allouées sous lock Acquire ; takeover conserve invalidation TAKEN_OVER. La failure d’une tentative dont le retry ou la finalisation d’échec committe se conserve dans son C clos, jamais sur un autre claim. Ne pas recopier dans la transaction failure la provenance de l'Execute rollbacké.

### 9.1 Contrat concret de résolution à la frontière Spring

La garde locale du §4 doit aussi conserver, dans des variables privées à l’appel, si l’action est revenue normalement et l’issue de son exécution owner via une TransactionSynchronization enregistrée **avant** l’action : afterCompletion COMMITTED/ROLLED_BACK/UNKNOWN, initialement inconnue. Pas de succès déduit du seul CAS ni de l'absence d'exception du callback. Si le runner retourne normalement sans commit confirmé, erreur d'infrastructure ; si erreur avec rollback confirmé, propager le signal original s’il vient de l’action ; si erreur de commit après retour normal de l’action, l’identifier comme résolution et conserver le rollback connu. Si erreur sans rollback confirmé ou après COMMITTED, ne pas la présenter comme échec métier rollbacké.

Pour ces issues exceptionnelles de résolution, le signal technique interne retenu pour WP2 est `ConsumptionTransactionResolutionException` (RuntimeException engine-consumption, issue ROLLED_BACK, COMMITTED ou UNKNOWN, phase COMMIT/ROLLBACK et cause conservée), reconnu **avant classifier** par Sequential et par la façade recovery. Avec ROLLED_BACK confirmé sur échec de commit, Sequential peut appliquer le classifier à la cause dans une nouvelle frontière, après contrôle frais d’ownership ; cette branche ne passe pas par la normalisation des erreurs **pré-CAS**. COMMITTED/UNKNOWN interdisent tout HandleFailure automatique. AcquireThenFinalize rend les erreurs de résolution RuntimeFailure. UNKNOWN inclut rollback non confirmé ; le message conserve phase commit/rollback et toute cause/suppressed. Ce type n'est ni LostClaimException ni ProcessingFailure ; aucune API publique de use case ne change. Pas d'extension du port TransactionRunner global ni de framework de statuts transactionnels.

Les callbacks de télémétrie LKV doivent capturer leurs propres erreurs d'observation, journaliser au mieux sans relancer d'exception dans la résolution ; ne pas entourer l'opération métier `advanceToAtLeast` d'un catch qui absorberait son erreur SQL. Le signal de résolution reste la protection de dernier recours si une autre observation postcommit lève. Les callbacks afterCompletion d'observation ne font pas de DML métier et ne masquent pas l'issue physique.

Réconciliation après commit incertain : lecture indépendante S et C exacts et publications requises. DONE avec clôture C attendue et contenu cohérent constate le commit ; remplacement de token constate un état courant différent, **sans prouver à lui seul** que la transaction incertaine n'a jamais committé. Aucun rejet/FAILED ajouté sur cette seule lecture ; conserver diagnostic et laisser discovery normale reprendre seulement l'état éligible. Ni exactement une livraison réseau ni restauration d'une session perdue ne sont promises.

## 10. Contrats Java publics arrêtés pour WP2

Les signatures suivantes décrivent les changements futurs ; aucune API n'est ajoutée dans WP1. Types de provenance = ceux de domain-consumption ; ClaimId et ProcessingFailure existants. `ConsumptionFinalization` reste dans engine-consumption.

```java
// ConsumptionFinalization : conserver Success et TerminalFailure,
// ajouter Rejected(String rejectionCode), non null/non blanc.
record FinalizeConsumptionInput(
    UUID slotId, ClaimId claimId, ConsumptionFinalization finalization,
    FencedDurableEffect durableEffect,
    List<ConsumptionInput> inputs, List<ConsumptionResult> results) { }

// ConsumptionLifecyclePersistencePort, participants MANDATORY :
void terminalize(UUID slotId, ClaimId claimId,
    ConsumptionFinalization finalization, Instant now);
void scheduleRetry(UUID slotId, ClaimId claimId,
    ProcessingFailure failure, Instant nextClaimAt, Instant now);
```

Choix précis : listes non nulles copiées immuables, vides par défaut via constructeur à quatre arguments conservé ; pas d'Optional<List>, pas de nouvel objet de phases. Valider finalization et toutes les slotIds avant append provenance et avant invocation d'un durableEffect lorsque les listes sont déjà fournies. Execute obtient ses listes du callback puis les valide avant délégation ; tout échec de validation rollbacke aussi les effets déjà exécutés. Success = pas de raison/failure ; Rejected = raison code métier non blanc, pas de failure ; TerminalFailure = ProcessingFailure non nul, raison failure.code. Aucun ABANDONED nouveau. Même horodatage now pour transition et clôture.

Le port terminalize applique CAS puis end/fail, contrôle les cardinalités et lève perte/anomalie. Il ne committe pas : `void` indique succès provisoire seulement au participant. scheduleRetry applique son SQL distinct puis fail avec mêmes exigences ; le moteur calcule nextClaimAt de la décision et de now une fois, validation temporelle conservée. Pas de booléen ignoré possible sur les nouvelles opérations.

FinalizeConsumptionService reçoit lifecycle, provenance et clock ; propriétaire unique du suffixe : durableEffect une fois, append des listes une fois, appel terminalize (barrière ORM et SQL dans adapter). Execute reçoit le finalizer **participant**, garde son callback et son résultat public, adapte Success/Rejected puis passe un effet vide avec les listes déjà obtenues. HandleFailure reçoit ce même finalizer participant et conserve query/policy/clock et transition retry. Pas d'instanciation d'un second mécanisme terminal ; aucune famille ne choisit le SQL de slot.

FencedMutationResult APPLIED peut rester une valeur interne privée renvoyée au wrapper, avec sens « opérations demandées exécutées provisoirement ». Sa **sémantique publique** est exclusivement « commit confirmé ». Les méthodes des use cases et résultats des supras/orchestrateurs ne changent pas. Les appels de participants ne sont jamais publiés directement au runtime.

## 11. Impacts précis par classe et module

Liens de sources ci-dessous et [index complet de l'audit §K](../architecture/Consumption_Generic_Transactional_Protocol_Audit_2026-10-16.md#k-sources-précises-et-traçabilité). Les noms de classes sont des repères exacts sous app ; ce tableau décrit WP2, pas des modifications réalisées WP1.

| Module / classes | Nature et adaptation minimale future |
|---|---|
| engine-consumption : [ConsumptionFinalization](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/in/consumption/contract/ConsumptionFinalization.java), FinalizeConsumptionInput | Signature additive Rejected + listes/constructeur compatible ; validation de métadonnées, pas de nouveau modèle par famille |
| engine-consumption : [FinalizeConsumptionService](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java), ExecuteConsumptionService, HandleConsumptionFailureService | Constructeurs d'injection changés ; suffixe unique dans Finalize ; effet déjà exécuté par Execute non rejoué ; Fail délègue, retry séparé ; signatures use case conservées |
| engine-consumption : [ConsumptionLifecyclePersistencePort](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/out/consumption/ConsumptionLifecyclePersistencePort.java), ConsumptionQueryPort | Nouvelles signatures void §10 ; query existante findSlot(UUID) suffit pour relecture, aucune extension nécessaire |
| engine-consumption : FencedDurableEffect, ConsumptionExecution, quatre wrappers Transactional*UseCase | Sémantique callbacks provisoires et owner-only ; perte propagée interne/traduite externe. Constructors existants restent possibles avec runner gardé. Signaux techniques ConsumptionClaimClosureException et ConsumptionTransactionResolutionException à ajouter, sans changer résultats publics |
| infra-persistence-primary-jpa : [JpaConsumptionLifecycleAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java) | Implémenter terminalize et scheduleRetry, validations communes/exactly-one ; MANDATORY, exception CAS zéro ; aucun commit autonome |
| Même module : [JpaConsumptionSlotRepository](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java), JpaConsumptionClaimRepository, JpaConsumptionProvenanceAdapter | SQL conditionnel, flush/clear, end/fail et saveAll conservés. findByIdForUpdate futur retrait WP5 ; aucune migration |
| infra-tx-spring : [SpringTransactionRunner](../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java), port-transaction : TransactionRunner | Aucune signature ni sémantique globale à changer ; garde/résolution locale Consumption en composition, runner actuel réutilisé |
| composition-runtime-spring : CommandConsumptionRuntimeConfiguration, EventConsumptionRuntimeConfiguration, CanonicalProjectionTaskRuntimeConfiguration, RegistrationRuntimeConfiguration, BindingRuntimeConfiguration, CommandResultRuntimeConfiguration, RegistrationResultRuntimeConfiguration, LatestKnownVersionRuntimeConfiguration | Assembler participant unique et owners gardés, qualifiers explicites ; same manager/datasource ; décorateur Spring local §4/§9 ; aucun flag famille, aucune nouvelle dépendance attendue |
| orchestrator-consumption : [SequentialConsumptionOrchestrator](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java), AcquireThenFinalizeConsumptionOrchestrator | Préserver budgets/scan ; reconnaître résolution avant classifier ; normalisation SQL externe avec relecture fraîche. Interfaces publiques conservées ; garde run fournie en composition |
| engine-consume-command : [BindingFenceRecoveryExecuteUseCase](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/consumption/BindingFenceRecoveryExecuteUseCase.java) | Constructor distingue Execute owner et participant, retire lifecycle.lockCurrentClaim ; conserve E/B, plus de prélock S, reconnaissance issue transactionnelle |
| engine-consume-command : CommandConsumptionExecution, ExecuteRecordedCommandService ; infra-primary : JpaPotCommandEventAppendAdapter, JdbcCommandOutcomeAdapter, JpaPotGlobalVersionRepository, JpaExternalIdentityBindingAdapter, ExternalIdentityBindingStreamJdbcRepository | Lecture/audit, préfixes et SQL conservés. Aucun déplacement Pot/Events/Binding, aucune idempotence Command ajoutée ; contrats seulement sauf correction rendue nécessaire par preuve WP2 |
| supra-consume-event : ProjectionMaterializationConsumptionService ; engine-produce-projection-task : ProduceProjectionTaskService ; infra-primary : JdbcProjectionTaskStoreAdapter | Contrat stale ; ensure SQL provisoire same tx ; résultats conservés ; normalisation externe éventuelle à l'adaptateur de finalisation, jamais dans callback SQL |
| engine-consume-projection-task : ProjectionTaskConsumptionService, ProjectionEngineService ; infra-persistence-projection-jdbc : JdbcProjectionStoreAdapter, ProjectionStoreAutoConfiguration | Préparation pure, root/artifacts/failure et REQUIRED conservés ; normalisation externe au service après owner ; pas d'autre protocole ni comparaison root nouvelle |
| engine-consume-registration : ExecuteRegistrationService ; supra-consume-registration : RegistrationConsumptionLocator ; infra-primary : stores Registration et User | SQL et initializer transactionnels conformes ; adoption du suffixe par wiring, aucun nouvel outcome Failed |
| supra-consume-binding : BindingFactConsumptionLocator ; engine-materialize-current-binding : MaterializeCurrentBindingService ; infra-persistence-read-jdbc : JdbcCurrentBindingAdapter | Contrats/assemblage seulement ; upsert et comparaison conservés |
| supra-consume-command-result / registration-result : CommandResultConsumptionLocator, RegistrationResultConsumptionLocator ; engine-materialize-command-result / registration-result : MaterializeCommandResultService, MaterializeRegistrationResultService ; infra-primary : JdbcCommandResultStore, JdbcRegistrationResultStore | Pas de signature métier à changer ; immutabilité/comparaison, provenance et owner historique conservés |
| supra-consume-lkv : LatestKnownVersionConsumptionLocator ; engine-materialize-latest-known-version : AdvanceLatestKnownVersionService ; infra-read : JdbcLatestKnownVersionAdapter ; runtime-latest-known-version-consumption-worker : MeteredAdvanceLatestKnownVersionUseCase | Upsert/provenance conservés ; afterCommit métriques isolé, pas de succès métrique après rollback ; errors de tentative conservés |
| Huit runtimes workers | Comportement externe/policies inchangés, tests de composition/ownership réels requis. Pas de changement HTTP, admission ou supra de lecture |

Anciennes méthodes `tryTerminalize`, `handleFailure` du port peuvent rester jusqu'à WP5 **uniquement comme délégations** vers les opérations communes, sans logique parallèle ni nouveau caller. `lockCurrentClaim` et `findByIdForUpdate` n'ont plus de caller terminal actif dès WP2 ; retrait de surface WP5 après inventaire production et doubles. Acquire conserve `findByKeyForUpdate`. Aucun test global déplacé ou supprimé pour éviter un gate. Toute dépendance nouvelle ou frontière modifiée déclenche révision préalable des slices selon politique.

## 12. Matrice obligatoire de preuves WP2

Les IDs T01–T11 restent ceux du plan ; ce tableau rend les décisions expérimentables. Tous les scénarios ci-dessous doivent avoir leur preuve minimale au WP2 ; WP3/WP4 approfondissent les variantes et familles prévues par le plan, sans reporter l'atomicité du suffixe. PostgreSQL réel, FK/uniques/triggers actifs, isolation/PID/XID capturés, barrières et horloge contrôlée ; observateur indépendant avant/après commit. Aucun sleep pour créer une race ni retry de test pour cacher un échec. Les compteurs Java ne remplacent pas les états durables.

| Tests / décisions | Composants et scénario concurrent | Assertions durables et ownership | Barrière/SQLSTATE |
|---|---|---|---|
| T01 — D02/D05/D06/D08/D10 | CommandConsumptionPostgresTest : D17 TARGET failure et recovery, vrai takeover C2 commit, C1 O/T provisoires, C2 attend O ; inputs, results, les deux, témoins sans provenance | C1 CAS zéro/rollback complet, C2 seul FAILED (recovery REJECTED), O/T exacts, S DONE sans C, C2 clos bon reason/failure, C1 TAKEN_OVER ; FK actives | Observer C2 attend INSERT O sans S FOR UPDATE ; flush FK C1 avant CAS ; aucun 40P01 sur ces entrelacements. 55P03 NOWAIT est diagnostic éventuel, pas perte logique |
| T02 — D01/D02/D06/D10 | JpaConsumptionLifecycleAdapterPostgresTest / TransactionalConsumptionExecutionPostgresTest : takeover avant/après effet, avant/après CAS ; takeover commit et rollback ; lease expirée courante | Un seul token terminal gagnant ; après CAS gagnant takeover attend résolution puis AlreadyDone ; rollback takeover laisse C1 autoritaire ; aucun write perdant. Ne pas forcer takeover à committer derrière une FK qui le bloque | S→C Acquire ; SQL effets/flush→CAS→C ; zéro CAS sans SQLSTATE ≠ 40001/40P01 |
| T03 — D02/D03/D07 | Deux finalisations même S/C et C1/C2, ordres de publication inversés ; Command succès/rejet/FAILED, Projection success/failure | Exactement un CAS gagnant ; perdant intégralement annulé ou erreur pré-CAS conservée/normalisée ; claim clos une fois, contenu gagnant exact ; données préexistantes inchangées | Unicité peut sérialiser avant S ; distinguer 23505 et perte CAS ; pas d'exception transformée en faux succès |
| T04 — D01/D02/D03/D05/D09 | Transactionnels/lifecycle : CAS perdu après vrais SQL ; vrai CAS un puis end/fail zéro ; terminal/retry ; contrainte différée refusant commit ; erreur rollback injectée à frontière | Aucun APPLIED avant commit ; rollback effets/O/T/I/R/S/C ; retry refusé ne laisse pas nextClaimAt nouveau. Rollback défaillant/issue inconnue → RuntimeFailure, pas LOST confirmé | Flush fautif avant CAS empêche UPDATE ; après CAS aucun DML effet. 23503/23514/23505 selon fixture réelle, anomalie cardinalité sans SQLSTATE, cause de résolution conservée |
| T05 — D01/D07/D09 | Command stale 23505 avant CAS contre publication gagnante ; variante même ownership/contenu divergent Results ; relecture fraîche après rollback | Stale établi → abandon, aucun effet/provenance ; encore courant → classification erreur initiale ; aucun DO NOTHING Command ni divergence cachée ; lecture indisponible reste RuntimeFailure | SQLSTATE 23505, 40P01, 40001 et classes 08 distingués ; pas de query dans tx avortée/25P02 ; aucun retry automatique de perte |
| T06 — D02/D04/D05/D07 | ProjectionMaterializationConsumptionPostgresTest : stale ensure réel ; deux slots même Task, holder commit/rollback. CanonicalProjectionTaskRuntimePostgresTest : stale publish root/artifacts et stale failure, ALREADY_EXISTS | Invocation provisoire permise et forcée ; zéro nouveau durable perdant après rollback, gagnant/préexistant intact ; S/C cohérents ; root first wins, failure_id compare | Task/root uniques avant S ; artifacts FK root ; tous SQL avant CAS ; conflit explicite failure_id ≠ LOST présumé |
| T07 — D05/D06 | Lifecycle/transactionnels : deux FK KEY SHARE S puis CAS ; takeover FOR UPDATE en troisième backend, vraie file et observation pg_locks/blocking_pids | Progrès des entrelacements, invalidation/end cohérents et aucun effet perdant ; pas de prétention d'absence universelle de cycles | Confirmer modes et clés, NO KEY UPDATE attendu, aucune écriture métier après CAS. 40P01/timeout font échouer le scénario de non-cycle attendu |
| T08 — D06/D08 | Command : detach pendant métier puis après fence ; même E/Pots distincts, même Pot/E distinctes ; recovery négative après rollback, B exact encore présent/absent | Pot/Events/outcome/provenance annulés si fence perdu ; aucun rejet public si B exact présent ; réattachement même User avec nouveau B reste distinct ; aucun Pot rejoué par recovery | CAS Pot avant DML ; Events flush avant fence E ; O puis flush I/R avant S. BindingFenceLost/Conflict ≠ LostClaim ≠ SQLSTATE |
| T09 — D02/D04/D09 | Runtimes Registration/Binding/CR/RR/LKV : perte après leurs SQL ; retry planifié après erreur transitoire et failure vide | User/facts/Binding/outcome, READ upsert, Results/provenance perdants annulés selon famille ; calendrier/budget/attempt/failure exacts ; **aucun terminal lors retry**, S PENDING sans C ; takeover inchangé | Même XID SQL→flush→S→C ; politiques classe erreur fonctionnelle/technique conservées ; ne pas inventer de FAILED Event |
| T10 — D01/D03/D05/D08/D10 | TransactionalConsumptionUseCaseTest + SpringTransactionRunnerTest + huit configurations réelles : owner sous ambient ; participant REQUIRED ; Execute échoué puis F/R ; stores JPA/JDBC/projection | Owner refuse avant callback ; participant rejoint XID owner et perte rollbacke tout ; XID E différent F/R ; APPLIED observable seulement après commit. Garde recovery→Execute participant, pas wrapper owner | Même manager/datasource/connexion ; s'assurer afterCompletion COMMITTED/ROLLED_BACK ; UnexpectedRollbackException ne devient pas LOST à l'intérieur d'une transaction |
| T11 — D04/D09 | Projection Temporary à haute tentative/Terminal ; erreur ensure Event ; LKV métrique afterCommit qui lève ; erreur de commit avec issue inconnue | Temporary sans root/failure terminal ; Event reste claim ouvert après erreur rollbackée ; LKV succès zéro après rollback, commit acquis ne lance aucun HandleFailure ; UNKNOWN ne republie rien | Préparation hors transaction finale ; après commit uniquement observation protégée ; pas de DML après CAS. 40P01/40001/08xxx/timeout restent diagnostics distincts |

T04 doit au minimum inclure une erreur de commit **réelle** (contrainte différée en fixture isolée) et un refus de clôture après vrai CAS SQL ; un mock CAS=1 ne suffit pas. L'erreur réseau/rollback non confirmé peut être injectée au manager pour tester la discipline de résultat sans prétendre reproduire une panne distribuée réelle. T10 sous REQUIRED doit tester aussi le participant perdant sous un owner qui laisse l'exception traverser jusqu'à la fin physique ; uniquement tester le refus de l'entrée publique ne suffit pas.

Pour chaque test : assertion de visibilité nulle avant commit, comparaison durable après résolution de toutes tables concernées, token et historique exacts ; capturer cardinalités et SQLSTATE de toute cause. Les méthodes des témoins WP0 et substitutions §6.2 restent les points de départ. Les nouvelles preuves sont locales aux modules/runtime concernés, pas dans architecture-tests par commodité. Un test de normalisation d'erreur isolé n'est pas une preuve de la concurrence PostgreSQL correspondante.

### 12.1 Slices et commandes futures, non exécutées WP1

Depuis `app/`, primaire COMMAND pour suffixe partagé ; secondaires EVENT/PROJECTION/BINDING/LKV et compléments explicites Registration/CommandResult/RegistrationResult. Les huit assemblages changent ensemble au WP2. Commandes canoniques unitaires :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl runtime-event-consumption-worker -am test
./mvnw -pl runtime-task-consumption-worker -am test
./mvnw -pl runtime-binding-consumption-worker -am test
./mvnw -pl runtime-latest-known-version-consumption-worker -am test
./mvnw -pl runtime-registration-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker -am test
```

Clôture locale WP2 : composition explicite ALL8 du plan, sans WEB/architecture implicites :

```sh
./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-latest-known-version-consumption-worker,runtime-registration-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker -am test
```

Feedback D17/rollback : commande ciblée du plan §7.2, puis ALL8 à clôture. Aucun -amd/install/SNAPSHOT. DB CURRENT avec contraintes ; politique au HEAD : baseline candidate V23, Trusted baseline NONE ; conserver bootstrap des tests si toujours aucun baseline certifié, pas de campagne historique autonome. Gate global/full non requis pour la conception retenue dans les frontières existantes ; toute dépendance/frontière/isolation runtime modifiée exige déclaration et gate selon politique avant continuation. Intégration main reste son gate propre, non autorisée ici.

## 13. Risques résiduels et points d'arrêt

- TARGET non exécuté : D17 avec results/recovery, files de takeover, erreurs tardives et huit callbacks restent à prouver. Ceci borne le PASS documentaire, sans reporter une règle de concurrence à inventer au WP2.
- Garde et distinction d'injection doivent être testées sur tous les assemblages ; oublier Execute/Acquire ou brancher un owner dans recovery permettrait une ambiguïté. Ce serait un échec WP2, pas une exception tolérée.
- Flush complet garanti par le repository commun sous contexte unique ; trace SQL requise pour détecter listener/second contexte/flush tardif. En présence d'un tel cas, corriger avant validation, sans affaiblir FK ou contrat.
- Transaction réseau incertaine : aucune promesse de résolution bornée ; RuntimeFailure et réconciliation, pas de republication aveugle. Les métriques d'observation ne portent aucun effet métier requis.
- Déploiement mixte ancien/nouveau non garanti sans D17 ; arrêter/drainer/reprendre les workers concernés sur artefact cohérent lors du futur déploiement. Aucun déploiement autorisé WP1.
- Callbacks futurs multiclés/autres managers/externe doivent être refusés tant qu'ils ne satisfont pas ce contrat ; aucune preuve sur code futur arbitraire, DDL/admin concurrent, fairness, délai maximal ou gain de performance.

Aucune décision bloquante ouverte ; aucune décision non bloquante laissée à choisir. Les inconnues ci-dessus sont des preuves d'implémentation à obtenir. Si WP2 démontre un contre-exemple à ces contrats, interrompre sa bascule et réouvrir la décision correspondante avec preuve ; ne pas livrer une exemption permanente de famille.

## 14. Verdict et journal de livraison

**PASS WP1 — contrat cohérent, testable, D01–D10 CLOSED ; READY FOR WP2 sur le plan contractuel uniquement.** Responsabilité physique et participants distingués ; aucune capture autorisée ne permet de committer des effets stale. Pas de changement runtime/test/schéma, pas de statut de dette modifié ; WP2 reste non commencé.

Commandes réellement utilisées : inspections Git (`status`, `branch`, `rev-parse`, `rev-list`, `log`, `diff --stat`, `ls-remote`), recherches `rg`/`rg --files`, lectures `cat`/`sed`/`wc`, scripts Python standard pour empreintes et validations documentaires ; consultation officielle Spring REQUIRED et Spring Data JPA (aucune preuve d'exécution locale déduite de ces pages). Livraison : contrôle des liens/fences, D01–D10 et T01–T11, `git diff --check` ciblé, revue du diff et staging des seuls deux Markdown WP1 ; commit et push normal après relecture du SHA serveur. Commit final et divergence après push sont rendus dans la restitution pour éviter une autoréférence impossible dans le commit contenant ce rapport.

Tests réellement exécutés WP1 : **NONE**, Maven/PostgreSQL inclus. Slices exécutées NONE ; production attendue/observée NONE ; franchissement inattendu NONE ; gate global NOT REQUIRED/non exécuté ; full reactor NOT REQUIRED/non exécuté (documentaire) ; database verification NONE ; aucune migration/install/SNAPSHOT, branche changée, merge ou force push. Les preuves WP0 restent historiques. Les fichiers Keycloak/Bruno sont exclus et leur contenu est contrôlé par empreintes avant/après.
