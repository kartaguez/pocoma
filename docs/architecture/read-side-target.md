# Architecture cible du read side

## 1. Autorité et périmètre

Ce document est la référence normative du Lot 7. Il décrit la cible décidée, pas nécessairement le
code déjà livré. L'[état actuel](read-side-current-state.md) inventorie l'implémentation réellement
présente et le [plan directeur](../plans/lot-7-read-side-implementation-plan.md) séquence l'écart restant.

En cas de contradiction, le présent document prévaut pour l'architecture read-side. Les invariants
du write side restent définis dans [write-side-closure.md](write-side-closure.md).

La cible couvre :

- la consommation indépendante des Business Events et Tasks ;
- les projections versionnées et leurs indexes dérivés ;
- les lectures `CURRENT` et `EXACT(V)` ;
- l'autorisation courante et historique ;
- la coexistence et le cutover des versions de pipeline ;
- l'observabilité et l'extinction du legacy.

## 2. Invariant transversal d'ordre et de convergence

Le read side doit rester correct quel que soit l'ordre de traitement des Events et des Tasks.

- Aucune projection ne dépend fonctionnellement de `N-1` pour produire `N`.
- Aucune FIFO globale ou par Pot n'est requise pour la correction.
- Les trous sont autorisés.
- Retry, duplicate et traitement hors ordre convergent par identité exacte.
- Les artifacts sont immuables et idempotents ; un contenu divergent sous la même identité est une
  violation, jamais un overwrite.
- Les heads et autres états courants monotones avancent par maximum et ne prouvent aucune continuité.
- Chaque pipeline produit et converge indépendamment des autres pipelines.

L'ordre peut être utilisé pour la pagination, la fairness, la reproductibilité d'un digest ou
l'efficacité d'un parcours. Il ne devient pas pour autant une précondition fonctionnelle.

## 3. Sources et frontière du read store

Le write side versionné et les Business Events durables restent les sources autoritatives. Le read
store ne devient jamais un second primaire : tout état métier qu'il contient est dérivé et
reconstructible depuis l'historique durable.

Le chemin normal d'un GET cible ne lit ni ne joint le modèle primaire. Les projectors peuvent relire
l'historique primaire exact afin de matérialiser une projection. Le write side n'écrit jamais une
projection ou un index read-side.

Une matérialisation rend atomiquement visibles, dans la frontière transactionnelle retenue :

```text
artifact logique complet
+ descriptor
+ ProjectionHead éventuel
+ indexes indispensables à son exposition
```

Un reader ne doit jamais observer `READY` sans artifact complet ni un index fonctionnel pointant vers
un artifact invisible.

## 4. LatestKnownVersion

Pour un Pot :

```text
latestKnownVersion
= plus grande version de BusinessEvent effectivement matérialisée
  par le consumer latest-known-version du read side
```

La connaissance est matérialisée par un consumer Event direct, court et transactionnel :

```text
BusinessEvent
  -> consumer latest-known-version
  -> moteur générique de consumption
  -> claim / lease / fencing / retry
  -> max-upsert(potId, event.version)
```

Le consumer ne crée aucune Task, ne produit aucune projection métier et n'appelle aucun service
externe. Sa mise à jour, sa provenance et sa terminalisation fencée commit ou rollback ensemble.

`latestKnownVersion` est unique par Pot, mutable, monotone, idempotent, sûr hors ordre et indépendant
de tous les pipelines métier.

Il ne prouve pas :

- que toutes les versions inférieures ou égales ont été observées ;
- qu'un artifact existe à cette version ;
- qu'une projection ou une autorisation est prête ;
- qu'un autre consumer a progressé.

Il ne doit jamais servir de gate au scheduling, à l'acquire, à l'exécution d'une Task ou à la
matérialisation. Ces états internes sont normaux :

```text
latestKnownVersion = 15, best READY READ_POT = 13
best READY READ_POT = 15, latestKnownVersion = 14
```

Production et exposition ont toutefois des règles distinctes :

```text
PRODUCTION : peut matérialiser une businessVersion > latestKnownVersion
EXPOSITION : ne sert jamais une businessVersion > latestKnownVersion
```

`latestKnownVersion` est donc une borne supérieure d'exposition, sans devenir une preuve de
continuité ou de readiness. Son absence rend toute lecture versionnée `NOT_READY`.

