# A2 — Consumption et Discovery : modèle minimal

Statut amendé le 2026-10-22 : **modèle applicatif approuvé conceptuellement ; plan non validé pour implémentation**. Les écarts de migration (§5) et les décisions ouvertes (§6) ne sont pas des acquis de la cible.

Périmètre : architecture cible et trajectoire de remplacement ; aucune implémentation runtime ni migration. A2-P sert d'inventaire, sans autorité sur les abstractions cibles.

## 1. Modèle cible approuvé conceptuellement : une définition, des traitements durables

Une **Consumption** est une définition applicative : un UUID stable, une famille de consommables et une fonction de transformation. `consumptionId` suffit à la référencer ; ni nom de consumer, ni projection, ni configuration de déploiement ne complètent son identité.

Un **slot** représente le traitement d'un consommable par cette définition : `(consumptionId, consumableId)`. Son `slotId` identifie l'exécution durable ; un claim identifie une tentative. La famille est déduite de la définition. Une référence de consommable peut rester composite pour les objets qui n'ont pas d'UUID propre, notamment une `ProjectionKey`.

La définition et sa fonction restent en Java, sans sérialisation en base. Deux réplicas partagent son UUID constant ; deux traitements indépendants ont deux UUID. Un changement exigeant le retraitement de l'historique crée une nouvelle Consumption. Des versions incompatibles ne travaillent pas simultanément sous le même UUID.

### Deux contrats de source

| Responsabilité | Burnable | Non-burnable |
|---|---|---|
| Autorité de disponibilité | État de disponibilité porté par la source, selon son contrat | Source conservée ; enrôlement et état du slot de chaque Consumption |
| Discovery | Trouver les sources disponibles pour cette Consumption | Créer durablement les slots manquants pour cette Consumption |
| Acquisition par Locator | Revérifier la disponibilité ; créer/reprendre le slot et son claim atomiquement | Acquérir un slot déjà enrôlé, échu ou reprenable |
| Finalisation Engine | Effets + transition de disponibilité prévue par la source + slot + clôture du claim, même transaction | Effets + slot + clôture du claim ; aucune mutation de consommation sur la source |
| Retry | Même slot, nouvelle tentative si la source reste éligible | Même slot, indépendamment du checkpoint |
| Historique | Aucun checkpoint E nécessaire à la disponibilité | Checkpoint utile pour enrôler un journal sans le reparcourir |

**Disponibilité et exclusivité sont deux contrats distincts.** Burnable signifie que la source peut cesser d'être disponible suivant son propre lifecycle ; cela n'impose ni affectation durable à une seule Consumption ni unicité globale de traitement. Le minimum est une lecture autoritative de disponibilité et, lorsqu'une issue la modifie, une transition conditionnelle atomique avec effets, slot et clôture du claim. Les issues qui rendent la source terminale sont fixées par cette famille, pas déduites automatiquement de SUCCESS/REJECTED/FAILED. Retry ne la brûle pas.

Le slot reste identifié par `(consumptionId, consumableId)` et porte tentatives, délais et claims, sans deuxième lease. Si un besoin réel exige qu'une seule Consumption gagne sur la source, son autorité doit aussi protéger cette transition sous concurrence ; le fencing d'un slot ne protège pas les slots d'autres Consumptions. Aucune exclusivité supplémentaire n'est requise par le parcours Event → tâches.

Une source non-burnable peut être enrôlée par plusieurs Consumptions. Son slot constitue déjà la preuve d'enrôlement : aucune table d'enrôlement parallèle n'est nécessaire. Un consommable hors du champ de la transformation peut produire zéro sortie et terminer SUCCESS ; cela évite un manifeste de filtres et des exclusions implicites lors du checkpoint.

La nature appartient au contrat typé de la famille. `BurnableDiscovery` et `NonBurnableDiscovery` exposent des responsabilités différentes ; claims/finalisation restent communs, avec un participant de finalisation de source dans le seul chemin burnable.

## 2. Grammaire applicative et coopération

