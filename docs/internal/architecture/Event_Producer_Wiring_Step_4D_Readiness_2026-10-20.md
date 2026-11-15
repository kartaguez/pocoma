# Lot 4D — Audit de préparation au raccordement PREPARED

Date : 2026-10-20. Référence auditée : `4defd5a63af267656f2feee92aed8daafef2175f`,
branche `feat/consumption-transaction-protocol`. **AUDIT DE PRÉPARATION ; arbitrage requis avant
implémentation coordonnée ; 4D non certifié ; 4E NOT STARTED.** Aucun code productif modifié.

Autorités relues : [AGENTS](../../AGENTS.md), [politique de vérification](../testing/Reactor_Verification_Policy.md),
[canon](../steps/current/DISCOVERY/Step_Canon.md), [plan](../steps/current/DISCOVERY/Step_Plan.md),
[suite](../steps/current/DISCOVERY/Next_Plan.md), [spécification Étape 4](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md),
rapports [4A](Event_Publication_Contracts_Step_4A_2026-10-19.md),
[4B](Event_Transaction_Protocol_Step_4B_2026-10-20.md),
[4C](Event_Transactional_Sequencer_Step_4C_2026-10-20.md) et leurs preuves versionnées,
[CI](../development/ci.md), [garanties CURRENT](../guarantees/System_Guarantees.md),
[protocole transactionnel CURRENT](consumption-transactional-execution.md).
Les résultats historiques 4A–C ne sont pas de nouvelles exécutions ni une certification 4D.

## Git et préservation

Le checkout initial est `feat/runtime-monolith`, HEAD `90926a5ec37d1ca841e3de017bec0d1f705d8839`,
avec modifications et non-suivis préexistants. Il n'est pas utilisé pour les écritures.
Le worktree cible existant est `/private/tmp/pocoma-consumption-transaction-protocol`.
Le seul commit depuis `de5e4c373d21c1409f6e75a4faac327926e01f92` est
`4defd5a6 docs: close independent 4C audit and trace 4D observations` ; aucun changement de code
dans cet intervalle. Fetch ciblé réussi : divergence **0/0** avec origin.
L'essai initial de fetch était refusé par la sandbox sur le `FETCH_HEAD` commun ; la reprise avec
les permissions nécessaires a réussi. Aucune divergence n'est déduite de ce refus local.

Aucun changement suivi initial dans le worktree cible. Non-suivi préservé et exclu du lot :
`docs/architecture/Architecture_Last_Skip_Audit_2026-10-20.md`, SHA-256
`e34013dbb00ea3f3ff5150a8ba9e850d49fb116b4d7f6703edb4db9a19af3092`.
Aucun reset, stash, rebase, merge, tag, changement de branche ou suppression de worktree.

## Inventaire des writers réellement présents

Préfixe des liens PRIMARY : `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/`.
Les recherches portent sur tous les `app/*/src/main`, les tables sources, les constructions d'entités,
les implémentations et appelants des ports, les annotations, templates, callbacks et constructions `new`.
Six adaptateurs convergent vers **cinq points d'écriture**. Les lectures Discovery/Results/LKV de ces
tables ne sont pas des producteurs. V22 reste un writer SQL historique, hors runtime.

| Writer / source | CURRENT au HEAD | Écart TARGET 4D |
|---|---|---|
| [JpaPotCommandEventAppendAdapter.appendAll](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaPotCommandEventAppendAdapter.java) ; `business_event_outbox` | MANDATORY ; UUID dans RecordedEvent ; `saveAllAndFlush` ; liste d'artifacts identifiés | `BusinessEventsAccepted` déjà livré en 4A ; insert-only `persist`, flush conservé avant B ; garde owner/OPEN et poisoning de toute erreur de l'opération ; acceptations ordonnées puis déclaration par Command |
| [JpaBusinessEventOutboxAdapter.append](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaBusinessEventOutboxAdapter.java) ; même source | REQUIRED autonome ou participant ; UUID local ; `save` différé ; retour void ; aucun appelant productif trouvé | Transférer l'autonomie à une façade applicative ; writer participant gardé, insert-only ; identité acceptée puis déclaration par façade ; **forme du retour à arbitrer ci-dessous** |
| [JdbcCommandOutcomeAdapter.publish](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java) ; `command_terminal_events` | MANDATORY ; INSERT outcome puis terminal avec UUID inline ; retour void ; dépendance JdbcOperations | UUID local conservé et retourné comme EventRef ; deux INSERT stricts exactement une ligne ; garde de corps liée au même manager/EMF/datasource, poisoning couvrant les deux INSERT même par `new` |
| [ExternalIdentityBindingFactJdbcRepository.append](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java) ; `external_identity_binding_facts` | Garde PrimaryWriteParticipation sur datasource/autocommit ; void interne | Garde owner/OPEN et poisoning dans le corps commun avant écriture ; INSERT exactement une ligne ; aucune déclaration dans repository ; signature interne void conservée |
| [JdbcUserCreatedFactAdapter.append](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java) ; `user_created_facts` | Classe final, garde de corps PrimaryWriteParticipation ; UUID inline ; void | Garder contrôle direct sans proxy ; owner/OPEN, poisoning ; INSERT exactement une ligne, EventRef exact ; déclaration par initializer Registration |

