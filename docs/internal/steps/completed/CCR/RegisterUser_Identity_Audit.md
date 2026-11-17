# Audit — identité appelante et pré-cadrage `RegisterUser`

Date de l'audit : 2026-09-30

Périmètre : repository local `/Users/julien.guezennec/Dev/projects/pocoma`, hors répertoire `graft/` qui est une copie de travail distincte.

Nature : audit factuel uniquement ; aucune implémentation n'a été réalisée.

## Résumé exécutif

La chaîne cible existe déjà pour les appels WRITE et READ d'une identité **déjà provisionnée** : Spring Security valide un JWT Bearer, Pocoma adapte les claims en `AuthenticatedExternalPrincipal`, résout `(issuer, subject)` vers un `PocomaUserId` grâce à `external_identities`, puis transmet ce `userId` à la chaîne métier.

En revanche, le bootstrap d'une identité inconnue n'existe pas. En WRITE, une identité non provisionnée est rejetée en HTTP 403 avant toute création de `RecordedCommand`. En READ, elle est masquée derrière un HTTP 404. Il n'existe ni modèle métier `User`, ni table canonique des Users, ni use case de création/rattachement/détachement d'identité, ni port d'écriture pour `external_identities`.

Enfin, le résultat réussi actuel d'une Command est fermé sur le couple `potId + resultingVersion`. `COMMAND_RESULT` ne sait pas transporter un résultat métier générique tel qu'un `userId` créé ; utiliser `potId` à cette fin détournerait sa sémantique.

## A. Baseline repository

- Branche : `v2-make-it-pull`.
- HEAD local : `79f394fc3c50df317b3a3ec9f88ca28e5ffdae20` (`test worker lifecycle and dynamic web port`).
- HEAD distant vérifié sur `refs/heads/v2-make-it-pull` : `79f394fc3c50df317b3a3ec9f88ca28e5ffdae20`.
- Divergence avec `origin/v2-make-it-pull` : `0` commit derrière, `0` commit devant.
- Working tree avant création de ce rapport : aucun fichier suivi modifié ou indexé ; un fichier non suivi préexistant, `docs/operations/cmd-start-runtimes.md`.
- La création du présent rapport ajoute naturellement un second fichier non suivi tant qu'il n'est pas ajouté à Git.

## B. AuthN actuelle

### Présent, câblé et utilisé

Le module `app/supra-authentication-spring-security` dépend de `spring-boot-starter-oauth2-resource-server` (`pom.xml`, lignes 20-23). Cette dépendance est réellement câblée :

- `WebApiSecurityConfiguration.webApiSecurityFilterChain` (`WebApiSecurityConfiguration.java`, lignes 22-38) installe une `SecurityFilterChain` stateless, désactive CSRF, exige une authentification pour :
  - `POST /api/v1/commands` ;
  - `/api/v1/command-results/**` ;
  - `/api/v1/pots/**` ;
- la même méthode appelle `oauth2ResourceServer(...jwt(...))` (ligne 33) : le header `Authorization: Bearer ...` est donc traité par le Resource Server JWT Spring Security ;
- la configuration est active dès qu'au moins l'un des trois runtimes HTTP est activé (lignes 23-24) ; les trois flags valent `true` dans `app/runtime-web-api/src/main/resources/application.properties`, lignes 6-8 ;
- `runtime-web-api` dépend effectivement du module d'authentification (`app/runtime-web-api/pom.xml`, lignes 21-25).

La validation du Bearer token n'est pas seulement suggérée par la dépendance : le filtre Resource Server est installé dans la chaîne. Le test `CommandAdmissionResourceServerTest` exerce une vraie paire de clés RSA et un `NimbusJwtDecoder` : token valide accepté (lignes 61-67), signature absente/invalide rejetée (69-79), issuer/audience invalides rejetés (82-91), expiration et `nbf` futur rejetés (94-103).

En environnement réel, la confiance dans l'issuer reste une configuration de déploiement :

- `application.properties` configure l'audience attendue à `${POCOMA_OIDC_AUDIENCE:pocoma-api}` (ligne 11) ;
- aucun `issuer-uri` ou `jwk-set-uri` n'est figé dans l'application ;
- le document d'exploitation non suivi `docs/operations/cmd-start-runtimes.md`, lignes 2-7, montre un lancement avec `spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/pocoma`.

Il n'existe pas de dépendance ni d'adapter Keycloak dans le code applicatif. Keycloak n'est qu'un issuer OIDC/JWT possible dans l'exemple de lancement ; l'implémentation est provider-neutral.

### Claims exploitées

