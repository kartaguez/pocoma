# Exécution transactionnelle du moteur de consommation

## Garantie

Le protocole livré garantit qu'aucun effet métier d'une exécution ayant perdu son `Claim` ne peut être
committé. L'unique autorité de commit est le fencing relationnel suivant :

```sql
status = 'PENDING' AND current_claim_id = :claimId
```

`leaseUntil` détermine uniquement si un autre worker peut effectuer un takeover. Son expiration ne
retire pas à elle seule l'autorité du Claim courant. `revision` reste une information d'optimistic
locking et de diagnostic, jamais un fencing token.

## Frontière transactionnelle gagnante

Les wrappers propriétaires Acquire/Execute/Finalize/HandleFailure utilisent un `TransactionRunner`
gardé : aucune transaction ni synchronisation ambiante ne peut exister à leur entrée. REQUIRED
ouvre alors la frontière physique attendue. Les services internes sont des participants.
`TransactionalExecuteConsumptionUseCase` ouvre la transaction englobante avec ce runner.
Le callback spécialisé et les adapters JPA appelés ensuite rejoignent cette transaction. Les
adapters d'écriture conservent une propagation `MANDATORY` et ne peuvent donc pas créer une
transaction autonome.

```text
BEGIN
  relire l'objet durable autoritatif
  exécuter le use case métier
  écrire les effets métier rollbackables
  écrire Events, Tasks, projections et outbox
  écrire ConsumptionInput
  écrire ConsumptionResult
  CAS final sur currentClaimId
  clore le Claim
COMMIT
```

Le CAS final est exécuté après les écritures métier et la provenance. Il doit modifier exactement
une ligne. Le suffixe commun de `FinalizeConsumptionService`, utilisé aussi par Execute, les échecs
terminaux et les recoveries, appelle le lifecycle : CAS zéro lève `LostClaimException` ;
l'exception traverse le wrapper et provoque le rollback de toute la transaction. Les écritures JPA
sont flushées par la barrière du repository avant le CAS natif. La clôture conditionnelle exige
exactement une ligne ; son refus ou son erreur SQL lève `ConsumptionClaimClosureException` et
annule aussi le CAS. Seule cette clôture suit le CAS ; aucun callback métier ensuite.
Les effets ne deviennent durables qu'au commit commun.

## Finalisation commune et callbacks

SUCCESS, REJECTED et FAILED suivent : effet SQL provisoire → provenance éventuelle validée → flush
complet → CAS S/PENDING/C → clôture conditionnelle C → commit. Aucun prélock terminal du slot.
Les callbacks peuvent être invoqués stale ; toutes leurs écritures doivent participer à la même
transaction et rollbacker si une autorité nécessaire est perdue. Acquire/Takeover conserve son
ordre slot verrouillé par clé → claims, sans publication métier.

Les wrappers Finalize/HandleFailure traduisent LostClaimException seulement après rollback physique.
Execute la propage. APPLIED public exige COMMITTED confirmé. Les erreurs de résolution distinguent
ROLLED_BACK, COMMITTED et UNKNOWN ; COMMITTED/UNKNOWN ne déclenchent pas une nouvelle failure
métier automatique. Une erreur SQL pré-CAS n'est normalisée qu'après rollback et relecture fraîche
prouvant la perte d'ownership ; l'erreur du claim courant reste une erreur.

La recovery Binding ouvre sa frontière après rollback de l'Execute initial, relit le Binding exact
et appelle un Execute participant sans prélock S. Binding encore présent reste un conflit technique ;
absence de l'occurrence exacte permet le rejet CALLER_IDENTITY_NOT_CURRENT, sans rejouer Pot.
Les autorités Pot et Binding gardent leurs positions propres.

Les preuves initiales WP2 sont rattachées au [rapport WP2](../plans/2026-10-16-consumption-generic-transactional-protocol-wp2-implementation.md). Les [preuves WP4](../plans/2026-10-16-consumption-generic-transactional-protocol-wp4-concurrency.md) approfondissent les files FK/unicité/takeover ; la [clôture bornée WP6](../plans/2026-10-16-consumption-generic-transactional-protocol-wp6-closure.md) fixe les limites finales.

