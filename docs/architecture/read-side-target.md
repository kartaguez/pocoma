# Architecture cible du read side

## 1. Statut et périmètre

Ce document formalise la cible normative du Lot 7 pour le read side de Pocoma. Il synthétise le
cadrage validé et le confronte au code au HEAD `24ef19fb849d53c119668193c61d66e62d07504f`.
Le plan directeur de réalisation est désormais porté par
[`lot-7-read-side-implementation-plan.md`](../plans/lot-7-read-side-implementation-plan.md). Les
choix encore ouverts sont des décisions d'implémentation ou de produit différées aux lots concernés,
pas des ambiguïtés sur les invariants consolidés ici.

Il constitue la baseline canonique pour les analyses et le découpage des Lots 7.x. Il ne décrit
ni un ordre de réalisation, ni des migrations, ni des classes ou tables définitives.

Documents complémentaires :

- [État actuel du read side](read-side-current-state.md), pour l'inventaire factuel des GET, des
  sources SQL et des deux persistences Balance existantes ;
- [Clôture du write side](write-side-closure.md), pour la voie canonique de mutation ;
- [Runtime Task Balance](consumption-task-balance-runtime.md), pour les garanties actuelles du moteur
  générique de consommation et de la projection Balance.

Le write side du Lot 6 reste hors périmètre fonctionnel du Lot 7. Une incohérence constatée avec
l'invariant de delete terminal est néanmoins signalée en section 14 : elle ne peut pas être compensée
correctement par le read side.

## 2. Résumé de la cible

Le write side conserve seul la vérité métier historisée. Toute mutation canonique suit :

```text
POST /api/v1/commands
  -> RecordedCommand durable
  -> Command consumption worker
  -> use case Pot
  -> état primaire historisé + BusinessEvent atomique
```

Le read side est un ensemble autonome de matérialisations déterministes, reconstructibles,
versionnées et immuables. Il ne reçoit aucune écriture directe du write side et apprend ses évolutions
uniquement par les Business Events.

```text
                                      +-> SourceVersionWatermark
BusinessEvent durable ----------------|       latestVersionSeen
                                      |
                                      +-> Event -> Task -> Task worker
                                                -> primaire historisé @ potVersion
                                                -> projection immuable
                                                -> état + head + indexes atomiques

GET -> read store uniquement -> version exacte ou état explicite
```

Le primaire historisé est une source de reconstruction des projections, jamais une dépendance du
chemin normal des GET. Il n'existe ni jointure runtime read/primary, ni fallback silencieux vers le
primaire, une ancienne version du Pot ou une ancienne version de pipeline.

## 3. Frontières et ownership

### Write store

Le write store possède :

- la version source autoritative du Pot ;
- les fragments historisés du modèle primaire ;
- les Business Events atomiques avec les mutations métier.

### Read store

Le read store possède :

- les watermarks de versions source observées ;
- les artifacts de projection et leurs fragments physiques ;
- les états fonctionnels et heads de projection ;
- les indexes secondaires nécessaires aux queries ;
- les données contextuelles nécessaires aux autorisations de lecture.

Le déploiement initial peut utiliser la même instance PostgreSQL, mais avec une séparation logique
permettant des schémas, datasources, migrations, transactions et ownership distincts. Le modèle ne
doit contenir aucune FK, vue ou jointure exigeant que read et primary résident dans la même base.

Le déplacement futur du read store vers une autre instance ne doit pas changer le modèle fonctionnel.
Il nécessitera cependant un protocole de commit idempotent et fencé entre le lifecycle Task et le read
store, sans transaction distribuée implicite. Le protocole concret est une décision d'implémentation
future, distincte de l'atomicité fonctionnelle exigée dans le read store.

## 4. Modèle temporel

### Version source observée

La version autoritative n'est jamais lue synchroniquement par le read side. Pour chaque `potId`, le
read store maintient un `SourceVersionWatermark.latestVersionSeen` : la plus haute `potVersion`
observée dans un Business Event.

La mise à jour est idempotente et hors-ordre :

```text
latestVersionSeen = max(latestVersionSeen, event.potVersion)
```

Un consommateur Event express et spécialisé porte cette seule responsabilité. Il ne crée pas de
Task, ne relit pas le primaire et ne matérialise aucune projection. C'est l'unique exception au chemin
général Event vers Task.

Une courte fenêtre où le write side est en `N` et le read side en `N-1` est admise. Les versions d'un
Pot étant supposées contiguës, `latestVersionSeen=N` prouve l'existence source de toutes les versions
de `1` à `N` sans registre unitaire supplémentaire.

