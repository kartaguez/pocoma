# Lot 7.10.1 — Plan d'implémentation du kernel d'autorisation

Statut : **IMPLEMENTED**.

## 1. Objet, autorité et dépendance documentaire

Ce document planifie l'implémentation incrémentale des contrats et policies pures du Lot 7.10.1. Il
s'appuie en priorité sur :

- les [contrats canoniques du kernel](../architecture/authorization-kernel-contracts.md) ;
- l'[architecture cible du read side](../architecture/read-side-target.md) ;
- le [plan directeur du Lot 7](lot-7-read-side-implementation-plan.md) ;
- la [clôture du write side](../architecture/write-side-closure.md) ;
- la [propriété des types](../architecture/type-ownership.md) ;
- la [matrice de dépendances](../architecture/module-dependency-matrix.md) ;
- la [reconstruction historique](../architecture/pot-historical-reconstruction.md) ;
- l'[analyse d'impact de la préallocation des IDs](../architecture/lot-7.10.1-preallocated-ids-impact-analysis.md).

Le cadrage complémentaire acté après l'étude d'impact abandonne la préallocation obligatoire des
IDs pour les actions de création. Il distingue désormais une cible existante, identifiée, d'une
cible prospective dont l'identité métier n'existe pas encore.

Le document canonique a été aligné avant l'implémentation : il distingue désormais les cibles
existantes des cibles prospectives et rend l'identité obligatoire seulement lorsque le contrat de
l'action ou la dérivation d'un fact l'exige.

## 2. Résultat attendu

À la fin de 7.10.1, le code expose et utilise :

```text
TokenCapabilities
+ RequiredCurrentCapabilities
+ AuthorizationTarget (existing ou prospective)
+ AuthorizationFacts
+ PotAction
-> AuthorizationDecision
```

avec séparation stricte entre :

```text
TokenCapabilityPolicy
PotBusinessAuthorizationPolicy
AuthorizationKernel
```

Le kernel est framework-free et sans I/O. Il ne connaît ni Keycloak, ni JWT, ni HTTP, ni
`CURRENT`, ni `EXACT(V)`, ni pipeline, readiness ou convergence. La business policy est réutilisable
depuis des relations métier courantes du write side et, ultérieurement, depuis les relations
historiques d'un artifact `AUTH(V)`.

Le lot ne modifie pas le mode actuel de génération des `ExpenseId` et `ShareholderId`. Une action de
création peut être autorisée sur une cible prospective lorsque son identité n'est nécessaire ni au
contrat de l'action, ni aux facts consultés par sa policy.

## 3. État actuel du code

### 3.1 Capabilities et contexte de sécurité

Le module `domain-authorization` possède aujourd'hui :

- `Permission`, record provider-neutral composé de `objectType` et `action` ;
- `PocomaPermissions`, catalogue de constantes Pot, Shareholder, Expense et Balance ;
- trois permissions d'archive spécialisées (`POT_VIEW_ARCHIVE`, `SHAREHOLDER_VIEW_ARCHIVE`,
  `EXPENSE_VIEW_ARCHIVE`) au lieu de l'unique capability canonique `VIEW_ARCHIVE`.

`ExternalAuthorityPermissionTranslator`, dans `orchestrator-command-admission`, transforme les
autorités externes de forme `pocoma:<object>:<action>` en `Permission`. Cette traduction est déjà
placée à la frontière de sécurité et ne doit pas entrer dans le kernel.

`AuthorizationSnapshot`, dans `engine-command`, conserve l'utilisateur et le `Set<Permission>`
courant enregistrés à l'admission de la Command. Il est durable pour l'exécution asynchrone mais
reste distinct d'un artifact historique `AUTH(V)`. `AbstractPotCommandUseCaseAdapter` le transforme
en `UserContext`, dont le champ `permissions` est encore un ensemble brut.

### 3.2 Policies Pot existantes

Le module `domain-pot-policy` contient treize policies spécialisées :

- Pot : `CreatePotAuthorizationPolicy`, `ReadPotAuthorizationPolicy`,
  `UpdatePotDetailsAuthorizationPolicy`, `DeletePotAuthorizationPolicy` ;
- Shareholder : `AddPotShareholdersAuthorizationPolicy`,
  `UpdatePotShareholdersDetailsAuthorizationPolicy`,
  `UpdatePotShareholdersWeightsAuthorizationPolicy` ;
- Expense : `ReadExpenseAuthorizationPolicy`, `CreateExpenseAuthorizationPolicy`,
  `UpdateExpenseDetailsAuthorizationPolicy`, `UpdateExpenseSharesAuthorizationPolicy`,
  `DeleteExpenseAuthorizationPolicy` ;
