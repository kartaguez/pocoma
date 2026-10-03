# Wave 2 — audit de l'autorité Binding optimiste et de la frontière Registration

Statut : **AUDIT READ-ONLY — recommandations et plan, aucune correction appliquée**. Date : 2026-10-03.

## 1. Baseline et méthode

| Élément | Constat |
|---|---|
| Branche | `v2-make-it-pull` |
| HEAD attendu, local, `origin/v2-make-it-pull` après `git fetch origin` | `2c8df956f4fe1459cb365071719e6f22ec4e8115` dans les trois cas |
| Divergence `origin/...HEAD` | `0/0` (behind/ahead) ; aucun écart de HEAD à auditer |
| Working tree initial | `?? docs/architecture/mermaid-diagram.png` uniquement ; PNG utilisateur non lu, non modifié et exclu du commit |
| Vérification déclarée pour cet audit | Documentation et inspection statique seulement ; aucune slice Maven, aucun gate architecture, aucun reactor complet, aucune migration exécutée |

Sources normatives relues : [canon Registration](Step_Canon.md), [plan Wave 2](Step_Plan.md), [plan des matérialisations READ](../ARCHITECTURE/Read_Materialization_Gap_and_Migration_Plan.md), [politique de vérification](../../../testing/Reactor_Verification_Policy.md) et [canon WRITE_ADMISSION](../../completed/WRITE_ADMISSION/Step_Canon.md). La politique présente dans ce checkout est `docs/testing/Reactor_Verification_Policy.md` ; il n'existe pas de copie sous `app/docs/testing/`. La politique indique `Trusted baseline: NONE` ; le plan de réalisation devra donc employer la chaîne Flyway existante pour les tests SQL, sans lancer une vérification historique distincte par défaut.

Traçabilité historique : [WA6_Audit](../../completed/WRITE_ADMISSION/WA6_Audit.md) a motivé le stream R parce que la ligne active supprimée par Detach ne gardait ni tombstone ni ordre source. [WA.6.1/6.2/6.7](../../completed/WRITE_ADMISSION/Step_Plan.md) ont choisi `createIfAbsent(E)`, puis `FOR UPDATE` du stream pour allouer R, muter l'autorité et écrire un fait atomiquement ; WA.6.7 a gelé l'ordre stream → autorité pour éviter les inversions de locks. [Binding_Canon_Alignment_Audit](../../completed/WRITE_ADMISSION/Binding_Canon_Alignment_Audit.md) a ensuite identifié la réutilisation historique possible de B, le manque de provenance R0 et l'ancien lock Command. WA.6A/B/D ont ajouté le registre permanent des occurrences et réparé les faits ; WA.6C a remplacé le lock Command par un fence tardif sur le stream ; WA.6E/F/G ont aligné READ et retiré le bootstrap primaire. Le [plan Registration](Step_Plan.md) a réutilisé la primitive WA.6.2, sans démontrer qu'elle était l'unique implémentation possible.

## 2. Executive findings

1. **Binding : `REPLACEABLE`.** La sérialisation de chaque transition par E est indispensable ; le `SELECT ... FOR UPDATE` préalable ne l'est pas. Le stream `(E,current_revision)` déjà présent peut arbitrer par `UPDATE ... WHERE current_revision=:expected`, avec vérification de l'état actif, mutations et fait dans une seule transaction. Cette conclusion est une preuve de faisabilité conditionnée au cutover de **tous** les writers et à des tests PostgreSQL à barrières ; aucun test de la nouvelle implémentation n'a été exécuté pendant l'audit.
2. **Registration → Consumption : `REMOVE`.** Aucune source `main` d'`engine-registration` n'importe ou ne nomme une classe Consumption. L'arc Maven fournit indirectement `engine-core` pour `TransactionRunner`, utilisé par l'admission. Il faut le remplacer par l'arc direct `engine-registration → engine-core`. Le runtime Registration assemble déjà les deux capacités.
3. **`EXECUTE` reste requis.** Dans le modèle envisagé, le CAS Binding gagnant, U, B, les deux familles de faits, l'outcome, la provenance et le CAS terminal du claim doivent partager le commit. Un perdant du CAS doit rollbacker, puis retenter dans une nouvelle transaction avant de publier éventuellement `Rejected`.

## 3. Invariants à préserver, indépendamment des locks

- **Current authority.** Pour E, l'autorité WRITE contient zéro ou une occurrence active `(E,U,B)` ; la ligne `external_identities` active et le stream durable R forment aujourd'hui une autorité composite. `CURRENT_BINDING` est une projection asynchrone, jamais l'arbitre WRITE.
- **Occurrence.** Chaque Attach/Registration gagnant reçoit un B nouveau, opaque et historiquement non réassignable, y compris après détachement du même U. Le registre `external_identity_binding_occurrences` demeure après Detach.
- **Révision.** Chaque transition autoritative committée de E consomme une seule nouvelle R, sans duplication ni réutilisation ; les tentatives rollbackées n'en consomment aucune. Le canon WRITE_ADMISSION §WA6 exige explicitement que chaque mutation incrémente R et « prolonge sans rupture » l'histoire. La contiguïté des transitions **réussies** est donc un contrat technique actuel, particulièrement pour l'audit/replay et la parité stream/faits, même si le simple upsert READ `R > stored.R` pourrait converger malgré un saut. Elle n'impose pas `FOR UPDATE` : un CAS `R → R+1` transactionnel la conserve. R0 reste la baseline historique documentée, non une mutation ordinaire.
- **Facts.** Pour chaque transition gagnante, exactement un fait `ATTACHED` ou `DETACHED` décrit E/U/B/R ; aucun pour conflit, stale detach ou rollback. Les faits sont append-only en production. L'unicité `(E,R)` limite le nombre à un, mais l'existence du fait pour toute transition repose sur la transaction et le writer, pas sur une contrainte SQL de totalité.
- **Atomicité.** Autorité active, R, réservation B, fait et effets Registration concernés ne doivent jamais être committés partiellement.
- **Command.** L'enveloppe conserve E+B exact ; elle ne porte pas R. Le worker ne réinterprète jamais B1 sous B2. Une mutation Binding qui gagne avant le fence final de Command annule les effets métier préparés ; un fence Command gagnant empêche la mutation de committer avant le commit Command. Un rejet métier calculé sous B doit être fenced lui aussi.
- **Registration.** Pour deux requests distinctes visant une E libre et en l'absence d'autres mutations durables : un `Registered`, un `Rejected`, un seul nouveau U/B et aucun User orphelin. Un retry de la même request lit l'outcome existant. Attach, Detach et Registration partagent un seul ordre autoritatif. La garantie « eventually Rejected » suppose que la policy de retry et l'infrastructure finissent par exécuter le perdant après rollback ; ce n'est pas une garantie temporelle sans hypothèse de disponibilité.

