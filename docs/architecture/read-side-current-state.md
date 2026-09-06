# État actuel du read side

Ce document décrit le code exécuté au HEAD `e842f201b9f8effa9cd27d2e30a436590ccce7d5`.
Il constitue l'état de référence du Lot 7.0, pas une architecture cible.

## 1. Résumé exécutif

Le read side HTTP expose six GET, tous dans `supra-http-rest-spring`. Les détails Pot et Expense ne
proviennent pas d'un read model dédié : `engine-query` reconstitue des vues à partir des tables
historisées du modèle primaire (`pot_headers`, `shareholders`, `expense_headers`, `expense_shares`),
avec `pot_global_versions` pour résoudre la version courante.

Les balances sont différentes : elles sont calculées de façon asynchrone à partir du même historique
primaire, puis matérialisées par version. Dans le runtime cible `runtime-web-api`, elles sont lues dans
les artifacts immuables `balance_projection_artifacts` et `balance_projection_entries` du pipeline
Balance configuré. Les queries Balance mélangent donc données primaires pour la version et
l'autorisation, puis projection pour le résultat.

Le pipeline cible est durable : `business_event_outbox` → Event consumption → `tasks_4_pipeline` →
Task consumption → calcul complet de la version demandée → artifact Balance immuable. Slots, claims,
retry et fencing vivent dans les tables génériques de consumption ; les anciennes colonnes lifecycle
des tables Event/Task ne font pas autorité.

`runtime-monolith` est transitionnel : ses queries Balance reçoivent encore `JpaPotBalancesAdapter`
et lisent les anciennes tables `pot_balance_*`. Le runtime web cible les remplace par un adapter
`@Primary` immuable. Les GET utilisent encore les headers `X-User-Id` / `X-User-Scopes`, tandis que
l'admission Command utilise OAuth2 Resource Server.

## 2. Carte globale

### Lecture HTTP actuelle

```text
PotsQueryController / ExpensesQueryController                 supra-http-rest-spring
  -> UserContextFactory (X-User-Id + X-User-Scopes)
  -> six ports entrants de query                               engine-query
  -> wrappers transactionnels + services de query             engine-query
  -> PotQueryPort / ExpenseQueryPort / PotBalancesQueryPort
  -> JpaPotQueryAdapter / JpaExpenseQueryAdapter               infra-persistence-jpa
       -> pot_global_versions + tables primaires historisées
  -> JpaImmutablePotBalancesQueryAdapter                       runtime-web-api, @Primary
       -> balance_projection_artifacts + balance_projection_entries
  -> RestMapper + DTO HTTP                                     supra-http-rest-spring
```

Dans `runtime-monolith`, le dernier adapter Balance est au contraire `JpaPotBalancesAdapter`, qui lit
`pot_balance_versions` et `pot_balances`.

### Projection Balance cible actuellement exécutée

```text
BusinessEvent durable dans business_event_outbox
  -> runtime-event-consumption-worker
  -> EventConsumptionLocator
  -> CreateTasksForEventService
  -> BalanceTaskCreationStrategy
  -> event_4_pipeline_materialization_status + tasks_4_pipeline
  -> runtime-task-consumption-worker
  -> TaskConsumptionLocator
  -> ComputeBalancesRecordedTaskMapper
  -> ExecuteTaskService
  -> ExecuteBalanceProjectionTaskHandler
  -> CalculatePotBalancesAtVersionService
  -> JpaHistoricalPotBalanceSourceAdapter
       -> pot_headers + shareholders + expense_headers + expense_shares @ targetVersion
  -> PotBalancesCalculator (calcul en mémoire)
  -> JpaImmutableBalanceProjectionAdapter
       -> balance_projection_artifacts + balance_projection_entries
```

Les deux locators utilisent `SequentialConsumptionOrchestrator` et le lifecycle générique
`ConsumptionSlot`/`Claim`. La découverte est best effort ; la relecture autoritative et la
terminalisation sont transactionnelles.

## 3. Inventaire des endpoints