`SpringSecurityExternalPrincipalAdapter.adapt` (`app/supra-authentication-spring-security/.../SpringSecurityExternalPrincipalAdapter.java`, lignes 19-35) extrait et exige :

- `iss` → `issuer` ;
- `sub` → `subject` ;
- `iat` → `issuedAt` ;
- `exp` → `expiresAt` ;
- `auth_time` → `authenticatedAt`.

Il contrôle également `auth_time <= iat < exp` (lignes 27-32). Les claims `scope` et `scp`, sous forme de chaîne ou collection, deviennent `externalAuthorities` (lignes 49-63). `ExternalAuthorityPermissionTranslator` ne conserve que la forme `pocoma:<resource>:<action>` et la transforme en `Permission` interne en majuscules (`ExternalAuthorityPermissionTranslator.java`, lignes 12-35).

La validation standard Spring utilise en plus les éléments cryptographiques et temporels du JWT ainsi que l'issuer/audience configurés. Ces derniers servent à valider le token ; ils ne sont pas tous exposés comme données métier.

### Représentation de l'identité appelante

- À la frontière Spring : `JwtAuthenticationToken`.
- Adapter : `SpringSecurityExternalPrincipalAdapter`.
- Injection MVC : `AuthenticatedExternalPrincipalArgumentResolver`, qui refuse tout principal qui n'est pas un `JwtAuthenticationToken` (`AuthenticatedExternalPrincipalArgumentResolver.java`, lignes 20-35).
- Contrat provider-neutral : `AuthenticatedExternalPrincipal(issuer, subject, authenticatedAt, issuedAt, expiresAt, externalAuthorities)` (`orchestrator-command-admission/.../AuthenticatedExternalPrincipal.java`, lignes 8-29).
- Clé externe minimale : `ExternalIdentity(issuer, subject)` (`ExternalIdentity.java`, lignes 5-17).

`issuer` et `subject` sont donc tous deux disponibles au point d'entrée HTTP.

## C. Résolution identité externe → User Pocoma

### Modèle réellement présent

La résolution existe et est utilisée au runtime :

1. `ExternalIdentityResolverPort.findUserId(ExternalIdentity)` retourne `Optional<PocomaUserId>` (`ExternalIdentityResolverPort.java`, lignes 8-10).
2. `JpaExternalIdentityResolverAdapter` est un `@Component`, exige une transaction et délègue au repository (`JpaExternalIdentityResolverAdapter.java`, lignes 16-29).
3. `ExternalIdentityJdbcRepository.findUserId(issuer, subject)` exécute un `SELECT pocoma_user_id FROM external_identities WHERE issuer=? AND subject=?` (`ExternalIdentityJdbcRepository.java`, lignes 9-27).
4. `PocomaUserId` est un wrapper provider-neutral d'un UUID (`engine-command/.../PocomaUserId.java`, lignes 7-12).

La table `external_identities` est créée par `V9__external_identities.sql` :

- colonnes `issuer`, `subject`, `pocoma_user_id` non nulles ;
- clé primaire `(issuer, subject)` : une identité externe donnée ne peut pointer que vers un seul User ;
- aucune contrainte unique sur `pocoma_user_id` : plusieurs identités externes peuvent pointer vers le même User ;
- aucun foreign key sur `pocoma_user_id`.

Cette cardinalité multiple est explicitement testée par `JpaRecordedCommandAdapterPostgresTest.permitsSeveralExternalIdentitiesForOneUserButKeepsEachExternalKeyUnique` (lignes 108-123).

### Ce qui n'existe pas

- Aucun agrégat ou record métier `User`.
- Aucune table canonique `users`/`pocoma_users`.
- Aucun `UserRepository` ou port de persistance de User.
- Aucun use case `CreateUser`, `RegisterUser`, rattachement ou détachement d'identité.
- Aucun port ou repository de production pour écrire dans `external_identities` ; le repository exposé est en lecture seule. Les insertions trouvées sont dans les tests/fixtures.

Il existe deux types d'identifiant selon les couches, `PocomaUserId` dans le moteur de Command et `domain-pot.value.UserId` dans le domaine Pot, mais pas de modèle de cycle de vie du User. Le `userId` est également porté par les relations Pot (créateur et actionnaires), sans que cela constitue un agrégat User.

## D. WRITE

### Chaîne représentative : soumission puis exécution d'une création de Pot