## 4. Carte du locking et de l'arbitrage actuels

| Chemin | Mécanisme exact, clé et moment | Transaction, durée, opérations sous protection | Invariant et concurrence |
|---|---|---|---|
| Bootstrap runtime du stream | `INSERT INTO external_identity_binding_streams(E,0) ON CONFLICT(E) DO NOTHING`, PK E ; puis `SELECT ... WHERE E FOR UPDATE` dans `ExternalIdentityBindingStreamJdbcRepository.lock` | `JpaExternalIdentityBindingAdapter.acquireWithInitializer`, transaction appelante `MANDATORY`. Le verrou explicite est acquis après l'insert idempotent et tenu jusqu'au commit/rollback, couvrant contrôle de l'actif, initializer, réservation B, insert actif, `advance`, append fact, puis éventuels writes Registration/outcome et terminalisation du claim. | Un stream unique, puis sérialisation de Acquire/Registration/Detach et allocation R. L'insert idempotent seul n'est pas le lock préalable. |
| Acquire / Attach | Même `streams.lock(E)` ; `hasActiveBinding(E)` non lockant ; `INSERT external_identities ... ON CONFLICT(E) DO NOTHING` ; `advance` par `UPDATE ... WHERE E AND current_revision=old` | Lock stream jusqu'au commit de l'appelant, y compris après retour de l'adapter. Réservation B par `INSERT occurrences ... ON CONFLICT(binding_id) DO NOTHING` (jusqu'à huit UUID) ; insert actif et fact dans la même transaction. | Deux Acquire, Acquire/Detach, absence de deux B actifs et R/fait unique. Le `advance` est déjà un CAS défensif, mais le lock rend sa perte anormale aujourd'hui. |
| Detach | Lecture `streams.find(E)` sans lock ; puis `streams.lock(E)` ; lecture exacte `findUserId(E,B)` ; `DELETE external_identities WHERE E AND B` ; `advance(E,old,old+1)` ; append fact | Transaction appelante `MANDATORY`, verrou stream jusqu'au commit. `NOT_CURRENT` ne change ni R ni faits. | Detach/Detach, Detach/Attach, stale B, continuité de R et du fact. |
| Command observe | `observeExact`: un `SELECT` non lockant `streams JOIN external_identities`, filtré par E+B, qui rend U+R | Transaction métier Command dans `TransactionalExecuteConsumptionUseCase` ; aucune protection Binding pendant AuthZ, Pot et append Event préparé. | Capture cohérente de l'occurrence précise et de R, sans autorité finale. |
| Command fence | `fenceExact`: `UPDATE streams s SET current_revision=s.current_revision WHERE E AND current_revision=R AND EXISTS(active E,U,B)` ; une row = succès, zéro = perte | Après travail métier, avant outcome/provenance/terminal claim, dans la **même** transaction Command. L'`UPDATE` prend son row lock PostgreSQL jusqu'au commit ; il ne modifie ni R ni fact. `BindingFenceRecoveryExecuteUseCase` ne relit l'autorité qu'après rollback dans une nouvelle transaction sous claim vérifié. | Command/mutation Binding : commit order établi au fence ; B obsolète → rollback puis rejet exact ; B encore courant → conflit technique/retry. Ce n'est pas un `SELECT FOR UPDATE` préalable. |
| Registration | `ExecuteRegistrationService.execute` appelle `acquireWithInitializer` ; l'initializer écrit User et `UserCreatedFact`, puis l'outcome est inséré | `RegistrationConsumptionLocator` appelle le service dans le callback de `TransactionalExecuteConsumptionUseCase`. Lock stream acquis par Acquire et tenu pendant User, B, Binding Fact, outcome, provenance et terminal claim CAS. Admission HTTP est une transaction distincte qui n'acquiert pas Binding. | Deux requests, Registration/Attach/Detach et absence de User orphelin ; claim perdu rollbacke les effets. |
| Binding Fact et revision | `occurrences.reserve`: PK B et unique `(E,attached_revision)` ; `facts.append`: `INSERT`, PK eventId et unique `(E,binding_revision)` ; `streams.advance`: expected R | Tous dans la transaction du writer sous lock stream actuel. | B non réassigné, R non dupliquée, un fait au plus par R ; les FK vérifient User, occurrence et stream. |
| CURRENT_BINDING | `JdbcCurrentBindingAdapter.apply`: `INSERT ... ON CONFLICT(E) DO UPDATE ... WHERE excluded.binding_revision > current.binding_revision`; à R égale, comparaison du payload, sinon stale | Transaction fenced du consumer de Binding Fact, distincte de la mutation WRITE. Aucun lock explicite du stream primaire. Le slot Consumption peut utiliser `FOR UPDATE`, sur **slot**, non sur E. | Projection monotone, duplicate idempotent, divergence à R égale signalée, retry/takeover. |

Recherche ciblée des sources `main` Binding/Registration/Command : aucun `pg_advisory_lock`, `PESSIMISTIC_WRITE` ou autre lock JPA d'autorité Binding. Les `FOR UPDATE` de `JpaConsumptionSlotRepository` concernent les claims et leases de Consumption ; ils restent une mécanique distincte. Les migrations V18/V20–V23/V27–V28 n'introduisent pas de trigger de verrouillage Binding.

