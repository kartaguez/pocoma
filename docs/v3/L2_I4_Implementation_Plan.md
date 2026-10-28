# Pocoma V3 — L2 / I4 — Plan d’implémentation Discovery

**PROPOSED — NOT AUDITED — NOT AUTHORIZED FOR IMPLEMENTATION.**

28 octobre 2026. Ce document propose une réalisation ; ses signatures, SQL, méthodes et gates futurs ne sont ni du code livré ni des preuves exécutées. **I1/I2/I3 : CLOSED / LOCAL BOUNDED PASS ; L2 global : INCONCLUSIVE, non ACCEPTED ; G1/G2/G3 non franchis.** Audit indépendant du présent plan puis GO humain explicite requis avant développement. Aucune activation production.

## 1. Mandat, baseline Git et vérification présente

Mandat exclusivement documentaire : analyser le référentiel et le code après I3, concevoir Discovery, publier ce plan puis s’arrêter. Aucun fichier productif, test Java, POM, migration, privilège effectif ou runtime n’est modifié par sa préparation.

| Objet contrôlé avant écriture | Constat |
|---|---|
| Dépôt / destination exclusive | `https://github.com/kartaguez/pocoma.git`, `feat/v3-architecture` |
| SHA de référence et véritable tête distante après fetch | `798eeb218fb65fbfe489433d2b97caab8dd412d1` ; identité confirmée, aucun delta à analyser depuis la référence |
| Checkout principal préservé | `/Users/julien.guezennec/Dev/projects/pocoma`, branche `feat/runtime-monolith`, HEAD `90a3e9bc1a1fdd325d0c36e543824239cd85a431` ; modifications préexistantes Keycloak, documentation, Bruno, test PostgreSQL et fichiers non suivis |
| Worktree documentaire choisi | `/private/tmp/pocoma-v3-postaud`, déjà sur la branche demandée, propre ; initialement `f528586c94ee7e1b0574e9ff9b108781b0e8090f`, divergence local/distant `0/24` |
| Mise à niveau | `git merge --ff-only origin/feat/v3-architecture` : avance rapide à `798eeb21`, sans fusion ni transfert de travaux ; HEAD propre avant création du plan |
| Autres worktrees | Inventoriés par `git worktree list --porcelain` ; aucun déplacé, nettoyé, supprimé ou modifié |
| Fichier cible | Absent avant création : aucun document préexistant écrasé |

Commandes Git de reconnaissance : `git status --short --branch`, `git branch --show-current`, `git rev-parse HEAD`, `git worktree list --porcelain`, `git remote -v`, `git fetch origin feat/v3-architecture`, `git rev-parse origin/feat/v3-architecture`, `git rev-list --left-right --count HEAD...origin/feat/v3-architecture`, `git log --oneline 798eeb218fb65fbfe489433d2b97caab8dd412d1..origin/feat/v3-architecture`, `git diff --stat 798eeb218fb65fbfe489433d2b97caab8dd412d1 origin/feat/v3-architecture`. L’avance rapide a nécessité l’accès au répertoire Git partagé protégé ; elle ne change aucun travail local non committé.

**Verification scope de ce mandat :** primaire NONE/documentation ; secondaires NONE ; DB NONE ; impact `app/` interdit ; aucun franchissement inattendu ; architecture globale NOT REQUIRED ; full reactor NOT REQUIRED. **Commandes Maven exécutées : AUCUNE.** Aucun bootstrap, SQL de mutation ou essai PostgreSQL. Lectures de fichiers, recherche `rg`, calcul indépendant SHA-256 Python et analyse XML statique des POM uniquement. Les contrôles documentaires de livraison sont consignés au §23 ; ils ne certifient aucun mécanisme I4.

Le SHA de publication est fourni dans le reçu Git final, sans auto-inscription circulaire dans son propre commit. L’arbre productif analysé reste exactement celui de `798eeb21`.

## 2. Références et hiérarchie des décisions

Sources directement consultées dans le dépôt, et à relire avant réalisation :

| Autorité / sources | Application |
|---|---|
| [Canon](V3_Canon_and_Roadmap.md), [plan directeur](V3_Implementation_Master_Plan.md) | Simplicité, programme, limites des lots, acceptations humaines et arrêt ; le plan directeur reste le registre de progression |
| [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-05](SPEC-05_Consumption_Discovery.md) | Sept familles, demandes durables, publications denses, références, K global, slots, autorités et indépendance |
| [SPEC-07](SPEC-07_Transverse_Requirements.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md) | Exigences/oracles, métrologie, limites des budgets, variantes CON/EVT, certificats bornés |
| [Plan L2](L2_Design_and_Verification_Plan.md), [registre A01–A12](L2_Decision_Register.md), [consolidation R1–R10](L2_R1_R10_Normative_Consolidation.md) | Décisions humaines ; R1–R10 remplacent les recommandations ouvertes antérieures ; R3 amende A03 à quatre tables |
| [Oracles indépendants](L2_Independent_Test_Oracles.md), [couverture L2](L2_Certification_Coverage_Manifest.md) | Paramètres préinscrits, snapshots extérieurs, allocations et reports ; conserver les 380 lignes et leurs identifiants |
| [Plan I1 et clôture](L2_I1_G0_Readiness_and_Implementation_Plan.md), [plan I2](L2_I2_Implementation_Plan.md), [rapport I2](L2_I2_Execution_Report.md) | UUID/P, bootstrap inerte, propriétaire physique, R1/R9 et garanties transférées |
| [Plan I3](L2_I3_Implementation_Plan.md), [rapport I3](L2_I3_Execution_Report.md), [corrections post-audit](L2_I3_Post_Audit_Corrections_Report.md) | Leases/claims/capacité, cession/admin/droits, corrections R-AUD-I3-01/02 ; R-AUD-I3-03 reste I5 INCONCLUSIVE |
| [Contrat L1-C](L1_C_Transaction_Contract.md), [clôture F](L1_F_Closure_Report.md), [clôture F2](L1_F2_Closure_Report.md), [matrice F2](L1_F2_Invariant_Matrix.md) | Ordre des protections, unité physique, poisoning, suffixe, intention immuable, préconditions WRITE locales |
| [Politique du réacteur](../testing/Reactor_Verification_Policy.md), `AGENTS.md` | Slices déclarées avant code, gates proportionnés, CURRENT distinct de HISTORICAL |
| [Preuves I1](../testing/evidence/L2-I1/Gate-index.json), [I2](L2_I2_Proof_Manifest.json), [I3](L2_I3_Proof_Manifest.json), [post-audit I3](L2_I3_Post_Audit_Proof_Manifest.json) | Preuves héritées avec leurs SHA, sujets et limites ; jamais des résultats frais I4 |

Les SPEC portent les invariants ; A/R les réalisations conceptuellement ratifiées ; les rapports datés attestent les livraisons. Une proposition de ce plan ne remplace aucune norme. Les anciennes recommandations de la [revue contradictoire](L2_Contradictory_Review.md) sont supplantées lorsqu’un R les tranche. Les corrections et le [contre-audit G0](L2_Independent_Counter_Audit_and_G0_Assessment.md) expliquent la consolidation ; aucun avis historique retiré ne redevient une autorité.

**Statuts historiques à ne pas appliquer comme état actuel :** en-tête du canon et SPEC-05 annonçant I2 non implémenté ; §18 du canon et fin de consolidation R1–R10 indiquant DESIGN PENDING/G0 NON VALIDÉ ; paragraphes de préparation I2/I3 interdisant leur implémentation ; colonnes prospectives du manifeste ; plan directeur annonçant encore audit I3 à venir. Ils sont suivis des mandats/clôtures datés et du rapport post-audit I3 à `798eeb21`. Les anciens « aucun test V3 exécuté » des SPEC décrivent leur rédaction conceptuelle. Leurs **oracles** restent normatifs. Aucun de ces changements de statut ne vaut autorisation I4.

Les anciens plans A2/A2-1 et `docs/steps/current/DISCOVERY/` ne gouvernent ni les slots V3 ni une migration V2. Le contrat L1-C nomme encore l’ancien owner Pot et le slot dédié dans son corps historique : I2/I3 les ont remplacés ; ses invariants C/F/F2 survivent, pas ces propriétaires.

## 3. Périmètre exact et exclusions

I4 proposé : primitives directement invocables de préparation des références, admission transactionnelle idempotente, fenêtre et checkpoint non-burnables, parcours circulaire burnable volatil ; softCap et fencing de Discovery ; scripts de droits ciblés ; fabrique explicite inactive ; preuves locales PostgreSQL.

I4 réutilise l’acquisition, les renewals, les tentatives, la cession, la quarantaine, la réactivation et le recovery I2/I3. Une erreur Discovery ne possède aucun claim et **ne consomme jamais N**. Discovery ne charge ni intention/payload métier ni histoire Pot et ne choisit pas ses sources en fonction des claims/retryAfter.

Exclus : les deux boucles I5, leur cadence adaptative, le round-robin intégré, exécuteurs/gestion complète des ressources Worker, projections L4, autorité Identity L3, Supras/HTTP/consentement/invitation complète L5, reconstruction READ métier/READY, distribution/activation. Un harnais manuel peut appeler Discovery puis les primitives I3/I2 réelles ; cela n’implémente pas le Worker I5.

## 4. Catalogue complet et disponibilité réelle

Toutes les identités ci-dessous existent dans `V3ConsumptionCatalogConfiguration.definitions()` et `scripts/v3/bootstrap-l2-routing.sql`. P=4, `sha256-v1`, autorités fixes. Les libellés de métier ne désignent pas nécessairement des classes Java présentes.