L'identité logique du watermark est au minimum `potId`. Son mécanisme de reprise après perte du read
store doit rester reconstructible ; le cadrage ne fixe pas encore si cette reconstruction repose sur
la rétention/relecture complète des Events ou sur une initialisation administrative depuis le
primaire.

### Version projetée

Pour chaque couple Pot/pipeline, un `ProjectionHead.latestProjectedVersion` désigne la plus haute
version dont l'artifact a été matérialisé avec succès. L'identité logique du head est :

```text
projectionType + pipelineId + pipelineVersion + potId
```

Le head est une donnée fonctionnelle canonique, pas un cache de `MAX(artifact.version)`. Il est
monotone et peut avancer malgré des trous : si 44 et 46 sont `READY` mais 45 ne l'est pas,
`latestProjectedVersion=46`.

`latestVersionSeen` et `latestProjectedVersion` restent dans des objets distincts, avec des producteurs
distincts. Aucun ordre d'arrivée entre ces producteurs ne peut être supposé.

Les deux chemins issus d'un Business Event sont indépendants. Un Event ou une Task de projection
légitime portant `potVersion=N` suffit à établir l'intention de projeter N : aucun acquire, claim ou
projector n'attend que `latestVersionSeen` atteigne N. Il est donc valide que
`latestProjectedVersion > latestVersionSeen` de manière transitoire.

Un artifact produit en avance ne fait pas progresser `latestVersionSeen`, ne modifie pas la
connaissance source du reader et ne constitue jamais un mécanisme implicite de découverte d'une
version. Seul le consumer express, ou une reconstruction administrative explicite du watermark, fait
évoluer cette connaissance.

### Version exposable

`latestExposableVersion` est une notion dérivée : la plus haute version servable compte tenu de la
projection demandée, des projections nécessaires à l'autorisation et des indexes indispensables. Elle
n'est pas stockée dans un head générique. Un index secondaire courant peut, par construction, ne
référencer que des versions exposables.

## 5. Modèle de projection

### Identité et générations

Toute projection canonique, Pot comme Balance, utilise l'identité complète :

```text
projectionType
pipelineId
pipelineVersion
potId
potVersion
```

Plusieurs pipelines et générations peuvent coexister pour permettre backfill, comparaison, bascule,
rollback et rétention. Toutes les générations applicables peuvent être produites. Pour l'exposition,
le reader utilise une `PipelineSelectionStrategy` statique propre à chaque `pipelineId` ; il ne choisit
jamais la plus grande `pipelineVersion` et ne retombe jamais implicitement sur une autre génération.

Une bascule exige que toutes les projections courantes et dépendances nécessaires aux lectures
courantes soient exposables. Le backfill historique complet n'est pas une précondition. Les anciennes
générations restent présentes jusqu'à application d'une politique explicite de rétention/GC.

### Artifact immuable et snapshot logique

`PotProjection(potVersion=N)` est le snapshot canonique complet, autonome et immuable du Pot en `N`.
Il porte notamment le header, le statut métier, les Shareholders, les Expenses, leurs shares et le
contexte d'autorisation versionné nécessaire aux ressources contenues.

La complétude est logique, pas physique. Header et collections peuvent être répartis dans plusieurs
tables afin de permettre pagination, indexation, lecture partielle et accès direct. Chaque fragment
conserve les éléments d'identité nécessaires pour appartenir sans ambiguïté au snapshot.

Il n'existe initialement ni `PotDetailsProjection`, ni `PotExpensesProjection`, ni timeline autonome
d'Expense ou de Shareholder. Une nouvelle projection spécialisée exige une query concrète qui la
justifie.

Pour une identité complète, le résultat est déterministe : un contenu identique peut être adopté ; un
contenu différent viole l'invariant. Un artifact existant n'est jamais écrasé. Si l'identité est déjà
`READY` avec un artifact A et qu'une nouvelle exécution calcule B différent, A reste inchangé, B n'est
pas écrit et le statut fonctionnel reste `READY`. La divergence est enregistrée et remontée comme une
violation d'invariant séparée ; elle ne provoque jamais automatiquement `READY -> FAILED`. Une
éventuelle quarantaine administrative serait un mécanisme distinct.

### Applicabilité, sélection reader, statut fonctionnel dérivé et head

