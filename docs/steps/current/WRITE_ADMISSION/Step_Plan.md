# WRITE_ADMISSION — Plan d’implémentation

```text
Step: WRITE_ADMISSION
Phase: IMPLEMENTATION PLANNED
Authority: Step_Canon.md — WA1–WA11
Sequencing: WA.1 → WA.2 → WA.3 → WA.4 → WA.5 → WA.6 → WA.6A → WA.6B → WA.6D → WA.6C → WA.6E → WA.6F → WA.6G → WA.7 → WA.8
Strategy: expand → consume → produce → read → contract
```

## 1. Baseline et portée

- branche : `v2-make-it-pull` ;
- HEAD local et distant au cadrage WA.6 : `bbb794cf7eaf533bc8ca1710a3dd0c8f400ce94a` ;
- divergence : `0/0` ;
- working tree initial au cadrage WA.6 : uniquement `WA6_Audit.md` non suivi ;
- autorité normative : [`Step_Canon.md`](Step_Canon.md), WA1–WA11 ;
- audits factuels : [`step_audit.md`](step_audit.md) et
  [`WA6_Audit.md`](WA6_Audit.md) pour le cadrage détaillé de WA.6 ;
- audit d'alignement du canon final :
  [`Binding_Canon_Alignment_Audit.md`](Binding_Canon_Alignment_Audit.md), verdict `PASS WITH GAPS`.

Ce plan ferme l’écart transversal entre l’admission Command actuelle et le canon WRITE_ADMISSION.
Il prépare les fondations User/Identity dont REGISTRATION dépend, migre Command sans big-bang et
termine par la suppression des représentations legacy. Il ne modifie aucun canon.

La stratégie est obligatoire :

```text
expand   : introduire les types, colonnes, contraintes et contrats cibles sans casser l’existant
consume  : rendre le worker capable de consommer et d’arbitrer la représentation cible
produce  : faire produire uniquement la représentation cible par l’admission HTTP
read     : basculer les résultats et le self-service vers des projections exclusivement READ
contract : drainer/supprimer les représentations, ports et colonnes legacy
```

Chaque lot est committable, garde le reactor vert et conserve une stratégie explicite pour toute
Command déjà enregistrée. Aucun lot ne peut inventer une ExternalIdentity ou un BindingId pour une
envelope historique qui ne les contient pas.

## 2. Invariants transverses

- Le POST authentifie, valide la structure, capture durablement l’intention et retourne `202` après
  commit ; il ne lit ni WRITE primaire ni READ pour prendre une décision métier.
- Toute ExternalIdentity authentifiée peut déposer une Command structurellement valide.
- Une Command cible capture exactement `ExternalIdentity E`, `BindingId B`, le payload et l’évidence
  d’authentification minimale ; elle ne capture pas un User résolu à l’admission.
- B absent ou mal formé est un rejet structurel synchrone sans row durable. B bien formé mais faux,
  ancien, absent ou détaché est admis puis produit uniquement `CALLER_IDENTITY_NOT_CURRENT`.
- Le worker résout autoritativement `(E,B) -> U`, évalue les capabilities et l’AuthZ, puis maintient
  B courant jusqu’au commit de la mutation métier dans la transaction fenced.
- Chaque occurrence de binding reçoit un B opaque, unique et jamais réutilisé.
- `BindingRevision R` appartient à E et ordonne ses mutations ; elle peut servir de version interne
  au CAS, mais la Command ne la porte jamais et son fence métier reste `(E,B)`.
- Les endpoints GET lisent exclusivement READ/projections, jamais les tables WRITE, les outcomes
  primaires ou Consumption.
- La projection self-service est strictement indexée par l’ExternalIdentity authentifiée et n’est
  jamais consommée par un worker WRITE.

## 3. Lots d’implémentation

### WA.1 — Ownership User/Identity et contrats neutres

**Objectif.** Installer les fondations canoniques partagées par WRITE_ADMISSION et REGISTRATION,
sans changer encore le comportement runtime.

**Changements.** Créer ou stabiliser l’ownership neutre de `User`, `PocomaUserId`,
`ExternalIdentity`, `BindingId` et des ports User/Identity. Les faits de lifecycle du binding ne sont
pas créés par ce lot et sont explicitement planifiés en WA.6.
Déplacer `AuthenticatedExternalPrincipal` dans une frontière d’authentification provider-neutral.
Conserver `domain-pot.value.UserId` comme référence locale adaptée explicitement à la frontière.
Supprimer toute seconde définition canonique dans le même lot.

**Preuves.** Value objects, égalité exacte issuer/subject, UUID opaque, dépendances Maven et guards
ArchUnit : User/Identity ne dépend ni de Command, ni de Pot, ni de Registration, ni d’un runtime.
Scan des anciens FQCN et reactor complet vert.

**DONE.** Il existe une définition canonique unique de chaque type et aucun cycle de modules.

**Résultat WA.1 — DONE (2026-10-01).**

- commit : `refactor: establish user identity ownership` ; baseline : `60a8ea5121e6d274d1989245682435c47aa8d997` ;
- owner métier : `pocoma-domain-user-identity` pour `User`, `PocomaUserId`,
  `ExternalIdentity`, `BindingId` et le port de résolution legacy conservé jusqu’au cutover ;
- frontière AuthN : `pocoma-authentication-contracts` possède
  `AuthenticatedExternalPrincipal`, garde les autorités externes sous forme attestée et ne dépend
  d’aucune `Permission` métier ;
- compatibilité : `domain-pot.value.UserId`, `AuthorizationSnapshot` et la traduction legacy des
  autorités restent en place ; le comportement Command est inchangé ;
- preuve value objects : `UserIdentityModelTest` couvre UUID opaque, égalité par valeur, rejet du
  null, absence d’ordre et invariants exacts issuer/subject ;
- preuve architecture : `HexagonalArchitectureTest` verrouille l’owner unique, l’absence de doublon,
  la dépendance JDK-only du domaine et l’absence de Spring Security, Keycloak, JPA ou `Permission`
  dans les contrats neutres ;
- preuves d’exécution : tests ciblés des modules touchés puis reactor Maven complet, tous verts ;
- critères de sortie : aucune migration SQL, aucun changement de schéma ou DTO HTTP, aucune
  implémentation de binding persistant, de fait Attached/Detached, d’endpoint ou de WA.2+.

### WA.2 — Expand de l’autorité de binding

**Prérequis.** WA.1.

**Objectif.** Étendre l’autorité persistante courante vers `Binding(E,U,B)` tout en préservant les
données et consommateurs existants.

**Changements.** Introduire la table `users` si nécessaire, backfiller les UUID User autoritatifs,
ajouter `binding_id` à `external_identities`, générer un UUID distinct pour chaque binding courant,
poser les contraintes d’unicité/FK/index et exposer les ports de résolution exacte. Les writers de
binding génèrent désormais un B neuf ; les readers legacy restent temporairement disponibles.

**Preuves.** Migration vide et prépeuplée, zéro perte d’UUID, B non null et unique, plusieurs E vers
U, aucun E vers plusieurs U, User sans binding, résolution exacte `(E,B)`, B faux/ancien absent et
backfill reproductible sur les fixtures historiques.

**DONE.** L’autorité primaire peut servir simultanément les anciens consumers et les contrats
cibles, sans faire de B une version ordinale ni créer de registre d’identités libres.

**Résultat WA.2 — DONE (2026-10-01).**

- commit : `feat: establish user identity persistence` ; baseline WA.1 :
  `fd08cd281d8c938460e42cb91c037aa40d70587d` ;
- migration : `V18__user_identity_binding_authority.sql` crée `users(user_id UUID PK)`, backfille
  chaque UUID distinct de V9, enrichit `external_identities` avec `user_id` et `binding_id UUID NOT
  NULL`, conserve la PK `(issuer, subject)`, rend B globalement unique et pose la FK
  `Binding -> User ON DELETE RESTRICT` ;
- primitives : `UserAuthorityPort.create/findById`, resolver legacy `findUserId(E)`,
  `ExternalIdentityBindingPort.findUserId(E,B)`, `lockCurrentBinding(E,B)`, `acquire(E,U,B)` et
  `detach(E,B)` ; tous les adapters exigent et rejoignent la transaction appelante ;
- arbitrage : `acquire` utilise l’insert PostgreSQL `ON CONFLICT (issuer,subject) DO NOTHING` et ne
  retourne que `ACQUIRED` ou `CONFLICT` ; `detach` conditionne le DELETE sur E+B ;
- preuves PostgreSQL : migration vide et V17 prépeuplée, UUID historiques et resolver legacy
  conservés, contraintes finales/FK, B faux, B unique, User inconnu, concurrence acquire avec un
  seul gagnant, detach stale après reattach et blocage réel d’un detach concurrent jusqu’au commit
  de la transaction tenant `lockCurrentBinding` ;
- preuves de non-régression : admission/consommation Command et E2E existants verts, guard HTTP sans
  accès direct aux adapters/repositories User/Identity, tests d’architecture et reactor Maven
  complet verts ;
- périmètre : aucun changement Command/RecordedCommand, aucun fait ou projection User/Identity,
  aucun endpoint, aucune Registration et aucune implémentation WA.3+.

### WA.3 — Expand de l’envelope Command et compatibilité historique

**Prérequis.** WA.2.

**Objectif.** Rendre la persistence Command capable de porter l’envelope cible avant son émission.

**Changements.** Ajouter à `recorded_commands` l’ExternalIdentity complète, le BindingId et
l’évidence d’authentification nécessaire au worker. Versionner explicitement l’envelope si les rows
legacy doivent coexister. Définir une règle sûre pour les Commands antérieures : drain préalable,
chemin legacy borné ou migration prouvable. Aucun `subject` ni B ne peut être synthétisé.

**Preuves.** Round-trip target/legacy, contraintes de forme, absence de JWT brut, reprise après
redémarrage et discovery inchangée. Un test de migration démontre le traitement exhaustif des rows
existantes avant toute future contrainte `NOT NULL`.

**DONE.** Le store accepte la représentation cible et chaque row historique possède un chemin de
traitement explicite.

**Résultat WA.3 — DONE (2026-10-01).**

- baseline WA.2 : `3a8a27ca9ff75298f0ad273bb020d329428d5e45` ; commit du lot :
  `feat: expand recorded command envelopes` ;
- audit de départ : `RecordedCommand` portait uniquement l'`AuthorizationSnapshot` V1
  (`auth_user_id`, issuer, temps et permissions déjà traduites), V8 ne stockait ni subject ni
  `binding_id`, le POST construisait ce V1 après résolution E→U et le worker rechargeait puis
  consommait ce même snapshot ; discovery restait indexée uniquement par
  `(submitted_at, command_id)` ;
- représentation Java : `RecordedCommandEnvelope` est scellée entre
  `AuthorizationSnapshot`/`LEGACY_V1` et `TargetCommandEnvelope`/`TARGET_V2` ; V2 contient exactement
  `ExternalIdentity(issuer,subject)`, `BindingId` et `CommandAuthenticationEvidence` ; le
  constructeur V1 et l'accesseur d'exécution legacy restent disponibles pour la compatibilité
  source et binaire du runtime courant ;
- évidence AuthN V2 : uniquement les autorités externes attestées provider-neutral requises pour
  la future traduction au worker et `validUntil` requis par son contrôle d'expiration ; ni JWT brut,
  ni `PocomaUserId`, ni permissions métier traduites, ni claims temporels inutilisés ne sont
  persistés dans V2 ;
- migration : `V19__recorded_command_envelope_expand.sql` ajoute `envelope_version SMALLINT NOT
  NULL DEFAULT 1`, `auth_subject`, `binding_id` et `auth_external_authorities_json`, rend seulement
  les colonnes exclusives V1 nullables et impose par contraintes deux formes disjointes et
  exhaustives ; le default V1 permet à un binaire producteur WA.2 de continuer à écrire pendant
  l'expand ;
- stratégie historique : chaque row antérieure reçoit explicitement `envelope_version=1` par la
  migration ; les colonnes V2 restent nulles et aucune ExternalIdentity, aucun subject, aucun B et
  aucune évidence cible ne sont synthétisés ; le mapper choisit exclusivement par discriminateur,
  rejette toute version inconnue et ne contient aucune heuristique de nullabilité ;
- persistence : l'adapter écrit et relit exactement les deux variantes ; un V2 complet survit à la
  reconstruction de l'adapter/reload, tandis qu'une row V1 historique reste lisible par le chemin
  legacy et processable par le worker existant ; payload, type, id et dates existants restent
  inchangés ;
- preuves migration/store : bootstrap V1→V19, validation Flyway, upgrade V18 prérempli, conservation
  exacte d'une row historique, contraintes rejetant les formes partielles/croisées, round-trips V1
  et V2, absence de colonne JWT, ordre/discovery et transactions du store ;
- preuves de non-régression : modèle/mapper, admission PostgreSQL actuelle, worker PostgreSQL
  mono-worker et multi-worker, E2E Command/COMMAND_RESULT, démolition/bootstrap de schéma et les 50
  guards `HexagonalArchitectureTest`, puis reactor Maven complet, tous verts ;
- périmètre : aucun changement de DTO ou comportement HTTP, aucune production V2 par le POST,
  aucune résolution `(E,B)->U`, aucun traitement métier V2, aucune évolution AuthZ ou READ, aucune
  suppression legacy, aucun changement de canon et aucune implémentation WA.4+.

### WA.4 — Consume : résolution et continuité transactionnelles au worker

**Prérequis.** WA.3.

**Objectif.** Faire de l’exécution fenced l’unique frontière de résolution métier et d’AuthZ.

**Changements.** Après reload, résoudre autoritativement `(E,B) -> U`, traduire/évaluer les
capabilities au traitement, puis exécuter la mutation métier dans une frontière PostgreSQL qui
maintient l’occurrence B courante jusqu’au commit. Choisir et documenter la primitive réelle : row
lock partagé avec Attach/Detach, conditional write ou équivalent prouvé. Mapper tout mismatch sur
`CALLER_IDENTITY_NOT_CURRENT` sans autre lookup révélateur.

**Preuves.** Identité inconnue, aucun binding, B faux, B ancien, detach, reattach même/autre U : un
seul rejet public. Deux transactions réelles prouvent l’absence de fenêtre TOCTOU avec Detach.
Claim perdu, exception métier et failure d’append rollbackent la mutation. Les policies Pot restent
évaluées avec U et l’état primaire courants.

**DONE.** Le worker cible n’utilise plus le User figé à l’admission et protège WA5, WA8 et WA11.

**Résultat WA.4 — DONE (2026-10-01).**