```text
Authorization: Bearer JWT
  ↓ Spring Security Resource Server valide le JWT
JwtAuthenticationToken
  ↓ AuthenticatedExternalPrincipalArgumentResolver
AuthenticatedExternalPrincipal(iss, sub, temps, scopes)
  ↓ AsyncCommandController.submit
SubmitRecordedCommandService.submit
  ↓ ExternalIdentityResolverPort.findUserId(iss, sub)
PocomaUserId
  ↓ AuthorizationSnapshotFactory
AuthorizationSnapshot(userId, permissions, temps, issuer)
  ↓ RecordedCommandPort.insert
recorded_commands.auth_user_id + auth_issuer + temps + permissions
  ↓ worker / ExecuteRecordedCommandService
CommandDispatcher
  ↓ AbstractPotCommandUseCaseAdapter
UserContext(domain UserId, permissions)
  ↓ CreatePotService / domaine Pot
```

Références précises :

- HTTP : `AsyncCommandController.submit`, `app/supra-http-write-command/.../AsyncCommandController.java`, lignes 39-55.
- Résolution : `SubmitRecordedCommandService.submit`, lignes 39-55, notamment 43-45.
- Snapshot et persistance : `AuthorizationSnapshotFactory.create`, lignes 23-41 ; `RecordedCommand`, lignes 7-21 ; `JpaRecordedCommandAdapter.insert`, lignes 29-42.
- Exécution asynchrone : `ExecuteRecordedCommandService.execute`, lignes 43-77.
- Adaptation vers le domaine : `AbstractPotCommandUseCaseAdapter.executeAdapted`, lignes 24-40.
- Autorisation/création représentative : `CreatePotCommandUseCaseAdapter.execute`, lignes 31-40, puis `CreatePotService.createPot`, lignes 47-86.

**Réponse : oui**, le code actuel détermine le `userId` Pocoma du submitter à partir de son Bearer token, à condition que `(issuer, subject)` soit déjà provisionné dans `external_identities`.

L'identité est conservée comme suit :

- `(issuer, subject)` n'existe que dans le principal HTTP et pendant la résolution ;
- `subject` n'est pas persisté dans la Command ;
- `AuthorizationSnapshot` conserve `userId`, permissions, `authenticatedAt`, `issuedAt`, `validUntil` et `issuer` ;
- `recorded_commands` persiste ces mêmes données dans `auth_user_id`, `auth_issuer`, les dates et `auth_permissions_json` (`V8__recorded_commands.sql`, lignes 1-16) ;
- au moment du dispatch métier, le snapshot devient `UserContext(UserId, permissions)` : le domaine reçoit le User interne et les permissions, pas l'identité OIDC.

Une Command persistée contient donc bien une notion d'acteur : `RecordedCommand.authorization.userId`, persistée sous `auth_user_id`. Elle ne contient pas de champ nommé `actor`/`caller`, ni le `subject` externe.

## E. READ

### Chaîne représentative : lecture d'un Pot à une version exacte

```text
Authorization: Bearer JWT
  ↓ Spring Security Resource Server valide le JWT                 [AuthN]
AuthenticatedExternalPrincipal(iss, sub, scopes)
  ↓ PotQueryController + ExternalIdentityResolverPort
Pocoma UserId                                                     [résolution]
  ↓ traduction scopes → TokenCapabilities
ReadPotService.read(userId, capabilities, potId, version)
  ↓ contrôle POT_VIEW puis lecture projection AUTH à la version V
AUTH {creator, shareholder→user relations}
  ↓ AuthorizationKernel.decide(... VIEW_POT)                      [autorisation objet]
projection READ_POT à la version V
  ↓
PotResponse
```

Références :

- `PotQueryController.get`, `app/supra-http-read-query/.../PotQueryController.java`, lignes 50-65 : résolution de l'identité lignes 56-59.
- `ReadPotUseCase.read(UserId, TokenCapabilities, PotId, long)`, lignes 7-8.
- `ReadPotService.read`, lignes 39-82 : capacité `POT_VIEW` (48), lecture de `AUTH` (50-62), décision sur l'objet (63-68), puis seulement lecture de `READ_POT` (70-82).
- `AuthProjectionDefinition` définit une projection `AUTH` ciblant un `POT`, avec un créateur unique et zéro à N associations actionnaire→User (`domain-pot-projection/.../AuthProjectionDefinition.java`, lignes 12-20).
- `AuthProjectionInterpreter` reconstruit `PotAuthorizationRelations` à partir de ces artifacts (`engine-pot-read/.../AuthProjectionInterpreter.java`, lignes 18-52).

**Réponse : oui**, le code actuel détermine le `userId` Pocoma du lecteur à partir du Bearer token, si son identité externe est provisionnée.

Les responsabilités sont distinctes :

