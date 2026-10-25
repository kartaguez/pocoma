# Pocoma SPEC-03 — Événements, demandes et consommables

> **Lecture des statuts (clôture L1, 2026-10-25) :** les mentions de non-implémentation/non-exécution ci-dessous décrivent l’état du cadrage conceptuel lors de sa rédaction, pas un relevé actuel des tests. Les contrats et oracles restent normatifs ; contributions internes L1 et limites dans le [dossier de clôture](L1_Documentary_Closure.md), programme et statuts dans le [plan directeur](V3_Implementation_Master_Plan.md). Aucun PASS global de cette SPEC n’est acquis par cette note.

**Statut : contrat conceptuel consolidé (AUD-V3-005/010) ; réalisation et certification V3 non exécutées.**  
**Date :** 2026-10-22. **Autorité de progression :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md).  
**Fondations :** [Pot V3-1](SPEC-01_Pot_Domain.md) et [Identity V3-1](SPEC-02_Identity_Domain.md).

## 1. Frontières et principes

Deux domaines indépendants : **Pot** et **Identity**. Une `COMMAND` peut modifier exclusivement Pot ; une `IDENTITY_REQUEST` peut modifier exclusivement Identity (Registration, Link, Unlink). Consulter des faits d'un autre domaine pour autoriser une opération n'est pas le modifier. **Toute mutation métier V3 provient nécessairement d'une demande durable** et chaque événement de mutation porte son `requestId` obligatoire. Aucune mutation auxiliaire autonome hors demande.

Deux natures de faits durables :
- **Mutation du domaine** : événement immuable référençant une nouvelle version effective d'un sujet.
- **Complétion d'une demande** : résultat terminal durable ET événement immuable signalant sa disponibilité à Consumption/READ. L'événement ne contient pas une copie du résultat.

Un succès sans effet ou un rejet n'émet aucun événement de mutation, mais donne lieu à un résultat terminal et son événement de complétion. Une erreur technique transitoire n'est pas un rejet fonctionnel terminal.

## 2. Catalogue normatif : sept familles de consommables

| Famille | Domaine | Nature | Disponibilité / découverte |
|---|---|---|---|
| `COMMAND` | Pot | **Burnable** | Demande `PENDING`, état durable porté par la source |
| `IDENTITY_REQUEST` | Identity | **Burnable** | Demande `PENDING`, état durable porté par la source |
| `POT_EVENT` | Pot | **Non-burnable** | Séquence transactionnelle dense `POT` |
| `INVITATION_EVENT` | Pot / Invitation | **Non-burnable** | Séquence transactionnelle dense `INVITATION` |
| `IDENTITY_EVENT` | Identity | **Non-burnable** | Séquence transactionnelle dense `IDENTITY` |
| `COMMAND_COMPLETION_EVENT` | Pot | **Non-burnable** | Séquence transactionnelle dense `COMMAND_COMPLETION` |
| `IDENTITY_COMPLETION_EVENT` | Identity | **Non-burnable** | Séquence transactionnelle dense `IDENTITY_COMPLETION` |

Ces familles sont **logiques** : ni sept tables ni sept moteurs ne sont imposés. Les événements ne portent aucun statut de consommation ; les demandes burnables portent leur propre statut. La Discovery des deux demandes burnables est asynchrone après admission, sans création de slot par la Supra ; les cinq sources événementielles sont non-burnables. Les algorithmes de Discovery, checkpoints, claims, quarantaine et retries sont définis en V3-4. Les anciennes familles techniques `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK` et `PreparePotProjectionTasks` n'existent pas en V3.

## 3. Demandes burnables et résultats terminaux