`PipelineVersionDefinition` associe l'identité globale `pipelineId + pipelineVersion` à une
`VersionApplicability` continue. Une définition publiée est immuable et conservée tant que sa
génération peut être référencée. `appliesTo(potVersion)` est l'unique règle d'applicabilité et de
production ; aucune notion supplémentaire de génération active n'existe côté producer.

`PipelineSelectionStrategy` choisit uniquement ce que les readers exposent. Pour un `pipelineId`, elle
contient une liste ordonnée `(fromPotVersion, pipelineVersion)` et résout le plus grand seuil inférieur
ou égal à V. Les seuils sont positifs et strictement croissants ; les pipelineVersions peuvent revenir
en arrière ou se répéter. La stratégie peut être vide et son premier seuil peut dépasser 1.

Une stratégie absente pour un pipeline demandé est une erreur de configuration. Une stratégie vide ou
V avant son premier seuil donne `NOT_FOUND`. Toute version référencée doit exister dans le catalogue
pour le même pipelineId. La stratégie est statique en code, unique par pipelineId dans un build, sans
SQL, refresh dynamique, variation par endpoint/utilisateur/scope/query ou fallback.

Pour une version applicable, `ProjectionStatus` est une vue fonctionnelle dérivée :

- artifact complet présent : `READY` ;
- `ProjectionFailure` terminale présente : `FAILED` ;
- ni artifact ni failure : `NOT_READY`, y compris pendant les retries temporaires.

Une définition sélectionnée mais non applicable produit `NOT_FOUND` et un signal interne, jamais un
nouvel état fonctionnel. L'existence source reste déterminée séparément par le watermark. Artifact et
failure sont mutuellement exclusifs ; une failure tardive ne dégrade jamais un artifact réussi.

Le statut n'est pas persisté dans une table de state. Il est résolu sans lire le lifecycle technique
des Tasks, claims, leases, slots ou retries. Un GET et un reader restent strictement read-only ; un
projector ne crée pas d'attente opportuniste. Aucune expectation, state ou table parent équivalente
n'est persistée. Les Tasks portent l'ordonnancement ; leur présence ou absence ne définit pas le statut.

## 6. Production et reconstruction

`PipelineVersionDefinition` est conservée dans `PocomaPipelineDefinitions`, catalogue framework-free
et canonique. Chaque processus construit son registry local depuis ce catalogue : mêmes valeurs sans
partage d'instances Java. Les générations inactives restent adressables jusqu'à leur GC explicite.

Le pipeline canonique est celui déjà adopté pour Balance :

```text
BusinessEvent(V)
  -> toutes les PipelineVersionDefinition applicables du pipelineId
  -> Task(eventId, pipelineId, pipelineVersion, potId, potVersion)
  -> Task worker du pipeline
  -> reconstruction primaire exacte @ potVersion
  -> calcul complet
  -> matérialisation read-side
```

Par défaut, chaque projection repart directement du primaire historisé à la version exacte. Elle ne
rejoue pas les Commands, ne dépend pas d'une projection précédente et n'a pas besoin de rejouer tous
les Events.

Une dépendance inter-projection est admise uniquement si elle est explicite et porte l'identité
complète de l'artifact source. Le calcul reste alors reproductible et ne dépend jamais de « la version
actuellement active » d'un pipeline source.

Lors d'un succès, une unique transaction du read store rend atomiquement visibles :

```text
artifact complet et tous ses fragments
+ descriptor d'artifact éventuel
+ max(ProjectionHead.latestProjectedVersion, potVersion)
+ indexes secondaires indispensables dérivés de cette projection
```

Le reader ne peut donc dériver `READY` sans artifact, ni voir un head sans artifact visible, ni projection
canonique prête avec un index indispensable en retard.

Cette transaction du read store constitue l'invariant permanent. Tant que read store et lifecycle
Task sont colocalisés dans PostgreSQL, le Lot 7 peut également coordonner la terminalisation de la Task
dans la même transaction locale. C'est un choix d'implémentation, pas une extension de l'invariant
fonctionnel. Une séparation physique future devra obtenir une reprise équivalente par idempotence et
fencing, sans supposer de commit distribué atomique.

Les pipelines peuvent terminer hors ordre. Les heads et tout index secondaire représentant le courant
doivent donc être protégés par la version : une matérialisation tardive de 45 ne peut pas faire
reculer un index déjà positionné par 46.

## 7. Workers, backfill et réparation