- **authentification** : Spring Security valide le JWT et produit le principal ;
- **résolution** : le controller transforme `(issuer, subject)` en `UserId` Pocoma ;
- **autorisation** : `ReadPotService` combine les capacités courantes du token avec les relations historiques de la projection `AUTH` à la version demandée, puis le kernel décide si `VIEW_POT` est permis.

La lecture de résultat de Command suit la même AuthN/résolution. `CommandResultController.get` résout le User (lignes 42-48), puis `GetCommandResultService` ne rend la projection visible que si `submittedByUserId == requestingUserId` (lignes 40-47) ; sinon il retourne volontairement `NotFound`.

## F. Identité externe inconnue

Cas : JWT valide, `(issuer, subject)` valide, aucune ligne correspondante dans `external_identities`.

- **WRITE `/api/v1/commands`** : `SubmitRecordedCommandService` lève `UserNotProvisionedException` avant génération/persistance de la Command. `WebApiExceptionHandler.unprovisioned` la transforme en HTTP **403** avec le code `USER_NOT_PROVISIONED` (`WebApiExceptionHandler.java`, lignes 33-36). `CommandAdmissionPostgresTest.rejectsMissingAuthenticationAndUnprovisionedIdentitiesWithoutRecording` vérifie le 403 et l'absence de ligne `recorded_commands` (lignes 115-127).
- **READ Pot** : `PotQueryController` retourne HTTP **404** si la résolution est vide (lignes 56-57), afin de masquer l'existence éventuelle de la ressource.
- **READ Command result** : `CommandResultController` retourne également HTTP **404** (lignes 46-47).

Il n'y a ni création automatique, ni placeholder, ni `CreateUser`, ni Command produite dans ce cas. Le traitement s'arrête à l'admission HTTP/application.

## G. `COMMAND_RESULT`

### Données actuelles

Le résultat fonctionnel durable est `CommandOutcome`, interface scellée (`engine-command/.../CommandOutcome.java`) :

- `Applied(commandId, potId, resultingVersion, resolvedAt)` ;
- `Rejected(commandId, rejectionCode, resolvedAt)` ;
- `Failed(commandId, publicFailureCode, resolvedAt)` ; le code public est obligatoirement `COMMAND_PROCESSING_FAILED`.

Le stockage `command_outcomes` reflète exactement cette forme : `command_id`, `outcome_type`, `pot_id`, `resulting_version`, `public_code`, `resolved_at`. Les contraintes SQL de `V17__command_outcomes_and_terminal_events.sql`, lignes 11-21, ferment la forme `APPLIED` sur `pot_id != null`, `resulting_version >= 1`, `public_code = null`.

La projection `COMMAND_RESULT` ajoute `submittedByUserId` pour le contrôle de visibilité. Son payload comporte exactement :

- `commandId` ;
- `submittedByUserId` ;
- `outcome` (`APPLIED`, `REJECTED`, `FAILED`) ;
- `potId` ;
- `resultingVersion` ;
- `code` ;
- `resolvedAt`.

La définition JSON interdit les propriétés supplémentaires (`CommandResultProjectionDefinition.java`, lignes 20-49, notamment ligne 46). `CommandResultProjector` ne sait projeter que les trois variantes ci-dessus (lignes 18-52). La réponse HTTP est également fermée sur `commandId, status, potId, resultingVersion, code, resolvedAt` (`CommandResultResponse.java`, lignes 6-7).

Il n'existe pas de champ public nommé `target`. Le target technique de la projection est la Command elle-même : type d'objet `COMMAND`, id = `commandId`, version fixe `1` (`GetCommandResultService.java`, lignes 32-35). Le résultat métier réussi, lui, désigne explicitement un Pot par `potId` et sa `resultingVersion`.

### Capacité à restituer un `userId`

**Non, pas dans le modèle actuel.** Il n'existe ni payload métier générique, ni variante de succès User, ni champ `userId`. La fermeture est volontaire et répétée à quatre niveaux : interface Java scellée, `CommandAppliedResult(potId, resultingVersion)`, contraintes SQL, schéma de projection avec `additionalProperties=false` et DTO HTTP fixe.

Réutiliser `potId` pour y placer un `userId` serait techniquement un UUID mais détournerait explicitement la sémantique actuelle et contaminerait les invariants de version Pot. Restituer proprement un `userId` nécessiterait de faire évoluer le contrat ; aucune telle capacité n'est déjà présente. Ceci est un constat de capacité, pas une proposition d'implémentation.

## H. Schéma de la chaîne réellement implémentée

### WRITE connu