Les identifiants persistés `SOURCE_VERSION_WATERMARK` et
`source_version_watermarks.latest_version_seen` restent compatibles avec le code et les données
existantes. Ce sont des noms physiques legacy ; ils ne définissent plus une sémantique de watermark
de continuité.

## 5. Modèle générique de projection

L'identité d'un artifact est complète :

```text
projectionType
pipelineId
pipelineVersion
potId
potVersion
```

Une `PipelineVersionDefinition` associe `pipelineId + pipelineVersion` à sa plage d'applicabilité sur
les business versions. Toute définition applicable produit sa propre Task et son propre artifact,
indépendamment de la version éventuellement exposée aux clients.

Pour une identité applicable, le statut est dérivé :

- artifact complet présent : `READY` ;
- failure terminale présente : `FAILED` ;
- ni artifact ni failure : `NOT_READY`.

Une définition non applicable n'est pas `NOT_READY` : elle ne désigne pas de projection attendue à
cette version. Tasks, slots, claims, leases et retries restent des états opérationnels et ne sont
jamais consultés pour dériver ce statut.

### ProjectionHead

`ProjectionHead.latestProjectedVersion` est la plus grande business version matérialisée avec succès
pour une identité de génération :

```text
projectionType + pipelineId + pipelineVersion + potId
```

Le head est un maximum observé, pas un watermark de continuité. Si 44 et 46 sont `READY` mais 45 ne
l'est pas, le head vaut 46. Il sert à l'observabilité et à certaines classifications explicites ; il
ne suffit jamais à prouver qu'une version intermédiaire est prête, qu'une convergence initiale est
complète ou qu'une projection est servable.

## 6. Queries versionnées monoprojection

Toute query versionnée porte exactement une projection métier logique, jamais zéro ni plusieurs.
Une projection est un artifact logique versionné produit par un pipeline ; ce n'est ni une table, ni
un fragment SQL.

`READ_POT`, par exemple, reste une seule projection même si son artifact est physiquement réparti
entre snapshot, Shareholders, Expenses et shares. Il n'existe pas de projection Expense ou
Shareholder autonome tant qu'une query concrète ne le justifie pas.

Deux endpoints indépendants peuvent servir des versions différentes :

```text
GET /pots/42           -> READ_POT servedVersion = 15
GET /pots/42/balances  -> BALANCE  servedVersion = 13
```

## 7. Intentions de lecture CURRENT et EXACT

Le Query Version Resolver reçoit une intention explicite pour l'unique projection métier :

```text
CURRENT
EXACT(V)
```

### CURRENT

CURRENT cherche exclusivement dans la génération serving de cette projection :

```text
servedVersion = plus haute V <= latestKnownVersion
                dont le statut est terminal

terminal = READY | FAILED
```

Si latest-known est absent ou si aucune version terminale de la génération serving n'existe sous la
borne, le résultat est `NOT_READY`. Si la plus haute version terminale est `READY`, CURRENT la résout.
Si elle est `FAILED`, CURRENT produit `PROJECTION_FAILED` à cette version. Un `FAILED` récent n'est
jamais masqué par un ancien `READY` :

```text
latestKnownVersion = 15
READ_POT(15) NOT_READY
READ_POT(14) FAILED
READ_POT(13) READY

CURRENT -> PROJECTION_FAILED(14)
```

La sélection est calculée depuis les états terminaux exacts, jamais depuis les heads et sans scanner
les businessVersions une par une.

### EXACT(V)

EXACT ne sonde aucune autre businessVersion :

```text
latestKnownVersion absent           -> NOT_READY
V > latestKnownVersion              -> NOT_READY
pipeline serving non applicable à V -> NOT_APPLICABLE
status(V) = READY                    -> RESOLVED(V)
status(V) = FAILED                   -> PROJECTION_FAILED(V)
status(V) = NOT_READY                -> NOT_READY
```

Un artifact interne supérieur à latest-known n'est jamais exposé. L'absence exacte est un état normal
du Query Kernel, jamais une exception technique de cardinalité.

### Sélection de pipelineVersion versus businessVersion

La `businessVersion` servie et la `pipelineVersion` serving sont deux axes distincts. Le lifecycle de
pipeline fournit au Query Kernel l'unique pipelineVersion serving de la `ProjectionType` demandée.
Cette génération fait autorité pour les lectures récentes et historiques. Le Query Kernel ne choisit
jamais lui-même la pipelineVersion et ne fallback jamais vers une ancienne génération, même si
celle-ci possède un artifact READY plus récent.

