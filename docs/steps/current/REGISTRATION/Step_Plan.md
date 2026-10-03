# REGISTRATION — plan rebaseliné sur les trois contrats READ

**WAVE 2: ROUVERTE, CORRECTION BLOQUÉE À FIX.1 — REG.1/REG.2/REG.3 historiquement terminés ; REG.4/REG.5 non commencés.**

La clôture antérieure ci-dessous est conservée comme historique. La correction demandée à partir du HEAD `6e889099` a été arrêtée au GO/NO-GO PostgreSQL : le test à deux transactions de l'[audit de correction](Wave2_Optimistic_Binding_and_Module_Boundary_Audit.md#22-journal-de-correction--fix1-gono-go-2026-10-03) montre qu'un `NOT EXISTS` dans l'`UPDATE` conditionnel peut rester fondé sur le snapshot antérieur après attente d'une mutation active. Aucun writer ni arc Maven n'a été modifié. Wave 2 ne redevient pas DONE sans nouvelle preuve et gates finaux ; Wave 3 reste interdite.

## Journal Wave 2 — Registration Core (2026-10-03)

Baseline : `git fetch origin` exécuté ; branche `v2-make-it-pull` ; HEAD
`1b04b5b3d85cc5e874fcac01a419a91657a1b6e1` ; divergence local/distant `0/0` ;
seul `docs/architecture/mermaid-diagram.png` est non suivi et doit rester intact.
Les quatre documents canoniques ont été relus. Aucun changement distant post-Wave-1.

### Déclaration de vérification avant code

| Lot | Impact de production permis | Impact interdit | Slice primaire | Slices secondaires | Base DB | Gate | Escalade |
|---|---|---|---|---|---|---|---|
| REG.1 | request Registration, SQL courant, admission HTTP AuthN, wiring WEB | User, Binding, outcome, faits, Consumption générique, READ | `./mvnw -pl runtime-web-api -am test` | persistance Registration ciblée | historique Flyway existant ; baseline trusted NONE | architecture si modules/arcs nouveaux ; FULL seulement clôture Wave 2 | frontière Binding/Command/Consumption réellement franchie |
| REG.2 | outcome, transition User/Binding existante, faits et SQL courant Registration | seconde autorité/lock/stream Binding, CURRENT_BINDING READ, REG.4/5 | `./mvnw -pl runtime-binding-consumption-worker -am test` | `./mvnw -pl runtime-command-consumption-worker -am test` uniquement si contrat Binding partagé modifié ; PostgreSQL Registration ciblé | historique Flyway existant | architecture si frontières nouvelles ; FULL seulement clôture Wave 2 | contrat Command/Binding partagé ou ordre de lock modifié |
| REG.3 | locator/runtime Registration, wiring Consumption existant, tests fenced | primitive Consumption générique sans obstacle démontré, REG.4/5 | `./mvnw -pl runtime-registration-consumption-worker -am test` si créé | BINDING, persistance Registration ; COMMAND si contrat partagé modifié | historique Flyway existant | `./mvnw -pl architecture-tests -am test` si nouveaux modules/arcs ; puis `./mvnw test` au milestone | obstacle concret des primitives génériques |

### Décision Consumption préalable à REG.3

- Projection Task : `projectionEngine.execute(task)` prépare hors transaction finale ; `TransactionalFinalizeConsumptionUseCase` ouvre la transaction, `FinalizeConsumptionService` verrouille d'abord le claim, publie la projection et terminalise dans cette transaction. Les écritures durables sont projection + transition du slot.
- Command Result : `CommandResultConsumptionLocator` recharge source/outcome/intention et matérialise dans son callback ; `TransactionalExecuteConsumptionUseCase` englobe ce callback, la provenance et le CAS terminal. Les écritures durables sont Result + provenance + transition du slot.
- Command WRITE : `CommandConsumptionExecution` appelle le métier dans `Execute` ; WA.6 documente l'ordre `locks métier → stream E au fence → outcome/provenance/claim`.
- Registration candidate : arbitrer sous le lock `stream E` de l'autorité Binding, puis créer U, acquérir B, écrire UserCreated et outcome ; la publication du Binding Fact est déjà intégrée à `acquire`. Toutes ces écritures et le CAS terminal doivent partager une transaction. **Mode retenu : Execute.** `Finalize` prendrait claim avant stream, ordre inverse de WA.6. Avec `Execute`, le CAS terminal perdu provoque rollback de l'ensemble des effets, sans nouvelle primitive Consumption.

### REG.1 — journal d'implémentation

