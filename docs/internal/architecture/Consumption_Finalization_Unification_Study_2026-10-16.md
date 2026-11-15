# Étude — Unification de la finalisation Consumption par CAS final

Date : 2026-10-16. Branche conservée : `feat/runtime-monolith`. HEAD étudié : `306963fce402e7d5176412fdb16deccec8436ff8`.

## 1. Executive summary

**Verdict : CONDITIONAL GO.** Une règle commune « effets SQL provisoires → CAS Consumption → clôture du claim → commit commun, sinon rollback intégral » est cohérente avec les invariants de commit actuels. Le chemin Execute l'applique déjà pour SUCCESS et REJECTED. L'étendre aux échecs terminaux et à la recovery Binding est une correction plausible de D17, sans supprimer les FK. **La variante étendue n'a pas été exécutée sur PostgreSQL : son absence de deadlock n'est pas PROVEN.**

Le plus petit changement cohérent consiste à conserver le succès Command et son fence Binding à leur position actuelle, puis à aligner Finalize et la recovery sur le CAS final existant. Il ne consiste ni à avancer le CAS du succès, ni à déplacer le fence Binding, ni à refaire Acquire/Takeover. Le CAS reste une validation de l'autorité Consumption ; il ne remplace pas l'autorité Binding.

Trois réserves empêchent un GO immédiat :

1. Après des effets provisoires, un retour normal `LOST_CLAIM` ne suffit plus : il faut rollbacker **avant** de rendre ce résultat. Aujourd'hui Finalize peut retourner normalement parce qu'il vérifie avant l'effet.
2. L'équivalence concerne les états committés admissibles, pas l'invocation des callbacks ni les mêmes erreurs observables. Un callback stale pourra écrire provisoirement, attendre, voire échouer sur une unicité **avant** son CAS. Certains tests actuels imposent zéro invocation ; ils ne peuvent être déclarés inchangés.
3. La suppression statique du cycle D17 doit être confirmée par des courses PostgreSQL avec les vrais wrappers, le flush de provenance, les résultats, la recovery et les contrôles de clôture. Aucun benchmark ne permet d'affirmer un gain global de contention.

### Niveaux de conclusion

- **PROVEN** : phénomène exécuté sur PostgreSQL, dans un périmètre et un entrelacement identifiés. Dans ce rapport, seulement des preuves historiques explicitement attribuées ; aucun nouveau test exécuté.
- **SUPPORTED BY CODE** : propriété ou déduction étayée par les sources courantes, sans campagne dynamique de cette étude.
- **HYPOTHESIS** : propriété attendue d'un protocole modifié, non exécuté.
- **REFUTED** : affirmation contredite par le code, un contre-exemple ou une preuve existante ; portée précisée.
- **INCONCLUSIVE** : données insuffisantes pour décider.

Sources de départ : [audit D17 du 16 octobre][audit17], [audit antérieur des autorités][auditlocks], [canon transactionnel Consumption][canon], [canon ProjectionTask][projectioncanon] et [courses takeover du 15 octobre][takeover]. Les succès de campagnes antérieures ne sont pas de nouvelles validations de ce rapport.

## 2. Invariants CURRENT

| Invariant | Mécanisme courant et portée |
|---|---|
| Une consommation logique, un slot | UNIQUE sur consumable/consumer et leurs composants ; création idempotente, acquisition sérialisée. |
| Autorité de finalisation | `slot_id=S AND status='PENDING' AND current_claim_id=C`. `ClaimId` est le token ; ni la lease ni `revision` ne le remplacent. |
| Lease | Autorise une tentative de takeover après expiration. Un claim expiré mais encore courant peut terminer. Ajouter `lease_until > now` au CAS changerait cette sémantique. |
| Ownership cohérent | FK claim→slot et FK `(slot_id,current_claim_id)`→claim du même slot. Takeover invalide l'ancien claim et installe un nouveau token dans une seule transaction. |
| Fin cohérente | DONE implique outcome, date et absence de claim courant. SUCCESS sans raison ; REJECTED/FAILED avec raison. V6/V7 complètent V4. |
| Historique cohérent | `claims.end` ou `claims.fail` doit affecter exactement une ligne du même slot, ouverte et non invalidée. L'échec lève une exception. Le seul UPDATE slot ne valide donc pas toute la transaction. |
| Effets atomiques | Métier SQL, événements/outbox, outcome éventuel, provenance éventuelle, slot et claim participent à la même transaction locale. Un effet réseau ou une seconde ressource autonome n'est pas couvert. |
| Deux autorités Command | Binding valide `(E,U,B,R)` ; Consumption valide `(S,C)`. Deux validations successives dans le même commit suffisent ; aucune instruction SQL composite n'est requise. |
| Publication spécialisée | Pas d'outcome ou d'événement terminal universel. FAILED technique d'une Registration ou de LKV ne fabrique pas un résultat métier Command. |

Ces mécanismes sont **SUPPORTED BY CODE** : [lifecycle][lifecycle], [SQL slot][slots], [SQL claims][claims], [schéma V4][v4], [V6][v6], [V7][v7]. La lease expirée sans takeover et l'arbitrage de takeover disposent aussi de preuves PostgreSQL antérieures [takeover][takeover]. Aucun état PROCESSING intermédiaire n'existe sur le slot : PENDING avec ou sans claim, puis DONE. `next_claim_at` gouverne l'acquisition, pas la validation terminale du détenteur courant.

### Port et implémentations réelles

[`ConsumptionLifecyclePersistencePort`][port] expose `acquire`, `lockCurrentClaim`, `tryTerminalize`, `handleFailure`. Le port n'impose pas à lui seul la bonne composition transactionnelle.

- `lockCurrentClaim` : SELECT slot par ID **FOR UPDATE**, puis vérifie PENDING et le token en Java. Il verrouille aussi un slot trouvé dont le token est déjà stale, jusqu'au retour transactionnel.
- `tryTerminalize` : accepte seulement SUCCESS/REJECTED ; UPDATE conditionnel slot puis `claims.end`, avec cardinalité exacte. Zéro sur le slot retourne `false`, sans rollback automatique.
- `handleFailure` : RetryAfter fait `scheduleRetry`, Fail fait `terminalize(FAILED)`, puis `claims.fail`. Zéro retourne `LOST_CLAIM`, sans rollback automatique.
- [`ExecuteConsumptionService`][execute] : callback spécialisé, validation des slot IDs de provenance, append inputs/results, `tryTerminalize` ; zéro lève `LostClaimException`.
- [`FinalizeConsumptionService`][finalize] : lock courant, durable effect, puis terminalisation SUCCESS ou failure Fail. Zéro initial retourne LOST_CLAIM ; refus après lock lève `IllegalStateException`.
- [`HandleConsumptionFailureService`][failure] : lit le claim, vérifie son slot, applique la policy ; Fail appelle Finalize, RetryAfter appelle directement le CAS de retry.
- [`BindingFenceRecoveryExecuteUseCase`][recovery] : intercepte la perte de fence **après** sortie de l'Execute transactionnel ; ouvre la recovery, lock le slot, lit le Binding exact puis réutilise Execute pour publier un rejet ou lève un conflit technique.

