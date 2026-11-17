# WRITE_ADMISSION — audit architectural de l'identité de binding

```text
Sujet: WRITE_ADMISSION
Nature: audit documentaire, sans implémentation
Date: 2026-09-30
Branche: v2-make-it-pull
HEAD audité: 67a7f2b38602450eef5bd5a3602d12b54a39c82e
Verdict: CLOSABLE
```

## 1. Baseline

Après `git fetch origin v2-make-it-pull` :

| Élément | Valeur |
|---|---|
| branche locale | `v2-make-it-pull` |
| HEAD local avant audit | `67a7f2b38602450eef5bd5a3602d12b54a39c82e` |
| `origin/v2-make-it-pull` | `67a7f2b38602450eef5bd5a3602d12b54a39c82e` |
| divergence | `0` derrière / `0` devant |
| working tree initial | propre |
| dernier commit | `docs: plan registration implementation` |

Le seul changement produit par cet audit est le présent document. Aucun code, test, migration,
POM, configuration, canon ou plan REGISTRATION n'est modifié.

## 2. Autorités documentaires et sources lues

### 2.1 Canon actuel

- [`REGISTRATION/Step_Canon.md`](../REGISTRATION/Step_Canon.md), autorité actuelle D1–D33 ;
- [`REGISTRATION/Step_Plan.md`](../REGISTRATION/Step_Plan.md), plan d'implémentation actuel, à
  réviser avant toute implémentation ;
- [`CCR/Step_Canon.md`](../CCR/Step_Canon.md), outcome terminal, terminal Event et
  projection `COMMAND_RESULT` ;
- [`authorization-kernel-contracts.md`](../../../architecture/authorization-kernel-contracts.md),
  séparation capacités courantes / relations métier et partage des policies WRITE/READ ;
- [`recorded-command-intake.md`](../../../architecture/recorded-command-intake.md), admission HTTP
  Command actuellement canonisée ;
- [`recorded-command-persistence.md`](../../../architecture/recorded-command-persistence.md),
  envelope durable et séparation Consumption ;
- [`command-consumption-runtime.md`](../../../architecture/command-consumption-runtime.md), chaîne
  du worker Command ;
- [`write-side-closure.md`](../../../architecture/write-side-closure.md), voie WRITE officielle ;
- [`read-side-current-state.md`](../../../architecture/read-side-current-state.md) et
  [`read-side-target.md`](../../../architecture/read-side-target.md), stores READ, versionnement et
  projection `AUTH` ;
- [`type-ownership.md`](../../../architecture/type-ownership.md) et
  [`module-dependency-matrix.md`](../../../architecture/module-dependency-matrix.md), ownership et
  dépendances ;
- documents Consumption, Event et Projection sous `docs/architecture/`, notamment
  `consumption-transactional-execution.md`, `consumption-event-pull-runtime.md` et
  `projection-engine.md` ;
- [`use-case-families.md`](../../../use-case-families.md) et
  [`worker-contract-matrix.md`](../../../testing/worker-contract-matrix.md).

Il n'existe pas de step ni de modèle nommé `EUTH` au HEAD audité. `AUTH` existe comme projection
historique par Pot et version métier ; ce n'est pas une autorité User/Identity.

### 2.2 Audits et historique

- [`REGISTRATION/step_audit.md`](../REGISTRATION/step_audit.md) et
  [`REGISTRATION/domain_audit.md`](../REGISTRATION/domain_audit.md) ;
- [`CCR/RegisterUser_Identity_Audit.md`](../CCR/RegisterUser_Identity_Audit.md) ;
- [`CCR/Step_Plan.md`](../CCR/Step_Plan.md) ;
- plans historiques Command, Consumption, Event, Projection et READ sous `docs/plans/` lorsque
  nécessaires à la traçabilité.

Ces documents historiques expliquent l'état actuel mais ne priment ni sur les canons actuels ni sur
WA1–WA10. En particulier, l'ancien audit REGISTRATION proposait de conserver la résolution User de
Command à l'admission ; cette proposition est précisément remplacée par le présent cadrage.

### 2.3 Code, schéma et tests déterminants

L'audit a suivi les classes de production et leurs tests dans :

- `supra-http-write-command`, `orchestrator-command-admission`,
  `supra-authentication-spring-security` et `runtime-web-api` ;
- `engine-command`, `locator-consumption-command`, `engine-pot-command` et
  `runtime-command-consumption-worker` ;
- `domain-authorization`, `domain-pot-policy`, `domain-pot-projection`,
  `engine-command-result` et `supra-http-read-query` ;
