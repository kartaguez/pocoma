# L2 — Plan de conception et de vérification

2026-10-27 — **G0 FRANCHI PAR DÉCISION HUMAINE ; I1 et I2 CLOSED / LOCAL BOUNDED PASS ; audit indépendant du code I2 à venir, I3 non autorisé ; G1/G2/G3 futurs.**

La préparation conceptuelle ci-dessous reste le référentiel des étapes futures. Le [dossier I1 courant](L2_I1_G0_Readiness_and_Implementation_Plan.md) consigne la décision humaine G0, les fondations techniques effectivement livrées et les preuves exécutées. Les anciennes mentions de non-exécution décrivent la préparation initiale ; elles ne révoquent pas le mandat I1. L2 n'est pas ACCEPTED et les certifications G1/G2/G3 restent futures. Le [plan d'implémentation I2](L2_I2_Implementation_Plan.md) précise l'extraction Command, les signatures et protocoles R1/R9, les fichiers et tests ; implémentation I2 autorisée par un mandat humain distinct puis vérifiée localement ; audit indépendant du code et des preuves à venir, arrêt avant I3.

## 1. Autorités, état de départ et contrat de reprise

Lire dans cet ordre : [plan directeur](V3_Implementation_Master_Plan.md), [canon](V3_Canon_and_Roadmap.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-10](SPEC-10_Test_Certification.md), [contrat L1-C](L1_C_Transaction_Contract.md), clôtures [L1-F](L1_F_Closure_Report.md)/[L1-F2](L1_F2_Closure_Report.md), [matrice F2](L1_F2_Invariant_Matrix.md), [politique du réacteur](../testing/Reactor_Verification_Policy.md), puis [registre des douze arbitrages](L2_Decision_Register.md) et [revue contradictoire](L2_Contradictory_Review.md).

Les SPEC portent les normes, le registre transcrit les décisions humaines L2, ce plan décrit leur application future, le plan directeur seul porte les statuts de programme. La revue distingue contradictions confirmées, risques de conception et lacunes ; ses recommandations **ne sont pas des décisions humaines acquises**. Une recommandation conditionne la conception correspondante jusqu'à arbitrage explicite. Les rapports L1 restent historiques et ne sont pas réécrits.

### Précontrôle du 2026-10-26

| Objet | Constat vérifié |
|---|---|
| Dépôt | origin = `https://github.com/kartaguez/pocoma.git` |
| Référence du mandat / tête distante actuelle | `4127767f2f9337b495501d7cd5d933ae9782c8ec`, confirmée par le connecteur GitHub, puis reconfirmée après complément du mandat |
| Checkout utilisateur | `feat/runtime-monolith`, `90a3e9bc1a1fdd325d0c36e543824239cd85a431`, sale avant intervention : runtime/Bruno, docs/README, test PG non suivi, docs/v3 non suivis notamment ; aucun fichier de ce checkout utilisé comme livraison V3 |
| Branche locale cible occupée | `/private/tmp/pocoma-v3-cleanup`, `ddca2827dac7cf13f8b07f620f0cd3b3cb392196`, 36 commits derrière origin ; dix Markdown V3 déjà indexés, préservés |
| Worktrees préexistants | checkout principal ; `/private/tmp/pocoma-v3-{c01-c02,cleanup,l0,l1-ab,l1-c,l1-consolidation,l1-de,l1-f2,l1-f2-before,l1-plan,n01}` ; worktree `debt-wa6-integration/pocoma` sous `.codex/worktrees` ; aucun déplacé/nettoyé |
| Worktree documentaire créé et attaché | `/Users/julien.guezennec/.codex/worktrees/v3-l2-documentation/pocoma`, HEAD détaché sur la tête distante ci-dessus, propre au départ ; aucune modification de branche préexistante |
| Vérification réseau shell | `git ls-remote` a échoué sur résolution DNS ; vérification distante réalisée via connecteur, pas assimilée à un fetch shell réussi |
| Inventaire | 60 modules enfants du POM, annexe §15 ; ports/engines/adaptateurs/compositions et DDL L1 lus directement |

**Scope du présent mandat :** impact production interdit ; primaire NONE/documentaire, secondaires NONE ; DB NONE ; architecture globale NOT REQUIRED ; full reactor NOT REQUIRED. Aucun franchissement applicatif. Contrôles : diff Markdown, liens/ancrages, douze décisions uniques/options, concordance du graphe inventorié, statuts et préservation des autres worktrees. Ni les contrôles ni la revue statique ne constituent des PASS PG nouveaux.

À la reprise future : reconfirmer Git local/distant et toutes modifications, lire les décisions de revue enregistrées, vérifier validation/audit de L1-F2 et plan directeur, figer les paramètres du §6, ouvrir manifeste/rapport de campagne, déclarer les slices avant code. Une tête différente exige analyse du delta ; ne pas faire de reset, stash, clean ou transfert automatique des travaux préexistants.

## 2. Objectifs, exclusions et catalogue exact

L2 doit livrer ultérieurement le protocole commun Consumption/Discovery/Engine sur ses deux autorités, avec Command Pot réel comme pilote WRITE et banc READ technique indépendant. Préserver tous les invariants C/F/F2 sans reconstruire les chaînes historiques par tentative. Ne pas livrer pendant ce mandat, ni anticiper la réalisation métier Identity, projections, HTTP, consentement, invitations, convergence ou distribution.

**Sept familles** selon SPEC-03 §2 ; les cinq séquences sont des séquences de publication transactionnelles, pas des `nextval` PostgreSQL ni des séquences par partition.

| Famille | Nature / UUID source | Séquence | Autorité source |
|---|---|---|---|
| COMMAND | burnable / requestId | aucune | WRITE |
| IDENTITY_REQUEST | burnable / requestId | aucune | WRITE |
| POT_EVENT | non-burnable / eventId | POT | WRITE |
| INVITATION_EVENT | non-burnable / eventId | INVITATION | WRITE |
| IDENTITY_EVENT | non-burnable / eventId | IDENTITY | WRITE |
| COMMAND_COMPLETION_EVENT | non-burnable / eventId | COMMAND_COMPLETION | WRITE |
| IDENTITY_COMPLETION_EVENT | non-burnable / eventId | IDENTITY_COMPLETION | WRITE |

**Neuf Consumptions de référence : deux WRITE, sept READ.** Les noms des deux exécutions WRITE et des matérialisations de résultats ci-dessous sont des libellés de catalogue, pas des nouvelles classes imposées ; les identités durables sont leurs UUID explicites à allouer en composition. Les neuf projections logiques ne sont pas neuf nouvelles Consumptions READ.

| Consumption / responsabilité unique | Famille | Effets / autorité finale | Périmètre L2 |
|---|---|---|---|
| Exécution Command (pilote via ExecutePotCommand adapté) | COMMAND | Pot, demande, résultat, POT/COMMAND_COMPLETION / WRITE | Réel ; Identity fixture explicitement |
| Exécution IdentityRequest | IDENTITY_REQUEST | Identity, demande, résultat, IDENTITY/IDENTITY_COMPLETION / WRITE | Contrat générique et source de fixture seulement ; producteur réel différé |
| MaterializePotVersion | POT_EVENT | PotVersion, SubobjectVersion, CurrentPot / READ | Définition seulement ; métier différé |
| MaterializePotBalance | POT_EVENT | Balance immuable par V active, aucun Balance(D) / READ | Définition seulement ; métier différé |
| MaterializePotVisibility | POT_EVENT | PotVisibilityState, navigation/tombstones / READ | Définition seulement ; métier différé |
| MaterializeInvitation | INVITATION_EVENT | CurrentInvitation / READ | Définition seulement ; producteur/projection différés |
| MaterializeCurrentBinding | IDENTITY_EVENT | CurrentBinding à version EUI, filtre EXTERNAL_USER_IDENTITY / READ | Définition seulement ; métier différé |
| Matérialisation CommandResult | COMMAND_COMPLETION_EVENT | résultat immuable et requesterEui / READ | Définition seulement ; métier différé |
| Matérialisation IdentityRequestResult | IDENTITY_COMPLETION_EVENT | résultat immuable et requesterEui / READ | Définition seulement ; métier différé |

Le banc READ est une **définition de test** avec UUID propre et effets techniques ; il ne devient pas une dixième Consumption métier de référence. Plusieurs définitions de banc peuvent prouver l'indépendance sur une même source sans ajouter des tâches au catalogue métier. Jamais POT_BALANCE_TASK/POT_VISIBILITY_TASK, READ_REQUEST, workId ou PreparePotProjectionTasks.

Les sources publiées demeurent conservées. Aucun événement ne reçoit un statut de consommation. La demande burnable porte PENDING/TERMINAL ; rejet métier = résultat terminal normal, erreur technique ≠ rejet. Les complétions ne recopient pas le résultat ou le requesterEui. Références détaillées : SPEC-03 §§3–7 et SPEC-04 §§2–10.

## 3. Architecture hexagonale et responsabilités

L2-A02/A09 imposent un Engine ignorant Pot/Identity/Balance/Visibility. Le domaine conserve uniquement identités/valeurs/invariants purs, sans Spring/JDBC/PostgreSQL. Engine orchestre des ports explicites ; adaptateurs portent SQL/leases/transactions physiques ; composition injecte les dépendances par constructeurs, par autorité. Pas de service par opération transférant simplement les appels, de transformation DSL ou de framework universel de verrouillage.

