# A2 — Consumption et Discovery : modèle minimal

Statut : **proposition à soumettre à revue architecturale, non validée**.

Périmètre : architecture cible et trajectoire de remplacement ; aucune implémentation runtime ni migration. A2-P sert d'inventaire, sans autorité sur les abstractions cibles.

## 1. Modèle cible : une définition, des traitements durables

Une **Consumption** est une définition applicative : un UUID stable, une famille de consommables et une fonction de transformation. `consumptionId` suffit à la référencer ; ni nom de consumer, ni projection, ni configuration de déploiement ne complètent son identité.

Un **slot** représente le traitement d'un consommable par cette définition : `(consumptionId, consumableId)`. Son `slotId` identifie l'exécution durable ; un claim identifie une tentative. La famille est déduite de la définition. Une référence de consommable peut rester composite pour les objets qui n'ont pas d'UUID propre, notamment une `ProjectionKey`.

La définition et sa fonction restent en Java, sans sérialisation en base. Deux réplicas partagent son UUID constant ; deux traitements indépendants ont deux UUID. Un changement exigeant le retraitement de l'historique crée une nouvelle Consumption. Des versions incompatibles ne travaillent pas simultanément sous le même UUID.

### Deux contrats de source

| Responsabilité | Burnable | Non-burnable |
|---|---|---|
| Autorité de disponibilité | État de consommation porté par la source | Enrôlement et état du slot de chaque Consumption |
| Discovery | Trouver les sources disponibles pour cette Consumption | Créer durablement les slots manquants pour cette Consumption |
| Acquisition par Locator | Revérifier la disponibilité et l'affectation ; créer/reprendre le slot et son claim atomiquement | Acquérir un slot déjà enrôlé, échu ou reprenable |
| Finalisation Engine | Effets + état terminal de la source + slot + clôture du claim, même transaction | Effets + slot + clôture du claim ; aucune mutation de consommation sur la source |
| Retry | Source non terminale ; même slot, nouvelle tentative | Même slot, indépendamment du checkpoint |
| Historique | Aucun checkpoint E nécessaire à la disponibilité | Checkpoint utile pour enrôler un journal sans le reparcourir |

Une source burnable est affectée durablement à **une** Consumption avant sa mise à disposition : une autre définition ne peut contourner l'exclusivité par un autre slot. La source porte disponibilité/terminalité ; le slot porte tentatives, délais et claims, sans deuxième lease. Retry ne brûle pas la source ; SUCCESS, REJECTED et FAILED la rendent terminale avec leur résultat distinct.

Une source non-burnable peut être enrôlée par plusieurs Consumptions. Son slot constitue déjà la preuve d'enrôlement : aucune table d'enrôlement parallèle n'est nécessaire. Un consommable hors du champ de la transformation peut produire zéro sortie et terminer SUCCESS ; cela évite un manifeste de filtres et des exclusions implicites lors du checkpoint.

La nature appartient au contrat typé de la famille. `BurnableDiscovery` et `NonBurnableDiscovery` exposent des responsabilités différentes ; claims/finalisation restent communs, avec un participant de finalisation de source dans le seul chemin burnable.

## 2. Grammaire applicative et coopération

Quelques types suffisent : `ConsumptionId`, `Consumption<I,O>`, `ConsumableRef<I>`, `SlotRef<I>`, `ClaimedSlot<I>`. Les deux dernières références portent explicitement `consumptionId`, `slotId` et la référence du consommable ; `ClaimedSlot` ajoute `claimId`. Le paramètre Java protège la famille ; l'UUID protège la définition au sein d'une même famille.

Chaque service reçoit sa **Consumption au constructeur**, avec ses ports typés, et expose son identité :