- Balance : `ReadBalanceAuthorizationPolicy`.

Chaque classe mélange actuellement :

- authentification et présence d'une `Permission` ;
- reconstruction ad hoc de `isCreator`, `isMember` ou de la relation au Shareholder cible ;
- règle métier ;
- levée directe de `BusinessRuleViolationException`.

La matrice métier est donc dispersée, même si les tests spécialisés décrivent déjà l'essentiel des
règles à préserver. `CreatePotAuthorizationPolicy` reste hors du nouveau kernel, car `CREATE_POT`
n'est pas une action Pot-scoped.

### 3.3 Use cases write-side et facts incomplets

Les services de `engine-pot-command` reçoivent les policies spécialisées via
`PotBusinessUseCaseFactory` et les adapters publics de Command. Le wiring Spring correspondant se
trouve dans `PotCommandBindingConfiguration`.

Plusieurs appels ne fournissent pas les relations déjà prévues par les anciennes policies :

- `CreateExpenseService`, `DeleteExpenseService`, `UpdateExpenseDetailsService` et
  `UpdateExpenseSharesService` transmettent `Set.of()` comme ensemble de membres ;
- `UpdatePotShareholdersDetailsService` transmet `null` comme utilisateur lié au Shareholder cible.

Le comportement exécutable est par conséquent plus restrictif que la matrice canonique : plusieurs
actions Expense sont de fait creator-only et le self-service Shareholder n'est pas raccordé. Le lot
doit converger vers les règles canoniques, pas conserver ces placeholders.

Les context adapters `JpaPotContextAdapter` et `JpaExpenseContextAdapter` ont déjà accès à
`JpaShareholderRepository.findActiveNotDeletedAtVersion`. Les entités obtenues portent
`shareholderId` et `userId`; aucune nouvelle table n'est nécessaire pour construire les relations
courantes `ShareholderId -> UserId`.

### 3.4 Création et identifiants

Le write side génère aujourd'hui :

- `ExpenseId` dans `ExpenseFactory.createExpense` après l'autorisation ;
- `ShareholderId` dans `PotShareholders.addShareholder` après l'autorisation ;
- `PotId` dans `PotFactory.createPot`, hors kernel Pot-scoped.

Les IDs restent générés à ces emplacements en 7.10.1. `CreateExpenseCommand` et
`AddPotShareholdersCommand.ShareholderInput` ne sont pas enrichis d'un ID prospectif. Aucun
`ExpenseIdGenerator` ou `ShareholderIdGenerator` n'est introduit.

### 3.5 Query side existant

`GetPotService`, `GetExpenseService`, `ListPotExpensesService`, `GetPotBalancesService`,
`ListUserPotsService` et `ListUserPotBalancesService` utilisent les anciennes read policies.
`QueryUseCaseFactory` et `QueryUseCaseConfiguration` les câblent individuellement.

La migration complète du Query Kernel appartient à 7.10.4. Dans 7.10.1, les anciennes read policies
deviennent seulement des façades compatibles déléguant aux composants purs. Les services query ne
reçoivent pas encore l'orchestration versionnée finale.

## 4. Architecture cible du lot

### 4.1 Ownership des types génériques de capabilities

`domain-authorization` conserve `Permission` et `PocomaPermissions` et ajoute :

```text
TokenCapabilities
  immutable Set<Permission>

RequiredCurrentCapabilities
  immutable non-empty Set<Permission>
```

Les deux wrappers sont non interchangeables par construction. `RequiredCurrentCapabilities`
permet une composition explicite afin qu'une orchestration future puisse ajouter `VIEW_ARCHIVE` à
la capability de base.

`PocomaPermissions.VIEW_ARCHIVE` devient la capability courante canonique. La normalisation depuis
les scopes externes reste dans `ExternalAuthorityPermissionTranslator`. Le format durable actuel
d'`AuthorizationSnapshot` peut rester compatible ; la conversion vers `TokenCapabilities` se fait à
la frontière d'appel du kernel.

### 4.2 Ownership des contrats Pot

`domain-pot-policy`, qui dépend déjà de `domain-authorization` et `domain-pot`, possède les contrats
Pot-scoped :

- `AuthorizationTargetType` ;
- `AuthorizationTarget` ;
- `AuthorizationFact` et `AuthorizationFacts` ;
- `PotAction` ;
- `PotActionRequirement` et son catalogue ;
- `PotAuthorizationRelations` et `PotAuthorizationFactResolver` ;
- `PotAuthorizationAtVersion`, contrat logique non persistant ;
- `AuthorizationDecision` et `AuthorizationDenialReason` ;
- `TokenCapabilityPolicy`, `PotBusinessAuthorizationPolicy` et `AuthorizationKernel`.

