# Pocoma V3 — L2 / I5 — Plan d’implémentation des Workers

**28 octobre 2026 — I5 DESIGN UNDER REVIEW — IMPLEMENTATION NOT AUTHORIZED.**

Ce mandat livre uniquement ce plan. Aucun Java, SQL, test, POM, profil ou réglage productif I5 n’est créé/modifié ; aucune campagne Maven/PostgreSQL n’est exécutée. Toutes les méthodes et commandes futures ci-dessous sont **NOT RUN**. Une revue documentaire n’est ni une preuve PostgreSQL, ni une certification globale, ni un GO de code.

## 1. Statut, provenance et sécurité Git

Dépôt `kartaguez/pocoma`, remote `https://github.com/kartaguez/pocoma.git`, destination exclusive `feat/v3-architecture`. HEAD distant réellement interrogé par `git ls-remote origin refs/heads/feat/v3-architecture` : **`e28645d3700e3dfdb198c5260333d382da58a7b5`**. Il égale la référence historique du mandat : delta à analyser = aucun. HEAD de conception et parent attendu du commit documentaire : ce même SHA. Le SHA du document publié est celui de `git log -1 --format=%H -- docs/v3/L2_I5_Implementation_Plan.md`, communiqué au reçu de livraison ; aucune auto-référence circulaire inventée.

Le checkout principal est sur `feat/runtime-monolith`, `90a3e9bc1a1fdd325d0c36e543824239cd85a431`, avec modifications suivies Keycloak/docs/Bruno et fichiers non suivis préexistants, dont `docs/v3/`, études et test PostgreSQL. Il reste intégralement préservé. `git worktree list --porcelain` a été inspecté : la branche cible est déjà dans `/private/tmp/pocoma-v3-postaud`, propre au départ, au HEAD distant. Ce worktree adapté est réutilisé ; aucun autre checkout n’est déplacé, archivé, nettoyé ou réinitialisé. Aucun stash, reset destructif, clean ou force push.

Le premier accès réseau sandboxé a échoué en DNS ; la lecture distante autorisée hors sandbox a ensuite réussi. Ne pas assimiler l’échec initial à une vérification du distant. Avant publication : relire distant, statut et diff ; si la tête a bougé, examiner le delta documentaire **et** applicatif avant de réconcilier le seul plan et refaire la revue. Push normal uniquement. Le présent mandat ne modifie ni le plan directeur ni les manifestes historiques : le seul fichier publiable est celui-ci.

Le mandat humain courant confirme I1–I4 réalisés, audités et validés pour poursuivre. Les rapports du dépôt conservent leurs anciens libellés « audit pending » : cette confirmation provient du mandat, sans prétendre avoir retrouvé ou reproduit un nouvel audit. Aucun G1/G2/G3 ou ACCEPTED L2/global V3 n’est déduit de ce statut.

## 2. Sources directes et hiérarchie

Les sources suivantes ont été consultées dans le worktree de référence, avec le code, les POM, profils et scripts correspondants ; les rapports de synthèse seuls ne gouvernent pas la conception.

| Autorité | Sources et emploi |
|---|---|
| Règles du dépôt | [AGENTS.md](../../AGENTS.md), [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md) : scope avant code, slices/gates et preuve courante distincte de l’histoire |
| Programme/normes | [Canon](V3_Canon_and_Roadmap.md), [plan directeur](V3_Implementation_Master_Plan.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-07](SPEC-07_Transverse_Requirements.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md) |
| Décisions L2 | [Plan L2](L2_Design_and_Verification_Plan.md), [registre A01–A12](L2_Decision_Register.md), [R1–R10](L2_R1_R10_Normative_Consolidation.md) : R3 amende A03 à quatre tables ; R1–R10 supplantent les propositions historiques contradictoires |
| Preuves à préserver | [Oracles indépendants](L2_Independent_Test_Oracles.md), [couverture L2](L2_Certification_Coverage_Manifest.md) : allocations conservées, aucun report effacé |
| I2 livré | [Plan I2](L2_I2_Implementation_Plan.md), [rapport I2](L2_I2_Execution_Report.md), [preuves I2](L2_I2_Proof_Manifest.json) ; signatures actuelles vérifiées directement |
| I3 livré/corrigé | [Plan I3](L2_I3_Implementation_Plan.md), [rapport I3](L2_I3_Execution_Report.md), [preuves I3](L2_I3_Proof_Manifest.json), [corrections post-audit](L2_I3_Post_Audit_Corrections_Report.md), [preuves corrigées](L2_I3_Post_Audit_Proof_Manifest.json) ; R-AUD-I3-03 reste à fermer en I5 |
| I4 livré | [Plan I4](L2_I4_Implementation_Plan.md), [rapport I4](L2_I4_Execution_Report.md), [preuves I4](L2_I4_Proof_Manifest.json) : 49 méthodes physiques, sources réellement disponibles et limites des fixtures |

Les normes gouvernent les invariants ; le code et les clôtures établissent le livré ; un plan décrit le futur ; un manifeste historique n’est pas une exécution fraîche I5. Les anciens en-têtes « aucun Engine/test V3 » et G0 non validé décrivent leurs dates de rédaction. Ils n’annulent pas les mandats ultérieurs et n’autorisent pas I5. L’orientation mono-pool du plan I3 §8 était une proposition de ressources : la décision I5 §14 la remplace explicitement, sans changer ses protocoles transactionnels.

## 3. Inventaire réel et matrice de réemploi

Convention de chemins, utilisée aussi §25 : `J(module)/x` signifie exactement `app/module/src/main/java/com/kartaguez/pocoma/x`, `T(module)/x` le même chemin sous `src/test/java`, `R(module)/x` sous `src/main/resources`. PRIMARY=`infra-persistence-primary-jpa`, READ=`infra-persistence-read-jdbc`, COMPOSITION=`composition-runtime-spring`, POLL=`orchestrator-poll-consumption`, COMMAND=`runtime-command-consumption-worker`, MONOLITH=`runtime-monolith`. Ces alias ne désignent pas de nouveaux modules.

| Composant / chemin réel | Contrat réel au HEAD | Owner | Décision | Justification / adaptation limitée future |
|---|---|---|---|---|
| `J(engine-consumption)/engine/consumption/v3/ConsumptionEngine.java` | `acquirePartition`, `renewPartitions`, `renewClaims`, `acquire`, `execute`, `abandon`, `settlePending`, `observe` ; aucune boucle | Engine I2/I3 | ADAPT | Garder le protocole ; injecter séparément owner/stores contrôle et effets, navigation et observation opérationnelle |
| Même package, `DiscoveryEngine.java` | `discoverEvents(lease)`, `discoverRequests(lease,walk)` ; références, préfixe K, softCap et RequestWalk | Engine I4 | REUSE | Corps transactionnels inchangés ; observation de frontières seulement, propriétaire de transaction injecté sur contrôle |
| Même package, `WorkerCapacity.java` | Une instance Worker ; réservation synchronisée de min(M_W restant,M_C restant,wanted), tickets internes et release idempotent | Engine | REUSE / ADAPT observation | Aucun second ticket/sémaphore M_C/M_W ; ajout éventuel du seul accès RO de réservation §17 et des hooks métriques |
| Même package, `LifecycleSettings.java` | B, lease/claim/cadence, lock/statement, RetryPolicy immutable ; `pilot()`=32/30s/60s/10s/500ms/2s/N3/d30s | Composition | REUSE | Point de départ expérimental ; validation supplémentaire des marges runtime, sans changer rétrospectivement les échéances |
| `.../v3/port/ConsumptionSlotStore.java` | Acquisition par clés, renew, cede, inspectResolved, protectFinal, deleteProtected, observe | Engine/adapter | REUSE | Navigation dans un **autre** port ; mêmes CAS/horloge/DELETE et quatre résultats R9 |
| `.../v3/port/PartitionOwnershipStore.java` | acquire/renew/protectAcquisition/bound ; aucun release administratif | Engine/adapter | REUSE | Leases laissés à expiration à l’arrêt ; pas nouveau protocole de release |
| `.../v3/ConsumptionParticipant.java` | `Optional<Prepared> prepare(UUID)` ; observe RO ; finalizeIn/requireCompletionReady | Métier ou probe identifié | REUSE | Jamais owner de transaction ni de budget ; ne pas citer l’ancien `Prepared prepare` du plan I2 comme API actuelle |
| `J(port-transaction)/port/transaction/v3/AttemptTransactions.java` | read RR RO / write RC, poison, requirePublicationClosed ; Failure avec résolution | Owner physique | REUSE UNCHANGED | Pas nouvel owner abstrait, pas router de transactions, pas transfert inter-thread |
| `J(infra-tx-spring)/infra/tx/spring/v3/SpringAttemptTransactions.java` | Refus d’ambiance ; identité exacte DS/TM/EMF ; garde holders/synchronization/thread ; poisoning et résolution physique | Spring | REUSE UNCHANGED | Une instance par DS/TM injectée ; plusieurs instances **non imbriquées** sur la même DB avec des pools distincts sont possibles |
| `J(PRIMARY)/infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java` | SKIP LOCKED par clés ; CAS ; clock_timestamp post-lock ; finalisation/inspection/cession gardées par la DS | Adapter | REUSE UNCHANGED | Deux instances typées par pool de la même autorité ; final store sur métier, technical store sur contrôle |
| Même package, `JdbcPartitionOwnershipStore.java` | Routage validé, verrou partition, heure DB, tokens ; timeouts techniques PostgreSQL17 | Adapter contrôle | REUSE UNCHANGED | Ne pas le raccorder au pool métier ; aucune propriété finale de partition ajoutée |
| Même package, `JdbcDiscoveryAdmissionStore.java` | COUNT non-Q, INSERT initial strict, K attendu/CAS sous partition ; pas owner caché | Adapter contrôle | REUSE UNCHANGED | P=4 explicite ; ni compteur durable, ni nouvelle décision d’admission |
| Même package, `JdbcCommandDiscoveryReferences.java` | U/page PENDING UUID bruts, puis tryProtectPending SKIP LOCKED ; aucun payload/claim | Source WRITE | REUSE UNCHANGED | Source/owner contrôle WRITE identiques pendant admission burnable |
| Même package, `JdbcEventDiscoveryReferences.java` | POT et COMMAND_COMPLETION seulement ; compteur/positions/enveloppe dans même RR RO ; autres familles refusées | Source WRITE | REUSE UNCHANGED | Pool WRITE de lecture métier, appel fermé avant transaction READ ; pas fallback V2 |
| `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` | Factories inactives ; garde DS réelle/Hikari max déclaré et M_W+4 ; classification conservative | Composition | ADAPT | M_W+4 **ne réserve rien** ; remplacer ce contrôle dans la nouvelle surcharge par l’inventaire effectif §14 ; conserver l’ancienne factory de preuve |
| Même package, `V3ConsumptionDiscoveryComposition.java` | Factories request/event inactives ; privilèges combinés explicites ; même owner/DS | Composition | ADAPT | Assembler le pool contrôle réel ; accepter les seules définitions techniques enregistrées en mode dev explicite |
| Même package, `V3ConsumptionCatalogConfiguration.java` | 9 UUID/P4 ; profil `v3-consumption-pilot` et refus de tout composant actif | Catalogue/I1 | REUSE UNCHANGED | `definitions()` réutilisée statiquement ; ne pas importer sa configuration inerte dans le profil actif |
| Même package, `V3BootstrapGuard.java` | Cibles TCP/base/schema/rôle ; WRITE≠READ ; droits effectifs, routage exact, partitions, hash ; requireInertType | Garde composition | ADAPT | Nouvelle entrée worker explicite, validation de chaque pool et whitelist active fermée ; règles I1 inchangées |
| `J(COMPOSITION)/runtime/command/v3/V3PotCommandComposition.java` | Vrai ExecutePotCommand, store/history/revisions/observation sur même DS/owner ; filtre exclusions avant spécialisation Collision | Composition Command | ADAPT | Surcharge métier+contrôle ; aucune duplication du participant ou de son classificateur |
| `J(engine-write-pot)/engine/write/pot/v3/ExecutePotCommand.java` et writers `J(PRIMARY)/infra/persistence/primary/jpa/v3/` | Participant réel L1/I2, slot→Identity→Pot, résultat→complete→publication fermée ; observation métier | Pot | REUSE UNCHANGED | C/F/F2 et suffixe conservés ; Identity fournie par fixture explicitement, jamais autorité L3 |
| `T(PRIMARY)/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresFixture.java`, `DiscoveryPostgresFixture.java` | vrais effets READ `fixture_effect`, callbacks gardés, completion/close/observe ; authority Identity de test | Banc technique | ADAPT / REUSE | Extraire un petit probe dev séparé, sans dépendance productive sur des classes JUnit ni prétention de projection |
| `J(POLL)/orchestrator/poll/consumption/ConsumptionPollingWorker.java` | Thread permanent **séquentiel**, `ConsumptionOrchestrator.run`, budget/Locator V2, pas d’exécuteur métier/renewals | Ancien runtime | REPLACE sur voie V3 | Modifier en framework universel imposerait des résultats/ports V2 aux boucles V3 ; garder le fichier pour les autres runtimes |
| Même package, `wait/ConsumptionWaiter.java`, `wait/ConditionConsumptionWaiter.java` | await interruptible/signal par Condition, sans données durables | Boucle locale | REUSE UNCHANGED | Une instance par attente ; bornes temporelles et signal de stop testés dans le nouveau superviseur |
| `orchestrator-consumption` : SequentialConsumptionOrchestrator, locators, wrappers fenced/owner | Discovery/exécution liées, contrats/états historiques | V2 | DEFER / REPLACE raccord V3 | Présents mais exclus du profil I5 ; pas réécriture générale avant L7 |
| `J(COMPOSITION)/runtime/command/consumption/{CommandConsumptionRuntimeConfiguration,CommandConsumptionWorkerLifecycle,MicrometerConsumptionPollingWorkerObservation}.java` | Beans Acquire/Execute/HandleFailure historiques ; SmartLifecycle autour du poller ; métriques outcomes V2 | Spring V2 | ADAPT principe / DEFER fichiers | Réutiliser les conventions Actuator/Micrometer/SmartLifecycle, pas leurs types de résultat ; nouveaux raccords ciblés V3 |
| Configurations `runtime/{event,task/consumption,binding,registration,registrationresult,result}/` | Event/ProjectionTask/Binding/Registration/Result historiques ; pas participants V3 | V2 | DEFER | Aucun type historique n’autorise l’activation d’une Consumption canonique absente |
| Racines COMMAND/MONOLITH et profils `application-v3-consumption-pilot.properties` | Legacy scan sous `!v3-consumption-pilot` ; pilote sans DS auto/Flyway/JPA/HTTP, catalogue seul | Racine Spring | ADAPT racines / REUSE profil | Exclure aussi Legacy sous profil I5 ; refuser combinaison des deux profils |
| `J(READ)/infra/persistence/read/jdbc/ReadStore{Access,Migration}AutoConfiguration.java` | Beans historiques conditionnels à DS/TM ; migrateur READ historique activable ; une DS non qualifiée | Auto-configuration historique | DEFER / EXCLUDE I5 | Plusieurs pools n’en font pas une composition V3 ; exclusion explicite, pas de @Primary qui masque l’ambiguïté |
| POM COMMAND, POLL, COMPOSITION, MONOLITH | POLL dépend orchestrator-consumption mais pas directement Engine ; COMPOSITION optional READ ; COMMAND ne reçoit pas toutes les optional transitives | Maven | ADAPT arêtes ciblées | POLL→engine-consumption ; COMMAND→READ pour migrations/banc ; aucun nouveau module/cycle |
| `scripts/v3/{bootstrap-l2-routing,grant-l2-lifecycle,grant-l2-discovery,reactivate-l2-slot}.sql` | Bootstrap admin exact 9/P4, grants union explicites, CAS Q ciblé ; pas init runtime | Admin | REUSE UNCHANGED | Sur installation neuve ; extensions dev séparées, pas de droits DDL runtime |

Aucun exécuteur de tentatives borné ni politique de rejet V3 n’a été trouvé dans ces compositions. Les Workers historiques créent leur thread de polling ; les composants nommés Executor/Service de projection sont des services métier, pas une réserve de threads/connections. Les pools historiques proviennent des auto-configurations Boot ; ils ne sont pas les quatre pools I5 proposés. Actuator/Prometheus sont des dépendances existantes, pas une preuve du branchement V3.

## 4. Catalogue : neuf Consumptions et disponibilité

Les sept familles restent exactement COMMAND, IDENTITY_REQUEST, POT_EVENT, INVITATION_EVENT, IDENTITY_EVENT, COMMAND_COMPLETION_EVENT, IDENTITY_COMPLETION_EVENT. P=4 et sha256-v1 restent communs par famille ; UUID natifs, pas chronologie UUID.

