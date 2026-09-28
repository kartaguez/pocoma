# Read side — état courant

Ce document décrit l'état exécutable après PCL.4. Les documents de décision et de cible restent les
références pour les lots futurs ; cette page ne crée pas une nouvelle architecture Query.

## 1. Frontière HTTP

Le runtime Web n'expose plus les six lectures synchrones legacy :

- `GET /api/pots` ;
- `GET /api/pots/{potId}` ;
- `GET /api/pots/{potId}/expenses` ;
- `GET /api/expenses/{expenseId}` ;
- `GET /api/pots/{potId}/balances` ;
- `GET /api/pots/balances/me`.

Leurs controllers, DTO de réponse, mapper, use cases, services, ports, policies d'autorisation et
wiring Spring ont été retirés. Les security schemes OpenAPI `X-User-Id` et `X-User-Scopes` ont
également disparu. L'admission Command asynchrone `POST /api/v1/commands` reste inchangée.

## 2. Lecture canonique exacte conservée

La capacité autoritaire de lecture d'une projection exacte reste :

```text
ReadPotService
  -> ExactProjectionReadUseCase / ExactProjectionReadService
  -> ProjectionReadPort
  -> JdbcProjectionStoreAdapter
  -> pocoma_read.projection_root
     + pocoma_read.projection_artifact
     + pocoma_read.projection_failure
  -> ReadPotInterpreter
```

Cette chaîne demande une identité et une version exactes. Elle valide l'artifact canonique et ne
retombe ni sur les tables du write model, ni sur les anciennes tables metadata, ni sur une façade
Query de compatibilité.

Les producteurs canoniques `READ_POT` et `POT_BALANCES`, leurs contrats, le moteur
`ProjectionEngineService`, les ProjectionTasks et leurs tests restent en place.

## 3. Read store conservé

`infra-read-persistence` reste un module utile. Il porte :

- le bootstrap et les migrations Flyway append-only du schéma `pocoma_read` ;
- l'accès `LatestKnownVersion` encore consommé par son runtime dédié ;
- l'intégration du store de projections exactes, dont l'adapter vit dans
  `infra-projection-persistence` et est testé contre les migrations du read store.

Les migrations V1 à V7 ne sont pas réécrites. Elles conservent donc les tables historiques déjà
livrées, mais aucun code de production PCL.4 ne lit plus `projection_artifacts`,
`projection_failures`, `projection_heads`, `projection_invariant_violations` ou les tables
`pot_projection_*`.

`ProjectionMetadataPort`, `JdbcProjectionMetadataAdapter`, le reader user/Pot, ses curseurs et son
wiring ont été supprimés. `domain-projection-legacy` ne contient plus que `LatestKnownVersion`, en
attendant son extraction prévue par PCL.5.

## 4. Adapter Balance mixte

`JpaPotBalancesAdapter` reste nécessaire à la projection Balance incrémentale historique. Il
implémente uniquement `PotBalanceProjectionPort`. Sa méthode `loadAtVersion` est conservée parce
qu'elle est requise par ce port de projection et par `ComputePotBalancesService` ; elle n'expose plus
`PotBalancesQueryPort`.

Les tables JPA `pot_balance_projection_states`, `pot_balance_versions` et `pot_balances` restent
donc hors de la démolition Query/read PCL.4. Leur retrait éventuel appartient aux lots de projection
legacy ultérieurs.

## 5. Frontières de persistance

Les repositories et tables du write model Pot/Expense restent autoritaires pour les Commands et la
reconstruction historique. Seules les méthodes de repository exclusivement appelées par les
adapters Query supprimés ont disparu. Aucun schéma primaire ni read-store n'est supprimé par PCL.4.

## 6. Preuves structurelles

`Pcl4LegacyQueryReadAbsenceTest` verrouille :

- l'absence du module et de l'artifact Maven `engine-query` ;
- l'absence des types, wiring et propriétés legacy dans le code de production ;
- l'absence d'accès runtime aux anciennes tables read-store, migrations exclues ;
- la conservation de `LatestKnownVersion`, de l'adapter Balance de projection et de la chaîne de
  lecture exacte canonique.

`WriteSideHttpClosureTest` vérifie en plus l'absence des six opérations GET et des anciens headers
de sécurité dans l'OpenAPI.

## 7. Hors périmètre

PCL.4 n'ajoute aucun endpoint de lecture, aucune sélection `CURRENT`, aucune nouvelle policy AUTH et
aucune façade. Une future exposition fonctionnelle devra consommer directement les capacités
canoniques décidées par les lots correspondants, sans restaurer la structure supprimée.
