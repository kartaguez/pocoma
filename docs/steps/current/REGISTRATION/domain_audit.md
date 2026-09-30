# REGISTRATION — audit de cadrage métier et architectural

Date : 2026-09-30

Repository : `/Users/julien.guezennec/Dev/projects/pocoma`

Branche auditée : `v2-make-it-pull`

HEAD audité : `7ac60930cab14c7d5d9a6587014596d99aac7879`

## 1. Baseline repository

Cet audit confronte les décisions D1–D30 au repository à ce HEAD. Il ne constitue ni un design
détaillé de `RegistrationRequest`, ni un plan d'implémentation.

Le worktree contenait avant cet audit des déplacements non commités de documents depuis `docs/`
vers `docs/plans/`. Ils sont hors périmètre et n'ont pas été modifiés. Le seul fichier créé par cet
audit est le présent document. Aucun code applicatif, test, migration ou autre document
`REGISTRATION` n'a été modifié.

Le dossier `docs/steps/current/REGISTRATION/` contenait un seul document avant cet audit :
[`step_audit.md`](step_audit.md). Il a été lu intégralement.

## 2. Sources documentaires et code lus

### 2.1 Documents

- [`docs/steps/current/REGISTRATION/step_audit.md`](step_audit.md), premier audit Registration ;
- [`docs/steps/completed/CCR/RegisterUser_Identity_Audit.md`](../../completed/CCR/RegisterUser_Identity_Audit.md),
  inventaire antérieur de la chaîne d'identité et du bootstrap absent ;
- [`docs/steps/completed/CCR/Step_Canon.md`](../../completed/CCR/Step_Canon.md), autorité sur les
  outcomes et terminal Events des Commands ;
- [`docs/use-case-families.md`](../../../use-case-families.md), séparation intention métier,
  enveloppe durable et Consumption ;
- [`docs/architecture/type-ownership.md`](../../../architecture/type-ownership.md), propriété actuelle
  des types, notamment `UserId`, `AuthenticatedExternalPrincipal` et `ExternalIdentity` ;
- [`docs/architecture/recorded-command-intake.md`](../../../architecture/recorded-command-intake.md),
  frontière d'authentification et résolution exacte `(issuer, subject)` ;
- [`docs/architecture/recorded-command-persistence.md`](../../../architecture/recorded-command-persistence.md),
  séparation entre donnée source durable et lifecycle Consumption ;
- [`docs/architecture/module-dependency-matrix.md`](../../../architecture/module-dependency-matrix.md),
  frontières de modules ;
- [`docs/architecture/authorization-kernel-contracts.md`](../../../architecture/authorization-kernel-contracts.md)
  et [`docs/architecture/read-side-target.md`](../../../architecture/read-side-target.md), usages du
  `UserId` dans l'autorisation et les projections Pot.

### 2.2 Code et schéma déterminants

- [`PocomaUserId`](../../../../app/engine-command/src/main/java/com/kartaguez/pocoma/engine/command/model/PocomaUserId.java)
  et [`domain-pot.value.UserId`](../../../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/UserId.java) ;
- [`ExternalIdentity`](../../../../app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/model/ExternalIdentity.java),
  [`AuthenticatedExternalPrincipal`](../../../../app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/model/AuthenticatedExternalPrincipal.java)
  et [`ExternalIdentityResolverPort`](../../../../app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/port/out/ExternalIdentityResolverPort.java) ;
- [`SubmitRecordedCommandService`](../../../../app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/SubmitRecordedCommandService.java),
  qui exige aujourd'hui une identité déjà provisionnée ;
- [`JpaExternalIdentityResolverAdapter`](../../../../app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/identity/JpaExternalIdentityResolverAdapter.java)
  et [`ExternalIdentityJdbcRepository`](../../../../app/infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/identity/ExternalIdentityJdbcRepository.java),
  tous deux limités à la résolution en lecture ;
- migrations [`V1__init_schema.sql`](../../../../app/infra-persistence-jpa/src/main/resources/db/migration/V1__init_schema.sql),
  [`V8__recorded_commands.sql`](../../../../app/infra-persistence-jpa/src/main/resources/db/migration/V8__recorded_commands.sql),
  [`V9__external_identities.sql`](../../../../app/infra-persistence-jpa/src/main/resources/db/migration/V9__external_identities.sql)
  et [`V17__command_outcomes_and_terminal_events.sql`](../../../../app/infra-persistence-jpa/src/main/resources/db/migration/V17__command_outcomes_and_terminal_events.sql) ;
