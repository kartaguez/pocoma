# Pocoma V3 — Gouvernance et attribution de toutes les identités d'objets

## Phase A : Inventaire exhaustif, Matrice de conformité et Architecture cible

Statut : AUDIT EXHAUSTIF ET PLAN DE MIGRATION  
Base Git : `main` (`1c5140fe`), branche de travail `feat/v3-identity-governance`  
Politique de vérification : [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)

---

## 1. Justification de l'exhaustivité de l'inventaire

L'inventaire ci-dessous a été établi par rapprochement croisé exhaustif entre :

1. **Contrats publics HTTP (`supra-web-write`, `supra-web-read`, `contract-write-admission`, `contract-read-query`, `contract-authentication`)** :
   - 2 endpoints publics d'écriture : `POST /api/v3/commands` (13 opérations `PotCommand.Intent`) et `POST /api/v3/identity/requests` (2 opérations actives : `Registration`, `Unlink` ; `Link` retirée en L7.2).
   - 10 endpoints publics de lecture (`GET /me/binding`, `GET /pots/{potId}`, `GET /pots/{potId}/versions/{version}`, `GET /pots/{potId}/versions/{version}/balance`, `POST /pots/{potId}/versions/{version}/expenses/query`, `GET /me/pots`, `GET /commands/{requestId}/result`, `GET /identity/requests/{requestId}/result`, `GET /invitations/by-creation-request/{requestId}`, `POST /invitations/available-shareholders/query`) + 2 endpoints de diagnostic (`stage-handoffs`).
2. **Entités et objets de valeur du domaine (`domain-pot`, `domain-identity`, `domain-consumption`, `domain-projection`)** :
   - Tous les 108 fichiers Java des 8 modules `domain-*` et `contract-*` inspectés.
3. **Schémas SQL actifs WRITE et READ (`infra-postgres-write`, `infra-postgres-read`, `scripts/v3/`)** :
   - **37 tables WRITE** (`V1__initial_schema.sql`) + 1 table DEV fixture (`i5_fixture_identity`).
   - **14 tables READ** (`V1__initial_schema.sql`) + 1 table de maintenance persistante (`public.read_maintenance_state`) + 1 table DEV probe (`i5_read_probe_effect`).
4. **Producteurs d'événements et moteurs (`engine-admit-*`, `engine-write-*`, `engine-materialize-*`, `orchestrator-consumption`, `supra-polling-consumption`)** :
   - 7 familles consommables (`COMMAND`, `IDENTITY_REQUEST`, `POT_EVENT`, `INVITATION_EVENT`, `IDENTITY_EVENT`, `COMMAND_COMPLETION_EVENT`, `IDENTITY_COMPLETION_EVENT`) et 9 `ConsumptionId` de production.
5. **Clients, collections et scripts (`scripts/bruno-playground/`, `scripts/bruno-v3/`, `scripts/k6/`, `scripts/local/`, `scripts/performance/`, `scripts/operations/`)**.

Aucune famille d'objets persistés ou chemin de création actif n'est exclu ou laissé non classifié.

---

## 2. Inventaire exhaustif et matrice de conformité

Verdicts normatifs utilisés :
- **`conforme`** : identité métier interne générée exclusivement par l'engine/autorité responsable au traitement, jamais fournie par le client.
- **`anomalie à corriger`** : identité d'un nouvel objet métier interne actuellement fournie/choisie par le client.
- **`identifiant externe légitime`** : identité issue d'un fournisseur d'authentification externe (JWT/OIDC), distincte de tout ID interne.
- **`identifiant technique légitime`** : identifiant de corrélation, d'idempotence, de révision technique, d'événement, de consommation ou de diagnostic sans violation de gouvernance métier.
- **`qualification supplémentaire nécessaire`** : non applicable à ce stade (toutes les familles actives sont classifiées ; les preuves PostgreSQL réelles en Phase C restent requises sur environnement disposant de Docker/PostgreSQL).

### 2.1 Objets métier Pot & Invitation

