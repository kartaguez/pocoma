# REGISTRATION — Plan d’implémentation

```text
Step: REGISTRATION
Phase: IMPLEMENTATION PLANNED
Baseline auditée: v2-make-it-pull @ 6c79b509f7f463a4e955aaa3e6a559fd6c279348
Verdict: IMPLEMENTATION READY
Lots: REGISTRATION.1 à REGISTRATION.7
```

La référence normative est [`Step_Canon.md`](Step_Canon.md). Les constats historiques de
[`step_audit.md`](step_audit.md) et [`domain_audit.md`](domain_audit.md) ont été revérifiés au HEAD
ci-dessus. Ce document choisit les représentations Java, Maven, SQL, runtime et HTTP nécessaires à
l’implémentation ; il ne modifie aucune décision D1–D33.

## 1. Baseline et périmètre de l’audit

Après `git fetch origin v2-make-it-pull` :

| Élément | Valeur |
|---|---|
| Branche | `v2-make-it-pull` |
| HEAD local | `6c79b509f7f463a4e955aaa3e6a559fd6c279348` |
| `origin/v2-make-it-pull` | `6c79b509f7f463a4e955aaa3e6a559fd6c279348` |
| Divergence | `0` derrière / `0` devant |
| Working tree initial | propre |

L’audit a couvert les sources, migrations, tests et documents d’architecture relatifs à User,
Identity, Command, Pot, READ, AUTH, admission HTTP, Consumption, outcomes, Events, projections et
runtimes. Aucun code n’est implémenté par le présent lot documentaire.

## 2. Lecture obligatoire du plan

Les termes suivants ont un statut différent :

- **Imposé par le canon** : invariant D1–D33, non négociable par un lot d’implémentation.
- **Constat repository** : état observé au HEAD audité.
- **Choix d’implémentation** : décision de ce plan, modifiable seulement par une révision explicite
  du plan qui reste compatible avec le canon.

Le plan ne contient aucun blocker. Les difficultés de migration et de concurrence sont absorbées
par les lots ci-dessous.

## 3. Constats du repository actuel

### 3.1 User et Identity

- `PocomaUserId` est un `record(UUID)` dans `engine-command`. Il est utilisé par
  `AuthorizationSnapshot`, l’admission Command, les adapters JDBC, READ et de nombreux tests.
- `domain-pot.value.UserId` est un type UUID local au domaine Pot. Les bindings Command → Pot
  adaptent déjà explicitement `PocomaUserId.value()` vers `UserId.of(...)`.
- `ExternalIdentity(issuer, subject)` et `AuthenticatedExternalPrincipal` appartiennent à
  `orchestrator-command-admission`. Le principal contient aussi les temps d’authentification et les
  autorités externes ; `identity()` construit l’identité opaque exacte.
- `ExternalIdentityResolverPort` retourne un `Optional<PocomaUserId>`.
  `JpaExternalIdentityResolverAdapter` et `ExternalIdentityJdbcRepository` lisent
  `external_identities`.
- `external_identities` est aujourd’hui exactement `(issuer, subject) -> pocoma_user_id`, avec PK
  composite et sans FK vers une autorité User. Il n’existe aucun writer de production ; les lignes
  sont créées par les fixtures.
- Il n’existe ni entité/table User autonome, ni attach/detach, ni port d’écriture du binding.

### 3.2 Admission et HTTP

- `SubmitRecordedCommandService` résout obligatoirement l’identité externe puis lève
  `UserNotProvisionedException` si aucun binding n’existe. Cette règle doit rester inchangée.
- `WebApiSecurityConfiguration` authentifie explicitement Commands, Command results et Pots, puis
  laisse les autres chemins sous `permitAll`; toute route Registration doit donc être ajoutée aux
  matchers authentifiés, sans élargissement global implicite.
- `AuthenticatedExternalPrincipalArgumentResolver` n’accepte qu’un `JwtAuthenticationToken` déjà
  validé par le Resource Server. Aucun composant métier ne reçoit le JWT brut.
- Les contrôleurs READ dépendent aujourd’hui nominalement de types possédés par Command admission.
  Ce couplage disparaîtra avec la fondation neutre prévue en REGISTRATION.1.

### 3.3 Consumption

- `ConsumptionKey` sépare `ConsumableIdentity` et `ConsumerIdentity`.
- Le slot est créé paresseusement à l’acquisition ; sa clé structurelle est unique.
- acquisition, claim, lease, takeover, retry, terminalisation et provenance sont génériques.
- `TransactionalExecuteConsumptionUseCase` exécute dans une transaction unique l’effet métier, la
  provenance et le CAS final sur `currentClaimId`. Un claim perdu provoque le rollback de l’effet.
- discovery et ordering restent propres à chaque famille ; Command utilise
  `(submitted_at, command_id)` et exclut les slots DONE, occupés ou non encore éligibles.
- `ConsumptionPollingWorker`, les budgets et l’orchestrateur séquentiel sont réutilisables. Plusieurs
  processus peuvent néanmoins travailler en parallèle grâce aux claims.
- Il n’existe pas de renouvellement de lease. Le traitement Registration doit donc rester court ;
  le fencing protège le commit si un takeover survient.

### 3.4 Outcomes, Events et projections

- `command_outcomes` garantit un outcome par Command. `command_terminal_events` est écrit dans la
  même transaction par `JdbcCommandOutcomeAdapter`.