| Use case lié à C | Entrée | Sortie | Invariant |
|---|---|---|---|
| `NonBurnableDiscovery<C>.enrol` | C, taille de page | C, références enrôlées, position atteinte éventuelle | Slots et progression durables atomiques pour C |
| `BurnableDiscovery<C>.findAvailable` | C, limite | C, références de sources disponibles | Aucune promesse d'acquisition ; aucune modification de checkpoint |
| `Locator<C>.acquire` | C, worker, lease, limite ; candidats pour le chemin burnable | `ClaimedSlot<C>` ou aucun travail | Disponibilité revérifiée ; claim du slot de C seulement |
| `Engine<C>.execute` | `ClaimedSlot<C>` | C, slot, résultat confirmé : terminal, retry ou perte de claim | Transformation fixée par C ; aucune fonction d'exécution dans la requête |

`C` désigne ici la définition liée, pas une classe Java par UUID. Commandes et résultats portent son `ConsumptionId` concret ; une identité différente de celle du service est refusée localement.

**Interdiction d'exécution croisée :** Engine charge le slot durable sous `(consumptionId, slotId)`, vérifie référence/famille et rattachement du claim avant d'appeler sa transformation. Locator cherche uniquement dans C. Finalisation/retry exigent à nouveau C/slot/claim. Falsifier l'identité d'un message ne change pas le propriétaire durable. Les ports imposent ces identités ; SQL garantit les invariants sous concurrence. Aucune API publique « slotId + callback arbitraire ».

Le Worker compose les trois composants par des constructeurs ordinaires vérifiant leur cohérence locale. Pas de validateur global, registre, manifeste ou framework. La relecture durable par Engine reste obligatoire.

### Enrôlement d'un journal non-burnable

Discovery lit une page après le checkpoint de C, crée idempotemment les slots et avance le checkpoint **dans la même transaction**. Plusieurs Discoverers sérialisent ou comparent cette progression ; une collision impose une reprise, jamais une progression sans enrôlement complet.

Le checkpoint signifie « préfixe enrôlé », jamais « traité ». Locator reprend les slots PENDING éligibles derrière le checkpoint sans reparcourir le journal. Une nouvelle Consumption part du début de l'historique conservé. Retenir un checkpoint par Consumption ; paralléliser l'exécution par claims, sans partitionnement durable des checkpoints initialement.

Sans journal E, une demande/task pour une Consumption connue peut être enrôlée à sa création, dans la même transaction. Sinon, garder un parcours complet reprenable jusqu'à disposer d'une preuve de préfixe stable ; date/UUID ne suffit pas pour un checkpoint durable.

### Garanties transactionnelles

Acquisition et exécution restent deux transactions physiques. L'acquisition verrouille le slot pour installer/remplacer le claim, sans effet métier. Engine recharge l'entrée autoritative, écrit les effets rollbackables, puis provenance, flush, CAS terminal de C et clôture conditionnelle du claim. Conserver ce suffixe commun, sans prélock terminal ni second fencing.

Le CAS exige le slot PENDING et son `currentClaimId`, avec le rattachement à C. Zéro ligne ou échec de clôture provoque le rollback de tous les effets. La lease autorise le takeover ; son expiration seule ne retire pas l'autorité au claim courant. La révision reste un contrôle optimiste, pas un fencing token. Les autorités Pot et Binding gardent leurs propres garanties.

Une exception technique provoque le rollback avant une transaction d'échec décidant retry/FAILED sous le même fencing. Perte de claim et commit COMMITTED/UNKNOWN ne déclenchent pas automatiquement un nouvel échec métier. Le succès public exige un commit confirmé. Un effet externe passe par une sortie durable transactionnelle ; `afterCommit` ne garantit pas sa livraison. Les tentatives peuvent se répéter, seuls leurs effets gagnants deviennent durables.

## 3. Familles minimales d'événements et positions E

Une **famille technique** définit un journal, son contrat de lecture et son espace de positions E. Un **type d'événement** décrit un fait dans cette famille. Une **opération métier** est la demande qui peut produire zéro, un ou plusieurs faits et une complétion.

Retenir deux catégories dans chacun des deux royaumes, soit quatre familles pour des raisons fonctionnelles — sans conserver le découpage historique :

