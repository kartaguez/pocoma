# REGISTRATION — plan rebaseliné sur les trois contrats READ

```text
Step: REGISTRATION
Phase: IMPLEMENTATION PLANNED, après R1 et R2 du plan global
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