Ces types ne migrent ni dans `engine-query`, ni dans un module de sécurité provider-specific. Aucun
type persistant `AUTH(V)` n'est créé par 7.10.1.

### 4.3 Cibles existantes et prospectives

Le modèle Java doit rendre les états invalides difficiles à construire. Une interface scellée et des
variants typés sont préférables à `Optional<Id>` ou à un couple libre `String/String` :

```text
AuthorizationTarget
  ExistingPot(PotId)
  ExistingExpense(ExpenseId)
  ExistingShareholder(ShareholderId)
  ProspectiveExpense
  ProspectiveShareholder
```

Une cible Pot prospective n'est pas requise puisque `CREATE_POT` reste hors du kernel.

Invariant :

> Une identité de cible est obligatoire uniquement lorsqu'elle est nécessaire pour satisfaire le
> contrat de l'action ou dériver les facts requis par sa policy.

Le catalogue des requirements associe à chaque action :

```text
targetType
baseCapability
targetContract = EXISTING_ONLY | PROSPECTIVE_ALLOWED
```

`PROSPECTIVE_ALLOWED` accepte soit une cible prospective du type attendu, soit une cible existante
préallouée si une future policy en a réellement besoin. Le catalogue initial utilise :

- `PROSPECTIVE_ALLOWED` pour `CREATE_EXPENSE` et `ADD_SHAREHOLDER` ;
- `EXISTING_ONLY` pour toutes les autres actions ;
- aucune action `CREATE_POT`.

Une action `EXISTING_ONLY` recevant une cible prospective, une cible du mauvais type ou une cible
existante sans identité exploitable produit `DENY(CONFIGURATION_ERROR)`.

### 4.4 Facts et relations

Le petit modèle courant partagé avec le futur chemin read-side est :

```text
PotAuthorizationRelations
  potId
  creatorUserId
  activeShareholders: ShareholderId -> UserId
```

`PotAuthorizationFactResolver` dérive uniquement :

```text
IS_POT_CREATOR
IS_POT_MEMBER
IS_TARGET_SHAREHOLDER
```

Les deux premiers facts dépendent des relations Pot et de l'utilisateur, pas de l'identité d'une
cible prospective. `IS_TARGET_SHAREHOLDER` n'est dérivable que pour un `ExistingShareholder` :

```text
relations.activeShareholders[target.shareholderId] == currentUserId
```

Une cible prospective n'acquiert jamais artificiellement ce fact. Le resolver ne transforme pas son
absence en erreur lorsqu'une action de création n'en dépend pas. La validation du contrat
`EXISTING_ONLY` appartient au catalogue/kernel avant l'évaluation métier.

### 4.5 Mapping action, cible et capability

Le mapping central et exhaustif reste :

| `PotAction` | Target type | Capability de base | Contrat de cible |
|---|---|---|---|
| `VIEW_POT` | `POT` | `POT_VIEW` | `EXISTING_ONLY` |
| `UPDATE_POT_DETAILS` | `POT` | `POT_UPDATE` | `EXISTING_ONLY` |
| `DELETE_POT` | `POT` | `POT_DELETE` | `EXISTING_ONLY` |
| `VIEW_SHAREHOLDER` | `SHAREHOLDER` | `SHAREHOLDER_VIEW` | `EXISTING_ONLY` |
| `ADD_SHAREHOLDER` | `SHAREHOLDER` | `SHAREHOLDER_CREATE` | `PROSPECTIVE_ALLOWED` |
| `UPDATE_SHAREHOLDER_DETAILS` | `SHAREHOLDER` | `SHAREHOLDER_UPDATE` | `EXISTING_ONLY` |
| `UPDATE_SHAREHOLDER_WEIGHTS` | `SHAREHOLDER` | `SHAREHOLDER_UPDATE` | `EXISTING_ONLY` |
| `REMOVE_SHAREHOLDER` | `SHAREHOLDER` | `SHAREHOLDER_DELETE` | `EXISTING_ONLY` |
| `VIEW_EXPENSE` | `EXPENSE` | `EXPENSE_VIEW` | `EXISTING_ONLY` |
| `CREATE_EXPENSE` | `EXPENSE` | `EXPENSE_CREATE` | `PROSPECTIVE_ALLOWED` |
| `UPDATE_EXPENSE_DETAILS` | `EXPENSE` | `EXPENSE_UPDATE` | `EXISTING_ONLY` |
| `UPDATE_EXPENSE_SHARES` | `EXPENSE` | `EXPENSE_UPDATE` | `EXISTING_ONLY` |
| `DELETE_EXPENSE` | `EXPENSE` | `EXPENSE_DELETE` | `EXISTING_ONLY` |
| `VIEW_BALANCE` | `POT` | `BALANCE_VIEW` | `EXISTING_ONLY` |