| Famille cible | Types représentatifs | Fonction |
|---|---|---|
| `BUSINESS_EVENT` | PotCreated, ExpenseSharesUpdated… | Modifications effectives du domaine métier |
| `BUSINESS_REQUEST_COMPLETED` | CommandApplied, CommandRejected, CommandFailed | Décision terminale d'une demande métier externe, même sans modification |
| `IDENTITY_EVENT` | UserCreated, ExternalIdentityAttached, ExternalIdentityDetached | Modifications effectives du domaine d'identité |
| `IDENTITY_REQUEST_COMPLETED` | RegistrationRegistered, RegistrationRejected ; autres complétions quand leurs demandes existent | Décision terminale d'une demande externe au domaine d'identité |

Inscription/attachement/détachement sont des opérations ; leurs faits sont des types. Une inscription réussie produit UserCreated + Attached + Registered atomiquement ; un rejet produit sa seule complétion. Une commande appliquée produit des faits et une complétion ; rejet/échec peuvent ne produire que cette dernière. Aucun retry ne publie une complétion prématurée. Une Registration définitivement FAILED exposée comme terminée exige une complétion explicite, absente du modèle REGISTERED/REJECTED actuel.

`BINDING_FACT` et `USER_CREATED_FACT` deviennent des types/sources de `IDENTITY_EVENT`. Tables distinctes et révision Binding ne justifient pas deux familles E ; les faits partagent déjà le propriétaire Registration. La fusion accroît la sérialisation des publications d'identité, sans besoin démontré de deux compteurs. Domaine/complétion restent séparés pour leurs usages, leur existence et leurs allocations indépendants. Aucune enveloppe universelle imposée.

Une publication expose famille, E, identité source, type, sujet/version et corrélation éventuelle. Registration garde `requestId` comme identité source ; son journal référence la décision durable sans dupliquer l'outcome.

**E est une position de publication par famille**, distincte de la version Pot, de la révision Binding et de l'identité Event. Le numéro d'une famille n'est pas comparable à celui d'une autre. Le checkpoint exige un préfixe stable : aucune publication future ne peut apparaître derrière la position franchie.

Retenir la publication sous le propriétaire transactionnel réel : mutations/faits/décisions et références exhaustives, provenance/flush/CAS/clôture s'il y a Consumption, puis **suffixe scellé d'allocation E et publication avant commit**. Ce suffixe structurel étend le protocole livré : aucun nouveau callback métier ni retour aux autorités Pot/Binding/slot après allocation. Tout échec annule aussi faits, slot et claim. Couvrir normal, recovery, échec terminal et producteurs directs hors Consumption.

Ordre d'acquisition technique : `BUSINESS_EVENT < BUSINESS_REQUEST_COMPLETED < IDENTITY_EVENT < IDENTITY_REQUEST_COMPLETED`. Les compteurs sont rollbackables, leurs verrous conservés jusqu'au commit/rollback. Dans un batch d'inscription : ordinaux UserCreated puis Attached. Prendre les ressources conflictuelles avant les compteurs ; éprouver les attentes SQL du suffixe. Aucun `nextval`, allocation autonome ou publication après commit. La stabilité du préfixe est nécessaire, sa densité ne l'est pas.

Le propriétaire appelle son participant explicite de publication avec les références réellement créées. FK/unicité garantissent des associations valides, pas l'exhaustivité des faits/publications : celle-ci se prouve dans les use cases producteurs.

## 4. Cas complet : BUSINESS_EVENT → ProjectionTasks

### Déclaration lisible en Java

Extrait **illustratif**, sans code runtime livré ; `Consumption` est le type de définition, les composants sont des classes ordinaires :

```java
// Type de la constante BUSINESS_EVENT : NonBurnableFamily<BusinessEvent>.
static final Consumption<BusinessEvent, List<ProjectionTask>> PROJECTION_TASKS =
    new Consumption<>(
        new ConsumptionId(UUID.fromString("bd7593f5-17f9-49b4-bfc7-daf221696642")),
        BUSINESS_EVENT,                    // famille NonBurnable
        new BusinessEventToProjectionTasks());

var discovery = new NonBurnableDiscovery(PROJECTION_TASKS, events, slots, checkpoints, tx);
var locator   = new NonBurnableLocator(PROJECTION_TASKS, slots, claims, tx);
var engine    = new ConsumptionEngine(PROJECTION_TASKS, events, slots, claims, tasks, tx);
var worker    = new ConsumptionWorker(PROJECTION_TASKS, discovery, locator, engine);
```