| Consumption et UUID stable vérifié | Autorité effets | Source productive livrée | Participant productif livré | Activation I5 dev |
|---|---|---|---|---|
| Execute Command `98f7268d-d340-581c-b83d-010926223e14` | WRITE | v3_command PENDING | ExecutePotCommand réel ; Identity fixture requise | Oui, sous opt-in fixture Identity déclaré |
| Execute IdentityRequest `2999e75c-272d-5afe-a966-c9364ef021fe` | WRITE | Non | Non | Non, L3 |
| MaterializePotVersion `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | READ | POT_EVENT réel | Non | Non, L4 |
| MaterializePotBalance `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | READ | POT_EVENT réel | Non | Non, L4 |
| MaterializePotVisibility `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | READ | POT_EVENT réel | Non | Non, L4 |
| MaterializeInvitation `924cd9e3-435d-5804-9d1e-c6aecadb9ab4` | READ | Non | Non | Non, L5 |
| MaterializeCurrentBinding `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | READ | Non | Non | Non, L3/L4 |
| MaterializeCommandResult `63664303-d33e-5d42-8cce-c4dae8023311` | READ | COMMAND_COMPLETION_EVENT réel | Non | Non, L4 |
| MaterializeIdentityRequestResult `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | READ | Non | Non | Non, L3/L4 |

Déclaré au catalogue ≠ activé. Refuser toute entrée enabled sans source **et** participant compatibles, sans fallback vers un no-op, une fixture ou une classe historique. Une désactivation n’efface ni routage ni slots/K.

Banc READ dev distinct, **aucune dixième Consumption métier** : deux définitions techniques explicitement nommées `I5ReadPotProbe` (`a5000000-0000-4000-8000-000000000001`, POT_EVENT) et `I5ReadCompletionProbe` (`a5000000-0000-4000-8000-000000000002`, COMMAND_COMPLETION_EVENT), READ/P4/sha256-v1. Elles utilisent les vraies sources I4 et une table de fixture `(consumption_id,consumable_id,value)` ; leurs effets n’ont aucune signification PotVersion/Balance/Result. Installation administrative dev séparée, immuable, contrôlée §18 ; aucune substitution de ces probes aux sept UUID métier READ. Les familles absentes ne sont exercées que par les fixtures de certification I4 héritées, jamais annoncées disponibles dans le runtime.

## 5. Frontière I5 et exclusions

I5 futur assemble activement I2/I3/I4 : deux boucles, rotation C/p, tour des slots, claims/dispatch, capacité unique, pools/threads effectivement réservés, renewals centraux, drainage/reprise/takeover, observation opérationnelle, Worker de développement et campagne PostgreSQL/runtime. Command est réel dans les limites du participant livré ; le READ et Identity restent techniques.

Exclus : autorité EUI/Identity L3 ; projections complètes L4 ; Supras HTTP, consentement/Invitation et Bruno métier L5 ; reconstruction/READY, SLO et performance intégrée globale L6 ; suppression/convergence historique L7 ; distribution finale L8. Aucun nouveau schéma de coordination, compteur durable, état UNKNOWN SQL, journal de tâches, dépendance entre slots, LISTEN/NOTIFY obligatoire, scheduler distribué ou priorité.

## 6. Architecture retenue et alternatives

| Alternative | Analyse avec le code existant | Décision |
|---|---|---|
| A : superviseur exécutant Discovery puis Consumption séquentiellement | Proche du poller historique, mais acquisition/préparation/DB longue retarde Discovery ; exécuter le métier ailleurs et ajouter des renewals recréerait C sans son indépendance explicite | Rejetée |
| B : scheduler par C/p | Jusqu’à 36 couples canoniques, davantage avec probes ; multiplie états d’arrêt, scans et contentions du même WorkerCapacity ; aucune utilité durable, n’assure pas l’équité M_W=1 | Rejetée |
| C : deux boucles, renewals dédiés et métier borné | Correspond à SPEC-05 et aux Engines déjà bornés ; attentes Condition réutilisables, SQL/fencing restent dans adaptateurs ; ressources comptables explicitement | **Retenue** |

Répartition proposée : quatre petites responsabilités dans POLL, sans nouveau module : superviseur `V3ConsumptionWorker`, `V3DiscoveryLoop`, `V3ConsumptionLoop`, `V3RenewalService`. Le dispatch est une méthode interne de la boucle Consumption ; pas un nouveau service de tickets. Records imbriqués pour rattachement C/Engine/Discovery/navigation ; pas de framework de tâches. Spring/Micrometer/Hikari restent dans COMPOSITION. Aucun SQL dans POLL, aucun Engine connaissant Pot/Identity/projection.

## 7. Responsabilités, mémoire locale et concurrence

Le superviseur construit le cycle local, possède les threads/exécuteurs et les références de handles, déclenche start/drain et supervise leur santé. Il ne décide ni acquisition durable, ni cession, ni résultat métier. Les deux boucles possèdent chacune leur position round-robin et leurs tours ; elles n’attendent jamais une Future métier. Le service renewal central parcourt les handles/leases enregistrés et appelle exclusivement les Engines.

Mémoire autorisée : listes immuables des définitions/partitions candidates, leases reçus comme capacités **à revalider SQL**, RequestWalk source, SlotWalk, références de handles et phase locale de tâche, échéances monotones de réveil, compteurs de diagnostic. Taille du registre des handles rendus ≤M_W, pending Engine inclus ≤M_W global par les mêmes tickets ; nombre de couples borné par configuration. Aucune copie autoritative des états SQL AVAILABLE/Q, K, count, retryAfter, résultat ou échéance de claim. Les objets lease/GrantedClaim peuvent porter l’échéance reçue pour programmer une alerte ; elle ne prouve jamais le droit de traiter.

Registre des leases partagé sous courte section critique ; snapshots immuables pour renewal, jamais de verrou Java conservé pendant JDBC. Installer un résultat de renewal seulement si l’incarnation/token locale correspond encore au snapshot envoyé ; un retour tardif ne réinscrit pas un lease retiré. Acquisition concurrente par les deux boucles : un seul résultat confirmé installé par couple, SQL reste arbitre, aucune double propriété locale supposée. Claims traités par référence/token ; retirer un lease n’entraîne aucun retrait de claim. Aucun contexte Spring, Prepared ou Connection transféré d’un thread à un autre ; seul le handle issu d’acquisition (hors transaction) est soumis, puis execute prépare/finalise entièrement sur son thread.

## 8. Protocole Discovery

1. Parcourir les Consumptions actives puis leur partition candidate suivante (§11), au plus **une invocation** I4 par opportunité. Acquisition de lease via Engine, résultat confirmé seulement ; aucune propriété supposée après exception. La même capacité lease locale est utilisable par les deux boucles, toujours fenced par I3/I4.
2. Si lease absent/suspect, offrir une acquisition bornée lors d’un prochain passage, jamais une attente sur le propriétaire. Enregistrer seulement les leases retournés, renewals centraux. La Discovery ne réserve aucun ticket M_C/M_W et ne dépend pas du pool d’exécution métier.
3. Burnable : `discoverRequests(lease,walk)` ; conserver exclusivement `RequestProgress.next()` d’un retour certain. `examined` inclut la frontière rencontrée ; `advanced` seul indique le préfixe franchissable ; `committedAdmissions` mesure les admissions physiques. Ne pas reconstruire next à partir de examined ou de la page préparée. RequestWalk est propre à cette source, distinct du tour des slots.
4. Non-burnable : `discoverEvents(lease)` relit K et la fenêtre sûre à chaque appel ; checkpoint/slots commitent dans READ. Le runtime ne calcule ni ne mémorise son propre K/H. STALE_CHECKPOINT offre une nouvelle préparation au passage suivant ; aucun retry interne serré.
5. CAPACITY : conserver le tour burnable, laisser les autres couples avancer ; reprendre après délai borné ou signal de fin de tentative. SoftCap plein ne bloque jamais l’Engine. Non-burnable peut couvrir des positions hors partition sans admission ; son checkpoint retourné est la seule progression durable déclarée par I4.
6. BUSY_OR_NOT_OWNER : suspendre les nouvelles opérations sur ce lease local jusqu’à revalidation ; omission de renewal SKIP LOCKED n’est pas preuve de takeover ; la politique conservative de retrait local §15 ne l’annonce jamais comme tel. Contrat incompatible confirmé : désactiver nouvelles admissions/acquisitions, health en défaut, aucun rebootstrap. Collisions/Failure/raw exception : conserver walk antérieur ; prochaine invocation repart des contrats durables, aucun delta déduit, même Failure(COMMITTED) sans résultat.
7. Cadence proposée : travail admis => minPoll=10ms ; navigation/progrès sans admission => au moins 10ms ; tour global sans travail => délai doublé jusqu’à maxPoll=1s ; faute DB => errorPoll=1s borné, jamais boucle chaude. Paramètres expérimentaux archivés ; signal local de capacité peut raccourcir le sommeil, sans être nécessaire à correction. Pas de scan non borné pour remplir un lot.
8. DRAINING interdit les nouveaux appels ; l’appel I4 déjà engagé termine/résout son unité, puis la boucle accuse sa sortie. Aucun arrêt ne réinitialise checkpoint/source ou walk durable (il n’existe pas).

Métriques : cycles, opportunités, examined/advanced/admissions distincts, CAPACITY/stale/busy/fault, durée d’appel et tours (§20). La progression de curseur source n’est jamais enregistrée comme nombre d’admissions.

## 9. Protocole Consumption

À chaque tour : vérifier RUNNING, executor non arrêté et possibilité locale de dispatch (§12), puis une seule Consumption et sa prochaine partition possédée. Tester M_W/M_C par `capacity.snapshot()` pour éviter un travail inutile ; cette lecture reste indicative. Si pas de capacité globale, attendre sans modifier la prochaine Consumption due. L’Engine demeure seul autorisé à réserver atomiquement.

Lire une page de **clés brutes** via l’extension §10, sans verrou de ligne, dans une courte transaction de lecture du pool contrôle. Conserver U retournée pour le tour ; ne pas déplacer after à la lecture. Fournir un préfixe borné à `engine.acquire(lease,keys)`. Quantum d’acquisition par passage configurable 1..B, **défaut 1**, afin d’offrir une place à chaque C sous M_W=1 ; taille de page défaut32 mais aucun suffixe non examiné n’est franchi. Ne pas garder une page au-delà du prochain passage : relire depuis after, simplicité et mémoire bornée.

Sur retour certain, vérifier `0<=examinedCount<=keys.size`, handles de cette C uniquement. Si e>0, after devient exactement `keys[e-1]`. Avant d’inscrire les handles gagnés, la seule boucle Consumption retire du registre les références pour lesquelles retainsCapacity est faux ; inscription/retrait local sont synchronisés et aucun verrou Java n’est conservé pendant Engine/JDBC. Cela maintient la borne du registre malgré une restitution Engine précédant le finally de l’envelope. Les handles gagnés vont au registre de dispatch/renewal puis soumission ; les places non gagnées sont déjà restituées par Engine. e=0 : pas d’avance. La rotation C peut avancer après refus M_C local ou occasion sans claim ; le curseur de clés reste inchangé si e=0. Perte de lease : nouveaux appels suspendus, handles déjà acquis toujours admissibles à execute/renew selon leur claim.

Sur Failure/RuntimeException/Error sans résultat exploitable : **aucune avance**. Aucun execute inventé à partir d’une lecture des tokens SQL ; Engine conserve/résout ses tickets pending, et les claims d’acquisition incertaine sont abandonnés à expiration conformément I3. `settlePending` est appelé hors des deux boucles par maintenance centrale bornée (§15), sans refaire l’acquisition originale. Une page raw vide après lecture confirmée clôt le tour, même si U a disparu ; elle ne prouve aucune complétion. Page non vide mais zéro claim ne clôt jamais le stock par elle-même.

## 10. Navigation R10 : SQL, index, MVCC et préfixe

**Extension proposée**, distincte de ConsumptionSlotStore.acquire : `SlotKeyNavigation` dans Engine et `JdbcSlotKeyNavigation` dans PRIMARY, réutilisable avec la DS READ. Le port lit uniquement les UUID et respecte un ordre total PostgreSQL. Pas de filtre state/retry/claim et pas de jointure source.

```sql
-- Début du tour, même snapshot RR RO que la première page :
select consumable_id from consumption_slot
where consumption_id=:c and partition_id=:p
order by consumable_id desc limit 1; -- U, Optional.empty si stock vide

-- Première page (forme dédiée, sans OR paramétré) :
select consumable_id from consumption_slot
where consumption_id=:c and partition_id=:p and consumable_id<=:u
order by consumable_id limit :b;

-- Pages suivantes :
select consumable_id from consumption_slot
where consumption_id=:c and partition_id=:p
  and consumable_id>:after and consumable_id<=:u