Le catalogue est total sur `PotAction.values()`. Une action absente, une incohérence de type ou de
contrat de cible, ou un ensemble de requirements qui omet la capability de base est fail-closed.

### 4.6 Policies et décision

La décision principale utilise seulement :

```text
AuthorizationDecision
  ALLOW
  DENY(MISSING_CAPABILITY)
  DENY(BUSINESS_POLICY_DENIED)
  DENY(CONFIGURATION_ERROR)
```

Les policies disjonctives ne choisissent pas arbitrairement entre `NOT_MEMBER`, `NOT_CREATOR` ou
`NOT_TARGET_SHAREHOLDER`. Un diagnostic interne facultatif peut exposer les prédicats évalués pour
les tests ou l'observabilité, sans complexifier `AuthorizationDecision` ni gouverner le mapping HTTP.

`TokenCapabilityPolicy` vérifie :

```text
TokenCapabilities contains every RequiredCurrentCapability
```

Toutes les capabilities requises sont obligatoires. Le kernel n'ignore aucune capability
supplémentaire fournie dans `RequiredCurrentCapabilities`; il ignore uniquement son origine et la
raison pour laquelle l'orchestration l'a ajoutée.

`PotBusinessAuthorizationPolicy` conserve exactement la matrice canonique :

- lecture Pot et Balance : créateur ou membre ;
- modification des détails et suppression du Pot : créateur ;
- toutes les actions Expense : créateur ou membre ;
- lecture Shareholder : créateur ou membre ;
- ajout, retrait et modification des poids Shareholder : créateur ;
- modification des détails Shareholder : créateur ou Shareholder cible.

`AuthorizationKernel` :

1. valide action, type et contrat existing/prospective grâce au catalogue ;
2. vérifie que les requirements contiennent la capability de base mappée ;
3. demande à `TokenCapabilityPolicy` de vérifier toutes les capabilities requises ;
4. en cas de succès, délègue à `PotBusinessAuthorizationPolicy` ;
5. retourne une décision sans I/O ni exception métier de transport.

Il ne sait pas si `VIEW_ARCHIVE` a été ajouté pour `EXACT(V)` et produit la même décision pour les
mêmes valeurs d'entrée, quelle que soit leur origine applicative.

## 5. Séquencement d'implémentation

Chaque sous-lot doit laisser le repository compilable et ses tests verts. Les anciens types sont
conservés comme adaptateurs jusqu'à ce que leurs appelants aient migré.

### 7.10.1.A — Capabilities courantes typées

**Objectif**

Séparer structurellement les capabilities présentées par le token des capabilities courantes
requises par l'orchestration.

**Code concerné**

- `domain-authorization` : `Permission`, `PocomaPermissions` et nouveaux wrappers ;
- `orchestrator-command-admission` : `ExternalAuthorityPermissionTranslator` ;
- frontières `AuthorizationSnapshot` / `UserContext`, sans migration de format durable.

**Modifications attendues**

- ajouter `TokenCapabilities` et `RequiredCurrentCapabilities` comme collections immuables et
  null-safe ;
- fournir une composition explicite des requirements ;
- introduire la capability canonique `VIEW_ARCHIVE` ;
- normaliser les autorités externes vers ce catalogue dans l'adapter de sécurité ;
- maintenir la compatibilité de `AuthorizationSnapshot` et convertir vers le wrapper au bord du
  kernel.

**Tests**

- immutabilité, copie défensive, égalité et validation des deux wrappers ;
- impossibilité d'interchanger leurs types ;
- composition d'un requirement de base avec `VIEW_ARCHIVE` ;
- traduction externe sans dépendance du domaine à Keycloak ou JWT.

**Critère de sortie**

Le nouveau code d'autorisation ne reçoit plus de `Set<Permission>` ambigu et les capabilities restent
strictement courantes.

**Dépendances**

Aucune.

### 7.10.1.B — Actions, cibles existing/prospective et mapping

**Objectif**

Créer les catalogues fermés et rendre explicite, par action, la nécessité ou non d'une identité de
cible déjà existante.

**Code concerné**

Nouveau package d'autorisation dans `domain-pot-policy`.

**Modifications attendues**

- ajouter `AuthorizationTargetType` avec `POT`, `EXPENSE`, `SHAREHOLDER` ;
- ajouter les variants typés existing/prospective d'`AuthorizationTarget` ;
- ajouter l'unique catalogue `PotAction` ;
- ajouter `PotActionRequirement(targetType, baseCapability, targetContract)` ;
- implémenter le mapping complet présenté en section 4.5 ;
- refuser par configuration une action `EXISTING_ONLY` sur cible prospective ;
- ne créer ni cible Pot prospective, ni `CREATE_POT`.