Le développeur écrit les branches typées : par exemple PotCreated → READ_POT et BALANCES à la version de l'événement. Sans conséquence : liste vide ; inconnu/invalide : erreur explicite. Les routes restent du Java, l'UUID une constante.

### Parcours et persistance

1. Le producteur métier persiste le BusinessEvent et sa publication E avec la mutation Pot et sa complétion éventuelle dans la transaction productrice. Cet Event ne porte aucun état consumed.
2. Discovery de PROJECTION_TASKS lit une page E. Il persiste un slot PENDING par événement et le checkpoint de cette Consumption dans une transaction courte. La transformation et les tâches ne s'exécutent pas pendant l'enrôlement.
3. Locator de PROJECTION_TASKS sélectionne un slot échu, installe son claim dans une transaction d'acquisition et retourne `ClaimedSlot(consumptionId, slotId, eventRef, claimId)`. Un crash après acquisition laisse un slot reprenable après la lease.
4. Engine de PROJECTION_TASKS vérifie le rattachement durable, recharge l'événement et appelle `BusinessEventToProjectionTasks`. Toutes les tâches retournées sont persistées idempotemment par leur `ProjectionKey`, avec la provenance Event → tâches, puis slot DONE/SUCCESS et claim clos, dans une transaction unique. Zéro tâche est un succès valide.
5. Échec avant commit : aucune tâche partielle durable. Retry : même slot. Takeover gagnant : le CAS du perdant annule ses tâches et sa provenance. Deux événements produisant une même ProjectionKey convergent vers la tâche existante sans fusionner leurs slots. Engine retourne le résultat seulement après résolution du commit.

À persister : événements/publications, checkpoint, slots/claims/historique, tâches/provenance. **Toutes les tâches d'un Event sont produites atomiquement** ; elles ont ensuite leurs slots d'exécution. Remplacer le slot Event × projection suppose une liste de sorties bornée, conforme au catalogue courant.

### Intégré ou séparé

Intégré : appels directs du Worker. Séparé : chaque processus charge son use case et la même déclaration Java. Seules les références contextualisées traversent le transport ; jamais la transformation.

Slots durables et leases permettent la reprise malgré perte/duplication de messages. Aucune transaction ouverte entre processus. Effets et finalisation gardent la même base transactionnelle ; la séparation de processus ne résout pas un déplacement des effets dans une autre base.

### Même modèle dans l'identité

Déclarer `IDENTITY_EVENT → CurrentBinding` sous un autre UUID. Discovery enrôle, Locator acquiert, Engine recharge : Attached/Detached applique CurrentBinding avec les contrôles BindingId/révision ; UserCreated ne produit rien. Application/finalisation sont atomiques. E sert au parcours, la révision garde son sens fonctionnel. Une autre Consumption matérialise les résultats depuis `IDENTITY_REQUEST_COMPLETED`.

## 5. Écarts et migration depuis le code observé

Référence inspectée : `90926a5ec37d1ca841e3de017bec0d1f705d8839`, avec les modifications locales préexistantes laissées intactes. Les études de numérotation sont des propositions : la recherche dans le runtime Java/SQL courant ne montre pas de positions de publication E ni de checkpoints durables. Les Discoveries utilisent date/UUID et les slots ; la révision du stream Binding est une autre notion.