### Frontières Spring

Les quatre wrappers `TransactionalAcquire/Execute/Finalize/HandleConsumptionFailureUseCase` du répertoire [transaction/consumption][wrappers] exécutent le delegate dans `TransactionRunner`. [`SpringTransactionRunner`][runner] délègue à `TransactionTemplate.execute`, sans absorption d'exception. Les configurations [Command][commandwiring], [Event][eventwiring], [ProjectionTask][taskwiring] utilisent `new TransactionTemplate(manager)` : REQUIRED, isolation DEFAULT. Les autres configurations runtime observées suivent la même forme.

Les mutations lifecycle/provenance, outcome Command et Binding imposent MANDATORY. Les stores JDBC de résultats/Registration/tasks participent à la connexion transactionnelle de l'appelant mais n'ont pas tous cette annotation. Le store de projection possède un template interne REQUIRED construit avec la datasource et le manager injectés ([configuration][projectionwiring]) ; il rejoint la finalisation dans cette composition, il ne committe pas séparément. Un schéma SQL READ/projection distinct n'implique pas une seconde base.

Acquisition et Execute sont des appels transactionnels séparés. L'orchestrateur [Sequential][sequential] ne possède pas de transaction ; il appelle failure après la sortie/rollback d'Execute et ignore `LostClaimException`. Dans la recovery, l'appel imbriqué à Execute rejoint la transaction de recovery, il ne crée pas une troisième transaction indépendante. REQUIRED ne garantit pas une transaction neuve sous un appelant qui envelopperait tout le worker : une telle composition ne doit pas être introduite. L'audit historique a constaté READ COMMITTED ; ce rapport n'affirme pas l'isolation d'un déploiement externe.

## 3. Séquences transactionnelles CURRENT

Notation : E = identité externe ; Q = Command ; S = slot ; C1 ancien claim, C2 repreneur. Chaque BEGIN/COMMIT ci-dessous est une frontière physique dans le wiring inspecté, sans transaction ambiante autour de l'orchestrateur.

### 3.1 Acquisition commune et takeover

```text
BEGIN acquisition
  INSERT slot ON CONFLICT(clé logique) DO NOTHING
  SELECT slot par clé FOR UPDATE
  si DONE -> AlreadyDone ; si trop tôt -> NotReady ; si lease active -> Busy
  si ancien claim expiré : UPDATE claim C1 invalidated_at/end_reason=TAKEN_OVER
  INSERT claim C2 (saveAndFlush, FK S, UNIQUE S/attempt)
  UPDATE slot SET current_claim_id=C2, last_attempt_number=..., revision=revision+1
COMMIT acquisition
```

L'INSERT idempotent peut déjà attendre avant le SELECT. Aucun outcome n'est publié dans Acquire. Le token C2 retourné avant un éventuel rollback de son acquisition n'a ensuite aucune autorité. `installClaim` est protégé par le lock préalable, pas par une comparaison explicite à C1 dans son WHERE.

### 3.2 Succès Command et rejet métier

```text
BEGIN Execute
  SELECT RecordedCommand ; SELECT Binding exact + révision sans lock
  si Binding absent : résultat REJECTED(CALLER_IDENTITY_NOT_CURRENT), sans métier/fence positif
  sinon : préparation autorisation, contrôle validUntil, decode/dispatch
    succès : mutations Pot et éventuels Events métier/outbox
    rejet métier ou autorisation expirée : résultat de rejet
    UPDATE stream SET R=R WHERE E,R AND EXISTS(actif E,U,B) -- fence Binding
  INSERT command_outcomes(Q, APPLIED ou REJECTED, ...)
  INSERT command_terminal_events(..., Q) -- FK outcome ; UNIQUE Q
  saveAll inputs ; saveAll results
  FLUSH JPA au plus tard avant le CAS natif
  UPDATE slot SET DONE, current_claim_id=NULL, ... WHERE S,PENDING,C
  si zéro : LostClaimException -> ROLLBACK de toute la transaction
  UPDATE claim SET ended_at, end_reason=SUCCESS ou REJECTED WHERE C,S,ouvert,non invalidé
  exiger une ligne
COMMIT Execute
```

L'ordre vient d'[`ExecuteRecordedCommandService`][recordedexecute], [`CommandConsumptionExecution`][commandexecution], [outcome JDBC][outcomes], [Execute générique][execute]. Les Events métier sont distincts du terminal event Command. Un rejet métier est une résolution valide : il n'est pas classifié comme ProcessingFailure. Les inputs peuvent être présents même pour un rejet.

### 3.3 Échec technique retryable

```text
BEGIN Execute -> exception -> ROLLBACK (métier/outcome/événements/provenance éventuels)
BEGIN HandleFailure
  SELECT claim ; policy(attemptNumber, failure, now) -> RetryAfter
  UPDATE slot SET current_claim_id=NULL, next_claim_at=..., revision=revision+1
    WHERE S,PENDING,C
  si zéro : LOST_CLAIM sans effet nouveau
  sinon UPDATE claim SET ended_at, PROCESSING_FAILURE, détails ; exiger une ligne
COMMIT HandleFailure
```

**Aucune publication terminale et pas de DONE lorsque la policy choisit RetryAfter.** Command classe notamment 40P01 et les erreurs SQL transitoires en catégorie TRANSIENT ; la policy générique propose 1 s, 5 s, 30 s aux tentatives 1, 2, 3, puis Fail dès la tentative 4. Même une erreur transitoire peut donc conduire au chemin terminal après épuisement. Une exception inconnue est terminale immédiatement. Les policies des autres familles restent spécialisées.

### 3.4 Échec technique terminal

```text
Execute exception -> ROLLBACK entièrement terminé
BEGIN HandleFailure
  SELECT claim ; policy -> Fail
  Finalize : SELECT slot FOR UPDATE ; vérifier PENDING/C
  si stale : LOST_CLAIM, aucun callback
  durableEffect Command : INSERT outcome FAILED(public code) ; INSERT terminal event COMMAND_FAILED
    autres familles : effet propre ou callback vide
  UPDATE slot DONE/FAILED, raison=failure.code WHERE S,PENDING,C
  UPDATE claim PROCESSING_FAILURE + détails ; exiger une ligne
COMMIT HandleFailure
```

Le code public Command et le code précis du ProcessingFailure ne sont pas interchangeables. Le premier est dans l'outcome public, le second dans la raison du slot et les détails du claim. La provenance de l'Execute échoué est rollbackée et n'est pas recopiée automatiquement dans la transaction d'échec.

### 3.5 Perte de claim

Sur Execute, le CAS à zéro lève `LostClaimException`, annulant tous les effets ; l'orchestrateur ne la classifie pas. Sur Finalize/recovery, le lock initial découvre le token perdu avant effet. Sur retry, zéro retourne LOST_CLAIM sans writes préparatoires à annuler. Un refus de clôture après un CAS gagnant est une erreur d'intégrité/composition à rollbacker, pas une simple perte normale à ignorer.

### 3.6 Perte du fence Binding