| Consumption de référence / UUID | Famille | Source disponible après I3 / preuve I4 | Effets et report |
|---|---|---|---|
| Exécution Command — `98f7268d-d340-581c-b83d-010926223e14` | COMMAND | `v3_command`, PENDING/TERMINAL ; admission interne SQL de test durable sans slot ; vrai participant `ExecutePotCommand` | WRITE réel ; admission HTTP L5, intégration Worker I5 |
| Exécution IdentityRequest — `2999e75c-272d-5afe-a966-c9364ef021fe` | IDENTITY_REQUEST | Aucune source V3 métier livrée ; table de demandes **de fixture** PENDING/TERMINAL, clé UUID et verrous réels | WRITE technique ; véritable producteur/autorité L3 |
| MaterializePotVersion — `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | POT_EVENT | `v3_pot_event` + `v3_event_position(family='POT')`, producteurs Command réels | READ ; métier L4 |
| MaterializePotBalance — `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | POT_EVENT | Même source, Discovery et K propres | READ ; métier L4, pas de Balance(D) |
| MaterializePotVisibility — `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | POT_EVENT | Même source, Discovery et K propres | READ ; métier L4 |
| MaterializeInvitation — `924cd9e3-435d-5804-9d1e-c6aecadb9ab4` | INVITATION_EVENT | Pas de journal/compteur V3 réel ; publication dense de fixture uniquement | READ ; producteur/parcours réel L5 |
| MaterializeCurrentBinding — `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | IDENTITY_EVENT | Publication dense de fixture ; filtre **EXTERNAL_USER_IDENTITY** ; POCOMA_USER examiné sans slot | READ ; producteur L3, projection L4 |
| MaterializeCommandResult — `63664303-d33e-5d42-8cce-c4dae8023311` | COMMAND_COMPLETION_EVENT | `v3_command_completion_event` + positions COMMAND_COMPLETION ; producteur réel I2 | READ ; résultat métier L4 |
| MaterializeIdentityRequestResult — `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | IDENTITY_COMPLETION_EVENT | Journal/compteur de fixture, pas de producteur V3 réel | READ ; L3 puis L4 |

Sept familles = deux burnables + cinq événementielles ; neuf Consumptions = deux WRITE + sept READ. Le banc READ a ses propres définitions techniques et effets immuables, **pas une dixième Consumption métier**. Les neuf projections logiques ne sont pas neuf Consumptions READ.

`v3_event_counter` n’accepte actuellement que POT et COMMAND_COMPLETION. I4 ne l’élargit pas pour inventer trois producteurs. Les fixtures des autres familles utilisent leurs propres tables de test et les mêmes exigences de publication dense. Les ports et l’orchestration couvrent les sept familles ; seuls Command/POT/COMMAND_COMPLETION reçoivent des adaptateurs de sources productives réelles dans I4. Une composition demandant une autre source réelle absente est refusée explicitement, jamais redirigée vers une table historique.

## 5. Inventaire contradictoire du code, propriétaires et garanties

Conventions de chemins : `J(m)=app/<m>/src/main/java/com/kartaguez/pocoma/`, `T(m)=app/<m>/src/test/java/com/kartaguez/pocoma/`. Chaque chemin relatif au préfixe indiqué ci-dessous existe à la baseline. « Testé » désigne les preuves héritées, non une réexécution présente.

| Composant / chemin réel | Rôle, owner transactionnel et activation | Dépendances / garanties héritées | Lacune et décision I4 |
|---|---|---|---|
| J(domain-consumption)`domain/consumption/v3/{ConsumptionDefinition,ConsumptionId,ConsumableFamily,RoutingV1}.java` | Valeurs pures ; aucun owner/SQL ; définitions inertes | Java seul ; sept noms exacts, SHA UUID natif, int32 signé/floorMod, autorités/P=4 ; vecteurs I1 | REUSE sans nouveau Locator/workId ; ne pas changer UUID/P |
| J(engine-consumption)`engine/consumption/v3/ConsumptionEngine.java` | Orchestration lifecycle ; seul demandeur des unités de tentative I2/I3, sans polling | domain-consumption/port-transaction ; handles/tickets, R1/R9, classification corrigée | REUSE **sans insérer Discovery dans execute/acquire/settlePending** ; nouvel Engine Discovery indépendant |
| J(engine-consumption)`engine/consumption/v3/port/PartitionOwnershipStore.java` | acquire/renew/protectAcquisition/bound ; pas d’owner physique | Définition et PartitionLease ; garde routage + lease | REUSE `protectAcquisition` pour la protection des nouveaux travaux Discovery ; sens documenté, pas d’alias de forwarding |
| J(engine-consumption)`engine/consumption/v3/port/ConsumptionSlotStore.java` | acquire/renew/cede/inspect/protectFinal/delete/observe | I2/I3 : heure après lock, token, non-purge et inspection prudente | REUSE inchangé ; admission nouvelle dans un port distinct, aucun deuxième lifecycle |
| J(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/consumption/JdbcPartitionOwnershipStore.java` | SQL technique sous `AttemptTransactions.write` appelé par Engine ; aucun begin/commit propre | JdbcTemplate, garde Spring ; SELECT routage, FOR UPDATE SKIP LOCKED partition, clock séparée, timeouts PG17 | REUSE ; retourné protégé dans **la même unité** que admission ; fraîcheur recontrôlée après toute attente admission |
| J(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java` | Seul adaptateur lifecycle, réutilisé aussi en READ par injection | Aucun SQL métier ; mêmes gardes, rowcounts et tests WRITE/READ | REUSE ; aucun accès Discovery aux champs claim/retry/count pour sélectionner les sources |
| J(port-transaction)`port/transaction/v3/{AttemptTransactions,RecoveryReadContext}.java` ; J(infra-tx-spring)`infra/tx/spring/v3/SpringAttemptTransactions.java` | Port unique ; Spring possède résolution physique ; RR réellement RO / RC RW ; inactif hors construction explicite | Holders + synchronization, poison JPA/JDBC, refus ambiance/nesting/REQUIRES_NEW ; Failure distingue trois résolutions | REUSE intégral ; transactions techniques n’exigent pas fermeture de publication ; aucun owner Discovery supplémentaire |
| J(engine-write-pot)`engine/write/pot/v3/ExecutePotCommand.java` | Participant Command, préparations et effets dans unités Engine ; aucune Discovery | Faits/autorité et store ; R1 avant suffixe, INVALID_BINDING sans Pot | REUSE ; aucune modification de finalisation pour faciliter l’admission |
| J(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/JdbcPotCommandStore.java` | load sans verrou de source explicite ; finishTerminal insère résultat **puis** CAS status ; publish densifie | FK résultat→source ; v3_command_intention ; événements/contraintes/compteurs I2 ; C/F/F2 | REUSE ; l’analyse doit inclure FK KEY SHARE et UPDATE de source, pas imaginer un FOR UPDATE absent |
| J(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/{JdbcPotCommandObservation,JdbcPotRevisionAdapter,JdbcPotHistoryAdapter,PotHistoryRows}.java` | Observations RO, writes gardés, histoire directe ; owner I2/caller B autorisé | R9 cohérent, xid8, préconditions F2, huit composantes, audit explicite | REUSE inchangés ; aucune lecture de chaîne ni réparation |
| J(composition-runtime-spring)`runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` | Fabrique statique inactive, injecte owner partagé et stores ; classification post-audit | Guard des vraies sources, M_W+4 déclaré ; taille pool ≠ réservation | ADAPT validation explicite du profil de droits combiné (§13), pas le classificateur ni les renewals |
| J(composition-runtime-spring)`runtime/command/v3/V3PotCommandComposition.java` | Fabrique statique du vrai participant/Engine ; crée actuellement son owner WRITE | Même source/JpaTransactionManager ; aucun bean actif | ADAPT injection explicite de l’instance owner partagée si assemblage conjoint ; conserver chemin strict I3 et ses tests |
| J(composition-runtime-spring)`runtime/consumption/v3/{V3BootstrapGuard,V3ConsumptionCatalogConfiguration}.java` | Inspection deux bases/routes/droits, catalogue neuf ; profil `v3-consumption-pilot` inerte | Reject des engines/infra/pollers actifs, 8/28 lignes ownership, memberships/colonnes | ADAPT nouveau contrôle Discovery explicite ; **validate() I1 reste strict** ; catalogue conservé |
| J(engine-consume-command)`engine/consume/command/port/out/CommandConsumptionDiscoveryPort.java`, `discovery/{CommandDiscoveryCursor,CommandConsumptionCandidate}.java` ; J(PRIMARY)`infra/persistence/primary/jpa/adapter/command/JpaCommandConsumptionDiscoveryAdapter.java`, `repository/command/JpaCommandConsumptionDiscoveryRepository.java` | Parcours historique recorded_commands, heure applicative, jointures claims/slots ; @Transactional propre et scan legacy | Types historiques, clé submitted_at/command_id, slotId et status PENDING historique ; tests V2 | REPLACE pour le parcours V3 ; RETIRE du raccord pilote déjà isolé ; retrait physique global DEFER L7 |
| J(PRIMARY)`infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java` | Business_event_outbox→projection tasks ; sélection par recorded_at et DONE ; transaction historique | Routes EventType/ProjectionType, WorkerSegment, hash Pot ; tests historiques | Incompatible avec K dense et trois Consumptions directes : DEFER fichier historique, aucun réemploi de protocole |
| J(PRIMARY)`infra/persistence/primary/jpa/adapter/processing/event/JpaLatestKnownVersionEventDiscoveryAdapter.java`, `repository/consumption/JpaLatestKnownVersionEventDiscoveryRepository.java` | LKV historique, pages 128 puis filtre segment ; boucle locale et @Transactional | Outbox/claims, hash Pot ; pas K V3 | DEFER ; le nom Discovery ne prouve pas R4/R3 |
| J(PRIMARY)`infra/persistence/primary/jpa/adapter/identity/JdbcBindingFactDiscoveryAdapter.java` | external_identity_binding_facts, clé issuer/subject/revision ; claims/retry historiques | Engine CurrentBinding V2 ; pas séquence IDENTITY V3 | DEFER L3/L4/L7 ; pas d’adaptateur fictif EUI V3 |
| J(PRIMARY)`infra/persistence/primary/jpa/adapter/registration/{JdbcRegistrationDiscovery,JdbcRegistrationResultDiscovery}.java` | registration_requests/outcomes ; clé temporelle et hashtext ; ancienne famille REGISTRATION | Ports registration, consumption_slots/claims ; managers historiques | DEFER ; ne certifie pas IDENTITY_REQUEST/COMPLETION V3 |
| J(PRIMARY)`infra/persistence/primary/jpa/adapter/command/JdbcCommandResultSource.java` ; J(engine-materialize-command-result)`engine/materialize/commandresult/CommandResultDiscovery.java` | command_terminal_events/outcomes/recorded_commands ; reload payload séparé | Famille historique COMMAND_TERMINAL_EVENT ; jointures claim ; état FAILED historique | DEFER L4/L7 ; aucune traduction de famille, source réelle I4 = v3_command_completion_event |
| `app/infra-persistence-read-jdbc/` | Schéma L2 READ réel ; adaptateurs READ métier historiques hors pilote ; aucun adaptateur V3 Discovery après I3 | Migrations READ, tests fondation/ACL ; pas dépendance productive vers PRIMARY | REUSE schéma ; test ACL I4 local ; adapter admission commun injecté côté READ comme I3, aucun POM inversé |
| `app/runtime-command-consumption-worker/`, `app/runtime-monolith/` | Racines importent Legacy sous `!v3-consumption-pilot`, catalogue sous profil inerte ; pas Worker V3 actif | Command et monolithe testent factories/isolation ; legacy peut fonctionner sur **ses** bases | REUSE racines/propriétés ; ADAPT tests d’inactivité et de factory uniquement |
| `app/architecture-tests/` | Règles globales, pureté, writers sans owner/connexion brute, handle unique et pas scheduler | `V3ConsumptionEngineArchitectureTest`, `V3ConsumptionFoundationArchitectureTest`, `V3FoundationArchitectureTest` et négatifs | ADAPT périmètre des nouveaux ports/adaptateurs et exemples négatifs précis ; aucune exemption globale |

PRIMARY dans les chemins ci-dessus signifie le module `infra-persistence-primary-jpa` (aucune nouvelle arborescence). Inventaire durable complémentaire : WRITE `db/v3-pot/migration/V1__pot_components.sql`, `V3__point_history_indexes.sql`, `V4__write_effect_indexes.sql` ; exécution `db/v3-pot-execution/migration/V2__command_execution.sql` ; L2 WRITE `db/v3-consumption-write/migration/V5__consumption_foundation.sql` ; READ `db/v3-consumption-read/migration/V1__consumption_foundation.sql`, sous `app/<module>/src/main/resources/`. Les migrations historiques PRIMARY V1–V31 et READ V1–V14 restent isolées. `v3_command_slot` est retirée par V5 ; aucune copie n’est autorisée.

## 6. Matrice de réemploi et unicité des propriétaires

| Décision | Éléments | Propriétaire final |
|---|---|---|
| REUSE | Domaine/identités/routage, quatre tables et index, owner Spring, PartitionOwnershipStore, lifecycle/participants/observation I2/I3, proxy ACK | Propriétaires existants inchangés |
| ADAPT | Gardes des droits/inactivité, factories explicites, architecture et collecte | Composition vérifie ; aucun nouveau owner physique |
| CREATE, **seulement après GO** | DiscoveryEngine, ports de références/admission, adaptateurs source Command et deux événements réels, admission SQL commune, script de grants, preuves | Discovery seul insère les slots initiaux et fait progresser K |
| REPLACE / RETIRE du parcours V3 | Tous les anciens Discovery/Locator/owners slotId/DONE/claims séparés | Déjà exclus du pilote ; aucune double écriture ; retrait physique L7 |
| DEFER | Producteurs Identity/Invitation, projections, HTTP/Bruno métier, boucles, ressources intégrées, reconstruction/distribution | L3/L4/L5/I5/L6/L8 |

Unicité d’écriture : bootstrap/admin→contrats et lignes ownership initiales ; lifecycle I3→lease/claim/retry/quarantaine ; Discovery→INSERT slot initial et K ; participant→métier/résultat/publication ; completion Engine→DELETE atomique ; admin réactivation→CAS ciblé sur même slot. Aucun cache de ces faits, journal DONE, checkpoint de complétion, compteur durable de capacité ou réservation durable de fenêtre.

## 7. Architecture proposée et alternatives

**Proposition soumise à audit :** un `DiscoveryEngine` dans le module existant `engine-consumption`, indépendant de `ConsumptionEngine`, trois ports substantiels : références événementielles, demandes burnables, admission dans l’autorité des effets. Les deux premiers expriment des opérations différentes (préfixe immuable / état mutable sous protection) ; le troisième porte le stockage K/stock/INSERT/CAS sans ouvrir de transaction. Le même `AttemptTransactions` que I2/I3 ouvre les unités. Discovery orchestre la protection ownership existante ; l’adaptateur d’admission ne prend pas clandestinement un lease.

Deux adaptateurs de sources PRIMARY : Command réel et publications POT/COMMAND_COMPLETION réelles. Un seul adaptateur JDBC d’admission utilisable en WRITE et READ par datasource injectée, comme le store de slots I3. Son emplacement PRIMARY n’autorise aucun appel READ→PRIMARY Maven : la composition possède déjà les deux infrastructures. Les sources absentes sont implémentées **en test** derrière les ports ; pas de table générique de payloads productive.

| Alternative | Analyse et décision proposée |
|---|---|
| Ajouter discover à ConsumptionEngine | Mélange préparation des sources, progression K et lifecycle ; grossit le propriétaire déjà sensible de réservations/recovery. Écarté : coordination par slots suffit |
| Un adaptateur monolithique propriétaire de tout le protocole | Moins de types mais cache softCap/préfixe/PENDING/transactions à l’application ; mauvais tests et doubles owners possibles. Écarté |
| Deux nouveaux modules/engines, framework Sources/locking générique | Arêtes et abstractions sans besoin ; écarté. Un Engine avec deux opérations typées suffit |
| Trois ports + une admission JDBC commune | Retenu comme proposition : chaque port a une garantie vérifiable, aucun port de simple forwarding ; pas de duplication du SQL lifecycle |
| Réutiliser les anciens Discovery | Refusé : claims consultés, DONE, sources/familles/routage incompatibles |

La composition est une factory statique, sans `@Component`, bean Engine, scan, runner, `@Scheduled` ou listener. Les tests la construisent explicitement. Aucun changement des imports actifs des runtimes ni nouveau profil Worker.

## 8. Interfaces Java envisagées, préconditions et postconditions

**Tous les types de ce bloc sont proposés et absents à la baseline**, sauf les types I1/I2/I3 importés : ConsumptionDefinition/Id, PartitionLease, AttemptTransactions, RecoveryReadContext, PartitionOwnershipStore. Imports usuels `java.util.*`, `java.time.Duration`, `java.util.function.Predicate`. Packages proposés `engine.consumption.v3` et `.port`. Les données auxiliaires restent imbriquées dans leur propriétaire, sans fichier utilitaire par valeur.

```java
interface NonBurnableReferences {
    ConsumableFamily family(); // famille fixe de cette source
    record Reference(long position, UUID eventId, String subjectType) {}
    record Window(long afterExclusive, long highInclusive,
                  List<Reference> references) {}
    Window window(long afterExclusive, int maxPositions, RecoveryReadContext read);
}

interface BurnableReferences {
    ConsumableFamily family(); // COMMAND ou IDENTITY_REQUEST uniquement
    Optional<UUID> upperPending(RecoveryReadContext read);
    List<UUID> pendingPage(Optional<UUID> afterExclusive, UUID upperInclusive,
                           int pageSize, RecoveryReadContext read);
    boolean tryProtectPending(UUID requestId); // RC RW, FOR UPDATE SKIP LOCKED
}

interface DiscoveryAdmissionStore {
    long checkpoint(ConsumptionId id, int partition, RecoveryReadContext read);
    long currentCheckpoint(PartitionLease protectedLease); // RC, absent = 0
    long liveSlots(PartitionLease protectedLease);          // non-QUARANTINED
    boolean contains(ConsumptionDefinition definition, int partition, UUID id);
    void insertInitial(ConsumptionDefinition definition, int partition, UUID id);
    void advanceCheckpoint(PartitionLease protectedLease, long expected, long next);
}

final class DiscoveryEngine {
    record Settings(int windowSize, int pageSize, int insertBatchSize,
                    long softCap, Duration lockTimeout, Duration statementTimeout) {}
    record RequestWalk(Optional<UUID> afterExclusive, Optional<UUID> upperInclusive) {}
    enum Status { COMMITTED, BUSY_OR_NOT_OWNER, STALE_CHECKPOINT, CAPACITY }
    record EventProgress(Status status, OptionalLong checkpoint, int examined, int inserted) {}
    record RequestProgress(Status status, RequestWalk next, int examined,
                           int inserted, boolean tourFinished) {}

    DiscoveryEngine(ConsumptionDefinition definition, AttemptTransactions effects,
        PartitionOwnershipStore ownership, DiscoveryAdmissionStore admissions,
        Settings settings, AttemptTransactions sourceReads,
        NonBurnableReferences source, Predicate<NonBurnableReferences.Reference> relevant);
    DiscoveryEngine(ConsumptionDefinition definition, AttemptTransactions effects,
        PartitionOwnershipStore ownership, DiscoveryAdmissionStore admissions,
        Settings settings, BurnableReferences source);

    EventProgress discoverEvents(PartitionLease lease);
    RequestProgress discoverRequests(PartitionLease lease, RequestWalk walk);
}
```

Les deux constructeurs sont deux assemblages typés du même Engine, pas deux protocoles lifecycle ; une opération du mauvais genre est refusée avant SQL. Définition/source/famille/autorité cohérentes à construction ; données non nulles, tailles positives et bornées, softCap positif (tests de marge nulle par stock), durées représentables. `RequestWalk` neuf = deux Optional vides ; after présent exige U présent et after≤U **selon ordre PostgreSQL UUID**, pas `UUID.compareTo` signé. Le résultat est immuable ; aucune progression mémoire avant retour physiquement confirmé.

EventProgress.checkpoint est vide si la partition n’a pas pu être protégée ; sinon il porte le K observé/progressé dans l’unité résolue, jamais la seule valeur préparatoire présentée comme progrès. Un RequestProgress BUSY_OR_NOT_OWNER conserve le walk d’entrée. Les retours faisant avancer ou clore un tour passent aussi par la protection ownership, y compris une page brute vide ; aucune page préparée n’autorise à elle seule cette avance.

Contrats des ports :

* `family()` est fixe pour la source et doit égaler definition.family ; le constructeur événementiel refuse une famille burnable et inversement. `window` est appelée sous sourceReads.read RR RO, contexte validé contre la **datasource source**, jamais contre READ effets ; positions exactes et contiguës K+1…H, taille≤W, UUID uniques, discriminant minimal, aucune donnée métier ; vide implique H=K. Absence de journal/famille, trou ou incohérence d’enveloppe lève une faute, jamais une fenêtre filtrée silencieusement.
* `pendingPage` rend les clés PENDING **globales avant filtre partition/slot**, strictement ordonnées et ≤U, taille≤B ; pas de claims consultés. `upperPending` capture la plus haute clé visible, pas une date. `tryProtectPending` est appelé uniquement dans effects.write sur la même autorité source/slots ; true signifie ligne PENDING verrouillée jusqu’à résolution ; false peut signifier terminal/absente/verrou sautée, et ne prouve aucune complétion.
* `checkpoint` RO sert à préparer ; `currentCheckpoint`, `liveSlots`, `contains`, INSERT et CAS exigent l’owner actif, le même datasource et la protection ownership dans **cette unité**. `contains` lit uniquement clé/hash/partition, valide leur identité et ignore totalement état/claim/retry ; tout slot existant fait doublon burnable.
* `insertInitial` calcule RoutingV1, insère exactement C/id/hash/p, defaults AVAILABLE/count=0/NULL ; aucun UPDATE de slot existant. Rowcount=1 ou exception. Le contrôle PENDING précède sous verrou ; il n’est pas remplacé par le résultat d’un INSERT.
* `advanceCheckpoint` impose next>expected≥0 et rowcount exact ; absence initiale n’est admissible que pour expected=0 ; toutes incohérences annulent les slots du lot. Pas de CAS burnable. Aucun de ces ports ne committe ou ne lance de retry.

`Predicate relevant` est pur, stable pour l’identité C et fixé explicitement en composition : tout événement valide pour les familles Pot/Invitation/complétion ; seulement EXTERNAL_USER_IDENTITY pour CurrentBinding. Il ne lit pas d’état mutable. Changer ce prédicat sous le même C/K serait une modification de définition incompatible, pas un réglage de charge. Le banc peut tester d’autres définitions techniques dans un schéma isolé administré ; le catalogue productif neuf reste neuf.

## 9. Protocoles Discovery

### 9.1 Non-burnable : préfixe intégralement couvert

1. Lire K dans une courte unité RR RO de la base des effets, absent=0. Fermer. Lire la source dans une unité RR RO WRITE distincte : compteur sûr et positions/enveloppes dans **la même photographie**, fenêtre `(K,H]`, au plus W positions globales. Fermer avant admission READ. Pas de transaction inter-bases ni de verrou du compteur source.
2. Valider la contiguïté/identités ; calculer RoutingV1 sur eventId et la pertinence stable. Garder toutes les positions examinables, y compris hors partition et POCOMA_USER non pertinent. Ne pas calculer H depuis la dernière référence locale.
3. Ouvrir effects.write RC RW ; timeouts via ownership.bound ; appeler protectAcquisition(definition,lease). Contrat absent/mismatch : faute ; ligne ownership sautée/lease faux/expiré : aucun delta. La méthode conserve le verrou partition jusqu’à résolution.
4. Relire K courant sous cette protection. Si différent du K préparé : STALE_CHECKPOINT sans INSERT/CAS ; nouvelle préparation au prochain appel. Compter le stock non-Q réel. Budget neuf `b=min(insertBatchSize,max(0,softCap-count))`.
5. Parcourir la page globalement dans l’ordre. Toute position non pertinente/hors p est couverte sans slot. Pour chaque référence locale éligible **strictement au-dessus de K**, contrôler l’identité d’un éventuel slot : sa présence serait une incohérence de couverture (normalement impossible avec K/slots atomiques), pas une raison de consommer zéro capacité et sauter silencieusement. Si b restant=0, arrêter **avant cette position**, Hfinal=position−1 ; sinon insérer son slot et décrémenter le budget. Après dernier INSERT autorisé, les positions non locales suivantes peuvent encore être couvertes jusqu’au prochain éligible.
6. Revalider l’échéance de la ligne ownership protégée avec clock_timestamp après toute attente potentielle et avant décision DML ; une expiration constatée annule **tout** le lot. CAS K→Hfinal dans cette unité seulement si Hfinal>K. Commit slots+K ensemble. Si Hfinal=K et aucun slot, aucune création artificielle de checkpoint.

Exemple indépendant : K=0, positions 1 locale, 2 autre partition, 3 locale, 4 autre partition, stock=63/cap=64. Insérer 1, avancer K à **2**, jamais 3/4. Si marge=0, K reste 0. Fenêtre ne contenant que 2/4 hors p : K peut atteindre H même avec marge=0. Avec 50 références pertinentes et marge60, insertBatchSize32 limite également le préfixe à la position précédant la 33e éligible.

**Argument d’absence de perte.** Par induction, le préfixe ≤K initial est couvert. La fenêtre ne contient que des positions définitivement publiées d’un journal dense et conservé. Chaque extension contient tous ses slots locaux avant le CAS atomique ; les autres positions ont un motif stable d’exclusion. Une position non admise borne strictement Hfinal et reste >K. Rollback conserve l’ancien K ; commit conserve le nouveau préfixe ; source immuable permet de rejeter une préparation périmée sans perdre sa référence. Une publication encore invisible ne peut apparaître plus tard sous H lorsque EVT-01 est respecté. Slot supprimé derrière K ne réapparaît jamais ; completion reste I2, pas Discovery. Cet argument est une obligation de preuve PostgreSQL, pas un PASS présent.

### 9.2 Burnable : tour volatil et revisite

Au début d’un tour neuf, capturer U avec upperPending dans RR RO WRITE. Si population vide : résultat idle/tourFinished, walk neuf. Sinon U reste fixe jusqu’à fin de tour. Chaque appel prépare une page PENDING `(cursor,U]` de clés UUID seulement ; ni filtre de claims ni anti-jointure n’élimine les clés de **navigation**. Le filtre de partition, l’existence du slot et la revalidation sont des étapes séparées.

L’admission WRITE prend la protection partition/routage, compte le stock, puis examine la page en ordre PostgreSQL. Si marge initiale nulle : CAPACITY, examined=0, walk inchangé. Pour chaque clé effectivement examinée : hors p → examinée sans admission ; locale → tryProtectPending ; false → saut transitoire ou source disparue/terminale, examinée pour ce tour, revisite prochaine ; true → contains par lecture MVCC simple ; présent, Q compris → doublon examiné sans toucher la ligne ; absent → INSERT initial si marge et budget disponibles. Dès épuisement, arrêter avant la prochaine clé qui n’a pas été examinée ; ne pas déclarer le reliquat couvert. Valider heure DB après attentes et résoudre l’unité. Cursor peut avancer au dernier élément du **préfixe réellement examiné**, uniquement après retour confirmé.

Une page préparée vide **avant filtrage** prouve la fin des clés PENDING visibles dans `(cursor,U]` à cet instant. Une page dont toutes les clés ont été filtrées/sautées ne prouve pas la fin : avancer au dernier UUID réellement examiné puis demander la page suivante. Atteinte U ou page brute vide → fin de tour, nouveau walk au prochain appel. Insertion devenue visible après ce constat sera revue au tour suivant. Le curseur n’est jamais une preuve durable d’enrôlement ou de complétion.

| Situation obligatoire | Comportement et preuve à produire |
|---|---|
| Clé insérée derrière cursor | Reste PENDING ; reprise au début du prochain tour ou après crash |
| Clé insérée au-delà de U | Non incluse au tour courant ; U n’est pas étendue ; tour suivant |
| Nouvelle clé entre cursor et U | Peut entrer dans une page ultérieure ; ordre strict maintenu, sans nouvelle borne |
| Déjà enrôlée, claim actif/expiré ou retryAfter futur | Tout slot empêche INSERT ; aucun renewal/cession/reset par Discovery |
| QUARANTINED | Même slot conservé, source PENDING ; exclu du COUNT mais pas du contrôle d’existence |
| TERMINAL sans slot ou finalisée pendant préparation | Revalidation PENDING échoue sous verrou ou saute le finaliseur ; aucun réenrôlement |
| Deux Discovery, même token de lease | Verrou partition sérialise ; deuxième voit le slot ou TERMINAL ; aucune confiance dans la page préparée |
| SoftCap plein au milieu du lot | Commit du préfixe admis/examiné ; walk ne dépasse pas les clés non examinées ; suspension puis reprise |
| Perte mémoire, crash, takeover | Nouveau walk depuis le début, mêmes sources durables, aucune table cursor/U |
| Page filtrée vide | Navigation continue sur les clés brutes ; ne pas boucler indéfiniment sur la même page |
| Tours sous admissions continues | U fixe, pages bornées ; insertions >U n’allongent pas le tour ; insertions derrière attendent le suivant |

**Progression :** sous populations/arrivées de campagne contrôlées, occasions répétées, capacité revenant, verrous libérés et fenêtres sans crash, toute demande durablement PENDING sans slot est revisitée et peut être admise. Les UUID forment un espace totalement ordonné fini ; cursor strictement croissant et U fixe empêchent une extension infinie par des clés >U. Des insertions dans `(cursor,U]` peuvent néanmoins augmenter le nombre de pages : **ne pas promettre ceil(population initiale/B) pages indépendamment des arrivées**. La campagne enregistre les clés entrées pendant le tour et vérifie sa terminaison effective. Aucun SLO universel ni preuve d’équité intégrée I5 n’est déduit de ce raisonnement.

Un verrou systématiquement repris devant chaque passage peut toujours empêcher une admission SKIP LOCKED ; « chaque verrou finit » seul ne prouve pas que le passage rencontre une occasion libre. Les tests de progression imposent une période libre contrôlée et des appels effectifs pendant cette période. Si l’audit exige une garantie adversariale plus forte, voir §22 : ne pas la déclarer acquise par le seul parcours circulaire.

## 10. Transactions et SQL dans l’ordre exact

Tous paramètres sont liés JDBC ; aucun nom de table/famille arbitraire fourni par un utilisateur. Les tables de source sont une sélection fermée de l’adaptateur. READ et WRITE sont deux bases physiques ; chaque unité a son propre datasource, contexte et manager déjà validés. Pas de `REQUIRES_NEW`, pas de transaction imbriquée, savepoint ou owner dans les adaptateurs.

Les adaptateurs de lecture source valident le contexte puis règlent leurs timeouts par SET LOCAL dans la transaction RO courante, avec durées injectées/archivées : réglages PostgreSQL, sans mutation de table ni de manager/session globale. Cela borne aussi une attente de relation pendant préparation. Ne pas appeler ownership.bound dans un snapshot RO : sa garde existante exige une unité write. Le timeout60s de finalisation I2 n’est pas changé pour imposer les bornes des seules opérations Discovery.

### 10.1 Préparation source non-burnable réelle

Sous sourceReads.read RR RO, même snapshot :

```sql
SELECT position FROM v3_event_counter WHERE family=:sequence;
-- safe = valeur committée ci-dessus ; H = K + min(W, safe-K), sans overflow.
SELECT position,event_id FROM v3_event_position
 WHERE family=:sequence AND position>:K AND position<=:H ORDER BY position;
-- Lire dans cette même unité l’enveloppe minimale des UUID obtenus :
SELECT event_id,subject_type FROM v3_pot_event WHERE event_id IN (:ids);
-- Ou v3_command_completion_event pour COMMAND_COMPLETION.
```

Exiger exactement une ligne de compteur et exactement H−K positions contiguës ; safe<K ou position/enveloppe manquante = incohérence, arrêt sans admission. Si safe=K, fenêtre vide ; ne pas avancer. Un counter non committé reste invisible ; son verrou ne bloque pas ces SELECT MVCC. Les contraintes productives n’ont pas de FK position→événement universelle : **la jointure ne doit jamais éliminer silencieusement une publication manquante**. Adapter de fixture : même contrat pour cinq familles, compteur transactionnel réel et enveloppes conservées, sans modifier v3_event_counter.

### 10.2 Admission non-burnable READ

effects.write RC RW :

1. `SET LOCAL` bornes via `ownership.bound` (lock500ms, statement2s, transaction3s initiaux de campagne, pas SLO).
2. SELECT contrat de routage puis partition `FOR UPDATE SKIP LOCKED`, puis instruction séparée `SELECT clock_timestamp()` ; comparaison ownerToken/deadline par protection I3. Absence/mismatch/refus → aucun INSERT.
3. `SELECT position FROM discovery_checkpoint WHERE consumption_id=:C AND partition_id=:p` (sans verrou de ligne supplémentaire ; la partition sérialise tous les writers de K). Absent=0 ; vérifier K attendu **avant** tout INSERT.
4. `SELECT count(*) FROM consumption_slot WHERE consumption_id=:C AND partition_id=:p AND state<>'QUARANTINED'`. MVCC ne verrouille pas les slots ; un DELETE concurrent invisible surestime prudemment le stock ; une réactivation concurrente peut dépasser le plafond souple R2-08.
5. Pour le préfixe calculé : contrôle non verrouillant des clés/hash/p ; clock fraîche après toute attente avant les décisions ; INSERT exact ci-dessous. Aucun accès source WRITE dans cette unité READ.
6. Recontrôle de deadline protégée après les éventuelles attentes ; si expirée, exception/poison/rollback total. K présent : `UPDATE discovery_checkpoint SET position=:Hfinal WHERE consumption_id=:C AND partition_id=:p AND position=:K RETURNING position`, exactement1. K absent et attendu0 : INSERT `(C,p,Hfinal)`, exactement1. La présence/absence utilisée est celle relue sous partition ; conflit ou rowcount0 annule tout.
7. Commit unique. Ne pas appeler `requirePublicationClosed` ni SlotCompletion : il s’agit d’une unité technique, pas d’une finalisation métier.

```sql
INSERT INTO consumption_slot
 (consumption_id,consumable_id,partition_hash,partition_id)
VALUES (:C,:id,:hash,:p);
```

Le trigger `consumption_row_guard` recalcule le hash, valide p et lit le contrat ; la FK ne référence que consumption_routing_contract. Les defaults existants donnent AVAILABLE, count0, claim/retry NULL. Aucun nouveau champ, FK source ou index requis.

### 10.3 Préparation et admission burnable Command

RR RO WRITE :

```sql
SELECT request_id FROM v3_command WHERE status='PENDING'
 ORDER BY request_id DESC LIMIT 1; -- U une fois par tour
SELECT request_id FROM v3_command
 WHERE status='PENDING' AND request_id>:cursor AND request_id<=:U
 ORDER BY request_id LIMIT :B;
-- Variante sans prédicat request_id>:cursor pour un début de tour.
```

RC RW WRITE : bornes → routage → partition protégée/heure → COUNT non-Q → pour chaque clé locale dans la marge :

```sql
SELECT request_id FROM v3_command
 WHERE request_id=:id AND status='PENDING' FOR UPDATE SKIP LOCKED;
-- Si une ligne est obtenue, NOUVELLE instruction et nouveau snapshot RC :
SELECT partition_hash,partition_id FROM consumption_slot
 WHERE consumption_id=:C AND consumable_id=:id; -- aucun FOR UPDATE
-- Si absent, heure DB fraîche + lease protégé valide, puis INSERT initial exact.
```

Le verrou source est conservé jusqu’au commit et interdit la transition TERMINAL pendant l’INSERT. La lecture du slot post-protection n’attend pas une suppression non committée : elle en voit l’ancienne ligne visible, ce qui suffit à constater le doublon. Aucun statement combinant un vieux snapshot de source et l’insertion. Les clés non locales ne prennent pas de verrou de source. La table de fixture IDENTITY_REQUEST reproduit exactement PENDING immuable/TERMINAL monotone et ce protocole SQL ; aucun nom de future table Identity productive n’est inventé.

**Idempotence et conflits.** L’idempotence est celle de l’opération complète : K revalidé exclut une seconde fenêtre ; source+existence excluent une seconde admission burnable. **Proposition : pas de ON CONFLICT DO NOTHING pour les slots.** Sous partition exclusive et routage stable, aucun autre writer Discovery conforme ne peut insérer la même clé pendant l’unité. Un 23505 inattendu révèle un writer/protocole incompatible : rollback, diagnostic, pas « succès idempotent ». Une présence au-dessus de K non-burnable est également une incohérence. Cette discipline évite de cacher identité erronée, K périmé ou statut terminal. Un éventuel UPSERT ne serait acceptable qu’avec contrôle explicite des mêmes postconditions et analyse de son attente d’unicité ; il n’apporte ici aucune garantie et n’est pas retenu.

### 10.4 Résolution et commit incertain

SpringAttemptTransactions conserve ses trois résolutions. Retour normal : publier EventProgress/RequestProgress et seulement alors le walk suivant. Failure ROLLED_BACK : aucun slot/K nouveau, aucun curseur avancé ni +1 ; appel ultérieur normal. Failure UNKNOWN, erreur brute ou COMMITTED avec résultat callback perdu : ne retourner **aucun** progrès supposé ; conserver le walk d’entrée ou repartir neuf, relire les états durables à l’appel suivant. Une répétition repasse par protection partition et source/K ; elle ne rejoue pas une fenêtre sur K avancé. Aucun recovery Discovery ne supprime/répare un slot ou ne terminalise une demande.

Si l’ancienne unité reste physiquement ouverte, sa partition reste verrouillée : la nouvelle admission la saute. Si la ligne est de nouveau protégeable, sa sérialisation et les états courants suffisent à une nouvelle décision ; une observation READ ancienne ne prouve pas le rollback. Le résultat de la première tentative demeure historiquement incertain, même si la reprise ne crée aucun doublon. Aucun compteur d’échecs Discovery et aucun nouvel état SQL UNKNOWN.

## 11. Analyse contradictoire des verrous burnables

### 11.1 Graphe effectivement livré et verrous implicites

F = finaliseur I2/I3 ; D = admission Discovery proposée. Le chemin Command réel est : slot FOR UPDATE/heure → reload source (SELECT simple) → témoin Identity → advisory et identité Pot → effets/révisions → `INSERT v3_command_result` → `UPDATE v3_command SET status='TERMINAL'` → completion DELETE slot → préfixe événements/flush/contraintes → compteurs POT puis COMMAND_COMPLETION → commit. INVALID_BINDING omet les verrous Pot. **Ne pas déplacer ce chemin.**

`v3_command_result.request_id` référence `v3_command.request_id` : sa vérification FK peut prendre KEY SHARE sur la source avant l’UPDATE de statut. L’UPDATE non clé protège la ligne source ; le DELETE du slot garde son verrou jusqu’à résolution. Des FK/UNIQUE d’événements/résultat peuvent également attendre. La table canonique consumption_slot ne référence pas la source ; sa seule FK va au contrat de routage. Le trigger slot lit le contrat et recalcule le hash, sans verrou source/partition. Aucun trigger entrant/cascade ne supprime une source avec un slot. Les triggers terminal/result/event différés font des SELECT de validation ; pas de nouveau verrou métier après suffixe. Ces faits proviennent de V2/V5 et du writer, et devront être confirmés par le catalogue et les traces SQL du banc.

PostgreSQL retient les verrous de ligne jusqu’à la fin de l’unité et distingue les conflits FOR UPDATE/KEY SHARE/NO KEY UPDATE ; la visibilité MVCC d’un SELECT simple est différente d’une acquisition de verrou. Référence technique : [PostgreSQL 17 — Explicit Locking](https://www.postgresql.org/docs/17/explicit-locking.html). SKIP LOCKED concerne les lignes, pas toutes les attentes de relation : [SELECT](https://www.postgresql.org/docs/17/sql-select.html). Les nouveaux snapshots par instruction RC sont essentiels après protection : [Transaction Isolation](https://www.postgresql.org/docs/17/transaction-iso.html). Enfin INSERT/ON CONFLICT peut attendre un conflit d’unicité ; DO NOTHING n’est pas une primitive de verrou non bloquante : [INSERT](https://www.postgresql.org/docs/17/sql-insert.html). L’analyse de graphe ci-dessous est propre au code Pocoma ; ces références ne sont pas des preuves d’exécution I4.

### 11.2 Alternative A — protection bloquante

**A naïve, rejetée :** D prend partition→source FOR UPDATE puis tente un verrou de slot ou un INSERT dont l’unicité attend F. F détient le slot et attend la source (FK résultat ou UPDATE terminal). Cycle D→F sur slot et F→D sur source ; ajouter ON CONFLICT DO NOTHING ne le supprime pas. Prendre seulement FOR NO KEY UPDATE sur source n’élimine pas l’attente de l’UPDATE terminal. Inverser F en source→slot modifierait I2 et n’est pas autorisé.

**A corrigée, candidate recevable sur le graphe fermé :** partition→source FOR UPDATE **avec attente**, puis lecture d’existence slot MVCC, jamais FOR UPDATE de slot existant. Si présent, ignorer ; si absent et PENDING verrouillé, INSERT frais. Deux D sérialisées par partition ; l’INSERT frais ne rencontre pas un slot en cours de finalisation sans que l’ancienne ligne soit visible au contrôle d’existence. Une erreur d’unicité inattendue annule le lot. L’ordre admissible porte sur les **attentes** : D peut attendre une source F ; après cette source, D ne doit attendre aucun slot F. La fermeture du suffixe F et l’absence de retour vers des demandes différentes doivent aussi exclure les chemins indirects via Identity/Pot/compteurs. Cette variante demande donc une vérification plus large de tous les verrous descendants de F, surtout quand D garde plusieurs sources du lot.

Attentes : un finaliseur ayant pris la source avant D retient la partition D pendant toute sa fin métier/publication/commit. Lease renewal/acquisition de cette partition la saute ; claim renewals restent indépendants et sautent leurs slots protégés. Si la deadline passe pendant attente, D doit rollback après horloge fraîche, avec aucun gain. Timeout borne la tentative mais n’est pas une garantie d’admission. La sûreté paraît réalisable sans changer I2 ; la durée de blocage, le maintien de plusieurs sources et la preuve du graphe indirect rendent A moins simple à exploiter. Elle n’est **pas certifiée** et n’est pas choisie pour réalisation proposée.

### 11.3 Alternative B — protection non bloquante et revisite

Ordre D : routage ACCESS SHARE→partition FOR UPDATE SKIP LOCKED/heure→COUNT MVCC→sources PENDING FOR UPDATE SKIP LOCKED en ordre UUID→existence slot **sans verrou de ligne**→INSERT seulement d’une clé absente protégée→heure/fence→commit. Aucun D ne prend un slot existant en FOR UPDATE, ni ne attend volontairement une source verrouillée. Pas de verrou de compteur de publication. Tout writer d’INSERT slot conforme passe sous la partition du routage stable.

| Entrelacement contrôlé | Observation / résolution proposée |
|---|---|
| F détient slot, n’a pas encore pris source ; D prend source | Le SELECT simple post-source de D voit le slot committé, même claim expiré ou DELETE F encore non committé ; D n’insère rien. F peut attendre KEY SHARE/UPDATE source ; D ne attend pas F sur son slot et résout son court lot |
| F a pris KEY SHARE source par résultat avant D | FOR UPDATE SKIP LOCKED de D saute ; cursor avance sur cette clé examinée pour ce tour, pas de couverture durable ; F termine, prochain tour voit TERMINAL |
| F a mis TERMINAL et supprimé slot sans commit | Si source verrouillée, saut ; si SELECT simple observe l’ancien slot, doublon ; aucune admission sur l’absence non committée |
| F committe entre préparation D et revalidation | Nouveau statement RC voit TERMINAL ou sa version réévaluée par PostgreSQL ; aucune ligne PENDING protégée, aucun INSERT |
| F rollback après saut de D | PENDING et slot originaux persistent ; prochain tour constate le slot. Si source était PENDING sans slot sous un autre verrou de fixture, prochain tour libre l’admet |
| D protège PENDING sans slot avant un nouveau D | Partition conserve l’unicité ; D2 saute la partition ou après commit voit le slot ; aucun claim/effet par D |
| D tient source d’un doublon tandis que F attend cette source | D n’a aucune attente de ligne vers F, y compris sur les autres sources qui sont SKIP LOCKED ; sortie courte puis F progresse |
| Renewal/cession/admin prend un slot | Lecture d’existence MVCC observe la même clé ; aucun D ne reset/renouvelle ni attend ce slot ; la réactivation peut modifier le COUNT seulement selon R2 |
| Contrat administrativement verrouillé ou DDL relation | SKIP LOCKED n’est pas suffisant ; timeouts et rollback conservateur. Administration exceptionnelle hors runtime, verrou contrat avant autres objets ; pas de disponibilité promise sous DDL permanent |

Le contre-exemple terminalisation sur ancienne observation est fermé par **verrou source courant + nouvelle lecture d’existence + INSERT dans la même unité**. L’absence de FK slot→source évite une nouvelle acquisition implicite de source après INSERT ; le KEY SHARE vers routing_contract est compatible avec ses lectures et l’immuabilité runtime. La PK slot, ses index et triggers sont conservés. Une unicité concurrente malgré ces préconditions est un défaut d’inventaire de writer, pas un événement normal à cacher.

**Recommandation structurante B, à ratifier par audit puis GO :** conserver F inchangé, protéger source sans attente et revisiter. C’est une proposition explicite d’ordre des verrous, pas une validation préalable donnée par le mandat. Moins d’attente sur partition et pas de dépendance du lot D envers une source tenue par F ; coût : une revisite possible et des locks source sur les doublons jusqu’à la fin du petit lot. Famine sous contention adversariale non exclue par SKIP LOCKED seul ; §9 fixe les hypothèses et §17 les preuves. Si elles ne satisfont pas R4 selon l’audit, ne pas implémenter une compensation durable : arbitrage §22.

### 11.4 Preuves indispensables pour départager A/B

Observer F arrêté avant FK source, après FK, après UPDATE, après DELETE et avant résolution ; établir D détenteur de partition/source ou source sautée, l’absence d’attente sur slot, et les états SQL extérieurs. Pour A naïve, un test SQL de contre-exemple isolé peut établir le cycle et le 40P01, puis rollback de toutes les connexions ; ce n’est pas du code productif alternatif. Pour A corrigée, mesurer l’attente source puis horloge/refus du lease. Pour B, exiger que D puisse résoudre pendant que F reste maintenu à sa barrière et que F finisse ensuite sans double effet. Répéter avec un second Command, Identity/Pot partagés et distincts, prefixe/counter verrouillé et admin/renewal concurrents. Toute attente de slot D inexpliquée ou cycle reproductible interdit la clôture.

## 12. SoftCap, collisions, interruptions et reprise

| Sujet | Contrat précis |
|---|---|
| Stock mesuré | COUNT des slots `(C,p)` non-QUARANTINED, y compris libres, claims actifs/expirés et retryAfter futur ; aucune lecture détaillée de ces états pour découvrir |
| Sérialisation | Ligne partition existante ; tous les INSERT d’une même clé routée sont sous ce verrou ; partage de ownerToken entre appels ne dispense pas du verrou |
| Plafonds | softCap admission ; insertBatchSize lot ; M_C/M_W tentatives I3. Discovery ne réserve pas de ticket métier et n’attend pas une place M_W |
| Réactivation concurrente | Reset administratif peut faire dépasser cap ; aucun refus rétroactif d’une admission conforme au COUNT observé ; prochain COUNT sans marge suspend les nouvelles admissions |
| Finalisation/cession concurrente | Un DELETE ou passage Q peut libérer de la marge ; si invisible au COUNT courant, admission plus prudente seulement ; aucun effet existant bloqué par cap |
| Collision ownership | Aucun slot/K ; walk non avancé ; retry au prochain appel normal sans boucle interne |
| Collision K | Repréparer depuis K durable ; ne pas tronquer/rejouer une page basée sur un autre K |
| Collision PK | Les doublons burnables connus sont exclus avant INSERT ; collision inattendue = rollback/diagnostic. Aucun UPDATE d’un slot ni masquage d’identité |
| Crash avant/après INSERT/K | Avant commit : rollback physique à établir ; après commit : durable fait autorité. Ni remise à zéro ni nouvelle identité de Consumption |
| ACK perdu | Retour sans progression affirmée ; reprise sous les mêmes fences, aucune cession, aucune correction automatique |
| Nouvelle Consumption | Historique conservé, nouveau C explicitement administré et destination technique séparée ; K propre absent=0. Ne pas transformer le catalogue pilote en allocation dynamique runtime |

## 13. Contrat de privilèges proposé

I3 livre un rôle runtime lifecycle avec UPDATE de colonnes ciblées et DELETE slot R1, mais **sans INSERT slot ni mutation K**. `V3BootstrapGuard.inspectLifecycle` refuse aussi les grants de colonne INSERT et tous les memberships. Ajouter seulement un GRANT ferait donc échouer la composition : le contrôle explicite fait partie du changement nécessaire.

**Proposition compatible avec un seul datasource/manager par autorité :** conserver le login runtime existant et ajouter une capacité Discovery par script administratif séparé, sur cibles V3 explicitement autorisées. Distinguer dans le garde les profils de privilèges `LIFECYCLE_ONLY` et `LIFECYCLE_AND_DISCOVERY`, choisis explicitement à la factory. Le profil I1 `validate()` et l’appel lifecycle-only existant restent stricts. Il s’agit de deux allowlists déclarées, pas d’un fallback automatique après échec de validation.

La séparation des **responsabilités et droits** admin/lifecycle/Discovery/réactivation est décrite ci-dessous ; elle n’impose pas quatre logins ou un nouveau pool. Deux rôles runtime séparés exigeraient soit deux sources/managers opérationnels, soit SET ROLE/membership que I3 refuse. Ce serait une décision d’intégration I5 plus large. La recommandation présente est l’union ciblée des capacités sur le login du Worker, avec writers Java séparés, sans donner au lifecycle un port d’admission. **Ce choix d’ACL et de composition est à approuver explicitement avec le plan**, pas une convention déjà livrée. Si une séparation stricte de principals SQL est exigée par l’audit, le §22 conditionne l’assemblage conjoint.

| Objet | Lifecycle existant | Delta Discovery minimal | Administration / interdits |
|---|---|---|---|
| Schéma / rôles | USAGE ; aucun membership, CREATE, owner ou superpouvoir | Aucun élargissement | DDL/bootstrap administratifs seulement ; pas PUBLIC/default privileges/SET ROLE |
| consumption_routing_contract | SELECT | Aucun | INSERT admin neuf ; immutable, pas UPDATE/DELETE Worker |
| partition_ownership | SELECT ; UPDATE(owner_token,owned_until) | Aucun : FOR UPDATE nécessite déjà UPDATE sur une colonne | Pas d’INSERT/DELETE ni modification clés ; Discovery ne renouvelle pas elle-même |
| consumption_slot | SELECT ; UPDATE(claim_token,claimed_until,retry_after,attempt_count,state) ; DELETE R1 | INSERT(consumption_id,consumable_id,partition_hash,partition_id) seulement | Pas d’INSERT explicite claim/count/state, pas UPDATE clé/hash/p ; aucun nouveau DELETE |
| discovery_checkpoint | SELECT | INSERT(consumption_id,partition_id,position), UPDATE(position) | Pas UPDATE des clés ni DELETE/TRUNCATE ; trigger anti-burnable/anti-régression conservé |
| v3_command | SELECT et UPDATE(status) déjà lifecycle WRITE | Aucun supplémentaire pour SELECT FOR UPDATE ; Discovery n’exécute pas UPDATE | Pas INSERT d’admission, payload/intention ou DELETE ; rôle d’admission séparé du Worker |
| v3_event_counter/position et enveloppes POT/COMPLETION | SELECT du lifecycle WRITE ; compteurs/positions écrits par participant I2 | Lectures uniquement nécessaires côté source pour non-burnable ; établir grants SELECT explicites sur le login WRITE utilisé | READ effets n’obtient jamais DML WRITE ; sourceReads ne donne pas au code Discovery l’accès aux writers I2 |
| Fonctions invoker/pgcrypto | Appels hash/triggers existants | EXECUTE/USAGE nécessaires constatés, sans SECURITY DEFINER | Conserver propriétés et vérifier droits effectifs des fonctions, pas de rôle privilégié caché |
| Réactivation | Rôle distinct : SELECT C/family contrat, SELECT slot + UPDATE cinq colonnes de reset | Aucun | Jamais hérité ; aucun INSERT/K/DELETE/source/métier |

Nouveau fichier proposé `scripts/v3/grant-l2-discovery.sql` : `psql -X`, ON_ERROR_STOP, transaction, assertions expected_database/schema/admin/runtime/reactivation, rôles non privilégiés distincts, objets/routes existants ; GRANTs de colonne ci-dessus, puis contrôle complet allowlist union table/colonne/PUBLIC/membership/ownership. Refuser les droits trop larges plutôt que les normaliser en silence. Vérifier les grants d’insertion avec `has_column_privilege` et ceux de table séparément ; un GRANT INSERT global ferait échouer le contrôle. Réexécution sûre sur rôle conforme ; cible/état non conformes annulent tout.

Conserver `grant-l2-lifecycle.sql` **inchangé** : il demeure le contrat d’installation lifecycle-only et refuse volontairement un rôle déjà plus large. Ordre administratif d’installation : bootstrap→grants lifecycle→grants Discovery ; ne pas réexécuter le premier comme une normalisation après le second. La migration I1 n’est pas rejouée sur un schéma peuplé. Les tests prouvent les deux profils et ce refus explicite. Aucun droit n’est accordé par un démarrage runtime.

Limite de confiance conservée : une ACL INSERT/K ne démontre pas l’atomicité de leur usage ; des credentials de Worker employés pour du SQL arbitraire pourraient contourner le port, comme le DELETE I2. Les seules entrées gardées, la composition et les preuves physiques portent le contrat ; pas de nouveau trigger de capability ou journal pour simuler une sécurité absente. Tester les véritables rôles non propriétaires, jamais uniquement superuser.

## 14. Fichiers exacts à créer, adapter ou conserver

**Liste prospective, aucune autorisation de les créer maintenant.** Préfixes J/T définis §5. C=créer (absent à la baseline), M=modifier ; auxiliaires imbriqués dans les fichiers nommés. Aucun wildcard autorisant une refonte de package.

| Action | Préfixe / fichier | Objet |
|---|---|---|
| C | J(engine-consumption)`engine/consumption/v3/DiscoveryEngine.java` | Deux opérations, Settings/Walk/Progress imbriqués ; pas de scheduler/cache durable |
| C | J(engine-consumption)`engine/consumption/v3/port/NonBurnableReferences.java` | Contrat Window/Reference et snapshot sûr |
| C | J(engine-consumption)`engine/consumption/v3/port/BurnableReferences.java` | Navigation PENDING et protection non bloquante |
| C | J(engine-consumption)`engine/consumption/v3/port/DiscoveryAdmissionStore.java` | K/COUNT/existence/INSERT/CAS, aucune transaction propre |
| C | J(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/consumption/JdbcDiscoveryAdmissionStore.java` | Adaptateur commun WRITE/READ |
| C | J(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/consumption/JdbcCommandDiscoveryReferences.java` | Sources v3_command, clé UUID, revalidation source |
| C | J(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/consumption/JdbcEventDiscoveryReferences.java` | Deux séquences réelles disponibles, table mapping fermé |
| C | J(composition-runtime-spring)`runtime/consumption/v3/V3ConsumptionDiscoveryComposition.java` | Factory inactive, même owner/source injectés, filtre stable |
| M | J(composition-runtime-spring)`runtime/consumption/v3/V3BootstrapGuard.java` | Allowlist union explicite, vraies sources/autorités, inertie maintenue |
| M | J(composition-runtime-spring)`runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` | Choix explicite de profil de droits, validations I3 conservées |
| M | J(composition-runtime-spring)`runtime/command/v3/V3PotCommandComposition.java` | Assemblage conjoint avec owner partagé injecté ; conserver l’usage strict I3, aucun nouveau manager |
| C | T(engine-consumption)`engine/consumption/v3/DiscoveryEngineTest.java` | Algorithmes purs et retours de progression, pas preuve de verrous |
| C | T(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/DiscoveryPostgresFixture.java` | Réutiliser mécanismes I3/proxy ; sources absentes en fixture, deux autorités |
| C | T(infra-persistence-primary-jpa)`infra/persistence/primary/jpa/v3/DiscoveryPostgresTest.java` | Méthodes de la matrice §17 |
| C | T(infra-persistence-read-jdbc)`infra/persistence/read/jdbc/v3/consumption/ReadDiscoveryPrivilegesPostgresTest.java` | Script réel et ACL du schéma READ |
| C | T(runtime-command-consumption-worker)`runtime/command/v3/DiscoveryCompositionPostgresTest.java` | Factory réelle, source/owner/privileges et absence de thread |
| M | T(runtime-command-consumption-worker)`runtime/command/v3/ConsumptionBootstrapPostgresTest.java` | Refus du profil I1 avec droits/beans actifs, sans réduire assertions I1 |
| M | T(runtime-command-consumption-worker)`runtime/command/v3/ConsumptionLifecycleCompositionPostgresTest.java` | Même comportement I3 pour profil strict, négatif union implicite et partage d’owner |
| M | T(runtime-command-consumption-worker)`runtime/command/v3/V3PotCommandCompositionPostgresTest.java` | Adapter l’injection d’owner si nécessaire, conserver toutes preuves métier/classification |
| M | T(runtime-monolith)`ConsumptionPilotIsolationTest.java` | Aucun worker/endpoint/legacy actif sur cible pilote |
| M | T(architecture-tests)`architecture/v3/V3ConsumptionEngineArchitectureTest.java` | Pureté des ports/Engine, aucune transaction source cachée, nouveaux writers gardés et négatifs |
| M | T(architecture-tests)`architecture/v3/V3FoundationArchitectureTest.java` | Inventaire nominatif des seules nouvelles valeurs pures de port, si la règle les rencontre |
| M | T(architecture-tests)`architecture/v3/V3ConsumptionFoundationArchitectureTest.java` | Catalogue et inactivité conservés avec nouvelle factory |
| C | `scripts/v3/grant-l2-discovery.sql` | Droits post-bootstrap §13 |
| C | `docs/v3/L2_I4_Proof_Manifest.json`, `docs/v3/L2_I4_Execution_Report.md` | À ouvrir après GO, méthodes/variants figés puis résultats réels |
| C | `scripts/testing/verify-v3-l2-i4.py`, `scripts/testing/test_i4_evidence.py` | Collecte stricte réutilisant la méthodologie I2/I3 |
| M après réalisation seulement | `docs/v3/L2_Certification_Coverage_Manifest.md`, `docs/v3/L2_Design_and_Verification_Plan.md`, `docs/v3/V3_Implementation_Master_Plan.md` | Contributions et liens exacts, sans écraser allocations ni rapports historiques |

À **conserver sans modification productive attendue** : domain-consumption ; ConsumptionEngine/WorkerCapacity/ConsumptionSlotStore/PartitionOwnershipStore ; JdbcConsumptionSlotStore/JdbcPartitionOwnershipStore ; AttemptTransactions/SpringAttemptTransactions ; ExecutePotCommand et stores/histoire/révisions ; catalogue neuf ; toutes migrations V1–V5 et READ V1 ; bootstrap, grants lifecycle et réactivation ; POMs ; racines/propriétés runtime ; tout code historique hors pilote. La factory proposée n’a pas besoin de nouvelle méthode de protection ownership : `protectAcquisition` exprime déjà l’autorisation des nouveaux travaux et garde le verrou jusqu’au commit.

Les dossiers de preuve futurs vont sous `docs/testing/evidence/L2-I4/`, inventaire généré avant campagne et empreintes à la collecte. Les fixtures peuvent ajouter sources/effets/instruments **de test**, jamais une migration productive ni une famille technique. Toute nécessité d’un autre fichier productif, d’une modification d’owner ou d’un index exige une révision préalable du scope et du plan.

## 15. Étapes futures ordonnées et bornées

| Étape | Travail et sortie reviewable | Condition / arrêt |
|---|---|---|
| I4-0 | Audit indépendant du présent plan, réponse aux constats, GO humain explicite ; reconfirmer Git/delta ; figer manifeste/méthodes/settings/rôles/images | Aucun code avant ce checkpoint ; B et ACL doivent être visés par la version approuvée |
| I4-1 | Ports/valeurs et algorithmes Engine, validations et unités avec mêmes owners ; tests purs préfixe/navigation | Ne pas modifier ConsumptionEngine ou recovery pour raccorder Discovery |
| I4-2 | Script ciblé et garde/factories de droits explicites ; tests véritables rôles WRITE/READ et profil I1 inchangé | Aucun grant runtime au démarrage, aucune migration rétroactive |
| I4-3 | Adaptateur sources Command + deux publications réelles ; autres sources techniques dans fixture ; contrôles de densité/snapshot | Une source absente ne peut être simulée en production |
| I4-4 | Admission non-burnable, COUNT/préfixe/K/CAS ; courses, fautes et ACK perdu | Aucune position éligible dépassée sans slot ; défaut physique = arrêt local/correction |
| I4-5 | Burnable U/page/protection B/existence/INSERT ; interleavings complets avec F, renewals et admin | Aucune attente D sur slot existant, aucun réenrôlement terminal/Q ; besoin de changer I2 = arbitrage |
| I4-6 | Assemblages explicites inactifs ; harnais manuel Discovery→acquisition I3→tentative I2 ; coûts et progression locale | Aucun poller, scheduler ni endpoint ajouté |
| I4-7 | SHA de code figé, gates §19, collecteur, revue complète des preuves et rapport borné ; publication sans force | Audit indépendant code+preuves puis décision humaine ; **arrêt avant I5**, aucun G1/G2/G3 automatique |

Les tests physiques sont écrits avec l’opération productrice, pas différés après une implémentation globale. Les futures classes énumérées ici ne sont pas créées pendant la préparation de ce plan.

## 16. Dispositif de vérification et fixtures indépendantes

### 16.1 Unités et contrats purs

Dans le futur `DiscoveryEngineTest` : `coveredPrefixStopsBeforeFirstUnadmittedReference`, `zeroLocalReferencesAdvanceGlobally`, `quarantinedPresenceIsNotNewWork`, `filteredPageIsNotEndOfTour`, `capacityPreservesUnexaminedSuffix`, `walkUsesUnsignedUuidOrder`, `unknownTransactionReturnsNoProgress`, `invalidSourceOrWindowRefusedBeforeAdmission`. Vérifier également les nulls/tailles/bornes/overflow et le rejet d’une méthode du mauvais genre. Les doubles de ports prouvent l’orchestration et les préconditions, **jamais** atomicité physique, locks, fencing, commit incertain ou non-famine.

### 16.2 Banc PostgreSQL réel

Réutiliser les mécanismes de `ConsumptionLifecyclePostgresFixture`, `I2Schema`, `CommitAcknowledgementProxy`, les scripts `psql` et les captures des campagnes I2/I3 ; ne pas recopier les fonctions productives pour les remplacer dans la preuve. La fixture I3 est actuellement package-private dans le package `v3`, ce qui permet le réemploi local sans exposer son API en production. Les noms ci-dessous sont des **fixtures proposées**, pas des tables productives existantes.

* **F-N :** WRITE source et READ effets physiquement distinctes, migrations V3 réelles ; cinq variantes de journal dense technique, une famille par sous-scénario isolé, avec compteur transactionnel et enveloppes minimales. K0=0, p=0, positions 1 et3 locales, 2 et4 vers p=1 ; événement supplémentaire POCOMA_USER permet le filtre Identity. cap par défaut64, W128, B32 ; variantes cap1/2 et W1/2/3. Un journal de fixture n’est jamais identifié comme producteur Identity/Invitation réel. Les UUID de la table documentaire sont réutilisés seulement entre cibles isolées, pas comme deux eventIds identiques dans un journal partagé.
* **F-R :** sources `v3_event_position`/enveloppes créées par le vrai participant Command I2 après admission PENDING interne puis Discovery/acquisition I3 réelles lorsque l’étape le permet ; autorité Identity explicitement fixture PostgreSQL. Capturer eventIds/positions réellement publiés **avant stimulus Discovery**, calculer les partitions par Python SHA externe, puis établir l’attendu pour les quatre partitions. Les eventIds sont générés par le producteur ; conserver le seed des intentions et le dump SQL exact pour reproduction. Les tests ne dépendent d’aucune distribution aléatoire particulière : F-N porte les motifs inter-partitions imposés.
* **F-B :** COMMAND réel dans v3_command ; analogue IDENTITY_REQUEST dans table dédiée de test avec PK UUID/intention immuable/statut monotone et finaliseur technique. k0<k1<k2<k3<k4 de p=0, U=k3 ; états actifs/expirés/retry/Q insérés par fixture seulement pour préconditions, jamais comme preuve d’acquisition/cession I3. TERMINAL Command réel est construit par une finalisation réelle, avec son résultat et sa complétion, jamais un UPDATE terminal incohérent.
* **F-X :** F-B/F-R + owners A/B, lease30s/claim60s, clocks DB, vraies opérations I3 et F ; variants d’échéance plus courts explicitement archivés. Sources/slots témoins d’autres C et p inchangés.

Fixtures UUID calculées indépendamment avec `hashlib.sha256(b'pocoma-consumable-v1\0'+family+b'\0'+UUID.bytes)`, premiers4 octets signés big-endian, modulo4. Notation u(n)=UUID de valeur entière n, par exemple u(3)=`00000000-0000-0000-0000-000000000003`. Ces valeurs documentaires ne sont pas des tests Java/SQL exécutés.

| Famille | UUID p=0 utilisables (valeurs n) | UUID p=1 (n) |
|---|---|---|
| COMMAND | 3,9,12,15,19 (k0…k4) | 1,2,4,7,8 |
| IDENTITY_REQUEST | 3,10,11,14,16 (k0…k4) | 2,5,6,8,9 |
| POT_EVENT | 2,3,4,11,14 | 5,8,9,12,13 |
| INVITATION_EVENT | 3,10,13,14,20 | 12,18,28,31,44 |
| IDENTITY_EVENT | 6,7,8,18,22 | 12,25,26,35,39 |
| COMMAND_COMPLETION_EVENT | 2,4,15,24,26 | 5,10,11,18,19 |
| IDENTITY_COMPLETION_EVENT | 17,21,24,25,27 | 3,5,11,14,15 |

Pour F-N, choisir successivement p0[0], p1[0], p0[1], p1[1] aux positions1…4. Le registre de fixture contient l’UUID de Consumption exact, la famille, p/K/U, les positions et motifs d’exclusion, les tokens privés, les deadlines/N/retryDelay, les rôles et le vecteur durable attendu **avant** les barrières. Un nouveau C technique est installé par administration explicite dans une cible de test distincte ; il n’est pas ajouté au catalogue productif neuf ni créé automatiquement par Discovery.

Image PostgreSQL17 proposée : reprendre le digest I3 `sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` si disponible et vérifier version effective ; toute substitution est déclarée avant campagne. Migrations WRITE : `db/v3-pot/migration`, `db/v3-pot-execution/migration`, `db/v3-consumption-write/migration` ; READ : fichier réel `app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql`. Le banc PRIMARY peut le résoudre filesystem avec racine/empreinte contrôlées comme I3, sans dépendance productive READ ajoutée pour une ressource.

Connexions distinctes pour D1, D2, F, renewal, admin et observateur. Barrières Java/wrappers de ports ou triggers **de fixture** aux instructions nommées ; pas de hook productif ni de sleep comme preuve. Archiver PID/xid8, pg_locks, pg_stat_activity, pg_blocking_pids lorsque l’attente est requise, clock_timestamp après verrou et issue physique. Un scénario SKIP LOCKED prouve une sortie/commit D ou renewal **pendant que le détenteur reste arrêté** ; un chronomètre « rapide » seul est insuffisant.

L’observateur ouvre un snapshot extérieur RR RO après résolution et compare sources, routes, ownership, K, slots, effets/résultats/événements/positions/compteurs pertinents. READ et WRITE sont photographiées séparément dans des états stables ; aucune transaction de preuve inter-bases inventée. Attendus par SQL direct/registre et SHA externe, jamais par Discovery, RoutingV1, mapper ou observe de production. Absence de slot après une finalisation n’est recevable qu’avec les faits I2/effet attendu et K.

## 17. Matrice exigences → oracles → méthodes → gates

**Toutes les lignes sont NOT RUN / À IMPLÉMENTER APRÈS GO.** Elles fixent les méthodes obligatoires du futur manifeste I4, sans renommer les L2M de l’allocation générale. Dans les tables, `n01` signifie exactement `DiscoveryPostgresTest.i4_n01` ; même convention b/x/p. G-C = gate COMMAND du §19, G-R = preuve directe READ ; G-A = architecture ; G-M = monolithe. Une méthode qui exerce plusieurs familles doit conserver les sous-variantes nommées dans ses traces/XML/inventaire, sans multiplier les garanties.

**Critères communs à CHAQUE ligne :** PASS borné si stimulus et ordre de barrières effectivement observés, issue physique connue, vecteur SQL avant/après conforme et oracle indépendant satisfait ; FAIL à toute perte/doublon/extra, demi-commit, réenrôlement terminal/Q, passage injustifié de K/cursor, droit ou attente interdite, cycle structurel ; INCONCLUSIVE si interleaving, état antérieur, résolution, source réelle requise, trace, méthode ou provenance manque. Timeout sans diagnostic n’est jamais PASS. L’absence de test vaut NOT RUN/INCONCLUSIVE, jamais succès. Les précisions PASS de chaque ligne complètent ces critères conjonctifs.

### 17.1 Non-burnables

N01/N02/N03/N08/N12 : cinq familles F-N obligatoires ; N01/N04/N05/N06/N11/N14/N15 : **aussi** deux sources réelles F-R. Autres N : POT F-N, plus la variante réelle lorsqu’indiquée. Identity/Invitation restent analogues techniques. Les tests d’atomicité N08…N11 conservent des coupures distinctes, pas une seule exception générique.

| Preuve / méthode | Contrat et oracle existant | Fixture et état avant | Transactions, synchronisation et stimulus | État extérieur attendu après résolution | Gate |
|---|---|---|---|---|---|
| I4-N01 / n01 | C01-A, E03-B ; OR-NB-SET | F-N K0, positions1…4, aucun slot | D lit page puis admet p0 et p1 dans unités distinctes | p0 slots{1,3}, p1{2,4}, K=4 chacun ; autres p indépendants ; F-R attendu calculé avant D | G-C |
| I4-N02 / n02 | C01-B, C02-F ; OR-NB-SET/ATOMIC | F-N fenêtre seulement hors p, stock déjà cap | D prépare/admet malgré zéro référence locale | K=H, zéro nouveau slot, autres C/p inchangés | G-C |
| I4-N03 / n03 | C01-C ; OR-NB-SET | F-N plusieurs centaines positions, W1/2/3 | Appels manuels répétés ; barrière entre chaque fenêtre | Union des préfixes exacte, aucune position omise aux bornes ; taille≤W et inserts≤B | G-C |
| I4-N04 / n04 | C01-D-COMMIT, E01-B ; OR-NB-SET/DENSE | F-N/F-R préfixe4, T1 publie5 non committé | Barrière après compteur T1 ; T2 publisher attend réellement ; D snapshot avant commit puis après | Avant : safe4/K≤4, aucun slot5 ; après commit : 5 puis T2 couverts exactement, pas de trou | G-C |
| I4-N05 / n05 | C01-D-ROLLBACK, E01-C/F ; OR-NB-SET/ATOMIC/DENSE | Même préfixe et T1 ouverte | Même ordre, rollback T1 puis résolution T2 | Compteur/position5 réutilisée par seul commit T2 ; D ne perd aucune publication | G-C |
| I4-N06 / n06 | C01-E, C02-G, E03-D ; OR-NB-FENCE/SET | Deux D préparent le même K | Bloquer D1 sous partition ; D2 saute ou attend sa prochaine invocation ; D1 commit ; soumettre page D2 périmée | Un seul delta de slots, K monotone ; D2 stale, repart du K durable | G-C |
| I4-N07 / n07 | C01-F, C02-H, C03-B ; OR-NB-FENCE/CL-OWNER | A prépare K0 sous ancien lease | Pause préparation ; expiration DB/takeover B ; A admet puis B admet | A zéro delta ; B seul produit préfixe/slots ; horloge et tokens DB établis | G-C |
| I4-N08 / n08 | C02-A/B, E03-E ; OR-NB-ATOMIC | F-N page avec≥2 locales, K0 | Deux sous-cas : faute avant INSERT1 ; puis après INSERT1 avant INSERT2 ; résolution rollback observée | K0 et zéro nouveau slot dans chaque cas ; reprise complète | G-C |
| I4-N09 / n09 | C02-C ; OR-NB-ATOMIC | F-N K0 | Faute après dernier INSERT avant CAS K | Tous INSERT annulés ; K0 ; aucune demi-fenêtre | G-C |
| I4-N10 / n10 | C02-D ; OR-NB-ATOMIC | F-N K0 | Barrière après CAS K, coupure/rollback avant commit | Lecteur extérieur ne voit jamais le K isolé ; après résolution K0/zéro slot | G-C |
| I4-N11 / n11 | C02-E, E03-F ; OR-NB-ATOMIC | F-N/F-R K0 | Proxy filaire supprime ACK **après commit serveur** ; seconde variante coupe avant commit, résolution contrôlée | Commit : K=H et slots exacts ; rollback : K0/aucun ; nouvel appel idempotent, aucun résultat de progrès supposé | G-C |
| I4-N12 / n12 | C01-G, R2-01…05 ; OR-NB-CAP | F-N locales1,3 ; stock63/cap64 | D COUNT puis admission ; marge épuisée après1 ; libérer capacité par vraie completion technique puis reprendre | Premier K2/slot1 ; second inclut3 ; variantes marge0 et limite B ; jamais K≥3 sans couverture | G-C |
| I4-N13 / n13 | SPEC-03§7, R3 ; OR-NB-SET/AR-ROUTE | IDENTITY_EVENT : POCOMA_USER puis EUI locale ; K0 | D CurrentBinding avec filtre fixé ; variantes autres partitions/aucune EUI | K examine les deux ; slot EUI seul ; aucun slot User ou mauvais p | G-C |
| I4-N14 / n14 | C01-H, C02-I/J/K ; OR-NB-SET/AT-UNIT/RC-FOUR | K≥position, slot admis | Acquisition I3 puis effet technique I2 ; rollback précommit et commit ACK perdu séparés ; redémarrer D | Rollback conserve slot ; commit donne effet exact/slot absent ; D ne recrée rien derrière K | G-C |
| I4-N15 / n15 | C01-K, E03-H ; OR-NB-SET/AT-READ | Historique conservé F-R ; C ancien avec K/effets | Admin installe C technique neuf dans destination isolée, K absent ; D neuf et ancien | K neuf part0, slots propres ; ancien intact ; aucun effet métier double prétendu | G-C |
| I4-N16 / n16 | C02-L, R3-05/07, R8 ; OR-AR-NOPURGE/AR-ROUTE | F-N K et slots existants, rôles réels | Tentatives reset partiel/K décroissant/burnable checkpoint/route erronée ; SQL rejeté et rollback | Témoins intacts ; routes/hash et K incohérents ne sont pas masqués ; pas de procédure de reset livrée | G-C, G-R ACL |
| I4-N17 / n17 | C01/C02 négatifs ; OR-NB-SET/ATOMIC | Journal fixture volontairement troué ou enveloppe absente, safe incohérent | Préparation lit compteur et lignes dans RR ; une variante publication entre les SELECT | Faute explicite sans delta K/slot ; snapshot cohérent conserve son préfixe ancien | G-C |
| I4-N18 / n18 | R2/R3, C06-K ; OR-NB-FENCE/AT-POISON | Lease valide au premier contrôle | Verrou relation contrôlé entre protection et INSERT/CAS ; clock franchit deadline ; libérer avant timeout dans sous-cas | Horloge post-attente entraîne rollback intégral, aucun K/slot sous lease expiré ; autre cas timeout diagnostiqué | G-C |

### 17.2 Burnables

Chaque B exécute COMMAND réel et IDENTITY_REQUEST **fixture** ; les sous-variantes états/commit/rollback sont inventoriées séparément. Le cas de finalisation métier réelle concerne Command ; l’analogue Identity ne certifie pas L3.

| Preuve / méthode | Contrat et oracle existant | Fixture et avant | Transactions et barrières | État SQL et progression attendus | Gate |
|---|---|---|---|---|---|
| I4-B01 / b01 | C01-I-NOSLOT ; OR-B-SET | F-B PENDING k1 sans slot | Admission source committe avant D ; barrière après admission initiale | Source reste PENDING et possède exactement1 slot initial ; aucun K burnable | G-C |
| I4-B02 / b02 | C01-I-EXISTING ; OR-B-SET | PENDING avec slot libre puis claim actif | D repeated, claims acquis par I3 dans variante active | Même clé/token/champs, aucun INSERT ni effet | G-C |
| I4-B03 / b03 | C01-J ; OR-B-SET | Claim expiré, source PENDING | D avant acquisition de reprise I3 | Pas de nouveau slot/effacement du token ; I3 seul reprend normalement | G-C |
| I4-B04 / b04 | C01-J, C05-F ; OR-B-SET | Slot retryAfter futur | D avant échéance DB, puis après avec même slot | D ne contourne pas le retry, source/slot identiques ; reprise I3 inchangée | G-C |
| I4-B05 / b05 | CON-05-DISCOVERY/PERSIST ; OR-B-SET/RT-QUAR | Q persistante/PENDING, stock hors-Q libre | Redémarrer Engine Discovery et faire plusieurs tours | Q exclue COUNT mais jamais recréée/reset ; même count/claimNULL | G-C |
| I4-B06 / b06 | C01-I-TERMINAL, E02-J ; OR-B-SET/AT-UNIT | TERMINAL par vrai I2, aucun slot | Page ancienne capturée PENDING avant F ; F commit puis D admission | Aucun réenrôlement ; résultat/complétion originaux uniques | G-C |
| I4-B07 / b07 | R4-07, C04-I ; OR-B-SET/AT-UNIT | PENDING avec slot ; F arrêté avant FK source | D protège source, barrière ; F tente résultat/FK ; D constate slot et commit ; F reprend | Attente F→D démontrée, aucune attente D→slot ; F termine exactement1 fois | G-C |
| I4-B08 / b08 | R4-07, C01-I ; OR-B-SET/AT-UNIT | F arrêté après FK puis après UPDATE puis après DELETE dans trois sous-cas | D tente source SKIP pendant F ouverte ; maintenir F à la barrière jusqu’à retour D | D zéro INSERT, clé examinée ; commit F⇒terminal sans slot, rollback F⇒slot original ; prochaine visite correcte | G-C |
| I4-B09 / b09 | C01-I, R2-02 ; OR-B-SET/NB-CAP | Deux D pages mêmes k1/k2 sans slot, même lease | D1 partition sous barrière, D2 concurrent ; résoudre D1 puis rejouer D2 | Un seul slot par clé, stock≤marge observée ; aucune collision masquée | G-C |
| I4-B10 / b10 | R4-04/05/10 ; OR-B-KEYSET | k1<k2<k3, U=k3, cursor atteint k2 | Insérer k0 derrière curseur après barrière page ; terminer puis nouveau tour | k0 non couvert au premier tour, admis au suivant ; PENDING jamais perdu | G-C |
| I4-B11 / b11 | R4-03/04 ; OR-B-KEYSET | Même tour U=k3 | Insérer k4>U après capture U | Premier tour ne franchit pas U ; prochain capte nouvelle U et visite k4 | G-C |
| I4-B12 / b12 | R4-10-SKIPPED ; OR-B-KEYSET | B1, première clé hors p ; deuxième déjà slot ; troisième source verrouillée ; suivante admissible | Pages brutes non vides mais toutes filtrées ; maintenir source lock puis libérer au tour suivant | cursor franchit chaque clé examinée et atteint la suivante ; page filtrée vide ne clôt pas ; sautée revisitée | G-C |
| I4-B13 / b13 | R4-06, R2-03 ; OR-B-SAT | Deux PENDING sans slot, cap occupé | D à cap plein, puis libérer capacité par completion I2 ; reprendre walk | examined0 à saturation initiale, aucun K ; chaque PENDING finit admise sous appels/liberté établis | G-C |
| I4-B14 / b14 | R4-05/06 ; OR-B-SAT | cap donne marge1, page k1/k2/k3 | D insère1 puis stop ; barrière avant appel suivant et retour capacité | next.after=k1 seulement ; k2/k3 restent PENDING sans marque de couverture ; suite admise | G-C |
| I4-B15 / b15 | R4-08 ; OR-B-CRASH | Tour après k2, PENDING k0/k4 | Arrêt du caller ; expiration/takeover réel B ; nouvel objet/walk vierge | Repart du début, visite k0/k4 et respecte slots existants ; aucun état cursor en SQL | G-C |
| I4-B16 / b16 | R4-09/10 ; OR-B-KEYSET/OBS-METRIC | Population initiale finie, page2/cap contrôlée | Insérer à chaque barrière page des clés derrière, entre cursor/U et au-delà ; périodes libres garantissant admissions | Tours terminent sur population de campagne documentée ; U non étendue ; ensemble initial et arrivées finalement revisités, sans promesse N0/B | G-C |
| I4-B17 / b17 | C06, R4-08 ; OR-B-CRASH/AT-UNIT | Page de PENDING sans slot | Faults après INSERT1/dernier, puis wire ACK avant/après commit ; reprendre walk d’entrée | Rollback zéro slots, commit sous-ensemble atomique attendu ; second appel ne duplique rien ; aucune terminalisation/cession par D | G-C |

### 17.3 Interactions I2/I3, droits et architecture

| Preuve / méthode | Norme / oracle | Fixture avant et séquence/barrières | État avant→après et critère décisif | Gate |
|---|---|---|---|---|
| I4-X01 / x01 | R3-01…09, C03-B ; OR-AR-ROUTE/CL-OWNER | F-X ; mauvaises famille/version/P/source ; route admin verrouillée dans les deux ordres runtime/admin | Refus avant gains ; admin/runtime sérialisés ; aucun delta sous mismatch ou ancien owner ; vecteurs Java/SQL/Python concordants | G-C |
| I4-X02 / x02 | C03-A/B/C, R10-09 ; OR-CL-OWNER/RENEW | A perd partition à B avec claim S valide ; A tente D, renouvelle/termine S ; B D d’autre source | A zéro nouveaux slots, claim renewal/finalisation autorisés ; B progresse indépendamment ; état K/slots exact | G-C |
| I4-X03 / x03 | R2-06…09, CON-05-REACTCAP ; OR-NB-CAP/RT-REACT | stock64+Q ; D COUNT sous partition, admin CAS Q→AVAILABLE avant/après COUNT dans deux essais | Réactivation même slot/count0, éventuel dépassement R2 admis ; prochain D n’insère plus ; autres slots restent traitables | G-C |
| I4-X04 / x04 | C03/C04/R10-08 ; OR-CL-RENEW/AT-UNIT | F tient slot S1 ; D travaille autre source ; renewal S1/S2 et lease sur connexions distinctes, échéances DB archivées | S1 sauté, S2 renouvelé avant deadline pendant F ouverte ; D n’acquiert aucun claim ; preuve primitive, ressources Worker intégré toujours I5 | G-C |
| I4-X05 / x05 | R1/R5/R9, C06-K ; OR-AR-BOUND/AT-UNIT | WRITE JPA et READ JDBC réels ; tracer PID/xid des INSERT slots/K, puis tentative/cession séparées | Slots+K même xid READ, aucune mutation WRITE de D événementiel ; F/cession I3 propres ; aucune transaction inter-base | G-C |
| I4-X06 / x06 | C06-E…I/M ; OR-AT-POISON/AR-BOUND | Writers Discovery gardés ; faute Java/SQL interceptée, mauvais datasource, ambiance/nested/REQUIRES_NEW aux INSERT/K | Refus/poison et zéro delta extérieur, aucune écriture indépendante ; corps owner I2 inchangé | G-C |
| I4-X07 / x07 | R1-10/R4-07 ; OR-AT-UNIT/AR-BOUND | SQL isolé A naïve→cycle attendu ; A corrigée→attente source/expiry ; B→b07/b08 ; deux Command mêmes/différents Pot/EUI et contre-lock counter | Contre-exemple A naïve archivé, rollback intégral ; B sans attente D sur slot ou source F, F finit après libération, aucune seconde complétion | G-C |
| I4-X08 / x08 | R8, R3/R7 ; OR-AR-NOPURGE/BOOT | Catalogue pg_constraint/index/trigger/pg_get_functiondef, grants table/colonne/fonctions, scanners sources/writers | Aucune nouvelle FK/cascade/source writer/DELETE ; seuls droits §13 ; baselines/migrations intègres | G-C, G-R |
| I4-X09 / x09 | E03-B/C/D/G, C06-P ; OR-AT-READ/NB-SET | Même POT_EVENT, trois C READ du catalogue avec participants techniques indépendants ; échec d’un D/F, autres résolvent | Trois espaces de slots/K autonomes, autres avancent ; aucune tâche/famille/ordre inter-C ; **pas PASS projections L4** | G-C |
| I4-X10 / x10 | E02-A/B/C/J, R1/R9 ; OR-L1-STATE/AT-UNIT/RC-FOUR | Admission PENDING réelle→D→acquire I3→F Command pour Changed/Unchanged/Rejected ; ACK perdu et nouvelle visite D | Intention immuable ; mutation éventuelle/terminal/résultat/completion/suppression uniques ; D ne ressuscite rien | G-C |

Méthodes complémentaires exactes proposées :

| Classe / méthodes | Contrat, stimulus, oracle et résultat attendu | Gate |
|---|---|---|
| `ReadDiscoveryPrivilegesPostgresTest.effectiveDiscoveryColumnPrivileges` | Migration READ réelle puis script psql livré ; vrais rôles ; INSERT slot/K et UPDATE position autorisés, INSERT explicite claim/state/attempt, UPDATE clé/hash, DELETE K, DDL et route interdits ; refus rollback target/membership/broad grant ; état témoins intact, OR-AR-ROUTE/BOOT/NOPURGE | G-R et G-C si présent dans fermeture |
| `DiscoveryCompositionPostgresTest.sharedPhysicalOwnerAndAuthorities` | Même instance owner injectée Discovery/lifecycle par autorité ; deux bases distinctes ; mauvais owner/source/autorité refusé ; PID/xid du test x05 et assertions de factory ; aucun second owner/manager ajouté | G-C |
| `DiscoveryCompositionPostgresTest.explicitPrivilegesDoNotActivatePilot` | Profil lifecycle-only rejette droits Discovery, profil union les valide explicitement ; validate I1 les refuse toujours ; aucune factory installe runner/poller/endpoint | G-C, G-M isolation |
| `V3ConsumptionEngineArchitectureTest.discoveryPortsAndWritersRemainBounded` | Nouvelle méthode proposée : pureté/absence types métier/JDBC en Engine, nouveau writer sans transaction/connexion/EM brute, tests négatifs concrets, interdits cache durable/Locator/scheduler ; OR-AR-BOUND/CERT | G-A |

Pour chaque ligne ACL/composition/architecture : conserver snapshot initial, paramètres, ordre des constructions/tentatives interdites, exception réelle et état SQL final ; appliquer les trois verdicts communs, jamais conclure depuis un simple grep. Les tests existants d’inactivité I1/monolithe et leurs négatifs restent obligatoires, pas remplacés par ces nouvelles méthodes.

### 17.4 Raccord et limites par exigence

| Exigence / lignes L2M existantes | Contribution directe I4 | Héritage à réexécuter / reports conservés |
|---|---|---|
| CON-01 C01-A…L et sous-variantes D/I/J | N01…N07/N12…N18, B01…B17, P01…P03 ; sources Command/POT/COMPLETION réelles et analogues nommés | Producteurs Identity/Invitation réels L3/L5 ; coûts globaux10M et instrumentation Worker I5/L6 ; ne pas clore CON-01 global |
| CON-02 C02-A…L | N08…N11 atomicité admission, N14 non-réenrôlement ; N16 refus reset partiel | I2 finalisation et R9 restent preuves héritées, nouveau croisement N14 ; reconstruction READ métier et E03-I→L6 |
| CON-03 C03-A/B/C/K/L | Moitié Discovery laissée I3 ouverte : N07/X01/X02 ; renewals en concurrence X04 | Toutes variantes génériques I3, y compris fence final après attente, recontrôlées par manifestes ; ressources/cadences intégrées I5 ; aucune nouvelle sémantique de claim |
| CON-04 C04-A…L | B06…B09/X07/X10 : admission contre terminalisation, pas de double effet | R1/slot/token/ACK/poison I2 et corrections I3 en non-régression ; vraies projections hors ordre L4 ; workers répétés I5 |
| CON-05 DISCOVERY/SOFTCAP/REACTCAP/PERSIST/NOPURGE | B03…B05/B13/N12/X03/X08 | Budget/retry/cession/admin I3 inchangés ; V/V+1 métier et HTTP L4/L5, équité intégrée I5 |
| CON-06 K/J/M, R1/R9 | N11/X05/X06 : lecture WRITE séparée, admission READ atomique et guard des nouvelles entrées | Finalisation physique héritée I2 ; Identity réel B et projections C/D/N/O/P réelles L3/L4 ; analogue X09 ne remplace pas la destination métier |
| EVT-01 A/B/C/F et ordres G disponibles | N04/N05 consomment vrai suffixe POT→COMPLETION et bornes cohérentes ; fixtures cinq familles | Densité producteur I2 héritée ; producteurs Identity/Invitation réels L3/L5 ; charge toutes familles L6 |
| EVT-02 A/B/C/G/J | X10 rattache admission durable à Discovery puis vraie terminalisation, aucune résurrection | Coupures de publication et gardes héritées I2 ; Identity/Invitation réels D/E/F/I→L3/L5 |
| EVT-03 A/B/C/D/E/F/H | Catalogue et indépendance technique N01/N06/N08/N11/N15/X09 | Trois transformations métier et hors ordre G/J L4 ; reconstruction I L6 ; pas de tâche intermédiaire |
| R2/R4/R7/R8 | Couverture directe du stock/préfixe/tours/terminal/Q/fences/absence purge ci-dessus | R10 round-robin, réservations/exécuteurs et métriques branchées I5 ; aucune promotion automatique |
| OPS-01 / PERF-01 / REC-01 | Contributions locales de progression et coûts mesurés, négatifs reset | Certificats globaux INCONCLUSIVE : I5/L6 puis code unifié L7 ; HTTP/Bruno L5, installation L8 |

Le futur manifeste doit relier **chaque** L2M concerné à ces méthodes et sous-variantes, conserver les autres lignes/reportées et distinguer DIRECT / INHERITED-FRESH-REGRESSION / PARTIAL / DEFERRED. Les noms `L2ManifestProof.l2m_*` historiques restent l’allocation générale ; ne pas créer des wrappers vides pour afficher une méthode par ID. R6 exige l’intégralité du contrat global et permet une contribution locale bornée, pas l’effacement d’un scénario.

## 18. Qualification locale des coûts et de la progression

Réutiliser la méthode I3 `i3_sql_costs` (PostgreSQL EXPLAIN ANALYZE BUFFERS WAL JSON, mix explicite de slots) et le harnais I2/F2 (mesures de phases, requêtes et ResultSet effectivement consommés, échauffement/répétitions, avant/après appariés). Ne pas requalifier les médianes I2 comme performance Discovery.

Les trois méthodes ci-dessous sont `DiscoveryPostgresTest.i4_p01`, `i4_p02`, `i4_p03`, obligatoires dans **G-C**. Elles appliquent les critères PASS/FAIL/INCONCLUSIVE du §17 ; pas de PASS de performance sans jeu/mesures comparables ni de SLO implicite en l’absence de seuil contractuel local.

| Preuve / méthode proposée | Jeu et variation préinscrits | Mesures, oracle et critère |
|---|---|---|
| I4-P01 / p01 | W=1/32/128, B=1/16/32, cap=1/64 ; 100/1000/10000 positions, 0/25/100% local ; cinq sources techniques et deux réelles lorsque possible | Références globales/enveloppes/lignes JDBC, slots/K, transactions, buffers/WAL/index ; tailles≤W/B, exactitude N01/N12 ; COUNT indépendant concordant ; hors-partition ne provoque pas de payload métier |
| I4-P02 / p02 | 100/1000/10000 PENDING, proportions slots actifs/expirés/retry/Q, pages1/16/32, clés hors p et pages filtrées | Keyset uses pending index/PK selon planner, aucune histoire TERMINAL transférée ; requêtes/pages/tours, locks source sautés, throughput admission et coût par nouveau slot ; B12/B16 restent corrects |
| I4-P03 / p03 | Stock100/1000/10000 par p, variations Q0/50/90%, cap plein puis rendu, admin et renewals concurrentes bornées | Coût COUNT, durée sous partition/source, attente relation/unikité, renewals S2, retries de Discovery, mémoire temporaire et WAL ; vérifier retour de progression sous capacité/liberté attestées |

Chaque mesure conserve schema/catalogue/statistiques, taille et distribution du jeu, SHA, version image et paramètres ; cinq échauffements puis dix échantillons par configuration locale comparable comme point de départ, sans builds Maven parallèles. `EXPLAIN (ANALYZE,BUFFERS,WAL,FORMAT JSON)` de DML uniquement sur cible jetable et transaction annulée, clairement distincte des commits de correction ; pas d’analyse de DML sur une base utilisateur.

Attendus de coût : page source bornée par W ou B, préparation O(W) en mémoire au plus ; admissions ≤min(marge,B), pas de payload Pot/histoire/claims chargés. COUNT reste proportionnel au stock/index réellement parcouru, **pas O(1)** ; les clés d’autres partitions peuvent coûter des lectures globales répétées, à mesurer. Réutiliser PK v3_event_position(family,position), index partiel `v3_command_pending_request_idx`, PK slots et `consumption_slot_live_count_idx` ; compter entretien index, trigger hash, heap fetch, vacuum et WAL. Ne pas forcer le planner ni exiger un index sur petite table.

Aucun nouvel index proposé avant plan réel et besoin démontré ; un index de hash sur v3_command imposerait une expression calculée et entretien durable sans nécessité de correction. Une nouvelle colonne de routage source est également injustifiée. Les chiffres100/1000/10000 sont une qualification locale, pas les profils globaux1M/10M de SPEC-09/10. Saturation : appels manuels espacés, compte SQL par passage, aucune boucle chaude introduite. Les conditions de progression ont des nombres de tours et opportunités de campagne préenregistrés ; si leur observation manque, INCONCLUSIVE.

Mesurer la durée maximale observée de verrou partition/source et ses quantiles sur l’échantillon, l’accès connexion, le délai de dispatch **du harnais** et les marges DB de renewals. Réduire W/B avant d’ajouter un mécanisme. L’échéance post-lock est contrôlée, mais son succès n’assure pas une réservation de ressources. R-AUD-I3-03 reste I5 : Discovery devra emprunter la voie de contrôle bornée prévue, sans consommer les accès renouvellement. Aucun délai maximal universel ni SLO E2E/balance/HTTP annoncé ; les objectifs SPEC-09 restent à prouver sur les chaînes complètes.

## 19. Verification scope futur, graphe Maven et gates

Déclaration proposée **avant toute implémentation I4**, sous la [politique normative](../testing/Reactor_Verification_Policy.md), notamment §§3/14/17/19/22/29/34 :

| Champ | Périmètre déclaré |
|---|---|
| Expected production impact | engine-consumption (Discovery et ports), infra-persistence-primary-jpa (sources/admission), composition-runtime-spring (factory/ACL), script administratif ; tests READ/Command/monolithe/architecture et collecte |
| Primary slice | **COMMAND**, vrai pilote Command et non-régression lifecycle/participant I2/I3 |
| Secondary canonical slices | **NONE** : aucun comportement runtime EVENT/PROJECTION/BINDING/WEB/LKV modifié ; primitives communes et READ technique n’activent pas leurs anciens runtimes |
| Direct complementary proofs | `infra-persistence-read-jdbc` (ressource READ réelle, ACL sur son classpath propre) ; `runtime-monolith` (racine/isolation effectivement différente du worker Command) |
| Forbidden production impact | Owner/résolution/poison I2, moteur lifecycle I3, commandes métier, nouveaux producteurs/projections, POM/modules, migrations, Workers/pollers/HTTP ; nettoyage historique et production |
| Required local proof | Toutes méthodes/sous-variantes §§16–18, vrais rôles/two bases/PID/xid/SQL/barrières, non-régression fraîche I2/I3/post-audit et C/F/F2 |
| Global architecture gate | **REQUIRED à clôture I4**, car nouvelles entrées writer/ports et règles de forbidden dependencies/isolation sont explicitement étendues ; pas dans chaque boucle de feedback |
| Full reactor | **NOT REQUIRED pour I4 borné** : pas restructuration Maven, clôture majeure L2, intégration main ou stabilisation globale ; impact bornable |
| Database | **CURRENT V3 neuf**, migrations isolées déjà livrées, bootstrap administratif et script de grants sur cibles de campagne jetables ; aucun HISTORICAL séparé ni baseline V2 TRUSTED présumée |
| Next mandatory global gate | Architecture pour I4 ; architecture + `./mvnw test` au G3/clôture majeure L2, et gates main/réorganisation large prescrits par la politique |
| Escalation conditions | Besoin de modifier un owner, métier, runtime non déclaré, POM, DDL/index ou contrat source : expliquer frontière et réviser scope avant code. Full seulement si gate/impact le requiert, pas pour diagnostiquer un échec de slice |

**Graphe statique vérifié par lecture XML des POM à `798eeb21` :** 60 modules enfants, noms artifactId préfixés `pocoma-` tandis que `-pl` ci-dessous emploie les répertoires. `engine-consumption→domain-consumption/port-transaction` ; PRIMARY→engine-consumption/engine-write-pot/infra-tx-spring ; composition→PRIMARY/READ/TX et engines/supras historiques ; worker Command→composition/PRIMARY/TX ; monolithe→composition et ses propres racines ; architecture-tests→runtimes et infrastructures. READ n’a pas de dépendance productive vers PRIMARY et TX n’a que port-transaction côté Pocoma.

Ensemble statique des dépendances internes directes/transitives de tout scope, racine exclue : COMMAND50 modules enfants, MONOLITH50, READ14, TRANSACTIONS2, ARCHITECTURE60. Ce calcul n’est pas une exécution Maven et n’est pas un second catalogue normatif du réacteur. COMMAND inclut PRIMARY, READ et TX par composition ; architecture inclut les runtimes de test. Un XML frais manquant reste une absence, même si le module est présent au graphe. Les mêmes méthodes répétées entre gates ne sont jamais additionnées comme garanties distinctes.

Commandes **futures, non exécutées dans la préparation**, depuis `app/`, sur un seul SHA final de code propre, sans filtre/exclusion :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

`./mvnw -pl infra-tx-spring -am test` a été examiné : owner inchangé, ses méthodes sont attendues fraîchement dans la fermeture COMMAND/architecture ; pas de campagne autonome supplémentaire imposée par défaut. FOUNDATION seule ne prouve pas Discovery productive. Si une correction d’owner devient nécessaire, redéclarer la frontière et ajouter cette preuve directe, sans attribuer à une fixture FOUNDATION la preuve des consommateurs.

EVENT `./mvnw -pl runtime-event-consumption-worker -am test` devient secondaire si son comportement/raccord change effectivement ; PROJECTION `./mvnw -pl runtime-task-consumption-worker -am test` si une matérialisation/runtime est touchée ; BINDING `./mvnw -pl runtime-binding-consumption-worker -am test` si son contrat productif change, jamais parce qu’une fixture Identity existe. Ce ne sont pas des modifications autorisées implicitement. Ne pas introduire un runtime ancien pour obtenir une ancre de test V3. Aucun `-amd` par défaut.

Les gates non filtrés peuvent réexécuter des migrations historiques transitives V2 : archiver ce coût réellement observé, sans l’appeler bootstrap I4 ni lancer une campagne historique en plus. Les fichiers de migrations sont conservés ; la politique rappelle qu’aucune baseline historique V2 n’est TRUSTED.

### Traçabilité et collecte futures

Avant campagnes : manifeste exact méthode→sous-variante→norme/oracle→autorité/réel-fixture→gate ; SHA et modes fichiers, état Git, cibles/rôles non secrets, paramètres/seed, sources des instruments, Java/Maven/PostgreSQL/Docker/OS, digest image et dépendances effectivement utilisées. Photographier les entrées et leurs SHA256 avant/après, puis comparer code testé→commit publié→tête distante ; exclure seulement les artefacts documentaires/archives explicitement inventoriés.

Le collecteur I4 reprend l’approche de `verify-v3-l2-i3.py`, sans modifier ses résultats historiques : présence unique des méthodes obligatoires, XML **frais**, états erreurs/skips, traces SQL/PID/barrières/commit de toutes sous-variantes, archives et empreintes. Tests du collecteur : méthode absente, skip obligatoire, doublon XML, testcase échoué, trace périmée, sous-variante famille/autorité/ACK absente et code source discordant doivent chacun être refusés. Noms `*Test` détectables par Surefire ; ne pas répéter le défaut historique I2 d’un fichier de preuve compilé mais non exécuté.

Conserver échecs initiaux, diagnostics et relances ; BUILD SUCCESS ne remplace pas les oracles. Le skip historique `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` reste SKIP, pas PASS, distinct des obligations I4. Aucune méthode I4 obligatoire ignorée. Tous les défauts démontrés ouverts donnent FAIL ; variante absente sans violation donne INCONCLUSIVE global ; aucun verdict rétrospectif par changement de seuil.

## 20. Compatibilité I5 et disponibilité Java/runtime/HTTP/Bruno

L’appelant I5 pourra conserver un RequestWalk volatil par `(C,p,instance)`, appeler une opération Discovery bornée à chaque opportunité puis fournir les slots au lifecycle existant. Il abandonne le walk au takeover/redémarrage ; pas de copie de K/slots/claims en mémoire faisant autorité. Les renouvellements de claims ne dépendent ni du succès Discovery ni de son lease. Le résultat distingue références examinées, slots réellement insérés et absence de progrès ; I5 devra régler sa cadence sur le progrès effectif, pas sur une page préparée ou un callback précommit.

I5 reste responsable des deux boucles indépendantes, rotation entre C/p, navigation circulaire des **slots** pour acquire, dispatch et budgets M_C/M_W, exécuteurs/pools effectivement réservés, appels settlePending et suivi des claims, cadences et instrumentation. Le curseur source burnable I4 ne devient pas celui des slots I3/I5 : ils parcourent deux populations différentes, sans nouvelle identité de travail. Discovery utilise la voie de contrôle bornée de la même autorité ; l’inventaire exhaustif des emprunteurs et les opportunités sous charge restent à prouver I5. Les paramètres W128/B32/cap64 sont expérimentaux, non dimensionnement.

| Étape | Primitives Java et PostgreSQL | Composition / Workers actifs | HTTP et Bruno réellement possibles |
|---|---|---|---|
| Après I3 / maintenant | Lifecycle/participants réels invocables par factories/tests ; source PENDING interne, Discovery absente | Profil `v3-consumption-pilot` inerte ; factories statiques ; Workers historiques seulement sur leurs bases isolées | Aucun parcours V3 Discovery via HTTP ; collection historique ne certifie pas V3 |
| Après I4-1 | Signatures et algorithmes unitaires directement appelables | Aucune activation | Rien de nouveau dans Bruno ; pas de transaction PG prouvée par une unité mockée |
| Après I4-3/4/5 | Sources/admission/K/PENDING testables sur PostgreSQL neuf par appel Java ; harnais direct possible sans I5 | Pas de boucle ; source réelle Command/POT/COMPLETION, autres familles techniques | Aucun endpoint ajouté ; Bruno ne peut appeler une méthode Java ou SQL interne |
| Après clôture locale I4 | Factory inactive construisible explicitement ; Discovery→acquisition→F démontrable manuellement, deux autorités | Aucun Worker V3 lancé automatiquement ; état pilote I1 toujours inerte | Aucun parcours API V3 bout en bout supplémentaire. Actuator historique éventuel ≠ API métier ; aucun endpoint de diagnostic I4 |
| Après I5, sous son GO propre | Boucles et pilote Command/banc READ intégrés **si livrés et qualifiés** | Premiers Workers V3 intégrés au périmètre approuvé I5 ; pas activation production implicite | I5 ne livre pas par lui-même l’admission/lecture métier L5 ; présence du module runtime ≠ API V3. Bruno reste limité aux endpoints effectivement exposés |
| Après L3/L4/L5 | Sources Identity/projections métier et Supras selon leurs livraisons | Intégration réellement câblée, autorisations qualifiées | Parcours V3 Bruno Registration/Command/Result/Binding/Pot/Invitation selon routes livrées ; finalisation de distribution L8, SLO L6, aucune route future supposée |

La première testabilité physique I4 est donc I4-3/4 pour les sources/admissions non-burnables, I4-5 pour burnables avec finalisation concurrente ; la première testabilité Java pure est I4-1. Aucun lancement de stack, endpoint provisoire ou script Bruno nouveau n’est requis pour fermer I4.

## 21. Limites de certification et reports

Le verdict futur maximal visé est **I4 LOCAL BOUNDED PASS** sur les primitives/sources disponibles, sous réserve de toutes preuves obligatoires. Ce n’est ni ACCEPTED L2, ni G1/G2/G3, ni PASS global CON/EVT/OPS/PERF. La présente préparation ne revendique même pas ce PASS local futur.

Garanties directement I4 : couverture des préfixes, K/slots atomiques, contrôle softCap, idempotence d’admission, revalidation de source, U/cursor/revisite locale, ownership de Discovery et compatibilité observable avec le lifecycle. Héritage : fencing final, suppression R1, poisoning, suffixe dense, quatre observations R9, leases/claims/cession/quarantaine/réactivation et tickets corrigés. Réexécuter leurs obligations de non-régression n’en change pas le propriétaire.

Reports nominatifs :

* **I5** : deux boucles, rotations, progression/équité entre C/p et slots sous charge, cadences, accès réservés et tous emprunteurs (R-AUD-I3-03), instrumentation Worker, campagne intégrée repeated crashes/saturation.
* **L3** : vrai IDENTITY_REQUEST/IDENTITY/IDENTITY_COMPLETION et autorité EUI ; tests réels de son finaliseur et de ses dépendances spécifiques. Le contrat port I4 ne prétend pas prouver ses futurs verrous métier.
* **L4/L5** : vraies matérialisations PotVersion/Balance/Visibility/Binding/résultats, CurrentInvitation et producteur Invitation, HTTP/consentement/Bruno ; analogues techniques conservent leur limite.
* **L6/L7/L8** : reconstruction READ métier/READY et maintenance exclusive, métriques/SLO/charge globale, convergence du code puis packaging/installation. Aucun reset ou certificat de non-famine universel en I4.

La nouvelle Consumption technique N15 prouve un nouvel espace d’admission ; elle ne certifie ni une procédure de reconstruction à mêmes IDs ni la sûreté de deux writers sur la même projection métier. Aucun PASS de producteur/projection absent, de scénario mocké ou de test simplement présent dans le code.

## 22. Risques, choix structurants et arbitrages éventuels

| Risque / décision explicite | Recommandation et preuve / conséquence |
|---|---|
| Ordre source/slot face à F, attentes FK et unicité | **B proposée**, ordre détaillé §11, aucune modification F, INSERT frais strict ; b07/b08/x07 obligatoires. A naïve rejetée, A corrigée documentée. Audit puis GO doivent approuver ce choix ; un cycle démontré le bloque |
| Non-famine sous SKIP LOCKED | Garantie locale conditionnelle de revisite et d’admission sous opportunités libres, pas échéance universelle ; B16/X04 et campagnes I5. Si la norme est interprétée comme exigeant succès face à locks adversariaux renouvelés à chaque visite, **AR-I4-PROGRESSION** : arbitrer une protection bornée avec attente et son graphe, ou préciser les hypothèses. Ne pas ajouter de liste durable de clés sautées |
| Union ciblée des droits runtime | Profil union explicite §13, même owner/source, rôle admin/réactivation toujours distincts. **AR-I4-PRINCIPALS** seulement si une séparation stricte des logins Discovery/lifecycle est exigée : comparer deux sources/managers vs union ciblée, effets sur I3/I5 ; sections7/13/14/20 d’assemblage conjoint conditionnées. Pas SET ROLE/membership caché |
| Densité supposée depuis un MAX ou jointure permissive | Compteur et positions/enveloppes dans RR même snapshot, validations de trou ; N04/N05/N17. Producteurs absents = fixtures ; aucune mutation du schéma réel pour les simuler |
| K avancé au dernier événement local ou au H initial malgré cap | Préfixe global recalculé sous partition ; N02/N12 ; rowcount/CAS exacts ; aucun slot local oublié |
| Vérification source obsolète ou slot Q « disponible » | Protection PENDING puis existence MVCC toutes lignes ; B03…B09 ; jamais ON CONFLICT permissif/UPDATE d’un doublon |
| Lots prolongés / expiration pendant relation lock | Taille bornée + timeouts et horloge post-attente ; rollback du lot expiré, N18 ; durée réelle et renewals mesurés ; pas de garantie pendant panne/DDL permanent |
| Future Identity modifie les chemins de locks | Port avec précondition source protégée mais adaptateur réel différé L3 ; refaire graphe réel lors du raccord, pas copier la preuve Command comme certificat Identity |
| Factory union assouplit I1 ou duplique manager | Tests profils stricts, owner injecté partagé, aucun catch-fallback/scan ; X05/X06 et composition ; nouvel owner requis ⇒ réviser le plan, pas changer I2 silencieusement |
| Présence de code historique active un second writer | Gardes existantes et nouvelles négatives worker/mono ; aucune migration historique/polling contre cible I4 ; retrait global L7 |
| Faux PASS par archives, fixtures ou XML manquant | Manifestes/sous-variantes/SHA/digests exacts et collecteur strict ; aucun autoaudit présenté comme audit indépendant |

**Aucun changement normatif n’est nécessaire pour la proposition B et les primitives décrites.** Les noms de méthodes/packages/DI mineure sont des choix techniques ordinaires, à résoudre sans arbitrage humain supplémentaire. Les choix structurants B et profil de droits sont présentés expressément au présent audit ; ils ne sont pas figés comme décisions humaines acquises. Les deux AR conditionnels ci-dessus ne sont pas des blocages actuellement démontrés ; s’ils deviennent réels à l’audit, consigner décision humaine et sections conditionnées avant GO d’implémentation. Toute modification de complétion, modèle durable, frontières physiques, propriétaires lifecycle ou équité normative exige ce même arrêt/arbitrage.

## 23. Critères de clôture, publication et audit indépendant

### Clôture future de l’implémentation, après GO seulement

Toutes méthodes et sous-variantes obligatoires §§16–18 présentes/exécutées sans skip, états extérieurs conformes, sources réelles/fixtures séparées, aucune perte/double effet/réenrôlement terminal/Q, aucun dépassement K injustifié, COUNT/cap/préfixe/reprise corrects, verrous B et état terminal démontrés. Propriétaires I2/I3 et protocoles R1/R9/recovery/cession inchangés, aucun second writer/owner/cache/état durable. Droits réels ciblés, inactivité et architecture conservées ; quatre gates futurs §19 au même code figé ; provenance, coûts locaux, limites et reports explicites. Publication code+preuves puis audit indépendant et corrections vérifiées ; aucune autoacceptation, aucune activation, **arrêt avant I5**.

### Livraison documentaire présente

Seul `docs/v3/L2_I4_Implementation_Plan.md` est créé par ce mandat. Le plan directeur n’est pas réécrit : son statut historique et le nouveau mandat sont expliqués ici ; aucune référence documentaire supplémentaire n’est nécessaire pour publier la proposition. L’audit demandé est une étape ultérieure indépendante ; la relecture présente ne s’y substitue pas.

Contrôles de préparation/livraison : relecture du diff exact, absence de delta `app/`/scripts/migrations et de fichier préexistant écrasé, chemins existants/proposés vérifiés, liens locaux, matrice et identifiants de méthodes sans doublon, contrôles du manifeste et vecteurs documentaires, `git diff --check`. Résultats de ces contrôles consignés ci-dessous avant commit. **Aucun Maven ni PostgreSQL exécuté ; slices applicatives NONE, aucun franchissement inattendu ; architecture globale non requise/non exécutée pour ce document ; full reactor non requis/non exécuté car mandat strictement documentaire.**

Contrôles effectivement exécutés : `python3 -B docs/v3/check_l2_manifest.py` → OK (253 identifiants normatifs, 380 lignes, 324 obligations L2, 56 reports, 33 oracles, aucun orphelin) ; `python3 -B scripts/v3/check-routing-vectors.py` → 21 vecteurs indépendants conformes ; vérification Python ponctuelle → 35 liens (cibles locales contrôlées), 23 actions de fichiers Java C/M cohérentes avec présence/absence, 23 sections ordonnées, 48 méthodes PostgreSQL n/b/x/p uniques et raccordées ; `git diff --check` → conforme. Ces résultats sont des contrôles documentaires, pas des PASS des méthodes I4 proposées.

Publication : reconfirmer fetch/HEAD distant et divergence ; s’il a changé depuis `798eeb21`, analyser le delta avant publication et adapter le cadrage documentaire si nécessaire. Indexer uniquement ce fichier, commit documentaire, push normal vers `origin/feat/v3-architecture`, sans force ni reset/nettoyage. Vérifier `git ls-remote origin refs/heads/feat/v3-architecture`, égalité avec HEAD et divergence0/0 ; communiquer SHA initial/final, commit et liste exacte des fichiers. Si le push est refusé pour avance distante, refaire fetch/analyse et intégrer uniquement par procédé non destructif, jamais forcer.

Le reçu final donne le SHA vérifié de publication. Les travaux du checkout principal et des autres worktrees restent préservés.

**I4 PLAN PREPARED — AWAITING INDEPENDENT AUDIT AND HUMAN GO.**