Quelques types suffisent : `ConsumptionId`, `Consumption<I,O>`, `ConsumableRef<I>`, `SlotRef<I>`, `ClaimedSlot<I>`. Les deux dernières références portent explicitement `consumptionId`, `slotId` et la référence du consommable ; `ClaimedSlot` ajoute `claimId`. Le paramètre Java protège la famille ; l'UUID protège la définition au sein d'une même famille.

Chaque service reçoit sa **Consumption au constructeur**, avec ses ports typés, et expose son identité :

| Use case lié à C | Entrée | Sortie | Invariant |
|---|---|---|---|
| `NonBurnableDiscovery<C>.enrol` | C, partition liée, taille de page | C, partition, références enrôlées, position atteinte éventuelle | Slots et progression durables atomiques pour C et cette partition |
| `BurnableDiscovery<C>.findAvailable` | C, limite | C, références de sources disponibles | Aucune promesse d'acquisition ; aucune modification de checkpoint |
| `Locator<C>.acquire` | C, worker, lease, limite ; candidats pour le chemin burnable | `ClaimedSlot<C>` ou aucun travail | Disponibilité revérifiée ; claim du slot de C seulement |
| `Engine<C>.execute` | `ClaimedSlot<C>` | C, slot, résultat confirmé : terminal, retry ou perte de claim | Transformation fixée par C ; aucune fonction d'exécution dans la requête |

`C` désigne ici la définition liée, pas une classe Java par UUID. Commandes et résultats portent son `ConsumptionId` concret ; une identité différente de celle du service est refusée localement.

**Interdiction d'exécution croisée :** Engine charge le slot durable sous `(consumptionId, slotId)`, vérifie référence/famille et rattachement du claim avant d'appeler sa transformation. Locator cherche uniquement dans C. Finalisation/retry exigent à nouveau C/slot/claim. Falsifier l'identité d'un message ne change pas le propriétaire durable. Les ports imposent ces identités ; SQL garantit les invariants sous concurrence. Aucune API publique « slotId + callback arbitraire ».

Le Worker compose les trois composants par des constructeurs ordinaires vérifiant leur cohérence locale. Pas de validateur global, registre, manifeste ou framework. La relecture durable par Engine reste obligatoire.

### Enrôlement d'un journal non-burnable

Conserver **un checkpoint par `(consumptionId, partitionId)`** ; la famille est fixée par C. La partition est du routage et peut être indexée pour sélectionner les slots ; elle ne complète jamais leur identité logique `(consumptionId, consumableId)`. Discovery et Worker restent liés à la même définition et à la même partition.

Discovery lit une plage bornée **contiguë de positions E globales de la famille**, après ce checkpoint, puis applique le routage au hash métier persisté. Les positions non locales sont examinées aussi ; un résultat local vide ne prouve pas à lui seul la couverture. Chaque position franchie doit avoir sa source ; trou ou source manquante interdit de franchir la position. Les slots locaux sont créés idempotemment sans modifier les slots existants, puis le checkpoint avance par **CAS de la valeur lue**, dans la même transaction PRIMARY. CAS perdu ou erreur : rollback de toutes les insertions, relecture et reprise ; COMMIT UNKNOWN : réconciliation durable. Aucun prélock du checkpoint n'est nécessaire.

Le checkpoint signifie « préfixe examiné et slots locaux enrôlés », jamais « traité ». Locator reprend les slots PENDING éligibles derrière le checkpoint sans reparcourir le journal. Une nouvelle Consumption part du début de l'historique conservé. Hash, algorithme et nombre de partitions sont stables pour le flux ; une évolution exige une transition explicite des affectations/checkpoints, sans créer un second slot pour la même paire logique. Cela préserve le [contrat A1][a1], sans reprendre les propositions de registre ou d'assemblage d'A2-P.

Sans journal E, une demande/task pour une Consumption connue peut être enrôlée à sa création, dans la même transaction. Sinon, garder un parcours complet reprenable jusqu'à disposer d'une preuve de préfixe stable ; date/UUID ne suffit pas pour un checkpoint durable.

### Garanties transactionnelles