Contrat normatif d'une demande durable :
- `requesterEui` : identité initiatrice authentifiée `(issuer,subject)`, capturée immuablement dès l'admission, y compris pour Registration et pour une demande ultérieurement rejetée ; elle est propriétaire de la demande et du résultat, sans transfert lors des Unlink/re-Link ;
- `bindingToken` explicite de l'EUI initiatrice pour toute Command ou IdentityRequest sauf Registration, sans inférence implicite de PocomaUserId ; validation de l'occurrence active par WRITE à l'exécution ;
- `requestId` stable et unique ; `operation` ; `payload` d'intention et preuves capturées de façon immuable ;
- `status` durable `PENDING` ou `TERMINAL`, **sans** `PROCESSING`/`RETRYING` comme états métier ;
- lors de la terminalisation, exactement un résultat terminal durable portant l'issue fonctionnelle et ses informations propres ; l'issue **ne se déduit pas** de `TERMINAL`.

La transition `PENDING → TERMINAL`, l'enregistrement du résultat, l'événement de complétion et toutes les mutations/événements produits sont atomiques. La demande ne redevient pas `PENDING`. Une nouvelle tentative technique d'une demande déjà terminale ne reproduit aucun effet métier. La correspondance durable `TERMINAL` ↔ résultat existant est un invariant de cohérence, non une raison de supprimer le statut. La demande et le résultat terminal WRITE restent retrouvables ensemble par `requestId`. La Consumption de complétion récupère les deux en WRITE puis matérialise `requesterEui` dans `CommandResult` ou `IdentityRequestResult` en READ ; aucune nouvelle projection ou consultation synchrone de WRITE par la Supra READ. Les opérations Identity partagent la famille `IDENTITY_REQUEST`, avec payloads et validations métier propres.

## 4. Contrats événementiels minimaux

**Socle commun de publication :** `eventId` (UUID unique et stable), `subjectType`, `subjectId`, `recordedAt`. La position et la famille de séquencement appartiennent à la publication durable, sans imposer d'enveloppe métier universelle.

**Événement de mutation :** socle + **`requestId` obligatoire** + `subjectVersion` (version **atteinte**, jamais ancienne). Pour `POT`, `changedComponents` contient les références/versions locales des composantes modifiées ; pour `POCOMA_USER` et `EXTERNAL_USER_IDENTITY`, aucune collection artificielle. Pas de `eventType`, pas de copie du nouvel état métier, pas de `bindingToken` dans l'événement partagé. L'histoire autoritative est la source des valeurs.