Deux chemins vers le writer Binding partagé :

- [JpaExternalIdentityBindingAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingAdapter.java)
  construit le repository fact par `new`, ainsi que les repositories stream/occurrence. Acquire simple
  délègue à acquireWithInitializer. Attach/detach créent le UUID du fact seulement sur mutation gagnante.
  Migrer les retours aux enveloppes `BindingAcquisitionAccepted` / `BindingDetachmentAccepted` 4A,
  sans modifier les résultats métier ni les huit essais de réservation BindingId. Garder initializer
  sous B. Protéger toute l'opération d'autorité, pas seulement son dernier INSERT fact : une erreur
  avant ce dernier ne doit pas laisser committer stream/occurrence/User partiels si elle est interceptée.
- [JpaExternalIdentityBindingFactAdapter.append](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingFactAdapter.java)
  utilise le repository injecté ; retourner l'EventRef portant l'UUID du fact reçu. Couvrir aussi
  une erreur de mapping avant l'entrée repository. Le lecteur BindingFactReadPort reste inchangé.

[JdbcRegistrationOutcomeStore.insert](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationOutcomeStore.java)
est un effet participant protégé à couvrir dans les tests de rollback/erreur interceptée ; son outcome
ne devient pas une cinquième famille. La garde CURRENT ne prouve pas owner/OPEN ni poisoning.
UserAuthority et les mutations Pot, provenance, S/Claim sont les effets dont l'atomicité sera observée.

## Matrice chemins → transactions → déclarations → preuves

Tous les items ci-dessous sont **à certifier en 4D** ; aucune ligne n'est acquise par le présent audit.
« Lot neuf » signifie nouvelle transaction après résolution de la précédente, jamais REQUIRES_NEW
avec suspension. B/E ne désignent pas les identités externes ni les versions métier.

