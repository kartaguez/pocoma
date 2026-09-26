# État actuel du read side

## 1. Portée de l'observation

Ce document décrit l'état courant du repository après livraison du lifecycle minimal du Lot 7.14.1.
Il est factuel : les décisions normatives appartiennent à
[read-side-target.md](read-side-target.md), et leur séquencement au
[plan directeur du Lot 7](../plans/lot-7-read-side-implementation-plan.md).

## 2. Résumé

Le read side est en transition :

- les six GET existants restent basés sur `engine-query` et lisent encore le primaire pour Pot,
  Expense, version courante et autorisation ;
- `runtime-web-api` lit les résultats Balance dans des artifacts immuables dédiés, mais demande encore
  au primaire la version et le contexte Pot ;
- le pipeline `read-pot/v1` matérialise en shadow un `PotProjection` canonique complet dans
  `pocoma_read` ;
- l'index versionné user→Pot et la pagination keyset existent en shadow, sans GET actif ;
- `latestKnownVersion` est produit par son consumer Event direct et indépendant ;
- les contrats framework-free du Query Kernel (`CURRENT`/`EXACT`, sélection monoprojection de la
  génération serving, état terminal, latest-known read-only et `VersionedQueryResponse`) sont
  présents dans `engine-query` ; le resolver monoprojection CURRENT/EXACT et ses quatre résultats
  typés sont livrés, sans adapter de persistence ni branchement aux GET actifs ;
- le control store autoritatif `pocoma_control` persiste les activations et la sélection serving ;
  Event et Task filtrent les générations inactives en discovery et verrouillent leur activation dans
  la transaction de Claim ; après acquisition, l'exécution est indépendante du lifecycle ;
- l'Authorization Kernel, `AUTH(V)`, l'éligibilité serving et le cutover gouverné n'existent pas encore.

## 3. GET réellement exposés

| Endpoint | Source actuelle dans `runtime-web-api` | Version courante | État cible |
|---|---|---|---|
| `GET /api/pots` | headers/shareholders primaires | une version primaire par Pot | Pas migré |
| `GET /api/pots/{potId}` | primaire historisé | `pot_global_versions` | Pas migré |
| `GET /api/pots/{potId}/expenses` | primaire historisé | `pot_global_versions` | Pas migré |
| `GET /api/expenses/{expenseId}` | primaire historisé | Pot retrouvé depuis l'Expense | Route globale legacy |
| `GET /api/pots/{potId}/balances` | primaire pour version/auth, artifact Balance pour data | `pot_global_versions` | Partiellement migré |
| `GET /api/pots/balances/me` | liste primaire puis lookup Balance par Pot | une version primaire par Pot | Partiellement migré, N+1 |

Tous ces GET construisent encore un `UserContext` depuis `X-User-Id` et `X-User-Scopes`. Le Resource
Server OAuth2 protège l'admission Command, pas encore les reads.

### Sémantique current réellement exécutée

Les services `GetPotService`, `ListPotExpensesService`, `GetExpenseService` et
`GetPotBalancesService` choisissent la version primaire lorsque le paramètre est absent. Les listes
partent également des données primaires courantes.

Les contrats `QueryVersionIntent`, `QueryProjectionSelection`, `TerminalProjectionState`,
`QueryVersionResolution` et `VersionedQueryResponse` existent désormais, mais aucun GET actif ne les
utilise. `QueryVersionResolver` résout CURRENT depuis le plus haut état terminal `READY | FAILED`
d'une génération serving exacte sous latest-known, et EXACT depuis l'applicabilité puis le statut
exact. Aucun adapter des ports read-only n'est encore branché et aucune enveloppe versionnée n'est
retournée en production.

Une Balance exacte absente dans `JpaImmutablePotBalancesQueryAdapter` produit actuellement une
`IllegalStateException`. Elle n'est pas encore traduite en état normal de Query Kernel.

## 4. Pipelines et consumers présents

### LatestKnownVersion

`runtime-latest-known-version-consumption-worker` consomme directement les Business Events sous la
clé de compatibilité :

```text
EVENT[eventId] / SOURCE_VERSION_WATERMARK[]
```

Le locator recharge l'Event autoritatif et avance
`pocoma_read.source_version_watermarks.latest_version_seen` par max-upsert. Update, provenance et CAS
terminal sont transactionnels. Aucun Task ou artifact métier n'est créé.

