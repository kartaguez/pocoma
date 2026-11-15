# Discovery A2.0 — Audit de simplification du modèle Consumption

Date : 2026-10-21. Branche : `feat/consumption-transaction-protocol`.

**Statut : diagnostic de conception, AUCUNE implémentation ni migration.** Prolonge [A1](Discovery_A1_Contract_2026-10-21.md) : flux mono-famille `(eventFamily, consumptionId, partitionId)`, slots indépendants, CAS transactionnel du checkpoint. Activation production toujours NON AUTORISÉE.

## Observations vérifiées dans les sources

1. `V4__consumption_engine.sql` : l'unicité de `consumption_slots` porte sur `(consumable_type, consumable_components JSONB, consumer_type, consumer_components JSONB)`. Le slot conserve `slot_id`, `status`, `terminal_outcome`, `revision`, `last_attempt_number`, `current_claim_id`, `next_claim_at`, timestamps. `consumption_claims` protège tentatives et leases ; `consumption_inputs` et `consumption_results` ont des FK vers `slot_id`.
2. `JpaConsumptionSlotRepository` utilise la clé composite JSONB pour `INSERT ... ON CONFLICT DO NOTHING`, les recherches et `FOR UPDATE`, mais les transitions `installClaim`, `terminalize`, `scheduleRetry` ciblent déjà `slot_id` avec `current_claim_id`/état. La simplification des identités ne justifie **aucune suppression du fencing**.
3. `FinalizeConsumptionService` applique effet durable, `appendInputs`, `appendResults`, puis `terminalize` ; `ExecuteConsumptionService` lui transmet ces collections. `JpaConsumptionProvenanceAdapter` persiste les collections et expose `findInputs`/`findResults`. Une recherche des appels repère ces lectures notamment dans des tests et dans l'interface/adaptateur ; **absence de lecteur métier exhaustivement prouvée : NON** (recherche de code, pas audit de toute la fermeture transitive).
4. `V6__consumption_terminal_reason.sql` complète la raison terminale des slots, avec contraintes distinctes pour SUCCESS, REJECTED, FAILED et ABANDONED. Les résultats métier publics (CommandResult, RegistrationResult) sont d'autres autorités, à ne pas confondre avec ces outcomes techniques.
5. Plusieurs parcours existants hors flux E (RecordedCommand, RegistrationRequest/Result, ProjectionTask) emploient le contrat générique des slots ; une modification globale de table exige un inventaire de ces consommateurs, leurs migrations et tests. Ne pas supposer que tous deviennent des flux E au même moment.

## Hypothèses de simplification proposées, NON encore décidées

| Objet | Cible candidate pour les nouveaux flux E | Précaution / preuve avant suppression globale |
|---|---|---|
| `consumer_type + consumer_components` | `consumption_id UUID` identifié par un registre immuable, mono-famille et génération de règles | Enlever les anciens champs seulement après migration de tous les consumers et vérification des requêtes SQL, index, probes, runbooks |
| `consumable_type + consumable_components` | `event_id UUID` et famille portée par le registre du flux ; clé naturelle `UNIQUE(consumption_id,event_id)` | Les sources non-E ont encore besoin d'identités propres, pas de réécriture forcée |
| Provenance `consumption_inputs/results` | Option : plus d'écriture systématique pour les flux E ; tracer la cause par `slot_id`/EventRef quand utile | Auditer producteurs et tous lecteurs métier, support/diagnostic, réparation, preuves E2E et rétention ; conserver les anciens historiques jusqu'à décision de migration explicite |
| `terminal_outcome/terminal_reason` | Conserver **les informations** SUCCESS/REJECTED/FAILED/ABANDONED et raisons ; éventuellement simplifier leurs types Java/API | Le diagnostic terminal, les échecs et le no-op réussi restent distinguables |
| `consumption_claims`, CAS/fencing, revision/retry | Préserver | Essentiels à la sécurité transactionnelle, aux reprises et au stale-worker rejection |