| Chemin réel | Owner / transaction CURRENT | Déclarant TARGET après acceptation | Preuve attendue, au-delà des tests CURRENT |
|---|---|---|---|
| Command Pot/Expense APPLIED | commandConsumptionTransactionRunner ; TE, P → append/flush → B → outcome → provenance/S/Claim | ExecuteRecordedCommandService : BusinessEventsAccepted.events ; CommandConsumptionExecution : Terminal APPLIED | Deux familles exactes, artifacts/UUID/ordre ; même PID/XID avec mutations et S/Claim ; snapshot puis suffixe ; aucun appel E avant finalisation |
| Command REJECTED | Même TE ; zéro BusinessEvent | CommandConsumptionExecution : Terminal REJECTED | Rejet métier/identité/auth expirée conservé ; une référence terminale, aucune référence Business fictive |
| Command TH FAILED | Wrapper failure CURRENT ; TH après rollback TE confirmé | terminalFailureEffect : déclarer seulement à l'exécution du callback | TE annulée ; lot TH neuf, un FAILED durable avec S/Claim ; aucune émission sur COMMITTED/UNKNOWN |
| Command TA / TH retry | TA acquisition et TH retry séparées, policies CURRENT | Aucun | Snapshot vide ; E tenues ailleurs sans attente ; absence d'Event/outcome/position |
| Command TR | BindingFenceRecoveryExecuteUseCase après rollback TE ; relecture occurrence exacte | Callback recovery : Terminal REJECTED | TE Business provisoires annulés ; occurrence encore présente = conflit technique sans Event ; disparue = une référence terminale TR, aucun replay Pot |
| Command perd P/B/S ou clôture Claim | TE perdante / TR ou TH selon classification existante | Aucun Event committé de TE perdante | Assertions état métier/outcome/provenance/S/Claim et quatre sources, pas seulement compteurs ; retries et classifications inchangés |
| Registration gagnante | registrationTransactionRunner ; TE, B → initializer → ATTACHED → outcome → S/Claim | ExecuteRegistrationService : UserCreated dans initializer, puis fact ATTACHED après acquire accepté | UUID acceptés/déclarés/persistés exacts ; même PID/XID pour User, fact, binding, outcome, S/Claim ; ordre UserCreated puis ATTACHED observé sans inventer ordre global inter-familles |
| Registration rejetée / existing | Même TE | Zéro | Conflit n'appelle pas initializer ; existing n'appelle ni initializer ni acquire ; outcome courant conservé |
| Registration initializer/fin en erreur | Même TE perdante ; TH distincte selon policy | Aucune référence durable après rollback | Injecter erreur UserCreated, ATTACHED, outcome et finalisation ; pas de User orphelin ni stream/occurrence/révision/fact partiel |
| Binding acquire / detach auxiliaires | Ports MANDATORY ; pas d'owner autonome productif trouvé | Appelant applicatif explicite sous owner PRIMARY ; enveloppe gagnante seulement | Bean + `new`, ACQUIRED/DETACHED = un fact, CONFLICT/NOT_CURRENT = zéro ; même connexion ; rollback révision/occurrence/actif |
| Binding fact auxiliaire | Port MANDATORY → repository injecté ; pas d'appelant productif trouvé | Appelant après EventRef accepté | UUID fourni inchangé ; N facts ordonnés ; erreur mapper et JDBC interceptée ; aucun bypass par repository `new` |
| Business auxiliaire autonome | Actuellement REQUIRED dans writer | Façade : owner PRIMARY → port → déclaration | Une transaction, retour seulement après commit, persist différé matérialisé au flush terminal ; pas de sous-commit |
| Business auxiliaire participant / N appels | Actuellement rejoint ambient REQUIRED | Même façade, participant sous lot OPEN uniquement | Pas de fermeture/flush/suffixe intermédiaire ; ordre exact entre appels ; rollback de tous les effets ; ambient inconnu refusé avant publication |

Les owners productifs à migrer sont déclarés dans
[CommandConsumptionRuntimeConfiguration](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/consumption/CommandConsumptionRuntimeConfiguration.java)
et [RegistrationRuntimeConfiguration](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registration/RegistrationRuntimeConfiguration.java).
Ils composent actuellement `ConsumptionOwnerTransactionRunner → SpringTransactionRunner → TransactionTemplate`.
Remplacer seulement le délégué par PrimaryEventTransactionRunner ; garder le flag Command
`preserveActionFailure=true` et les frontières CURRENT. La façade auxiliaire doit ouvrir ou participer
par ce même protocole, sans wrapper Consumption ni nouveau manager.

Le root monolithe importe ces deux configurations, le root Command et le root Registration importent
chacun la leur. Les annotations SpringBootApplication scannent aussi les adaptateurs PRIMARY.
Il faut donc une composition PRIMARY commune disponible là où les writers sont instanciés, y compris
WEB/BINDING, sans substituer globalement tous les autres runners. Les autres consumers/admissions
ne gagnent pas le droit de publier les quatre sources parce qu'un manager existe.

## Matrice de certification A / B / C