Le nom Java canonique est `LatestKnownVersion`; les noms consumer/SQL restent legacy pour
compatibilité. Les tests couvrent duplicate, traitement hors ordre, rollback, retry et fencing.

### Event vers Tasks de projection

`runtime-event-consumption-worker` découvre les conséquences `EventId × ProjectionType` dues depuis
les seules métadonnées durables. Son unique configuration fonctionnelle est un set explicite de
`ProjectionType`; les EventTypes/routes sont dérivés de la policy canonique.

Après acquire, une finalisation courte et fenced assure la `ProjectionTask`, termine le Claim en
`SUCCESS` et le slot en `DONE/SUCCESS` dans la même transaction. Le runtime ne recharge pas le payload,
ne consulte aucune pipeline generation et n'écrit ni `tasks_4_pipeline` ni provenance Event legacy.

### Exécution Task multi-pipeline

`runtime-task-consumption-worker` utilise la clé générique :

```text
TASK[taskId] / TASK_EXECUTOR[]
```

Une instance est configurée pour une génération exacte et accepte actuellement les bindings
`balance-projection/v2` ou `read-pot/v1`. Reload, calcul, persistence, provenance et terminalisation
fencée sont atomiques dans la composition déployée.

## 5. Modèle générique effectivement livré

Le module `domain-projection` contient :

- `ProjectionIdentity` et `ProjectionGenerationIdentity` ;
- `ProjectionArtifactDescriptor` ;
- `ProjectionFailure` ;
- `ProjectionHead` ;
- `ProjectionInvariantViolation` ;
- `ProjectionStatus` ;
- `LatestKnownVersion` et `PotProjection`.

`ProjectionMaterializationService` vérifie l'applicabilité exacte, adopte un contenu identique,
conserve l'artifact READY et enregistre une violation sur duplicate divergent, refuse de remplacer
une failure terminale et écrit artifact, descriptor et head dans la transaction read-store.

`ProjectionStatusResolver` dérive `READY`, `FAILED` ou `NOT_READY` depuis artifact/failure. Il ne lit
aucun lifecycle Task/Slot. Le head avance par maximum et accepte les trous.

La table `projection_coverages`, introduite dans une première migration, est supprimée par V3. Aucun
coverage persisté ou curseur de continuité n'est actif.

## 6. Projections et read models disponibles

### READ_POT

Le pipeline `read-pot/v1` produit un artifact logique `READ_POT` complet à une business version exacte :

- statut Pot, label et creator ;
- Shareholders, user links, poids et deleted ;
- Expenses, payers, montants et deleted ;
- Expense shares ;
- `PotVersionMetadata.createdAt` exact.

La représentation physique utilise quatre tables V5 dans `pocoma_read`, mais constitue un seul
composant logique. `JpaHistoricalPotSnapshotSourceAdapter` relit le primaire à la version exacte ;
`JdbcPotProjectionArtifactWriter` matérialise le snapshot, les fragments, le descriptor, le head et
les index nécessaires.

Le delete du Pot est terminal côté write depuis le correctif 7.6 ; le snapshot de suppression reste
matérialisable avec le statut `DELETED`.

### Index user→Pot

La migration read-store V6 ajoute `pot_version_metadata` et
`pot_projection_user_index`. L'index est versionné, scopé par génération et produit atomiquement avec
READ_POT. Son ordre keyset est :

```text
updatedAt DESC, potId ASC
```

`JdbcPotUserIndexReader` est seulement shadow. Sa requête actuelle impose encore
`index.potVersion = source_version_watermarks.latest_version_seen`. La cible conserve latest-known
comme borne d'exposition, mais ne traite plus cette égalité comme la sélection CURRENT : chaque ligne
d'index n'est qu'un candidat. Sa projection métier doit être résolue en CURRENT, puis AUTH demandé
séparément en `EXACT(servedVersion)`. Aucun GET actif ne dépend encore de ce reader.

### BALANCE immuable

Le pipeline distribué `balance-projection/v2` :

```text
BusinessEvent -> Task -> CalculatePotBalancesAtVersionService
              -> balance_projection_artifacts / entries
```

