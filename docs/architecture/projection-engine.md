# Architecture canonique du moteur générique de projections

## 1. Statut et portée

Ce document est la référence normative pour la préparation générique des projections canoniques.
Il affine la partie « préparation hors transaction » de
[`projection-task-execution.md`](projection-task-execution.md), sans modifier les garanties de
Consumption ni celles du store définies dans
[`read-side-target.md`](read-side-target.md).

Le Lot 5 a livré deux producteurs concrets, `READ_POT` et `POT_BALANCES`. Leur implémentation montre
qu'ils diffèrent par leurs données d'entrée et leur calcul, mais partagent le même algorithme de
préparation. Cette convergence justifie désormais un moteur unique. `AUTH` sera le prochain cas
concret utilisé pour vérifier l'extensibilité de cette architecture ; il n'est ni conçu ni
implémenté par le présent document.

Ce document décrit une cible et ses invariants. Il ne constitue pas un plan de migration et ne fixe
pas les signatures Java, le découpage en commits ou le wiring Spring.

## 2. Motivation et état actuel

Le code actuel contient deux préparateurs spécialisés :

- `PrepareReadPotProjection` reçoit un `ReadPotProjectionInputLoader`, un `ReadPotProjector` et un
  `ProjectionValidator` ;
- `PreparePotBalancesProjection` reçoit un calculateur historique, un `PotBalancesProjector` et un
  `ProjectionValidator`.

Tous deux répètent le même enchaînement : charger une version exacte, calculer une `Projection`,
vérifier sa clé, appliquer la définition, puis traduire les issues explicitement temporaires ou
terminales. Tous deux transforment également les autres incohérences en violation interne.

Le runtime assemble aujourd'hui un seul `ProjectionTaskPreparation` au moyen d'un `switch` sur une
propriété `projection-type`. Le locator est lui-même filtré sur ce type. Cette configuration permet
un déploiement spécialisé, mais fait du choix opérationnel du worker le mécanisme fonctionnel de
résolution du producteur. Ajouter un type exige donc de modifier le runtime et de créer un nouveau
préparateur répétant l'algorithme invariant.

`ExecuteProjectionTaskService` orchestre actuellement cette préparation avec un Claim, le retry et
la finalisation durable. Il reste utile comme intégration Consumption, mais il ne constitue pas la
façade cible de production : le futur moteur doit pouvoir préparer une Task sans connaître le
Claim, tandis que l'intégration Consumption interprète ensuite son outcome sous fencing.

La cible supprime cette duplication fonctionnelle : le runtime assemble un moteur capable de
connaître plusieurs producteurs, et un seul use case public prépare toute `ProjectionTask`.

## 3. Décision architecturale

Il existe un seul Projection Engine applicatif et une seule façade publique de préparation d'une
`ProjectionTask` :

```text
ProjectionTask
      │
      ▼
Projection Engine
      │
      ▼
catalogue des producteurs
   ┌──┼──────────┐
   ▼  ▼          ▼
READ_POT  POT_BALANCES  AUTH
   │          │          │
loader     loader      loader
   │          │          │
projector  projector   projector
   └──────────┼──────────┘
              ▼
          Projection
              │
      contrôles invariants
              │
     ProjectionValidator
              │
              ▼
     ValidatedProjection
```

Conceptuellement, la façade offre une opération équivalente à :

```text
execute(ProjectionTask) -> ProjectionPreparationOutcome
```

Le nom Java reste ouvert. Quelle que soit sa dénomination, cette opération signifie « préparer la
projection demandée » et non « committer son effet durable ». Elle ne reçoit ni Claim ni
`ConsumptionSlot`, et elle ne publie jamais la projection.

L'intégration générique avec Consumption peut conserver une opération d'orchestration distincte,
mais celle-ci ne devient ni un second moteur de projection ni un point d'extension par type. Elle
appelle toujours la même façade, puis applique uniformément retry ou finalisation.

## 4. Responsabilités du Projection Engine

Le moteur possède exclusivement le comportement invariant de préparation :

1. recevoir une `ProjectionTask` non nulle et en extraire la `ProjectionKey` ;
2. résoudre dans le catalogue l'unique producteur configuré pour son `ProjectionType` ;
3. vérifier que la déclaration du producteur accepte le `TargetObjectType` demandé ;
4. appeler le loader de la déclaration pour charger l'input exact ;
5. transmettre explicitement cet input et la clé demandée au projector de la même déclaration ;
6. recevoir la `Projection` candidate calculée par ce projector ;
7. vérifier que la clé de la candidate est exactement la clé demandée, y compris l'identifiant et
   la version ;
