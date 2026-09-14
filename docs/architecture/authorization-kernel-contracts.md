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
- les faits métier Pot nécessaires à l'autorisation et leur enveloppe versionnée ;
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
   + business authorization facts
   + PotAction
   -> AuthorizationDecision
   ```

2. Le système reste un kernel d'autorisation du domaine Pot et de ses sous-objets. Il ne devient pas
   un moteur RBAC générique.
3. Les capacités du token sont courantes et provider-neutral. Elles ne sont jamais historisées par
   businessVersion et ne sont jamais persistées dans `AUTH(V)`.
4. `AUTH(V)` historise des faits métier nécessaires à l'autorisation, jamais le résultat d'une
   policy d'autorisation.
5. Aucun scope, rôle IAM, capability, droit dérivé ou résultat de policy n'appartient à `AUTH(V)`.
6. La policy métier Pot est pure, unique et partagée entre write side et read side.
7. Une action est une intention métier stable. Elle n'encode ni endpoint, ni scope, ni mécanisme
   technique, ni historicité.
8. L'historicité est une propriété de la requête et des capacités courantes requises, pas une
   variante de l'action métier.
9. Une action sans mapping explicite vers ses capacités requises est refusée par défaut et produit
   un diagnostic interne de configuration.
10. `AuthorizationDecision` exprime uniquement le résultat de la policy d'autorisation sur des
    entrées déjà disponibles. Elle ne transporte aucun état de projection ou de convergence.

## 4. Modèle conceptuel

Les responsabilités sont séparées :

```text
adapter de sécurité
  token / autorités externes -> TokenCapabilities courantes

source de faits
  write side courant         -> PotAuthorizationFacts
  read side AUTH(V)          -> PotAuthorizationAtVersion -> PotAuthorizationFacts

AuthorizationKernel
  TokenCapabilityPolicy(current capabilities, required capabilities)
  + PotBusinessAuthorizationPolicy(facts, action)
  -> AuthorizationDecision
```

La résolution, le chargement et la disponibilité des entrées précèdent la décision. Le kernel ne
charge ni token, ni Pot, ni artifact et ne résout aucune version.

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

## 6. `PotAuthorizationAtVersion`

`PotAuthorizationAtVersion` désigne les faits métier historiques nécessaires pour un utilisateur,
un Pot et une businessVersion exacts :

```text
PotAuthorizationAtVersion
  potId
  businessVersion
  userId
  isMember
  isCreator
```

`potId`, `businessVersion` et `userId` identifient les faits et permettent de vérifier que les bons
faits ont été chargés. Ils ne participent pas à la règle une fois `PotAuthorizationFacts` construit.

Le contrat ne contient jamais :

- scopes, capacités ou rôles IAM ;
- `canView`, `canUpdate`, `canDelete` ou tout autre droit calculé ;
- résultat ou version d'une policy ;
- état de readiness, failure, pipeline ou convergence.

Conformément à la reconstruction historique canonique, `isCreator` est dérivable du `creator_id` du
Pot à V et `isMember` d'un Shareholder applicable à V, non supprimé et lié au `userId`. La production
et la persistence de cette enveloppe appartiennent aux lots suivants.

> **Invariant :** `AUTH(V)` historise des faits métier nécessaires à l'autorisation, jamais le
> résultat d'une policy d'autorisation.

## 7. `PotAuthorizationFacts`

La policy métier partagée dépend d'un contrat minimal non versionné et non spécifique au read side :

```text
PotAuthorizationFacts
  isMember
  isCreator