```text
latestKnownVersion = 100
READ_POT serving P/v3 : V100 NOT_READY, V99 NOT_READY, V98 READY
READ_POT ancienne P/v2 : V100 READY

CURRENT -> P/v3 / V98
```

Si P/v3 ne possède aucun état terminal sous la borne, le résultat est `NOT_READY`, sans consultation
de P/v2.

## 8. Enveloppe versionnée

Toute réponse versionnée, `CURRENT` ou `EXACT`, utilise :

```text
VersionedQueryResponse<T>
  requestedVersion
  servedVersion
  latestKnownVersion
  generatedAt
  data
```

- `requestedVersion` vaut exactement `CURRENT` ou le numéro demandé par le client.
- `servedVersion` est la business version exacte et commune aux données retournées.
- `latestKnownVersion` est la plus grande business version connue du read side et la borne supérieure
  appliquée à l'exposition.
- `generatedAt` est l'instant de génération de l'enveloppe, pas celui de la projection.

Aucun champ `stale` n'est exposé. Toute réponse réussie possède un latest-known et respecte
`servedVersion <= latestKnownVersion`. Cette relation ne prouve toujours aucune continuité.

## 9. Listes et indexes secondaires

Une liste est une vue convergente, pas un snapshot global atomique. Le read model
`user -> PotProjection@V` est produit avec `READ_POT` et reste reconstructible. Il fournit uniquement
des candidats de découverte et ne constitue jamais une preuve d'autorisation.

Conséquences acceptées en V1 :

- un nouveau Pot peut être temporairement absent ;
- un ancien Pot peut rester temporairement candidat dans l'index ;
- la collection peut changer entre deux pages ;
- la pagination keyset garantit un parcours déterministe de l'état observé, pas un snapshot global.

Pour `/pots`, le reader parcourt l'index, puis résout `CURRENT` pour la projection métier de chaque
candidat. Une fois `servedVersion` obtenue, il demande AUTH en `EXACT(servedVersion)` et évalue les
droits à cette version. Un index stale peut encore référencer un Pot désormais interdit ; ce Pot est
filtré et n'est jamais exposé.

Le reader ne relit pas le primaire. L'ordre canonique de l'index reste :

```text
updatedAt DESC, potId ASC
```

`updatedAt` provient du `PotVersionMetadata.createdAt` durable de la business version, jamais de
l'heure du worker, de l'Event ou de l'enveloppe HTTP.

Pour une page de taille N, le reader examine les candidats dans cet ordre et continue après les
candidats non autorisés ou non exposables jusqu'à obtenir autant que possible N résultats, épuiser
les candidats ou atteindre une limite technique `maxCandidatesScannedPerPage`. Cette limite est
bornée mais sa valeur n'est pas fixée ici.

Le curseur suivant désigne le dernier candidat d'index réellement examiné, pas le dernier élément
retourné. Une page partielle avec curseur est valide lorsque la limite de scan est atteinte.

## 10. Sécurité et autorisation

### Deux couches strictement distinctes

`TokenCapabilities` représente les scopes/capacités du token présenté au moment de la requête. Ces
capacités sont toujours courantes et ne sont jamais historisées.

`PotAuthorizationAtVersion` représente uniquement les faits métier du Pot à une business version :

```text
isMember(userId, potId, version)
isCreator(userId, potId, version)
```

Les droits fins sont dérivés de ces faits par la même policy métier que côté write. Le read side ne
persiste pas une matrice de permissions déterministes et ne parle jamais de « scope historique ».

### Projection AUTH complète

AUTH est une projection indépendante de `READ_POT`. Pour toute businessVersion V applicable :

```text
AUTH(V)
= snapshot complet et exact de isMember/isCreator à V
```

Comme toute projection métier durable, sa voie normale est :

```text
BusinessEvent -> Event vers Task -> Task AUTH durable
              -> reconstruction exacte à V -> artifact AUTH(V)
```

AUTH(V) est autonome, immuable, idempotent, calculable sans AUTH(V-1) et sûr hors ordre. AUTH(15)
peut être produit avant AUTH(13) sans compromettre aucun des deux artifacts. La projection canonique
se limite à ces artifacts versionnés ; elle n'ajoute aucun état mutable d'autorisation ni consumer
Event direct spécialisé.

### Autorisation après résolution CURRENT