**Nécessité prouvée vs choix courant.** Les invariants exigent un point de décision commun et un ordre transactionnel par E, notamment face au fence Command. Ils n'exigent pas un verrou obtenu **avant** lecture et décision. WA.6 a testé son choix `stream → authority` ; ses tests ne sont pas une preuve d'impossibilité d'un CAS tardif ou premier.

## 5. Timelines des transactions actuelles

```text
Acquire A : createIfAbsent(E) → FOR UPDATE stream E → actif absent
            → reserve B1 → insert active → R+1 → fact → COMMIT
Acquire B : createIfAbsent(E) attend si stream neuf, sinon FOR UPDATE attend A
            → actif présent → CONFLICT, aucun fait ni R

Command C : observe B1/R1 → travail Pot/Event → UPDATE no-op stream(E,R1,B1)
Detach D  : FOR UPDATE stream E attend C → DELETE B1 → R2/fact → COMMIT
            ou D gagne d'abord, C obtient zéro row et rollbacke tout.

Registration : claim acquis → Execute transaction → lock stream E → décision
             → U/B/faits/outcome → provenance → terminal claim CAS → COMMIT.
```

Le lock stream actuel peut donc couvrir une phase métier et le terminal claim, bien au-delà de la courte instruction SQL d'allocation R. Il évite la course en cours, mais augmente la durée de sérialisation de toutes les mutations de E.

## 6. Alternative optimiste retenue comme cible de preuve

Conserver le schéma d'autorité composite actuel. Dans `READ COMMITTED`, lire `(E,R,active U/B ou absent)` par un snapshot SQL. Préparer U/B en mémoire, puis tenter **avant les écritures métier** un unique `UPDATE` conditionnel du stream :

```sql
-- Acquire seulement si E est encore libre ; rendu R+1 = révision gagnante.
UPDATE external_identity_binding_streams AS s
SET current_revision = s.current_revision + 1
WHERE s.issuer = :issuer AND s.subject = :subject
  AND s.current_revision = :expected_r
  AND NOT EXISTS (SELECT 1 FROM external_identities AS a
                  WHERE a.issuer = s.issuer AND a.subject = s.subject)
RETURNING s.current_revision;

-- Detach seulement si le B observé est encore l'actif de la même révision.
UPDATE external_identity_binding_streams AS s
SET current_revision = s.current_revision + 1
WHERE s.issuer = :issuer AND s.subject = :subject
  AND s.current_revision = :expected_r
  AND EXISTS (SELECT 1 FROM external_identities AS a
              WHERE a.issuer = s.issuer AND a.subject = s.subject
                AND a.binding_id = :expected_b)
RETURNING s.current_revision;
```

Une row retournée est le point d'arbitrage. Le winner conserve le row lock interne de l'`UPDATE` jusqu'au commit, puis applique insert/delete actif, réserve B, écrit le fait et les autres effets dans **la même transaction**. Zéro row est un conflit de snapshot : rollbacker la tentative, recharger dans une transaction neuve et distinguer l'état métier réellement occupé/détaché d'une collision technique. Une exception SQL n'est pas un `CONFLICT` métier implicite. Overflow de R, échec d'insert, collision B épuisée, échec fact/outcome ou claim perdu rollbackent aussi l'incrément.

Pour un **rejet sans transition** sur E déjà occupée, la simple lecture ne suffit pas : Detach pourrait committer entre lecture et outcome. Utiliser dans la transaction de rejet un fence conditionnel no-op sur `stream(E,R)` avec `EXISTS(active E,B observé)` ; une row permet de committer `Rejected` et le terminal claim, zéro impose rollback/reload. Ce fence est une validation optimiste au point de décision, pas une transition : il ne crée ni R ni fait. Il peut naturellement retenir le row lock interne jusqu'au commit court.

Cette cible signifie « sans stratégie explicite lock-before-decide » dans Pocoma. PostgreSQL attend et verrouille nécessairement lors des `UPDATE`, de l'unicité et des FK sous MVCC ; l'objectif n'est pas « zéro lock physique ». Le CAS-first évite de produire des écritures candidates avant de savoir qui a gagné, tout en conservant le rollback atomique. Une variante qui écrit le candidat avant CAS peut être correcte si tout est rollbacké, mais augmente les collisions et ne sera pas la première implémentation proposée.

| Option étudiée | Verdict |
|---|---|
| PK/`ON CONFLICT` de la seule ligne active comme arbitre | Insuffisant pour Detach et l'ordre des faits : après suppression, elle ne porte plus R ni l'histoire de E. |
| `INSERT stream(E,0) ON CONFLICT DO NOTHING` seul | Assure une row E unique au bootstrap, mais ne décide pas quelle transition gagne à R ultérieure. À suivre par le CAS. |
| CAS sur le stream permanent + précondition d'actif et commit atomique | Retenu pour preuve : même point d'arbitrage pour Acquire, Detach, Registration et fence Command, sans nouveau store. |
| Fusion future R + état actif dans une seule row | Pourrait simplifier la précondition SQL, mais exigerait migration et cutover de l'autorité ; aucun manque concret du schéma actuel ne la justifie pendant ce refactoring. |

## 7. Acquire / Registration : autorité et collisions

L'autorité CAS est **la row permanente du stream E et sa R attendue** ; l'absence de row active à cette R est la précondition métier d'Acquire. Après CAS gagnant : invoquer l'initializer User seulement pour Registration, réserver B dans le registre historique, insérer l'unique row active E/U/B, insérer le fact `ATTACHED(E,U,B,R+1)`, puis l'outcome `Registered` si Registration. L'ordre exact des inserts après CAS doit respecter les FK existantes : User avant occurrence, occurrence avant actif et fact. Toute insertion active qui retourne zéro après un CAS gagnant est une violation de l'hypothèse « tous les writers CAS-first » : rollback et diagnostic, jamais succès silencieux. PK `(issuer,subject)` de l'actif empêche physiquement deux actifs ; PK B empêche la réutilisation ; unique `(E,attached_revision)` et `(E,fact_revision)` défendent l'histoire. Les contraintes sont des filets de sécurité, non des substituts à l'atomicité du writer.