Le schéma minimal illustratif **pour E seulement**, non une migration validée :

```sql
-- Candidat logique ; la définition du flux porte event_family et partitionCount.
consumption_slots_e (
  slot_id UUID PRIMARY KEY,
  consumption_id UUID NOT NULL,
  event_id UUID NOT NULL,
  partition_id INTEGER NOT NULL,
  status TEXT NOT NULL,
  terminal_outcome TEXT,
  terminal_reason TEXT,
  revision BIGINT NOT NULL,
  last_attempt_number INTEGER NOT NULL,
  current_claim_id UUID,
  next_claim_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  done_at TIMESTAMPTZ,
  UNIQUE (consumption_id, event_id)
);
```

Cette représentation **ne constitue pas** la recommandation de créer une deuxième table : préférer si possible une évolution additive de la table existante, à comparer au maintien d'une identité polymorphe pour les autres sources. La famille et le partitionnement doivent être immuables dans le registre de flux ; le partition_id dénormalisé éventuel est à valider contre les transitions administratives de topologie.

## Plan d'audit préalable à la migration A2

1. Dresser une matrice exhaustive des producteurs et lecteurs **en code applicatif, SQL, tests et runbooks** pour `consumer_type/components`, `consumption_inputs/results`, `terminal_outcome/reason` ; distinguer le rôle d'audit et le rôle de correction effective. Éviter toute déduction de « inutilisé » à partir de la seule recherche des mots `findInputs`/`findResults`.
2. Classer chaque source : E mono-famille cible / non-E conservée ; préciser la migration ou la coexistence et le contrat d'identité de chaque famille, y compris projection tasks et results.
3. Choisir **un seul modèle cible** (évolution de la table unique ou tables spécialisées) selon invariants et volume SQL ; fixer l'index d'acquisition `(consumption_id,partition_id,status,next_claim_at)` si partition persistée, tout en conservant le fencing de `slot_id`.
4. Expliquer comment relier chaque slot existant à une génération UUID sans ambiguïté ; backfill, comparaison et retour administratifs. Ne pas écraser `DONE`, les claims, les résultats métier ni la provenance historique.
5. Protéger la transaction effets → provenance retenue si applicable → terminal CAS, le rollback, les claims/leases, les anciens consumers et la cohérence des lectures READ. Fixer les preuves PostgreSQL, E2E, test de migration et performances.

**Arbitrage recommandé pour discussion :** remplacer l'identité dynamique du consumer par `consumptionId` pour E ; conserver le modèle d'outcome terminal ; traiter la provenance comme **option de traçabilité à justifier**, sans la supprimer tant que ses lecteurs et ses usages de réparation ne sont pas prouvés. Aucune décision de migration définitive prise par ce diagnostic.

## Arbitrage validé — option 2 (2026-10-21)

**DÉCISION : retirer entièrement la provenance du protocole générique Consumption.** Aucun `ConsumptionInput` ou `ConsumptionResult` systématique à émettre, conserver ou lire dans le moteur. Chaque Consumer persiste, dans ses propres autorités métier si nécessaire, les références aux Events, slots ou autres causes utiles. Cela ne supprime pas les résultats métier publics, les liens de cause exigés par un invariant précis, ni l'observabilité du moteur. Le suffixe commun devient : effets du Consumer → terminalisation fenced/CAS dans la même transaction. Les issues terminales et raisons restent disponibles.

**Migration NON réalisée.** `consumption_inputs` et `consumption_results` existent actuellement ; A2 doit vérifier tous les producteurs/lecteurs (dont tests, SQL, exploitation et consumers non-E), définir la rétention ou l'archivage éventuel, retirer de façon cohérente ports/adapters/entités et tables lorsque cela est justifié, puis prouver rollback, fencing et idempotence. Interdit : suppression immédiate de tables historiques ou abandon de la garantie transactionnelle.
