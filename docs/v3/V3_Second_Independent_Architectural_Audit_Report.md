# Pocoma V3 — Second audit architectural indépendant et contradictoire (`V3-CADRE` consolidé)

* **Dépôt audité** : `https://github.com/kartaguez/pocoma`
* **Branche auditée** : `feat/v3-architecture`
* **SHA exact du HEAD audité** : **`51349aab2cd79692e4dd7a7f7c6db84960d89c80`** (`docs(v3): reconcile audit closure documentation in V3_Independent_Conceptual_Audit_Report.md`, 2026-10-24)
* **Date de l’audit** : 24 octobre 2026
* **Statut de lecture des 12 documents obligatoires (`docs/v3/`)** : **100 % lus intégralement** à l’état exact du commit `51349aab2cd79692e4dd7a7f7c6db84960d89c80` :
  1. [V3_Canon_and_Roadmap.md](./V3_Canon_and_Roadmap.md) (77 lignes)
  2. [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) (152 lignes)
  3. [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) (111 lignes)
  4. [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) (91 lignes)
  5. [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (110 lignes)
  6. [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) (87 lignes)
  7. [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) (166 lignes)
  8. [SPEC-07_Transverse_Requirements.md](./SPEC-07_Transverse_Requirements.md) (57 lignes)
  9. [SPEC-08_Observability.md](./SPEC-08_Observability.md) (77 lignes)
  10. [SPEC-09_Performance.md](./SPEC-09_Performance.md) (77 lignes)
  11. [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (1 187 lignes)
  12. [V3_Independent_Conceptual_Audit_Report.md](./V3_Independent_Conceptual_Audit_Report.md) (709 lignes)

---

## Livrable 1 — Reconstitution indépendante de l’architecture (Passe A)

Cette reconstitution est établie exclusivement à partir de [V3_Canon_and_Roadmap.md](./V3_Canon_and_Roadmap.md) et de [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) à [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md), sans dépendre du rapport d’audit historique.

### 1.1 Modèle d’autorité et séparation WRITE / READ

```
                ┌──────────────────────────────────────────────────────────┐
                │                     CLIENTS HTTP                         │
                └───────────────┬──────────────────────────┬───────────────┘
      POST + Idempotency-Key    │                          │  GET / POST batch
      (202 Accepted + requestId)│                          │  (200 / 401 / 403 / 404 / 503)
                                ▼                          ▼
                ┌───────────────────────────┐  ┌───────────────────────────┐
                │        SUPRA WRITE        │  │        SUPRA READ         │
                │ AuthN OIDC + Forme + S-01 │  │ AuthN OIDC + Binding READ │
                └─────────────┬─────────────┘  └─────────────┬─────────────┘
                              │                              │
                              ▼                              │ (Zéro lecture synchrone
┌─────────────────────────────────────────────────────────┐  │  de WRITE depuis READ)
│                       BASE WRITE                        │  │
│ • Demandes burnables : COMMAND, IDENTITY_REQUEST        │  │
│ • Slots WRITE : ExecutePotCommand, ExecuteIdentityReq   │  │
│ • État métier versionné [activeFrom, activeTo] :        │  │
│   - Pot : PotHeader/Details, ShareholderHeader/Details/ │  │
│     Weight, ExpenseHeader/Details/Financials            │  │
│   - PotInvitation(tokenHash, potId, expiresAt) [§8]     │  │
│   - Identity : PocomaUser, EUI, Binding(token, active)  │  │
│ • 4 journaux non-burnables à compteur verrouillé :      │  │
│   - POT_EVENT (séquence POT)                            │  │
│   - IDENTITY_EVENT (séquence IDENTITY)                  │  │
│   - COMMAND_COMPLETED_EVENT                             │  │
│   - IDENTITY_REQUEST_COMPLETED_EVENT                    │  │
└──────────────┬───────────────────────────┬──────────────┘  │
               │ Lecture fenêtres (K, H]   │ Lecture état    │
               │ des 4 journaux            │ Pot@V / EUI@V   │
               ▼                           ▼                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                                BASE READ                                 │
│ • Checkpoints K et Slots READ des 6 Consumptions non-burnables :         │
│   1. MaterializePotVersion     ──► PotVersion(P,V), SubobjectVersion,    │
│                                    CurrentPot(P)                         │
│   2. MaterializePotBalance     ──► PotBalance(P,V)                       │
│   3. MaterializePotVisibility  ──► PotVisibilityState(P),                │
│                                    CurrentUserPotList(U,P)               │
│   4. MaterializeCurrentBinding ──► CurrentBinding(EUI)                   │
│   5. MaterializeCommandResult  ──► CommandResult(requestId)              │
│   6. MaterializeIdentityReqRes ──► IdentityRequestResult(requestId)      │
└──────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Invariants structurants reconstruits depuis `SPEC-01` à `SPEC-10`

1. **Domaine `Pot` ([SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md))** :
   * Un `Pot` de version globale `V` ($V \ge 1$) est un agrégat de composantes versionnées par intervalles globaux `[activeFrom, activeTo]` et dotées chacune d’une version locale monotone : `PotHeader(potId, creatorId, deleted)`, `PotDetails(label)`, `ShareholderHeader(deleted)`, `ShareholderDetails(name, userId)`, `ShareholderWeight(weight > 0)`, `ExpenseHeader(deleted)`, `ExpenseDetails(label, date)`, `ExpenseFinancials(amount > 0, payerId, participants/shares > 0)`.
   * Toute mutation d’une composante ferme l’instance active (`activeTo = V`), incrémente la version globale du Pot (`V+1`) et crée une nouvelle instance (`activeFrom = V+1, activeTo = null, localVersion + 1`). Une commande valide sans changement d’état (`no-op`) ne crée pas de version globale et n’émet pas de `POT_EVENT`.
   * **Droits WRITE (`SPEC-01` §5)** : Le créateur (`creatorId`, immuable) et tout `PocomaUser` rattaché à au moins un `Shareholder` actif à la version courante sont les acteurs autorisés du Pot ; toutefois, `Supprimer le Pot` et `Supprimer un Shareholder` sont réservés exclusivement au créateur (`creatorId`).
   * **Préconditions `observedVersion` (`SPEC-01` §6)** : Toute commande sur un Pot existant déclare `observedVersion` ; si l’une des composantes dont dépend la commande a `activeFrom > observedVersion`, la commande est rejetée comme `OBSOLETE`.
   * **Balances rationnelles (`SPEC-01` §4)** : À toute version active `V` (`deleted = false`), la balance de chaque `Shareholder` actif est calculée exactement sous forme rationnelle irréductible (`den > 0`) sur les `Expenses` actives à `V` selon la formule $w_i \cdot s_i / \sum_j (w_j \cdot s_j)$, avec $\sum_i \text{balance}_i = 0$.

2. **Domaine `Identity` ([SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md))** :
   * Deux entités principales : `PocomaUser(userId)` (identité interne immuable, version 1 unique) et `ExternalUserIdentity(issuer, subject)` dont la composante versionnée unique est `Binding(pocomaUserId, active, bindingToken)` sur `[activeFrom, activeTo]`.
   * Trois opérations : `Registration(E)` (crée `U` et `Binding` actif `V=1`), `Link(E1, E2)` (rattache `E2` libre au `PocomaUser` de `E1` avec nouveau `bindingToken` et consentement signé de `E2`), `Unlink(E1, E2)` (ferme le `Binding` actif de `E2` lié au même `U` que `E1` et ouvre un `Binding` inactif sans token).
   * Révocation immédiate : un `Unlink` ou une rotation de `bindingToken` invalide immédiatement l’ancien token.

3. **Consommables, Séquencement et Moteur `Consumption` ([SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md), [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md))** :
   * **6 familles de consommables** : 2 burnables en WRITE (`COMMAND`, `IDENTITY_REQUEST`) et 4 non-burnables en WRITE (`POT_EVENT`, `IDENTITY_EVENT`, `COMMAND_COMPLETED_EVENT`, `IDENTITY_REQUEST_COMPLETED_EVENT`).
   * **8 Consumptions** : 2 en WRITE (`ExecutePotCommand`, `ExecuteIdentityRequest`) et 6 en READ (`MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`, `MaterializeCurrentBinding`, `MaterializeCommandResult`, `MaterializeIdentityRequestResult`).
   * **Alignement transactionnel (`SPEC-05` §4)** : Les tables de `Slot` et de `Checkpoint` résident dans la base où la Consumption écrit ses effets (WRITE pour les 2 Consumptions WRITE ; READ pour les 6 Consumptions READ).
   * **Séquencement sans trou visible (`SPEC-03` §5, `SPEC-05` §2)** : Chaque famille d’événements alloue son `sequenceNumber` sur une ligne compteur dédiée verrouillée jusqu’au commit WRITE ; `Discovery` lit uniquement jusqu’à la borne haute sûre `H` (préfixe contigu committé sans trou).

4. **Projections READ et Contrats Supra ([SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md), [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md))** :
   * **8 projections READ** : `CurrentPot(potId -> currentVersion)`, `PotVersion(potId, version)`, `SubobjectVersion`, `PotBalance(potId, version)`, `PotVisibilityState(potId)`, `CurrentUserPotList(pocomaUserId, potId)`, `CurrentBinding(issuer, subject)`, `CommandResult(requestId)` et `IdentityRequestResult(requestId)`.
   * **Isolation stricte READ / WRITE** : READ ne consulte jamais WRITE lors d’un appel API (`SPEC-04` §1, `SPEC-06` §1).
   * **Règle d’accès Pot et non-oracle d’existence (`SPEC-04` §§9–10, `SPEC-06` §§4–5)** : Toute lecture courante ou historique d’un Pot vérifie d’abord le droit d’accès **courant** dans `PotVersion(P, V_courant)` (et, pour une lecture historique à `V`, le droit d’accès à `V`). Toute absence, suppression terminale `D` ou non-autorisation retourne `404 Not Found` **avant** toute évaluation de `minVersion` ou de disponibilité de `PotBalance(P,V)` (`503 Service Unavailable`).

---

## Livrable 2 — Registre des nouveaux constats (`GEM-V3-001` à `GEM-V3-010`)

L’audit indépendant du corpus consolidé (`SPEC-01` à `SPEC-10`) met en évidence **10 nouveaux constats**, dont **3 bloquants**, **6 majeurs** et **1 mineur**. Fait notable : les 3 constats bloquants (`GEM-V3-001`, `GEM-V3-002`, `GEM-V3-003`) proviennent directement de mécanismes introduits ou remaniés lors de la clôture du premier audit sans validation de bout en bout de leurs contrats inter-SPEC.

---

### `GEM-V3-001` — Impasse architecturale du mécanisme d’invitation et d’auto-rattachement (`SPEC-01 §8`) : violation de la frontière READ/WRITE, absence de commandes, d’événements, de projections READ, d’endpoints Supra et d’oracles

* **Identifiant** : `GEM-V3-001`
* **Titre précis** : Le mécanisme `PotInvitation` ajouté dans `SPEC-01 §8` viole l’interdiction de lecture synchrone de WRITE depuis READ et est dépourvu de tout contrat dans `SPEC-01 §§5–6`, `SPEC-03`, `SPEC-04`, `SPEC-06` et `SPEC-10`.
* **Sévérité** : **Bloquant**
* **Documents, sections et lignes concernés** :
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) : §1 l.16 ; §5 l.60–70 ; §6 l.84–95 ; §8 l.131–149
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) : §1 l.9 ; §2 l.19–27 ; §6 l.66–78
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) : §1 l.8 ; §2 l.10–21 ; §3 l.27–30
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) : §1 l.8 ; §4 l.60–64
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) : §2 l.16–44
* **Règles normatives en conflit ou insuffisamment définies** :
  1. **Conflit frontal `SPEC-01 §8` vs `SPEC-04 §1` / `SPEC-06 §1`** :
     * `SPEC-01` §8 (l.135) définit `PotInvitation(tokenHash, potId, expiresAt)` comme un *« objet de support **WRITE** non versionné »*.
     * `SPEC-01` §8 (l.143) exige simultanément : *« Tant que le rattachement n'est pas committé en WRITE puis projeté en READ, l'invitation ne confère aucun accès READ au Pot complet ni présence dans `CurrentUserPotList`. **En revanche, un utilisateur authentifié disposant d'une invitation encore valide peut obtenir une vue minimale de choix comprenant identifiants et noms des `Shareholders` libres uniquement, sans accès au Pot complet.** »*
     * Or `SPEC-04` §1 (l.8) et `SPEC-06` §1 (l.8) / §4 (l.64) interdisent toute lecture synchrone de WRITE pour répondre à une consultation (*« READ répond aux consultations courantes et historiques à partir de ses seules projections, jamais par une consultation synchrone de WRITE »*).
     * En READ (`SPEC-04` §2 l.10–21), **aucune des 8 projections** ne stocke `PotInvitation(tokenHash, potId, expiresAt)`, aucun événement de `SPEC-03` (§2 l.19–27) ne transporte une création ou révocation d’invitation (un `POT_EVENT` n’est émis que lorsqu’une version globale `V` du Pot est créée : `SPEC-03` §4 l.47), et la projection publique des `Shareholders` dans READ ne distingue pas un `Shareholder` libre d’un `Shareholder` déjà rattaché (`SPEC-01` §1 l.16 : *« `userId` n'est pas exposé dans les projections publiques du Pot »* ; `SPEC-04` §3 l.27 : *« `SubobjectVersion` ne contient jamais `ShareholderDetails.userId` »*) !
  2. **Absence de contrat de création et de restitution du jeton d’invitation** :
     * `SPEC-03` §1 (l.9) impose : *« Toute modification de l'état métier provient d'une demande explicite »* (`COMMAND` asynchrone retournant `202 Accepted` avec `requestId`).
     * Si la création d’une invitation est une `COMMAND` asynchrone, `CommandResult` (`SPEC-04` §2 l.19) ne contient que `(requestId, requesterEui, status, rejectionReason, potId, resultingPotVersion)` et `PotInvitation` en WRITE ne stocke que `tokenHash` (`SPEC-01` §8 l.135 : jamais le token brut). Comment le créateur obtient-il le jeton d’invitation en clair à partager ?
  3. **Absence de précondition `observedVersion` pour l’auto-rattachement (`SPEC-01` §6)** :
     * `SPEC-01` §6 (l.84–95) impose `observedVersion` à toute commande sur un Pot existant. Or l’invité qui soumet une commande d’auto-rattachement (`SPEC-01` §8 l.133) **n’a pas accès au Pot** avant son rattachement (`SPEC-01` §8 l.143) et ne connaît donc pas `observedVersion` (ou bien la table §6 omet de définir la précondition de concurrence de cette commande).
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  1. Alice crée le Pot `P` à `V1` et ajoute le `Shareholder` libre `S_Bob` (`userId = null`) à `V2`.
  2. Alice veut inviter Bob selon `SPEC-01` §8 : aucun endpoint Supra (`SPEC-06`), aucune `Command` (`SPEC-01` §§5–6), aucun mécanisme de retour du token clair n’existe.
  3. Supposons qu’Alice transmette néanmoins un `invitationToken` à Bob. Bob (authentifié et lié à `U_Bob`) appelle l’API pour obtenir *« la vue minimale de choix comprenant identifiants et noms des Shareholders libres uniquement »* (`SPEC-01` §8 l.143).
  4. La Supra READ reçoit la requête : elle n’a ni table `PotInvitation` en READ (pour vérifier `tokenHash` et `expiresAt`), ni l’information indiquant si `S_Bob` a `userId == null` dans `SubobjectVersion`, et il lui est interdit d’interroger la base WRITE (`SPEC-04` §1 l.8). La requête est **impossible à servir**.
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Le parcours permettant à un second utilisateur de rejoindre un Pot existant (`SPEC-01` §8, censé clore `AUD-V3-002`) est **non implémentable sans violer au moins un invariant normatif fondamental** (`SPEC-03` §1, `SPEC-04` §1/§2/§3 ou `SPEC-06` §1).
  * En outre, `SPEC-10` ne comporte **aucun** scénario de test pour `PotInvitation` ou l’auto-rattachement.
* **Correction minimale envisageable** :
  Deux options simples et conformes à l’esprit V3 :
  * **Option A (Recommandée — Jeton d’invitation signé sans état ou commande dédiée + projection minimale)** :
    1. Soit le jeton d’invitation est généré à l’admission WRITE lors d’une requête dédiée du créateur (ou signé cryptographiquement par WRITE / émis à l’admission qui stocke `tokenHash` dans `PotInvitation` et retourne le token clair une seule fois au créateur),
    2. Dans `PotVersion(P,V)` en READ (`SPEC-04` §3 l.28), stocker un simple booléen public `isFree` (`userId == null`) par référence de `Shareholder` (ce qui ne révèle aucun `PocomaUserId` !), et projeter `PotInvitation(tokenHash, potId, expiresAt, revoked)` en READ (ou inclure les `tokenHash` actifs dans `PotHeader`/`PotVersion`) afin que la Supra READ puisse valider le token et retourner la liste des `Shareholders` où `isFree = true` uniquement depuis READ.
    3. Définir explicitement la commande `ClaimShareholderByInvitation(potId, invitationToken, shareholderId)` dans `SPEC-01` §§5–6 : autorisée pour tout `PocomaUser` authentifié muni d’un `invitationToken` valide non expiré, sans exiger `observedVersion` (ou en exigeant uniquement que `ShareholderDetails.userId == null` à la version courante en WRITE).
  * **Option B** : Si le partage multi-utilisateurs par invitation n’est pas requis dès le premier lot fonctionnel, retirer `PotInvitation` de `SPEC-01` §8 ou le borner explicitement avec un contrat WRITE/READ complet.
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-01` (§§5, 6, 8), `SPEC-03` (§§2, 6), `SPEC-04` (§§2, 3), `SPEC-06` (§§2, 4), `SPEC-10` (`DOM-02`, `SEC-01`).
* **Niveau de confiance** : **100 % (Certitude formelle)**.

---

### `GEM-V3-002` — Faille d’autorisation WRITE et contradiction interne sur `ShareholderDetails.userId` : n’importe quel `Shareholder` actif peut détacher les autres participants (ou le `Shareholder` du créateur) et contourner l’interdiction de `DeleteShareholder`

* **Identifiant** : `GEM-V3-002`
* **Titre précis** : Absence de restriction sur la modification de `ShareholderDetails.userId` dans `SPEC-01 §§5–6` permettant l’éviction unilatérale d’autres participants, et contradiction interne entre `SPEC-01 §1` (`1 User ↔ N Shareholders`) et `SPEC-01 §8` (`1 User ↔ 1 Shareholder`).
* **Sévérité** : **Bloquant**
* **Documents, sections et lignes concernés** :
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) : §1 l.16, l.22 ; §5 l.60–70 ; §6 l.87–88 ; §8 l.133, l.137–138
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) : §6 l.81
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) : §3 l.27 ; §5 l.54 ; §6 l.62
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) : `P03-D` l.775 ; `S01-H` l.858
* **Règles normatives en conflit ou insuffisamment définies** :
  1. Lors du nettoyage de l’ancienne règle mal placée dans `V3-3` §4 l.47 (*« Un Shareholder ne peut être associé à un PocomaUser qu'avec le consentement de ce dernier »*), les règles régissant qui a le droit de définir, modifier ou mettre à `null` le champ `ShareholderDetails.userId` lors de `CreateShareholder` et `ModifyShareholderDetails` **n’ont pas été écrites dans `SPEC-01` §§5–6**.
  2. Dans `SPEC-01` §5 (table l.62–70) :
     * `Ajouter un Shareholder` est autorisé (`Oui`) pour le Créateur et pour **tout Shareholder actif**.
     * `Modifier un Shareholder` (`ModifyShareholderDetails(name, userId)` et `ModifyShareholderWeight`) est autorisé (`Oui`) pour le Créateur et pour **tout Shareholder actif**.
     * Seul `Supprimer un Shareholder` (`DeleteShareholder`) est réservé au Créateur (`Non` pour un Shareholder actif).
  3. Par ailleurs, `SPEC-01` §1 (l.22) stipule : *« **plusieurs `Shareholders` d'un même Pot peuvent être associés au même `userId`**. Aucun objet `Membership` ni unicité `Shareholder→User` ne sont ajoutés »* (confirmé par `SPEC-04` §5 l.54 et testé par `SPEC-10` `P03-D` l.775 et `S01-H` l.858), tandis que `SPEC-01` §8 (l.133 et l.137–138) impose à l’auto-rattachement par invitation *« l'absence de tout autre `Shareholder` actif déjà rattaché à ce même `PocomaUserId` dans le Pot »*.
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  * **Contre-exemple 1 (Éviction d’un autre participant par un simple `Shareholder`)** :
    1. Alice (`U_Alice`, créatrice) crée un Pot `P`, et deux participants Bob (`S_Bob`, lié à `U_Bob`) et Charlie (`S_Charlie`, lié à `U_Charlie`) sont actifs dans `P` et participent à des `Expenses` actives.
    2. Bob n’a pas le droit d’appeler `DeleteShareholder(S_Charlie)` (car Bob n’est pas le créateur, et `S_Charlie` est référencé dans une `Expense` active).
    3. Bob appelle alors `ModifyShareholderDetails(shareholderId = S_Charlie, name = "Charlie", userId = null)` !
    4. Selon `SPEC-01` §5 l.67 (`Modifier un Shareholder = Oui` pour tout Shareholder actif) et §6 l.88, cette commande est **100 % valide**. À la version `V+1`, `S_Charlie.userId` devient `null` : Charlie perd instantanément tout accès READ et WRITE au Pot `P` (y compris à tout son historique, en vertu de `SPEC-04` §10 l.104 !), alors même que Charlie reste débiteur/créditeur dans les `Expenses` actives du Pot !
  * **Contre-exemple 2 (Contournement de l’unicité de `SPEC-01` §8)** :
    1. Bob rejoint le Pot `P` via invitation en se rattachant à `S_Bob1` (`SPEC-01` §8 vérifie que `U_Bob` n’a aucun autre Shareholder actif).
    2. Dès la version suivante, Bob étant désormais un `Shareholder` actif, il dispose du droit `Modifier un Shareholder = Oui` (`SPEC-01` §5 l.67) et peut rattacher `U_Bob` à `S_Bob2` et `S_Bob3` sans passer par une invitation.
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * **Faille de sécurité majeure (élévation de privilège / déni de service métier)** : tout participant lié à un `Shareholder` peut expulser n’importe quel autre participant du Pot (et le priver de son droit de consultation courant et historique) en mettant `ShareholderDetails.userId = null` via `ModifyShareholderDetails`, contournant ainsi la réservation de `DeleteShareholder` au seul créateur (`SPEC-01` §5 l.68) ET l’interdiction de supprimer un `Shareholder` engagé dans une `Expense` active (`SPEC-01` §3 l.41).
  * Ambiguïté complète en implémentation sur la façon dont un utilisateur renseigne `userId` dans `CreateShareholder` / `ModifyShareholderDetails` alors qu’aucune API READ n’expose jamais `PocomaUserId` (`SPEC-02` §6 l.81).
* **Correction minimale envisageable** :
  Expliciter dans `SPEC-01` §5 et §6 les règles de mutation de `ShareholderDetails.userId` :
  1. **Création (`CreateShareholder`)** : `userId` ne peut valoir que `null` (Shareholder libre) ou le `PocomaUserId` de l’auteur de la commande (identifié par son `bindingToken` en WRITE, sans que le client ait besoin de connaître son UUID interne `PocomaUserId`, par ex. via un booléen `attachToCaller: boolean`).
  2. **Rattachement (`userId: null -> U_caller`)** : Seul l’appelant authentifié peut rattacher son propre `PocomaUserId` à un `Shareholder` libre (`userId == null`). Jamais un utilisateur ne peut affecter le `PocomaUserId` d’un tiers.
  3. **Détachement (`userId: U_target -> null`)** : Seul **le créateur du Pot (`creatorId`)** ou **l’utilisateur `U_target` lui-même** (auto-détachement) peut détacher `U_target` d’un `Shareholder` (et préciser si l’auto-détachement / détachement par le créateur est autorisé lorsqu’une `Expense` active référence ce `Shareholder`).
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-01` (§§1, 5, 6, 8), `SPEC-06` (§2), `SPEC-10` (`DOM-02`, `SEC-01`).
* **Niveau de confiance** : **100 % (Certitude formelle)**.

---

### `GEM-V3-003` — Impossibilité structurelle d’exécuter `Unlink(E1 -> E2)` sur une autre EUI du même utilisateur : contradiction entre l’exigence du `bindingToken` cible de `E2` et l’interdiction absolue de lire ou lister les `bindingToken` / EUI tiers

* **Identifiant** : `GEM-V3-003`
* **Titre précis** : Contradiction bloquante entre `SPEC-02 §4` / `SPEC-10 I01-I` (qui exigent que `Unlink(E1 -> E2)` fournisse le `bindingToken` courant de `E2`) et `SPEC-02 §6` / `SPEC-04 §6` / `SPEC-06 §7` (qui interdisent à `E1` de connaître le `bindingToken` de `E2` et de lister les EUI liées à `U`).
* **Sévérité** : **Bloquant**
* **Documents, sections et lignes concernés** :
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) : §4 l.39, l.44 ; §6 l.69–82 ; §7 l.97
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) : §3 l.34
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) : §6 l.62–69
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) : §7 l.111
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) : `I01-H` l.197 ; `I01-I` l.198 ; `I01-J` l.199 ; `A10-D` l.948
* **Règles normatives en conflit ou insuffisamment définies** :
  1. `SPEC-02` §4 (l.39 et l.44) et `SPEC-10` (`I01-H`, `I01-I`, `I01-J`, `A10-D`) définissent `Unlink` comme une opération où une EUI initiatrice `E1` peut détacher une EUI cible `E2` (`E2` pouvant être `E1` elle-même **ou une autre EUI liée au même `PocomaUser U`**).
  2. La table normative de `SPEC-02` §4 (l.39) impose comme précondition d’`Unlink` :
     > *« `E1` initiatrice authentifiée liée à `U`, `E2` liée au même `U` **et dont le token courant correspond au token cible déclaré** »*
     Et `SPEC-10` `I01-I` (l.198) teste explicitement : *« Unlink avec token E2 obsolète -> Rejet »*.
  3. Parallèlement, `SPEC-03` §3 (l.34) exige dans toute `IdentityRequest` (sauf `Registration`) le *« `bindingToken` explicite de l'EUI initiatrice »* (`E1`), tandis que `SPEC-02` §6 (l.73) indique : *« `Unlink` : `Binding` que l'EUI détache »*.
  4. **Or**, `SPEC-02` §6 (l.79–82), `SPEC-04` §6 (l.62–69) et `SPEC-06` §7 (l.111) interdisent formellement à `E1` de jamais obtenir le `bindingToken` de `E2` (*« Après `Link`, `E2` doit s'authentifier elle-même pour recevoir son token ; `E1` ne peut le récupérer depuis sa propre session »*, *« Un `bindingToken` n'est jamais exposé à une autre EUI que celle qu'il lie »*) et suppriment toute recherche des EUI par `pocomaUserId` (`SPEC-04` §6 l.67 : *« Aucun parcours V3 retenu n'exige de rechercher les EUI par `pocomaUserId` »*) !
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  1. Un utilisateur possède `E1` (son téléphone) et `E2` (son ordinateur ou un compte OIDC secondaire qu’il a perdu ou compromis), tous deux liés à son compte `U`.
  2. Depuis sa session `E1`, l’utilisateur veut détacher `E2` via `Unlink(initiator = E1, target = E2)` (cas nominal prévu par `SPEC-02` §4 l.39, `I01-J` et `A10-D`).
  3. Pour construire la requête `Unlink`, `SPEC-02` §4 l.39 et `SPEC-10` `I01-I` exigent que `E1` fournisse le **`bindingToken` courant de `E2`**.
  4. Mais `E1` n’a **aucun moyen légal dans Pocoma V3** de connaître le `bindingToken` de `E2` (puisque `GET /identity/me/binding` appelé par `E1` ne retourne que le `bindingToken` de `E1`, jamais celui de `E2`), ni même de connaître la liste des EUI `(issuer, subject)` rattachées à son propre `PocomaUser U` !
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * L’opération `Unlink(E1 -> E2)` avec `E1 != E2` est **impossible à appeler par un client réel** respectant `SPEC-02` §6 et `SPEC-06` §7.
  * De plus, il existe une contradiction directe entre `SPEC-03` §3 l.34 (qui exige le `bindingToken` de l’initiatrice `E1`) et `SPEC-02` §6 l.73 (qui dit que le `bindingToken` fourni dans `Unlink` est celui du `Binding` que l’EUI détache, soit `E2`).
* **Correction minimale envisageable** :
  Deux arbitrages simples sont possibles :
  * **Option A (Recommandée — Autorisation par le `bindingToken` de l’initiatrice `E1` + appartenance au même `U`)** :
    Lors d’un `Unlink(initiator = E1, target = E2)`, le client fournit uniquement le **`bindingToken` actif de l’EUI initiatrice `E1`** (conformément à `SPEC-03` §3 l.34). En WRITE, le Worker vérifie :
    1. que `E1` possède un `Binding` actif dont le token est égal au `bindingToken` initiateur fourni (ce qui identifie `U` et prouve que `E1` n’a pas été détachée concurremment),
    2. que `E2` possède un `Binding` actif lié **au même `U`** (sans exiger que `E1` connaisse le secret `bindingToken` de `E2`, ou en prenant optionnellement `expectedTargetEuiVersion` si l’on souhaite une garde d’obsolescence).
    *(Et si un utilisateur doit pouvoir voir quelles EUI sont rattachées à son compte `U` pour choisir `E2`, l’exposer sans jamais exposer le `bindingToken` de `E2`, ou restreindre `Unlink` en V3 à l’auto-détachement `E1 == E2`.)*
  * **Option B (Auto-détachement uniquement `E1 == E2`)** : Si V3 ne propose pas d’annuaire des EUI d’un même `U` (`SPEC-04` §6 l.67), restreindre `Unlink` à l’EUI authentifiée elle-même (`E2 == E1`), auquel cas le token de `E1` et celui de `E2` sont le même.
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-02` (§4 l.39, §6 l.73), `SPEC-03` (§3 l.34), `SPEC-04` (§6 l.67), `SPEC-10` (`I01-H`, `I01-I`, `I01-J`, `A10-D`).
* **Niveau de confiance** : **100 % (Certitude formelle)**.

---

### `GEM-V3-004` — Orphelinat irréversible d’un `PocomaUser` (et perte définitive des droits de créateur sur ses Pots) après `Unlink` de sa dernière EUI ou lors du parcours `Registration` puis `Link`

* **Identifiant** : `GEM-V3-004`
* **Titre précis** : L’absence de rattachement d’une EUI à un `PocomaUser` sans EUI active et la prescription de `Registration` préalable dans `SPEC-01 §8` condamnent tout utilisateur qui fait `Unlink` puis `Link` à perdre définitivement ses Pots et ses droits exclusifs de `creatorId`.
* **Sévérité** : **Majeur**
* **Documents, sections et lignes concernés** :
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) : §5 l.64, l.68 ; §8 l.139
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) : §2 l.12 ; §4 l.36–44 ; §8 l.104
* **Règles normatives en conflit ou insuffisamment définies** :
  1. Selon `SPEC-02` §4 (l.36–44) :
     * `Registration(E)` crée **toujours** un nouveau `PocomaUser`.
     * `Link(E1, E2)` exige que `E1` soit **déjà liée** à un `PocomaUser U1` et que `E2` soit libre ou inconnue, et rattache `E2` à `U1`.
     * `SPEC-02` §4 (l.43–44) stipule : *« Aucun transfert direct d'EUI de `U1` vers `U2` n'est retenu : `Unlink` puis `Link`. Un `Unlink` peut laisser un `PocomaUser` sans EUI active. »*
  2. Par ailleurs, `SPEC-01` §8 (l.139) prescrit pour une invitation à un Pot :
     * *« EUI authentifiée mais non liée : passage préalable par `Registration` pour obtenir un `PocomaUser` et un `bindingToken` actif, puis reprise du parcours d'invitation. »*
  3. **Incompatibilité structurelle** :
     * Dès lors qu’un `PocomaUser U2` perd sa dernière EUI active par `Unlink(E2)`, **aucune opération de `SPEC-02` ne permet plus jamais de rattacher une EUI à `U2`** (car `Registration` crée un nouveau `U3`, et `Link(E1, E2)` exige une EUI `E1` déjà active sur `U2` !).
     * `U2` devient donc un compte **définitivement inaccessible (orphelin irréversible)**.
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  * **Cas A (Perte définitive d’un Pot par son créateur)** :
    1. Alice s’inscrit avec `E1` (`Registration` $\rightarrow$ `U1`), crée un Pot `P` (`creatorId = U1`) et ajoute des dépenses.
    2. Alice détache `E1` (`Unlink(E1)`), ce que `SPEC-02` §4 l.44 autorise explicitement (*« Un `Unlink` peut laisser un `PocomaUser` sans EUI active »*).
    3. Lorsque Alice se reconnecte avec `E1` et exécute `Registration(E1)`, le système crée un **nouveau** `PocomaUser U2 != U1` !
    4. Résultat : plus aucun utilisateur dans le système ne pourra jamais incarner `U1`. Le Pot `P` n’a plus de créateur (`DeletePot` et `DeleteShareholder` réservés à `creatorId = U1` dans `SPEC-01` §5 l.64/68 sont **définitivement verrouillés pour toujours**).
  * **Cas B (Parcours d’invitation `SPEC-01` §8 suivi de `Link`)** :
    1. Bob possède déjà un compte Pocoma `U1` via `E1` (Google). Il ouvre une invitation Pot sur un appareil où il s’authentifie avec `E2` (Apple, non encore lié).
    2. Suivant `SPEC-01` §8 l.139, il fait `Registration(E2)` (créant `U2`) et rattache son `Shareholder` `S_Bob` à `U2`.
    3. Plus tard, voulant unifier ses deux identités `E1` et `E2` selon `SPEC-02` §4 l.43 (*« `Unlink` puis `Link` »*), il fait `Unlink(E2)` puis `Link(E1, E2)`.
    4. `E2` est désormais liée à `U1`, mais `S_Bob` dans le Pot reste lié à `U2` (désormais orphelin irréversible) : Bob a perdu tout accès au Pot qu’il avait rejoint !
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Perte irréversible d’accès aux Pots et blocage permanent des opérations réservées à `creatorId` (`DeletePot`, `DeleteShareholder`) dès que la dernière EUI d’un `PocomaUser` est détachée.
  * Piège UX dans `SPEC-01` §8 l.139 si l’utilisateur invité possède déjà un compte Pocoma sous une autre EUI.
