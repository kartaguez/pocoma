# Étape 4A — Contrats applicatifs de publication

Travail commencé le 2026-10-19, livraison le 2026-10-20. Baseline : `a53999cb4dc7dac5973478b5ad50243710acba87`.
Branche : `feat/consumption-transaction-protocol`.
**Statut actualisé le 2026-10-20 : 4A CLOSED au SHA `b7aacef54457583eccd405ef03acc21e05bb156c` ; 4B NEXT, non commencé.**
Le [plan courant et ses preuves](../steps/current/DISCOVERY/Step_Plan.md#autorité-critères-et-état-4a) consolident les corrections Consumption puis bootstrap : gate global PASS, 1 257 tests déclarés, 0 failure/error, 1 skip exploratoire intentionnel (`-Dstep3b.measure=true`). Les résultats rouges et restrictions de passage relatés ci-dessous restent le journal historique de la livraison initiale, pas le statut courant. Contrats livrés ; propriétaire/collecteur transactionnel et raccordement restent à réaliser en 4B/4D.
Autorité : [plan Étape 4](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md), §§2.1, 3.6–3.7, 6 et 10 ; [politique de vérification](../testing/Reactor_Verification_Policy.md).

## Livré et limites

4A conserve les signatures CURRENT, les use cases/factories Pot/Expense, `EventPublisherPort`, les artifacts, outcomes et résultats métier Binding. Les nouvelles enveloppes sont des types TARGET compilables, sans branche de migration ni ports de production parallèles. Les déclarants TARGET sont exercés par des orchestrations **de test** ; aucun adaptateur ne collecte implicitement les références.

`port-transaction` définit `EventFamily` (BUSINESS_EVENT, COMMAND_TERMINAL_EVENT, BINDING_FACT, USER_CREATED_FACT), `EventRef(family, eventId)` et la vue `PublishedEvents.recordPublishedEvent(EventRef)`. Aucune position, rang de verrou, technologie ou table n'est exposé. Les quatre valeurs ne constituent pas un ordre inter-familles. L'UUID peut être attribué à l'acceptation ; les Events métier Pot/Expense restent sans EventId.

La seule nouvelle dépendance de production est `port-binding-authority → port-transaction`, nécessaire aux enveloppes d'autorité. Aucun cycle ni dépendance transactionnelle n'est ajouté au métier Pot/Expense. `engine-consume-command` dépendait déjà de `port-transaction`.

Les gardes CURRENT de corps sont livrées pour `JdbcUserCreatedFactAdapter.append`, `ExternalIdentityBindingFactJdbcRepository.append` et `JdbcRegistrationOutcomeStore.insert`. Elles exigent une transaction active avec synchronisation, non read-only, une connexion liée à la datasource du writer et sans autocommit. Elles n'ouvrent aucune transaction. Le repository construit par `new` dans l'autorité Binding applique la même garde que le bean. La vérification ne dépend pas d'un proxy et ne transforme pas l'annotation MANDATORY en propriétaire autonome.

Ces gardes ne certifient pas encore un owner E ni une phase OPEN : les contextes physiques, fermeture, poisoning et cleanup appartiennent à 4B, leur raccordement aux producteurs réels à 4D. L'autonomie REQUIRED auxiliaire Business CURRENT reste en place jusqu'à 4D. Aucune promesse d'interception JDBC/Hibernate universelle.

## Formes TARGET figées, migration réservée à 4D

**Amendement ciblé approuvé au raccordement 4D, 2026-10-20.** Le tableau ci-dessous conserve
la cible historique 4A. Pour le seul port auxiliaire `BusinessEventAppendPort` dans
`engine-write-pot`, la cible désormais approuvée est `UUID append(BusinessEvent)` : le writer
retourne l'identité exacte de l'Event durable créé. La façade applicative construit ensuite
`EventRef(BUSINESS_EVENT, acceptedId)` et le déclare dans la même transaction physique PRIMARY.
Cette adaptation préserve l'interdiction de dépendre de `port-transaction` dans le métier,
sans nouvelle dépendance Maven, sans collecte cachée ni affaiblissement des critères A/B/C 4D.
L'ancienne forme EventRef de la ligne auxiliaire est historique, remplacée uniquement par cet amendement.

Les signatures ci-dessous décrivent les futurs ports spécialisés ; elles ne modifient pas leurs signatures CURRENT. Aucun super-port, bus générique ou flag de capacité par Event n'est introduit.

| Port / grammaire | Résultat TARGET | Déclaration par l'orchestration |
|---|---|---|
| `EventAppendPort.appendAll(List<BusinessEvent>)` | `BusinessEventsAccepted(artifacts, events)` dans `engine-consume-command.model` ; deux listes immuables alignées, même cardinalité, UUID et ordre ; famille BUSINESS_EVENT ; références distinctes | Après le retour d'acceptation, itérer `events` dans l'ordre ; liste vide = zéro déclaration. Conserver le point CURRENT avant B |
| `CommandOutcomePublicationPort.publish(CommandOutcome)` | `EventRef` COMMAND_TERMINAL_EVENT ; nouvel UUID de l'acceptation atomique outcome + terminal | TE, callback TH FAILED et TR recovery déclarent chacun au moment de leur appel effectif ; pas lors de la création d'un callback |
| `ExternalIdentityBindingPort.acquire/acquireWithInitializer` | `BindingAcquisitionAccepted(BindingAcquireResult, Optional<EventRef>)` dans `port-binding-authority` | ACQUIRED = un fact BINDING_FACT ; CONFLICT = zéro ; résultat métier conservé |
| `ExternalIdentityBindingPort.detach` | `BindingDetachmentAccepted(BindingDetachResult, Optional<EventRef>)` | DETACHED = un fact BINDING_FACT ; NOT_CURRENT = zéro |
| `ExternalIdentityBindingFactPort.append(fact)` | `EventRef` BINDING_FACT, UUID exact du fact fourni | Déclarer après acceptation ; repository interne toujours `void append(row)` |
| `UserCreatedFactPort.append(requestId, userId)` | `EventRef` USER_CREATED_FACT ; UUID de l'acceptation, distinct du UserId | Dans l'initializer Registration, avant retour de l'acceptation ATTACHED ; existing/conflit n'invoquent pas cet initializer |
| `BusinessEventAppendPort.append(event)` auxiliaire | `EventRef` BUSINESS_EVENT | Future façade applicative propriétaire : demander publication puis déclarer. Aucun ajout de `port-transaction` au métier Pot/Expense pour réaliser cette façade en 4A |

Les résultats singuliers utilisent directement `EventRef`, sans enveloppe artificielle. Les résultats d'autorité enveloppent le résultat métier inchangé avec une référence optionnelle, plutôt qu'une liste universelle. Les constructeurs refusent une référence absente pour mutation gagnante ou présente pour conflit/no-op et une famille erronée. `BusinessEventsAccepted` refuse les cardinalités, familles, doublons et identités artifacts/références incohérents. Aucun constructeur ne prouve à lui seul l'acceptation ou l'atomicité d'un adaptateur.

Pour les ports durables CURRENT, acceptation signifie engagement provisoire dans la transaction appelante : pas commit ni visibilité. La compatibilité atomique relève du câblage explicite et de sa certification. Un acquittement réseau ne peut pas remplacer cet engagement. La fixture d'architecture d'un faux `EventAppendPort` réseau est rejetée **avant son invocation** ; il ne s'agit ni d'une négociation de capacités ni d'un validateur runtime universel. Les déclarations de test conservent l'ordre des listes et appels, sans tri UUID. L'ordre UserCreated puis ATTACHED est un ordre d'exécution ; il ne crée pas un ordre global entre familles.

## Production et publication non persistantes

`NonPersistentEventPublicationTest` utilise les factories métier réelles Pot et Expense avec ports de mutation et publisher mémoire. Les objets typés reçus et les versions métier sont vérifiés sans stockage d'Event, transaction, registre ni position. `NonPersistentDispatchRelayTest` exerce le vrai dispatch CreatePot, sa capture CURRENT, puis un relais applicatif vers une implémentation mémoire du même `EventPublisherPort`. Capture et demande au destinataire sont des moments distincts.

Un Event produit n'est pas une publication acceptée. Une acceptation n'est ni un commit confirmé, ni une preuve de visibilité externe. Les ports durables CURRENT conservent leur sémantique ; la publication non ordonnée indépendante utilise `EventPublisherPort` sans `PublishedEvents`. Le contrat CURRENT interdit toujours les effets externes irréversibles directs dans le callback fenced. Aucun transport réseau fiable ni atomicité PRIMARY/externe n'est livré.

## Inventaire et restrictions structurelles

La carte des six adaptateurs / cinq points d'INSERT du plan est revalidée à cette baseline : deux writers outbox, Terminal, repository Binding partagé, UserCreated. Registration outcome est un effet participant protégé, sans cinquième famille d'Event. Aucun nouveau store, owner ou appelant auxiliaire de production n'est introduit.

Le gate ArchUnit contrôle les implémentations des six ports durables par allowlist, les accès aux repositories fact/outbox et les constructions d'entités outbox. Le lecteur `JpaEventPort` via EntityManager reste autorisé. Dans les engines Command/Registration et writers/repositories du graphe recensé : propagations fractionnantes, templates propriétaires locaux, savepoints/commit/rollback manuels, callbacks enregistrés et dépendances réseau sont refusés. Des fixtures négatives prouvent ces refus. La règle globale du kernel Command autorise seulement les deux nouvelles dépendances de `BusinessEventsAccepted` vers `EventRef`/`EventFamily`, sans ouverture générale vers la propriété transactionnelle. Le métier reste indépendant de l'infrastructure et de `port-transaction` ; aucun adaptateur n'accède à `PublishedEvents`. Ce contrôle porte sur le graphe recensé, pas sur toutes les fraudes possibles d'un adaptateur natif.

## Vérification déclarée avant implémentation

Impact attendu : contrats TARGET et gardes locales CURRENT seulement. Slice primaire COMMAND ; secondaires BINDING et WEB. Ancre complémentaire Registration, distincte d'une slice canonique. Gate architecture global **REQUIRED**. Full reactor **NOT REQUIRED**, non exécuté : clôture bornée 4A, sans restructuration large des modules.

Commandes de clôture depuis `app/` :

```bash
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test
./mvnw -pl runtime-registration-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

DB : CURRENT, bootstrap Flyway existant V1–V29 et suites de migrations déjà incluses dans `-am` ; aucune nouvelle campagne historique ni baseline trusted inventée. Les gardes sont testées en PostgreSQL 17 : appels directs/new hors transaction, read-only et transaction d'une autre datasource refusés sans effets ; participation/rollback commun, gagnante/conflit/existing Registration et comportements Command CURRENT. Les résultats effectivement exécutés sont consignés dans le rapport de livraison ci-dessous.

## Avant 4B et frontière 4D

4B doit prouver hors production le propriétaire/participant PRIMARY, l'implémentation de `PublishedEvents`, l'ordre et les phases, le flush/fermeture, le poisoning irréversible, le cleanup et les classifications de résolution, sans raccordement ni E actif. Les types TARGET ne constituent pas cette preuve.

4D reste responsable de la migration coordonnée des signatures et appelants, des UUID réellement acceptés de bout en bout, de la façade auxiliaire, des gardes owner/phase, des déclarations réelles et de la réalisation JPA insert-only. Ni ces migrations, ni 4C, ni PREPARED/ACTIVE, ni le séquenceur ne sont commencés par 4A. Aucun passage automatique à 4B.

## Résultats exécutés et revue de livraison

Start HEAD : `a53999cb4dc7dac5973478b5ad50243710acba87`. Le SHA final, le HEAD distant et la divergence sont donnés dans la restitution après commit/push, sans autoréférence dans ce document.

| Preuve réellement exécutée | Résultat |
|---|---|
| Quatre classes applicatives ciblées sans infrastructure | 10 tests, zéro échec/erreur/skip |
| Reprise ciblée Command/Registration + trois classes PostgreSQL après corrections | 29 tests, zéro échec/erreur/skip, dont 21 PostgreSQL |
| Clôture canonique COMMAND + BINDING + WEB | BUILD SUCCESS ; 1 045 tests, zéro échec/erreur, un skip |
| Ancre réelle `runtime-registration-consumption-worker` | BUILD SUCCESS ; 926 tests, zéro échec/erreur/skip |
| Architecture ciblée, avant ajout du contrôle explicite annotations/gardes | BUILD SUCCESS ; 5 tests |
| Preuve structurelle complète ciblée (nouvelle règle + règle hexagonale) | BUILD SUCCESS ; 60 tests, zéro échec/erreur/skip |
| Premier gate global `architecture-tests` | BUILD FAILURE dans LKV ; 998 tests rapportés, une erreur ; module architecture non atteint |
| Diagnostic LKV inchangé après reprise | BUILD SUCCESS ; 8 tests PostgreSQL, zéro échec/erreur/skip |
| Reprise exacte du gate global `architecture-tests` | BUILD FAILURE ; 1 257 tests rapportés, 11 erreurs de bootstrap PostgreSQL, un skip ; tous les runtimes SUCCESS, module architecture FAILURE |
| Reproduction isolée sur archive exacte de la baseline a53999cb | BUILD FAILURE attendu du diagnostic ; `DurableEventToProjectionPostgresTest` : une erreur, même connexion localhost:5432 refusée |

Le skip des slices correspond au test de mesure 3B `TerminalSequencingPostgresTest` conditionné par `step3b.measure=true` ; aucune activation de cette campagne de mesure ni désactivation de test n'est ajoutée. Les totals par commande se recouvrent par `-am` et ne s'additionnent pas comme une couverture unique.

Diagnostic conservé : les trois commandes de clôture émettent dans PRIMARY le message Surefire `The exit has elapsed 30 seconds after System.exit(0)`. Les assertions PRIMARY restent à zéro échec/erreur ; les clôtures de slices et Registration retournent code 0 / BUILD SUCCESS. Le gate global retourne code 1 pour les erreurs de bootstrap décrites ci-dessous. Le même diagnostic de fermeture est déjà décrit dans la [preuve 3C](Event_Terminal_Sequencing_Step_3C_Transactional_Integration_2026-10-19.md). Sa cause n'est ni corrigée ni requalifiée par 4A ; aucune option de test n'a été modifiée pour le masquer.

Échec du premier gate global, distinct des erreurs de mise au point : `LatestKnownVersionRuntimePostgresTest.retriesATechnicalReloadFailureThenResumesWithOneDurableProvenanceInput`, ligne 189, échoue sur `ck_consumption_slots_next_claim_at` lors du SQL de test `set next_claim_at=current_timestamp`. Le détail PostgreSQL porte `next_claim_at=2026-10-19 22:26:13.160331+02`, antérieur au `created_at=22:30:45.729807+02`. Cet écart est compatible avec un décalage d'horloges durant l'interruption/reprise de la machine ; la cause exacte n'est pas certifiée. Après reprise, comparaison en lecture seule : hôte et Docker à `2026-10-19T23:21:47Z`. Aucune remise à l'heure, mutation de Docker ou correction de LKV/contrainte n'a été effectuée. La classe LKV inchangée repasse ses huit tests, puis le gate global exact est relancé ; le premier échec reste consigné. C'est un diagnostic dans la fermeture Maven du gate global, sans changement de production ni extension de slice fonctionnelle LKV.

La reprise globale atteint le module architecture mais échoue sur quatre classes : `DurableEventToProjectionPostgresTest` (1 erreur), `HistoricalEventRouteEvolutionPostgresTest` (1), `CommandResultTakeoverRacePostgresTest` (5) et `CommandCompletionE2EPostgresTest` (4). Cause commune observée : création de contexte / Flyway, SQLSTATE 08001, connexion `localhost:5432` refusée au lieu de la datasource Testcontainers. Les 54 tests hexagonaux et les 6 nouveaux tests structurels 4A passent dans ce même gate ; les autres règles structurelles passent également.

Attribution : les quatre sources de tests et `runtime-monolith/src/main/resources/application.properties` sont byte-identiques à a53999cb. Une archive `git archive a53999cb4dc7dac5973478b5ad50243710acba87 app` extraite dans `/private/tmp/pocoma-4a-baseline-proof` reproduit le même défaut avec la seule classe `DurableEventToProjectionPostgresTest` (une erreur, BUILD FAILURE). Le défaut préexistant bloquant le gate est donc reproduit sans les changements 4A. Le mécanisme probable est la priorité des propriétés de configuration runtime sur les propriétés par défaut du `SpringApplicationBuilder` des tests ; aucune correction de configuration n'est revendiquée. Aucun test concerné n'a été modifié, exclu ou désactivé. Aucun PostgreSQL externe n'a été créé à localhost:5432 pour contourner ce défaut.

**Condition avant 4B :** traiter séparément ou faire arbitrer explicitement ce défaut préexistant et clôturer le gate requis ; le présent rapport ne déclare pas V-A + gate global entièrement vert. Les preuves bornées de 4A sont acquises, le passage de lot n'est pas prononcé. 4B nécessite ensuite son autorisation propre et sa déclaration de vérification.

Échecs de mise au point, conservés comme tels :

- Première clôture composée : **BUILD FAILURE** dans PRIMARY (228 tests, 1 failure, 51 errors). La première garde exigeait à tort `ConnectionHolder.isSynchronizedWithTransaction()`, indicateur non renseigné par le manager JPA CURRENT. Retrait de cette hypothèse ; transaction active, synchronisation globale, datasource liée et autocommit désactivé restent vérifiés. Reprise ciblée puis clôtures des slices et Registration vertes ; le gate global reste en échec pour une cause distincte décrite ci-dessus.
- Une reprise ciblée s'est arrêtée avant PostgreSQL sur une erreur de fixture : `CommandOutcome.Failed` exige le code public CURRENT `COMMAND_PROCESSING_FAILED`. Fixture corrigée pour utiliser la constante existante ; aucune modification du modèle/outcome/payload. La reprise de 29 tests est verte.
- Une invocation du wrapper depuis la racine, où `mvnw` n'existe pas, n'a pas démarré Maven ; répétée depuis `app/`. Une première création du test Registration utilisait également le mauvais répertoire ; le fichier a ensuite été créé à sa place correcte et ses deux tests exécutés.

Commandes ciblées réellement exécutées depuis `app/` (en complément des trois commandes canoniques ci-dessus) :

```bash
./mvnw -pl engine-write-pot,engine-consume-command,engine-consume-registration -am test -Dtest=NonPersistentEventPublicationTest,NonPersistentDispatchRelayTest,PublicationAcceptanceContractTest,RegistrationPublicationContractTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=RegistrationAuthorityPostgresTest,JpaUserIdentityAuthorityAdapterPostgresTest,JpaExternalIdentityBindingLifecycleAdapterPostgresTest,PublicationAcceptanceContractTest,RegistrationPublicationContractTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test -Dtest=EventPublicationContractArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test -Dtest=EventPublicationContractArchitectureTest,HexagonalArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-latest-known-version-consumption-worker -am test -Dtest=LatestKnownVersionRuntimePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
# Depuis /private/tmp/pocoma-4a-baseline-proof/app, baseline exacte, classe unique :
./mvnw -pl architecture-tests -am test -Dtest=DurableEventToProjectionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

Impact observé conforme au périmètre déclaré ; aucun franchissement inattendu de slice. Les modules transitoires construits par `-am` et les autres runtimes couverts par le gate global ne sont pas des changements de comportement de ces slices. Aucun reactor complet, migration nouvelle, activation, séquenceur, modification de retry/classification/fencing ou de finalisation. Les suites de migration existantes ont bien exécuté leur bootstrap historique, en l'absence de baseline trusted ; elles n'ont pas été retirées pour obtenir un PASS.

Revue : diff complet, dépendance applicative unique, liste des fichiers, liens locaux et `git diff --check`. Le plan est identique à la baseline hors sa ligne de statut/lien vers cette livraison. Checkout principal et autres worktrees préservés ; aucun reset/stash/rebase/merge ni push forcé. Journaux détaillés de cette session : `/private/tmp/pocoma-4a-{contracts,guards,slices,registration,architecture-targeted,architecture-global}.log`, premier échec de garde dans `pocoma-4a-slices-initial-failure.log` et erreur de fixture dans `pocoma-4a-contract-fixture-failure.log`. Les preuves complémentaires sont dans `pocoma-4a-architecture-complete-targeted.log`, `pocoma-4a-architecture-global-lkv-failure.log`, `pocoma-4a-lkv-diagnostic.log` et `pocoma-4a-baseline-ept.log`. Ces journaux sont locaux, non ajoutés au dépôt.

Fichiers de cette livraison (chemins relatifs au dépôt) :

- `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/EventPublicationContractArchitectureTest.java`
- `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/HexagonalArchitectureTest.java`
- `app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/model/BusinessEventsAccepted.java`
- `app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/model/PublicationAcceptanceContractTest.java`
- `app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/NonPersistentDispatchRelayTest.java`
- `app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/RegistrationPublicationContractTest.java`
- `app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/NonPersistentEventPublicationTest.java`
- `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationOutcomeStore.java`
- `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java`
- `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java`
- `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/PrimaryWriteParticipation.java`
- `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/RegistrationAuthorityPostgresTest.java`
- `app/port-binding-authority/pom.xml`
- `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/BindingAcquisitionAccepted.java`
- `app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/BindingDetachmentAccepted.java`
- `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/EventFamily.java`
- `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/EventRef.java`
- `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/PublishedEvents.java`
- `docs/architecture/Event_Publication_Contracts_Step_4A_2026-10-19.md`
- `docs/architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md`
