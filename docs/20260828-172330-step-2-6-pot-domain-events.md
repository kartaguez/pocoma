# Étape 2.6 — Déplacement des événements métier vers `domain-pot`

## Objectif

Déplacer `BusinessEvent` et les dix événements Pot typés de `engine-core` vers
`domain-pot`, sous `com.kartaguez.pocoma.domain.pot.event`, sans modifier leur
comportement ni leur représentation persistée.

`RecordedEvent` et `EventTraceMetadata` restent dans `engine-core` : le premier
décore un fait métier avec son identité et sa date d'enregistrement, le second
porte la trace technique.

## Sous-étapes

1. Créer `domain.pot.event` et y déplacer `BusinessEvent`, les événements Pot et
   les événements Expense, sans alias dans l'ancien package.
2. Adapter `RecordedEvent` pour borner son type générique avec le nouveau
   `BusinessEvent` du domaine.
3. Adapter la production d'événements dans `engine-pot-command` sans changer les
   transactions ni les valeurs publiées.
4. Adapter `engine-task-creation` et `engine-processing-event`; les stratégies
   pures manipulent l'événement métier, tandis que le processing durable utilise
   `RecordedEvent`.
5. Adapter projections, observabilité, runtimes et supras sans déplacer leurs
   responsabilités.
6. Adapter le mapper d'outbox en conservant les discriminants fondés sur les noms
   simples, les payloads JSON et la lecture des données historiques.
7. Supprimer les anciens imports, déclarer les dépendances Maven directes et
   renforcer les tests d'architecture.

## Invariants

- `domain-pot` reste JDK-only, sans Jackson, JPA, Spring, worker ou claim.
- Les noms simples des événements, leurs champs et leurs validations ne changent
  pas.
- `BusinessEventEnvelope`, les mappers JSON et les ports de publication ne sont
  pas déplacés.
- `RecordedEvent` ne porte aucun statut de consommation.
- L'ordre, la segmentation, la clé de consommation Event et la multi-consommation
  par pipeline restent inchangés.
- Aucune migration SQL ou JSON n'est introduite.

## Validation

Tester successivement `domain-pot`, `engine-core`, `engine-pot-command`,
`engine-task-creation`, `engine-processing-event`, `infra-persistence-jpa`,
`observability`, `architecture-tests`, puis le reactor complet. Les tests doivent
couvrir les invariants des dix événements, leur production, leur utilisation pure,
le traitement enregistré, ainsi que la compatibilité de sérialisation de l'outbox.

## Étape suivante

L'étape 2.7 séparera le domaine Pipeline/Task; elle ne fait pas partie de cette
livraison.
