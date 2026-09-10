# Reconstruction historique canonique d'un Pot

## Statut et portée

Ce document décrit les faits persistés et les règles de lecture primaire nécessaires à une
reconstruction exacte de `Pot@V`. Il complète l'[état actuel du read side](read-side-current-state.md)
et sert de référence aux projectors. Il ne transforme pas le primaire en source autorisée pour les
GET : un projector ou une procédure administrative peut lire cet historique, un reader cible ne le
peut pas.

## Règle temporelle commune

Les quatre familles de fragments primaires utilisent des intervalles semi-ouverts. Une ligne décrit
l'état applicable à `V` si et seulement si :

```text
started_at_version <= V
and (ended_at_version is null or V < ended_at_version)
```

Une mutation ferme la ligne précédente à la nouvelle version puis insère la nouvelle ligne. Le
champ `deleted` des headers Pot, Shareholder et Expense est un état historique explicite ; une
suppression fonctionnelle n'efface donc pas la dernière représentation du sous-objet.

Les identifiants techniques `id` des lignes temporelles et leurs bornes ne font pas partie du
contenu fonctionnel d'un snapshot projeté. Ils décrivent la représentation physique de l'historique,
pas l'état métier à `V`.

## Fragments disponibles

### Pot header

`pot_headers`, identifié fonctionnellement par `pot_id`, expose à la version exacte :

- `label` ;
- `creator_id` ;
- `deleted`.

`JpaPotHeaderRepository.findActiveAtVersion` applique directement la règle temporelle commune et
ne filtre pas `deleted`.

### Shareholders

`shareholders`, identifié fonctionnellement par `shareholder_id` dans un Pot, expose :

- `name` ;
- `weight_numerator` et `weight_denominator` ;
- `user_id`, nullable ;
- `deleted`.

`JpaShareholderRepository.findActiveAtVersion` retourne toutes les lignes applicables, y compris
celles portant `deleted=true`. Les queries `findActiveNotDeletedAtVersion` et
`findLinkedUserAtVersion` sont des vues filtrées destinées à des besoins particuliers ; elles ne
suffisent pas à construire un snapshot historique complet.

### Expense headers

`expense_headers`, identifié fonctionnellement par `expense_id` dans un Pot, expose :

- `payer_id` ;
- `amount_numerator` et `amount_denominator` ;
- `label` ;
- `deleted`.

`JpaExpenseHeaderRepository.findActiveAtVersion` charge une Expense connue, mais le repository ne
possède actuellement aucune query Pot-wide qui retourne aussi les Expenses supprimées. La query
`findByPotActiveNotDeletedAtVersion` et `JpaProjectedExpenseAdapter.loadActiveAtVersion` filtrent
explicitement `deleted=false`. Le reconstructeur Balance est donc une bonne preuve de lecture exacte
pour son calcul, mais pas un reconstructeur complet de `PotProjection`.

Le projector Pot utilise désormais `JpaExpenseHeaderRepository.findByPotActiveAtVersion`, lecture
Pot-wide appliquant seulement l'intervalle temporel, sans filtre sur `deleted`.

### Expense shares

`expense_shares` expose, pour chaque `expense_id` :

- `shareholder_id` ;
- `weight_numerator` et `weight_denominator`.

`JpaExpenseShareRepository.findActiveAtVersion` applique la règle temporelle commune. L'absence de
ligne applicable signifie absence de cette association à `V`. Il n'existe pas de marqueur `deleted`
indépendant pour une share : fermeture de l'intervalle et absence à la version cible portent cette
sémantique.

## Reconstruction cohérente à V

Une reconstruction complète doit lire, dans une seule transaction primaire read-only cohérente :

1. le Pot header applicable à `V` ;
2. tous ses Shareholders applicables à `V`, supprimés compris ;
3. tous ses Expense headers applicables à `V`, supprimés compris ;
4. pour chaque Expense sélectionnée, toutes ses shares applicables à `V`.

Elle ne doit ni charger la version courante, ni filtrer un sous-objet supprimé, ni appliquer un Event
à une projection précédente. L'ordre renvoyé par SQL/JPA n'est pas une propriété fonctionnelle : le
projector doit canoniser les collections avant digest et matérialisation.