Le producer ne consulte ni artifact, failure, head, statut ni stratégie reader. L'identité durable
d'une Task Event→Task est `(eventId, pipelineId, pipelineVersion)` ; deux Events distincts de même
version restent indépendants. Le payload transporte l'identité complète de projection. Un conflit de
Pot ou version sous une identité existante est une violation sans overwrite.

Le moteur de consommation et ses garanties restent génériques. Les pools sont néanmoins isolés par
pipeline afin d'autoriser un dimensionnement indépendant et d'empêcher l'affamement entre projections
lourdes. Le nombre d'instances n'est pas un invariant d'architecture.

Plusieurs workers d'un même pipeline peuvent scanner, claim et exécuter en concurrence via le
fencing : c'est le fonctionnement normal d'un pool. Un seul producer logique coordonné évalue le
catalogue et crée/adopte les Tasks de toutes les définitions applicables.

Le backfill volontaire utilise le même contrat de Task, le même executor et les mêmes garanties que le
trafic normal. Une Task administrative a pour identité
`(campaignId, potId, potVersion, pipelineId, pipelineVersion)` et peut exister sans Event. Deux campagnes
peuvent viser la même ProjectionIdentity ; la matérialisation finale reste idempotente.

La réparation automatique est distincte et limitée aux trous anormaux ou projections applicables
absentes/défaillantes. Elle ne remplace pas un backfill ou une migration
volontaire.

## 8. Résolution des lectures unitaires

### Choix de la version

Pour une query Pot sans version explicite, la cible est exactement `latestVersionSeen`. Le reader ne
sert jamais une ancienne projection au motif qu'elle est disponible.

Après détermination de V et contrôle du watermark, le reader récupère la stratégie du pipeline,
résout V, reconstruit l'identité de génération, charge sa définition exacte et vérifie `appliesTo(V)`.
Une stratégie absente ou une définition absente est une erreur de configuration. Une résolution vide
ou une définition sélectionnée non applicable donne `NOT_FOUND`, cette dernière avec un signal interne.
Current et historique suivent ce même chemin ; seule la détermination initiale de V diffère.

Pour une version explicite `V` :

| Condition read-side | Résultat fonctionnel |
|---|---|
| Pot/watermark inconnu | `NOT_FOUND` |
| `V > latestVersionSeen`, même si un artifact V existe en avance | `NOT_FOUND` |
| stratégie vide, V avant son premier seuil ou définition sélectionnée non applicable | `NOT_FOUND` |
| V connue et contexte d'autorisation exact absent, `NOT_READY` ou `FAILED` | `NOT_READY` |
| contexte d'autorisation `READY` mais accès refusé | `NOT_FOUND` masqué |
| contexte d'autorisation `READY`, accès accordé et projection métier `FAILED` | `FAILED` |
| contexte d'autorisation `READY`, accès accordé et projection métier non prête | `NOT_READY` |
| contexte d'autorisation, artifact métier et préconditions d'exposition prêts | `READY` |

Une projection Balance `READY` n'est pas exposable tant que la `PotProjection` sélectionnée par la
stratégie READ_POT à la même `potVersion` n'est pas disponible pour l'autorisation. Balance et READ_POT
gardent leurs stratégies indépendantes ; aucune autorisation n'utilise une génération différente de
celle sélectionnée pour l'artifact de contexte. Cette précondition ne crée aucune dépendance de calcul ou
d'ordre entre les deux pipelines. Que cette PotProjection soit absente, `NOT_READY` ou `FAILED`, son
indisponibilité produit fonctionnellement `NOT_READY`, donc HTTP 409. Son état interne reste observable
opérationnellement. Le 404 est réservé à une inexistence établie ou à un refus évalué depuis un
contexte PotProjection `READY`.

La même priorité s'applique aux lectures Pot, Expense et Shareholder, car leur `PotProjection` porte
à la fois les données métier et le contexte d'autorisation versionné. Une PotProjection `FAILED` ne
produit donc pas automatiquement un 503 côté client : tant qu'elle ne permet pas d'établir
l'autorisation, la réponse fonctionnelle est `NOT_READY`/409. `FAILED`/503 n'est exposable pour une
projection métier demandée qu'après disponibilité du contexte requis et autorisation accordée.

L'artifact éventuellement produit en avance n'est pas consulté pour établir l'existence source : tant
que `V > latestVersionSeen`, V reste inconnue du reader. Cette asymétrie est une conséquence assumée
de l'eventual consistency.

### Contrat HTTP