Deux RegistrationRequests distinctes qui observent libre `E@R` peuvent préparer U1/B1 et U2/B2 en mémoire. W1 gagne CAS `R→R+1`, écrit U1/B1/faits/outcome/claim et committe. W2 attend l'`UPDATE`, obtient zéro après le commit W1 et **rollbacke toute sa transaction Execute** ; U2/B2 préparés n'ont jamais été durables. Le `Rejected` de W2 ne peut pas être écrit dans cette transaction rollbackée. Après rollback, un retry sous claim valide recharge request/outcome et l'autorité, valide l'actif par fence no-op, écrit `Rejected` et terminalise le claim dans un **nouveau** commit. Si W1 rollbacke, W2 peut gagner ; si W2 perd son claim, le nouveau worker reprend. Le classifier Registration actuel met `IllegalStateException`/`IllegalArgumentException` en `REGISTRATION_INVARIANT` terminal : une perte de CAS doit avoir un signal **typé retryable**, distinct de ces invariants et du rejet métier. Les tests doivent prouver le trajet complet via worker, pas seulement le service appelé deux fois.

Pour Acquire ordinaire, après perte de CAS, recharger : actif durable → `CONFLICT` après validation de cette autorité ; toujours libre à nouvelle R (par exemple Detach gagnant) → retenter avec la R neuve. Le nombre de retries et la policy de contention devront être bornés ; une limite épuisée est technique, jamais un `CONFLICT` métier fabriqué. Registration/Attach doivent utiliser **la même** primitive, pas deux CAS concurrents de sens différent.

## 8. Detach : cible distincte

`detach(E,B,R)` gagne seulement si stream encore R **et** B toujours actif. Après CAS gagnant, lire U exact de l'occurrence active, supprimer par `DELETE ... WHERE E AND B`, puis append `DETACHED(E,U,B,R+1)` dans le même commit ; zéro suppression après CAS gagnant est une violation et rollbacke. `NOT_CURRENT` n'avance pas R et doit être validé par fence no-op sur la R et l'état observés si une autre transition peut encore gagner avant sa décision.

| Course | Ordre voulu |
|---|---|
| Detach(B1) / Attach | Si B1 est actif, Attach valide un conflit avant Detach ou perd son fence et retente après Detach ; après Detach committé, Attach CAS sur la nouvelle R et réserve B2. Aucun overlap de deux actifs. |
| Detach(B1) / Detach(B1) | Un CAS `R→R+1` gagne et écrit un seul fact ; l'autre recharge et rend `NOT_CURRENT`, sans trou de R. |
| Detach(B1) / Registration | Rejet Registration validé avant Detach, ou Detach gagne puis Registration retente et peut créer U/B2. Aucun rejet basé seulement sur une lecture périmée. |
| Detach(B1) / Attach(B2) / Detach(B1) tardif | Le B1 tardif ne peut supprimer B2 ; equality B et R croissante ferment l'ABA. |

## 9. Command fencing après retrait du lock préalable

Le port `observeCurrentBinding(E,B)` fait un seul `SELECT` sans lock et rend U+R ; `fenceObservedBinding(E,U,B,R)` est l'`UPDATE` no-op conditionnel décrit en §4. Dans `ExecuteRecordedCommandService`, il intervient après dispatcher/append Event préparé, y compris pour rejet métier/expiration, puis avant outcome, provenance et terminal claim. `BindingFenceRecoveryExecuteUseCase` attend le rollback du travail perdu, revérifie claim + E/B en transaction neuve et publie soit le rejet non-oracle, soit un conflit technique retryable.

Le retrait du `FOR UPDATE` d'Acquire/Detach **ne simplifie pas** ce fence et n'introduit pas de nouvelle race si chaque mutation devient CAS-first sur **la même row stream** :

```text
C observe B1/R1 ; D CAS R1→R2 et committe Detach ; C fence(R1,B1)=0 → rollback Pot/Event.
C observe B1/R1 ; C fence(R1,B1)=1 ; D CAS attend C COMMIT → C commit sous B1 courant.
D CAS R1→R2 non committé ; C observe encore B1/R1 ; C fence attend D,
  puis voit R2 et échoue après le commit D.
C fence B1/R1 ; Attach/Registration attendent le stream ; elles ne peuvent
  rendre B1 obsolète avant le commit de C.
```

Le no-op UPDATE de Command est déjà une primitive optimiste de validation tardive avec row lock interne jusqu'au commit. La précondition décisive est que **tous** les writers Binding passent par le stream avant d'écrire l'actif ; un vieux writer mixé au cutover briserait cette preuve. Le B durable non réassignable empêche l'ABA même si U est le même après reattach. R est une version de fence interne, pas une précondition portée par la Command.

## 10. Stream absent et bootstrap concurrent

`createIfAbsent(E)` est une création idempotente de **row d'autorité**, tandis que `lock(E)` est l'acquisition d'un verrou pessimiste explicite ; les confondre masquerait le changement. L'alternative réutilise `INSERT ... VALUES(E,0) ON CONFLICT(E) DO NOTHING`, puis relit E et tente le CAS. Si aucun stream n'existe, W1 et W2 qui insèrent simultanément se rencontrent sur la PK : le perdant peut attendre le commit/rollback de la row de W1. Après commit gagnant de Registration/Attach, il lit R1 et l'actif ; après rollback W1, il peut créer sa propre row R0. Il n'y a jamais deux streams E durables. Cette attente de contrainte est acceptable ; elle n'est pas un `SELECT FOR UPDATE` préalable.

La timeline « W1 et W2 lisent tous deux R » se produit directement sur un stream déjà présent et détaché. Sur un stream initialement absent, l'insert concurrent peut sérialiser avant cette lecture ; c'est une variante plus forte du même arbitrage. Ne pas créer une ligne vide R0 durable séparément sans vérifier sa provenance : V22 traite les streams R0 vides historiques comme des cas nécessitant revue. La création normale doit rester dans la transaction de la transition gagnante ; les conflits/rollbacks n'en laissent pas une nouvelle durable.