- [`WebApiSecurityConfiguration`](../../../../app/supra-authentication-spring-security/src/main/java/com/kartaguez/pocoma/supra/authentication/springsecurity/WebApiSecurityConfiguration.java),
  [`SpringSecurityExternalPrincipalAdapter`](../../../../app/supra-authentication-spring-security/src/main/java/com/kartaguez/pocoma/supra/authentication/springsecurity/SpringSecurityExternalPrincipalAdapter.java)
  et [`AuthenticatedExternalPrincipalArgumentResolver`](../../../../app/supra-authentication-spring-security/src/main/java/com/kartaguez/pocoma/supra/authentication/springsecurity/AuthenticatedExternalPrincipalArgumentResolver.java) ;
- modèles Pot et autorisation qui portent un `UserId` : `PotHeader`, `Shareholder`, `UserContext`,
  `AuthProjectionInput` et `PotAuthorizationRelations` ;
- événements Pot sous `domain-pot/.../event`, `CommandTerminalEventTypes`,
  `JdbcCommandOutcomeAdapter` et les deux outboxes existantes.

Une recherche exhaustive des sources principales n'a trouvé aucun `User` métier, `UserRepository`,
table `users`/`pocoma_users`, `AttachExternalIdentity`, `DetachExternalIdentity`, ni événement lié à
la création d'un User ou au rattachement d'une identité.

## 3. Modèle actuel constaté

### 3.1 Ce qui constitue aujourd'hui un User Pocoma

Il n'existe pas aujourd'hui d'entité, d'agrégat ou de table canonique `User`. Le repository ne sait
représenter qu'un identifiant UUID :

- `PocomaUserId(UUID)` dans `engine-command`, décrit comme l'identité provider-neutral d'un User ;
- `domain-pot.value.UserId(UUID)` dans le domaine Pot ;
- des colonnes UUID qui reprennent cette valeur sans foreign key vers une autorité User.

En pratique, un « User connu » au point d'entrée HTTP est aujourd'hui un UUID trouvé dans une ligne
`external_identities`. Cette résolution opérationnelle n'est pas un modèle d'existence du User.
Supprimer le dernier binding supprimerait la seule preuve directe de son existence dans ce sous-
système, alors que d'autres références historiques ou courantes à son UUID peuvent subsister.

Les références principales au User sont :

- `recorded_commands.auth_user_id`, snapshot durable de l'acteur (`V8`, lignes 1–17) ;
- `pot_headers.creator_id`, obligatoire (`V1`, lignes 7–16) ;
- `shareholders.user_id`, optionnel (`V1`, lignes 18–30) ;
- les modèles Pot `PotHeader.creatorId` et `Shareholder.userId` ;
- les relations d'autorisation `creatorUserId` et `shareholderId -> userId` dans `AUTH` ;
- `COMMAND_RESULT.submittedByUserId`, utilisé pour la visibilité du résultat, pas comme autorité
  d'existence User.

Aucune de ces références ne possède une FK vers un registre User. D1 et D8 invalident donc
l'hypothèse implicite selon laquelle la présence d'au moins un binding suffirait durablement à
constituer le User. Elles n'invalident pas les références UUID existantes : elles exigent une
autorité d'existence autonome que le repository ne possède pas encore.

### 3.2 ExternalIdentity et binding actuel

`ExternalIdentity` est un value object `(String issuer, String subject)`. Son constructeur refuse
`null` et les chaînes blank mais ne transforme ni ne normalise les valeurs. L'égalité des records
Java est l'égalité exacte des deux chaînes. `AuthenticatedExternalPrincipal.identity()` construit
exactement cette valeur.

La frontière Spring extrait `iss` et `sub` d'un `JwtAuthenticationToken` déjà authentifié. La
validation JWT et les contrôles de signature, issuer, audience et dates appartiennent au Resource
Server ; l'adapter produit ensuite un contrat provider-neutral. Cela correspond à D6 et D29.

La table `external_identities` contient directement :

```text
(issuer, subject) -> pocoma_user_id
```