order by consumable_id limit :b;
```

Aucun FOR UPDATE/SHARE dans ces requêtes ; les verrous de relation restent possibles, donc timeouts read explicites. Index **déjà présent** WRITE V5 et READ V1 : `consumption_slot_partition_walk_idx(consumption_id,partition_id,consumable_id)`. PK(C,id) utile aux acquisitions mais p filtré résiduel : comparaison de plans obligatoire. Pas de nouvelle migration/index prévu. Borne haute et pages ultérieures ont des snapshots courts différents ; aucun snapshot RR retenu pendant tout le tour. Des suppressions peuvent produire une page vide avant U ; nouvelles insertions dans (after,U] peuvent apparaître, derrière after ou >U attendent le tour suivant. U n’est jamais étendue en cours de tour. En Java comparer les 16 octets réseau non signés (comme DiscoveryEngine), **jamais UUID.compareTo signé** ; conserver l’ordre SQL, pas retrier les résultats avec un autre comparateur.

| Alternative SQL | Coût/comportement | Décision |
|---|---|---|
| Keyset ci-dessus, deux formes first/next | Seek index + au plus B clés visibles ; index-only possible mais heap/MVCC/vacuum conditionnent le coût ; pas de coût physique constant promis | Retenue |
| OFFSET/LIMIT | Relecture du préfixe, déplacements sous suppressions, coût de profondeur | Rejetée |
| WHERE eligible + SKIP LOCKED comme navigation | Cache slots temporairement inéligibles ; page déverrouillée vide ne prouve pas fin ; pas de revisite assurée | Rejetée |
| ROW_NUMBER, materialized liste de tout le tour | Scan/mémoire proportionnels au stock ou snapshot long retenant MVCC | Rejetée |
| UNION des deux moitiés pour wrap dans une requête | Ambiguïté U/fin/retour, tri/doublons supplémentaires ; aucune nécessité | Différée tant que trois seeks simples satisfont les mesures |

**Contrat existant précisément vérifié.** `Acquisition.examinedCount()` n’est ni claims.size, ni nombre de lignes verrouillées, ni nombre d’UPDATE gagnés. Engine réserve r tickets pour le préfixe `examinedKeys[0:r]`, construit r candidats, exécute une unité technique ; si protectAcquisition refuse, e=0 ; sinon retour normal e=r. Le store soumet **toutes ces r clés** à son unique sélection IN ordonnée SKIP LOCKED ; absence, verrou sauté, claim actif, retry ou Q comptent comme un examen d’acquisition sans gain. Le suffixe `r..size` n’est pas soumis. Son éventuelle boucle CAS peut s’arrêter quand le lease a expiré après la sélection : e indique la couverture du scan de clés déjà effectué, **pas** la validation de chaque CAS ni le maintien du lease jusqu’au retour. Aucune de ces clés n’est réputée traitée ; elles reviennent au prochain tour. Ce sens découle du code, différent du champ `advanced` burnable I4.

I5 doit exercer ce contrat sur le vrai store, y compris lease expirant entre scan et CAS. Aucun compteur ne doit être utilisé comme attestation d’un préfixe jamais soumis au SQL. Si une évolution du store introduit un arrêt **avant** la sélection de tout le préfixe, il faudra faire remonter son préfixe réel avant de conserver e=r ; cette évolution est hors du réemploi inchangé présent. Un résultat perdu n’a pas d’e récupérable : after reste antérieur. Le rescannage est sûr grâce au claim/CAS et ne perd rien.

| Cas obligatoire | Règle de tour / acquisition |
|---|---|
| Slot verrouillé | Visible à navigation MVCC ; acquisition le saute ; e peut le franchir pour ce tour seulement ; revisite après libération contrôlée |
| Claim actif / expiré | Tous deux navigués ; actif non repris, expiré CAS/token neuf selon I3 ; aucun nettoyage d’échéance préalable |
| retryAfter futur / Q | Navigués sans acquisition ; tour suivant pour retry ; Q persiste, même si jamais réactivée |
| Suppression entre page/acquire | Absence dans scan d’acquisition = zéro claim ; e du préfixe peut avancer, jamais une preuve de succès |
| Insertion derrière curseur / au-delà de U | Prochain tour avec nouvelle U ; pas report durable ; insertion dans fenêtre encore devant peut être incluse |
| Page raw vide | Tour terminé à cet instant de snapshot ; prochain tour repart au début ; pas de conclusion métier |
| Saturation M_C/M_W / exécuteur | Pas de lecture inutile si connue ; e=0 si aucune réservation ; aucun déplacement fictif |
| Partiel | Avance e, pas nombre de gagnants ; suffixe non examiné reste devant after |
| Commit UNKNOWN ou résultat perdu COMMITTED | Aucune progression inférée ; handles non rendus non exécutés ; pending I3 inspectés |
| Lease perdu / redémarrage | Stop nouvelles acquisitions sur lease ; tours perdus sans effet durable ; reprise depuis début, mêmes slots |

Argument de revisite : ensembles de couples finis, occasions répétées, capacité revenant et périodes sans crash assez longues ; dans chaque tour U fixe, ordre strict et pages bornées ; e>0 franchit seulement un préfixe soumis, e=0 le conserve ; fin raw/U rend le début de nouveau accessible. Une clé non supprimée et temporairement inéligible est donc revisitée après retour au début. Pour **acquisition** finale, il faut aussi une fenêtre libre rencontrée par une opportunité, pas seulement des verrous qui finissent mais seraient repris devant chaque visite. Insertions dans (cursor,U] peuvent allonger un tour : ne pas promettre ceil(stock initial/B) tours/pages indépendamment des arrivées, ni latence absolue sous adversaire continu.

## 11. Politique d’équité entre C et partitions

Deux sélections indépendantes, une par boucle ; **round-robin d’abord entre Consumptions**, puis, pour celle choisie, prochaine partition candidate. Une liste plate C/p donnerait davantage d’occasions à une C déclarant davantage de partitions ; elle est écartée. Ordre de liste stable de configuration, pas priorité métier ; chaque C reçoit au plus une opération bornée par passage, chaque partition de C revient circulairement. Les C sans lease/capacité propre sont sautées après une vérification bornée ; aucun sommeil long placé au milieu du passage n’empêche les autres.

La boucle Consumption conserve `nextConsumption` après la dernière opportunité effectivement offerte, **y compris entre les réveils**. Si M_W devient nul après le gain C1, la prochaine C due reste C2 ; le polling saturé ne fait pas tourner aveuglément le pointeur jusqu’à C1. Si une C est bloquée par M_C tandis que M_W est disponible, les autres reçoivent leur occasion. Si l’exécuteur n’a aucun emplacement effectif probable, ne consommer aucune opportunité de capacité ; conserver le prochain bénéficiaire. Une tentative de soumission rejetée clôt l’occasion C courante, et la prochaine reste offerte à la suivante, sans réessai préférentiel de C1.

M_W=1, C1/C2 constamment éligibles, quantum1 : après résolution/settle d’une réservation C1, C2 est la prochaine occasion d’acquérir, puis C1. Si l’occasion C2 rencontre une clé verrouillée/inéligible, l’équité n’invente pas un gain ; elle poursuit son tour de clés aux prochaines occasions. M_C sont des maxima, jamais des réservations permanentes ; pas de quota global entre Workers. Oracle OR-EQ-CAP sur occasions **et** tentatives réellement démarrées, avec libérations et fenêtres libres imposées. Égalité des débits, ordre FIFO et bornes de latence universelles ne sont pas promis.

## 12. Réservation M_C/M_W et dispatch : enchaînement exact

L’acquisition est actuellement **synchrone après réservation**. Elle demeure sur le thread Consumption, via le pool contrôle ; elle n’est pas une tâche métier soumise au même exécuteur. Son temps est borné par accès connexion/lock/statement/unité techniques. Cela laisse Discovery et renewals indépendants, mais peut retarder la prochaine opportunité Consumption : mesurer ce segment séparément.

Séquence : executor vivant et disponibilité indicative → navigation brute confirmée → `Engine.acquire` (réservation atomique interne, transaction de claims) → retour de handles confirmés → inscription locale → `executor.execute(envelope)` → début réel de l’envelope → `engine.execute(handle)` → résolution métier, éventuelle cession I3 → sortie locale effective → restitution Engine ou `settlePending`. Les refus/libérations restent exclusivement `abandon`, `execute`, `settlePending`. Le Worker n’appelle jamais reserve/release, ne fabrique pas de ClaimedSlot exécutable et ne tient aucun second système de tickets.

L’envelope possède seulement une transition locale atomique « pas démarrée / démarrée / abandonnée / sortie » pour arbitrer début et annulation ; ce n’est pas un quota ni une preuve SQL. Une annulation gagnante **avant** début appelle abandon une seule fois ; une runnable arrivée plus tard ne fait rien. Après début, abandon est interdit, y compris si execute lève. Le code `finally` du Worker retire seulement les références dont la réservation est effectivement restituée selon l’accès RO §17 ; il ne restitue pas de capacité lui-même. Les handles incertains restent suivis et renouvelables ; pending d’une acquisition incertaine sans handle retourné reste Engine seul et ne déclenche aucun métier/renewal.

| Événement | Claim / tâche / transaction | Ticket et reprise autorisée |
|---|---|---|
| Exécuteur saturé/arrêté détecté avant navigation | Aucun appel acquire, aucun claim | Rien réservé, cursor inchangé ; attendre/rendre la main |
| Aucun ticket disponible à acquire | e=0, aucune transaction d’acquisition | Aucun SQL, aucun changement cursor ; prochain bénéficiaire conservé |
| Réservation puis acquisition sans claim / partielle | Scan borné confirmé ; k claims sur r | Engine rend r−k ; cursor e ; soumettre seulement k handles |
| Collision concurrente de claim/ownership | CAS gagne partiellement ou protect refuse | Zéro double claim ; places rendues selon résultat certain ; refus ownership e=0 |
| Soumission acceptée | Durable claim existe ; tâche peut ne pas avoir commencé | Ticket gardé, inscrire renewal avant soumission ; compter début seulement à l’entrée réelle |
| Soumission refusée (AbortPolicy), race stop/saturation | Aucun callback métier de cette envelope | `abandon(handle)` une fois ; ticket rendu après acquisition certaine, claim laissé expirer sans cession/count ; métrique rejet |
| Annulation avant démarrage | CAS locale empêche définitivement execute | abandon, pas de DELETE/release SQL du claim, aucune comptabilisation |
| Annulation après début / interruption | Signal coopératif ; thread/SQL peuvent continuer | Aucun droit de libérer ; laisser execute résoudre ; pas de retry sous le handle ; ne pas déduire rollback du signal |
| Fin normale COMMITTED | Unité physique certaine, callback sorti | Engine restitue ; une observation métier seule ne remplace pas cette résolution |
| Rollback confirmé | Peut suivre classification puis cession séparée | Engine garde le ticket pendant cession ; rend si dernière unité certaine ; N seulement selon I3 |
| Cession UNKNOWN | Rollback métier certain, nouvelle transaction incertaine | Garder ticket/pending ; ne jamais répéter la cession même si count lu semble ancien |
| Commit UNKNOWN, R9 COMPLETED compris | Issue métier éventuellement connue, ACK physique historique inconnu | Garder jusqu’à inspection I3 suffisante et fin locale ; aucune nouvelle exécution immédiate |
| Exception brute/Error du port owner | Résolution non qualifiée | pending conservé ; `settlePending` seulement ; erreur fatale déclenche drain et diagnostic |
| Classifier RuntimeException/Error/null après rollback certain | Pas de nouvelle unité démarrée | Correction post-audit rend le ticket en finally ; le Worker ne reconstruit pas la classification |
| Transaction toujours active après retour local | Verrou slot encore détenu, inspect le saute | Garder ticket même après deadline/Future terminée ; pas de cession/relance |
| Crash JVM | Réservations locales disparaissent avec ce Worker | PostgreSQL règle les unités ; nouveau Worker n’a pas de quota global hérité ; claims expirent/reprise fenced |
| Takeover | Nouveau lease ne permet pas de reprendre un claim encore valide | Nouvelle acquisition seulement après conditions I3 ; ancien finaliseur valide sous verrou peut encore committer |

Une Future done/cancelled/timeout ou absence de thread local ne suffit jamais à qualifier la résolution physique. Aucun retry propriétaire autour de execute/cession. La classification commune garde la priorité transport/configuration exclue > SQL/JPA > faute technique ; les enveloppes cancellation/interruption seules sont neutres. Les corrections R-AUD-I3-01/02, y compris originalException/suppressed/nextException, sont des régressions obligatoires fraîches.

`settlePending()` inspecte au plus B réservations `localDone`. PRESENT_UNLOCKED peut rendre la place sans conclure rollback ou résultat ; ABSENT après businessStarted exige R9 COMPLETED cohérent ; LOCKED/UNAVAILABLE/INCONSISTENT conservent. Deux inspections concurrentes ne rendent qu’une fois. Le Worker ne transforme aucune inspection en effet, réparation ou libération durable du claim.

## 13. Exécuteurs, files et délais locaux

Exécuteur métier proposé : `ThreadPoolExecutor(core=M_W,max=M_W,keepAlive=0,SynchronousQueue,threadFactory nommée,AbortPolicy)`, threads plateforme non daemon, pré-démarrage des core threads. **File métier de taille zéro** ; pas CallerRuns (bloquerait Consumption), pas discard/discardOldest, pas retry de soumission en boucle. getActiveCount est un indice d’évitement de rejet, jamais une réservation. Le vrai accept/reject et les handles déterminent le nettoyage. Les tests imposent une race malgré le contrôle indicatif, y compris une tâche acceptée dont le thread n’est pas encore entré.

Deux threads de boucle non daemon, attentes Condition réutilisées, aucun @Scheduled par C/p. Renewals : par autorité deux voies claims et une voie leases, chacune monothread, indépendantes des deux boucles et de l’exécuteur métier. Maintenance settle : un thread unique Worker. Le superviseur ne lance pas un quatrième poller général ; son thread maintenance vérifie aussi santé/drain. Au pilote mixte : 2 boucles +6 renewal +1 maintenance +M_W métier ; aucune création de thread par slot. Toutes ces voies utilisent des attentes monotones/réveillables. Un seul lot en vol par voie, pas accumulation de tâches périodiques : échéance manquée coalescée, prochain passage borné, pas de rafale de rattrapage.

Bounds expérimentales initiales : minPoll10ms/maxPoll1s/errorPoll1s, settleInterval1s, renewalInterval10s, batch32, quantum1. Utiliser Duration/horloge monotone pour réveils ; droits durables toujours PostgreSQL. Long arrêt GC ou réseau peut faire perdre les droits : pas de promesse de renouvellement sans hypothèses. Déclarer la configuration exacte avant chaque campagne, y compris les variantes à deadlines courtes.

## 14. Pools JDBC et owners physiques : fermeture de R-AUD-I3-03

**Décision proposée : quatre HikariDataSource explicitement distinctes** pour un Worker mixte : writeBusiness, writeControl, readBusiness, readControl. Deux pools d’une autorité pointent vers **la même base/schema/rôle runtime** et les mêmes tables ; WRITE et READ restent des bases physiques distinctes. Le rôle runtime peut conserver l’union I3/I4 des droits : séparation des ressources ≠ cloisonnement de credentials. Aucun SET ROLE ou rôle hérité ; admin/réactivation/injection dev sur leurs connexions séparées.

Cette séparation ferme l’effet d’épuisement du pool métier, mais un pool contrôle partagé ne réserve pas à lui seul les renewals. La garantie vient aussi de l’inventaire **exhaustif et borné** des appelants suivants, imposé par construction, règles d’architecture et tests de saturation. Pas de datasource brute exposée aux boucles, pas d’emprunteur historique/HTTP/métriques SQL caché.

| Pool par autorité A | Emprunteur maximal simultané | Majorant |
|---|---|---:|
| Business A | Préparation/finalisation d’une tentative sous ticket ; séquentielles, jamais une connexion pendant l’attente de contrôle de cession | M_W |
| Business WRITE | Lecture source I4 événementielle, depuis la seule boucle Discovery, unité fermée avant admission READ | 1 |
| Business A | Observation R9 appelée par maintenance après inspection ; un seul thread maintenance Worker | 1 |
| Control A | Cessions exécutées par les threads métier, après rollback physique de leur unité Business | M_W |
| Control A | Discovery (lectures/admission séquentielles d’une seule boucle, une connexion par appel) | 1 |
| Control A | Consumption : navigation/acquisition/acquisition de lease séquentielles | 1 |
| Control A | Inspection technique `settlePending` (fermée avant R9 Business) | 1 |
| Control A | Renewal claims voie S1 et voie S2 | 2 |
| Control A | Renewal leases | 1 |

Tailles conservatrices initiales par autorité : **Business=M_W+2**, **Control=M_W+6** (10 et6 respectivement Control/Business pour M_W4). READ Business garde le même majorant pour simplicité, bien que la lecture source réservée soit WRITE ; aucun quota permanent par Consumption. Tous les emprunteurs non-renewal du contrôle sont bornés à M_W+3 : trois accès restent donc disponibles même lorsqu’ils sont tous occupés. Aucun thread de tentative ne peut emprunter simultanément Business et Control ; ni transaction imbriquée ni continuation après démarrage cession. Pool minimumIdle=maximumPoolSize au pilote pour éliminer une croissance paresseuse non mesurée ; vérifier le nombre réel de connexions établies avant RUNNING, et les délais de remplacement. Ce réglage ne garantit pas la disponibilité pendant panne réseau/serveur.

Les voies renewal ne se partagent pas un exécuteur monothread. Une voie claims bloquée en relation/transport n’empêche pas l’autre d’emprunter ; le lease dispose aussi de sa voie. SKIP LOCKED évite des attentes de **lignes**, pas toutes les attentes PostgreSQL. Si un DDL exclusif bloque toute la relation, aucun dispositif de pool ne peut assurer S2 ; timeout puis diagnostic, aucune garantie fictive. La campagne S1/S2 établit explicitement un blocage qui laisse S2 accessible.

WRITE Business : `JpaTransactionManager` avec EMF explicitement construit sur **writeBusiness**, nested=false ; `SpringAttemptTransactions(writeBusiness,tm,60)` garde JDBC/JPA, final slot et tous writers Pot sur cette même DS. Pas d’auto-scan des anciennes entités/repositories : EMF minimal explicite, pas de validation du schéma V2 sur V3. READ Business technique : DataSourceTransactionManager readBusiness nested=false, owner Spring existant. Control A : DataSourceTransactionManager controlA nested=false, owner Spring existant avec timeout technique3s ; mêmes guards et aucune publication métier. La pluralité de managers correspond à des **unités distinctes et successives**, pas à deux owners d’une tentative finale.

Extension Engine limitée : champ effectsTransactions/effectsSlots pour prepare/finalize/observe/Completion ; champ controlTransactions/controlSlots/ownership pour acquire/renew/cession/inspect/navigation. `technical()` utilise contrôle ; observe reste effets. Discovery I4 utilise controlA comme effects owner (ses « effets » sont slots/K), sourceReads writeBusiness pour événements ; Command burnable source/control sont identiques. L’inspection contrôle se ferme avant observe métier ; la cession contrôle commence après rollback métier confirmé. Aucune délégation `control.write(() -> effects.write(...))`, aucun routing datasource dynamique, aucune réécriture des SQL I2/I3/I4 ou du suffixe.

Configuration effective proposée : Hikari connectionTimeout1s, validationTimeout500ms (valeurs compatibles à valider au démarrage), JDBC connectTimeout2s/socketTimeout5s contrôle ; réglages par connexion/session, jamais mutation globale à chaud. `ownership.bound` garde lock500ms, statement2s, transaction_timeout3s techniques. Navigation read reçoit ces bornes explicitement ; une transaction read n’est pas bornée par ownership.bound gardé en écriture. Business conserve timeout Spring60s ; fixer aussi des limites PostgreSQL/JDBC métier cohérentes et vérifiées par profil (première expérience : statement60s, transaction65s, socket70s) ; une durée longue de test ajuste ces valeurs **avant** création des pools. Un timeout reste une issue à qualifier, jamais une preuve de rollback.

Dimensionnement PostgreSQL : nombre de Workers W fixé ; budget de connexions au moins somme des maxima réellement établis des quatre pools de chacun + admin/injecteur/observateurs + marge serveur déclarée. Hikari max borne les connexions louées par pool, pas tous les backends résiduels après coupure/éviction. Les coupures filaires doivent mesurer ces résidus ; transaction_timeout serveur et cadences bornent leur maintien sous serveur répondant. Ne pas promettre un budget serveur à partir de 4 tailles sans cet essai ; dégrader/arrêter les nouvelles opérations en cas d’épuisement, conserver UNKNOWN. Aucun ACK local/attente temporelle ne rend un ticket physique par supposition.

**Méthode de qualification bloquante :** mesurer emprunt, dispatch, statements, locks, commit et pauses JVM par voie sur le runtime complet ; archives config Hikari réelle, managers, pg_stat_activity/PID, attente et échéances PostgreSQL. Pour chaque classe lease/claim et autorité, relever L nombre maximal de lots dus par cadence, r nombre de voies réellement utilisables, t unité maximale budgétée (incluant emprunt/lock/commit/cleanup), j retard d’ordonnancement/GC budgeté. Exiger `renewalInterval + ceil(L/r)*t + j + safety < durée utile minimale`, puis contrôler la marge **réellement observée en DB** avant chaque commit. Par C, les lots ne dépassent pas B ; augmenter le nombre de C/partitions implique recalculer L. La voie claims perdue dans le scénario S1 conduit à r=1 pour les claims restants ; la voie leases est r=1 séparée. Déduire t de timeouts totaux et mesures, pas de somme de p95 ni de `10<30`.

Exemple initial (hypothèse, **pas certificat**) : 3 C actives,12 leases, M_W4, B32 ; L_lease=sum_C ceil(p_C/B)=3 et L_claim=sum_C ceil(h_C/B)≤3 avec sum(h_C)≤4. Exemple de budget préalable t=5s (1s emprunt+3s unité+1s cleanup), j=1s, safety=2s : lease10+3×5+1+2=28s<30s ; claims avec une voie disponible10+3×5+1+2=28s<60s. Ce budget doit être mesuré, pas présumé ; si cela ne tient pas, réduire lots/concurrence contrôlée ou ajuster les durées expérimentales avant activation. Ne pas annoncer PASS parce que les valeurs pilot sont acceptées par LifecycleSettings. Les seuils sont figés avant campagne et confrontés aux maxima observés, pas ajustés après pour faire passer.

## 15. Renewals et maintenance indépendants

Le dispositif central prend des snapshots bornés : leases locaux encore candidats ; handles confirmés non abandonnés dont Engine conserve la réservation ; pending acquisition UNKNOWN reste inspecté, **jamais exécuté ni renouvelé**. Deux voies claims par autorité ; répartition déterministe des handles (index du snapshot/rotation locale, sans priorité métier) ; pas deux appels simultanés sur le même handle. Si une voie est bloquée, la voie libre revisite les clés non actuellement en vol après son lot, sans attente de l’autre ; pas affectation permanente d’une moitié à une voie bloquée. Les lots sont par Engine/C, ≤B ; rotation aussi entre C. Registre/retrait et renouvellements concurrents sont idempotents sous CAS I3. Un renewal déjà en vol peut encore confirmer une extension valide d’un claim juste abandonné avant début ; ne pas prétendre restaurer son ancienne échéance. Aucun nouveau lot ne le reprend après retrait, le claim expire à sa dernière échéance durable, sans cession ni effet.

Appeler renewClaims même si lease partition supprimé/suspect/perdu. Aucune condition M_C/M_W ni executor métier libre. Ne pas consulter ownership pour renewal ou finalisation d’un claim individuel. R10-09 : perte confirmée de lease => cesser son renewal de partition et nouveaux Discovery/acquire ; conserver le claim et le droit de demander son renewal individuel. Nouvelle propriété n’autorise pas de reprise avant son expiration.

Retour renewal partiel : seules les échéances retournées sont confirmées renouvelées ; une clé sautée n’est pas déclarée perdue ou finalisée. Garder son suivi tant que tâche/réservation active ; prochain passage borné. Si token expiré/remplacé confirmé, ne pas le ressusciter ; execute protégera le slot et refusera le mauvais token ; signal coopératif possible mais aucun abandon d’une tentative démarrée. Renewal UNKNOWN : pas de nouvelle échéance supposée ; prochain CAS même token seulement s’il reste valide, conformément I3. Politique minimale des leases : toute entrée absente du retour confirmé de son lot (ou tout lot dont le résultat est perdu) est retirée du registre local utilisable et de la liste à renouveler ; cela signifie « capacité locale abandonnée », **pas preuve de perte durable/takeover**. Aucun besoin d’un nouveau port de lecture d’ownership. Le prochain passage peut seulement tenter acquirePartition avec un token neuf : la DB refuse tant que l’ancien lease subsiste valide, puis applique I3. Ce choix peut coûter jusqu’à une expiration après simple contention, mais évite toute réinscription/revalidation implicite d’un lease retiré ; conserver les claims individuels. Un BUSY ponctuel Discovery/acquire suspend l’usage jusqu’au prochain renewal confirmé du lease encore enregistré ; s’il est omis, appliquer le retrait ci-dessus.

Maintenance appelle `settlePending` sur chaque Engine en rotation, au plus un appel/C/passage et 1s entre passages sans progrès ; ne dépend pas d’un ticket métier. Une observation R9 lente peut retarder settle, pas les renewals. Si erreur du service central ou thread mort, superviseur interdit les nouveaux travaux et entre DRAINING, conserve les voies encore saines et diagnostic ; ne masque pas une perte de mécanisme de renouvellement derrière un health RUNNING.

## 16. Cycle de vie, arrêt, crash et takeover

Machine locale minimale : STARTING → RUNNING → DRAINING → STOPPED. Échec avant lancement mène STARTING→STOPPED après fermeture des ressources non utilisées. Pas d’état SQL ajouté. Indisponibilité DB, lease perdu et saturation sont des conditions observables locales ; pas de nouvelle machine durable. DB indisponible temporaire : backoff borné, nouvelles opérations seulement sous validations I3/I4 ; après échec persistant du health/config/threads, DRAINING. Saturation persistante n’est pas un succès/fin : conserver rotation due, alerte et diagnostic des tickets ; ne pas les rendre pour rétablir artificiellement le débit.

STARTING : propriétés/profils validés, catalogue exact et activation présents, cibles/rights/routage/hash vérifiés sur **chaque** pool, connexion réserve établie, arêtes de composition explicites et absence de Legacy. Démarrer renewals/maintenance puis les deux boucles ; RUNNING seulement après accusés de démarrage de ces threads. Un thread vivant ne prouve pas disponibilité DB, exposer health séparément.

Ordre de drain :

1. Transition atomique vers DRAINING, readiness false ; interdire nouveaux appels Discovery/acquire/leases, réveiller attentes. Les opérations déjà engagées finissent selon leurs owners ; garder un compteur local d’appels en vol afin de ne pas fermer une DS sous leur callback.
2. Joindre les deux boucles après résolution/retour de ces appels. Les acquisitions confirmées avant la barrière peuvent être dispatchées si le superviseur les a admises avant drain ; politique retenue : **abandonner celles qui n’ont pas démarré** via CAS envelope+abandon. Les démarrées continuent. Ne jamais confondre un handle pending UNKNOWN non rendu avec une tâche à abandonner.
3. Fermer les nouvelles soumissions (`shutdown`, pas shutdownNow automatique). Drainer callbacks métier et cessions ; continuer renewals des claims suivis et settle. Les renewals de leases peuvent s’arrêter dès fin des appels Discovery/acquire : laisser les leases expirer pour takeover ; aucune libération durable supposée. Les claims restent indépendants.
4. Fin propre seulement quand boucles sorties, aucune envelope susceptible de démarrer/continuer, réservations `WorkerCapacity.snapshot().worker()==0`, pending réglés et aucune opération contrôle en vol. Arrêter/join maintenance et renewals, puis fermer EMF/pools Business, enfin pools Control. SmartLifecycle callback appelé **après** cette séquence. Stop est idempotent.
5. Deadline expérimentale drain90s, timeout Spring par phase supérieur (120s initial). Deadline dépassée : conserver DRAINING et alerte, ne pas déclarer STOPPED/callback accompli et ne pas fermer les pools sous transactions actives. Le processus peut être terminé par le lanceur/opérateur à une échéance externe explicite ; c’est un crash, pas un drain réussi. Le dossier de preuve conserve les transactions encore physiques après retour local et leur résolution/timeout DB. Une décision locale de Future.cancel ne les efface jamais.

Crash : mémoire de tours/tickets perdue, PostgreSQL reste seule autorité ; redémarrage revalide tout sans bootstrap ni reset. Nouveau Worker acquiert les leases expirés par I3 ; slots libres acquis normalement, claims anciens encore valides laissés, expirés repris avec nouveau token. Verrou final valide de l’ancien sérialise le takeover jusqu’à résolution ; observation de slot absent seule ne suffit pas à conclure. Aucun restart ne réactive Q, purge un incomplet, réinitialise K ou relance aveuglément une transaction incertaine.

## 17. API Java : existant vérifié et extensions proposées

**API existante vérifiée**, types de retour et paramètres significatifs exacts (méthodes publiques, pas pseudo-API de plan historique) :

```java
// ConsumptionEngine
Optional<PartitionLease> acquirePartition(int partition);
List<PartitionLease> renewPartitions(List<PartitionLease> leases);
List<ConsumptionSlotStore.GrantedClaim> renewClaims(List<ClaimedSlot> claims);
Acquisition acquire(PartitionLease lease, List<UUID> examinedKeys);
void abandon(AcquiredClaim acquired);
Attempt execute(AcquiredClaim acquired);
int settlePending();
RecoveryObservation observe(ClaimedSlot claim);
record Acquisition(List<AcquiredClaim> claims, int examinedCount) {}
// AcquiredClaim.claim() -> ClaimedSlot ; aucun deadline getter existant