## 11. Primitives et contraintes PostgreSQL

| Objet | Contraintes/usage actuels | Rôle optimiste proposé et sort du perdant |
|---|---|---|
| `external_identities` | PK `(issuer,subject)` depuis V9, unique `binding_id` et FK User V18, FK occurrence composite V22 ; delete exact E+B | Au plus un actif. `INSERT ... ON CONFLICT(E) DO NOTHING` zéro après CAS gagnant signifie writer hors protocole/invariant. Pas d'ABA car B n'est jamais réutilisé. |
| `external_identity_binding_streams` | PK E, `current_revision>=0`, R conservée après Detach (V20) | Arbitre `UPDATE ... WHERE E AND R=:expected AND état attendu`, affected rows 1/0. Un concurrent attend éventuellement puis perd sur R. Aucun `SELECT FOR UPDATE` nécessaire. |
| `external_identity_binding_occurrences` | PK B, unique `(E,attached_revision)`, FK stream et User (V21) | Réservation permanente ; collision UUID `ON CONFLICT(binding_id) DO NOTHING` génère un autre B, ou rollback si budget épuisé. Collision E/R après CAS = invariant technique. |
| `external_identity_binding_facts` | PK eventId, unique `(E,binding_revision)`, FK stream/User/occurrence, CHECK type/origin (V20–V23) | Un fait au plus par R ; insert avec la transition dans un commit. Une erreur de contrainte provoque rollback, jamais un rejet métier. Production append-only contrôlée par le writer et le guard architecture ; pas de trigger SQL d'immutabilité observé pour cette table. |
| Registration | `registration_requests` PK requestId + trigger immutable V27 ; `registration_outcomes` PK/FK requestId + CHECK Registered/Rejected + trigger immutable V28 ; `user_created_facts` PK eventId et uniques requestId/userId | Outcome et UserCreated uniques, mais aucun de ces objets n'arbitre E. CAS stream puis transaction Execute établissent l'unique gagnant E ; une collision requestId est technique/replay, pas un second Registration. |
| `CURRENT_BINDING` READ | PK E et upsert `WHERE excluded.binding_revision > current.binding_revision`, comparaison à R égale | Convergence du dernier fait ; ni autorité d'Acquire ni fence de Command. |

Le perdant d'un `UPDATE` conditionnel obtient `0 row`, pas nécessairement une exception. Une violation unique/FK produit une erreur SQL qui invalide la transaction PostgreSQL et impose rollback avant toute décision nouvelle. Deadlock `40P01`, serialization `40001`, timeout et commit incertain suivent une reprise technique. Le couple R monotone + B immuable empêche un cycle `attached(B1)→detached→attached(B1)` ; comparer seulement l'état `absent/présent` serait vulnérable à l'ABA. Le rapport ne propose **aucune migration** : les primitives sont possibles sur le schéma existant ; les preuves SQL pourraient révéler ensuite un besoin de contrainte supplémentaire, à instruire comme lot distinct.

## 12. Timelines Registration et deux fences

```text
R0 libre, stream présent :
W1 claim C1 ; read R0 ; candidate U1/B1 en mémoire ; CAS R0→R1 = 1
W2 claim C2 ; read R0 ; candidate U2/B2 en mémoire ; CAS attend W1
W1 écrit U1, occurrence B1, actif, UserCreated, Attached, Registered,
   provenance, terminal claim(C1) ; COMMIT
W2 CAS = 0 ; ROLLBACK de l'Execute(C2), aucun U2/B2/fait/outcome durable
W2 retry sous claim courant ou nouveau claim ; recharge R1+B1 ; fence de rejet
   sur R1+B1 ; écrit Rejected(C2), provenance, terminal claim(C2) ; COMMIT.
```

Si W2 arrive alors que l'actif existe, il ne crée aucun U : le fence de rejet à R/B fixe l'ordre avec un Detach éventuel. Si Attach gagne le CAS initial, Registration perd et suit la même reprise ; si Registration gagne, Attach revoit l'actif et retourne conflit. Si Detach gagne alors que Registration a lu B1, le fence de rejet perd et Registration retente sur l'état détaché. Si le rejet est validé avant Detach, il committe avant que Detach puisse gagner. Les barrières de test doivent couvrir les deux ordres, et pas seulement lancer deux threads simultanément.

## 13. Consumption fence et Binding fence

**Consumption fencing** protège le droit du worker à publier sous `slotId/claimId/lease` : `tryTerminalize` CAS termine le claim dans Execute ; claim perdu fait rollbacker le travail. L'acquisition/takeover de slot peut prendre `FOR UPDATE` sur `consumption_slots`, sans être un lock Binding. **Binding optimistic fencing** protège la version/occurrence de E, via CAS R et B. Les deux se composent : gagner Binding mais perdre le claim rollbacke U/B/R/faits/outcome ; garder le claim mais perdre Binding rollbacke la tentative puis permet une nouvelle décision. Aucun des deux fences ne remplace l'autre.

## 14. Choix EXECUTE et frontière de commit

Le runtime câble `TransactionalExecuteConsumptionUseCase(ExecuteConsumptionService, TransactionRunner)` ; le locator recharge la request pendant le callback. `ExecuteConsumptionService` exécute le métier, append la provenance, puis appelle `tryTerminalize`, le tout dans la transaction ouverte par le wrapper. Le succès Registration doit y inclure : CAS Binding, `users`, occurrence B, actif, Binding Fact, `user_created_facts`, `registration_outcomes`, provenance et terminal claim. Le rejet validé doit inclure son outcome, provenance et terminal claim. Si l'un échoue, aucun effet métier/outcome ne peut rester. La request admise avant 202 est, elle, une transaction antérieure indépendante.

