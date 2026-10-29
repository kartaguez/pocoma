# Pocoma V3 — L2 / I5 — Plan d’implémentation des Workers

> Document historique de conception et d’amendement. Le GO humain ultérieur a autorisé l’implémentation ; son état et ses preuves sont décrits dans le [rapport d’exécution I5](L2_I5_Execution_Report.md). Les statuts NOT RUN et les interdictions ci-dessous décrivent le mandat documentaire initial, pas les résultats du runtime courant.

**28 octobre 2026 — I5 AMENDED DESIGN — READY FOR INDEPENDENT RE-AUDIT — IMPLEMENTATION NOT AUTHORIZED.**

Ce mandat livre uniquement ce plan. Aucun Java, SQL, test, POM, profil ou réglage productif I5 n’est créé/modifié ; aucune campagne Maven/PostgreSQL n’est exécutée. Toutes les méthodes et commandes futures ci-dessous sont **NOT RUN**. Une revue documentaire n’est ni une preuve PostgreSQL, ni une certification globale, ni un GO de code.

## 1. Statut, provenance et sécurité Git

Dépôt `kartaguez/pocoma`, remote `https://github.com/kartaguez/pocoma.git`, destination exclusive `feat/v3-architecture`. **Baseline de cet amendement réellement constatée : `2fb6fca70c635994216494f1ab939825bf0b6373`**, HEAD local et distant identiques, worktree propre ; aucun delta concurrent à intégrer. Ce commit avait ajouté uniquement ce plan depuis la baseline de conception `e28645d3700e3dfdb198c5260333d382da58a7b5` : le code I2/I3/I4 inspecté est inchangé. Le SHA de l’amendement publié est celui de `git log -1 --format=%H -- docs/v3/L2_I5_Implementation_Plan.md`, communiqué au reçu final sans auto-référence circulaire.

**Audit indépendant : CONDITIONAL PASS**, architecture générale acceptée et cinq réserves communiquées par le mandat humain courant. Aucun rapport d’audit supplémentaire n’est fabriqué. Le présent amendement traite leurs contrats et preuves futures ; il ne déclare ni leur réussite applicative, ni fermeture de R-AUD-I3-03, ni GO de code.

| Réserve | Sections de règle corrigées | Preuves futures additionnelles, toutes NOT RUN |
|---|---|---|
| R-I5-01 | §§7,9,12,15–17,22–23,25–26,28,30 | w17 : incarnation, retrait monotone, retour renewal tardif et settlePending |
| R-I5-02 | §§13–16,20,22–23,25–28,30 | A/B/C séparées, r01…r09 ; lot claims singleton et timeout physique incertain |
| R-I5-03 | §§7,9,12–13,16–17,22–23,25–26,28,30 | w18 : barrières drain/submit/accept/start, cleanup et deadline UNKNOWN |
| R-I5-04 | §§9–13,20,22–23,25–26,28,30 | w19 : acquisition synchrone lente, attente des autres C et reprise de rotation |
| R-I5-05 | §§4,18–19,21–23,25–26,28,30 | c05 : matrice négative complète, avant threads Worker et mutation de coordination |

Le checkout principal est sur `feat/runtime-monolith`, `90a3e9bc1a1fdd325d0c36e543824239cd85a431`, avec modifications suivies Keycloak/docs/Bruno et fichiers non suivis préexistants, dont `docs/v3/`, études et test PostgreSQL. Il reste intégralement préservé. `git worktree list --porcelain` a été inspecté : la branche cible est déjà dans `/private/tmp/pocoma-v3-postaud`, propre au départ, au HEAD distant. Ce worktree adapté est réutilisé ; aucun autre checkout n’est déplacé, archivé, nettoyé ou réinitialisé. Aucun stash, reset destructif, clean ou force push.

Lors de la préparation initiale, l’accès sandboxé avait échoué en DNS ; cela reste historique. Pour cet amendement, `git ls-remote` autorisé a confirmé directement la tête distante ci-dessus. Avant publication : relire distant, statut et diff ; si la tête a bougé, examiner le delta documentaire **et** applicatif avant de réconcilier le seul plan et refaire la revue. Push normal uniquement. Le présent mandat ne modifie ni le plan directeur ni les manifestes historiques : le seul fichier publiable est celui-ci.

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

Déclaré au catalogue ≠ activé. Refuser toute entrée enabled sans source **et** participant compatibles, sans fallback vers un no-op, une fixture ou une classe historique. Une désactivation n’efface ni routage ni slots/K. **R-I5-05 :** la validation lie le tuple complet `(rôle métier/technique, UUID, famille, autorité, routage, source, participant, destination d’effet)`, pas seulement un UUID connu. Les tuples métier et techniques sont disjoints et non substituables ; conditions et refus détaillés §18, oracle c05 §22.

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

Le superviseur possède les threads/exécuteurs, la barrière locale de lifecycle et les références d’envelopes ; les Engines conservent exclusivement claims, unités physiques, tickets et décisions de recovery. Les deux boucles ne joignent jamais une Future métier. `V3RenewalService` appelle seulement les Engines, sans SQL ni reprise métier.

**R-I5-01/R-I5-03 — un seul moniteur local**, privé au superviseur, protège lifecycle, inscription/retrait des envelopes, permission de début et marqueurs des appels renewal en vol. Il ne protège aucun état PostgreSQL. Toutes les sections critiques sont finies : lecture/modification de champs/listes locales seulement ; **aucun appel Engine, WorkerCapacity, JDBC, executor.execute/shutdown, callback, métrique ou attente/join sous ce moniteur**. Lire `retainsCapacity` hors moniteur ; false est monotone et peut servir au retrait sous moniteur. Aucun ordre imbriqué WorkerCapacity→moniteur ou inverse.

Une envelope référence l’Engine, l’**objet AcquiredClaim exact** et son claim/token immuable. Cette identité d’objet sert d’incarnation locale ; pas nouvel UUID ni type durable. Ses phases locales restent les quatre déjà prévues : non démarrée, démarrée, abandonnée avant début, sortie locale. Une inscription est unique et un retrait est irréversible pour cette incarnation. Le même `(C,consumableId)` acquis plus tard possède un autre handle/token : un ancien résultat ne le concerne jamais.

Le registre de suivi est une seule collection bornée d’envelopes, non une autorité de capacité. Sa vue active pour renewal exclut toute envelope abandonnée/retirée ou dont le ticket est observé rendu. Une envelope sortie mais dont Engine conserve le ticket reste suivie, sans droit de réexécution, jusqu’à règlement I3. **Correction de la borne initiale trop forte** : les tickets restent ≤M_W ; les descripteurs peuvent transitoirement atteindre ≤2×M_W, car une restitution Engine peut précéder la sortie du wrapper d’un thread métier (≤M_W) pendant que d’autres handles sous tickets (≤M_W) sont inscrits. Supprimer les descripteurs sortis et réglés à chaque fin/settle et avant inscription ; aucune rétention historique. Les snapshots de renewals déjà partis gardent au plus un contexte par voie (deux claims et un leases par autorité), pas un second registre non borné.

**Points de linéarisation des handles** : inscription = insertion unique sous le moniteur après acquisition confirmée ; début = transition non démarrée→démarrée par la runnable sous ce même moniteur et seulement si RUNNING ; retrait actif = marquage abandonnée avant début, ou retrait d’une envelope sortie dont le ticket est observé rendu. Les détails dispatch/drain sont définis une seule fois au §12. Retrait n’est jamais une restitution de ticket. Sortie locale du wrapper, résolution physique de la dernière unité et restitution Engine sont trois faits distincts ; ni un seul ni une réponse SQL de renewal ne remplace les deux autres.

Les leases conservent un registre court séparé par couple ; installation d’un retour seulement si même incarnation/token toujours enregistré. Retirer un lease n’enlève aucun claim. Listes de configuration, tours RequestWalk/SlotWalk et échéances de réveil sont volatils ; pas de cache de K/AVAILABLE/Q/count/retry/résultat. Aucun contexte Spring, Prepared ou Connection ne passe entre threads : seul le handle confirmé sortant de toute transaction est transmis, et execute réalise préparation/finalisation sur son propre thread.

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

Sur retour certain, vérifier `0<=examinedCount<=keys.size`, handles de cette C uniquement. Si e>0, after devient exactement `keys[e-1]`, sinon aucun progrès. La boucle garde la responsabilité de **tous** les handles rendus jusqu’à leur inscription ou leur abandon réglé suivant §12 (la liste bornée Acquisition.claims reste détenue jusqu’au transfert de chacun ; une exception de préparation d’envelope ne perd pas le suffixe : finally le prend en charge/abandonne hors moniteur sans doubler les entrées déjà transférées), y compris si drain intervient entre retour SQL et inscription. Aucun handle confirmé ne disparaît dans un intervalle non suivi. Les places non gagnées sont déjà restituées par Engine. Une occasion C peut être consommée sans gain ; la rotation et le curseur de clés sont deux progressions distinctes. Un lease perdu arrête les nouveaux appels mais n’invalide pas un handle déjà acquis.

**R-I5-04 : une seule opportunité Consumption est en cours.** Elle commence lorsque la boucle choisit C/p et se termine après navigation, acquisition synchrone, prise en charge de tous les handles et retour/échec des soumissions locales ; pas à la fin métier. Un appel lent sur ce chemin retarde les autres C malgré les pools séparés. Bornes de configuration et mesures ne garantissent pas le retour d’un appel bloqué durablement. Après retour certain ou exceptionnel, nextConsumption conserve la suivante due, sans remettre la liste au début ; après saturation avant toute occasion il reste inchangé. Mesures et oracle w19 aux §§20/22 ; aucun nouvel exécuteur d’acquisition.
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

M_W=1, C1/C2 constamment éligibles, quantum1 : après résolution/settle d’une réservation C1, C2 est la prochaine occasion d’acquérir, puis C1. Si l’occasion C2 rencontre une clé verrouillée/inéligible, l’équité n’invente pas un gain ; elle poursuit son tour de clés aux prochaines occasions. M_C sont des maxima, jamais des réservations permanentes ; pas de quota global entre Workers. Oracle OR-EQ-CAP sur occasions **et** tentatives réellement démarrées, avec libérations et fenêtres libres imposées. Égalité des débits, ordre FIFO et bornes de latence universelles ne sont pas promis. **R-I5-04 :** un quantum1 borne le travail proposé, pas la durée JDBC d’une opportunité. Une acquisition synchrone lente immobilise cette boucle et retarde les suivantes ; le tour reprend à nextConsumption après son retour, certain ou exceptionnel. Aucune progression temporelle des autres C n’est promise si cet appel ne revient jamais. Après disparition du blocage, capacité et fenêtres libres contrôlées, w19 doit établir la reprise des occasions/gains sans réinitialisation du pointeur. Ajouter un exécuteur d’acquisition pour éviter cette limite serait une nouvelle conception non autorisée.

## 12. Réservation M_C/M_W et dispatch : enchaînement exact

L’acquisition reste **synchrone après réservation** sur la boucle Consumption et le pool Control. Elle peut donc retarder les autres C (§§9/11), indépendamment des threads Discovery/renewals. Aucun second ticket/sémaphore M_C/M_W. Le Worker utilise exclusivement `abandon`, `execute`, `settlePending` ; il ne reconstruit aucun handle exécutable depuis des tokens SQL.

**R-I5-03 — protocole linéarisé par le moniteur §7**. Les noms L1…L6 ci-dessous identifient des points locaux de test, pas de nouveaux objets/classes :

1. **L1 — appel engagé.** Sous moniteur, vérifier RUNNING et compter l’appel acquire en cours ; sortir puis appeler Engine sans lock local. Cette responsabilité en vol n’est décrémentée qu’après prise en charge de tous les handles retournés, ou sortie exceptionnelle sans handles dont pending reste Engine. Le drain attend ce compteur, pas seulement le retour JDBC.
2. **L2 — inscription.** Après retour confirmé, inscrire chaque envelope exactement une fois sous moniteur. Si RUNNING, elle est non démarrée ; si DRAINING, elle est immédiatement marquée abandonnée avec une responsabilité de cleanup unique. Aucun passage par un registre invisible au superviseur ; taille bornée §7. Ne pas soumettre un handle abandonné.
3. **L3 — admission locale à la soumission.** Sous moniteur, vérifier même envelope/incarnation encore non démarrée et RUNNING ; compter la soumission en cours, puis sortir. L3 **n’est pas** une permission de commencer après drain. `executor.execute(envelope)` se fait hors moniteur, sans retry. Une admission peut donc être suivie d’une acceptation ou d’un rejet pendant drain ; L4 décidera toujours du droit de commencer. Décrémenter le compteur de soumissions seulement après traitement de son retour/rejet.
4. **L4 — début effectif de l’envelope.** À l’entrée de la runnable, sous moniteur : vérifier inscription exacte, non retrait, phase non démarrée et RUNNING, puis transition atomique vers démarrée. C’est le début effectif local retenu, avant tout appel métier. Sortir du moniteur et appeler `engine.execute(handle)` **une seule fois**, sans nouvelle fenêtre d’annulation avant début. Si L4 précède L5, la tentative est déjà engagée même si son premier SQL survient après L5 ; elle poursuit normalement I2/I3. Si L5 précède L4, aucun appel execute/prepare/finalize n’est permis. Un simple test RUNNING en dehors de cette transition serait insuffisant.
5. **L5 — barrière de drain.** Sous le même moniteur, passer RUNNING→DRAINING et marquer toutes les envelopes encore non démarrées abandonnées ; les retirer irréversiblement de la vue active renewal et attribuer une unique responsabilité de cleanup. Une envelope admise à L3 ou acceptée par l’exécuteur mais n’ayant pas franchi L4 est incluse. Les inscriptions L2 ultérieures voient DRAINING et suivent le même abandon. Sortir avant réveils, `shutdown()` et toute opération Engine.
6. **L6 — cleanup et sortie.** Le seul gagnant du marquage non démarrée→abandonnée appelle `engine.abandon(handle)` hors moniteur, puis constate la restitution via Engine et termine sa responsabilité locale. Drain, refus AbortPolicy et annulation pré-début partagent cette même décision : les perdants n’appellent ni abandon ni execute. Une runnable acceptée tardivement constate le marquage et retourne sans métier. Après L4, abandon est interdit ; le wrapper exécute I2/I3 puis signale sa sortie dans finally sous moniteur, indépendamment de la restitution du ticket. Une faute brute de cleanup conserve son suivi, déclenche diagnostic/drain et n’invente aucune libération.