| Classement | Existant constaté | Traitement cible |
|---|---|---|
| **Conserver** | `ConsumptionSlot`, `Claim`, lifecycle acquire/terminalize/scheduleRetry ; propriétaires transactionnels et participants ; garanties Pot/Binding | Garder les garanties de concurrence, rollback, clôture stricte et résolution du commit ; adapter les identités |
| **Conserver** | Événements métier typés, décisions/outcomes, ProjectionKey et `ProduceProjectionTaskService.ensure` | Conserver leur sens et la convergence des tâches ; ne pas confondre décision et statut de Consumption |
| **Simplifier** | `ConsumptionKey(ConsumableIdentity, ConsumerIdentity)` et composants textuels/JSON ; clés fabriquées par chaque supra | Remplacer l'identité consumer par un ConsumptionId stable ; conserver seulement la référence source nécessaire |
| **Remplacer** | `ConsumptionLocator.openSearch`, `LocatedConsumption` embarquant exécution/classifier/effet ; `ExecuteConsumptionInput(slotId, claimId, execution)` et finalisation publique à callback | Use cases liés à la définition, références contextualisées et Engine à transformation fixe ; finaliseur générique interne |
| **Remplacer** | `ProjectionMaterializationConsumptionSource` + discovery SQL Event × routes/projections, curseur temporaire et anti-join DONE | Enrôlement Event par Consumption avec checkpoint ; Locator des slots ; transformation de l'Event complet dans Engine |
| **Simplifier** | Sources Binding, LKV, CommandResult, RegistrationResult répliquant les conventions de consumer et les recherches sur l'historique | Déclarations de Consumption explicites ; réutiliser enrôlement et acquisition, garder leurs transformations spécialisées |
| **Remplacer** | Séparation physique BindingFacts/UserCreatedFacts et consommation directe de RegistrationOutcome | Familles logiques IDENTITY_EVENT et IDENTITY_REQUEST_COMPLETED ; adapter les lecteurs, sans imposer de fusion physique des tables |
| **Supprimer** | Conventions d'identité par noms `*_V1`, clé Event × projection pour ce traitement, callbacks publics arbitraires et ancien chemin de découverte après bascule | Aucun adaptateur de compatibilité permanent ; aucune commande V1 en production à protéger |
| **Supprimer** | Colonnes legacy de consommation de `business_event_outbox`, après contrôle des usages restants | Aucun état partagé burned/processed sur un événement non-burnable |

Command, RegistrationRequest et ProjectionTask n'ont actuellement pas d'état de consommation dans leur objet source ; leur traitement repose sur les slots. **Ne pas les déclarer burnables par intuition.** Le modèle cible permet leur enrôlement explicite à la création pour leur Consumption désignée ; leur conversion éventuelle en burnables demanderait un besoin démontré et une migration de lifecycle distincte. Le contrat burnable reste nécessaire, sans imposer cette conversion pour réaliser le parcours Event → tâches.

### Ordre de migration proposé après revue

1. Introduire les définitions Java et références contextualisées, puis fermer les API permettant l'exécution croisée. Affecter un UUID à chaque traitement réellement indépendant ; pas de registre global. Maintenir les frontières transactionnelles et épreuves de fencing existantes.
2. Mettre en place les quatre journaux de publication et leur suffixe transactionnel chez tous les producteurs concernés. RegistrationOutcome conserve son identité requestId ; compléter son modèle uniquement pour les complétions réellement nécessaires, dont l'échec terminal exposé. Backfill sous arrêt coordonné des writers : conserver IDs, faits, décisions et versions ; attribuer un ordre historique déterministe sans prétendre reconstruire les commits passés.
3. Installer l'enrôlement et les checkpoints, puis basculer Event → tâches vers le slot par Event. Reprendre les conséquences historiques manquantes par `ensure` sous la nouvelle Consumption et son checkpoint initial ; les tâches existantes convergent. Ne pas convertir plusieurs anciens slots Event × projection en un unique DONE sans démontrer la couverture de toutes les sorties.
4. Basculer les autres Consumptions, puis retirer les anciens chemins et colonnes devenus inutiles. Préserver l'historique utile des claims/provenances ; toute réinitialisation de données de développement doit être explicite. L'absence de contrainte produit V1 autorise la rupture de contrat, pas l'abandon silencieux d'un travail durable.