| Endpoint | Use case | Source réellement lue dans `runtime-web-api` | Versionnable ? | Projection ? |
|---|---|---|---|---|
| `GET /api/pots` | `ListUserPotsUseCase` | `pot_headers`, `pot_global_versions`, `shareholders` | Non : version courante de chaque Pot | Non |
| `GET /api/pots/{potId}` | `GetPotUseCase` | `pot_global_versions`, `pot_headers`, `shareholders` | Oui, `?version=` ; sinon courante | Non |
| `GET /api/pots/{potId}/expenses` | `ListPotExpensesUseCase` | `pot_global_versions`, `pot_headers`, `shareholders`, `expense_headers` | Oui, `?version=` ; sinon courante | Non |
| `GET /api/expenses/{expenseId}` | `GetExpenseUseCase` | `expense_headers`, `pot_global_versions`, `pot_headers`, `shareholders`, `expense_shares` | Oui, `?version=` ; sinon version courante du Pot | Non |
| `GET /api/pots/{potId}/balances` | `GetPotBalancesUseCase` | primaire pour version/autorisation, puis artifacts Balance immuables | Oui, `?version=` ; sinon courante | Oui |
| `GET /api/pots/balances/me` | `ListUserPotBalancesUseCase` | Pots/shareholder primaires, puis artifacts Balance immuables | Oui, la même version demandée pour chaque Pot ; sinon courante par Pot | Oui |

Il n'existe actuellement aucun GET autonome pour un Shareholder, aucune route de lecture d'archive et
aucun endpoint utilisateur distinct. Les DTO sont construits par `RestMapper`; celui-ci stabilise
notamment l'ordre des collections Shareholder, ExpenseShare et Balance dans les réponses.

Tous ces GET construisent un `UserContext` depuis les headers legacy requis `X-User-Id` et
`X-User-Scopes` (permissions séparées par `;`, au format `OBJECT:ACTION`). Le Resource Server protège
`POST /api/v1/commands`; sa configuration laisse les autres routes au mécanisme de headers actuel.

## 4. Inventaire des queries

### `ListUserPotsUseCase`

- Service : `ListUserPotsService`.
- Port : `PotQueryPort.listAccessiblePotHeaders`.
- Adapter : `JpaPotQueryAdapter`.
- Sources : header actif à la version courante jointe via `pot_global_versions`, plus existence d'un
  Shareholder actif lié à l'utilisateur. Les Pots supprimés et liens Shareholder supprimés sont exclus.
- Résultat : headers courants accessibles, ordonnés par label puis `potId`.

### `GetPotUseCase`

- Service : `GetPotService`.
- Ports : `PotQueryPort.currentVersion`, `loadPotHeaderAtVersion`,
  `loadPotShareholdersAtVersion`.
- Sources : `pot_global_versions`, `pot_headers`, `shareholders`.
- Reconstruction : un header et toutes les lignes Shareholder temporellement actives à la même version.
  Les enregistrements portant `deleted=true` restent présents dans la vue directe ; seuls les
  Shareholders non supprimés participent à la décision d'autorisation contextuelle.

### `ListPotExpensesUseCase`

- Service : `ListPotExpensesService`.
- Ports : `PotQueryPort` pour version et autorisation, puis
  `ExpenseQueryPort.listExpenseHeadersByPotAtVersion`.
- Sources : tables Pot/Shareholder primaires et `expense_headers`.
- Reconstruction : uniquement les Expense headers temporellement actifs et `deleted=false`, ordonnés
  par version de début puis `expenseId`.

### `GetExpenseUseCase`

- Service : `GetExpenseService`.
- Ports : `ExpenseQueryPort` et `PotQueryPort`.
- Sources : `expense_headers`, `expense_shares`, `pot_global_versions`, `pot_headers`, `shareholders`.
- Version explicite : le header Expense détermine le Pot ; tous les éléments sont chargés à cette
  version.
- Version absente : le header Expense courant identifie le Pot, la version globale courante du Pot est
  résolue, puis le header et les shares sont relus à cette version.
- Comme le GET Pot, le GET direct ne filtre pas le flag `deleted` du header chargé.

### `GetPotBalancesUseCase`

- Service : `GetPotBalancesService`.
- Ports : `PotQueryPort` pour version et autorisation, puis `PotBalancesQueryPort.loadAtVersion`.
- Runtime web cible : `JpaImmutablePotBalancesQueryAdapter`, configuré par
  `pocoma.query.balance.pipeline-id` (défaut `balance-projection`) et la version de pipeline obligatoire.
- Sources : tables primaires Pot/Shareholder puis identité exacte dans
  `balance_projection_artifacts`, et entrées dans `balance_projection_entries`.
- Aucun calcul Balance n'est effectué pendant le GET.

### `ListUserPotBalancesUseCase`

- Service : `ListUserPotBalancesService`.
- Départ : Pots courants, accessibles et non supprimés.
- Pour chaque Pot : choisit la version explicite commune ou sa version courante, cherche le Shareholder
  actif lié à l'utilisateur, charge la projection exacte et ne renvoie que son entrée Balance.