- `business_event_outbox` et les `BusinessEvent` Pot sont spécialisés par `potId` et `version`.
  Cette table ne peut pas devenir l’outbox User/Identity sans faire dépendre ce domaine de Pot.
- `domain-event.BusinessEvent` fournit déjà un marqueur générique, mais `RecordedEvent` et
  `BusinessEventAppendPort` sont encore typés par le sous-type Pot. La généricité utile peut être
  extraite sans migrer l’outbox Pot.
- l’infrastructure Projection découvre explicitement l’union de `business_event_outbox` et
  `command_terminal_events`. Elle n’est pas une source automatique pour de nouveaux faits.
- `COMMAND_RESULT` utilise outcome → terminal Event → projection READ. Sa visibilité compare un
  UserId soumis, ce qui ne suffit pas pour D19 : une autre identité du même User serait confondue
  avec l’identité créatrice et un detach ne serait pas reflété automatiquement.

## 4. Architecture cible et ownership D31

### 4.1 Modules canoniques

Le découpage cible est le suivant :

| Module | Ownership cible | Dépendances autorisées principales |
|---|---|---|
| `domain-user-identity` | `User`, `PocomaUserId`, `ExternalIdentity`, `UserCreated`, `ExternalIdentityAttached`, types d’événements | JDK, `domain-event` |
| `engine-authentication` | contrat provider-neutral `AuthenticatedExternalPrincipal` et erreur de principal invalide | `domain-user-identity`, JDK |
| `engine-user-identity` | ports neutres de résolution, existence User, acquisition de binding initial et append des faits | `domain-user-identity`, `engine-core` si le contrat d’enveloppe enregistré l’exige |
| `engine-registration` | `RegistrationRequestId`, request, outcome, admission durable, transition Registration et lecture visible | `domain-user-identity`, `engine-user-identity`, `engine-core` |
| `orchestrator-registration-admission` | adaptation du principal authentifié vers l’admission Registration, sans résolution User | `engine-authentication`, `engine-registration` |
| `locator-consumption-registration` | clé, discovery, exécution, classification et policy Consumption Registration | `engine-registration`, Consumption générique |
| `supra-http-write-registration` | `POST /api/v1/registrations` | orchestrateur d’admission |
| `supra-http-read-registration` | `GET /api/v1/registrations/{requestId}` | lecture Registration, `engine-authentication` |
| `runtime-registration-consumption-worker` | composition worker Registration | locator, orchestrateur/supra Consumption, persistence |

`runtime-web-api`, `infra-persistence-jpa`, `supra-authentication-spring-security` et
`architecture-tests` sont étendus ; ils ne deviennent propriétaires d’aucun concept métier.

### 4.2 Migration des types

- `PocomaUserId` est déplacé vers `domain-user-identity`. Tous les imports de Command, admission,
  persistence, READ et tests basculent dans REGISTRATION.1, puis l’ancien type est supprimé dans le
  même lot. Il n’y a jamais deux types canoniques actifs au terme d’un commit.
- `ExternalIdentity` est déplacé de `orchestrator-command-admission` vers
  `domain-user-identity`, avec les mêmes validations et l’égalité exacte Java. L’ancien type est
  supprimé dans le même lot.
- `AuthenticatedExternalPrincipal` est déplacé vers `engine-authentication`; sa méthode
  `identity()` retourne le type canonique. L’adapter Spring, Command admission, READ et Registration
  dépendent alors d’un contrat neutre.
- `ExternalIdentityResolverPort` devient un port neutre de `engine-user-identity`, nommé
  `CurrentExternalIdentityBindingQueryPort` (ou nom équivalent explicite). Command admission
  continue de l’utiliser exactement avant création de `RecordedCommand`.

### 4.3 Décision sur `domain-pot.value.UserId`

`domain-pot.value.UserId` est **conservé comme référence locale typée du domaine Pot**. Il ne devient
pas une seconde identité canonique : l’autorité système est `PocomaUserId`; Pot conserve son nom de
vocabulaire et adapte explicitement la valeur UUID à sa frontière, comme aujourd’hui.

Cette décision évite une dépendance `domain-pot -> domain-user-identity`, un big-bang sur tous les
agrégats, événements et projections Pot, et un couplage entre deux domaines. Les adapters doivent
centraliser les conversions et les tests doivent prouver qu’elles sont sans perte. Aucun nouveau
`UserId` générique ne doit être créé ailleurs.

### 4.4 Graphe de dépendances

```text
domain-event
    ↑
domain-user-identity ← engine-authentication
    ↑                         ↑
engine-user-identity          │
    ↑                         │
engine-registration ← orchestrator-registration-admission
    ↑                         ↑
locator-consumption-registration   supras HTTP Registration
    ↑                         ↑
runtime-registration-consumption-worker / runtime-web-api

domain-pot.UserId reste local ; les frontières adaptent UUID ↔ UUID.
```

User/Identity ne dépend ni de Command, ni de Pot, ni de Registration, ni d’un runtime. Command et
Registration dépendent de l’autorité User/Identity ; aucune dépendance circulaire n’est admise.

## 5. Modèle persistant User et binding — D32

### 5.1 Schéma cible

La migration de REGISTRATION.2 introduit :

```sql
users (
  pocoma_user_id uuid primary key
)
```

`external_identities` est conservée comme autorité relationnelle compacte du binding courant :