Une annulation avant début suit L5/L6 pour l’envelope ciblée sans modifier lifecycle ; une annulation après L4 n’est qu’une demande coopérative. Le flag d’usage unique d’AcquiredClaim dans Engine reste le dernier garde, mais **ne remplace pas** la barrière commune Worker. Aucun nouveau type de ticket ou état durable.

| Événement | Traitement local et durable / capacité |
|---|---|
| Exécuteur connu saturé/arrêté, aucune place avant acquisition | Aucun nouvel acquire ; conserver prochain bénéficiaire ; pas de progression fictive |
| Réservation puis aucun claim / acquisition partielle | Engine rend les places non gagnées ; e couvre le préfixe effectivement soumis ; L2 prend en charge tous les gagnants |
| Collision / ownership refusé | CAS/gains I3 seulement ; ownership refusé e=0 ; ne pas refaire une tentative sous un handle |
| Soumission acceptée | Pas encore un début ; registre actif et ticket conservés ; L4 reste obligatoire |
| Refus AbortPolicy / drain / annulation avant début | Un seul L6 abandon, aucun callback métier ; ticket rendu par Engine, claim SQL laissé expirer sans cession/count |
| Annulation/interruption après L4 | Aucun abandon ni restitution Worker ; execute règle la dernière unité ou garde pending |
| COMMITTED / ROLLED_BACK certains | Engine restitue selon I3, cession éventuelle comprise ; wrapper peut n’avoir pas encore signalé sa sortie locale (§7) |
| Cession UNKNOWN / commit UNKNOWN, même R9 COMPLETED | Garder pending/ticket selon Engine ; pas de cession répétée ni réexécution ; settlePending seul chemin de règlement ultérieur |
| Exception brute/Error du port owner | Pas de résolution inventée ; pending Engine, diagnostic/drain ; classifier Error/null après rollback certain conserve la correction post-audit de restitution |
| Retour local avec transaction toujours active / Future terminée | Aucun fait de résolution physique ; ticket gardé selon I3 et locks observés ; stop réussi interdit |
| Crash / takeover | Réservations locales meurent avec le processus, claims SQL suivent I3 ; pas de quota inter-Workers ni reprise anticipée |

**R-I5-01 — retrait et réponses concurrentes.** Après sortie locale, lire retainsCapacity hors moniteur. Si false, retirer sous moniteur seulement la même envelope/incarnation encore sortie ; si true, la laisser dans le suivi actif pending, sans permission de réexécution. Maintenance applique le même retrait après `settlePending`, lequel reste seul juge de ses tickets. Abandon retire la capacité locale de renewal dès le marquage pré-début, mais sa responsabilité de cleanup reste comptée jusqu’au retour de l’Engine. Aucun `Future.cancel`, délai, réponse renewal ou retrait de registre ne rend directement un ticket.

`settlePending()` garde son contrat réel : ne traite que localDone Engine ; PRESENT_UNLOCKED peut résoudre une protection sans inférer rollback métier ; ABSENT après businessStarted exige R9 COMPLETED ; LOCKED/UNAVAILABLE/INCONSISTENT conservent. Son retour ne rend pas à lui seul vraie la sortie du wrapper. Les règles de réponse tardive renewal sont exclusivement §15 ; elles ne modifient aucun de ces verdicts.

## 13. Exécuteurs, files et délais locaux

Exécuteur métier proposé : `ThreadPoolExecutor(core=M_W,max=M_W,keepAlive=0,SynchronousQueue,threadFactory nommée,AbortPolicy)`, threads plateforme non daemon, pré-démarrage des core threads. **File métier de taille zéro** ; pas CallerRuns (bloquerait Consumption), pas discard/discardOldest, pas retry de soumission en boucle. getActiveCount est un indice d’évitement de rejet, jamais une réservation. Le vrai accept/reject et les handles déterminent le nettoyage. Les tests imposent une race malgré le contrôle indicatif, y compris une tâche acceptée dont le thread n’est pas encore entré.

Deux threads de boucle non daemon, attentes Condition réutilisées, aucun @Scheduled par C/p. Renewals : par autorité deux voies claims et une voie leases, chacune monothread, indépendantes des deux boucles et de l’exécuteur métier. Maintenance settle : un thread unique Worker. Le superviseur ne lance pas un quatrième poller général ; son thread maintenance vérifie aussi santé/drain. Au pilote mixte : 2 boucles +6 renewal +1 maintenance +M_W métier ; aucune création de thread par slot. Toutes ces voies utilisent des attentes monotones/réveillables. Un seul lot en vol par voie, y compris après retour local UNKNOWN (§15), pas accumulation de tâches périodiques : échéance manquée coalescée, prochain passage borné, pas de rafale de rattrapage. **R-I5-02 : lot claims singleton au premier runtime**, via renewClaims(List.of(claim)) existant ; B32 reste la borne du port, pas l’obligation de grouper S1 et S2. Ainsi un lot bloqué ne retient pas à lui seul tous les claims locaux et l’autre voie peut servir les autres incarnations. Lots leases ≤B32 inchangés. Aucun nouvel exécuteur/pool pour les acquisitions (R-I5-04). Le shutdown hors moniteur suit exactement L5/L6 (§12), jamais une annulation fondée sur Future comme preuve physique (R-I5-03).

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

Tailles conservatrices initiales par autorité : **Business=M_W+2**, **Control=M_W+6** (10 et6 respectivement Control/Business pour M_W4). Ces quatre tailles sont inchangées par l’amendement et restent des hypothèses NOT RUN. READ Business garde le même majorant pour simplicité, bien que la lecture source réservée soit WRITE ; aucun quota permanent par Consumption. Tous les emprunteurs non-renewal du contrôle sont bornés à M_W+3 : trois accès sont donc une réserve **candidate à prouver**, sous hypothèse d’exhaustivité A et disponibilité physique B ci-dessous ; ce calcul n’est pas une preuve acquise. Aucun thread de tentative ne peut emprunter simultanément Business et Control ; ni transaction imbriquée ni continuation après démarrage cession. Pool minimumIdle=maximumPoolSize au pilote pour éliminer une croissance paresseuse non mesurée ; vérifier le nombre réel de connexions établies avant RUNNING, et les délais de remplacement. Ce réglage ne garantit pas la disponibilité pendant panne réseau/serveur.

**R-I5-02 — trois preuves indépendantes, conjonctives et archivées séparément pour WRITE et READ :**

| Preuve | Responsabilité et invariant précis | Preuve future / règle d’échec |
|---|---|---|
| A — exhaustivité des emprunteurs | Composition : chaque DS/TM/EMF et chaque entrée d’emprunt sont attribués au tableau ; aucun appelant extérieur ne peut emprunter les réserves Worker. Inspecter Spring/auto-configs/JPA, superviseur, R9, cessions, maintenance, health et métriques, pas seulement les threads nommés | c03/r02/r08 + inventaire statique et appels réels par voie ; compteur d’emprunts de test et sessions extérieures. Un emprunteur non classé ou un chemin FactoryBean non résolu interdit la clôture A |
| B — disponibilité effective | Les voies renewal accèdent effectivement aux connexions et peuvent committer sous occupations Business/Control admissibles et stress physique explicitement distingués ; les opérations incertaines ne disparaissent pas du décompte des voies | r01/r02/r03/r06/r09, vrais pools/owners/PID. Hikari max/idle ou présence d’un thread seuls insuffisants ; attente de connexion causée par couplage invalide B |
| C — échéances complètes | Composition/renewal : budget préalable de bout en bout, mesuré depuis échéance de réveil jusqu’à commit et nettoyage, incluant emprunt, verrous, SQL, ordonnanceur et pauses JVM. Conditions/périmètre de panne explicites | r05/r07/r09, horloges DB et marges au commit ; pas somme de p95. Dépassement sous hypothèses admissibles = FAIL C ; absence d’observation/panne hors hypothèses = INCONCLUSIVE, jamais fermeture par A+B |

Actuator est un emprunteur potentiel : **désactiver le health JDBC automatique** (`management.health.db.enabled=false`) dans le profil actif et refuser tout autre health/probe qui exécuterait du SQL via un pool Worker. Le health superviseur/readiness lit des observations locales déjà collectées, sans test DB synchrone ; Hikari/Micrometer ne lisent que MXBeans/compteurs. Observateur SQL d’exploitation et outils dev ont leurs connexions externes identifiées. L’initialisation JPA/metadata a lieu uniquement en STARTING, avant la mesure des réserves et les threads Worker, sans tâche/repository/OSIV asynchrone ultérieure ; `spring.jpa.open-in-view=false` et absence effective de filtre/interceptor OSIV vérifiées. A doit vérifier ces absences effectives, y compris sous appels répétés health/prometheus ; les qualificatifs Spring ne les prouvent pas à eux seuls.

Les voies renewal ne se partagent pas un exécuteur monothread. Une voie claims bloquée en relation/transport n’empêche pas l’autre d’emprunter ; le lease dispose aussi de sa voie. SKIP LOCKED évite des attentes de **lignes**, pas toutes les attentes PostgreSQL. Si un DDL exclusif bloque toute la relation, aucun dispositif de pool ne peut assurer S2 ; timeout puis diagnostic, aucune garantie fictive. La campagne S1/S2 établit explicitement un blocage qui laisse S2 accessible.

WRITE Business : `JpaTransactionManager` avec EMF explicitement construit sur **writeBusiness**, nested=false ; `SpringAttemptTransactions(writeBusiness,tm,60)` garde JDBC/JPA, final slot et tous writers Pot sur cette même DS. Pas d’auto-scan des anciennes entités/repositories : EMF minimal explicite, pas de validation du schéma V2 sur V3. READ Business technique : DataSourceTransactionManager readBusiness nested=false, owner Spring existant. Control A : DataSourceTransactionManager controlA nested=false, owner Spring existant avec timeout technique3s ; mêmes guards et aucune publication métier. La pluralité de managers correspond à des **unités distinctes et successives**, pas à deux owners d’une tentative finale.

Extension Engine limitée : champ effectsTransactions/effectsSlots pour prepare/finalize/observe/Completion ; champ controlTransactions/controlSlots/ownership pour acquire/renew/cession/inspect/navigation. `technical()` utilise contrôle ; observe reste effets. Discovery I4 utilise controlA comme effects owner (ses « effets » sont slots/K), sourceReads writeBusiness pour événements ; Command burnable source/control sont identiques. L’inspection contrôle se ferme avant observe métier ; la cession contrôle commence après rollback métier confirmé. Aucune délégation `control.write(() -> effects.write(...))`, aucun routing datasource dynamique, aucune réécriture des SQL I2/I3/I4 ou du suffixe.

Configuration effective proposée : Hikari connectionTimeout1s, validationTimeout500ms (valeurs compatibles à valider au démarrage), JDBC connectTimeout2s/socketTimeout5s contrôle ; réglages par connexion/session, jamais mutation globale à chaud. `ownership.bound` garde lock500ms, statement2s, transaction_timeout3s techniques. Navigation read reçoit ces bornes explicitement ; une transaction read n’est pas bornée par ownership.bound gardé en écriture. Business conserve timeout Spring60s ; fixer aussi des limites PostgreSQL/JDBC métier cohérentes et vérifiées par profil (première expérience : statement60s, transaction65s, socket70s) ; une durée longue de test ajuste ces valeurs **avant** création des pools. Un timeout reste une issue à qualifier, jamais une preuve de rollback.