- `infra-persistence-jpa`, migrations V8, V9 et V17, repositories et adapters Command/Identity ;
- tests unitaires, PostgreSQL, runtime et E2E cités en section 13.

Références de code principales, toutes sous `app/` :

| Rôle | Fichier |
|---|---|
| endpoint WRITE | `supra-http-write-command/src/main/java/com/kartaguez/pocoma/supra/http/write/command/AsyncCommandController.java` |
| admission Command | `orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/SubmitRecordedCommandService.java` |
| port de résolution | `orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/port/out/ExternalIdentityResolverPort.java` |
| adapter/repository Identity | `infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/identity/JpaExternalIdentityResolverAdapter.java` et `repository/identity/ExternalIdentityJdbcRepository.java` |
| envelope Command | `engine-command/src/main/java/com/kartaguez/pocoma/engine/command/model/RecordedCommand.java` et `AuthorizationSnapshot.java` |
| persistence Command | `infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/command/JpaRecordedCommandAdapter.java`, `RecordedCommandRecordMapper.java` et `repository/command/JpaRecordedCommandRepository.java` |
| exécution | `engine-command/src/main/java/com/kartaguez/pocoma/engine/command/execution/ExecuteRecordedCommandService.java` |
| adaptation Pot/AuthZ | `engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/service/command/AbstractPotCommandUseCaseAdapter.java` et `PotAuthorizationGuard.java` |
| résultat READ | `infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/projection/JdbcCommandResultProjectionInputLoader.java` et `supra-http-read-query/src/main/java/com/kartaguez/pocoma/supra/http/read/query/CommandResultController.java` |
| schéma | `infra-persistence-jpa/src/main/resources/db/migration/V8__recorded_commands.sql`, `V9__external_identities.sql`, `V17__command_outcomes_and_terminal_events.sql` |

## 3. Architecture actuelle constatée

### 3.1 Chaîne Command réelle

```text
POST /api/v1/commands
  -> WebApiSecurityConfiguration
     -> Spring OAuth2 Resource Server valide le JWT
  -> AuthenticatedExternalPrincipalArgumentResolver
  -> SpringSecurityExternalPrincipalAdapter
     -> AuthenticatedExternalPrincipal
     -> ExternalIdentity(issuer, subject)
  -> AsyncCommandController
     -> forme HTTP minimale + sérialisation opaque du payload
  -> SubmitRecordedCommandService, transaction synchrone
     -> ExternalIdentityResolverPort.findUserId(E)
     -> JpaExternalIdentityResolverAdapter
     -> ExternalIdentityJdbcRepository
     -> SELECT pocoma_user_id FROM external_identities
     -> AuthorizationSnapshotFactory
        -> PocomaUserId + Permission traduites + temps JWT + issuer
     -> RecordedCommandPort.insert
     -> JpaRecordedCommandAdapter / JpaRecordedCommandRepository
     -> INSERT recorded_commands
  -> commit
  -> 202

plus tard :

recorded_commands
  -> Command discovery / Consumption acquire
  -> transaction fenced
  -> ExecuteRecordedCommandService.reload
  -> contrôle validUntil
  -> decode
  -> CommandDispatcher
  -> AbstractPotCommandUseCaseAdapter
     -> UserContext(auth_user_id, auth_permissions_json)
  -> PotAuthorizationGuard + état Pot primaire courant
  -> mutation / rejet
  -> outcome + Events + terminalisation
```

Références directes :

- `AsyncCommandController.submit` valide seulement l'envelope HTTP ;
- `SubmitRecordedCommandService.submit` effectue la résolution E → U avant l'insert ;
- `ExternalIdentityResolverPort` expose `Optional<PocomaUserId> findUserId(ExternalIdentity)` ;
- `JpaExternalIdentityResolverAdapter` exige la transaction appelante et délègue le SELECT à
  `ExternalIdentityJdbcRepository` ;
- `AuthorizationSnapshotFactory` traduit les autorités externes en `Permission` dès l'admission ;
- `RecordedCommand` contient un `AuthorizationSnapshot`, pas l'ExternalIdentity ni son subject ;
- `ExecuteRecordedCommandService` recharge le snapshot mais ne relit jamais User/Identity ;
- `AbstractPotCommandUseCaseAdapter` transforme le `PocomaUserId` capturé en `domain-pot.UserId` ;
- `PotAuthorizationGuard` combine les permissions capturées et les relations Pot primaires chargées
  pendant le traitement.

### 3.2 État durable actuel

V8 crée `recorded_commands` avec :

```text
command_id, command_type, payload_json, submitted_at,
auth_user_id, auth_issuer,
auth_authenticated_at, auth_issued_at, auth_valid_until,
auth_permissions_json
```