| Frontière conceptuelle future (noms non imposés) | Responsabilité / résultat explicite |
|---|---|
| Définition de Consumption | UUID, famille unique, autorité, routage et capacités ; chargement/préparation, validations et protections métier, effets/publication, observations durables |
| Lecture des références source | Fenêtre sûre non-burnable ou page PENDING burnable ; bornes, identités, hash et discriminant de pertinence seulement |
| Discovery dans l'autorité cible | Ownership, K attendu si applicable, count réel hors quarantaine, préfixe couvert, enrôlement idempotent atomique |
| Lifecycle de slot | Acquisition, renouvellement, protection finale, suppression protocolaire, cession unique, réactivation ciblée ; conditions visibles dans le contrat |
| Owner physique de transaction | Ouvrir/résoudre l'unité, vérifier identité réelle des ressources, refuser participation illicite, poison irréversible, distinguer rollback confirmé/UNKNOWN |
| Engine | Composer ces opérations, une tentative complète par claim, gérer capacité et recovery sans vérité métier |
| Polling / renouvellements | Deux boucles indépendantes et dispositif central de renouvellement, ressources non monopolisées par transformations |

Les ports regroupent une responsabilité cohérente ; ce tableau n'oblige pas à créer six interfaces ou six nouveaux modules. Les conditions CAS, l'ordre des protections et la classification d'issue doivent être explicites côté application ; le SQL les réalise et ne décide pas secrètement du protocole.

**Signatures conceptuelles P0, sans classe Java imposée :**

| Opération / résultat conceptuel | Responsable et postcondition |
|---|---|
| `Discovery.discover(C, p, ownerToken, borne)` → préfixe couvert / slots insérés | Engine pilote ; adaptateur de l'autorité des effets protège ownership, K et softCap, puis commite slots/K atomiquement |
| `Engine.acquire(C, p, places)` → claims `(consumableId, claimToken, claimedUntil)` | Engine réserve M_C/M_W, vérifie propriété et contrat de routage ; aucun chargement métier ni effet |
| `Engine.finalize(claim, Consumption)` → issue durable ou incertaine | Engine seul ouvre l'unité physique de l'autorité, protège le slot puis délègue la transformation ; rollback/UNKNOWN restent distincts |
| `SlotCompletion.complete()` → exactement une suppression | Capacité Engine liée à l'unité et au slot/token final ; appel unique après effets/résultat, avant fermeture/suffixe ; erreur poisonne l'unité |
| `Consumption.observe(consumableId, RecoveryReadContext)` → COMPLETED / NOT_COMPLETED / INCONSISTENT / UNAVAILABLE | Lecture métier sans mutation dans une vue cohérente de l'unité Engine ; jamais une décision de cession par la Consumption |
| `Engine.cede(claim, cause, paramètres)` → compte/état/échéance durables ou issue incertaine | Engine seul, après rollback certain, vérifie token et horloge DB ; une cession au plus |
| `Admin.reactivate(C, consumableId, expectedState)` → transition CAS ou refus | Administration ciblée, indépendante de l'ownership et de la marge softCap ; aucune suppression ou effet métier |

Les paramètres et identités des signatures sont conceptuels ; les types, noms Java et SQL finaux sont fixés à l'étape productrice sans changer les invariants R1–R10.

Une préparation est jetable : jamais persistée ou réutilisée après collision. Le propriétaire final courant est l'Engine. L'exécuteur L1 ne doit pas rester un owner imbriqué. L'adaptation doit dégager sa transformation métier et ses observations tout en conservant les guards L1 ; un appel au handoff `execute` inchangé depuis une transaction Engine serait non conforme.

## 4. Inventaire de réemploi et des propriétaires

REUSE conserve une garantie ou un composant compatible ; ADAPT modifie le contrat raccordé ; REPLACE substitue une implémentation incompatible ; RETIRE désactive le chemin devenu inutile ; DEFER conserve hors L2. Ce sont des destinations futures, aucun retrait effectué ici. L'annexe §15 classe les 60 modules ; les lignes suivantes détaillent les points critiques.