| État fonctionnel | HTTP | Contenu minimal |
|---|---:|---|
| `NOT_FOUND` ou non autorisé | 404 | code fonctionnel stable |
| `NOT_READY` | 409 | code, `requestedVersion`, `latestProjectedVersion` |
| `FAILED`, après autorisation établie | 503 | code fonctionnel stable |
| succès | 200 | représentation et `potVersion` réellement servie |

Les réponses fonctionnelles n'exposent ni pipeline interne, ni worker, claim, retry count ou lag
technique. Un succès n'expose pas systématiquement `latestVersionSeen` ou le lag.

Le préfixe d'URL du code actuel est `/api` (`/api/pots`, `/api/expenses`) alors que le cadrage emploie
la notation `/pots`. Ce document considère les routes du cadrage comme des noms fonctionnels et ne
décide pas d'une rupture d'URI ou d'une version d'API.

## 9. Autorisation temporelle

Le read side évalue les droits contextuels exclusivement à partir des snapshots de la version
consultée. Un utilisateur ajouté en version 50 n'acquiert donc pas rétroactivement un accès en 42.

L'identité des GET provient du principal OAuth2 Resource Server. Les headers libres
`X-User-Id`/`X-User-Scopes` décrits dans l'état courant sont un mécanisme legacy à retirer, pas une
frontière d'identité cible.

Les permissions de ressources sont autonomes :

```text
POT:VIEW                  POT:VIEW_ARCHIVE
SHAREHOLDER:VIEW          SHAREHOLDER:VIEW_ARCHIVE
EXPENSE:VIEW              EXPENSE:VIEW_ARCHIVE
BALANCE:VIEW              BALANCE:VIEW_ARCHIVE
```

Lire une Expense ne requiert pas en plus `POT:*`, même si le contexte de la PotProjection sert à
évaluer l'accès. La même règle vaut pour Shareholder et Balance.

Pour un Pot actif, une version égale au `latestProjectedVersion` de la projection servant la ressource
requiert `VIEW`; une version inférieure requiert `VIEW_ARCHIVE`. Un snapshot dont le statut est
`DELETED`/archivé requiert toujours `VIEW_ARCHIVE`, y compris s'il est le plus récent projeté.
Cette classification reste volontairement fondée sur le head même lorsque celui-ci devance
temporairement `latestVersionSeen`.

Le current est global, jamais personnalisé. Une lecture sans version ne cherche pas une ancienne
version autorisée. Une ressource ou version non autorisée est masquée par un 404, jamais révélée par
un 403.

L'ordre de résolution est impératif : établir l'existence source connue, sélectionner la génération,
charger les projections exactes nécessaires au contexte d'autorisation, retourner `NOT_READY`/409 si ce contexte n'est pas `READY`,
masquer en 404 un refus établi, puis seulement interpréter l'état de la projection métier demandée.
Ainsi, une PotProjection d'autorisation absente, `NOT_READY` ou `FAILED` donne fonctionnellement
`NOT_READY`/409 ; son éventuel `FAILED` reste visible dans l'observabilité opérationnelle. Lorsque le
contexte est `READY` mais refuse l'utilisateur, le refus est masqué en 404. Après autorisation
accordée, la projection métier demandée produit respectivement 409, 503 ou succès selon qu'elle est
`NOT_READY`, `FAILED` ou `READY`.

## 10. Query models et indexes secondaires

Les indexes sont des structures read-side dérivées, non autoritatives et reconstructibles. Ils ne
créent pas de nouvelle projection canonique.

### Liste des Pots

`GET /pots` retourne par défaut uniquement les Pots actifs. L'inclusion des supprimés/archivés exige
un paramètre explicite en plus du scope adéquat.

La query utilise un index courant `userId -> Pot` contenant au minimum le `potId`, le statut, la
version de provenance et les données de tri/résolution. Il n'est pas historisé. La pagination est par
curseur/keyset sur un ordre strict :

```text
updatedAt DESC, potId
```

`updatedAt` doit être une donnée source stable associée à la version métier, et non l'heure de fin de
projection, faute de quoi un rebuild ou le hors-ordre modifierait l'ordre visible. Le code actuel ne
porte pas encore ce timestamp dans `PotHeader`; sa définition exacte reste à confirmer.

### Expenses et Shareholders

`GET /pots/{id}/expenses` lit et pagine les fragments indexés de la PotProjection exacte, sans
projection Expenses séparée.

`GET /expenses/{id}` utilise un index `expenseId -> potId`, puis résout la version globale du Pot et
lit l'Expense dans la PotProjection correspondante. L'index doit conserver la résolution nécessaire
aux lectures historiques même après suppression de l'Expense ou du Pot.

