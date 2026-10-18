# Pocoma — Chantier A : versions et obsolescence des commandes Pot

Étude canonique de conception, 18 octobre 2026. **TARGET NON IMPLÉMENTÉ**. Audit statique, sans tests, Maven, SQL exécuté, benchmark ou modification runtime. Ce rapport complète l’[index transactionnel](transactional-protocol-studies.md) et ne remplace aucun rapport historique.

> **Révision courante : [Chantier A.2, sections L à V](#l-chantier-a2--révision-par-conflits-décriture-18-octobre-2026), verdict READY_WITH_DECISIONS.** Les sections A à K ci-dessous sont conservées comme historique ; la matrice A.2 remplace leur politique de protection des lectures.

## A. Executive summary et référence

**Verdict : BLOCKED_BY_EVENT_GAPS.** Le contrat observed/working/next est viable. Le code courant permet déjà des décisions conservatrices par identités et types. Il ne permet pas une acceptation sûre de toutes les commandes anciennes : les Events ne décrivent ni les changements de relation Shareholder–User, ni les valeurs affectant le sens d’une allocation ; la preuve de complétude de l’intervalle doit aussi devenir explicite. Certaines lacunes peuvent être contournées par une politique conservatrice validée fonctionnellement, sans enrichir les Events. Elles ne doivent jamais être masquées par une acceptation implicite.

**PROVEN — base exclusive** : worktree `/private/tmp/pocoma-consumption-transaction-protocol`, branche `feat/consumption-transaction-protocol`, HEAD de départ `62a57c780368ea3b46e6d1ae2ffb3031858a6a5a`, propre à l’entrée. `git merge-base --is-ancestor 675533e7 HEAD` réussit. R03 est présent dans le code : `JpaExpenseContextAdapter.loadExpenseContextData` découvre le propriétaire, fixe V puis relit le lifecycle Expense à V. Le [rapport d’intégration R03](../testing/evidence/Expense_Context_Integration_Report_2026-10-18.md) reste une preuve historique bornée, non réexécutée ici.

Fetch `git fetch --no-tags origin` réussi après une première tentative refusée par le sandbox sur FETCH_HEAD ; aucune intégration automatique, changement de HEAD, merge, rebase ou push. Les autres worktrees sont conservés ; le checkout initial `feat/runtime-monolith` à `90926a5e` n’a pas servi de base d’analyse.

Lecture initiale : [docs README](../README.md), [index consolidé](transactional-protocol-studies.md), [étude des autorités et publication](Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md), [audit transversal et correction R03, sections J/K](Transactional_Authorities_Fencing_Locks_Audit_2026-10-18.md), [audit Consumption](Consumption_Generic_Transactional_Protocol_Audit_2026-10-16.md), [contrat transactionnel livré](consumption-transactional-execution.md), [modèle fonctionnel](../product/Functional_Model.md), [contrats d’autorisation](authorization-kernel-contracts.md). Le code au HEAD ci-dessus prévaut sur les descriptions antérieures, notamment le diagnostic R03 historique.

Statuts utilisés dans tout le rapport : **PROVEN** = code/invariant existant constaté statiquement ; **PROPOSED** = règle cible à valider ; **UNDECIDED** = arbitrage fonctionnel ; **GAP** = information ou preuve absente ; **NOT_APPLICABLE** = hors du domaine de la règle. Ces statuts de preuve sont distincts des quatre décisions de la matrice. Aucune règle d’acceptation de commande ancienne n’est présentée comme déjà livrée.

### Périmètre de vérification déclaré

Impact production attendu et observé : NONE. Slice primaire/secondaires exécutées : NONE (documentation seule). Lecture du code COMMAND et de contrats EVENT/BINDING sans modification de leur comportement. Tous les fichiers `app/`, migrations, tests, use cases et adapters sont hors périmètre d’édition. Gate global : NOT REQUIRED. Full reactor : NOT REQUIRED et non exécuté. Vérification base : NONE. Aucun franchissement inattendu de slice. Contrôle documentaire : liens locaux, cohérence des tableaux, `git diff --check`, statut/diff Git. La [politique normative](../testing/Reactor_Verification_Policy.md) a été lue avant analyse du code.

## B. Contrat des versions

| Terme | Contrat cible |
|---|---|
| `observedVersion` O | Version métier du Pot connue de l’émetteur lors de la création de la commande ; immuable pendant toutes les tentatives. Ce n’est ni une position de publication ni une révision Binding. |
| `workingVersion` V | Dernière version primaire du Pot lue et fixée une seule fois pour la tentative. L’historique, le contexte, les autorisations relationnelles et le candidat sont rattachés à ce même V. |
| `nextVersion` N | V+1 pour une mutation acceptée ; jamais O+1 si O<V. Tous les états et BusinessEvents de la mutation portent N. Aucun incrément pour un rejet. |

**PROVEN — CURRENT** : neuf inputs portent `expectedVersion >= 1`, comparé exactement au `PotGlobalVersion` du contexte. Le décalage est actuellement refusé par `VersionConflictException(POT_VERSION_CONFLICT)`. `observedVersion` est une sémantique TARGET ; aucun renommage de champ public ni réinterprétation rétroactive d’un payload V1 n’est décidé ici. Un futur plan devra préciser la compatibilité des commandes durables existantes et des clients utilisant `expectedVersion` comme précondition stricte.

**PROPOSED — séquence de tentative** : identifier le Pot (pour Expense, lookup de propriétaire seulement) → fixer V → vérifier O et lire tous les Events de `(O,V]` → établir la complétude → appliquer la politique historique → charger/valider les faits cohérents à V → préparer N → CAS V→N → persister les mutations et Events dans la transaction propriétaire existante → fences et finalisation Consumption existants → commit confirmé. Un contexte déjà chargé peut être réutilisé seulement s’il certifie exactement V ; aucun second loader « current » ne doit choisir V′ silencieusement. Les ports actuels choisissent eux-mêmes V ; leur évolution éventuelle devra garantir cette liaison, sans la présumer fournie par une simple nouvelle méthode de politique.

- O=V : historique vide ; compatible du point de vue historique uniquement, règles métier/autorisation à V toujours requises.
- 1≤O<V : inclure V6, V7 et V8 pour O=5/V=8 ; borne inférieure exclusive, supérieure inclusive.
- O>V, O invalide, Pot différent, version observée inexistante/non attestée : impossible à déterminer sûrement ; pas une preuve d’obsolescence métier. Ne pas attendre aveuglément un V futur. Valider la provenance/validité de la référence selon un contrat explicite.
- Débordement V+1 : impossibilité technique, aucune mutation ; ne pas envelopper la version.
- `CreatePot` : O et V préexistantes **NOT_APPLICABLE**, insertion initiale à V1, sans CAS d’une version antérieure. Une nouvelle commande de création n’est pas une modification stale d’un Pot existant.

**R03 PROVEN et à préserver** : aucun lifecycle issu du premier lookup Expense ne peut accompagner une version Pot lue plus tard. Les intervalles `started_at_version <= V AND (ended_at_version IS NULL OR V < ended_at_version)` incluent le tombstone ; « active at V » ne signifie pas « non supprimée ». Tous les agrégats supplémentaires sont lus à V. Les writers conformes ferment à W>V et ajoutent à W ; ils préservent les faits à V malgré les snapshots READ COMMITTED successifs. Ce n’est pas une garantie SQL universelle contre les réécritures rétroactives.

**CAS et rollback** : toute avancée du Pot après fixation de V fait perdre le CAS V→N ; aucun candidat préparé à V ne peut être committé à V′+1. Rollback de metadata, états, Events, Outcome, provenance et finalisation de l’essai selon le propriétaire transactionnel. Réévaluation éventuelle = nouvelle tentative, nouveau V, nouveau contexte et analyse `(O,Vnouveau]` ; conserver O. Ne pas recycler une décision compatible calculée précédemment.

## C. Inventaire exact des dix commandes

Inventaire établi depuis `PotCommandTypes`, les records `pot/intent`, leurs mappers, les interfaces et services `engine-write-pot`, puis les adapters de persistance. Aucun retrait de Shareholder n’est une onzième commande livrée : `REMOVE_SHAREHOLDER` existe dans le kernel mais n’a ni commande, service de mutation ni Event dans le catalogue Pot actuel.

Conventions des tableaux : P=PotId UUID, E=ExpenseId UUID, S=ShareholderId UUID, U=UserId UUID ; Oci=`expectedVersion` actuel. Chaque ligne identifie le record `*Command`, son input `*Input`, son interface `*UseCase` et le service homonyme `*Service`. Les inputs sont détaillés ci-dessous, les liens source sont réunis en C.4.

### C.1 Contrats, intentions et préconditions

| Commande exacte / type durable | Input exact utile (en plus de Oci, sauf création Pot) | Intention et préconditions métier courantes |
|---|---|---|
| `CreatePotCommand` / `POT_CREATE_V1` | `CreatePotInput(label:String, creatorId:UUID)` | Création d’une nouvelle identité Pot générée ; label et creator non nuls. Pas de contexte/version observée. |
| `UpdatePotDetailsCommand` / `POT_DETAILS_UPDATE_V1` | `UpdatePotDetailsInput(potId, label, expectedVersion)` | Fixer absolument le label du Pot ; Pot non supprimé. Ne promet pas un solde particulier. |
| `DeletePotCommand` / `POT_DELETE_V1` | `DeletePotInput(potId, expectedVersion)` | Suppression logique destructive du Pot, terminale pour ses mutations ; Pot non supprimé. Sens « Pot tel que vu » versus « quel que soit son état futur » non spécifié par l’input. |
| `AddPotShareholdersCommand` / `POT_SHAREHOLDERS_ADD_V1` | `AddPotShareholdersInput(potId, Set<ShareholderInput(name,weightNumerator,weightDenominator)>, expectedVersion)` | Ajout relatif de nouvelles identités, pas remplacement de la collection. Pot vivant, ensemble non vide, poids strictement positifs. UUID générés et userId=null ; pas d’unicité métier des noms imposée par l’agrégat. |
| `UpdatePotShareholdersDetailsCommand` / `POT_SHAREHOLDERS_DETAILS_UPDATE_V1` | `UpdatePotShareholdersDetailsInput(potId, Set<ShareholderDetailsInput(shareholderId,name,userId)>, expectedVersion)` | Fixer nom ET lien User de chaque cible ; userId nullable permet de retirer ce lien. Cibles présentes et non supprimées. Opération sur une relation existante, pouvant changer des droits. |
| `UpdatePotShareholdersWeightsCommand` / `POT_SHAREHOLDERS_WEIGHTS_UPDATE_V1` | `UpdatePotShareholdersWeightsInput(potId, Set<ShareholderWeightInput(shareholderId,weightNumerator,weightDenominator)>, expectedVersion)` | Fixer les poids des seules cibles présentes/non supprimées ; ensemble non vide et poids positifs. Poids bruts versus proportions visées : UNDECIDED. Pas une augmentation relative implicite. |
| `CreateExpenseCommand` / `EXPENSE_CREATE_V1` | `CreateExpenseInput(potId,payerId,amountNumerator,amountDenominator,label,date:LocalDate,Set<ExpenseShareInput(shareholderId,weightNumerator,weightDenominator)>,expectedVersion)` | Ajouter une Expense à identité générée, montant/payer/parts explicitement fournis. Pot vivant, payer et bénéficiaires membres, shares non vides et positives, Amount/Fraction valides. Aucune sélection automatique de tous les membres ni copie des poids Pot dans le service. |
| `UpdateExpenseDetailsCommand` / `EXPENSE_DETAILS_UPDATE_V1` | `UpdateExpenseDetailsInput(expenseId,payerId,amountNumerator,amountDenominator,label,date,expectedVersion)` | Fixer tout le header (payer, montant, label, date), pas un patch d’un seul champ ; Expense et Pot vivants, nouveau payer membre. Résultat financier dépend aussi des shares existantes. |
| `UpdateExpenseSharesCommand` / `EXPENSE_SHARES_UPDATE_V1` | `UpdateExpenseSharesInput(expenseId,Set<ExpenseShareInput(shareholderId,weightNumerator,weightDenominator)>,expectedVersion)` | Remplacer la totalité des shares de l’Expense ; pas ajout relatif d’une seule part. Pot et Expense vivants, membres connus, shares non vides/positives. Les poids d’un même S sont fusionnés par l’agrégat. Résultat dépend du montant/payer existants. |
| `DeleteExpenseCommand` / `EXPENSE_DELETE_V1` | `DeleteExpenseInput(expenseId, expectedVersion)` | Suppression logique destructive d’une Expense vivante d’un Pot vivant ; indépendance d’une autre Expense possible sous intention « supprimer cette cible », à valider fonctionnellement. |

Les constructeurs contrôlent nulls, fraction/montant et ensembles. Les contextes contrôlent les lifecycle, l’égalité Oci=V et la présence des identifiants. Les agrégats ajoutent leurs invariants (dont refus de modifier un Shareholder deleted). L’identité/propriété Expense→Pot est contrôlée avant l’autorisation par `ExistingExpenseAuthorizationContext` ; une incohérence n’est pas une compatibilité historique.

### C.2 Contextes, lectures, écritures, Events et version

Pour les neuf mutations existantes, **PROVEN** : contexte homonyme `XContext` obtenu par `loadXContext`, puis `updateIfActive(currentVersion,nextVersion)` avec N=V+1, insertion `pot_version_metadata(P,N)` et persistance temporelle. Oci doit aujourd’hui être exactement V. `CreatePot` utilise `save` initial, pas ce CAS. Les fermetures historiques contrôlent aussi leur cardinalité ; un échec n’autorise pas un commit partiel.

| Commande (suffixe Command omis) | Contexte chargé et objets lus à V | Objets modifiés / appels de persistance après autorité | BusinessEvent produit |
|---|---|---|---|
| CreatePot | Aucun contexte ; factory Pot | `PotGlobalVersionPort.save(V1)` + metadata ; `PotHeaderPort.saveNew(V1)` | `PotCreatedEvent(P,1)` |
| UpdatePotDetails | `UpdatePotDetailsContext` : version, deleted, creator ; `PotHeaderPort.loadActiveAtVersion` | `PotHeader.updateDetails(label)` ; header `save` ferme ancien intervalle et insère nouveau | `PotDetailsUpdatedEvent(P,N)` |
| DeletePot | `DeletePotContext` : version, deleted, creator ; header à V | `markAsDeleted` ; header `save` ; aucune cascade physique sur Expenses/shares/Shareholders | `PotDeletedEvent(P,N)` |
| AddPotShareholders | `AddPotShareholdersContext` : version, deleted, creator ; `PotShareholdersPort.loadActiveAtVersion` | `addShareholder` pour chaque élément ; `PotShareholdersPort.save`, insertion des seuls IDs ajoutés | `PotShareholdersAddedEvent(P,addedShareholderIds,N)` |
| UpdatePotShareholdersDetails | `UpdatePotShareholdersDetailsContext` : version, deleted, creator, IDs non deleted, liens S→U ; agrégat Shareholders | `updateShareholderDetails` ; ferme/insère seulement IDs mis à jour, conserve poids et deleted | `PotShareholdersDetailsUpdatedEvent(P,updatedShareholderIds,N)` |
| UpdatePotShareholdersWeights | `UpdatePotShareholdersWeightsContext` : version, deleted, creator, IDs ; agrégat Shareholders | `updateShareholderWeight` ; ferme/insère seulement IDs mis à jour, conserve nom/userId/deleted | `PotShareholdersWeightsUpdatedEvent(P,updatedShareholderIds,N)` |
| CreateExpense | `CreateExpenseContext` : version, Pot deleted, creator, IDs/liens S→U | factory ExpenseHeader+ExpenseShares ; `saveNew` des deux à N après un seul CAS Pot | `ExpenseCreatedEvent(E,P,N)` (un seul Event pour les deux agrégats) |
| UpdateExpenseDetails | `UpdateExpenseDetailsContext` : version, Expense/Pot deleted, creator, IDs/liens ; ExpenseHeader à V | header `updateDetails` ; header `save` ferme/insère ; shares inchangées | `ExpenseDetailsUpdatedEvent(E,P,N)` |
| UpdateExpenseShares | `UpdateExpenseSharesContext` : mêmes faits que Details ; ExpenseShares à V (fallback header à V si besoin) | `updateExpenseShares` ; `ExpenseSharesPort.save` ferme toutes les shares actives de E et insère le nouveau jeu | `ExpenseSharesUpdatedEvent(E,P,N)` |
| DeleteExpense | `DeleteExpenseContext` : version, Expense/Pot deleted, creator, liens ; header à V | header `markAsDeleted` + `save` ; shares historiques non fermées par cette commande | `ExpenseDeletedEvent(E,P,N)` |

### C.3 Autorisations nécessaires

**PROVEN** : UserContext authentifié/non anonyme et capacité requise par `PotActionRequirements`, puis relations métier à V. Les codes de refus détaillés restent ceux du service ; on ne les remplace pas par un code d’obsolescence.

| Commande | Capacité de base | Relation autorisée |
|---|---|---|
| CreatePot | `POT_CREATE` | user non nul selon `CreatePotAuthorizationPolicy` ; ne pas inventer un contrôle creatorId=caller absent de ce service |
| UpdatePotDetails | `POT_UPDATE` | creator |
| DeletePot | `POT_DELETE` | creator |
| AddPotShareholders | `SHAREHOLDER_CREATE` | creator |
| UpdatePotShareholdersDetails | `SHAREHOLDER_UPDATE` | creator OU utilisateur de chaque Shareholder cible |
| UpdatePotShareholdersWeights | `SHAREHOLDER_UPDATE` | creator |
| CreateExpense | `EXPENSE_CREATE` | creator OU membre du Pot |
| UpdateExpenseDetails | `EXPENSE_UPDATE` | creator OU membre du Pot |
| UpdateExpenseShares | `EXPENSE_UPDATE` | creator OU membre du Pot |
| DeleteExpense | `EXPENSE_DELETE` | creator OU membre du Pot |

Le Binding exact de l’enveloppe, sa révision, la validité temporelle et les permissions traduites à l’exécution restent gouvernés par le flux Command existant. Une compatibilité historique n’accorde aucun droit. Les changements de Binding sont hors du catalogue Pot et n’incrémentent pas nécessairement V ; le fence Binding ne peut donc pas être remplacé par cette matrice.

### C.4 Sources code de l’inventaire

- `CreatePotCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/CreatePotCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/CreatePotInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/CreatePotUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/CreatePotService.java).
- `UpdatePotDetailsCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotDetailsCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdatePotDetailsInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/UpdatePotDetailsUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotDetailsService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdatePotDetailsContext.java).
- `DeletePotCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/DeletePotCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/DeletePotInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/DeletePotUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/DeletePotService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/DeletePotContext.java).
- `AddPotShareholdersCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/AddPotShareholdersCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/AddPotShareholdersInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/AddPotShareholdersUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/AddPotShareholdersService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/AddPotShareholdersContext.java).
- `UpdatePotShareholdersDetailsCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotShareholdersDetailsCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdatePotShareholdersDetailsInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/UpdatePotShareholdersDetailsUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersDetailsService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdatePotShareholdersDetailsContext.java).
- `UpdatePotShareholdersWeightsCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotShareholdersWeightsCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdatePotShareholdersWeightsInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/UpdatePotShareholdersWeightsUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersWeightsService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdatePotShareholdersWeightsContext.java).
- `CreateExpenseCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/CreateExpenseCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/CreateExpenseInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/CreateExpenseUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/CreateExpenseService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/CreateExpenseContext.java).
- `UpdateExpenseDetailsCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdateExpenseDetailsCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdateExpenseDetailsInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/UpdateExpenseDetailsUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseDetailsService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdateExpenseDetailsContext.java).
- `UpdateExpenseSharesCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdateExpenseSharesCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdateExpenseSharesInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/UpdateExpenseSharesUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseSharesService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdateExpenseSharesContext.java).
- `DeleteExpenseCommand` : [record](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/DeleteExpenseCommand.java), [input](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/DeleteExpenseInput.java), [use case](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/DeleteExpenseUseCase.java), [service](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/DeleteExpenseService.java), [contexte](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/DeleteExpenseContext.java).

Sources transversales : [types durables](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/decode/PotCommandTypes.java), [mapper inputs](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/PotCommandInputMapper.java), [capture Events et inputs](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/PotCommandInvocation.java), [contextes Expense R03](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/JpaExpenseContextAdapter.java), [contextes Pot](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/JpaPotContextAdapter.java), [CAS et metadata](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/JpaPotGlobalVersionAdapter.java), [requête CAS](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/JpaPotGlobalVersionRepository.java), [capacités](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/authorization/PotActionRequirements.java), [relations métier](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/authorization/PotBusinessAuthorizationPolicy.java).

## D. Catalogue des BusinessEvents et fidélité historique

**PROVEN : dix types**, issus de [PocomaEventTypes](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/event/PocomaEventTypes.java). Tous portent P et `version` métier ≥1. Les classes sont dans le [package Event Pot](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/event). Codes courts pour la matrice :

| Code | Type exact / classe Java | Identifiants utilisables | Informations absentes |
|---|---|---|---|
| PC | `POT_CREATED` / PotCreatedEvent | P ; aggregate_id=P | label, creator |
| PD | `POT_DELETED` / PotDeletedEvent | P ; aggregate_id=P | raison et étendue métier de l’intention de suppression |
| PU | `POT_DETAILS_UPDATED` / PotDetailsUpdatedEvent | P ; aggregate_id=P | ancien/nouveau label ; changement effectif ou valeur identique |
| SA | `POT_SHAREHOLDERS_ADDED` / PotShareholdersAddedEvent | P, Set<S> ; aggregate_id=P | noms, poids, liens User des nouveaux membres |
| SD | `POT_SHAREHOLDERS_DETAILS_UPDATED` / PotShareholdersDetailsUpdatedEvent | P, Set<S> ; aggregate_id=P | champs effectivement changés, nom avant/après, userId avant/après |
| SW | `POT_SHAREHOLDERS_WEIGHTS_UPDATED` / PotShareholdersWeightsUpdatedEvent | P, Set<S> ; aggregate_id=P | poids avant/après, effet sur proportions |
| EC | `EXPENSE_CREATED` / ExpenseCreatedEvent | P, E ; aggregate_id=E | payer, montant, date, label, IDs/poids des shares |
| ED | `EXPENSE_DELETED` / ExpenseDeletedEvent | P, E ; aggregate_id=E | payer et bénéficiaires supprimés logiquement |
| EU | `EXPENSE_DETAILS_UPDATED` / ExpenseDetailsUpdatedEvent | P, E ; aggregate_id=E | champs changés, payer/montant avant/après, lien aux participants |
| ES | `EXPENSE_SHARES_UPDATED` / ExpenseSharesUpdatedEvent | P, E ; aggregate_id=E | bénéficiaires et poids avant/après |

[RecordedEvent](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/event/RecordedEvent.java) ajoute UUID Event, recordedAt et trace optionnelle ; pas d’ordre métier entre deux Events de même version. Le [mapper](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/BusinessEventRecordMapper.java) écrit type/P/aggregate/version et éventuellement `shareholderIds` dans `payload_json`. Les IDs Shareholder restent dans le JSON, pas dans une colonne d’index dédiée. Il vérifie les champs redondants lorsqu’ils sont présents, mais un `shareholderIds` absent/non array devient **Set vide**, accepté par les records. Ce comportement de lecture ne constitue pas une attestation d’absence de cibles : **GAP** pour l’obsolescence. Le lecteur cible devra distinguer donnée absente et ensemble valide ; aucune correction apportée ici.

### D.1 Plusieurs Events et une version

**PROVEN dans les dix services courants** : exactement un appel de publication BusinessEvent pour une mutation logique réussie. CreateExpense écrit deux agrégats mais émet un seul EC ; les commandes Shareholders émettent un Event contenant plusieurs IDs ; DeletePot ne produit pas un ED par Expense. Les Outcome/CommandTerminalEvent sont une autre famille, pas des changements métier Pot et pas un second Event Pot à la même version.

**PROVEN comme capacité technique** : le collecteur et `appendAll(List<BusinessEvent>)` acceptent plusieurs Events ; V2 n’a aucune unicité `(pot_id,version)`. Plusieurs Events à V sont donc possibles structurellement, sans producteur multi-Event parmi ces dix services. Aucun ordre intraversion ne doit être déduit de created_at, UUID, statut de consommation ou position de publication. La politique cible regroupe tous les Events d’une version et prend leur union ; elle n’essaie pas de rejouer des transitions intraversion.

### D.2 Complétude : garanties et limites

**PROVEN — code du chemin Command livré** : mutations, CAS/metadata, collecte puis [append MANDATORY avec saveAllAndFlush](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaPotCommandEventAppendAdapter.java), Outcome/provenance et clôture Consumption participent au même propriétaire transactionnel. Un rejet n’append pas de BusinessEvent de mutation. Un rollback confirmé ne laisse aucun sous-ensemble durable de l’essai. Les writers temporels inspectés préservent le passé à V.

**Limite** : atomicité d’une liste fournie ne prouve pas que la liste décrit tout changement significatif. Les Events décrivent une catégorie et des identités, pas un journal de deltas. Ils sont fidèles aux agrégats touchés par les services actuels mais insuffisants pour distinguer un renommage d’un transfert de relation, ou deux écritures identiques d’un changement financier. Le calcul des [balances](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/projection/balance/PotBalancesCalculator.java) utilise header.amount, header.payerId et poids des ExpenseShares ; les poids Pot ne réécrivent pas automatiquement ces ExpenseShares.

**GAP — garanties non universelles** : pas de FK Event→metadata, pas de contrainte « au moins tous les Events attendus pour chaque version », pas de manifeste de cardinalité par version, pas de trigger d’immutabilité des colonnes Event. `updatable=false` JPA n’interdit pas le SQL externe. Aucun writer de purge métier trouvé dans les sources production examinées ; cela ne démontre ni la rétention opérationnelle ni l’intégrité d’un jeu de données déployé. Les méthodes CRUD héritées du repository ne sont pas une garantie d’append-only. V11 protège les metadata contre update/delete, mais ne certifie pas les Events et refuse une base legacy avec Pots préexistants au moment de son application.

**PROPOSED — preuve minimale avant Compatible** : attester que O est une version valide de ce Pot ; borner l’extraction à V fixé ; lire sans filtre de statut tous les Events ; contrôler chaque version attendue et chaque payload ; connaître les writers/catalogues autorisés sur toute la période ; garantir absence de purge/réécriture. Pour le contrat CURRENT strictement un Event par version, la couverture de O+1…V avec un Event canonique par version, jointe à cette attestation des writers, constitue une preuve bornée. Une cardinalité inattendue n’est ni ignorée ni arbitrairement dédupliquée. Pour un futur producteur multi-Event, la présence d’un Event par version ne suffira plus : il faudra prouver que tout le groupe est fourni (contrat producteur/rétention ou attestation adaptée), sans choisir ici une nouvelle architecture.

Une liste partielle n’autorise jamais la commande, même si tous ses éléments semblent indépendants. Un trou, type inconnu, données invalides, rétention non attestée ou groupe incomplet produit `ImpossibleToDetermine`. Un Event incompatible valide peut être un témoin de refus, mais un intervalle déclaré corrompu doit conserver son diagnostic d’intégrité ; on ne le « répare » pas en métier.

## E. Matrice complète d’obsolescence

**PROPOSED**, sous historique complet et valide, même Pot, 1≤O≤V et intention bornée décrite ci-après. C=`COMPATIBLE`, O=`OBSOLETE`, X=`CONDITIONAL`, U=`UNDETERMINED`. Le suffixe donne la règle de justification (§F). N/A=`NOT_APPLICABLE`, exception explicite de CreatePot (§B) : il serait trompeur de forcer cette ligne dans une décision d’obsolescence.

| Commande | PC | PD | PU | SA | SD | SW | EC | ED | EU | ES |
|---|---|---|---|---|---|---|---|---|---|---|
| CreatePot | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| UpdatePotDetails | U0 | O1 | O2 | C1 | C1 | C1 | C1 | C1 | C1 | C1 |
| DeletePot | U0 | O1 | O3 | O3 | O3 | O3 | O3 | O3 | O3 | O3 |
| AddPotShareholders | U0 | O1 | C1 | U1 | U1 | U2 | C1 | C1 | C1 | C1 |
| UpdatePotShareholdersDetails | U0 | O1 | C1 | X7 | X1 | X2 | C1 | C1 | C1 | C1 |
| UpdatePotShareholdersWeights | U0 | O1 | C1 | U2 | X2 | X3 | C1 | C1 | C1 | C1 |
| CreateExpense | U0 | O1 | C1 | X7 | X4 | C3 | C4 | C4 | C4 | C4 |
| UpdateExpenseDetails | U0 | O1 | C1 | X7 | U3 | C3 | X5 | X6 | X6 | X6 |
| UpdateExpenseShares | U0 | O1 | C1 | X7 | U3 | C3 | X5 | X6 | X6 | X6 |
| DeleteExpense | U0 | O1 | C1 | C3 | U3 | C3 | X5 | X6 | X6 | X6 |

Cette grille comporte 100 intersections : **35 COMPATIBLE, 18 OBSOLETE, 21 CONDITIONAL, 16 UNDETERMINED et 10 N/A** justifiées. Les 16 U comprennent neuf U0 (anomalies PC) et sept cas métier indéterminés ; les 21 conditions comportent aussi des branches U. Soit 90 cases classées et 10 exceptions de création. Aucun blanc, aucun U converti en C. Les X peuvent résoudre vers U, lorsque les identités connues ne suffisent pas. La décision finale Compatible exige toutes les règles applicables résolues C. Les décisions O2/O3/X6 sont des choix conservateurs proposés, pas de nouvelles règles fonctionnelles déjà approuvées.

### E.1 Portée des intentions proposées

**PROPOSED** : `CreateExpense` crée exactement l’Expense fournie, sans objectif sur le solde global ; les modifications d’une autre Expense restent compatibles même si le solde total évolue. Pour Update/DeleteExpense, une autre Expense est indépendante seulement dans ce même contrat centré sur la cible. Si le produit veut garantir « obtenir tel solde net », ces inputs ne l’expriment pas et toutes ces indépendances doivent être réexaminées, pas acceptées par analogie d’IDs.

Pour une même Expense, header et shares forment une intention financière couplée. Refuser par défaut toute modification intermédiaire de la cible évite d’appliquer une nouvelle répartition sur un autre montant, ou un nouveau montant sur une répartition non vue. Un label seul pourrait être indépendant, mais EU ne dit pas si seul le label a changé ; pas d’optimisation par champ sans preuve.

Pour les Shareholders, même ID n’implique pas nécessairement même personne métier après changement userId ; les poids peuvent être compris comme valeurs absolues ou proportions. Le code prouve le mode d’écriture absolu, pas la promesse de sens de l’interface client. Ces ambiguïtés expliquent U1/U2/U3/X2/X3/X4.

## F. Règles, justifications et conditions exactes

Toutes les conditions ci-dessous supposent des IDs et payloads complets et valides. Donnée absente, identifiant invalide ou type futur : U, jamais ensemble vide compatible.

| Règle | Définition exacte et justification |
|---|---|
| U0 | PC dans `(O,V]` avec O≥1 est impossible pour une modification d’un Pot déjà observé valide (création à V1). **NOT_APPLICABLE** dans un historique normal ; si rencontré, **UNDETERMINED** et diagnostic incohérence de référence/historique, pas acceptation. |
| O1 | PD du Pot cible invalide toute intention de modification ultérieure, y compris une autre suppression. Terminalité actuelle PROVEN ; catégorie historique OBSOLETE proposée. Si suppression déjà visible en O et O=V, c’est la validation courante qui rejette en BUSINESS_REJECTION. |
| O2 | PU depuis O pour UpdatePotDetails : nouvelle écriture absolue de label risquant d’écraser une décision plus récente ; OBSOLETE conservateur même si valeur finale identique, faute de delta. |
| O3 | DeletePot + tout autre changement intermédiaire pertinent : refuser de supprimer un Pot dont le contenu/les relations ont changé depuis sa consultation. **PROPOSED**, validation fonctionnelle requise ; une suppression inconditionnelle serait un autre contrat. |
| C1 | Changement hors de la surface d’intention de la ligne : label Pot sans effet sur Expense ou identité membre ; opération Expense sans effet sur label/fiche/poids Shareholder ; membres/Expenses sans effet sur simple label Pot. Les autorisations à V restent obligatoires. Chaque C1 du tableau est justifié par ces surfaces explicites, pas par une règle générale « IDs différents ». |
| C3 | Les SA/SW classés C3 dans la grille ne changent pas les parts explicites d’une Expense existante ou demandée. Les services n’en déduisent pas de nouvelles shares ; les balances utilisent ExpenseShares, pas les poids Pot. Cette compatibilité proposée suppose que les paramètres explicites portent l’intention entière ; si le client copie des poids comme valeurs par défaut en promettant « suivre la configuration actuelle », l’arbitrage doit changer. |
| C4 | CreateExpense génère sa propre identité et reçoit payer/montant/shares ; une Expense existante n’est ni lue ni remplacée. Autres mutations Expense compatibles sous intention d’ajout explicite, pas d’atteinte d’un solde global. |
| U1 | AddShareholders après SA ou SD : noms/personnes des ajouts non visibles dans les Events ; on ne peut distinguer « ajouter une autre occurrence » de « ne pas ajouter une personne déjà ajoutée/renommée ». Aucune unicité métier inventée. **UNDECIDED** : accepter des occurrences nouvelles sans déduplication, ou exiger une intention/personne explicite. |
| U2 | AddShareholders après SW, ou UpdateShareholdersWeights après SA : ajout/poids indépendants techniquement mais proportions possibles modifiées. Pas de promesse produit tranchée, pas de valeurs historiques suffisantes pour affiner. **UNDETERMINED**. |
| U3 | Commande sur Expense existante après SD : Event ne dit pas si identité User ou simple nom a changé ; la commande ne fournit pas tous les participants historiques (ancien payer/anciennes shares). Même les IDs SD disjoints des nouveaux IDs demandés ne prouvent pas l’indépendance. **UNDETERMINED pour toute cette intersection**, sans reconstruction de snapshots. |
| X1 | UpdateShareholdersDetails après SD : soit T l’ensemble des IDs modifiés par la commande et H=`event.shareholderIds`. Si T∩H≠∅ : O (fiche complète écrasée) ; sinon C (autres fiches conservées, droits réévalués à V). |
| X2 | UpdateShareholdersDetails après SW, ou UpdateShareholdersWeights après SD : si T∩H=∅, C ; sinon U, car la signification du poids envers une personne dont le lien/fiche a changé reste à arbitrer. Les écritures conservent le champ complémentaire, mais cette commutation technique ne prouve pas le sens métier. |
| X3 | UpdateShareholdersWeights après SW : si T∩H≠∅, O (écrasement d’un poids concurrent) ; sinon U (poids absolus indépendants possibles, proportions couplées possibles). Décision fonctionnelle requise avant C sur ensembles disjoints. |
| X4 | CreateExpense après SD : R={payerId}∪IDs des shares demandées. Si R∩H=∅, C sous intention explicite de C4 ; sinon U (identité de bénéficiaire/payeur potentiellement changée). Les changements de droits du caller sont contrôlés à V même si son S n’est pas dans R. |
| X5 | Commande sur E existante après EC : si event.expenseId≠E, C (autre Expense, sous intention cible) ; si égal, U : cible créée après l’observation prétendue, son existence/intentionalité à O n’est pas attestée. Ne pas considérer cette anomalie comme une création indépendante. |
| X6 | Commande sur E existante après ED/EU/ES : si event.expenseId=E, O ; sinon C. ED invalide la relation cible ; EU/ES changent la substance financière supprimée/modifiée, y compris en croisant header/shares. Pour DeleteExpense, refuser de supprimer la cible transformée depuis O est une proposition conservatrice nécessitant accord fonctionnel. |
| X7 | Après SA, soit R les IDs explicitement référencés : cibles pour UpdateShareholdersDetails ; payer et shares pour CreateExpense ; nouveau payer pour UpdateExpenseDetails ; nouvelles shares pour UpdateExpenseShares. Si R∩H≠∅, U (référence créée après la version prétendument observée, intention non attestée) ; sinon C (ajout indépendant, sous contrats C1/C3/C4). Cette condition empêche de qualifier compatible une cible qui ne pouvait pas être connue à O sur la seule base de son existence à V. |

### F.1 Dépendances non couvertes par un type existant

Retrait de Shareholder : **NOT_APPLICABLE aux dix producteurs actuels** ; présence d’un champ deleted et d’une action kernel ne prouve pas un writer de retrait. Un futur retrait affecterait au minimum payer, bénéficiaires, cibles fiche/poids et droits d’un membre. Sans Event exprimant ID/lifecycle et sans analyse des dépendances historiques d’Expense, décision U ; aucun retrait silencieux ne peut conserver la grille actuelle.

Changement d’autorisations : SD peut modifier S→U et donc plusieurs relations. Le fence Binding concerne une autre autorité et n’est pas un BusinessEvent Pot. Les capacités traduites depuis l’enveloppe et les contrôles temporels restent ceux du protocole actuel ; la politique n’invente pas un flux historique de révocation de tokens. L’analyse historique et l’autorisation actuelle sont deux obligations cumulatives.

## G. Lacunes, impact et possibilités de fermeture

| Lacune | Impact sur l’acceptation ancienne | Fermeture minimale à examiner, sans décision de schéma |
|---|---|---|
| G1 SD ne distingue pas nom et userId, sans avant/après | U3, branche affectée de X4 et de X2 ; impossible de prouver qu’une relation personnelle a gardé son sens | Valider un rejet conservateur de toute modification de fiche pertinente, ou définir les faits Event minimaux : nature du changement, S et anciens/nouveaux liens. Ne pas déduire le passé du seul état courant. |
| G2 SA sans noms/identité sémantique | U1 pour ajout potentiellement doublonné | Décider si des occurrences distinctes sont toujours intentionnelles ; enrichissement éventuel seulement si une règle de non-doublon est voulue. |
| G3 SW sans valeurs/deltas ; intention poids non formalisée | U2 et X3 disjoint ; résultat relatif non démontré | Choisir poids bruts ou proportion et la surface de dépendance ; conserver refus conservateur ou exprimer les changements nécessaires. Pas de chiffre ni ratio inventé. |
| G4 EU/ES/EC/ED sans participants ni détail de mutation | Pas de compatibilité fine au sein d’une même Expense ni de suivi historique complet des relations affectées | X6 conservateur est décidable par E. Affiner demanderait champs modifiés et identités pertinentes, sans replay de snapshots. |
| G5 Historique complet/non purgé non attesté ; pas de manifeste multi-Event | Empêche tout verdict Compatible fondé sur une liste partielle | Démontrer le contrat writer/rétention pour l’intervalle et la cardinalité attendue. Une requête réussie ou metadata continue ne suffisent pas seules. |
| G6 Désérialisation shareholderIds manquant → Set vide ; pas de révision de contrat Event dédiée | Fausse disjonction d’IDs possible pour X1…X4/X7 | Validation stricte et contrat de décodage des données historiques ; ancien format non attesté → U. Pas d’enrichissement rétroactif fictif. |
| G7 Input ne porte ni anciennes valeurs, ni intention « supprimer malgré changements », ni objectif de solde | Arbitrages O3/X6 et indépendance C4 non prouvés fonctionnellement | Revue produit, sans modifier les payloads publics dans ce chantier. Une règle conservative peut rendre les Events existants suffisants pour les seuls cas autorisés. |

**À ne pas surinterpréter** : tous les Events n’ont pas besoin de snapshots complets. P/version/type/E ou S suffisent pour refuser conservativement de nombreuses collisions. Aucune décision ici n’exige de reconstruire les états intermédiaires ni de rejouer l’agrégat. Le blocage vise l’acceptation sûre de la politique complète, pas l’impossibilité de tout sous-ensemble.

## H. Proposition d’API métier et politique de résultat

**PROPOSED — contrat conceptuel seulement**, sans fichier Java ni nouveau moteur générique :

```text
assessObsolescence(
  typedCommandAndParameters,
  potId,
  observedVersion,
  workingVersion,
  businessHistoryInterval   // Events + bornes + attestation de complétude
) -> Compatible(evaluatedAtVersion, policyRevision)
   | Obsolete(reason, conflictingEventIds, conflictingVersions)
   | ImpossibleToDetermine(reason, missingEvidence)
