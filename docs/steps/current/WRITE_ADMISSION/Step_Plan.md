# WRITE_ADMISSION — Plan d’implémentation

```text
Step: WRITE_ADMISSION
Phase: IMPLEMENTATION PLANNED
Authority: Step_Canon.md — WA1–WA11
Sequencing: WA.1 → WA.2 → WA.3 → WA.4 → WA.5 → WA.6 → WA.7 → WA.8
Strategy: expand → consume → produce → read → contract
```

## 1. Baseline et portée

- branche : `v2-make-it-pull` ;
- HEAD local et distant : `00de08c3fbe3aef9290b7183d9b85331bd6b7582` ;
- divergence : `0/0` ;
- working tree initial : propre ;
- autorité normative : [`Step_Canon.md`](Step_Canon.md), WA1–WA11 ;
- audit factuel : [`step_audit.md`](step_audit.md).

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
- Les endpoints GET lisent exclusivement READ/projections, jamais les tables WRITE, les outcomes
  primaires ou Consumption.
- La projection self-service est strictement indexée par l’ExternalIdentity authentifiée et n’est
  jamais consommée par un worker WRITE.

## 3. Lots d’implémentation

### WA.1 — Ownership User/Identity et contrats neutres

**Objectif.** Installer les fondations canoniques partagées par WRITE_ADMISSION et REGISTRATION,
sans changer encore le comportement runtime.

**Changements.** Créer ou stabiliser l’ownership neutre de `User`, `PocomaUserId`,
`ExternalIdentity`, `BindingId`, `ExternalIdentityAttached(E,U,B)` et des ports User/Identity.
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

### WA.6 — READ : résultat Command et binding self-service

**Prérequis.** WA.5 ; faits User/Identity avec B disponibles depuis WA.1–WA.2.

**Objectif.** Supprimer toute résolution primaire des GET concernés et permettre au client de
retrouver son occurrence courante.

**Changements.** Faire porter à la chaîne `COMMAND_RESULT` une ownership READ dérivée de l’E capturée,
sans dépendre de `auth_user_id`. Ajouter une projection User/Identity minimale construite depuis les
faits Attached/Detached et un GET self-service `E authentifiée -> U+B`. Les apply sont
occurrence-aware : un detach B1 ou un fait stale ne retire jamais B2.

**Preuves.** Les controllers et use cases GET ne dépendent que de READ. Une autre E du même U ne lit
pas le résultat. Attach B1, detach B1, attach B2 converge vers B2 ; un événement stale ne réactive ni
ne supprime la mauvaise occurrence. Le worker Command n’injecte jamais cette projection.

**DONE.** Résultat Command et identité self-service sont construits exclusivement depuis READ.

### WA.7 — Contract des représentations legacy

**Prérequis.** WA.5 produit uniquement le nouveau format, WA.6 sert READ, backlog legacy drainé et
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

- séquencement WA.1–WA.8 inchangé ;
- stratégie expand/consume/produce/read/contract conservée ;
- couverture WA1–WA11 explicite ;
- aucune modification de canon ;
- `git diff --check` propre ;
- liste de fichiers modifiés limitée aux deux `Step_Plan.md` de WRITE_ADMISSION et REGISTRATION.

Commit prévu : `docs: refine registration implementation plan`

Push prévu : `origin/v2-make-it-pull`

Blocking questions : **0**

Le commit et le push sont seulement annoncés : ils ne sont pas exécutés pendant cette session de
planification.