```sql
external_identities (
  issuer text not null,
  subject text not null,
  pocoma_user_id uuid not null references users(pocoma_user_id),
  primary key (issuer, subject),
  check (btrim(issuer) <> ''),
  check (btrim(subject) <> '')
)
```

La FK utilise `ON DELETE RESTRICT` (comportement explicite ou défaut PostgreSQL). Aucun cascade ne
peut supprimer un binding ou un User. La table n’ajoute ni binding id, ni statut, ni `primary`, ni
soft delete. Un detach futur supprime la ligne ; aucune ligne ne représente une identité libre.

`users` matérialise l’existence autonome de U. L’absence de ligne dans `external_identities` ne
supprime pas U et un User avec zéro binding reste représentable.

### 5.2 Ports

`engine-user-identity` expose au minimum :

- `CurrentExternalIdentityBindingQueryPort.findUserId(ExternalIdentity)` pour Command, READ et AUTH ;
- `UserExistenceQueryPort.exists(PocomaUserId)` pour les futurs use cases exigeant une existence ;
- `InitialUserBindingAcquisitionPort.tryCreateUserAndAcquire(PocomaUserId, ExternalIdentity)` qui
  retourne `ACQUIRED` ou `EXTERNAL_IDENTITY_ALREADY_USED` sans retourner l’owner existant ;
- `UserIdentityEventAppendPort.appendAll(...)` pour les faits D33.

Le port d’acquisition initial est une primitive User/Identity, pas un port Command ni un
get-or-create. Les futurs Attach/Detach devront utiliser la même table et la même clé naturelle via
des ports dédiés, sans contourner cette autorité.

### 5.3 Migration et backfill des données existantes

Avant d’ajouter la FK, la migration insère dans `users` l’union distincte de tous les UUID User
durables connus :

- `external_identities.pocoma_user_id` ;
- `recorded_commands.auth_user_id` ;
- `pot_headers.creator_id`, y compris l’historique temporel ;
- `shareholders.user_id` non null, y compris l’historique temporel.

La migration vérifie ensuite qu’aucun binding ne pointe vers un User absent, puis ajoute la FK.
Elle est idempotente au sens d’un passage Flyway unique et n’altère aucun UUID historique.

Les projections et outboxes dérivées ne sont pas une autorité supplémentaire à backfiller : leurs
UserIds proviennent des sources ci-dessus. Des requêtes de preuve comparent avant/après les ensembles
distincts. Les fixtures et bootstrap local qui insèrent directement un binding doivent d’abord
insérer le User correspondant ; aucun contournement durable de la FK n’est ajouté.

Les FK depuis `recorded_commands`, `pot_headers` ou `shareholders` vers `users` ne sont pas ajoutées
dans REGISTRATION : ces tables contiennent aussi des snapshots/historiques, et leur migration
accroîtrait inutilement le verrouillage. Leur cohérence est assurée par le backfill et, pour les
nouvelles transitions qui exigent un User existant, par les ports User/Identity.

## 6. RegistrationRequest durable

### 6.1 Modèle Java

```text
RegistrationRequestId(UUID)
RegistrationRequest(
  requestId,
  creatorExternalIdentity,
  capturedAt
)
```

L’identifiant est un UUID aléatoire généré côté serveur via un port, conformément à `CommandId`.
`capturedAt` vient d’un `Clock` serveur et fournit l’ordre durable. `creatorExternalIdentity` est
copiée du principal attesté et demeure immutable.

### 6.2 Table

```sql
registration_requests (
  registration_request_id uuid primary key,
  creator_issuer text not null,
  creator_subject text not null,
  captured_at timestamptz not null,
  check non blank sur issuer et subject
)

index (captured_at, registration_request_id)
```

Ne sont stockés ni JWT, ni autorités/scopes, ni temps du JWT, ni `PocomaUserId`, ni statut métier,
ni état Consumption. Le JWT valide au moment de l’admission suffit selon D12 ; les autres claims
n’interviennent dans aucune règle Registration.

La table est la source métier immutable. `consumption_slots` et `consumption_claims` portent seuls
PENDING, claim, lease, takeover, retry et terminalisation technique.

## 7. Outcome Registration autoritatif

### 7.1 Modèle et table

Le modèle scellé contient exactement :

```text
Registered(requestId, pocomaUserId, resolvedAt)
Rejected(requestId, EXTERNAL_IDENTITY_ALREADY_USED, resolvedAt)
```

La table cible :

```sql
registration_outcomes (
  registration_request_id uuid primary key
    references registration_requests(registration_request_id),
  outcome_type varchar(...) not null check in ('REGISTERED', 'REJECTED'),
  pocoma_user_id uuid null references users(pocoma_user_id),
  rejection_code varchar(...) null,
  resolved_at timestamptz not null,
  shape check:
    REGISTERED => user non null et code null
    REJECTED => user null et code = 'EXTERNAL_IDENTITY_ALREADY_USED'
)
```

La PK garantit au plus un résultat. Il n’existe aucune variante `FAILED`. Le rejet ne contient jamais
l’owner du binding concurrent.

### 7.2 Idempotence de la même request

L’exécution recharge d’abord l’outcome par `registration_request_id` sous la transaction fenced :

- s’il existe, elle le convertit en outcome Consumption cohérent sans réappliquer la mutation ;
- sinon, elle tente la transition métier ;
- la PK de `registration_outcomes` est la dernière protection contre une double publication.