```

Le paramètre `potId` est nécessaire aux commandes Expense qui ne le portent pas ; son ownership doit être résolu de manière sûre. L’intervalle atteste `(P,O,V]`, validité d’O, couverture des groupes et décodage ; une `List<Event>` nue ne peut pas prétendre cette complétude. L’API pure ne charge pas la base, n’autorise pas le caller, ne valide pas les montants courants et n’effectue ni CAS ni persistance. La complétude est un prérequis d’entrée vérifié explicitement, pas un booléen laissé au jugement du moteur Consumption.

Combinaison recommandée : une policy lisible par commande (dix dispatchs explicites, CreatePot hors politique) et quelques fonctions pures partagées : même Pot/intervalle, suppression Pot, collision ExpenseId, intersection des ShareholderIds, classification d’un groupe. Pas de DSL, graphe dynamique ou moteur de règles. Une version/revision de policy dans le diagnostic sert à expliquer la décision, sans nouveau contrat durable imposé ici.

Algorithme :

1. Vérifier type de commande, identité Pot, O/V et attestation d’intervalle ; anomalie → ImpossibleToDetermine.
2. O=V → Compatible historique, sans passer outre les préconditions courantes.
3. Grouper par version, valider tous les Events du groupe, appliquer les règles exactes ; ordre des Events d’un groupe sans effet sur la décision.
4. Agréger : preuve valide d’O → Obsolete avec témoins ; sinon au moins un U → ImpossibleToDetermine ; sinon Compatible. Conserver tous les diagnostics pertinents, déterministes par version puis ID. Une corruption/absence d’attestation est traitée à l’étape 1, pas neutralisée par un témoin O.
5. Sur Compatible seulement, contrôles métier/autorisation à V et préparation N ; l’orchestrateur existant porte la transaction et les autorités. Perte de CAS → rollback, pas Obsolete. Toute tentative réévaluée refait les étapes précédentes.

**Résultats conceptuels distincts** :

| Catégorie | Nature | Correspondance possible aux contrats existants, sans modification |
|---|---|---|
| OBSOLETE_COMMAND | Historique incompatible prouvé selon une règle approuvée | `CommandUseCaseResult.Rejected` → Outcome `REJECTED` et terminal Consumption `REJECTED`, sans mutation ni BusinessEvent Pot. Raison publique exacte à décider ; aucun nouveau code imposé. |
| BUSINESS_REJECTION | Précondition/autorisation non satisfaite à V | Chemin Rejected existant, code métier précis conservé. Exemple : membre absent à V, Pot déjà supprimé à O=V. |
| TECHNICAL_CONFLICT | CAS Pot perdu, fence Binding perdu ou autorité Consumption perdue | Exception hors transaction propriétaire → rollback confirmé avant handling. Pot doit avoir une classification technique explicite avant tout éventuel retry. LostClaim garde son traitement spécifique et n’est pas une failure retryable générique. |
| ImpossibleToDetermine | Preuve manquante, pas une incompatibilité métier établie | Aucun APPLIED, aucun faux OBSOLETE. Traduction opérationnelle à décider selon cause : attente/retry seulement si lacune transitoire démontrée ; donnée corrompue/rétention perdue ne devient pas compatible après plusieurs essais. Ne pas épuiser arbitrairement des retries ni fixer ici un nouveau code public. |

**PROVEN — écart CURRENT à ne pas masquer** : [AbstractPotCommandUseCaseAdapter](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/AbstractPotCommandUseCaseAdapter.java) attrape la même VersionConflictException pour version préalable et CAS/fermeture perdus, et construit Rejected si aucun Event n’a été collecté. Cela démontre une ambiguïté du résultat Java, pas qu’un rejet de CAS perdu peut réellement committer : l’adapter transactionnel peut avoir marqué rollback-only. La future implémentation doit distinguer les catégories avant le mapping public et revoir sa classification de retry ; ce chantier ne la modifie pas.

La politique n’avance ni ne recule le fence Binding, ne change ni Slot/Claim, ni l’ordre des verrous, ni Consumption. Elle ne définit aucune EventPublication et ne dépend pas d’une position de publication. Les propositions de l’étude consolidée restent séparées de ce contrat métier.

## I. Performances et index réels

**PROVEN — schéma source** : [V2](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V2__projection_outbox_tasks.sql) crée `business_event_outbox(id PK,event_type,pot_id,aggregate_id,version,payload_json TEXT,...)` et l’index **non unique** `idx_business_event_outbox_pot_version(pot_id,version)`. `pot_version` n’est pas le nom de colonne actuel. V15 canonise les dix types et pose leur CHECK ; le JSON est du **text**, pas un JSONB stocké/indexé. La version SQL du déploiement n’a pas été interrogée. Aucun benchmark/EXPLAIN exécuté.

Requête conceptuelle correcte de base (non exécutée) :

```sql
SELECT id, pot_id, version, event_type, aggregate_id, payload_json
FROM business_event_outbox
WHERE pot_id = :potId
  AND version > :observedVersion
  AND version <= :workingVersion
