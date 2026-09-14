# Lot 7.10.1 — Contrats canoniques du kernel d'autorisation

## 1. Objet et autorité du document

Ce document est la référence normative spécialisée du Lot 7.10.1. Il fixe les contrats, invariants
et frontières logiques de l'autorisation Pot cible ; il décrit la cible décidée, pas nécessairement
le code déjà livré.

Il précise, sans les remplacer :

- l'[architecture cible du read side](read-side-target.md), notamment sa section 10 ;
- le [plan directeur du Lot 7](../plans/lot-7-read-side-implementation-plan.md), notamment 7.9.3 et
  7.10 ;
- la [reconstruction historique canonique](pot-historical-reconstruction.md), qui définit comment
  `creator_id` et les Shareholders actifs permettent de reconstruire les faits à une version ;
- la [clôture du write side](write-side-closure.md), qui reste autoritative sur le chemin Command ;
- la [propriété des types](type-ownership.md) et la
  [matrice de dépendances](module-dependency-matrix.md) pour les frontières de modules existantes.

En cas de contradiction sur l'architecture read-side, `read-side-target.md` prévaut. Dans le
périmètre plus précis des contrats 7.10.1, le présent document constitue l'entrée canonique d'un
futur plan d'implémentation. L'état actuel est une contrainte de migration et de vocabulaire, jamais
une raison de contredire la cible.

## 2. Périmètre de 7.10.1

Le lot définit uniquement :

- les capacités courantes présentées à une requête ;
- le petit modèle de relations historiques que `AUTH(V)` doit représenter conceptuellement ;
- les types de cibles et les faits métier nécessaires à l'autorisation ;
- le contrat logique des faits résolus pour une décision à une version ;
- les intentions métier autorisables ;
- les deux niveaux de policy et leur orchestration ;
- la décision pure produite lorsque toutes les entrées sont disponibles ;
- les invariants `CURRENT`, `EXACT(V)` et fail-closed à appliquer ultérieurement.

Il ne crée ni pipeline, ni artifact persistant, ni raccordement HTTP et ne constitue pas un plan
d'implémentation détaillé.

## 3. Invariants canoniques

1. Le modèle conceptuel est :

   ```text
   current TokenCapabilities
   + required current capabilities
   + AuthorizationTarget
   + AuthorizationFacts
   + PotAction
   -> AuthorizationDecision
   ```

2. Le système reste un kernel d'autorisation du domaine Pot et de ses sous-objets. Il ne devient pas
   un moteur RBAC générique.
3. Les capacités du token sont courantes et provider-neutral. Elles ne sont jamais historisées par
   businessVersion et ne sont jamais persistées dans `AUTH(V)`.
4. `AUTH(V)` historise les relations structurelles nécessaires à l'autorisation, jamais une liste
   croissante de faits dérivés ni le résultat d'une policy d'autorisation.
5. Le contrat logique des faits sélectionnés pour une décision ne préjuge ni de la granularité ni du
   layout physique de l'artifact complet `AUTH(V)`.
6. Aucun scope, rôle IAM, capability, droit dérivé ou résultat de policy n'appartient à `AUTH(V)`.
7. `AuthorizationFacts` est une vue éphémère calculée pour une décision à partir du modèle de
   relations d'autorisation, de l'utilisateur courant et de la cible.
8. Une donnée métier n'entre pas dans `AUTH(V)` simplement parce qu'une future règle d'autorisation
   pourrait la consulter.
9. Le catalogue des faits d'autorisation peut évoluer lorsque de nouvelles règles métier l'exigent,
   mais chaque fait est explicitement typé, possède une sémantique métier stable et n'est introduit
   que s'il est nécessaire à au moins une policy réelle.
10. La policy métier Pot est pure, unique et partagée entre write side et read side.
11. Une action est une intention métier stable. Elle n'encode ni endpoint, ni scope, ni mécanisme
   technique, ni historicité.
12. L'historicité est une propriété de la requête et des capacités courantes requises, pas une
    variante de l'action métier.
13. `AuthorizationKernel`, `TokenCapabilityPolicy` et `PotBusinessAuthorizationPolicy` ne reçoivent
    jamais `CURRENT`, `EXACT(V)`, un mode historique ou une businessVersion à sélectionner.
14. Une action sans mapping explicite vers ses capacités requises est refusée par défaut et produit
    un diagnostic interne de configuration.
15. `AuthorizationDecision` exprime uniquement le résultat de la policy d'autorisation sur des
    entrées déjà disponibles. Elle ne transporte aucun état de projection ou de convergence.

## 4. Modèle conceptuel

Les responsabilités sont séparées :