| Catégorie / Objet | Identifiant exact | Propriétaire | Lieu et moment actuels de génération | Chemins publics et internes de création | IDs exigés en entrée (état initial) | Persistance et restitution | Retries, collisions, rollbacks | Compatibilité historique | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **Pot** | `PotId` (`UUID`, `v3_pot_identity.pot_id`) | Pocoma (autorité WRITE Pot) | **Client** (Bruno, k6, scripts, tests) avant appel HTTP | Public : `POST /api/v3/commands` (`CreatePot`). Interne : `ExecutePotCommand.prepareCreatePot` | **`potId` (UUID top-level obligatoire)** | Persisté dans `v3_command.pot_id`, `v3_pot_identity`, `v3_pot_version`, `v3_pot_event`, `read_*`. Restitué en écho dans `read_command_result.pot_id` | Collision -> rejet métier `ALREADY_EXISTS` ; concurrence sérialisée par `pg_advisory_xact_lock` + `FOR UPDATE` | Commandes historiques `v3_command` ont `pot_id NOT NULL` | **`anomalie à corriger`** |
| **Shareholder** | `ShareholderId` (`UUID`, `v3_shareholder_identity.object_id`) | Pocoma (autorité WRITE Pot) | **Client** avant appel HTTP | Public : `POST /api/v3/commands` (`AddShareholders`). Interne : `ExecutePotCommand.decideAddShareholders` (aucune création implicite sur `CreatePot`) | **`payload.targets[].id` (UUID obligatoire par cible)** + `potId` existant | Persisté dans `v3_command.payload`, `v3_shareholder_identity`, composants `v3_shareholder_*`, `read_*`. **Non restitué dans `CommandTerminalResult`** | Doublon intra-lot -> 400 ; collision DB -> rejet `ALREADY_EXISTS` ; rollback annule tout | Commandes historiques `v3_command.payload` (v1/v2) portent `targets[].id` | **`anomalie à corriger`** |
| **Expense** | `ExpenseId` (`UUID`, `v3_expense_identity.object_id`) | Pocoma (autorité WRITE Pot) | **Client** avant appel HTTP | Public : `POST /api/v3/commands` (`CreateExpense`). Interne : `ExecutePotCommand.decideCreateExpense` | **`payload.id` (UUID obligatoire)** + `potId`, `payerId`, `participants[].id` existants | Persisté dans `v3_command.payload`, `v3_expense_identity`, composants `v3_expense_*`, `read_*`. **Non restitué dans `CommandTerminalResult`** | Collision DB -> rejet `ALREADY_EXISTS` ; rollback annule tout | Commandes historiques `v3_command.payload` (v1) portent `details.id` et `financials.id` | **`anomalie à corriger`** |
| **Invitation** | `invitationId` (`UUID`) + `token` (256-bit `SecureRandom`) / `tokenHash` (SHA-256) / `protectedLink` (AES-256-GCM) | Pocoma (autorité WRITE Pot/Invitation) | **Engine WRITE** (`JdbcInvitationStore.prepare`, appelé par `ExecutePotCommand.prepareCreateInvitation`) au traitement | Public : `POST /api/v3/commands` (`CreateInvitation`). Aucune création implicite | `potId` existant, `expiresAt` uniquement (aucun `invitationId` ni token en entrée) | Persisté dans `v3_invitation` (`creation_request_id` unique) -> `v3_invitation_event` -> `read_current_invitation`. Restitué au créateur seul via `GET /api/v3/invitations/by-creation-request/{requestId}` | Rollback jette `invitationId` et `token` ; expiration revérifiée sous verrou (`clock_timestamp()`) et par trigger différé `v3_invitation_final_expiry` | Amendé dès SPEC-01 §8 (remplace toute variante client) | **`conforme`** |

### 2.2 Objets métier Identité & Binding