Sa clé primaire composite garantit qu'une clé externe courante ne peut viser deux Users. L'absence
d'unicité sur `pocoma_user_id` permet plusieurs identités pour le même UUID. Il n'y a ni identifiant
de binding, ni dates, ni statut, ni notion d'identité principale, ni FK vers un User. Une ligne est
donc simultanément la représentation SQL de l'ExternalIdentity rattachée et du binding ; le modèle
applicatif ne nomme pas le binding comme concept distinct.

Le repository et le port exposent uniquement `findUserId`. Il n'existe aucune écriture de production,
aucun attach et aucun detach. Les insertions actuellement visibles sont des fixtures de tests.

### 3.3 Emplacement architectural

`AuthenticatedExternalPrincipal`, `ExternalIdentity` et leur resolver sont possédés par
`orchestrator-command-admission` (`type-ownership.md`, ligne 29), alors qu'ils sont aussi consommés
par les controllers READ. Cette propriété était acceptable pour livrer l'admission Command mais
n'est pas une propriété canonique du domaine User/Identity : Registration et les futurs use cases
Attach/Detach ne doivent pas dépendre nominalement d'un orchestrateur Command.

Le contenu de `ExternalIdentity` est cohérent et doit rester unique dans le système. Son ownership
architectural est à revoir ; créer une seconde valeur équivalente dans Registration introduirait
deux définitions concurrentes de la même identité.

## 4. Matrice D1–D30

Les statuts portent sur le repository actuel, pas sur la validité de la décision. « Absente » est un
écart normal pour un domaine nouveau et ne constitue pas en soi un défaut.

| Décision | Statut | Confrontation concise |
|---|---|---|
| D1 — User autonome | **Absente** | Seuls des UUID et bindings existent ; aucune autorité User autonome. |
| D2 — Registration crée un nouveau User | **Absente** | Aucun use case Registration. La proposition get-or-create du premier audit la contredit, pas le code actuel. |
| D3 — unicité globale ExternalIdentity | **Partiellement représentée** | La PK `(issuer, subject)` matérialise l'unicité courante, sans invariant métier ni transition d'écriture. |
| D4 — plusieurs identités par User | **Partiellement représentée** | Aucune unicité sur `pocoma_user_id` ; cardinalité testée, mais aucun Attach. |
| D5 — identité déjà utilisée = rejet | **Absente** | Aucun traitement Registration. Le get-or-create proposé auparavant est contradictoire. |
| D6 — identité déjà attestée | **Compatible** | Resource Server puis `AuthenticatedExternalPrincipal`; le domaine ne reçoit pas le JWT. |
| D7 — pas d'identité principale | **Compatible** | Aucun champ, statut ou contrainte de primary identity. |
| D8 — User avec zéro identité | **Absente** | Impossible de matérialiser l'existence autonome d'un tel User ; les UUID peuvent néanmoins subsister ailleurs. |
| D9 — création User + binding atomique | **Absente** | Ni création User, ni port d'écriture binding, ni transaction Registration. |
| D10 — identité détachée réutilisable | **Partiellement représentée** | La PK ne réserve que les lignes présentes ; aucune histoire SQL ne bloque une réinsertion, mais Detach est absent. |
| D11 — User minimal | **Compatible mais absent** | Aucun profil User anticipé ; le User canonique lui-même manque. |
| D12 — toute identité acceptée peut s'inscrire | **Partiellement représentée** | AuthN provider-neutral existe ; aucune route/use case Registration ni règle d'admission associée. |
| D13 — `Registered` ou rejet unique | **Absente** | Aucun résultat Registration. `CommandOutcome` est spécifique aux Commands/Pots. |
| D14 — request/intention ≠ User ≠ Consumption | **Partiellement représentée** | La séparation source durable/Consumption est canonique pour Command, mais aucun type Registration n'existe. |
| D15 — frontière transactionnelle User + binding | **Absente** | Le binding SQL existe sans User autonome ni opération transactionnelle commune. |
| D16 — Registration irrévocable | **Absente** | Aucun lifecycle Registration. |
| D17 — seul rejet fonctionnel actuel | **Absente** | Aucun catalogue Registration ; les erreurs actuelles concernent Command/authentification. |
| D18 — acceptation = intention durable seulement | **Compatible mais absent** | La même sémantique est canonique pour l'admission Command ; Registration n'existe pas. |
| D19 — lecture liée à l'identité créatrice et au binding courant | **Absente** | Le READ Command contrôle seulement le `userId`, donc son pattern ne satisfait pas cette règle s'il était copié. |
| D20 — résultat terminal unique et immuable | **Partiellement représentée** | Le pattern `command_outcomes` (PK par request) prouve la capacité, sans résultat Registration. |
| D21 — aucun état technique dans le READ | **Compatible mais absent** | CCR sépare déjà outcome fonctionnel et lifecycle Consumption ; aucun READ Registration. |
| D22 — fait métier durable du succès | **Absente** | Les BusinessEvents existants sont Pot ; les terminal Events Command signalent un outcome, pas une mutation User. |
| D23 — UserId généré à la transition effective | **Absente** | Aucun générateur ni transition de création User. |
| D24 — concurrence Registration/Attach/Detach | **Absente** | Aucun des trois use cases et aucune autorité d'écriture partagée. |
| D25 — aucune compensation User après succès | **Absente** | Aucun lifecycle Registration/User. Compatible avec l'atomicité transactionnelle existante. |
| D26 — pas de lifecycle User anticipé | **Compatible** | Aucun état `ACTIVE`/`DISABLED`/`DELETED` User. |
| D27 — pas de registrationIdentity dans User | **Compatible** | Aucun état User ni identité privilégiée. |
| D28 — pas de registre des identités libres | **Partiellement représentée** | Seuls les bindings présents sont stockés ; aucun Detach ne confirme encore la transition. |
| D29 — identité opaque/provider-neutral | **Compatible** | `ExternalIdentity` ne connaît que deux chaînes et aucun provider. |
| D30 — égalité exacte `(issuer, subject)` | **Compatible** | Record Java et PK SQL comparent le couple ; les checks `btrim` valident sans normaliser. |