```text
adapter de sécurité
  token / autorités externes -> TokenCapabilities courantes

application orchestration
  PotAction -> capacités courantes requises via le mapping central
  ajoute VIEW_ARCHIVE lorsque l'intention de requête l'exige
  -> required current capabilities

source de relations et dérivation des faits
  write side current domain state
    -> current authorization relation model
    -> derive(current userId, AuthorizationTarget)
    -> AuthorizationFacts

  read side AUTH(V)
    -> historical authorization relation model at V
    -> derive(current userId, AuthorizationTarget)
    -> PotAuthorizationAtVersion
    -> AuthorizationFacts

AuthorizationKernel
  TokenCapabilityPolicy(TokenCapabilities, required current capabilities)
  + PotBusinessAuthorizationPolicy(AuthorizationTargetType, AuthorizationFacts, PotAction)
  -> AuthorizationDecision
```

La résolution de version, le chargement du modèle de relations et la dérivation des faits précèdent
la décision.
Le kernel ne charge ni token, ni Pot, ni artifact et ne résout aucune version. Il reçoit l'ensemble
des capacités requises déjà déterminé et ignore pourquoi chacune d'elles est requise.

## 5. `TokenCapabilities`

`TokenCapabilities` est un ensemble typé de capacités accordées au token présenté au moment de la
requête. Une capacité comme `POT_UPDATE` signifie seulement que l'identité courante peut tenter cette
catégorie d'opération ; elle ne l'autorise jamais à modifier un Pot précis.

Les capacités sont :

- courantes et issues de l'authentification de la requête ;
- exprimées dans le vocabulaire applicatif ;
- indépendantes de Keycloak, JWT et du format brut des scopes ;
- absentes de toute projection `AUTH(V)` et de tout historique indexé par businessVersion.

Le vocabulaire provider-neutral existant `Permission(objectType, action)` et les constantes de
`PocomaPermissions` doivent être réutilisés ou adaptés derrière ce contrat, sans exposer leur forme
générique au kernel comme un RBAC extensible. Le catalogue cible comprend au minimum :

```text
POT_VIEW          POT_UPDATE          POT_CREATE          POT_DELETE
SHAREHOLDER_VIEW  SHAREHOLDER_UPDATE  SHAREHOLDER_CREATE  SHAREHOLDER_DELETE
EXPENSE_VIEW      EXPENSE_UPDATE      EXPENSE_CREATE      EXPENSE_DELETE
BALANCE_VIEW
VIEW_ARCHIVE
```

`VIEW_ARCHIVE` est une capacité courante transversale supplémentaire. Les constantes actuelles
`POT_VIEW_ARCHIVE`, `SHAREHOLDER_VIEW_ARCHIVE` et `EXPENSE_VIEW_ARCHIVE`, et l'absence d'un équivalent
Balance, ne définissent pas la cible : leur convergence vers le contrat unique relève de
l'implémentation future.

Le parsing ou mapping des autorités/scopes Keycloak appartient exclusivement à un adapter de
sécurité. `ExternalAuthorityPermissionTranslator` illustre déjà une frontière provider-neutral côté
admission Command ; son format actuel n'est pas imposé au kernel.

L'`AuthorizationSnapshot` durable d'une Command est une preuve d'admission propre au write side. Il
n'est ni un `TokenCapabilities` relu pour une requête future, ni un fait `AUTH(V)`, ni un précédent
autorisant des « scopes historiques » par businessVersion.

## 6. `AUTH(V)` et `PotAuthorizationAtVersion`

### Modèle historique de relations d'autorisation

`AUTH(V)` est l'artifact read-side complet et exact du modèle relationnel historique d'autorisation
du Pot à la businessVersion V. Son contenu conceptuel minimal est :

```text
AUTH(V)
  potId
  businessVersion
  creatorUserId
  activeShareholders
    shareholderId -> userId
```

Ce petit modèle conserve les relations d'identité, de propriété et d'appartenance nécessaires aux
policies actuelles. Il permet d'établir au minimum `IS_POT_CREATOR`, `IS_POT_MEMBER` et
`IS_TARGET_SHAREHOLDER` sans persister ces booléens comme une matrice de faits ou de droits.

La formulation de `read-side-target.md` — « snapshot complet et exact de isMember/isCreator à V » —
reste vraie au niveau fonctionnel : la relation `creatorUserId` établit la qualité de créateur et les
relations `shareholderId -> userId` des Shareholders actifs établissent l'appartenance. Le présent
document spécialisé précise le modèle relationnel minimal qui permet de dériver ces qualités ; il ne
change ni l'autorité ni les invariants de l'architecture read-side.

La représentation physique de l'artifact n'est pas fixée ici. En particulier, 7.10.1 ne décide ni sa
structure SQL, ni son nombre de lignes, ni sa forme sérialisée, ni son layout, ni son indexation.

> **Invariant :** `AUTH(V)` persiste les relations structurelles historiques nécessaires à
> l'autorisation, pas une liste croissante de faits dérivés ni de résultats de policy.

### Frontière avec le modèle métier

Une donnée appartient à `AUTH(V)` si elle décrit une relation structurelle d'autorisation entre une
identité et le Pot ou l'un de ses sous-objets. Le modèle actuel admet donc :

- `creatorUserId` ;
- les identités utilisateur membres du Pot ;
- la relation `shareholderId -> userId` pour les Shareholders actifs.