| Catégorie / Objet | Identifiant exact | Propriétaire | Lieu et moment actuels de génération | Chemins publics et internes de création | IDs exigés en entrée | Persistance et restitution | Retries, collisions, rollbacks | Compatibilité historique | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **PocomaUser** | `PocomaUserId` / `UserId` (`UUID`, `v3_pocoma_user.user_id`) | Pocoma (autorité WRITE Identity) | **Engine WRITE** (`ExecuteIdentityRequest` -> `UUID::randomUUID`) au traitement de `Registration` | **Création implicite** via `POST /api/v3/identity/requests` (`Registration`). Aucun endpoint `CreateUser` | Aucun ID en entrée (`{"operation":"Registration"}`) | Persisté dans `v3_pocoma_user`, `v3_eui_version.user_id`, `v3_pot_details.creator_id`, `v3_shareholder_details.user_id`. **Jamais exposé publiquement** | Rollback annule l'insertion ; collision UUID -> erreur technique + retry avec nouvel UUID ; survit à `Unlink` | Invariant fondamental SPEC-02 | **`conforme`** |
| **ExternalUserIdentity (ID interne)** | `ExternalUserIdentityId` (`UUID`, `v3_eui_authority.eui_id`) | Pocoma (autorité WRITE Identity) | **Engine WRITE** (`JdbcIdentityAuthority.lockInCanonicalOrder` -> `UUID.randomUUID()`) au premier verrouillage canonique de `(issuer, subject)` | **Création implicite** sur `Registration`, `Unlink`, ou verrouillage d'acteur `PotCommand` (`ON CONFLICT (issuer, subject) DO NOTHING`) | Aucun `eui_id` en entrée | Persisté dans `v3_eui_authority`, `v3_eui_version`, `v3_identity_request_result.target_eui_id`, `read_current_binding.eui_id`. **Jamais exposé publiquement** | `ON CONFLICT DO NOTHING` garantit l'unicité par `(issuer, subject)` et la stabilité de `eui_id` après `Unlink` | Invariant SPEC-02 | **`conforme`** |
| **ExternalIdentity (clé externe)** | `(issuer, subject)` (`ExternalIdentity`, `PotCommand.Eui`) | IdP externe (OIDC / JWT) | IdP externe avant authentification | Extrait du principal JWT (`AuthenticatedExternalPrincipal`) ; cible sur `Unlink` | Principal JWT authentifié (`issuer`, `subject`) | Clé naturelle de `v3_eui_authority`, `v3_command`, `v3_identity_request`, `read_current_binding` | Clé externe unique `(issuer, subject)` distincte de `eui_id` et `user_id` | Stable | **`identifiant externe légitime`** |
| **Binding (occurrence)** | `BindingToken` (`String`, 256-bit `SecureRandom` base64url, `v3_eui_version.binding_token`) | Pocoma (autorité WRITE Identity) | **Engine WRITE** (`SecureBindingTokenGenerator.generate()`) au traitement de `Registration` | **Création implicite** sur `Registration` (remis à `null` sur `Unlink`). Pas d'entité autonome `BindingId` | Aucun en entrée de `Registration` ; exigé comme preuve de session sur les commandes et lectures Pot | Persisté dans `v3_eui_version` (contrainte `UNIQUE` globale), matérialisé dans `read_current_binding`. Restitué uniquement via `GET /api/v3/me/binding` | Rollback jette le token ; collision unique SQL -> rollback + retry ; `toString()` et logs SQL redigés | Stable | **`conforme`** |
| **LinkProposal** | `link_request_id` (`UUID`, `v3_link_proposal`) | Pocoma (retiré) | Inactif (`Link` retiré en L7.2, rejeté HTTP 400) | Aucun chemin actif | N/A | Table conservée en V1 uniquement pour compatibilité de schéma historique | Triggers immuables | Historique conservé | **`conforme`** (retiré/inactif) |

### 2.3 Requêtes durables, Associations d'admission, Révisions, Événements, Résultats, Consommation et Projections