Les Shareholders appartiennent de la même manière au snapshot global du Pot et n'ont pas de timeline
read-side indépendante.

Le comportement d'un GET direct par `expenseId` lorsque l'index de routage n'est pas encore
matérialisé, mais que la version source existe, reste à préciser : sans `potId`, le reader ne peut
actuellement distinguer `NOT_FOUND` de `NOT_READY`.

### Balances transverses

`GET /pots/balances/me` utilise un index/read model courant `userId -> Balances`. Il ne réalise ni
N+1 Pot vers Balance, ni scan transverse au moment du GET. Cet index est mis à jour atomiquement avec
la BalanceProjection dont il dérive et respecte le fencing monotone des versions.

## 11. Delete et statut métier

Chaque PotProjection porte son statut `ACTIVE`, `DELETED` ou futur équivalent. Aucun head mutable de
statut n'est ajouté sans query concrète.

La suppression du Pot est une version métier normale mais terminale : elle incrémente la version,
reste consultable dans l'historique, interdit toute version future et requiert `VIEW_ARCHIVE` à la
lecture. Ainsi, si 46 est la suppression, 46 existe et 47+ n'existe pas.

Cet invariant est nécessaire à la règle d'existence fondée uniquement sur `latestVersionSeen` et à
la stabilité des indexes courants.

La terminalité est une précondition produite par le write side. Le read side ne filtre, ne
réinterprète et ne compense jamais des versions que le primaire aurait produites après le delete.

## 12. Observabilité fonctionnelle

Le read side expose au minimum, par pipeline et Pot ou sous forme agrégée adaptée :

- l'écart `latestVersionSeen - latestProjectedVersion` ;
- le nombre de projections `NOT_READY` ;
- le nombre de projections `FAILED` ;
- l'âge de la plus ancienne projection attendue.

Comme les consumers du watermark et des projections sont indépendants, l'écart brut peut être
négatif transitoirement si une projection termine avant l'observation express du même Event. Ce cas
doit être distingué d'un lag de projection positif ; ni la monotonie ni l'ordre relatif des deux
watermarks ne permettent de l'exclure.

Claims, leases, retries et slots restent dans l'observabilité technique du runtime et ne contaminent
pas le modèle fonctionnel du reader.

## 13. Compatibilité avec le code existant

### Fondations directement réutilisables conceptuellement

| Cible | État actuel |
|---|---|
| Business Event portant `potId` et `version` | Tous les Events Pot typés exposent déjà ces deux valeurs. |
| Reconstruction exacte depuis le primaire | Balance relit déjà l'état historisé à `targetVersion`. |
| Pipeline Event -> Task -> worker générique | Chemin distribué Balance déjà actif. |
| Identité complète de projection | `balance_projection_artifacts` applique déjà les cinq dimensions. |
| Immutabilité/idempotence stricte | L'adapter Balance adopte le contenu identique et rejette un conflit. |
| Hors-ordre des versions | Les artifacts Balance sont indépendants par version. |
| Sélection explicite à migrer | Le reader Balance exige déjà `pipeline-id` et `pipeline-version`, mais pas encore une stratégie par version Pot. |
| Pools séparables | Un runtime Task est configuré pour un pipeline et un ensemble de types. |

### Écarts attendus du chantier read-side

| Cible | Écart actuel |
|---|---|
| GET autonomes du primaire | Pot/Expense lisent `pot_global_versions` et les tables historisées ; Balance y lit encore version et autorisation. |
| PotProjection complète | Aucun snapshot Pot read-side n'existe. |
| SourceVersionWatermark express | Aucun consumer ou store dédié n'existe. |
| ProjectionStatus et ProjectionHead | La projection immuable Balance n'a ni statut fonctionnel dérivé dédié ni head. |
| États HTTP | Une projection Balance absente devient actuellement une erreur technique ; les autorisations donnent 403. |
| Autorisation archive | Les policies de lecture n'utilisent que `*:VIEW`; `BALANCE:VIEW_ARCHIVE` n'existe pas encore dans les permissions canoniques. |
| Indexes transverses | Les listes actuelles relisent le primaire et `balances/me` réalise une boucle par Pot. |
| Pagination keyset | La liste Pot actuelle est non paginée et triée par label. |
| Identité authentifiée homogène | Les GET utilisent encore `X-User-Id` et `X-User-Scopes`, contrairement au POST Command protégé par Resource Server. |
| Read store logique | Migrations, datasource et transactions sont aujourd'hui partagées ; `balance_projection_artifacts.pot_id` référence directement `pot_global_versions`. |
| Legacy Balance unique | Le monolithe utilise encore les tables mutables `pot_balance_*` en parallèle des artifacts immuables. |