Restent explicitement hors d'`AUTH(V)` :

- montant, statut ou catégorie d'une Expense ;
- poids d'un Shareholder ;
- devise, nom ou autre valeur métier du Pot sans rapport avec identité, propriété ou appartenance ;
- toute autre valeur métier générale sans relation structurelle d'autorisation.

Même si une future policy pouvait consulter l'une de ces données, ce seul usage potentiel ne justifie
pas son ajout à `AUTH(V)`. Une évolution de policy doit d'abord établir le besoin et la bonne frontière
du modèle ; elle ne transforme jamais automatiquement AUTH en réplique du domaine Pot.

> **Invariant :** une donnée métier n'entre pas dans `AUTH(V)` simplement parce qu'une future règle
> d'autorisation pourrait la consulter.

### Contexte logique d'une décision historique

`PotAuthorizationAtVersion` est un contrat logique de faits déjà sélectionnés pour une décision
concernant un utilisateur, un Pot, une businessVersion et une cible :

```text
PotAuthorizationAtVersion
  potId
  businessVersion
  userId
  target
    targetType
    targetIdentity when required by the action contract
  facts
    Set<AuthorizationFact>
```

Il peut être dérivé ou extrait de l'artifact complet `AUTH(V)`. Il n'est pas nécessairement une ligne,
un document sérialisé ni l'artifact persistant lui-même. `potId`, `businessVersion`, `userId` et la
cible permettent de désigner et de vérifier les bons faits ; la policy métier reçoit ensuite
seulement `targetType`, les faits sélectionnés et l'action.

Les `AuthorizationFacts` de ce contrat sont dérivés de l'artifact, de `userId` et de la cible. Ils ne
définissent pas la persistence d'`AUTH(V)` et ne doivent pas y être recopiés comme une matrice par
utilisateur, cible et action.

Le contrat logique d'une décision ne préjuge pas de la granularité physique de l'artifact. La
sélection et la dérivation des faits pertinents pour un utilisateur et une cible interviennent lors
de la lecture ou de la résolution d'`AUTH(V)`.

Le contrat logique et l'artifact ne contiennent jamais :

- scopes, capacités ou rôles IAM ;
- `canView`, `canUpdate`, `canDelete` ou tout autre droit calculé ;
- résultat ou version d'une policy ;
- état de readiness, failure, pipeline ou convergence.

Conformément à la reconstruction historique canonique, `creatorUserId` est reconstructible depuis le
`creator_id` du Pot à V. Les relations actives `shareholderId -> userId` sont reconstructibles depuis
les Shareholders applicables à V, non supprimés et liés à un utilisateur. Leur production et leur
persistence exactes appartiennent au Lot 7.10.3.

> **Invariant :** `AUTH(V)` historise le petit modèle de relations métier nécessaire à
> l'autorisation, jamais des capacités courantes, des faits dérivés persistés ou le résultat d'une
> policy.

## 7. `AuthorizationTargetType` et `AuthorizationFacts`

### Type de cible

`AuthorizationTargetType` est un type fermé représentant la nature de l'objet métier cible :

```text
AuthorizationTargetType
  POT
  EXPENSE
  SHAREHOLDER
```

`EXPENSE` reste une cible distincte même si ses policies utilisent actuellement les mêmes faits Pot
que les policies visant directement un Pot.

`BALANCE` n'est pas un `AuthorizationTargetType`. Une Balance est une vue ou projection du Pot et ne
possède pas de modèle d'autorisation autonome. `VIEW_BALANCE` reste une `PotAction` distincte dont la
cible d'autorisation est `AuthorizationTargetType.POT`.

Une cible est soit existante et identifiée, soit prospective lorsque l'objet métier n'a pas encore
été créé :

```text
AuthorizationTarget
  Existing
    targetType
    targetId
  Prospective
    targetType

Existing POT         -> potId
Existing EXPENSE     -> expenseId
Existing SHAREHOLDER -> shareholderId
Prospective EXPENSE
Prospective SHAREHOLDER
```

`CREATE_POT` restant hors du kernel Pot-scoped, aucune cible `POT` prospective n'est requise.

> **Invariant :** une identité de cible est obligatoire uniquement lorsqu'elle est nécessaire pour
> satisfaire le contrat de l'action ou dériver les faits requis par sa policy.

La représentation Java exacte et le type technique de `targetId` ne sont pas fixés ici. La cohérence
entre le type, l'état existant ou prospectif et l'identité éventuellement fournie est un invariant de
contrat. Une action exigeant une cible existante mais recevant une cible prospective est refusée par
défaut avec un diagnostic de configuration.

`CREATE_EXPENSE` accepte une cible `EXPENSE` prospective : ses faits sont les faits Pot courants et,
après `ALLOW`, le domaine choisit l'`ExpenseId` comme aujourd'hui. `ADD_SHAREHOLDER` accepte de même
une cible `SHAREHOLDER` prospective et le domaine choisit ensuite le ou les `ShareholderId`. La
préallocation reste possible si une future policy réelle exige l'identité avant création, mais elle
n'est pas une exigence de 7.10.1.