| Catégorie / Objet | Identifiant exact | Propriétaire | Lieu et moment de génération | Chemins de création | IDs exigés en entrée | Persistance et restitution | Retries, collisions, rollbacks | Compatibilité historique | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **Command** | `requestId` (`UUID`, `v3_command.request_id`) | Admission WRITE | `CommandAdmission.newAssociation` (`UUID.randomUUID()`) à l'admission | Public : `POST /api/v3/commands` | `Idempotency-Key` (header) | `v3_command`, `v3_request_position`. Restitué en `202 Accepted {"requestId": ...}` | Idempotence S-03 via `v3_admission_association` (`scope_key_fingerprint`) : même clé + même intention -> même `requestId` ; intention différente -> `409` | Immuable (`v3_command_intention`) | **`identifiant technique légitime`** |
| **IdentityRequest** | `requestId` (`UUID`, `v3_identity_request.request_id`) | Admission WRITE | `IdentityRequestAdmission.newAssociation` (`UUID.randomUUID()`) à l'admission HTTP (ou appelant interne `CONTROLLED_INTERNAL` non exposé HTTP) | Public : `POST /api/v3/identity/requests` | `Idempotency-Key` (header) | `v3_identity_request`, `v3_request_position`. Restitué en `202 Accepted {"requestId": ...}` | Idempotence S-03 identique | Immuable | **`identifiant technique légitime`** |
| **AdmissionAssociation** | `request_id` (`UUID`), `scope_key_fingerprint` / `intent_fingerprint` (`byte[32]` HMAC-SHA256) | Admission WRITE | `CommandAdmission` / `IdentityRequestAdmission` + `HmacAdmissionProtection` à l'admission | Implicite sur toute admission HTTP | `Idempotency-Key` (header légitime) | `v3_admission_association` | `ON CONFLICT (scope_key_fingerprint) DO NOTHING` ; comparaison en temps constant | Immuable | **`identifiant technique légitime`** |
| **Composantes historisées & Révisions** | `revision_id` (`BIGINT GENERATED ALWAYS AS IDENTITY`), `local_version`, `active_from`, `active_to`, `v3_pot_version.version`, `v3_expense_participant` | PostgreSQL / WRITE Pot | PostgreSQL `IDENTITY` + transitions domaine dans `ExecutePotCommand` | Implicite à chaque mutation `CHANGED` d'un Pot | `observedVersion` (référence optimiste) | 8 tables `v3_*` + `v3_pot_version` + `v3_expense_participant` | CAS SQL `v3_pot_cas` (`40001`), index uniques partiels `active_to IS NULL`, rollback atomique | Immuable (fermeture `active_to` seule) | **`identifiant technique légitime`** |
| **Événements & Positions** | `event_id` (`UUID`), `(family, position)` (`v3_event_position`, `v3_request_position`) | WRITE Execution / Admission | `UUID.randomUUID()` dans `JdbcPotCommandStore.publish` / `JdbcIdentityRequestStore` ; compteurs SQL denses | Implicite à la terminalisation / admission | Aucun | `v3_pot_event`, `v3_pot_event_component`, `v3_invitation_event`, `v3_command_completion_event`, `v3_identity_event`, `v3_identity_completion_event`, `v3_event_position`, `v3_request_position` | Suffixe de publication fermé dans la transaction ; rollback annule tout | Immuable | **`identifiant technique légitime`** |
| **Résultats terminaux** | `request_id` (`v3_command_result`, `v3_identity_request_result`, `read_command_result`, `read_identity_request_result`) | WRITE Execution -> READ Materializer | Clé = `request_id` existant | Implicite à la terminalisation puis matérialisation | Consultation par `requestId` | Persistés en WRITE puis projetés en READ (`GET /commands/{requestId}/result`, `GET /identity/requests/{requestId}/result`) | `INSERT ... ON CONFLICT DO NOTHING` + vérification d'égalité en READ | **Extension requise en Phase B** pour porter `pot_id` assigné et `created_resources` sans casser les résultats historiques | **`identifiant technique légitime`** (contrat de restitution à étendre en Phase B) |
| **Objets de Consumption** | `ConsumptionId` (UUIDv5 statique), `ownerToken` (`UUID`), `claimToken` (`UUID`), `consumable_id` (= `request_id` / `event_id`), `partition_id` (`0..3`), `checkpoint` (`bigint`) | Orchestrateur Consumption | `ConsumptionCatalog` (statique), `ConsumptionEngine` (`UUID.randomUUID()`), `DiscoveryEngine` | Implicite (polling, discovery, acquisition) | Aucun | `consumption_routing_contract`, `partition_ownership`, `discovery_checkpoint`, `consumption_slot` (WRITE & READ) | Fencing par bail/token, retry/quarantaine, rollback sans fuite | Inchangé | **`identifiant technique légitime`** |
| **Projections READ** | `SubobjectKey(potId, kind, objectId, localVersion)`, `read_pot_version`, `read_current_pot`, `read_pot_balance`, `read_pot_visibility_state`, `read_user_pot`, `read_current_binding`, `read_current_invitation` | READ Materializers | Aucun ID métier inventé : projection fidèle des IDs WRITE | Implicite via les 7 materializers | Aucun | 10 tables `read_*` | Idempotence `insertOrVerify` / monotonie de version ; reconstruction vierge depuis WRITE | Inchangé (hors extension `read_command_result`) | **`identifiant technique légitime`** |
| **Technique / Opérationnel / Éphémère** | `read_maintenance_state.operation_id` (`UUID`), `traceId` HTTP (`UUID`) | Opérations / Transport HTTP | `UUID.randomUUID()` côté serveur | Verrou de maintenance READ / réponse d'erreur HTTP | Aucun | `public.read_maintenance_state` / logs & corps d'erreur | Mono-ligne `id = 1` / éphémère | Inchangé | **`identifiant technique légitime`** |

---

## 3. Synthèse des anomalies confirmées

L'audit exhaustif confirme exactement **3 anomalies d'attribution cliente d'identités métier internes** (et aucune autre création active n'est affectée) :