Le calcul recharge l'état primaire exact et recalcule entièrement la Balance à V. Il ne dépend pas de
Balance(V-1), supporte l'exécution hors ordre et couvre toutes les business versions pertinentes du
catalogue actuel.

Cette persistence reste toutefois spécifique : elle vit dans le schéma primaire, possède sa propre
identité/adoption et n'utilise pas encore artifact/failure/head/index génériques. Le provider serving
canonique sait construire `QueryProjectionSelection` depuis le control store ; les GET actifs ne
l'utilisent toutefois pas encore.

### BALANCE legacy

`runtime-monolith` utilise toujours `JpaPotBalancesAdapter`, les tables mutables
`pot_balance_projection_states`, `pot_balance_versions` et `pot_balances`, ainsi que le calcul
incrémental qui part du précédent head. Ce chemin dépend fonctionnellement d'un état antérieur et ne
respecte pas la cible hors ordre. Il reste legacy actif jusqu'au cutover.

### Projections absentes

Il n'existe actuellement :

- ni projection complète `AUTH(V)` par businessVersion ;
- ni projection Expense ou Shareholder autonome ;
- ni index transverse Balance pour remplacer le N+1 de `balances/me`.

## 7. Ordre et indépendance observés

Les tris des discoveries Event/Task et des collections sont utilisés pour pagination, fairness ou
déterminisme. Aucun chemin cible READ_POT, BALANCE immuable ou latest-known n'attend N-1 avant N.

Les pipelines READ_POT, BALANCE et latest-known ne consultent ni le head ni la fin d'un autre
pipeline. Leur production converge indépendamment. Le futur besoin d'AUTH pour autoriser une réponse
sera une composition de query, pas une dépendance de production.

Le seul chemin métier encore séquentiel est le calcul Balance legacy du monolithe.

## 8. Reconstruction réellement disponible

Le mécanisme générique déjà présent couvre la propriété canonique attendue : ajouter une nouvelle
`pipelineVersion` applicable entraîne la redécouverte de l'historique Event correspondant et la
création de Tasks propres à cette génération.

Il n'existe aucun système autonome de rebuild/replay/backfill, aucune campagne administrative et
aucun reset de Tasks/Slots/Claims. Cette absence est conforme à la cible réconciliée : une perte ou une
rematérialisation doit utiliser une nouvelle pipelineVersion.

La reconstruction suppose que l'historique durable nécessaire est conservé. La migration primaire
V11 refuse une base legacy possédant des versions sans `PotVersionMetadata.createdAt` exact ; elle
demande un reset de ces données de développement plutôt qu'un timestamp approximatif.

## 9. Sécurité réellement disponible

Les policies actuelles évaluent les permissions passées dans le `UserContext` et les faits Pot lus du
primaire. Elles ne séparent pas encore explicitement `TokenCapabilities` et
`PotAuthorizationAtVersion`.

Les permissions `VIEW_ARCHIVE` cibles ne sont pas toutes définies, notamment pour Balance. Aucun
pipeline ou artifact AUTH exact n'existe. Les GET peuvent donc encore révéler existence/readiness
selon leur logique legacy avant la future résolution métier suivie, pour une query protégée, d'AUTH
en `EXACT(servedVersion)`.

## 10. Écarts restants vers la cible

| Cible | État actuel |
|---|---|
| Contrats Query Kernel `CURRENT` / `EXACT(V)` | Présents, framework-free, non branchés |
| Resolver Query Kernel `CURRENT` / `EXACT(V)` | Présent, non branché aux GET |
| Plus haut état terminal de l'unique génération serving, borné par latest-known | Resolver présent ; adapters readiness/latest-known encore non branchés |
| `VersionedQueryResponse` | Présent, non utilisé par les GET actifs |
| Liste exclusivement issue de l'index convergent | Reader shadow encore joint à latest-known |
| AUTH indépendante | Absente |
| AUTH complet à chaque businessVersion | Absent |
| GET sans lecture primaire | Aucun cutover effectué |
| Balance sur fondation générique | Production immuable présente, persistence générique absente |
| declared/active/serving | Lifecycle minimal livré : catalogue declared, activations et serving persistés, gates Claim actifs |
| `eligibleForServing` | Absent |
| Cutover manuel gouverné | Absent |
| Observabilité cible | Partielle ; consumption et métriques legacy seulement |
| Extinction legacy | Non commencée |