Normalement, outcome et slot DONE ont été commités ensemble et la discovery ne repropose plus la
request. Cette relecture est une défense explicite pour un retry après réponse de commit perdue ou
une réparation technique. Elle distingue la même request d’une nouvelle request pour E et empêche
le binding créé par R d’être interprété comme le rejet de R.

## 8. Transaction métier atomique et concurrence

### 8.1 Frontière de commit

L’exécution Registration se déroule dans le `TransactionalExecuteConsumptionUseCase` existant.
Dans cette transaction PostgreSQL unique :

1. relire et verrouiller logiquement la request et son éventuel outcome ;
2. si aucun outcome n’existe, générer U à cet instant ;
3. insérer `users(U)` ;
4. tenter `external_identities(E, U)` avec `ON CONFLICT (issuer, subject) DO NOTHING` ;
5. si le binding est acquis, écrire `Registered(U)`, `UserCreated(U)` et
   `ExternalIdentityAttached(E,U)` ;
6. si le binding n’est pas acquis, supprimer dans la même transaction le candidat U qui vient
   d’être créé, puis écrire seulement `Rejected(EXTERNAL_IDENTITY_ALREADY_USED)` ;
7. append la provenance technique éventuelle ;
8. terminaliser le slot par CAS `currentClaimId` ;
9. committer.

L’insert User suivi du cleanup du candidat perdant est encapsulé dans
`InitialUserBindingAcquisitionPort`; aucun User candidat n’est observable ni présent après le commit
d’un rejet. Aucun fait n’est append avant de connaître l’acquisition. Une implémentation JDBC par
CTE ou plusieurs statements est acceptable si elle conserve cette atomicité et vérifie exactement
les nombres de lignes affectées.

Si le CAS final perd le claim, toute la transaction — User, binding, outcome et faits — rollback.
Il n’existe aucun commit intermédiaire ni `REQUIRES_NEW` dans cette chaîne.

### 8.2 Arbitrage SQL

La PK `(issuer, subject)` reste l’arbitre concurrent réel. `ON CONFLICT DO NOTHING` est préféré à
l’interception d’une violation après abort de transaction : le résultat `0 row` se traduit en rejet
métier sans jamais relire ni révéler `pocoma_user_id` du gagnant.

Deux requests distinctes R1(E), R2(E) peuvent créer deux candidats non visibles. L’insert binding
sérialise la course : une seule obtient une ligne, l’autre nettoie son candidat et rejette. Après
commit il existe exactement un nouveau User, un binding, deux outcomes (un de chaque type) et deux
faits appartenant uniquement au succès.

### 8.3 Attach et Detach futurs

Attach utilisera le même insert sur la clé naturelle ; Detach supprimera sous transaction la ligne
exacte attendue. Registration ne fait aucun pré-check de disponibilité. L’ordre avec Attach/Detach
est donc celui des transitions SQL autoritatives, conformément à D24.

## 9. Faits User/Identity — D33

### 9.1 Modèle Java

Dans `domain-user-identity` :

```text
UserCreated(PocomaUserId)
ExternalIdentityAttached(ExternalIdentity, PocomaUserId)
```

Les deux implémentent `domain-event.BusinessEvent` et possèdent des `EventType` stables
`USER_CREATED` et `EXTERNAL_IDENTITY_ATTACHED`. Aucun `UserRegistered` n’est introduit.

Le décorateur enregistré générique porte :

```text
eventId, eventType, payload, recordedAt,
causationType, causationId, traceId optionnel, partitionHash
```

Pour Registration, `causationType=REGISTRATION_REQUEST` et `causationId=requestId`. Les deux faits
partagent cette corrélation mais ont des `eventId` distincts.

### 9.2 Outbox

Une table dédiée `user_identity_event_outbox` est créée. Elle contient l’enveloppe générique et un
payload JSON versionnable, avec contraintes de type/forme et unicité
`(causation_type, causation_id, event_type)`. Elle n’a ni `pot_id`, ni `version` Pot et ne dépend pas
de `business_event_outbox`.

`RecordedEvent` est généralisé vers `domain-event.BusinessEvent`; l’append Pot existant reste dans
sa table et garde son contrat. Seules les primitives réellement génériques sont partagées.

Les deux rows User/Identity sont append dans la transaction de succès. Aucun row n’est append au
rejet. Un futur consumer utilisera `EVENT/[eventId]` et son propre `ConsumerIdentity`; sa discovery
pourra unir les métadonnées de cette outbox au moteur Event ou employer un locator dédié. Aucun
consumer/projection fictif n’est créé avant un besoin réel.

## 10. Admission Registration

### 10.1 Chaîne cible

```text
Bearer JWT valide
  → Spring Resource Server
  → AuthenticatedExternalPrincipal
  → ExternalIdentity exacte
  → SubmitRegistrationRequestService
  → INSERT registration_requests
  → 202 Accepted + requestId + Location
```

Le contrat est `POST /api/v1/registrations`, sans identité ni UserId dans le body. Une identité
connue et une identité inconnue suivent exactement cette chaîne. L’admission n’appelle ni
`CurrentExternalIdentityBindingQueryPort`, ni la transition User, ni Consumption.

Le matcher `/api/v1/registrations/**` devient explicitement `authenticated()`. La condition
d’activation de la configuration Security inclut les propriétés Registration. Les endpoints
Command conservent leur matcher et `SubmitRecordedCommandService` conserve sa résolution User
obligatoire ; aucun fallback Registration n’y est ajouté.