Baseline : HEAD et divergence indiqués ci-dessus ; aucun commit distant nouveau. Décision : le Canon ne définit aucun attribut de profil ; le payload courant est l'objet JSON vide `{}`. Le POST `/api/v1/registrations` accepte ce contrat et refuse les champs qui tentent de fournir E. Le `requestId` est un UUID serveur. `AdmitRegistrationService` ouvre et termine la transaction avant le retour 202. Le store SQL V27 conserve E `(issuer,subject)` exactement, `payload` JSONB et `created_at` ; PK et trigger interdisent duplication, update et delete. Aucun état technique de traitement n'est dans la request.

Fichiers/modules : `engine-registration` (request, port, admission), `infra-persistence-jpa` (V27 et adapter JDBC), `runtime-web-api` (controller/config), `supra-authentication-spring-security` (route authentifiée), test PostgreSQL WEB. La sécurité JWT réutilise le resolver du principal existant ; aucune résolution E→U ni écriture métier à l'admission.

Slice déclarée : `./mvnw -pl runtime-web-api -am test`, avec persistance Registration ciblée. Base DB : chaîne Flyway existante, car le statut normatif reste `Trusted baseline: NONE`. Gate architecture requis par `engine-registration` et ses dépendances. Aucun crossing inattendu. Commandes exécutées : `./mvnw -pl runtime-web-api -am test` (échec d'environnement : JVM/Mockito dans le sandbox), puis la même commande avec `JAVA_HOME=/Users/Kartaguez/.sdkman/candidates/java/current` hors sandbox ; relances `JAVA_HOME=... ./mvnw -pl runtime-web-api -am test -q` après correction des attentes de migration V27/V28 et du parsing HTTP. Dernière exécution : **PASS**. Le reactor complet n'a pas servi de boucle REG.1.

Preuves : `RegistrationAdmissionPostgresTest` lit la request immédiatement après le 202, vérifie l'E exacte, le replay HTTP avec un deuxième UUID, l'absence de U/B/outcome/faits/Events, 401 sans request, refus du spoofing par payload et l'interdiction SQL de mutation. `PrimaryMigrationsPostgresTest` valide V27/V28 sur la chaîne existante. Commit REG.1 : `72eac6b9137ce42757e03d899a12b3d799dacda3`.

### REG.2 — journal d'implémentation

Décision : `ExternalIdentityBindingPort.acquireWithInitializer` et son adapter existant prennent `stream E`, contrôlent l'autorité primaire, puis invoquent l'initialisation du User uniquement sur le chemin libre, avant de réserver B, avancer R et append le Binding Fact habituel. Le callback crée `User(U)` et `user_created_facts`; `ExecuteRegistrationService` écrit ensuite l'unique outcome. Le tout se déroule dans la transaction externe `Execute` ; le chemin conflict n'invoque pas l'initialisation. V28 impose outcome terminal Registered/Rejected par CHECK, FK et PK requestId ; les outcomes et faits sont immutables. Aucun nouveau lock, stream, table d'autorité Binding ou accès CURRENT_BINDING.

Fichiers/modules : port `domain-user-identity`, adapter Binding existant, `engine-registration` (transition/outcome/fact port), `infra-persistence-jpa` (V28 et adapters JDBC), test du faux port côté `engine-command`, tests PostgreSQL Registration. Le contrat Binding partagé ayant changé, la slice COMMAND secondaire déclarée a été exécutée. Aucun crossing inattendu et aucune primitive Consumption générique modifiée.

Slices exécutées : `JAVA_HOME=... ./mvnw -pl runtime-binding-consumption-worker -am test -q` **PASS**, puis **PASS** après ajout de la course Detach ; `JAVA_HOME=... ./mvnw -pl runtime-command-consumption-worker -am test -q` **PASS** ; tests PostgreSQL `RegistrationAuthorityPostgresTest` inclus dans la dépendance persistence. Base DB : chaîne Flyway existante ; gate architecture requis par module/frontière nouveaux. Reactor complet réservé au gate final. `RegistrationAuthorityPostgresTest` prouve succès et replay sans duplication, deux requests concurrentes même E avec exactement un gagnant, courses Registration/Attach et Registration/Detach via le même stream, rejet sur E déjà attachée, detach suivi d'un B neuf, révisions contiguës et rollback de U/B/faits/outcome lors d'une panne. Aucun fait synthétique historique ni écriture directe CURRENT_BINDING. Commit REG.2 : `47cf9b51a8fb679f64424221c41a00431ebe8976`.

### REG.3 — journal d'implémentation

Décision : runtime dédié `runtime-registration-consumption-worker`, discovery JDBC de la request et reload autoritatif pendant `Execute`; clés `REGISTRATION_REQUEST` / `REGISTRATION_WORKER_V1`. `SequentialConsumptionOrchestrator`, claim/lease/provenance, retry, takeover, terminal CAS et polling restent les primitives existantes. La failure technique `IllegalStateException` est classée invariant terminal Consumption ; les autres exceptions techniques sont réessayées. Le conflit E déjà utilisé produit `RegistrationOutcome.Rejected` et un terminal Consumption REJECTED, jamais une failure technique. Un outcome déjà présent est relu sans rejouer U/B/faits.

Fichiers/modules : nouveau runtime Registration, `JdbcRegistrationDiscovery` dans `infra-persistence-jpa`, tests PostgreSQL runtime. Aucun changement générique Consumption. Slice exécutée : `JAVA_HOME=... ./mvnw -pl runtime-registration-consumption-worker -am test -q` **PASS** ; les derniers ajouts de preuve W1/W2 tardive et de classification d'invariant sont inclus dans le reactor final **PASS**. Secondary BINDING et persistance exécutées ; COMMAND exécutée à cause du port partagé REG.2. Base DB : chaîne Flyway ; gate architecture `JAVA_HOME=... ./mvnw -pl architecture-tests -am test -q` **PASS**. Commit REG.3 : `6daba15f4353ebcb82f3fc856011b31731681879`.

`RegistrationRuntimePostgresTest` couvre deux requests, restart, replay, panne transitoire suivie de retry, takeover, claim perdu avant commit, reprise tardive de W1 après commit de W2 et multi-worker. Le CAS terminal et les écritures métier appartiennent à la même transaction ; la perte du claim rollbacke U, B, outcome et faits. Le worker n'attend pas CURRENT_BINDING et ne produit ni `REGISTRATION_RESULT`, ni ProjectionTask, ni Event de conversion.

### Matrice de preuves Wave 2

| Invariant | Preuve ciblée |
|---|---|
| E exacte, UUID serveur, commit avant 202, replay HTTP nouveau | `RegistrationAdmissionPostgresTest.authenticatedRequestCommitsExactlyTheAttestedIdentityBefore202AndReplayIsNew` |
| AuthN absente/invalide et spoofing sans request | `RegistrationAdmissionPostgresTest.unauthenticatedAndSpoofedPayloadLeaveNoRequest` |
| Request immutable ; admission sans U/B/outcome/fait/Event | `RegistrationAdmissionPostgresTest.sqlRejectsMutation` et assertions de tables après POST |
| Succès : U, B neuf, UserCreated, Attached/Binding Fact, Registered cohérents et uniques | `RegistrationAuthorityPostgresTest.successIsAtomicAndRetryDoesNotReplayEffects` |
| Rejet : outcome unique, aucune nouvelle mutation/fait | `RegistrationAuthorityPostgresTest.existingAttachRejectsWithoutCreatingUserAndDetachAllowsFreshOccurrence` |
| Deux requests même E ; pas de User orphelin | `RegistrationAuthorityPostgresTest.twoConcurrentRequestsChooseOneWinnerWithoutAnOrphan` |
| Registration vs Attach ; même stream et lock order | `RegistrationAuthorityPostgresTest.registrationAndAttachArbitrateThroughTheSameStream` ; slices BINDING et COMMAND |
| Detach puis Registration et course Detach/Registration ; B neuf et R contigu | `RegistrationAuthorityPostgresTest.existingAttachRejectsWithoutCreatingUserAndDetachAllowsFreshOccurrence`, `registrationAndDetachAreOrderedByTheAuthoritativeStream` |
| Rollback technique de la transition entière | `RegistrationAuthorityPostgresTest.technicalFailureRollsBackUserBindingFactAndOutcome` |
| Restart, retry, outcome non rejoué | `RegistrationRuntimePostgresTest.workerDrainsTwoRequestsAndRestartCannotReplayTheWinner`, `technicalFailureRetriesWithoutBusinessOutcome` |
| Takeover, multi-worker, stale claim et reprise tardive W1 | `RegistrationRuntimePostgresTest.takeoverFencesStaleExecutionAndTwoWorkersConverge`, `workerThatBeganBeforeTakeoverCannotPublishAfterWinnerCommits` |
| Binding Facts append-only, pas de synthetic historical fact, Attach/Detach et Command fencing conservés | slices BINDING/COMMAND et gate architecture existants ; V27/V28 n'écrivent pas les faits Binding historiques |

Clôture Wave 2 : gate architecture **PASS** ; `JAVA_HOME=/Users/Kartaguez/.sdkman/candidates/java/current ./mvnw test -q` **PASS** sur l'état final. Le reactor complet a été exécuté au gate architectural, puis répété après l'ajout du test concurrent Detach et du classement explicite `IllegalArgumentException` en invariant technique ; il n'a pas servi de boucle locale REG.1/2/3. Aucun crossing inattendu : la dépendance COMMAND du port Binding était prévue comme secondary conditionnelle et a été vérifiée. Aucun scope REG.4/REG.5 traité. Le PNG utilisateur non suivi reste intact.


```text
Step: REGISTRATION
Phase: REGISTRATION CORE IMPLEMENTED ; REG.4/REG.5 PLANNED, après R1 et R2 du plan global
Authority: Step_Canon.md et ../ARCHITECTURE/Read_Materialization_Gap_and_Migration_Plan.md
Sequence: REG.1 → REG.2 → REG.3 → REG.4 → REG.5
```

Ce plan remplace l'ancien REG.0 Event→ProjectionTask CURRENT_BINDING, l'ancien `REGISTRATION_RESULT@1` et le GET conditionné par le binding courant. L'[audit d'écart global](../ARCHITECTURE/Read_Materialization_Gap_and_Migration_Plan.md) contient les références au code, les alternatives de stockage, le failure model, le cutover de COMMAND_RESULT et les preuves transversales. Le [Canon](Step_Canon.md) fixe le métier. Les audits historiques [step](step_audit.md), [domain](domain_audit.md) et [Current Binding](Current_Binding_Registration_Architecture_Audit.md) restent des traces factuelles ou d'anciennes hypothèses, pas des instructions de pipeline.