// DiscoveryEngine
EventProgress discoverEvents(PartitionLease lease);
RequestProgress discoverRequests(PartitionLease lease, RequestWalk walk);
record RequestProgress(Status status, RequestWalk next, int examined,
    int advanced, int committedAdmissions, boolean tourFinished) {}
record EventProgress(Status status, OptionalLong checkpoint, int examined, int inserted) {}

// Participant / port physique (conservés)
Optional<ConsumptionParticipant.Prepared> prepare(UUID consumableId);
RecoveryObservation observe(UUID consumableId, RecoveryReadContext context);
// Prepared.finalizeIn(SlotCompletion), Prepared.requireCompletionReady()
// AttemptTransactions.read(Function<RecoveryReadContext,T>), write(Supplier<T>)
// poison(Throwable), requirePublicationClosed()
// WorkerCapacity(int workerLimit, Map<ConsumptionId,Integer> consumptionLimits)
// WorkerCapacity.snapshot() -> Snapshot(int worker, Map<ConsumptionId,Integer> consumptions)
```

**Extensions proposées**, aucune existante au HEAD :

```java
// engine-consumption/.../v3/port/SlotKeyNavigation.java ; valeurs imbriquées
interface SlotKeyNavigation {
    record Walk(Optional<UUID> afterExclusive, Optional<UUID> upperInclusive) {}
    record Page(Optional<UUID> upperInclusive, List<UUID> keys) {}
    Page readPage(ConsumptionId consumption, int partition, Walk walk,
                  int limit, RecoveryReadContext context);
}
// ConsumptionEngine : unité read contrôle explicite, timeout appliqué par adapter
SlotKeyNavigation.Page navigate(PartitionLease lease, SlotKeyNavigation.Walk walk, int limit);
// Lecture locale RO de l'état du ticket, jamais d'un droit durable :
boolean retainsCapacity(AcquiredClaim handle);
```

`readPage` : C/p explicites, limit1..B, after seulement si U présente et after<=U en ordre unsigned ; résultats uniques strictement croissants, ≤limit, dans (after,U]. Si U absente, la capter et lire première page dans **le même** RecoveryReadContext. Aucune création de checkpoint. Page/Walk construits immuables avec validations ; aucune faculté de progression persistante. `navigate` vérifie C/p/borne mais ne prétend pas certifier l’ownership (aucun DML) ; acquire revalide sous verrou. `retainsCapacity` vérifie owner/handle et lit sous synchronisation le flag interne Ticket ; expose **zéro droit de release** ; WorkerCapacity.Ticket reçoit seulement un accesseur package-private RO. Usage exclusif retrait du registre volatile/metrics, jamais autorisation de traiter ou preuve SQL.

Constructeur Engine proposé : conserver celui de preuve existant qui délègue avec effects=control ; ajouter une surcharge explicite `(definition, effectsTransactions, effectsSlots, participant, controlTransactions, controlSlots, ownership, settings, capacity, classifier, navigation, observation)`. Toutes les factories actives utilisent cette surcharge ; les anciennes ne deviennent pas actives par défaut. Pas de `AttemptTransactions` composite dispatchant selon un ThreadLocal ou une phase ; choix du champ explicite dans chaque méthode.

Observation proposée unique dans Engine, port pur `ConsumptionObservation` : `void record(ConsumptionId id, Phase phase, Outcome outcome, long amount, Duration elapsed)`, enums fermés pour phases/opérations décrites §20, et no-op par défaut. Pas de UUID/token de slot dans cet événement. Appels protégés contre les fautes d’observateur, jamais avant la restitution critique ; aucune exception de métrique ne change une transaction/ticket. WorkerCapacity peut émettre les variations sous sa synchronisation après mutation, vers un observateur **non bloquant** ; jauges lisent snapshot. La composition Micrometer vérifie ce caractère et ne fait aucun SQL/réseau synchrone. Phases préparation/finalisation/cession/résolution instrumentées aux points Engine, pas un proxy créant de nouvelles transactions. Le port ne sert ni de bus métier ni de framework d’événements. Les transitions entrée/sortie de pending sont émises à leurs points réels Engine pour la gauge correspondante, y compris acquisition UNKNOWN dont aucun handle n’est retourné ; ne pas déduire pending du seul nombre de tâches ni lui attribuer un état durable.

Contrats locaux POLL proposés : `V3ConsumptionWorker.start()/requestDrain()/isRunning()` et état local RO ; `V3DiscoveryLoop.runOnePass()`, `V3ConsumptionLoop.runOnePass()` pour unités ; `V3RenewalService.renewClaimsOnce(authority,lane)/renewLeasesOnce(authority)/settleOnce()`. Ces signatures internes pourront être privées/package-private ; pas API publique distribuée. Leur responsabilité et leurs entrées sont fixées §§7–16 ; seuls noms/encapsulation sans effet sur invariants sont des détails d’implémentation. Aucun contrat transactionnel conceptuel laissé à réinventer.

## 18. Composition Spring, droits et profils

Créer le profil **`v3-consumption-worker-dev`**, distinct de `v3-consumption-pilot`, et la propriété obligatoire `pocoma.v3.worker.enabled=true`. Sans les deux, aucun Worker actif. Combinaison des deux profils, profil postgres historique ou demande d’activer une Consumption absente => refus avant threads. COMMAND et MONOLITH importent explicitement la configuration I5 ; Legacy devient conditionnel à `!v3-consumption-pilot & !v3-consumption-worker-dev`. Aucun component scan global dans la voie I5. Le catalogue inerte garde intégralement ses gardes ; le profil dev appelle `definitions()` sans importer ses BeanFactoryPostProcessor I1.

Auto-configurations DS/TM/JPA/repositories/Flyway Boot exclues dans le nouveau profil comme au pilote ; DS/TM/EMF créés explicitement. Exclure aussi ReadStoreAccessAutoConfiguration et ReadStoreMigrationAutoConfiguration historiques : leurs conditions DS/TM pourraient sinon voir un candidat inattendu. Aucun @Primary général ; tous les raccords qualifiés par autorité **et rôle de pool**. Disable des propriétés historiques command/event/projection-task/registration/result/binding/admission/HTTP en défense, mais preuve de non-instanciation par imports/scans obligatoire (les flags seuls ne suffisent pas).

`V3BootstrapGuard` garde son API/règles I1. Nouvelle méthode active proposée `validateWorkerResources(...)` vérifie : les 9 définitions exactes/P4/hash, enablements, deux autorités physiques distinctes, chaque pool réel URL/schema/current_user/rights/ownership et identité DS/TM/EMF, tailles/concurrency/timeout, réserve établie, pas de Legacy owner/scheduler/runner/migrateur historique, une seule WorkerCapacity par identité d’objet, source et participant disponibles. La whitelist active est fermée aux classes I5 nommées §25 ; ne pas supprimer requireInertType ni ouvrir globalement tous `.v3`. Les tests négatifs enregistrent un bean Legacy sous alias et une classe active non listée.

Droits : union explicite `LIFECYCLE_AND_DISCOVERY` I4 sur le même principal par autorité, dans les deux pools ; pas de droit DDL/routage/TRUNCATE, pas de rôle admin/membership/SECURITY DEFINER supplémentaire. Aucun grant INSERT v3_command au Worker ; injecteur dev séparé. Reprendre scripts lifecycle puis discovery ; ne pas changer leur sens pour faire passer le mode actif. Final slot et writers Business partagent les droits I2 existants. La séparation de pools n’empêche pas un SQL arbitraire sous ces credentials : cette limite de confiance reste fermée par les entrées inventoriées et ArchUnit.

Mode technique dev supplémentaire explicite `pocoma.v3.worker.technical-probes=true` et `pocoma.v3.worker.fixture-identity=true`, valeurs requises pour la démonstration complète, interdites hors environnement identifié dev. Nouveau bootstrap **dev séparé**, après bootstrap canonique neuf : ajoute les deux définitions de probe §4 et leurs8 lignes ownership dans READ, la table de fixture READ et fixture_identity WRITE ; pas de nouvelle table technique productive de coordination. Validation active attend exactement2 contrats WRITE/9 READ,8 ownership WRITE/36 READ ; I1 reste exactement2/7 et8/28. Aucun nombre dérivé d’entrées arbitraires ; si probes=false, attente canonique9, pas d’IDs techniques admis. Worker actif par défaut : Command et les deux probes seulement ; sept READ métier et IdentityRequest disabled. Changer le mode ne réutilise pas un UUID métier pour une transformation technique.

Fixture Identity : adaptateur dev nommé explicitement, même DS Business WRITE et même unité du participant pour protection du témoin positif/négatif, depuis table administrée de fixture, conforme au port PotActorAuthority. Aucune réponse « allow all », aucune transformation de Binding V2 en EUI V3. Elle ne qualifie ni AuthN, ni consentement, ni autorité L3. Probe READ applique un effet technique idempotent vérifié, complete puis closePublication dans sa transaction READ, observe RO cohérent ; il ne lit pas des données métier complètes en Discovery.

## 19. Activation et disponibilité réellement livrable

À clôture future : jar COMMAND existant avec profil dev explicite, deux boucles vivantes, threads/pools observables, Command réel et probes READ alimentés par ses publications, arrêt/restart sans bootstrap. Même assemblage dans MONOLITH pour prouver l’isolation/composition ; il ne devient pas un serveur fonctionnel V3 complet. Aucune modification des autres runtimes séparés ni de Docker de distribution finale.

Profil dev : servlet uniquement pour Actuator health/prometheus sur adresse loopback et port de management configuré ; aucun controller métier scanné, aucun endpoint de fixture/injection. Port applicatif distinct, aucun mapping métier ; propriété d’adresse explicite et tests de mappings. Runtime exécutable ≠ autorité Identity réelle ≠ API d’admission/lecture métier. **Aucun parcours Bruno V3 métier pertinent n’est disponible dans ce périmètre** ; les collections historiques ne peuvent appeler une méthode interne ni une fixture SQL. Interroger Prometheus/health avec curl est une observation opérationnelle, pas un parcours Bruno métier.

## 20. Observabilité opérationnelle

Réemploi : MeterRegistry/Actuator/registry-prometheus et conventions de Micrometer existants ; nouveau adaptateur V3 pour outcomes V3. Timers monotones locaux avec histogrammes (`publishPercentileHistogram`/buckets explicites expérimentaux 1ms,5ms,10ms,50ms,100ms,500ms,1s,2s,5s,30s,60s,120s) ; exporter la précision réelle. Aucun timestamp durable/journal de claims créé pour mesurer.

Labels bornés : `authority` 2 ; `consumption` libellé fermé9+2 probes ; `loop`2 ; `lane`3 par autorité ; `phase` liste fermée≤16 ; `outcome` liste fermée≤12 par métrique, **pas produit cartésien de tous** ; `pool`4. Partition exclue des séries par défaut, éventuellement4 seulement pour une métrique explicitement justifiée. worker/instance est une cible de scrape bornée par déploiement, pas UUID applicatif dynamique en label. `operation` métier n’est pas nécessaire au Worker. Budget par nom = produit des seuls labels de sa ligne (borne majorée ci-dessous), multiplié par buckets pour histogrammes ; vérifier sur /prometheus.

| Nom Micrometer proposé | Type / labels / cardinalité maximale hors buckets | Point exact / vérification future |
|---|---|---|
| `pocoma.v3.worker.state` | gauge one-hot state4,4 | Transitions superviseur ; start/drain/deadline/stop testés, pas STOPPED avant résolution |
| `pocoma.v3.worker.cycles` et `.cycle.duration` | counter + timer/histogram ; loop2,outcome6=12 | Retour de passage de boucle, y compris erreur ; appels indépendants et délais de polling |
| `pocoma.v3.rotation.opportunities` / `.tours` | counters consumption11,loop2=22 chacun | Offre réelle à C ; clôture de tour raw/U ; OR-EQ-WALK/CAP |
| `pocoma.v3.discovery.references` | counter consumption11,kind3(examined/advanced/admitted)=33 | Retours confirmés I4 ; amount exact ; NB examined ne devient pas inserted, perte ACK outcome unknown séparée |
| `pocoma.v3.discovery.duration` | timer/hist consumption11,outcome6=66 | Appel complet I4, acquisition connexion incluse ; comparaison SQL extérieur/barrières |
| `pocoma.v3.slot.navigation.keys` / `.acquisition.examined` | counters consumption11=11 chacun | Clés lues vs e confirmé ; saturations/UNKNOWN e sans avance ; OR-EQ-WALK |
| `pocoma.v3.lease.operations` | counter authority2,operation3(acquire/renew/suspend),outcome6=36 | Appels Engine et retours confirmés ; suspend n’est pas perte durable supposée |
| `pocoma.v3.claim.operations` | counter consumption11,operation3(acquire/renew/lost),outcome6=198 | Gains confirmés/renouvelés, CLAIM_LOST confirmé ; SKIP outcome distinct de lost |
| `pocoma.v3.renewal.duration` / `.schedule.lag` | timers/hist authority2,lane3,outcome6=36 | Chaque lot et retard monotone depuis réveil prévu ; S1/S2/lease sous saturation |
| `pocoma.v3.renewal.margin` | distribution summary authority2,kind2=4, unité secondes | Marge basée échéance confirmée/horloge DB lorsque disponible, sinon pas d’échantillon ; oracle privé mesure DB post-lock exact, proxy runtime biais annoncé |
| `pocoma.v3.capacity.used` / `.limit` | gauges scope worker+11 C=12 | Snapshot/limites immuables de l’unique WorkerCapacity ; maximum sous barrières et events reserve/release |
| `pocoma.v3.capacity.changes` | counter consumption11,kind2(reserved/returned)=22 | Section atomique réelle WorkerCapacity ; pas de décrément Worker au retour d’une Future |
| `pocoma.v3.saturation` | counter kind4(worker,consumption,executor,softcap),consumption11=44 | Décisions bornées de régulation ; pas incrément à chaque spin |
| `pocoma.v3.attempt.started` | counter consumption11=11 | Juste avant engine.execute dans envelope gagnant start ; soumission acceptée non comptée ici |
| `pocoma.v3.attempt.duration` / `.phase.duration` | timer/hist consumption11,outcome6=66 ; phase4,outcome6=264 | Début réel→sortie locale ; prepare/finalize/cession/observe instrumentés Engine ; aucune confusion avec durée physique UNKNOWN |
| `pocoma.v3.attempt.outcomes` | counter consumption11,outcome8=88 | Issue Engine ; métier normal/rejet distingué via participant si disponible, jamais ROLLED_BACK requalifié en rejet ; arrêt brut séparé |
| `pocoma.v3.retry.cessions` / `.quarantine.transitions` | counters consumption11,outcome3=33 chacun | Cession confirmée seulement ; UNKNOWN mesure opération incertaine, pas transition supposée |
| `pocoma.v3.dispatch.rejected` | counter consumption11,reason3(saturated/stopped/race)=33 | Catch RejectedExecutionException puis abandon avant début ; tâche jamais démarrée |
| `pocoma.v3.physical.unknown` / `.pending` | counter consumption11,phase3=33 ; gauge consumption11=11 | Failure UNKNOWN/raw et réservations non résolues ; COMPLETED R9 ne supprime pas l’incertitude historique |
| `pocoma.v3.takeover.observed` | counter authority2=2 | Seulement quand preuve durable d’ancien propriétaire expiré/token changé est disponible ; acquisition seule après redémarrage = acquire, pas takeover inféré |
| `pocoma.v3.drain.duration` / `.deadline` | timer outcome2=2 ; counter=1 | Fin propre vs deadline, jamais victoire de Future.cancel ; tests du superviseur |
| métriques Hikari/JVM/Actuator existantes | gauges/timers pool4 | active/idle/pending/max/emprunt et threads/GC ; vérifier les noms exposés par version effective plutôt que dupliquer Hikari |

Backlog et âge : comptage SQL périodique **hors réserve runtime**, par observateur d’exploitation/dev séparé et fréquence fixée (5s initial) ; count non-Q/Q, sources PENDING et plus ancienne date source si disponible. L’âge exact d’éligibilité n’est pas dans le slot ; fournir proxy source/échéances ou âge local depuis première observation, marqué borné et réinitialisé au restart. Ne pas ajouter createdAt de slot pour prétendre à un audit temporel exact. Les compteurs d’événements confirmés peuvent sous-compter sous ACK perdu : les appeler confirmés, les preuves SQL arbitrent les transitions durables.

Logs structurés : phase/C/autorité/issue et corrélation ciblée contrôlée ; **aucun token fencing/binding**, aucun toString de ClaimedSlot/PartitionLease/exception incluant paramètres sensibles. UUID slot/Pot/user/request ne sont **jamais labels Prometheus** ; diagnostics privés de certification peuvent conserver les clés/token jetables nécessaires aux assertions, séparés et non exposés. Metrics=exploitation ; traces=diagnostic ; SQL extérieur cohérent=preuve. Tester un observateur qui lève et une instrumentation omise/décalée : ni protocole altéré ni faux PASS de branchement ; O01-N doit détecter la mesure absente.

## 21. Démonstration reproductible Command / READ

**Tout ce chapitre est une procédure future NOT RUN.** Les scripts/classes suffixés worker-dev n’existent pas encore ; leur création fait partie d’I5 autorisé ultérieurement. Ne pas lancer ces commandes comme si le présent plan les avait livrés. Il n’existe actuellement aucun lanceur Worker V3 actif à tester.

Prérequis : JDK21 compatible avec le POM (toolchain effective à enregistrer), Maven wrapper, Docker pour le banc jetable ou PostgreSQL17.11 TCP dédié (version/digest du banc I4 à reprendre), pgcrypto dans public, client psql, Python3, ports locaux libres ; aucun Keycloak/Bruno nécessaire pour ce pilote interne. Deux **nouvelles bases** explicitement nommées, par exemple `pocoma_i5_write` / `pocoma_i5_read`, schéma `pocoma_i5`, rôles admin/runtime/injecteur/réactivation distincts, aucune appartenance runtime, secrets fournis par environnement/PGSERVICE hors Git et fichiers locaux600. Refuser cible `public`, cible existante non vide, noms/serveur inattendus ou absence de mandat dev ; pas de DROP/reset automatique.

Le futur `worker-dev.py` est un lanceur de développement, pas un installeur L8. Il vérifie config/cibles, utilise psql avec `-X -v ON_ERROR_STOP=1`, construit le jar existant sur demande puis extrait BOOT-INF/classes et libs dans un dossier temporaire pour invoquer `V3WorkerDevelopmentTools` (main Java **sans** contexte Spring). Le Java réutilise Flyway de l’application et ses migrations, sans chaîne V2 ; les opérations sont séparées `prepare`, `inject-command`, `verify`, `inspect`. `prepare` n’est jamais appelé par start/restart. L’injection utilise un principal distinct et un PreparedStatement/intention encodée par le codec livré, sans slot synchrone.

Contrat CLI proposé, commandes exactes envisageables depuis la racine :

```sh
# Construction de l'artefact dev ; pas une preuve de tests.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am package -DskipTests