**Tests**

- exhaustivité sur toutes les valeurs de `PotAction` ;
- mapping exact action/type/capability/contrat ;
- `VIEW_BALANCE` cible un Pot existant ;
- `CREATE_EXPENSE` accepte `ProspectiveExpense` ;
- `ADD_SHAREHOLDER` accepte `ProspectiveShareholder` ;
- action existing-only, mauvais type ou identité absente : `CONFIGURATION_ERROR` ;
- aucune représentation libre `String/String` ou état type/ID incohérent constructible.

**Critère de sortie**

Une seule table typée définit la cible, la capability de base et le besoin d'identité de chaque
action. Les créations n'exigent aucun ID artificiel.

**Dépendances**

7.10.1.A et alignement préalable de la formulation canonique signalée en section 1.

### 7.10.1.C — Relations et dérivation des facts

**Objectif**

Dériver les facts éphémères depuis un petit modèle de relations réutilisable par le write side et le
futur read side.

**Code concerné**

`domain-pot-policy` : `PotAuthorizationRelations`, `AuthorizationFact`, `AuthorizationFacts`,
`PotAuthorizationFactResolver` et contrat logique `PotAuthorizationAtVersion`.

**Modifications attendues**

- représenter le créateur et les liaisons actives `ShareholderId -> UserId` ;
- dériver `IS_POT_CREATOR` et `IS_POT_MEMBER` pour toute cible cohérente, y compris prospective ;
- dériver `IS_TARGET_SHAREHOLDER` uniquement depuis un `ExistingShareholder` identifié ;
- ne jamais produire `IS_TARGET_SHAREHOLDER` pour `ProspectiveShareholder` ;
- exclure tout fact spéculatif, notamment `IS_EXPENSE_CREATOR` ;
- garder `PotAuthorizationAtVersion` logique et non persistant.

**Tests**

- créateur, membre, non-membre ;
- Shareholder existant lié ou non à l'utilisateur ;
- cible prospective Expense/Shareholder conservant seulement les facts Pot ;
- cible existante nécessaire aux facts target-specific ;
- mêmes relations côté write/read logique : mêmes facts ;
- absence de maps de facts dynamiques et de persistence `AUTH(V)`.

**Critère de sortie**

La business policy peut être alimentée sans connaître l'origine current ou historique des relations,
et aucun ID n'est inventé pour dériver un fact inutile.

**Dépendances**

7.10.1.B.

### 7.10.1.D — Policies pures et kernel

**Objectif**

Centraliser la matrice métier et orchestrer séparément requirements courants et facts métier.

**Code concerné**

`domain-pot-policy` : décision, raisons minimales, policies et kernel.

**Modifications attendues**

- introduire `ALLOW | DENY(reason)` avec seulement `MISSING_CAPABILITY`,
  `BUSINESS_POLICY_DENIED`, `CONFIGURATION_ERROR` ;
- implémenter la vérification `containsAll` de toutes les required capabilities ;
- implémenter la matrice métier canonique dans une seule business policy ;
- valider target type, état existing/prospective et présence du requirement de base avant les
  policies ;
- retourner `CONFIGURATION_ERROR` pour toute incohérence contractuelle ;
- n'introduire aucun état opérationnel ni mapping HTTP.

**Tests**

- matrice métier complète, y compris les prédicats disjonctifs ;
- capability de base absente : `MISSING_CAPABILITY` ;
- requirement supplémentaire absent du token : `MISSING_CAPABILITY` ;
- token contenant toutes les capabilities, y compris la supplémentaire : passage à la business
  policy ;
- même ensemble requis issu de deux contextes différents : même décision ;
- `UPDATE_SHAREHOLDER_DETAILS` sur cible prospective : `CONFIGURATION_ERROR` ;
- Expense existante consultée ou modifiée avec cible prospective : `CONFIGURATION_ERROR` ;
- `CREATE_EXPENSE` prospective avec facts Pot suffisants : `ALLOW` ;
- `ADD_SHAREHOLDER` prospectif avec `IS_POT_CREATOR` : `ALLOW` ;
- absence de `CURRENT`, `EXACT`, businessVersion, projection ou provider de sécurité dans les API.

**Critère de sortie**

Le kernel est pur, fail-closed et exige chaque capability de l'ensemble fourni sans en connaître
l'origine. La décision métier n'impose aucune raison élémentaire arbitraire aux rules disjonctives.

**Dépendances**

7.10.1.A à C.

### 7.10.1.E — Façades compatibles et parité

**Objectif**

Faire converger les anciennes policies vers les composants partagés avant de modifier leurs
appelants, sans big-bang et sans dupliquer la matrice.