## SUCCESS

Le callback retourne `BusinessConsumptionOutcome.Success`. Les inputs et résultats réellement
utilisés sont persistés, puis le slot passe à `DONE/SUCCESS` et le Claim est clos avec `SUCCESS` dans
la même transaction.

## REJECTED

Le callback retourne `BusinessConsumptionOutcome.Rejected(rejectionCode)`. Il s'agit d'une décision
métier valide : les effets prévus et la provenance sont committés, le slot passe à `DONE/REJECTED`
avec un `terminalReason` construit depuis le code de rejet, et aucune failure technique n'est
enregistrée. La raison reste un code générique : le moteur ne connaît pas sa signification métier.

## Échec technique

Une exception du callback sort de `TransactionalExecuteConsumptionUseCase`. La transaction métier
est d'abord entièrement rollbackée : effets, outbox, inputs, résultats et éventuelle terminalisation.
Seulement après ce rollback, la couche spécialisée construit un `ProcessingFailure` et appelle
`TransactionalHandleConsumptionFailureUseCase`, qui ouvre une nouvelle transaction courte.

La policy décide alors :

- `RETRY_AFTER` : le Claim est clos, le slot reste `PENDING`, sans Claim courant, avec son
  `nextClaimAt` et sans raison terminale ;
- `FAIL` : le Claim est clos et le slot devient `DONE/FAILED`, avec le code précis du
  `ProcessingFailure` comme `terminalReason`. Sa catégorie reste réservée à la décision de policy.

Le code, la catégorie, le message et l'horodatage du `ProcessingFailure` restent dans l'historique
du Claim. Seul son code est repris comme raison sur un slot `FAILED`.

Pour Command, la classification est volontairement conservatrice. `DEADLOCK`,
`SERIALIZATION_FAILURE`, `DATABASE_UNAVAILABLE`, `TIMEOUT` et les autres erreurs JDBC explicitement
transitoires suivent le calendrier générique de retry. Payload invalide, type inconnu, Command
absente, invariant technique, erreur de configuration et `RuntimeException` inconnue conduisent
immédiatement à `DONE/FAILED` avec leur code précis. Une erreur inconnue n'est jamais supposée
transitoire.

Si le Claim a été remplacé avant cette seconde transaction, le résultat est `LOST_CLAIM` et ni le
slot ni le Claim gagnant ne sont modifiés. `LostClaimException` issu du CAS final n'est pas une
failure technique : il ne doit pas être classifié ni soumis à la policy.

## Effets externes

Le callback ne peut protéger que les écritures rollbackables de la base PostgreSQL principale. Un
appel HTTP, un email, une écriture dans une seconde base ou tout autre effet irréversible ne doit pas
être exécuté directement. L'exécution gagnante produit à la place une Task ou une ligne d'outbox dans
sa transaction ; cette sortie sera livrée et consommée ultérieurement avec son propre
`ConsumptionSlot`. Un callback `runAfterCommit` ne constitue pas une garantie de livraison.

## Frontière du protocole livré

Le chemin `ExecuteConsumptionUseCase` ne dépend pas d'un execution guard séparé et ne cumule jamais
deux mécanismes de fencing. Un `ConsumptionKey` est toujours formé d'une `ConsumableIdentity` et
d'une `ConsumerIdentity` explicites. Le slot est identifié par `slotId` et son propriétaire courant
par `ClaimId`; le Claim ne transporte ni copie de la clé ni token de compatibilité. Les transitions
de slot exigent le Claim courant lorsqu'elles modifient son ownership ou le terminalisent.

## Spécialisation Command

`supra-consume-command` possède la convention de clé, la discovery, l'adaptation du résultat
Command et la classification technique. Il réutilise sans duplication
`SequentialConsumptionOrchestrator`, `TransactionalExecuteConsumptionUseCase` et le failure handler
générique. Il ne recharge ni ne décode une Command avant l'acquisition.