8. valider la candidate avec la `ProjectionDefinition` portée par cette même déclaration ;
9. retourner `Prepared(ValidatedProjection)` ou l'issue temporaire ou terminale explicitement
   produite ;
10. laisser remonter séparément toute erreur interne, d'invariant ou de configuration.

La séquence `resolve → load → project → key check → validate` appartient au moteur. Le moteur est
l'unique propriétaire applicatif de son ordre, de la vérification de clé et du passage par
`ProjectionValidator`. Ces contrôles ne sont pas optionnels et ne varient pas par type. Il ne
délègue donc pas à une opération opaque `producer.produce(key)` la responsabilité de rejouer cette
orchestration dans chaque producteur.

Le moteur ne possède pas :

- la création ou la persistence des `ProjectionTask` ;
- la discovery, l'acquisition, le lease ou le fencing ;
- la politique opérationnelle de retry et de backoff ;
- `ProjectionWritePort` ni l'écriture d'un `ProjectionFailure` ;
- la finalisation du Claim ou du slot ;
- les Events, pipelines, generations ou dépendances entre tâches.

## 5. Responsabilités d'un Projection Producer

Un producteur configuré représente une unité cohérente pour un seul `ProjectionType`. Il associe au
minimum :

- le `ProjectionType` qu'il produit ;
- le `TargetObjectType` accepté ;
- sa `ProjectionDefinition` ;
- le chargement exact des données nécessaires ;
- la transformation pure de ces données en `Projection`.

Il fournit conceptuellement au moteur les deux comportements variables suivants :

```text
load(ProjectionKey) -> Input I
project(ProjectionKey, Input I) -> Projection
```

Le producteur sait **comment** charger et calculer son type. Le Projection Engine sait **dans quel
ordre** invoquer ces comportements et quels invariants génériques contrôler avant de produire une
preuve validée. Un producteur ne réimplémente donc ni la résolution, ni le contrôle final de clé,
ni l'appel au validator, ni la création de `Prepared`.

Le chargement et le calcul restent spécifiques au type. Le loader peut lire une source primaire
historique, une projection exacte déjà publiée ou une combinaison des deux. Il ne remplace jamais
une dépendance demandée par une valeur `CURRENT`, une autre version ou la dernière valeur connue.

Le projector transforme conceptuellement :

```text
ProjectionKey + Input I -> Projection
```

Il ne publie rien, ne connaît pas Consumption et ne classe pas un résultat de publication. Il peut
vérifier les relations propres à son input lorsqu'elles sont nécessaires pour calculer une valeur
cohérente, mais les vérifications génériques de clé finale et de schéma appartiennent au moteur.

Les conditions temporaires ou terminales doivent être explicitement reconnues à la frontière du
producteur. Une exception quelconque ne devient pas implicitement temporaire ou terminale.

## 6. Cohérence d'une déclaration de producteur

La configuration d'un type constitue une seule déclaration atomique. Trois registries indépendants
de définitions, loaders et projectors sont interdits, car ils permettraient un assemblage tel que :

```text
READ_POT
    + ReadPotLoader
    + PotBalancesDefinition
```

Une déclaration doit garantir ou faire vérifier au démarrage :

- que sa clé de catalogue est le `ProjectionType` de sa définition ;
- que son `TargetObjectType` est celui de sa définition ;
- que loader et projector partagent un input compatible ;
- qu'aucun second producteur n'est enregistré pour le même `ProjectionType` ;
- qu'aucun élément obligatoire de la déclaration n'est absent.

Le couple générique `Loader<I>` / `Projector<I>` est une forme indicative naturelle : il exprime
simplement que la sortie du loader est l'entrée du projector. Java ne permet toutefois pas de
conserver directement des instances paramétrées par différents `I` dans une collection hétérogène
sans frontière effacée, capture de wildcard ou encapsulation supplémentaire. L'implémentation peut
donc employer une déclaration générique à la construction puis exposer au catalogue une opération
non générique sûre.