Réponse d’admission choisie :

- `202 Accepted` ;
- body minimal `{ "registrationRequestId": "..." }` ;
- `Location: /api/v1/registrations/{id}` ;
- `401` pour JWT absent/invalide ;
- aucun `409` synchrone pour une identité déjà utilisée.

## 11. Exécution asynchrone

### 11.1 Spécialisation Consumption

```text
ConsumableIdentity: REGISTRATION_REQUEST / [registrationRequestId]
ConsumerIdentity:   REGISTRATION_PROCESSOR / []
```

Le locator Registration fournit :

- discovery keyset `(captured_at, registration_request_id)` ;
- exclusion des slots DONE, claims non expirés et retries non encore éligibles ;
- relecture autoritative de la request après acquire ;
- exécution de la transaction §8 ;
- classification/policy technique Registration ;
- effet terminal technique vide : jamais d’outcome fonctionnel FAILED.

Il n’y a pas d’ordre par ExternalIdentity, pas de verrou applicatif par issuer/subject et pas de
priorité entre Registration et futurs Attach/Detach. L’unicité SQL suffit. Le keyset garantit que
la request plus ancienne en backoff ne bloque pas les requests éligibles suivantes.

La V1 reste non segmentée, comme Command. Plusieurs instances du runtime peuvent être déployées ;
claims et fencing fournissent le parallélisme sûr. Segmentation n’est ajoutée que sur preuve de
besoin de débit.

### 11.2 Failures et reprise

- crash avant commit : rollback complet, lease expire, takeover et retry de la même request ;
- réponse de commit perdue : slot déjà DONE, ou relecture de l’outcome par request id ;
- deadlock, sérialisation, timeout, connexion indisponible : classification transitoire et backoff
  exponentiel borné par les primitives existantes ;
- corruption/invariant/configuration non transitoire : Consumption peut terminer techniquement
  `FAILED`, sans `registration_outcome`, sans mutation et sans fait ; le GET reste `404` et les
  diagnostics restent opératoires ; une réparation rejoue la même clé, jamais une nouvelle
  intention ;
- aucune exception infrastructure n’est convertie en `Rejected`.

Les seuils de lease, polling et retry sont configurables comme dans le runtime Command et couverts
par tests ; le travail SQL court doit rester largement inférieur au lease par défaut.

## 12. Lecture du résultat et D19

### 12.1 Comparaison

| Option | Avantages | Défauts dans Pocoma | Verdict |
|---|---|---|---|
| Outcome autoritatif direct | aucune latence de projection ; contrôle atomique request/outcome/binding courant ; peu de composants | exception documentée au READ exact par projection | **retenue** |
| Projection `REGISTRATION_RESULT` | similaire à CCR ; découplage read store | terminal Event, route, task et projector supplémentaires ; projection obsolète après detach ; exige malgré tout un lookup du binding courant | rejetée pour V1 |

Une projection ne peut pas figer la visibilité : D19 exige le binding exact **au moment de la
lecture**. La lecture directe est donc la plus petite architecture correcte. Elle passe par un use
case et un port, jamais par du JDBC dans le contrôleur.

### 12.2 Contrat de lecture

Le port exécute une requête/snapshot read-only qui ne retourne un terminal visible que si :

1. `request.creatorExternalIdentity == caller ExternalIdentity` exactement ;
2. un outcome terminal existe ;
3. si `REGISTERED(U)`, `external_identities` contient encore exactement `E -> U`.

Pour `REJECTED`, aucun lookup ni champ de l’owner existant n’est réalisé. Le response body contient :

- succès : `{ outcome: "REGISTERED", pocomaUserId: U, resolvedAt: ... }` ;
- rejet : `{ outcome: "REJECTED", code: "EXTERNAL_IDENTITY_ALREADY_USED", resolvedAt: ... }`.

Traduction HTTP :

| Situation interne | HTTP public |
|---|---|
| outcome terminal visible | `200` |
| request absente | `404` |
| request présente sans outcome | `404` |
| caller non créateur | `404` |
| succès mais binding E→U absent ou différent | `404` |

Ce masquage suit le précédent `COMMAND_RESULT`, n’expose aucun PENDING technique et ne crée aucun
oracle d’existence. Les cas restent distingués dans les tests de service/adapter, mais sont
volontairement indistinguables sur HTTP.

## 13. Lots d’implémentation

Chaque lot est committable, garde le reactor vert et ne suppose pas l’activation prématurée d’un
endpoint ou worker.

### REGISTRATION.1 — Ownership User/Identity et types neutres

**Objectif.** Installer les autorités Java/Maven D31 sans changement de comportement.

**Prérequis.** Canon D31–D33 ; reactor vert au HEAD de départ.

**Modules/fichiers probablement impactés.** Nouveau `domain-user-identity`, `engine-authentication`,
`engine-user-identity`; `app/pom.xml`; POM et imports de `engine-command`,
`orchestrator-command-admission`, `supra-authentication-spring-security`, READ, Pot bindings,
`infra-persistence-jpa`, runtimes et tests ; `docs/architecture/type-ownership.md` et
`module-dependency-matrix.md`; guards ArchUnit.