* **Correction minimale envisageable** :
  1. **Dans `SPEC-02` §4 (`Unlink`)** : Soit **interdire l’`Unlink` de la dernière EUI active d’un `PocomaUser`** (exiger qu’au moins une autre EUI reste active sur `U` pour pouvoir détacher une EUI, ce qui empêche tout `PocomaUser` orphelin irréversible !), soit documenter explicitement que le détachement de la dernière EUI équivaut à un abandon définitif et irrévocable du `PocomaUser`.
  2. **Dans `SPEC-01` §8 l.139** : Préciser qu’une EUI non liée peut faire `Registration` (nouvel utilisateur) **ou `Link` à un `PocomaUser` existant** avant de reprendre le parcours d’invitation.
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-01` (§8 l.139), `SPEC-02` (§2 l.12, §4 l.39–44), `SPEC-10` (`I01-H`).
* **Niveau de confiance** : **95 % (Très élevé)**.

---

### `GEM-V3-005` — Défaut de concurrence et d’isolation inter-partitions en WRITE pour les opérations Identity à deux EUI (`Link(E1, E2)` et `Unlink(E1, E2)`) : verrouillage et ordre anti-deadlock renvoyés à `SPEC-05` mais omis dans `SPEC-05`

* **Identifiant** : `GEM-V3-005`
* **Titre précis** : `SPEC-02 §7` renvoie à `SPEC-05` la définition du verrouillage et de l’ordre anti-deadlock des opérations à deux EUI (`Link`, `Unlink`), mais `SPEC-05` l’omet totalement, exposant `ExecuteIdentityRequest` à des courses critiques inter-partitions (`I01-J`, `I01-K`) ou à des deadlocks PostgreSQL.
* **Sévérité** : **Majeur**
* **Documents, sections et lignes concernés** :
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) : §7 l.96–99
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) : §1 l.11 ; §4 l.44–52
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) : `I01-J` l.199 ; `I01-K` l.200
* **Règles normatives en conflit ou insuffisamment définies** :
  1. `SPEC-02` §7 (l.99) stipule explicitement :
     > *« les opérations à deux EUI doivent respecter leur frontière transactionnelle de cohérence ; **verrouillage et ordre anti-deadlock à préciser en V3-4 [SPEC-05]**. »*
  2. Or `SPEC-05` ne contient **aucune mention** des opérations à deux EUI, ni de la clé de partitionnement exacte de `IDENTITY_REQUEST`, ni de l’ordre d’acquisition des verrous sur `E1` et `E2`.
  3. Dans `SPEC-05` §1 (l.11), chaque `IDENTITY_REQUEST` est routée vers une seule partition (`partitionId = hash(targetEui)`). Dès lors que `E1 != E2`, `E1` et `E2` appartiennent en général à **deux partitions différentes** et sont donc traitées simultanément par **deux Workers différents** dans deux transactions PostgreSQL concurrentes.
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  * **Cas 1 (`I01-K` : `Link(E1 -> E2)` concurrent avec `Unlink(E1)`)** :
    1. Le Worker A traite `Link(E1 -> E2)` (partition de `E2`) et le Worker B traite `Unlink(E1 -> E1)` (partition de `E1`).
    2. En isolation PostgreSQL standard (`READ COMMITTED`), si le Worker A vérifie que `E1` a un `Binding` actif avec un simple `SELECT` (sans `SELECT ... FOR UPDATE` sur `E1`) puis verrouille/écrit `E2`, tandis que le Worker B modifie `E1` pour la détacher, **les deux transactions committent avec succès** : `E2` est rattachée à `U` par une EUI `E1` qui a été détachée simultanément, ce qui viole directement l’oracle `I01-K` (`SPEC-10` l.200) !
  * **Cas 2 (`I01-J` : `Unlink` croisés `E1 -> E2` et `E2 -> E1`)** :
    1. Si chaque Worker verrouille d’abord son EUI initiatrice (`SELECT ... FOR UPDATE` sur `E1`) puis son EUI cible (`SELECT ... FOR UPDATE` sur `E2`), les deux Workers exécutant simultanément `Unlink(E1 -> E2)` et `Unlink(E2 -> E1)` provoquent un **deadlock PostgreSQL déterministe**.
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Violation des invariants d’atomicité `I01-J` et `I01-K` en `READ COMMITTED` si `E1` n’est pas verrouillée en écriture, ou deadlocks en base WRITE si `E1` et `E2` sont verrouillées sans ordre canonique.
* **Correction minimale envisageable** :
  Ajouter dans `SPEC-02` §7 et `SPEC-05` §4 la règle normative promise :
  * Dans la transaction WRITE finale d’exécution d’une `IdentityRequest` impliquant `E1` et `E2`, le Worker acquiert un verrou exclusif (`SELECT ... FOR UPDATE`, avec insertion préalable de l’entité `EUI` si inconnue) sur les lignes `ExternalUserIdentity` de `E1` et `E2` **dans l’ordre lexicographique canonique de leur clé `(issuer, subject)`**, avant de vérifier les préconditions (`Binding` actif et `bindingToken` de `E1`, état de `E2`) et d’écrire la mutation.
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-02` (§7 l.99), `SPEC-05` (§4), `SPEC-10` (`I01-J`, `I01-K`).
* **Niveau de confiance** : **100 % (Certitude formelle)**.