Acquisition et exécution restent deux transactions physiques. L'acquisition verrouille le slot pour installer/remplacer le claim, sans effet métier. Engine recharge l'entrée autoritative, écrit les effets rollbackables, puis flush, CAS terminal du slot de C et clôture conditionnelle du claim. Conserver ce suffixe commun, sans prélock terminal ni second fencing. La provenance générique obligatoire (`ConsumptionInput`/`ConsumptionResult`) est **hors cible**, conformément à la [décision A2.0][a20] ; le Consumer persiste seulement les liens causaux nécessaires à ses propres invariants, dans cette transaction. Le code et les tables de provenance peuvent subsister pendant la migration.

Le CAS exige le slot PENDING et son `currentClaimId`, avec le rattachement à C. Zéro ligne ou échec de clôture provoque le rollback de tous les effets. La lease autorise le takeover ; son expiration seule ne retire pas l'autorité au claim courant. La révision reste un contrôle optimiste, pas un fencing token. Les autorités Pot et Binding gardent leurs garanties et l'ordre utile P → B → S pour Command, sans imposer P/B aux autres Consumers. Les issues terminales (SUCCESS/REJECTED/FAILED/ABANDONED) et leurs raisons demeurent ; les Results métier restent distincts de ces issues.

Une exception technique provoque le rollback avant une transaction d'échec décidant retry/FAILED sous le même fencing. Perte de claim et commit COMMITTED/UNKNOWN ne déclenchent pas automatiquement un nouvel échec métier. Le succès public exige un commit confirmé. Un effet externe passe par une sortie durable transactionnelle ; `afterCommit` ne garantit pas sa livraison. Les tentatives peuvent se répéter, seuls leurs effets gagnants deviennent durables.

## 3. Familles minimales d'événements et positions E

Une **famille technique** définit un journal, son contrat de lecture et son espace de positions E. Un **type d'événement** décrit un fait dans cette famille. Une **opération métier** est la demande qui peut produire zéro, un ou plusieurs faits et une complétion.

La cible fonctionnelle distingue deux catégories dans chacun des deux royaumes, soit quatre familles. **Ce vocabulaire cible ne décrit pas les quatre familles E livrées** : leur correspondance et leur migration restent explicites (§5–6).

| Famille cible | Types représentatifs | Fonction |
|---|---|---|
| `BUSINESS_EVENT` | PotCreated, ExpenseSharesUpdated… | Modifications effectives du domaine métier |
| `BUSINESS_REQUEST_COMPLETED` | CommandApplied, CommandRejected, CommandFailed | Décision terminale d'une demande métier externe, même sans modification |
| `IDENTITY_EVENT` | UserCreated, ExternalIdentityAttached, ExternalIdentityDetached | Modifications effectives du domaine d'identité |
| `IDENTITY_REQUEST_COMPLETED` | RegistrationRegistered, RegistrationRejected ; autres complétions quand leurs demandes existent | Décision terminale d'une demande externe au domaine d'identité |

Inscription/attachement/détachement sont des opérations ; leurs faits sont des types. Une inscription réussie produit UserCreated + Attached + Registered atomiquement ; un rejet produit sa seule complétion. Une commande appliquée produit des faits et une complétion ; rejet/échec peuvent ne produire que cette dernière. Aucun retry ne publie une complétion prématurée. Une Registration définitivement FAILED exposée comme terminée exige une complétion explicite, absente du modèle REGISTERED/REJECTED actuel.