**Changements.** Créer `User`, déplacer `PocomaUserId` et `ExternalIdentity`, neutraliser
`AuthenticatedExternalPrincipal`, déplacer le port de résolution, conserver l’adaptation Pot.
Supprimer les anciennes définitions dans le même commit.

**Invariants.** D1, D6, D11, D29–D31 ; admission Command toujours identique.

**Preuves.** Tests value objects ; tests de principal ; tests d’admission Command connue/inconnue ;
compilation de tous les consommateurs ; guards interdisant User/Identity → Command/Pot/Registration
et interdisant toute seconde définition d’ExternalIdentity/PocomaUserId.

**DONE.** Une définition canonique de chaque type, aucun cycle Maven, reactor complet vert.

**Risques.** Grand nombre d’imports ; oubli dans fixtures ou reflection. Mitigation : scan `rg` des
anciens FQCN et test de reactor.

**Hors lot.** SQL, request, outcome, HTTP, worker, mutation User/binding.

### REGISTRATION.2 — Autorité persistante User/binding et backfill

**Objectif.** Matérialiser User autonome et rendre le binding référentiellement cohérent.

**Prérequis.** REGISTRATION.1.

**Modules/fichiers.** `infra-persistence-jpa` migration suivante (attendue V18), repositories et
adapters identity ; tests migration/PostgreSQL ; fixtures architecture/runtime ; bootstrap local ;
tests de démolition/schema.

**Changements.** Créer `users`, backfiller l’union des quatre sources, ajouter la FK RESTRICT,
implémenter les query ports et `InitialUserBindingAcquisitionPort`; adapter toutes les fixtures à
User puis binding.

**Invariants.** D1, D3, D4, D8, D10, D15, D28, D32.

**Preuves.** Migration sur schéma vide et schéma prépeuplé ; zéro perte d’UUID ; User sans binding ;
plusieurs bindings vers U ; impossibilité de binding vers User absent ; conflit concurrent exact ;
cleanup du candidat perdant ; résolution Command inchangée.

**DONE.** Toutes les lignes binding référencent `users`; données historiques cohérentes ; aucun
registre d’identités libres.

**Risques.** verrou de validation FK, fixture oubliée, UUID présent dans une source historique.
Mitigation : insert-select distinct avant contrainte, requêtes de contrôle et tests legacy.

**Hors lot.** RegistrationRequest, outcome, faits, endpoints, runtime.

### REGISTRATION.3 — Source durable RegistrationRequest et discovery

**Objectif.** Introduire l’intention immutable et sa spécialisation Consumption, sans exécuter la
transition métier.

**Prérequis.** REGISTRATION.2.

**Modules/fichiers.** Nouveau `engine-registration`, `locator-consumption-registration`; migration
suivante attendue V19 ; adapters request/discovery ; POM reactor ; tests unitaires/PostgreSQL.

**Changements.** Modèle/id/générateur/request store ; table §6 ; port d’admission interne ; keys
`REGISTRATION_REQUEST/REGISTRATION_PROCESSOR`; discovery keyset. Ne pas composer de worker actif
avant REGISTRATION.4.

**Invariants.** D14, D16, D18, D21, D23.

**Preuves.** round-trip immutable ; UUID/capturedAt serveur ; absence de JWT/scopes/UserId/état
technique ; ordering stable ; exclusion DONE/busy/not-ready ; request sans slot avant acquisition.

**DONE.** Source durable et discovery testées, aucun effet User, aucun endpoint exposé, aucun worker
activé.

**Risques.** dupliquer le lifecycle dans la table ou confondre request et Command. Guards de colonnes
et dépendances dédiés.

**Hors lot.** outcome, transition, faits, HTTP, runtime actif.

### REGISTRATION.4 — Transition, outcome, faits et worker

**Objectif.** Livrer le cœur atomique asynchrone de Registration.

**Prérequis.** REGISTRATION.3 ; primitive d’acquisition REGISTRATION.2.

**Modules/fichiers.** `domain-user-identity`, `engine-user-identity`, `engine-registration`,
`engine-core` pour la généricité Event, `locator-consumption-registration`, nouveau
`runtime-registration-consumption-worker`, `infra-persistence-jpa`; migration suivante attendue V20;
configuration/properties runtime ; tests PostgreSQL/concurrence.

**Changements.** outcomes §7, outbox §9, service §8, locator/exécution/classifier/policy, composition
transactionnelle et polling. Généraliser seulement l’enveloppe Event commune ; garder Pot séparé.

**Invariants.** D2–D5, D9–D10, D13, D15, D17, D20, D22–D25, D32–D33.

**Preuves.** cinq effets atomiques du succès ; rejet sans User/fait ; retry même request ; rollback
sur exception à chaque write ; claim perdu ; takeover ; crash simulé ; deux requests concurrentes ;
unicité outcome/faits ; absence de `FAILED` fonctionnel.

**DONE.** Un worker peut traiter des requests seedées ; exactement les états canoniques sont
commités ; runtime restart-safe et multi-worker-safe.

**Risques.** transaction abort sur conflit, outcome dupliqué, événement Pot réutilisé à tort,
terminal failure public. Mitigation : `ON CONFLICT DO NOTHING`, PK outcome, outbox dédiée, tests de
forme et architecture.

**Hors lot.** HTTP admission et lecture publique ; Attach/Detach ; consumers des faits.

### REGISTRATION.5 — Admission HTTP authentifiée

**Objectif.** Accepter toute identité attestée sans résolution User et sans affaiblir Command.