Dimensionnement PostgreSQL : nombre de Workers W fixé ; budget de connexions au moins somme des maxima réellement établis des quatre pools de chacun + admin/injecteur/observateurs + marge serveur déclarée. Hikari max borne les connexions louées par pool, pas tous les backends résiduels après coupure/éviction. Les coupures filaires doivent mesurer ces résidus ; transaction_timeout serveur et cadences bornent leur maintien sous serveur répondant. Archiver séparément connexions louées et backends résiduels : une voie renewal UNKNOWN ne lance aucun remplacement en série (§15), ce qui borne ses résidus sans assimiler Hikari active à activité serveur. Ne pas promettre un budget serveur à partir de 4 tailles sans cet essai ; dégrader/arrêter les nouvelles opérations en cas d’épuisement, conserver UNKNOWN. Aucun ACK local/attente temporelle ne rend un ticket physique par supposition.

**Méthode de qualification bloquante :** mesurer emprunt, dispatch, statements, locks, commit et pauses JVM par voie sur le runtime complet ; archives config Hikari réelle, managers, pg_stat_activity/PID, attente et échéances PostgreSQL. Pour chaque classe lease/claim et autorité, relever L nombre maximal de lots dus par cadence, r nombre de voies réellement utilisables, t unité maximale budgétée (incluant emprunt/lock/commit/cleanup), j retard d’ordonnancement/GC budgeté. Exiger `renewalInterval + ceil(L/r)*t + j + safety < durée utile minimale`, puis contrôler la marge **réellement observée en DB** avant chaque commit. Les lots claims sont singleton (§13) : L_claim est le nombre de handles à renouveler, pas le nombre de C non vides ; L_lease=sum_C ceil(p_C/B). Augmenter M_W/C/partitions impose de recalculer L et de refaire C. Une voie claims occupée par S1 conduit à r=1 **uniquement pour les autres handles non en vol** ; sa capacité/son lot ne sont jamais réattribués après simple timeout local. Une voie leases est r=1 séparée. Un lot physiquement UNKNOWN n’a plus de t fini démontré : appliquer §15, ne pas le cacher dans le calcul de C. Déduire t de timeouts totaux et mesures, pas de somme de p95 ni de `10<30`.

Exemple initial (hypothèse, **pas certificat**) : 3 C actives,12 leases, M_W4, B32 ; L_lease=sum_C ceil(p_C/B)=3 et L_claim=sum_C h_C≤4 avec sum(h_C)≤4 (claims singleton). Exemple de budget préalable t=5s (1s emprunt+3s unité+1s cleanup), j=1s, safety=2s : lease10+3×5+1+2=28s<30s ; claims avec une voie disponible10+4×5+1+2=33s<60s. Ce budget doit être mesuré, pas présumé ; si cela ne tient pas, réduire lots/concurrence contrôlée ou ajuster les durées expérimentales avant activation. Ne pas annoncer PASS parce que les valeurs pilot sont acceptées par LifecycleSettings. Les seuils sont figés avant campagne et confrontés aux maxima observés, pas ajustés après pour faire passer.

## 15. Renewals et maintenance indépendants

**R-I5-01 — sélection et retour de renewal.** Sous le moniteur §7, prendre seulement des références d’envelopes inscrites, non abandonnées/non retirées, et sans appel renewal déjà en vol pour la même incarnation. Exiger une lecture RO retainsCapacity=true hors moniteur pour proposer un renewal ; false ne donne qu’une occasion de retrait après sortie, jamais un nouveau lot. Revalider ensuite l’identité et la phase courante sous moniteur avant de marquer le lot en vol. Une restitution Engine concurrente peut rendre ce true ancien : son unique effet possible reste un renewal déjà lancé, jamais restauration d’un ticket ou d’un droit de début. Le snapshot est un contexte immuable d’appel, pas une seconde inscription. Chaque voie claims porte un singleton (§13), indépendamment de M_C/M_W disponibles et de la propriété de partition. La voie libre sert en rotation les autres C/handles non en vol ; aucune affinité permanente ne condamne une moitié des handles.

À la sortie de `renewClaims`, qu’elle soit normale ou exceptionnelle, **aucun INSERT/upsert dans le registre de handles**. Sous moniteur, appliquer un éventuel résultat positif seulement si l’entrée existe encore, référence le même objet AcquiredClaim, même Engine/token, et est encore localement active (non démarrée, démarrée, ou sortie avec ticket encore conservé d’après une lecture RO). Dans les autres cas, ignorer ce résultat pour le suivi local ; il ne rouvre ni phase, ni dispatch, ni capacité. Une réponse retardée ne peut remplacer l’entrée d’une réacquisition. Les confirmations d’échéance servent uniquement au diagnostic/réveil et jamais de preuve de propriété ou de résultat métier.

Le retrait d’une envelope peut précéder la fin du lot qui la référence : le contexte en vol subsiste jusqu’à sa propre fin, sans réinscription possible. Un renewal déjà lancé peut prolonger durablement un claim valide juste abandonné ; **aucune compensation SQL** ne rétablit l’ancienne échéance. Après retrait, aucun nouveau lot ne le sélectionne, aucun execute/cession ne découle de cette prolongation et le claim expire à sa dernière échéance durable. Une réponse SQL positive ne restitue/restaure jamais un ticket. Les overlaps execute/abandon/settle suivent uniquement L4/L6 et les conditions §12 : une fin locale UNKNOWN garde le suivi tant qu’Engine conserve la place ; une restitution settle ne prouve pas que le wrapper a quitté. La collection retire les descripteurs sortis/réglés et ne conserve aucun historique.

**R-I5-02 — fin d’un lot, timeout et progrès.** Un seul appel par voie et par incarnation ; ses marqueurs en vol sont de la comptabilité d’appels, **pas des tickets de capacité métier**. Les règles suivantes s’appliquent aux claims, et au lot de la voie leases pour la comptabilité des ressources :

| Issue de l’appel Engine | Marqueur/ressource et prochaine opération |
|---|---|
| Retour normal confirmé | Après traitement local du résultat sous moniteur, retirer le marqueur de cet appel seulement ; candidat toujours actif à nouveau sélectionnable au prochain passage, jamais re-exécutable |
| Failure(COMMITTED) sans résultat exploitable | Résolution physique certaine mais aucune échéance à inventer ; retirer le marqueur après sortie locale ; pour claim, prochain renewal normal même token seulement sous CAS valide ; pour lease, abandon local de l’incarnation selon politique ci-dessous |
| Failure(ROLLED_BACK), timeout serveur **physiquement confirmé** compris | Aucune extension acquise ; retirer le marqueur après sortie locale et backoff borné ; voie libre ou initiale peut offrir un prochain renewal normal au claim encore actif/valide. Tester cette reprise après timeout, pas la présumer |
| Appel encore localement en cours malgré deadline/Future timeout | Conserver marqueur et voie occupés ; aucun second appel sur son candidat. L’autre voie peut renouveler **les autres** handles ; si la deadline complète de sûreté est dépassée, déclencher DRAINING, sans fermer le pool sous cet appel |
| Failure(UNKNOWN), exception brute ou transport laissant la résolution physique incertaine après retour local | Conserver le contexte/voie indisponibles et les candidats exclus de tout autre renewal ; pas de répétition même sur l’autre voie. Passer en DRAINING, poursuivre les autres renewals nécessaires et les résolutions métier I3. Aucun délai, valeur Hikari, absence ponctuelle de lock ou succès d’une autre clé ne requalifie cette opération |

**Choix minimal de recovery renewal : pas de nouveau protocole de règlement physique.** I3 `settlePending` règle les réservations des tentatives/acquisitions ; son contrat ne qualifie pas une transaction de renewal indépendante et ne doit pas être détourné pour effacer son marqueur. Après un retour réellement UNKNOWN, aucun réarmement automatique de cette voie n’est prévu dans cette incarnation du Worker. Un drain qui ne peut établir la fin de cet appel reste incomplet (§16), puis la terminaison externe/reprise JVM suit I3 après résolution DB ; une nouvelle JVM ne suppose aucun rollback de l’ancienne. Les observations SQL du banc peuvent établir ce qui s’est réellement passé, elles ne deviennent pas une API runtime cachée. C’est une dégradation de disponibilité explicitement acceptée sous incertitude, **pas une garantie de progrès sur le lot encore physique**. Un appel simplement retardé qui finit ensuite avec résolution certaine peut terminer son marqueur ; il ne remet jamais DRAINING à RUNNING.

Ainsi, un timeout **certainement rollbacké** permet une reprise mesurée ; un timeout local/UNKNOWN ne permet que le progrès des candidats non en vol et le drainage prudent. Les claims d’un lot physique incertain restent régis par leurs tokens/horloges/verrous PostgreSQL ; aucune voie ne les ressuscite. Avec lot claims singleton, S1 ne rend pas artificiellement S2 membre du même lot bloqué. Un DDL exclusif sur toute relation peut bloquer les deux voies, hors garantie de renewal ; diagnostiquer et appliquer ces règles. Aucun cinquième/sixième pool ni nouveau manager. Si A/B/C échouent sous leurs hypothèses, conserver R-AUD-I3-03 ouverte, publier le contre-exemple et revoir ce plan explicitement avant tout changement d’architecture.

**R10-09 inchangé.** Renouveler les claims actifs même après retrait/perte du lease de partition, y compris durant drain. Aucun contrôle d’ownership ni place métier libre requis. Retour partiel : seules les échéances rendues sont confirmées ; clé sautée n’est pas déclarée perdue/finalisée ; prochain passage si encore active et non en vol incertain. Claim expiré/remplacé : CAS refuse, pas de résurrection ni abandon d’une tentative démarrée.

Politique des leases conservée : entrée absente du retour confirmé ou résultat perdu => retrait local de l’incarnation utilisable/à renouveler, sans métrique de takeover supposé. Un retour tardif d’un lot dont le lease a été retiré n’en recrée pas l’entrée. Tant que RUNNING et qu’aucune voie physique incertaine n’impose drain, prochain passage peut seulement tenter acquirePartition à token neuf ; la DB refuse l’ancien lease encore valide puis applique I3. Un BUSY ponctuel Discovery/acquire suspend son usage jusqu’au renewal confirmé d’un lease encore enregistré. Aucun de ces retraits n’enlève les handles de claims.

Maintenance appelle `settlePending` sur chaque Engine en rotation, au plus un appel/C/passage et 1s sans progrès ; les retraits locaux qui suivent appliquent §12. Elle ne prend aucun ticket métier et ne réarme jamais un lot renewal incertain. Une observation R9 lente peut retarder settle, pas les voies renewal. Défaut d’un thread/service central => DRAINING et diagnostic, maintien des voies saines pour les candidats encore suivis ; aucune santé RUNNING fabriquée.

## 16. Cycle de vie, arrêt, crash et takeover

Machine locale minimale conservée : STARTING → RUNNING → DRAINING → STOPPED ; échec de validation avant threads : STARTING→STOPPED après fermeture des seules ressources initialisées. Pas de nouvel état SQL. Une demande de stop pendant STARTING interdit atomiquement tout passage ultérieur à RUNNING : entrer DRAINING, attendre la sortie de l’initialisation engagée puis fermer seulement les ressources créées, sans lancer de boucle. DB indisponible, lease perdu et saturation sont des conditions observables. Backoff borné et validations I3/I4 restent obligatoires ; UNKNOWN d’un renewal ou défaut du mécanisme de sûreté déclenche DRAINING (§15). La saturation seule n’autorise aucun ticket rendu artificiellement.

STARTING : préflight d’activation §18, catalogue/source/participant/cibles/droits vérifiés, quatre pools réels et réserve candidate inspectés, anciens owners exclus. Aucun Engine mutateur, boucle ou thread Worker avant ces validations. Démarrer ensuite les voies renewal/maintenance et les boucles en attente de RUNNING ; RUNNING seulement après leurs accusés de démarrage. Une classe/ressource manquante avant cette phase refuse le boot sans mutation de coordination, sans catalogue/seed réparé automatiquement.

**R-I5-03 — ordre de drain, L5/L6 font autorité (§12) :**