```text
Execute : métier éventuel -> fence Binding=0 -> BindingFenceLostException -> ROLLBACK
BEGIN recovery
  SELECT slot FOR UPDATE ; si C perdu -> LostClaimException
  SELECT actif par E et B exact
  B encore courant -> BindingFenceConflictException -> ROLLBACK
    puis policy Command : conflit TRANSIENT, RetryAfter ou Fail si budget épuisé
  B absent -> Execute imbriqué REQUIRED :
    INSERT outcome REJECTED(CALLER_IDENTITY_NOT_CURRENT) ; terminal event
    provenance vide ; CAS final S/C ; claims.end(REJECTED)
COMMIT recovery
```

Une nouvelle association à la même E, même avec le même User, utilise un B neuf. La non-réutilisation des occurrences B et le writer sérialisé par stream justifient la décision négative : elle ne prétend pas valider positivement un nouveau Binding. Supprimer le lock Consumption n'autorise pas à supprimer cette relecture, à réutiliser les effets métier rollbackés ou à convertir le conflit « B toujours présent » en rejet public. Si ce conflit épuise les retries, il rejoint l'échec technique terminal de §3.4 et publie FAILED ; il ne devient pas CALLER_IDENTITY_NOT_CURRENT.

### 3.7 Publications existantes : quatre sémantiques différentes

| Chemin | CURRENT |
|---|---|
| `JdbcCommandOutcomeAdapter.publish` | INSERT strict ; aucun ON CONFLICT, aucune comparaison ni réutilisation. Attend une publication Q non committée ; si elle rollbacke, l'insert peut réussir ; si elle committe, violation unique avant le CAS. Le deuxième terminal event n'est pas écrit si l'outcome échoue. |
| `ExecuteRegistrationService` | Relit l'outcome et retourne celui déjà présent sans refaire Acquire Binding. Si deux lecteurs voient absent, l'INSERT final reste soumis à l'unicité ; ce n'est pas un upsert arbitraire. |
| CommandResult / RegistrationResult | `ensureResult` INSERT ON CONFLICT DO NOTHING, puis relecture et comparaison du résultat immutable. Divergence = exception et rollback. Ce sont des slots consommateurs distincts des slots sources. |
| Projection / Task | Projection `ALREADY_EXISTS` = succès sans comparaison de contenu (first published wins, canon) ; Task.ensure converge par ProjectionKey. Le CAS du slot reste requis après ce succès idempotent. |

**REFUTED :** « toute publication terminale actuelle est idempotente ». L'unification ne doit pas introduire `DO NOTHING` dans Command pour cacher des résultats contradictoires ou une perte de claim.

## 4. Variantes TARGET étudiées

### 4.1 Protocole minimal proposé

```text
pour une failure technique : attendre d'abord le rollback de l'Execute
BEGIN transaction terminale
  effet durable SQL propre au résultat (éventuellement vide ou déjà préparé)
  outcome / événement terminal si la famille en possède
  provenance si elle en possède (pas celle de l'exécution échouée)
  FLUSH de tous les writes ORM nécessaires
  CAS slot WHERE slot_id=S AND current_claim_id=C AND status='PENDING'
  si zéro : lever une exception qui traverse la frontière transactionnelle
  si un : clore/fail le claim, en exigeant exactement une ligne
COMMIT seulement si tous les contrôles et contraintes réussissent
après rollback, éventuellement traduire la perte en FencedMutationResult.LOST_CLAIM
```

L'outcome est « provisoire » au sens transactionnel : une ligne SQL normale non committée, pas un nouveau statut durable à nettoyer. Un crash avant commit doit tout annuler ; aucun protocole de compensation n'est ajouté. Le CAS ne doit pas créer un commit intermédiaire, et un callback `afterCommit` ne peut porter un effet requis.

Le CAS actuel est suffisant **pour l'ownership** sous les transitions inspectées (**SUPPORTED BY CODE**) : S identifie la ligne ; PENDING écarte la fin ; C n'est jamais réinstallé après son remplacement. Pas besoin d'ajouter une révision lue auparavant. La FK ne prouve pas que le claim est ouvert : cette garantie supplémentaire vient des writers et de l'UPDATE de clôture. Une corruption ou une nouvelle API qui invaliderait un claim sans retirer son ownership pourrait faire gagner le CAS puis échouer la clôture ; il faut rollbacker l'ensemble. Une erreur SQL, un trigger, une contrainte ou le commit lui-même peuvent aussi échouer après le CAS. Aucun succès ne doit être annoncé avant la sortie transactionnelle réussie.

### 4.2 A, B et C : deux autorités, une transaction

| Critère | A : métier → Binding → outcome → provenance → CAS Consumption | B : métier → CAS Consumption → Binding → outcome → provenance | C : métier → outcome → provenance → Binding → CAS Consumption |
|---|---|---|---|
| Atomicité possible | Oui, CURRENT du succès | Oui si CAS, clôture, fence et effets partagent le commit ; la perte Binding annule aussi le CAS | Oui sous la même exigence ; perte Binding annule les publications provisoires |
| Validité concurrente | E/U/B/R puis S/C, verrous conservés | S/C puis E/U/B/R ; aucun succès externe à annoncer au CAS anticipé | E/U/B/R puis S/C tardifs ; les deux doivent encore gagner |
| Verrous après le métier | Stream E → unicité Q → FK S → UPDATE S → claim | UPDATE S → stream E → unicité Q/FK S → claim, suivant placement de clôture | Unicité Q → FK S → stream E → UPDATE S → claim |
| Durée / contention | Fence E détenu pendant outcome/provenance/CAS | Slot acquis tôt, tenu pendant les attentes Binding et publication ; ne retire aucune sérialisation | Fence E acquis plus tard, mais outcome/FK détenus en attendant Binding ; travail perdu plus important possible |
| Acquire/Detach Binding | S'ordonnent sur E ; pas d'attente du slot Command dans leurs chemins | Mêmes writers E ; ils peuvent prolonger la détention du slot Command | Mêmes writers E ; outcome et provenance peuvent déjà être occupés pendant leur attente |
| Takeover Consumption | Peut gagner avant CAS ; après CAS attend la résolution | Bloqué dès le CAS réussi jusqu'au commit/rollback, même si fence Binding non exécuté | Peut gagner plus tard dans la tentative ; provenance KEY SHARE peut déjà le retarder |
| Rollback | Annule les deux validations et tous les writes | Annule aussi le DONE provisoire | Annule aussi outcome/provenance antérieurs au fence |
| D17 | Le succès A n'est pas seul en faute : le finalizer inverse S→Q | Pas nécessaire pour corriger D17 ; remplace un lock par un UPDATE retenu | Pas nécessaire pour D17 ; élargit le changement Command |
| Avis | **Conserver**, aligner les autres terminaux sur son ordre Q→S | Non retenu : pas de bénéfice établi, slot détenu plus longtemps | Non retenu : preuve de migration et d'ordres supplémentaire |

La validité des variantes B/C reste **HYPOTHESIS** tant qu'elles ne sont pas exécutées ; leur coût comparatif est **INCONCLUSIVE**. B n'est pas une suppression de verrou. C n'est pas automatiquement meilleur parce que les validations sont « plus finales ».

