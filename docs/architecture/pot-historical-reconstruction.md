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

Les tables `pot_global_versions`, `pot_headers`, `shareholders`, `expense_headers` et
`expense_shares` ne stockent aucun timestamp métier ou de commit associé à une `potVersion`.

`business_event_outbox.created_at` et `RecordedEvent.recordedAt` sont des métadonnées durables
candidates, mais elles ne constituent pas aujourd'hui une source canonique de `PotProjection.updatedAt` :

- plusieurs BusinessEvents distincts peuvent légitimement porter la même `potVersion` ;
- aucune contrainte ne garantit `potVersion -> exactly one recordedAt` ;
- choisir `min`, `max`, premier ou dernier Event introduirait une règle métier non documentée.

Classification actuelle : **B — source candidate nécessitant un invariant supplémentaire**. Sans
nouvel invariant ou métadonnée durable versionnée, la valeur fonctionnelle `updatedAt` doit rester
absente de `PotProjection`. Les timestamps techniques de matérialisation (`created_at` des artifacts)
restent exploitables pour le diagnostic, mais sont exclus du contenu fonctionnel, du digest et du
futur tri métier.

Cette décision est un gate d'entrée du Lot 7.7 : elle doit être fermée avant de commencer ou déclarer
valides l'ordre, les indexes current et la pagination canonique `updatedAt DESC, potId`. Elle n'empêche
pas la construction ni la clôture du snapshot shadow 7.6 sans `updatedAt`.

## Points de code vérifiés

Cette documentation repose sur les inspections ciblées suivantes :

- `JpaPotHeaderEntity`, `JpaShareholderEntity`, `JpaExpenseHeaderEntity`,
  `JpaExpenseShareEntity` ;
- `JpaPotHeaderRepository`, `JpaShareholderRepository`, `JpaExpenseHeaderRepository`,
  `JpaExpenseShareRepository` ;
- `JpaHistoricalPotBalanceSourceAdapter` et `JpaProjectedExpenseAdapter` ;
- `JpaPotCommandEventAppendAdapter`, `RecordedEvent` et les migrations primaires V1/V2.

## Implémentation Lot 7.6

`JpaHistoricalPotSnapshotSourceAdapter` exécute la lecture sous transaction read-only
`REPEATABLE_READ`. `ReconstructPotProjectionService` vérifie les rattachements Pot/Expense/share,
les doublons d'identité et les références de payer/share, puis canonise Shareholders, Expenses et
shares par UUID. Les fractions restent des `Fraction` normalisées ; aucun timestamp ou UUID technique
n'entre dans le contenu ou son digest.