Il n'existe ni `auth_subject`, ni ExternalIdentity complète, ni `binding_id`. `auth_user_id` est
donc une résolution synchrone figée ; `auth_permissions_json` est une traduction de droits effectuée
avant l'enregistrement.

V9 crée l'autorité actuelle `external_identities(issuer, subject, pocoma_user_id)` avec PK
`(issuer, subject)`. Elle ne possède ni `binding_id`, ni FK vers une table User, ni writer de
production. Au HEAD audité, les bindings sont créés par les fixtures.

V17 crée `command_outcomes` et `command_terminal_events`. Le loader `COMMAND_RESULT`
(`JdbcCommandResultProjectionInputLoader`) relit `recorded_commands.auth_user_id` afin de publier
`submittedByUserId`. `CommandResultController` résout par ailleurs le JWT vers U via le même port
primaire avant de comparer ce UserId à la projection. C'est un accès primaire sur un chemin READ,
pas une violation de WA2, mais ce contrat doit évoluer lorsque `auth_user_id` disparaît.

### 3.3 AuthN, AuthZ et AUTH

- AuthN est externe au domaine : Spring vérifie signature, issuer, audience et validité du JWT ;
  l'adapter exige `iss`, `sub`, `iat`, `exp` et `auth_time`.
- La traduction `scope/scp` → `Permission` se fait actuellement à l'admission. La décision finale
  d'autorisation métier se fait au worker.
- Le worker lit déjà l'état Pot primaire courant nécessaire aux relations creator/member et aux
  invariants métier. Il ne lit cependant pas l'identité primaire courante : U et les permissions
  arrivent du snapshot.
- La projection `AUTH(Pot,V)` est une vue READ historique des relations Pot. Le canon d'autorisation
  interdit d'en faire une réplique de l'autorité User/Identity ou une matrice de permissions.
- Les capabilities issues du token restent distinctes des relations métier. WA1 impose que leur
  interprétation pour autoriser l'opération ait lieu au traitement ; l'admission peut seulement
  conserver l'évidence authentifiée nécessaire à cette décision ultérieure.

## 4. Matrice WA1–WA10

| Décision | Compatibilité | Écart actuel | Impact nécessaire |
|---|---|---|---|
| WA1 — admission = AuthN + intention durable | compatible | Command résout U et traduit les permissions avant insert | réduire l'admission à la forme, E attestée, B et évidence AuthN nécessaire |
| WA2 — zéro lecture primaire WRITE | compatible comme cible | l'unique POST Command lit `external_identities` | déplacer la résolution dans l'exécution fenced ; ajouter un guard architectural/test |
| WA3 — toute E authentifiée peut déposer | compatible | identité inconnue → `403 USER_NOT_PROVISIONED` sans Command | toute E valide + B bien formé → `202`, y compris sans User/binding |
| WA4 — identité capturée E (+ B) | compatible | le subject n'est pas persisté et U est persisté | persister issuer + subject + binding_id ; retirer U de l'admission |
| WA5 — Command sous occurrence B | compatible | aucune occurrence n'est modélisée | modèle et port atomique de résolution exacte `(E,B) -> U` au worker |
| WA6 — B opaque, unique, non ordinal, non réutilisé | compatible | aucun type/colonne | value object `BindingId(UUID)` cohérent avec les IDs opaques du repository |
| WA7 — forme de B seulement au POST | compatible | aucun B ; existence de E vérifiée | DTO exige B syntaxiquement valide ; aucune query d'existence/courant |
| WA8 — rejet fonctionnel non-oracle | compatible | identité inconnue rejetée synchroniquement, cas distinguable | motif worker unique `CALLER_IDENTITY_NOT_CURRENT`, port sans diagnostic public détaillé |
| WA9 — self-service READ courant | compatible | aucune projection Identity ; GET actuels résolvent E sur le primaire | projection READ minimale dédiée, alimentée par faits User/Identity |
| WA10 — nouvel ID à chaque attach | compatible | binding naturel sans identité d'occurrence ; plan REGISTRATION interdit B | ajouter B au binding, au succès Registration et aux faits nécessaires |

Les dix décisions n'exigent aucun changement du moteur Consumption, du fencing, de l'atomicité
PostgreSQL ou du kernel d'autorisation. Elles déplacent le moment de la résolution et enrichissent
l'identité durable du binding.

## 5. Inventaire des lectures primaires HTTP WRITE synchrones

### 5.1 Violation constatée

Une seule route HTTP WRITE de production existe au HEAD audité, et elle viole WA2 :