## 5. Analyse User / ExternalIdentity / binding

### 5.1 Modèle imposé par les décisions

D1 et D8 ferment une question laissée ouverte par les audits précédents : une ligne
`external_identities` ne peut pas constituer à elle seule un User. Un User doit avoir une existence
durable indépendante des bindings courants, même si son état métier minimal ne contient que son
`PocomaUserId`.

D3, D4, D10, D24 et D28 décrivent séparément une relation courante :

```text
ExternalIdentity (issuer, subject) -- binding courant --> PocomaUserId
```

Cette relation a ses propres transitions (attacher, détacher, être acquise par Registration) et son
propre invariant global. Elle mérite donc un concept explicite dans le modèle et les ports, même si
sa représentation SQL finale peut rester compacte. « Concept explicite » ne préjuge ni d'une classe
`Binding`, ni d'une table avec identifiant artificiel.

### 5.2 Hypothèses actuelles affectées

- La résolution HTTP assimile aujourd'hui « binding trouvé » à « User provisionné ». Elle reste
  correcte pour authentifier un User via cette identité, mais ne peut devenir le test canonique
  d'existence du User.
- Les références Pot et Command acceptent des UUID sans vérifier une autorité User. D1 n'oblige pas
  rétroactivement toute lecture historique à joindre un registre User ; elle impose que les futures
  transitions qui exigent un User existant disposent d'une autorité explicite.
- Le retrait du dernier binding ne doit ni supprimer ni désactiver implicitement le User. Le modèle
  actuel ne sait ni effectuer ni représenter cette distinction.
- `PocomaUserId` est actuellement possédé par `engine-command`, tandis que `UserId` est possédé par
  `domain-pot`. Un domaine User autonome ne peut avoir pour identité canonique un type appartenant
  à l'un de ses consommateurs. L'unification ou l'adaptation entre ces types est une question
  d'ownership architectural, pas une raison de modifier leur valeur UUID.

## 6. Agrégat et frontière de cohérence

### 6.1 User comme aggregate root

`User { PocomaUserId }` peut raisonnablement être un aggregate root : il porte sa propre identité et
son existence, et pourra être référencé sans charger ses ExternalIdentities. Cela satisfait D1, D8,
D11 et D26 sans inventer de profil ou lifecycle.

User seul ne peut toutefois pas protéger D3. Deux opérations visant la même ExternalIdentity mais
deux Users différents ne se coordonnent pas par la frontière d'un aggregate User. Mettre une liste
d'identités dans chaque User ne rend pas la recherche globale atomique et risque de dupliquer la
relation avec un index de résolution séparé.