ORDER BY version, id;
```

Ne filtrer ni status/claim/processed_at, ni familles supposées déjà consommées. Un Event PENDING est déjà un fait métier committé. Ne pas utiliser les projections/LKV comme preuve de l’intégralité du primaire. Le suffixe `id` sert à une pagination stable et au diagnostic, pas à rejouer un ordre métier dans une version.

Coût : M lignes sur `(O,V]`, M=V−O pour les seuls writers courants et un historique normal un Event/version ; plusieurs Events autorisés structurellement donnent M=Σ cardinalité(version). Aucun maximum fixe n’est imposé au retard des commandes ni à la cardinalité SQL ; pas de borne générale inventée. Décodage des sets S proportionnel à leur nombre total d’IDs. Une commande très ancienne peut parcourir tout l’historique retenu et transporter de gros ensembles Shareholders ; N données métier mutées n’implique pas N Events.

Optimisations sûres à étudier après correction :

- Projection scalaire de métadonnées `id,P,version,type,aggregate_id`, sans charger une entité JPA complète ; les Events Expense/Pot simples se classent souvent ainsi. Lire/valider le payload SD/SA/SW pour les branches utilisant S et la cohérence du contrat. Pas de promesse d’index-only scan avec l’index actuel.
- Deux phases possibles : couverture non filtrée de tout l’intervalle, puis données nécessaires ; le lecteur doit garantir stabilité/rétention et identité de la même extraction entre phases. Ajouter un filtre ExpenseId seul dès la première phase éliminerait PD/SD et serait incorrect.
- Filtre type/objet uniquement après preuve que les types exclus sont C pour cette commande et après preuve indépendante de couverture/décodage. Une whitelist de types connus seule masque un type nouveau ; l’inconnu doit être détecté et refusé. Pas d’optimisation supprimant U3 sous prétexte que les IDs demandés diffèrent.
- Pagination par clé `(version,id)` sur bornes O/V figées ; ne jamais couper un groupe sans le compléter ni conclure sur un préfixe. L’index `(pot_id,version)` soutient la plage ; le tri secondaire ou un futur index adapté doivent être évalués sans migration dans ce chantier.
- `payload_json::jsonb` en WHERE pour intersecter S implique parsing du text, sans index JSONB existant ; préférer lire/décoder les quelques types concernés. Aucun besoin de joindre toutes les tables temporelles ou reconstruire des snapshots intermédiaires. Une comparaison de couverture avec `pot_version_metadata` (PK P/version) peut détecter des trous, mais pas un Event manquant dans un groupe multi-Event.
- Un budget de parcours peut retourner ImpossibleToDetermine/diagnostic ou imposer une nouvelle observation selon contrat produit ; atteindre une limite de lignes n’équivaut jamais à Compatible. La rétention et la durée de validité des commandes sont des décisions produit/ops non définies ici.

## J. Stratégie de tests à spécifier, sans exécution

Aucun test écrit, modifié ou exécuté ; le test R03 existant n’a pas été rejoué. Plan futur après validation des décisions :

| Famille / scénario | Preuve attendue |
|---|---|
| Policy pure, O=V | Compatible historique avec liste vide ; validation courante peut encore rejeter ; CreatePot ne demande pas O. |
| Ancienne commande et changements indépendants | Exemple UpdatePotDetails après ExpenseCreated : C ; exemple O5/V8 inclut exactement V6/V7/V8, aucune version extérieure. |
| Event incompatible | PD rejette les neuf modifications ; PU contre UpdatePotDetails donne O2 ; pas d’incrément ni Event Pot de refus. |
| Conditions Expense | Pour chacune des trois commandes sur Expense existante, E identique/autre E avec ED/EU/ES ; même E→O, autre E→C. Croiser explicitement Details↔Shares. EC même E→U ; EC autre E→C. |
| Conditions Shareholders | X1…X4 et X7 : ensembles identiques, partiels, disjoints, plusieurs cibles, payload absent/non array/vide invalide ; chaque branche C/O/U spécifiée. SD qui renomme versus change userId doit rester U lorsque non discernable. |
| Poids et allocations | X3 même S→O, S disjoint→U avant arbitrage ; SA/SW avec U2 ; aucune indépendance globale présumée ; mode absolu versus proportions à tester après décision. |
| Suppressions | PD dans intervalle→O ; Pot tombstone déjà à O=V→rejet courant. ED cible→O et autre E→C ; delete répété ne devient pas succès implicite ; DeletePot après tout type selon O3. |
| Historique invalide/incomplet | Trou de version, mauvaise borne/P, O futur ou non attesté, type inconnu, payload incohérent, cardinalité inattendue, rétention non attestée→ImpossibleToDetermine. Aucun appel de mutation. |
| Ordre et groupes | Extraction désordonnée détectée/normalisée avant certification ; ordre d’un groupe sans effet sur résultat ; tous les Events d’un groupe examinés, un conflit caché en fin de page empêche C. Métadonnée continue + sous-groupe partiel ne certifie pas la complétude. |
| Couverture matrice | Les 90 cases classées et leurs branches conditionnelles disposent de cas de contrat, les dix N/A de création d’un cas d’exception. Tester que U n’aboutit jamais à Compatible. |
| PostgreSQL ciblé : lecture à V | Un concurrent avance entre fixation de V et chargements ; tous les faits sont à V, aucun header courant mélangé, CAS ultérieur perd. Complète la preuve future du nouveau flux sans redéfinir R03. |
| PostgreSQL : CAS après analyse | A juge compatible O→V ; B committe V+1 ; A perd CAS, tous ses effets (y compris après flush provoqué) rollbackent. Aucun Outcome REJECTED métier durable dû à cette perte technique. |
| PostgreSQL : visibilité et exhaustivité | Un commit mutation+Event rend les deux visibles ; rollback ne laisse ni version ni Event ; lecture range inclut tous statuts et groupe complet ; contraintes/attestations de couverture utilisées réellement vérifiées. |
| PostgreSQL : autorisations | SD change les relations après O : contrôle à V ; détachement Binding pendant tentative : fence exact existant arbitre, aucun succès par la seule matrice. |
| PostgreSQL : autorités et rollback | Perte Claim, refus clôture, échec append/flush ou commit : absence d’effets partiels ; conserver les garanties Consumption existantes, sans nouvelle architecture. |
| Retry éventuel | Après rollback confirmé, V est relu, O conservé, tout `(O,Vnouveau]` réévalué ; un essai précédemment C peut devenir O. Aucun retry automatique sur COMMITTED/UNKNOWN. ImpossibleToDetermine permanent non traité comme conflit transitoire. |

Pour une future implémentation limitée au comportement Command : slice primaire **COMMAND**, commande canonique future `./mvnw -pl runtime-command-consumption-worker -am test`. Ce n’est pas une commande exécutée dans cette étude. BINDING ne serait secondaire que si son comportement change ; EVENT/WEB uniquement si leurs contrats/comportements changent réellement. Pas d’architecture-tests ni full reactor par défaut ; déclarer de nouveau le périmètre après choix exact de l’implémentation. Aucun changement de migrations n’est prescrit ici.

## K. Décisions architecturales, conclusion et traçabilité

| Sujet | Statut | Décision / action de revue |
|---|---|---|
| Distinguer O/V/N, analyser `(O,V]`, CAS sur V | PROPOSED — contrat demandé, cohérent avec autorités PROVEN | Adopter sans confondre avec la garantie déjà livrée d’égalité expectedVersion. |
| Création initiale sans O, version initiale 1 | PROVEN / NOT_APPLICABLE | Exception nécessaire à la généralisation « toute commande » ; aucune version zéro synthétique. |
| Préserver R03, Binding exact, Slot/Claim et rollback | PROVEN | Obligatoire ; pas de nouvelle architecture Consumption/publication. |
| Obsolescence ≠ précondition courante ≠ CAS perdu | PROPOSED, écart CURRENT PROVEN | Séparer à l’intérieur du traitement avant mapping vers les résultats existants. Codes publics inchangés ici. |
| Refus sur modifications même Expense, même fiche, même label | PROPOSED | Confirmer politique conservative ; pas d’acceptation de simple réécriture identique sans données. |
| DeletePot après n’importe quel changement ; DeleteExpense après changement cible | UNDECIDED | Valider l’intention destructive proposée O3/X6. Aucun droit de supprimer des changements non vus n’est déduit du code. |
| Commandes Expense avec paramètres explicites et pas d’objectif global | PROPOSED / UNDECIDED fonctionnel | Confirmer les indépendances C3/C4 ; ne pas confondre commutation technique et intention du client. |
| Poids bruts versus proportions ; ajouts distincts versus doublons sémantiques | UNDECIDED | Trancher U1/U2/X3 avant toute acceptation correspondante. |
| Même ShareholderId après changement de userId | GAP / UNDECIDED | Définir continuité de personne ; choisir refus conservateur ou faits Event permettant de conclure. |
| Complétude/rétention et groupes multi-Event | GAP | Démontrer sur l’intervalle ; pas d’acceptation à partir d’un résultat SQL partiel. |
| ImpossibleToDetermine, codes et ancienne sémantique expectedVersion | UNDECIDED | Fixer comportement opérationnel/public et migration de contrat, sans modifier schémas/codes ici. |
| Enrichissements Events et index éventuels | PROPOSED, non décidés | Seulement après choix des acceptations nécessaires ; aucune migration ni gain chiffré. |

**Prochaine étape minimale** : revue architecturale et fonctionnelle de C3/C4, O3/X6, U1/U2 et de la continuité Shareholder–User ; décider quelles branches demeurent impossibles à accepter, puis préciser la preuve de complétude et le traitement de l’indétermination. Cette revue peut rendre un sous-ensemble implémentable sans enrichir tous les Events, mais ne vaut pas autorisation d’un prototype COMMAND ou d’un changement de publication.

### Contrôles et livraison de cette étude

Commandes réellement utilisées : `pwd`, `git status --short` / `--porcelain=v1` / `--branch`, `git branch --show-current`, `git rev-parse HEAD`, `git worktree list`, `git log -6 --oneline`, `git remote -v`, `git fetch --no-tags origin` (première tentative sandbox refusée puis réussite), `git merge-base --is-ancestor 675533e7 HEAD`, lectures `cat`/`sed`, inventaires/recherches `rg`/`rg --files`, extraction statique et rédaction par Python, vérification locale des liens et de la matrice par Python, `git diff --check`, `git diff --stat` et `git diff --name-only`. Aucun programme de test ni commande Maven exécuté. Ces contrôles documentaires ne constituent pas une preuve PostgreSQL nouvelle.

Livrables stricts : ce rapport et une entrée dans l’index transactionnel canonique. Rapports historiques conservés. Aucun commit local créé : diff documentaire laissé pour revue. HEAD final identique au départ ; état attendu : ce nouveau rapport non suivi et l’index modifié uniquement. Aucun push, nettoyage destructif, merge, rebase ou reset. Slices déclarées/exécutées NONE ; franchissements inattendus NONE ; gate global NOT REQUIRED/non exécuté ; full reactor non exécuté (étude statique exclusivement).

**Verdict final : BLOCKED_BY_EVENT_GAPS.**

---

## L. Chantier A.2 — Révision par conflits d’écriture (18 octobre 2026)

**Révision de référence : L à V ci-dessous. Verdict : READY_WITH_DECISIONS. TARGET NON IMPLÉMENTÉ.** Les sections A à K, leur matrice et leur verdict `BLOCKED_BY_EVENT_GAPS` sont conservés comme conclusions historiques du chantier A. Elles ne gouvernent plus la politique cible lorsqu’elles protègent un état lu plutôt qu’une composante écrite. Cette révision ne change aucun runtime, SQL, test, protocole Consumption ou EventPublication.

### L.1 Base réelle et méthode

Le checkout initial est `feat/runtime-monolith` à `90926a5e`, avec travaux préexistants ; il reste intact. L’analyse et les seules éditions se font dans le worktree déjà existant `/private/tmp/pocoma-consumption-transaction-protocol`, branche **`feat/consumption-transaction-protocol`**, HEAD **`62a57c780368ea3b46e6d1ae2ffb3031858a6a5a`**. Le correctif `675533e7` est ancêtre de ce HEAD. À l’entrée A.2, le présent rapport est **non suivi** et l’index `transactional-protocol-studies.md` est déjà modifié par A. Une sauvegarde des deux documents et des empreintes des fichiers préexistants a été prise avant édition dans `/private/tmp/pocoma-a2-preservation-20261018`. Aucun checkout de branche, fetch, reset, clean, merge, rebase, push ou commit durant A.2.

La politique normative de vérification a été lue ; sa copie du worktree cible est identique à celle du checkout initial. **Périmètre déclaré avant édition : documentation seule ; impact production NONE ; slices primaire et secondaires NONE ; global gate NOT REQUIRED ; full reactor NOT REQUIRED ; base NONE.** La lecture statique porte sur COMMAND, les contrats BusinessEvent et R03. Aucun fichier sous `app/` n’est édité. Les preuves de tests R03 citées en A/B restent historiques, sans nouvelle exécution.

### L.2 Décision fonctionnelle appliquée

Une commande protège ses **objets ou composantes à modifier**, pas tout l’état observé à O. Pour chaque Event de `(O,V]`, on compare les écritures par identité et composante. Une intersection de composante remplacée est un conflit ; une destruction de la cible invalide toutes ses composantes. Une lecture nécessaire à l’autorisation ou à une précondition n’élargit pas automatiquement le périmètre d’écriture.

L’unité de détails est celle du contrat réel : `ExpenseDetails` remplace ensemble payer, montant, label et date ; `ShareholderDetails` remplace ensemble nom et lien User ; `PotDetails` remplace le label. Aucun patch par champ, objectif de solde, préservation des proportions globales, identité personnelle implicite ou déduplication métier n’est ajouté. Une écriture concurrente de la même composante est rejetée même si les valeurs sont égales : la politique détecte une écriture, sans comparer des valeurs avant/après.

Deux questions restent séparées : **historique compatible ?**, puis **exécution autorisée et métier valide à V ?** Un lien Shareholder–User changé ne rend pas automatiquement une Expense obsolète ; il peut retirer au caller son autorisation à V. Une référence à un Shareholder ajouté après O ne constitue pas à elle seule une incohérence de la version Pot O : O ne certifie plus que toutes les références étaient déjà présentes à O. Si la commande veut modifier les composantes de ce nouveau Shareholder, sa création est en revanche une écriture concurrente sur ces composantes.

## M. Périmètres d’écriture des dix commandes

P=PotId, E=ExpenseId, T=ensemble des ShareholderIds ciblés par une mise à jour. Les chemins sont **métier**, pas des lignes SQL. Fermer/réinsérer une ligne Shareholder ne signifie pas écrire ses poids quand seuls ses détails sont remplacés. De même, la recopie du flag deleted dans un header ne constitue pas une écriture de lifecycle. Le CAS et les metadata version sont communs à toute mutation : les inclure dans les conflits métier recréerait artificiellement le rejet de toute commande ancienne.

| Commande | Périmètre exact | Remplacement / modification et relations concernées |
|---|---|---|
| CreatePot | Nouvelle identité P : `PotDetails(P)`, `PotLifecycle(P)` et relation creator fixée à la création | Insertion initiale V1, aucune version Pot préalable. Pas de comparaison historique. |
| UpdatePotDetails | `PotDetails(P)` | Remplacement du label seulement ; lifecycle lu et préservé. |
| DeletePot | `PotLifecycle(P)` avec destruction logique de tout le périmètre P | Le tombstone interdit toutes les mutations du Pot et de ses enfants. Pour le conflit destructif, portée `PotTree(P)` : détails, membres, relations et Expenses, même sans cascade SQL. |
| AddPotShareholders | Nouvelles entrées de `Shareholders(P)` et, pour chaque nouvel S, Details/Weights/Lifecycle et appartenance S→P | Ajout relatif ; ne remplace pas les membres existants, ni leurs poids, ni la collection entière. IDs générés lors de la préparation, userId initial null. |
| UpdatePotShareholdersDetails | `ShareholderDetails(P,S)` pour S∈T | Remplace nom et S→User (nullable) de chaque cible ; poids, appartenance et lifecycle préservés. Ni remplacement de tous les membres, ni écriture des Expenses liées. |
| UpdatePotShareholdersWeights | `ShareholderWeights(P,S)` pour S∈T | Remplace les poids fournis des seules cibles ; aucun changement de nom, User ou autres poids. Les proportions calculées peuvent évoluer sans écriture des autres membres. |
| CreateExpense | Nouvelle entrée de `Expenses(P)` ; `ExpenseDetails(P,E)`, `ExpenseShares(P,E)` et `ExpenseLifecycle(P,E)` | Crée une identité E générée et ses relations payer/bénéficiaires→S ; n’écrit ni les fiches/poids S, ni les autres Expenses. |
| UpdateExpenseDetails | `ExpenseDetails(P,E)` | Remplace payer, montant, label et date ensemble, donc la relation payer→S. Préserve toutes les shares et le lifecycle. |
| UpdateExpenseShares | `ExpenseShares(P,E)` | Remplace **tout** le jeu des bénéficiaires et de leurs poids ; deux remplacements de shares du même E se heurtent même avec des bénéficiaires disjoints. Préserve payer, montant, label, date et lifecycle. |
| DeleteExpense | `ExpenseLifecycle(P,E)` avec destruction logique de l’objet E | Pour le conflit destructif, portée `ExpenseObject(P,E)` incluant Details et Shares. Les parts historiques ne sont pas physiquement supprimées ; aucune autre Expense n’est détruite. |

**Sources relues pour A.2** : inventaire C et sources C.4 ; [PotShareholders](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/PotShareholders.java) (mises à jour partielles, génération UUID), [ExpenseShares](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/ExpenseShares.java) (remplacement et fusion des poids par S), [ExpenseFactory](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/factory/ExpenseFactory.java) (UUID E), services `UpdatePotShareholdersWeightsService`, `UpdateExpenseDetailsService`, `UpdateExpenseSharesService`, création et suppression de C.4. Les dépendances financières croisées Details/Shares changent le résultat calculé, mais ne remplacent pas la composante complémentaire. La nouvelle décision retire précisément leur protection historique implicite.

**Suppressions** : la matrice ci-dessous traite la suppression comme destructive pour toutes les modifications de l’objet détruit, dans les deux sens. `DeleteExpense(E)` entre donc en conflit avec EU/ES du même E ; ED(E) invalide toute modification de E. `DeletePot(P)` entre en conflit avec toute mutation de P ; PD invalide toutes les mutations de P. C’est la portée destructive retenue pour rendre la politique explicite, pas une règle d’unicité ou une extension de la cascade de persistance. Sa confirmation fonctionnelle est une décision résiduelle avant implémentation ; si « supprimer malgré toutes les modifications concurrentes » était souhaité, les lignes Delete devraient être revues. Aucune telle exception n’est présumée ici.

## N. Matrice A.2 complète

Les dix colonnes sont les **types réels** inventoriés en D : PC=`POT_CREATED`, PD=`POT_DELETED`, PU=`POT_DETAILS_UPDATED`, SA=`POT_SHAREHOLDERS_ADDED`, SD=`POT_SHAREHOLDERS_DETAILS_UPDATED`, SW=`POT_SHAREHOLDERS_WEIGHTS_UPDATED`, EC=`EXPENSE_CREATED`, ED=`EXPENSE_DELETED`, EU=`EXPENSE_DETAILS_UPDATED`, ES=`EXPENSE_SHARES_UPDATED`.

Légende : C=`COMPATIBLE`, O=`OBSOLETE`, X=`CONDITIONAL`, U=`UNDETERMINED`, N/A=`NOT_APPLICABLE`. Xs et Xe sont les règles exactes de O ci-dessous. La matrice suppose un Event valide du **même P**, situé dans un intervalle complet `(O,V]`, avec 1≤O≤V. Ces conditions sont des conditions d’entrée, pas des acceptations implicites des historiques malformés.

| Commande | PC | PD | PU | SA | SD | SW | EC | ED | EU | ES |
|---|---|---|---|---|---|---|---|---|---|---|
| CreatePot | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| UpdatePotDetails | U | O | O | C | C | C | C | C | C | C |
| DeletePot | U | O | O | O | O | O | O | O | O | O |
| AddPotShareholders | U | O | C | C | C | C | C | C | C | C |
| UpdatePotShareholdersDetails | U | O | C | Xs | Xs | C | C | C | C | C |
| UpdatePotShareholdersWeights | U | O | C | Xs | C | Xs | C | C | C | C |
| CreateExpense | U | O | C | C | C | C | C | C | C | C |
| UpdateExpenseDetails | U | O | C | C | C | C | Xe | Xe | Xe | C |
| UpdateExpenseShares | U | O | C | C | C | C | Xe | Xe | C | Xe |
| DeleteExpense | U | O | C | C | C | C | Xe | Xe | Xe | Xe |

**Totaux A.2 : 49 COMPATIBLE, 18 OBSOLETE, 14 CONDITIONAL, 9 UNDETERMINED, 10 NOT_APPLICABLE = 100.** Les quatorze conditions se résolvent en C/O sur des données valides ; des données insuffisantes donnent U et un refus conservateur. Les neuf U sont les anomalies PC ; ils ne sont pas convertis artificiellement en C ou en N/A.

## O. Règles conditionnelles et exceptions exactes

| Règle | Évaluation conceptuellement exécutable |
|---|---|
| Xs | T=`command.shareholderIds`, H=`event.shareholderIds`. Si H absent, invalide ou vide non justifié : U. Sinon `T ∩ H ≠ ∅ → O`, `T ∩ H = ∅ → C`. Appliquée à SD contre Details, SW contre Weights, et SA contre l’une ou l’autre (SA crée les deux composantes). Une cible conflictuelle suffit à rendre toute la commande obsolète ; aucune application partielle. |
| Xe | E=`command.expenseId`, H=`event.expenseId`. Si H invalide/absent : U. Sinon `E = H → O`, `E ≠ H → C`, uniquement pour les cases Xe de N. EC crée Details/Shares/Lifecycle ; ED détruit toutes ces composantes ; EU ne touche que Details, ES que Shares. DeleteExpense cible l’objet entier. |
| PC anormal | Pour une commande existante, PC à V1 ne peut être dans `(O,V]` avec O≥1 sur ce même P. Sa présence donne U : incohérence de bornes, de référence ou d’historique, aucune acceptation. Pour CreatePot, l’évaluation est N/A. |
| PD | Un PD valide du même P dans l’intervalle donne O pour les neuf mutations, y compris DeletePot. Un tombstone déjà présent à O=V relève du refus métier courant. |
| PU | Contre UpdatePotDetails ou DeletePot : O. Toute autre ligne est C, car elle conserve les détails Pot. |
| C croisés | SD↔Weights, SW↔Details, EU↔Shares, ES↔Details : C même avec le même ID, car la composante modifiée est conservée. Aucun test sur la valeur, le caller, l’ancien payer ou les anciens bénéficiaires dans cette décision historique. |

Les autres C suivent les périmètres M : une mutation Expense ne remplace aucun Shareholder ou label Pot ; une mutation de fiche/poids S ne remplace aucune Expense ; créer une autre entrée ne remplace pas toute la collection. Les relations nécessaires demeurent contrôlées à V (§R). Pour SD sur un S dont le caller perd le lien User, la politique peut rendre C et l’autorisation rendre refus : les résultats ne se contredisent pas.

### O.1 Créations, collisions et doublons

`AddPotShareholders` et `CreateExpense` ne portent pas d’ID de nouvel objet dans les commandes réelles ; les IDs sont générés après l’analyse historique. SA/EC antérieurs ne sont donc pas des remplacements des futures entrées créées : les C de leurs lignes ne supposent pas une comparaison avec des IDs absents de la commande. La nouveauté des IDs reste une obligation d’insertion à V : **une collision UUID ne doit jamais écraser une entrée existante ni être traitée comme un doublon réussi**. `PotShareholders.addShareholder` utilise actuellement `Map.put` sans garde de collision ; la génération aléatoire seule n’est pas une preuve absolue d’unicité. La future implémentation devra préserver l’insertion seule et classifier un échec de collision avec rollback ; cette étude ne corrige pas ce point. Si un contrat futur admettait des IDs imposés par le client, une création concurrente au même ID deviendrait un conflit ; cela n’est pas le contrat des dix commandes actuelles.

Deux Shareholders de même nom avec IDs distincts ne sont pas interdits par le domaine actuel. Deux Expenses au même contenu avec E distincts ne sont pas dédupliquées sémantiquement. La politique A.2 peut donc accepter ces ajouts malgré SA/SD/SW ou EC intermédiaires ; inventer une unicité par nom, personne ou montant ajouterait une règle fonctionnelle. Le `Set` d’inputs élimine les éléments strictement égaux dans une même commande ; ce n’est pas une déduplication entre commandes. `ExpenseShares` fusionne les poids des entrées d’un même S ; ce comportement ne rend pas deux remplacements concurrents compatibles. La protection Consumption d’une même commande et les règles de retry restent distinctes : une nouvelle commande au contenu identique peut créer un nouvel objet ; aucun succès implicite d’un delete répété n’est introduit.

EC du **même E** après O pour une commande sur E passe de l’ancien X5 (branche U) à Xe (O). SA intersectant T pour une modification S passe de X7 (branche U) à Xs (O). La création a bien écrit la composante ciblée depuis O ; il n’est pas nécessaire d’ajouter une exigence « cette cible existait à O » pour refuser son écrasement. À l’inverse, SA intersectant les seules références payer/bénéficiaires d’une Expense est C : aucune composante S n’y est modifiée, et l’existence/l’appartenance se valident à V.

## P. Suffisance des dix BusinessEvents

Tous les types portent P et version de mutation ; le type est stable dans le catalogue actuel. Les colonnes d’enveloppe `pot_id`, `version`, `event_type`, `aggregate_id` et les IDs Shareholder du JSON forment les faits minimaux. L’ID Event sert au diagnostic et à la pagination stable. Le type suffit à déterminer la composante et la nature créatrice/destructive ; aucun booléen nouveau ni valeur avant/après n’est nécessaire.

| Type | Objet / composante identifiables avec les données actuelles | Nature et suffisance pour A.2 |
|---|---|---|
| PC | P ; détails/lifecycle initiaux et création du Pot | Créateur, suffisant pour détecter l’anomalie d’intervalle. |
| PD | P ; destruction logique de `PotTree(P)` | Destructif, suffit sans liste des enfants ni motif. |
| PU | P ; PotDetails | Remplacement, suffit sans ancien/nouveau label. |
| SA | P et Set<S> ; nouvelles entrées et Details/Weights/Lifecycle de ces S | Créateur, suffit si la liste des IDs est fidèle et complète. |
| SD | P et Set<S> ; ShareholderDetails (nom et lien User) | Remplacement, suffit sans distinguer renommage et changement User. |
| SW | P et Set<S> ; ShareholderWeights | Remplacement, suffit sans valeurs ni proportions. |
| EC | P et E ; Details/Shares/Lifecycle du nouvel E | Créateur, suffit sans participants, montant ni parts. |
| ED | P et E ; destruction de `ExpenseObject(P,E)` | Destructif, suffit sans ancien payer/bénéficiaires. |
| EU | P et E ; ExpenseDetails | Remplacement, suffit sans distinguer payer/montant/label/date. |
| ES | P et E ; ExpenseShares (jeu complet) | Remplacement, suffit sans IDs des bénéficiaires ni poids. |

**Informations réellement manquantes ou non attestées** :

- Pour une occurrence historique SA/SD/SW dont `shareholderIds` est absent, malformé ou injustifiablement vide, les cibles manquent réellement. Les records acceptent le vide et le mapper lu en D transforme l’absence/non-array en vide : **lacune de décodage/qualité des données**, pas besoin d’enrichir les Events valides. Un lecteur historique ne doit pas utiliser ce vide pour prouver T∩H=∅. Refus conservateur U, sans APPLIED ni fausse qualification OBSOLETE. Aucun Event modifié ou enrichi ici.
- Pour un catalogue/type futur, une sémantique de composante non attestée ou un payload incohérent, l’empreinte de mutation n’est pas démontrée : U et refus conservateur. Aucun retrait Shareholder ou changement de propriétaire Expense n’est livré dans le catalogue examiné ; on ne les invente pas comme Events supplémentaires. Un futur retrait devra porter son ID et sa destruction, puis réexaminer les préconditions de relations.
- La complétude/rétention de l’intervalle et la fidélité de tous les writers restent à prouver (§Q). Ce sont des garanties d’historique, **pas des champs métier manquants dans les dix payloads valides**.

Les anciennes lacunes G1/G2/G3/G4 et la partie fonctionnelle G7 ne sont plus des exigences de payload pour A.2 : ancien/nouveau User, noms, poids, participants et état complet ne sont nécessaires ni à Xs ni à Xe. On conserve le blocage G5 (preuve historique) et G6 (décodage strict), en les nommant précisément. La politique ne sait pas autoriser une compatibilité plus fine au sein de Details ; elle n’en a pas besoin, puisque la commande remplace toute cette composante.

## Q. Complétude historique : preuve restante distincte du payload

D.1/D.2 restent applicables : les dix services émettent **un BusinessEvent par mutation réussie**, même lorsqu’ils modifient plusieurs lignes ; append et mutation relèvent de la transaction Command. La structure autorise plusieurs Events par version sans garantie SQL de cardinalité/exhaustivité. Atomicité d’une liste appendée et continuité des metadata ne prouvent pas seules que cette liste décrit toute mutation.

Avant toute acceptation ancienne, il faut une preuve bornée aux writers et à la période concernés :

1. O est une version valide du bon P ; V est fixé une seule fois au primaire et O≤V. Aucun Event d’un autre Pot ou hors intervalle ne sert de preuve.
2. Lecture intégrale de `(O,V]`, sans filtre sur statut de consommation/publication, depuis le primaire ; un Event PENDING committé compte. Pagination complète par `(version,id)`, bornes figées, aucun groupe tronqué ni budget de lignes confondu avec la fin de l’historique.
3. Contrat des writers autorisés sur **tout** l’intervalle : toute mutation pertinente avance V et émet le ou les Events fidèles dans la même transaction. Les dix services actuels permettent un contrat borné un Event/version ; vérifier la couverture de toutes les versions O+1…V, le type, les identités et la cardinalité attendue. Une cardinalité inattendue ne se déduplique pas silencieusement.
4. Absence de purge/réécriture des Events concernés, intégrité des IDs et conservation du passé temporel. Aucun accès SQL déployé ni garantie opérationnelle universelle n’est démontré par cette lecture statique. Les contraintes existantes ne fournissent pas à elles seules ces preuves.
5. Pour tout producteur multi-Event autorisé, prouver l’exhaustivité de chaque groupe autrement que par « au moins un Event à chaque version ». Aucun manifeste, migration ou protocole de publication n’est prescrit ici.

Historique manquant, inconnu ou illisible ⇒ `Undetermined` et refus conservateur. Une liste vide avec O<V ne donne jamais Compatible. Une absence d’attestation ne devient pas un conflit métier prouvé, et un retry aveugle ne répare pas une rétention perdue. O=V ne nécessite aucun Event intermédiaire mais conserve la validation de la référence/version et les contrôles courants. Les neuf PC anormaux de la matrice et ces anomalies transversales restent indéterminés ; aucun effort n’est fait pour les convertir artificiellement en acceptations.

## R. Validations courantes et conséquences fonctionnelles

Les règles existantes C.1/C.3 sont conservées à **V**, avec leurs codes métier : Pot et Expense vivants, propriété stable E→P, Shareholders cibles présents et non supprimés, nouveau payer et bénéficiaires membres du bon P, entrées/poids/fractions/montants valides, ensembles requis non vides. Les capacités et relations d’autorisation (creator, membre, User de chaque S ciblé), le Binding exact et son fence restent obligatoires. La compatibilité historique ne donne aucun droit supplémentaire. Elle ne remplace pas non plus les validations d’inputs ou les contraintes d’insertion.

Exemples discriminants :

- SD change le User d’un S : `UpdateExpenseShares` peut être historiquement C mais le caller n’est plus membre à V ⇒ refus d’autorisation. `UpdatePotShareholdersWeights` peut être C sur ce même S, sous contrôle creator à V.
- SA ajoute le futur payer/référencé après O : `CreateExpense` reste historiquement C, puis vérifie présence et appartenance à V. Une référence absente du contexte V reste un refus métier, jamais une raison d’inventer un conflit avec une fiche non écrite.
- EU change le montant : `UpdateExpenseShares` est C, puis répartit le montant à V. ES change les bénéficiaires : `UpdateExpenseDetails` est C, puis applique le nouveau header avec les shares à V. Le résultat financier peut différer de celui calculé à O ; c’est une conséquence explicite du périmètre d’écriture décidé.
- SW modifie d’autres poids ou SA ajoute un membre : une mise à jour des poids de T ne protège pas les proportions de tous les membres. Aucun recalcul automatique des ExpenseShares n’est livré.
- ED(E) dans l’intervalle invalide une modification de E : O. E déjà supprimée et O=V : historique vide compatible puis refus métier. Deux deletes successifs ne sont pas un succès idempotent introduit par A.2.
- Deux remplacements Details ou deux remplacements Shares du même E sont O, même avec valeurs identiques ou listes de bénéficiaires disjointes. Deux suppressions ou une suppression face à toute modification de la même cible sont refusées selon la portée destructive M.

Les acceptations supplémentaires portent sur des composantes préservées et des références réévaluées ; elles peuvent produire des doublons sémantiques déjà autorisés par le domaine. Toute compatibilité reste soumise à Q. Aucune nouvelle validation « absence de doublon par nom/personne/contenu », aucun retrait membre implicite ni réallocation globale ne découle de cette étude.

## S. Contrat O/V/N et cohérence R03

Contrat conservé : `observedVersion=O` immuable depuis l’émission ; `workingVersion=V` dernière version primaire choisie pour l’essai ; `nextVersion=N=V+1`. O ne devient jamais la valeur attendue du CAS quand O<V. CreatePot reste une insertion V1 hors de ce protocole d’ancienne commande. O futur/invalide ou overflow N : aucun effet, diagnostic distinct d’obsolescence prouvée.

Séquence cible, sans modification du protocole transactionnel livré :

1. Identifier P (lookup Expense de propriétaire seulement si nécessaire), charger et fixer V.
2. Lire les BusinessEvents de `(O,V]` et établir leur complétude selon Q.
3. Évaluer les conflits d’écriture selon M/N/O.
4. Charger le contexte métier **explicitement à V**, y compris les agrégats à modifier et les relations d’autorisation. Un contexte préchargé ne peut être réutilisé que s’il certifie V ; aucun appel « current » ne peut choisir V′.
5. Valider les préconditions métier courantes et les autorisations à V.
6. Préparer les effets à N depuis ces faits à V, sans effets externes irréversibles.
7. CAS **V→N**, puis persistance des effets/Events et finalisation sous le propriétaire transactionnel existant.
8. Si CAS perdu : rollback intégral de l’essai ; aucun résultat métier appliqué ou rejet d’obsolescence ne doit durablement représenter cette perte technique.

**R03 vérifié statiquement** dans [JpaExpenseContextAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/JpaExpenseContextAdapter.java) : `findCurrentActive` n’en extrait que P ; après lecture de V, le header Expense, le header Pot et les relations S→User sont relus à V ; la propriété stable est contrôlée. Les services Details/Shares relisent également leur agrégat à `currentVersion.version()`. Les writers append/close à W>V conservent les faits temporels à V sous READ COMMITTED. Un concurrent entre deux lectures ne remplace donc pas un lifecycle à V par un lifecycle current à W. Les tombstones restent des faits à V, puis les préconditions refusent l’objet supprimé.

**Limite à préserver dans la future API** : les ports de contexte actuels choisissent V eux-mêmes. R03 prouve la cohérence interne de leur contexte, pas l’existence déjà livrée d’un nouveau flux « lire V → historique → charger à ce V ». Il faudra un chargement lié au V fixé ou réutiliser un contexte certifié à ce V ; un deuxième choix de V recréerait l’incohérence. R03 ne protège pas contre les réécritures SQL rétroactives et ne prouve pas Q.

Retry après CAS perdu : attendre le rollback confirmé, relire Vnouveau, relire et analyser **tout `(O,Vnouveau]`**, recharger et revalider à Vnouveau, repréparer Nnouveau. Ne réutiliser ni verdict C, ni autorisations, ni candidat d’ID sans revalidation d’insertion. Le traitement existant Binding/Claim et la distinction commit confirmé/inconnu restent applicables. Le défaut de classification Java documenté en H (VersionConflict préalable versus CAS) reste une tâche future à délimiter, sans modification ici.

## T. Comparaison avec la matrice A

| Classification | A historique | A.2 | Écart |
|---|---:|---:|---:|
| COMPATIBLE | 35 | 49 | +14 |
| OBSOLETE | 18 | 18 | 0 |
| CONDITIONAL | 21 | 14 | −7 |
| UNDETERMINED | 16 | 9 | −7 |
| NOT_APPLICABLE | 10 | 10 | 0 |
| Total | 100 | 100 | 0 |

Les **15 cases reclassées** se répartissent exactement en 8 X→C, 6 U→C et 1 U→X ; 85 conservent leur classe. Certaines conditions conservées changent de branches : le total ne suffit pas à décrire le changement.

| Reclassement | Intersections exactes et raison |
|---|---|
| 8 X→C | Details S×SW et Weights S×SD (2) ; CreateExpense×SA/SD (2) ; UpdateExpenseDetails×SA/ES (2) ; UpdateExpenseShares×SA/EU (2). Composante complémentaire préservée ou simple relation lue, validée à V. |
| 6 U→C | AddPotShareholders×SA/SD/SW (3) ; UpdateExpenseDetails, UpdateExpenseShares et DeleteExpense×SD (3). Ajout d’identités nouvelles ou droits/liens lus : les valeurs personnelles et financières historiques ne sont plus requises pour ce périmètre. |
| 1 U→X | Weights S×SA : l’intersection de T avec les nouveaux IDs décide le conflit sur les poids créés ; la disjonction est C, sans objectif implicite de proportions. |
| X conservés, branches revues | Weights S×SW : intersection O, disjonction désormais C au lieu de U. Details S×SA : intersection désormais O au lieu de U, disjonction C. Les trois commandes sur Expense existante×EC gardent X mais leur branche même E devient O au lieu de U ; leurs cases ED restent X. DeleteExpense×EU/ES et Details×EU / Shares×ES restent Xe. |
| O conservés | PotDetails×PU ; PD pour les neuf mutations ; DeletePot contre toute mutation. La portée destructive est explicitée et reste à confirmer, sans acceptation silencieuse. |
| U conservés | Neuf PC anormaux dans un intervalle d’une commande existante. Aucune donnée valide n’a été inventée pour les classer C. |

Le changement de verdict ne vient pas d’Events enrichis : la question posée aux Events est devenue « quelle composante de quel objet a été écrite ? ». Les anciennes exigences sur le sens d’une allocation ou la personne derrière un S ne doivent plus être réintroduites indirectement comme conflits de lecture. Les défauts de preuve d’historique et les questions de livraison du contrat restent ouverts.

## U. API métier simple et plan ciblé de tests (aucune exécution)

Proposition pure, sans moteur générique, sans fichier Java ajouté :

```text
assessWriteConflicts(commandIntent, potId, observedVersion, workingVersion,
                     certifiedBusinessHistory)
  -> Compatible(evaluatedAtVersion)
   | Obsolete(rule, conflictingEventIds, objectIds, components)
   | Undetermined(reason, affectedVersionsOrEvents)
   | NotApplicable                      // CreatePot uniquement