| Élément réel à la référence | Destination / action future | Owner actuel → owner cible / preuve |
|---|---|---|
| domain-pot, transitions/Fraction/calcul exact ; JdbcPotHistoryAdapter, JdbcPotRevisionAdapter, PotHistoryRows | REUSE invariants et accès locaux/groupés ; ADAPT guards au nouvel owner sans affaiblir xid8 | Pot L1 → Pot participant WRITE ; régressions C/F/F2 |
| engine-write-pot/v3/ExecutePotCommand | ADAPT extraction du cycle technique, métier/preparation conservés | Owns snapshot/write/failure/recovery aujourd'hui → Consumption participant à Engine ; R1/R5/R9 |
| ports execution/v3/PotCommand, PotActorAuthority | REUSE intention immuable et témoin Identity ; fournisseur réel DEFER | Autorité métier distincte ; aucune conversion BindingId V2→V3 |
| ports execution/v3/PotCommandStore, PotCommandTransactions | ADAPT : retirer propriété des slots/transactions du port métier ; observations et publication restent Pot | Store/owner L1 → lifecycle/owner commun injectés ; aucun port de simple forwarding |
| infra-persistence-primary-jpa/v3/JdbcPotCommandStore | ADAPT résultats/événements/positions ; REPLACE ses accès au slot dédié et cession technique | finish supprime actuellement v3_command_slot, publish ferme puis séquence ; R1 obligatoire |
| infra-persistence-primary-jpa/v3/PotCommandJdbcTransactions | ADAPT mécanismes vérifiés de ressources/poison/résolution vers owner commun | Même datasource/JpaTransactionManager actuels → un owner par autorité ; pas deux owners emboîtés |
| JdbcPotCommandStore + DDL V2 v3_require_command_unit | ADAPT contrainte terminal/result/completion/absence slot vers clé canonique | Fonction vérifie encore v3_command_slot ; modifier future contrainte, pas seulement le Java |
| composition-runtime-spring/runtime/command/v3/V3PotCommandComposition | ADAPT assemblage explicite | Assemble handoff L1 déjà claimé → raccord réel Discovery/acquisition/Engine/Consumption |
| domain-consumption/claim, key, lifecycle, segmentation/PartitionHash/WorkerSegment | ADAPT valeurs utiles ; REPLACE sémantiques slotId/DONE/provenance/segments incompatibles | Hash actuel UUID.hashCode/pipelineId, pas un contrat de routage V3 ratifié ; R3 |
| engine-consumption : Acquire/Execute/Finalize/HandleFailure services et wrappers Transactional* ; ConsumptionLifecyclePersistencePort, QueryPort, ProvenancePersistencePort | REPLACE cycle historique ; réutiliser seulement garanties démontrées ; RETIRE états/provenance techniques redondants | Ancien terminalize/slotId/now applicatif → clé canonique, heure DB, suppression, cession Engine |
| JpaConsumptionLifecycleAdapter/ProvenanceAdapter, JpaConsumption{Slot,Claim,Input,Result}Entity/Repository | REPLACE pour nouveaux parcours V3 ; RETIRE raccords incompatibles sur ces parcours | Anciennes tables séparées claims/provenance → quatre tables techniques L2 selon R3 ; pas de double écriture |
| orchestrator-consumption : Sequential/AcquireThenFinalize, locator/ConsumptionLocator, fenced/* | REPLACE orchestration ; RETIRE Locator autonome V3 | Recherche/exécution liées → deux boucles ; protections non supprimées avec Locator |
| orchestrator-poll-consumption : ConsumptionPollingWorker, settings, waiter/ConditionConsumptionWaiter | ADAPT lifecycle/attente/interruption utiles | Boucle historique → deux boucles indépendantes, renouvellements hors saturation |
| engine-consume-command : ExecuteRecordedCommandService, decode/dispatch et CommandConsumptionDiscoveryPort ; JpaCommandConsumptionDiscoveryAdapter/Repository | REPLACE parcours V3 ; RETIRE activation historique incompatible au pilote | Binding/token et source historiques ≠ intention/autorité Pot L1 ; ne pas translater fictivement |
| JdbcProjectionMaterializationDiscoveryAdapter, JpaLatestKnownVersionEventDiscoveryAdapter ; JdbcBindingFactDiscoveryAdapter ; JdbcRegistrationDiscovery/ResultDiscovery | DEFER consommateurs historiques hors pilote ; REPLACE lors conversion effective | Leurs checkpoints/sources historiques ne certifient pas L2 ; garde contre activation sur nouveau schéma |
| PrimaryEventTransactionRunner/Suffix, JdbcEventTransactionSequencer ; runtime/consumption/ConsumptionOwner{Orchestrator,TransactionRunner}, ConsumptionTerminalParticipant | DEFER chemin V2 ; REUSE savoir-faire de suffixe seulement si compatible démontré | Owner PRIMARY V2 ne devient pas owner V3 par renommage ; inventaire de leurs imports obligatoire |
| infra-tx-spring/SpringTransactionRunner ; port-transaction | ADAPT uniquement si nécessaire après comparaison avec owner L1 | Le simple nom de manager ne prouve pas la transaction physique ; FOUNDATION seule insuffisante |
| infra-persistence-read-jdbc ; infra-persistence-projection-jdbc / JdbcProjectionStoreAdapter | ADAPT infrastructure strictement nécessaire au banc READ ; projections métier DEFER | Manager/DataSource READ propre ; un store JSON historique n'est pas une matérialisation L4 |
| engine-produce-projection-task, engine-consume-projection-task, LKV, Binding autonome et supras correspondantes | RETIRE du chemin V3 quand remplacé ; retrait exhaustif DEFER à convergence | Aucun writer Task/LKV autonome réintroduit pour le pilote |
| supra-consume-command/event et runtime-command/event/task workers, runtime-monolith/composition | ADAPT raccords effectivement nécessaires et inventorier tous les imports/scans/profiles | Pas d'activation de producteurs absents ; ancres futures COMMAND/EVENT/PROJECTION |
| Authority/acquisition/admission initiales et reset L1 de test ; fixtures L0 | REPLACE acquisition/slots par Engine réel ; REUSE Identity fixture déclarée ; admission durable interne minimale réelle pour le pilote | Fixture d'identité n'est pas ID-01 ; API HTTP d'admission hors pilote |

### DDL et writers : clôture requise avant bascule future

| Existant | Destination / obligations |
|---|---|
| `db/v3-pot/migration` V1/V3/V4 | REUSE histoire, contraintes, index temporels/effets et préconditions F2 ; installation propre ne dispense pas de leurs garanties |
| `db/v3-pot-execution/migration` V2 | ADAPT reconstruction V3 autorisée A01 : v3_command/result, événements/positions/compteurs utiles ; REPLACE v3_command_slot et références de triggers ; aucun changement aujourd'hui |
| PRIMARY V1–V31, READ V1–V14 | DEFER comme historiques ; aucune baseline V2 TRUSTED ; ne pas les présenter comme bootstrap V3 final |
| Quatre tables L2 WRITE et READ, incluant le contrat de routage administratif R3 | REPLACE stockage technique, concept §5 ; pas de catalogue SQL, compteur softCap ou état supplémentaire |
| Séquences POT/COMMAND_COMPLETION L1 | REUSE protocole dense ; ADAPT owner/suffixe ; autres séquences : contrat/fixture technique tant que producteurs absents |
| Banc L0 `db/v3-foundation/migration` | REUSE méthode de schémas isolés, pas comme schéma métier ou baseline certifiée |

Writers cibles exclusifs : admission→source PENDING sans slot ; Discovery→insert slots/K ; lease→ownership ; Engine→claims/cession/suppression ; Consumption→effets métier/résultat/publication ; admin→CAS réactivation ; maintenance READ autorisée→reset coordonné. Pas d'autre DELETE, purge, réactivation ou source→slot synchrone.

Avant code, compléter l'inventaire par recherche des noms de tables/ports, appels JDBC/JPA et imports, tests/fixtures SQL, POM, wiring explicite/scans, runtime-monolith, profils/propriétés, scripts d'exploitation et bootstrap. Chaque writer productif doit être affecté à une autorité et une voie active. Un fichier encore présent n'est pas forcément actif ; prouver sa désactivation dans la composition distribuée et monolithique utilisée, sans attendre L7 pour neutraliser un writer incompatible. Aucun nettoyage général L7 n'est lancé.

## 5. Schéma conceptuel et frontières physiques

Les noms ci-dessous sont conceptuels selon A03 ; le futur DDL utilisera un nommage V3 cohérent, explicitement fixé avant code. Deux installations du même modèle technique, l'une WRITE, l'autre READ. Ne pas ajouter des FK inter-bases vers les sources.

| Objet | Clé et invariants minimaux | Ownership d'écriture |
|---|---|---|
| consumption_slot | PK `(consumption_id UUID, consumable_id UUID)` ; partition_hash stable non null ; token/claimed_until tous deux null ou tous deux présents ; attempt_count≥0 ; state AVAILABLE/QUARANTINED ; quarantaine sans claim/retry_after ; pas de family_id/workId | Discovery insère, Engine gère/finalise ; admin réactive |
| partition_ownership | PK `(consumption_id, partition_id)` ; owner_token UUID/owned_until cohérents ; un droit effectif non expiré ; absente = non possédée | Lease/acquisition/renewal/takeover dans autorité cible |
| discovery_checkpoint | PK `(consumption_id, partition_id)` ; position≥0, monotone ; absent=0 ; aucun enregistrement burnable | Discovery non-burnable seulement |
| consumption_routing_contract | Par Consumption : famille canonique, version de routage et nombre de partitions ou empreinte vérifiable ; P identique pour toute la famille, immutable tant que slots/K existent | Bootstrap et administration exclusivement ; Workers en lecture/validation transactionnelle |

`claimedUntil` expiré reste un claim présent reprenable ; ne pas effacer par balayage automatique. AVAILABLE avec retryAfter futur est non acquérable. Le succès supprime le slot ; ni DONE ni ligne de positions traitées. Non-burnable : `K≥p ET slot absent` prouve complétion **seulement** avec tous les invariants SPEC-05 §3 ; avant K, l'absence ne prouve rien. Filtrer aussi le prédicat d'éligibilité (notamment les événements POCOMA_USER ignorés par CurrentBinding).

**Index à concevoir et mesurer, pas DDL imposé :** PK du slot pour unicité/anti-jointure ; accès par consumption/hash/état et échéances pour acquisitions ; count hors quarantaine par partition ; sources burnables PENDING par clé de pagination ; événements par séquence/position, avec hash si utile ; PK ownership/K. Ne pas prétendre qu'un index garantit l'atomicité, le fencing ou la continuité. Pas de partitionnement physique par défaut.

Pour Command, source/slot/effets/résultat/publication sont WRITE dans la même transaction finale. Pour le banc READ, source d'événements en WRITE lue sans mutation ; slots/K/ownership/effets techniques en READ, un manager physique READ. Un Worker mixte héberge deux unités distinctes, aucun XA ni commit inter-bases. Les seules lectures WRITE préparatoires ne doivent pas être décrites comme une transaction commune aux royaumes.

Installation propre A01 : créer un environnement V3 identifié, vérifier l'absence de second Engine/anciens slots actifs, initialiser les demandes du pilote PENDING sans slot, conserver le jeu d'entrée et ses UUID/statuts. **Ne pas supprimer un schéma préexistant sur la seule base de ce plan.** S'il contient des demandes à conserver, R7 doit fixer la conservation des sources et des résultats sans migrer les anciens slots. Aucune TERMINAL n'est réadmise comme PENDING.

## 6. Identités, routage, leases et configuration reproductible

Les décisions stables sont A04/A05 ; les valeurs opérationnelles restent à figer au gate G0. La composition associera explicitement à chacun des neuf libellés métier un UUID littéral, une famille, une autorité et une sémantique. Le banc aura ses UUID de test distincts. Ce dossier ne génère pas d'UUID de production : l'activation est interdite si l'allocation manque, varie entre Workers ou dépend du démarrage. Rejeu indépendant : nouvelle identité et destination d'effets ; reconstruction totale READ : mêmes IDs uniquement avec reset cohérent.

**Contrat de routage retenu par R3.** Clé UUID natif du consommable, octets UUID réseau big-endian ; domaine `pocoma-consumable-v1`, séparateur octet zéro, nom exact de famille ASCII, séparateur zéro, 16 octets UUID ; SHA-256 ; quatre premiers octets interprétés int32 signé big-endian = partitionHash ; `partitionId=floorMod(partitionHash, P_famille)`. Pas de nom de classe, de nombre de Workers, de hash dépendant d'une JVM ou du pipeline. Figer avant code les vecteurs de référence pour UUID nul/maximal/ordinaire, hash négatif et extrêmes, toutes familles et plusieurs P. Le nom de famille sépare les espaces, pas une colonne de slot.

Vecteurs documentaires de la convention **retenue conceptuellement par R3**, calculés avec SHA-256 Python standard hors dépôt applicatif ; ce ne sont pas des tests de production PASS. P=4/16 sont des valeurs illustratives, pas les valeurs finales.

| Famille / UUID de test | Hash int32 documentaire | Partition P=4 | Partition P=16 |
|---|---:|---:|---:|
| COMMAND / `123e4567-e89b-12d3-a456-426614174000` | -805145351 | 1 | 9 |
| IDENTITY_REQUEST / `123e4567-e89b-12d3-a456-426614174000` | 570413422 | 2 | 14 |
| POT_EVENT / `123e4567-e89b-12d3-a456-426614174000` | -115927568 | 0 | 0 |
| INVITATION_EVENT / `123e4567-e89b-12d3-a456-426614174000` | 524744889 | 1 | 9 |
| IDENTITY_EVENT / `123e4567-e89b-12d3-a456-426614174000` | 1379522458 | 2 | 10 |
| COMMAND_COMPLETION_EVENT / `123e4567-e89b-12d3-a456-426614174000` | 1913063262 | 2 | 14 |
| IDENTITY_COMPLETION_EVENT / `123e4567-e89b-12d3-a456-426614174000` | -1931455455 | 1 | 1 |
| COMMAND / `00000000-0000-0000-0000-000000000000` | 1843303294 | 2 | 14 |
| IDENTITY_REQUEST / `00000000-0000-0000-0000-000000000000` | -828141649 | 3 | 15 |
| POT_EVENT / `00000000-0000-0000-0000-000000000000` | -830870973 | 3 | 3 |
| INVITATION_EVENT / `00000000-0000-0000-0000-000000000000` | 1677666318 | 2 | 14 |
| IDENTITY_EVENT / `00000000-0000-0000-0000-000000000000` | 1882584737 | 1 | 1 |
| COMMAND_COMPLETION_EVENT / `00000000-0000-0000-0000-000000000000` | -1791788227 | 1 | 13 |
| IDENTITY_COMPLETION_EVENT / `00000000-0000-0000-0000-000000000000` | 1354666831 | 3 | 15 |
| COMMAND / `ffffffff-ffff-ffff-ffff-ffffffffffff` | -1551802490 | 2 | 6 |
| IDENTITY_REQUEST / `ffffffff-ffff-ffff-ffff-ffffffffffff` | -1591625855 | 1 | 1 |
| POT_EVENT / `ffffffff-ffff-ffff-ffff-ffffffffffff` | -926302663 | 1 | 9 |
| INVITATION_EVENT / `ffffffff-ffff-ffff-ffff-ffffffffffff` | -1550265312 | 0 | 0 |
| IDENTITY_EVENT / `ffffffff-ffff-ffff-ffff-ffffffffffff` | 941823169 | 1 | 1 |
| COMMAND_COMPLETION_EVENT / `ffffffff-ffff-ffff-ffff-ffffffffffff` | 1356767700 | 0 | 4 |
| IDENTITY_COMPLETION_EVENT / `ffffffff-ffff-ffff-ffff-ffffffffffff` | -290190640 | 0 | 0 |

P est strictement positif, fixe pour une famille et partagé entre toutes ses Consumptions selon R3. Modifier P/hash/clé/discriminant sous un K existant peut rendre fausse la preuve d'absence de slot. Le contrat durable de la quatrième table `consumption_routing_contract` est installé exclusivement par bootstrap administratif ; tout déploiement incompatible bloque Discovery et nouvelles acquisitions. Aucun Worker ne modifie ce contrat.

Configuration à figer et archiver : UUID/famille/autorité/sémantique, convention de routage et vecteurs, P, partitions candidates par Worker ; softCap C(C,p), taille fenêtre globale W et lot B, capacités M_C/M_W ; N/retryDelay ; durées leases/claims, cadence renouvellement, timeouts ; bornes de polling, borne de retour du curseur. Valider positivité/cohérence, unité/précision des durées ; documenter quelles modifications requièrent arrêt/reconstruction. Les identités, la convention/empreinte, P par famille, les autorités, les responsabilités de bootstrap/transaction et les hypothèses de capacité/renouvellement sont **bloquants avant code et pour G0**. Les valeurs de charge et de cadence propres à une campagne sont figées avant cette campagne sous les limites décidées à P0. Aucune valeur de production prétendument validée n'est inventée ici.

**Amendement de portée I1 du 26-10-2026 :** le [plan I1 amendé](L2_I1_G0_Readiness_and_Implementation_Plan.md#4-configuration-pilote-et-catégories-de-paramètres) enregistre le GO humain et ratifie les neuf UUID et `P=4` du seul pilote L2. Les hypothèses numériques de capacité, retries et renouvellement y sont conservées comme valeurs expérimentales initiales ; leur qualification revient aux étapes qui mettent en œuvre les mécanismes, I3–I5 et P4/P7. Elles ne sont ni des SLO ni un dimensionnement de production et aucune mesure de ces moteurs futurs n'est requise pour coder la fondation inactive I1. Les identités, autorités, convention de routage, partitions et cibles restent figées avant I1.

Lease : acquisition/renouvellement/takeover sous verrou/CAS et heure PostgreSQL fraîche après attente ; vérifier ownership dans la transaction de nouvelle Discovery/acquisition. L'ancien propriétaire perd ces droits mais pas les claims encore valides ; ne jamais recopier ownerToken dans le slot ou le consulter comme fence final. Un transfert ne réécrit aucun slot/K. Verrou partition avant les opérations d'enrôlement/acquisition ; réactivation administrative indépendante selon R2-06/08 ; finalisation/cession ne reviennent pas acquérir un verrou de partition.

## 7. Discovery : protocoles reproductibles proposés

Ces algorithmes concrétisent A06/A07 et les décisions ratifiées R2/R4 sans ajouter d'état durable. Leur traduction SQL devra être qualifiée à G1/G2.

### Non-burnables

1. Lire K durable, absent=0. Dans une photographie source PostgreSQL cohérente, lire la page globale ordonnée des au plus W positions suivantes et sa borne sûre couverte. Densité transactionnelle/immutabilité/conservation permettent qu'une photographie ancienne soit un préfixe toujours vrai ; un nouveau commit n'autorise pas à réinventer H depuis un autre snapshot.
2. Préparer seulement `(eventId, position, partitionHash, discriminant de pertinence)`. Une fenêtre sans référence éligible pour la partition peut être entièrement examinée. Une séquence vide reste à K ; ne pas avancer au-delà du préfixe prouvé.
3. Dans l'autorité des effets, conformément à R2, verrouiller la ligne ownership `(C,p)` (création concurrente de ligne absente traitée par unicité et relecture), vérifier token/échéance après attente ; protéger K attendu. Même verrou sérialise les Discoveries concurrentes et le takeover. La réactivation administrative reste indépendante de l'ownership et de la capacité ; sa course peut dépasser temporairement softCap selon R2-08. Pas de simple lecture COUNT hors l'unité Discovery protégée.
4. Compter réellement les slots non QUARANTINED de cette partition, claims/retries compris. Capacité d'enrôlement `a=max(0,softCap-count)` ; aucune réservation ou compteur durable. Sélectionner le plus long préfixe de la page comportant au plus a références pertinentes nouvelles. **Si a=0**, seules les positions avant le prochain événement pertinent peuvent être franchies ; on peut aussi s'arrêter à K, jamais sauter cet événement. Si la référence suivante dépasse le budget, H final est sa position−1 dans le préfixe dense couvert. À capacité restante nulle, les positions suivantes hors partition peuvent encore être examinées jusqu'au premier événement pertinent non enrôlable.
5. Insérer tous les slots requis de ce préfixe ; vérifier conflits d'unicité/identité (ne pas masquer une anomalie de K par ON CONFLICT permissif). CAS K→H final, commit ensemble. Capacité réduite depuis préparation : raccourcir le préfixe dans l'unité, sans sauter. Rollback conserve K et aucun slot nouveau ; ACK perdu impose relecture K/slots avant reprise.

La lecture WRITE préalable ne verrouille pas des événements jusqu'au commit READ. Ils sont immuables, publiés denses, conservés ; seul K/ownership/capacité doivent être revalidés dans READ. Aucun transfert d'ownership ne peut valider une fenêtre sous ancien token. Ce protocole n'étend pas un snapshot cross-base ; CON-01/02/06 le prouvent séparément.

### Burnables

1. L'admission interne du pilote persiste une demande PENDING immuable, sans slot. Index PENDING et clé UUID stable permettent une page keyset bornée, pas un scan de TERMINAL ni OFFSET.
2. Chercher PENDING sans slot pour C, partition courante, **sans inspecter claim/retry** pour choisir les sources. Tout slot existant, QUARANTINED compris, empêche un nouvel enrôlement.
3. Curseur circulaire en mémoire selon R4 : au début d'un tour capturer une borne haute U des clés PENDING visibles ; parcourir `(cursor,U]` en pages bornées, puis revenir au début. Les insertions >U attendent le prochain tour ; celles derrière cursor sont reprises au tour suivant. Avancer le curseur sur les références examinées même si un autre processus les a enrôlées ; ne pas le bloquer sur un doublon. Perdre cursor/U au redémarrage est permis.
4. La transaction WRITE d'enrôlement protège ownership/capacité, revalide PENDING sous une protection qui sérialise la terminalisation et insère idempotemment la clé canonique. Course critique : un slot existant est supprimé avec TERMINAL pendant la recherche ; il faut relire le statut courant avant insertion, et non se fier au snapshot ancien. Les chemins de verrouillage sont revus pour éviter cycle partition→source→slot contre finaliseur slot→source. Recommandation : ne jamais attendre un slot existant après prise d'une source ; constater le doublon et abandonner l'enrôlement ; démonstration PostgreSQL obligatoire.
5. Si softCap plein, conserver les sources intactes ; le curseur reste une optimisation jetable et ne prouve aucune fin. Reprendre après capacité libre, y compris derrière l'ancienne clé.

La borne U rend un tour fini sur un ensemble initial fini, mais ne garantit pas un délai absolu si le Worker redémarre à chaque page, si tous slots restent verrouillés indéfiniment ou si les ressources ne reviennent jamais. Les hypothèses de progression et la borne observable de retour doivent être déclarées dans la campagne. Aucun marqueur durable pour réparer la famine.

## 8. Acquisition et transaction finale : point critique R1

**Acquisition.** Réserver au plus les places de tentative disponibles dans M_C et M_W. Transaction courte cible : propriété effective, sélection de slots AVAILABLE avec retryAfter échu et claim absent/expiré, SKIP LOCKED, CAS claim neuf/échéance. Succès partiel libère les places inutilisées. Pas de source métier chargée en sélection. Lots de renouvellement courts par autorité, même token valide, aucune résurrection ; leur exécution dispose de ressources même à saturation.

**Préparation.** Charger source et faits dans la frontière appropriée ; Command conserve snapshot WRITE cohérent, intention/requesterEui/observedVersion immuables et transition pure L1. Banc READ prépare références/valeur technique ; aucun calcul métier Pot/Balance simulé pour obtenir un faux PRJ PASS.

**Transaction finale — obligations déjà décidées.** Engine ouvre une unité physique, verrouille exclusivement le slot puis lit une horloge DB fraîche dans une instruction après l'attente ; vérifie token/state/claimedUntil. Le verrou demeure jusqu'à résolution physique, même si le temps dépasse ensuite l'échéance. Pas de fence final de partition. Participation de la Consumption avec protections/validations/effets et observations ; succès seulement avec suppression Engine dans la même unité. Poison irréversible après erreur SQL/JPA/applicative durable interceptée ; ressources effectives identiques et ambiante/REQUIRES_NEW/owner imbriqué refusés. UNKNOWN reste une observation locale, pas un état SQL.

**Command :** ordre slot→demande/intention→Identity→Pot ; INVALID_BINDING protège le témoin négatif Identity sans lire/verrouiller le Pot ; autres branches protègent Pot, revalident la version de travail et CAS. Changed seul crée candidate/révisions typées ; Unchanged/Rejected n'inventent pas de mutation. writer_xid xid8, comparaison exhaustive des huit composantes à candidate, flush et contraintes métier différées avant suffixe ; complétion/résultat/source atomiques. Les contraintes de correspondance publication restent différées en lecture seule au commit.

**Prescription R1 unique.** Après les effets et le résultat métier, la Consumption invoque une seule fois `SlotCompletion.complete()` sur le slot/token protégé, **avant** la fermeture de publication et l'attribution du suffixe dense. L'Engine vérifie l'appel unique et la suppression d'exactement une ligne dans la même unité physique. Appel absent, répété, tardif, hors ordre ou sur mauvaise unité : poisoning et rollback. Après fermeture, aucun writer métier ni suppression de slot n'est autorisé. Analyser les FK/triggers réels de DELETE avant code ; préserver les contraintes métier différées et le verrou final jusqu'à résolution physique. [R1 normatif](L2_R1_R10_Normative_Consolidation.md#1-r1--finalisation-du-slot-option-b).

R1 conditionne l'ordre final reproductible et les futurs guards/DDL. Tests exigés : erreur de suppression→rollback de métier/positions/résultat, writer métier post-suffixe refusé/poison, aucune nouvelle attente/cycle de verrous, contraintes terminales sur slot canonique, expiration post-verrou, ACK commit perdu. Command ne supprime pas son propre slot hors capacité Engine.

**Recovery.** Engine classe rollback certain vs transport/commit ambigu, puis observe la vérité dans l'autorité d'effets. Command : TERMINAL avec résultat/complétion originaux et slot absent = complété une fois ; PENDING avec slot = pas de permission de réexécuter immédiatement, attendre issue/reprise conditionnelle. Banc non-burnable : K et absence du slot seulement sous ses invariants, effet technique vérifiable indépendamment. Les contrats d'observation de chaque Consumption doivent exprimer cohérent terminé / présent récupérable / incohérence / indisponibilité ; aucune donnée Pot dans le cœur générique. R9 est arbitré conceptuellement ; les signatures et postconditions SQL exactes sont à figer avant code, sans table de reçus supplémentaire.

## 9. Retry, quarantaine et réactivation

Protocole décidé A10 : une tentative par acquisition, rollback confirmé avant transaction séparée de cession ; vérifier claim courant/valide sous verrou puis heure DB ; compter un seul échec confirmé ; effacer claim, fixer retryAfter=dbNow+delay si count<N, sinon QUARANTINED sans retryAfter. Le changement de token/sa suppression rend une seconde cession du même claim inefficace. Si réponse de cession perdue, ne pas l'émettre aveuglément à nouveau : relire le durable. Pas de journal de tentatives ajouté.

| Issue | Effets / budget / reprise |
|---|---|
| CHANGED / UNCHANGED / REJECTED métier | Terminalisation normale une fois ; pas d'échec technique |
| Échec SQL/JPA, anomalie de faits, exception technique confirmée | Poison/rollback, cession conditionnelle, +1 durable si claim encore valide |
| Collision CAS/autorité | Nouvelle préparation sous nouvelle acquisition, jamais rejet automatique ; collision explicitement constatée après rollback comptée comme échec technique selon R5-03 |
| Claim perdu/expiré, crash sans constat durable | Aucun compte par supposition ; ancien token ne touche pas le nouveau |
| Commit ou cession UNKNOWN | Observations durables, attente si nécessaire ; aucune cession fondée seulement sur erreur réseau |
| Erreur de configuration/composition avant acquisition | Refus de démarrage/campagne ; ne pas consommer le budget de tous les slots pour une composition invalide |

N et délai restent configurables, bornés/typés/archivés par campagne ; tester leur changement de configuration et préciser effet sur slots dont count≥nouveau N : R5. Réactivation ciblée : CAS QUARANTINED→AVAILABLE, count=0 et claim/claimedUntil/retryAfter effacés ; zéro mutation source/effet. Deux administrateurs ne font qu'une transition ; aucun redémarrage/temps/déploiement ne réactive. Jamais de purge d'incomplet.

Quarantaine exclue de softCap, pas de réenrôlement ; même agrégat/versions ultérieures et autres Consumptions continuent. Réactivation réintroduit un slot dans count : R2 distingue plafond d'enrôlement et invariant de stock absolu. Ne pas ajouter silencieusement un compteur ou bloquer la réactivation sans décision.

## 10. Polling, capacité et équité

Deux boucles indépendantes, chacune round-robin entre Consumptions/partitions candidates ; un seul lot borné par Consumption et passage. Slots durables seul lien ; aucun LISTEN/NOTIFY, priorité, file ou graphe de dépendances requis. Cadence adaptative bornée : ralentir après tour sans progrès effectif, accélérer après insertions/acquisitions réellement gagnées ; une collision de lecture seule ne compte pas comme progrès.

Trois limites distinctes : stock non QUARANTINED par `(C,p)` pour Discovery ; tentatives simultanées M_C sur ce Worker ; total M_W sur ce Worker. Plafonds de tentatives locaux au Worker, pas quota global inventé entre instances. Réserver atomiquement les places avant soumission, restituer à refus/échec/cancel/résolution, y compris résultats partiels. Ne pas réserver définitivement une part à chaque Consumption ; les autres utilisent la capacité libre dans leur maximum. Une tentative longue occupe sa place sans bloquer les sélections restantes si capacité existe.

Le dispositif Engine de renouvellement doit avoir un accès indépendant aux threads/connexions ; cadence < durée utile du lease/claim avec marge tenant compte de l'attente DB, sans promesse de lease perpétuel sous panne. Une attente du verrou final de S1 par un renouvellement doit être bornée ou évitée de façon compatible avec R10 afin que le claim S2, à échéance proche, soit renouvelé à temps ; la réservation des ressources seule ne prouve pas cette progression. Perte de partition arrête ses Discoveries/nouvelles acquisitions/renewal partition, pas les renouvellements des claims individuels valides.

Équité : opportunités répétées lorsque du travail reste éligible et que les ressources reviennent, pas égalité de débit. Hypothèses minimales de campagne : ensembles testés finis ou charge contrôlée, retour de capacité, transactions/verrous finissant, DB disponible durant fenêtres annoncées, périodes sans crash suffisamment longues. Avec `M_W=1` et deux Consumptions continuellement alimentées, chaque restitution de place doit permettre à la rotation de proposer des occasions répétées aux deux, sans monopolisation indéfinie. Round-robin entre Consumptions ne prouve pas à lui seul l'équité entre slots SKIP LOCKED : R10 impose un parcours circulaire volatil borné et sa démonstration sous contention admissible. Aucun FIFO strict ni égalité de débit.

Mesures : âge du plus ancien éligible, délai entre occasions, pages/tours, références/slots insérés/acquis, échecs CAS/takeovers, durées/attentes DB, renouvellements à temps/en retard, concurrence effective C/Worker. Pas de UUID/token comme labels métriques à forte cardinalité ; traces de preuve privées corrélées séparément. PERF-01 global reste hors certification L2.

## 11. Pilotes et oracles PostgreSQL indépendants

### WRITE réel

Admission interne minimale durable, vraie intention L1, aucune création de slot ; Discovery/acquisition/Engine réels ; autorité EUI explicitement fixture PostgreSQL derrière PotActorAuthority. Pot history/revisions, transition pure, CAS, résultats et publication réels. Jeux Changed (y compris multi-cibles/suppression), Unchanged, Rejected, INVALID_BINDING sans Pot, collision puis réévaluation avec observedVersion initiale. Aucun endpoint HTTP ni consentement qualifié revendiqué.

### READ technique

Dans une autorité READ distincte, persister un effet technique déterministe, par exemple une ligne immuable keyed `(UUID_banc,eventId)` avec valeur attendue calculée par l'oracle ; pas de statut DONE Engine ou table de positions traitées. Utiliser les vrais événements POT/COMMAND_COMPLETION du pilote quand disponibles et des sources explicitement fixtures pour autres familles. L'effet appartient au banc, pas aux quatre tables techniques ou au catalogue métier. Deux définitions techniques indépendantes sur une même publication peuvent montrer commits/retries autonomes. Aucun CurrentPot/Balance/Visibility/Binding/Result/Invitation réel déduit de ces lignes.

### Observations indépendantes

Les familles de preuves et leurs critères PASS/FAIL/INCONCLUSIVE sont détaillés dans le [catalogue des oracles L2](L2_Independent_Test_Oracles.md) et raccordés ligne par ligne au [manifeste](L2_Certification_Coverage_Manifest.md). La revue contradictoire documentaire du catalogue donne des exemples calculés indépendamment ; elle ne remplace ni les essais futurs ni la revue indépendante de G0.

Les assertions ne rappellent pas le sélecteur/mapper/fonction de routage de production pour calculer l'attendu. Une connexion PostgreSQL extérieure, ouverte après résolution, lit directement tables sources, positions/compteurs, K, ownership, slots et effets. Pour chaque scénario, archiver snapshot avant/après, ensemble attendu des UUID/positions/partitions/effets, chronologie de barrières et issue physique. Une jointure d'observation cross-base n'est pas une transaction d'effets ; capturer séparément les deux états stables.

Oracles : (a) sources conservées et ensembles éligibles littéraux ; (b) positions durables exactement 1..M par famille testée, pas d'ordre inféré de recordedAt ; (c) K monotone et chaque p éligible≤K couvert par slot ou effet réellement committé ; (d) compte de résultats/complétions et candidate exactes ; (e) intégralité rollback ; (f) identité réelle connexion/xid/EntityManager, poisoning testé même exception interceptée ; (g) concurrence attendue observée avec barrières et `pg_blocking_pids`, pas par sleeps ; (h) catalogue de contraintes/indexs et requêtes/ResultSet/buffers réellement observés ; (i) aucune nouvelle lecture de chaîne dans WRITE courant, auditeur historique explicite séparé.

Proxy de perte de réponse COMMIT et injecteurs doivent différencier commit réussi/réponse perdue, rollback réussi/réponse perdue et résolution encore inconnue. Une erreur SQLSTATE transport n'est jamais preuve de rollback. Les tokens ne sont gardés que dans assertions/archives privées nécessaires, sans exposition aux métriques.

## 12. Matrice de couverture normative et campagnes

Toutes les lignes ci-dessous sont **À EXÉCUTER**. « Réel » et « technique/fixture » qualifient la future contribution, pas un résultat déjà obtenu. Le [manifeste de couverture](L2_Certification_Coverage_Manifest.md) alloue chaque variante, sous-scénario et report à une méthode prévisionnelle et un oracle ; aucun ID supplémentaire ne remplace un scénario SPEC-10 existant.

| Exigence / variantes exactes | Contribution réalisable en L2 | Limite / report empêchant PASS global |
|---|---|---|
| CON-01 C01-A…H/K/L | Fenêtres sur source réelle POT/COMPLETION et banc READ ; borne sûre, CAS K, softCap, coût | C01-I/J pour COMMAND réel, IDENTITY_REQUEST fixture seulement ; pas de certificat producteur Identity |
| CON-02 C02-A…L | Toutes coupures d'enrôlement et effet technique/slot READ, recovery, refus reset partiel | C02-L reconstruction métier complète READ non livrée ; négatif technique seulement |
| CON-03 C03-A…L | Leases/claims génériques WRITE/READ réels, takeover, horloge post-verrou, concurrence et traces | Pas de promesse de capacité/performance universelle ; toutes variantes génériques restent obligatoires |
| CON-04 C04-A…I/K/L | Pilote réel Command et banc READ, double finaliseur, exceptions, ACK commit perdu | C04-J prouve indépendance technique seulement ; idempotence/versions des projections réelles PRJ différées |
| CON-05 C05-A…M + compléments Q | Command réel et banc, délai fixe, cession unique, N, redémarrage/réactivation, autre slot progressant | Lecture HTTP 200/404/503, CurrentBinding et V/V+1 métier projetés différés ; technique ne remplace pas ces assertions |
| CON-06 C06-A/E…M | Owner physique Command ; erreurs JPA/JDBC dans les deux ordres, composition invalide, 2PC absent, READ technique | C06-B Identity réel absent ; C/D/N/O/P sur vraies projections différés. C06-C/D/P analogues techniques seulement ; C06-M inventaire complet du périmètre réellement livré |
| EVT-01 E01-A…C/E/F/H | POT→COMMAND_COMPLETION réels, compteurs/positions et contention | E01-D lot Identity, E01-E variante Identity, E01-G cinq producteurs, trois ordres de familles : fixtures possibles, producteurs réels absents donc PASS global interdit |
| EVT-02 E02-A…C/F…J | Mutations/no-op/rejets Command réels, fautes/contraintes/recovery | E02-D/E Identity et variante INVITATION→COMPLETION différées ; E02-I négatifs Command seulement tant qu'autres writers absents |
| EVT-03 E03-A | Catalogue exact sept familles/neuf définitions vérifié statiquement | Ce constat de catalogue n'est pas une livraison des neuf transformations |
| EVT-03 E03-C…I | Discovery/indépendance/reset technique avec IDs distincts et banc READ | E03-B trois slots de vraies matérialisations, E03-G/J effets métier/hors ordre et reset READ complet différés. Les analogues techniques sont identifiés comme tels |
| Non-régression L1-C/F/F2 | Tous invariants du §13 sur modèle canonique et owner adapté, oracles PG frais | PASS historiques conservés comme références, jamais importés comme résultats frais L2 |

Critères globaux SPEC-10 demeurent inchangés : CON-01 A…L, CON-02 A…L, CON-03 A…L, CON-04 A…L, CON-05 A…M + compléments, CON-06 A…P, EVT-01 A…H, EVT-02 A…J, EVT-03 A…J. Le mot « applicable » dans A12 impose une allocation explicite et une réserve, pas la suppression d'une variante pour fabriquer un PASS. Voir R6.

| Campagne future | Stimuli et oracle décisif | Gates / preuves attendues |
|---|---|---|
| P1 — Structure/identités/bootstrap | Quatre tables par autorité, enums/UUID, configuration stable, ancienne voie inactive, writers/DDL couverts | Catalogue, inventaire des compositions/imports, vecteurs hash/config, gardes architecture |
| P2 — Non-burnable | Fenêtres inter-partitions, zéro local, capacité coupant fenêtre, deux Discoveries, takeover entre lecture/commit, coupures chaque frontière K/slots, ACK perdu | C01/C02/C06-K ; ensembles SQL littéraux et K avant/après, aucun événement omis |
| P3 — Burnable | PENDING/TERMINAL, slot expiré/retry/quarantaine, source terminalisant pendant sélection, admissions devant/derrière cursor, perte/restart, capacité rétablie | Aucune PENDING éligible oubliée sur tour complet ; aucun réenrôlement TERMINAL ; propriétés fixtures étiquetées |
| P4 — Leases/claims | C03-A…L, lots partiels SKIP LOCKED, ancienne propriété/token, renouvellement expiré, attente réelle au verrou final et expiration après verrou ; renouvellement de S2 à échéance pendant que S1 est bloqué par un autre verrou final | Horloge DB après acquisition effective ; protection jusqu'à résolution ; attente de S1 bornée/évitée et S2 renouvelé avant échéance sous les hypothèses de campagne |
| P5 — Unité physique/publication | R1 ratifié ; deux finaliseurs, toutes fautes précommit, poisoning intercepté JPA/JDBC/validation/wrong manager, suffixe fermé, commits incertains | C04/C06/EVT applicables, identité des ressources, traces d'attente/graphe, états SQL tout ou rien |
| P6 — Budget/recovery/admin | N−1 puis succès, N puis quarantaine, collision/faits incohérents, crash/expiry, ACK cession perdu, ancien token, double admin, réactivation sous softCap plein | Un compte par échec constaté ; zéro budget supposé ; slots incomplets conservés ; autres slots progressent |
| P7 — Capacité/équité/coûts | Consumptions asymétriques, traitements longs, saturation/retour de capacité, polling à vide/progrès, renouvellement sous charge ; S1 verrouillé n'affame pas le renouvellement S2 ; `M_W=1` avec deux Consumptions continuellement alimentées ; source infinie simulée bornée par tours | M_C/M_W jamais dépassés, S2 renouvelé avant échéance, occasions répétées pour les deux Consumptions à chaque restitution, sans FIFO ni égalité de débit ; SQL/lignes/buffers/temps/cycles/mémoire bruts |
| P8 — Régression et clôture majeure | Rejouer garanties L1 sur schéma canonique ; profiler WRITE 100/1000/10000 avec même harnais et croissance appariée ; audits frais | Slices puis architecture/full selon §14 ; manifeste exact, archives/provenance, revue indépendante avant décision humaine |

Une course doit être démontrée par connexions/barrières et état DB, pas par un seul test séquentiel. Chaque répétition et chaque faute conserve son résultat ; ne pas faire disparaître une exécution FAIL après relance réussie. Un blocage DB sans diagnostic est une anomalie, pas un test d'équité concluant.

## 13. Régression L1 : propriétés à transférer, pas tables à reproduire

| Acquis / preuves de référence | Obligation L2 sur nouveau owner/slot |
|---|---|
| C une acquisition/une tentative ; c02/c03/c04, E multi-cibles/observedVersion | Nouvelle acquisition et préparation entière après collision ; observedVersion/intention immuables, pas de retry interne ni faux rejet CAS |
| C c07/c09/c14/c15/c22 ; F f02 | Claim au verrou/heure fraîche, Identity retenue, témoin négatif sans Pot pour INVALID_BINDING, ordre claim→Identity→Pot |
| B/C writer_xid, contraintes et c10/c11/c17/c18/c20 | xid8 complet, ressources physiques identiques, poison même intercepté, pas de transaction indépendante, rollback intégral |
| C c01/c09/c13/c16/c23, E ; F f05 | Résultat/terminal/event/completion/positions/slot atomiques, changedComponents exact huit tables ; suffixe fermé ; incertitude COMMIT et cession |
| F lectures temporelles et auditeur | Reconstructibilité directe à V, NotLoaded ≠ absent, audit historique explicitement hors chemin critique |
| F2 f201…f207 et matrice | Préconditions locales indexées, identités/références et lots groupés, close exact affectant une ligne + insert atomique, anciennes corruptions étrangères réservées à audit, index d'effet ≠ contraintes |
| F2 profil profondeur | Ne pas réintroduire des chaînes transférées en Java ; 28 lignes était un profil historique, pas un seuil universel après ajout du protocole L2. Comparer le coût métier isolé et le surcoût technique séparément |
| Quarantaine/retry/recovery L1-C | Même intention reprise, zéro double effet/complétion, cession unique après rollback certain, N configurable, quarantaine persistante et reset explicite |

Relier dans le futur manifeste chaque propriété à ses méthodes conservées/adaptées et à ses nouveaux points d'injection. Ne pas exécuter les collecteurs L1 avec exclusions élargies pour obtenir artificiellement une provenance L2 PASS ; leurs listes figées restent intactes.

## 14. Vérification future, gates, provenance et critères de sortie

### Déclaration prospective avant toute implémentation autorisée

Impact attendu : domain-consumption, engine-consumption, orchestrateurs, adaptation Pot C/ports/guards, adaptateurs PRIMARY et READ, composition et raccords worker nécessaires, schéma V3 technique reconstruit. **Primaire COMMAND ; secondaires EVENT et PROJECTION**, à reconfirmer par graphe réel et localisation du banc. Interdit : autorité Identity réelle, projections métier/API, tâches/LKV V3 autonomes, nettoyage général et lots suivants. BINDING/WEB/LKV non sélectionnés par simple dépendance transitive. Si leur comportement doit changer, expliquer/redéclarer le franchissement avant code. FOUNDATION ne couvre que son infrastructure de test L0.

Commandes canoniques futures, depuis `app/`, **non exécutées dans ce mandat** :

```bash
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl runtime-event-consumption-worker -am test
./mvnw -pl runtime-task-consumption-worker -am test
./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
./mvnw test
```

La commande composée peut remplacer les trois invocations séparées au gate commun ; ne pas les compter comme couvertures distinctes. **Architecture globale requise** à la future clôture L2 et aux changements de frontières/dépendances/guards. **Full reactor requis à la future clôture majeure L2**, selon A12 ; pas à chaque sous-étape. Aucun -amd par défaut. Le graphe doit démontrer que le banc READ et ses tests sont réellement inclus ; sinon déclarer une ancre complémentaire explicite avant campagnes, pas un PASS par absence de tests.

DB future : CURRENT V3 sur schémas/environnements neufs explicitement isolés, WRITE et READ distincts ; bootstrap/locations fixés dans le plan d'exécution approuvé, aucun schéma préexistant détruit. Aucune baseline V2 TRUSTED présumée. Pas de campagne historique séparée par défaut ; si les suites transitives des gates appliquent PRIMARY/READ historiques, le rapport le dira. Baseline/histoire de migrations sont des preuves distinctes du protocole L2.

### Séquence de préparation, implémentation et qualification

**P0 précède G0 et reste documentaire.** P0 produit le [manifeste exhaustif](L2_Certification_Coverage_Manifest.md), la matrice des exigences/variantes/reports, les décisions et contrats consolidés, les signatures conceptuelles et responsabilités du §3, le périmètre de modules des §§4/15, la matrice des dépendances ci-dessous et les hypothèses/paramètres bloquants avant code. La revue du DDL et des FK/triggers L1 existants appartient à P0. Les paramètres purement opérationnels sont fixés avant leur campagne, dans les plages et sous les responsabilités identifiées à P0. G0 vérifie ces artefacts et requiert une décision humaine explicite ; il ne consomme aucune preuve du futur code L2.

Ces unités internes restent toutes dans L2 ; elles ne lancent aucun lot suivant. Réviser le scope avant chaque unité si le graphe réel diffère.

| Ordre | Travail futur et condition de fin locale | Dépendance |
|---|---|---|
| P0 | Manifeste, matrices, signatures conceptuelles, modules, dépendances/gates et paramètres pré-code ; contrôle de cohérence | Décisions A01–A12/R1–R10 acquises |
| G0 | Revue indépendante de P0, validation/audit L1-F2 et autorisation humaine d'implémentation ; aucune preuve du futur code | P0 |
| I1 — CLOSED / LOCAL BOUNDED PASS | Valeurs pures/familles/identités, quatre tables par autorité et bootstrap administratif du routage R3, environnement neuf, contraintes/index, anciens writers incompatibles désactivés dans composition pilote ; aucun slot dédié actif | G0, R3/R7 |
| I2 — CLOSED / LOCAL BOUNDED PASS | Owner physique commun et participation Command, guards/poison, suppression protocolaire suivant R1, adaptation des contraintes terminales ; régressions C/F/F2 avant nouveau polling | I1, R1/R9 |
| I3 | Leases, acquisition/renouvellement/capacité et cession/quarantaine/admin communes ; courses PostgreSQL ciblées avant intégration des boucles | I2, R2/R5 |
| I4 | Discovery non-burnable et burnable, préfixe/capacité, tour volatil et revalidation de PENDING ; aucune perte dans P2/P3 | I3, R4 |
| I5 | Deux boucles et raccord explicite Command réel ; banc READ technique avec owner propre et effets réels ; renouvellement indépendant sous saturation | I4, R10 |
| G1 | Qualification locale P1–P6 des mécanismes livrés et slices déclarées ; preuves recueillies dès l'étape productrice | I1–I5 |
| G2 | Pilote intégré, P7/P8 techniques, interleavings, transactions physiques, progression, mesures et régressions bornées | G1, I5 |
| I6 | Consolider campagnes, coûts et provenance ; corriger puis rejouer les contrôles affectés ; dossier de verdicts bornés | G2 |
| I7 | Soumettre le dossier à l'audit indépendant et intégrer les corrections vérifiées | I6 |
| G3 | Gates globaux, régressions, preuves, audit indépendant et décision humaine de clôture ; aucun ACCEPTED automatique | I7 |

### Gates et checkpoint

| Gate | Condition de passage |
|---|---|
| G0 — avant code | Cohérence et complétude documentaire P0, revue indépendante, L1-F2 audité/validé, puis autorisation humaine distincte ; aucun code ou essai PostgreSQL L2 exigé |
| G1 — preuve locale future | P1…P6 sur composants réels/fixtures identifiés, méthodes/oracles du manifeste, slices adaptées au sous-périmètre ; zéro invariant régressé |
| G2 — qualification technique future | P7/P8, trois slices concernées, interleavings, transactions physiques, progression, mesures et provenance du pilote intégré |
| G3 — clôture majeure | Architecture et full reactor requis, régressions, dossier de preuves, audit indépendant et corrections vérifiées ; décision humaine explicite et bornée avant L2 ACCEPTED |

### Dépendances des obligations et des gates

| Obligation | Artefact attendu | Étape productrice | Gate consommateur | Prérequis |
|---|---|---|---|---|
| Décisions, invariants, signatures conceptuelles et responsabilités | Consolidation R1–R10, registre A01–A12, contrats conceptuels | P0 | G0 | Sources normatives et L1 existants |
| Couverture exhaustive, reports et méthodes prévisionnelles | Manifeste et contrôle de complétude | P0 | G0 | SPEC-10, R1–R10, L1-C/F/F2 |
| Périmètre, hypothèses et paramètres à fixer avant code | Modules/owners, matrice variantes, paramètres bloquants | P0 | G0 | Contrats conceptuels |
| R1 : ordre et contraintes existantes | Analyse documentaire de L1, FK/triggers et injections prévues | P0 | G0 | Contrat L1-C et DDL existant |
| R3/R7 : installation et routage | DDL/contrat administratif, vecteurs et writers raccordés | I1 | G1 | G0 |
| R1/R9/CON-06 : unité physique, rollback, poisoning | Code/DDL, identité des ressources et oracles PostgreSQL | I2 | G1 | I1 |
| R2/R5 : claims, softCap, cession, quarantaine | SQL, courses et résultats durables P4/P6 | I3 | G1 | I2 |
| R4/CON-01/02 : Discovery et tour burnable | SQL/barrières P2/P3, ensembles source/slots indépendants | I4 | G1 | I3 |
| R10 : clés sautées, renouvellement S1/S2 et M_W=1 | SQL/barrières/horloges DB et mesures P4/P7 | I5 | G2 | I4 et mécanismes I3 |
| Pilote WRITE/READ intégré | Effets, P7/P8, provenance et coûts bruts | I5 | G2 | G1 |
| Clôture majeure | Régressions, gates architecture/full, audit, décision humaine | I6–I7 | G3 | G2 |

Ordre topologique : **P0 → G0 → I1 → I2 → I3 → I4 → I5 → G1 → G2 → I6 → I7 → G3**. Les preuves locales peuvent être recueillies dès leur étape productrice ; leur consommation formelle reste G1/G2. Aucun artefact futur ne conditionne G0. Les preuves documentaires disponibles avant code y restent.

Le mandat de préparation initial s'arrêtait avant G0. La décision humaine du 26-10-2026 lève explicitement la réserve procédurale L1-F2 et autorise I1 seul, avec les limites des preuves historiques conservées. Aucun audit historique n'est réécrit.

### Provenance future

Avant campagnes : figer SHA propre, méthode par scénario/variante, oracles indépendants, paramètres/seed/jeux de données et modes fichiers. Archiver versions Java/Maven/PostgreSQL/Docker, digest image, OS/architecture, classpaths/dépendances utilisés, configurations non secrètes (UUID/routage/P/capacités/N/délai/polling), commandes exactes et points de coupure. Collecter XML frais avec présence de chaque méthode obligatoire, logs bruts/SQL/chronologies/plans/données avant-après, codes retour et skips. Conserver SHA256/reçus d'archives, comparaison d'entrées avant/après tests puis code testé→livraison→tête distante. Aucun nom d'archive ou SHA de campagne future inventé maintenant.

**PASS d'une contribution** : toutes variantes obligatoires de son périmètre explicite exécutées sur PostgreSQL réel, oracle indépendant satisfait, aucune méthode manquante/skip obligatoire, provenance concordante. **FAIL** : invariant violé, effet partiel/double/perdu, fence ou budget incorrect, writer secondaire actif, preuve contredite. **INCONCLUSIVE** : méthode/variante absente, environnement indisponible, ordre concurrent non démontré, provenance insuffisante, producteur/projection absent ; préciser ce qui manque. BUILD SUCCESS seul ≠ PASS de certification. Même une contribution L2 PASS n'est ni PASS global d'une exigence incomplète ni L2 ACCEPTED.

**Critère documentaire de reprise autoportante :** l'agent doit pouvoir retrouver sources, décisions/options, inventaire/owners, prérequis ouverts, protocoles, scénarios/limites, gates et artefacts depuis ces fichiers uniquement. Les paramètres ou décisions ouverts sont des entrées à obtenir, jamais des hypothèses de code à inventer. Les contrôles de livraison documentaire et les décisions encore nécessaires sont consignés dans la revue.

## 15. Annexe — inventaire exact des modules

Les 60 modules ci-dessous sont extraits statiquement de `app/pom.xml` à la référence de départ, sans exécuter Maven. Une destination vise le chemin V3 pertinent ; DEFER n'autorise pas l'activation d'un ancien writer contre le nouveau schéma. Aucun POM ou module modifié dans ce mandat.

| Module existant | Destination L2 / périmètre |
|---|---|
| `domain-authorization` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `domain-event` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `domain-user-identity` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `contracts-authentication` | REUSE — acquis/contrat utile, pas nouvelle capacité métier |
| `domain-pot` | REUSE — acquis/contrat utile, pas nouvelle capacité métier |
| `domain-projection` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `domain-consumption` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `port-transaction` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `port-binding-authority` | REUSE — acquis/contrat utile, pas nouvelle capacité métier |
| `engine-consumption` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `engine-consume-command` | REPLACE — parcours V3 par Consumption Pot, ancien exécuteur non raccordé |
| `supra-consume-command` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `supra-consume-command-result` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-read-command-result` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-materialize-command-result` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `contracts-command` | REUSE — acquis/contrat utile, pas nouvelle capacité métier |
| `contracts-registration` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-admit-registration` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-consume-registration` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `supra-consume-registration` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `supra-consume-registration-result` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-read-registration-result` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-materialize-registration-result` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-produce-projection-task` | RETIRE du chemin V3 ; retrait physique général DEFER |
| `engine-write-pot` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `port-projection` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-read-projection` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-consume-projection-task` | RETIRE du chemin V3 ; retrait physique général DEFER |
| `supra-consume-projection-task` | RETIRE du chemin V3 ; retrait physique général DEFER |
| `engine-read-pot` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `projector-pot` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-read-current-binding` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-materialize-current-binding` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `engine-materialize-latest-known-version` | RETIRE du chemin V3 ; retrait physique général DEFER |
| `infra-persistence-read-jdbc` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `contracts-observability` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `infra-tx-spring` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `infra-persistence-primary-jpa` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `infra-persistence-projection-jdbc` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `infra-projection-validation-networknt` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `orchestrator-consumption` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `engine-admit-command` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `orchestrator-poll-consumption` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `supra-consume-event` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `supra-consume-binding` | RETIRE du chemin V3 ; retrait physique général DEFER |
| `supra-consume-lkv` | RETIRE du chemin V3 ; retrait physique général DEFER |
| `supra-http-write` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `supra-http-read` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `composition-runtime-spring` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `runtime-monolith` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `runtime-web-api` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `runtime-event-consumption-worker` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `runtime-command-result-consumption-worker` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `runtime-registration-result-consumption-worker` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `runtime-registration-consumption-worker` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `runtime-latest-known-version-consumption-worker` | RETIRE du chemin V3 ; retrait physique général DEFER |
| `runtime-binding-consumption-worker` | DEFER — capacité hors L2, empêcher raccord incompatible au nouveau schéma |
| `runtime-task-consumption-worker` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `runtime-command-consumption-worker` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |
| `architecture-tests` | ADAPT — chemin commun/pilote/banc ou garde, selon inventaire §4 |


## Décisions R1–R10 ratifiées — supersession des propositions ouvertes (2026-10-26)

Le [contrat normatif consolidé](L2_R1_R10_Normative_Consolidation.md) fait foi pour les dix points R1–R10. Les passages antérieurs présentant R1, R3, R4, R5, R9 ou R10 comme ouverts sont historiques et supersédés. Le modèle en vigueur comporte **quatre** tables par autorité, dont `consumption_routing_contract` initialisé administrativement. Le [manifeste](L2_Certification_Coverage_Manifest.md) alloue les variantes SPEC-10 et obligations R1–R10 ; ses méthodes restent prévisionnelles. Les analyses documentaires signalées au §12 de la consolidation conditionnent G0, tandis que SQL, barrières et mesures du futur code conditionnent G1/G2. Aucune preuve n'est acquise par cette mise à jour.


## 16. Passage I1 clos → I2 réalisé et vérifié localement

Le mandat humain de clôture accepte I1 sur sa portée bornée : [statut définitif et recontrôle de provenance](L2_I1_G0_Readiness_and_Implementation_Plan.md#10-clôture-définitive-i1-et-préparation-i2--26-octobre-2026). Aucune campagne I1 relancée ; aucune modification de code pour clôturer. La contribution technique et les régressions L1 ne constituent pas le franchissement G1/G2/G3 ni l'acceptation globale L2.

Le [plan I2 dédié](L2_I2_Implementation_Plan.md) développe l'étape du §14 sans changer A01–A12/R1–R10 : owner commun, participant Command, suppression R1 avant suffixe et observation R9 ; entrée déjà claimée en fixture, aucun moteur réel d'acquisition/cession I3. COMMAND primaire, preuves directes owner/monolithe et architecture globale requise à la future réalisation ; full différé au gate majeur sauf impact réellement non bornable. Recommandation GO technique pour la réalisation bornée, après audit et instruction distincte. **Arrêt documentaire avant tout code I2.**

## 17. Clôture locale I2 — statut courant

GO humain d’implémentation reçu après audit du plan simplifié. [Dossier I2](L2_I2_Execution_Report.md), [inventaire de preuves](L2_I2_Proof_Manifest.json) et [archives](../testing/evidence/L2-I2/Gate-index.json) : Engine transactionnel, participant Command, R1/R9 et protections L1 repris, quatre commandes canoniques PASS au SHA final. COMMAND primaire ; owner/monolithe en preuves directes ; architecture globale requise/exécutée ; aucun franchissement inattendu ; full reactor non requis/non exécuté pour cette tranche bornée.

**I2 CLOSED / LOCAL BOUNDED PASS**, audit indépendant du code et des preuves à venir. Les mentions prospectives historiques du présent plan ne révoquent pas cette réalisation ; elles restent applicables aux étapes non livrées. Aucun I3, worker actif, G1/G2/G3 ou ACCEPTED global L2 engagé.