**Prérequis.** REGISTRATION.3 ; REGISTRATION.4 disponible pour l’E2E mais non requis pour le test
unitaire d’admission.

**Modules/fichiers.** Nouveau `orchestrator-registration-admission`,
`supra-http-write-registration`; `supra-authentication-spring-security`, `runtime-web-api`, OpenAPI,
properties et tests HTTP/PostgreSQL.

**Changements.** POST §10, matcher Security explicite, composition du writer request, gestion 202.
Le service n’injecte pas le binding query port.

**Invariants.** D6, D12, D18, D23, D29–D30 ; invariant Command ordinaire protégé.

**Preuves.** JWT valide inconnu → 202/request durable ; JWT valide connu → 202 ; invalide/absent →
401 ; aucune mutation/outcome/fait synchrone ; Command avec identité inconnue reste rejetée ; identité
du payload impossible.

**DONE.** Admission observable en HTTP, strictement asynchrone, sans changement d’admission Command.

**Risques.** route laissée sous `permitAll`, bean Security non activé, réutilisation accidentelle du
resolver Command. Tests de chaîne réelle et guard de dépendance.

**Hors lot.** GET résultat ; changement de la politique Command ; scope Registration.

### REGISTRATION.6 — Lecture autoritative et visibilité

**Objectif.** Exposer uniquement les outcomes terminaux visibles selon D19.

**Prérequis.** REGISTRATION.4 et REGISTRATION.5.

**Modules/fichiers.** `engine-registration`, nouveau `supra-http-read-registration`, adapter query
dans `infra-persistence-jpa`, `runtime-web-api`, Security/OpenAPI, tests service/JDBC/controller et
guards READ.

**Changements.** Port/use case de lecture snapshot §12, GET, responses, mapping uniforme 404. Aucun
terminal Event ni projection Registration.

**Invariants.** D7, D19–D21, D27, D30.

**Preuves.** créateur lit rejet ; autre identité non ; créateur lit succès tant que E→U ; autre
identité du même U non ; detach ou rebind masque ; absent/nonterminal/nonowner tous 404 ; rejet ne
charge/expose jamais l’owner.

**DONE.** Contrat HTTP prouvé sans oracle et sans exposition Consumption.

**Risques.** contrôle par UserId au lieu d’ExternalIdentity, projection obsolète, TOCTOU en plusieurs
transactions. Mitigation : requête/snapshot unique et tests de rebind.

**Hors lot.** listing, pending public, projection, Attach/Detach endpoints.

### REGISTRATION.7 — Preuves E2E, architecture et clôture

**Objectif.** Prouver le flux complet et nettoyer la documentation/configuration sans élargir le
scope métier.

**Prérequis.** REGISTRATION.1–6.

**Modules/fichiers.** `architecture-tests`, tests runtime/web Testcontainers, scripts Bruno si la
convention l’exige, docs architecture/operations/README, matrice de preuve du présent document.

**Changements.** E2E admission → worker → GET, restart de contexte, tests concurrence, scans de
dépendances/FQCN/tables, valeurs de configuration documentées. Mettre les statuts des lots à DONE
uniquement sur preuves vertes.

**Invariants.** D1–D33 et non-régression Command/READ/AUTH/Pot/Consumption.

**Preuves.** Matrice §14 complète ; reactor Maven complet ; migrations depuis base pré-Registration ;
runtime restart ; aucune ancienne définition ni writer de binding hors autorité.

**DONE.** `REGISTRATION PROOF MATRIX COMPLETE — CLOSABLE`, documentation d’exploitation alignée,
aucun finding ouvert.

**Risques.** E2E trop mocké ou données seedées après admission. Le test de référence ne seed que le
JWT technique et, pour les cas de rejet, l’état User/binding préexistant via l’autorité de production.

**Hors lot.** Attach/Detach, profil/lifecycle User, listing, nouvelle projection, optimisation sans
preuve de besoin.

## 14. Matrice de preuves cible

| Axe | Preuve durable attendue |
|---|---|
| Admission inconnue | JWT Keycloak valide, aucune ligne binding : POST 202 et request durable seule |
| Admission connue | binding existant : POST 202, puis worker écrit le rejet |
| AuthN | JWT absent/invalide : 401 et aucune request |
| Command | identité inconnue : Command toujours non admise, aucune `recorded_command` |
| Succès | User, binding, Registered, UserCreated, ExternalIdentityAttached présents ensemble |
| Atomicité succès | failure injectée après chacun des cinq writes : rollback de tous les effets |
| Rejet | binding préexistant : seul Rejected ; aucun User/fait/owner exposé |
| Même request | exécutions/retries avant et après commit : un User et un outcome identique |
| Claim perdu | takeover avant CAS : tous les effets du perdant rollback |
| Crash/reprise | crash avant commit puis lease expiry : reprise ; crash/réponse perdue après commit : pas de rejeu |
| Deux requests E | barrière concurrente : exactement un Registered, un Rejected, un User, un binding, deux faits |
| User autonome | User sans binding persiste et reste identifiable |
| Multi-identités | plusieurs bindings distincts peuvent viser le même User |
| Rejet visible | créateur exact 200 ; autre identité 404 |
| Succès visible | créateur exact + E→U 200 ; autre identité du même U 404 |
| Detach/rebind | après suppression ou E→autre U, ancien succès 404 |
| Non-oracle | absent, nonterminal, nonowner et binding perdu ont tous la même réponse 404 |
| Events | deux faits, types/payload/corrélation exacts, aucun au rejet, aucune dépendance Pot |
| Backfill | tous les UUID externals/Commands/Pots/Shareholders historiques existent dans `users` |
| READ/AUTH/Pot | suites existantes inchangées avec adaptation explicite de UserId |
| Consumption | discovery, claims, retry, lease, takeover, fencing et restart verts |
| Architecture | modules/dépendances/ownership/FQCN uniques et absence de cycle |

