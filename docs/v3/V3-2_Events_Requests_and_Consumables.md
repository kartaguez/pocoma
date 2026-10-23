# Pocoma V3-2 — Événements, demandes et consommables

**Statut : CONCEPTION CONCEPTUELLE VALIDÉE, sous réserve de revue transversale V3-3/V3-4 et jalon V3-CADRE. Non implémentée, non testée en V3.**  
**Date :** 2026-10-22. **Autorité de progression :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md).  
**Fondations :** [Pot V3-1](V3-1_Domain_and_Versioning.md) et [Identity V3-1](V3-1_Identity_Domain_and_Versioning.md).

## 1. Frontières et principes

Deux domaines indépendants : **Pot** et **Identity**. Une `COMMAND` peut modifier exclusivement Pot ; une `IDENTITY_REQUEST` peut modifier exclusivement Identity (Registration, Link, Unlink). Consulter des faits d'un autre domaine pour autoriser une opération n'est pas le modifier. **Toute mutation métier V3 provient nécessairement d'une demande durable** et chaque événement de mutation porte son `requestId` obligatoire. Aucune mutation auxiliaire autonome hors demande.

Deux natures de faits durables :
- **Mutation du domaine** : événement immuable référençant une nouvelle version effective d'un sujet.
- **Complétion d'une demande** : résultat terminal durable ET événement immuable signalant sa disponibilité à Consumption/READ. L'événement ne contient pas une copie du résultat.

Un succès sans effet ou un rejet n'émet aucun événement de mutation, mais donne lieu à un résultat terminal et son événement de complétion. Une erreur technique transitoire n'est pas un rejet fonctionnel terminal.

## 2. Six familles de consommables, deux mécanismes de Discovery

| Famille | Domaine | Nature | Disponibilité / découverte |
|---|---|---|---|
| `COMMAND` | Pot | **Burnable** | Demande `PENDING`, état durable porté par la source |
| `IDENTITY_REQUEST` | Identity | **Burnable** | Demande `PENDING`, état durable porté par la source |
| `POT_EVENT` | Pot | **Non-burnable** | Séquence transactionnelle dense `POT` |
| `IDENTITY_EVENT` | Identity | **Non-burnable** | Séquence transactionnelle dense `IDENTITY` |
| `COMMAND_COMPLETION_EVENT` | Pot | **Non-burnable** | Séquence transactionnelle dense `COMMAND_COMPLETION` |
| `IDENTITY_COMPLETION_EVENT` | Identity | **Non-burnable** | Séquence transactionnelle dense `IDENTITY_COMPLETION` |

Ces familles sont **logiques** : ni six tables ni six moteurs ne sont imposés. Les événements ne portent aucun statut de consommation ; les demandes burnables portent leur propre statut. Les algorithmes de Discovery, checkpoints, claims et retries sont conçus en V3-4.

## 3. Demandes burnables et résultats terminaux

Contrat conceptuel minimal d'une demande durable :
- `requestId` stable et unique ; `operation` ; `payload` d'intention et preuves capturées de façon immuable ;
- `status` durable `PENDING` ou `TERMINAL`, **sans** `PROCESSING`/`RETRYING` comme états métier ;
- lors de la terminalisation, exactement un résultat terminal durable portant l'issue fonctionnelle et ses informations propres ; l'issue **ne se déduit pas** de `TERMINAL`.

La transition `PENDING → TERMINAL`, l'enregistrement du résultat, l'événement de complétion et toutes les mutations/événements produits sont atomiques. La demande ne redevient pas `PENDING`. Une nouvelle tentative technique d'une demande déjà terminale ne reproduit aucun effet métier. La correspondance durable `TERMINAL` ↔ résultat existant est un invariant de cohérence, non une raison de supprimer le statut. Les opérations Identity partagent la famille `IDENTITY_REQUEST`, avec payloads et validations métier propres.

## 4. Contrats événementiels minimaux

**Socle commun de publication :** `eventId` (UUID unique et stable), `subjectType`, `subjectId`, `recordedAt`. La position et la famille de séquencement appartiennent à la publication durable, sans imposer d'enveloppe métier universelle.

**Événement de mutation :** socle + **`requestId` obligatoire** + `subjectVersion` (version **atteinte**, jamais ancienne). Pour `POT`, `changedComponents` contient les références/versions locales des composantes modifiées ; pour `POCOMA_USER` et `EXTERNAL_USER_IDENTITY`, aucune collection artificielle. Pas de `eventType`, pas de copie du nouvel état métier, pas de `bindingToken` dans l'événement partagé. L'histoire autoritative est la source des valeurs.