### Catalogue de faits

`AuthorizationFacts` est une vue éphémère et typée des faits métier établis pour un utilisateur et
une cible précis :

```text
AuthorizationFacts
  Set<AuthorizationFact>

AuthorizationFact
  IS_POT_CREATOR
  IS_POT_MEMBER
  IS_TARGET_SHAREHOLDER
```

La représentation `Map<String, Boolean>` et tout mécanisme de propriétés dynamiques non typées sont
interdits. Le catalogue publié est fermé ; son évolution exige l'ajout explicite d'un fait typé,
motivé par une règle métier réelle et doté d'une sémantique stable.

`IS_TARGET_SHAREHOLDER` signifie exactement :

> L'utilisateur objet de la décision correspond au Shareholder cible de l'action.

Ce fait ne doit jamais être nommé `IS_SHAREHOLDER`, qui serait ambigu avec l'appartenance générale au
Pot exprimée par `IS_POT_MEMBER`.

Le catalogue minimal n'inclut pas `IS_EXPENSE_CREATOR` : aucune règle métier actuelle n'en a besoin.
Aucun fait spéculatif ne doit être introduit.

Les faits sont calculés, jamais persistés comme matrice, à partir de :

```text
historical authorization relation model from AUTH(V)
+ current userId
+ AuthorizationTarget
-> AuthorizationFacts

ou, côté write :

current authorization relation model from domain state
+ current userId
+ AuthorizationTarget
-> AuthorizationFacts
```

Par exemple :

```text
IS_POT_CREATOR
= relationModel.creatorUserId == currentUserId

IS_POT_MEMBER
= currentUserId appartient aux userId des activeShareholders

IS_TARGET_SHAREHOLDER
= target is Existing SHAREHOLDER
  && relationModel.activeShareholders[target.targetId] == currentUserId
```

Une cible `SHAREHOLDER` prospective ne produit jamais `IS_TARGET_SHAREHOLDER`. Cette absence n'est
pas une erreur pour `ADD_SHAREHOLDER`, dont la règle ne dépend pas de l'identité du futur objet.

> **Invariant :** `AuthorizationFacts` est une vue éphémère calculée pour une décision à partir du
> modèle de relations d'autorisation, de l'utilisateur courant et de la cible.

### Faits attendus par type de cible

```text
target = POT
facts possibles :
  IS_POT_CREATOR
  IS_POT_MEMBER

target = EXPENSE
facts possibles :
  IS_POT_CREATOR
  IS_POT_MEMBER

target = SHAREHOLDER
facts possibles :
  IS_POT_CREATOR
  IS_POT_MEMBER
  IS_TARGET_SHAREHOLDER
```

Un fait absent est faux pour la décision. La source des faits doit néanmoins distinguer une absence
établie d'une impossibilité à charger ou résoudre les faits : cette dernière est un état amont et ne
doit pas être convertie silencieusement en décision métier.

Le write side construit d'abord le modèle courant de relations depuis l'état métier, puis en dérive
les faits. Le read side lit le modèle historique depuis `AUTH(V)`, puis applique la même dérivation
logique. Les deux chemins doivent produire les mêmes faits pour des relations, un utilisateur et une
cible équivalents.

## 8. `PotAction`

`PotAction` est un ensemble fermé d'intentions métier stables et Pot-scoped. Les noms sont alignés sur
la granularité des use cases métier spécialisés existants :

```text
VIEW_POT
UPDATE_POT_DETAILS
DELETE_POT
VIEW_SHAREHOLDER
ADD_SHAREHOLDER
UPDATE_SHAREHOLDER_DETAILS
UPDATE_SHAREHOLDER_WEIGHTS
REMOVE_SHAREHOLDER
VIEW_EXPENSE
CREATE_EXPENSE
UPDATE_EXPENSE_DETAILS
UPDATE_EXPENSE_SHARES
DELETE_EXPENSE
VIEW_BALANCE
```

`CREATE_POT`, qui ne possède encore ni Pot ni faits Pot, relève de la seule capability courante et ne
fait pas partie du kernel Pot-scoped défini ici. Il ne justifie ni faits Pot fictifs ni
généralisation RBAC.

Sont interdits : `VIEW_POT_ARCHIVE`, `VIEW_BALANCE_ARCHIVE`, noms d'endpoints, noms de scopes,
opérations SQL/JPA et variantes `CURRENT`/`EXACT`.

> **Invariant :** l'historicité est une propriété de la requête et des capacités courantes requises,
> pas une variante de l'action métier.

## 9. Mapping central et `TokenCapabilityPolicy`

Le mapping canonique initial est :