La forme exacte de cette capture de type n'est pas canonique. Elle doit rester locale à la
composition et ne doit ni exposer de casts au moteur, ni recréer un framework de handlers. Le
critère est la cohérence effective de chaque déclaration, pas l'emploi obligatoire d'une interface
générique particulière. Même si l'effacement de type impose une frontière technique encapsulée,
l'algorithme fonctionnel `load → project → key check → validate` reste implémenté une seule fois par
le moteur ; cette frontière ne doit pas devenir un préparateur complet spécifique à chaque type.

## 7. Catalogue des producteurs

Le catalogue effectue une seule résolution :

```text
ProjectionType -> déclaration de producteur configurée
```

Il est explicite, fini et local à une instance du moteur ou au processus qui la compose. Il décrit
les producteurs disponibles dans ce runtime, pas nécessairement tous les `ProjectionType` connus
de Pocoma. Il est validé au démarrage autant que possible et peut donc légitimement contenir un
seul producteur ou plusieurs.

Il ne constitue ni un pipeline, ni un registry de Tasks, ni un moteur de règles. Il ne connaît pas :

- les Events ou la création des tâches ;
- pipeline, `taskType` ou producer generation ;
- un DAG ou un ordre entre versions ;
- les Claims, workers, segments ou politiques de retry ;
- le store de projections.

L'absence de producteur pour un `ProjectionType`, un doublon de configuration ou une incohérence
entre déclaration et définition est une erreur interne de configuration. Elle ne devient ni
`Temporary`, ni `Terminal`, ni `ProjectionFailure`.

Le moteur reste générique même lorsque son catalogue local ne contient qu'un producteur. En
particulier, `generic engine` ne signifie pas `universal worker`.

## 8. Flux complet d'exécution

Le flux canonique sépare la préparation du commit autoritaire :

```text
ProjectionTask(ProjectionKey)
          │
        LOCATE
          │
        ACQUIRE
          │
   Claim courant installé
          │
          ▼

    HORS TRANSACTION FINALE

   Projection Engine
          │
   resolve producer
          │
   exact load -> typed input
          │
   pure project
          │
      Projection
          │
   exact key check
          │
 ProjectionValidator(definition)
          │
 ValidatedProjection
          │
          ▼

    TRANSACTION COURTE

 lock/fence current Claim
          │
 ProjectionWritePort.publish
   PUBLISHED | ALREADY_EXISTS
          │
      finish Claim
          │
      finish Slot
          │
        COMMIT
```

`Prepared` transporte la `ValidatedProjection` vers l'orchestration générique. Seule cette
orchestration associe le résultat préparé à la finalisation transactionnelle fenced. Le moteur ne
doit jamais déplacer `publish` avant l'établissement de l'autorité du Claim courant.

`PUBLISHED` et `ALREADY_EXISTS` satisfont tous deux la tâche. `ALREADY_EXISTS` ne déclenche aucune
comparaison de payload ou digest.

## 9. Résultats et erreurs

### 9.1 Prepared

Le chargement et le calcul ont produit une candidate de la clé demandée, acceptée par sa définition.
Le résultat transporte exclusivement une `ValidatedProjection` publiable sous fencing.

### 9.2 Temporary

Une condition explicitement temporaire empêche la préparation : dépendance exacte non prête ou
indisponibilité technique reconnue comme transitoire, par exemple. Aucun `ProjectionFailure` n'est
créé et le slot reste réacquérable selon la politique opérationnelle. Le nombre de tentatives ne
change jamais cette classification.

### 9.3 Terminal

Il est explicitement établi que la clé demandée ne peut pas être produite. L'orchestration effectue
alors, dans la transaction courte fenced, `recordFailure`, la clôture du Claim et la terminalisation
du slot.

### 9.4 Internal, invariant ou configuration

Cette famille comprend notamment : producteur absent ou dupliqué, déclaration incohérente,
projection portant une autre clé, schéma canonique invalide, validation révélant une candidate
impossible ou erreur de wiring. Elle n'est transformée ni en failure métier terminale ni en retry
éternel par défaut. Elle remonte au runtime et à l'observabilité comme erreur interne. La politique
ultérieure de quarantaine ou d'intervention opérateur n'est pas décidée ici.

## 10. Application aux producteurs actuels

### 10.1 READ_POT

Restent spécifiques au producteur :