`FinalizeConsumptionService` verrouille le claim **avant** le travail ; le passer à Registration changerait l'ordre et n'apporte aucune garantie manquante. Garder `EXECUTE` évite le chemin claim → Binding et conserve le rollback sur claim perdu. La perte du Binding CAS doit sortir du callback par une exception typée pour rollbacker l'Execute entier ; le retry/rejet nouveau s'effectue après cette sortie, pas dans la transaction PostgreSQL déjà perdante.

## 15. Graphe Maven et imports Registration

Arcs Maven directs pertinents au HEAD (les autres dépendances de `infra-persistence-jpa` et `runtime-web-api` sont hors de cet audit) :

```text
domain-user-identity
  ↑
engine-registration ──→ engine-consumption ──→ engine-core, domain-consumption
                                           ↑
orchestrator-consumption ─────────────────┘  (+ engine-projection-task)
supra-consumption-worker ──→ orchestrator-consumption
infra-persistence-jpa ──→ engine-registration, domain-user-identity,
                          engine-consumption, engine-core, ...
runtime-registration-consumption-worker ──→ engine-registration,
  orchestrator-consumption, supra-consumption-worker, infra-persistence-jpa,
  infra-tx-spring
runtime-web-api ──→ engine-registration, infra-persistence-jpa, ...
```