| Condition | CURRENT : portée réellement acquise | Travail V-D et observation exigée |
|---|---|---|
| A : chaque writer owner/OPEN | Runner/primitive 4B dormants ; gardes datasource partielles 4A ; autres annotations participantes | Gardes de corps sur toutes surfaces durables, y compris `new`, mapper, liste vide, initializer et erreurs avant dernier INSERT ; appels hors owner/fermés refusés avant écriture |
| A : poisoning irréversible | Exceptions de garde/participants 4B ; pas d'interception universelle des writers réels | Pour chaque writer, erreur pertinente interceptée localement puis tentative de poursuivre/committer ; relecture indépendante prouve rollback physique de tous les effets. Inclure erreur Java après un premier INSERT JDBC, pas seulement SQL qui abort déjà PostgreSQL |
| B : manager/EMF/datasource | Un manager standard attendu ; runners reçoivent PlatformTransactionManager sans qualification physique propre au protocole | Valider JpaTransactionManager PRIMARY, EMF attendu, datasource effective des JdbcTemplate/JdbcOperations, nested=false ; refuser manager/EMF/datasource indépendants avant effets |
| B : même transaction | PID/XID testés dans compositions synthétiques 4B/4C | Triggers sur vrais effets et observation JPA/JDBC dans Command, Registration et auxiliaires ; même PID/XID intra-transaction, transactions distinctes pour TA/TE/TR/TH ; roots distribués et monolithiques |
| C : identités et ordre | Types 4A vérifient enveloppes, producteurs réels ne déclarent pas | Instrumentation de test des acceptations et de la façade PublishedEvents, snapshot fermé puis relecture DB : listes exactes par famille, égalité des ensembles et cardinalités, aucun tri UUID |
| C : ACTIVE isolé | DDL/séquenceur 4C éprouvés sur sources réelles avec producteurs fixtures | Refaire sur les producteurs réels : acceptés = déclarés = Events = associations ; positions denses et ordre ; omission/fictive/doublon/tardif, rollback après S/Claim et associations |
| C : PREPARED | DDL autorise légitimement Event sans position | Prouver exactitude applicative par tests, E=0/positions vides, aucun SQL réservation ; ne pas attribuer à PREPARED la complétude SQL inactive |
| C : frontière de confiance R1 | Le rôle runtime peut appeler la fonction de réservation ; contraintes seules ne prouvent pas la densité | Règle structurelle : aucun producteur n'appelle réservation/séquenceur directement ; suffixe fermé unique, droits effectifs et égalité exacte sur compositions autorisées ; pas de police SQL universelle |

## Arbitrage architectural bloquant identifié

La cible 4A exige explicitement `BusinessEventAppendPort.append → EventRef` dans son tableau
« Formes TARGET figées ». Le port est dans `engine-write-pot`, dont le POM ne dépend pas de
`port-transaction`. La règle
[EventPublicationContractArchitectureTest.businessProductionAndNarrowTransactionContractRemainTechnologyFree](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/EventPublicationContractArchitectureTest.java)
interdit `port.transaction..` à **tout** `engine.write.pot..`, ports compris.
Importer EventRef directement ferait échouer cette règle et nécessiterait une nouvelle dépendance.
Le test [Pcl7ModuleDependencyCollapseTest.canonicalInfrastructureRemainsConcrete](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/Pcl7ModuleDependencyCollapseTest.java)
protège aussi explicitement le chemin actuel du port et son implémentation par l'adaptateur.
Ce n'est pas un défaut d'exécution PostgreSQL ni une réouverture de 4A–C ; c'est une incompatibilité
entre la signature TARGET et la frontière structurelle livrée, rencontrée au raccordement 4D.

**Proposition précise, à arbitrer :** garder le port durable à sa place et lui faire retourner
`UUID append(BusinessEvent event)`, identité technique durable acceptée créée par le writer.
La façade applicative construit alors `new EventRef(BUSINESS_EVENT, acceptedId)` et la déclare
immédiatement après acceptation. Pas de PublishedEvents/EventRef dans le métier, pas de POM,
pas de capture cachée, même principe B et même atomicité. Cela demande d'amender explicitement
la seule forme TARGET auxiliaire 4A ; aucune garantie de couverture n'est retirée.

Alternative : déplacer ce port durable vers une couche applicative déjà dépendante de
port-transaction, y retourner EventRef et réviser les chemins/allowlists/règles PCL7 concernés.
Cette option change une frontière protégée et doit être déclarée comme telle avant modification.
Ajouter simplement une dépendance transactionnelle à tout engine-write-pot n'est pas retenu :
cela contredit la séparation prescrite et affaiblirait la règle pour résoudre une seule signature.

L'arbitrage est demandé au responsable. Aucun changement normatif ni de signature n'est effectué
par cet audit. La migration productive est coordonnée : ne pas livrer un runtime partiellement
raccordé ni des gardes activées face à des owners encore anciens.

## Ordre d'implémentation proposé après arbitrage