1. Sous moniteur, L5 passe à DRAINING et retire tous les droits de début non encore L4. Répétitions stop/drain sont idempotentes ; enregistrer les callbacks SmartLifecycle sans les invoquer sous moniteur. Sortir avant signaux et opérations Engine.
2. Appeler `executor.shutdown()` hors moniteur **sans attendre** une résolution SQL pour rendre la barrière effective ; pas shutdownNow/cancel Future comme nettoyage. Une soumission L3 déjà engagée peut encore retourner acceptée/refusée : les deux sont sûres car L4 est fermé. L6 prend en charge chaque abandonnée exactement une fois. Une acquisition L1 rendue après L5 inscrit ses handles comme abandonnés puis termine sa responsabilité, jamais execute. Aucun handle n’est perdu entre le retour SQL et le registre.
3. Attendre, hors moniteur, fin des deux boucles et de leurs responsabilités de post-acquisition, zéro soumission en cours et cleanup pré-début réglé ; les appels déjà engagés résolvent normalement. Les démarrées à L4 continuent execute/cession et signalent leur sortie propre ; aucune annulation de leur droit au seul motif du drain.
4. Continuer les renewals nécessaires aux claims encore actifs, indépendamment des leases, et settlePending. Dès fin de D/acquire, cesser les renewals de partition et laisser expirer les leases ; pas de release durable supposé. Les lots renewal déjà en vol gardent leurs contextes, même si leur handle est retiré ; résultat tardif ne rouvre aucun droit. Deadline et UNKNOWN suivent §15, pas une résolution par délai.
5. Arrêt propre seulement si : boucles/post-acquisitions/soumissions/cleanups terminés, chaque wrapper sorti et exécuteur réellement terminé, WorkerCapacity.worker==0 et pending tentatives réglés par Engine, **aucun lot renewal local ou physiquement incertain**, aucune autre unité/connexion en vol. Arrêter/join les voies maintenance/renewal hors moniteur, fermer EMF/pools Business puis Control ; transition STOPPED sous moniteur après ces faits et invocation des callbacks SmartLifecycle hors moniteur. Callback ne signifie jamais simple demande de stop/Future annulée. `SmartLifecycle.isRunning()` reste vrai pendant le drainage d’un Worker démarré jusqu’à STOPPED ; readiness et état RUNNING sont des notions distinctes. Chaque callback enregistré est invoqué au plus une fois, hors moniteur, seulement après ces faits.
6. Drain90s et timeout Spring120s restent expérimentaux ; ils ne constituent pas une preuve d’achèvement. Deadline dépassée ou contexte renewal UNKNOWN non qualifiable => garder DRAINING/readiness false, signaler les appels/pending restants, **aucun callback de succès**, aucune fermeture de pool sous opérations actives. Une terminaison externe à l’échéance du lanceur est un crash documenté ; le timeout global Spring ne doit pas être présenté comme réussite ni masquer sa destruction éventuelle de ressources. Le tool dev `worker-dev.py stop` (§21) impose une échéance externe105s après SIGTERM, entre drain90s et timeout Spring120s : si le processus reste présent, consigner drain incomplet et le terminer par SIGKILL sur le PID vérifié, **résultat CRASH**, jamais STOPPED propre. La terminaison doit précéder la fermeture forcée de phase Spring ; une exécution manuelle sans ce contrôle ne reçoit aucune garantie de shutdown ordonné au-delà du timeout Spring. Reprise seulement avec vérifications DB/takeover I3. En présence d’un UNKNOWN définitivement non qualifié localement, cette voie dégradée peut être nécessaire même si l’observateur extérieur voit ensuite sa résolution.

Crash/restart/takeover : seuls tours/tickets mémoire disparaissent ; pas bootstrap/reset. Nouveau Worker revalide ses cibles et acquiert leases/slots par I3, attend l’expiration propre de chaque ancien claim et respecte son verrou final. Un token valide pris au verrou final peut encore committer malgré expiry ultérieure ; slot absent seul ne prouve rien. Aucun restart/résultat tardif ne réactive Q, purge un incomplet, réinitialise K, répète une cession UNKNOWN ou relance sous un même claim. Les tests w17/w18/r09 établissent aussi les frontières arrêt/renewal/résolution, sans imputer à une Future les faits PostgreSQL.

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

**Clarification post-audit R-I5-01/02/03 : aucune nouvelle API publique nécessaire.** Les envelopes, le moniteur, les compteurs d’appels/cleanups et le contexte d’un lot en vol restent privés/imbriqués dans les quatre classes déjà listées. `retainsCapacity` reste une lecture RO hors moniteur ; ni son résultat true ni un GrantedClaim ne donnent un droit de début. Les points L1…L6 et l’état du registre sont exposés seulement par seams/snapshots de test package-private bornés, sans tokens exposés aux endpoints. Aucune méthode « settleRenewal » ni extension de AttemptTransactions/SpringAttemptTransactions n’est proposée : la règle conservative §15 évite un second protocole de recovery. `requestDrain()` réalise L5 avant retour ; son callback d’arrêt suit §16, pas le simple retour de cette méthode.

Contrats locaux POLL proposés : `V3ConsumptionWorker.start()/requestDrain()/isRunning()` et état local RO ; `V3DiscoveryLoop.runOnePass()`, `V3ConsumptionLoop.runOnePass()` pour unités ; `V3RenewalService.renewClaimsOnce(authority,lane)/renewLeasesOnce(authority)/settleOnce()`. Ces signatures internes pourront être privées/package-private ; pas API publique distribuée. Leur responsabilité et leurs entrées sont fixées §§7–16 ; seuls noms/encapsulation sans effet sur invariants sont des détails d’implémentation. Aucun contrat transactionnel conceptuel laissé à réinventer.

## 18. Composition Spring, droits et profils

**R-I5-05 — activation en conjonction, avant toute activité.** Le profil `v3-consumption-worker-dev` est conservé, ainsi que `pocoma.v3.worker.enabled=true`. S’y ajoutent une déclaration obligatoire `pocoma.v3.worker.environment=DEV` et une liste explicite de cibles dev autorisées (`pocoma.v3.worker.dev-targets`, tuples serveur TCP/port/base/schema/rôle attendus, distincte des URLs à valider, sans défaut ni déduction depuis un suffixe de nom). Cette déclaration relève du déploiement dev autorisé ; elle n’est pas une détection magique d’un environnement de production. La garde compare ces tuples aux identités physiques réelles sur les deux autorités. Ni profil seul, ni flag seul, ni base dont le nom ressemble à dev ne suffisent. Toute configuration incohérente impliquant I5 refuse le démarrage, sans fallback Legacy.

**Ordre obligatoire, sans nouvelle classe de garde :**

1. Un préflight statique dans les racines COMMAND/MONOLITH, appuyé sur V3BootstrapGuard, est enregistré **même lorsque le profil I5 est absent**. Avant instanciation des beans applicatifs et pools : **même sans aucun profil/flag I5, refuser une définition de fixture Identity, probe ou Worker I5 actif sans autorisation** ; les configurations/valeurs pures ne sont pas des Workers actifs et le chemin historique valide sans I5 conserve son comportement. Dès qu’I5 est demandé, appliquer aussi toutes les exclusions Legacy/HTTP. Lire l’environnement, vérifier conjonction des flags/profils/allowlist, définitions/enablements et types de beans explicites, y compris imports indirects et factories. WorkerConfiguration contient ce préflight importé explicitement ; seule sa partie de création des ressources/Worker est conditionnée par l’opt-in complet. Ne pas placer toute la garde sous @Profile, ce qui ignorerait enabled=true hors profil.
2. Les tuples de source/participant/destination sont fermés : neuf définitions métier exactes, sources livrées seulement ; les seules entrées métier activables en I5 incluent Command avec sa fixture autorisée. Les deux définitions READ techniques §4 gardent leurs UUID/transformation et table de fixture. technical-probes=true exige DEV autorisé et les deux définitions exactes installées ; false exige leur absence des définitions actives/beans et du catalogue attendu du mode. fixture-identity=true exige DEV autorisé **et Command actif** ; Command actif exige ce flag et cet unique fournisseur de fixture ; sans flag, aucun tel bean même indirect. Aucune configuration ne peut mapper une probe sur un UUID métier, ou réciproquement, ni sélectionner une classe librement. Un sous-ensemble actif non vide des couples réellement disponibles est permis ; il ne modifie jamais les neuf définitions canoniques.
3. Inspecter les définitions de beans avant création : aucun Legacy, migrateur/repository historique, endpoint/admission/HTTP métier, scheduler/runner non explicitement prévu. Un alias, import transitif ou factory ne contourne pas le contrôle. Tout bean applicatif potentiellement actif de type non déterminable sans instanciation est refusé en mode I5 ; pas d’instanciation « pour voir » suivie d’une garde tardive. Les factories/infrastructures Spring admissibles sont explicitement inventoriées, sans whitelist globale .v3. Les constructeurs/factories autorisés ne lancent ni Engine mutateur ni thread Worker ; la composition et les tests négatifs le vérifient.
4. Préflight DB RO avec les cibles non poolées déjà utilisées par V3BootstrapGuard : mêmes paramètres exacts que ceux validés pour les futurs pools, cibles autorisées/physiquement distinctes, rôles/droits/routage/hash et catalogue complet. Ces lectures/rollback n’installent ni ne réparent aucun objet. Ce contrôle se fait avant démarrage des pools/EMF et de leurs ressources de fond pour les erreurs connues ; il ne constitue pas un cinquième pool. Après création des quatre pools et EMF, revérifier leurs **ressources réellement injectées**, limites/emprunteurs et beans finaux. Toute différence refuse avant le premier thread Worker/appel Engine mutateur, ferme les ressources initialisées et laisse le vecteur de coordination SQL inchangé.
5. Seulement après réussite de ces gardes : start du superviseur §16. Aucun acquisition/renewal/Discovery, seed ou mutation de coordination n’a lieu dans un callback de validation ou un health. La vérification tardive complète la garde pré-instanciation, elle ne la remplace pas.

| Configuration invalide à exercer individuellement (c05) | Refus requis |
|---|---|
| Profil I5 avec enabled absent/false ; enabled=true sans profil I5 | Erreur de configuration avant création des beans actifs ; pas de fallback ou silence |
| Profil I5 valide mais environment absent/non DEV ou dev-targets absent/vide/cible hors liste | Refus de chaque propriété/cible obligatoire séparément, pas de défaut inféré du profil |
| Profil I5 associé à v3-consumption-pilot, postgres historique ou autre profil non explicitement admis (pour ce pilote : I5 seul) | Refus sans changer les règles du profil I1 inerte |
| Probes ou fixture Identity demandées hors DEV/allowlist, y compris enabled=false/profil absent | Refus préflight ; aucun fournisseur de fixture instancié |
| Définition technique absente, inattendue ou mapping UUID technique↔métier inversé | Refus du tuple complet ; pas no-op/substitution ou création runtime |
| Catalogue canonique incomplet, famille/P/autorité/routage incompatibles ; probe inattendue quand option false | Refus RO, pas correction du catalogue ni création de lease |
| Droits excessifs, mauvaise cible, admin/membership/DDL ; mauvais pool/TM/EMF | Refus avant threads Worker et toute mutation durable de coordination ; snapshot avant/après identique |
| Bean fixture Identity présent sans autorisation ; Legacy ou HTTP métier importé indirectement, sous alias/factory | Refus de définition avant constructeur actif ; garde finale et test de compteurs/absence de mapping comme seconde protection |

Les racines importent explicitement I5 et le catalogue I1 ; Legacy reste sous `!v3-consumption-pilot & !v3-consumption-worker-dev`, mais le préflight ci-dessus est nécessaire pour enabled=true seul. Le catalogue inerte reste inchangé ; le profil actif appelle statiquement definitions() sans importer ses BeanFactoryPostProcessor inertes. Pas de component scan général. Auto-configurations DS/TM/JPA/repositories/Flyway Boot et ReadStoreAccess/Migration historiques exclues ; pas @Primary général ; qualifiers par autorité et pool. Désactivation des propriétés historiques en défense supplémentaire, jamais comme seule preuve de non-instanciation. Health DB automatique désactivé selon preuve A §14.

V3BootstrapGuard conserve intégralement son API/règles I1. Son entrée active `validateWorkerResources(...)` proposée vérifie l’unique WorkerCapacity par identité d’objet, quatre pools/TM/EMF explicites, sources/participants réellement raccordés, contrats/partitions/rights ; aucun contournement de requireInertType par autorisation globale de packages. Une classe seulement présente au dépôt n’est pas un participant actif.

Droits : même union explicite LIFECYCLE_AND_DISCOVERY I4 sur les deux pools d’une autorité ; pas DDL/routage/TRUNCATE/SET ROLE/membership/SECURITY DEFINER, pas INSERT v3_command au Worker. Admin, réactivation, injection et bootstrap dev sont externes. Scripts lifecycle puis discovery inchangés ; grants de fixture séparés. Une séparation de pools n’empêche pas un SQL arbitraire sous ces credentials ; les entrées inventoriées/ArchUnit restent nécessaires.

Mode probes : bootstrap administratif dev séparé après bootstrap canonique **neuf**, deux tuples READ §4, huit ownership et table d’effet de fixture ; aucune table de coordination nouvelle. Avec probes=true, attendre exactement2 contrats WRITE/9 READ et8 ownership WRITE/36 READ ; avec probes=false exactement2/7 et8/28, jamais se contenter du seul COUNT sans comparer les tuples. I1 reste2/7 et8/28. Un changement d’option n’efface aucun contrat/slot ; si le durable ne correspond pas au mode choisi, refuser plutôt que réparer. Sept READ métier et IdentityRequest restent disabled ; trois couples disponibles sont activés dans la démonstration complète.

Fixture Identity : adaptateur dev nommé, même DS Business WRITE et unité du participant ; témoin positif/négatif protégé selon PotActorAuthority, pas allow-all, pas transformation Binding V2→EUI. Elle ne qualifie pas L3/AuthN/consentement. Les probes READ gardent effets techniques/completion/close/observe cohérents, jamais une matérialisation L4. Toute erreur de ces gardes bloque l’activation ; elle ne donne aucun PASS métier.