| Route | Classe d'entrée | Port | Adapter | Store/table | Lecture et raison |
|---|---|---|---|---|---|
| `POST /api/v1/commands` | `AsyncCommandController` → `SubmitRecordedCommandService` | `ExternalIdentityResolverPort.findUserId` | `JpaExternalIdentityResolverAdapter` → `ExternalIdentityJdbcRepository` | PostgreSQL primaire, `external_identities` | `SELECT pocoma_user_id WHERE issuer=? AND subject=?` pour refuser une E non provisionnée et construire `AuthorizationSnapshot.userId` |

Nombre de chemins HTTP WRITE actuels lisant le primaire : **1**.

Toutes les variantes Command partagent ce même endpoint et ce même chemin ; elles ne constituent
pas des violations distinctes. Aucun autre `POST`, `PUT`, `PATCH` ou `DELETE` de production n'a été
trouvé. Registration et Attach/Detach ne sont pas encore implémentés.

### 5.2 Ce qui n'est pas une violation de WA2

- validation JWT/JWK par le Resource Server : AuthN externe, pas lecture Pocoma primaire ;
- validation locale de `commandType`, présence du payload, taille et sérialisabilité : structure ;
- `INSERT recorded_commands` : écriture durable attendue ;
- discovery/reload/lectures Pot dans le worker : traitement asynchrone autorisé ;
- `GET /api/v1/command-results/{id}` et `GET /api/v1/pots/{id}` : chemins READ. Leur résolution
  actuelle via le primaire reste néanmoins une dette vis-à-vis de WA9 et de la séparation READ.

## 6. Impact Command

### 6.1 Envelope durable cible

Le minimum conceptuel devient :

```text
RecordedCommand
  commandId
  commandType
  serializedPayload
  submittedAt
  authExternalIdentity       // issuer + subject attestés
  bindingId                  // présenté par le client, forme validée seulement
  authenticationEvidence     // uniquement ce qui est requis pour décider plus tard
```

`authenticationEvidence` peut conserver `authenticatedAt`, `issuedAt`, `validUntil` et les
autorités externes attestées. Le plan futur devra choisir la forme exacte, mais l'admission ne doit
plus produire une décision d'AuthZ. Si les capabilities du token restent requises, conserver les
claims/autorités provider-neutral nécessaires puis les traduire au worker est la lecture la plus
stricte de WA1. Le token brut ne doit pas être persisté.

### 6.2 Champs actuels

| Champ actuel | Sort cible |
|---|---|
| `auth_user_id` | obsolète dans la source Command ; U est résolu au traitement |
| `auth_issuer` | devient une composante de `authExternalIdentity`, avec le subject |
| absence de subject | ajout obligatoire d'`auth_subject` ou représentation équivalente |
| `auth_permissions_json` | ne peut plus signifier « droits déterminés à l'admission » ; remplacer par évidence attestée ou documenter explicitement une simple capture non décisionnelle |
| `auth_authenticated_at`, `auth_issued_at`, `auth_valid_until` | conservables comme évidence/TTL d'admission si la policy future les exige |

`AuthorizationSnapshot` ne peut plus exiger `PocomaUserId` à la construction. Il doit être séparé
entre une preuve d'authentification durable et un contexte d'exécution enrichi plus tard de U et des
capabilities. `PocomaUserId` quitte déjà conceptuellement `engine-command` selon REGISTRATION D31.

### 6.3 Point précis de résolution

La résolution doit être appelée dans `ExecuteRecordedCommandService.execute`, après le reload
autoritatif de `RecordedCommand` et les contrôles techniques de validité de l'évidence AuthN, mais
avant décodage/dispatch et avant toute lecture ou mutation Pot :

```text
reload RecordedCommand(E,B,...)
  -> CurrentBindingResolutionPort.resolveCurrent(E,B)
     -> SELECT primary User/Identity
     -> Optional<PocomaUserId> seulement
  -> empty: REJECTED(CALLER_IDENTITY_NOT_CURRENT)
  -> U: construire le contexte d'exécution
  -> traduire/évaluer les capabilities
  -> decode + dispatch
  -> policies sur état Pot primaire courant
```

Le port doit exprimer une égalité conjointe et ne pas exposer au caller la raison de l'échec :

```text
WHERE issuer = ? AND subject = ? AND binding_id = ?
```

Cette exécution se trouve déjà sous la transaction fenced de
`TransactionalExecuteConsumptionUseCase`. Une résolution staleness-prone avant acquire ou dans la
discovery serait incorrecte. La valeur U résolue est ensuite adaptée en `domain-pot.UserId` comme
aujourd'hui ; ni `CommandDispatcher` ni les dix use cases Pot n'ont besoin de connaître E ou B.