Le fence positif Binding doit rester après le travail qu'il protège et avant le commit commun ; rien n'impose de le coller au CAS Consumption. Il verrouille la ligne stream jusqu'au commit ; un Detach ultérieur attend. Déplacer le fence change les fenêtres de contention, pas le besoin d'atomicité. Les prédicats EXISTS Binding restent soumis aux limites de snapshot déjà caractérisées dans l'audit antérieur ; conserver les writers stream/révision évite d'inventer une nouvelle variante optimiste.

## 5. Matrices de concurrence et de verrous

### 5.1 Ressources effectivement attendues

| Ressource | CURRENT / TARGET et conséquences |
|---|---|
| Slot SELECT FOR UPDATE | Acquire/Takeover ; Finalize/recovery CURRENT. Incompatible avec KEY SHARE de provenance. TARGET doit retirer les deux derniers usages terminaux, pas Acquire. |
| Slot UPDATE terminal/retry | Colonnes changées hors clés uniques référencées dans le schéma inspecté : mode attendu NO KEY UPDATE, compatible avec KEY SHARE, incompatible avec un autre writer. Gardé jusqu'au commit/rollback. Revalider si le schéma évolue. |
| Inputs | UNIQUE(S,subject,type/version) et FK S : attente d'unicité possible avant l'autorité ; protection référentielle du parent. |
| Results | FK S, PK UUID ; pas de même contrainte d'unicité métier que les inputs dans V4. Même rôle possible de la FK dans un cycle. |
| Outcome Command | PK Q ; l'insert concurrent attend la transaction propriétaire de la clé. Une seule attente n'est pas un deadlock. |
| Terminal event | UNIQUE Q, PK event ; FK outcome. Ordre fixe outcome puis event. Il peut lui aussi attendre avant le CAS, mais n'introduit pas un slot→outcome dans TARGET. |
| Claim | UPDATE invalidation ou clôture ; acquisition et terminalisation écrivent slot avant claim. FK slot→claim ne suffit pas à garantir un historique de clôture valide. |
| Binding / métier | Fence E est une écriture ; Pot peut déjà être verrouillé avant E ; Registration verrouille E avant User/faits/outcome. Le protocole Consumption ne normalise pas tous ces ordres métier. |