| `PotAction` | `AuthorizationTargetType` | Capability courante de base | Contrat de cible |
|---|---|---|---|
| `VIEW_POT` | `POT` | `POT_VIEW` | existante |
| `UPDATE_POT_DETAILS` | `POT` | `POT_UPDATE` | existante |
| `DELETE_POT` | `POT` | `POT_DELETE` | existante |
| `VIEW_SHAREHOLDER` | `SHAREHOLDER` | `SHAREHOLDER_VIEW` | existante |
| `ADD_SHAREHOLDER` | `SHAREHOLDER` | `SHAREHOLDER_CREATE` | prospective admise |
| `UPDATE_SHAREHOLDER_DETAILS` | `SHAREHOLDER` | `SHAREHOLDER_UPDATE` | existante |
| `UPDATE_SHAREHOLDER_WEIGHTS` | `SHAREHOLDER` | `SHAREHOLDER_UPDATE` | existante |
| `REMOVE_SHAREHOLDER` | `SHAREHOLDER` | `SHAREHOLDER_DELETE` | existante |
| `VIEW_EXPENSE` | `EXPENSE` | `EXPENSE_VIEW` | existante |
| `CREATE_EXPENSE` | `EXPENSE` | `EXPENSE_CREATE` | prospective admise |
| `UPDATE_EXPENSE_DETAILS` | `EXPENSE` | `EXPENSE_UPDATE` | existante |
| `UPDATE_EXPENSE_SHARES` | `EXPENSE` | `EXPENSE_UPDATE` | existante |
| `DELETE_EXPENSE` | `EXPENSE` | `EXPENSE_DELETE` | existante |
| `VIEW_BALANCE` | `POT` | `BALANCE_VIEW` | existante |

Chaque action possède une entrée explicite. Il n'existe ni héritage implicite de capability entre
actions, ni valeur signifiant « aucune capability requise ». Le mapping appartient à la frontière
d'autorisation et constitue l'unique source utilisée par l'orchestration applicative ; il n'est
jamais dispersé dans les controllers, handlers ou endpoints.

L'orchestration applicative obtient d'abord les capacités de base via ce mapping. Elle ajoute
`VIEW_ARCHIVE` lorsque l'intention de requête l'exige, puis transmet l'ensemble final au kernel. Le
mapping central ne connaît lui-même aucune historicité.

`TokenCapabilityPolicy` compare uniquement :

```text
TokenCapabilities
vs
required current capabilities
```

Elle :

- ne consulte aucun fait métier ni aucune action ;
- ne charge et ne parse aucun token ;
- ne connaît aucun provider IAM ;
- ne connaît ni l'origine ni la raison d'une capability requise ;
- ne déduit jamais qu'une capability absente est implicitement accordée ;
- retourne une décision exploitable par le kernel, notamment `MISSING_CAPABILITY`.

Toutes les capabilities de l'ensemble requis sont obligatoires. Le kernel ignore leur origine, pas
leur présence : si l'orchestration transmet par exemple `EXPENSE_VIEW` et `VIEW_ARCHIVE`, le token
doit posséder les deux.

## 10. `PotBusinessAuthorizationPolicy`

`PotBusinessAuthorizationPolicy` centralise la règle pure :

```text
AuthorizationTargetType
+ AuthorizationFacts
+ PotAction
-> business AuthorizationDecision
```

Elle ne lit aucune base ni aucun token ; elle ne connaît ni HTTP, ni Keycloak, ni `CURRENT`, ni
`EXACT`, ni mode historique, ni projection `AUTH`, ni readiness, ni convergence. Elle ne reçoit
aucune capability. Elle ne dépend directement ni de l'artifact `AUTH(V)`, ni du modèle courant du
write side : ces sources sont ramenées aux mêmes `AuthorizationFacts` avant son invocation.

La matrice métier de base reprend les règles exprimées par les policies Pot actuelles :

- cible `POT`, `VIEW_POT` : `IS_POT_CREATOR` ou `IS_POT_MEMBER` ;
- cible `POT`, `UPDATE_POT_DETAILS` ou `DELETE_POT` : `IS_POT_CREATOR` ;
- cible `POT`, `VIEW_BALANCE` : `IS_POT_CREATOR` ou `IS_POT_MEMBER` ;
- cible `EXPENSE`, lecture, création, mise à jour ou suppression : `IS_POT_CREATOR` ou
  `IS_POT_MEMBER` ;
- cible `SHAREHOLDER`, `VIEW_SHAREHOLDER` : `IS_POT_CREATOR` ou `IS_POT_MEMBER` ;
- cible `SHAREHOLDER`, ajout, retrait ou modification des poids : `IS_POT_CREATOR` ;
- cible `SHAREHOLDER`, `UPDATE_SHAREHOLDER_DETAILS` : `IS_POT_CREATOR` ou
  `IS_TARGET_SHAREHOLDER`.

La dernière règle préserve explicitement le comportement métier actuel : le créateur peut modifier
les détails d'un Shareholder et le Shareholder concerné peut modifier ses propres informations. La
simple présence de `IS_POT_MEMBER` ne suffit pas pour modifier les détails d'un autre Shareholder.