- le reload de la Command prépare localement un contexte d’exécution `LEGACY_V1` ou `TARGET_V2` ;
  V1 conserve son snapshot historique, tandis que V2 résout exclusivement l’occurrence exacte
  `(ExternalIdentity, BindingId)` par `lockCurrentBinding(E,B)` et traduit l’évidence AuthN au worker ;
- le `SELECT ... FOR UPDATE` exact est acquis dans la transaction fenced portée par
  `TransactionalExecuteConsumptionUseCase` ; cette même transaction couvre reload, résolution,
  policies sur l’état primaire courant, mutation métier, append Event, résultat durable et
  finalisation CAS du claim, de sorte que le lock reste détenu jusqu’au commit métier ;
- toute absence de l’occurrence exacte — identité inconnue, B faux/ancien/détaché, reattach vers le
  même U ou un autre U — produit uniquement `CALLER_IDENTITY_NOT_CURRENT`, sans second lookup et sans
  fallback `findUserId(E)` ;
- les tests PostgreSQL prouvent le blocage d’un detach concurrent jusqu’au commit, le rejet lorsque
  le detach précède le lock, les deux formes de reattach, ainsi que le rollback de la mutation et la
  libération du lock sur échec technique ; les preuves existantes de claim/fence, append et
  multi-worker restent vertes ;
- les guards empêchent l’ajout de U/Permission dans `TargetCommandEnvelope`, le fallback legacy, les
  dépendances READ/provider-specific du worker et la résolution de binding dans l’admission HTTP ;
- périmètre : comportement HTTP et production POST V1 inchangés, aucune implémentation WA.5+, aucune
  modification de `Step_Canon.md`. Tests ciblés, PostgreSQL, architecture, reactor pertinent et
  reactor Maven complet : verts.

### WA.5 — Produce : admission ouverte et sans lecture primaire

**Prérequis.** WA.4 déployable et WA.3 compatible.

**Objectif.** Faire produire l’envelope cible par `POST /api/v1/commands`.

**Changements.** Recevoir B dans le contrat HTTP, le valider uniquement en forme, capturer E et
l’évidence attestée depuis le principal, persister la Command et retourner `202`. Retirer de
l’orchestrateur d’admission la résolution E→U et toute traduction anticipée des droits métier.

**Preuves.** E connue ou inconnue + B syntaxiquement valide → même `202` et row durable ; B absent ou
mal formé → 4xx et aucune row ; instrumentation/guard prouvant zéro SELECT primaire ; aucun decoder,
dispatcher, use case Pot, Event ou Consumption appelé dans la requête HTTP.

**DONE.** Toute nouvelle Command est produite au format cible et l’admission satisfait WA1–WA7.

**Résultat WA.5 — DONE (2026-10-01).**

- baseline WA.4 : `8f65eab266f6a9938f485bd4d8a93db7eb20cf48` ; commit du lot :
  `feat: produce target command envelopes` ;
- contrat HTTP : le body JSON de `POST /api/v1/commands` ajoute `bindingId`, chaîne UUID explicite
  désérialisée en `UUID` puis construite en `BindingId` opaque ; l’endpoint, `commandType`, `payload`,
  la réponse `202` et le commit synchrone de la row restent inchangés ;
- production : le POST construit exclusivement `TargetCommandEnvelope/TARGET_V2` avec
  l’`ExternalIdentity(issuer,subject)` exacte du principal, le B fourni par le client, le payload et
  `CommandAuthenticationEvidence` ; aucun champ client ne permet de choisir E ;
- admission : suppression de `findUserId(E)`, du port resolver et du rejet
  `USER_NOT_PROVISIONED` dans ce chemin ; suppression de la construction
  d’`AuthorizationSnapshot` et de la traduction anticipée authorities → `Permission` ;
- évidence AuthN : seules les authorities externes attestées provider-neutral et
  `validUntil=min(token.exp, submittedAt+TTL)` sont persistées ; aucun token/JWT, User résolu,
  permission métier, `authenticatedAt`, `issuedAt` ou claim supplémentaire n’est écrit en V2 ;
- admission ouverte : E inconnue et B UUID valide, ainsi que B faux, ancien ou détaché, suivent tous
  `insert TARGET_V2 → commit → 202` ; B absent, vide ou non UUID produit `400` sans row durable ;
- preuve zéro lecture primaire : le test HTTP PostgreSQL capture les statements serveur entre deux
  marqueurs autour du POST et exclut tout SELECT User/Identity, Pot, Event, Consumption ou READ ; il
  vérifie aussi l’absence d’effet synchrone. Les guards interdisent en complément resolver/binding
  ports, adapters/repositories Identity, READ et use cases Pot depuis HTTP/admission ;
- compatibilité WA.4 : un E2E PostgreSQL prouve `POST TARGET_V2 → worker → AuthZ → mutation/outcome`
  pour B courant ; un second prouve `POST 202` avec B stale puis
  `CALLER_IDENTITY_NOT_CURRENT`, sans mutation métier ;
- legacy : le POST ne produit plus V1 ; le modèle, le mapper, les colonnes et le consumer
  `LEGACY_V1` restent présents, et l’E2E historique recharge/consomme encore des rows V1 ; aucune
  migration destructive n’est ajoutée ;
- validations : tests unitaires admission/HTTP, intégrations HTTP et Command PostgreSQL, worker
  mono/multi-runtime, E2E WA.5, 51 guards d’architecture, reactor pertinent et reactor Maven complet
  exécutés avec succès ;
- hors périmètre conservé : aucune WA.6+, aucune évolution de `COMMAND_RESULT`, aucun endpoint de
  binding, aucune Registration/Attach/Detach, aucun changement du lock ou des policies WA.4, aucune
  suppression des colonnes ou du consumer V1, aucune modification de `Step_Canon.md`.

### WA.6 — READ : résultat Command et binding self-service

**État du cadrage : CLOSED.** Le présent plan applique les conclusions de
[`WA6_Audit.md`](WA6_Audit.md). Il ne modifie pas le canon et ne laisse aucune question bloquante.

**Prérequis.** WA.5 ; autorité User/ExternalIdentity/Binding et protections concurrentes WA.2 ;
consommation V2 transactionnelle WA.4. Contrairement à l’hypothèse de l’ancien plan, WA.1–WA.2
n’ont pas créé de faits de lifecycle : WA.6 les introduit.

#### Décisions figées pour tout le lot

- Dans l'état livré WA.6, `external_identities(issuer, subject, user_id, binding_id)` porte la
  row active et `external_identity_binding_streams` porte R même après detach. Ensemble, ils
  constituent l'autorité composite explicitée pour la cible en WA.6A. Le journal de
  faits et `CURRENT_BINDING` sont respectivement la trace durable des mutations et une projection ;
  aucun des deux n’est un second store de décision WRITE.
- L’ordre est une `binding_revision BIGINT` monotone **par** `(issuer, subject)`. `binding_id` reste
  opaque, non ordonné et n’est jamais comparé pour établir la fraîcheur.
- Toute ligne V18 déjà attachée reçoit l’état de stream `revision = 0`. Aucun fait historique n’est
  inventé. La première mutation réelle réussie reçoit la révision `1`, puis `n + 1`.
- Une mutation réussie effectue, dans une transaction PostgreSQL unique : verrou du stream de E,
  contrôle/mutation de l’autorité, allocation de la révision, append du fait correspondant. Une
  transaction en conflit ou un detach stale n’avance pas la révision et n’émet aucun fait.
- `COMMAND_RESULT` conserve deux politiques de visibilité distinctes. V1 reste autorisé par le
  `userId` historique ; V2 est visible par l’ExternalIdentity exacte capturée dans la Command. E est
  ici une clé de visibilité immuable, pas le propriétaire métier du résultat et pas une copie de
  l’autorité de binding.
- Pour V2, ni `bindingId`, ni `bindingRevision`, ni un `userId` résolu ne sont nécessaires à la
  visibilité du résultat : les figer ferait dépendre une permission historique du lifecycle futur.
  Le couple exact `auth_issuer/auth_subject`, déjà durable dans `RecordedCommand V2`, est suffisant.

#### WA.6.1 — Contrats de lifecycle et schéma WRITE extensif

**Objectif.** Donner à chaque E un stream ordonné et rendre chaque mutation réelle reconstructible.

**Fichiers/modules probables.** `domain-user-identity` pour `BindingRevision`,
`ExternalIdentityAttached` et `ExternalIdentityDetached` ; `infra-persistence-jpa` pour les ports et
adapters JDBC, les entités/rows éventuelles et la migration Flyway suivant V19 ; tests de migration
dans ce même module. Les records de domaine restent framework-free et JDK-only.

**Modèle/SQL.** Ajouter une table primaire de stream, par exemple
`external_identity_binding_streams(issuer, subject, current_revision)`, clé primaire `(issuer,
subject)`, `current_revision >= 0`. Ajouter un journal append-only, par exemple
`external_identity_binding_facts(event_id, issuer, subject, binding_revision, fact_type, user_id,
binding_id, recorded_at, partition_hash)`, avec PK `event_id`, unicité `(issuer, subject,
binding_revision)`, FK/contraintes de forme et `binding_revision >= 1`. `Attached` porte au minimum
`E, userId, bindingId, revision, recordedAt, eventId`; la forme `Detached` sans U décrite ici est
**l'état livré en WA.6.1**, corrigé par WA.6B–WA.6D ci-dessous. La clé de partition est dérivée de E.

**Runtime/transaction.** Aucun polling n’est activé dans cette sous-étape. La migration crée une row
de stream à `0` pour chaque E V18 actuellement attachée, avec `INSERT ... ON CONFLICT DO NOTHING` ;
elle ne crée aucune row de fait.

**Invariants.** Une seule séquence locale existe par E ; la révision `0` signifie exclusivement
« état hérité/bootstrap » ; tout fait réel a une révision strictement positive ; les contraintes
interdisent un fait partiel, une révision dupliquée ou une variante inconnue.

**Tests/preuves.** Tests unitaires des value objects/faits ; Testcontainers de bootstrap V18→nouvelle
migration, schéma vide, migration rejouée par Flyway, contraintes et unicité ; preuve qu’aucun fait
n’est créé pour les rows V18.

**Clôture.** Le schéma extensif est déployable avant tout writer et permet d’ordonner durablement les
mutations d’une E sans compteur global.

**Résultat WA.6.1 — DONE (2026-10-01).**

- baseline : `de74108fdb94352f0bab7206083a82db72801d9a` ; commit dédié :
  `feat: establish external identity binding lifecycle schema` ;
- migration : `V20__external_identity_binding_lifecycle.sql` crée les tables WRITE extensives
  `external_identity_binding_streams` et `external_identity_binding_facts`, sans trigger et sans
  modifier `external_identities`, qui reste l’unique autorité du binding ;
- contrats : `BindingRevision` accepte `0` pour le bootstrap, rejette les valeurs négatives et reste
  locale à une E ; `ExternalIdentityAttached` porte eventId, E, U, B, révision positive et
  recordedAt ; `ExternalIdentityDetached` porte eventId, E, B, révision positive et recordedAt,
  sans User inventé ;
- persistence : rows, mapper, repositories JDBC et ports/adapters transactionnels non encore câblés
  représentent la création idempotente d’un stream à `0`, sa lecture et l’append des deux formes de
  faits ; aucune primitive de lock, allocation ou incrément de révision n’est introduite ;
- schéma : stream clé `(issuer,subject)` avec révision `>= 0` ; journal clé `event_id`, unicité
  `(issuer,subject,binding_revision)`, révision `>= 1`, FK vers le stream et User Attached, formes
  `ATTACHED`/`DETACHED` disjointes, `binding_id` obligatoire, `partition_hash` dérivé de E et indexé
  pour le futur discovery ;
- bootstrap : chaque E héritée de V18/V19 reçoit exactement un stream à révision `0` via
  `INSERT ... ON CONFLICT DO NOTHING` ; U et B autoritatifs restent inchangés et aucun fait
  historique synthétique n’est créé ; plusieurs E possèdent chacune leur propre révision `0`, sans
  compteur global ;
- preuves : unitaires domaine et mapper ; PostgreSQL/Testcontainers sur base vide, upgrade V19
  prérempli, idempotence du bootstrap, contraintes de révision/unicité/type/formes/FK, persistence
  des deux variantes et absence de mutation de l’autorité ; preuve explicite que les writers
  `acquire/detach` existants ne créent ni stream ni fait ; migrations Flyway, architecture et
  reactor pertinent `architecture-tests -am` verts ; reactor Maven complet vert ;
- limites : aucun writer Attach/Detach modifié, aucune allocation `nextRevision`, aucun consumer,
  aucune Consumption de binding fact, aucune projection READ, aucun `CURRENT_BINDING`, aucun job de
  bootstrap READ, aucun endpoint et aucune implémentation WA.6.2+ ; `Step_Canon.md` inchangé.

#### WA.6.2 — Mutation atomique Attach/Detach, allocation et append

**Objectif.** Faire de la mutation d’autorité et de son fait une seule unité atomique.

**Fichiers/modules probables.** `domain-user-identity` (`ExternalIdentityBindingPort`, résultats
d’acquisition/détachement enrichis si nécessaire), `infra-persistence-jpa`
(`JpaExternalIdentityBindingAdapter`, `ExternalIdentityJdbcRepository`, nouveaux adapters de stream
et journal), et la couche app/use case qui possède les transactions Attach/Detach. Aucun caller de
production ne doit muter directement la table d’autorité.

**Modèle/SQL.** Pour une E nouvelle, créer le stream à `0` par upsert, puis le verrouiller
`FOR UPDATE`. Pour une E existante, verrouiller directement la même row. Après mutation autoritative
réussie, calculer `next = current + 1`, mettre à jour le stream et insérer le fait avec `next`. Un
overflow est une erreur technique fermée. `Attached` enregistre exactement U et B écrits ;
`Detached` enregistre exactement B supprimé.

**Runtime/transaction.** L’application Attach/Detach ouvre l’unique transaction ; les adapters
restent `Propagation.MANDATORY`. Ordre de lock obligatoire pour tous les writers : stream E, puis row
autoritative E. Attach : lock → contrôle de conflit → insertion d’autorité → incrément → append.
Detach : lock → `DELETE ... WHERE E AND binding_id = B` → si une row est supprimée, incrément et
append ; sinon `NOT_CURRENT`, sans incrément/fait. Commit ou rollback couvre les cinq opérations.
Le lock WA.4 sur l’autorité bloque alors le detach jusqu’au commit métier de la Command ;
WA.6C remplace explicitement ce comportement historique.

**Invariants.** Aucun état d’autorité committé ne peut manquer son fait ; aucun fait ne décrit une
mutation rollbackée ; deux writers de la même E sont sérialisés ; des E différentes restent
indépendantes ; `Detached(B1)` stale après `Attached(B2)` conserve B2 et n’émet rien.