Les modes attendus sont déduits du SQL/schéma et des [règles PostgreSQL de verrous](https://www.postgresql.org/docs/17/explicit-locking.html). Les index uniques peuvent attendre la résolution du concurrent ([contrôle d'unicité](https://www.postgresql.org/docs/17/index-unique-checks.html)). Sous READ COMMITTED, un UPDATE conditionnel en conflit peut attendre puis réévaluer sa ligne cible ([isolation](https://www.postgresql.org/docs/17/transaction-iso.html)). Ce cadre ne prouve pas une variante non exécutée.

Les `saveAll` et leur flush sont visibles dans [l'adapter provenance][provenance]. Les ordres du stream et du fence proviennent du [repository stream][stream] et du [writer Binding][bindingwriter]. Les stores [projection][projectionstore], [CURRENT_BINDING][currentbindingstore] et [LKV][lkvstore] portent leurs propres arbitrages ; ils ne délèguent pas l'intégrité de leur contenu au seul slot.

### 5.2 Ancien et nouveau worker : arbitrage terminal

| Ordre admissible | CURRENT | TARGET attendu | Classe TARGET |
|---|---|---|---|
| C1 finit avant acquisition C2 | Verrou/CAS de C1 retenu ; C2 attend puis AlreadyDone | Même état committé ; C2 peut déjà attendre l'INSERT idempotent du slot | HYPOTHESIS, analogues CURRENT exécutés historiquement |
| C2 takeover committe avant CAS C1 | C1 perd son CAS si aucun write préalable ne bloque ; peut subir D17 | C1 rollbacke ses effets ; C2 reste autoritaire | HYPOTHESIS |
| Takeover détient S sans avoir committé | Provenance C1 peut attendre S ; takeover n'attend ni Q ni métier | Même attente possible. Takeover committe ou rollbacke sans attendre Q : pas de cycle D17 à deux transactions | HYPOTHESIS |
| C1 détient déjà KEY SHARE(S), takeover commence | Takeover FOR UPDATE attend ; C1 peut atteindre son UPDATE non-key puis commit | Même résultat : compatible KEY SHARE/NO KEY UPDATE, pas de promotion universelle à FOR UPDATE | HYPOTHESIS |
| C2 terminal attend l'outcome provisoire C1 | C2 détient FOR UPDATE(S) ; C1 flush FK attend C2 : D17 | C2 attend Q sans détenir le lock préalable S ; C1 flush, CAS stale=0, rollback ; C2 continue | HYPOTHESIS |
| C2 publie Q en premier après takeover committé | C1 peut échouer sur unicité sans atteindre son CAS | Identique pour INSERT strict : aucune autorité stale committée, mais erreur à gérer après rollback | HYPOTHESIS |
| Deux appels concurrents sur le même C | CURRENT Finalize sérialise avant callback | Callbacks provisoires concurrents possibles ; un seul CAS gagne, l'autre rollbacke ou échoue avant CAS sur une contrainte | HYPOTHESIS ; équivalence d'invocation REFUTED |
| Deux takeovers concurrents | Sérialisation acquisition, un seul claim installé ; loser Busy | Acquisition conservée : pas de changement de protocole proposé | SUPPORTED BY CODE, preuve historique limitée à CURRENT |
| Claim invalidé pendant Execute | Takeover transactionnel remplace aussi le token ; CAS final refuse C1 | Idem ; si invalidation seule anormale, la clôture doit refuser et tout annuler | HYPOTHESIS |

Pour les outcomes d'une même Q, l'unicité tend à sérialiser **avant** les FK/CAS. Pour un store sans cette unicité, deux KEY SHARE(S) ne se bloquent pas mutuellement, et l'UPDATE non-key n'attend pas ces KEY SHARE. Il n'y a donc pas de cycle symétrique déduit du seul partage de S. Ajouter un lock fort ou modifier une clé référencée invaliderait cet argument. Il reste des cycles possibles entre plusieurs ressources métier ; l'étude n'en déduit pas une absence globale de deadlock.

### 5.3 Cohabitation des protocoles et ordres inverses

- Garder un seul finalizer `S FOR UPDATE → Q` concurrent d'un Execute `Q → provenance S` conserve D17. Corriger uniquement `FinalizeConsumptionService` laisse aussi la recovery `lockCurrentClaim` dans le graphe. Son témoin historique est sans provenance ; une ancienne tentative normale avec inputs peut fournir l'attente manquante (**HYPOTHESIS**).
- Une migration partielle A/C peut inverser **Binding et outcome** : C1 version C prépare un rejet AUTHORIZATION_EXPIRED, détient Q puis attend E ; C2 après takeover, version A, a fenced E puis attend Q. Les rejets d'autorisation n'ont pas besoin d'un verrou Pot commun. Cycle Q→E contre E→Q **HYPOTHESIS**, non exécuté. Retirer D17 ne justifie pas ce nouveau changement d'ordre.
- B introduit S→E alors que le succès courant a E→S. Sur un unique slot après takeover committé, le CAS C1 stale peut refuser sans attendre : cette inversion textuelle n'est pas, à elle seule, une preuve de deadlock. Elle augmente néanmoins la portée de preuve, sans résoudre un besoin absent du choix A.

## 6. Analyse spécifique du deadlock D17

### CURRENT : preuve historique, pas expérience de cette étude

Le test [`terminalFailureAndStaleProvenanceFormARealPostgresDeadlock`][commandtest] et [l'audit du 16 octobre][audit17] établissent cet entrelacement :

| Étape | Ancien C1 | Nouveau C2 |
|---|---|---|
| 0 | Claim expiré/remplacé, continue Execute | Takeover C2 committé |
| 1 | Fence Binding puis INSERT outcome Q et terminal event, non committés | Execute technique échoue et rollbacke |
| 2 | Retenu avant retour du callback | Failure Fail : FOR UPDATE(S), puis INSERT outcome Q **attend C1** |
| 3 | Reprend ; saveAll input ; flush FK demande KEY SHARE(S), **attend C2** | Garde FOR UPDATE(S) en attendant Q |
| 4 | CAS final encore non atteint | PostgreSQL détecte 40P01, annule une transaction |

**PROVEN (historique)** : existence de ce cycle. Les témoins `terminalFailureWaitingOnStaleOutcomeDoesNotDeadlock` et `bindingRecoveryWaitingOnStaleOutcomeDoesNotDeadlock` montrent que, sans provenance dans ces cas, C1 atteint son CAS stale, retourne zéro sans attendre S et rollbacke. Ils ne prouvent pas le cas avec provenance.

Le test utilise de vrais adapters, deux transactions PostgreSQL, latches, horloge contrôlée et observation `pg_blocking_pids` de l'INSERT Q bloqué. Son fixture métier est contrôlé ; il ne reproduit pas toute la mutation Pot. Il exige un seul 40P01 sans imposer la victime et inspecte les états persistants. Les résultats avec seulement `consumption_results` n'ont pas été exécutés comme D17 distinct dans cette preuve.

### TARGET : suppression attendue de l'arête qui ferme le cycle

```text
C1 : détient Q ──> flush provenance S ──> CAS S/C1=0 ──> ROLLBACK libère Q
C2 : attend Q, sans FOR UPDATE préalable sur S ──> publie ──> CAS S/C2=1 ──> clôture ──> COMMIT
```

C2 ne possède plus le verrou fort S pendant son attente de Q. Si C1 a publié en premier, C2 n'a pas encore atteint sa propre provenance ni son CAS. C1 peut franchir la FK, atteindre le contrôle stale et libérer Q par rollback. Si C2 publie d'abord, C1 attend Q puis peut rencontrer l'unicité après le commit C2 ; C2 n'attend pas une ressource acquise plus tard par C1 dans cette séquence. Il n'apparaît pas de cycle symétrique Q/S pour ces deux ordres.

**HYPOTHESIS : D17 supprimé par le protocole complet**, incluant recovery et absence de locks terminaux cachés. La déduction du graphe est solide, mais n'est pas une preuve PostgreSQL TARGET. Un `UPDATE slot` anticipé ne « libère » pas le slot après son retour ; il le garde jusqu'à résolution et n'est donc pas une justification générique d'absence de cycle.

Le flush est essentiel : `terminalize` a `flushAutomatically=true` et `clearAutomatically=true`. L'appel Java au CAS peut déclencher les INSERT de provenance avant d'envoyer l'UPDATE. JDBC publie déjà l'outcome et l'événement. Le protocole doit être testé selon cet ordre SQL réel ; compter seulement les appels Java masquerait précisément D17. Les contraintes uniques des inputs, les FK des results et tout write ORM en attente sont aussi des points d'échec pré-CAS.

**Conserver les FK de provenance.** Elles empêchent les orphelins ; elles ne constituent pas l'autorité Consumption et ne rendent pas l'ordre terminal actuel nécessaire. Leur suppression cacherait une arête, sans aligner les protocoles ni traiter les autres écritures pré-CAS. Aucune migration de schéma n'est justifiée par cette étude (**SUPPORTED BY CODE**).

## 7. Applicabilité par famille de Consumption

Tous les Acquire gardent leur sérialisation préalable. « Verrou préalable » ci-dessous concerne uniquement la transaction terminale, pas l'acquisition ni les verrous métier.

| Famille / sources | CURRENT | Effets particuliers | Applicabilité TARGET / exception |
|---|---|---|---|
| Command | Execute CAS final ; failure terminale et recovery verrou préalablement S | Pot, Events métier, fence E/U/B/R, outcome strict + terminal event, inputs/results | Priorité de correction. Succès/rejet restent A. Fail et recovery peuvent être alignés sous conditions de rollback et gestion des erreurs pré-CAS. HYPOTHESIS. |
| Registration ([service][registration], [locator][registrationlocator]) | Execute CAS final ; failure générique lock S seulement si Fail | Acquire Binding verrouille stream E ; crée User/UserCreatedFact/occurrence/actif/fait puis outcome REGISTERED/REJECTED et input request ; réutilise outcome existant | Déjà conforme au CAS final pour résolution métier. Conserver la sérialisation Binding, aucune généralisation du rejet de recovery Command. FAIL technique n'invente pas un RegistrationOutcome.Failed absent du modèle. SUPPORTED BY CODE. |
| Binding ([locator][bindinglocator]) | Execute CAS final ; failure terminale générique lock S | Consomme les faits immuables vers CURRENT_BINDING ; upsert monotone R, doublon même payload accepté, divergence à R égale rejetée | Déjà conforme pour succès. Ne pas confondre ce worker read model avec Acquire/Detach WRITE, dont le lock stream reste nécessaire au protocole actuel. SUPPORTED BY CODE. |
| Event ([service][eventservice]) | AcquireThenFinalize ; Finalize lock S avant Task.ensure ; CAS final ensuite | Matérialisation de tâches par ProjectionKey, sans décodage métier lourd ; pas de provenance générique ajoutée ici | SQL ensure rollbackable : compatible pour états durables. Échecs ensure remontent RuntimeFailure, slot peut rester Busy jusqu'au takeover ; ne pas leur inventer une publication FAILED automatique. Exclusion du callback stale et sérialisation d'invocation changent : vérifier la portée contractuelle avant migration. HYPOTHESIS. |
| Projection Task ([service][taskservice]) | Calcul hors transaction finale ; publish/recordFailure sous Finalize lock S ; Temporary passe par retryHandler | Projection immutable first published wins ; ProjectionFailure terminal distinct ; aucune provenance générique obligatoire | Garder préparation hors transaction et publication courte. CAS final compatible avec le canon de rollback intégral, sous preuve du manager commun. Le store conserve ALREADY_EXISTS sans comparaison. HYPOTHESIS. |
| Command Result ([locator][commandresultlocator], [store][commandresultstore]) | Execute CAS final ; failure générique | Terminal event source → résultat immutable comparé après ON CONFLICT ; input événement ; slot distinct de Command | Déjà le modèle recherché ; garder idempotence spécialisée. Aucune publication d'outcome Command depuis ce worker. SUPPORTED BY CODE. |
| Registration Result ([locator][registrationresultlocator], [store][registrationresultstore]) | Execute CAS final ; failure générique | Outcome source → résultat immutable et input ; comparaison après ON CONFLICT | Même règle ; pas d'alignement forcé avec le store de projection sans comparaison. SUPPORTED BY CODE. |
| Latest Known Version ([locator][lkvlocator]) | Execute CAS final ; failure générique | UPSERT monotone du watermark Pot, input Event ; plusieurs slots peuvent viser le même Pot | Déjà conforme. Garder monotonie et indépendance des slots/versions ; le CAS ne remplace pas l'upsert conditionnel. SUPPORTED BY CODE. |

Le test Event `expiredCurrentClaimMayFinalizeButTakeoverFencesTheOldClaimBeforeEnsure` exige explicitement **zéro appel** du store stale. `twoWorkersNeverInvokeTheEffectConcurrentlyForTheSameCandidate` constate une seule invocation concurrente dans le scénario courant (sans forcer une expiration pendant le callback). Ces tests ne prouvent pas que cette exclusion est nécessaire à l'intégrité SQL ; ils montrent néanmoins une promesse observable actuelle. Une migration ne doit pas simplement effacer leurs assertions pour faire passer la suite : distinguer l'invariant durable canonique de cette protection d'invocation, et ajouter le cas avec takeover pendant publication provisoire. Tant que ce contrat n'est pas clarifié, Event peut conserver une exception locale ; elle ne maintient pas D17 Command puisqu'elle n'écrit pas `command_outcomes`.

Même réserve sur les tests unitaires de Finalize qui imposent `lock → effect → terminalize` et ceux qui vérifient l'absence totale d'appel stale. Leur contrat doit être réévalué explicitement, pas présenté comme équivalent à l'exécution spéculative. Rien ne justifie d'ajouter des outcomes, des événements ou de la provenance aux familles qui n'en ont pas.

## 8. Garanties préservées, risques et limites

### Réponses aux dix critères

| # | Question | Conclusion et classement |
|---|---|---|
| 1 | Équivalence fonctionnelle ? | **REFUTED** pour l'équivalence stricte des invocations, exceptions et fenêtres de takeover. **HYPOTHESIS** pour l'équivalence des états committés admissibles, sous transaction unique/rollback et contrats spécialisés conservés. |
| 2 | Sûr avec takeovers ? | **SUPPORTED BY CODE** pour la suffisance du prédicat courant et la lease ; **HYPOTHESIS** pour les nouveaux chemins qui préparent des effets avant ce CAS. |
| 3 | Atomicité outcome/événement/provenance/slot/claim ? | **HYPOTHESIS**, faisable avec la transaction commune déjà utilisée ; zéro CAS et clôture non unitaire doivent sortir par rollback. **REFUTED** si le changement se limite à retirer le lock en gardant un retour normal après effet. |
| 4 | Supprime D17 ? | **HYPOTHESIS**, l'arête S fort→Q disparaît dans les entrelacements étudiés. D17 CURRENT est **PROVEN historiquement**, pas TARGET. |
| 5 | Autres deadlocks ? | **INCONCLUSIVE** globalement. Pas de cycle symétrique déduit sur Q/S dans le TARGET uniforme ; cycles métier/contraintes et cohabitation A/C restent à caractériser. **REFUTED** : « CAS final implique absence globale de deadlock ». |
| 6 | Réduit la contention ? | **SUPPORTED BY CODE** pour le retrait d'un SELECT FOR UPDATE et une section forte S plus tardive sur les branches modifiées ; **INCONCLUSIVE** sur débit/latence. Attentes d'unicité, FK et Binding subsistent, le travail stale peut augmenter. |
| 7 | Supprimer lockCurrentClaim ? | **HYPOTHESIS** pour les terminaux Command/failure/recovery après adaptation complète. Pas une suppression mécanique du port avant migration de tous ses callers, notamment Event/Projection. Acquire reste verrouillé. |
| 8 | Généralisation ? | **SUPPORTED BY CODE** : de nombreuses familles appliquent déjà la règle au succès. **HYPOTHESIS** pour Finalize commun ; **REFUTED** pour une forme universelle d'outcome/événement/provenance ou une API de préparation unique imposée. |
| 9 | Plus petit changement ? | **SUPPORTED BY CODE** : aligner les deux branches terminales inversées de Command sur le succès existant ; conserver primitives SQL/CAS et policies, factoriser seulement le contrôle de complétion nécessaire. Faisabilité dynamique **HYPOTHESIS**. |
| 10 | Garder les FK ? | **SUPPORTED BY CODE** : oui. Intégrité référentielle utile, aucun invariant n'impose leur retrait ; validation TARGET avec FK actives obligatoire. |

### Risques à traiter avant implémentation

**Retour après CAS perdu.** Remplacer le lock par `durableEffect.apply(); handleFailure(...)` et retourner LOST_CLAIM serait incorrect : le wrapper committe un retour normal. Même problème dans une transaction REQUIRED englobante si l'exception est capturée trop tôt. La conversion en LOST_CLAIM appartient après la résolution transactionnelle ; un état rollback-only peut encore faire échouer la sortie d'un template imbriqué. Ne pas capter l'exception à l'intérieur pour poursuivre des écritures.

**Erreur avant CAS.** Un ancien finalizer qui commence après le commit d'un résultat gagnant pouvait autrefois sortir au lock sans appeler l'effet. Avec INSERT strict, il peut maintenant lever 23505 avant le CAS. Le CAS final empêche les commits stale, pas toutes les erreurs stale. Il faut spécifier le résultat externe : rollback puis contrôle frais d'ownership pour normaliser une tentative effectivement supplantée, ou erreur technique assumée. Une simple lecture préalable non verrouillante peut éviter le cas déjà stale, mais ne fence aucune race et ne résout pas ce problème général. Ne jamais traiter un conflit de contenu du détenteur encore courant comme un succès idempotent. Une relecture après rollback ne doit ni reprendre le même Execute ni masquer un échec tant que son claim est toujours courant.

**Déplacement de la priorité.** Aujourd'hui Finalize gagnant au SELECT verrouillé empêche le takeover pendant l'effet. Demain le takeover peut gagner pendant la préparation de cet effet. C'est compatible avec « seul le détenteur courant committe », mais ce n'est pas le même ordre de victoire. Sous forte contention, davantage de calcul/SQL pourrait être annulé ; pas de garantie de fairness ou de progression bornée.

**Isolation/ORM.** Tests historiques en READ COMMITTED ; en REPEATABLE READ/SERIALIZABLE, des conflits peuvent lever une erreur de sérialisation au lieu de zéro. Cela doit rollbacker, sans transformer toute erreur en rejet métier. `clearAutomatically` peut détacher des entités ; conserver le même ordre de flush et ne pas porter une entité managée supposée courante au-delà du CAS sans examen. Aucun changement d'isolation proposé.

**Fin après CAS.** Le claim peut refuser la clôture (déjà fini/invalidé, mauvais slot), une contrainte peut échouer, le commit peut échouer. La cardinalité exactement un reste obligatoire. Un CAS gagnant suivi d'une clôture zéro ne doit jamais laisser un slot DONE committé. Une clôture séparée REQUIRES_NEW ou un journal asynchrone serait incompatible avec l'atomicité demandée.

**Limites du périmètre.** Aucun DDL concurrent, crash de processus, panne réseau, benchmark, déploiement multi-datasource ou effet externe testé. Pas de preuve d'absence de tous les cycles sur toutes les clés métier. Aucun runtime/schéma modifié pour simuler TARGET dans cet audit.

## 9. Recommandation architecturale

Adopter comme règle de conception : **tous les effets requis d'une décision terminale sont préparés dans sa transaction ; celle-ci ne peut committer que si le CAS d'ownership et la clôture cohérente du claim réussissent.** Une famille peut préparer hors transaction ce qui est pur, mais aucune sortie durable requise ne peut précéder le commit commun.

Conserver A pour Command. Aligner l'échec terminal de failure et la branche terminale de recovery sur `outcome → CAS S → clôture`, avec la relecture Binding négative conservée après rollback. Laisser les retries sur leur CAS distinct : retry n'est pas une décision terminale et n'a pas d'outcome terminal à publier.

Réutiliser `tryTerminalize` et `handleFailure(Fail)` : ils font déjà CAS puis clôture. Leur différence SUCCESS/REJECTED versus FAILED porte des invariants utiles de raisons et d'historique ; il n'est pas nécessaire d'unifier immédiatement leur signature. Si un helper commun est introduit, il doit seulement appliquer « résultat du CAS exigé, rollback si perdu » dans le service terminal. Pas de registry de phases, de nouveau state machine ni de framework de finalisation.

Avant de modifier Finalize partagé, décider explicitement si sa promesse « callback jamais appelé stale » est encore requise pour Event/Projection. Si elle l'est, conserver provisoirement ce chemin pessimiste pour ces consommateurs, avec une justification documentée, et corriger Command localement. Si seule l'absence d'effet **committé** est requise, faire évoluer Finalize commun et les tests associés. Supprimer ensuite `lockCurrentClaim` seulement lorsqu'aucun usage terminal justifié ne reste. Le lock d'acquisition et celui du stream Binding restent hors de ce chantier.

Ne pas déplacer le fence Binding pour ce correctif. Ne pas modifier le statut officiel de la dette Binding : D17 est un défaut de progression Consumption distinct de l'ordre stream/actif. Garder les FK et les contraintes d'unicité existantes.

## 10. Plan incrémental proposé et vérification

La cohérence statique est suffisante pour préparer un chantier, pas pour annoncer sa validation. Chaque étape ci-dessous est **proposée, non exécutée**.

1. **Fixer le contrat terminal minimal.** Préciser rollback avant LOST_CLAIM, traitement des erreurs d'unicité pré-CAS, cardinalité de clôture et comportement d'invocation stale. Aucun changement aux policies ni aux résultats publics spécialisés. Conserver un cas témoin D17 CURRENT dans la preuve historique.
2. **Aligner les terminaux Command ensemble.** Fail et recovery négative, sans toucher au succès A, Acquire, fence Binding ou schéma. Si le service partagé change, déclarer avant édition les consommateurs réellement modifiés : COMMAND, EVENT, PROJECTION et, pour le failure générique, BINDING/LKV ainsi que Registration/Results selon leur couverture runtime réelle. Ne pas supposer que COMMAND teste tous les runtimes.
3. **Prouver le nouveau protocole sur PostgreSQL.** Reprendre la barrière D17 avec FK actives et vrais wrappers ; exiger aucune 40P01, C1 rollback et C2 terminal complet. Tester inputs seuls, results seuls, les deux, et sans provenance. Ajouter la recovery négative avec ancienne provenance. Ne pas injecter un faux CAS gagnant pour contourner l'attente.
4. **Prouver les frontières et les échecs.** Takeover avant publication, pendant effets provisoires, après CAS ; takeover committé et rollbacké ; lease expirée sans takeover ; deux publications terminales dans les deux ordres ; deux appels sur même claim ; résultat déjà committé et conflit divergent. Forcer un refus de clôture après vrai UPDATE slot (par fixture/trigger de test), une erreur après effet, un flush en erreur et une perte de fence Binding ; vérifier l'absence totale de fuite.
5. **Étendre seulement les contrats acceptés.** Event/Projection : conserver calcul hors transaction, idempotence native et retries ; prouver task/publication/failure + slot + claim sur la même transaction. Vérifier explicitement les propriétés qui changent depuis « zéro invocation stale » et celles qui restent (« zéro effet stale committé »). Registration/Results/LKV/Binding succès : non-régression de leur CAS existant, pas de refonte métier.
6. **Consolider sobrement.** Retirer les callers/port de lock devenus inutiles, ajuster le canon et les tests qui codent l'ordre pessimiste seulement après les preuves. Mesurer attentes slot/Q/E et coût des rollbacks avant de promettre un gain de débit. Arrêter l'élargissement si une famille exige réellement un effet hors transaction ou une exclusion avant callback.

### Protocole expérimental requis

Barrières latches ou équivalent, horloge de lease contrôlée, transactions/connexions distinctes, observateur sur troisième backend. Attendre une vraie attente SQL avec PID/XID et `pg_blocking_pids`, jamais un `sleep` de synchronisation. Ne pas retenir le contrôleur derrière une étape devenue inaccessible : le TARGET n'a plus la barrière « slot verrouillé par finalizer », donc observer son attente d'outcome. Bornes d'attente explicites, zéro retry masquant un échec et contrôle des SQLSTATE. Dans les variantes où aucune attente n'est attendue, barrière avant commit et contrôle des états depuis l'observateur.

Avant résolution : aucun effet terminal visible par un lecteur externe ; après résolution : exactement le résultat autoritaire, un terminal event Command si applicable, provenance correcte, slot cohérent et historique complet. Les deux rôles ancien/nouveau et les deux ordres de publication sont nécessaires. Tester aussi une exécution gagnante déjà sérialisée par Pot/Binding pour éviter d'attribuer au slot une sérialisation obtenue ailleurs.

Les tests TARGET qui nécessitent le nouveau Finalize/recovery appartiennent au futur chantier d'implémentation. Cette étude ne remplace pas silencieusement ces composants dans le runtime pour produire une preuve artificielle.

### Commandes de vérification futures

Pour un premier changement limité au comportement Command, commande canonique depuis `app/` :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
```

Si Finalize partagé est modifié et ses contrats Event/Projection migrés, inclure les slices réellement touchées, par exemple :

```sh
./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am test
```

Ajouter les anchors BINDING/LKV si leur failure générique est affecté ; les commandes canoniques sont respectivement `./mvnw -pl runtime-binding-consumption-worker -am test` et `./mvnw -pl runtime-latest-known-version-consumption-worker -am test`. La politique ne nomme pas de slice Registration/Results autonome : expliciter leurs modules runtime comme preuves complémentaires si le service partagé y change. Ne pas les appeler implicitement « couverts » parce que les adapters ont compilé. Aucun gate global nécessaire sans modification de dépendances/frontières ; réévaluer la politique si la conception en modifie. Aucun reactor complet pour ce chantier local par défaut.

### Vérification réellement effectuée dans cette étude

**Aucun test exécuté ni ajouté.** Inspection du code, des contraintes/migrations et des tests existants ; consultation des trois pages PostgreSQL citées. Aucune variante TARGET exécutée. Le choix documentaire permet de livrer une conception conditionnelle sans modifier la production ; les incertitudes dynamiques sont des critères d'entrée du futur chantier.

Tests lus, sans revendiquer de nouveau PASS :

- `CommandConsumptionPostgresTest` : D17, deux témoins sans provenance, succès/rejet, takeover, stale failure, rollback Binding et recovery.
- `TransactionalConsumptionExecutionPostgresTest` : atomicité, lease expirée, rollback stale, finalisation courte, échec technique suivi de retry/failure, erreurs de provenance.
- `JpaConsumptionLifecycleAdapterPostgresTest` et `JpaConsumptionSlotRepositoryFencingQueryTest` : transitions, CAS et takeover ; le second contrôle la requête, pas toute sa concurrence SQL.
- `FinalizeConsumptionServiceTest` : ordre actuel et exclusion du callback stale ; ce sont des doubles de ports, pas des preuves de locks PostgreSQL.
- `ProjectionMaterializationConsumptionPostgresTest` et `CanonicalProjectionTaskRuntimePostgresTest` : contrats d'invocation, atomicité task/projection/failure et finalisation.
- Sources et rapport des courses `CommandResultTakeoverRacePostgresTest` : publication concurrente et résolution du slot dans le protocole Execute existant.

L'audit précédent signale cinq tests Binding runtime empêchés par une ambiguïté d'injection ; ce rapport ne les relance pas et ne présume pas leur correction. Aucun `install`, SNAPSHOT installé, migration exécutée, gate architecture global ni reactor complet.

### Discipline Git et périmètre réel

- Départ : `feat/runtime-monolith`, HEAD `306963fce402e7d5176412fdb16deccec8436ff8`, upstream local aligné 0/0. Remote `origin` : `https://github.com/kartaguez/pocoma.git` (fetch/push). `git ls-remote origin refs/heads/feat/runtime-monolith` confirme le même SHA sur le serveur avant rédaction ; premier essai DNS refusé dans le sandbox, second réussi avec accès réseau autorisé.
- Working tree préexistant préservé : `docker/keycloak/pocoma-realm.json` modifié ; Bruno `01 Create Pot.bru` supprimé, `02` à `07` et `folder.bru` modifiés ; nouveaux `001 Registrations.bru`, `002 RegistrationResult.bru`, `003 Me-Binding.bru`, `004 Create Pot.bru`.
- Impact production attendu/observé : NONE. Slices d'analyse déclarées : COMMAND principale, Consumption partagé et lecture des autres familles ; slices Maven exécutées : NONE (document uniquement). Forbidden production impact : tout `app/`, runtime et schéma. Aucun franchissement inattendu. Politique [Reactor_Verification_Policy][policy] lue avant analyse sous `app/`.
- Commandes réellement utilisées : `pwd`, `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git rev-list --left-right --count HEAD...@{upstream}`, `git ls-remote origin refs/heads/feat/runtime-monolith`, `git log -3 --oneline` ; recherches `rg`/`find`, lectures `cat`/`sed`/`head` et contrôle documentaire décrit dans la restitution Git. Aucune commande Maven.
- Contrôle documentaire : script Python standard vérifiant les cibles des liens locaux, les références Markdown et la parité des clôtures de blocs de code ; `git diff --check` et examen du diff destiné au commit. Ce contrôle n'est ni un test runtime ni une validation PostgreSQL.
- Seul ce rapport est destiné au commit. Aucun merge, force push, changement de branche ou modification du statut des dettes. Le SHA de livraison, le push et la divergence finale sont donnés dans la restitution après commit, pour ne pas inscrire une autoréférence Git impossible dans le rapport.

## Références de code et de preuve

Les liens pointent vers les sources du HEAD indiqué ; les noms de méthodes dans le texte sont les repères de lecture.

[audit17]: ../debts/BINDING_LOCK_ORDER_GUARD/Audit_2026-10-16.md
[auditlocks]: Concurrency_Authorities_Fencing_Locks_Audit.md
[canon]: consumption-transactional-execution.md
[projectioncanon]: projection-task-execution.md
[takeover]: ../debts/TAKEOVER_TEST_FLAKINESS/Concurrent_Takeover_2026-10-15.md
[policy]: ../testing/Reactor_Verification_Policy.md
[port]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/out/consumption/ConsumptionLifecyclePersistencePort.java
[execute]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java
[finalize]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java
[failure]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/HandleConsumptionFailureService.java
[wrappers]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption
[runner]: ../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java
[lifecycle]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java
[slots]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java
[claims]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionClaimRepository.java
[v4]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V4__consumption_engine.sql
[v6]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V6__consumption_terminal_reason.sql
[v7]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V7__consumption_processing_failure_code.sql
[recordedexecute]: ../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/execution/ExecuteRecordedCommandService.java
[commandexecution]: ../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/consumption/CommandConsumptionExecution.java
[recovery]: ../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/consumption/BindingFenceRecoveryExecuteUseCase.java
[outcomes]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java
[sequential]: ../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java
[commandwiring]: ../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/consumption/CommandConsumptionRuntimeConfiguration.java
[eventwiring]: ../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/event/consumption/EventConsumptionRuntimeConfiguration.java
[taskwiring]: ../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/task/consumption/CanonicalProjectionTaskRuntimeConfiguration.java
[projectionwiring]: ../../app/infra-persistence-projection-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/projection/jdbc/ProjectionStoreAutoConfiguration.java
[registration]: ../../app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/ExecuteRegistrationService.java
[registrationlocator]: ../../app/supra-consume-registration/src/main/java/com/kartaguez/pocoma/supra/consume/registration/RegistrationConsumptionLocator.java
[bindinglocator]: ../../app/supra-consume-binding/src/main/java/com/kartaguez/pocoma/supra/consume/binding/BindingFactConsumptionLocator.java
[eventservice]: ../../app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionService.java
[taskservice]: ../../app/engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/ProjectionTaskConsumptionService.java
[commandresultlocator]: ../../app/supra-consume-command-result/src/main/java/com/kartaguez/pocoma/supra/consume/commandresult/CommandResultConsumptionLocator.java
[registrationresultlocator]: ../../app/supra-consume-registration-result/src/main/java/com/kartaguez/pocoma/supra/consume/registrationresult/RegistrationResultConsumptionLocator.java
[lkvlocator]: ../../app/supra-consume-lkv/src/main/java/com/kartaguez/pocoma/supra/consume/lkv/LatestKnownVersionConsumptionLocator.java
[commandresultstore]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandResultStore.java
[registrationresultstore]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationResultStore.java
[commandtest]: ../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/CommandConsumptionPostgresTest.java
[provenance]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionProvenanceAdapter.java
[stream]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingStreamJdbcRepository.java
[bindingwriter]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingAdapter.java
[projectionstore]: ../../app/infra-persistence-projection-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/projection/jdbc/JdbcProjectionStoreAdapter.java
[currentbindingstore]: ../../app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/JdbcCurrentBindingAdapter.java
[lkvstore]: ../../app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/JdbcLatestKnownVersionAdapter.java