Une combinaison incompatible entre target type et action est refusée par défaut et diagnostiquée
comme erreur de configuration. Une nouvelle règle qui ne peut pas être exprimée avec le catalogue
actuel impose une évolution explicite d'`AuthorizationFact` ; elle ne justifie jamais une clé
dynamique ou un droit dérivé persistant.

Le write side et le read side réutilisent cette même policy. Les classes actuelles
`ReadPotAuthorizationPolicy`, `UpdatePotDetailsAuthorizationPolicy`, etc. expriment le vocabulaire et
les règles à préserver, mais leur mélange actuel entre `Permission` et faits Pot n'est pas le contrat
cible.

## 11. `AuthorizationKernel`

`AuthorizationKernel` reçoit :

```text
TokenCapabilities
required current capabilities
AuthorizationTarget
AuthorizationFacts
PotAction
```

Il orchestre, sans I/O :

1. l'évaluation par `TokenCapabilityPolicy` des capacités courantes déjà déterminées ;
2. seulement si elle réussit, l'évaluation par `PotBusinessAuthorizationPolicy` du target type, des
   faits et de l'action ;
3. la production d'une unique `AuthorizationDecision`.

Le kernel ne reçoit et ne connaît jamais `CURRENT`, `EXACT(V)`, une businessVersion à sélectionner,
un mode historique ou un mode archive. Il ne décide jamais d'ajouter `VIEW_ARCHIVE`. Il ignore si une
capability requise provient du mapping de l'action ou a été ajoutée par l'orchestration applicative.
Il vérifie néanmoins chacune des capabilities reçues comme requise.

Le kernel ne charge ni token, ni Pot, ni artifact `AUTH(V)`. Les capacités et les faits demeurent deux
entrées distinctes ; aucun modèle persistant ou objet opaque ne doit les fusionner.

## 12. `AuthorizationDecision`

La décision n'est pas un simple booléen. Elle distingue au minimum :

```text
ALLOW
DENY(reason)
```

Le contrat principal conserve un ensemble minimal de raisons :

```text
MISSING_CAPABILITY
BUSINESS_POLICY_DENIED
CONFIGURATION_ERROR
```

Une policy disjonctive telle que `IS_POT_CREATOR || IS_POT_MEMBER` ne choisit pas arbitrairement une
raison élémentaire lorsque les deux prédicats échouent. Un diagnostic interne plus détaillé peut être
produit pour les tests ou l'observabilité sans complexifier `AuthorizationDecision` ni gouverner son
mapping HTTP.

Une raison sert aux tests, au diagnostic et éventuellement à l'observabilité. Elle ne définit jamais
directement le statut ou le corps HTTP public.

Sont interdits dans `AuthorizationDecision` : `NOT_READY`, `PROJECTION_FAILED`, `AUTH_FAILED`, états
de Task/Claim, lifecycle de pipeline et états de convergence.

> **Invariant :** `AuthorizationDecision` exprime uniquement le résultat de la policy
> d'autorisation sur des entrées déjà disponibles.

## 13. `CURRENT` versus `EXACT(V)`

L'historicité appartient exclusivement à l'orchestration applicative en amont du kernel.

Pour une lecture courante protégée, l'orchestration construit :

```text
required current capabilities
= capability de base requise par PotAction
```

Pour une lecture historique protégée, elle construit :

```text
required current capabilities
= capability de base requise par PotAction
+ VIEW_ARCHIVE
```

Elle transmet ensuite cet ensemble au kernel. `AuthorizationKernel`, `TokenCapabilityPolicy` et
`PotBusinessAuthorizationPolicy` ne reçoivent aucune information `CURRENT`, `EXACT(V)`, historique ou
archive. Le kernel ne sait pas pourquoi `VIEW_ARCHIVE` est présent.

Pour le read side, `servedVersion` est déterminée par le Query Version Resolver conformément à
`read-side-target.md`, puis les faits pertinents sont sélectionnés depuis AUTH à cette version exacte.
Ni le kernel ni les policies ne choisissent une businessVersion ou une pipelineVersion, et aucun
refus ne déclenche de fallback.

`VIEW_ARCHIVE` n'est jamais reconstruit depuis l'historique, lu depuis `AUTH(V)` ou persisté comme un
fait historique. Une évolution des capacités courantes peut donc modifier l'accès à une ancienne
businessVersion sans reconstruire `AUTH(V)`.

L'ordre exact de résolution, le masquage sans fuite et le mapping HTTP restent gouvernés par 7.9.3 et
7.10.4.

## 14. Fail-closed et erreurs de configuration

Le catalogue `PotAction -> AuthorizationTargetType + capabilities de base` est total pour toute
action déployée :

```text
known PotAction
+ no explicit mapping
-> DENY(CONFIGURATION_ERROR)
-> diagnostic interne
```

Une absence de mapping ne signifie jamais « aucune capability requise ». Une combinaison action/cible
incompatible, notamment une cible prospective pour une action exigeant un objet existant, suit la
même règle fail-closed. Le diagnostic de configuration doit être distinguable
en interne d'un refus métier normal et testable comme tel. Sa traduction externe peut rester
indistinguable d'un refus ordinaire ; ce choix relève de 7.9.3.