- `ReadPotProjectionDefinition` et les types d'artifacts `POT`, `SHAREHOLDER`, `EXPENSE` ;
- le chargement d'un `ReadPotProjectionInput` exact, incluant la date des dépenses ;
- la conversion des value objects Pot en artifacts JSON ;
- les relations propres à l'input nécessaires à la construction de `READ_POT`.

Doivent devenir génériques :

- l'appel au `ProjectionValidator` ;
- le contrôle exact de la clé retournée ;
- la création de `Prepared` ;
- la traduction des issues explicitement temporaires et terminales ;
- l'enveloppement des incohérences inattendues en erreur interne de préparation.

Le HEAD contient l'interface et le modèle d'input, mais aucun loader runtime de production : la
source historique actuelle ne fournit pas la date canonique exigée pour une dépense. Le moteur ne
doit ni inventer cette donnée ni affaiblir la définition. Cette lacune reste une responsabilité du
producteur et de sa source, pas une variation du moteur générique.

### 10.2 POT_BALANCES

Restent spécifiques au producteur :

- `PotBalancesProjectionDefinition` et ses artifacts `BALANCE` ;
- le chargement/reconstruction historique exacte du Pot à la version demandée ;
- le calcul typé de `PotBalances` ;
- la conversion d'une balance par shareholder en artifact JSON ;
- la règle numérique `numerator ∈ Z` et `denominator > 0`.

Doivent devenir génériques :

- l'appel au validator et le contrôle de clé ;
- la production des outcomes ;
- la frontière entre incohérence interne et failure explicitement classifiée.

Le calculateur historique actuel peut tenir le rôle du chargement typé, même si le futur
refactoring choisit de séparer plus nettement les noms de loader et de calculateur. Le moteur ne
doit pas imposer cette terminologie interne au producteur.

À terme, `PrepareReadPotProjection` et `PreparePotBalancesProjection` ne sont plus des use cases
publics distincts. Leur logique variable est absorbée par les déclarations de producteurs ; leur
algorithme commun appartient à la façade unique.

## 11. AUTH comme prochain test d'extension

Après migration de `READ_POT` et `POT_BALANCES`, ajouter `AUTH` doit normalement nécessiter
seulement :

- sa `ProjectionDefinition` ;
- son chargement exact ;
- son projector ;
- son enregistrement dans le catalogue.

Le use case générique, le locator, l'acquisition, la validation, le writer et la finalisation ne
doivent pas être modifiés. Si l'implémentation réelle d'AUTH révèle une variation légitime absente
des deux premiers cas, l'abstraction sera adaptée à partir de cette preuve concrète ; le présent
document n'anticipe aucune extension spéculative.

## 12. Conséquences sur le runtime

Le runtime compose :

- un catalogue contenant les producteurs disponibles dans cette instance ou ce processus ;
- un moteur générique utilisant ce catalogue et le mécanisme commun `ProjectionValidator` ;
- l'orchestration Consumption générique et le `ProjectionWritePort` canonique.

« Quelles `ProjectionTask` ce worker acquiert-il ? » et « comment une Task acquise est-elle
préparée ? » sont deux questions distinctes :

- la première relève du worker, du locator, de la discovery et de la configuration opérationnelle ;
- la seconde relève exclusivement du Projection Engine générique.

La topologie opérationnelle des workers ne détermine donc pas l'architecture fonctionnelle du
moteur. Le runtime ne construit plus un préparateur fonctionnellement différent par valeur d'une
propriété. Une tâche acquise est toujours transmise à la même façade générique, dont le routing est
fondé exclusivement sur le `ProjectionType` de sa `ProjectionKey`.

### 12.1 Workers spécialisés

Un worker peut être volontairement spécialisé :

```text
Worker READ_POT
    discovery = READ_POT
    catalogue = { READ_POT }

Worker POT_BALANCES
    discovery = POT_BALANCES
    catalogue = { POT_BALANCES }

Worker AUTH
    discovery = AUTH
    catalogue = { AUTH }
```

Cette topologie est autorisée et peut être privilégiée initialement pour isoler capacité, charge ou
déploiement. Les trois workers emploient pourtant exactement le même moteur et le même use case de
préparation. Il n'existe ni `ReadPotProjectionEngine`, ni `PotBalancesProjectionEngine`, ni
`AuthProjectionEngine`.

