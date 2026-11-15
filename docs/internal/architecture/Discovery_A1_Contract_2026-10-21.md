# Discovery — Contrat A1 (décisions du 2026-10-21)

**Statut : contrat de conception retenu ; NON IMPLÉMENTÉ, preuves PostgreSQL et raccordement A2–A5 à produire.** 4A–4E restent CLOSED/PASS fonctionnel hors production ; **activation production NON AUTORISÉE**. Ce document précise le [canon](../steps/current/DISCOVERY/Step_Canon.md) sans remplacer les garanties transactionnelles E ni le protocole de Consumption existant.

## 1. Identité et responsabilités

Une unité logique Discovery **et** son worker Consumption sont strictement alignés et mono-famille, identifiés par le triplet `(eventFamily, consumptionId UUID, partitionId)`. Un seul worker est prévu par partition d'un flux, exécution des slots initialement séquentielle ; le CAS protège aussi les chevauchements de redémarrage. `consumptionId` désigne un flux de consommation et une génération **immuable** de règles, opaques pour Discovery. Plusieurs flux/générations actifs peuvent coexister et retravailler le même Event.

Discovery découvre **tous** les Events de sa famille affectés à sa partition, sans filtre de type métier/règle de projection ; elle enrôle **un slot par Event et consumptionId**. Le consumer interprète l'Event et peut terminer avec succès sans effet métier. Consumption seule acquiert, exécute, reprend et finalise les slots, indépendamment de l'avancement de Discovery. Les consommateurs doivent garantir l'idempotence et la commutativité revendiquées ; les projections READ mutables doivent conserver leur monotonie et les API READ leur contrat de disponibilité (chantier B/C). Aucune dépendance de versions ou synchronisation inter-partitions introduite par Discovery.

## 2. Partitionnement à l'écriture

L'identité/hash de partitionnement est fixé à l'écriture dans la source durable ; la partition est calculée à partir du hash **persisté**, de l'algorithme versionné et du nombre de partitions figé pour le flux. Les Events sont répartis par identité d'objet cible **propre à leur famille**, non par EventId.

| Famille E | Source et métadonnées existantes | Choix A1 |
|---|---|---|
| BUSINESS_EVENT | `business_event_outbox(id, pot_id, pot_partition_hash)` | Réutiliser hash du Pot propriétaire |
| COMMAND_TERMINAL_EVENT | `command_terminal_events(event_id, command_id, command_partition_hash)` | Réutiliser hash Command (y compris rejet sans Pot) |
| BINDING_FACT | `external_identity_binding_facts(event_id, issuer, subject, partition_hash)` | Réutiliser hash identité externe |
| USER_CREATED_FACT | `user_created_facts(event_id, user_id, request_id)` | Cible : ajouter hash durable dérivé de user_id à l'écriture ; migration/backfill à concevoir en A2 |

Le sens exact, la stabilité des fonctions de hash existantes, leur normalisation modulo pour les entiers négatifs et l'absence de dépendance à la topologie dynamique doivent être **audités en A2**, non présumés. La source est jointe à l'association E par EventId indexé ; ne pas décoder de payload JSONB pour choisir la partition. Un changement de `partitionCount`/algorithme/affectation impose une transition **administrative explicite**, y compris les slots et checkpoints préexistants ; aucune reconfiguration transparente. Identité et configuration active du flux sont persistées/validées indépendamment du checkpoint (schéma exact à définir A2).

## 3. Checkpoint et invariant

Schéma cible conceptuel (DDL final à valider A2) :

```sql
CREATE TABLE discovery_checkpoints (
    event_family TEXT NOT NULL,
    consumption_id UUID NOT NULL,
    partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
    last_examined_position BIGINT NOT NULL DEFAULT 0 CHECK (last_examined_position >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_family, consumption_id, partition_id)
);
```

`last_examined_position=C` signifie que **chaque position de la famille jusqu'à C** a été examinée avec les règles de partition du flux, et que **tous les slots localement requis sont présents durablement**, dans la même transaction que l'avancement du checkpoint. Les positions non locales sont également examinées et permettent d'avancer C ; ne jamais calculer C comme `max(position)` d'une requête préfiltrée par partition. Le checkpoint n'attend **pas** DONE, ni READY dans READ. Pas de liste de trous de consommation : les PENDING, claims/leases/retries existants portent la reprise.