```text
Bearer JWT
  ↓ validation Spring Security (signature/issuer/audience/temps selon configuration)
AuthenticatedExternalPrincipal {issuer, subject, times, scopes}
  ↓ SELECT external_identities(issuer, subject)
PocomaUserId
  ↓ AuthorizationSnapshot durable
RecordedCommand {auth_user_id, auth_issuer, times, permissions}
  ↓ worker Command
UserContext {UserId, permissions}
  ↓ autorisation + domaine Pot
CommandOutcome {potId, resultingVersion | code}
  ↓
COMMAND_RESULT
```

### READ Pot connu

```text
Bearer JWT
  ↓ validation Spring Security
AuthenticatedExternalPrincipal
  ↓ SELECT external_identities(issuer, subject)
Pocoma UserId + capacités du token
  ↓
AUTH(potId, version) → AuthorizationKernel
  ↓ si autorisé
READ_POT(potId, version)
```

### Identité inconnue

```text
JWT valide
  ↓
(issuer, subject) absent de external_identities
  ├─ WRITE → 403 USER_NOT_PROVISIONED, aucune Command
  └─ READ  → 404
```

### Bootstrap

```text
identité externe inconnue
  ↓
RegisterUser
  ↓
ABSENT DU REPOSITORY ACTUEL
```

## I. Écarts avec la chaîne cible

Pour `Bearer → identité externe → userId → READ/WRITE`, la chaîne est déjà complète uniquement pour les identités préprovisionnées. Les écarts restants vers le bootstrap envisagé sont :

1. aucun modèle canonique et aucun stockage de `User` interne ; seul l'UUID référencé existe ;
2. aucun générateur/port/use case de création de User ;
3. aucun port d'écriture ni use case atomique de création/rattachement/détachement pour `external_identities` ;
4. aucun endpoint `POST /users/register` ou équivalent ;
5. la `SecurityFilterChain` actuelle ne protège explicitement que `/api/v1/commands`, `/api/v1/command-results/**` et `/api/v1/pots/**` ; un futur chemin de registration n'est pas aujourd'hui dans les matchers authentifiés et tomberait sous `anyRequest().permitAll()` ;
6. l'admission générique actuelle exige la résolution du `userId` **avant** de construire `AuthorizationSnapshot` et `RecordedCommand` ; elle ne peut donc pas admettre telle quelle une Command bootstrap soumise par une identité inconnue ;
7. aucune Command `RegisterUser`/`CreateUser`, aucun decoder, handler domaine ou worker associé ;
8. aucun comportement d'idempotence/concurrence défini pour deux registrations du même `(issuer, subject)` ; la PK SQL empêcherait seulement un doublon brut ;
9. `CommandOutcome`/`COMMAND_RESULT` ne peut pas porter un `userId` créé ou résolu sans évolution de sa sémantique et de ses contrats fermés ;
10. aucune API ne permet aujourd'hui de retrouver son propre `userId` indépendamment d'une ressource Pot ou d'une Command déjà soumise.

## J. Questions de cadrage restantes

Questions que le code actuel ne permet pas d'arbitrer :

1. Qu'est-ce qu'un `User` Pocoma : simple UUID propriétaire, agrégat avec profil, état de cycle de vie, ou autre ?
2. La registration doit-elle créer un User à la première identité, ou seulement résoudre/rattacher une identité à un User créé ailleurs ?
3. En cas de registration répétée du même `(issuer, subject)`, faut-il retourner le même `userId` comme succès idempotent, rejeter, ou distinguer « créé » et « déjà existant » ?
4. Quelles règles permettent de rattacher plusieurs identités externes au même User, et comment éviter une prise de contrôle de compte ?
5. Quelles règles de détachement, révocation, changement d'issuer ou suppression de User sont attendues ?
6. Quels issuers, audiences, claims et capacités sont requis pour appeler la registration ? Une simple authentification valide suffit-elle ?
7. La registration doit-elle obligatoirement suivre le pipeline asynchrone générique, malgré l'absence initiale de `userId` nécessaire à `AuthorizationSnapshot`, ou possède-t-elle une sémantique bootstrap distincte ?
8. Le contrat de résultat de Command doit-il devenir générique/typé par famille de Command, ou ajouter une variante explicitement dédiée à la registration ?
9. Le `userId` doit-il être exposé directement dans `COMMAND_RESULT`, dans une ressource User consultable ensuite, ou dans les deux ?
10. Pour une identité valide mais inconnue, les autres READ doivent-ils continuer à répondre 404 et les autres WRITE 403 après introduction de la registration ?

Ces questions sont des décisions fonctionnelles ou architecturales ; aucun plan d'implémentation n'est proposé dans cet audit.