1. **`CreatePot`** : exige `potId` du client dans l'enveloppe HTTP et en base (`v3_command.pot_id NOT NULL`).
2. **`AddShareholders`** : exige `payload.targets[].id` du client pour chaque nouvel actionnaire, et ne restitue pas les IDs créés dans le résultat durable.
3. **`CreateExpense`** : exige `payload.id` du client pour la nouvelle dépense, et ne restitue pas l'ID créé dans le résultat durable.

Toutes les autres créations métier (`Invitation`, `PocomaUser`, `ExternalUserIdentity`, `BindingToken`) sont déjà générées exclusivement côté serveur lors du traitement WRITE et sont conformes aux 10 principes arrêtés.

---

## 4. Architecture cible et plan de migration (Phase B)

### 4.1 Contrats HTTP d'admission (`POST /api/v3/commands`)

- **`CreatePot`** :
  - L'enveloppe HTTP n'accepte **plus** `potId` (objet fermé : `operation`, `observedVersion` = `0`, `bindingToken`, `payload: { label }`). Tout envoi de `potId` est rejeté en `400 Bad Request`.
  - L'empreinte S-03 d'idempotence porte sur l'intention canonique sans `potId`.
- **`AddShareholders`** :
  - `potId` (existant) reste requis dans l'enveloppe.
  - Chaque cible de `payload.targets[]` est un objet fermé `{ name, attachment ("FREE"|"SELF"), weight }` **sans champ `id`**. Tout envoi de `id` est rejeté en `400 Bad Request`.
  - L'ordre du tableau `targets[]` est préservé à l'identique (`0..n-1`) pour la correspondance exacte entrée/résultat.
- **`CreateExpense`** :
  - `potId` (existant) reste requis dans l'enveloppe.
  - `payload` est un objet fermé `{ label, date, amount, payerId, participants }` **sans champ `id`**. Tout envoi de `id` est rejeté en `400 Bad Request`.
  - `payerId` et `participants[].id` restent des références vers des `ShareholderId` existants.

### 4.2 Représentation durable des commandes et compatibilité historique

- **`v3_command` (migration WRITE `V2__server_assigned_pot_identities.sql`)** :
  - `pot_id` devient nullable (`ALTER TABLE v3_command ALTER COLUMN pot_id DROP NOT NULL`).
  - Contrainte `v3_command_pot_id_presence` : `CHECK (operation = 'CreatePot' OR pot_id IS NOT NULL)`.
  - Le trigger d'immuabilité `v3_command_intention` reste **strictement inchangé** : `v3_command.pot_id` n'est jamais muté lors du traitement. Les commandes historiques `CreatePot` (`pot_id IS NOT NULL`) et les nouvelles commandes (`pot_id IS NULL`) coexistent sans modification des lignes existantes.
- **Codec `PotCommandJson` (`payloadVersion: 3`)** :
  - Nouvelles commandes `AddShareholders` (sans `id`) : `{"payloadVersion": 3, "targets": [{"name": ..., "attachment": "FREE"|"SELF", "weight": {...}}]}`.
  - Nouvelles commandes `CreateExpense` (sans `id`) : `{"payloadVersion": 3, "label": ..., "date": ..., "amount": {...}, "payerId": ..., "participants": [...]}`.
  - Les formats historiques `v1` et `v2` (commandes pending ou terminales existantes avec `id` client) restent intégralement décodables et exécutables.

### 4.3 Attribution exclusivement au traitement WRITE (`ExecutePotCommand`)

Un générateur d'identifiants `Supplier<UUID>` (par défaut `UUID::randomUUID`, injectable en test) est porté par `ExecutePotCommand` :