**Tests/preuves.** Unitaires des résultats et de l’absence d’append sur échec ; PostgreSQL à deux
transactions pour attach/attach, attach/detach, detach/detach et Command/detach ; rollback forcé
entre mutation et append ; concurrence multi-thread prouvant les révisions contiguës des seules
mutations réussies ; reprises des tests stale-detach WA.2 et continuité WA.4.

**Clôture.** Tous les chemins de mutation autorisés passent par cette frontière et une inspection
PostgreSQL montre exactement un fait à la révision suivante pour chaque mutation committée.

**Résultat WA.6.2 — DONE (2026-10-01).**

- `JpaExternalIdentityBindingAdapter` conserve la transaction appelante obligatoire et exécute,
  pour Attach comme pour Detach, l'ordre unique `stream E FOR UPDATE → authority row E` ; la
  création idempotente du stream à `0`, la mutation de `external_identities`, l'allocation
  `Math.addExact(current_revision, 1)`, l'update compare-and-set du stream et l'append du fait sont
  dans le même commit PostgreSQL ;
- Attach committé produit exactement un `ExternalIdentityAttached(eventId,E,U,B,revision,recordedAt)` ;
  Detach supprime exclusivement `(E,B)` et produit exactement un `ExternalIdentityDetached` sur
  succès. `CONFLICT` et `NOT_CURRENT`, notamment le stale detach WA.2 après rebind, ne changent ni
  révision ni journal ;
- `external_identities(issuer,subject,user_id,binding_id)` reste l'unique autorité WRITE ; streams
  et facts n'introduisent aucune décision métier concurrente ;
- les preuves PostgreSQL couvrent attach/detach, reattach, conflits, attach/attach,
  attach/detach et detach/detach concurrents, continuité des révisions, unicité du fait, overflow,
  rollback forcé sur append et continuité WA.4 : un lock Command exact E+B bloque le detach jusqu'au
  commit métier.

#### WA.6.3 — READ model `CURRENT_BINDING` et règle d’apply

**Objectif.** Exposer l’état courant déterministe d’une E, y compris son détachement explicite.

**Fichiers/modules probables.** Nouveau moteur User/Identity READ dédié (module du type
`engine-user-identity-read`) ou extension strictement bornée de `engine-read-projection` ;
`infra-read-persistence` pour l’adapter JDBC et sa migration READ ; un locator/runtime dédié calqué
sur `runtime-latest-known-version-consumption-worker`, le Consumption générique et ses métriques.

**Modèle/SQL.** Table `pocoma_read.current_external_identity_binding`, clé naturelle `(issuer,
subject)`, colonnes `binding_revision`, `binding_status` (`ATTACHED|DETACHED`), `user_id`,
`binding_id`, `source_event_id`, `projected_at`. La tombstone avec ancien `binding_id` décrite ici
est **l'état livré en WA.6.3** ; WA.6E la migre vers `user_id=NULL, binding_id=NULL`.
Révision `>= 0` ;
`source_event_id` nul uniquement pour un bootstrap révision `0`.

**Discovery/Consumption.** Réutiliser le locator pull canonique de Command/LKV, sans watermark global
persisté. La clé de Consumption est
`(consumableType=IDENTITY_BINDING_FACT, event_id;
consumerType=CURRENT_BINDING_PROJECTOR)` ; son unicité est celle de `consumption_slots`. La query
PostgreSQL scanne, par pages bornées, les rows append-only de `external_identity_binding_facts` du
segment courant qui n’ont pas de slot `DONE`, ou dont le slot `PENDING` est à nouveau éligible et
sans claim vivant. `partition_hash` est calculé et persisté depuis l’E exacte ; le prédicat modulo
canonique assigne chaque fait à exactement un segment pour une configuration donnée. Il sert au
routage, jamais de position durable.

L’ordre SQL `(issuer, subject, binding_revision)` et son index ne servent qu’à la pagination keyset
d’une invocation de recherche. Le curseur est en mémoire, local à `openSearch`, jamais persisté, et
repart de vide après chaque exécution acquise ainsi qu’au poll/restart suivant. `recorded_at` reste
une donnée d’observabilité ; `event_id` reste une identité opaque ; aucun des deux n’est un
watermark. Les queries de discovery sont de courtes transactions `READ COMMITTED`, pas une longue
snapshot : chaque nouvelle query voit toutes les rows alors committées.

**Preuve anti-skip.** La propriété SQL décisive est « row append-only committée + absence durable
d’un slot `DONE` pour sa clé » : une row non encore consommée reste sélectionnable à chaque scan
repartant de zéro. Si une transaction committe une row dont la clé de tri précède le curseur
éphémère courant, elle peut être absente de cette page mais réapparaît au prochain `openSearch` ;
aucun high-water mark ne peut la masquer définitivement. Deux workers peuvent découvrir le même
fait, mais l’unicité de `consumption_slots`, le claim CAS, le lease et la finalisation fenced donnent
un seul effet committé. Un retry technique conserve le slot `PENDING` avec `next_claim_at`; un claim
perdu redevient éligible après expiration ; restart et changement de workers reconstruisent le scan
depuis la table durable. Les défaillances d’infrastructure suivent une politique `RetryAfter` sans
terminalisation par compteur, comme `ProjectionTaskRetryPolicy`; seules une donnée durable
introuvable/corrompue ou une collision égale/divergente deviennent des échecs terminaux observables.

**Runtime/transaction.** Après acquisition canonique, le consumer direct recharge le fait par
`event_id`, applique `CURRENT_BINDING`, écrit la provenance et finalise le claim dans la même
transaction fenced. L’upsert compare exclusivement la révision : supérieure → remplace ; inférieure
→ stale/no-op ; égale et contenu identique → duplicate/no-op ; égale et contenu divergent →
invariant failure observable, jamais overwrite. Une arrivée hors ordre `B2@3` avant `B1@1/2` reste
B2.

**Invariants.** Tout `ExternalIdentityAttached`/`ExternalIdentityDetached` committé reste découvrable
par le consumer, indépendamment de l’ordre dans lequel des transactions concurrentes ont commencé,
alloué leurs données puis committé. La projection n’écrit jamais l’autorité et le worker Command ne
la consulte jamais ; un detach reste observable ; un replay/duplicate/stale message ne régresse pas
l’état ; l’ordre ne dépend jamais de B.

**Tests/preuves.** Unitaires de la machine d’apply ; PostgreSQL pour create/update/tombstone,
duplicate identique, collision divergente, inférieur, saut de révision et concurrence. Test
Testcontainers anti-skip obligatoire : T1 insère F1 pour E1 dans une transaction non committée ; T2
insère et committe F2 pour E2, choisi dans le même segment et après F1 dans l’ordre keyset ; le
consumer découvre/finalise F2 ; T1 committe ensuite F1 ; un nouvel `openSearch` sans curseur retrouve
F1 et le projette. Le test inspecte les deux slots/provenances `DONE` et l’état final des deux E. Une
variante prouve que deux faits d’une même E ne peuvent pas reproduire cette inversion car le verrou
de stream les sérialise. Couvrir aussi page pleine, row tardive classée avant le dernier curseur,
retry, restart, claim perdu, redistribution de segments et multi-worker ; métriques backlog/failure.

**Clôture.** Les séquences `Attach(B1) → Detach(B1) → Attach(B2)` et tous leurs replays/permutations
autorisées convergent vers `ATTACHED/B2` à la révision maximale.

**Résultat WA.6.3 — DONE (2026-10-01).**

- la migration READ `V9__current_external_identity_binding.sql` crée
  `pocoma_read.current_external_identity_binding`, clé `(issuer,subject)`, avec
  `binding_revision`, `binding_status`, `user_id` nullable seulement pour `DETACHED`, `binding_id`,
  `source_event_id` et `projected_at` ; la tombstone Detach reste donc explicite ;
- `JdbcCurrentBindingAdapter` applique exclusivement la révision : supérieure `APPLIED`, inférieure
  `STALE`, égale/identique `DUPLICATE`, égale/divergente
  `CurrentBindingInvariantException`. Ni la fraîcheur ni l'ordre ne comparent les BindingId ; les
  tests `@1 → @3 → @2` et saut de révision conservent `@3` ;
- `JdbcBindingFactDiscoveryAdapter` exécute de courts scans SQL `READ COMMITTED`, bornés et
  partitionnés par `partition_hash(E)`. Il sélectionne les facts sans Consumption `DONE` et les
  retries éligibles sans claim vivant ; son keyset `(issuer,subject,binding_revision)` est uniquement
  éphémère dans un `openSearch` et chaque nouveau scan/restart repart sans curseur. Aucun watermark
  global, ordre UUID ou frontière exclusive `recorded_at` n'existe ;
- la clé canonique est `IDENTITY_BINDING_FACT[event_id] / CURRENT_BINDING_PROJECTOR[]`. Le locator
  recharge par `event_id`, applique la projection, écrit la provenance et finalise le claim sous le
  fence Consumption dans la même transaction ; échec technique reste `PENDING` avec retry, donnée
  manquante/collision divergente est terminale et observable ;
- le runtime dédié `runtime-binding-consumption-worker` réutilise le claim, lease, retry, fence et
  terminal `DONE` canoniques. Les preuves Testcontainers couvrent duplicate discovery, retry,
  reconstruction après restart, claim perdu, multi-worker et finalisation idempotente. La preuve
  anti-skip réelle laisse F1 non committé, consomme F2, committe F1 puis le retrouve au scan suivant ;
  F1 et F2 terminent `DONE` ;
- le consumer ne lit ni ne modifie l'autorité, le Command worker ne dépend pas de `CURRENT_BINDING`
  et aucune couche HTTP n'y accède.

#### WA.6.4 — Bootstrap borné des bindings V18

**Objectif historique WA.6.4.** Initialiser `CURRENT_BINDING` sans faux historique et sans perdre
une mutation réelle. WA.6D/WA.6F remplacent ce bootstrap primaire par des facts de baseline
identifiés comme données de migration, puis par un replay exclusivement fondé sur les facts.

**Fichiers/modules probables.** Port et use case de bootstrap dans le moteur User/Identity READ ;
reader primaire dédié et writer READ dans les adapters ; wiring dans un job administratif dédié ou
un mode one-shot du runtime identity, plus runbook sous `docs/`.

**Modèle/SQL.** Lecture keyset-paginée et bornée de l’autorité jointe au stream sur `(issuer,
subject)`, uniquement lorsque `current_revision = 0`. Écriture `ATTACHED, revision=0, user_id,
binding_id, source_event_id=NULL`. Aucun `ExternalIdentityAttached` n’est synthétisé.

**Runtime/transaction.** Le job est restartable, paramétré par taille de page et curseur. L’upsert
réutilise la règle de fraîcheur : absence → insert 0 ; row 0 identique → no-op ; row >0 → conserver
la mutation projetée ; row 0 divergente → relire l’autorité/stream et retenter ou signaler un
invariant, sans overwrite aveugle. Le déploiement active le consumer de faits avant ou avec le job,
de sorte qu’une mutation concurrente à révision `1+` gagne toujours.

**Invariants.** Idempotent, borné, reproductible ; aucune collision avec la première mutation réelle
(`0` contre `1`) ; aucune dépendance à l’ordre de BindingId ; aucune invention de fait.

**Tests/preuves.** Testcontainers avec base V18 préremplie, pages multiples, interruption/reprise,
double exécution, mutation avant/pendant/après la page, detach et rebind concurrents ; comparaison
finale autorité/projection après rattrapage.

**Clôture.** Le compteur de rows `revision=0` attendu est expliqué, le job peut être rejoué sans
effet et tout stream `revision>0` converge uniquement via ses faits réels.

**Résultat WA.6.4 — DONE (2026-10-01).**

- `HistoricalBindingBootstrap` lit par pages bornées/keyset
  `external_identities JOIN external_identity_binding_streams` avec `current_revision = 0` et écrit
  `ATTACHED@0` avec U/B autoritatifs et `source_event_id = NULL` ; aucun fact historique synthétique
  n'est créé ;
- le bootstrap s'exécute au démarrage du runtime avant le polling, peut être interrompu, repris et
  rejoué. Il réutilise la même règle d'apply : absence insérée, `@0` identique no-op, `@1+` conservé,
  `@0` divergent signalé sans overwrite ;
- les tests PostgreSQL couvrent plusieurs pages, double exécution/restart, mutation avant, pendant
  et après bootstrap, ainsi que les deux ordres `bootstrap @0 → mutation @1` et
  `mutation/consumer @1 → tentative bootstrap @0` ; l'état final reste toujours `@1` ;
- la preuve verticale couvre `Attach → fact durable → consumer → ATTACHED`, puis
  `Detach → fact durable → DETACHED`, puis reattach avec nouveau B → `ATTACHED` sur ce nouveau B ;
- la vague s'arrête ici : aucun changement de `COMMAND_RESULT`, loader/payload/ownership, aucun GET
  ou contrôle HTTP par `CURRENT_BINDING`, aucune implémentation WA.6.5+, WA.7 ni modification de
  `Step_Canon.md`.

#### WA.6.5 — `COMMAND_RESULT` V1/V2 : contexte durable et matérialisation

**Objectif.** Matérialiser effectivement les résultats V2 sans casser les artefacts V1 existants.

**Fichiers/modules probables.** `engine-command-result` (`CommandResultProjectionInput`, loader,
projector, définition/payload), `infra-persistence-jpa`
(`JdbcCommandResultProjectionInputLoader`) et `runtime-task-consumption-worker`. Le terminal event,
la policy et la clé de ProjectionTask existants restent inchangés : ils identifient déjà sans
ambiguïté le `commandId` permettant de relire la `RecordedCommand` exacte.

**Modèle/SQL.** Introduire un contexte de visibilité discriminé :
`LEGACY_USER(userId)` pour V1, `EXACT_EXTERNAL_IDENTITY(issuer,subject)` pour V2. Le loader branche
sur `recorded_commands.envelope_version`, jamais sur un NULL : V1 exige `auth_user_id`; V2 exige
`auth_issuer/auth_subject` et ne lit pas `auth_user_id`. Étendre la définition du payload par deux
formes exclusives : la forme V1 existante reste byte-for-byte compatible ; la forme V2 porte un
discriminant et `visibleToExternalIdentity{issuer,subject}` sans `submittedByUserId`.

**Stratégie de compatibilité.** Conserver le même type physique, la même target version et la même
clé `COMMAND_RESULT`. C’est le plus petit changement sûr : les tâches V2 déjà créées restent en
retry permanent grâce à `ProjectionTaskRetryPolicy` et réussiront après déploiement du loader ; les
terminal events pas encore matérialisés seront découverts normalement. Aucun slot n’est réinitialisé,
aucune row primaire n’est réécrite et aucun artefact V1 immuable n’est rematérialisé. Les payloads V2
nomment E comme `visibleToExternalIdentity`, jamais `owner`.