# Configuration dev locale fournie explicitement, pas secret dans la ligne de commande.
python3 scripts/v3/worker-dev.py prepare --config /private/tmp/pocoma-i5-dev.properties
python3 scripts/v3/worker-dev.py inspect --config /private/tmp/pocoma-i5-dev.properties

java -jar app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar \
  --spring.profiles.active=v3-consumption-worker-dev \
  --spring.config.additional-location=file:/private/tmp/pocoma-i5-dev.properties \
  --pocoma.v3.worker.enabled=true \
  --pocoma.v3.worker.fixture-identity=true \
  --pocoma.v3.worker.technical-probes=true

# Dans un second terminal ; UUID fixe de démonstration, pas de nouvel ID au retry.
python3 scripts/v3/worker-dev.py inject-command --config /private/tmp/pocoma-i5-dev.properties \
  --scenario create-pot --request-id a5000000-0000-4000-8000-000000000100
python3 scripts/v3/worker-dev.py verify --config /private/tmp/pocoma-i5-dev.properties \
  --request-id a5000000-0000-4000-8000-000000000100 --await-seconds 90
curl --fail http://127.0.0.1:18081/actuator/health
curl --fail http://127.0.0.1:18081/actuator/prometheus
```

Le POM COMMAND a déjà le plugin Boot `classifier=exec`, version du parent0.0.1-SNAPSHOT ; ce chemin n’est pas un nouveau packaging. Le futur script refuse un jar absent/périmé et enregistre SHA Git, digest jar, Java/PG, config non sensible et commandes. Aucun `-DskipTests` de cette construction ne remplace les gates §24.

Propriétés nouvelles proposées à documenter intégralement dans `docs/v3/L2_I5_Worker_Development_Runbook.md` futur : `pocoma.v3.worker.{enabled,fixture-identity,technical-probes,worker-name,worker-limit,consumption-limits,partitions,min-poll,max-poll,error-poll,drain-timeout}` ; préfixes `pocoma.v3.worker.write-business`, `write-control`, `read-business`, `read-control` pour URL/user/password/max/minIdle/timeouts ; cibles attendues `pocoma.v3.pilot.{write,read}.{database,schema,username}` réutilisées pour guard, URLs/rôles identiques aux pools correspondants. Le script valide les secrets via variables d’environnement et renseigne le fichier local, ne les écrit pas dans un rapport. Rôles admin/injecteur/réactivation déclarés dans la configuration du **tool**, jamais injectés au contexte Worker. Paramètres enabled-C figés aux trois définitions disponibles, aucune acceptation libre de nom de classe/UUID. `management.server.port=18081`, `management.server.address=127.0.0.1`, port applicatif18080 loopback ; deuxième Worker ports18082/18083.

Étapes physiques de prepare, sans requête destructive :

1. Établir l’inventaire des bases/serveur/rôles et l’absence d’anciens writers. Créer bases/schémas/rôles neufs et extension avec l’administrateur explicite, pas Worker.
2. WRITE Flyway locations `classpath:db/v3-pot/migration`, `classpath:db/v3-pot-execution/migration`, `classpath:db/v3-consumption-write/migration`, donc V1…V5 existantes ; READ `classpath:db/v3-consumption-read/migration`, V1. Placeholders obligatoires `expectedDatabase/expectedSchema/expectedAdmin/expectedRuntime`, history isolée ; aucune baseline V2 supposée.
3. Sur chaque autorité, appeler `scripts/v3/bootstrap-l2-routing.sql` avec variables `expected_database/expected_schema/expected_admin/authority` et search_path explicite. Vérifier2/7 contrats,8/28 ownership, zéro slots/K. Exécuter lifecycle puis discovery avec runtime_role/reactivation_role ; ordre et inputs conformes aux scripts livrés. Les quatre pools utilisent ces mêmes rôles validés.
4. Installer les seuls objets de fixture/IDs techniques dev par le script séparé §25 ; vérifier2/9 et8/36. Fixture Identity associée à EUI de test/témoin/token de test et UserId connu, sans admission Identity métier ; probes READ vides, autorisations minimales. Créer principal injecteur avec seules permissions d’admission interne requises, distinct des grants runtime ; aucune connexion admin au Worker.
5. Avant injection : boot du Worker doit constater deux boucles et réserves établies, aucun ancien bean actif, pas de slot ni checkpoint burnable. Inspect peut vérifier `current_database/current_schema/current_user`, privilèges effectifs et sessions `application_name` pool/voie bornée ; les noms de sessions ne contiennent pas de token.

Après injection : source COMMAND PENDING durable, sans slot dans l’unité d’admission ; Discovery crée le slot canonique, Consumption acquiert un claim, ExecutePotCommand applique CreatePot, résultat/source terminale/DELETE/publications dans un seul xid8 WRITE. Oracle attendu préinscrit : une version Pot1, composantes initiales exactes du scénario, un POT_EVENT et une COMMAND_COMPLETION_EVENT chacun positionné, un seul résultat, source TERMINAL, slot Command absent. READ : deux effets de probe, chacun atomique avec son slot supprimé ; leurs checkpoints propres dépassent les positions globales sources couvertes ; **aucune** PotVersion/Balance/CommandResult métier READ attendue. Inspect capture séparément WRITE/READ (pas snapshot distribué), puis vérifie la stabilité après quiescence.

Arrêt : SIGTERM du processus identifié (PID du lancement enregistré, pas kill par motif large), observations DRAINING/STOPPED et ordre des fermetures. Injecter avant arrêt une seconde Command PENDING ou une tentative longue pour variante drain ; redémarrer avec exactement la même commande java **sans prepare**. Attendre finalisation des seuls incomplets et comparer compteurs/effets avant/après ; la première demande ne produit rien de plus. Variante crash SIGKILL contrôlée dans le banc : attendre conditions takeover en DB, jamais effacer le claim. `verify` utilise requêtes SQL extérieures et un délai borné de sondage ; timeout=échec d’observation/INCONCLUSIVE, pas succès. Les scénarios UNCHANGED/REJECTED réutilisent le vrai participant via méthodes de campagne, sans nouvelle API HTTP.

## 22. Campagne PostgreSQL et runtime déterministe

Classes/méthodes ci-dessous **proposées, NOT RUN**, noms figés pour le futur manifeste. Gate **C**=`./mvnw -pl runtime-command-consumption-worker -am test`, **R**=READ JDBC, **M**=monolithe, **A**=architecture, **F**=full final (§24). Chaque méthode PostgreSQL spécifie variantes WRITE réel/READ technique quand le mécanisme est commun ; ne compter les variantes ni les répétitions de gates comme des garanties métier nouvelles.

Dispositif commun : PostgreSQL17.11 épinglé comme I4, bases/schémas neufs, credentials runtime réels non propriétaires. Deux instances **du vrai assemblage Worker**, quatre pools chacune, un WorkerCapacity chacune. Connexions d’observateur/bloqueur distinctes des pools Worker ; registre indépendant des IDs/faits/partitions/positions/K/U/count/délais/token et attendus **avant** stimulus. Barrières Java pour phases locales, SQL/advisory de fixture pour phases DB, connexions maintenues ouvertes, PID/xid8, `pg_locks`, `pg_blocking_pids`, `pg_stat_activity`, `clock_timestamp()` post-lock et snapshots RR RO extérieurs. Les sleep seuls ne qualifient jamais un interleaving. Un proxy filaire réutilisé du banc I2/I3 différencie avant COMMIT, réponse COMMIT perdue et unité toujours active après retour local. Instrumentation de test distincte des écritures productives.

Unitaires POLL, mocks autorisés : `V3ConsumptionWorkerTest.i5_u01` rotation M_W1 ; `i5_u02` absence de progrès à e0/exception ; `i5_u03` accept/reject/stop/cancel pré-début ; `i5_u04` independent loops/renewals ; `i5_u05` drain idempotent/deadline ; `i5_u06` backoff borné/interruptions ; `i5_u07` snapshots concurrentiels sans verrou Java pendant JDBC. Ces unités ne prouvent ni locks physiques ni droits/atomicité PostgreSQL.

| Méthode future (classe précisée) | Stimulus déterministe / oracle indépendant et résultat requis | Frontière/gate |
|---|---|---|
| `SlotNavigationPostgresTest.i5_n01` | Jeu UUID incluant bit de signe, C/p multiples ; U/page comparés à ORDER BY SQL extérieur ; aucune clé filtrée par state/claim | WRITE/READ, C+A |
| `i5_n02` même classe | S1 libre,S2 verrouillé,S3 actif,S4 expiré,S5 retry futur,S6 Q ; page sans verrou obtenue pendant bloqueur ; e borné, seuls admissibles gagnent ; libérer S2/laisser échéances et garantir revisites aux tours suivants | WRITE/READ, C+A |
| `i5_n03` | Insérer S0 derrière cursor,S7>U, et clé dans (cursor,U] ; U immuable ; prochains tours couvrent S0/S7, comparer ensembles indépendants | WRITE/READ, C+A |
| `i5_n04` | Supprimer U/clé après navigation par vraie finalisation I2, page vide avant U, zéro gains SKIP ; pas fausse fin sur gains vides, nouvelle U au tour suivant | WRITE/READ, C+A |
| `i5_n05` | Page4, capacité2 puis1 puis0, partiel locked/Q ; e=taille préfixe réservé et soumis, pas gains ; suffixe jamais franchi ; leases refusé/expirant après scan et avant CAS inclus | WRITE/READ, C+A |
| `i5_n06` | Acquisition ACK perdu avant/après commit, Failure(COMMITTED) sans résultat, exception brute ; cursor reste identique, aucun execute des handles non rendus ; settle et expiry normaux | WRITE/READ, C+A |
| `i5_n07` | 100/1000/10000 slots/p, proportions Q/claims/retry, scan first/next/upper et PK vs walk index ; EXPLAIN ANALYZE BUFFERS/WAL, lignes JDBC, temps/emprunt/mémoire ; pas nouvelle migration | WRITE/READ, C+A |
| `i5_n08` | Restart à milieu tour avec slot durable encore inéligible ; nouveau tour dès début puis fenêtre libre, pas perte/double effet | WRITE/READ, C+A |
| `V3WorkerPostgresTest.i5_w01` | Deux boucles réellement actives ; maintenir tentative longue sous barrière et nouvelle source ; Discovery admet pendant traitement, Consumption démarre autre travail si place ; SQL extérieur slot/effets | Runtime mixte, C+F |
| `i5_w02` même classe | Deux Workers, mêmes3 C actives/P4, candidatures chevauchantes ; ownership durable exclusif ; chaque p servie, claims valides conservés | WRITE/READ, C+F |
| `i5_w03` | M_W1,2 probes continuellement éligibles et Command, quantum1 ; libérations contrôlées ; séquence d’opportunités et gains après fenêtres libres prouve absence de monopolisation par redémarrage de pointeur | Mixte, C+F |
| `i5_w04` | M_W4,M_Command1,M_probeA2,M_probeB3 ; barrières de démarrage/finalisation/cession et snapshots à chaque réservation/libération ; journal d’occupation indépendant ≤M_C/M_W, capacité partagée WRITE/READ | Mixte, C+F |
| `i5_w05` | Forcer reject saturation/stop entre acquire et execute, tâche acceptée mais pas démarrée puis cancel ; aucun callback métier, abandon une fois, ticket0, claim intact jusqu’à expiry, count inchangé | WRITE/READ, C+F |
| `i5_w06` | Cancel/interrupt après début, Future déclarée finie mais callback retenu ; aucun ticket rendu avant sortie réelle + résolution ; aucun double effet | WRITE/READ, C+F |
| `i5_w07` | Retry/cession après vrai rollback, SQL comptabilisable, collision Pot réelle puis recharge, UNCHANGED/REJECTED normal ; count/délais/quarantaine exacts en SQL extérieur | Command réel + analogue READ, C+F |
| `i5_w08` | Q persistante, softCap plein et Q exclue ; restart/deadline ne réactivent pas ; autres slots/C progressent ; admin ciblé dépasse cap et D suspend/reprend ; pas purge | WRITE/READ, C+F |
| `i5_w09` | A perd lease à B alors que claim A valide ; A Discovery/acquire refusés, A renewal claim et finalisation réussissent, B autres slots avance, aucun takeover anticipé | WRITE/READ, C+F |
| `i5_w10` | C03-E/F/G/H : ancien token expiré avant lock, expiration pendant attente, expiration après lock valide, takeover concurrent ; DB-now post-attente et effets uniques | WRITE/READ, C+F |
| `i5_w11` | UNKNOWN finale/cession avant et après COMMIT, transaction toujours active après retour ; inspect saute tant que lock actif, garde ticket après deadline ; résolution externe puis restitution unique sans réexécution/cession répétée | WRITE/READ, C+F |
| `i5_w12` | Arrêt progressif à préparation/acquisition/avant-start/finalisation/cession ; renewals continuent pendant drain ; ordre fermeture/callback et zéro tâches tardives ; doublon stop sans effet | WRITE/READ, C+F |
| `i5_w13` | Deadline drain avec transaction toujours physique et callback arrêté ; pas STOPPED/rendu anticipé ; terminaison externe puis restart/takeover fenced | WRITE/READ, C+F |
| `i5_w14` | JVM enfant crash avant/après admission, acquisition, DELETE, publication, COMMIT et cession ; processus ne peut appeler cleanup ; résolutions DB établies puis autre Worker ; sources/K/Q conservés et aucun double effet | WRITE/READ, C+F |
| `i5_w15` | Démo CreatePot réel depuis PENDING interne, vrai participant, deux sources réelles I4 et probes READ ; snapshot complet préinscrit, un seul xid WRITE effets/slot/publications, autre xid READ par probe | Mixte réel/technique, C+F |
| `i5_w16` | Vide prolongé puis source isolée, fortes C/p asymétriques ; SQL/CPU/réveils/délais et progression indépendants, aucune boucle chaude ; fenêtres de mesure5min avant/5min après | Runtime, C+F |
| `V3WorkerResourcesPostgresTest.i5_r01` | Saturer les tentatives Business réellement autorisées ; variante de faute épuise physiquement les connexions Business restantes par bloqueurs de test identifiés. Renewals Control deux claims+lease empruntent et commitent ; distinguer preuve d’inventaire runtime et stress de pool, Hikari/PID/deadlines effectifs | WRITE/READ, C+F |
| `i5_r02` même classe | Exercer chaque catégorie non-renewal à son maximum puis leur maximum **conjointement admissible** (pas la somme artificielle de maxima incompatibles), avec vraies cessions après rollback et D/acquire/settle. Prouver plafond≤M_W+3 et3 connexions renewal disponibles ; stress séparé remplit les places non-renewal par bloqueurs de test identifiés | WRITE/READ, C+F |
| `i5_r03` | S1 row lock : renewal SKIP sans blocage de S2 ; autre variante S1 UPDATE retenu par trigger/advisory **test seulement ciblé S1**, pg_blocking_pids montre attente ; autre voie renouvelle S2 puis lease pendant S1 retenu ; pas DDL bloquant toute table | WRITE/READ, C+F |
| `i5_r04` | Verrou de relation bloquant tous claims, réseau coupé et échéances proches ; timeouts/backoff/UNKNOWN explicites, jamais fausse garantie de renouvellement durable ; récupération conditionnelle sans résurrection | WRITE/READ, C+F |
| `i5_r05` | Toutes C/p maximales admises au profil et2 Workers ; cadence/retards/emprunt/locks/commit/jitter mesurés, calcul §14 préfigé ; minimum marge DB positif sous hypothèses ; variantes B1/B32 et M_W1/4 | Runtime, C+F |
| `i5_r06` | Coupure laissant backend physique résiduel après éviction ; compter pg_stat_activity et Hikari indépendamment ; transaction_timeout observé, réserve renouvellement et budget max_connections contrôlés | WRITE/READ, C+F |
| `i5_r07` | Tentative longue >lease, <claim avec renewals puis lease perdu ; pas d’interruption automatique, claim maintenu ; variante claim expirant refusée au lock ; aucune connexion Business retenue pendant cession Control | WRITE/READ, C+F |
| `i5_r08` | Mauvais pool/store/EMF/manager même DB ou autre DB, owner intérieur/ambiant et read context étranger ; refus avant effet/poison vérifiés, PID/xid corrects dans cas sain | WRITE/READ, C+A+F |
| `V3WorkerCompositionPostgresTest.i5_c01` | Profils absent/inert/worker/inert+worker, enabled manquant, flags fixture absents ; worker actif seulement opt-in complet ; I1 reste strict et inerte | C+A |
| `i5_c02` même classe | UUID/famille/P/authority/rights/ancienne table/double WorkerCapacity/source ou participant absent ; refus fail-closed avant threads ; aucun admin runtime | C+A |
| `i5_c03` | Comptage beans/threads effectifs et POM réel ; aucun Legacy/scheduler/migrateur/repository/controller métier ;4 pools et managers qualifiés ; hash/routage/probes distincts | C+A |
| `i5_c04` | prepare sur cible non vide/fausse base/refus rôle ; restart sans prepare ; témoins V2/L1 extérieurs identiques ; pas INSERT source possible par rôle Worker | C+A |
| `V3WorkerMetricsTest.i5_m01` | Nombre connu d’événements chacune des branches ; compteurs confirmés et timers égalent journal indépendant, label sets/buckets bornés, aucun UUID/token/log toString ; observateur défaillant sans effet protocolaire | C+A |
| `i5_m02` même classe | O01-N instrument volontairement omis/décalé en banc ; vérificateur détecte défaut ; UNKNOWN distinct commit/rollback et démarrage distinct submit | C+A |
| `V3WorkerMonolithPostgresTest.i5_mono01` | Même assemblage mixte sous racine monolithe, profil dev seul ; finalisation réelle Command/probes, deux autorités/pools ; absence HTTP métier/Legacy, drain/restart | M+F |
| `ReadDiscoveryPrivilegesPostgresTest.i5_read_pools` (extension) | Deux pools READ même rôle exact et droits autorisés, excès membership/DDL/routage refusés ; grants probes dev ne changent pas contrats I1 et runtime sans injection | R+A+F |

Les tests de ressources peuvent utiliser des barrières/seams de **test** autour des vrais ports/connexions pour conserver toutes les voies occupées, mais aucune implémentation mock de transaction/store/renewal ne remplace les vraies opérations. R02 doit enregistrer l’inventaire complet et un témoin positif de chaque emprunteur ; louer des connexions arbitraires à la place des cessions/boucles ne suffirait pas à certifier l’assemblage. La somme des majorants est conservative : le même ticket ne peut simultanément porter une finalisation et une cession. La saturation physique supplémentaire est une injection de faute séparée, jamais présentée comme une occupation naturellement atteignable par le runtime. R03 différencie row SKIP et vraie attente ciblée, pas un sommeil local qualifié abusivement de lock PostgreSQL.

Campagne coût local n07/r05/w16 :5 échauffements+30 mesures par configuration, jeux déterministes et proportions0/50/90% Q/actifs/retry, B1/32,100/1000/10000 slots/p ; enregistrer SQL/lignes/buffers/WAL, p50/p95/p99 descriptifs et maxima, Hikari/CPU/GC/mémoire, backlog et âge proxy. DML EXPLAIN seulement sur cible jetable puis rollback ; aucune suppression d’index sur une cible d’utilisateur. SLO SPEC-09 et 10M événements reportés L6, ces mesures ne les certifient pas.

## 23. Allocation SPEC-10, R10 et oracles

Matrice additionnelle I5 ; **toutes lignes futures statut NOT RUN**, même lorsqu’une preuve primitive historique est PASS. Source des obligations=SPEC-10/R1–R10 et [manifeste L2](L2_Certification_Coverage_Manifest.md). Ne modifier/supprimer aucune des allocations existantes ; le futur manifeste I5 les référence par ID, conserve les reports réels et importe les méthodes héritées exactes des manifestes I2/I3 corrigé/I4. Aucun wildcard d’héritage non résolu ni PASS par succès Maven seul. Gates ci-dessous sont §22/24 ; preuve **P** signifie XML frais+trace de barrière/PID/xid/horloge+snapshots SQL extérieur avant/après+SHA/config/commande, pas uniquement un log Engine. Toutes les cellules P incluent leur portée réel/fixture.

### 23.1 CON-03 : douze variantes individuellement allouées

| ID normatif → méthode future | Stimulus | Oracle indépendant | Frontière PostgreSQL / gate | Preuve attendue / statut |
|---|---|---|---|---|
| C03-A → w09 | A garde claim valide après transfert B | OR-CL-OWNER/AT-UNIT, état ownership/slot/effets extérieur | WRITE + READ technique, C/F | P, A finit/B autres slots ; NOT RUN |
| C03-B → w09,c02 | Ancien token demande D/acquire | OR-CL-OWNER, absence delta SQL | Deux autorités, C/A/F | P, zéro gain ancien owner ; NOT RUN |
| C03-C → w09,r03 | Renewal claim A pendant renewal lease B | OR-CL-OWNER/EQ-RENEW, deadlines DB | Deux autorités, C/F | P, indépendance vérifiée ; NOT RUN |
| C03-D → w09,r07 | Lease expiré, claim non expiré | OR-CL-OWNER, CAS refusé B sur S | Deux autorités, C/F | P, pas reprise anticipée ; NOT RUN |
| C03-E → w10 | Claim expiré, takeover contre renewal tardif | OR-CL-FENCE, token/deadline DB | Deux autorités, C/F | P, zéro résurrection ; NOT RUN |
| C03-F → w10,w11 | Claim valide au lock, expiry avant commit | OR-CL-FENCE/AT-UNIT, attente takeover prouvée | Deux autorités, C/F | P, un seul effet après résolution ; NOT RUN |
| C03-G → w10 | Expire pendant attente de lock | OR-CL-FENCE, clock_timestamp post-lock | Deux autorités, C/F | P, refus avant effets ; NOT RUN |
| C03-H → w10 | Token B remplace A avant retour A | OR-CL-FENCE, SQL token courant | Deux autorités, C/F | P, A ne mute rien ; NOT RUN |
| C03-I → w02,w09 | B perd aussi sa partition après takeover | OR-CL-OWNER, troisième phase de transfert dans w09 | Deux autorités, C/F | P, claim B reste indépendant ; NOT RUN |
| C03-J → w11,w14 | Crash/UNKNOWN pendant finalisation | OR-AT-UNIT/CL-FENCE, résolution serveur | Deux autorités, C/F | P, effets uniques ; NOT RUN |
| C03-K → w02 | Deux Workers/P4, alternance propriété | OR-CL-OWNER/AR-ROUTE, routing externe | Deux autorités, C/F | P, propriété régulière et couples couverts ; NOT RUN |
| C03-L → w02,m01,r05 | Campagne répétée, durées variables/takeover | OR-OBS-METRIC/EQ-RENEW, SQL vs métriques | Deux autorités, C/A/F | P+scrapes+histogrammes ; NOT RUN |

C03-I impose explicitement dans w09 A→B→A avec B possédant le claim repris ; ne pas remplacer par deux répétitions de C03-A sans reprise effective. Les primitives `i3_c03_a`…`i3_c03_l`, `i3_acquisition_lease_clock`, `i3_lease_races` sont reprises fraîches en non-régression ; elles ne remplacent pas ces exécutions avec les vrais threads/pools I5.

### 23.2 R10 et réserve d’audit

| ID normatif → méthode future | Stimulus | Oracle indépendant | Frontière / gate | Preuve attendue / statut |
|---|---|---|---|---|
| R10-01 → w02,w03,n01 | C/p asymétriques, quantum1, tours de clés | OR-EQ-WALK, liste de fixture SQL | Mixte, C/F | P, occasions C puis p répétées ; NOT RUN |
| R10-02 → n01,n08,c03 | UUID unsigned/tours volatils/restart | OR-EQ-WALK/AR-BOUND, catalogue tables | WRITE/READ, C/A/F | P, aucun état scheduler durable ; NOT RUN |
| R10-03 → n03,n05,n06 | Insertions avant/après U, e partiel/exception | OR-EQ-WALK, clés SQL préinscrites | WRITE/READ, C/A/F | P, aucun franchissement non soumis ; NOT RUN |
| R10-04 → n02,n04 | Tous slots verrouillés, navigation non vide/acquire vide | OR-EQ-WALK, bloqueur extérieur | WRITE/READ, C/A/F | P, pas fin fictive ; NOT RUN |
| R10-05 → n02,w08 | Actif/expiré/retry/Q puis fenêtre libre | OR-EQ-WALK/RT-QUAR, horloge DB | WRITE/READ, C/F | P, revisite et Q intacte ; NOT RUN |
| R10-06 → n05,w04,w05,w06,w11 | Saturation/partiel/reject/cancel/UNKNOWN | OR-EQ-CAP, admission indépendante + SQL | Mixte, C/F | P, max≤M_C/M_W, e correct ; NOT RUN |
| R10-07 → w03,w04,w08,c02 | M_C distinct, worker partagé, softCap/admin | OR-EQ-CAP/NB-CAP, COUNT extérieur | Mixte, C/A/F | P, aucune réserve permanente/distribuée ; NOT RUN |
| R10-08 → r01…r07 | Pools réellement saturés/S1 bloqué/S2/lease | OR-EQ-RENEW/CL-RENEW, PID/deadlines | Deux autorités, C/F | P+config effective et calcul marge ; NOT RUN |
| R10-09 → w09,r07 | Lease perdu, claim valide renouvelé | OR-CL-OWNER, SQL extérieur | Deux autorités, C/F | P, stop D/acquire pas claim renewal ; NOT RUN |
| R10-10 → w03,w16,n07,m01,r05 | Capacité/fenêtres libres contrôlées, âge proxy/coûts | OR-EQ-WALK/OBS-METRIC, registre source | Mixte, C/A/F | P+mesures, hypothèses explicites ; NOT RUN |
| R10-11 → c03 + architecture | Inventaire tables/beans/POM et contre-exemples | OR-AR-BOUND/NOPURGE, catalogue/imports | Statique + DB, A/F | P+rapport ArchUnit, aucun coordinateur externe ; NOT RUN |
| R-AUD-I3-03 → r01…r08,w01,w12 | Tous emprunteurs réels, threads distincts, saturation et drain | OR-EQ-RENEW/AR-BOUND, inventaire/PID indépendant | WRITE/READ, C/A/F | P, réserve effective mesurée ; **NOT RUN / réserve ouverte** |

### 23.3 Autres exigences et régressions obligatoires

| ID normatif → méthode future / héritage frais | Stimulus | Oracle indépendant | Frontière / gate | Preuve attendue / statut |
|---|---|---|---|---|
| CON-01, C01-A…L et compléments I4 | w01,w02,w08,w15,w16 + toutes49 méthodes I4 selon manifeste | Fenêtres/source réelle, softCap/restart ; OR-NB-SET/FENCE/CAP, OR-B-SET/KEYSET/CRASH | WRITE/READ, C/M/A/F | P, K/slots exacts, progrès/admissions distincts ; NOT RUN |
| CON-02, C02-A…L | w11,w14,w15 + héritage I4 N/X et I2 | Coupures avant/après K/DELETE et ACK ; OR-NB-ATOMIC/AT-UNIT/AR-NOPURGE | WRITE/READ, C/M/A/F | P, aucun demi-enrôlement/finalisation, reset partiel refusé ; NOT RUN |
| CON-04, C04-A…I/K/L | w02,w09,w10,w11,w14,w15 + I2/I3 corrigé | Multi-Workers/crash/fault/UNKNOWN ; OR-AT-UNIT/CL-FENCE/RC-FOUR | WRITE réel + READ technique, C/M/A/F | P, non-double effet/terminal/résultat ; NOT RUN |
| C04-J | Probes distincts w02,w15 pour mécanique ; matérialisations réelles L4 | Effets READ séparés ; OR-AT-READ, pas projection métier simulée | READ technique C/F ; métier L4 | NOT RUN technique ; **DEFER L4**, aucun PASS métier |
| CON-05, C05-A…M et Q/NOPURGE/SOFTCAP/REACTCAP | w05,w07,w08,w11,w14,m01 + i3_c05_a…m et variantes post-audit | Fautes/collisions/retry/Q/admin ; OR-RT-CEDE/QUAR/REACT/CONFIG, OR-L1-STATE | Command réel + READ technique, C/M/A/F | P, count une fois après rollback certain, retryRespect ; NOT RUN |
| CON-06 C06-A/E/F/G/H/I/J/K/L/M | r08,w15,c01…c04,mono01 + I2 guard/owner | JDBC/JPA, owners imbriqués/DS étrangère/profils ; OR-AT-UNIT/AR-BOUND | WRITE réel/READ technique, C/M/A/F | P, un PID/xid par unité finale, aucun nesting/2PC ; NOT RUN |
| C06-B | Registration2 events réelle | OR-AT-UNIT sur autorité Identity réelle | WRITE L3 | **DEFER L3, NOT RUN**, fixture ne satisfait pas |
| C06-C/D/N/O/P | w15 probes pour propriété physique ; métier complet reporté | OR-AT-READ, deux effects techniques indépendants | READ C/F puis L4/L5 | NOT RUN technique ; projections réelles **DEFER L4/L5** |
| R1-01…10 / R9-01…10 | w11,w15,r08 + toutes obligations I2 R1/R9 | Slot/effets/suffixe/completion poison et4 observations ; OR-AT-UNIT/POISON/DENSE, OR-RC-FOUR | WRITE/READ, C/M/A/F | P, protocole I2 conservé sous pools séparés ; NOT RUN |
| R2-01…09 / R4-01…10 | w08,n03,w14 + I4 complet | Cap/CAS/admin/frontière burnable ; OR-NB-CAP/B-KEYSET/SAT | WRITE/READ, C/M/A/F | P, aucune avance fictive/checkpoint burnable ; NOT RUN |
| R3-01…10 / R7-01…09 | c02,c04,read_pools,w02 | Bootstrap/routes/rights/restart ; OR-AR-ROUTE/BOOT | WRITE/READ, C/R/M/A/F | P, neuf canoniques + seuls probes dev autorisés, ancienne cible inchangée ; NOT RUN |
| R5-01…11 / R8-01…05 | w07,w08,w11,w14 + I3 corrigé/I4 | Cession unique/config prospective/Q persistante ; OR-RT-CEDE/CONFIG/AR-NOPURGE | WRITE/READ, C/M/A/F | P, aucune purge/réactivation automatique ; NOT RUN |
| R6-01…08 | futur collecteur/manifeste I5 + revue I6/I7/G3 | Méthode absente/skippée/dupliquée, fixture et reports ; OR-AR-CERT | Toutes autorités, gates exécutés puis G3 | NOT RUN, inventaires conservés, aucune autoacceptation |
| R-AUD-I3-01/02 | w07,w11 + toutes5 méthodes audit lifecycle et tests Engine/composite du manifeste corrigé | Classifier Error/null/originalException/transport et cession ouverte ; OR-EQ-CAP/RT-CEDE | WRITE/READ, C/M/A/F | P, pas de fuite ni release prématuré ; NOT RUN |
| OPS-01 O01-A…G/I/K/L/M/N | w01…w16,n02,n07,r01…r07,m01/m02 | Petite/grosse C, slots anciens, idle, fautes, saturation/pause READ, métrique omise ; OR-EQ-WALK/CAP/RENEW/OBS-METRIC | Runtime borné mixte, C/F | P+mesures locales, **NOT RUN** ; O01-M technique seulement |
| O01-H/J, PERF-01 F01-A…J | n07/w16 contributions coût100…10000, pas chaîne READ métier | OR-OBS-METRIC ; SQL/plans/CPU/WAL | Local C/F, intégré L6 | NOT RUN local ; **DEFER L6** 1/10/50s,10k/1M/10M, rattrapage et SLO complets |
| SPEC-08 O-01 / C05-M / C03-L / O01-N | m01,m02,r05,w16 | Comparer frontières/labels/histos au journal/SQL externe ; OR-OBS-METRIC | Runtime C/A/F | P+scrapes, pas audit temporel durable ; NOT RUN |
| EVT-01/02/03 applicables | w15,w14 + I2/I4 invariants densité/fenêtres | POT/COMPLETION réels, identité/nombre et positions préinscrits ; OR-AT-DENSE/UNIT/NB-SET | WRITE réel/READ probe, C/M/A/F | P ; producteurs Identity/Invitation et3 matérialisations réelles reportés ; NOT RUN |
| L1-C/F/F2, DOM-02 applicable | w07,w15 + héritage intégral obligations I2 | Command réel, CAS/intent immuable/8 tables/suffixe/localité ; OR-L1-STATE/AT-UNIT | WRITE, C/M/A/F | P, mêmes variantes historiques rejouées au SHA I5 ; NOT RUN |
| REC-01/READY | c04,w12,w14 négatifs de reset ; pas reconstruction métier | OR-AR-NOPURGE/BOOT | Runtime borné, C/F ; intégration L6 | NOT RUN ; **DEFER L6** maintenance exclusive/reset total/projections/READY |

Les groupes d’héritage doivent être développés **mécaniquement en IDs et méthodes exacts** dans le manifeste JSON avant code de campagne : 142 méthodes I2 et leurs owners/régressions, obligations I3 post-audit en sur-ensemble du manifeste I3 original,49 méthodes I4 avec sous-variantes et gates. Le document global conserve253 IDs normatifs/380 lignes/56 reports selon le dernier contrôle historique, à recalculer sans supposer ces nombres éternels. Les manifestes historiques ont des structures différentes : I2/I3/post-audit portent required_groups/gate_groups et exigences physiques, I4 porte aussi proofs[49] ; le collecteur doit lire leurs vraies structures, pas supposer une clé proofs uniforme. Le contrôle du manifeste seul ne prouve pas leur suffisance ; la revue compare les stimuli/attendus des variantes normatives, en particulier les clauses conservées C06-N/O/P. Identifiant absent/skip obligatoire=INCONCLUSIVE de la contribution ; violation ouverte=FAIL ; aucun PASS global pour un producteur/projection différé.

## 24. Verification scope futur et gates Maven

**Mandat présent :** primaire NONE/documentaire, secondaires NONE ; impact app/SQL/tests/POM interdit ; DB NONE ; architecture globale NOT REQUIRED ; full NOT REQUIRED. Aucune commande Maven/PostgreSQL exécutée. Vérifications actuelles = statuts Git, lectures directes, cohérence/chemins/signatures, diff et contrôles documentaires §30.

**Déclaration prospective avant code I5**, à reconfirmer sur la tête réellement approuvée :

| Champ | Déclaration |
|---|---|
| Impact productif attendu | Engine : sélection explicite des owners/stores et observation ; POLL orchestration V3 ; PRIMARY navigation ; COMPOSITION pools/profils/guards ; COMMAND et MONOLITH raccords minces ; outils/fixtures dev |
| Slice primaire | **COMMAND**, ancre `runtime-command-consumption-worker` |
| Slices secondaires canoniques | **NONE** tant que les runtimes EVENT/PROJECTION/BINDING/LKV/WEB ne changent pas de comportement ; leurs V3 métier restent absents |
| Preuves complémentaires | **infra-persistence-read-jdbc**, **runtime-monolith** ; preuves unitaires locales Engine/POLL possibles en développement |
| Modules directement affectés | engine-consumption, orchestrator-poll-consumption, infra-persistence-primary-jpa, composition-runtime-spring, runtime-command-consumption-worker, runtime-monolith ; tests infra-persistence-read-jdbc et architecture-tests ; scripts/docs dédiés |
| Impact interdit | Domaines, port transactionnel/owner Spring I2, SQL de finalisation/claim/cession/discovery, schémas productifs/routage canonique, producteurs/projections/HTTP métier, autres runtimes et nettoyage général |
| Autorités PostgreSQL | **CURRENT V3 neuf**, WRITE/READ isolés, migrations existantes ; fixtures dev supplémentaires explicitement séparées ; pas HISTORICAL/BASELINE par défaut |
| Architecture globale | **REQUIRED** : nouvelle arête POLL→Engine, raccord runtime READ, règles d’isolation active et pools modifiés ; une fois au gate structurel/final, pas à chaque edit |
| Full reactor | **REQUIRED une fois au gate final d’intégration I5** : premier runtime actif et infrastructure transversale threads/pools dans deux racines constituent un jalon majeur ; pas requis aux sous-étapes intermédiaires |
| Prochain global L2 | I6/I7 puis G3 restent distincts ; full I5 ne remplace pas dossier/audit/acceptation L2 |
| Escalade | Besoin de changer participant/owner/DDL, dépendance ou comportement hors boundary : consigner finding, réviser scope avant code ; pas lancer full pour masquer une incertitude |

L’ancre POLL existe mais son POM actuel ne dépend pas directement engine-consumption : ajouter cette arête, pas une dépendance inverse Engine→orchestrateur. COMPOSITION possède déjà des optional vers POLL/PRIMARY/READ/infra-tx ; COMMAND reçoit POLL/PRIMARY/infra-tx mais pas nécessairement READ optional, donc ajouter **une dépendance directe READ** pour migrations et tests du banc actif. MONOLITH reçoit déjà READ/POLL. Si les tests ou imports révèlent un besoin de dépendance directe déjà utilisée transitivement, le déclarer dans le diff POM avant implémentation ; aucune nouvelle version/bibliothèque/module prévue.

Le plan L2 initial listait EVENT/PROJECTION comme secondaires envisagées. L’inventaire actuel les resserre : aucune de leurs Consumptions V3 réelles n’est activée ou modifiée par I5, le banc READ est dans COMMAND avec vraie autorité READ. Leur présence dans la fermeture de compilation ou dans full n’est pas un changement de capacité. Si l’on devait finalement y installer un Worker ou modifier un contrat historique, elles deviendraient secondaires déclarées avant changement, commandes canoniques à ajouter alors ; aucune réduction d’allocation métier de SPEC-10.

Commandes finales, depuis `app/`, **NOT RUN ici**, sans filtre/exclusion :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
./mvnw test
```