---

### `GEM-V3-006` — Émission d’un `IDENTITY_EVENT` avec `subjectType = POCOMA_USER` lors de `Registration` sans aucun consommateur ni projection associée (ou générateur de slots parasites dans `MaterializeCurrentBinding`)

* **Identifiant** : `GEM-V3-006`
* **Titre précis** : `SPEC-03` impose l’émission de deux `IDENTITY_EVENT` lors de `Registration` (`POCOMA_USER` et `EXTERNAL_USER_IDENTITY`) dans la séquence `IDENTITY`, alors que `MaterializeCurrentBinding` (seul consommateur de cette séquence) ne projette que `CurrentBinding(EUI)`.
* **Sévérité** : **Majeur**
* **Documents, sections et lignes concernés** :
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) : §2 l.12 ; §4 l.36
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) : §2 l.24 ; §4 l.45, l.49 ; §6 l.74 ; §7 l.85
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) : §2 l.18 ; §6 l.64, l.67
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) : §2 l.19
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) : `E01-D` l.286 ; `E02-D` l.324
* **Règles normatives en conflit ou insuffisamment définies** :
  1. `SPEC-03` §4 (l.45, l.49) et §6 (l.74), ainsi que `SPEC-10` (`E01-D` l.286, `E02-D` l.324), prescrivent que lors d’une `Registration` acceptée, la transaction WRITE émet dans la même séquence `IDENTITY` **deux** `IDENTITY_EVENT` :
     * un `IDENTITY_EVENT` avec `subjectType = POCOMA_USER`, `subjectId = userId`, `version = 1`,
     * un `IDENTITY_EVENT` avec `subjectType = EXTERNAL_USER_IDENTITY`, `subjectId = euiId`, `version = V`.
  2. Or, selon `SPEC-03` §7 (l.85) et `SPEC-04` §6 (l.64), la séquence `IDENTITY_EVENT` possède **un seul consommateur** : `MaterializeCurrentBinding`, dont l’unique effet est de lire l’état de l’`ExternalUserIdentity` à la version `V` en WRITE et de mettre à jour `CurrentBinding(issuer, subject)` en READ.
  3. De plus, `SPEC-04` §6 (l.67) confirme qu’aucune projection READ n’existe pour `PocomaUser`.
  4. Selon `SPEC-05` §2 (l.19), la `Discovery` non-burnable de `MaterializeCurrentBinding` parcourt tous les événements de la séquence `IDENTITY` dans `(K, H]` correspondant à `partitionId` et crée un `Slot` pour chacun d’eux.
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  1. Une `Registration(E1)` réussit et écrit dans la séquence `IDENTITY` :
     * `seq = 1` : `IDENTITY_EVENT(subjectType = POCOMA_USER, subjectId = U1, version = 1)`
     * `seq = 2` : `IDENTITY_EVENT(subjectType = EXTERNAL_USER_IDENTITY, subjectId = E1, version = 1)`
  2. La `Discovery` de `MaterializeCurrentBinding` lit la fenêtre `(0, 2]` et enrôle un slot pour `seq = 1` (`POCOMA_USER`) et un slot pour `seq = 2` (`EXTERNAL_USER_IDENTITY`).
  3. Le Worker `MaterializeCurrentBinding` réclame le slot `seq = 1` (`subjectType = POCOMA_USER, subjectId = U1`) : il tente de charger l’EUI `(issuer, subject)` correspondant à `U1` pour projeter `CurrentBinding(issuer, subject)` — ce qui échoue ou l’oblige à exécuter un cycle complet `Discovery -> Slot -> Claim -> No-op -> Delete Slot` uniquement pour jeter l’événement `POCOMA_USER` !
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Complexité et travail SQL parasites (1 événement, 1 slot et 1 exécution Worker inutiles à chaque `Registration`) ou erreur d’exécution dans `MaterializeCurrentBinding` si `subjectType = POCOMA_USER` n’est pas explicitement ignoré.
  * Contrevient directement à la règle d’or du canon ([V3_Canon_and_Roadmap.md](./V3_Canon_and_Roadmap.md) §1 l.12 : *« aucune structure, table, projection, tâche, index ou étape ne doit exister sans invariant ou besoin explicite »*).