## Architecture cible

```text
POST Registration(authenticated E, payload)
  → RegistrationRequest(requestId, requesterExternalIdentity=E, payload) durable
  → 202 + requestId
  → Consumption Registration : reload, claim, retry, fencing
  → outcome terminal unique : Registered(U,B) ou Rejected(EXTERNAL_IDENTITY_ALREADY_USED)
  → source terminale durable → Consumption Result → immutable REGISTRATION_RESULT
  → GET(requestId, authenticated E) si E = request.requesterExternalIdentity

sur succès uniquement, dans le commit autoritatif du terminal :
  User(U) + Binding(E,U,B) + UserCreated(U) + ExternalIdentityAttached(E,U,B)
  → Binding Fact durable → Consumption Binding → advance CURRENT_BINDING(E,R,value)
  → GET self binding → B pour la première Command(E,B)
```

Le commit autoritatif du succès réunit U, B, outcome et faits. Les deux matérialisations READ sont asynchrones et indépendantes. `REGISTRATION_RESULT` n'attend ni ne lit `CURRENT_BINDING` ; l'inverse aussi. Après detach/B2, E lit encore le résultat Registered historique. `Rejected` reste lisible sans B. Aucun ResponseToken, ProjectionTask Result, ProjectionTask Current ou Event Binding de pure conversion n'est requis.