**Code concerné**

Les policies existantes de `domain-pot-policy`, leurs tests, `QueryUseCaseFactory` et les tests des
services query.

**Modifications attendues**

- convertir les anciennes policies en façades déléguant au resolver, aux policies et au kernel ;
- traduire les décisions vers les `BusinessRuleViolationException` et codes historiques attendus aux
  frontières existantes ;
- conserver `ReadPotAuthorizationPolicy`, `ReadExpenseAuthorizationPolicy` et
  `ReadBalanceAuthorizationPolicy` comme façades jusqu'à 7.10.4 ;
- faire utiliser `TokenCapabilityPolicy` par les méthodes de liste qui n'effectuent pas de décision
  Pot-specific ;
- conserver `CreatePotAuthorizationPolicy` hors kernel ;
- ne laisser aucune matrice métier dans les façades.

**Tests**

- réexécuter les tests historiques des policies contre les façades ;
- table de parité ancien/nouveau pour chaque action existante ;
- conservation des codes de rejet observables ;
- absence de divergence entre les façades read et la business policy centrale.

**Critère de sortie**

Les anciens points d'entrée restent compatibles, mais une seule classe décide les droits métier Pot.

**Dépendances**

7.10.1.D.

### 7.10.1.F — Convergence progressive du write side

**Objectif**

Raccorder les use cases write-side au kernel et fournir les relations courantes réelles, sans changer
la génération des IDs uniquement pour l'autorisation.

**Code concerné**

- services et contextes de `engine-pot-command` ;
- `PotContextPort`, `ExpenseContextPort` ;
- `JpaPotContextAdapter`, `JpaExpenseContextAdapter` ;
- factories/adapters de Command et tests write-side.

**Modifications attendues**

1. Migrer les actions Pot creator-only vers des cibles `ExistingPot`.
2. Enrichir les contextes Expense avec les relations actives nécessaires, puis remplacer les
   `Set.of()` par des `AuthorizationFacts` réellement dérivés.
3. Autoriser `CREATE_EXPENSE` une fois sur `ProspectiveExpense`, avec les facts Pot courants ; après
   `ALLOW`, laisser `ExpenseFactory.createExpense` générer l'ID exactement comme aujourd'hui.
4. Autoriser un batch `ADD_SHAREHOLDER` une seule fois sur `ProspectiveShareholder`, avec les facts
   Pot pré-commande ; après `ALLOW`, laisser `PotShareholders.addShareholder` générer les IDs comme
   aujourd'hui.
5. Pour `UpdatePotShareholdersDetailsService`, construire une cible `ExistingShareholder` par ID
   demandé et dériver `IS_TARGET_SHAREHOLDER` depuis les relations pré-mutation.
6. Pour toute commande multi-objets existing dont la policy dépend de la cible, évaluer toutes les
   décisions depuis le même snapshot de relations et avant la première mutation. Un seul refus rend
   le batch entier rejeté.
7. Préserver les codes métier de rejet via un guard/application adapter ; ne pas mapper les décisions
   vers HTTP dans ce lot.

À ne pas faire :

- ne pas créer `ExpenseIdGenerator` ou `ShareholderIdGenerator` ;
- ne pas modifier `ExpenseFactory.createExpense` pour recevoir un ID uniquement à cause du kernel ;
- ne pas modifier `PotShareholders.addShareholder` pour recevoir un ID uniquement à cause du kernel ;
- ne pas ajouter d'ID à `CreateExpenseCommand` ou `AddPotShareholdersCommand` ;
- ne pas autoriser chaque futur Shareholder individuellement lorsque la rule d'ajout ne consulte pas
  son identité.

**Tests**

- membre autorisé pour les actions Expense conformément à la policy canonique ;
- création Expense prospective autorisée avant que l'ID soit généré ;
- ajout batch Shareholder : une décision avant toute mutation, puis création atomique ;
- refus de l'ajout : aucun appel de mutation ;
- self-service Shareholder existant autorisé sur sa propre cible ;
- batch existing mixte : toutes les décisions utilisent le même état pré-mutation et un refus
  empêche toutes les mutations ;
- régression des générations d'`ExpenseId` et `ShareholderId` actuelles ;
- aucun test n'exige un ID préalloué pour les actions de création.

**Critère de sortie**

Les use cases write-side utilisent les nouveaux contrats, les droits membre/self sont effectivement
raccordés et aucune modification de génération d'identité n'a été introduite uniquement pour donner
une forme uniforme à `AuthorizationTarget`.

**Dépendances**

7.10.1.E.

### 7.10.1.G — Wiring, nettoyage et garde-fous

**Objectif**