### Points d'ancrage dans le code audité

Ces fichiers suffisent à retrouver les preuves principales sans nouveau crawl global :

- `domain-pot/.../event/BusinessEvent.java` : contrat commun `potId()`/`version()` ;
- `infra-persistence-jpa/.../projection/JpaHistoricalPotBalanceSourceAdapter.java` : reconstruction
  Balance à la version exacte ;
- `infra-persistence-jpa/.../projection/JpaImmutableBalanceProjectionAdapter.java` : identité,
  adoption idempotente et détection de conflit ;
- `infra-persistence-jpa/.../db/migration/V5__transactional_task_consumption.sql` : schéma des
  artifacts et FK actuelle vers `pot_global_versions` ;
- `engine-query/.../GetPotService.java`, `GetExpenseService.java` et `GetPotBalancesService.java` :
  résolution actuelle depuis le primaire et autorisation avant lecture Balance ;
- `supra-http-rest-spring/.../RestExceptionHandler.java` : mapping actuel des refus en 403 et absence
  de statuts read-side dédiés ;
- `runtime-task-consumption-worker/.../TaskConsumptionRuntimeConfiguration.java` : binding Balance
  et pool configuré par pipeline.

## 14. Incompatibilité du delete terminal avec le write side actuel

Le cadrage suppose qu'aucune mutation ne peut créer une version après la suppression d'un Pot. Cette
propriété n'est pas entièrement vraie au HEAD audité.

Les preconditions Pot et la création d'Expense contrôlent bien `PotHeader.deleted`. En revanche,
`DeleteExpenseContext`, `UpdateExpenseDetailsContext` et `UpdateExpenseSharesContext` reçoivent un
booléen `deleted` alimenté par `ExpenseHeader.deleted`. Ils chargent aussi le PotHeader courant mais
n'en propagent pas le statut supprimé. Une Expense encore active peut donc être supprimée ou modifiée
après la suppression de son Pot, ce qui incrémente `pot_global_versions` et produit un nouvel Event.

Conséquences :

- la version de delete Pot n'est pas nécessairement la dernière version ;
- l'invariant 15 du cadrage est faux pour les données que le code peut produire ;
- le read side ne peut pas réparer cette contradiction sans inventer une vérité différente du primaire.

La cible conserve l'invariant « delete Pot terminal ». Sa mise en cohérence est un prérequis write-side
externe au Lot 7 malgré la clôture annoncée du Lot 6. Tant que le correctif n'est pas effectif et
validé, aucun lot dépendant de la terminalité du delete ne peut satisfaire ses critères de sortie. Le
read side ne doit pas masquer cette contradiction par une règle locale.

## 15. Décisions d'implémentation différées

Les invariants d'autorisation, d'indépendance des consumers et d'atomicité read-store sont consolidés
dans les sections précédentes. Les points ci-dessous restent volontairement à décider dans les lots
qui en dépendent ; ils ne bloquent pas la conception conceptuelle du Lot 7.2.

### Routage d'une ressource enfant encore non projetée

Un GET direct par `expenseId` a besoin de `potId` avant de consulter le watermark et le snapshot. Si
l'index `expenseId -> potId` est créé seulement avec la PotProjection, l'absence d'index ne permet pas
de distinguer une Expense inexistante d'une Expense connue du write side mais non encore projetée.
Le contrat `NOT_FOUND`/`NOT_READY` et le producteur de cette information de routage doivent être
alignés au plus tard au début du Lot 7.7, avant la migration du GET direct Expense.

### Sémantique de `updatedAt`

Le tri keyset cible dépend d'`updatedAt`, absent du modèle Pot actuel. Une date de projection serait
non déterministe lors d'un retry/backfill et incorrecte en cas de hors-ordre. La cible doit consacrer
une date stable issue du fait source ou une autre clé de tri métier stable. Cette source doit être
fermée au Lot 7.6 ou au début du Lot 7.8 avant qu'un rebuild complet soit déclaré valide.

### Protocole d'une séparation physique future