Le filtre par `ProjectionType` du locator reste une sélection opérationnelle des tâches candidates.
Il ne constitue pas le dispatch fonctionnel : après acquisition, le moteur résout toujours la
déclaration dans son propre catalogue. Le `switch` actuel qui utilise la même propriété pour filtrer
la discovery et choisir un préparateur spécifique doit donc être découplé dans la cible.

### 12.2 Workers multi-projections et scaling

Une autre instance peut contenir plusieurs producteurs sans aucune modification du moteur :

```text
Worker DEV
    discovery = { READ_POT, POT_BALANCES, AUTH }
    catalogue = { READ_POT, POT_BALANCES, AUTH }

Worker LIGHT_PROJECTIONS
    discovery = { READ_POT, AUTH }
    catalogue = { READ_POT, AUTH }
```

L'architecture autorise ainsi les workers mono-projection, les workers multi-projections, plusieurs
workers concurrents pour un même `ProjectionType`, le scaling indépendant par type et le
regroupement de types. Elle ne prescrit aucune de ces topologies concrètes.

Un worker ne doit acquérir que des types présents dans son catalogue ; un écart reste une erreur de
configuration interne, pas un mécanisme normal de délégation à un autre worker.

### 12.3 Validation générique commune

« Validator commun » signifie que toutes les projections passent par le même contrat générique :

```text
ProjectionValidator.validate(ProjectionDefinition, Projection)
```

Il n'existe aucune variante `ReadPotValidator`, `PotBalancesValidator` ou `AuthValidator` dans le
chemin canonique. Cette unicité est fonctionnelle, pas physique : chaque processus ou worker peut
posséder sa propre instance stateless de `ProjectionValidator`. Aucun singleton global au
déploiement n'est requis.

Il n'existe aucun locator métier ni writer fonctionnel distinct par type. Un même locator générique
peut recevoir un filtre opérationnel de `ProjectionType`, et le writer canonique reste commun. Le
catalogue ne participe ni à la discovery ni à la publication.

## 13. Modularisation cible

La responsabilité future des modules suit ces frontières :

- le module applicatif générique du Projection Engine porte la façade unique, le catalogue, les
  contrats minimaux de déclaration/producteur et les outcomes de préparation ;
- `engine-projection-task` est le point d'évolution naturel pour ces responsabilités déjà liées à
  `ProjectionTask`, sous réserve que l'intégration Consumption et la préparation restent
  clairement séparées à l'intérieur du module ou par un découpage minimal justifié lors du plan ;
- les implémentations `READ_POT` et `POT_BALANCES` restent dans leurs modules applicatifs de
  production (`engine-projection-pot` et `engine-projection-balance`) ;
- l'adapter concret de `JsonSchemaValidator` reste en infrastructure ;
- le runtime assemble les producteurs et les adapters sans héberger l'algorithme métier du moteur.

Une `ProjectionDefinition` est le contrat partagé de la projection produite et lue. Elle ne doit
pas appartenir conceptuellement à un read use case uniquement parce que celui-ci la consomme.
Aujourd'hui, `ReadPotProjectionDefinition` et `AuthProjectionDefinition` résident dans
`engine-pot-read`, ce qui force notamment `engine-projection-pot` à dépendre du module de lecture
métier. La direction souhaitable est un ownership neutre, accessible au producteur comme au reader,
sans duplication des constantes ou schemas.

Le présent document ne décide pas si cet ownership prendra la forme d'un module de contrats dédié,
d'un module spécifique à la projection ou d'une extraction plus locale. Cette décision doit être
prise dans le plan à partir du graphe Maven réel, en évitant à la fois la dépendance inversée vers
un read use case et une prolifération de micro-modules. `PotBalancesProjectionDefinition`, déjà
colocalisée avec son producteur, ne justifie pas à elle seule une règle différente : toute
définition appelée à être lue ailleurs doit conserver une source canonique unique.

Les modules de domaine et d'engine restent Java purs. Aucun contrat du moteur ne dépend de Spring,
JDBC/JPA, Jackson, Networknt, PostgreSQL, runtime, pipeline ou legacy.

## 14. Invariants préservés