### 6.2 Binding explicite et autorité globale

Le candidat le plus cohérent avec D15 est :

- User est l'autorité sur l'existence de `PocomaUserId` ;
- un registre de bindings courants est l'autorité sur `ExternalIdentity -> PocomaUserId` ;
- Registration est une opération métier transactionnelle qui crée les deux faits ensemble ;
- Attach et Detach passent par la même autorité de binding et le même invariant d'unicité.

Le binding peut être considéré comme un aggregate/root autonome identifié naturellement par
`ExternalIdentity`, ou comme une relation durable protégée par un service de domaine et un port
atomique. Le repository ne fournit pas assez de matière pour préférer définitivement l'une de ces
deux formulations DDD. En revanche, un « aggregate singleton de toutes les identités » serait une
construction artificielle et un point de contention ; rien dans D1–D30 ne le justifie.

### 6.3 Frontière transactionnelle

D9 et D25 exigent que la transition gagnante rende visibles ensemble :

```text
User(U) existe
binding(E -> U) existe
résultat terminal Registered(U) existe
fait(s) métier durable(s) du succès existe(nt), si enregistrés dans le même write model
```

ou qu'aucun de ces effets de création n'existe. La `RegistrationRequest`, capturée auparavant, reste
durable dans les deux cas. Cette frontière est une transaction métier qui peut englober plusieurs
concepts durables ; la faire dicter par une table ou par le lifecycle Consumption contredirait D14
et D15.

L'invariant métier est : « une ExternalIdentity courante appartient à au plus un User ». Une
contrainte unique/PK transactionnelle sur la représentation persistée du binding est une excellente
matérialisation concurrente de cet invariant, mais elle ne remplace pas la décision métier : le code
doit traduire le conflit d'une nouvelle intention en
`Rejected(EXTERNAL_IDENTITY_ALREADY_USED)`, sans retourner l'owner existant.

Le modèle actuel `external_identities` possède déjà la bonne clé d'arbitrage. Il ne possède pas la
ligne User, le port de transition, l'atomicité avec le résultat, ni la distinction sémantique entre
retry de la même request et nouvelle request.

### 6.4 Autorité de lecture du résultat

D19 requiert deux preuves distinctes que le READ Command actuel ne fournit pas ensemble :

- la request doit mémoriser immuablement l'ExternalIdentity exacte qui l'a créée ;
- pour `Registered(U)`, le binding courant exact `E -> U` doit encore exister au moment de la
  lecture.

Résoudre seulement l'identité courante vers un `PocomaUserId` puis comparer ce UUID, comme le fait
`CommandResultController` avec `submittedByUserId`, serait insuffisant : une autre identité du même
User pourrait lire le résultat, et l'identité créatrice détachée conserverait potentiellement un
accès indirect. Pour `Rejected(EXTERNAL_IDENTITY_ALREADY_USED)`, seule l'égalité avec l'identité
créatrice est pertinente puisque cette Registration n'a créé aucun User. Ce constat fixe les preuves
métier requises sans choisir leur projection ou leur traduction HTTP.

## 7. Analyse de concurrence

### 7.1 Registration(E) contre Registration(E)

Pour deux `RegistrationRequest` distinctes, une seule transition peut acquérir le binding courant.
Elle crée son User et obtient `Registered(U)`. L'autre obtient
`Rejected(EXTERNAL_IDENTITY_ALREADY_USED)` et ne crée aucun User. Le résultat perdant ne révèle pas
`U`. Un simple get-or-create qui relit le binding après conflit violerait D2, D5, D13 et D23.

Pour deux exécutions de la même request, l'identité durable de la request et l'unicité de son résultat
terminal doivent faire converger vers le même résultat, sans rejouer l'intention comme nouvelle.
D20 exige au plus un résultat ; la distinction ne peut donc pas reposer uniquement sur `(issuer,
subject)`.

### 7.2 Registration(E) contre Attach(E)

Les deux opérations tentent d'établir le même binding naturel. Elles doivent utiliser la même
autorité transactionnelle. Celle dont la transition gagne fixe l'état courant ; l'autre observe un
conflit et applique sa propre issue métier. Aucune priorité Registration/Attach ne doit être codée
dans l'ordre de discovery, de worker ou de claim.

### 7.3 Registration(E) contre Detach(E)