L'atomicité à l'intérieur du read store est fixée. Le protocole concret qui coordonnera ultérieurement
un lifecycle Task et un read store placés dans deux PostgreSQL distincts reste une décision
d'implémentation future ; il devra respecter idempotence et fencing sans transaction distribuée
implicite.

## 16. Invariants canoniques consolidés

1. Le write side n'écrit jamais dans le read store.
2. Le chemin normal des GET ne lit ni ne joint le primaire.
3. Une projection repart du primaire historisé exact, sauf dépendance inter-projection explicite et
   complètement versionnée.
4. Toute identité de projection comprend type, pipeline, génération, Pot et version du Pot.
5. Les artifacts sont immuables, déterministes et strictement idempotents. Un duplicate divergent
   laisse l'artifact et le statut dérivé `READY` inchangés et produit une violation séparée.
6. Watermark source et head de projection sont distincts, par Pot, monotones et produits séparément ;
   aucun projector n'est gaté par le watermark.
7. `latestProjectedVersion > latestVersionSeen` est temporairement valide. Un artifact produit en
   avance ne fait pas connaître sa version au reader.
8. Pour une query explicite, `V > latestVersionSeen` reste `NOT_FOUND`, même si un artifact V existe.
9. Le head est canonique, peut avancer malgré des trous et ne se recalcule pas depuis les artifacts.
10. L'applicabilité canonique implique la production de toutes les générations applicables ; la
    sélection reader, le scheduling et le statut restent indépendants.
11. Pour une définition applicable, `NOT_READY` est dérivé uniquement de l'absence d'artifact et de
    failure. Une définition sélectionnée non applicable donne `NOT_FOUND`, jamais `NOT_READY`.
12. Artifact, descriptor éventuel, head et indexes indispensables deviennent visibles atomiquement
    dans le read store, de sorte que `READY` soit dérivable sans ambiguïté.
13. La coordination éventuelle avec le lifecycle Task est un choix local ; une séparation physique
    n'implique aucune transaction distribuée.
14. Les structures courantes dérivées sont version-fencées et ne régressent jamais lors d'un traitement
   hors ordre.
15. Le reader ne fallback jamais vers le primaire, une ancienne `potVersion` ou une ancienne
    `pipelineVersion`.
16. La stratégie reader est statique, unique par pipelineId et appliquée avant l'autorisation à current
    comme à l'historique ; toute référence est validée contre le catalogue exact.
17. Les droits contextuels sont évalués au temps de la version consultée ; un refus avec contexte
    disponible est masqué en 404.
18. La readiness du contexte d'autorisation précède l'exposition d'un échec métier : une
    PotProjection de contexte absente, `NOT_READY` ou `FAILED` retourne fonctionnellement
    `NOT_READY`/409 ; `FAILED`/503 n'est exposé qu'après autorisation établie.
19. Une Balance prête sans PotProjection `READY` à la même version retourne `NOT_READY`/409.
20. Une Task Event est unique par `(eventId,pipelineId,pipelineVersion)` ; une Task administrative par
    `(campaignId,potId,potVersion,pipelineId,pipelineVersion)`. Toutes utilisent le même executor.
21. Les versions source sont contiguës et la suppression du Pot est terminale ; cette dernière est un
    prérequis write-side externe que le read side ne compense pas.
22. Les pools de workers sont séparés par pipeline mais partagent le moteur générique. Plusieurs
    workers sont autorisés, avec un seul producer logique coordonné évaluant le catalogue.
23. Backfill normal et trafic courant utilisent le même moteur de projection.
24. Tout read model et index est reconstructible ; le read side ne devient jamais un second primaire.

## 17. Hors périmètre de cette baseline

Ce document ne choisit pas les noms Java, packages, ports/adapters, tables, migrations, indexes SQL,
source exacte de `updatedAt`, sérialisation des curseurs, valeurs par défaut/maximales de `limit`, forme
des envelopes HTTP, tailles de batch, nombres de workers, politique de retries ou de réparation des
`FAILED`, interface CLI ou autre mécanisme administratif de backfill, noms exacts des pipelines,
premières `pipelineVersion`, caractère current-only ou historique de `balances/me`, durée de
coexistence legacy, politique de GC ou découpage détaillé des Lots 7.x.

Il ne décide pas non plus du retrait physique du legacy, du moment du déplacement vers une seconde
instance PostgreSQL ni du protocole concret de coordination avec cette seconde instance. Ces choix
seront fermés dans les lots qui en dépendent. Le prérequis externe de la section 14 reste à corriger
côté write avant validation des comportements fondés sur le delete terminal.