- Un Pot sans lien Shareholder ou sans entrée Balance est omis.
- Le service prévoit aussi d'omettre une projection absente lorsqu'elle est signalée par
  `BusinessEntityNotFoundException`. L'adapter immuable du runtime web signale actuellement une
  cardinalité différente de un par `IllegalStateException` : une projection absente y produit donc une
  erreur technique au lieu de cette omission. C'est un écart factuel à traiter dans un lot ultérieur.

## 5. Sources de vérité actuelles

### Modèle primaire d'écriture, également lu

| Table | Rôle de lecture actuel |
|---|---|
| `pot_global_versions` | Version courante autoritative de chaque Pot |
| `pot_headers` | Versions temporelles des attributs et du flag de suppression du Pot |
| `shareholders` | Versions temporelles des Shareholders, liens utilisateur, poids et suppression |
| `expense_headers` | Versions temporelles des attributs Expense et suppression |
| `expense_shares` | Versions temporelles des répartitions d'une Expense |

Ces tables sont le modèle primaire mutable par ajout/fermeture de versions. Elles ne deviennent pas
des projections parce qu'elles sont interrogées par `engine-query`.

### Modèles de lecture/projection

| Tables | Producteur | Lecteur actuel | Statut |
|---|---|---|---|
| `balance_projection_artifacts`, `balance_projection_entries` | Task Balance cible | `JpaImmutablePotBalancesQueryAdapter` dans `runtime-web-api` | Projection cible, immuable et versionnée par pipeline/Pot |
| `pot_balance_projection_states`, `pot_balance_versions`, `pot_balances` | ancien moteur/worker Balance | `JpaPotBalancesAdapter` dans `runtime-monolith` | Projection legacy encore câblée dans le monolithe |

`business_event_outbox`, `event_4_pipeline_materialization_status` et `tasks_4_pipeline` sont des
données durables de transport/pipeline, pas des read models HTTP. `consumption_slots`,
`consumption_claims` et la provenance portent le lifecycle technique, pas une vue métier.

### Calculs dérivés

Les vues Pot et Expense sont assemblées à la demande à partir de plusieurs familles de lignes
historiques ; aucun snapshot Pot complet n'est stocké. Les balances, en revanche, sont calculées hors
requête par `PotBalancesCalculator` à partir d'un ensemble historique complet, puis matérialisées.
Le GET Balance ne recalcule jamais le résultat.

## 6. Modèle temporel

La version globale d'un Pot est un entier positif dans `pot_global_versions`. Les autres objets sont
des fragments historisés portant `started_at_version` inclusif et `ended_at_version` exclusif. Une
ligne est active pour `v` lorsque :

```text
started_at_version <= v
and (ended_at_version is null or v < ended_at_version)
```

Une mutation ferme les lignes affectées à la version suivante et insère leurs nouvelles versions.
Le read side reconstruit donc un Pot à `v` en sélectionnant séparément le header, les Shareholders,
les Expense headers et/ou les Expense shares actifs à `v`. Il n'existe pas de snapshot complet par
version.

Sans paramètre `version`, les queries Pot prennent `pot_global_versions.version`. Le GET Expense doit
d'abord retrouver le Pot depuis son header courant avant d'utiliser cette version globale. Une version
explicite n'est pas ramenée automatiquement à la version courante.

Les suppressions sont des états historisés (`deleted=true`), pas des suppressions physiques. Les listes
de Pots et d'Expenses les masquent explicitement. Les GET directs chargent la ligne temporellement
active et peuvent donc restituer son flag `deleted`. Pour l'autorisation contextuelle, seuls les
Shareholders non supprimés sont pris en compte.

## 7. Pipeline Balance actuel

1. La transaction Command gagnante ajoute l'Event typé dans `business_event_outbox` avec la mutation
   primaire.
2. `runtime-event-consumption-worker` découvre l'Event pour le pipeline configuré sans utiliser le
   statut lifecycle legacy de l'outbox. `AcquireConsumption` arbitre avec la clé
   `EVENT[eventId] / PIPELINE[pipelineId,pipelineVersion]`.
3. Après acquire, `EventConsumptionLocator` recharge l'Event. `BalanceTaskCreationStrategy` accepte
   actuellement tout `BusinessEvent` Pot et produit une Task `COMPUTE_BALANCES_FOR_VERSION` dont la
   cible est `potId:eventVersion`.
4. `JpaTaskCreationAdapter` matérialise idempotemment le couple Event/pipeline dans
   `event_4_pipeline_materialization_status` et la Task dans `tasks_4_pipeline`. Une matérialisation
   legacy existante peut être adoptée.