* **Correction minimale envisageable** :
  * **Option A (Recommandée — Simplification maximale)** : Supprimer `subjectType = POCOMA_USER` de `IDENTITY_EVENT`. Un `IDENTITY_EVENT` porte exclusivement sur une `EXTERNAL_USER_IDENTITY` (`(issuer, subject, version)`), exactement comme `MaterializeCurrentBinding` l’attend. (`PocomaUser` reste créé atomiquement dans la table WRITE `PocomaUser` lors de `Registration`, sans émettre d’événement orphelin).
  * **Option B (Conservatrice)** : Si `IDENTITY_EVENT(POCOMA_USER)` est conservé, spécifier dans `SPEC-04` §6 et `SPEC-05` §2 que `Discovery` de `MaterializeCurrentBinding` filtre `subjectType = EXTERNAL_USER_IDENTITY` (tout en avançant `K` sur la fenêtre `(K, H]`).
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-03` (§2 l.24, §4 l.45/49, §6 l.74), `SPEC-04` (§6 l.64), `SPEC-10` (`E01-D` l.286, `E02-D` l.324).
* **Niveau de confiance** : **100 % (Certitude formelle)**.

---

### `GEM-V3-007` — Contradictions normatives résiduelles majeures dans `SPEC-08`, `SPEC-09` et `SPEC-10` malgré la clôture déclarée de `AUD-V3-012` : maintien dans les oracles bloquants des `Projection Tasks` supprimées, de l’interdiction d’API historique et du rejet du créateur non-Shareholder

* **Identifiant** : `GEM-V3-007`
* **Titre précis** : La consolidation documentaire (`AUD-V3-012`) a laissé dans `SPEC-08`, `SPEC-09` et `SPEC-10` des exigences et oracles de certification bloquants qui imposent encore les `Projection Tasks` supprimées (`AUD-V3-005`), interdisent l’API historique (`SPEC-06 §4`) et rejettent le créateur non-Shareholder (`AUD-V3-001`).
* **Sévérité** : **Majeur**
* **Documents, sections et lignes concernés** :
  * [V3_Canon_and_Roadmap.md](./V3_Canon_and_Roadmap.md) : §6 l.73 (`AUD-V3-012 : CLOS DOCUMENTAIREMENT`)
  * [SPEC-08_Observability.md](./SPEC-08_Observability.md) : §2 l.37–38
  * [SPEC-09_Performance.md](./SPEC-09_Performance.md) : §3 l.41, l.45, l.49 vs §4 l.55 (et renvoi cassé à `§§5–6` en l.55)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) :
    * `CON-01` : l.381
    * `CON-04` : `C04-J` l.520
    * `CON-06` : `C06-D` l.611
    * `PRJ-01` : l.661–694 (`P01-A..L`, en particulier l.671 et l.685)
    * `PRJ-02` : `P02-I` l.739, `P02-J` l.740, l.749
    * `SEC-01` : l.702, `S01-F` l.856, l.869
    * `ADM-01` : l.917, l.921
    * `REC-01` : l.992, l.994, l.1001, `RCV01-E` l.1005, `RCV01-F` l.1006, `RCV01-I` l.1009, `RCV01-J` l.1010, l.1024, l.1026, l.1030
    * `PERF-01` : l.1140, l.1156, et duplication intégrale de `A11-A..N` aux lignes 1164–1183 (déjà présente aux lignes 960–980)
* **Règles normatives en conflit ou insuffisamment définies** :
  1. **Vestiges bloquants des `Projection Tasks` supprimées (`AUD-V3-005`)** :
     * `SPEC-08` §2 (l.37–38) exige d’instrumenter :
       * `MaterializeCurrentPot` (nom remplacé par `MaterializePotVersion` dans `SPEC-03`/`04`/`05`),
       * **`MaterializePotVersion → création atomique de deux tâches READ`** (alors que `SPEC-04` §8 l.85 interdit toute table de tâches intermédiaires en READ !).
     * `SPEC-10` `REC-01` (exigence de certification **bloquante** `C-02`, l.992–1030) impose dans `RCV01-E` (l.1005), `RCV01-I` (l.1009) et dans l’oracle `READY` (l.1026, l.1030) de vérifier la recréation et la complétion des *« deux Projection Tasks par événement Pot »* et l’absence de *« tâche dérivée encore PENDING »* !
     * De même, `CON-01` (l.381), `C04-J` (l.520), `C06-D` (l.611), `P02-I`/`P02-J` (l.739–740) et `PERF-01` (l.1140) exigent toujours de tester les tâches READ burnables supprimées.
  2. **Contradiction frontale sur `SEC-01` (`S01-F`) et le créateur non-Shareholder (`AUD-V3-001`)** :
     * `SPEC-01` §5 (l.60) et `SPEC-04` §§9–10 (l.96–104) donnent au créateur (`creatorId`) un droit d’accès complet en lecture et écriture même s’il n’est rattaché à aucun `Shareholder` (notamment à `V1` juste après `CreatePot`).
     * Or dans `SPEC-10` `SEC-01`, la table normative des scénarios (`S01-F`, l.856) prescrit toujours :
       > *« `S01-F` | Utilisateur valide mais **aucun Shareholder lié au User dans Pot@V** | **Refus** »*
       Et aucun scénario `S01-A..O` (l.851–865) ne valide l’acceptation du créateur sans `Shareholder` ! Pire, la ligne 869 affirme : *« Il ne doit pas inventer une API publique de lecture historique à V si elle n'existe pas »*, en contradiction directe avec `SPEC-04` §10, `SPEC-06` §4 et `A11-F`.
  3. **Contradiction interne dans `SPEC-09` (§3 vs §4)** :
     * `SPEC-09` §3 (l.41, l.45, l.49) inclut toujours `PotVisibilityState(P)` dans le budget de complétion bout en bout d’une `PotCommand`, alors que `SPEC-09` §4 (l.55) l’en exclut explicitement (et renvoie à des sections `§§5–6` qui n’existent pas dans `SPEC-09`).
  4. **Copier-coller accidentel dans `SPEC-10`** :
     * Les lignes 1164–1183 de `SPEC-10` (au milieu de la section `PERF-01 / PERF-02`) dupliquent mot pour mot la table `A11-A` à `A11-N` de la section `DELETE-01` (lignes 960–980).
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  Lors de la réalisation des tests `V3-6` et de la certification `V3-7` :
  * Le testeur qui implémente `S01-F` (l.856) codera une assertion de **refus (`404`)** lorsqu’un utilisateur n’a aucun `Shareholder` lié dans `Pot@V`, ce qui fera échouer la lecture de `Pot@V1` par son créateur après `CreatePot` (ou inversera l’oracle de sécurité).
  * Le testeur qui implémente `RCV01-E`, `RCV01-I`, `C06-D`, `P02-I/J` ou l’instrumentation `SPEC-08` §2 l.38 cherchera les deux tâches READ par événement Pot, qui n’existent pas dans `SPEC-03`/`04`/`05`.
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Impossibilité de certifier `V3-7` sans violer soit `SPEC-01`/`03`/`04`/`05`/`06`, soit les oracles obligatoires de `SPEC-08`/`09`/`10`.
* **Correction minimale envisageable** :
  Nettoyer chirurgicalement les passages résiduels dans `SPEC-08` (§2 l.37–38), `SPEC-09` (§3 l.41–49, §4 l.55) et `SPEC-10` (`CON-01` l.381, `C04-J` l.520, `C06-D` l.611, `PRJ-01` l.661–694, `P02-I/J` l.739–749, `SEC-01` l.702, `S01-F` l.856, l.869, `ADM-01` l.917/921, `REC-01` l.992–1030, `PERF-01` l.1140, l.1156, l.1164–1183).
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-08`, `SPEC-09`, `SPEC-10`.
* **Niveau de confiance** : **100 % (Certitude formelle)**.