## 19. Activation et disponibilité réellement livrable

À clôture future : jar COMMAND existant avec profil dev explicite, deux boucles vivantes, threads/pools observables, Command réel et probes READ alimentés par ses publications, arrêt/restart sans bootstrap. R-I5-05 : cet exécutable ne devient actif qu’après le préflight complet §18 (flags, DEV autorisé, tuples et beans/ressources réels), jamais au seul nom du profil. Même assemblage dans MONOLITH pour prouver l’isolation/composition ; il ne devient pas un serveur fonctionnel V3 complet. Aucune modification des autres runtimes séparés ni de Docker de distribution finale.

Profil dev : servlet uniquement pour Actuator health/prometheus sur adresse loopback et port de management configuré ; aucun controller métier scanné, aucun endpoint de fixture/injection. Port applicatif distinct, aucun mapping métier ; propriété d’adresse explicite et tests de mappings. Runtime exécutable ≠ autorité Identity réelle ≠ API d’admission/lecture métier. **Aucun parcours Bruno V3 métier pertinent n’est disponible dans ce périmètre** ; les collections historiques ne peuvent appeler une méthode interne ni une fixture SQL. Interroger Prometheus/health avec curl est une observation opérationnelle, pas un parcours Bruno métier.

## 20. Observabilité opérationnelle

Réemploi : MeterRegistry/Actuator/registry-prometheus et conventions de Micrometer existants ; nouveau adaptateur V3 pour outcomes V3. Timers monotones locaux avec histogrammes (`publishPercentileHistogram`/buckets explicites expérimentaux 1ms,5ms,10ms,50ms,100ms,500ms,1s,2s,5s,30s,60s,120s) ; exporter la précision réelle. Aucun timestamp durable/journal de claims créé pour mesurer.

Labels bornés : `authority` 2 ; `consumption` libellé fermé9+2 probes ; `loop`2 ; `lane`3 par autorité ; `phase` liste fermée≤16 ; `outcome` liste fermée≤12 par métrique, **pas produit cartésien de tous** ; `pool`4. Partition exclue des séries par défaut, éventuellement4 seulement pour une métrique explicitement justifiée. worker/instance est une cible de scrape bornée par déploiement, pas UUID applicatif dynamique en label. `operation` métier n’est pas nécessaire au Worker. Budget par nom = produit des seuls labels de sa ligne (borne majorée ci-dessous), multiplié par buckets pour histogrammes ; vérifier sur /prometheus.

| Nom Micrometer proposé | Type / labels / cardinalité maximale hors buckets | Point exact / vérification future |
|---|---|---|
| `pocoma.v3.worker.state` | gauge one-hot state4,4 | Transitions superviseur ; start/drain/deadline/stop testés, pas STOPPED avant résolution |
| `pocoma.v3.worker.cycles` et `.cycle.duration` | counter + timer/histogram ; loop2,outcome6=12 | Retour de passage de boucle, y compris erreur ; appels indépendants et délais de polling |
| `pocoma.v3.consumption.opportunity.duration` / `.opportunity.wait` | timers/hist consumption11,outcome6=66 ; wait consumption11=11 | R-I5-04 : sélection C/p→prise en charge des handles/retour des soumissions, et temps monotone depuis la précédente opportunité de C ; nav/emprunt/acquire séparés par phases. Une acquisition lente se reflète dans l’attente des autres C ; test w19, pas SLO absolu |
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
| `pocoma.v3.physical.unknown` / `.pending` | counter consumption11,phase4(acquire/attempt/cession/renewal)=44 ; gauge pending tentatives consumption11=11 | Failure UNKNOWN/raw ; distinguer réservations Engine et opération renewal incertaine, COMPLETED R9 ne supprime pas l’incertitude historique (R-I5-01/02) |
| `pocoma.v3.renewal.inflight` | gauge authority2,lane3,kind2(active/unknown)=12 | Contexte local de lot conservé même après retrait de handle ; lecture de snapshot local sans SQL/callback sous moniteur, validée r09/m01 ; ni ticket ni état durable |
| `pocoma.v3.takeover.observed` | counter authority2=2 | Seulement quand preuve durable d’ancien propriétaire expiré/token changé est disponible ; acquisition seule après redémarrage = acquire, pas takeover inféré |
| `pocoma.v3.drain.duration` / `.deadline` | timer outcome2=2 ; counter=1 | Fin propre vs deadline, jamais victoire de Future.cancel ; tests du superviseur |
| métriques Hikari/JVM/Actuator existantes | gauges/timers pool4 | active/idle/pending/max/emprunt et threads/GC ; vérifier les noms exposés par version effective plutôt que dupliquer Hikari |

Backlog et âge : comptage SQL périodique **hors réserve runtime**, par observateur d’exploitation/dev séparé et fréquence fixée (5s initial) ; count non-Q/Q, sources PENDING et plus ancienne date source si disponible. L’âge exact d’éligibilité n’est pas dans le slot ; fournir proxy source/échéances ou âge local depuis première observation, marqué borné et réinitialisé au restart. Ne pas ajouter createdAt de slot pour prétendre à un audit temporel exact. Les compteurs d’événements confirmés peuvent sous-compter sous ACK perdu : les appeler confirmés, les preuves SQL arbitrent les transitions durables.

Logs structurés : phase/C/autorité/issue et corrélation ciblée contrôlée ; **aucun token fencing/binding**, aucun toString de ClaimedSlot/PartitionLease/exception incluant paramètres sensibles. UUID slot/Pot/user/request ne sont **jamais labels Prometheus** ; diagnostics privés de certification peuvent conserver les clés/token jetables nécessaires aux assertions, séparés et non exposés. Metrics=exploitation ; traces=diagnostic ; SQL extérieur cohérent=preuve. Tester un observateur qui lève et une instrumentation omise/décalée : ni protocole altéré ni faux PASS de branchement ; O01-N doit détecter la mesure absente.

## 21. Démonstration reproductible Command / READ

**Tout ce chapitre est une procédure future NOT RUN.** Les scripts/classes suffixés worker-dev n’existent pas encore ; leur création fait partie d’I5 autorisé ultérieurement. Ne pas lancer ces commandes comme si le présent plan les avait livrés. Il n’existe actuellement aucun lanceur Worker V3 actif à tester.

Prérequis : JDK21 compatible avec le POM (toolchain effective à enregistrer), Maven wrapper, Docker pour le banc jetable ou PostgreSQL17.11 TCP dédié (version/digest du banc I4 à reprendre), pgcrypto dans public, client psql, Python3, ports locaux libres ; aucun Keycloak/Bruno nécessaire pour ce pilote interne. Deux **nouvelles bases** explicitement nommées, par exemple `pocoma_i5_write` / `pocoma_i5_read`, schéma `pocoma_i5`, rôles admin/runtime/injecteur/réactivation distincts, aucune appartenance runtime, secrets fournis par environnement/PGSERVICE hors Git et fichiers locaux600. Refuser cible `public`, cible existante non vide, noms/serveur inattendus ou absence de mandat dev ; pas de DROP/reset automatique.

Le futur `worker-dev.py` est un lanceur de développement, pas un installeur L8. Il vérifie config/cibles, utilise psql avec `-X -v ON_ERROR_STOP=1`, construit le jar existant sur demande puis extrait BOOT-INF/classes et libs dans un dossier temporaire pour invoquer `V3WorkerDevelopmentTools` (main Java **sans** contexte Spring). Le Java réutilise Flyway de l’application et ses migrations, sans chaîne V2 ; les opérations sont séparées `prepare`, `inject-command`, `verify`, `inspect`, `stop`. La commande stop reçoit explicitement le PID du lancement vérifié (identité/processus attendu), envoie SIGTERM puis applique l’échéance externe105s du §16 ; codes/messages distincts pour sortie propre prouvée, drain incomplet/CRASH et observation insuffisante. Une simple disparition de PID ne prouve pas un drain réussi. `prepare` n’est jamais appelé par start/restart. L’injection utilise un principal distinct et un PreparedStatement/intention encodée par le codec livré, sans slot synchrone.

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

Propriétés nouvelles proposées à documenter intégralement dans `docs/v3/L2_I5_Worker_Development_Runbook.md` futur : `pocoma.v3.worker.{enabled,environment,dev-targets,fixture-identity,technical-probes,worker-name,worker-limit,consumption-limits,partitions,min-poll,max-poll,error-poll,drain-timeout}` ; préfixes `pocoma.v3.worker.write-business`, `write-control`, `read-business`, `read-control` pour URL/user/password/max/minIdle/timeouts ; cibles attendues `pocoma.v3.pilot.{write,read}.{database,schema,username}` réutilisées pour guard, URLs/rôles identiques aux pools correspondants. R-I5-05 : environment=DEV et dev-targets exacts sont obligatoires dans ce fichier avant prepare/start ; leur absence refuse, aucun défaut « dev » dérivé du profil. Le script valide les secrets via variables d’environnement et renseigne le fichier local, ne les écrit pas dans un rapport. Rôles admin/injecteur/réactivation déclarés dans la configuration du **tool**, jamais injectés au contexte Worker. Paramètres enabled-C figés aux trois définitions disponibles, aucune acceptation libre de nom de classe/UUID. `management.server.port=18081`, `management.server.address=127.0.0.1`, port applicatif18080 loopback ; deuxième Worker ports18082/18083.

Étapes physiques de prepare, sans requête destructive :

1. Établir l’inventaire des bases/serveur/rôles et l’absence d’anciens writers. Créer bases/schémas/rôles neufs et extension avec l’administrateur explicite, pas Worker.
2. WRITE Flyway locations `classpath:db/v3-pot/migration`, `classpath:db/v3-pot-execution/migration`, `classpath:db/v3-consumption-write/migration`, donc V1…V5 existantes ; READ `classpath:db/v3-consumption-read/migration`, V1. Placeholders obligatoires `expectedDatabase/expectedSchema/expectedAdmin/expectedRuntime`, history isolée ; aucune baseline V2 supposée.
3. Sur chaque autorité, appeler `scripts/v3/bootstrap-l2-routing.sql` avec variables `expected_database/expected_schema/expected_admin/authority` et search_path explicite. Vérifier2/7 contrats,8/28 ownership, zéro slots/K. Exécuter lifecycle puis discovery avec runtime_role/reactivation_role ; ordre et inputs conformes aux scripts livrés. Les quatre pools utilisent ces mêmes rôles validés.
4. Installer les seuls objets de fixture/IDs techniques dev par le script séparé §25 ; vérifier2/9 et8/36. Fixture Identity associée à EUI de test/témoin/token de test et UserId connu, sans admission Identity métier ; probes READ vides, autorisations minimales. Créer principal injecteur avec seules permissions d’admission interne requises, distinct des grants runtime ; aucune connexion admin au Worker.
5. Avant injection : boot du Worker doit constater deux boucles et réserves établies, aucun ancien bean actif, pas de slot ni checkpoint burnable. Inspect peut vérifier `current_database/current_schema/current_user`, privilèges effectifs et sessions `application_name` pool/voie bornée ; les noms de sessions ne contiennent pas de token.

Après injection : source COMMAND PENDING durable, sans slot dans l’unité d’admission ; Discovery crée le slot canonique, Consumption acquiert un claim, ExecutePotCommand applique CreatePot, résultat/source terminale/DELETE/publications dans un seul xid8 WRITE. Oracle attendu préinscrit : une version Pot1, composantes initiales exactes du scénario, un POT_EVENT et une COMMAND_COMPLETION_EVENT chacun positionné, un seul résultat, source TERMINAL, slot Command absent. READ : deux effets de probe, chacun atomique avec son slot supprimé ; leurs checkpoints propres dépassent les positions globales sources couvertes ; **aucune** PotVersion/Balance/CommandResult métier READ attendue. Inspect capture séparément WRITE/READ (pas snapshot distribué), puis vérifie la stabilité après quiescence.

