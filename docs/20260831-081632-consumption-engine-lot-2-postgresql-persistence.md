# Lot 2 — Persistence PostgreSQL du moteur de consommation

## Résumé

Implémenter la persistence durable du nouveau modèle et ses opérations atomiques, sans toucher aux
workers, aux transactions métier ni à `engine-execution-guard`.

Le lot est découpé en six lots vérifiables :

1. alignement de `Claim` et contrats persistence cibles ;
2. migration PostgreSQL et mappings JPA ;
3. création paresseuse et acquisition atomique ;
4. terminalisation, failure handling et abandon ;
5. persistence de provenance ;
6. tests PostgreSQL concurrents et fermeture architecturale.

## 1. Contrats cibles et ajustements du domaine

### `Claim` basé sur `slotId`

- Ajouter `slotId` comme rattachement obligatoire du `Claim`.
- Les nouvelles factories prennent `slotId`; `attemptNumber` n'est jamais fourni par le use case
  d'acquisition.
- La persistence ne stocke aucune `ConsumptionKey` dans `consumption_claims` : la seule FK
  relationnelle est `slot_id`.
- Conserver temporairement l'accès legacy à `ConsumptionKey` comme donnée dérivée du slot et
  dépréciée, uniquement pour maintenir les anciens processing engines compilables.
- `ClaimToken` reste déprécié et n'apparaît dans aucun nouveau port, adapter JPA, repository ou test
  PostgreSQL.

### Nouveaux ports

Créer dans `engine-consumption` des contrats distincts du `ClaimPort` legacy :

- `ConsumptionLifecyclePersistencePort` : acquisition, terminalisation par CAS, failure handling et
  abandon ;
- `ConsumptionQueryPort` : lecture des slots et de l'historique des Claims ;
- `ConsumptionProvenancePersistencePort` : écriture et lecture des inputs et résultats.

`tryTerminalize` retourne `true` uniquement si l'UPDATE de fencing a affecté exactement une ligne.
Zéro ligne signifie perte d'autorité ; plus d'une ligne est une corruption.

Les services cibles d'acquisition, failure et abandon utilisent ces ports. La policy reste pure. La
primitive de terminalisation ne reçoit pas de transaction autonome : elle rejoindra la transaction
métier au Lot 3.

### Compatibilité

- Marquer `ClaimPort`, `TryAcquireConsumptionResult`, les inputs/use cases utilisant `ClaimToken` et
  les services associés comme legacy.
- Isoler toute conversion `ClaimToken -> ClaimId` dans la compatibilité.
- Aucun nouveau code métier ou JPA ne dépend de cette compatibilité.
- Ne modifier aucun module `supra-worker-command/event/task`.

## 2. Schéma PostgreSQL et mappings JPA

Créer `V4__consumption_engine.sql` dans `infra-persistence-jpa`.

### `consumption_slots`

Le slot contient son UUID, les quatre parties structurelles de `ConsumptionKey`, la révision, le
dernier numéro de tentative, le lifecycle, le Claim courant et les dates. La clé est représentée par
le quadruplet `(consumable_type, consumable_components::jsonb, consumer_type,
consumer_components::jsonb)` protégé par une vraie contrainte UNIQUE. Les composants sont des
tableaux JSON ordonnés.

Les checks couvrent les formes JSON, les compteurs, les dates et les invariants `PENDING/DONE`. Un
index `(status, next_claim_at)` est ajouté.

### `consumption_claims`

Le Claim contient `claim_id`, `slot_id`, `attempt_number`, le worker, les dates, la failure et la
raison de fin. Le schéma impose notamment :

- FK du Claim vers le slot ;
- `UNIQUE(slot_id, attempt_number)` et `UNIQUE(slot_id, claim_id)` ;
- lease strictement postérieur à l'acquisition ;
- cohérence des formes ouvertes, terminées, invalidées et des failures ;
- FK composite `(slot_id, current_claim_id)` du slot vers le Claim, interdisant un Claim d'un autre
  slot.

### Provenance

Créer `consumption_inputs` et `consumption_results`, tous deux rattachés au seul `slot_id`, avec les
checks de versions et de triplet subject. Les quatre entités JPA gardent des UUID explicites, sans
relations bidirectionnelles ni cascades implicites.

Aucune reprise de données n'est nécessaire, puisqu'il n'existe aucune table de consommation.

## 3. Création paresseuse et acquisition atomique

`JpaConsumptionLifecycleAdapter` exécute, dans la transaction appelante :

1. `INSERT ... ON CONFLICT DO NOTHING` du slot initial (`next_claim_at = created_at = now`) ;
2. lecture par clé avec `SELECT ... FOR UPDATE` ;
3. retour `ALREADY_DONE`, `NOT_READY` ou `BUSY` lorsque nécessaire ;
4. invalidation `TAKEN_OVER` du Claim courant seulement si `lease_until <= now` ;
5. incrémentation du compteur sous verrou ;
6. insertion du nouveau Claim ;
7. installation atomique de `current_claim_id` et incrément de révision ;
8. retour `ACQUIRED`.

L'expiration seule ne produit aucune écriture et ne retire pas l'autorité de l'ancien Claim.

## 4. Mutations clôturées par fencing

La terminalisation SUCCESS/REJECTED utilise obligatoirement un UPDATE conditionnel sur
`slot_id`, `status=PENDING` et `current_claim_id=:claimId`. Le nombre de lignes est vérifié, puis le
Claim est terminé dans la même transaction. Aucun `save(slot)` ni CAS fondé seulement sur la
révision ne sert de fencing.

Le failure handling vérifie le même fencing, termine le Claim avec sa failure puis applique la
décision :

- `RETRY_AFTER` : `PENDING`, Claim courant nul, échéance `now + duration` ;
- `FAIL` : `DONE/FAILED`, Claim courant nul, `done_at=now`.

L'abandon verrouille le slot, invalide l'éventuel Claim courant avec `ABANDONED`, puis termine le
slot en `DONE/ABANDONED`.

## 5. Persistence de provenance

`JpaConsumptionProvenanceAdapter` insère par lot et relit de façon déterministe les inputs et
résultats par `slotId`. Il ne démarre aucune transaction propre. Le Lot 2 vérifie leur rollback ; le
Lot 3 les placera dans la transaction métier gagnante.

## 6. Tests PostgreSQL réels

Ajouter Testcontainers PostgreSQL, le driver et Flyway aux tests de `infra-persistence-jpa`.

Les tests couvrent : création et acquisition concurrentes, expiration sans takeover, takeover et
fencing du stale Claim, allocation monotone des tentatives, backoff/NOT_READY, ALREADY_DONE,
failure retryable et terminale, failure stale, abandon et courses avec abandon, ainsi que plusieurs
consommateurs structurels d'un même Event.

Des insertions SQL directes vérifient les contraintes : clé dupliquée, tentative dupliquée, Claim
courant d'un autre slot, lifecycles ou Claims incohérents, failures partielles et provenance invalide.

La validation finale exécute les tests ciblés, les tests PostgreSQL, les tests d'architecture, tout le
réacteur Maven et `git diff --check`.

## Limites explicites

- Aucun worker Command/Event/Task n'est migré ou modifié.
- Aucun effet métier n'est encore placé dans la transaction du CAS final.
- Aucun input/résultat n'est encore produit par les use cases métier.
- `engine-execution-guard`, les statuts legacy et les outbox restent inchangés.
- Aucun heartbeat n'est ajouté.
- `revision` reste diagnostique/optimiste ; seul `current_claim_id` fence le commit.