```

`commandIntent` est un type parmi les dix commandes réelles avec ses identifiants/paramètres ; aucune ancienne valeur n’est nécessaire. `certifiedBusinessHistory` porte P, bornes O/V, catalogue reconnu, Events complets et preuve de couverture/rétention applicable. Ce n’est pas un simple booléen arbitraire ajouté par l’appelant. La manière de fournir cette preuve reste à spécifier. Une petite table explicite type→composante plus dix dispatchs lisibles suffit ; Xs et Xe sont des fonctions pures partagées. Les IDs générés des créations sont vérifiés lors de la préparation/insertion, pas inventés dans l’intention reçue.

Algorithme : CreatePot→N/A ; vérifier référence/bornes et certificat ; O=V→C historique ; décoder strictement tous les Events ; union des empreintes par version ; appliquer N/O ; conflit prouvé→O, doute non résolu→U, sinon C. Une corruption ou une absence de preuve d’exhaustivité est signalée avant de produire un verdict métier ordinaire. Ordre des Events au sein d’une version sans importance. C seul autorise la poursuite vers `loadContextAt(V)` puis `validateCurrentBusinessRules(contextAtV, command, caller)` ; ces appels sont une proposition de contrat, pas des méthodes déjà existantes. Aucun fence ni nouvelle transaction dans la policy.

Ne pas fusionner les sorties : OBSOLETE_COMMAND = conflit historique démontré ; BUSINESS_REJECTION = règle/autorisation courante ; TECHNICAL_CONFLICT = CAS/fence perdu avec rollback ; UNDETERMINED = absence de preuve, refus conservateur sans prétendre à un conflit démontré. Le mapping public/opérationnel de cette dernière sortie reste à décider ; défaut permanent et indisponibilité transitoire ne méritent pas le même retry.

Plan de tests **à écrire et exécuter seulement dans un chantier ultérieur autorisé** :

| Cible future | Cas et assertion |
|---|---|
| Matrice pure | 100 intersections ; pour chaque Xs : T/H identiques, disjoints et intersection partielle ; pour chaque Xe : même/autre E. Une cible conflictuelle invalide la commande entière. |
| Composantes croisées | Même S Details↔Weights et même E Details↔Shares donnent C ; même composante donne O même si valeurs égales ; remplacements de shares disjoints donnent O sur même E. |
| Créations/références | SA même S cible Details/Weights→O ; SA même S seulement référencé par Expense→C ; EC même E cible→O ; autres IDs→C. Pas d’exigence supplémentaire d’existence des seules références à O. |
| Ajouts/doublons | Deux commandes distinctes identiques créent des identités distinctes selon le domaine ; doublons intra-Set et fusion des shares respectent le code actuel. Injection contrôlée d’une collision d’ID : aucune entrée existante écrasée, aucun commit partiel. |
| Suppressions | PD invalide les neuf mutations ; DeletePot contre tous les types mutateurs→O ; ED même E contre Details/Shares/Delete→O, autre E→C ; DeleteExpense contre EU/ES même E→O. Tombstone déjà visible à O=V→rejet courant, pas obsolescence historique. |
| Préconditions et droits | Historique SD compatible puis caller détaché/refusé à V ; payer/bénéficiaire absent ou mauvais Pot→refus métier ; relation valide nouvellement créée→éligible à V. Aucune autorisation par la seule matrice. |
| Intervalle | O5/V8 inclut exactement 6,7,8 ; O=V vide ; O>V/invalide, PC anormal, mauvais P, overflow→aucune mutation avec diagnostic adéquat. |
| Payload | SA/SD/SW manquant/non-array/vide injustifié, UUID invalide, redondances incohérentes, type inconnu→U conservateur ; jamais faux ensemble disjoint. |
| Exhaustivité | Trou, rétention non attestée, limite atteinte, page/groupe incomplet, cardinalité inattendue→U. Tous les statuts inclus, conflit sur dernière page non masqué. Métadonnées seules insuffisantes pour multi-Event. |
| Cohérence PostgreSQL future | Concurrent après fixation de V : tous les chargements restent à V, y compris après historique ; préserver les cas R03 lifecycle/header/shares et propriété E→P. Aucune relecture current à V′. |
| CAS et retry futurs | Concurrent commit V+1 après analyse C : CAS V perdu, rollback de tous les effets/Events/Outcome, puis nouvelle validation à V+1 pouvant rendre O. Claim/Binding conservent leurs fences ; aucun retry automatique d’un commit inconnu. |
| Transaction/historique futurs | Mutation et Event visibles ensemble ; rollback ne laisse ni version ni fragment d’historique ; exercer la preuve de couverture choisie sur le vrai lecteur, sans modifier EventPublication. |

La future slice attendue serait **COMMAND**, commande canonique `./mvnw -pl runtime-command-consumption-worker -am test`, à déclarer de nouveau selon le diff futur. Cette commande est **uniquement un plan**, non exécutée. Aucune slice EVENT/BINDING, aucun gate architecture global, full reactor ou migration n’est ajouté par anticipation.

## V. Verdict A.2, décisions restantes et livraison

**READY_WITH_DECISIONS.** Les dix types valides possèdent les informations minimales pour détecter les conflits à la granularité des dix commandes. L’ancien verdict `BLOCKED_BY_EVENT_GAPS` n’est donc plus justifié par l’absence de valeurs avant/après, participants ou liens User historiques. Cela ne vaut ni autorisation d’implémenter dans ce chantier, ni preuve que tout historique déployé est complet.

Décisions et preuves restantes, sans inventer de règle métier supplémentaire :

1. Confirmer la portée destructive symétrique retenue en M (objet Expense entier ; ensemble des mutations du Pot pour DeletePot). La matrice affiche le refus conservateur retenu ; aucune exception « supprimer malgré changements » n’est déduite.
2. Définir et démontrer la certification de l’intervalle complet pour les writers réellement autorisés et la rétention concernée (§Q), ainsi que le décodage strict des IDs historiques. Sans cette preuve, les anciennes commandes affectées sont refusées conservativement ; aucun enrichissement automatique des Events n’est proposé.
3. Arrêter le mapping de `Undetermined` et la compatibilité du contrat durable/public `expectedVersion` avec la sémantique `observedVersion`. La politique de retry CAS doit conserver O et refaire toutes les validations ; la classification actuelle à revoir reste celle constatée en H/S.
4. Dans le plan d’implémentation futur, garantir le chargement lié au V fixé (préservation de R03) et l’insertion sans écrasement sur collision d’ID. Ces obligations de correction ne créent aucune unicité sémantique ni nouveau protocole Consumption/publication.

**Prochaine étape minimale :** une revue documentaire de ces décisions et de la preuve bornée de complétude, puis un plan COMMAND limité au lecteur historique, à la policy pure et à la liaison du contexte à V. Aucun prototype ni test exécuté dans A.2. Aucun besoin de reconstruire l’analyse initiale, d’enrichir les payloads valides ou d’ouvrir une migration pour discuter cette étape.

### V.1 Contrôle documentaire A.2

Commandes réellement exécutées pour A.2 : `pwd`, `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git worktree list --porcelain`, `git show-ref --verify refs/heads/feat/consumption-transaction-protocol`, `git log -5 --oneline feat/consumption-transaction-protocol`, `git merge-base --is-ancestor 675533e7 HEAD`, `git diff -- <index>`, `git ls-files --modified --others --exclude-standard -z`, `cat`, `sed`, `rg`/`rg --files`, `cmp` des politiques ; scripts Python de sauvegarde/empreintes, rédaction et contrôle documentaire ; `git diff --check`, `git diff --stat`, `git diff --name-only`. Le dernier contrôle porte aussi sur le rapport non suivi, absent du diff Git ordinaire. Les recherches initiales sans correspondance et chemins absents ont été corrigées par inventaire, sans action de nettoyage.

Contrôles documentaires : comptage des 100 cases et des transitions A→A.2, liens locaux, conservation exacte du corps historique A→K et des empreintes des autres travaux, état Git final. Ce sont des vérifications de document, **aucun test applicatif**. HEAD final identique au HEAD de départ ; aucun commit créé pour ne pas mélanger la livraison non committée de A et cette révision. Deux seuls chemins édités : ce rapport (toujours non suivi) et son index (déjà modifié). Tous les autres travaux préexistants sont préservés.

Slices déclarées/exécutées : **NONE** ; franchissement inattendu : **NONE** ; impact production : **NONE** ; gate global : **NOT REQUIRED/non exécuté** ; full reactor : **non exécuté**, étude documentaire ; Maven/tests/migrations/SQL exécutés : **aucun** ; push/merge/rebase : **aucun**. Arrêt après la revue documentaire.

**Verdict final A.2 : READY_WITH_DECISIONS.**

### Livraison documentaire ultérieure autorisée

Après clôture de la revue A.2, l’utilisateur a explicitement demandé de pousser le rapport, levant pour cette livraison l’interdiction initiale de push. Le commit documentaire réunit le rapport A historique non committé, sa révision A.2 et leur seule entrée d’index. Les constats Git de A et A.2 ci-dessus décrivent leur état avant cette livraison. Aucun runtime, test, migration ou autre travail préexistant n’est inclus ; les limites de vérification restent inchangées.