Développement intermédiaire autorisé futur : `./mvnw -pl engine-consumption -am test` et `./mvnw -pl orchestrator-poll-consumption -am test` pour leurs seules propriétés ; ils ne remplacent pas COMMAND ni PG réel. Pas de `-amd` automatique. Pas de migrations historiques supplémentaires par habitude ; si la fermeture canonique exécute des tests V2/Flyway historiques, le rapport le consigne séparément. V3 neuf n’est pas une baseline V2 TRUSTED.

Toutes les méthodes requises doivent apparaître effectivement dans XML frais : aucun motif de nom de classe exclu par Surefire, aucun skip/filtre obligatoire. Collecteur par SHA figé, commandes/version environnement, sous-variantes physiques et sources/classes empreintées, résultats séparés des diagnostics ; un skip exploratoire historique reste SKIP et ne devient pas PASS. Si code/scripts changent après un gate, rejouer les gates affectés puis les gates finaux sur le même SHA cohérent ; aucune addition des décomptes du même test entre gates.

## 25. Fichiers exacts : CREATE / MODIFY / REUSE UNCHANGED / DEFER

**Ce tableau est prospectif, pas le diff de ce mandat.** Les alias J/T/R du §3 développent des chemins exacts. Les listes entre accolades ci-dessous désignent chacun des fichiers énumérés, pas un wildcard autorisant d’autres changements. Aucun fichier productif hors tableau n’est implicitement autorisé ; finding et redéclaration requis si besoin réel. Pas de nouveau module ni migration productive.