---

### `GEM-V3-008` — Incohérence entre l’insertion atomique de `PotVersion(P,V)` + `SubobjectVersion` (`SPEC-04 §3`) et les scénarios d’indisponibilité `503` pour `SubobjectVersion` manquante (`SPEC-06 §§4–5`, `SPEC-10 P01-H / R01-D`), ainsi que sur la détection de divergence d’idempotence

* **Identifiant** : `GEM-V3-008`
* **Titre précis** : L’atomicité transactionnelle de `MaterializePotVersion` rend impossible l’état « `PotVersion(P,V)` présente mais `SubobjectVersion` manquante » exigé par `SPEC-06` et `SPEC-10`, et l’obligation de détecter un contenu divergent lors d’une insertion idempotente de `SubobjectVersion` contredit un simple `ON CONFLICT DO NOTHING`.
* **Sévérité** : **Majeur**
* **Documents, sections et lignes concernés** :
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) : §3 l.28–29, l.38–40 ; §8 l.87
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) : §4 l.62 ; §5 l.87
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) : `P01-H` l.684 ; `R01-D` l.819
* **Règles normatives en conflit ou insuffisamment définies** :
  1. `SPEC-04` §3 (l.29, l.38–40) et §8 (l.87) imposent que `MaterializePotVersion` insère dans **une seule transaction READ atomique** toutes les `SubobjectVersion` manquantes référencées par `Pot@V`, la ligne `PotVersion(P,V)` et l’avancement de `CurrentPot(P)`.
  2. Par conséquent, en isolation `READ COMMITTED`, **aucun lecteur ne peut jamais observer `PotVersion(P,V)` (ni `CurrentPot(P) = V`) sans que 100 % des `SubobjectVersion` référencées par `PotVersion(P,V)` soient déjà committées en READ** (sauf corruption manuelle de la base READ).
  3. Pourtant, `SPEC-06` §4 (l.62), §5 (l.87) et `SPEC-10` (`P01-H` l.684, `R01-D` l.819) exigent de tester le cas où `CurrentPot(V)` / `PotVersion(P,V)` est visible mais une `SubobjectVersion` référencée n’est pas encore projetée (`503 Service Unavailable`).
  4. En outre, `SPEC-04` §3 (l.40) exige : *« Une insertion concurrente de même clé [`SubobjectVersion`] est idempotente seulement si le contenu est identique ; toute divergence est une anomalie technique, jamais silencieusement acceptée. »* Or en PostgreSQL, un `INSERT INTO subobject_version ... ON CONFLICT DO NOTHING` ignore silencieusement toute divergence de contenu sans lever d’erreur.
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  1. Un développeur écrit le test `R01-D` (`SPEC-10` l.819 : *« `CurrentPot(V)` présent mais `SubobjectVersion` requise absente -> Refus explicite »*) : aucune exécution normale ou hors-ordre des Workers `MaterializePotVersion` ne peut produire cet état puisque `CurrentPot(V)`, `PotVersion(P,V)` et `SubobjectVersion` sont écrits dans la même transaction SQL. Seule une suppression artificielle en base (injection de faute de corruption) peut le déclencher.
  2. Un développeur code l’insertion de `SubobjectVersion` avec `INSERT ... ON CONFLICT DO NOTHING` : si un test de non-régression injecte une `SubobjectVersion` de même clé avec un contenu différent pour vérifier `SPEC-04` §3 l.40, PostgreSQL ne lève aucune anomalie.
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Ambiguïté entre un état transitoire normal (qui ne concerne que `PotBalance(P,V)`, projetée par une Consumption séparée) et une corruption de données READ (`SubobjectVersion` absente alors que `PotVersion(P,V)` est committée).
* **Correction minimale envisageable** :
  1. Préciser dans `SPEC-06` §§4–5 et `SPEC-10` (`P01-H`, `R01-D`) que l’absence d’une `SubobjectVersion` alors que `PotVersion(P,V)` est présente ne peut résulter que d’une anomalie/injection de faute en base READ (défense en profondeur retournant `503`), tandis que l’indisponibilité transitoire normale à `V` provient de l’absence de `PotBalance(P,V)` (ou de `CurrentPot.version < minVersion`).
  2. Pour `SPEC-04` §3 l.40, préciser si la comparaison de contenu en cas de conflit de clé sur `SubobjectVersion` est requise en SQL/Java ou si l’immuabilité en WRITE suffit à garantir l’identité du contenu (auquel cas `ON CONFLICT DO NOTHING` suffit et la phrase de l.40 doit être simplifiée).
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-04` (§3 l.40), `SPEC-06` (§§4–5), `SPEC-10` (`P01-H`, `R01-D`).
* **Niveau de confiance** : **95 % (Très élevé)**.

---

### `GEM-V3-009` — Contradiction sur l’oracle de lecture d’une version `V` mise en quarantaine (`SPEC-10 CON-05 l.572` vs `SPEC-04 §10` / `SPEC-06 §4` / `SPEC-09 §4`)

* **Identifiant** : `GEM-V3-009`
* **Titre précis** : `SPEC-10 CON-05` (l.572) exige que la mise en quarantaine de `Pot P version V` garde le point de lecture à `V` « indisponible » (`503`), alors que si `MaterializePotVersion(P,V)` est en quarantaine et que `V+1` a progressé, `PotVersion(P,V)` est absente et toute lecture à `V` doit retourner `404 Not Found` (et toute lecture courante `minVersion=V` retourne `200 OK` avec `V+1`).
* **Sévérité** : **Majeur**
* **Documents, sections et lignes concernés** :
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) : §10 l.104
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) : §7 l.71
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) : §4 l.61–63
  * [SPEC-09_Performance.md](./SPEC-09_Performance.md) : §4 l.55
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) : `CON-05` l.572
* **Règles normatives en conflit ou insuffisamment définies** :
  1. `SPEC-10` `CON-05` (l.572) prescrit :
     > *« mettre Pot P version V en quarantaine, prouver la progression de P version V+1 dans la même Consumption et celle des autres Consumptions ; **garder le point de lecture à V indisponible si une projection requise à V manque**. »*
  2. Or il existe **trois** Consumptions distinctes sur `POT_EVENT` (`MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`) :
     * Si c’est **`MaterializePotVersion(P, V)`** qui est mise en quarantaine tandis que `MaterializePotVersion(P, V+1)` réussit :
       * La lecture courante avec `minVersion=V` voit `CurrentPot(P) = V+1 >= V` et retourne **`200 OK`** (avec l’état `V+1`), conformément à `SPEC-09` §4 `C-03`.
       * La lecture historique explicite de la version `V` voit que `PotVersion(P, V)` est absente. Or selon `SPEC-04` §10 (l.104) et `SPEC-06` §4 (l.61), lorsque `PotVersion(P, V)` est absente, la Supra READ ne peut pas vérifier le droit d’accès à `V` et **doit impérativement retourner `404 Not Found`** (jamais `503 Service Unavailable`, sinon un tiers non autorisé à `V` pourrait distinguer une version en quarantaine d’une version non autorisée !).
     * Ce n’est que si **`MaterializePotVersion(P, V)` a réussi** (donc `PotVersion(P, V)` est présente et autorise l’appelant) ET que **`MaterializePotBalance(P, V)` est en quarantaine** qu’une lecture historique explicite à `V` retourne **`503 Service Unavailable`**.
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  Dans le test `CON-05` (l.572), le développeur met en quarantaine le slot `(MaterializePotVersion, POT_EVENT(P, V))`, laisse passer `V+1`, puis appelle `GET /pots/{P}/versions/{V}`. La Supra READ applique `SPEC-06` §4 l.61 (`PotVersion(P,V)` absente $\rightarrow$ `404`), mais le test `CON-05` attend « indisponible » (`503`) !
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Contradiction directe entre l’oracle de `CON-05` (l.572) et la règle anti-oracle d’existence `404` avant `503` de `SPEC-04` §10 / `SPEC-06` §4.
* **Correction minimale envisageable** :
  Préciser dans `SPEC-10` `CON-05` (l.572) et `SPEC-05` §7 (l.71) les deux cas :
  * si `MaterializePotVersion(P, V)` est en quarantaine et `V+1` est projetée : `GET /pots/{P}?minVersion=V` retourne `200 OK` (version `V+1`) et `GET /pots/{P}/versions/{V}` retourne `404 Not Found` (`PotVersion(P,V)` absente) ;
  * si `MaterializePotBalance(P, V)` est en quarantaine alors que `MaterializePotVersion(P, V)` et `V+1` sont projetées : `GET /pots/{P}?minVersion=V` retourne `200 OK` (version `V+1`) et `GET /pots/{P}/versions/{V}` par un utilisateur autorisé retourne `503 Service Unavailable` (`PotBalance(P,V)` manquante).
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-05` (§7 l.71), `SPEC-10` (`CON-05` l.572).
* **Niveau de confiance** : **100 % (Certitude formelle)**.

---

### `GEM-V3-010` — Sous-spécification de `resultingPotVersion` dans `CommandResult` pour une commande valide sans effet (`no-op`) et des champs retournés par `CurrentUserPotList`