Un simple SELECT non verrouillant laisserait toutefois un TOCTOU avec Detach. La résolution worker
et les transitions Attach/Detach devront partager une sérialisation PostgreSQL de l'occurrence
(verrou de row ou primitive conditionnelle équivalente) jusqu'au commit fenced. Le choix SQL exact
relève du plan ; l'invariant est que Detach ne puisse pas rendre B obsolète entre sa validation et
le commit métier de la Command.

### 6.4 Outcomes et `COMMAND_RESULT`

Le motif de mismatch produit un `CommandOutcome.Rejected` normal, le terminal Event
`COMMAND_REJECTED`, puis une projection `COMMAND_RESULT`; aucun outcome spécialisé n'est requis.
Le public code doit être l'unique code non-oracle, conceptuellement
`CALLER_IDENTITY_NOT_CURRENT`.

La projection ne pourra plus charger `submittedByUserId` depuis `auth_user_id`. Le contrat CCR doit
être révisé pour porter l'owner externe attesté de la Command, ou une clé READ dérivée non ambiguë,
afin que le GET compare le caller sans lookup primaire. Le résultat ne doit publier ni U résolu en
échec, ni B courant, ni historique de binding. La question générale de visibilité après detach ne
bloque pas WA1–WA10 : le minimum sûr est l'égalité avec l'ExternalIdentity créatrice capturée.

## 7. Impact User/Identity et BindingId

### 7.1 Ownership et type

`BindingId` appartient à `domain-user-identity`, aux côtés de `ExternalIdentity` et
`PocomaUserId`. Le type concret cohérent est un value object opaque autour de `UUID` : tous les IDs
durables voisins (`CommandId`, request IDs, User IDs, Event IDs) utilisent déjà UUID et seule
l'égalité est requise. La stratégie précise de génération peut rester derrière un port ; elle ne
doit être ni ordinale ni dérivée de E ou U.

Le modèle D31/D32 devient :

```text
CurrentExternalIdentityBinding
  ExternalIdentity
  PocomaUserId
  BindingId
```

L'ExternalIdentity reste la clé naturelle d'unicité du binding courant. `BindingId` identifie
l'occurrence. Un detach retire/invalide l'occurrence ; tout attach ultérieur crée un autre UUID,
même pour le même couple E/U. L'absence d'historique est compatible avec la non-réutilisation :
celle-ci est un invariant de génération, pas une obligation de conserver les rows supprimées.

### 7.2 Impact sur D31/D32

- D31 reste valide sur l'ownership, mais doit ajouter `BindingId` et le port de résolution exacte ;
- D32 reste valide sur la séparation User / autorité de binding, mais sa représentation doit inclure
  l'identité d'occurrence ;
- la phrase du plan §5.1 « la table n'ajoute ni binding id » est directement incompatible avec WA6 ;
- `CurrentExternalIdentityBindingQueryPort.findUserId(E)` devient insuffisant pour Command. Un port
  READ self-service et un port primaire worker `(E,B)` doivent rester distincts ;
- `InitialUserBindingAcquisitionPort` doit générer et retourner B avec le succès ;
- les futurs ports Attach retournent le nouveau B ; Detach doit cibler/invalider l'occurrence
  attendue afin qu'une demande stale ne détache pas un binding plus récent.

## 8. Impact AuthZ

Le worker peut raisonnablement garantir l'ordre demandé sans contradiction de module :

```text
(E,B) durable
  -> engine-user-identity port primaire
  -> U courant exact
  -> contexte d'exécution Command(U, capabilities attestées)
  -> chargements Pot primaires courants
  -> PotAuthorizationGuard / AuthorizationKernel
  -> mutation ou rejet
```

Le kernel est déjà pur et le canon §15 prévoit le write side à partir des relations courantes du
Pot. Les dix adapters Pot reçoivent actuellement un `AuthorizationSnapshot` déjà enrichi ; leur
signature ou le contexte transmis devra évoluer, mais les policies métier et leurs lectures
autoritatives restent au traitement.

Le seul couplage structurel à casser est l'exigence de `PocomaUserId` dans
`AuthorizationSnapshot`, `RecordedCommand`, `CommandDispatcher` et les factories/tests associés.
C'est une migration importante, pas un blocker architectural. Consumption n'exige pas U dans sa
clé : `COMMAND/[commandId]` suffit déjà jusqu'au reload.

## 9. READ self-service du binding courant

### 9.1 Évaluation de l'existant