## Modules et contrats à concevoir

Réutiliser `domain-user-identity` pour E/U/B, l'autorité `ExternalIdentityBindingPort` et son stream de révision ; `engine-consumption`, `orchestrator-consumption` et `supra-consumption-worker` pour les claims/leases/retries ; `locator-consumption-binding`, `runtime-binding-consumption-worker` et `JdbcCurrentBindingAdapter` pour CURRENT_BINDING. Les noms exacts des futurs modules Registration/Result sont à fixer au lot concerné, avec un gate architectural si les frontières Maven changent. Les responsabilités requises sont : admission WRITE, request/outcome autoritatifs, exécution sous claim fenced, store Result/read par id, HTTP POST/GET et runtime worker. Ne créer ni deuxième autorité Binding ni deuxième moteur Consumption.

Le POST prend E exclusivement du principal JWT ; pas de résolution E→U, contrôle de disponibilité, B, User, outcome ou fait métier à l'admission. La request est immutable, irrévocable, identifiée par un UUID serveur et committée avant réponse. AuthN absente/invalide → 401 sans request ; toute E attestée → request seule et 202. Le GET prend `requestId` et E authentifiée, lit uniquement le store Result, rend 404 opaque pour absent/non matérialisé/non-owner ; aucun état Consumption ni failure technique public.

