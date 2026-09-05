# Exécution transactionnelle du moteur de consommation

## Garantie

Le chemin cible garantit qu'aucun effet métier d'une exécution ayant perdu son `Claim` ne peut être
committé. L'unique autorité de commit est le fencing relationnel suivant :

```sql
status = 'PENDING' AND current_claim_id = :claimId
```

`leaseUntil` détermine uniquement si un autre worker peut effectuer un takeover. Son expiration ne
retire pas à elle seule l'autorité du Claim courant. `revision` reste une information d'optimistic
locking et de diagnostic, jamais un fencing token.

## Frontière transactionnelle gagnante

`TransactionalExecuteConsumptionUseCase` ouvre la transaction englobante avec `TransactionRunner`.
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
une ligne. S'il ne modifie aucune ligne, `ExecuteConsumptionService` lève `LostClaimException` ;
l'exception traverse le wrapper et provoque le rollback de toute la transaction. Les écritures JPA
sont flushées avant le CAS natif, mais ne deviennent durables qu'au commit commun.

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

## Frontière legacy

Le nouveau chemin `ExecuteConsumptionUseCase` ne dépend pas d'`engine-execution-guard` et ne cumule
jamais les deux mécanismes de fencing. Le module guard et les APIs basées sur `ClaimToken` restent
présents uniquement pour maintenir les workers historiques compilables. Les workers Command et Task
utilisent encore le guard ; le worker Event reste sur son orchestration actuelle.

## Spécialisation Command

`locator-consumption-command` possède la convention de clé, la discovery, l'adaptation du résultat
Command et la classification technique. Il réutilise sans duplication
`SequentialConsumptionOrchestrator`, `TransactionalExecuteConsumptionUseCase` et le failure handler
générique. Il ne recharge ni ne décode une Command avant l'acquisition.

Dans la transaction gagnante, `JpaPotCommandEventAppendAdapter` convertit les Events Pot typés et
les insère dans l'outbox avec une propagation `MANDATORY`. Un rejet n'appelle jamais ce port. Le CAS
final fence donc ensemble mutation métier, Events et provenance. Le worker Command qui appellera ce
locator reste réservé au Lot 6.7.

## Responsabilités du Lot 4

Le Lot 4 devra assembler les wrappers transactionnels dans les runtimes, adapter les couches
spécialisées pour produire `ConsumptionExecutionResult`, migrer successivement les workers Command,
Event et Task, classifier leurs exceptions techniques hors de la transaction métier, puis supprimer
les appels au guard et les APIs `ClaimToken` devenues sans consommateur. Il devra aussi vérifier que
tous les effets externes des workers passent par une Task ou une outbox.