Fermer l'ownership, supprimer les duplications devenues inutiles et vérifier les frontières de
modules.

**Code concerné**

`PotBusinessUseCaseFactory`, `PotCommandBindingConfiguration`, `QueryUseCaseFactory`,
`QueryUseCaseConfiguration`, tests d'architecture et anciennes policies.

**Modifications attendues**

- câbler les composants purs partagés ;
- supprimer les anciennes policies write-side après migration de tous leurs appelants ;
- conserver temporairement les trois façades read jusqu'à 7.10.4 ;
- conserver `CreatePotAuthorizationPolicy` ;
- interdire par tests d'architecture toute dépendance du kernel à Spring, JPA, HTTP, sécurité
  provider-specific, query versioning ou pipelines ;
- rechercher et éliminer les checks directs de capabilities ou la matrice métier dupliquée dans les
  services Pot-scoped migrés ;
- vérifier l'absence de générateur ou de préallocation d'ID ajouté par 7.10.1.

**Tests**

- factories et configurations Spring ;
- règles de dépendances modules/packages ;
- compilation des runtimes command et monolith ;
- suite de régression globale.

**Critère de sortie**

Le repository satisfait tous les critères de fin, compile intégralement et ne conserve aucune
duplication active des règles métier.

**Dépendances**

7.10.1.F.

## 6. Migration et suppression des policies existantes

La convergence suit quatre états successifs :

1. les nouveaux contrats et policies existent sans modifier les use cases ;
2. les anciennes policies deviennent des façades sans logique métier propre ;
3. les services write-side migrent famille par famille vers le kernel ;
4. les façades write devenues sans appelant sont supprimées.

À la fin de 7.10.1 :

- les neuf policies write Pot-scoped spécialisées peuvent être supprimées après migration de leurs
  appelants ;
- `CreatePotAuthorizationPolicy` reste en place ;
- `ReadPotAuthorizationPolicy`, `ReadExpenseAuthorizationPolicy` et
  `ReadBalanceAuthorizationPolicy` restent comme adaptateurs temporaires pour les queries actuelles ;
- aucune façade conservée ne doit réimplémenter la matrice métier.

Le passage d'une décision pure à un code de `BusinessRuleViolationException` est une adaptation du
write side. Il ne réintroduit pas de raison métier fine dans `AuthorizationDecision` et ne définit
aucun comportement HTTP.

## 7. Stratégie de tests

### 7.1 Mapping et cible

- une assertion par `PotAction` sur target type, capability et target contract ;
- assertion globale que le nombre d'entrées égale `PotAction.values().length` ;
- tests existing/prospective et combinaisons incohérentes ;
- tests fail-closed pour mapping absent simulé, type incorrect et requirement de base omis.

### 7.2 `TokenCapabilityPolicy`

- toutes les required capabilities présentes : succès ;
- capability de base absente : `MISSING_CAPABILITY` ;
- capability additionnelle absente : `MISSING_CAPABILITY` ;
- capability additionnelle présente : succès ;
- capabilities token non requises : sans effet ;
- ensemble requis identique mais construit par deux orchestrations : décision identique.

### 7.3 `PotBusinessAuthorizationPolicy`

- matrice complète des quatorze actions ;
- créateur, membre, cible Shareholder et utilisateur sans facts ;
- règles disjonctives testées comme expressions globales et refusées avec
  `BUSINESS_POLICY_DENIED` ;
- aucune dépendance à une capability, un token, une version ou un artifact.

### 7.4 `AuthorizationKernel`

- priorité du fail-closed de configuration ;
- refus capability avant business policy ;
- passage à la business policy seulement si toutes les capabilities requises sont présentes ;
- `CREATE_EXPENSE` et `ADD_SHAREHOLDER` sur cibles prospectives ;
- actions existing-only refusées sur cibles prospectives ;
- invariance vis-à-vis de l'origine de `VIEW_ARCHIVE` ;
- aucune décision ne contient un état de projection.

### 7.5 Facts et parité write/read logique

Construire deux jeux de relations identiques, l'un nommé current write-side et l'autre historical
read-side, sans créer d'artifact persistant. Vérifier qu'ils produisent les mêmes facts et les mêmes
décisions pour le même utilisateur, la même cible et la même action.

### 7.6 Régressions use cases

- conserver tous les scénarios historiques d'autorisation ;
- ajouter les droits membre Expense et self Shareholder aujourd'hui non raccordés ;
- vérifier la décision unique d'un batch `ADD_SHAREHOLDER` ;
- vérifier l'évaluation préalable complète des batches existing target-specific ;
- vérifier que la génération d'IDs continue après `ALLOW` dans les composants domaine actuels ;
- exécuter les tests unitaires des contexts, services, adapters de Command et adapters JPA.