**Runtime/transaction.** L’outcome et le terminal event restent écrits dans la transaction fenced de
la Command. Le routage exact est découvert après commit ; le loader recharge l’outcome et le
contexte depuis la même `RecordedCommand`. Aucun lookup de binding n’intervient. La projection V2
est écrite/finalisée sous le même type par le moteur de ProjectionTask existant avec ses garanties
immuables.

**Invariants.** V1 continue strictement par son `auth_user_id`; V2 utilise exclusivement E capturée ;
E1 et E2 liées au même U restent séparées ; detach/rebind ne transfère pas la visibilité historique ;
V1 ne reçoit jamais de subject inventé. B, révision et U résolu sont volontairement absents du
contexte de visibilité V2. Un éventuel futur besoin d’audit `executedAsUserId` serait un autre fait à
capturer dans la transaction WA.4, pas une condition de lecture WA.6.

**Tests/preuves.** Unitaires loader/projector pour deux variantes et toutes formes invalides ;
PostgreSQL V1 persisted → artefact historique inchangé ; V2 applied/rejected/failed → artefact V2 ;
task V2 antérieurement en retry qui réussit après activation ; terminal event V2 non encore découvert
qui suit la chaîne normale ; retry/restart/immutabilité/conflit de payload ; backlog V1 toujours
consommable ; aucun changement de policy ou de clé de matérialisation.

**Clôture.** Une Command V2 terminale obtient un résultat READ sans `auth_user_id`, tandis que les
goldens et comportements V1 restent inchangés.

#### WA.6.6 — GET self-service et autorisation READ-only

**Objectif.** Servir le binding courant et les résultats sans lecture du schéma primaire.

**Fichiers/modules probables.** Ports/read services dans le moteur User/Identity READ et
`engine-command-result`; `infra-read-persistence`; `supra-http-read-query` pour controllers/DTO ;
`runtime-web-api` pour le wiring AuthN et suppression du resolver primaire des GET.

**Contrats HTTP.** Ajouter un GET self-service (chemin final aligné aux conventions API, par exemple
`GET /api/v1/me/binding`) sans issuer/subject client : E provient uniquement de
`AuthenticatedExternalPrincipal`; réponse attachée minimale `userId, bindingId, bindingRevision,
status`, et réponse détachée/non trouvée non-oracle définie par le contrat HTTP. Conserver
`GET /api/v1/commands/{commandId}/result` avec `commandId + AuthN` uniquement.

**Runtime/autorisation.** Lire l’unique projection `COMMAND_RESULT`, puis appliquer sa forme. Pour V2,
comparer en READ E authentifiée au couple exact du payload. Pour le V1 historique, résoudre E dans
`CURRENT_BINDING` READ puis comparer le `userId` au `submittedByUserId` historique : c’est le
comportement legacy courant transposé côté READ, pas une migration V1 vers E. Une forme mixte ou
inconnue est une invariant failure. Toute absence/non-ready/non-ownership conserve la réponse
non-oracle actuelle.

**Cohérence V1 assumée.** Pour V1 seulement, la révocation liée au binding devient éventuellement
cohérente. Après un detach/rebind committé dans WRITE et avant le rattrapage de `CURRENT_BINDING`, le
GET voit encore l’ancienne projection E→U : un résultat V1 `submittedByUserId=U` reste donc lisible
pendant cette fenêtre. Après projection du detach, E est `DETACHED` et l’accès est refusé ; après un
reattach E→U2, un résultat historique de U1 reste refusé puisque U2 ≠ U1. Cette fenêtre est la
conséquence acceptée du GET READ-only et de l’absence d’E historique dans V1. Il est interdit de la
fermer par une lecture de l’autorité WRITE ou par l’invention/reconstruction d’une E V1 ; cette dette
disparaît avec la contraction V1 en WA.7.

V2 ne suit pas cette règle legacy : l’autorisation compare uniquement E authentifiée à l’E exacte
durable capturée à l’admission. Attach, detach, rebind et lag de `CURRENT_BINDING` ne transfèrent ni
ne révoquent cette visibilité historique ; E2 ne voit jamais le résultat soumis par E1, même lorsque
E1 et E2 sont ou ont été liées au même U.

**Invariants.** Aucun paramètre ne permet de choisir E ; aucun controller/use case GET n’importe le
repository Identity WRITE, `recorded_commands`, `command_outcomes` ou Consumption ; aucune E n’est
reconstruite pour V1 ; E2 ne lit jamais le résultat V2 de E1, même si elles partagent U ; le statut
futur du binding n’altère pas ce contrôle V2.

**Tests/preuves.** MVC/AuthN : token E exact, issuer/subject homonymes, inconnu, commandId
absent/non-ready/non-owned ; spy/statement capture prouvant zéro SELECT primaire ; contrat de réponse
binding attaché/détaché et absence de paramètres d’identité. Ajouter explicitement :

- V1 lag : `CURRENT_BINDING` contient E→U et le résultat V1 porte U ; detach E committe côté WRITE
  mais la projection n’a pas encore appliqué le fait ; le GET authentifié par E reste temporairement
  autorisé. Dès application du detach, le même GET retourne la réponse non-oracle de refus ;
- V1 detach/rebind : E→U1, résultat V1 de U1, detach puis attach E→U2 et projection rattrapée ; le GET
  reste refusé et ne transfère jamais le résultat historique à U2 ;
- V2 exact-E : résultat soumis par E1, avec E1 et E2 liées au même U ; E1 est autorisée par égalité
  exacte, E2 est toujours refusée avant, pendant et après detach/rebind/lag READ.

**Clôture.** Les deux GET utilisent exclusivement `pocoma_read`/artefacts et toutes les décisions
d’identité proviennent du principal authentifié.

**Résultat WA.6.5–WA.6.6 — DONE.** L'implémentation retient la hiérarchie scellée
`CommandResultVisibility` avec les records `LegacyUser(UUID userId)` et
`ExactExternalIdentity(ExternalIdentity identity)`. Le loader branche uniquement sur
`recorded_commands.envelope_version` : V1 exige `auth_user_id`, V2 exige le couple durable
`auth_issuer/auth_subject`; il ne consulte ni binding, ni resolver User/Identity, ni
`CURRENT_BINDING`. Les trois outcomes `APPLIED/REJECTED/FAILED` sont projetés pour les deux formes.

Le payload V1 conserve exactement ses champs historiques, dont `submittedByUserId`, sans subject
synthétisé. Le payload V2 est une forme JSON disjointe portant
`visibility=EXACT_EXTERNAL_IDENTITY` et
`visibleToExternalIdentity={issuer,subject}`, sans userId, BindingId ni BindingRevision. Le schéma
`oneOf` et le service READ rejettent les formes mixtes/inconnues comme violations d'invariant. Le
type physique, la target version et la clé `COMMAND_RESULT` restent inchangés. Une tâche V2 laissée
`PENDING` par l'ancien loader avec un claim expiré est reprise après redémarrage par le worker
existant et se matérialise sans reset de slot, réécriture de RecordedCommand ou destruction V1.

`GET /api/v1/commands/{commandId}/result` lit uniquement l'artefact exact `COMMAND_RESULT`. Pour V2,
l'autorisation est l'égalité exacte issuer+subject avec E issue de
`AuthenticatedExternalPrincipal`; detach, absence ou rebind de `CURRENT_BINDING` ne changent pas
la visibilité. Pour V1 seulement, E est recherchée dans `CURRENT_BINDING` READ et U courant est
comparé à `submittedByUserId`. La révocation V1 est donc EVENTUAL : l'ancien accès persiste pendant
le lag, puis disparaît après projection du detach. Un rebind vers U2 ne restaure pas un résultat de
U1 ; un rebind vers le même U restaure l'accès, conséquence assumée de la sémantique legacy par
userId.

Le self-service final est `GET /api/v1/me/binding`, sans paramètre d'identité : E provient seulement
du principal. Un binding `ATTACHED` retourne le DTO minimal `userId, bindingId, bindingRevision,
status`; une tombstone `DETACHED` et une absence retournent toutes deux `404`, sans exposer l'ancien
BindingId. Les lectures `find` de `CURRENT_BINDING` s'exécutent dans une transaction read-only.

La preuve SQL PostgreSQL encadre les deux GET par des marqueurs et observe uniquement
`pocoma_read.projection_root`/artefacts et `pocoma_read.current_external_identity_binding`; elle
interdit explicitement `recorded_commands`, `command_outcomes`, `external_identities`, les tables de
stream/facts de binding, Pot/Event et Consumption primaires. Les guards ArchUnit excluent également
des controllers HTTP READ `infra-persistence-jpa`, les ports Command WRITE, l'infrastructure de
transaction WRITE et Consumption. Les tests couvrent payloads V1/V2, outcomes V2, E exacte et
homonymes issuer/subject, compatibilité V1, incohérences, reprise V2 après claim/restart, chaîne HTTP
V2, self-service attached/detached/absent, AuthN/non-oracle, cohérence legacy et preuve zéro SELECT
primaire. Le lot n'implémente aucune clôture WA.6.7/WA.6.8 et ne modifie pas `Step_Canon.md`.

#### WA.6.7 — Concurrence, idempotence et reprise opérationnelle

**Objectif.** Prouver la convergence de la chaîne complète sous course, replay et redémarrage.

**Fichiers/modules probables.** Tests d’intégration des runtimes identity/task, suites PostgreSQL et
E2E dans `architecture-tests`; propriétés/métriques des workers et runbook WA.6.

**Scénarios.** `Attached(B1@1) → Detached(B1@2) → Attached(B2@3)` avec replay tardif de `@1/@2` ;
duplicate `@3`; livraison `@3,@1,@2`; detach B1 stale après B2 ; deux attach concurrents ; detach
pendant Command WA.4 ; crash avant/après append, avant/après apply et avant finalisation ; bootstrap
en concurrence avec `@1`; redémarrage/multi-worker. Pour `COMMAND_RESULT`, couvrir V1 backlog,
terminal V2 déjà existant, outcome V2 nouveau, duplicate task et E1/E2 même U.

**Stratégie transactionnelle.** Les tests inspectent simultanément autorité, stream, journal,
Consumption/provenance et READ : aucun commit partiel n’est accepté. Un retry technique peut
réexécuter un apply, jamais une mutation d’autorité déjà finalisée hors de son idempotency contract.

**Invariants/tests.** Révision maximale et contenu correspondant gagnent ; le nombre de faits égale
le nombre de mutations réussies ; aucune lacune causée par un échec fonctionnel ; stale detach WA.2
et lock WA.4 étaient alors valides ; WA.6C remplace ce lock. Les workers ne lisent pas
`CURRENT_BINDING` pour décider une Command.

**Clôture.** La matrice de courses passe de manière répétable sous Testcontainers avec au moins deux
workers et après restart.

**Résultat WA.6.7 — DONE (2026-10-01).**

- audit global du lock-order : tous les accès production aux trois tables binding ont été recensés.
  `ExternalIdentityJdbcRepository.lockCurrentBinding` est le seul lock d'autorité E et ne connaît
  pas le stream ; `ExternalIdentityBindingStreamJdbcRepository.lock` est le seul lock de stream et
  ne connaît pas l'autorité. Le seul writer qui combine les deux est
  `JpaExternalIdentityBindingAdapter`, avec l'ordre structurel gardé `stream E → authority E` dans
  `acquire` et `detach`. Aucun chemin `authority E → stream E` n'existe. Le worker Command ne prend
  que le lock d'autorité exact E+B ; il ne consulte ni stream, facts ni `CURRENT_BINDING` ;
- classification : resolvers legacy/exact et discovery/bootstrap sont des READ non-locking ;
  `lockCurrentBinding` est authority-lock-only ; les repositories lifecycle sont stream-lock-only
  ou append-only ; `acquire`/`detach` sont `stream → authority`. Les accès SQL directs à
  `external_identities`, streams, facts, `CURRENT_BINDING`, `recorded_commands` et
  `command_outcomes` sont figés par `Wa67BindingArchitectureTest` dans leurs adapters propriétaires ;
- matrice mutation : Attach/Attach, Attach/Detach et Detach/Detach PostgreSQL sérialisent sur le
  stream, ont un seul résultat fonctionnel par état, et ne perdent ni update ni fact. Conflict
  attach et stale detach ne consomment aucune revision. Le test WA.4 maintient le lock exact E+B
  jusqu'au commit Command et fait attendre Detach(B) ; Command(B1) et Detach(B0) stale ne peuvent
  supprimer B1. Les rollbacks après mutation d'autorité, échec d'allocation et échec d'append
  restaurent autorité, revision et journal ensemble ; l'overflow ne produit ni fact ni autorité ;
- propriété forte : pour une identité créée après WA.6, chaque mutation réussie ajoute exactement
  une revision et un fact ; le test vérifie explicitement `facts = 1..N`. Une identité historique
  commence à 0 et sa première mutation réussie produit `@1`. La contrainte unique `(E,revision)` et
  l'unicité `event_id` interdisent double fact et trou masqué par duplication ;
- append-only : le scan de tout `src/main/java` exige l'unique `INSERT` du fact repository et
  interdit tout `UPDATE`/`DELETE` de `external_identity_binding_facts`. Les suppressions restent
  limitées aux fixtures de test ;
- convergence : les tests PostgreSQL couvrent duplicate, stale, saut et ordre `@3,@1,@2` pour
  ATTACHED/DETACHED. Seule la revision maximale valide gagne ; same-revision/same-payload est
  `DUPLICATE`, same-revision/divergent-payload lève une invariant failure observable ;
- discovery/Consumption : la requête bornée sélectionne uniquement slots absents ou PENDING
  retry-eligible, exclut DONE, partitionne par hash(E), et ordonne par `(issuer,subject,revision)`.
  Son cursor est un champ de l'itérateur de scan, jamais persisté. Il n'existe aucun watermark
  binding, `recorded_at > last_seen` ou `event_id > last_seen`; chaque nouveau scan repart sans
  cursor. La late-commit race, les nouveaux facts, duplicate discovery, deux workers, expiration de
  claim, lost claim et retry après reconstruction d'orchestrator convergent via Consumption(eventId) ;
- bootstrap : les courses réelles PostgreSQL prouvent A (lecture E@0, mutation/consumer @1, apply
  tardif @0), B (apply @0 puis mutation/consumer @1), C (pagination, reconstruction du bootstrap et
  reprise depuis le début) et D (detach/rebind pendant/après bootstrap). Le résultat conserve la
  plus grande revision réelle et le nombre de facts reste exactement celui des mutations : aucun
  fact synthétique @0 ;