```

Cet objet est une valeur d'entrée éphémère, pas un artifact supplémentaire. Le write side le
construit depuis l'état métier courant ; le read side l'extrait d'un `PotAuthorizationAtVersion` déjà
résolu. Les deux chemins doivent produire les mêmes faits pour une situation métier équivalente.

L'identité de l'utilisateur, du Pot et de la version reste à la frontière de résolution. Elle ne doit
pas contaminer une règle qui ne dépend, après résolution, que de `isMember`, `isCreator` et de
l'action.

## 8. `PotAction`

`PotAction` est un ensemble fermé d'intentions métier stables et Pot-scoped. Les noms précis doivent
rester cohérents avec les use cases métier spécialisés existants. Le noyau initial attendu couvre :

```text
VIEW_POT
UPDATE_POT
DELETE_POT
VIEW_SHAREHOLDER
ADD_SHAREHOLDER
UPDATE_SHAREHOLDER
REMOVE_SHAREHOLDER
VIEW_EXPENSE
CREATE_EXPENSE
UPDATE_EXPENSE
DELETE_EXPENSE
VIEW_BALANCE
```

Une implémentation peut retenir un nom plus précis déjà porté par un use case, par exemple
`UPDATE_POT_DETAILS`, `UPDATE_SHAREHOLDER_DETAILS`, `UPDATE_SHAREHOLDER_WEIGHTS`,
`UPDATE_EXPENSE_DETAILS` ou `UPDATE_EXPENSE_SHARES`, à condition que chaque valeur reste une
intention métier et possède un mapping explicite.

`CREATE_POT`, qui ne dispose pas encore de faits pour un Pot, relève de la seule capability courante
et doit rester une intention distincte si le même kernel l'orchestre. Il ne justifie ni faits Pot
fictifs ni généralisation RBAC.

Sont interdits : `VIEW_POT_ARCHIVE`, `VIEW_BALANCE_ARCHIVE`, noms d'endpoints, noms de scopes,
opérations SQL/JPA et variantes `CURRENT`/`EXACT`.

> **Invariant :** l'historicité est une propriété de la requête et des capacités courantes requises,
> pas une variante de l'action métier.

## 9. `TokenCapabilityPolicy`

`TokenCapabilityPolicy` vérifie que toutes les capacités courantes explicitement requises sont
présentes dans `TokenCapabilities`. Elle :

- ne consulte aucun fait métier ;
- ne charge et ne parse aucun token ;
- ne connaît aucun provider IAM ;
- ne déduit jamais qu'une capacité absente est implicitement accordée ;
- retourne une décision exploitable par le kernel, notamment `MISSING_CAPABILITY`.

Le mapping canonique initial est :

| `PotAction` | Capability courante requise |
|---|---|
| `VIEW_POT` | `POT_VIEW` |
| `UPDATE_POT` | `POT_UPDATE` |
| `DELETE_POT` | `POT_DELETE` |
| `VIEW_SHAREHOLDER` | `SHAREHOLDER_VIEW` |
| `ADD_SHAREHOLDER` | `SHAREHOLDER_CREATE` |
| `UPDATE_SHAREHOLDER` | `SHAREHOLDER_UPDATE` |
| `REMOVE_SHAREHOLDER` | `SHAREHOLDER_DELETE` |
| `VIEW_EXPENSE` | `EXPENSE_VIEW` |
| `CREATE_EXPENSE` | `EXPENSE_CREATE` |
| `UPDATE_EXPENSE` | `EXPENSE_UPDATE` |
| `DELETE_EXPENSE` | `EXPENSE_DELETE` |
| `VIEW_BALANCE` | `BALANCE_VIEW` |

Les variantes plus précises autorisées à la section 8 héritent explicitement de la capability de leur
famille. Le mapping appartient au kernel/policy d'autorisation ; il n'est jamais dispersé dans les
controllers, handlers ou endpoints.

Pour `EXACT(V)`, l'orchestration ajoute `VIEW_ARCHIVE` aux capacités requises par l'action. Cette
précondition transversale n'altère ni `PotAction` ni la policy métier.

## 10. `PotBusinessAuthorizationPolicy`

`PotBusinessAuthorizationPolicy` centralise la règle pure :

```text
PotAuthorizationFacts + PotAction -> business AuthorizationDecision
```

Elle ne lit aucune base ni aucun token ; elle ne connaît ni HTTP, ni Keycloak, ni `CURRENT`, ni
`EXACT`, ni projection `AUTH`, ni readiness, ni convergence. Elle ne reçoit aucune capability.

La matrice métier de base reprend les règles déjà exprimées par les policies Pot actuelles :

- lecture du Pot, de ses Shareholders, de ses Expenses ou de sa Balance : créateur ou membre ;
- mise à jour ou suppression du Pot : créateur ;
- ajout, retrait et mutations structurelles de Shareholders : créateur ;
- création, mise à jour ou suppression d'Expense : créateur ou membre.

Une intention plus fine dont la règle diffère doit avoir sa propre valeur `PotAction` explicite. Par
exemple, la règle actuelle « créateur ou Shareholder concerné » pour la modification des détails d'un
Shareholder ne doit pas être élargie silencieusement à tous les membres : elle exige une intention
distincte et, si nécessaire, un fait minimal explicitement cadré par une évolution de ce contrat.

Le write side et le read side réutilisent cette même policy. Les classes actuelles
`ReadPotAuthorizationPolicy`, `UpdatePotDetailsAuthorizationPolicy`, etc. expriment le vocabulaire et
les règles à préserver, mais leur mélange actuel entre `Permission` et faits Pot n'est pas le contrat
cible.

## 11. `AuthorizationKernel`

`AuthorizationKernel` orchestre, sans I/O :

1. la recherche fail-closed des capacités exigées par `PotAction` ;
2. l'ajout de `VIEW_ARCHIVE` si l'orchestrateur appelant indique une lecture historique ;
3. l'évaluation par `TokenCapabilityPolicy` ;
4. seulement si elle réussit, l'évaluation par `PotBusinessAuthorizationPolicy` ;
5. la production d'une unique `AuthorizationDecision`.

Le kernel ne résout pas `CURRENT` ou `EXACT(V)` et ne charge pas `AUTH(V)`. Il reçoit des faits déjà
disponibles et un contexte d'exigences courantes déjà déterminé par l'orchestration applicative. La
mention de `VIEW_ARCHIVE` ci-dessus désigne l'assemblage des préconditions, pas une connaissance de
la résolution versionnée dans la policy métier.

Les capacités et les faits demeurent deux entrées distinctes. Aucun modèle persistant ou objet opaque
ne doit les fusionner.

## 12. `AuthorizationDecision`

La décision n'est pas un simple booléen. Elle distingue au minimum :

```text
ALLOW
DENY(reason)
```

Les raisons internes peuvent notamment distinguer :

```text
MISSING_CAPABILITY
NOT_MEMBER
NOT_CREATOR
BUSINESS_POLICY_DENIED
CONFIGURATION_ERROR
```

Une raison sert aux tests, au diagnostic et éventuellement à l'observabilité. Elle ne définit jamais
directement le statut ou le corps HTTP public.

Sont interdits dans `AuthorizationDecision` : `NOT_READY`, `PROJECTION_FAILED`, `AUTH_FAILED`, états
de Task/Claim, lifecycle de pipeline et états de convergence.

> **Invariant :** `AuthorizationDecision` exprime uniquement le résultat de la policy
> d'autorisation sur des entrées déjà disponibles.

## 13. `CURRENT` versus `EXACT(V)`

La policy métier ne reçoit aucune information `CURRENT` ou `EXACT(V)`.

Pour une lecture courante protégée :

```text
capability courante requise par PotAction
+ droits métier à servedVersion
```

Pour une lecture historique protégée :

```text
capability courante requise par PotAction
+ capability courante VIEW_ARCHIVE
+ droits métier à V
```

`servedVersion` est déterminée par le Query Version Resolver, conformément à
`read-side-target.md`. Le read side demande ensuite les faits AUTH à cette version exacte. Ni le
kernel ni la policy métier ne choisissent une businessVersion ou une pipelineVersion, et aucun refus
ne déclenche de fallback.

`VIEW_ARCHIVE` n'est jamais reconstruit depuis l'historique, lu depuis `AUTH(V)` ou persisté comme un
fait historique. Une évolution des capacités courantes peut donc modifier l'accès à une ancienne
businessVersion sans reconstruire `AUTH(V)`.

L'ordre exact sans fuite, le masquage public et le mapping HTTP restent gouvernés par 7.9.3 et 7.10.4.

## 14. Fail-closed et erreurs de configuration

Le catalogue `PotAction -> capabilities requises` est total pour toute action déployée :

```text
known PotAction
+ no explicit capability mapping
-> DENY(CONFIGURATION_ERROR)
-> diagnostic interne
```

Une absence de mapping ne signifie jamais « aucune capability requise ». Le diagnostic de
configuration doit être distinguable en interne d'un refus métier normal et testable comme tel. Sa
traduction externe peut rester indistinguable d'un refus ordinaire ; ce choix relève de 7.9.3.

Les entrées invalides ou incohérentes sont également refusées ou rejetées comme erreurs de contrat ;
elles ne produisent jamais un `ALLOW` par défaut.

## 15. Partage write/read

Le partage porte sur la règle métier, pas sur les mécanismes d'acquisition des données :

```text
write side current Pot state -> PotAuthorizationFacts --+
                                                       +-> PotBusinessAuthorizationPolicy