1. Consigner l'arbitrage auxiliaire et sa portée structurelle ; figer signatures et constructeurs
   applicatifs. Aucun nouveau module. Ne conserver aucun constructeur productif de secours avec
   registre no-op ou garde optionnelle.
2. Étendre les gardes techniques locales : contrôle OPEN, identité manager/EMF/JDBC et poisoning
   irréversible couvrant l'intégralité de chaque opération durable, y compris appels directs.
   Réutiliser la primitive insert-only et le contexte 4B, sans collecteur parallèle.
3. Migrer simultanément retours d'acceptation et déclarants Command TE/TR/TH, Registration initializer
   et résultat Binding ; garder points de flush/fences/finalisation. Ajouter la façade auxiliaire
   Business et les usages auxiliaires Binding sous owner explicite.
4. Câbler une seule composition PRIMARY runner/suffixe/vue PublishedEvents, contrôle version/mode
   avant lancement des workers et refus des compositions physiques incompatibles. Aucune activation.
5. Certifier les chemins réels et négatifs A/B/C en PostgreSQL jetable ; trace d'ordre S/Claim/E,
   N publications/appels, lots vides, collisions, contexte repris après rollback, droits restreints.
6. Exécuter V-D composé, compléments Registration/monolith, gate architecture global ; répéter
   retour à l'ancien runtime avec drainage en base jetable PREPARED. Revue des preuves, docs CURRENT,
   puis seulement commit/push non forcé si tous les critères de certification sont satisfaits.

## Verification scope déclaré avant toute modification productive

- Expected production impact : ports/acceptations et orchestrations Command/Registration/Binding,
  six adaptateurs et repository commun PRIMARY, gardes/primitive/runner/suffixe et composition Spring
  nécessaires à PREPARED complet, façade auxiliaire Business ; tests et documentation correspondants.
- Primary slice : **COMMAND** ; secondary slices : **BINDING, WEB**.
- Compléments de composition : **runtime-registration-consumption-worker, runtime-monolith**,
  distincts des slices canoniques. Aucun runtime-to-runtime dependency.
- Forbidden production impact : métier Pot/Expense et EventPublisherPort, payloads, Discovery/READ,
  algorithmes Consumption, P/B/S, TA/TE/TR/TH, fences/CAS/classifications/retries ; migrations
  historiques/V30, activation, backfill, nouvel owner/store/manager non déclaré, POM non arbitré.
- Global architecture gate : **REQUIRED pour V-D**, restrictions d'accès/dépendances au cœur du lot.
- Full reactor : **NOT REQUIRED pour 4D borné**, non exécuté ; prochain gate majeur 4E/intégration.
- Database verification : **CURRENT V30 + ACTIVE strictement isolé** ; trusted baseline **NONE**.
  Garder bootstraps existants V1–V30 nécessaires, sans campagne historique ajoutée par défaut.
- Escalade préalable : décision auxiliaire ci-dessus ; autre owner/store/manager/datasource,
  dépendance/POM/frontière, comportement d'une autre slice, savepoint ou métier après E.

Commandes futures de clôture, depuis `app/` (non exécutées par cet audit) :