| Candidat | Verdict | Motif |
|---|---|---|
| `AUTH(Pot,V)` | non | projection historique par Pot/version, relations creator/member ; User/Identity et B en sont explicitement exclus |
| `EUTH` | non | aucun concept, module, table ou document correspondant au HEAD |
| projection User/Identity existante | non | aucune n'existe |
| nouvelle projection minimale | oui | seule option naturelle respectant self-service, READ eventual et zéro primaire |

La projection recommandée peut être nommée ultérieurement, par exemple
`CURRENT_EXTERNAL_IDENTITY_BINDING`. Sa clé de lookup est dérivée de E authentifiée ; l'API ne doit
jamais accepter issuer/subject arbitraires en path ou body.

Elle expose au plus :

```text
ExternalIdentity E -> PocomaUserId U + BindingId B
```

Elle est alimentée par les faits User/Identity et supprimée/invalidée par un futur fait de detach.
La cohérence éventuelle est acceptable : un ancien B lu juste avant un detach peut conduire à une
Command durablement admise puis rejetée au worker, ce qui est exactement le rôle de WA5/WA8.

Cette projection remplace aussi à terme les résolutions primaires effectuées par les controllers
READ actuels. Elle ne doit jamais servir au worker Command : l'autorité primaire reste obligatoire
pour la décision `(E,B) -> U`.

## 10. Impact REGISTRATION

### 10.1 Canon à réviser avant implémentation

| Décision/section | Évolution requise |
|---|---|
| Purpose et diagramme | `Registered(U,B)` ; succès crée `Binding(E,U,B)` |
| §2.3 / D3, D10 | binding courant enrichi de B ; chaque nouvelle occurrence reçoit un B neuf |
| §4 / D13 | issue succès `Registered(PocomaUserId, BindingId)` |
| §4.1 / D19 | visibilité du succès exige l'occurrence exacte `E,U,B` courante, pas seulement E→U |
| §5.1 / D9, D15 | génération/acquisition de B dans la même transition atomique User + binding + outcome + faits |
| §5.3 / D24 | Attach crée B neuf ; Detach invalide l'occurrence ; reattach ne réutilise pas B |
| §6 / D33 | `ExternalIdentityAttached(E,U,B)` |
| §7 / D31–D32 | ownership de `BindingId`, autorité du binding occurrence et ports associés |
| table D1–D33 | mettre à jour au minimum D13, D19, D31, D32 et D33 ; D30 garde l'égalité exacte de E et s'étend aux comparaisons utilisant E |

Le rejet `EXTERNAL_IDENTITY_ALREADY_USED`, l'ouverture de l'admission Registration, l'atomicité,
l'absence de get-or-create et la séparation Consumption restent inchangés.

### 10.2 Plan à réécrire avant reprise

Le plan actuel n'est plus exécutable tel quel. Impacts précis :

- §3.2, §4.2, §15 et §17 imposent aujourd'hui que Command continue sa résolution synchrone : à
  inverser selon WA1–WA3 ;
- §5.1 interdit explicitement un binding id : à remplacer ;
- §5.2 : ports d'acquisition/résolution doivent retourner ou comparer B ;
- §5.3 : le backfill doit générer un B distinct pour chaque binding courant existant ;
- §7 : `Registered` et `registration_outcomes` ajoutent `binding_id` ; cette valeur historique ne
  doit pas avoir de FK destructive vers le binding courant ;
- §8 : la transition génère B avec U, insère `(E,U,B)`, puis écrit `Registered(U,B)` ;
- §8.3 : Attach/Detach/reattach suivent WA10 ;
- §9 : payload `ExternalIdentityAttached` ajoute B ;
- §12 : visibilité du succès compare `(E,U,B)` courant ;
- lots REGISTRATION.1/.2/.4/.6/.7, matrices §14, risques §15, surfaces §16 et invariants §17 doivent
  ajouter les preuves BindingId ;
- ajouter un lot ou une séquence transversale Command pour migrer admission, table, worker,
  outcomes/projection et tests avant d'activer les nouvelles admissions ;
- ajouter la projection self-service User/Identity, sans la confondre avec le GET outcome
  Registration direct actuellement planifié.

## 11. Impact Events

`ExternalIdentityAttached` doit porter `BindingId` : **oui**.

Justifications :

1. le fait décrit désormais la création d'une occurrence, pas seulement l'existence du couple E/U ;
2. la projection self-service doit publier exactement B sans relire le primaire ;
3. attach, detach et reattach vers le même User doivent rester distinguables ;
4. un consumer doit pouvoir ignorer/invalider l'occurrence exacte sans révéler l'historique au
   caller ;
5. D33 exige le même fait pour Registration et le futur Attach, donc omettre B créerait deux
   sémantiques ou forcerait une lecture primaire du consumer.

La forme cible est conceptuellement :