Les entrées invalides ou incohérentes sont également refusées ou rejetées comme erreurs de contrat ;
elles ne produisent jamais un `ALLOW` par défaut.

## 15. Partage write/read

Le partage porte sur la dérivation logique des faits et sur la policy métier, pas sur les mécanismes
d'acquisition ou les représentations persistantes :

```text
write side current Pot state
  -> current authorization relation model
  -> derive(current userId, AuthorizationTarget)
  -> AuthorizationFacts -----------------------------+
                                                      |
read side AUTH(V)
  -> historical authorization relation model
  -> derive(current userId, AuthorizationTarget)
  -> PotAuthorizationAtVersion
  -> AuthorizationFacts -----------------------------+
                                                      |
                                                      v
  AuthorizationTargetType + AuthorizationFacts + PotAction
  -> PotBusinessAuthorizationPolicy
```

À relations structurelles, utilisateur, cible et action équivalents, les deux chemins doivent produire
les mêmes `AuthorizationFacts` et la même décision métier. Le write side peut conserver ses
contraintes propres d'admission et de Command, mais il ne duplique ni la dérivation des faits ni la
matrice métier. Le read side ne dépend pas d'un objet write-side ni l'inverse. La policy ne dépend
jamais directement de l'artifact AUTH.

## 16. Ce qui est explicitement hors périmètre

N'appartiennent pas au Lot 7.10.1 :

- déclaration et scheduling du pipeline AUTH : 7.10.2 ;
- production, reconstruction exacte et persistence du modèle historique de relations dans
  l'artifact complet `AUTH(V)` : 7.10.3 ;
- structure SQL, nombre de lignes, forme sérialisée, layout physique et indexation d'`AUTH(V)` :
  7.10.3 ;
- lifecycle, éligibilité et sélection de la `pipelineVersion` serving d'AUTH : 7.10.4 avec 7.14 ;
- intégration complète du Query Kernel et décision d'accès à `servedVersion` : 7.10.4 ;
- ordre exact de masquage HTTP et mapping final des refus en codes HTTP : 7.9.3 ;
- packages Java, wiring, migration et séquencement détaillé.

## 17. Conséquences attendues pour l'implémentation future

Une implémentation conforme devra :

- introduire des contrats typés sans dépendance framework ;
- fermer l'ownership des types AUTH encore ouvert dans `type-ownership.md` ;
- isoler la traduction des autorités externes dans les adapters de sécurité ;
- séparer des policies actuelles la vérification des capacités et la règle métier Pot ;
- remplacer toute duplication write/read de la matrice métier par la policy pure partagée ;
- représenter les types de cible et les faits par des catalogues typés, sans propriétés dynamiques ;
- représenter explicitement les cibles existantes identifiées et les cibles prospectives admises par
  les actions de création ;
- dériver les faits d'une décision depuis le modèle de relations, l'utilisateur et la cible sans les
  assimiler au contenu persistant de l'artifact complet `AUTH(V)` ;
- limiter AUTH aux relations structurelles d'identité, de propriété et d'appartenance nécessaires,
  sans y recopier les valeurs générales du domaine Pot ;
- rendre le mapping de chaque `PotAction` explicite, central et exhaustivement testé ;
- construire les capacités requises, y compris `VIEW_ARCHIVE`, avant l'appel au kernel ;
- empêcher structurellement toute persistence de capacités, de faits dérivés ou de droits calculés
  dans AUTH ;
- conserver les états opérationnels hors d'`AuthorizationDecision`.

Ces conséquences sont des critères architecturaux, pas un découpage de commits ou de classes.

## 18. Cas de test canoniques

1. Token sans capability requise et faits métier autorisés : `DENY(MISSING_CAPABILITY)`.
2. Token avec capability requise et faits métier insuffisants : `DENY` métier.
3. Token avec capability requise et faits métier autorisés : `ALLOW`.
4. `AUTH(V)` contient `creatorUserId = U1` ; une décision pour `userId = U1` dérive
   `IS_POT_CREATOR`.
5. `AUTH(V)` contient un Shareholder actif lié à `U2` ; une décision pour `userId = U2` dérive
   `IS_POT_MEMBER`.
6. La cible est `SHAREHOLDER(S7)` et la relation active vaut `S7 -> U2` ; une décision pour `U2`
   dérive `IS_TARGET_SHAREHOLDER`.
7. La cible est `SHAREHOLDER(S8)` et la relation active vaut `S8 -> U3` ; une décision pour `U2` ne
   dérive pas `IS_TARGET_SHAREHOLDER`.
8. Une cible `SHAREHOLDER` prospective est valide pour `ADD_SHAREHOLDER`, mais ne dérive jamais
   `IS_TARGET_SHAREHOLDER`.