read side AUTH(V) -> PotAuthorizationAtVersion -> facts+
```

À faits et action identiques, les deux chemins doivent produire la même décision métier. Le write
side peut conserver ses contraintes propres d'admission et de Command, mais il ne duplique pas la
matrice `isMember/isCreator -> actions`. Le read side ne dépend pas d'un objet write-side ni l'inverse.

## 16. Ce qui est explicitement hors périmètre

N'appartiennent pas au Lot 7.10.1 :

- déclaration et scheduling du pipeline AUTH : 7.10.2 ;
- production d'un artifact `AUTH(V)`, reconstruction exacte de `isMember/isCreator` à V et
  persistence des artifacts : 7.10.3 ;
- lifecycle, éligibilité et sélection de la `pipelineVersion` serving d'AUTH : 7.10.4 avec 7.14 ;
- intégration complète du Query Kernel et décision d'accès à `servedVersion` : 7.10.4 ;
- ordre exact de masquage HTTP et mapping final des refus en codes HTTP : 7.9.3 ;
- schémas SQL, serialization, packages Java, wiring, migration et séquencement détaillé.

## 17. Conséquences attendues pour l'implémentation future

Une implémentation conforme devra :

- introduire des contrats typés sans dépendance framework ;
- fermer l'ownership des types AUTH encore ouvert dans `type-ownership.md` ;
- isoler la traduction des autorités externes dans les adapters de sécurité ;
- séparer des policies actuelles la vérification des capacités et la règle métier Pot ;
- remplacer toute duplication write/read de la matrice métier par la policy pure partagée ;
- rendre le mapping de chaque `PotAction` explicite, central et exhaustivement testé ;
- empêcher structurellement toute persistence de capacités ou de droits dérivés dans AUTH ;
- conserver les états opérationnels hors de `AuthorizationDecision`.

Ces conséquences sont des critères architecturaux, pas un découpage de commits ou de classes.

## 18. Cas de test canoniques

1. Token sans capability requise et utilisateur membre : `DENY(MISSING_CAPABILITY)`.
2. Token avec capability requise et utilisateur ni membre ni autorisé par sa qualité de créateur :
   `DENY` métier.
3. Token avec capability requise et faits métier autorisés : `ALLOW`.
4. `EXACT(V)` sans `VIEW_ARCHIVE` : `DENY(MISSING_CAPABILITY)` avant toute autorisation historique
   effective.
5. `EXACT(V)` avec `VIEW_ARCHIVE`, mais faits métier à V insuffisants : `DENY` métier.
6. `EXACT(V)` avec `VIEW_ARCHIVE` et faits métier à V autorisés : `ALLOW`.
7. Les capabilities courantes ne sont jamais lues depuis `AUTH(V)`.
8. Une modification des capabilities courantes peut modifier l'accès à une ancienne businessVersion
   sans reconstruire `AUTH(V)`.
9. Une nouvelle `PotAction` sans mapping explicite : fail-closed avec diagnostic
   `CONFIGURATION_ERROR`.
10. La même instance ou implémentation de `PotBusinessAuthorizationPolicy` accepte des faits issus du
    write side courant ou d'un artifact AUTH read-side.
11. La policy métier ne reçoit aucune information `CURRENT` ou `EXACT`.
12. `VIEW_ARCHIVE` n'est jamais persisté comme fait historique.
13. Aucun droit dérivé (`canView`, `canUpdate`, etc.) n'est persisté dans AUTH.
14. `AuthorizationDecision` ne contient aucun état opérationnel de projection.
15. À faits et action identiques, les chemins write et read produisent la même décision métier.
16. Toute variante d'action plus fine possède son mapping explicite et ne peut élargir silencieusement
    une règle métier existante.