* **Identifiant** : `GEM-V3-010`
* **Titre précis** : Valeur de `resultingPotVersion` non spécifiée lorsqu’une commande acceptée est sans effet (`no-op`, sans création de version `V`), et contenu fonctionnel d’un item de `CurrentUserPotList` non précisé.
* **Sévérité** : **Mineur**
* **Documents, sections et lignes concernés** :
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) : §6 l.107
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) : §6 l.70
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) : §5 l.52, l.58 ; §7 l.79
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) : §6 l.99–103
* **Règles normatives en conflit ou insuffisamment définies** :
  1. **Commande valide sans effet (`no-op`)** :
     * `SPEC-01` §6 (l.107) et `SPEC-03` §6 (l.70) précisent qu’une commande valide et non obsolète qui ne produit aucun changement ne crée aucune version globale `V`, n’émet pas de `POT_EVENT`, mais émet un `COMMAND_COMPLETED_EVENT`.
     * `SPEC-04` §7 (l.79) indique : *« Une `Command` réussie fournit `resultingPotVersion`, réutilisable comme `minVersion` pour les consultations READ ultérieures. »*
     * Que vaut `resultingPotVersion` pour une commande `no-op` réussie : la version courante `V_courant` du Pot au moment de l’exécution (ce qui permet au client d’appeler `GET /pots/{P}?minVersion=resultingPotVersion` de manière uniforme), ou `null` ?
  2. **Contenu d’une entrée de `CurrentUserPotList`** :
     * `SPEC-04` §5 (l.52, l.58) définit `CurrentUserPotList` par `(pocomaUserId, potId, potVersion, visible)` et précise qu’elle n’est mise à jour **que** lorsque la visibilité change (donc pas lors d’un simple renommage de `PotDetails.label`).
     * `SPEC-06` §6 (l.99–103) ne précise pas si chaque item retourné par `GET CurrentUserPotList` contient uniquement `(potId, lastVisibilityPotVersion)` ou s’il joint le `label` courant depuis `CurrentPot` / `PotVersion` / `SubobjectVersion`.
* **Scénario concret de reproduction / Contre-exemple déterministe** :
  Un client soumet `ModifyPotDetails(label = "Vacances")` alors que le libellé vaut déjà `"Vacances"` à `V3`. La commande réussit en `no-op`. Si `resultingPotVersion = null`, le client qui enchaîne systématiquement `GET /pots/{P}?minVersion={resultingPotVersion}` échoue ou doit coder une branche spéciale.
* **Conséquences fonctionnelles, techniques et de sécurité** :
  * Risque de divergence mineure entre l’implémentation du Worker WRITE et du client / Supra READ.
* **Correction minimale envisageable** :
  1. Spécifier dans `SPEC-03` §6 l.70 et `SPEC-04` §7 l.79 que pour une commande `no-op` valide, `status = COMPLETED` et `resultingPotVersion = V_courant` (la version courante inchangée du Pot en WRITE).
  2. Spécifier dans `SPEC-06` §6 que `CurrentUserPotList` retourne `(potId, potVersion)` (ou préciser si le libellé est résolu ou non).
* **Impacts sur les autres SPEC et les oracles** :
  `SPEC-03` (§6 l.70), `SPEC-04` (§5, §7), `SPEC-06` (§6).
* **Niveau de confiance** : **90 % (Élevé)**.

---

## Livrable 3 — Vérification contradictoire des `AUD-V3-001` à `AUD-V3-013` (Passe B)

Le tableau ci-dessous évalue de manière indépendante et contradictoire chacun des 13 constats historiques au regard de l’état réel des fichiers du commit `51349aab2cd79692e4dd7a7f7c6db84960d89c80` :

| ID historique | Sujet du constat initial | Statut réel après consolidation | Preuves documentaires précises & Justification contradictoire |
| :--- | :--- | :--- | :--- |
| **`AUD-V3-001`** | Absence de matrice d’autorisation WRITE et impasse du créateur (`creatorId`) à `V1` après `CreatePot` | **Partiellement résolu** | • **Résolu dans `SPEC-01`, `SPEC-04`, `SPEC-06`** : `SPEC-01` §5 (l.58–70) définit la table des droits WRITE ; `SPEC-04` §§9–10 (l.96–104) et `SPEC-06` §4 (l.61) incluent explicitement `creatorId` dans les utilisateurs autorisés de `PotVersion(P,V)`.<br>• **Mais contredit dans `SPEC-10`** (`GEM-V3-007`) : `SPEC-10` `SEC-01` (`S01-F` l.856) impose toujours un **Refus** dès qu’*« aucun Shareholder [n’est] lié au User dans Pot@V »*, et aucun scénario de `S01-A..O` ne valide l’accès du créateur sans `Shareholder` ! |
| **`AUD-V3-002`** | Règle d’attribution / détachement de `ShareholderDetails.userId` et paradoxe d’auto-rattachement | **Résolu mais générateur d’un nouveau problème (Bloquant)** | • Pour clore `AUD-V3-002`, l’ancienne phrase de `V3-3` §4 a été supprimée et remplacée par `SPEC-01` §8 (l.131–149, `PotInvitation`).<br>• **Génère `GEM-V3-001` (Bloquant)** : `SPEC-01` §8 exige une vue minimale en lecture des `Shareholders` libres à partir d’une `PotInvitation` stockée uniquement en WRITE, violant `SPEC-04` §1 / `SPEC-06` §1, sans événement (`SPEC-03`), sans projection (`SPEC-04`), sans endpoint (`SPEC-06`) et sans test (`SPEC-10`).<br>• **Génère `GEM-V3-002` (Bloquant)** : `SPEC-01` §§5–6 n’interdit pas à un `Shareholder` actif quelconque de mettre `ShareholderDetails.userId = null` sur les autres `Shareholders` via `ModifyShareholderDetails`. |
| **`AUD-V3-003`** | Incomplétude des préconditions `observedVersion` sur les `Shareholders` d’une `Expense` et rupture de somme nulle via `DeleteShareholder` | **Résolu correctement** | • `SPEC-01` §3 (l.41) et §6 (l.89, l.94–95, l.99) interdisent explicitement `DeleteShareholder` si le `Shareholder` est référencé comme payeur ou participant dans au moins une `Expense` active à la version courante.<br>• `SPEC-01` §6 (l.90–91) exige pour `CreateExpense` et `ModifyExpenseFinancials` que `ShareholderHeader.activeFrom <= observedVersion` et `ShareholderDetails.activeFrom <= observedVersion` pour le payeur et chaque participant.<br>• Aligné dans `SPEC-10` (`D02-J`, `D02-K`, `D02-L` l.147–149). |
| **`AUD-V3-004`** | Race condition sur la fenêtre `(K, H]` des séquences d’événements et sous-spécification de l’avancement de `K` | **Résolu correctement** | • `SPEC-03` §5 (l.55–64) et `SPEC-05` §2 (l.18–26) définissent la borne haute sûre `H` comme le dernier `sequenceNumber` du préfixe contigu committé sans trou visible, interdisent `MAX(sequence_number)` naïf et imposent la persistance atomique de `K <- H` avec la création des slots. Aligné dans `SPEC-10` (`E01-A..K`, `C01-A..J`). |
| **`AUD-V3-005`** | Sur-architecture du pipeline READ (`PreparePotProjectionTasks`, `POT_BALANCE_TASK`, `POT_VISIBILITY_TASK` et double reconstruction de `Pot@V`) | **Partiellement résolu** | • **Résolu dans `SPEC-03`, `SPEC-04`, `SPEC-05`** : les tâches intermédiaires ont été supprimées au profit de 3 Consumptions directes de `POT_EVENT` (`MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`).<br>• **Mais non nettoyé dans `SPEC-08`, `SPEC-09` et `SPEC-10`** (`GEM-V3-007`) : `SPEC-08` §2 l.38, `SPEC-10` `CON-01` l.381, `C04-J` l.520, `C06-D` l.611, `P02-I/J` l.739–740 et surtout l’exigence bloquante `REC-01` (`RCV01-E`, `RCV01-I`, l.1005–1030) imposent encore de créer et compléter les *« deux Projection Tasks par événement Pot »*. |
| **`AUD-V3-006`** | Redondance du `BurnableDiscovery` par polling pour `COMMAND` et `IDENTITY_REQUEST` | **Résolu correctement (arbitrage explicite)** | • Arbitrage assumé et cohérent dans `SPEC-05` §2 (l.21) et `V3_Canon_and_Roadmap.md` §6 (l.67) : conservation de la `Discovery` burnable asynchrone (avec index partiel `PENDING` et anti-join sur `(consumptionId, targetId)`) afin de garder l’admission HTTP WRITE découplée du moteur de slots. |
| **`AUD-V3-007`** | Blocage d’une partition par les slots `QUARANTINED` dans le décompte du plafond souple (`softCap`) | **Partiellement résolu (et générateur d’une contradiction d’oracle)** | • **Résolu sur le `softCap`** : `SPEC-05` §2 (l.23) et §7 (l.69) excluent explicitement les slots `QUARANTINED` du calcul du `softCap` (`READY` + `CLAIMED` uniquement).<br>• **Mais génère `GEM-V3-009` (Majeur)** dans l’oracle `SPEC-10` `CON-05` (l.572), qui exige que le point de lecture à `V` reste « indisponible » (`503`) lorsque `P@V` est en quarantaine et que `V+1` progresse, contredisant `SPEC-06` §4 l.61 (`PotVersion(P,V)` absente $\rightarrow$ `404`). |
| **`AUD-V3-008`** | Infaisabilité standard de l’attestation externe IdP pour `Link` (`C-01`) et contradiction avec l’absence d’index inverse dans `CurrentBinding` | **Résolu correctement** | • L’ancienne exigence d’affichage de compteurs métier READ (`potCount`, `linkedEuiCount`) par l’IdP a été retirée (`SPEC-02` §5 l.50–67, `SPEC-04` §6 l.67, `SPEC-06` §10 l.150–159, `SPEC-10` `C-01` l.1037–1071) au profit d’une attestation transactionnelle signée liant `(LINK, E1, E2, nonce, iat, exp)`. |
| **`AUD-V3-009`** | Récupération du nouveau `bindingToken` via `CurrentBinding` après `Registration` / `Link` et chargement depuis WRITE | **Résolu mais générateur d’un nouveau problème (Bloquant)** | • **Résolu sur `CurrentBinding`** : `SPEC-04` §6 (l.64–66) explicite que `MaterializeCurrentBinding` charge `EUI@V` depuis WRITE, et `SPEC-06` §7 (l.107–115) définit `GET /identity/me/binding` comme propre à l’EUI authentifiée dans le Bearer token.<br>• **Mais génère `GEM-V3-003` (Bloquant)** : en interdisant à `E1` de jamais connaître le `bindingToken` de `E2` tout en maintenant dans `SPEC-02` §4 (l.39) et `SPEC-10` (`I01-I` l.198) l’obligation de fournir le `bindingToken` courant de `E2` lors d’un `Unlink(E1 -> E2)`, la spécification rend `Unlink(E1 -> E2)` impossible ! |
| **`AUD-V3-010`** | Absence d’autorisation propriétaire et d’arbitrage HTTP (`404` vs `503`) sur `CommandResult` et `IdentityRequestResult` | **Résolu correctement** | • `SPEC-03` §4 (l.51), `SPEC-04` §7 (l.76–82) et `SPEC-06` §8 (l.119–133) intègrent `requesterEui` dans les événements de complétion et les projections de résultat, et fixent la matrice `200` (même `requesterEui`) / `404` (autre `requesterEui` ou demande inconnue/expirée) / `503` (pendant la fenêtre bornée post-admission pour la même EUI). Testé par `A10-A` à `A10-K` (`SPEC-10` l.942–956). |
| **`AUD-V3-011`** | Lecture d’un Pot supprimé (`DeletePot`) vis-à-vis de `PotVersion(P,V)` et de l’oracle `C-03`, et ordre de vérification `404` vs `503` | **Résolu correctement** | • `SPEC-01` §7 (l.115–129), `SPEC-04` §§9–10 (l.96–106), `SPEC-06` §§4–5 (l.60–95) et `SPEC-09` §4 (l.55) définissent la version terminale `D` (`404` sur la lecture courante et sur `D`, lectures historiques `< D` préservées pour les acteurs autorisés à `D` et à `V`, oracle interne pour `DeletePot`, et évaluation stricte `404` avant `503`). Testé par `A11-A` à `A11-N` (`SPEC-10` l.960–979). |
| **`AUD-V3-012`** | Contradictions documentaires internes et numérotations conflictuelles | **Partiellement résolu** | • Les doublons de sections et la collision `S-02` vs `S-04` ont été nettoyés dans `SPEC-04` et `SPEC-06`.<br>• **Mais non résolu dans `SPEC-08`, `SPEC-09` et `SPEC-10`** (`GEM-V3-007`), où subsistent des pans entiers de l’ancienne architecture (tâches READ, interdiction d’API historique, rejet du créateur sans Shareholder, table `A11` dupliquée en fin de fichier). |
| **`AUD-V3-013`** | Coût d’amplification d’écriture et de payload HTTP sur un « Grand Pot » (100 Shareholders, 2 000 Expenses) | **Partiellement résolu** | • `SPEC-04` §3 (l.38–40) et `SPEC-09` §2 (l.29) ajustent le chargement aux seules `SubobjectVersion` absentes de READ et fixent un SLO dédié Grand Pot (`p95 <= 2 s` à 1 cmd/s).<br>• **Mais génère `GEM-V3-008` (Majeur)** sur l’impossibilité transactionnelle de `SubobjectVersion` manquante après commit de `PotVersion(P,V)` et sur la détection de divergence de contenu en cas de conflit d’insertion. |