L'exécution gagne le lock/l'arbitrage de la même autorité Binding qu'Attach/Detach. Elle crée U seulement sur le chemin gagnant, obtient un B neuf, écrit outcome unique et `UserCreated` ; l'autorité Binding émet une seule fois `ExternalIdentityAttached`. Sur conflit, seul `Rejected(EXTERNAL_IDENTITY_ALREADY_USED)` est écrit. Deux requests concurrentes pour E : un succès et un rejet, sans User orphelin. Le retry de la même request recharge l'outcome et ne rejoue pas ses effets. Exception ou perte de claim rollbacke la transition entière. Le fait Binding durable existant est la source de CURRENT_BINDING ; son tombstone/révision restent inchangés.

Le résultat dérive de l'outcome terminal et de l'owner E de la request. Une découverte directe des outcomes est le premier choix à examiner ; un terminal Event atomique avec l'outcome peut être retenu si nécessaire à la discovery/reprise/provenance. Le payload de discovery n'est pas autoritatif à lui seul : recharger `RegistrationRequest` et `RegistrationOutcome`, puis vérifier `terminalSource.requestId == request.requestId == outcome.requestId`, la terminalité réelle et l'égalité exacte de `Result.ownerE` avec `request.requesterExternalIdentity` avant `ensureResult(requestId,E,outcome)`. Cette écriture doit être immutable et idempotente : même contenu = no-op ; contenu divergent sous le même requestId = violation d'invariant ; perte du claim avant commit = aucun Result publié. La représentation SQL commune à COMMAND_RESULT sera choisie en R1 entre table générique et tables dédiées. L'échec du worker Result est diagnostiqué et réessayé techniquement ; il ne devient jamais un troisième outcome Registration.

## Lots et preuves

| Lot / dépendance | Travail et preuve de sortie | Frontière de vérification prévue |
|---|---|---|
| REG.1, après R2 du plan global | Request durable, discovery, POST JWT. Prouver commit avant 202, immutabilité, E seule, 401, zéro U/B/outcome/fait avant worker. | WEB `./mvnw -pl runtime-web-api -am test` ; SQL actuel ciblé. |
| REG.2, après REG.1 | Transition WRITE et outcome unique via l'autorité Binding. Prouver concurrence de deux requests, Attach/Detach, B neuf, un `UserCreated`/`Attached`, rejet sans B, rollback à chaque point et reprise même request. | BINDING `./mvnw -pl runtime-binding-consumption-worker -am test` et COMMAND `./mvnw -pl runtime-command-consumption-worker -am test` si contrat Binding partagé changé ; tests SQL ciblés. |
| REG.3, après REG.2 | Worker Registration avec discovery/reload, claim, lease, retry, takeover et finalisation fenced existants. Prouver restart, multi-worker, claim perdu et aucune double mutation. | Nouvelle ancre `./mvnw -pl runtime-registration-consumption-worker -am test` si ce runtime est créé. |
| REG.4, après REG.3 et R1 | Source terminale, reload request/outcome, contrôle id/terminalité/owner, `ensureResult`, GET E et backfill éventuel. Prouver chaque mismatch, Registered et Rejected lisibles par E, E2→404, detach/B2 sans perte d'accès, retry/replay/divergence, claim perdu et zéro lecture READ→READ. | Nouvelle ancre Result/Registration + WEB ; BINDING seulement si son comportement change. |
| REG.5, après REG.4 et migration COMMAND_RESULT | E2E réel : E→request→Result, Binding Fact→Current→B→première Command(E,B)→CommandResult ; workers Result et Binding arrêtés alternativement pour prouver la convergence indépendante. Prouver ancien B refusé et anciens résultats encore accessibles. | Slices affectées composées ; gate architecture si nouveaux modules/frontières ; gate reactor complet seulement à la clôture de la grande Wave ou intégration qui l'exige. |

Avant toute modification de `app/`, relire [`Reactor_Verification_Policy.md`](../../../testing/Reactor_Verification_Policy.md) et déclarer impact permis/interdit, slices primaire/secondaires, base, gate et conditions d'escalade. `architecture-tests` est un gate global aux vrais changements de modules/frontières, pas un ajout automatique aux slices. Ne pas lancer le reactor complet comme boucle locale. La politique du checkout indique encore `Trusted baseline: NONE` : ne pas supposer une baseline certifiée pour la vérification SQL.

## Décisions à fermer avant le code concerné

La forme physique du store Result, la découverte directe de l'outcome versus un terminal Event, le diagnostic des failures Result et la méthode de cutover/backfill de COMMAND_RESULT sont décrits dans le plan global. Aucun de ces choix ne change l'owner historique E, la séparation des deux READ ou la source Binding Fact. Une violation démontrée de la garantie de non-réattribution `(issuer,subject)` doit être résolue au niveau AuthN/issuer avant de s'appuyer sur E pour l'ownership ; elle ne justifie pas à elle seule un ResponseToken.