`BINDING_FACT` et `USER_CREATED_FACT` relèvent conceptuellement de `IDENTITY_EVENT`. **Fusionner leurs espaces E reste à décider** : Registration les publie ensemble, mais les chemins auxiliaires Binding existent aussi. Une fusion peut conserver les tables sources et réutiliser le même runner/collecteur/suffixe dense, avec une autorité commune et les déclarations dans leur ordre (UserCreated puis Attached pour l'inscription). Elle accroît la contention et doit préserver unicité, références sources valides et complétude pour les deux sources. Garder provisoirement les deux journaux livrés permet de réaliser Discovery sans attendre cette décision ; cela ne crée pas encore un E commun d'identité. Aucun journal fusionné creux ni enveloppe universelle n'est imposé.

Une publication expose famille, E, identité source, type, sujet/version et corrélation éventuelle. Registration garde `requestId` comme identité source ; son journal référence la décision durable sans dupliquer l'outcome.

**E est une position de publication par famille**, distincte de la version Pot, de la révision Binding et de l'identité Event. Le numéro d'une famille n'est pas comparable à celui d'une autre. Le checkpoint exige un préfixe stable : aucune publication future ne peut apparaître derrière la position franchie.

**Réutiliser le protocole E dense réellement livré en 4A–4E** : mutations/faits/décisions et déclarations explicites des références acceptées ; finalisation S/claim s'il y a Consumption ; flush terminal complet, fermeture du lot, puis allocation des blocs E et associations avant commit, sur la même transaction physique PRIMARY. `PrimaryEventTransactionRunner`, `PublishedEvents` et `JdbcEventTransactionSequencer` réalisent déjà ce chemin : pas de reconstruction du séquencement. Aucun callback métier, nouvelle déclaration ni retour aux autorités Pot/Binding/slot après fermeture/allocation. Les gardes OPEN et le poisoning empêchent un commit après une violation interceptée ; tout échec annule aussi faits, slot, claim, compteurs et associations. Les chemins normal, recovery, échec terminal et auxiliaires sont raccordés en 4D.

Ordre technique **CURRENT** : `BUSINESS_EVENT < COMMAND_TERMINAL_EVENT < BINDING_FACT < USER_CREATED_FACT`, seules les familles non vides étant acquises. Les compteurs sont rollbackables, leurs verrous conservés jusqu'au commit/rollback ; l'ordre de déclaration est conservé dans chaque famille. **Conserver densité et préfixe stable** : les positions conservées forment `1..E` après cutover complet. V30 apporte FK, uniques, immutabilité et complétude différée en ACTIVE ; V31 contrôle notamment `count(source)=count(position)=E`, les bornes et les associations manquantes/orphelines. Les droits et writers conformes restent des hypothèses de cette garantie, pas une protection absolue contre tout SQL privilégié. Aucun `nextval`, allocation autonome ou publication après commit. L'ordre adapté à une fusion/nouvelle famille et ses attentes SQL devront être requalifiés avant migration ; ne pas l'appliquer au protocole actuel par simple renommage.

L'exhaustivité des déclarations reste vérifiée chez les producteurs ; les contrôles différés en ACTIVE refusent en plus un commit de source sans association. Discovery ne franchit pas un trou et ne le répare pas. PREPARED n'alloue pas E ; ACTIVE exige le cutover/backfill administratif livré. La clôture fonctionnelle ne certifie ni activation production ni performance E2E.

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

var discovery = new NonBurnableDiscovery(PROJECTION_TASKS, partitionId, events, slots, checkpoints, tx);
var locator   = new NonBurnableLocator(PROJECTION_TASKS, partitionId, slots, claims, tx);
var engine    = new ConsumptionEngine(PROJECTION_TASKS, events, slots, claims, tasks, tx);
var worker    = new ConsumptionWorker(PROJECTION_TASKS, partitionId, discovery, locator, engine);
```

Le développeur écrit les branches typées : par exemple PotCreated → READ_POT et BALANCES à la version de l'événement. Sans conséquence : liste vide ; inconnu/invalide : erreur explicite. Les routes restent du Java, l'UUID une constante.

### Parcours et persistance

1. Le producteur métier persiste le BusinessEvent et sa publication E avec la mutation Pot et sa complétion éventuelle dans la transaction productrice. Cet Event ne porte aucun état consumed.
2. Discovery de PROJECTION_TASKS lit une plage E contiguë, puis route les événements de sa partition. Il persiste un slot PENDING par événement local et avance par CAS le checkpoint `(consumptionId, partitionId)` dans une transaction courte. La transformation et les tâches ne s'exécutent pas pendant l'enrôlement.
3. Locator de PROJECTION_TASKS sélectionne un slot échu, installe son claim dans une transaction d'acquisition et retourne `ClaimedSlot(consumptionId, slotId, eventRef, claimId)`. Un crash après acquisition laisse un slot reprenable après la lease.
4. Engine de PROJECTION_TASKS vérifie le rattachement durable, recharge l'événement et appelle `BusinessEventToProjectionTasks`. Toutes les tâches retournées sont persistées idempotemment par leur `ProjectionKey`, puis slot DONE/SUCCESS et claim clos, dans une transaction unique. Un lien Event → tâches n'est persisté par ce Consumer que si son contrat l'exige ; aucune provenance générique n'est obligatoire. Zéro tâche est un succès valide.
5. Échec avant commit : aucune tâche partielle durable. Retry : même slot. Takeover gagnant : le CAS du perdant annule tous ses effets, liens causaux éventuels compris. Deux événements produisant une même ProjectionKey convergent vers la tâche existante sans fusionner leurs slots. Engine retourne le résultat seulement après résolution du commit.

À persister : événements/publications, checkpoints par partition, slots/claims/issues et tâches ; liens causaux seulement selon le besoin du Consumer. **Toutes les tâches d'un Event sont produites atomiquement** ; elles ont ensuite leurs slots d'exécution. Remplacer le slot Event × projection suppose une liste de sorties bornée, conforme au catalogue courant.

### Intégré ou séparé

Intégré : appels directs du Worker. Séparé : chaque processus charge son use case et la même déclaration Java. Seules les références contextualisées traversent le transport ; jamais la transformation.

Slots durables et leases permettent la reprise malgré perte/duplication de messages. Aucune transaction ouverte entre processus. Effets et finalisation gardent la même base transactionnelle ; la séparation de processus ne résout pas un déplacement des effets dans une autre base.

### Même modèle dans l'identité

À terme, déclarer `IDENTITY_EVENT → CurrentBinding` sous un autre UUID. Discovery enrôle, Locator acquiert, Engine recharge : Attached/Detached applique CurrentBinding avec les contrôles BindingId/révision ; UserCreated ne produit rien. Application/finalisation sont atomiques. E sert au parcours, la révision garde son sens fonctionnel. Avant fusion, le flux peut rester lié à `BINDING_FACT`. RegistrationResult reste actuellement produit depuis RegistrationOutcome ; son passage à `IDENTITY_REQUEST_COMPLETED` exige une décision et une migration distinctes.

## 5. CURRENT réconcilié et écarts de migration

### Audit préalable du séquencement livré

Le plan initial (`516d2a94`) avait inspecté `90926a5e`. Cet état local ne décrit pas le CURRENT du chantier Discovery. Audit statique de **`feat/consumption-transaction-protocol` à `ac1a895af02190886814318ae49d63105297af00`**, sans changement de branche ni intégration : rapports 4A–4E, runner/séquenceur, déclarants Command/Registration/auxiliaires, V30/V31 et tests PostgreSQL associés. Base commune avec la branche du plan : `2eef3ab9`. Les preuves PASS citées sont celles publiées sur cette branche, **non réexécutées pour cet amendement**.

| Déjà livré sur la branche Discovery | Acquis à réutiliser ; limite |
|---|---|
| **[4A][4a] — contrats** | `EventFamily`, `EventRef`, `PublishedEvents`, acceptations spécialisées ; métier Pot/Expense sans position E. |
| **[4B][4b] — propriétaire PRIMARY** | Même transaction physique JPA/JDBC, phases OPEN/CLOSED, poisoning, flush terminal après S/claim, snapshot fermé et suffixe avant commit, cleanup/résolution. |
| **[4C][4c] — séquenceur et [V30][v30]** | Quatre autorités et associations typées, allocation transactionnelle dense par blocs, ordre fixe des familles, FK/uniques/immutabilité/complétude différée en ACTIVE. PREPARED reste dormant. |
| **[4D][4d] — producteurs réels** | Command TE/TR/TH, Registration et auxiliaires Business/Binding raccordés ; déclarations après acceptation, writers gardés/poisonnés et compositions réelles. Aucun checkpoint Discovery livré. |
| **[4E][4e] — administration et [V31][v31]** | Cutover/backfill atomique des quatre familles, inspection densité/complétude, privilèges, reprise de la même opération après UNKNOWN, tests de transport/crash et runbook. Clôture fonctionnelle `2506368c` : CLOSED/PASS ; certification production toujours NOT CERTIFIED, réserves R1–R6 conservées. |

Le [runner][runner], le [séquenceur][sequencer] et les [tests E PostgreSQL][tests-e] confirment le protocole ; les [preuves de clôture 4E][proof-4e] en fixent la portée historique. Il reste à implémenter l'enrôlement incrémental et ses checkpoints : les Discoveries CURRENT utilisent encore date/UUID, routes et slots. **L'absence de checkpoint ne signifie pas absence de E.** A1 est un contrat retenu non implémenté ; A2-P reste une proposition sans autorité sur les abstractions de ce plan.

### Correspondance des familles, sans reconstruction générale

| CURRENT livré | Cible conceptuelle | Écart réel |
|---|---|---|
| `BUSINESS_EVENT` | `BUSINESS_EVENT` | Réutiliser journal, positions, producteurs et hash Pot. |
| `COMMAND_TERMINAL_EVENT` | `BUSINESS_REQUEST_COMPLETED` | Correspondance sémantique à confirmer pour les demandes concernées ; un renommage seul n'exige pas de renuméroter. |
| `BINDING_FACT` + `USER_CREATED_FACT` | `IDENTITY_EVENT` | Deux espaces E indépendants existent ; leur fusion et le routage de leurs sujets différents restent à arbitrer. |
| RegistrationOutcome, hors E | `IDENTITY_REQUEST_COMPLETED` | Nouvelle publication éventuelle, pas un journal déjà livré ; préserver outcome/requestId et RegistrationResult direct. Décider notamment de la complétion FAILED exposée avant de l'ajouter. |

Si la fusion E d'identité est retenue, conserver le runner, les gardes et l'allocation dense ; adapter seulement familles, associations/contraintes, suffixe et administration concernés. Construire un **nouvel espace dense** sous gel coordonné des writers/Discoverers, avec correspondance historique déterministe et contrôlée ; ne pas réécrire les anciens couples immuables ni prétendre reconstruire leur ordre de commit. Reconstruire les checkpoints de ce nouvel espace depuis zéro, en conservant ou rapprochant les slots par leur identité source/C ; vérifier aussi les éventuelles collisions d'UUID entre sources. Jamais `max(oldE)`, concaténation de rangs non prouvée ou comparaison entre les deux anciens compteurs. Prouver complétude des deux sources, densité `1..E`, rollback multifamille, ordre des ressources, reprise/UNKNOWN et couverture des auxiliaires. V31 administre exactement les quatre familles actuelles : son outil ne réalise **pas** cette fusion. La représentation SQL finale reste ouverte.

### Autres écarts à traiter explicitement

- **Identité et API :** remplacer progressivement les conventions `ConsumptionKey(ConsumableIdentity, ConsumerIdentity)`/noms `*_V1` par l'UUID de définition et les références contextualisées. Les callbacks d'exécution publics CURRENT sont un écart ; garder le lifecycle et les propriétaires transactionnels, sans créer de validateur, registre, manifeste ou framework d'assemblage.
- **Partition :** les slots n'ont pas de `consumption_id`/`partition_id` relationnels ; encodage/index restent à choisir, avec unicité de la paire logique excluant la partition. Réutiliser les hash Pot/Command/identité externe ; le hash durable UserCreated manque encore. Ne pas uniformiser silencieusement leurs algorithmes.
- **Event → tâches :** remplacer le slot Event × projection par un slot Event × Consumption, toutes sorties atomiques et bornées. Bascule coordonnée, reprise par `ensure` des conséquences manquantes ; ne pas convertir plusieurs anciens DONE en un DONE sans preuve de couverture. Les tâches existantes convergent par ProjectionKey.
- **Provenance :** `FinalizeConsumptionService`, ports/adaptateurs et tables `consumption_inputs`/`consumption_results` sont CURRENT, pas la cible. Inventorier producteurs/lecteurs, tests, SQL/exploitation et consumers non-E ; décider rétention/archivage avant retrait d'API ou DDL. Les liens nécessaires aux invariants métier restent chez le Consumer ; claims, retries, fencing, issues/raisons et atomicité restent dans le moteur.
- **Autres sources :** Command, RegistrationRequest et ProjectionTask reposent actuellement sur les slots, sans lifecycle de consommation de source : ne pas les déclarer burnables par intuition. Un enrôlement à la création est possible ; une conversion burnable demanderait un besoin distinct. Retirer les colonnes legacy outbox seulement après contrôle des usages ; aucune suppression silencieuse de travail durable ni réinitialisation de données.

## 6. Décisions encore ouvertes et petits lots proposés

**À arbitrer avant les lots concernés :** fusion physique des espaces E d'identité ou maintien transitoire des deux journaux ; routage compatible avec leurs sujets/hash différents ; création et couverture exacte d'IDENTITY_REQUEST_COMPLETED ; représentation/index des identités de slot et des checkpoints ; modalités de bascule historique et rétention de provenance. Une exclusivité burnable ne sera spécifiée que pour un besoin de source démontré. Aucun de ces points ne rouvre la densité E ou le partitionnement retenus. Le plan reste non validé pour implémentation.

Les lots ci-dessous partent d'une base intégrant **4A–4E/V30/V31** ; porter le plan sur cette base précède tout code. Aucun lot ne reconstruit les journaux existants et les trois premiers ne dépendent pas de la fusion d'identité ni d'un nouveau journal Registration.

| Lot après validation | Plus petite livraison cohérente | Preuve et périmètre prospectifs |
|---|---|---|
| 1 — Définition et slot, premier chemin EVENT | UUID Java, références C/slot/source/claim et liaison des use cases ; identité unique hors partition. Préserver les anciens consumers pendant leur transition. | EVENT : refus C1/C2 de même famille et entrée falsifiée, acquisition/finalisation réelles, fencing/rollback inchangés. |
| 2 — Enrôlement BUSINESS_EVENT | Seek sur les positions déjà livrées, hash Pot existant, slots idempotents et CAS checkpoint par C/partition ; API d'un batch, sans nouveau scheduler. | EVENT : plage globale avec positions non locales, trou/source absente, CAS concurrent, rollback intégral/crash/UNKNOWN, absence de mutation des slots existants. |
| 3 — Exécution et bascule Event → tâches | Transformation de l'Event complet, toutes tâches dans TE ; Locator des slots dus de la partition, retry derrière checkpoint ; bascule des anciens workers et rattrapage par `ensure`. | EVENT + PROJECTION : panne/takeover/clôture perdue sans tâche partielle, convergence ProjectionKey, couverture historique et reprise sans reparcours. |
| 4 — Autres consommateurs, un à la fois | Répéter définition/enrôlement/reprise sur leur source actuelle. Ajouter le hash UserCreated seulement pour un flux qui l'utilise ; RegistrationResult conserve son outcome direct tant que sa migration n'est pas décidée. | Déclarer les seuls runtimes touchés pour chaque répétition ; maintenir claims, issues, READ et compatibilité des slots historiques. |
| 5 — Retrait de la provenance générique | Après inventaire, retirer l'obligation moteur et migrer les liens réellement utiles ; retirer tables/API historiques dans un lot distinct seulement si leur rétention est résolue. | Slices des consumers réellement affectés : mêmes effets/outcomes, rollback sur claim/CAS/clôture perdus, retry et UNKNOWN ; aucune suppression anticipée des données. |

La fusion E et une éventuelle complétion Registration feront chacune un **lot séparé conditionnel**, après arbitrage, avec leur preuve de bascule ; elles ne sont pas des prérequis à Discovery BUSINESS_EVENT. Pour la fusion : BINDING, complément Registration et consommateurs effectivement adaptés. Pour la complétion : complément Registration et RegistrationResult, plus les slices dont le comportement change réellement. Réutiliser les tests et gates E livrés, sans nouveau framework de validation.

### Vérification de l'amendement et des futurs lots

Cette intervention est documentaire : **slices déclarées : aucune ; commandes Maven : aucune ; franchissement inattendu : aucun ; gate global : non requis ; reactor complet : non exécuté, car aucun comportement applicatif ne change ; vérification DB : aucune.** Contrôles : lecture statique des références Git ci-dessus, `git diff --check -- docs/plans/2026-10-22-a2-minimal-consumption-discovery.md`, liens locaux/références épinglées et cohérence du document. Aucun test, code ni migration n'est écrit ou exécuté ici.

Chaque futur Step Plan déclarera son scope complet selon la [politique normative](../testing/Reactor_Verification_Policy.md), avant code : impact attendu/interdit, slices, preuves, DB, escalade et prochain gate global. Depuis `app/`, lots 1–2 : `./mvnw -pl runtime-event-consumption-worker -am test` ; lot 3 : `./mvnw -pl runtime-event-consumption-worker,runtime-task-consumption-worker -am test`. Les autres lots sélectionneront leurs anchors réels ; COMMAND, BINDING et LKV utilisent respectivement `runtime-command-consumption-worker`, `runtime-binding-consumption-worker` et `runtime-latest-known-version-consumption-worker`, toujours avec `-am test` ; WEB seulement si admission/HTTP change. Registration et les runtimes Results exigent des anchors complémentaires explicites, non présumés couverts par BINDING.

Un changement intentionnel de frontière architecturale, notamment la fermeture des API d'exécution, ou de dépendances/modules exige `./mvnw -pl architecture-tests -am test` au lot concerné. Le full reactor `./mvnw test` reste au gate d'intégration significative, de restructuration large ou de clôture majeure prévu ; pas de full par défaut. Tout franchissement imprévu impose révision préalable du scope. Migrations futures : preuve CURRENT et de bascule ciblée ; baseline seulement si réellement certifiée, historique seulement au gate pertinent. Les réserves de production 4E restent distinctes de ces lots.

### Sources de l'amendement

Les références 4A–4E/A1/A2.0 ci-dessous sont épinglées au SHA audité de la branche Discovery ; elles ne désignent pas des fichiers déjà intégrés à la branche locale du plan. Lecture par `git show ac1a895a:<chemin>` ; aucune preuve d'exécution nouvelle n'est déduite de la lecture des tests.

- [Identité locale](../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumptionKey.java), [finaliseur local](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java), [Discovery Event locale](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java), [production des tâches](../../app/engine-produce-projection-task/src/main/java/com/kartaguez/pocoma/engine/produce/projectiontask/materialization/ProduceProjectionTaskService.java).
- [Canon Discovery audité][canon], [contrat A1][a1], [décision A2.0][a20], [rapport 4E][4e] et [runbook][runbook].

[4a]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/architecture/Event_Publication_Contracts_Step_4A_2026-10-19.md
[4b]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md
[4c]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md
[4d]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/architecture/Event_Producer_Wiring_Step_4D_2026-10-20.md
[4e]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/architecture/Event_Sequencing_Step_4E_2026-10-20.md
[v30]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/app/infra-persistence-primary-jpa/src/main/resources/db/migration/V30__prepared_event_transactional_sequencing.sql
[v31]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/app/infra-persistence-primary-jpa/src/main/resources/db/migration/V31__event_sequencing_administration.sql
[runner]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionRunner.java
[sequencer]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/JdbcEventTransactionSequencer.java
[tests-e]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/EventSequencingPostgresTest.java
[proof-4e]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/testing/evidence/Event_Sequencing_Step_4E_Functional_Closure_2026-10-20.json
[canon]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/steps/current/DISCOVERY/Step_Canon.md
[a1]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/architecture/Discovery_A1_Contract_2026-10-21.md
[a20]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/architecture/Discovery_A2_0_Consumption_Model_Simplification_2026-10-21.md
[runbook]: https://github.com/kartaguez/pocoma/blob/ac1a895af02190886814318ae49d63105297af00/docs/operations/event-sequencing-runbook.md