**Synthèse de la Passe B** : Sur les 13 constats du premier audit :
* **6 sont résolus correctement** (`AUD-V3-003`, `AUD-V3-004`, `AUD-V3-006`, `AUD-V3-008`, `AUD-V3-010`, `AUD-V3-011`) ;
* **5 sont partiellement résolus** (`AUD-V3-001`, `AUD-V3-005`, `AUD-V3-007`, `AUD-V3-012`, `AUD-V3-013`) ;
* **2 ont été résolus en introduisant de nouveaux problèmes bloquants** (`AUD-V3-002` $\rightarrow$ `GEM-V3-001` & `GEM-V3-002` ; `AUD-V3-009` $\rightarrow$ `GEM-V3-003`).

---

## Livrable 4 — Matrice de cohérence inter-SPEC

| Interface / Invariant partagé | Autorité normative principale | SPEC participantes | État de cohérence inter-SPEC & Contradictions détectées | Preuve / Oracle `SPEC-10` associé |
| :--- | :--- | :--- | :--- | :--- |
| **1. Modèle de versionnement `[activeFrom, activeTo]` et reconstruction `Pot@V`** | `SPEC-01` (§§1–4, 6) | `SPEC-03` (§4), `SPEC-04` (§3), `SPEC-05` (§4) | **Cohérent** sur les règles d’ouverture/fermeture et les préconditions `observedVersion` (incluant `DeleteShareholder` et `ShareholderDetails`). | `DOM-01` (`D01-A..K`), `DOM-02` (`D02-A..L`), `PRJ-02` (`P02-A..H`) |
| **2. Droits du créateur (`creatorId`) et des `Shareholders` actifs en WRITE et en READ** | `SPEC-01` (§5), `SPEC-04` (§§9–10) | `SPEC-06` (§§4–5), `SPEC-10` (`SEC-01`) | **Contradiction (`GEM-V3-002`, `GEM-V3-007`)** :<br>• `SPEC-01` §5 autorise tout `Shareholder` actif à modifier `ShareholderDetails.userId` d’autrui.<br>• `SPEC-10` `S01-F` (l.856) refuse la lecture si aucun `Shareholder` n’est lié au User (contredisant le droit du créateur non-Shareholder de `SPEC-01` §5 et `SPEC-04` §9). | `DOM-02`, `SEC-01` (`S01-F` **erroné**), `DELETE-01` (`A11-A..N`) |
| **3. Invitation Pot et auto-rattachement d’un `Shareholder` libre (`PotInvitation`)** | `SPEC-01` (§8) | `SPEC-03`, `SPEC-04`, `SPEC-06`, `SPEC-10` | **Rupture inter-SPEC bloquante (`GEM-V3-001`)** : `SPEC-01` §8 stocke `PotInvitation` en WRITE et promet une vue de choix en lecture sans accès au Pot, mais `SPEC-03` n’a aucun événement, `SPEC-04` aucune projection, `SPEC-06` aucun endpoint et interdit de lire WRITE depuis READ, et `SPEC-10` aucun test. | **Aucun oracle dans `SPEC-10`** |
| **4. Cycle de vie `Identity` (`Registration`, `Link`, `Unlink`) et confidentialité du `bindingToken`** | `SPEC-02` (§§2–7) | `SPEC-03` (§§3–4, 6), `SPEC-04` (§6), `SPEC-05` (§4), `SPEC-06` (§§7, 10) | **Contradictions bloquante et majeures (`GEM-V3-003`, `GEM-V3-004`, `GEM-V3-005`, `GEM-V3-006`)** :<br>• `SPEC-02` §4 l.39 exige le `bindingToken` de `E2` pour `Unlink(E1->E2)`, mais `SPEC-02` §6 / `SPEC-06` §7 interdisent à `E1` de l’obtenir (`GEM-V3-003`).<br>• `Unlink` de la dernière EUI rend `PocomaUser` irrécupérable (`GEM-V3-004`).<br>• Verrouillage anti-deadlock à 2 EUI renvoyé à `SPEC-05` mais absent de `SPEC-05` (`GEM-V3-005`).<br>• `IDENTITY_EVENT(POCOMA_USER)` émis sans consommateur (`GEM-V3-006`). | `ID-01` (`I01-A..K`), `ID-02` (`I02-A..N`), `C-01` |
| **5. Séquencement sans trou visible `(K, H]` et exécution bornée avec fencing / quarantaine** | `SPEC-03` (§5), `SPEC-05` (§§1–8) | `SPEC-04` (§8), `SPEC-08` (§§1–4), `SPEC-10` (`EVT-01..03`, `CON-01..06`) | **Contradiction d’oracle (`GEM-V3-009`)** : Le mécanisme de `SPEC-03` et `SPEC-05` est sain, mais `SPEC-10` `CON-05` l.572 exige que la version `V` en quarantaine reste « indisponible » (`503`) même si `PotVersion(P,V)` est absente (`404` selon `SPEC-06` §4). | `EVT-01..03`, `CON-01..06` |
| **6. Pipeline READ direct (3 Consumptions `POT_EVENT` sans tâches intermédiaires)** | `SPEC-03` (§7), `SPEC-04` (§§2–5, 8), `SPEC-05` (§8) | `SPEC-08` (§2), `SPEC-09` (§§3–4), `SPEC-10` (`PRJ-01..03`, `REC-01`, `PERF-01`) | **Contradiction majeure (`GEM-V3-007`, `GEM-V3-008`)** :<br>• `SPEC-08` §2 l.38 et `SPEC-10` (`REC-01` l.1005–1030, `CON-01`, `C06-D`, `P02-I/J`) exigent encore les 2 `Projection Tasks` READ supprimées.<br>• `SPEC-09` §3 inclut encore `PotVisibilityState` dans le budget E2E Pot, contredisant `SPEC-09` §4. | `PRJ-01..03`, `REC-01` (`C-02` **incompatible**), `PERF-01` (`C-03`) |
| **7. Contrats HTTP Supra (Admission `S-01`/`S-03`, Lecture `404` avant `503`, Manifeste `S-04`, Résultats `CommandResult` / `IdentityRequestResult`)** | `SPEC-06` (§§1–10) | `SPEC-04` (§§3–10), `SPEC-07`, `SPEC-10` (`ADM-01`, `SUPRA-READ-01`, `DELETE-01`, `S-01..04`) | **Cohérent** sur l’admission, les résultats (`A10-A..K`), la suppression `DeletePot` (`A11-A..N`) et le manifeste d’Expenses (`S-04`), sous réserve de préciser `resultingPotVersion` sur un `no-op` (`GEM-V3-010`) et l’injection de faute pour `SubobjectVersion` manquante (`GEM-V3-008`). | `ADM-01`, `SUPRA-READ-01`, `DELETE-01`, `S-01`, `S-03`, `S-04` |

---

## Livrable 5 — Audit de certification (`SPEC-10`)

L’examen exhaustif des 1 187 lignes de [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) révèle quatre catégories de défauts dans le dispositif de certification :

### 5.1 Oracles absents (exigences normatives de `SPEC-01..06` sans aucune couverture dans `SPEC-10`)

1. **Parcours d’invitation et d’auto-rattachement (`SPEC-01` §8)** :
   * **0 scénario** dans `SPEC-10` ne teste la création d’une `PotInvitation`, la consultation de la vue minimale des `Shareholders` libres, l’auto-rattachement d’un `PocomaUser` non encore membre, l’expiration `expiresAt`, la révocation par `DeletePot` ou le rejet si l’invité possède déjà un `Shareholder` actif (`GEM-V3-001`).
2. **Accès READ et WRITE du créateur non-Shareholder (`SPEC-01` §5, `SPEC-04` §§9–10)** :
   * Alors que `A11-A..N` teste le créateur lors de `DeletePot`, **aucun scénario de `SEC-01` (`S01-A..O`, l.851–865) ni de `DOM-02` (`D02-A..L`, l.138–149)** ne certifie positivement qu’un créateur qui n’est rattaché à aucun `Shareholder` peut lire `Pot@V1` après `CreatePot`, lire une version historique `Pot@V`, et exécuter les commandes WRITE réservées au créateur (`GEM-V3-007`).
3. **Autorisation sur la modification de `ShareholderDetails.userId` (`SPEC-01` §§5–6)** :
   * Aucun scénario de `DOM-02` ne teste qui a le droit de renseigner ou d’effacer `ShareholderDetails.userId` (`GEM-V3-002`).

### 5.2 Oracles obsolètes ou contradictoires avec l’architecture consolidée (`GEM-V3-007`)

1. **Maintien des `Projection Tasks` supprimées dans l’oracle bloquant `C-02` (`REC-01`) et dans 5 autres exigences** :
   * `REC-01` (l.992, l.994, l.1001, `RCV01-E` l.1005, `RCV01-F` l.1006, `RCV01-I` l.1009, `RCV01-J` l.1010, l.1024, l.1026, l.1030) impose comme critère de certification bloquant `C-02` la recréation et la complétion des *« deux Projection Tasks par événement Pot »*.
   * `CON-01` (l.381), `CON-04` (`C04-J` l.520), `CON-06` (`C06-D` l.611), `PRJ-02` (`P02-I`, `P02-J` l.739–749) et `PERF-01` (l.1140, l.1156) référencent également ces tâches supprimées ou l’exigence retirée `PRJ-04`.
2. **Oracle `S01-F` (l.856) et section `PRJ-01` (l.661–694) inversés par rapport aux SPEC** :
   * `S01-F` (l.856) impose un `Refus` si aucun `Shareholder` n’est lié à l’utilisateur dans `Pot@V` (faux pour le créateur `creatorId`).
   * `PRJ-01` (l.671, l.685) et `SEC-01` (l.869) interdisent encore d’exposer l’API de lecture historique à `V` pourtant normative dans `SPEC-04` §10, `SPEC-06` §4 et testée dans `A11-F`.

### 5.3 Oracles contradictoires avec une règle de sécurité ou d’isolation (`GEM-V3-003`, `GEM-V3-009`)

1. **`I01-I` (l.198) vs `S01-J` / `SPEC-06 §7`** :
   * `I01-I` teste le rejet d’un `Unlink(E1 -> E2)` lorsque le `bindingToken` de `E2` fourni par `E1` est obsolète, alors que `SPEC-02` §6 et `SPEC-06` §7 interdisent à `E1` de connaître le `bindingToken` de `E2` (`GEM-V3-003`).
2. **`CON-05` (l.572) vs `A11-E` / `SPEC-06 §4`** :
   * `CON-05` (l.572) exige que la version `V` mise en quarantaine garde son point de lecture « indisponible » (`503`), alors que si `PotVersion(P,V)` est absente en READ, `SPEC-06` §4 impose `404 Not Found` (`GEM-V3-009`).

### 5.4 Oracles supposant une injection de faute non qualifiée comme telle (`GEM-V3-008`)

* `P01-H` (l.684) et `R01-D` (l.819) exigent de tester l’absence d’une `SubobjectVersion` alors que `CurrentPot(V)` / `PotVersion(P,V)` est présente. Comme `MaterializePotVersion` les écrit dans la même transaction READ (`SPEC-04` §3 l.29), ces deux tests doivent être explicitement qualifiés de **tests d’injection de faute en base READ** (et non de tests de concurrence asynchrone des Workers).

---

## Livrable 6 — Verdict indépendant