Un Pot header introuvable à la version exacte, plusieurs lignes temporelles actives pour la même
identité, une Expense rattachée à un autre Pot ou une share incohérente constituent des anomalies de
reconstruction. Elles ne doivent pas être corrigées silencieusement par le projector.

## Contexte d'autorisation reconstructible

Le primaire contient les données versionnées nécessaires aux règles contextuelles aujourd'hui
canoniques :

- `creator_id` sur le Pot header ;
- `user_id` nullable et `deleted` sur chaque Shareholder.

À `V`, l'ensemble des membres actifs est donc dérivable des Shareholders applicables tels que
`deleted=false` et `user_id is not null`. Aucun rôle Shareholder distinct n'existe dans le modèle
actuel. Les permissions globales et scopes appartiennent au principal d'appel futur et ne sont pas
des données du snapshot Pot.

## Delete Pot : divergence write-side connue

Le snapshot de la version de suppression reste reconstructible : le Pot header applicable porte
`deleted=true` et les fragments enfants demeurent historisés. Il doit produire un statut projeté
`DELETED`, sans effacer les versions antérieures.

La cible architecturale exige que le delete du Pot soit terminal. L'état actuel documenté du write
side autorise encore certaines mutations d'Expense après le delete du Pot, car leurs contextes
valident la suppression de l'Expense mais pas celle du Pot. Le projector ne doit ni masquer ni
réinterpréter cette divergence. Le Lot 7.6 prouve d'abord la reconstruction shadow de la version
`DELETED`, puis livre un micro-correctif write-side strictement ciblé et ses tests avant clôture.

## Source temporelle de `updatedAt`

Depuis le Lot 7.7, `pot_version_metadata` porte exactement une ligne append-only par
`(pot_id, version)`. Son `created_at` est fixé par PostgreSQL avec `CURRENT_TIMESTAMP` dans la même
transaction que l'allocation de la version globale. La relation autoritative est donc désormais :

```text
potId + potVersion -> exactly one durable createdAt
```

Le reconstructeur relit cette ligne exacte avec le header et les fragments historiques. Il échoue
terminalement avec `POT_VERSION_METADATA_ABSENT` si elle manque. `business_event_outbox.created_at`,
`RecordedEvent.recordedAt`, l'horloge worker et l'heure de projection restent interdits comme
substituts. Les bases legacy contenant déjà des versions sans source exacte doivent être reset
explicitement avant la migration V11 ; aucun backfill approximatif n'est exécuté.

Le read store adopte ou vérifie la copie exacte dans sa propre `pot_version_metadata` lors de la
matérialisation. Cette metadata alimente `updatedAt` dans l'index utilisateur/Pot. Le digest publié de
`read-pot/v1` reste inchangé : la metadata est adjacente au snapshot canonique, stable entre rebuilds
et vérifiée par l'idempotence de la matérialisation.

## Points de code vérifiés

Cette documentation repose sur les inspections ciblées suivantes :

- `JpaPotHeaderEntity`, `JpaShareholderEntity`, `JpaExpenseHeaderEntity`,
  `JpaExpenseShareEntity` ;
- `JpaPotHeaderRepository`, `JpaShareholderRepository`, `JpaExpenseHeaderRepository`,
  `JpaExpenseShareRepository` ;
- `JpaHistoricalPotBalanceSourceAdapter` et `JpaProjectedExpenseAdapter` ;
- `JpaPotCommandEventAppendAdapter`, `RecordedEvent` et les migrations primaires V1/V2.
- `JpaPotGlobalVersionAdapter`, `JpaPotGlobalVersionRepository` et la migration primaire V11.

## Implémentation Lot 7.6

`JpaHistoricalPotSnapshotSourceAdapter` exécute la lecture sous transaction read-only
`REPEATABLE_READ`. `ReconstructPotProjectionService` vérifie les rattachements Pot/Expense/share,
les doublons d'identité et les références de payer/share, puis canonise Shareholders, Expenses et
shares par UUID. Les fractions restent des `Fraction` normalisées ; aucun timestamp ou UUID technique
n'entre dans le contenu ou son digest.