Le résultat dépend de la sérialisation des transitions autoritatives :

- si Detach a effectivement supprimé le binding avant l'acquisition par Registration, E est libre et
  Registration peut réussir ;
- si Registration vérifie/acquiert alors que le binding existe encore, elle est rejetée ;
- une lecture préalable non verrouillée ou faite hors de la transition ne peut décider du résultat.

La disparition du binding ne supprime pas l'ancien User (D1, D8, D25) et ne laisse aucun registre
d'identité « libre » (D28).

### 7.4 Commit métier puis résultat non observable

Le cas dangereux est un commit de `User + binding` séparé du résultat terminal : un retry pourrait
alors prendre le binding créé par sa propre tentative pour l'identité « déjà utilisée » et produire
un rejet incohérent, ou créer un User orphelin avant un échec. D9, D20 et D25 imposent d'exclure cette
fenêtre en rendant la mutation et l'outcome autoritaire atomiques.

Après ce commit atomique, un retry recharge le résultat terminal immutable de la même request. Une
éventuelle propagation READ différée peut rendre le résultat temporairement non observable, mais ne
doit ni rouvrir l'intention ni déclencher une seconde mutation. Le fencing Consumption peut protéger
le commit gagnant comme il le fait pour Command ; il reste un mécanisme technique, pas l'état
fonctionnel exposé.

Ces règles rendent D3, D5, D9, D20, D24 et D25 conjointement satisfaisables. Le schéma actuel n'en
implémente que la clé concurrente de D3.

## 8. Événements

### 8.1 Inventaire existant

Deux familles durables existent :

1. les `BusinessEvent` Pot (`POT_CREATED`, mises à jour/suppressions Pot, Shareholder et Expense),
   qui exigent `potId` et `version` et sont persistés dans `business_event_outbox` ;
2. les terminal Events Command (`COMMAND_APPLIED`, `COMMAND_REJECTED`, `COMMAND_FAILED`) dans
   `command_terminal_events`, qui indiquent qu'un `command_outcome` terminal est disponible.

Aucun événement User/Identity n'existe. L'outbox Pot ne convient pas telle quelle : son contrat est
spécifique à un agrégat Pot versionné. Les terminal Events Command sont des notifications de
disponibilité d'outcome, pas des faits décrivant la mutation User/Identity demandée par D22.

### 8.2 Formes compatibles avec D22

Plusieurs vocabulaires restent légitimes :

- un fait combiné de Registration réussie, portant la création du User et son binding initial ;
- deux faits atomiquement enregistrés, l'un pour la création du User, l'autre pour l'attachement de
  l'ExternalIdentity ;
- un fait de création User et une représentation durable différente de la mutation de binding, si
  les consommateurs n'ont pas besoin d'un événement d'attachement.

Le choix dépend de la frontière d'agrégat et des besoins de consommateurs futurs. Dans tous les cas,
le rejet ne produit aucun fait de mutation User/Identity, et une identité ayant servi à Registration
ne reçoit aucun statut privilégié dans l'état courant (D7, D27). Le choix d'une famille d'outbox ou
de l'évolution du contrat d'Event est architecturalement ouvert ; le présent audit ne le fige pas.

## 9. Écarts avec le premier audit REGISTRATION

### 9.1 Confirmé

- `RegistrationRequest` doit être une intention durable distincte de Command et de Consumption.
- L'identité vient du principal authentifié et jamais du payload.
- `AuthenticatedExternalPrincipal` est déjà la bonne frontière provider-neutral après AuthN.
- `external_identities` garantit déjà l'unicité SQL de `(issuer, subject)` et autorise plusieurs
  identités par `pocoma_user_id`.
- Aucun modèle User canonique, port d'écriture identity ou use case Registration/Attach/Detach
  n'existe.
- L'emplacement des types d'identité dans `orchestrator-command-admission` est trop spécifique pour
  devenir leur ownership durable.
- L'intention ne doit pas recopier pending, claim, lease ou retry ; CCR fournit un précédent de
  séparation outcome fonctionnel / Consumption.

### 9.2 Précisé par D1–D30

- Le User est désormais défini : entité autonome minimale `{ PocomaUserId }`, y compris avec zéro
  identité. La question « une ligne external_identities suffit-elle ? » est close : non.
- Une authentification valide par un fournisseur accepté suffit au droit de demander sa propre
  Registration ; aucun scope ou User préalable n'est requis.