### CREATE — futurs composants et preuves

| Chemin exact (avec alias définis §3) | Responsabilité concrète |
|---|---|
| `J(engine-consumption)/engine/consumption/v3/port/SlotKeyNavigation.java` | Port de clés/tour, records Walk/Page, sans acquisition ni SQL |
| `J(engine-consumption)/engine/consumption/v3/port/ConsumptionObservation.java` | Observation technique pure, bornée, no-op ; aucun owner/metric backend |
| `J(PRIMARY)/infra/persistence/primary/jpa/v3/consumption/JdbcSlotKeyNavigation.java` | Trois formes SQL §10 et contexte RO/timeouts, injection DS des deux autorités |
| `J(POLL)/orchestrator/poll/consumption/v3/V3ConsumptionWorker.java` | Superviseur local/drain, exécuteur métier et registre de références ; types de rattachement/envelope imbriqués |
| `J(POLL)/orchestrator/poll/consumption/v3/V3DiscoveryLoop.java` | Rotation et RequestWalk, appels I4 uniquement |
| `J(POLL)/orchestrator/poll/consumption/v3/V3ConsumptionLoop.java` | Rotation/SlotWalk, nav/acquire/dispatch sans SQL ni tickets propriétaires |
| `J(POLL)/orchestrator/poll/consumption/v3/V3RenewalService.java` | Voies claims/lease, maintenance settle et santé, jamais d’effets métier |
| `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java` | Opt-in actif, propriétés/qualifiers, quatre pools/TM/EMF, assemblage, SmartLifecycle adaptateur imbriqué |
| `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionWorkerSettings.java` | Propriétés et validation fail-closed des bounds/emprunteurs/configs, pas état durable |
| `J(COMPOSITION)/runtime/consumption/v3/MicrometerV3ConsumptionObservation.java` | Traduction des événements bornés en métriques §20 ; aucune requête DB |
| `J(COMPOSITION)/runtime/consumption/v3/dev/V3TechnicalReadParticipant.java` | Probe READ nommé, garde/completion/close/observe, aucune projection métier |
| `J(COMPOSITION)/runtime/consumption/v3/dev/V3FixturePotActorAuthority.java` | Témoin Identity de fixture dev positif/négatif protégé, aucun fournisseur L3 |
| `J(COMMAND)/runtime/command/v3/dev/V3WorkerDevelopmentTools.java` | Main administratif/injection/inspection dev hors Spring ; Flyway existant, cibles neuves et oracle externe |
| `R(COMMAND)/application-v3-consumption-worker-dev.properties` | Profil actif explicite, exclusions auto, management loopback, pas secret/default cible DB |
| `R(MONOLITH)/application-v3-consumption-worker-dev.properties` | Même isolation du monolithe, flags Legacy/HTTP inactifs |
| `T(POLL)/orchestrator/poll/consumption/v3/V3ConsumptionWorkerTest.java` | i5_u01…u07 |
| `T(PRIMARY)/infra/persistence/primary/jpa/v3/SlotNavigationPostgresTest.java` | i5_n01…n08, vraies DS WRITE/READ et coûts |
| `T(COMMAND)/runtime/command/v3/V3WorkerPostgresTest.java` | i5_w01…w16, assemblage actif réel, JVM enfants pour crash |
| `T(COMMAND)/runtime/command/v3/V3WorkerResourcesPostgresTest.java` | i5_r01…r08, emprunteurs/pools/renewals physiques |
| `T(COMMAND)/runtime/command/v3/V3WorkerCompositionPostgresTest.java` | i5_c01…c04, refus et profils positifs |
| `T(COMMAND)/runtime/command/v3/V3WorkerMetricsTest.java` | i5_m01/m02, cardinalité/corrélation/observateur défaillant |
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3WorkerMonolithPostgresTest.java` | i5_mono01, composition par vraie racine monolithe |
| `scripts/v3/worker-dev.py` | CLI dev §21, prepare/inject-command/inspect/verify, extraction jar/temp et psql sans secrets/logs |
| `scripts/v3/bootstrap-l2-worker-dev.sql` | Ajout administratif des seuls probes et tables fixture, bases neuves ; appels distincts WRITE/READ avec assertions |
| `scripts/v3/grant-l2-worker-dev.sql` | Droits minimaux fixtures/injecteur et assertions dev ; aucun élargissement DDL/routage runtime |
| `scripts/testing/verify-v3-l2-i5.py` | Collecte fraîche, manifeste/variantes/empreintes/gates et distinction diagnostics |
| `scripts/testing/test_i5_evidence.py` | Refus artefact manquant/skip/doublon/périmé, statut report/fixture et sous-variante absente |
| `docs/v3/L2_I5_Proof_Manifest.json` | Allocation additionnelle/héritage exact, méthodes et preuves par gate ; initialement NOT RUN |
| `docs/v3/L2_I5_Execution_Report.md` | Rapport ultérieur des résultats réellement exécutés, scopes et réserves |
| `docs/v3/L2_I5_Worker_Development_Runbook.md` | Paramètres/commandes/attendus et limites HTTP/Bruno de développement |

Les records auxiliaires et seams de test restent imbriqués dans les fichiers nommés ; si une séparation concrète devient nécessaire, la justifier et inscrire le chemin avant création, pas créer un framework générique.

### MODIFY — adaptations limitées futures

| Chemin | Delta autorisé par cette conception |
|---|---|
| `J(engine-consumption)/engine/consumption/v3/ConsumptionEngine.java` | Surcharge deux owners/stores, navigation, accès RO de réservation, hooks observation ; préserver corps R1/R9/classification/settle logique |
| Même package, `WorkerCapacity.java` | Accès RO package-private Ticket et hook variations non bloquant ; réservation/release synchronisées inchangées |
| Même package, `DiscoveryEngine.java` | Hooks opérationnels si nécessaires aux frontières de mesure ; aucun changement de ses décisions/preuves de préfixe |
| `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` | Factory active avec ressources contrôlées/deux owners ; conserver anciennes factories de preuve et priorité du classificateur |
| Même package, `V3ConsumptionDiscoveryComposition.java` | Assembler DS contrôle/sourceRead explicites ; accepter seulement probes connus mode dev |
| Même package, `V3BootstrapGuard.java` | Entrée validation active fermée et catalogue+probes exacts ; règles I1/inert préservées |
| `J(COMPOSITION)/runtime/command/v3/V3PotCommandComposition.java` | Surcharge deux pools/owners, construire le même participant/classificateur sur Business |
| `app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaCommandConsumptionWorkerApplication.java` | Import explicite I5 et condition Legacy étendue |
| `app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java` | Même frontière d’import/isolation, pas HTTP métier nouveau |
| `app/orchestrator-poll-consumption/pom.xml` | Dépendance directe engine-consumption ; pas nouvelle version/lib/module |
| `app/runtime-command-consumption-worker/pom.xml` | Dépendance directe READ pour banc/migrations ; conserver Surefire/Boot defaults, pas exclusion de tests |
| `T(engine-consumption)/engine/consumption/v3/WorkerCapacityTest.java` | Accesseur/hook RO sans droits de release externe, borne synchronisée sous concurrence |
| Même package, `ConsumptionFailureClassificationTest.java` | Non-régression sorties brutes/observer Error/ticket après séparation des owners |
| `T(PRIMARY)/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresFixture.java` | Fabrique/test helpers deux pools, pas changement des oracles historiques |
| Même package, `DiscoveryPostgresFixture.java` | Réemploi sources/probes techniques et snapshots, pas inversion verrous I4 |
| `T(COMMAND)/runtime/command/v3/ConsumptionLifecycleCompositionPostgresTest.java` | Conserver harnais I3 distinct, ajouter garde des nouvelles surcharges/ressources actives |
| Même package, `DiscoveryCompositionPostgresTest.java` | Raccord actif aux owners/DS et refus des producteurs absents, factory inactive conservée |
| Même package, `V3PotCommandCompositionPostgresTest.java` | Vrai participant/classificateur sur Business et contrôle distinct, mêmes invariants |
| `T(READ)/infra/persistence/read/jdbc/v3/consumption/ReadDiscoveryPrivilegesPostgresTest.java` | i5_read_pools et droits dev explicitement distincts |
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/ConsumptionPilotIsolationTest.java` | Ancien profil toujours inerte, nouveau profil seul actif, combinaisons refusées |
| `T(architecture-tests)/architecture/v3/V3ConsumptionEngineArchitectureTest.java` | Nouveaux port/adapter/raccords, interdits ownership SQL dans POLL et ticket extérieur, exemples négatifs imbriqués précis |
| Même package, `V3ConsumptionFoundationArchitectureTest.java` | Isolation profils/classes actives, métadonnées probes et DS ; pas whitelist globale V3 |
| `docs/v3/L2_Certification_Coverage_Manifest.md`, `L2_Design_and_Verification_Plan.md`, `V3_Implementation_Master_Plan.md` | Après GO d’implémentation seulement : contribution I5, liens/statuts et justification des gates ; aucune allocation/report supprimé |