```text
ExternalIdentityAttached(ExternalIdentity E, PocomaUserId U, BindingId B)
```

Le futur fait de detach devrait lui aussi identifier B afin qu'un event stale ne supprime pas une
occurrence plus récente. Consumers prévisibles : projection self-service du binding courant,
projection/index administratif futur, audit/sécurité et éventuels workflows Attach/Detach. Le
worker Command ne consomme pas ce fait pour autoriser : il lit l'autorité primaire.

`UserCreated(U)` reste inchangé. `Registered(U,B)` est un outcome Registration, distinct des deux
faits User/Identity.

## 12. Impacts SQL et migrations conceptuelles

Aucune migration n'est écrite par cet audit. Le futur plan devra couvrir :

1. `users`, backfill et FK déjà prévus par REGISTRATION, en tenant compte du séquencement Command ;
2. ajout de `binding_id uuid not null` à `external_identities`, avec unicité ;
3. backfill d'un UUID opaque différent pour chaque row courante existante ;
4. modification des repositories et contraintes pour `(issuer, subject, binding_id) -> U` ;
5. évolution `recorded_commands` : ajout `auth_subject` et `binding_id`, suppression ou
   dépréciation/backfill de `auth_user_id`, changement de la représentation des permissions ;
6. stratégie de compatibilité des Commands historiques déjà enregistrées. Elles n'ont ni subject ni
   B : elles doivent être drainées avant contrainte stricte, migrées avec une règle explicitement
   sûre, ou conservées dans une version d'envelope legacy ; aucune valeur ne peut être inventée sans
   preuve ;
7. ajout de `binding_id` au succès `registration_outcomes` ;
8. ajout de B au payload/version de `user_identity_event_outbox` ;
9. store READ de la projection de binding courant et mécanisme d'upsert/delete par occurrence ;
10. évolution du loader/schema `COMMAND_RESULT` pour remplacer `submittedByUserId` comme clé
    d'ownership ;
11. indexes de résolution exacte et de lookup READ, sans faire de B une version ordinale.

Une contrainte unique sur les bindings actifs ne peut, après suppression, prouver à elle seule la
non-réutilisation historique. Avec un UUID nouvellement généré à chaque attach, aucun historique
supplémentaire n'est requis par WA10 ; la non-réutilisation est un contrat du générateur.

## 13. Impacts tests et nouvelles preuves

### 13.1 Tests existants à faire évoluer

- `SubmitRecordedCommandServiceTest.resolvesRecordsAndReturnsTheServerGeneratedIdentityInsideOneTransaction` :
  ne doit plus résoudre U ; vérifie E+B capturés ;
- `SubmitRecordedCommandServiceTest.unknownIdentityCreatesNoCommand` : remplacé par identité inconnue
  + B valide admise et durable ;
- `CommandAdmissionPostgresTest.authenticatedProvisionedIdentityDurablyAcceptsWithoutAnySynchronousEffects` :
  ne seed plus le binding et vérifie absence de SELECT primaire par architecture/spy dédié ;
- `CommandAdmissionPostgresTest.rejectsMissingAuthenticationAndUnprovisionedIdentitiesWithoutRecording` :
  conserve 401, remplace le 403 inconnu par 202 ;
- `AsyncCommandControllerTest` : ajoute B obligatoire, absent/invalide → rejet structurel ;
- `JpaRecordedCommandAdapterPostgresTest` et `RecordedCommandRecordMapperTest` : round-trip E+B et
  nouvelle évidence AuthN, disparition du sens actuel de `auth_user_id` ;
- `CommandModelTest` et `AuthorizationSnapshotFactoryTest` : séparent AuthN durable et contexte
  d'exécution résolu ;
- `ExecuteRecordedCommandServiceTest.expiredAuthorizationRejectsBeforeDecodeDispatchAndAppend` et
  tests dispatch : ajoutent résolution `(E,B)` avant dispatch ;
- `CommandConsumptionPostgresTest`, `CommandConsumptionRuntimePostgresTest` et
  `CommandConsumptionMultiWorkerPostgresTest` : seedent l'autorité binding, prouvent résolution dans
  la transaction fenced et motif unique ;
- `PotCommandUseCaseAdapterTest` : conserve les preuves de policies avec U résolu au worker ;
- `CommandResultProjectionChainPostgresTest`, `CommandCompletionE2EPostgresTest`,
  `CommandResultTest` et `CommandResultControllerTest` : ownership résultat sans `auth_user_id` ni
  lookup primaire ;
- `PrimaryMigrationsPostgresTest`, tests de schéma/démolition, fixtures et SQL E2E : nouvelles
  colonnes, contraintes et backfills ;