- Invitation : exactement un `INVITATION_EVENT` par version locale effective ; aucune copie du token ou du lien dans cet événement.
- Pot : **exactement un** événement par mutation effective de version globale V, y compris multi-cibles et suppression (pas d'événement de mutation par enfant).
- EUI : **exactement un** événement par nouvelle version EUI (création, Link, Unlink), avec version locale.
- PocomaUser : événement lors de création, version initiale **1**, non évolutive.
- Unicité naturelle complémentaire : `(subjectType, subjectId, subjectVersion)` ; `eventId` est néanmoins conservé pour simplifier les références techniques.

**Événement de complétion :** socle, avec `subjectId = requestId` de la demande terminée et `subjectType` identifiant la catégorie de demande (Command ou opération Identity). Pas de `requestId` dupliqué, ni de version artificielle, ni de résultat recopié. Un événement de complétion par demande terminalisée ; pas de statut porté par cet événement. Ni `requesterEui` ni `bindingToken` n'enrichissent cet événement partagé.

## 5. Publication et séquences transactionnelles

Cinq séquences **distinctes** : `POT`, `INVITATION`, `IDENTITY`, `COMMAND_COMPLETION`, `IDENTITY_COMPLETION`. Chaque événement publié a un `(sequence, position)` unique ; l'identité `eventId` reste indépendante de sa position.

Positions croissantes et **denses** sur l'historique durable conservé, sans trou créé par rollback ; une position plus haute committée ne doit pas masquer une position plus basse qui pourrait committer plus tard. Des `nextval` PostgreSQL classiques ou des horodatages/UUID seuls ne prouvent pas cela. Les compteurs sont transactionnels, avec verrous retenus jusqu'à résolution physique de la transaction ; un lot de plusieurs événements d'une famille reçoit des positions consécutives dans l'ordre de publication déclaré. La position n'est ni version métier ni ordre inter-séquences.

Une transaction `COMMAND` utilise, selon sa mutation effective, `POT` ou `INVITATION` puis `COMMAND_COMPLETION`. Une transaction `IDENTITY_REQUEST` n'utilise au plus que `IDENTITY` puis `IDENTITY_COMPLETION`. Une demande sans mutation n'utilise que sa séquence de complétion. Aucune transaction de demande ne mute les deux domaines. Pas d'ordre global entre les cinq séquences.

Le changement métier, ses événements, la terminalisation, le résultat, les événements de complétion et l'attribution des positions sont dans **une seule transaction physique**. Le suffixe d'attribution de positions intervient après la finalisation des écritures métier / validations, suivant une discipline explicite interdisant de retourner acquérir les verrous métier après ceux des séquences ; un échec annule tout. Les détails de composition du moteur, fencing, rollback/UNKNOWN et preuve d'absence de nouveaux cycles de verrouillage relèvent de V3-4/V3-5. Les garanties fonctionnelles 4A–4E sont un acquis historique à reproduire, **pas** une preuve V3 ni une contrainte de réutilisation littérale des tables/ports.

## 6. Matrice de cohérence avec V3-1

| Cas | Mutation(s) et événement(s) | Résultat / événement de complétion |
|---|---|---|
| Création Invitation | Invitation v1, un `INVITATION_EVENT`, sans nouvelle version Pot | Succès + un `COMMAND_COMPLETION_EVENT` ; lien absent de CommandResult |
| Création Pot | Pot V1, un `POT_EVENT` | Succès + un `COMMAND_COMPLETION_EVENT` |
| Command multi-cibles | Une nouvelle V globale, un événement Pot indiquant les composantes changées | Succès + complétion |
| Command non obsolète sans effet | Aucune nouvelle V, aucun événement de mutation | Succès (`resultingPotVersion = V_courant`) + complétion |
| Command obsolète | Aucune mutation ni événement métier | Rejet + complétion |
| Suppression Expense | `ExpenseHeader` seul modifié, un événement Pot | Succès + complétion |
| Suppression Pot | `PotHeader` seul modifié, un événement Pot, aucun événement enfant | Succès + complétion |
| Registration gagnante | Un événement User v1 **et** un événement EUI (création liée v1 ou transition version suivante) | Succès + une complétion Identity |
| Link gagnant | Un événement EUI, sans événement User | Succès + une complétion Identity |
| Unlink gagnant, même dernière EUI | Un événement EUI ; User persiste sans changement | Succès + une complétion Identity |
| Registration/Link/Unlink rejetée | Aucun nouvel événement métier | Rejet + une complétion Identity |

Lors d'une Registration, les deux événements Identity et la complétion sont publiés atomiquement dans **deux** séquences, sans ordre global entre elles. Le `requestId` commun relie les événements de mutation à la demande. Les événements de complétion sont consommables indépendamment de l'avancement des matérialisations de mutation ; **publication atomique ≠ projections READ simultanément à jour**.

## 7. Destinataires des sources et frontières de responsabilité

Chaque `POT_EVENT(P,V)` est consommé indépendamment par trois Consumptions directes : `MaterializePotVersion` (composition historique, sous-objets et pointeur courant), `MaterializePotBalance` (balance exacte et immuable à V pour une version active ; traitement terminal sans Balance(D) pour DeletePot) et `MaterializePotVisibility` (état de visibilité et navigation). Chaque Consumption dispose de son propre Discovery, de ses slots et checkpoints ; aucune tâche préparatoire, dépendance de slot ou séquence supplémentaire.

`INVITATION_EVENT` alimente indépendamment `CurrentInvitation` via `MaterializeInvitation` depuis l'état WRITE à la version exacte, sans lien dans l'événement. `IDENTITY_EVENT` alimente `CurrentBinding` depuis l'état autoritatif EUI@version sans propager le bindingToken dans l'événement ; seuls les événements `EXTERNAL_USER_IDENTITY` sont enrôlés/projetés par `MaterializeCurrentBinding` (un événement de création de `POCOMA_USER` n'impose aucune projection Identity inverse et est ignoré par `MaterializeCurrentBinding`). Les événements de complétion alimentent les deux projections de résultats immuables, à partir de la demande et du résultat WRITE. Voir [V3-3](SPEC-04_Read_Projections.md) pour les projections et [V3-4](SPEC-05_Consumption_Discovery.md) pour l'exécution, l'atomicité et la reprise.

## 8. Garanties et qualification restantes

Les sept familles sont **logiques** et n'imposent aucune topologie de tables ou de Workers. Les identifiants de Consumption sont stables pour une définition donnée et changent pour un rejeu réellement indépendant ; les règles de reconstruction restent définies en V3-4.

La publication dense, l'atomicité des transactions physiques et leur absence de pertes sont à prouver dans les exigences EVT-01/02, CON-04/06 et REC-01 de [V3-5D](SPEC-10_Test_Certification.md). Les trois Consumptions directes et l'absence de familles techniques sont à certifier dans EVT-03 et PRJ-01/02/03 ; la propriété EUI des résultats dans les scénarios A10-A à A10-K. Aucun code, DDL ou test V3 n'est revendiqué par cette consolidation.

## 9. Mutation d'invitation et complétion

Le catalogue comporte désormais **sept familles** : deux burnables (`COMMAND`, `IDENTITY_REQUEST`) et cinq non-burnables (`POT_EVENT`, `INVITATION_EVENT`, `IDENTITY_EVENT`, `COMMAND_COMPLETION_EVENT`, `IDENTITY_COMPLETION_EVENT`), donc **cinq séquences événementielles denses**. Toute mutation effective d'invitation émet `INVITATION_EVENT(invitationId, invitationVersion, requestId)` et conserve l'état d'invitation historisé correspondant en WRITE. Aucun token ni lien en clair dans l'événement. Cet événement métier est atomique avec l'écriture WRITE et la complétion distincte de la commande ; un rejet n'émet que la complétion. `INVITATION_EVENT` ne se substitue pas à `POT_EVENT`, et `COMMAND_COMPLETION_EVENT` ne sert jamais à projeter l'invitation.

## 10. Correspondance WRITE / READ des résultats — L1-F

| Issue durable WRITE | Statut fonctionnel terminal READ | Effets Pot |
|---|---|---|
| CHANGED | COMPLETED | Une nouvelle V globale et un POT_EVENT pour une mutation effective de Pot |
| UNCHANGED | COMPLETED | Aucune nouvelle V ni POT_EVENT ; resultingPotVersion reste V courante |
| REJECTED | REJECTED | Aucune mutation/V/POT_EVENT ; motif métier conservé |

Chaque ligne produit exactement une complétion de la demande. `ExecutePotCommand.Attempt.COMPLETED` signifie tentative terminalisée et peut donc contenir une issue WRITE REJECTED ; il ne remplace pas le statut fonctionnel READ. TECHNICAL, INCONSISTENT_FACTS, COLLISION, RETRY_SCHEDULED, QUARANTINED, CLAIM_LOST et RECOVERY_PENDING ne deviennent pas des rejets métier READ. Le matérialisateur V3 réel de résultats reste à livrer en L4 ; les tests internes WRITE ne certifient pas cette projection.

Le rattachement effectif via invitation réutilisable du même Pot révise ShareholderDetails et Pot, publie POT_EVENT et sa complétion, sans modifier ni consommer l'invitation. Il ne produit donc pas un nouvel INVITATION_EVENT au seul titre de son utilisation.