## 4. Transaction CAS, pas verrou anticipé

1. Lire C et la configuration active autorisée du triplet ; vérifier leur cohérence.
2. Lire une **plage bornée contiguë** de positions E strictement après C, dans une vue SQL cohérente ; une position absente ou sa source manquante interdit de la franchir. Pour la frontière de commit, respecter les garanties de séquencement transactionnel 4E ; ne pas confondre absence provisoire et trou définitif.
3. Joindre sur UUID source, prendre seulement hash et métadonnées nécessaires ; sélectionner localement par partition.
4. Insérer de façon idempotente les slots des Events locaux **sans jamais réinitialiser** un slot existant (notamment DONE/PENDING/claim/lease).
5. Dans **la même transaction PRIMARY**, effectuer CAS `UPDATE ... SET last_examined_position=:N WHERE (family, consumption_id, partition_id)=D AND last_examined_position=:C`. Exiger exactement une ligne mise à jour.
6. CAS gagnant et tous slots garantis → COMMIT ; CAS perdant, erreur technique ou doute sur couverture → **ROLLBACK intégral** puis relire le checkpoint et reprendre une nouvelle transaction. En cas de COMMIT UNKNOWN, réconcilier par lecture durable, jamais rejouer aveuglément.

```sql
UPDATE discovery_checkpoints
SET last_examined_position = :N, updated_at = now()
WHERE event_family = :family
  AND consumption_id = :consumption_id
  AND partition_id = :partition_id
  AND last_examined_position = :C;
```

Le CAS PostgreSQL peut attendre un verrou interne sur la ligne : il évite seulement le `SELECT FOR UPDATE` initial. Une collision sur les uniques de slots ou un deadlock peut précéder le CAS : transactions courtes, insertions ordonnées, reprise intégrale. La ligne checkpoint initiale doit être créée par un bootstrap idempotent et sûr. Une activation/désactivation concurrente doit être cohérente avec la transaction d'enrôlement ; la politique d'exécution des slots déjà enrôlés est distincte et devra être spécifiée.

## 5. Pont exact vers A2 : slots existants

La table actuelle `consumption_slots` (migration `V4__consumption_engine.sql`) a pour unique métier : `(consumable_type, consumable_components JSONB, consumer_type, consumer_components JSONB)`, avec états PENDING/DONE, claim courant, échéance et révision. **Il n'y a actuellement ni colonne `consumption_id` ni `partition_id` dans cette table.**

A2 doit donc fixer une **correspondance bijective, canonique et indexable** entre `(eventFamily, eventId, consumptionId)` et cette identité de slot, puis rendre efficace la sélection des slots de `(family, consumptionId, partitionId)`. La clé de partition doit rester déterministe même après la création du slot. Options techniques (à comparer sans préjuger) : représentation dans `consumer_components` et métadonnées relationnelles/indexées additionnelles, ou évolution explicite du modèle de slot. Ne pas faire dépendre une reprise rapide d'une anti-jointure JSONB sur tout l'historique. Préserver les consumers/slots anciens tant que la migration n'est pas explicitement actée.

## 6. Preuves requises A2–A5

- Pour chacune des **quatre familles** : hash et partition, source/position jointes par UUID, Event rejeté/no-op, cas source manquante.
- Plage sans Event local, positions non locales intercalées, lot borné, continuité depuis C, publications concurrentes et rollbacks producteurs.
- Crash avant/après COMMIT, COMMIT UNKNOWN, CAS concurrent gagnant/perdant, deadlock et rollback de toutes insertions du perdant.
- Insertion idempotente répétée sans mutation d'un slot DONE ou déjà claimé ; deux générations actives et relecture historique depuis checkpoint zéro.
- Arrêt/reprise d'un flux, changement de topologie refusé hors administration, conservation des slots existants et cohérence du périmètre.
- Reprise Consumption PENDING/Busy/lease/retry indépendante du checkpoint ; exécution mono-famille/mono-partition ; pas de régression READ.
- Plans PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`, rows, buffers, WAL, latences, et amplification due aux scans des positions par partition : seuils de performance définis avant certification, aucun gain affirmé à ce stade.

**Hors A1 :** A2 schéma/SQL/enrôlement, A3 reprise, A4 orchestration/équité, A5 optimisation ; B/C contrats et refonte READ. Aucune activation de séquencement en production n'est déduite de cette décision documentaire.