Les tests de concurrence utilisent deux transactions/connexions réellement parallèles et une
barrière au point d’insert binding ; un simple test séquentiel ne constitue pas la preuve.

## 15. Risques de migration et réponses

| Risque | Réponse planifiée |
|---|---|
| déplacement de `PocomaUserId` | un lot mécanique atomique, ancien FQCN supprimé, reactor complet et scan `rg` |
| déplacement de `ExternalIdentity` | même stratégie ; aucune classe de transition concurrente |
| coexistence temporaire de types | coexistence seulement dans le diff du lot, jamais à un commit ; adapters UUID explicites pour Pot |
| dépendances Maven circulaires | domaine User/Identity intérieur, engines au-dessus, locators/supras/runtimes extérieurs ; guards |
| `domain-pot.UserId` ambigu | documenté comme référence locale, conversion centralisée, aucun ownership système |
| ajout de `users` sur base existante | backfill union distincte avant FK, contrôles exhaustifs, aucun UUID régénéré |
| FK `external_identities` | validation après backfill ; RESTRICT ; test sur volume représentatif si nécessaire |
| fixtures directes binding | helpers `insertUserThenBinding`, scan de tous les SQL de test/bootstrap |
| Commands/Pots/Shareholders historiques | leurs UUID sont inclus au backfill même sans binding actuel |
| projections/outboxes historiques | dérivées des sources ; pas utilisées comme seconde autorité ni détruites |
| conflit SQL | `ON CONFLICT DO NOTHING` et row-count, pas d’exception qui abort la transaction |
| User candidat orphelin | cleanup dans la même primitive/transaction avant outcome rejeté ; preuve concurrente |
| fencing trop tardif | transaction unique incluant CAS final ; aucun `REQUIRES_NEW` |
| failure technique publique | aucun variant/colonne/DTO FAILED Registration ; Consumption seul garde le diagnostic |
| outbox Pot réutilisée | outbox User/Identity dédiée sur primitives `domain-event` génériques |
| READ par projection obsolète | lecture autoritative avec test du binding courant au même snapshot |
| route Registration publique | matchers `authenticated()` explicites et tests 401 |
| admission Command affaiblie | resolver obligatoire conservé et régression HTTP/PostgreSQL permanente |
| bootstrap local/dev | seed Users avant bindings ; runbook et scripts mis à jour dans REGISTRATION.7 |
| tests de démolition/schema | listes/checksums attendus adaptés uniquement dans les lots de migration concernés |

## 16. Fichiers et surfaces attendus par famille

Cette liste guide l’implémentation sans imposer chaque nom de classe :

- reactor/POM : `app/pom.xml` et POM des nouveaux modules/consommateurs ;
- domaines/engines : nouveaux modules §4 ; `engine-command`, `engine-core`, bindings Pot ;
- persistence : migrations V18–V20 attendues, adapters/repositories identity/registration/outbox ;
- HTTP/AuthN : `supra-authentication-spring-security`, deux supras Registration,
  `runtime-web-api` ;
- Consumption : locator et runtime Registration, propriétés/lifecycle/observation génériques ;
- preuves : tests unitaires de chaque module, PostgreSQL adapters/runtime, E2E architecture ;
- docs : ownership, matrice de modules, contrat HTTP, opérations et suivi des statuts.

Les numéros V18–V20 sont ceux attendus au HEAD audité où V17 est la dernière migration. Si une
autre migration est fusionnée avant un lot, l’implémentation prend les prochains numéros libres sans
réécrire l’historique Flyway.

## 17. Invariants hors négociation pendant l’implémentation

- RegistrationRequest n’est jamais une Command.
- Consumption ne devient jamais l’état fonctionnel public de Registration.
- l’admission ne résout jamais E vers U et ne vérifie jamais la disponibilité de E.
- les Commands ordinaires exigent toujours un binding existant.
- une nouvelle request pour une identité utilisée est rejetée ; elle ne récupère jamais l’owner.
- le retry de la même request retrouve son outcome propre.
- succès = cinq effets dans un commit ; rejet = outcome seul.
- aucun User orphelin n’est commité par un perdant.
- `UserCreated` et `ExternalIdentityAttached` ne sont pas remplacés par `UserRegistered`.
- aucune identité principale, aucun registre d’identités libres, aucun lifecycle User anticipé.
- aucune issue fonctionnelle `FAILED`.
- le GET compare l’ExternalIdentity exacte ; un UserId égal ne suffit pas.

## 18. Verdict

**IMPLEMENTATION READY**

Le repository fournit les primitives de transaction, fencing, retry, polling et atomic outbox
nécessaires. Les écarts restants sont des travaux d’implémentation explicitement distribués entre
REGISTRATION.1 et REGISTRATION.7 ; aucune contradiction avec D1–D33 ni décision métier/architecturale
manquante n’a été trouvée.