1. L'identité sémantique d'une `ProjectionTask` est exactement sa `ProjectionKey`.
2. Une seule tâche durable existe par clé, indépendamment des Events ou producers qui la demandent.
3. Le moteur n'impose aucun ordre, continuité ou dépendance à la version précédente.
4. Toute donnée et dépendance est chargée à la version exacte demandée.
5. Le projector n'effectue aucune écriture.
6. Toute candidate porte exactement la clé demandée avant validation.
7. Toute publication reçoit une `ValidatedProjection`.
8. La préparation s'exécute hors de la transaction finale courte.
9. Seul le Claim courant peut établir l'autorité avant l'effet durable.
10. L'expiration du lease seule ne révoque pas ce Claim ; un takeover le fence.
11. Une projection publiée est immutable et `first published wins`.
12. `ALREADY_EXISTS` est un succès sans comparaison de contenu ni digest.
13. Une issue temporaire reste temporaire quel que soit le nombre de tentatives.
14. Seule une issue terminale explicite produit un `ProjectionFailure`.
15. Une erreur interne ou de configuration n'est ni Temporary ni ProjectionFailure.
16. Locator, acquisition, retry, finalisation et writer restent génériques ; la validation emploie
    un contrat commun non spécialisé, sans exiger une instance singleton globale.
17. Le catalogue est local aux producteurs disponibles dans une instance de moteur.
18. Un worker spécialisé et un worker multi-projections utilisent le même moteur fonctionnel.
19. Le filtrage de discovery ne constitue jamais le dispatch fonctionnel du moteur.
20. Plusieurs workers peuvent acquérir concurremment un même `ProjectionType` sous les garanties
    existantes de Claim, fencing et publication immutable.

## 15. Hors scope

Cette évolution ne décide ni ne modifie :

- le mapping Event vers `ProjectionTask` ;
- le redesign des pipelines ou leurs generations ;
- le backlog, le backfill ou le cutover legacy ;
- la résolution `latest` ou `CURRENT` ;
- les intersections de segments ;
- de nouveaux read use cases ;
- la topologie de déploiement ;
- le load testing ;
- un DAG ou un ordonnancement de projections.

En particulier, le catalogue de producteurs ne devient pas le mécanisme de création des tâches.

## 16. Critères d'acceptation du futur refactoring

Le refactoring sera conforme lorsque :

1. un seul use case public prépare `READ_POT` et `POT_BALANCES` depuis une `ProjectionTask` ;
2. les deux types sont résolus dans un catalogue de déclarations cohérentes ;
3. le moteur implémente une seule fois `resolve → load → project → key check → validate` ;
4. aucun producer ou préparateur spécifique ne répète cet algorithme générique ;
5. une configuration absente, dupliquée ou incohérente échoue comme erreur interne ;
6. la clé produite est contrôlée par le moteur avant validation ;
7. le contrat générique `ProjectionValidator` est toujours appliqué avec la définition de la même
   déclaration, sans validator spécialisé par type ;
8. `Prepared` transporte une `ValidatedProjection` sans la publier ;
9. les chemins Temporary, Terminal et Internal restent distincts ;
10. l'orchestration publie seulement après fencing et conserve l'atomicité Claim/Slot ;
11. le runtime peut contenir un ou plusieurs producteurs sans changer de moteur ;
12. les workers spécialisés et multi-projections utilisent la même façade ;
13. plusieurs workers peuvent traiter et scaler indépendamment un même `ProjectionType` ;
14. le filtrage opérationnel par type ne participe pas au routing fonctionnel ;
15. ajouter AUTH ne requiert normalement que definition, loader, projector et registration ;
16. aucune dépendance à pipeline, `taskType`, generation, Event ou legacy n'entre dans le moteur.

## 17. Points laissés à l'implémentation

Restent volontairement ouverts jusqu'au plan et à l'inspection détaillée du graphe de dépendances :

- le nom exact de la façade et de la déclaration de producteur ;
- la signature Java reliant loader et projector et la technique de capture du type `I` ;
- la représentation interne immutable du catalogue ;
- la visibilité exacte des implémentations ;
- le découpage Maven minimal entre moteur, intégration Consumption et contrats de définition ;
- l'ownership physique final des définitions partagées ;
- le wiring Spring et le mécanisme de découverte des beans ;
- la politique opératoire appliquée aux erreurs internes ;
- la source future de la date nécessaire au loader `READ_POT`.

Aucun de ces choix ouverts ne peut remettre en cause la façade unique, la déclaration cohérente par
type, la validation centralisée ou la séparation entre préparation et publication fenced.