Pour toute query protégée, les `TokenCapabilities` actuelles doivent permettre la query. Le Query
Version Resolver résout ensuite la seule projection métier sans connaître AUTH. Après un succès à
`servedVersion`, l'Authorization Kernel demande AUTH en `EXACT(servedVersion)` et évalue les droits
métier depuis cet artifact. AUTH ne possède aucune version d'autorisation distincte de la
servedVersion.

### Autorisation EXACT(V)

Une requête historique combine :

```text
current TokenCapabilities avec VIEW_ARCHIVE
+ Pot business rights effectifs à V
```

Donc :

```text
historicalAccessAllowed
= hasCurrentVIEW_ARCHIVE && businessRightsAt(V)
```

La projection métier est d'abord résolue en `EXACT(V)`. Pour une query protégée, AUTH est ensuite
demandé séparément en `EXACT(V)`. Aucun fallback de businessVersion, de pipelineVersion ou de scope
historique n'existe. Une query non protégée ne passe pas par cette étape d'autorisation.

### Ordre sans fuite du Query Kernel

Pour une query protégée, aucune information sur l'existence, la readiness ou la failure d'une
projection métier n'est révélée avant la décision d'autorisation appropriée.

L'ordre conceptuel est : vérifier les TokenCapabilities, résoudre sans fuite externe la projection
métier, demander AUTH en `EXACT(servedVersion)`, évaluer les faits métier à cette version, puis lire
les données. Un refus établi est masqué selon le contrat HTTP. Le résultat d'AUTH ne relance jamais la
résolution métier à une businessVersion plus ancienne.

Une query non protégée omet simplement les étapes TokenCapabilities et AUTH ; le contrat générique
n'introduit aucun mécanisme d'autorisation alternatif.

## 11. Endpoints cibles

### Pot, Expense et sous-objets

```text
HTTP
  -> Query Version Resolver : CURRENT | EXACT(V) sur READ_POT
  -> si protégée : Authorization Kernel, AUTH EXACT(servedVersion)
  -> READ_POT reader
  -> VersionedQueryResponse
```

Expense et Shareholder sont lus à l'intérieur de `READ_POT(servedVersion)`. Aucun routing global
Expense/Shareholder ni lecture primaire n'est requis dans la cible.

### Balance

```text
GET /pots/{potId}/balances
  -> Query Version Resolver : CURRENT | EXACT(V) sur BALANCE
  -> si protégée : Authorization Kernel, AUTH EXACT(servedVersion)
  -> BALANCE reader
  -> VersionedQueryResponse
```

CURRENT résout la plus haute businessVersion terminale de la génération BALANCE serving. Un statut
`FAILED` à cette version produit `PROJECTION_FAILED` et n'est pas masqué par une ancienne balance
READY. Pour une query protégée, AUTH est ensuite demandé en `EXACT(servedVersion)`. Le pipeline
BALANCE calcule chaque version indépendamment de BALANCE(V-1).

## 12. Versions de pipeline et reconstruction

Une famille de pipeline peut avoir plusieurs versions coexistantes :

```text
declared -> active -> serving
```

- `declared` : définition connue du système ;
- `active` : pipeline dont de nouvelles consommations Event/Task peuvent acquérir un Claim ;
- `serving` : pipelineVersion explicitement sélectionnée pour servir les queries de cette famille,
  pour les businessVersions récentes comme historiques.

Une seule version est `serving` par famille à un instant donné. Elle est l'unique génération
autoritative : aucune ancienne pipelineVersion ne sert de fallback. Le passage à serving est toujours
manuel. `active` ne signifie jamais que la convergence est terminée. Le système calcule et expose
séparément l'éligibilité au serving.

La discovery filtre les générations inactives en best effort. Le contrôle autoritatif est effectué
dans la transaction d'acquisition du Claim. Un Claim committé avant `deactivate` conserve le droit de
terminer normalement ; après le commit de `deactivate`, aucun nouveau Claim de cette génération ne
peut être acquis. Aucun contrôle lifecycle n'est rejoué pendant l'exécution.

### Reconstruction sans mécanisme spécial

Il n'existe pas de protocole architectural distinct de rebuild, replay ou backfill. Une
reconstruction est l'effet normal de l'activation d'une nouvelle version de pipeline sur l'historique
durable existant :