9. L'identité logique de la cible est obligatoire pour toute règle target-specific qui en dépend.
10. `AuthorizationFacts` n'est jamais persisté comme matrice de faits ou de permissions dans
    `AUTH(V)`.
11. L'ajout d'une nouvelle `PotAction` réutilisant les relations existantes ne force aucune
    modification d'`AUTH(V)`.
12. Une future policy nécessitant une donnée métier non relationnelle ne provoque pas automatiquement
    l'ajout de cette donnée à `AUTH(V)`.
13. Des relations structurelles, un utilisateur et une cible identiques produisent les mêmes
    `AuthorizationFacts` depuis l'état write-side courant et depuis `AUTH(V)`.
14. Cible `POT`, fait `IS_POT_MEMBER`, action `VIEW_POT` : autorisation selon la policy de lecture.
15. Cible `EXPENSE`, fait `IS_POT_MEMBER`, action Expense autorisée par la règle actuelle : `ALLOW` si
    la capability requise est présente.
16. Cible `SHAREHOLDER`, simple `IS_POT_MEMBER` sans `IS_TARGET_SHAREHOLDER`, action
    `UPDATE_SHAREHOLDER_DETAILS` : `DENY` métier.
17. Cible `SHAREHOLDER`, fait `IS_TARGET_SHAREHOLDER`, action `UPDATE_SHAREHOLDER_DETAILS` : `ALLOW` si
    la capability requise est présente.
18. Cible `SHAREHOLDER`, fait `IS_POT_CREATOR` sans `IS_TARGET_SHAREHOLDER`, action
    `UPDATE_SHAREHOLDER_DETAILS` : `ALLOW` si la capability requise est présente.
19. Aucune règle et aucun test ne dépend d'`IS_EXPENSE_CREATOR`.
20. `VIEW_BALANCE` utilise `AuthorizationTargetType.POT` et les faits Pot.
21. Une nouvelle règle nécessitant un nouveau fait provoque une évolution explicite du catalogue
    typé ; aucune clé dynamique n'est ajoutée silencieusement.
22. `EXACT(V)` sans `VIEW_ARCHIVE` dans les capacités requises construites en amont :
    `DENY(MISSING_CAPABILITY)` avant toute autorisation historique effective.
23. `EXACT(V)` avec `VIEW_ARCHIVE`, mais faits métier à V insuffisants : `DENY` métier.
24. `EXACT(V)` avec `VIEW_ARCHIVE` et faits métier à V autorisés : `ALLOW`.
25. À entrées identiques, `AuthorizationKernel` produit la même décision quelle que soit la raison
    pour laquelle l'orchestration a inclus `VIEW_ARCHIVE` ; le kernel n'en connaît pas l'origine.
26. Les capabilities courantes ne sont jamais lues depuis `AUTH(V)`.
27. Une modification des capabilities courantes peut modifier l'accès à une ancienne businessVersion
    sans reconstruire `AUTH(V)`.
28. Une nouvelle `PotAction` sans mapping explicite : fail-closed avec diagnostic
    `CONFIGURATION_ERROR`.
29. La même implémentation de `PotBusinessAuthorizationPolicy` accepte des faits dérivés du write side
    courant ou du modèle relationnel historique read-side.
30. Aucune policy ni le kernel ne reçoit d'information `CURRENT`, `EXACT`, historique ou archive.
31. `VIEW_ARCHIVE` n'est jamais persisté comme fait historique.
32. Aucun droit dérivé (`canView`, `canUpdate`, etc.) n'est persisté dans AUTH.
33. `AuthorizationDecision` ne contient aucun état opérationnel de projection.
34. `AUTH(V)` reste conceptuellement un artifact complet du petit modèle relationnel historique ; le
    contexte logique d'une décision en est dérivé sans que 7.10.1 en fixe la granularité physique.
35. À target type, faits et action identiques, les chemins write et read produisent la même décision
    métier.
36. `CREATE_EXPENSE` avec une cible `EXPENSE` prospective et des faits Pot suffisants : `ALLOW` si
    toutes les capabilities requises sont présentes ; l'ID est choisi ensuite par le domaine.
37. `ADD_SHAREHOLDER` avec une cible `SHAREHOLDER` prospective et `IS_POT_CREATOR` : `ALLOW` si
    toutes les capabilities requises sont présentes ; un batch ne nécessite qu'une décision.
38. `UPDATE_SHAREHOLDER_DETAILS` avec une cible prospective : `DENY(CONFIGURATION_ERROR)`.
39. Une action visant une Expense existante mais recevant une cible prospective :
    `DENY(CONFIGURATION_ERROR)`.
40. Une capability supplémentaire présente dans `RequiredCurrentCapabilities` mais absente du token :
    `DENY(MISSING_CAPABILITY)`.
41. Aucun test du lot n'exige de préallouer un `ExpenseId` ou un `ShareholderId` avant une action de
    création.
42. Pour un batch target-specific visant des objets existants, toutes les décisions sont calculées
    depuis le même état pré-mutation et aucune mutation ne précède leur réussite complète.