### REUSE UNCHANGED — invariants/protocoles

`J(engine-consumption)/engine/consumption/v3/{ConsumptionParticipant,SlotCompletion,LifecycleSettings,ClaimedSlot,PartitionLease,RecoveryObservation}.java` ; ports `{ConsumptionSlotStore,PartitionOwnershipStore,DiscoveryAdmissionStore,BurnableReferences,NonBurnableReferences,AttemptFailureClassifier}.java` du même package `port/` ; `J(port-transaction)/port/transaction/v3/{AttemptTransactions,RecoveryReadContext}.java` ; `J(infra-tx-spring)/infra/tx/spring/v3/SpringAttemptTransactions.java`.

`J(PRIMARY)/infra/persistence/primary/jpa/v3/consumption/{JdbcConsumptionSlotStore,JdbcPartitionOwnershipStore,JdbcDiscoveryAdmissionStore,JdbcCommandDiscoveryReferences,JdbcEventDiscoveryReferences}.java` ; `J(engine-write-pot)/engine/write/pot/v3/ExecutePotCommand.java` et ses ports/writers `JdbcPotCommandStore`, `JdbcPotCommandObservation`, `JdbcPotHistoryAdapter`, `JdbcPotRevisionAdapter` sous `J(PRIMARY)/infra/persistence/primary/jpa/v3/` ; `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionCatalogConfiguration.java` ; `J(POLL)/orchestrator/poll/consumption/wait/{ConsumptionWaiter,ConditionConsumptionWaiter}.java`.

Migrations existantes `R(PRIMARY)/db/v3-pot/migration/{V1__pot_components,V3__point_history_indexes,V4__write_effect_indexes}.sql`, `R(PRIMARY)/db/v3-pot-execution/migration/V2__command_execution.sql`, `R(PRIMARY)/db/v3-consumption-write/migration/V5__consumption_foundation.sql`, `R(READ)/db/v3-consumption-read/migration/V1__consumption_foundation.sql` ; scripts `scripts/v3/{bootstrap-l2-routing,grant-l2-lifecycle,grant-l2-discovery,reactivate-l2-slot}.sql`. Profils `R(COMMAND)/application-v3-consumption-pilot.properties`, `R(MONOLITH)/application-v3-consumption-pilot.properties` inchangés.

Manifestes/rapports de preuves I2/I3/post-audit/I4 restent historiques et inchangés ; le collecteur I5 en dérive un sur-ensemble frais. Les archives futures `docs/testing/evidence/L2-I5/` seront générées avec inventaire exact/empreintes au gel, pas des fichiers de code à prédéterminer ; aucune archive produite pour ce mandat.

### DEFER — aucun changement I5 implicite

domain-pot/identity, engine/participants Identity réels, projections/HTTP/Supras, tous les anciens locators et orchestrators/pollers hors voie active, autres runtimes, scripts Bruno/k6/Keycloak, Docker de distribution, migrations V2/historiques. `port-transaction`, `infra-tx-spring` et SQL de protocole restent hors delta attendu : si une preuve démontre un défaut, le traiter comme finding et réviser ce scope avant toute correction.

## 26. Étapes futures ordonnées, entrées et sorties

| Étape | Entrée | Travail borné | Sortie vérifiable avant étape suivante |
|---|---|---|---|
| I5-0 | Audit indépendant de ce plan, GO humain d’implémentation **distinct** | Reconnaissance Git fraîche/delta, relire normes/policy et inventaire, figer manifeste et paramètres/scopes | Branche/HEAD réels, réserve R-AUD-I3-03 ouverte, manifest NOT RUN, aucun choix transactionnel implicite |
| I5-1 | I5-0 et contrats acquis | Port/navigation sans filtre et tests n01…n08 ; API e inchangée, instrumenter précisément | Pages/UUID/U/préfixe vrai sous PG, aucun nouveau DDL ni changement acquire SQL |
| I5-2 | Navigation correcte | Séparer explicitement owners/stores Business/Control par surcharge ; factories/guards ; r08 | I2/I3/I4 et corrections fraîches passent avec même final owner et deux unités techniques non imbriquées |
| I5-3 | I5-2, quatre pools et inventaire | Superviseur/deux boucles/dispatch/capacité unique, unitaires puis w01…w08 | Équité M_W1/C/p, rejects/cancel/partiel prouvés, aucun ticket extérieur |
| I5-4 | Ressources exhaustives identifiées | Renewals/maintenance indépendants, r01…r07/w09…w11 ; calcul des marges avant campagne | **R-AUD-I3-03 fermable seulement sur preuves runtime WRITE/READ**, pas sur taille Hikari |
| I5-5 | Capacité/résolution prouvées | Drain/restart/crash/takeover w12…w14/mono01 ; interleavings réels | Ordre sûr, aucun callback de stop mensonger, claims indépendants, anciens états conservés |
| I5-6 | Runtime robuste | Dev bootstrap/tools/runbook, Command réel/probes w15, métriques/m01/m02 et coûts w16/n07 | Démonstration reproductible, droits effectifs, aucun endpoint métier nouveau, limites explicites |
| I5-7 | Toutes méthodes et variantes obligatoires | Gel unique, C/R/M/A/F, collecteur/inventaire/revue SQL/archives | Dossier I5 borné complet sans méthode requise absente/skip, rapports réels/techniques distincts |
| I5-8 | Livraison code+preuves sous GO propre | Audit indépendant contradictoire ; corriger findings et rejouer les preuves affectées | Décision de clôture I5 explicite, puis I6/I7/G3 selon programme ; aucune autoacceptation L2 |

Ces étapes ne sont pas autorisées par la publication du plan. Une difficulté locale n’autorise ni full systématique ni nouvelles classes/owners/protocoles ; résoudre dans la frontière et escalader un écart réel. Les sous-étapes conservent leurs diagnostics, sans attribuer au gel final les campagnes de révisions antérieures.

## 27. Alternatives rejetées et simplification

Outre A/B (§6) et SQL (§10) :

- Réutiliser intégralement ConsumptionPollingWorker/SequentialConsumptionOrchestrator : contrats Locator/budget V2 incompatibles ; conserver attente et enseignements de lifecycle suffit.
- Un pool unique simplement M_W+4 : taille déclarée sans maîtrise des emprunteurs, réserve I3 non fermée ; séparation physique et plafond des appelants requis.
- Un pool par C ou par p, un scheduler par slot, virtual thread illimité : multiplie connexions et files cachées, ne résout pas M_W ni la résolution physique.
- Pool renouvellement supplémentaire par type dès le départ : pourrait réserver plus directement, mais quatre pools avec plafonds **prouvés** suffisent ; pas six/huit pools tant que r01/r02 établissent la réserve et r03 l’indépendance. Si ces preuves échouent par emprunteur non bornable, revoir explicitement ce choix, pas maquiller le test.
- Exécuter les acquisitions dans le pool métier : retarde sélection et lie contrôle à saturation ; elles restent synchrones dans Consumption, courtes et sur contrôle.
- File non bornée/CallerRuns/discard : autorise attente de claims et/ou bloque la boucle/masque rejets ; file zéro+AbortPolicy donne une issue explicite.
- Semaphore/Future parallèle aux tickets Engine : crée une seconde autorité de capacité, course de libération ; refusée. Une envelope protège uniquement start contre annulation.
- Nouveau manager de recovery, reçus SQL, état UNKNOWN/DONE persistant, liste durable de clés sautées : I2/I3/les sources suffisent ; aucun invariant nouveau ne justifie ces états.
- Partager une transaction Spring entre threads, REQUIRES_NEW de contrôle à l’intérieur de la finale, DataSource router par phase : viole les guards I2 ; unités explicites successives et final store Business conservés.
- Activer les neuf Consumptions avec no-op/fixture sous leurs UUID métier : fausse capacité livrée et risque de suppression de slots sans transformation réelle ; refus fail-closed.
- Ajouter une API d’injection HTTP ou modifier Bruno pour masquer l’absence L5 : hors I5 ; injection interne dev clairement identifiée.

## 28. Risques résiduels et décisions réellement ouvertes

| Risque | Traitement / limite à conserver |
|---|---|
| R-AUD-I3-03 | **Ouverte, NOT RUN** ; proof r01…r08 et calcul des marges bloquent la certification I5 ; taille Hikari et test manuel I3 insuffisants |
| Blocage de toute relation/panne DB durable/GC long | Pas de garantie perpétuelle de renewal ; expirations/fencing et dégradation diagnostiquées ; aucun optimisme sur UNKNOWN |
| Borrowers oubliés/auto-config historique | Graphe fermé, qualifiers et interdits ArchUnit ; tests actifs dans deux racines et inventory de sessions réel, pas simple grep |
| Réservation incertaine conservée longtemps | Capacité utile réduite assumée, pending/alertes ; pas de release par délai ; drain peut devenir crash contrôlé |
| Lease expire après scan d’acquisition | e couvre scan et non gains, revisite au tour suivant ; test n05 protège cette nuance ; pas droit final fondé sur partition |
| Retrait local de lease sur SKIP/ACK perdu | Peut perdre une opportunité jusqu’à expiration normale sans perte de travail ; pas métrique takeover fabriquée, claims renouvelés indépendamment ; test w09/r04 |
| Rejets après acquisition | Claims inutilisés attendent expiration, coût de latence à mesurer ; quantum/exécuteur pré-démarré réduisent fréquence ; pas second protocole de release SQL |
| Équité conditionnelle | Rotation durablement répétée sous fenêtres libres ; aucun succès assuré si adversaire reprend chaque lock avant chaque visite, ni SLA universel |
| Q nombreuses et COUNT/scan MVCC | Index existants et pages bornées, coût réellement mesuré ; réduire B/cadence si nécessaire, pas promettre O(1)/HOT ni ajouter index sans preuve |
| Catalogue technique dev | IDs propres, flags explicites, aucun remplacement métier ; guards/grants dev séparés, vérification2/9 explicite ; ne pas diffuser ce mode comme L8 |
| Metrics sous ACK perdu | Séries confirmées peuvent sous-compter les transitions physiques ; SQL extérieur arbitre ; pas de tokens dans logs/labels |
| Couverture métier différée | Aucun PASS global CON/EVT/OPS/PERF/REC sur fixtures ; même un full Maven vert ne livre pas L3–L8 |

Aucun arbitrage humain de **conception ordinaire** n’est identifié comme bloquant pour rédiger ce plan : choix de pools/quantum/ports/profil résolus ci-dessus et soumis à audit. Restent bloquants pour **implémenter** l’audit contradictoire puis le GO explicite demandé par le mandat, et pour **certifier** les preuves NOT RUN, mesures/config effectives et résolution de findings. Une contradiction normative nouvelle devra revenir à son autorité ; ce risque n’est pas une question de préférence à renvoyer préventivement au responsable.

## 29. Reports aux lots suivants

| Lot | Report explicite, aucun PASS produit par I5 |
|---|---|
| L3 | Autorité EUI/Identity réelle, Registration/Link/Unlink et leurs producteurs/participants ; remplacer fixture PotActorAuthority par fournisseur réel avec ses preuves |
| L4 | Sept matérialisations métier READ hors CurrentInvitation selon découpage directeur, versions/immutabilité/Balance/Visibility/Binding/Results ; probes ne sont pas des projections |
| L5 | Supras/HTTP/AuthN/consentement/Invitation complète/CurrentInvitation et Bruno métier ; aucun parcours externe déduit de CreatePot interne |
| L6 | Reconstruction exclusive et READY, charge intégrée1/10/50s, volumes10k/1M/10M et rattrapage10k/5min, SLO C-03/Grand Pot ; les mesures I5 sont seulement locales |
| L7 | Convergence/suppression générale V2, rationalisation noms/modules/owners historiques ; I5 neutralise seulement ceux incompatibles sur son profil |
| L8 | Packaging final/Docker/topologies/guides de distribution et parcours utilisateur installés ; jar dev et outils internes n’en tiennent pas lieu |
| I6/I7/G3 L2 | Consolidation complète, audit/corrections et décision d’acceptation globale, allocations et reports conservés ; aucune promotion automatique par I5 |

Les 56 reports du manifeste historique restent nominativement alloués ; leur regroupement ici n’en retire ni variante ni stimulus. INV-01/ID-02/PRJ/READ/SEC/ADM/S-01…03 métier conservent leur lot, NOT RUN hors contributions effectivement réalisées plus tard. Une fonctionnalité absente ne reçoit jamais PASS.

## 30. Critères de clôture I5 et vérification documentaire présente

**Clôture future de l’implémentation** exige : deux boucles effectives simultanées, équité C/p/slots prouvée avec M_W1 et capacités distinctes, Engine unique owner des tickets/claims/cession, aucun franchissement fictif e/U, quatre pools/transactions réellement raccordés et réserve R-AUD-I3-03 prouvée sur les deux autorités, renewals claim après perte de partition, résolution UNKNOWN physique conservative, drain/restart/crash/takeover sans perte/double effet/purge/Q réactivée, Command réel et probes explicitement techniques, activation fail-closed dans les deux racines, métriques vérifiées et coûts documentés. Toutes méthodes/variantes/manifeste/héritages frais et gates C/R/M/A/F au même gel, archives/empreintes/commandes et rapports bornés, audit indépendant et corrections vérifiées. Aucun test absent/skip obligatoire/fixture sur métier absent ne permet une clôture globale ; L2 reste gouverné par son programme et sa décision humaine.

**Livraison documentaire présente** : uniquement ce fichier, revue des normes R1–R10 et des contrats code réels ; références/chemins, signatures existantes vs proposées, disponibilité des neuf, ownership/limites des sources et fixtures, absence de duplication des tickets/protocoles, cohérence SQL/index/MVCC et tables existantes, matrices de risques/oracles/gates, reports et absence de code I5 contrôlés. Les commandes réellement exécutées se limitent aux lectures/recherches Git/rg/cat/sed, analyses statiques Python (liens/chemins/POM/manifestes/signatures/inventaire du document), `git diff --check` et opérations Git de publication autorisées. Aucune commande Maven, PostgreSQL ou Docker ; DB NONE, slices présentes NONE, aucun crossing applicatif, gate global non requis pour ce mandat, full reactor non exécuté car documentaire. Les gates futurs de ce document ne sont pas des commandes exécutées.

Publication : seul `docs/v3/L2_I5_Implementation_Plan.md` indexé/committé, distant reconfirmé avant push normal ; reçu final fournit SHA initial/final, commit/branche, divergence distante et propreté du worktree, sans réécrire les autres rapports. Si un contrôle révèle un delta concurrent, analyser avant publication. Contrôles documentaires frais :30 sections numérotées,27 liens relatifs résolus, chemins Java MODIFY (y compris « même package ») et expansions REUSE vérifiés, tous chemins CREATE constatés absents, méthodes publiques Engine existantes et absence des extensions proposées vérifiées ; manifestes JSON et arêtes POM parsés. `python3 -B docs/v3/check_l2_manifest.py` retourne253 identifiants normatifs,380 lignes de preuve,324 obligations L2,56 reports,33 oracles,0 orphelin,826 liens et63 ancres. Les tableaux couvrent séparément les12 variantes C03 et11 clauses R10 ; code fences équilibrés et whitespace contrôlé. Aucun de ces contrôles n’est une exécution applicative. Les contrôles sémantiques ici sont ceux de l’auteur, **pas** l’audit indépendant contradictoire à venir.

**Point d’arrêt : I5 DESIGN UNDER REVIEW — IMPLEMENTATION NOT AUTHORIZED.**