- Le seul rejet fonctionnel est `EXTERNAL_IDENTITY_ALREADY_USED`; les failures d'infrastructure ne
  sont pas des issues métier Registration.
- Le `PocomaUserId` naît pendant la transition autoritative, pas à l'admission.
- L'ownership du READ est plus strict que celui de `COMMAND_RESULT` : identité créatrice exacte et,
  en cas de succès, binding courant vers le User créé.
- La disponibilité ne se vérifie pas à l'admission ; l'acceptation ne préjuge pas du résultat.
- Attach et Detach sont hors du use case Registration mais partagent nécessairement son invariant
  global.

### 9.3 Invalidé

- La « répétition concurrente [qui] converge vers le même `PocomaUserId` » n'est vraie que pour le
  retry technique de la même `RegistrationRequest`. Deux requests distinctes ne convergent pas :
  l'une réussit, l'autre est rejetée.
- La primitive proposée `INSERT ... ON CONFLICT DO NOTHING; SELECT association` implémente un
  get-or-create et retournerait le User existant à une nouvelle intention. Elle est contraire à D2,
  D5 et D13.
- La proposition d'un « effet get-or-create » fenced est remplacée par une création ou un rejet
  autoritatif, corrélé à l'identité de la request.
- Les anciennes questions sur le caractère répétable de Registration, le comportement d'une identité
  déjà provisionnée, le scope d'inscription, le profil User et le catalogue de rejets sont closes
  par D1–D30.
- La proposition d'une issue fonctionnelle publique générique `FAILED` n'est pas compatible avec
  D13/D17 : une failure d'infrastructure demeure technique.

Les parties du premier audit relatives au rangement général de `docs/steps`, à POT_E2E ou à
l'autorité documentaire EPT/CCR sont orthogonales au cadrage métier Registration et ne sont pas
réévaluées ici.

## 10. Décisions déjà clôturables

Le cadrage permet de fermer sans autre décision métier :

- identité et autonomie du User (D1, D8, D11, D26) ;
- valeur et égalité opaque de l'ExternalIdentity (D6, D29, D30) ;
- cardinalités et absence d'identité principale (D3, D4, D7, D27, D28) ;
- sémantique création-versus-rejet et distinction request/retry (D2, D5, D13, D17, D20, D23) ;
- droit d'admission et sens de l'acceptation (D12, D18) ;
- atomicité et absence de compensation (D9, D15, D24, D25) ;
- séparation intention/opération/état/Consumption et visibilité fonctionnelle (D14, D16, D21) ;
- règles d'accès au résultat (D19) ;
- nécessité d'un fait métier durable au succès, sans encore figer son vocabulaire (D22).

## 11. Questions réellement encore ouvertes

Les décisions métier sont suffisamment précises. Trois choix architecturaux structurants restent à
fermer avant de déclarer le cadrage métier **et architectural** complet :

1. **Ownership canonique des types** : quel module possède `User`, `PocomaUserId`,
   `ExternalIdentity` et le concept de binding, et comment les `UserId` consommateurs existants s'y
   adaptent-ils sans dépendre de `engine-command` ?
2. **Formulation de la frontière de cohérence** : User aggregate root + binding root/registre sous
   une opération transactionnelle commune, ou binding relationnel protégé par un service/port
   atomique ; le User seul ne peut pas porter l'invariant global.
3. **Vocabulaire et famille d'événements** : fait combiné ou faits `UserCreated` et
   `ExternalIdentityAttached`, et frontière durable capable de porter ces faits hors du contrat Pot.

Ces questions ne rouvrent aucune décision D1–D30 et ne concernent ni HTTP, ni worker, ni lease, ni
projection, ni migration finale. Elles doivent néanmoins être tranchées pour éviter que
l'implémentation choisisse implicitement le modèle de domaine à travers ses tables.

## 12. Verdict

**NOT CLOSABLE** pour le cadrage combiné métier et architectural demandé.

Le cadrage métier D1–D30 est cohérent et clôturable. Le repository ne le contredit pas, mais il ne
possède pas encore le domaine User/Identity autonome nécessaire. L'ownership canonique, la
formulation de la frontière de cohérence et le vocabulaire événementiel restent les trois décisions
architecturales indispensables avant un canon ou un plan d'implémentation.