```text
nouvelle pipelineVersion déclarée/active
  -> redécouverte automatique de tout Event applicable
  -> Tasks et ConsumptionKeys propres à la nouvelle génération
  -> exécution indépendante
  -> convergence
```

La motivation humaine — évolution, perte de données, correction ou rematérialisation identique — ne
change pas le mécanisme. Une reconstruction ne rouvre, ne reset et ne réutilise jamais les Tasks,
Slots ou Claims d'une ancienne `pipelineVersion`.

Les bornes d'applicabilité définissent l'historique à redécouvrir. La conservation de l'historique
source nécessaire est donc un prérequis durable du système.

### Éligibilité au cutover

Avant qu'une version active puisse devenir serving, la convergence initiale complète est exigée :

```text
structural readiness
+ historical catchup complete
+ no known holes
+ no unresolved failures
```

Un head maximal ne suffit pas. Après le cutover manuel, CURRENT consulte exclusivement la génération
serving et respecte son plus haut état terminal sous latest-known.

## 13. Observabilité

L'observabilité expose les états réels, sans créer de modèle parallèle.

Par famille/version de pipeline :

- `declared`, `active`, `serving` et plage d'applicabilité ;
- head comme maximum matérialisé ;
- backlog, trous connus et failures ;
- `eligibleForServing` et raisons structurées lorsque faux.

Fraîcheur :

- `latestKnownVersion` ;
- plus haute businessVersion terminale de chaque génération serving ;
- statut `READY` ou `FAILED` de cette version terminale.

Traitement : latences Event→pickup, Event→Task et Task→completion, retries, claims expirés et failures.

Queries : `requestedVersion`, `servedVersion`, `latestKnownVersion`, `NOT_READY` et
`PROJECTION_FAILED`, ainsi que les refus après évaluation AUTH à servedVersion.

```text
eligibleForServing = true
!= becomeServing()
```

## 14. Extinction du legacy

Le legacy devient supprimable uniquement lorsque :

- aucun endpoint client ne lit ses structures ;
- aucun worker legacy ne participe aux réponses ;
- les pipelines cibles sont serving ;
- les readers passent par Query Kernel et Authorization Kernel ;
- les anciennes tables ne sont plus nécessaires ;
- l'observabilité confirme l'absence de dépendance runtime.

L'extinction se fait en deux temps : désactivation avec structures encore présentes, période
d'observation et rollback possible, puis suppression physique explicite.

## 15. Invariants consolidés

1. Events et Tasks peuvent terminer dans n'importe quel ordre.
2. Toute projection métier N est indépendante de N-1 et de latest-known.
3. `latestKnownVersion` est une connaissance monotone, pas un watermark de continuité.
4. Un head est un maximum observé, jamais une preuve de complétude.
5. Toute query versionnée porte exactement une projection métier.
6. `CURRENT` sélectionne sous latest-known le plus haut état terminal `READY` ou `FAILED` de la
   génération serving ; un `FAILED` récent n'est jamais masqué par un ancien `READY`.
7. `EXACT(V)` consulte uniquement V dans la génération serving, après contrôle de latest-known et de
   l'applicabilité, sans fallback.
8. Deux endpoints indépendants peuvent servir des versions différentes.
9. Une liste est convergente et exclusivement read-side ; aucun snapshot global V1 n'est promis.
10. Toute réponse versionnée utilise `VersionedQueryResponse` sans champ `stale` et possède un latest-known.
11. Les capacités du token sont courantes ; seuls les faits métier Pot sont historisés.
12. AUTH(V) est un artifact complet, indépendant et hors ordre pour chaque businessVersion applicable.
13. Pour une query protégée, AUTH est demandé en EXACT(servedVersion) après résolution métier.
14. Pour une query protégée, un refus de AUTH(servedVersion) est terminal ; aucune businessVersion
    antérieure n'est essayée.
15. L'index user→Pot découvre des candidats et ne prouve jamais l'autorisation.
16. Pour une query protégée, l'autorisation précède toute révélation externe d'existence ou de
    readiness métier.
17. Une nouvelle pipelineVersion est l'unique mécanisme de reconstruction/rematérialisation.
18. Une seule pipelineVersion est serving par famille et le cutover reste manuel ; elle fait autorité
    pour les lectures récentes et historiques, sans fallback vers une ancienne génération.
19. `active` autorise le travail ; seule l'éligibilité prouve la convergence initiale requise.
20. L'éligibilité serving exige une convergence initiale complète, jamais un head seul.