5. `runtime-task-consumption-worker` découvre les Tasks structurelles de ce pipeline/type ; leurs
   colonnes `status`, claim et lease legacy ne sont pas l'autorité. La clé générique est
   `TASK[taskId] / TASK_EXECUTOR[]`.
6. Après acquire, `TaskConsumptionLocator` recharge la Task. `ComputeBalancesRecordedTaskMapper`
   valide que son payload correspond au `potId` et à `targetVersion` durables.
7. `ExecuteBalanceProjectionTaskHandler` appelle `CalculatePotBalancesAtVersionService`.
   `JpaHistoricalPotBalanceSourceAdapter` relit le header, les Shareholders et toutes les Expenses non
   supprimées avec leurs shares à la version cible. `PotBalancesCalculator` effectue alors un calcul
   complet en mémoire ; ce n'est ni un delta ni un calcul pendant le GET.
8. `JpaImmutableBalanceProjectionAdapter` crée ou vérifie l'artifact identifié par
   `(POT_BALANCES, pipelineId, pipelineVersion, potId, potVersion)` et ses entrées. Un contenu différent
   sous la même identité est une erreur d'invariant.
9. Projection, provenance, fencing et `DONE/SUCCESS` sont atomiques. Des versions différentes d'un Pot
   peuvent terminer hors ordre, car chaque artifact est immuable et indépendant.
10. `runtime-web-api` relit uniquement l'artifact de la version de pipeline configurée. Une nouvelle
    version de pipeline ne remplace pas implicitement les artifacts d'une autre version.

Les runtimes `runtime-event-consumption-worker` et `runtime-task-consumption-worker` sont la chaîne
distribuée cible. Les anciens runtimes fondés sur les colonnes lifecycle de `business_event_outbox`,
sur `projection_tasks` et sur le worker Spring Balance restent présents dans le repository à titre
transitionnel, mais ne constituent pas le chemin Balance lu par `runtime-web-api`. La table
`business_event_outbox` elle-même reste la source durable active des Events pour la chaîne cible.

## 8. Couplages et limites actuelles

### Factuel

- Quatre des six query families lisent directement le modèle primaire historisé ; les deux queries
  Balance combinent modèle primaire et projection.
- `runtime-web-api` dépend d'`infra-persistence-jpa` et sélectionne directement son adapter Balance
  immuable dans une configuration de runtime.
- L'identité et les permissions des GET proviennent de headers legacy librement formés ; seul le POST
  Command utilise le principal Resource Server.
- La reconstruction temporelle assemble plusieurs sous-objets ; aucun snapshot Pot complet ne garantit
  à lui seul la cohérence d'une version.
- Deux persistences Balance et deux compositions de lecture coexistent : immutable pipeline dans le
  runtime web cible, `pot_balance_*` dans le monolithe transitionnel.
- Le pipeline cible réutilise les tables `business_event_outbox` et `tasks_4_pipeline` comme données
  structurelles tout en ignorant leurs colonnes lifecycle legacy.
- L'absence d'artifact immuable n'est pas traduite de la même manière que l'absence de projection prévue
  par `ListUserPotBalancesService`.
- Les anciens modules et tables `projection_tasks`/workers restent dans le reactor et dans des
  compositions historiques, mais ne sont pas utilisés par le chemin distribué cible
  Event→Task→Balance.

### Candidats pour les lots 7.x

- Définir la source canonique des vues Pot/Expense au lieu de maintenir implicitement un read side sur
  le write store.
- Unifier la lecture Balance et décider du devenir de la projection `pot_balance_*` du monolithe.
- Définir la sémantique explicite d'une projection absente ou en retard.
- Remplacer la confiance dans les headers GET par une frontière d'identité authentifiée cohérente.
- Décider si les vues temporelles restent reconstruites par fragments ou deviennent des snapshots/read
  models dédiés.
- Après choix de la cible, isoler puis retirer les composants de projection legacy réellement devenus
  sans consommateurs.

Ces points sont des sujets, pas des décisions de ce document.

## 9. Frontière avec le Lot 6

Le write side est clos selon [write-side-closure.md](write-side-closure.md). `POST /api/v1/commands`
reste l'unique voie canonique de mutation : admission durable, puis exécution par le Command worker.
Le Lot 7 ne doit réintroduire aucune mutation primaire directe depuis HTTP.

Le futur read side devra consommer ou projeter les données produites par cette chaîne sans modifier les
invariants de `RecordedCommand`, du lifecycle générique de consumption, de la transaction gagnante ou
de l'append atomique des Business Events. Le présent audit n'a modifié aucun de ces composants.