Après chaque sous-lot, exécuter les tests Maven des modules modifiés avec leurs dépendances `-am`. La
sortie 7.10.1 exige ensuite le build complet du reactor et les tests d'architecture.

## 8. Risques et mesures de maîtrise

| Risque | Mesure prévue |
|---|---|
| Deux modèles normatifs de cible | Aligner le document canonique avant 7.10.1.B. |
| Duplication de business policy dans les façades | Tests de parité puis recherche statique avant suppression. |
| Dépendance circulaire entre modules | Types génériques dans `domain-authorization`, types Pot dans `domain-pot-policy`. |
| Confusion fact/capability | Wrappers distincts et policies séparées. |
| Une required capability supplémentaire traitée comme facultative | `containsAll` testé explicitement dans la token policy et le kernel. |
| Raison de refus arbitraire pour une rule disjonctive | Une raison principale unique `BUSINESS_POLICY_DENIED`. |
| Cible prospective acceptée pour une action target-specific | Contrat `EXISTING_ONLY` validé avant la business policy. |
| Mutation partielle d'un batch existing | Toutes les décisions sur le même état pré-mutation avant tout changement. |
| Autorisation répétée inutilement pour chaque création d'un batch | Une seule décision `ADD_SHAREHOLDER` prospective. |
| Modification artificielle de la génération d'IDs | Interdiction explicite dans 7.10.1.F et test de régression. |
| Réutilisation abusive d'un type write-side par le futur read-side | Partager relations/facts/policy, jamais contexts ou entités JPA. |
| Généralisation RBAC ou facts dynamiques | Catalogues fermés et aucune map de propriétés. |
| Changement involontaire des règles métier | Matrice exhaustive et parité avec les tests historiques. |

## 9. Hors périmètre

7.10.1 n'implémente pas :

- pipeline ou scheduling AUTH ;
- artifact `AUTH(V)` persistant ;
- table, repository, projector ou layout AUTH ;
- reconstruction historique AUTH ;
- lifecycle ou sélection de pipelineVersion AUTH ;
- intégration complète du Query Kernel ;
- résolution `CURRENT` ou `EXACT(V)` ;
- ajout effectif de `VIEW_ARCHIVE` par l'orchestration historique ;
- ordre de masking ou protection contre les fuites d'existence ;
- mapping HTTP des refus ;
- état de Task, Slot, Claim, projection, readiness ou convergence ;
- préallocation d'`ExpenseId`, de `ShareholderId` ou de `PotId` ;
- évolution des Commands ou du résultat durable pour exposer un ID prospectif.

## 10. Critères de fin de 7.10.1

1. Un seul catalogue canonique de `PotAction` existe.
2. Un mapping central et exhaustif lie chaque action à son target type, sa capability de base et son
   contrat existing/prospective.
3. `TokenCapabilities` et `RequiredCurrentCapabilities` sont structurellement distincts.
4. Toutes les capabilities requises transmises au kernel sont obligatoires.
5. `TokenCapabilityPolicy` est pure et provider-neutral.
6. `PotBusinessAuthorizationPolicy` est pure, unique et partagée write/read.
7. `AuthorizationKernel` est sans I/O et fail-closed.
8. Aucun composant du kernel ne connaît `CURRENT`, `EXACT`, Keycloak, JWT ou HTTP.
9. Une cible prospective n'est admise que pour une action dont le contrat l'autorise.
10. Une identité est exigée pour toute action ou dérivation de fact qui en dépend.
11. `CREATE_EXPENSE` et `ADD_SHAREHOLDER` sont autorisables sans ID préalloué.
12. Le batch `ADD_SHAREHOLDER` effectue une seule décision prospective avant les mutations.
13. Les batches existing target-specific évaluent toutes les décisions sur le même état
    pré-mutation.
14. `AuthorizationDecision` utilise les raisons minimales `MISSING_CAPABILITY`,
    `BUSINESS_POLICY_DENIED`, `CONFIGURATION_ERROR`.
15. Aucun droit métier n'est dérivé d'une capability seule.
16. Les règles write-side canoniques sont effectivement préservées, y compris membre Expense et
    self Shareholder.
17. Une future source read-side `AUTH(V)` peut alimenter le même resolver et la même business policy.
18. Aucun artifact AUTH, générateur d'ID ou changement de Command de création n'est introduit.

## 11. Clôture documentaire

Le préalable documentaire a été résolu dans `authorization-kernel-contracts.md` avant l'exécution.
Les sous-lots A à G sont implémentés sans décision d'architecture résiduelle pour 7.10.1. Les façades
query conservées restent volontairement temporaires jusqu'à l'intégration complète du Query Kernel
prévue en 7.10.4.