Arrêt (R-I5-03) : commande future `python3 scripts/v3/worker-dev.py stop --config /private/tmp/pocoma-i5-dev.properties --pid "$WORKER_PID"`, variable du PID de lancement explicitement enregistré/vérifié, pas kill par motif large ; elle envoie SIGTERM avec borne externe105s (§16), observations DRAINING/STOPPED et ordre des fermetures. Injecter avant arrêt une seconde Command PENDING ou une tentative longue pour variante drain ; redémarrer avec exactement la même commande java **sans prepare**. Attendre finalisation des seuls incomplets et comparer compteurs/effets avant/après ; la première demande ne produit rien de plus. Variante crash SIGKILL contrôlée dans le banc : attendre conditions takeover en DB, jamais effacer le claim. `verify` utilise requêtes SQL extérieures et un délai borné de sondage ; timeout=échec d’observation/INCONCLUSIVE, pas succès. Les scénarios UNCHANGED/REJECTED réutilisent le vrai participant via méthodes de campagne, sans nouvelle API HTTP.

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
| `i5_r05` | Toutes C/p maximales admises au profil et2 Workers ; cadence/retards/emprunt/locks/commit/cleanup/jitter/pauses JVM mesurés, calcul §14 préfigé avec claims singleton ; minimum marge DB positif sous hypothèses ; variantes B1/B32 et M_W1/4 | Runtime, C+F |
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
| `V3WorkerPostgresTest.i5_w17` | R-I5-01 : renewal positif retardé, sortie locale/rendu Engine/retrait puis retour ; abandon avant début ; réacquisition de même clé avec autre incarnation ; overlaps pending/settle. Variantes et oracle I5-O-HANDLE ci-dessous | WRITE réel/READ technique, C+F |
| `V3WorkerPostgresTest.i5_w18` | R-I5-03 : ordres L1…L6 contrôlés, drain avant/pendant soumission, après acceptation avant L4, deux ordres start/drain et deadline UNKNOWN ; oracle I5-O-DRAIN | WRITE/READ, C+F |
| `V3WorkerPostgresTest.i5_w19` | R-I5-04 : acquisition C1 maintenue lente, autres C éligibles, mesures d’opportunité/attente puis capacité saturée/rendue et reprise ; oracle I5-O-SLOW | Runtime mixte, C+F |
| `V3WorkerResourcesPostgresTest.i5_r09` | R-I5-02 : ligne SKIP, attente connexion, relation, transport, timeout confirmé puis reprise, UNKNOWN physique après retour ; ressources/échéances et lot singleton selon I5-O-POOL | WRITE/READ, C+F |
| `V3WorkerCompositionPostgresTest.i5_c05` | R-I5-05 : chaque configuration négative §18, tuples et flags/DEV autorisé, beans indirects/FactoryBean ; refus avant threads Worker et zéro delta coordination ; oracle I5-O-ACT | C+M+A+F (même matrice via racine monolithe dans mono01) |
| `ReadDiscoveryPrivilegesPostgresTest.i5_read_pools` (extension) | Deux pools READ même rôle exact et droits autorisés, excès membership/DDL/routage refusés ; grants probes dev ne changent pas contrats I1 et runtime sans injection | R+A+F |

Les tests de ressources peuvent utiliser des barrières/seams de **test** autour des vrais ports/connexions pour conserver toutes les voies occupées, mais aucune implémentation mock de transaction/store/renewal ne remplace les vraies opérations. R02 doit enregistrer l’inventaire complet et un témoin positif de chaque emprunteur ; louer des connexions arbitraires à la place des cessions/boucles ne suffirait pas à certifier l’assemblage. La somme des majorants est conservative : le même ticket ne peut simultanément porter une finalisation et une cession. La saturation physique supplémentaire est une injection de faute séparée, jamais présentée comme une occupation naturellement atteignable par le runtime. R03 différencie row SKIP et vraie attente ciblée, pas un sommeil local qualifié abusivement de lock PostgreSQL.

Campagne coût local n07/r05/w16 :5 échauffements+30 mesures par configuration, jeux déterministes et proportions0/50/90% Q/actifs/retry, B1/32,100/1000/10000 slots/p ; enregistrer SQL/lignes/buffers/WAL, p50/p95/p99 descriptifs et maxima, Hikari/CPU/GC/mémoire, backlog et âge proxy. DML EXPLAIN seulement sur cible jetable puis rollback ; aucune suppression d’index sur une cible d’utilisateur. SLO SPEC-09 et 10M événements reportés L6, ces mesures ne les certifient pas.

### Variantes post-audit : oracles locaux additionnels, tous NOT RUN

Ces assertions complètent les oracles indépendants existants (OR-EQ-CAP/RENEW/WALK, OR-CL-OWNER, OR-AT-UNIT, OR-AR-BOUND/CERT) **sans modifier leur catalogue ni les allocations SPEC-10**. Les identifiants I5-O-* sont des descriptions d’oracles locaux de ce plan, pas de nouvelles exigences normatives. Chaque variante ci-dessous est obligatoire dans le futur manifeste et possède son artefact propre, pas seulement le nom partagé de la méthode.

**I5-O-HANDLE / w17 (R-I5-01).** Avant essai : enregistrer incarnation d’objet/envelope, clé/token SQL privé, phase/registre/ticket, vecteur source/effets/résultat/publication ; snapshots locaux RO de test et observateur SQL extérieur distinct. Une barrière de proxy retient le retour d’un renewal **après son commit PostgreSQL constaté extérieurement** : le callback métier reste libre de finir, puis on libère le retour ancien. Variantes : H1 succès/fin locale/retrait avant retour positif ; H2 abandon pré-début pendant ce retour retardé (claim peut être prolongé, count0/PENDING/zéro effet) ; H3 vraie cession après rollback puis retry/acquisition d’une **nouvelle incarnation de même clé**, ancien retour après inscription B ; H4 tentative UNKNOWN encore physique après retour local, deux settle concurrents pendant lock puis résolution serveur et règlement unique, retour renewal avant et après ce retrait. Inclure le chevauchement restitution Engine→wrapper pas encore sorti pour vérifier la borne des descripteurs §7. Assertions conjonctives : mêmes entrées vivantes attendues, retirée jamais réinscrite, B non remplacé, aucun nouveau début causé par réponse, ticket rendu uniquement Engine/une fois, claim SQL/deadline compatibles avec l’ordre réellement committé, aucun double effet/cession. Si réponse manque ou résolution physique non observée, INCONCLUSIVE ; si réinscription ou restitution Worker, FAIL.

**I5-O-DRAIN / w18 (R-I5-03).** Journal de test des transitions L1…L6 (échantillons locaux bornés copiés sous moniteur, exportés hors moniteur sans appel observateur/attente) et journal indépendant des appels réels Engine/participant, barrières **hors moniteur** aux frontières : D1 acquisition confirmée puis drain avant inscription/admission ; D2 L3 avant appel executor.execute, drain/shutdown pendant cet intervalle ; D3 soumission en cours/acceptée avec runnable retenue avant L4 ; D4 deux ordres forcés L4<L5 et L5<L4, y compris pause hors moniteur après L4 avant premier appel Engine ; D5 retour local avec transaction physiquement ouverte, Future finie/annulée ; D6 deadline avec pending tentative UNKNOWN et lot renewal UNKNOWN r09, puis terminaison externe avant timeout forcé Spring et verdict CRASH distinct ; variante stop pendant STARTING interdisant RUNNING. Forcer aussi rejet AbortPolicy et répétition stop/cancel sur la même envelope. Attendus : tous handles confirmés comptés jusqu’à leur cleanup, chaque L4 seulement avant L5, aucune execute pour l’autre ordre, une seule abandon pour chaque pré-début, aucune abandon après L4, zéro soumission/cleanup perdus, pas lock local pendant SQL/callback. Les démarrées à L4 terminent normalement ou restent pending I3 ; snapshots SQL/tickets et sorties réelles établis séparément. Callback SmartLifecycle une fois par demande enregistrée après STOPPED propre seulement, zéro avant ; deadline/UNKNOWN => DRAINING/callback absent/pools non fermés sous activité. Retenue SQL puis sortie sûre observée, pas simple attente temporelle. Un arrêt prématurément annoncé est FAIL, même si les effets finissent ensuite corrects.

**I5-O-POOL / r01…r09 (R-I5-02).** Émettre trois artefacts distincts **A,B,C par autorité** ; garder l’inventaire réel de chaque emprunteur, les conditions communes et les variantes. P1 : verrou de ligne S1, SKIP LOCKED, S2 singleton renouvelé ; P2 : waiters Hikari identifiés et emprunt S2 mesuré sous épuisement Business, puis Control non-renewal au majorant conjoint admissible et stress physique séparé ; P3 : S1 UPDATE retenu par un trigger/advisory de test ciblé S1, S2 hors de ce lot et sur l’autre voie, PID/bloqueurs PostgreSQL ; P4 : vraie attente de relation empêchant tous les claims, délais qualifiés séparément ; P5 : réseau lent/supprimé avant/après COMMIT, socket/local timeout avec backend/xid/lock encore actifs **après retour local** ; P6 : timeout serveur et rollback **confirmé**, marqueur terminé, backoff et reprise sur une voie libre lorsque token toujours valide ; P7 : timeout local/UNKNOWN, marqueur/lot S1 jamais réattribués, S2 progresse si accessible, DRAINING sans faux succès, résolution DB extérieure puis terminaison/restart I3 sans réarmement local supposé. Varier Business/Control saturés, WRITE/READ, M_W1/4 ; S2 distinct est exigé lorsque M_W≥2, tandis que M_W1 vérifie qu’unique candidat en vol ne peut être repris par l’autre voie. Le singleton implique L_claim≤M_W, B de port32 n’autorise pas de réintroduire un gros lot claims.

A vérifie notamment health/prometheus sollicités, constructeurs JPA hors RUNNING, R9/cession/maintenance/superviseur, et refuse tout nouvel emprunteur non alloué. B distingue active/idle/pending Hikari des PID/xid encore serveur et prouve la connexion **et le commit**, pas seulement la demande de S2. C mesure réveil prévu→emprunt→verrous→SQL→commit→cleanup puis délai de service des prochains candidats, pause JVM/jitter budgetés et échéances DB ; aucun appel incertain effacé pour faire rentrer le budget. Sous hypothèses A/B/C déclarées, dépassement = FAIL ; panne globale durable ou observation manquante reste hors certificat/INCONCLUSIVE. Des tests passant sur les seuls cas de panne diagnostiquée ne ferment pas R-AUD-I3-03. Pas de ressource supplémentaire introduite pour obtenir un PASS.

**I5-O-SLOW / w19 (R-I5-04).** Préinscrire au moins trois C/P multiples, M_W1 puis4, quantum1 et des slots durablement éligibles. Retenir l’acquisition C1 sur une **attente PostgreSQL attestée** (relation/SQL de fixture sur son chemin), sans bloquer les tables de C2/C3 ; les maintenir libres. Journal extérieur des sélections C/p et heures monotones, SQL/PID/bloqueurs ; observer positivement que Discovery et renewal continuent, tandis que cette seule boucle Consumption n’offre pas C2/C3 pendant l’attente synchrone. Libérer avant timeout puis variante retour par timeout confirmé/exception ; mesurer durée complète de l’opportunité et retard C2/C3 (pas de faux progrès cursor en exception). Après retour, prochain bénéficiaire attendu C2, jamais tête réinitialisée C1 ; saturer M_W puis le rendre et vérifier même prochaine C conservée, rotations de partitions et acquisitions successives sous fenêtres libres. Le coût imposé par C1 est une mesure, pas un échec si conforme à cette limite déclarée ; oubli/réinitialisation injustifiée ou absence de reprise après fenêtres rétablies = FAIL. Blocage durable sans retour ne permet aucun certificat d’équité temporelle.

**I5-O-ACT / c05 + mono01 (R-I5-05).** Figer une configuration positive de référence et la changer **une condition à la fois** pour toutes les lignes négatives §18, avec variantes UUID technique à rôle métier/UUID métier à rôle probe, catalogue9 incomplet ou11 incohérent, probe manquante/inattendue, principal trop privilégié, fixture Identity sans drapeau/DEV autorisé, Legacy/controller métier sous import transitif/alias/factory à type indéterminé. Fournir observateur SQL externe des deux autorités avant/après, compteurs de construction des beans sentinelles et capture des threads Worker. Attendus : exception de préflight avant constructeur applicatif interdit, zéro démarrage Worker/L1/renewal, **vecteur durable contrats/ownership/K/slots inchangé**, aucune API métier instanciée. Contrôle positif voisin : opt-in complet démarre les seuls couples disponibles ; I1 seul reste inerte et aucun paramètre I5 invalide n’est ignoré par absence du profil. READ probe ne produit jamais de table/effet L4. Toutes ces variantes tournent dans les deux racines ; classe non instanciée, absence de threads et absence de mutation sont trois assertions distinctes, pas un seul contexte Spring fermé.

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
| R10-08 → r01…r09 | Pools réellement saturés/S1 bloqué/S2/lease | OR-EQ-RENEW/CL-RENEW, PID/deadlines | Deux autorités, C/F | P+A/B/C séparées+config effective et calcul marge ; NOT RUN |
| R10-09 → w09,r07 | Lease perdu, claim valide renouvelé | OR-CL-OWNER, SQL extérieur | Deux autorités, C/F | P, stop D/acquire pas claim renewal ; NOT RUN |
| R10-10 → w03,w16,n07,m01,r05 | Capacité/fenêtres libres contrôlées, âge proxy/coûts | OR-EQ-WALK/OBS-METRIC, registre source | Mixte, C/A/F | P+mesures, hypothèses explicites ; NOT RUN |
| R10-11 → c03 + architecture | Inventaire tables/beans/POM et contre-exemples | OR-AR-BOUND/NOPURGE, catalogue/imports | Statique + DB, A/F | P+rapport ArchUnit, aucun coordinateur externe ; NOT RUN |
| R-AUD-I3-03 → r01…r09,w01,w12 | Tous emprunteurs réels, threads distincts, saturation et drain | OR-EQ-RENEW/AR-BOUND, inventaire/PID indépendant | WRITE/READ, C/A/F | P, A ∧ B ∧ C WRITE ∧ READ ; **NOT RUN / réserve ouverte** |

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