- matrice consumer : consumer/consumer et lost-claim/worker concurrent sont fenced par Consumption ;
  consumer/bootstrap converge par comparaison de revision ; retry/new facts repart d'un scan sûr.
  Les tests reconstruisent l'orchestrator et le bootstrap, tandis que les E2E reconstruisent les
  application contexts event/task : aucune propriété ne dépend du cursor ou d'un état Java durable.

#### WA.6.8 — Frontières d’architecture et preuves de clôture

**Objectif.** Verrouiller la séparation core/app/infra/api/worker et démontrer la non-régression
WA.2–WA.5.

**Fichiers/modules probables.** `architecture-tests/HexagonalArchitectureTest` et guards spécialisés,
POMs/composition roots uniquement pour les dépendances nécessaires ; documentation opérationnelle
et résultat de step après implémentation.

**Guards à ajouter/étendre.** Domaine User/Identity JDK-only ; un seul adapter d’autorité de binding ;
mutation autoritative uniquement derrière le use case transactionnel ; HTTP READ sans
`infra-persistence-jpa`, repositories WRITE ou transactions primaires ; worker Command sans moteur
Identity READ ; runtime Identity READ sans droit d’écriture sur l’autorité ; V2 result sans
`auth_user_id`, resolver User ou fallback legacy ; V1 sans `auth_subject` synthétisé ; sens des
dépendances core/app→ports et infra→adapters sans cycle.

**Preuves de non-régression.** Rejouer les tests ciblés WA.2 attach/detach/stale, WA.3 round-trip V1/V2,
WA.4 lock/TOCTOU/rollback, WA.5 admission ouverte/zéro SELECT ; migrations V18→HEAD et base neuve ;
tests unitaires, PostgreSQL/Testcontainers, HTTP/AuthN/AuthZ, E2E Command→result, architecture tests,
reactors impactés puis reactor Maven complet.

**Clôture WA.6.** Journal de faits et projection rattrapés, bootstrap achevé/mesuré, aucun failed slot
V2 non traité, GET sans accès primaire, matrice E1/E2 et V1/V2 verte, architecture verte, runbook et
rollback de déploiement documentés. Aucun code WA.7 de contraction n’est inclus.

**Résultat WA.6.8 — DONE (2026-10-01).**

- `COMMAND_RESULT` V1 conserve exactement `submittedByUserId`, sans reconstruction d'E ni subject
  synthétique ; ses rows, tâches et projections historiques restent matérialisables. V2 capture et
  publie E exacte, sans U ni lookup binding. Retry/restart d'une tâche V2 conserve E ; E1/E2 liées au
  même U restent distinctes et detach/rebind ne transfère jamais la visibilité V2 ;
- le vrai validateur NetworkNT accepte les formes V1 historique et V2 complète, puis rejette V1 avec
  champs exact-E, V2 avec `submittedByUserId`, V2 sans discriminateur, forme vide et forme V2
  incomplète. Le `oneOf` et `additionalProperties:false` rendent les formes disjointes ;
- la matrice finale V2 est historique : E1 reste visible après attach, detach et rebind même/autre U,
  E2 reste invisible quel que soit son U. La matrice V1 est volontairement legacy : visible lorsque
  `CURRENT_BINDING(E)=U`, invisible après convergence d'un detach ou rebind autre U, de nouveau
  visible après detach + rebind même U ;
- les GET Command result et self-service utilisent seulement `pocoma_read`. La preuve SQL runtime
  interdit `recorded_commands`, `command_outcomes`, `external_identities`, streams/facts binding,
  tables Pot/Event primaires et tables Consumption ; les guards excluent ports/repositories WRITE.
  Les réponses inexistante, non visible, non ready, binding absent/detached et caller non autorisé
  restent non-oracle selon les conventions `NotFound`/404 existantes ;
- les guards finaux figent l'autorité unique `external_identities`, l'ownership projection de
  `CURRENT_BINDING`, l'absence de READ dans le worker Command, E+B sans U dans l'envelope V2,
  l'absence de fallback legacy et de lookup binding pour la visibilité V2, l'isolation V1 et le
  domaine User/Identity JDK-only. L'audit SQL n'a trouvé aucune exception cachée ; les jointures
  directes de discovery/bootstrap et du loader de projection sont les exceptions READ
  intentionnelles, dans leurs adapters dédiés ;
- synthèse de clôture : WA.6 est fermé. Autorité binding = `external_identities`; revision = stream
  monotone 0 historique puis 1..N ; transaction = authority/revision/fact atomiques ; lock-order =
  stream puis authority ; facts = journal append-only ; discovery = scan keyset éphémère sans
  watermark ; identité Consumption = eventId ; `CURRENT_BINDING` appartient à READ ; bootstrap =
  ATTACHED@0 sans fact ; V1 = LEGACY_USER ; V2 = EXACT_EXTERNAL_IDENTITY ; GET result = projection
  READ et, uniquement pour V1, `CURRENT_BINDING`; self-service = `CURRENT_BINDING`; révocation V1 =
  EVENTUAL ; concurrence/restart/architecture = prouvés par PostgreSQL, E2E et guards.

**Dette legacy restante assumée.** V1 conserve la visibilité par userId, l'autorisation via
`CURRENT_BINDING`, la révocation EVENTUAL et la restauration possible après rebind du même E vers le
même U. Cette dette n'est pas un défaut WA.6 et ne peut disparaître qu'avec la contraction future de
V1. Aucun travail WA.7 n'est inclus. `Step_Canon.md` a été réaudité sans contradiction : changement
canonique **NO**.

#### Clôture de l'audit final

- `WA.6 FINAL AUDIT: PASS` ;
- `WA.6 STATUS: CLOSED` ;
- findings Blocking/Major/Minor : `0` ;
- dettes de durcissement `LOW` : `2`, documentées dans [`Step_debt.md`](Step_debt.md) ;
- aucune de ces dettes ne justifie la réouverture de WA.6 ;
- `WA.7 STATUS: NOT STARTED`.

**Ordre d’implémentation.** `WA.6.1 → WA.6.2 → WA.6.3 → WA.6.4 → WA.6.5 → WA.6.6 → WA.6.7 →
WA.6.8`. WA.6.3 peut être développé en parallèle de WA.6.2 après gel des contrats, mais son activation
attend le journal durable. WA.6.5 peut être développé après WA.6.1 et s’active avant WA.6.6. WA.6.4
s’exécute après activation du consumer WA.6.3. La clôture reste séquentielle.

### WA.6A–WA.6G — Réalignement du canon final avant WA.7

**Statut : PLANIFIÉ, non implémenté.** Le canon [`Step_Canon.md`](Step_Canon.md) reste fermé.
Les paragraphes « Résultat WA.1–WA.6 — DONE » et les formes SQL précédentes décrivent l'état
livré à leur date, et non la cible après réalignement. Les sous-lots suivants les remplacent comme
prescription technique. Aucun changement de WA.7/WA.8 n'est exécuté ici.

**Décisions communes.** Conserver l'autorité composite WRITE existante :
`external_identity_binding_streams(E,current_revision)` persiste après detach ;
`external_identities(E,U,B)` existe exactement quand E est ATTACHED. L'état DETACHED est
`stream présent + row active absente` ; `stream absent` signifie E jamais connue. Cette relation
est lue par le WRITE dans sa propre base, jamais depuis `CURRENT_BINDING`. La contrainte d'unicité
globale historique de B vient d'un registre permanent `external_identity_binding_occurrences`
avec `binding_id UUID PRIMARY KEY`, une row par **occurrence attachée**, jamais supprimée. Les
facts, avec deux rows possibles pour un même B (attach et detach), ne peuvent pas porter seuls
une contrainte `UNIQUE(binding_id)`. Les nouvelles acquisitions génèrent B côté domaine/adapter
WRITE au moment de l'Attach ; ni le caller ni la Command ne choisissent un B à créer. L'adapter
réserve B dans le registre dans la même transaction que l'authority, R et le fact. La collision
UUID, improbable mais possible, est traitée comme conflit de clé : rollback au savepoint ou de la
transaction, nouvelle tentative avec **un nouveau** B, jamais réutilisation du candidat. Le
résultat d'acquisition expose B seulement après succès ; un retry d'une acquisition committée
doit retrouver son résultat via une clé d'idempotence métier si le caller en possède une, ou être
un nouvel Attach soumis aux règles de conflit, jamais rejouer un B proposé. REGISTRATION future
utilisera ce port et recevra B pour `Registered(U,B)` ; aucune Registration n'est implémentée ici.

Les writers Attach/Detach/Reattach conservent leur verrou court de stream E, dans l'ordre
`stream E → réserve occurrence B → authority E → fact E,R` pour Attach, et
`stream E → authority E → fact E,R` pour Detach ; ils effectuent l'ensemble dans une transaction
PostgreSQL. Ce verrou sérialise les **mutations de binding**, pas la phase métier d'une Command.
R n'avance que si la mutation et son fact committent. Le registre persiste après detach et restart ;
un rollback annule sa réservation. La commande V2 garde `E+B+payload+authenticationEvidence`,
sans R, U d'admission, JWT brut ni lecture métier à l'admission. V1 reste compatible jusqu'à WA.7.

**Déploiement transversal.** L'ordre d'exécution est A → B (writer préparé, inactif) → D
(backfill complet et activation writer) → C → E → F → G. EXPAND les structures et lecteurs compatibles ; MIGRATE sous
préconditions vérifiées ; SWITCH les writers, le fence puis les projections ; VERIFY sur PostgreSQL
et replay ; CONTRACT seulement lorsque les preuves sont acquises. Des versions mixtes ne doivent
pas écrire des rows que l'autre version ne sait pas protéger : suspendre les writers de binding
pendant le backfill et le basculement du port, puis n'activer que les writers nouveaux. Les
Commands peuvent continuer à être admises durant cette fenêtre ; ne relancer leur exécution V2
qu'une fois le registre et le CAS déployés. Après chaque sous-lot, les tests ciblés et le reactor
pertinent doivent rester verts ; chaque migration est additive avant sa contraction. Ne jamais
abandonner le verrou WA.4 avant la preuve PostgreSQL du CAS.

#### WA.6A — Unicité historique de B et support de l'autorité

**Prérequis.** WA.6 livré ; inventaire des rows actives, facts et streams sur la base cible.
**Objectif.** Fermer A1/J1 durablement, sans remodeler l'autorité composite qui satisfait B1/C1.
**État initial réel.** `external_identities.binding_id` est unique seulement pour les actifs ;
`acquire(E,U,B)` accepte B du caller ; le stream conserve déjà R après detach.
**État cible.** Registre `external_identity_binding_occurrences(binding_id PK, issuer, subject,
user_id, attached_revision, created_at)` avec E et U non nuls, FK vers stream/User, unicité
`(issuer,subject,attached_revision)` ; aucune suppression métier, aucun UPDATE d'identité.
`external_identities` et stream restent l'autorité ATTACHED/DETACHED décrite plus haut ; index
exact E+B actif conservé. Le registre est la preuve permanente de réservation globale B, non une
projection READ ni un compteur de révision.

**Changements.** Migrations WRITE après V20 : EXPAND du registre et du journal, MIGRATE du
registre, puis contraintes/CONTRACT après WA.6D ; ne modifier aucune migration déjà appliquée.
Adapter `JpaExternalIdentityBindingAdapter` et port
`ExternalIdentityBindingPort` : remplacer le chemin public `acquire(E,U,B)` par `acquire(E,U)`
retournant le B neuf en cas de succès ; production d'un B opaque UUID dans l'adapter, réservation
en base et fact Attached atomiques. Conserver les fixtures d'injection de B uniquement dans les
tests du repository ou SQL, pour prouver que la contrainte refuse la réutilisation. La cohérence
`active B ↔ registre(E,U,B)` est vérifiée par le writer et une contrainte FK/trigger ciblée si une
écriture SQL directe peut contourner l'adapter ; les droits DB de mutation sont limités au writer.
**Migration/data strategy.** Backfill initial du registre depuis tous les `ATTACHED` facts et les
rows actives R0 ; compléter les occurrences initiales déjà détachées depuis les `DETACHED@1`
seulement après la récupération fiable de U en WA.6D. Pour éviter une protection incomplète,
garder l'ancien writer arrêté jusqu'à cette complétion ; le SWITCH définitif du port attend WA.6D.
Préflight : même B associé à deux attaches distinctes, U contradictoire, stream manquant ou B
impossible à attribuer => migration arrêtée avec rapport, jamais correction silencieuse.
**Transactions/concurrency.** Deux inserts concurrents d'un même B ne peuvent tous deux
committer ; la PK globale tranche, y compris sur E différentes. Attach conflictuel, stale detach,
collision, échec de fact ou rollback ne consomment ni B réservé ni R durable. La nouvelle tentative
génère un autre B après rollback. **Compatibilité historique.** Les B déjà détachés doivent être
réservés avant l'ouverture du writer nouveau ; aucune nouvelle occurrence ne reprend un B ancien.
**Tests/preuves.** Fresh DB, upgrade V18/V20 prérempli, B1 après detach sur E1 puis sur E2,
deux réutilisations concurrentes, rollback, retry et restart ; rejets SQL persistants pour B
historique détaché ; R continue après detach et ATTACHED/DETACHED restent sans ambiguïté.
**Non-objectifs.** Table d'autorité unique, Registration, interprétation ordinale/temps de B.
**DONE.** Schéma et nouveau port prêts, préflight historique chiffré, ancien writer suspendu ;
la complétion du registre, le retrait effectif de `acquire(E,U,B)` et la réouverture des writers
sont le SWITCH de WA.6D, avant WA.6C. Aucun writer partiellement protégé n'est activé.

#### WA.6B — Facts de lifecycle canoniques pour les nouveaux writes