| Dépendance **directe** d'`engine-registration` | Classes `main` réellement utilisées | Évaluation |
|---|---|---|
| `domain-user-identity` | `ExternalIdentity`, `BindingId`, `PocomaUserId`, `User`, `UserAuthorityPort`, `ExternalIdentityBindingPort`, `BindingAcquireResult` | **Nécessaire** : contrats de domaine et autorité partagée. |
| `engine-consumption` | **Aucune** : recherche de tous les imports et noms Consumption sous `engine-registration/src/main/java` | **Inutile comme dépendance métier ; REMOVE.** Elle rend indirectement visible `engine-core`. |
| `engine-core` (aujourd'hui transitif, pas déclaré) | `TransactionRunner` importé dans `AdmitRegistrationService` | **Nécessaire directement** tant que l'admission garde son runner transactionnel ; ajouter l'arc direct lors du retrait de l'arc Consumption. |

## 16. Finding `engine-registration → engine-consumption`

Conclusion explicite : **`engine-registration → engine-consumption` est une dependency unused pour les classes de ce module.** Maven et `javac` acceptent les dépendances déclarées inutilisées ; ils acceptent aussi l'usage d'une classe fournie transitivement, donc les tests verts ne réfutent pas ce défaut. Aucun `maven-dependency-plugin`/`dependency:analyze` ni guard d'arc Registration n'est configuré dans les POM/tests inspectés ; les tests d'architecture vérifient des dépendances de classes et plusieurs guards WA.6, mais pas cette déclaration Maven précise. Un contrôle d'arc direct est à ajouter au gate de correction.

## 17. Graphe cible et sens des ports de persistence

La cible conforme aux conventions observées est (flèches = dépendances Maven) :

```text
engine-registration ──→ domain-user-identity
engine-registration ──→ engine-core (TransactionRunner)
infra-persistence-jpa ──→ engine-registration
runtime-registration-consumption-worker ──→ engine-registration,
  engine-consumption, domain-consumption, orchestrator-consumption,
  supra-consumption-worker, infra-persistence-jpa, infra-tx-spring
```

Le runtime importe déjà `ConsumptionKey`, services/transactions Consumption, locator/orchestrator et worker, et peut déclarer directement `engine-consumption` et `domain-consumption` au lieu de s'appuyer sur la transitivité de `orchestrator-consumption`. `engine-registration` ne doit recevoir ni key, claim, lease, locator, orchestration ni policy de retry Consumption. Sa documentation/commentaire « runs inside fenced Consumption Execute » est une connaissance de déploiement à rendre neutre lors du lot de frontière, sans déplacer le métier dans le runtime.

Le sens `infra-persistence-jpa → engine-registration` est **conforme** : les interfaces `RegistrationRequestStore`, `RegistrationOutcomeStore` et `UserCreatedFactPort` vivent dans `engine-registration`, leurs implémentations JDBC dans l'infrastructure ; `engine-registration` n'importe ni Spring, ni JDBC, ni JPA. `JdbcRegistrationDiscovery`, technique de Consumption, réside dans l'infrastructure et est assemblé par le runtime ; il ne remonte pas dans le moteur. Aucune anomalie de ports/adapters Registration autre que l'arc Maven inutilisé n'a été relevée.

## 18. Classification des preuves existantes

`INVARIANT` désigne la propriété à conserver ; `IMPLEMENTATION` désigne une assertion à remplacer, même si elle cohabite dans un test avec des assertions utiles.

| Scénario | Tests/guards existants | Classe et action au refactoring |
|---|---|---|
| Acquire/Acquire, B global, R | `JpaUserIdentityAuthorityAdapterPostgresTest.concurrentAcquiresHaveOneWinnerAndOneRevision`, `writerGeneratesPermanentGlobalBindingIdsAndExactDetachedFacts`, `concurrentSqlReservationsCannotClaimTheSameBindingId` | **INVARIANT** ; ajouter barrière « deux lectures R identique → un CAS » et cas stream absent. |
| Acquire/Detach, Detach/Detach, fact/rollback | Même classe : `simultaneousAttachAndDetachRemainSerializedOnTheStream`, `concurrentDetachesAdvanceOnlyOnceAndRetryKeepsTheSameHistory`, `failedDetachedFactRestoresActiveOccurrenceAndRevision`, `conflictRollbackAndFactFailureConsumeNoRevisionOrReservation` | **INVARIANT** pour résultat/R/faits/rollback ; remplacer les attentes de sérialisation par ordre au CAS. |
| Revision continuity/restart | `newWriterInstanceContinuesRevisionAfterDetach`, assertions R1/R2/R3 des tests Binding/Registration, migrations V22 | **INVARIANT** ; conserver R contigu des commits gagnants, aucune consommation par perdant. |
| Append-only facts et lock order | `Wa67BindingArchitectureTest.bindingFactsAreAppendOnlyAndBindingMutationsLockStreamBeforeAuthority` | Append-only **INVARIANT** ; `assertOrdered(..."streams.lock("...)` **IMPLEMENTATION**, à remplacer par guard CAS-first et ownership SQL. |
| Registration/Registration | `RegistrationAuthorityPostgresTest.twoConcurrentRequestsChooseOneWinnerWithoutAnOrphan` | **INVARIANT** ; renforcer par barrières W1/W2 même R, rollback perdant, retry vers Rejected et deux claims/outcomes. |
| Registration/Attach, Registration/Detach | `registrationAndAttachArbitrateThroughTheSameStream`, `registrationAndDetachAreOrderedByTheAuthoritativeStream`, `existingAttachRejectsWithoutCreatingUserAndDetachAllowsFreshOccurrence` | Résultats et atomicité **INVARIANT** ; libellé « même stream » reste pertinent, hypothèse de lock order **IMPLEMENTATION**. Ajouter les deux ordres déterministes de rejet/Detach. |
| Command vs mutation et late commit | `CommandConsumptionPostgresTest.detachCommitsDuringBusinessWorkAndLostFenceRollsBackMutation`, `detachWaitsAfterCommandFenceUntilCommandCommit`, `sameUserReattachDuringBusinessWorkLosesFenceWithoutFallback`, `otherUserReattachDuringBusinessWorkLosesFenceWithoutOracle`, `twoCommandsUnderSameBindingReachBusinessWorkBeforeEitherFence` | **INVARIANT** du fence et de l'ordre des commits ; conserver sous nouveau writer CAS-first. Les anciens noms `targetDetachedBeforeLock...`/`targetTechnicalRollbackReleasesBindingLock...` sont historiques, pas preuve d'un lock actuel Command. |
| Command exact B et retry technique | `targetStaleBindingIsRejectedAfterSameUserReattach`, `...OtherUserReattach`, `fenceDoesNotAdvanceRevisionOrAppendBindingFact`, `lostFenceWithStillCurrentExactBindingIsTechnicalRetryAndNeverPublicRejection` | **INVARIANT** ; aucun R dans l'enveloppe, aucun fact au fence. |
| Binding Fact late commit, READ, restart/retry/takeover | `BindingRuntimePostgresTest.lateCommitBeforeTheEphemeralCursorIsFoundOnTheNextScan`, `baselineFactsRebuildAttachedDetachedAndReattachedWithoutPrimaryBootstrap`, `technicalFailureRetriesAfterAReconstructedScanAndThenFinalizesIdempotently`, `lostClaimRollsBackProjectionBeforeWinnerAndMultipleWorkersConverge` et `CurrentBindingPersistencePostgresTest` | **INVARIANT** de projection/Consumption, indépendant de `FOR UPDATE` primaire ; ne pas transformer ces tests en assertions de writer. |
| Registration restart/retry/takeover/late worker | `RegistrationRuntimePostgresTest.workerDrainsTwoRequestsAndRestartCannotReplayTheWinner`, `technicalFailureRetriesWithoutBusinessOutcome`, `takeoverFencesStaleExecutionAndTwoWorkersConverge`, `workerThatBeganBeforeTakeoverCannotPublishAfterWinnerCommits` | **INVARIANT** ; compléter pour le nouveau conflit Binding retryable. |
| Bootstrap concurrence | `JpaExternalIdentityBindingLifecycleAdapterPostgresTest.createsARevisionZeroStreamIdempotentlyAndRequiresTheCallingTransaction` ; preuves de migration V20/V22 et WA.6G | **INVARIANT** d'unicité/provenance ; ajouter bootstrap neuf W1/W2 CAS sur base PostgreSQL. L'ancien bootstrap primaire READ est retiré et ne doit pas être réintroduit. |

## 19. Risques, limites et recommandations

1. **Cutover mixte interdit.** Tant qu'un writer Attach/Detach/Registration peut muter l'actif sans CAS-first sur le stream, la preuve Command et l'absence de deux décisions concurrentes ne tiennent plus. Déployer le changement comme un writer unifié ; évaluer arrêt/drain des anciens workers et rollback applicatif avant activation. Aucune migration destructive n'est envisagée.
2. **Décision négative à fencer.** Rendre `CONFLICT`, `NOT_CURRENT` ou `Rejected` après une seule lecture peut produire une décision périmée face à Detach. Le fence no-op de validation et les retries sont partie du modèle, pas une optimisation facultative.
3. **Transaction perdante.** Un CAS zéro, une violation unique ou un deadlock ne peuvent pas être convertis en outcome dans la transaction perdue. Le classifier actuel de Registration traite certains `IllegalStateException` comme terminal : le signal de conflit optimiste doit être typé et retryable. Ne jamais publier `Rejected` sur simple exception SQL.
4. **Contention et ordre des locks.** CAS-first réduit le temps avant arbitrage mais conserve un row lock interne jusqu'au commit Execute, qui inclut User/faits/outcome/claim ; il ne garantit pas une faible latence à E très contendue. Vérifier les courses avec Command qui tient des locks Pot, les erreurs SQL et les limites de retry. Aucun writer Binding ne doit prendre un lock Pot/claim avant le CAS E.
5. **Preuve dynamique encore à faire.** L'audit n'a exécuté ni nouvelle SQL ni test de concurrence du modèle cible. En particulier, vérifier en PostgreSQL `READ COMMITTED` la réévaluation des conditions après attente, les lignes affectées et les interactions `EXISTS`/`NOT EXISTS`. Si un scénario ne donne pas l'ordre spécifié, STOP et réviser la primitive ; ne pas supprimer le lock existant sur la seule base de ce rapport.

**Recommandation A — `REPLACEABLE`.** Remplacer le `FOR UPDATE` préalable par CAS-first du stream existant et fences conditionnels pour les décisions négatives, sans supprimer le fence Command ni les contraintes SQL. Conserver l'atomicité de `EXECUTE`. Aucun changement de schéma n'est requis par le modèle proposé ; une migration éventuelle ne sera décidée qu'après preuve d'un manque concret.

**Recommandation B — `REMOVE`.** Retirer l'arc direct `engine-registration → engine-consumption`, déclarer `engine-core` directement pour `TransactionRunner`, garder les ports Registration dans le moteur et le wiring Consumption dans le runtime. Ajouter un guard Maven d'absence d'arc et lancer le gate architectural à ce lot.

## 20. Plan d'implémentation éventuel, par petits lots

Chaque lot déclare sa frontière **avant** code selon la politique. Commandes canoniques ci-dessous depuis `app/` ; les tests PostgreSQL pertinents sont inclus par les slices, avec ciblage de classe possible pour boucle rapide. `architecture-tests` est un gate global aux changements de module/frontière, pas une slice locale. L'unique full reactor éventuel est réservé à la clôture d'une grande Wave ou à une restructuration Maven jugée large selon la politique ; la correction de l'arc seul ne déclenche pas automatiquement ce gate.

| Lot | Scope et invariant de sortie | Primary / secondary Maven slices | PostgreSQL et gate ; STOP/rollback |
|---|---|---|---|
| FIX.1 — primitive d'autorité | Introduire lecture snapshot E/R/actif, CAS attach/detach et fence conditionnel des décisions négatives dans le repository Binding, sans changer encore les callers. Définir signal typé `CAS_LOST` distinct du conflit métier. | BINDING `./mvnw -pl runtime-binding-consumption-worker -am test` ; COMMAND secondaire seulement si le port/fence partagé change. | Tests SQL à deux connexions : CAS 1/0, attente, rollback gagnant, absent stream, `NOT EXISTS`/`EXISTS` après attente. **STOP** si une condition gagne sur un état obsolète ; garder l'ancien writer. |
| FIX.2 — Acquire/Detach unifiés | Faire CAS-first dans `acquire`, `acquireWithInitializer`, `detach` ; B neuf, actif unique, R contigu et fact exact dans un commit. Valider `CONFLICT`/`NOT_CURRENT` à R avant retour. | BINDING primary ; COMMAND `./mvnw -pl runtime-command-consumption-worker -am test` et Registration `./mvnw -pl runtime-registration-consumption-worker -am test` secondary car les deux consomment le writer partagé. | PostgreSQL : acquire/acquire, acquire/detach, detach/detach, détachement tardif B1, collision UUID, rollback User/fact/stream, bootstrap W1/W2. **STOP** si double autorité, trou R ou fait absent ; retour au writer locké unifié avant exposition. |
| FIX.3 — fence Command | Conserver `observe/fence` et prouver sa composition avec le writer CAS-first ; n'éditer Command que si un test démontre un défaut. | COMMAND primary ; BINDING secondary si correction du contrat de stream. | Barrières Command/Detach et Command/Attach dans les deux ordres, commit tardif, même/autre U, lost fence, deux Commands B stable, no-op R/faits. **STOP** si une Command sous B périmé committe. |
| FIX.4 — Registration et classifier | Traiter CAS perdu comme conflit technique retryable après rollback Execute ; nouvelle transaction/claim pour `Rejected` validé ; conserver U/B/faits/outcome/claim atomiques. | `./mvnw -pl runtime-registration-consumption-worker -am test` primary ; BINDING secondary si le writer partagé change encore. | Barrières deux requests même R, Registration/Attach/Detach deux ordres, rollback candidat, restart, takeover et stale claim. **STOP** si User orphelin, outcome manquant/double ou `FAILED` terminal sur contention ordinaire. |
| FIX.5 — retrait du lock et guards | Après preuves FIX.2–4, retirer `streams.lock`/`FOR UPDATE` Binding et assertions d'ordre de lock ; garder `FOR UPDATE` des slots Consumption et le fence `UPDATE` Command. | BINDING primary ; COMMAND et Registration secondary si leur comportement partagé est ajusté. | Search production ciblée + tests SQL précédents ; gate `./mvnw -pl architecture-tests -am test` car garde de frontière/SQL modifié. **STOP** si un writer Binding contourne le CAS ou si un guard de propriété disparaît sans remplacement. |
| FIX.6 — frontière Maven | Remplacer l'arc inutilisé par `engine-core` direct ; déclarer au runtime ses dépendances Consumption réellement importées ; guard d'arc direct, aucun déplacement JDBC/JPA. | `./mvnw -pl runtime-registration-consumption-worker -am test` primary ; `./mvnw -pl runtime-web-api -am test` secondary pour le consommateur de l'admission. | Gate global `./mvnw -pl architecture-tests -am test` **requis** par changement de dépendances. **STOP** si le runtime ne compile qu'avec une dépendance transitive cachée ou si le moteur gagne un import Consumption. |
| FIX.7 — clôture de correction | Rejouer les slices matériellement touchées et revoir facts/READ ; fermer de nouveau Wave 2 seulement après les preuves transversales. | Composer BINDING, COMMAND, Registration et WEB selon l'impact réellement observé ; commandes canoniques ci-dessus. | Global architecture requis car arcs/guards changés ; `./mvnw test` **requis à la clôture de cette Wave 2 rouverte**, comme gate majeur, jamais comme boucle locale. Aucun replay historique indépendant sauf changement de sémantique d'upgrade. **STOP** sur crossing de slice non déclaré ; réviser d'abord la frontière. |

## 21. Vérification de cet audit et état du dépôt

Méthode exécutée : `git fetch origin`, baseline Git, `rg` ciblés sur locks/imports/tests, lecture des sources Java `main`, des POM, des migrations V9/V18/V20–V23/V27–V28 et de la documentation citée. Aucun code, aucune migration et aucun test Maven n'ont été modifiés ou exécutés pour cet audit. Aucun crossing de slice n'a eu lieu ; gate global **non requis** pour le seul rapport ; full reactor **non exécuté** car il ne prouverait aucune propriété nouvelle de cet audit documentaire. Vérification finale : liens documentaires locaux valides, index limité au rapport et `git diff --cached --check -- docs/steps/current/REGISTRATION/Wave2_Optimistic_Binding_and_Module_Boundary_Audit.md` **PASS**. Le commit ne contient pas le PNG utilisateur non suivi.