- **`CreatePot` sans `potId` (`command.potId() == null`)** :
  - Si l'acteur est invalide (`INVALID_BINDING`) : rejet immédiat sans aucun `PotId` généré ni publié (`v3_command_result.pot_id = NULL`, `created_resources = NULL`).
  - Si l'acteur est valide : génération d'un `PotId` candidat dans l'attempt d'exécution, verrouillage via `protectPot(assignedPotId, 0)`. En cas de collision technique (UUID déjà présent ou course concurrente), déclenchement de `Collision` (rollback + retry avec un nouvel UUID, jamais un rejet métier `ALREADY_EXISTS`).
  - À la finalisation atomique : création de `v3_pot_identity` / `v3_pot_version(1)` / composants, enregistrement dans `v3_command_result(pot_id = assignedPotId, created_resources = {"potId": ...})`, publication de `v3_pot_event(pot_id = assignedPotId)` et `v3_command_completion_event`.
  - Un rollback avant COMMIT ne laisse aucun `PotId` publié ni persisté.
  - Les commandes historiques `CreatePot` avec `command.potId() != null` conservent leur sémantique exacte (`ALREADY_EXISTS` en cas d'existence).
- **`AddShareholders` sans IDs clients** :
  - Génération de `N` `ShareholderId` distincts dans l'attempt, résolvant `attachment` (`SELF`/`FREE`) avec l'acteur WRITE.
  - Sur `CHANGED` : `created_resources = {"shareholders": [{"inputIndex": 0, "shareholderId": "..."}, ...]}` en correspondance exacte `0..N-1`.
  - Sur `REJECTED` ou rollback : aucun ID persisté ni publié.
- **`CreateExpense` sans ID client** :
  - Génération d'un `ExpenseId` dans l'attempt.
  - Sur `CHANGED` : `created_resources = {"expenseId": "..."}`.
  - Sur `REJECTED` ou rollback : aucun ID persisté ni publié.

### 4.4 Résultat terminal durable, triggers SQL, rôles PostgreSQL et projection READ

- **WRITE `v3_command_result` (`V2__server_assigned_pot_identities.sql`)** :
  - Ajout des colonnes `pot_id uuid` et `created_resources jsonb`.
  - Contrainte `v3_command_result_created_resources_shape` : `created_resources IS NULL OR (outcome = 'CHANGED' AND jsonb_typeof(created_resources) = 'object')`.
  - Mise à jour de `v3_require_event_request()` et `v3_require_command_unit()` pour utiliser `coalesce(r.pot_id, c.pot_id)` tout en préservant strictement la projection `SECURITY INVOKER` de `admission_role` (aucun privilège supplémentaire requis pour `admission_role`, ni pour `source_role` qui possède déjà `SELECT` sur `v3_command_result`).
- **Recovery (`JdbcPotCommandObservation`)** :
  - Résout le `pot_id` effectif via `coalesce(result.pot_id, command.pot_id)` et vérifie l'absence de `pot_id` / `created_resources` sur un `CreatePot` rejeté sans `command.pot_id`.
- **Source WRITE -> Projection READ (`JdbcCommandTerminalSource`, `read_command_result` V2, `JdbcCommandTerminalResultStore`, `CommandTerminalResult`, `ReadDtos.Command`)** :
  - `read_command_result.pot_id` devient nullable uniquement pour `(operation = 'CreatePot' AND write_outcome = 'REJECTED')`.
  - Ajout de `created_resources jsonb` sur `read_command_result`.
  - `GET /api/v3/commands/{requestId}/result` expose :
    - `potId` : le `PotId` assigné (ou `null` si `CreatePot` rejeté sans `potId`).
    - `createdResources` :
      - `CreatePot` (`COMPLETED`) : `{"potId": "<uuid>"}`
      - `AddShareholders` (`COMPLETED`) : `{"shareholders": [{"inputIndex": 0, "shareholderId": "<uuid>"}, ...]}`
      - `CreateExpense` (`COMPLETED`) : `{"expenseId": "<uuid>"}`
      - Autres opérations / `REJECTED` / historique sans `created_resources` : `null`.

### 4.5 Périmètre de vérification déclaré (normatif)

- **Impact production attendu** : `contract-write-admission`, `domain-projection`, `engine-write-pot`, `infra-postgres-write`, `infra-postgres-write-source-read`, `infra-postgres-read`, `supra-web-read`, et scripts clients (`scripts/bruno-playground`, `scripts/bruno-v3`, `scripts/local`, `scripts/k6`, `scripts/performance`, `scripts/operations`).
- **Slice primaire** : `COMMAND` (`./mvnw -pl runtime-worker-process -am test`).
- **Slices secondaires** : `WEB` (`./mvnw -pl runtime-web-write,runtime-web-read -am test`), `PROJECTION` (`./mvnw -pl runtime-worker-materialize -am test`).
- **Impact production interdit** : `domain-identity`, `engine-write-identity`, `engine-admit-identity`, `domain-consumption`, `orchestrator-consumption`, `supra-polling-consumption`, `infra-tx-spring`, `infra-security-spring`, graphe de dépendances Maven (`pom.xml`).
- **Gate architecture global** : `NOT REQUIRED` (aucun changement de dépendances ou de frontières modulaires).
- **Full reactor** : `NOT REQUIRED` par politique locale ; les tests Testcontainers/PostgreSQL nécessitent Docker (absent de cette VM) et seront explicitement consignés `NOT RUN`.