### Verdict : **`NO-GO`** (pour l’entrée immédiate en implémentation `V3-6` sans correction préalable des blocages normatifs `GEM-V3-001` à `GEM-V3-003` et nettoyage de `GEM-V3-005` à `GEM-V3-009`)

> **Justification du `NO-GO`** :
> La refonte documentaire issue du premier audit a considérablement assaini le cœur du moteur (`SPEC-03`, `SPEC-04`, `SPEC-05`) et les contrats de suppression/résultats (`AUD-V3-003`, `004`, `005`, `008`, `010`, `011`).
> **Toutefois**, trois mécanismes introduits ou modifiés lors de cette consolidation contiennent des contradictions normatives **bloquantes** qui empêchent une implémentation cohérente dès les Lots 1, 3, 4 et 5 de `V3-6` :
> 1. **`GEM-V3-001` (Bloquant)** : Le mécanisme `PotInvitation` (`SPEC-01` §8) est un corps étranger incomplet qui viole l’interdiction de lecture synchrone de WRITE depuis READ et ne possède ni commande, ni événement, ni projection READ, ni endpoint Supra, ni test.
> 2. **`GEM-V3-002` (Bloquant)** : L’absence de règle d’autorisation sur `ShareholderDetails.userId` dans `SPEC-01` §§5–6 permet à tout `Shareholder` actif d’expulser unilatéralement les autres participants du Pot via `ModifyShareholderDetails(userId = null)`.
> 3. **`GEM-V3-003` (Bloquant)** : Le contrat d’`Unlink(E1 -> E2)` dans `SPEC-02` §4 l.39 exige que `E1` fournisse le `bindingToken` de `E2`, que `SPEC-02` §6 et `SPEC-06` §7 lui interdisent formellement de connaître.
> 4. **`GEM-V3-005` à `GEM-V3-009` (Majeurs)** : Le verrouillage anti-deadlock à deux EUI promis par `SPEC-02` §7 est absent de `SPEC-05`, `IDENTITY_EVENT(POCOMA_USER)` n’a aucun consommateur, et `SPEC-08` / `SPEC-09` / `SPEC-10` contiennent encore des oracles bloquants (`REC-01` / `C-02`, `S01-F`, `CON-05`) qui contredisent directement `SPEC-01`, `SPEC-04`, `SPEC-05` et `SPEC-06`.

Ces points ne relèvent pas d’un ajout de complexité : ils se résolvent par une **clarification et un élagage documentaires rapides et ciblés** avant de lever la suspension de `V3-6`.

---

### Répartition précise des actions par jalon

#### 1. Ce qui doit impérativement être arbitré et corrigé **avant `V3-6`** (condition de passage au `GO`)

1. **`[GEM-V3-001]` Arbitrer le parcours d’invitation (`SPEC-01` §8)** :
   * Soit compléter son contrat minimal sans violer READ/WRITE (exposer un booléen `isFree` sur les références de `Shareholders` dans `PotVersion`, projeter `PotInvitation` en READ ou utiliser un jeton vérifiable, définir la commande d’auto-rattachement sans `observedVersion` dans `SPEC-01` §§5–6 et `SPEC-06`, et ajouter son scénario dans `SPEC-10`),
   * Soit sortir `PotInvitation` du périmètre V3 initial si l’auto-rattachement par lien d’invitation n’est pas requis au premier jalon.
2. **`[GEM-V3-002]` Verrouiller les règles de mutation de `ShareholderDetails.userId` (`SPEC-01` §§5–6)** :
   * Interdire à tout acteur d’affecter le `PocomaUserId` d’un tiers ; n’autoriser le rattachement (`null -> U_caller`) qu’à l’appelant lui-même ; n’autoriser le détachement (`U_target -> null`) qu’au créateur du Pot (`creatorId`) ou à `U_target` lui-même (en précisant sa compatibilité avec les `Expenses` actives).
3. **`[GEM-V3-003]` et `[GEM-V3-004]` Corriger le contrat d’`Unlink` (`SPEC-02` §§4, 6 ; `SPEC-03` §3 ; `SPEC-10` `I01-I`)** :
   * Exiger uniquement le `bindingToken` actif de l’EUI initiatrice `E1` et vérifier en WRITE que `E1` et `E2` sont activement liées au même `PocomaUser U` (sans exiger que `E1` fournisse le secret `bindingToken` de `E2`), ou restreindre `Unlink` en V3 à l’auto-détachement (`E1 == E2`).
   * Interdire l’`Unlink` de la dernière EUI active d’un `PocomaUser` (ou documenter explicitement que cela abandonne définitivement le `PocomaUser`), et permettre `Link` (en plus de `Registration`) au §8 l.139 de `SPEC-01`.
4. **`[GEM-V3-005]` et `[GEM-V3-006]` Aligner `SPEC-02`, `SPEC-03` et `SPEC-05` sur `Identity`** :
   * Inscrire dans `SPEC-05` §4 le verrouillage exclusif de `E1` et `E2` dans l’ordre canonique `(issuer, subject)` lors de l’exécution WRITE de `Link` et `Unlink`.
   * Supprimer `IDENTITY_EVENT(POCOMA_USER)` de `SPEC-03` (ou spécifier son filtrage explicite par `MaterializeCurrentBinding`).
5. **`[GEM-V3-007]`, `[GEM-V3-008]` et `[GEM-V3-009]` Purger les contradictions résiduelles de `SPEC-08`, `SPEC-09` et `SPEC-10`** :
   * Supprimer les références aux 2 `Projection Tasks` READ dans `SPEC-08` §2 l.38 et `SPEC-10` (`REC-01` l.992–1030, `CON-01` l.381, `C04-J` l.520, `C06-D` l.611, `P02-I/J` l.739–749, `PERF-01` l.1140/1156).
   * Aligner `SPEC-09` §3 sur `SPEC-09` §4 (`PotVisibilityState` hors du jalon de lecture du Pot).
   * Corriger `S01-F` (l.856) pour autoriser le créateur non-Shareholder, supprimer la table `A11` dupliquée aux lignes 1164–1183 de `SPEC-10`, et corriger l’oracle `CON-05` (l.572) pour respecter `404` quand `PotVersion(P,V)` est absente.

#### 2. Ce qui peut être déterminé ou prouvé **pendant `V3-6`** (réalisation par lots)

* **`[GEM-V3-010]`** : Précision de `resultingPotVersion = V_courant` sur une commande `no-op` et du payload exact d’un item de `CurrentUserPotList`.
* **Réalisation PostgreSQL du verrouillage de compteur et de la requête de fenêtre sûre `(K, H]`** (`SPEC-03` §5, `SPEC-05` §2).
* **Intégration concrète de la vérification d’attestation `Link` (`C-01`)** dans le profil d’intégration retenu (`SPEC-02` §5, `SPEC-06` §10).
* **Valeur numérique par défaut de `maxAttempts` (`N`)** avant passage d’un slot à `QUARANTINED` (`SPEC-05` §7) et durée de rétention des clés d’idempotence (`S-03`).

#### 3. Ce qui relève de **`V3-7`** (certification finale)

* Exécution complète des suites d’oracles `SPEC-10` (`DOM`, `ID`, `EVT`, `CON`, `PRJ`, `READ`, `SEC`, `OBS`, `ADM`, `SUPRA-READ`, `DELETE`, `REC`, `PERF`).
* Validation expérimentale des budgets `PERF-01` / `PERF-02` (`C-03`) sur le profil nominal (p95 ≤ 500 ms à 20 cmd/s) et sur le profil « Grand Pot » (100 Shareholders, 2 000 Expenses, p95 ≤ 2 s à 1 cmd/s) en Configurations A et B.
* Validation de la reconstruction complète à froid `C-02` (`REC-01`) et de la résilience sous pannes injectées.

#### 4. Ce qui peut rester **hors périmètre V3**

* Tout mécanisme de fusion de deux `PocomaUser` existants (`U1` et `U2`) ou de transfert d’historique entre `PocomaUser` (`SPEC-02` §8).
* Toute optimisation prématurée par calcul différentiel ou cache durable des balances (`SPEC-04` §4) tant que le calcul rationnel en lot par version respecte le budget Grand Pot (`p95 <= 2 s`) en `V3-7`.
* Tout annuaire inverse global ou recherche publique d’utilisateurs dans `Identity` (`SPEC-02` §8, `SPEC-04` §6).

---

## Addendum post-audit — Résolution documentaire de `GEM-V3-001` à `GEM-V3-010` (2026-10-24)

Les dix points `GEM-V3-001` à `GEM-V3-010` relevés ci-dessus ne remettaient en cause aucun invariant structurel de l’architecture Pocoma V3 et ont été intégralement résolus dans les spécifications normatives :

| ID | Résolution appliquée | Fichiers mis à jour |
| :--- | :--- | :--- |
| **`GEM-V3-001`** | Exposition de l’indicateur booléen public `isFree` (`userId == null`) sur chaque référence de `Shareholder` dans `PotVersion(P,V)` (sans exposer `userId`) pour servir la vue minimale de choix depuis READ sans accès WRITE synchrone ; ajout de la précondition d’auto-rattachement sans `observedVersion` dans `SPEC-01` §6. | `SPEC-01` (§6, §8), `SPEC-04` (§3) |
| **`GEM-V3-002`** | Règle explicite sur `ShareholderDetails.userId` : rattachement (`null -> U`) limité au propre `PocomaUserId` de l’appelant ; détachement (`U_target -> null`) réservé au créateur (`creatorId`) ou à `U_target` lui-même. | `SPEC-01` (§5) |
| **`GEM-V3-003`** | Alignement d’`Unlink(E1 -> E2)` : `E1` fournit uniquement son propre `bindingToken` actif ; WRITE vérifie que `E1` et `E2` sont activement liées au même `U`, sans exiger le secret `bindingToken` de `E2`. | `SPEC-02` (§4, §5, §6), `SPEC-10` (`I01`, `I01-I`) |
| **`GEM-V3-004`** | Explicitation du caractère irrévocable de l’`Unlink` de la dernière EUI d’un `PocomaUser`, et possibilité de `Link` (en plus de `Registration`) avant auto-rattachement sur invitation. | `SPEC-01` (§8), `SPEC-02` (§4) |
| **`GEM-V3-005`** | Inscription dans `SPEC-02` §7 et `SPEC-05` §4 du verrouillage exclusif de `E1` et `E2` dans l’ordre lexicographique canonique `(issuer, subject)` lors des opérations Identity à deux EUI. | `SPEC-02` (§7), `SPEC-05` (§4), `SPEC-10` (`I01`) |
| **`GEM-V3-006`** | Filtrage explicite par `MaterializeCurrentBinding` des seuls `IDENTITY_EVENT` de type `EXTERNAL_USER_IDENTITY` (`POCOMA_USER` ignoré). | `SPEC-03` (§7), `SPEC-04` (§6) |
| **`GEM-V3-007`** | Purge des références résiduelles aux anciennes `Projection Tasks` READ dans `SPEC-08` §2 et `SPEC-10` (`CON-01`, `C04-J`, `C06-D`, `P02-I/J`, `REC-01` / `C-02`, `OPS-01`, `PERF-01`), alignement de `SPEC-09` §3 sur §4, correction de `S01-A`/`S01-F` pour le créateur non-Shareholder et suppression de la table `A11` dupliquée en fin de `SPEC-10`. | `SPEC-08` (§2), `SPEC-09` (§§3–4), `SPEC-10` |
| **`GEM-V3-008`** | Qualification explicite de `P01-H` et `R01-D` (`SubobjectVersion` absente alors que `PotVersion(P,V)` est présente) comme scénarios d’injection de faute en base READ. | `SPEC-10` (`P01-H`, `R01-D`) |
| **`GEM-V3-009`** | Alignement de l’oracle de quarantaine `CON-05` avec la règle `404` avant `503` (`404` à `V` si `PotVersion(P,V)` est en quarantaine, `503` à `V` si seule `PotBalance(P,V)` est en quarantaine, et `200` en lecture courante `minVersion=V` dès que `V+1` est prête). | `SPEC-10` (`CON-05`) |
| **`GEM-V3-010`** | Précision de `resultingPotVersion = V_courant` pour une commande valide sans effet (`no-op`). | `SPEC-01` (§6), `SPEC-03` (§6), `SPEC-04` (§7) |

**Verdict actualisé après nettoyage documentaire : `GO` architectural et documentaire pour `V3-6`.**