Dans la transaction gagnante, `JpaPotCommandEventAppendAdapter` convertit les Events Pot typés et
les insère dans l'outbox avec une propagation `MANDATORY`. Un rejet n'appelle jamais ce port. Le CAS
final fence donc ensemble mutation métier, Events et provenance. Le runtime
`runtime-command-consumption-worker` compose ce supra avec la boucle générique.

## Spécialisations CURRENT

`domain-consumption` porte uniquement les primitives et le protocole générique explicite.
`engine-consumption` porte acquire/execute/finalize et la gestion transactionnelle des échecs.
`orchestrator-consumption` coordonne une tentative ; `orchestrator-poll-consumption` répète le
traitement. Les supras Command, Event, ProjectionTask, Binding, Results, Registration et LKV adaptent
leurs consumables propres sans réintroduire de protocole parallèle. Les effets externes restent
produits comme Task ou ligne d'outbox transactionnelle avant leur livraison indépendante.

## Surface lifecycle consolidée

Le port participant expose seulement `acquire`, `terminalize` et `scheduleRetry`. Le retrait WP5
supprime `lockCurrentClaim`, `tryTerminalize`, l'ancien `handleFailure` de persistance et le
repository `findByIdForUpdate`. Le failure use case conserve sa policy puis appelle le même
finalizer ou la transition retry ; aucun booléen de perte n'est laissé à interpréter après effet.
`findByKeyForUpdate` reste réservé à Acquire/Takeover. Les locks stream Binding et CAS Pot
restent des autorités distinctes, avant le suffixe Consumption.

Les constructeurs sans provenance restent utilisés par Event/Projection et les chemins failure
sans provenance ; ils rejettent une liste non vide en l'absence d'adapter. Ils ne possèdent aucun
CAS alternatif. Les wrappers owner et les holders participants ont des responsabilités distinctes
et restent nécessaires aux frontières transactionnelles.

## Ordre partiel des acquisitions et flush

Acquire/Takeover conserve `S FOR UPDATE par clé → invalider/insérer C → installer C → commit`.
Il ne réclame aucune publication métier sous ce verrou. Le préfixe Command conserve
`CAS Pot → metadata/DML temporel → Events saveAllAndFlush → fence Binding exact → outcome strict → terminal Event`.
Le fence Binding de Command est `SELECT stream FOR SHARE`, suivi d'un SELECT distinct validant
révision, utilisateur et occurrence exacte après l'éventuelle attente. Il rejoint la même TE via
`MANDATORY` et garde le verrou au commit/rollback ; `clearAutomatically` ne le libère pas.
Acquire/Detach gardent le stream exclusif `FOR UPDATE`.
Voir la [preuve d'intégration 2C](Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md).
Registration conserve `stream Binding → initializer User/fact → occurrence/actif/fact → outcome`.
Les matérialiseurs conservent leur UPSERT ou INSERT/compare ; Projection publie root avant artifacts.
Tous convergent ensuite vers provenance/flush/CAS S/clôture C. Il s'agit d'une relation partielle,
aucune hiérarchie totale entre ressources indépendantes n'est imposée.

Les FK de provenance peuvent prendre KEY SHARE sur S avant le CAS ; cela ne constitue pas un
prélock d'élection. Le CAS non-clé et cette FK sont compatibles dans les entrelacements testés.
Un takeover FOR UPDATE peut attendre les détenteurs FK ; les files avec deux détenteurs et un
acquéreur ont été éprouvées avec résolution commit/rollback au WP4. L'unicité peut sérialiser des
publications avant même le CAS. Aucun de ces constats ne prouve l'absence générale de deadlock.

`flushAutomatically=true` du repository Slot envoie les écritures JPA en attente avant UPDATE ;
`clearAutomatically=true` détache ensuite les entités sans commit ni libération des verrous.
Le flush de clôture ne doit plus découvrir d'écriture métier. Les callbacks avant commit/listeners
futurs ne peuvent ajouter du DML métier après cette barrière sans revoir et éprouver ce contrat.
Une contrainte différée peut encore refuser le commit après CAS et clôture réussis.