**Prérequis.** WA.6A EXPAND ; le port ancien ne produit plus pendant le SWITCH.
**Objectif.** Fermer la partie « Detached sans U » de D1 pour les mutations futures.
**État initial réel.** Attached contient E/U/B/R ; Detached et sa contrainte SQL imposent U nul.
**État cible.** Les deux variantes portent exactement E/U/B/R comme information métier ; les
colonnes techniques `event_id`, `recorded_at`, `partition_hash` restent techniques. Detached
identifie l'occurrence invalidée et son U au moment de la suppression.
**Changements.** EXPAND la contrainte de shape pour accepter temporairement les anciennes rows
DETACHED nulles ; adapter `ExternalIdentityDetached`, `ExternalIdentityBindingFactRecordMapper`,
row mapper, writer et sérialisation. Detach lit U de la row active exacte E+B **sous le lock de
stream**, puis supprime cette row et écrit `Detached(E,U,B,R+1)` dans la même transaction. Un
detach stale ne connaît pas U et n'émet aucun fact. Le fact n'est jamais enrichi à partir de READ.
**Migration/data strategy.** Anciennes rows nulles restent lisibles pendant l'EXPAND ; le NOT NULL
et la nouvelle contrainte commune ATTACHED/DETACHED attendent WA.6D et sa vérification.
**Transactions/concurrency.** Lock stream par E, lecture U, suppression exacte, avance R et append
atomiques ; fact append failure rollback tout ; reattach génère un autre B, même vers U identique.
**Compatibilité historique.** Reader transitoire accepte les anciens DETACHED nulls mais ne les
présente pas comme canoniques au replay final. **Tests/preuves.** Fact Attached et Detached exacts
E/U/B/R, attach/attach, detach/detach, stale detach, reattach même/autre U, rollback et absence de
trou de R. **Non-objectifs.** Rejouer les données historiques ou changer la projection ici.
**DONE.** Le nouveau writer (activé seulement après WA.6D) émet tout fact DETACHED avec U ; les
facts restent append-only en production,
uniques par E+R et contigus pour les mutations réussies.

#### WA.6C — Fence optimiste de Command V2 au commit

**Prérequis.** WA.6A/WA.6B et WA.6D, writers cohérents et registre complet ; preuve du CAS en environnement PostgreSQL avant
suppression de `lockCurrentBinding` dans le worker. **Objectif.** Remplacer le lock pessimiste long
de WA.4, en conservant WA11, F1/G1/I1 et le rejet J1 non-oracle.
**État initial réel.** `SELECT ... FOR UPDATE` sur `external_identities(E,B)` est gardé durant AuthZ,
Pot, Event, outcome et finalisation du claim ; Detach attend cette phase.
**État cible.** Dans la transaction métier `READ COMMITTED`, lire sans lock le tuple cohérent
`stream E JOIN active E` : `E, state=ATTACHED, U, B, R`. Une absence/mismatch E+B donne
`CALLER_IDENTITY_NOT_CURRENT`. Après capabilities/AuthZ, état métier, mutations, append Event et
préparation du résultat, **avant** publication d'outcome, provenance et finalisation du claim,
exécuter le fence SQL suivant sur la même connexion/transaction :

```sql
UPDATE external_identity_binding_streams AS s
SET current_revision = s.current_revision
WHERE s.issuer = :issuer AND s.subject = :subject
  AND s.current_revision = :observed_r
  AND EXISTS (
    SELECT 1 FROM external_identities AS a
    WHERE a.issuer = s.issuer AND a.subject = s.subject
      AND a.user_id = :observed_u AND a.binding_id = :command_b
  );
```

Une row affectée = fence acquis ; zéro = fence perdu. Le no-op UPDATE crée un tuple PostgreSQL
mais n'avance pas R et n'émet aucun fact. Son row lock de stream dure jusqu'au commit de cette
transaction, **uniquement après** le travail métier. Les writers prennent le même stream lock avant
toute mutation. Si Detach committe d'abord, PostgreSQL `READ COMMITTED` réévalue la condition après
attente et voit R différent ; le CAS échoue. Si le CAS gagne, Detach attend le commit de la Command.
Il n'existe donc aucune fenêtre validation réussie → detach committé → commit Command. Le
`SELECT ... FOR UPDATE` long est interdit dans la cible ; le verrou interne bref du CAS jusqu'au
commit est admis. Deux Commands sous B inchangé peuvent toutes deux réussir séquentiellement au
fence final ; leur concurrence Pot/target reste réglée par les mécanismes métier existants, sans
verrou binding durant leur phase métier. Le no-op UPDATE, son coût et l'ordre des locks avec Pot et
claim doivent être contrôlés par les tests PostgreSQL ; les writers n'acquièrent aucun lock Pot.

**Changements.** Port WRITE `observeCurrentBinding(E,B)` retournant U+R localement (R ne sort pas
dans `RecordedCommand`), puis `fenceObservedBinding(E,U,B,R)` ; intégration dans
`ExecuteRecordedCommandService`, `CommandConsumptionExecution` et frontière
`TransactionalExecuteConsumptionUseCase`. Pour V2, l'outcome `Applied/Rejected`, les rows de
provenance et le terminal claim CAS n'écrivent qu'après fence réussi et avant le commit ; V1 garde
sa voie historique jusqu'à WA.7. Si le dispatcher retourne un rejet métier, fencer également avant
de le publier : le résultat décrit alors un examen sous une occurrence encore courante.
**Migration/data strategy.** Aucune colonne Command ni migration SQL de Command ; conserver
temporairement l'ancien port locké derrière le switch, puis supprimer son appel et le SQL
`LOCK_EXACT_USER_ID` une fois les preuves obtenues.
**Transactions/concurrency.** Fence zéro : lever un signal typé qui force le rollback de **toute**
la transaction métier (Pot, Event, outcome préparé, provenance, claim). Après rollback, une
transaction neuve sous le claim encore valide recharge uniquement l'autorité E+B : si B n'est plus
courant, publier `Rejected(CALLER_IDENTITY_NOT_CURRENT)` et terminaliser le claim par son CAS dans
cette transaction ; si E+B est encore courant, traiter comme conflit technique et retenter le
traitement complet. Aucun lookup U/historique n'est rendu public. Timeout, deadlock, SQL exception,
claim perdu ou commit incertain suivent la politique technique existante ; un claim perdu ne
terminalise jamais. La transaction de rejet est elle-même fenced par le claim. Aucun succès ni
Event de la transaction rollbackée n'est visible. Le CAS est le **dernier contrôle de binding**
avant les seules écritures courtes d'outcome/provenance/claim et commit.
**Compatibilité historique.** V1 inchangé ; admission V2 garde 202 pour B syntaxiquement valide
mais faux ; aucune R en Command. **Tests/preuves.** Testcontainers à barrières de transactions :
Command observe B1/R1, Detach committe R2 pendant le travail métier, CAS zéro, zéro Pot/Event/
outcome succès ; reattach U1/B2/R3 et U2/B2/R3 idem ; sans mutation CAS un et commit ; deux
Commands même B sans sérialisation durant AuthZ/travail et avec règles Pot inchangées ; mesure de
barrière prouvant Detach committé avant libération du travail Command ; rollback et pannes SQL.
Remplacer le test `targetBindingLockIsHeldUntilBusinessCommitAndBlocksConcurrentDetach` qui prouve
l'ancien modèle ; adapter les autres tests de `CommandConsumptionPostgresTest`, unités de service
et guards `Wa67BindingArchitectureTest`. **Non-objectifs.** CAS comme précondition client,
nouveau framework d'events ou changement d'AuthZ. **DONE.** Lock pessimiste long retiré ;
optimistic fence et rollback prouvés, non-oracle conservé.

**Résultat WA.6C (2026-10-02).** L'observation WRITE retenue est
`ExternalIdentityBindingPort.observeCurrentBinding(E,B)` : un seul `SELECT` sans lock joint
`external_identity_binding_streams` à `external_identities` et retourne U+R pour l'occurrence
exacte. R reste local au worker ; ni `RecordedCommand`, ni envelope V2, ni HTTP ne le portent.
`fenceObservedBinding(E,U,B,R)` exécute dans la transaction métier `READ COMMITTED` le
`UPDATE external_identity_binding_streams s SET current_revision = s.current_revision WHERE
s.issuer = ? AND s.subject = ? AND s.current_revision = ? AND EXISTS (SELECT 1 FROM
external_identities a WHERE a.issuer = s.issuer AND a.subject = s.subject AND a.user_id = ?
AND a.binding_id = ?)` ; une row signifie succès. Le service exécute ce fence après le
dispatcher et l'append Event, y compris pour un rejet métier ou une expiration, mais avant
`CommandConsumptionExecution` (outcome), la provenance, le CAS terminal du claim et le commit.
Un fence zéro lève `BindingFenceLostException` et rollbacke la transaction entière. Le wrapper
Command relit E+B dans une nouvelle transaction sous le claim courant : occurrence absente →
rejet `CALLER_IDENTITY_NOT_CURRENT` et terminalisation CAS ; occurrence encore courante →
`BindingFenceConflictException` classée transitoire pour retry. Claim perdu → aucune
terminalisation.
Avec les writers actuels, un zéro SQL suivi de E+B toujours courant n'a pas d'ordonnancement
normal : toute avancée de R remplace ou supprime B, et le registre interdit la réutilisation de
B. Le test injecte le signal typé pour vérifier néanmoins le chemin de conflit technique ; les
pannes SQL suivent la classification transitoire existante.

Les barrières Testcontainers/PostgreSQL prouvent Detach committé pendant le travail métier,
avant le fence Command : R change, fence zéro, mutation et succès rollbackés, puis rejet exact.
Elles prouvent aussi le cas inverse : fence Command acquis, Detach attend le commit Command
avant de prendre le stream lock. Deux Commands sous B inchangé entrent simultanément dans
leur travail et leurs fences réussissent séquentiellement. Le fence ne change ni R ni les
facts. Ordre des locks audité : writers Binding `stream E → authority E → fact E,R`, sans
lock Pot ; Command V2 `locks métier → stream E au fence → outcome/provenance/claim` ;
la récupération prend le lock claim puis lit seulement l'autorité sans lock. Aucun chemin
inverse Pot/stream ni claim/stream n'est introduit par ces changements. V1 garde son
`AuthorizationSnapshot` et sa voie d'exécution historique. Aucune WA.6E+ n'est implémentée.

#### WA.6D — Récupération historique et replay canonique des facts

**Prérequis.** WA.6A/WA.6B nouveaux contrats prêts ; writers suspendus pour snapshot/backfill ; WA.6A
registre encore sous gate si l'historique manque. **Objectif.** Fermer D1 et finir le registre B.
**État initial réel.** V20 a créé des streams R0 sans fact initial ; DETACHED historiques ont U nul ;
le bootstrap R0 lit encore l'autorité primaire.
**État cible.** Pour chaque stream issu de V18, un fact `ATTACHED(E,U,B,R0)` de **baseline de
migration**, identifié par `record_origin=MIGRATION_BASELINE` (métadonnée de provenance, pas un
événement observé ni un nouveau type métier). Les facts normaux portent
`record_origin=LIFECYCLE`. Les faits DETACHED historiques sont complétés avec le U exact de leur
occurrence. Le journal seul rejoue toute E ; R0 est la baseline de l'état existant au passage V20,
et R1+ sont les mutations réellement enregistrées.
**Changements.** EXPAND `external_identity_binding_facts` pour R0 et `record_origin` avec default
`LIFECYCLE` ; `CHECK` : R0 seulement ATTACHED+baseline, R>0 seulement lifecycle, U et B non nuls
pour les deux types ; conserver `UNIQUE(E,R)` et PK `event_id`. Le mapper de domaine voit toujours
Attached/Detached E/U/B/R ; l'origine de baseline reste métadonnée du record persistant. Pour
chaque E, rejouer R0 puis R1…Rn, vérifier continuité et correspondance finale avec stream/active.
**Migration/data strategy.** Source fiable de U pour DETACHED@R : `ATTACHED` fact antérieur de
la même E et du même B, ou baseline R0. Pour une occurrence R0 déjà détachée dont l'active V18 a
disparu, utiliser le snapshot vérifié de `external_identities` pris au cutover V18→V20, ou une
sauvegarde/WAL équivalente portant exactement E/U/B à ce point ; recouper E+B avec le premier
DETACHED et les facts ultérieurs. Une row READ/bootstrap, une Command ou une supposition à partir
du User courant ne sont pas des sources suffisantes. Préflight compté : chaque R0 doit avoir un
E/U/B prouvable, chaque DETACHED doit retrouver une unique attache précédente, tout B doit désigner
une seule occurrence ; si preuve absente/contradictoire, **arrêt de la migration et de la gate
WA.7**, restauration de la source, sans valeur fabriquée. Backfill transactionnel par lots
restartables, identifiants de facts R0 déterministes/stables ou ledger de migration, et contrôle
`ON CONFLICT` identique seulement ; U des DETACHED existants est corrigé dans une migration de
réparation historique unique et tracée, avant de déclarer le journal append-only canonique. Aucun
UPDATE/DELETE de fact n'est autorisé dans le runtime normal. Finaliser le registre B avec toutes
les occurrences R0 et R1+, puis activer ses contraintes et le nouveau writer.
**Transactions/concurrency.** Freeze des writers et snapshot cohérent ; migration répétable et
rollback par transaction de lot, jamais trou durable de R ; writer futur insère R+1+fact+registre
atomiquement. Le SWITCH active alors le writer WA.6B et retire le port public acceptant B.
**Compatibilité historique.** Facts R0 explicitement marqués baseline ; pas de
réécriture des dates en prétendu timestamp d'Attach ; `recorded_at` de baseline = instant de
migration, provenance du snapshot conservée dans le ledger. **Tests/preuves.** Upgrade V18/V20,
historique R0 encore attaché, R0 détaché, R0 reattaché, U manquant mais récupérable, source absente
ou contradictoire => fail fermé ; comptages E/R/B, contraintes et facts-only replay exact.
**Non-objectifs.** Reconstitution spéculative d'événements passés. **DONE.** Zéro fact incomplet,
une baseline justifiée par stream historique, R continu, registre global complet, état replayé
égal à l'autorité et provenance de migration vérifiable.

**Résultat de la Wave 1 — WA.6A/WA.6B/WA.6D (2026-10-02).** Les migrations WRITE V21 et V22
ajoutent le registre permanent de B, la provenance `record_origin` et la réparation historique.
**Statut d'implémentation : WA.6A DONE ; WA.6B DONE ; WA.6D DONE sur les bases de test.**
V21 conserve temporairement la lisibilité SQL des anciens `DETACHED` sans U et crée la table
`external_identity_binding_baseline_evidence` pour les preuves E/U/B issues d'un snapshot/WAL
vérifié. V22 produit les baselines `ATTACHED@R0` marquées `MIGRATION_BASELINE`, complète U des
anciens `DETACHED` depuis l'`ATTACHED(E,B)` exact, remplit le registre depuis toutes les attaches,
vérifie la continuité E/R et l'état final contre l'autorité composite, puis rend U obligatoire et
lie les rows actives/facts au registre. Toute occurrence R0 détachée sans preuve E/U/B, tout
historique contradictoire ou tout stream R0 vide arrête la migration avec `MIGRATION BLOCKED` ;
aucun U n'est déduit du User courant, de READ ou d'une Command. Pour une telle base, appliquer
V21 sous writers Binding suspendus, charger l'évidence vérifiée avec sa `source_ref`, puis
appliquer V22 ; le nouveau writer ne démarre pas tant que V22 échoue.