- Pot : **exactement un** événement par mutation effective de version globale V, y compris multi-cibles et suppression (pas d'événement de mutation par enfant).
- EUI : **exactement un** événement par nouvelle version EUI (création, Link, Unlink), avec version locale.
- PocomaUser : événement lors de création, version initiale **1**, non évolutive.
- Unicité naturelle complémentaire : `(subjectType, subjectId, subjectVersion)` ; `eventId` est néanmoins conservé pour simplifier les références techniques.

**Événement de complétion :** socle, avec `subjectId = requestId` de la demande terminée et `subjectType` identifiant la catégorie de demande (Command ou opération Identity). Pas de `requestId` dupliqué, ni de version artificielle, ni de résultat recopié. Un événement de complétion par demande terminalisée ; pas de statut porté par cet événement.

## 5. Publication et séquences transactionnelles

Quatre séquences **distinctes** : `POT`, `IDENTITY`, `COMMAND_COMPLETION`, `IDENTITY_COMPLETION`. Chaque événement publié a un `(sequence, position)` unique ; l'identité `eventId` reste indépendante de sa position.

Positions croissantes et **denses** sur l'historique durable conservé, sans trou créé par rollback ; une position plus haute committée ne doit pas masquer une position plus basse qui pourrait committer plus tard. Des `nextval` PostgreSQL classiques ou des horodatages/UUID seuls ne prouvent pas cela. Les compteurs sont transactionnels, avec verrous retenus jusqu'à résolution physique de la transaction ; un lot de plusieurs événements d'une famille reçoit des positions consécutives dans l'ordre de publication déclaré. La position n'est ni version métier ni ordre inter-séquences.

Une transaction `COMMAND` n'utilise au plus que `POT` puis `COMMAND_COMPLETION`. Une transaction `IDENTITY_REQUEST` n'utilise au plus que `IDENTITY` puis `IDENTITY_COMPLETION`. Une demande sans mutation n'utilise que sa séquence de complétion. Aucune transaction de demande ne mute les deux domaines. Pas d'ordre global entre les quatre séquences.

Le changement métier, ses événements, la terminalisation, le résultat, les événements de complétion et l'attribution des positions sont dans **une seule transaction physique**. Le suffixe d'attribution de positions intervient après la finalisation des écritures métier / validations, suivant une discipline explicite interdisant de retourner acquérir les verrous métier après ceux des séquences ; un échec annule tout. Les détails de composition du moteur, fencing, rollback/UNKNOWN et preuve d'absence de nouveaux cycles de verrouillage relèvent de V3-4/V3-5. Les garanties fonctionnelles 4A–4E sont un acquis historique à reproduire, **pas** une preuve V3 ni une contrainte de réutilisation littérale des tables/ports.

## 6. Matrice de cohérence avec V3-1

| Cas | Mutation(s) et événement(s) | Résultat / événement de complétion |
|---|---|---|
| Création Pot | Pot V1, un `POT_EVENT` | Succès + un `COMMAND_COMPLETION_EVENT` |
| Command multi-cibles | Une nouvelle V globale, un événement Pot indiquant les composantes changées | Succès + complétion |
| Command non obsolète sans effet | Aucune nouvelle V, aucun événement de mutation | Succès + complétion |
| Command obsolète | Aucune mutation ni événement métier | Rejet + complétion |
| Suppression Expense | `ExpenseHeader` seul modifié, un événement Pot | Succès + complétion |
| Suppression Pot | `PotHeader` seul modifié, un événement Pot, aucun événement enfant | Succès + complétion |
| Registration gagnante | Un événement User v1 **et** un événement EUI (création liée v1 ou transition version suivante) | Succès + une complétion Identity |
| Link gagnant | Un événement EUI, sans événement User | Succès + une complétion Identity |
| Unlink gagnant, même dernière EUI | Un événement EUI ; User persiste sans changement | Succès + une complétion Identity |
| Registration/Link/Unlink rejetée | Aucun nouvel événement métier | Rejet + une complétion Identity |

Lors d'une Registration, les deux événements Identity et la complétion sont publiés atomiquement dans **deux** séquences, sans ordre global entre elles. Le `requestId` commun relie les événements de mutation à la demande. Les événements de complétion sont consommables indépendamment de l'avancement des matérialisations de mutation ; **publication atomique ≠ projections READ simultanément à jour**.

## 7. Décisions reportées et critères de clôture

**V3-3** : projections exactes, liens causalement cohérents, lecture sécurisée des tokens et niveaux de fraîcheur des READ ; ne pas supposer qu'une complétion matérialisée entraîne nécessairement une projection Pot/Identity déjà matérialisée.

**V3-4** : identité stable `consumptionId` (une nouvelle définition de transformation reçoit un nouvel ID), `consumableId`, traitement déclenché par **une** source (lectures complémentaires possibles), Discovery et checkpoints par séquence, claims/retries/fencing, résolution de commit incertain, performances de sélection `PENDING`, traitement inter-projections sans dépendance implicite. Aucun `workId` nouvel UUID imposé.

**Admission/sécurité** : preuve fiable de contrôle/consentement de l'EUI cible pour Link, préservant les décisions V3-1 ; avant implémentation.

**V3-5** : preuves de densité et ordre de visibilité sous concurrence/rollback ; deux familles avec mutation + complétion ; Registration multi-événements ; commandes sans effet/rejetées ; atomie en cas de crash/UNKNOWN ; absence de perte et reprise ; mesures SQL/charge réalistes ; pas de certification production héritée de V2.

Les contrats précis des résultats terminalisés (payload par opération), les index/tables, la portée des IDs à l'échelle des familles et les mécanismes des workers restent à détailler sans ajouter d'abstraction inutile. **Aucune implémentation V3 n'est autorisée avant le jalon transverse V3-CADRE.**


## Amendement V3-4 — sources internes READ (2026-10-23)

Le catalogue initial des **six familles** concerne les demandes métier et les événements. Deux familles techniques supplémentaires, **internes à READ et burnables**, sont retenues : `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK`, chacune à statut `PENDING → TERMINAL` et avec une seule Consumption destinataire. Aucune nouvelle séquence globale d'événements n'est introduite. Pour chaque `POT_EVENT`, la Consumption de préparation produit atomiquement les deux tâches avec la suppression de son slot, dans READ. Chaque tâche est identifiée de façon stable par `(preparationConsumptionId,potEventId,taskType)` ; un rejeu indépendant crée une nouvelle Consumption de préparation et donc de nouvelles tâches, alors qu'une reconstruction de READ à blanc peut réutiliser les mêmes IDs après reset cohérent. Matérialisation, terminalisation et suppression du slot d'une tâche s'effectuent en une transaction READ. Ces contrats devront être prouvés en V3-5.