```bash
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test
./mvnw -pl runtime-registration-consumption-worker,runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

Points d'ancrage pour les tests attendus (tous restent à adapter/étendre, noms nouveaux proposés) :

| Couverture | Tests réels existants à préserver | Complément V-D attendu |
|---|---|---|
| Command APPLIED/REJECTED/FAILED, retries, TR, CAS/Claims/fences | CommandConsumptionPostgresTest, CommandConsumptionRuntimePostgresTest, CommandConsumptionMultiWorkerPostgresTest, BindingSharedFencePostgresTest | `RealProducerEventProtocolPostgresTest` dans le worker Command : capturer les acceptations/déclarations réelles, traces PID/XID et ordre, ACTIVE isolé et fautes après S/Claim |
| Registration gagnante/rejet/existing/initializer et takeover | RegistrationAuthorityPostgresTest, RegistrationRuntimePostgresTest | Étendre le test du vrai worker avec deux familles exactes, poisoning intercepté, traces de tous effets ; rejouer PREPARED/ACTIVE isolé |
| Binding lifecycle et repository `new`/bean | JpaUserIdentityAuthorityAdapterPostgresTest, JpaExternalIdentityBindingLifecycleAdapterPostgresTest | `RealProducerWriterPoisoningPostgresTest` : chaque writer réel en erreur interceptée, phases/owners refusés, rollback physique et auxiliaires N appels |
| Monolithe et composition commune | RuntimeMonolithPostgresTest | Vraies exécutions Command et Registration sous le root monolithe ; manager/EMF uniques et PID/XID, aucune composition permissive secondaire |
| Invariants runner/suffixe et intégrité SQL | PrimaryEventTransactionPostgresTest, EventSequencingPostgresTest, EventSequencingUpgradePostgresTest | Conserver ces preuves bornées et les distinguer des nouveaux producteurs réels ; omission/fictive/doublon/tardif et contextes successifs |
| Restrictions de dépendances et accès directs E | EventPublicationContractArchitectureTest, HexagonalArchitectureTest, PrimaryEventProtocolStructureTest | Déclarants permis seulement, writers gardés/poisoning, accès réservation/suffixe exclus des producteurs ; fixtures négatives de composition |
| Publication non persistante et ancien runtime | NonPersistentEventPublicationTest, NonPersistentDispatchRelayTest, script de compatibilité 4C | Rejouer les premières sans registre ; adapter une répétition de retour à l'ancien binaire sur V30 PREPARED, writers drainés, sources anciennes exportées |

Points de test préexistants à adapter dans la frontière déclarée :
`RuntimeMonolithPostgresTest.assertMigrations` attend encore 29, alors que V30 est au HEAD ;
les preuves de dormance 4B/4C doivent être distinguées des nouveaux contrôles PREPARED câblés ;
les expériences 3C utilisent un manager expérimental et les anciens writers, elles ne doivent
ni devenir des compositions productives ni être annoncées comme preuves V-D. Garder la portée
historique de ces expériences et documenter toute adaptation des fixtures aux nouvelles gardes.
Les fixtures actuelles qui construisent des writers par `new` doivent utiliser le propriétaire
certifié, jamais un constructeur permissif ajouté pour garder leurs tests verts.

Les logins/provisioning d'un environnement déployé ne sont pas certifiés par la seule configuration
du dépôt. Les paramètres runtime et Flyway partagent par défaut la datasource configurée ; les tests
doivent séparer maintenance et rôle runtime restreint. Aucune connexion ni mutation d'environnement
déployé n'est réalisée pour cet audit. Cette limite doit rester explicite dans le futur rapport 4D.

## Bilan exécuté et critères ouverts

Travail livré à ce stade : audit statique, matrice des écarts, déclaration préalable V-D et proposition
d'arbitrage. Aucun raccordement productif, aucune intégration A/B/C certifiée et aucune preuve
PostgreSQL nouvelle. PREPARED est l'état **défini par V30 au dépôt**, pas une mesure d'une base déployée.
4B/4C restent CLOSED / PASS dans leurs périmètres ; 4D reste ouvert et 4E NOT STARTED.

Commandes effectivement exécutées : `pwd`, états/branche/HEAD/log/worktrees Git, `git cat-file -t`,
`git fetch origin feat/consumption-transaction-protocol`, `git rev-list --left-right --count`,
inventaires/lectures `rg`, `cat`, `sed`, `wc`, `shasum -a 256` ; contrôles documentaires et empreintes
consignés dans [la preuve d'audit](../testing/evidence/Event_Producer_Wiring_Step_4D_Readiness_2026-10-20.json).
**Aucune commande Maven, aucun test, aucune migration ni essai PostgreSQL lancé.**
Scope effectivement modifié à ce stade : documentation uniquement, slices applicatives **NONE** ;
V-D ci-dessus est la déclaration de la future implémentation. Gate global requis pour certification
4D mais **non exécuté** ; full reactor **non exécuté et non requis pour cet audit**.
Franchissement applicatif effectif : **NONE** ; contradiction structurelle identifiée avant changement.
Pas de commit/push de livraison 4D : condition utilisateur « après certification » non satisfaite.

**Verdict de préparation : ARBITRAGE REQUIS. Pas de 4D CLOSED / PASS.** Conditions restant ouvertes :
arbitrage de la signature auxiliaire, totalité du raccordement coordonné, matrices V-D A/B/C sur
chemins réels, compositions distribuées/monolithiques, retour runtime antérieur répété et trois gates.