### 23.4 Traçabilité des cinq réserves et gates additionnels

Aucune ligne §§23.1–23.3 n’est supprimée ou réduite. Les exigences déjà allouées conservent leurs variantes ; les nouvelles preuves suivantes s’y ajoutent, toutes **NOT RUN** :

| Réserve → méthode future | Invariant / stimulus | Oracle indépendant | Frontière / gate | Preuve attendue / règle d’échec |
|---|---|---|---|---|
| R-I5-01 → w17, w11, u03/u05/u07 | Inscription unique, retrait monotone, identité+phase courantes, fin locale/settle/renewal retardé | I5-O-HANDLE + OR-EQ-CAP/AT-UNIT | WRITE/READ, C/F | Traces H1…H4, registre/tickets+SQL/effets ; réinscription/release Worker = FAIL |
| R-I5-02 A → r02/r08,c03/c05,read_pools | Tous emprunteurs Spring/JPA/health/cession/R9/maintenance attribués | I5-O-POOL A + OR-AR-BOUND | WRITE/READ, C/R/M/A/F | Inventaire statique et positif réel par entrée ; appelant inconnu bloque A |
| R-I5-02 B → r01/r02/r03/r06/r09 | Quatre pools, saturation métier/contrôle, lot bloqué non repris par l’autre voie | I5-O-POOL B + OR-EQ-RENEW/CL-RENEW | WRITE/READ, C/F | PID/emprunt/commit S2 et voie leases pendant S1 ; aucune disponibilité inférée |
| R-I5-02 C → r05/r07/r09 | Budget complet, singleton, reprise après rollback timeout certain vs UNKNOWN physique | I5-O-POOL C + OR-EQ-RENEW/OBS-METRIC | WRITE/READ, C/F | Délais/marges préfigés mesurés et trace P1…P7 ; dépassement admissible = FAIL |
| R-I5-03 → w18,w05/w06/w12/w13,r09 | Barrière L5 atomique avec L4, cleanup univoque, arrêt jamais présumé | I5-O-DRAIN + OR-EQ-CAP/AT-UNIT | WRITE/READ, C/M/F | Six courses D1…D6, callbacks et pools ordonnés ; début après L5/callback faux = FAIL |
| R-I5-04 → w19,w03/w16,m01 | Opportunité lente mesurée, nextConsumption conservé, reprise sans équité temporelle stricte | I5-O-SLOW + OR-EQ-WALK/CAP/OBS-METRIC | Mixte, C/F | Horodatages C1/C2/C3, bloqueur SQL, délais et ordre après libération ; reset pointeur = FAIL |
| R-I5-05 → c05,c01…c04,mono01,read_pools | Opt-in complet+DEV autorisé+tuples réels et beans admissibles avant activités | I5-O-ACT + OR-AR-BOUND/BOOT/CERT | WRITE/READ, C/R/M/A/F | Chaque négatif §18+positif voisin, zéro delta SQL/thread ; fallback/substitution = FAIL |

Les allocations C03-C/L, R10-01/06/08/09/10/11, CON-04/06, OPS-01 et O-01 consomment aussi ces nouvelles méthodes. **R-AUD-I3-03 reste NOT RUN/ouverte jusqu’à A ∧ B ∧ C pour WRITE ∧ READ sur le vrai runtime**, plus les régressions et les comportements conservateurs de timeout/UNKNOWN. Un amendement documentaire ou une preuve seulement A, seulement B, seulement C ne la ferme jamais. Aucun des nouveaux oracles locaux ne certifie Identity ou une projection L4.

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
| `J(POLL)/orchestrator/poll/consumption/v3/V3ConsumptionWorker.java` | Moniteur local L1…L6, registre/incarnations et drain/cleanup (R-I5-01/03), exécuteur métier ; types déjà prévus imbriqués |
| `J(POLL)/orchestrator/poll/consumption/v3/V3DiscoveryLoop.java` | Rotation et RequestWalk, appels I4 uniquement |
| `J(POLL)/orchestrator/poll/consumption/v3/V3ConsumptionLoop.java` | Rotation/SlotWalk et opportunités lentes mesurées (R-I5-04), nav/acquire/dispatch L1…L6 sans SQL ni tickets propriétaires |
| `J(POLL)/orchestrator/poll/consumption/v3/V3RenewalService.java` | R-I5-01/02 : snapshots/incarnations, retour sans upsert, singleton/voie en vol et UNKNOWN non réarmé, maintenance settle existante ; aucune nouvelle recovery métier |
| `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java` | Préflight de définitions/propriétés toujours importé (R-I5-05) ; ressources/Worker seulement sous opt-in complet ; quatre pools/TM/EMF, health sans JDBC et SmartLifecycle imbriqué |
| `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionWorkerSettings.java` | Propriétés et validation fail-closed des bounds/emprunteurs/configs, pas état durable |
| `J(COMPOSITION)/runtime/consumption/v3/MicrometerV3ConsumptionObservation.java` | Traduction des événements bornés en métriques §20 ; aucune requête DB |
| `J(COMPOSITION)/runtime/consumption/v3/dev/V3TechnicalReadParticipant.java` | Probe READ nommé, garde/completion/close/observe, aucune projection métier |
| `J(COMPOSITION)/runtime/consumption/v3/dev/V3FixturePotActorAuthority.java` | Témoin Identity de fixture dev positif/négatif protégé, aucun fournisseur L3 |
| `J(COMMAND)/runtime/command/v3/dev/V3WorkerDevelopmentTools.java` | Main administratif/injection/inspection dev hors Spring ; Flyway existant, cibles neuves et oracle externe |
| `R(COMMAND)/application-v3-consumption-worker-dev.properties` | R-I5-02/05 : exclusions auto, db-health/OSIV off, management loopback ; DEV/cibles autorisées obligatoires sans secret/défaut DB |
| `R(MONOLITH)/application-v3-consumption-worker-dev.properties` | Même isolation/dev-targets et health sans JDBC, flags Legacy/HTTP inactifs (R-I5-02/05) |
| `T(POLL)/orchestrator/poll/consumption/v3/V3ConsumptionWorkerTest.java` | i5_u01…u07 |
| `T(PRIMARY)/infra/persistence/primary/jpa/v3/SlotNavigationPostgresTest.java` | i5_n01…n08, vraies DS WRITE/READ et coûts |
| `T(COMMAND)/runtime/command/v3/V3WorkerPostgresTest.java` | i5_w01…w19, dont H1…H4/D1…D6/acquisition lente post-audit, assemblage actif réel, JVM enfants pour crash |
| `T(COMMAND)/runtime/command/v3/V3WorkerResourcesPostgresTest.java` | i5_r01…r09, artefacts indépendants A/B/C et P1…P7 sur les deux autorités |
| `T(COMMAND)/runtime/command/v3/V3WorkerCompositionPostgresTest.java` | i5_c01…c05, matrice R-I5-05 complète et profils positifs dans les deux racines |
| `T(COMMAND)/runtime/command/v3/V3WorkerMetricsTest.java` | i5_m01/m02, cardinalité/corrélation/observateur défaillant |
| `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3WorkerMonolithPostgresTest.java` | i5_mono01, composition par vraie racine monolithe |
| `scripts/v3/worker-dev.py` | CLI dev §21, prepare/inject-command/inspect/verify et stop du PID vérifié avec deadline externe avant timeout Spring (R-I5-03) ; extraction jar/temp et psql sans secrets/logs |
| `scripts/v3/bootstrap-l2-worker-dev.sql` | Ajout administratif des seuls probes et tables fixture, bases neuves ; appels distincts WRITE/READ avec assertions |
| `scripts/v3/grant-l2-worker-dev.sql` | Droits minimaux fixtures/injecteur et assertions dev ; aucun élargissement DDL/routage runtime |
| `scripts/testing/verify-v3-l2-i5.py` | Collecte fraîche, manifeste/variantes/empreintes/gates et distinction diagnostics |
| `scripts/testing/test_i5_evidence.py` | Refus artefact manquant/skip/doublon/périmé, statut report/fixture et sous-variante absente |
| `docs/v3/L2_I5_Proof_Manifest.json` | Allocation additionnelle/héritage exact, méthodes et preuves par gate ; initialement NOT RUN |
| `docs/v3/L2_I5_Execution_Report.md` | Rapport ultérieur des résultats réellement exécutés, scopes et réserves |
| `docs/v3/L2_I5_Worker_Development_Runbook.md` | Paramètres/commandes/attendus et limites HTTP/Bruno de développement |

**Amendement : aucun fichier CREATE/MODIFY supplémentaire n’est ajouté pour les cinq réserves.** Les responsabilités et variantes supplémentaires se logent dans les fichiers déjà prévus ; aucun port transactionnel/SQL/pool supplémentaire, aucune nouvelle signature publique de recovery. Les records auxiliaires et seams de test restent imbriqués dans les fichiers nommés ; si une séparation concrète devient nécessaire, la justifier et inscrire le chemin avant création, pas créer un framework générique.

### MODIFY — adaptations limitées futures

| Chemin | Delta autorisé par cette conception |
|---|---|
| `J(engine-consumption)/engine/consumption/v3/ConsumptionEngine.java` | Surcharge deux owners/stores, navigation, accès RO de réservation, hooks observation ; préserver corps R1/R9/classification/settle logique |
| Même package, `WorkerCapacity.java` | Accès RO package-private Ticket et hook variations non bloquant ; réservation/release synchronisées inchangées |
| Même package, `DiscoveryEngine.java` | Hooks opérationnels si nécessaires aux frontières de mesure ; aucun changement de ses décisions/preuves de préfixe |
| `J(COMPOSITION)/runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` | Factory active avec ressources contrôlées/deux owners ; conserver anciennes factories de preuve et priorité du classificateur |
| Même package, `V3ConsumptionDiscoveryComposition.java` | Assembler DS contrôle/sourceRead explicites ; accepter seulement probes connus mode dev |
| Même package, `V3BootstrapGuard.java` | R-I5-05 : préflight flags/DEV/cibles/beans et tuples métier/probes, contrôle DB RO puis pools réels ; règles I1/inert préservées |
| `J(COMPOSITION)/runtime/command/v3/V3PotCommandComposition.java` | Surcharge deux pools/owners, construire le même participant/classificateur sur Business |
| `app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaCommandConsumptionWorkerApplication.java` | Import du préflight même hors profil (R-I5-05), création active conditionnelle et exclusion Legacy |
| `app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java` | Même préflight permanent/isolation (R-I5-05), aucun HTTP métier indirect |
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
| I5-0 | Audit indépendant de ce plan, GO humain d’implémentation **distinct** | Reconnaissance Git fraîche/delta, relire normes/policy et inventaire, figer manifeste et paramètres/scopes incluant H1…H4/D1…D6/P1…P7/w19/c05 | Branche/HEAD réels, réserve R-AUD-I3-03 ouverte, manifest NOT RUN, aucun choix transactionnel implicite |
| I5-1 | I5-0 et contrats acquis | Port/navigation sans filtre et tests n01…n08 ; API e inchangée, instrumenter précisément | Pages/UUID/U/préfixe vrai sous PG, aucun nouveau DDL ni changement acquire SQL |
| I5-2 | Navigation correcte | Séparer explicitement owners/stores Business/Control par surcharge ; factories/guards ; r08 | I2/I3/I4 et corrections fraîches passent avec même final owner et deux unités techniques non imbriquées |
| I5-3 | I5-2, quatre pools et inventaire | Superviseur/deux boucles/capacité unique, moniteur L1…L6 et registre monotone ; unitaires, w01…w08/w17/w18/w19 | R-I5-01/03/04 prouvées : pas handle perdu/réinscrit, aucun début après barrière, rotation conservée malgré lenteur |
| I5-4 | Ressources exhaustives identifiées | Renewals singleton/maintenance indépendants et UNKNOWN non réarmé, r01…r09/w09…w11/w17 ; A/B/C et budgets avant campagne | R-I5-02 : **A ∧ B ∧ C WRITE ∧ READ** seuls rendent R-AUD-I3-03 fermable ; sinon finding explicite, pas nouveau pool silencieux |
| I5-5 | Capacité/résolution prouvées | Drain/restart/crash/takeover w12…w14/w18/r09/mono01 ; interleavings L1…L6 et transactions encore physiques | R-I5-03 : aucun début interdit ni stop mensonger, UNKNOWN/deadline gardent DRAINING et claims indépendants |
| I5-6 | Runtime robuste | Dev bootstrap/tools/runbook et préflight complet R-I5-05/c05/mono01, Command/probes w15, m01/m02 et coûts w16/w19/n07 | Démonstration et négatifs d’activation avant activité, mesures de lenteur/R-I5-04, aucun endpoint/projection métier inventé |
| I5-7 | Toutes méthodes et variantes obligatoires | Gel unique, C/R/M/A/F, collecteur/inventaire/revue SQL/archives | Dossier I5 borné complet, cinq réserves tracées aux variantes obligatoires et A/B/C distinctes ; aucun absent/skip, rapports réels/techniques distincts |
| I5-8 | Livraison code+preuves sous GO propre | Audit indépendant contradictoire ; corriger findings et rejouer les preuves affectées | Cinq réserves réauditées et décision de clôture I5 explicite, puis I6/I7/G3 ; aucune autoacceptation L2 |

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
- Réarmer un lot renewal UNKNOWN par délai ou via settlePending : le contrat livré ne qualifie pas cette unité indépendante ; conserver le contexte et drainer (§15), sans nouvelle API de recovery (R-I5-02).
- Nouveau manager de recovery, reçus SQL, état UNKNOWN/DONE persistant, liste durable de clés sautées : I2/I3/les sources suffisent ; aucun invariant nouveau ne justifie ces états.
- Partager une transaction Spring entre threads, REQUIRES_NEW de contrôle à l’intérieur de la finale, DataSource router par phase : viole les guards I2 ; unités explicites successives et final store Business conservés.
- Activer les neuf Consumptions avec no-op/fixture sous leurs UUID métier : fausse capacité livrée et risque de suppression de slots sans transformation réelle ; refus fail-closed.
- Ajouter une API d’injection HTTP ou modifier Bruno pour masquer l’absence L5 : hors I5 ; injection interne dev clairement identifiée.