- `WriteSideClosurePostgresTest` et `WriteSideHttpClosureTest` : le seul WRITE reste asynchrone et
  n'effectue aucune lecture primaire ;
- tests Security : AuthN inchangée, B ne vient jamais du JWT et une identité valide inconnue passe
  l'admission technique.

### 13.2 Nouvelles preuves indispensables

| Invariant | Preuve attendue |
|---|---|
| zéro lecture primaire au POST | test HTTP PostgreSQL + instrumentation/guard interdisant le resolver primaire dans l'orchestrateur d'admission |
| B absent/invalide | HTTP 4xx structurel, aucune row Command |
| B valide faux/ancien/inexistant | HTTP 202 puis exactement `REJECTED/CALLER_IDENTITY_NOT_CURRENT` |
| E sans binding | même réponse asynchrone et même motif |
| E rattachée à autre U | même motif, aucun U/B divulgué |
| detach avant traitement | rejet unique |
| reattach même U avec B2 | Command(B1) rejetée, Command(B2) évaluée normalement |
| reattach autre U | même propriété, sans oracle |
| résolution atomique | `(E,B)->U` relu dans la transaction fenced avant toute mutation Pot |
| résolution contre Detach concurrent | verrouillage/sérialisation réelle : la Command voit B courant ou rejette, sans fenêtre TOCTOU |
| AuthZ au worker | capacités traduites/évaluées au traitement et relations Pot courantes chargées |
| Registration succès | User + binding B + `Registered(U,B)` + deux faits dont Attached(E,U,B), un commit |
| Attach | chaque succès produit un B neuf, y compris même E/U après detach |
| READ self-service | E vient du JWT ; aucun paramètre E arbitraire ; aucune query primaire ; cohérence éventuelle admise |
| events/projection | attach B1, detach B1, attach B2 converge vers B2 ; event stale ne retire pas B2 |
| migrations | backfill B unique, données historiques préservées, stratégie explicite pour Commands legacy |
| architecture | User/Identity possède B ; Command/HTTP ne possède ni resolver synchrone ni copie de type |

## 14. Décisions déjà clôturables

Les éléments suivants sont suffisamment déterminés par WA1–WA10 et le repository :

1. l'invariant zéro lecture primaire s'applique transversalement à toute admission HTTP WRITE ;
2. le seul contre-exemple actuel est `POST /api/v1/commands` ;
3. l'admission Command doit capturer E+B et accepter une E inconnue si la forme est valide ;
4. `BindingId` appartient au domaine User/Identity et un `UUID` opaque est le type concret naturel ;
5. la résolution exacte doit avoir lieu dans l'exécution Command fenced, avant dispatch ;
6. le mismatch produit un seul rejet fonctionnel non-oracle ;
7. `AUTH(Pot,V)` ne doit pas porter le binding courant ; une projection User/Identity minimale est
   requise pour WA9 ;
8. Registration produit `Registered(U,B)` et `ExternalIdentityAttached(E,U,B)` ;
9. attach génère toujours un B neuf ; detach invalide l'occurrence courante ;
10. Consumption, fencing et les policies Pot restent réutilisables ;
11. le canon et le plan REGISTRATION doivent être révisés avant toute implémentation.

## 15. Questions réellement encore ouvertes

Aucune question métier ou architecturale n'empêche de fermer le cadrage WA1–WA10.

Restent des choix d'implémentation à traiter par le futur plan, sans rouvrir ce cadrage : nom exact
des types/ports/projection, méthode UUID derrière le générateur, représentation durable minimale des
capabilities attestées, stratégie de migration des Commands historiques et découpage des migrations.

## 16. Verdict

**CLOSABLE**

WA1–WA10 sont compatibles avec l'architecture Pocoma. Le moteur Consumption, la transaction fenced,
le reload autoritatif, les policies d'autorisation et le store de projections fournissent les points
d'extension nécessaires. Aucun canon de Consumption, Event, Projection ou AUTH ne les contredit.

L'écart est néanmoins transversal et doit être traité avant REGISTRATION : l'admission Command
actuelle lit le primaire, `RecordedCommand` fige U et les permissions trop tôt, le binding n'a pas
d'identité d'occurrence, `COMMAND_RESULT` dépend de `auth_user_id`, et le plan REGISTRATION interdit
explicitement `BindingId`. Ces écarts sont des migrations importantes, pas des blockers.

La poursuite correcte est donc : réviser d'abord les canons concernés et `REGISTRATION/Step_Plan.md`
sur la base de cet audit, planifier la migration Command/User-Identity/READ/Events, puis seulement
reprendre l'implémentation REGISTRATION.