Le port public est désormais `acquire(E,U)` et retourne le B généré par le writer. Une collision
UUID tente un nouveau candidat sans réserver l'ancien ; réservation, mutation active, R et fact
`ATTACHED` committent ensemble. Le detach lit U sur l'occurrence active exacte sous le lock court
du stream, puis supprime cette occurrence et émet `DETACHED(E,U,B,R+1)` dans la même transaction.
Un detach stale n'écrit ni stream nouveau, ni fact, ni R. Le lock pessimiste de Command,
`CURRENT_BINDING` et la forme E+B sans R de la Command restent inchangés. Les tests PostgreSQL
couvrent la base neuve, les upgrades V18/V20, R0 attaché/détaché/reattaché, l'échec fermé sans
preuve, l'unicité historique de B, collision, concurrence, rollback, échec d'append et reprise
avec un nouvel adapter ; les tests runtime Binding et Command et les règles d'architecture sont
réexécutés dans le reactor pertinent. Ce résultat décrit le code et les bases de test : la
complétude d'une base de déploiement se décide par son inventaire V21/V22, jamais par hypothèse.

**Correction du contrat SQL après `13ee62b4` — V23 (2026-10-02).** V20 conservait un CHECK
`fact_type IN (ATTACHED, DETACHED)` après V21 ; le domaine SQL était déjà rejeté par cette
contrainte séparée. V23 consolide ce CHECK et celui de provenance de V21 en un contrat final
unique : `R0 = ATTACHED + MIGRATION_BASELINE` ; `R>0 = (ATTACHED | DETACHED) + LIFECYCLE`.
Les tests PostgreSQL prouvent les trois formes acceptées, toutes les combinaisons interdites,
l'upgrade V22→V23 sans mutation métier et le bootstrap neuf. WA.6A : DONE ; WA.6B : DONE ;
WA.6D : DONE ; gap de contrat SQL : CLOSED ; WA.6C : NOT STARTED à cette baseline
(résultat WA.6C du 2026-10-02 documenté ci-dessus).

#### WA.6E — Forme canonique de CURRENT_BINDING DETACHED

**Prérequis.** WA.6B nouveaux facts et WA.6D historique complet.
**Objectif.** Fermer E1 sans perdre E2. **État initial réel.** READ V9 impose
`binding_id NOT NULL` et stocke l'ancien B sur DETACHED ; `CurrentBinding` et les tests l'attendent.
**État cible.** Une row par E, `binding_revision NOT NULL`; ATTACHED impose U+B non nuls ;
DETACHED impose U+B nuls et garde R. Aucune row reste distincte de DETACHED@R.
**Changements.** Migrations READ après V9 : EXPAND nullabilité de B, MIGRATE des rows, puis
contrainte/CONTRACT ; ne modifier aucune migration déjà appliquée. Modèle `CurrentBinding`, mapper JDBC,
`BindingFactConsumptionLocator`, apply/upsert et contrainte de shape ; un Detach fact garde B
pour l'histoire mais projette `(E,R,DETACHED,null,null,source_event_id)`. Self-service conserve
`getAttached` et peut répondre 404 pour absent et DETACHED, sans fusion interne des états.
**Migration/data strategy.** SWITCH consumer vers nouvelle forme, arrêter les anciens workers READ,
normaliser les rows DETACHED existantes par `UPDATE ... SET binding_id=NULL` en gardant E/R/source,
rejouer facts après backfill ; VERIFY zéro DETACHED avec B et zéro ATTACHED incomplet ; CONTRACT
nouvelle contrainte CHECK et suppression du NOT NULL seulement après preuve des données.
**Transactions/concurrency.** Upsert uniquement si R supérieure ; R inférieure stale ignorée ;
R égale et payload identique duplicate ; R égale divergent erreur. Claim/finalisation et apply
restent atomiques ; late commit et multi-worker ne ressuscitent pas B.
**Compatibilité historique.** Adapter de lecture transitoire accepte l'ancienne tombstone avant
normalisation ; aucun endpoint WRITE ne la consulte. **Tests/preuves.** ATTACHED E/R/U/B →
DETACHED E/R+1/null/null → REATTACHED E/R+2/U/B2 ; une row par E, absent ≠ DETACHED, duplicate,
stale, equal-divergent, replay déterministe, endpoint self-service ; remplacer l'assertion ancien
B du test `CurrentBindingPersistencePostgresTest` et les attentes associées des tests runtime.
**Non-objectifs.** Nouveau statut HTTP ou API de supervision. **DONE.** Schéma et données READ
imposent la forme canonique ; aucune projection DETACHED ne conserve B.

#### WA.6F — Cutover facts-only et retrait du bootstrap primaire

**Prérequis.** WA.6D replay historique exact ; WA.6E READ canonique et consumer activé.
**Objectif.** Supprimer la dépendance permanente de READ à l'autorité primaire pour R0.
**État initial réel.** `HistoricalBindingBootstrap` et `findRevisionZeroPage` lisent
`external_identities` au R0 et écrivent une projection `source_event_id=NULL`.
**État cible.** Discovery/Consumption incluent les facts baseline R0 ; chaque projection provient
d'un fact, avec son `event_id` ; rebuild `facts only → CURRENT_BINDING` exact. Aucun bootstrap
primaire au démarrage. **Changements.** EXPAND discovery pour R0 et source de baseline, appliquer
le même claim/idempotence/retry que R1+ ; migrer les rows READ R0 de `source_event_id=NULL` via
replay de baseline et vérifier l'identité de payload ; SWITCH le runtime ; retirer
`HistoricalBindingBootstrap`, `JdbcBindingFactDiscoveryAdapter.findRevisionZeroPage`, wiring/job
et exceptions d'architecture associées ; CONTRACT `source_event_id NOT NULL` pour toute row READ
issue d'un fact et la règle bootstrap obsolète.
**Migration/data strategy.** Consumer facts R0 d'abord ou replay complet sur une projection de
comparaison ; bascule seulement après égalité par E/R/state/U/B et nombre de rows, y compris
DETACHED. Ne supprimer le bootstrap primaire qu'après cette preuve et après que tous les workers
READ utilisent le nouveau consumer ; rollback de déploiement conserve les facts, l'ancien job reste
désactivé. **Transactions/concurrency.** Même scan sans watermark, claims et upsert par R que R1+ ;
late commit, retry, restart, multi-worker et stale/duplicate facts convergent.
**Compatibilité historique.** Baselines R0 sont traitées comme état migré, pas comme Attach observé
au temps R0. **Tests/preuves.** Rebuild sur schema READ vide pour attached, detached, reattach
même/autre U, V18/V20 migré, retry, restart, multi-worker, late commit, stale/duplicate ; absence
de lecture primaire dans le runtime READ et absence de READ côté WRITE.
**Non-objectifs.** Modifier les endpoints ou l'AuthZ. **DONE.** Tout E connu est reconstruisible
depuis les facts ; bootstrap primaire et ses ports supprimés.

#### WA.6G — Preuves de clôture et gate WA.7

**Prérequis.** WA.6A, WA.6B, WA.6D, WA.6C, WA.6E et WA.6F SWITCH/VERIFY/CONTRACT terminés.
**Objectif.** Fermer tous les gaps de l'audit et le remplacement WA.4 avant WA.7.
**État initial réel.** Audit `PASS WITH GAPS` et tests qui prouvent certaines anciennes formes.
**État cible.** Audit conservé comme preuve historique ; preuves nouvelles de l'alignement final.
**Changements.** Mettre à jour les guards d'architecture, fixtures et documentation de résultat ;
retirer seulement les anciens paths dont les consommateurs ont été migrés : lock exact pessimiste
du worker, surcharge publique `acquire(E,U,B)`, bootstrap primaire, ancien CHECK Detached, vieux
tests de tombstone et de blocage Command/Detach. Conserver la résolution exacte E+B autoritative
nécessaire aux contrôles WRITE et les ports V1 jusqu'à WA.7.
**Migration/data strategy.** Requêtes de comptage et d'égalité : streams ↔ registre ↔ facts ↔
authority ↔ projection ; aucun B répété entre occurrences, aucune R manquante, aucun U nul dans
un fact, aucune projection DETACHED avec B. Documenter la source des baselines et le rollback de
chaque migration. **Transactions/concurrency.** Rejouer les ordonnancements PostgreSQL de WA.6A–F,
y compris Detach committé pendant le travail Command, CAS réussi, CAS perdu, claim perdu, fact
append failure et deux Commands sous B. **Compatibilité historique.** V1 conservé exactement
jusqu'à WA.7 ; admission V2 et HTTP READ conservent leurs contrats.
**Tests/preuves.** Tests ciblés Testcontainers, migrations fresh/upgrade, `Wa67BindingArchitectureTest`,
`HexagonalArchitectureTest`, E2E Command/READ, reactor pertinent ; scans statiques : pas de
`FOR UPDATE` du binding actif dans le worker, pas de Command R, pas de WRITE sur
`CURRENT_BINDING`. **Non-objectifs.** WA.7/WA.8, REGISTRATION, délégation, actor≠subject, Keycloak,
Pot redesign, API de supervision, framework générique d'events, optimisation hors fence.
**DONE / gate WA.7.** Toutes les lignes suivantes sont prouvées `PASS` : B historique non
réutilisable ; autorité ATTACHED/DETACHED et R survivant au detach ; R contigu sans consommation
sur échec ; facts Attached et Detached exacts E/U/B/R append-only ; baseline/historique reconstruits
depuis facts ; CURRENT_BINDING ATTACHED U+B non nuls et DETACHED U+B nuls avec R, absence distincte ;
Command porte E+B sans R, fence logique `(E,B)` ; lock pessimiste long retiré et CAS au commit ;
Command vs Detach, reattach même U, reattach autre U, et sans concurrence ; CAS perdu rollbacke
Pot/Event/outcome succès ; WRITE ne consomme jamais CURRENT_BINDING ; admission V2 inchangée,
`CALLER_IDENTITY_NOT_CURRENT` non-oracle ; tests PostgreSQL ciblés, architecture et reactor pertinent
verts. **WA.7 ne commence pas avant cette gate.**

**Traçabilité exhaustive de l'audit.** A1/J1 → WA.6A, WA.6C, WA.6G (registre et stale B) ;
D1 → WA.6B, WA.6D, WA.6F, WA.6G (U des facts, baseline, replay) ; E1 → WA.6E–WA.6G
(shape READ) ; H1 → WA.6C/WA.6G (remplacement explicite demandé du lock). B1/C1/D2/E2/
F1/G1/I1/K1/L1 classés `NONE` → préservation vérifiée par WA.6A–WA.6G selon leurs frontières :
R, autorité, apply, distinction absent/DETACHED, envelope, admission, fence `(E,B)`,
actor==subject, séparation READ/WRITE. WA.1/WA.3/WA.5 n'ont aucune migration intrinsèque ;
WA.2, WA.4 et WA.6 ont chacun remédiation et preuve de sortie. Aucune modification hors canon,
audit ou demande explicite de fence optimiste n'est incluse.

### WA.7 — Contract des représentations legacy

#### Verification scope (déclaré avant modification du code)

- Expected production impact : migration WRITE `recorded_commands`, modèle/persistence/exécution
  Command, loader/projector/GET `COMMAND_RESULT`, gate de migration READ.
- Primary slice : COMMAND (`./mvnw -pl runtime-command-consumption-worker -am test`).
- Secondary slices : PROJECTION et WEB (`./mvnw -pl runtime-task-consumption-worker,runtime-web-api -am test`).
- Forbidden production impact : EVENT, BINDING et LKV ; aucune modification du canon, des migrations
  appliquées, de Registration ou de Keycloak.
- Required local proof : Command target round-trip, worker E+B/fence, projection exact-E, admission,
  migrations WRITE et READ PostgreSQL fresh/upgrade et rejets fail-closed.
- Global architecture gate : REQUIRED (`./mvnw -pl architecture-tests -am test`) pour la clôture WA.
- Full reactor : REQUIRED (`./mvnw test`) après les tranches et la gate d'architecture, pour cette
  clôture de Wave majeure.
- Database verification : HISTORICAL ciblé pour l'upgrade V23→V24 et READ V13→V14 ; fresh install
  jusqu'au schéma final. Les migrations déjà appliquées restent immuables.
- Next mandatory global gate : WA.7 avant tout travail WA.8, puis preuve finale de Wave.
- Escalation conditions : changement de comportement d'un autre runtime ou d'un contrat partagé
  hors COMMAND/PROJECTION/WEB ; la tranche correspondante doit être annoncée avant expansion.

**WA.7 — DONE (preuve logicielle, 2026-10-02).** Le checkout alternatif part de
`1446628aafc1b3ca65f08b0039bc957081afbc6d`, working tree initial propre, divergence
distante `0/0`. WRITE V24 verrouille `recorded_commands`, refuse V1 pending ou terminale,
version inconnue, target invalide et slot Command incohérent avant DDL, puis supprime les cinq
colonnes/discriminants V1 et impose les neuf colonnes canoniques non nulles. READ V14 refuse les
artifacts `COMMAND_RESULT` V1, mixed ou malformés sans les modifier. Les tests PostgreSQL
`RecordedCommandContractionMigrationPostgresTest`, `PrimaryMigrationsPostgresTest` et
`CommandResultExactIdentityCutoverMigrationPostgresTest` passent : fresh, upgrade V23→V24 et
V13→V14, rejets fail-closed et shape final. La persistance Command target round-trip, le worker
E+B, les fences et rollbacks, la projection exact-E, le GET non-oracle et le self-service binding
passent dans les suites des slices COMMAND, PROJECTION et WEB. Les guards d'architecture passent ;
le scan du Java de production ne trouve plus les symboles V1 ciblés. Aucun impact de production
hors COMMAND/PROJECTION/WEB n'a été nécessaire.

Exécution (depuis `app/`, Java 21, cache Maven local, `-o -q`) :
`./mvnw -pl infra-persistence-jpa,infra-read-persistence -am test -Dtest=RecordedCommandContractionMigrationPostgresTest,CommandResultExactIdentityCutoverMigrationPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`
PASS ; `./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker,runtime-web-api -am test`
PASS ; `./mvnw -pl architecture-tests -am test` PASS ; `./mvnw test` PASS. Le premier essai
du reactor pertinent a relevé deux assertions de migration historique comptant V24 au lieu de
s'arrêter à V23 ; leurs cibles Flyway ont été corrigées, puis le reactor a passé. Avant la remise
en service de Docker, un essai Testcontainers avait échoué sans preuve PostgreSQL. Aucune suite
n'a été ignorée ou désactivée pour obtenir le résultat final.