## 28. Risques résiduels et décisions réellement ouvertes

| Risque | Traitement / limite à conserver |
|---|---|
| R-AUD-I3-03 / R-I5-02 | **Ouverte, NOT RUN** ; A/B/C séparées pour WRITE/READ et r01…r09 requises ; dimensionnement initial inchangé, aucun cinquième/sixième pool préventif. Échec admissible => contre-exemple et révision explicite avant code |
| Blocage de toute relation/panne DB durable/GC long | Pas de garantie perpétuelle de renewal ; expirations/fencing et dégradation diagnostiquées ; aucun optimisme sur UNKNOWN |
| Borrowers oubliés/auto-config historique | R-I5-02 : health JDBC/OSIV off, superviseur/métriques sans SQL, JPA STARTING et maintenance/R9/cession comptées ; preuve A et sessions réelles, pas simple taille/grep |
| Réservation incertaine conservée longtemps | Capacité utile réduite assumée, pending/alertes ; pas de release par délai ; drain peut devenir crash contrôlé |
| Lease expire après scan d’acquisition | e couvre scan et non gains, revisite au tour suivant ; test n05 protège cette nuance ; pas droit final fondé sur partition |
| Retrait local de lease sur SKIP/ACK perdu | Peut perdre une opportunité jusqu’à expiration normale sans perte de travail ; pas métrique takeover fabriquée, claims renouvelés indépendamment ; test w09/r04 |
| Rejets après acquisition | Claims inutilisés attendent expiration, coût de latence à mesurer ; quantum/exécuteur pré-démarré réduisent fréquence ; pas second protocole de release SQL |
| Équité sous acquisition lente / R-I5-04 | Consumption synchrone peut retarder toutes les C ; quantum1 ne borne pas le temps DB. w19 mesure ce retard et la reprise après retour ; pas déplacement dans un autre exécuteur, pas délai absolu sous blocage durable |
| Q nombreuses et COUNT/scan MVCC | Index existants et pages bornées, coût réellement mesuré ; réduire B/cadence si nécessaire, pas promettre O(1)/HOT ni ajouter index sans preuve |
| Catalogue technique dev / R-I5-05 | Préflight permanent, conjonction flags/DEV/cibles et tuples exacts, c05/mono01 zéro activité avant refus ; aucun remplacement métier, aucune confiance dans le seul profil, pas distribution L8 |
| Retours renewal tardifs / R-I5-01 | Un résultat n’inscrit rien, compare incarnation/phase encore actives ; ticket Engine distinct de registre/sortie. Borne des descripteurs corrigée à2×M_W, tickets toujours≤M_W ; H1…H4 |
| Drain concurrent / R-I5-03 | L4 et L5 sur même moniteur, submit/shutdown/SQL hors lock, nettoyage univoque ; acceptation avant drain insuffisante, D1…D6 et callbacks réels |
| Timeout renewal physique UNKNOWN / R-I5-02 | Voie/contexte non réattribués, DRAINING et autres claims encore servis ; pas de nouveau règlement physique. Drain peut nécessiter terminaison externe même après résolution DB vue seulement du banc ; disponibilité dégradée explicitement bornée par ce périmètre |
| Metrics sous ACK perdu | Séries confirmées peuvent sous-compter les transitions physiques ; SQL extérieur arbitre ; pas de tokens dans logs/labels |
| Couverture métier différée | Aucun PASS global CON/EVT/OPS/PERF/REC sur fixtures ; même un full Maven vert ne livre pas L3–L8 |

**Position vis-à-vis de l’audit : aucune réserve contestée.** Deux omissions transversales sont explicitées plutôt que masquées : la borne exacte du nombre de descripteurs n’est pas celle des tickets (§7), et les health JDBC automatiques seraient des emprunteurs hors inventaire (§14). Le cas d’un renewal encore physique après retour local n’a pas de règlement dédié dans I3 ; le choix minimal est la dégradation/drain §15, pas une extension transactionnelle implicite. Aucun invariant normatif contraire n’est identifié ; leur vérification reste NOT RUN. Aucun arbitrage humain de **conception ordinaire** n’est identifié comme bloquant pour rédiger ce plan : choix de pools/quantum/ports/profil résolus ci-dessus et soumis à audit. Restent bloquants pour **implémenter** l’audit contradictoire puis le GO explicite demandé par le mandat, et pour **certifier** les preuves NOT RUN, mesures/config effectives et résolution de findings. Une contradiction normative nouvelle devra revenir à son autorité ; ce risque n’est pas une question de préférence à renvoyer préventivement au responsable.

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

**Clôture future de l’implémentation** exige : deux boucles effectives simultanées, équité C/p/slots prouvée avec M_W1 et capacités distinctes, Engine unique owner des tickets/claims/cession, aucun franchissement fictif e/U, quatre pools/transactions réellement raccordés et réserve R-AUD-I3-03 prouvée par A ∧ B ∧ C séparées sur les deux autorités, renewals claim après perte de partition, résolution UNKNOWN physique conservative, drain/restart/crash/takeover sans perte/double effet/purge/Q réactivée, Command réel et probes explicitement techniques, activation fail-closed dans les deux racines, métriques vérifiées et coûts documentés. Toutes méthodes/variantes/manifeste/héritages frais et gates C/R/M/A/F au même gel, archives/empreintes/commandes et rapports bornés, audit indépendant et corrections vérifiées. **R-I5-01 à R-I5-05 exigent aussi** toutes variantes H1…H4/D1…D6/P1…P7 et les oracles w19/c05 : aucun retour tardif ne réinscrit un handle, aucun début non L4 avant L5, aucun lot renewal UNKNOWN réattribué, aucune garantie temporelle fabriquée et aucune activation hors conjonction validée. Le cas de mise en sécurité par drain reste distinct d’une preuve de renouvellement à échéance ; il ne suffit pas pour fermer R-AUD-I3-03. Aucun test absent/skip obligatoire/fixture sur métier absent ne permet une clôture globale ; L2 reste gouverné par son programme et sa décision humaine.

**Livraison documentaire présente** : uniquement ce fichier, revue des normes R1–R10 et des contrats code réels ; références/chemins, signatures existantes vs proposées, disponibilité des neuf, ownership/limites des sources et fixtures, absence de duplication des tickets/protocoles, cohérence SQL/index/MVCC et tables existantes, matrices de risques/oracles/gates, reports et absence de code I5 contrôlés. Les commandes réellement exécutées se limitent aux lectures/recherches Git/rg/cat/sed, analyses statiques Python (liens/chemins/POM/manifestes/signatures/inventaire du document), `git diff --check` et opérations Git de publication autorisées. Aucune commande Maven, PostgreSQL ou Docker ; DB NONE, slices présentes NONE, aucun crossing applicatif, gate global non requis pour ce mandat, full reactor non exécuté car documentaire. Les gates futurs de ce document ne sont pas des commandes exécutées.

Publication : seul `docs/v3/L2_I5_Implementation_Plan.md` indexé/committé, distant reconfirmé avant push normal ; reçu final fournit SHA initial/final, commit/branche, divergence distante et propreté du worktree, sans réécrire les autres rapports. Si un contrôle révèle un delta concurrent, analyser avant publication. Contrôles historiques de la livraison initiale (conservés comme tels) :30 sections numérotées,27 liens relatifs résolus, chemins Java MODIFY (y compris « même package ») et expansions REUSE vérifiés, tous chemins CREATE constatés absents, méthodes publiques Engine existantes et absence des extensions proposées vérifiées ; manifestes JSON et arêtes POM parsés. `python3 -B docs/v3/check_l2_manifest.py` retourne253 identifiants normatifs,380 lignes de preuve,324 obligations L2,56 reports,33 oracles,0 orphelin,826 liens et63 ancres. Les tableaux couvrent séparément les12 variantes C03 et11 clauses R10 ; code fences équilibrés et whitespace contrôlé. Aucun de ces contrôles n’est une exécution applicative. Les contrôles sémantiques ici sont ceux de l’auteur, **pas** l’audit indépendant contradictoire à venir.

**Amendement post-audit présent :** seul ce plan est modifié. Contrôles frais : comparaison Git avec la référence2fb6fca7, relecture des normes/contrats concernés, traçabilité des cinq réserves et vérification inverse des variantes nouvelles, maintien des30 sections et des allocations antérieures, chemins/signatures/liens, absence de fichier hors périmètre, `git diff --check` et vérificateur documentaire du manifeste. Les contrôles frais ont notamment confirmé30 sections,27 liens,44 identifiants de lignes d’allocation antérieurs conservés, blocs d’API existante et listes de cibles CREATE/MODIFY inchangés ; chemins vérifiés :30 CREATE absents,23 MODIFY existants et27 expansions REUSE, sans créer ces fichiers. Le vérificateur de manifeste conserve253 IDs/380 lignes/56 reports/33 oracles/0 orphelin. Les autres résultats chiffrés frais sont consignés au reçu de livraison ; aucune trace/Maven/PostgreSQL future n’est produite. Aucun désaccord avec l’audit, aucune preuve physique ni GO déduit de ces amendements. Les fichiers CREATE/MODIFY restent prospectifs ; aucune classe/SQL/configuration/test n’a été créé ou changé.

**Point d’arrêt : I5 AMENDED DESIGN — READY FOR INDEPENDENT RE-AUDIT — IMPLEMENTATION NOT AUTHORIZED.**


**Suivi d’implémentation sous GO humain distinct (28–29 octobre2026).** Le statut de mandat documentaire ci-dessus est historique. Le GO « OK tu as le go pour implémenter ce plan » a autorisé l’exécution ; [rapport courant](L2_I5_Execution_Report.md), dont le finding physique I5-F01. Clarification de sûreté aux §§12/16/17/22 : une préparation READ UNKNOWN n’a pas nécessairement de verrou de slot ; son ticket ne peut être réglé par la seule présence déverrouillée du slot. Le handle Engine conserve un flag local monotone non qualifiable par les ports actuels, le Worker draine, et seul un redémarrage abandonne cette mémoire locale. Aucun état durable/owner/pool/protocole SQL ajouté ; le cas COMMITTED physique connu reste inspectable. Les variantes w13 WRITE/READ et les assertions I3/unitaires renforcées sont des preuves additionnelles obligatoires du manifeste I5. Cette correction d’implémentation ne vaut ni clôture I5 ni audit indépendant, et ne réécrit pas les normes ou les preuves historiques.


**Clarification d’implémentation I5-F02 (29 octobre2026), §§14–15/22/28/30.** Un lot déjà servi peut redevenir dû avant la fin du tour ; choisir toujours le premier couple dû ne prouve donc pas la progression de tous les leases. Le service existant choisit l’âge de renouvellement local le plus ancien, puis le même ordre dans son lot borné. Cet âge décide seulement d’une opportunité ; PostgreSQL conserve seul la validité/propriété. Aucun nouveau scheduler, état durable, pool, owner ou file de priorité. Le calcul initial en lots théoriques pleins reste un filtre de configuration nécessaire, **pas une garantie physique**. La qualification C compte conservativement les clés candidates par autorité, y compris les lots partiels, mesure le chemin complet, inclut polling/jitter et vérifie toutes les échéances PostgreSQL. `i5_u08` et `i5_r10` sont additionnels, avec contre-exemples négatifs conservés ; r05 exige désormais les douze renouvellements de leases en plus des claims. Leur statut réel et les limites restent ceux du rapport/manifeste, sans autofermeture de R-AUD-I3-03.