Si des positions E ont été ajoutées entre cette référence et l'implémentation : renommer une famille inchangée peut préserver ses positions ; fusionner les espaces Binding/User exige un nouvel espace E et une reconstruction des checkpoints, jamais `max(oldE)` ni une comparaison entre anciens compteurs. Les anciens enrôlements restent identifiés par source/Consumption. Toute bascule est coordonnée avec l'arrêt des anciens Discoverers ; pas de double protocole permanent.

## 6. Arbitrages soumis à la revue et preuves attendues

La revue doit ratifier trois choix : les quatre familles domaine/complétion ; l'atomicité de toutes les ProjectionTasks d'un Event sous une seule Consumption ; le suffixe de publication E et son ordre de ressources avec la finalisation existante. La validité sous concurrence de ce dernier ne découle pas du présent plan. Aucun choix de framework ou de représentation SQL universelle n'est requis.

Cette étape est documentaire : **slices déclarées : aucune ; commandes Maven exécutées : aucune ; franchissement inattendu : aucun ; gate global : non requis ; reactor complet : non exécuté ; vérification DB : aucune.** La vérification effectuée est une lecture statique des sources citées ci-dessous et un contrôle du diff documentaire.

Contrôles exécutés : `git diff --no-index --check /dev/null docs/plans/2026-10-22-a2-minimal-consumption-discovery.md` (aucun diagnostic d'espacement ; code 1 du diff avec un fichier nouveau), puis contrôle Python des espaces finaux, 14 liens locaux et blocs Java : succès. `git diff --check` global signale des espaces finaux préexistants dans Keycloak/Bruno, laissés intacts.

Les futurs lots déclareront leurs slices avant modification selon la [politique normative](../testing/Reactor_Verification_Policy.md). Event → tâches : EVENT et PROJECTION ; publications métier/complétion : COMMAND ; identité/complétion : BINDING et consommateurs effectivement modifiés ; enrôlement à l'admission : WEB si touché ; LKV seulement si son comportement change. Les runtimes Registration/Results ne sont pas des slices canoniques nommées par la politique : déclarer explicitement leurs anchors complémentaires lorsqu'ils changent, sans les considérer couverts par BINDING seul.

Exemples de commandes canoniques, **à exécuter lors des lots concernés, non à cette étape** : depuis `app/`, `./mvnw -pl runtime-event-consumption-worker,runtime-task-consumption-worker -am test` ; `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test`. Changer les frontières/modules impose `./mvnw -pl architecture-tests -am test`. Une restructuration large ou la clôture majeure impose `./mvnw test` au gate prévu, pas à chaque lot. Les migrations futures exigent une preuve CURRENT et de bascule des données ; l'usage d'une baseline dépend de sa certification réelle, jamais supposée.

Les preuves décisives seront : refus d'un slot de C1 par Engine C2 de même famille, même si l'entrée est falsifiée ; slots et checkpoint annulés ensemble ; retry derrière checkpoint ; crash entre composants ; effets multi-tâches annulés sur perte de claim/clôture/commit ; disponibilité burnable et affectation revérifiées ; publication multifamille commit/rollback sans insertion tardive derrière checkpoint ; bascule historique sans conséquence perdue. Aucun test ni migration n'est écrit ici.

### Sources du constat

- [Identité actuelle](../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumptionKey.java), [slot](../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/claim/ConsumptionSlot.java), [entrée Execute](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/in/consumption/input/ExecuteConsumptionInput.java), [LocatedConsumption](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/locator/LocatedConsumption.java).
- [Discovery Event actuelle](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java), [clés Event × projection](../../app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionKeys.java), [production des tâches](../../app/engine-produce-projection-task/src/main/java/com/kartaguez/pocoma/engine/produce/projectiontask/materialization/ProduceProjectionTaskService.java).
- [Protocole transactionnel livré](../architecture/consumption-transactional-execution.md), [lifecycle réel](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java), [étude de publication](../architecture/Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md#d-eventpublication--modèle-et-intégration).
- [Registration et ses faits](../../app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/ExecuteRegistrationService.java), [schéma outcomes/UserCreated](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V28__registration_outcomes.sql), [outcome et terminal Event Command](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java).