Ces tests prouvent la logique fail-closed sur des fixtures PostgreSQL ; **aucun preflight sur une
base de production/déployée n'a été exécuté**. Le cutover opérationnel exige l'arrêt des anciens
writers, la mesure du backlog V1 à zéro et l'exécution réussie des gates WRITE et READ sur les
bases concernées avant le déploiement du runtime target-only.

**Prérequis.** Gate WA.6G validée ; WA.5 produit uniquement le nouveau format, WA.6 sert READ, backlog legacy drainé et
mesuré à zéro.

**Objectif.** Retirer les branches de compatibilité devenues inutiles.

**Changements.** Supprimer `auth_user_id` comme identité d’admission, les snapshots d’AuthZ métier
figés, le resolver primaire de l’admission, les readers/writers legacy, les colonnes et contraintes
transitoires, puis rendre E+B obligatoires. Nettoyer wiring, fixtures et documentation.

**Preuves.** Requêtes de précondition avant migration destructive, migrations depuis une base
pré-WA, scan des symboles/colonnes legacy, reactor complet, tests de démolition et rollback de
déploiement documenté avant le point de contraction.

**DONE.** Une seule représentation Command et une seule autorité User/Identity subsistent.

### WA.8 — Preuves E2E, architecture et clôture

**Prérequis.** WA.1–WA.7.

**Objectif.** Prouver la chaîne réelle et fermer WRITE_ADMISSION avant REGISTRATION.

**Preuves minimales.** HTTP Command → row durable E+B → worker → résultat → `COMMAND_RESULT` READ ;
E inconnue admise puis rejetée ; B ancien après detach/reattach rejeté ; B2 évalué normalement ;
concurrence Command/Detach sans TOCTOU ; self-service E→U+B exclusivement READ ; restart et
multi-worker ; aucune lecture primaire au POST ou aux GET ; guards d’ownership et reactor complet.

**DONE.** Matrice WA1–WA11 complète, aucun chemin legacy actif, documentation opérationnelle et
architecture alignées. REGISTRATION peut commencer sur ces fondations.

**WA.8 — DONE (preuve finale, 2026-10-02).** Scope : primaire WEB pour le GET
`COMMAND_RESULT` ; aucune slice secondaire de production, impact interdit COMMAND/EVENT/
PROJECTION/BINDING/LKV. Le gate d'architecture et le reactor complet sont requis pour la clôture
de Wave. La recherche de contre-exemple a trouvé qu'un payload READY dont `commandId` ou
`artifactKey` contredisait la clé demandée pouvait être servi après corruption durable. Le GET
vérifie désormais clé de projection, clé d'artifact et `commandId` ; le test de régression injecte
des valeurs divergentes. Aucune nouvelle architecture n'a été introduite et il n'y a pas eu de
franchissement inattendu de slice.

La matrice finale s'appuie sur `CommandAdmissionPostgresTest` (E+B admise, E inconnue admise,
B malformé rejeté, aucune lecture primaire), `CommandConsumptionPostgresTest` (stale B,
detach/reattach même U ou autre U, ordre detach/fence, rollback Pot/Event/outcome, deux Commands,
retry, claim perdu et fence sans avancement R ni fact), les tests runtime restart/multi-worker,
`CommandCompletionE2EPostgresTest` (HTTP → Command → Event → projection → GET pour APPLIED,
REJECTED, FAILED, résultat visible après detach/reattach, self-service READ et absence de SELECT
primaire au GET), `CommandResultTest` (exact E, pending/absent/nonowner non-oracle, clés durables
divergentes) et les guards d'ownership. Les chemins rares inspectés incluent retry/restart,
duplicate delivery, late commit, malformed durable rows, equal-revision divergence, stale
projection et transaction boundaries ; les suites pertinentes du reactor les exercent.

Exécution finale (depuis `app/`, Java 21, cache Maven local, `-o -q`) :
`./mvnw -pl engine-command-result -am test -Dtest=CommandResultTest -Dsurefire.failIfNoSpecifiedTests=false`
PASS ; `./mvnw -pl runtime-web-api -am test` PASS ;
`./mvnw -pl architecture-tests -am test` PASS ; `./mvnw test` PASS.
`git diff --check` PASS. **WRITE_ADMISSION implementation/proof scope: CLOSED.**

## 4. Matrice de traçabilité WA1–WA11

| Invariant | Lots principaux |
|---|---|
| WA1 — AuthN/capture sans décision métier | WA.3, WA.5 |
| WA2 — zéro lecture primaire à l’admission | WA.5, WA.8 |
| WA3 — admission ouverte | WA.5, WA.8 |
| WA4 — E et B capturés, jamais U résolu | WA.3, WA.5, WA.7 |
| WA5 — sémantique Command sous occurrence B | WA.3–WA.5 |
| WA6 — BindingId d’occurrence | WA.1, WA.2 |
| WA7 — forme synchrone, vérité au worker | WA.5 |
| WA8 — rejet unique non-oracle | WA.4, WA.8 |
| WA9 — self-service exclusivement READ | WA.6, WA.8 |
| WA10 — lifecycle et faits occurrence-aware | WA.1, WA.2, WA.6 |
| WA11 — continuité jusqu’au commit | WA.4, WA.8 |

## 5. Validation documentaire

- séquencement WA.1–WA.8 enrichi par WA.6A–WA.6G avant WA.7 ;
- stratégie expand/consume/produce/read/contract conservée ;
- couverture WA1–WA11 explicite ;
- aucune modification de canon ;
- `git diff --check` propre ;
- plan WA.6 découpé en huit sous-étapes atomiques avec frontières transactionnelles, migrations,
  runtime, tests et critères de clôture explicites ;
- distinction V1/V2 de `COMMAND_RESULT` explicite, sans migration exact-E de V1 ni fallback
  heuristique ;
- audit du plan validé par 78 tests existants ciblés verts : modèle User/Identity (3), modèle
  `COMMAND_RESULT` (4), mapper `RecordedCommand` (4), adapter PostgreSQL `RecordedCommand` (15),
  chaîne PostgreSQL `COMMAND_RESULT` (1) et guards `HexagonalArchitectureTest` (51) ;
- liste de fichiers modifiés par ce cadrage limitée à ce `Step_Plan.md` ; `WA6_Audit.md` reste le
  livrable d’audit préexistant non suivi.

Blocking questions : **0**

Les résultats et métriques WA.6 ci-dessus sont des preuves historiques antérieures à l'audit
d'alignement. La gate WA.6G définit désormais la cible et aucune de ses preuves n'est déclarée
exécutée par cette seule modification documentaire.

## 6. Résultat de la Wave 2 — WA.6E → WA.6F → WA.6G (2026-10-02)

Les mentions antérieures « WA.6 CLOSED » décrivent l'ancien périmètre WA.6.1–WA.6.8. Le présent
résultat concerne le réalignement WA.6A–WA.6G du canon fermé ; les preuves historiques restent
consultables plus haut sans être confondues avec la forme finale.

**WA.6E — DONE.** READ V10 élargit `binding_id` à nullable ; V11 normalise les tombstones
historiques DETACHED en `user_id=NULL, binding_id=NULL`, conserve E/R/source et impose le CHECK
ATTACHED U+B non nuls / DETACHED U+B nuls. Le modèle, le mapper et le locator projettent le
Detach fact `(E,U,B,R)` en `(E,R,DETACHED,NULL,NULL,event_id)`. Une row absente reste distincte
d'une tombstone et le self-service conserve son 404 pour ces deux cas. L'upsert garde les règles
R supérieure/appliquée, R inférieure/stale, R égale identique/duplicate et R égale divergente/
erreur. La preuve PostgreSQL couvre fresh V11, upgrade V9 d'une ancienne tombstone, rejets SQL
des formes incomplètes, cycle attach/detach/reattach, source et self-service ; le reactor ciblé
WA.6E est vert avant le travail WA.6F.

**WA.6F — DONE.** Le curseur de discovery accepte R0 ; les facts R0 `MIGRATION_BASELINE` passent
par la même identité Consumption `event_id`, claim, retry, apply et redécouverte keyset éphémère
que R1+. La provenance Consumption versionne le *fact immuable* à 1 ; la révision métier R=0
reste dans le fact et `CURRENT_BINDING`. READ V12 vérifie chaque row présente contre le fact
exact E/R/état/U/B et son éventuel `source_event_id`, renseigne les anciens R0 depuis le vrai
`event_id`, puis impose `source_event_id NOT NULL`. La migration échoue fermée si un journal
nécessaire manque ou contredit la projection ; elle ne lit pas l'autorité active WRITE.
Le test PostgreSQL reconstruit READ vide depuis les facts seuls pour R0 attaché, detached,
reattach même U et autre U, compare par E/R/état/U/B/source et nombre de rows, puis recommence
après vidage de READ et des slots avec un nouvel orchestrateur. Les tests existants de retry,
late commit, claim perdu et multi-worker restent verts. `HistoricalBindingBootstrap`,
`findRevisionZeroPage`, le port/source, le wiring et les propriétés du job primaire ont été
supprimés ; le guard d'ownership ne permet plus la lecture de `external_identities` ou du stream
dans l'adapter de discovery READ.

**WA.6G — DONE.** Les guards `Wa67BindingArchitectureTest` et
`HexagonalArchitectureTest`, le reactor pertinent `architecture-tests,runtime-binding-consumption-worker -am test -q`
et ses suites PostgreSQL sont verts. Le test de démolition compare désormais bootstrap et upgrade
jusqu'à READ V12 et confirme l'historique des migrations déjà appliquées. Les suites historiques
WA.6A/B/D/C demeurent dans ce reactor : registre B permanent, facts E/U/B/R et baseline,
continuité de R, rollback d'append, fence optimiste Command V2, claim perdu, rejet non-oracle,
compatibilité V1 et absence d'accès WRITE à `CURRENT_BINDING`. Le reactor complet et le contrôle
final Git passent également : `./mvnw test -q` et `git diff --check` ont réussi. La comparaison
SQL facts-only du test runtime vérifie `distinct on (E)` à R maximale contre chaque row READ,
y compris état, U, B et `source_event_id`, avec zéro différence et le même nombre d'E. Les tests
PostgreSQL de migration couvrent base neuve, ancienne tombstone V9, provenance R0 V9→V12 et
bootstrap/upgrade historique ; les tests Command rejouent les ordonnancements et rollbacks WA.6C.
Les scans de production ne trouvent plus `HistoricalBindingBootstrap`, `findRevisionZeroPage` ou
`lockCurrentBinding`, et le guard d'ownership réserve l'écriture `CURRENT_BINDING` à son adapter
READ. Aucun test cassé n'a été désactivé ; les anciens tests du bootstrap primaire ont été
remplacés par la reconstruction depuis facts et les assertions de tombstone ont été canonisées.

**WA.6 STATUS: CLOSED.** WA.6E, WA.6F et WA.6G sont DONE. Les dettes LOW ci-dessous ne bloquent
pas cette clôture. Aucun code WA.7 ou WA.8 n'a été introduit.

**Rollback des migrations READ.** V10 est additive : un rollback applicatif vers l'ancien writer
reste possible tant que V11 n'est pas appliquée. Pour V11, suspendre les workers READ avant
MIGRATE/SWITCH ; l'ancien binaire ne doit pas redémarrer après la normalisation car il écrit B
sur DETACHED. Un rollback après V11 restaure le snapshot READ V9 ou redéploie le binaire canonique
et rejoue les facts ; ne jamais recréer l'ancien B depuis la tombstone. Pour V12, conserver les
facts et la sauvegarde pré-migration ; une erreur annule la transaction Flyway entière. Après
CONTRACT `source_event_id NOT NULL`, un retour au bootstrap primaire est interdit ; reconstruire
READ et ses slots depuis le journal sur une base restaurée ou poursuivre avec le consumer
facts-only. Les writers de binding doivent être suspendus pendant la réparation historique V21/V22
déjà décrite ci-dessus, puis les workers READ arrêtés pendant les migrations V10–V12 et réactivés
après vérification du journal et de la projection. Les facts R0 restent marqués
`MIGRATION_BASELINE`, jamais interprétés comme Attach observés historiquement.

Les dettes `LOW` historiques sont consignées dans [`Step_debt.md`](Step_debt.md) et ne sont pas
modifiées par cette Wave. `Step_Canon.md` est inchangé. **WA.7 STATUS: NOT STARTED.**

### Correction de preuve du cutover WA.6F (audit externe, 2026-10-02)

**Finding MAJOR corrigé.** La vérification de V12 allait de chaque row READ présente vers un fact
de même E/R/payload. Elle pouvait laisser passer une E absente de READ ou une projection à une
révision réelle mais non terminale. V12 appartient à la baseline commitée et poussée ; elle reste
inchangée. La migration READ forward-only V13 ajoute une gate fail-closed avant que le déploiement
ne puisse considérer le cutover comme achevé.

V13 sélectionne le fact de révision maximale par `(issuer,subject)` avec `DISTINCT ON`, exige la
même cardinalité entre les E terminales et `CURRENT_BINDING`, puis compare les deux ensembles par
`FULL JOIN`. Elle rejette une E manquante ou supplémentaire, une R stale, un état différent, U/B
divergents pour ATTACHED, U/B non nuls pour DETACHED et toute provenance différente de l'`event_id`
terminal. Le résultat ne modifie aucune row métier ; toute divergence lève
`CURRENT_BINDING differs from terminal binding fact journal` et fait échouer Flyway. La seule
réparation automatique de provenance reste celle de V12 pour une row dont le payload a été prouvé
exact. La migration d'une base avec facts existants et READ vide échoue donc jusqu'à ce que le
consumer facts-only ait reconstruit et vérifié la projection ; aucun bootstrap primaire n'est
réintroduit.

Les tests PostgreSQL de V13 prouvent l'échec transactionnel pour E manquante, projection stale,
E supplémentaire, état/U/B divergents et `source_event_id` incorrect, ainsi que le succès pour
R0 baseline, ATTACHED, DETACHED et reattach avec provenance terminale exacte. Les tests runtime
facts-only restent la preuve de reconstruction depuis READ vide ; V13 est la preuve de cutover
sur la base à migrer. Les tests ciblés READ, le reactor pertinent
`infra-read-persistence,architecture-tests,runtime-binding-consumption-worker -am test -q` et
le reactor complet `./mvnw test -q` sont verts avec V13 ; le test de démolition matérialise la
projection facts-only avant cette gate sur l'upgrade historique. **WA.6F : DONE ; WA.6G : DONE ;
WA.6 STATUS: CLOSED ; WA.7 STATUS: DONE ; WA.8 STATUS: DONE.**
