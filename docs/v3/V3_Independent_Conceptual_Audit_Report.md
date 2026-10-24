# Rapport d’audit architectural indépendant — Cadrage conceptuel Pocoma V3 (`V3-CADRE`)

* **Date de l’audit** : 2026-10-24
* **Dépôt audité** : `https://github.com/kartaguez/pocoma`
* **Branche auditée** : `feat/v3-architecture`
* **SHA exact du `HEAD` audité** : `7438b94de49fe368c417466b77abf87659f9f7d2`
* **Corpus normatif audité (`docs/v3/`)** :
  * [V3_Canon_and_Roadmap.md](./V3_Canon_and_Roadmap.md)
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md)
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md)
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md)
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md)
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md)
  * [SPEC-07_Transverse_Requirements.md](./SPEC-07_Transverse_Requirements.md)
  * [SPEC-08_Observability.md](./SPEC-08_Observability.md)
  * [SPEC-09_Performance.md](./SPEC-09_Performance.md)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md)
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md)

---

## I. Synthèse exécutive

### 1. Verdict global : **GO SOUS RÉSERVES**

Le cadrage conceptuel Pocoma V3 repose sur une colonne vertébrale saine, nettement plus claire et robuste que l’architecture V2 : la décomposition de `Pot` en sous-objets versionnés immuables indexés par `(activeFrom, activeTo)`, le contrôle d’obsolescence par simple comparaison `activeFrom <= observedVersion` sans relecture d’événements, l’introduction de `PotVersion(P,V)` immuable avec pointeur `CurrentPot(P)`, et l’alignement des slots de `Consumption` sur l’autorité transactionnelle des effets constituent des avancées majeures.

Cependant, la reconstitution de bout en bout des flux met en évidence **1 constat bloquant**, **9 constats majeurs** et **3 constats mineurs** qui empêchent de lancer la réalisation du code (`V3-6`) sans arbitrage préalable. En l’état, un développeur appliquant littéralement les documents V3 aboutirait à un système où **un Pot nouvellement créé (`V1`) est visible dans la liste de son créateur mais impossible à lire (`404`) et dépourvu de matrice d’autorisation d’écriture**, où **un changement concurrent d’identité d’un `Shareholder` peut imputer une dépense à la mauvaise personne sans rejet `OBSOLETE`**, et où **la pipeline de projection READ traverse un étage intermédiaire redondant (`PreparePotProjectionTasks` + tâches burnables)** qui double les reconstructions WRITE et le stockage READ sans apporter de garantie supplémentaire.

### 2. Les 5 risques principaux

1. **Impasse d’autorisation du créateur non-Shareholder et absence de matrice d’autorisation WRITE (`AUD-V3-001` — Bloquant)** : `V3-1` autorise un créateur distinct des `Shareholders` et fait de `CreatePot` (`V1`) une opération sans `Shareholder` (ajoutés en `V2`). Or `V3-3` (§9–10) et `V3-5D` (§43) réservent la lecture de `PotVersion(P,V)` aux seuls `PocomaUser` rattachés à un `Shareholder` actif à `V` (en exigeant même de tester le refus pour le créateur non-Shareholder), tout en rendant le Pot visible au créateur dans `CurrentUserPotList`. Résultat : à `V1`, le créateur voit le Pot dans sa liste mais reçoit `404` en le consultant, ne peut jamais valider l’oracle `C-03` (`PERF-01`), et `V3-1` ne définit nulle part qui a le droit d’exécuter une `Command` en WRITE.
2. **Trou de concurrence sur les références croisées et l’existence à `observedVersion` (`AUD-V3-003` — Majeur)** : La table des dépendances de `V3-1` (§5) vérifie uniquement `activeFrom <= observedVersion` sur la composante modifiée, sans vérifier que `ShareholderDetails` (notamment `userId` / nom) des payeurs et participants d’une `Expense` n’a pas changé depuis `observedVersion`, ni formuler explicitement que l’en-tête (`Header`) d’un sous-objet ciblé devait déjà exister (`Header.activeFrom <= observedVersion`). Une dépense soumise en observant Alice (`S1@V5`) peut être imputée silencieusement à Charlie (`S1@V6`).
3. **Sur-complexité et coût de l’étage intermédiaire `PreparePotProjectionTasks` + tâches burnables (`AUD-V3-005` — Majeur)** : Pour chaque `POT_EVENT`, `PreparePotProjectionTasks` reconstruit `Pot@V` une seconde fois depuis WRITE, calcule déjà les balances rationnelles et les utilisateurs visibles, puis persiste `PotBalanceTask(P,V,balances)` et `PotVisibilityTask(P,V,visibleUserIds)` en `PENDING` + `Slot`, qui doivent ensuite être redécouverts par `Discovery`, acquis par `Engine`, recopiés dans `PotBalance(P,V)` / `CurrentUserPotList`, et conservés indéfiniment en état `TERMINAL`.
4. **Angle mort d’authentification/autorisation sur `CurrentBinding`, `CommandResult` et `IdentityRequestResult` (`AUD-V3-009`, `AUD-V3-010` — Majeurs)** : Le nouveau `bindingToken` n’étant présent ni dans `IDENTITY_EVENT` ni dans `IdentityRequestResult`, le client doit lire `CurrentBinding(EUI cible)` après `Registration` ou `Link`. Or, lors d’un `Link` initié par `E1` vers `E2`, `E1` n’a pas le droit de lire le `bindingToken` de `E2` (`BLOC-03`). De plus, `CommandResult` et `IdentityRequestResult` ne stockent pas l’identité du demandeur en READ et leur code HTTP avant matérialisation (`404` vs `503`) a été laissé non tranché à la clôture de `V3-5E`.
5. **Infaisabilité standard de l’attestation externe IdP (`C-01`) et contradiction avec l’absence d’index inverse Identity (`AUD-V3-008` — Majeur)** : `V3-5D` (§32–37) délègue intégralement l’écran de confirmation de `Link` et l’affichage des compteurs métier (nombre de Pots accessibles, nombre d’EUI liés, horodatage) à l’IdP externe (Keycloak en Config B) sans page Pocoma, tout en interdisant dans `V3-3` (§1) tout index inverse `pocomaUserId -> EUI` nécessaire pour compter les EUI liés.

### 3. Principales qualités et simplifications réussies de la conception V3

* **Fermeture temporelle `[activeFrom, activeTo]` en WRITE (`V3-1`)** : La séparation nette entre `Header` (cycle de vie / `deleted`), `Details` (métadonnées / rattachement identitaire) et `Weight`/`Financials` permet à la fois une reconstruction SQL déterministe à toute version `V` et une détection d’obsolescence en $O(1)$ sans rejouer d’événements.
* **Immuabilité de `PotVersion(P,V)` et pointeur atomique `CurrentPot(P)` (`V3-3` §§9–10)** : Ce choix élimine toute course entre mise à jour de l’état courant et lectures concurrentes, permet de servir indifféremment le Pot courant et une version historique `V`, et retire proprement `PotVisibilityState` du chemin critique de lecture d’un Pot (`C-03`).
* **Alignement transactionnel `Slot ↔ Effets` (`V3-4` §4)** : Placer les slots et checkpoints dans la base de données des effets de la `Consumption` garantit l’atomicité `effet métier + avancement/suppression du slot` sans transaction distribuée (2PC) entre WRITE et READ.
* **Isolation stricte des identifiants externes (`EUI`) et du `bindingToken` (`V3-1 Identity`, `V3-2`)** : Le domaine `Pot` ne manipule que `PocomaUserId`, et les événements partagés ne divulguent jamais le `bindingToken`.

### 4. Niveau de confiance et limites de l’audit

* **Niveau de confiance** : **Élevé** sur le cadrage conceptuel. L’intégralité des 11 documents normatifs `docs/v3/` au commit `7438b94de49fe368c417466b77abf87659f9f7d2` a été lue ligne à ligne et croisée avec les études architecturales V2 pertinentes.
* **Limites** : Conformément au mandat, cet audit porte exclusivement sur le modèle conceptuel et documentaire `V3-CADRE` (sans exécution de tests ni inspection d’un code V3 qui n’existe pas encore). Les garanties de performance (`V3-5C`) et de tenue de charge PostgreSQL restent conditionnées à l’exécution effective du banc de certification (`V3-7`).

---

## II. Architecture fonctionnelle reconstruite

Afin de valider la compréhension de bout en bout sans présupposer la cohérence des textes, voici la reconstitution synthétique des 9 flux structurants tels qu’ils découlent des amendements les plus récents du corpus V3 :

1. **Création et modification d’un Pot (`Command` → `POT_EVENT` + `COMMAND_COMPLETED_EVENT`)** :
   * Le client appelle la Supra WRITE avec un jeton `EUI` authentifié, son `bindingToken`, une `Idempotency-Key`, et le payload de commande portant `observedVersion` (sauf création).
   * La Supra valide la forme intrinsèque (schéma, bornes numériques, absence de doublons, `Idempotency-Key`) et admet durablement une `COMMAND` (`PENDING`) en base WRITE dans la même transaction que l’enregistrement d’idempotence (`202 Accepted` + `requestId`).
   * `Discovery` (en WRITE) détecte la `COMMAND` `PENDING` sans slot et insère un `Slot(consumptionId, requestId)` partitionné par `potId`.
   * Un worker `Engine` acquiert le slot par bail (`lease`), charge `Identity(EUI)` en WRITE pour vérifier `bindingToken` et résoudre `PocomaUserId` (sans aller-retour READ), charge les versions courantes (`activeTo = null`) des sous-objets ciblés du `Pot`, et évalue les préconditions (`activeFrom <= observedVersion`, règles métier).
   * Dans une **unique transaction WRITE** :
     * *Si acceptée* : incrémente la version globale `V -> V+1`, ferme les instances remplacées (`activeTo = V`), crée les nouvelles instances (`activeFrom = V+1, activeTo = null`), alloue sous verrou transactionnel les `sequencePosition` de `POT_EVENT` et `COMMAND_COMPLETED_EVENT`, insère les deux événements, passe la `COMMAND` à `TERMINAL`, et supprime le slot.
     * *Si rejetée métier* (`OBSOLETE`, etc.) : n’émet aucun `POT_EVENT`, émet uniquement `COMMAND_COMPLETED_EVENT(REJECTED, raison)` sous verrou de séquence, passe la `COMMAND` à `TERMINAL`, et supprime le slot.
   * En READ, la `Consumption` de `COMMAND_COMPLETED_EVENT` matérialise `CommandResult(requestId, issue, potId, resultingPotVersion)`.

2. **Matérialisation READ d’un `POT_EVENT` (`PotVersion`, `CurrentPot`, `SubobjectVersion`)** :
   * En READ, `Discovery` de la `Consumption` `MaterializeCurrentPot` scanne `POT_EVENT` au-delà de `K(MaterializeCurrentPot, partitionId)` jusqu’à une borne sûre `H`, crée les slots pour sa partition (`hash(potId)`), et avance `K` atomiquement en READ.
   * Le worker reconstruit `Pot@V` depuis les sous-objets WRITE (`activeFrom <= V` et `(activeTo IS NULL OR activeTo >= V)`), écrit les `SubobjectVersion` absentes, écrit le snapshot structurel immuable `PotVersion(P,V)` (métadonnées du Pot, références `(shareholderId, version)` et `(expenseId, version)`, indicateur `deleted`, et liste interne `authorizedUserIds`), puis met à jour `CurrentPot(P)` par garde monotone `WHERE version < V`.

3. **Calcul et matérialisation des balances (`PotBalance`)** :
   * Une seconde `Consumption` sur `POT_EVENT` (`PreparePotProjectionTasks`) reconstruit `Pot@V` depuis WRITE, calcule les balances rationnelles exactes (somme nulle sur les `Shareholders` actifs à `V`) ainsi que les `visibleUserIds`, et insère dans une même transaction READ deux tâches burnables `PENDING` : `PotBalanceTask(P,V,balances)` et `PotVisibilityTask(P,V,visibleUserIds)` avec identifiants déterministes `(preparationConsumptionId, potEventId, taskType)`, puis supprime son slot.
   * `Discovery` (en READ) détecte `PotBalanceTask` `PENDING` sans slot et crée un slot. `Engine` acquiert le slot, écrit `PotBalance(P,V)` (immuable, sans verrou inter-version), passe `PotBalanceTask` à `TERMINAL`, et supprime le slot.

4. **Calcul et matérialisation de la visibilité (`PotVisibilityState`, `CurrentUserPotList`)** :
   * `Discovery` (en READ) détecte `PotVisibilityTask` `PENDING` sans slot et crée un slot partitionné par `potId`.
   * `Engine` acquiert le slot et exécute une transaction READ : verrouille ou initialise `PotVisibilityState(P)`. Si `V <= materializedVersion`, la tâche est un no-op obsolète (`TERMINAL` + suppression du slot). Si `V > materializedVersion`, calcule le diff entre `PotVisibilityState(P).visibleUserIds` et la nouvelle liste (`creatorId` $\cup$ `Shareholders` liés, ou $\emptyset$ si Pot supprimé), met à jour `CurrentUserPotList(U, P)` par garde monotone `WHERE lastPotVersion < V` (`visible = true/false`), met à jour `PotVisibilityState(P)` à `(V, visibleUserIds)`, passe `PotVisibilityTask` à `TERMINAL`, et supprime le slot.

5. **Enregistrement, liaison et déliaison d’identité (`Registration`, `Link`, `Unlink`)** :
   * La Supra admet une `IDENTITY_REQUEST` (`PENDING`) en WRITE (`202 Accepted`).
   * Après enrôlement par `Discovery` et acquisition du slot partitionné par `EUI cible` (l’EUI initiateur pour `Registration`/`Unlink`, l’EUI second `E2` pour `Link`), le worker vérifie l’état de `EUI cible` en WRITE :
     * `Registration(E1)` : si `E1` inconnu ou non lié (`activeTo != null` sur son dernier `Binding`), crée `PocomaUser`, crée `Binding(E1 -> U, bindingToken)` (`activeFrom = V_E1`), émet `IDENTITY_EVENT(E1, V_E1)` et `IDENTITY_REQUEST_COMPLETED_EVENT`.
     * `Link(E1 -> E2)` : vérifie `E1` actif (`bindingToken` valide, rattaché à `U`) et la preuve de consentement transactionnel signée par l’autorité de `E2`. Si `E2` est libre, crée `Binding(E2 -> U, nouveauBindingToken)` à `V_E2`, émet `IDENTITY_EVENT(E2, V_E2)` et `IDENTITY_REQUEST_COMPLETED_EVENT`.
     * `Unlink(E)` : ferme le `Binding` courant de `E` (`activeTo = V_E`), invalide le `bindingToken`, émet `IDENTITY_EVENT(E, V_E)` et `IDENTITY_REQUEST_COMPLETED_EVENT`.
   * En READ, `IDENTITY_EVENT` met à jour `CurrentBinding(EUI)` par garde monotone `version < V_EUI`, et `IDENTITY_REQUEST_COMPLETED_EVENT` matérialise `IdentityRequestResult(requestId, issue, targetEuiId, targetEuiVersion)`.

6. **Lecture courante et lecture historique d’un Pot** :
   * Le client authentifié par `EUI` présente son `bindingToken`. La Supra READ vérifie `CurrentBinding(EUI)` (`bindingToken` actif $\rightarrow$ résout `PocomaUserId`).
   * Pour une lecture courante (avec option `minVersion`), la Supra lit `CurrentPot(P) -> V_courant` (si `V_courant < minVersion`, retourne `503`), puis lit `PotVersion(P, V_courant)`. Pour une lecture historique explicite à `V`, elle lit directement `PotVersion(P, V)`.
   * Elle vérifie que `PocomaUserId` appartient à `PotVersion(P, V).authorizedUserIds` (sinon `404`), charge `SubobjectVersion` pour les `Shareholders` référencés à `V` et `PotBalance(P, V)` (si absent, `503`), et retourne l’en-tête du Pot, les `Shareholders`, les balances et le **manifeste complet des références d’Expenses** `(expenseId, version)` sans leurs détails, qui sont ensuite chargés par lots (`POST .../expenses:batchGet`).

7. **Révocation d’accès et masquage `404`** :
   * Si un `Shareholder` associé à `U` est supprimé ou détaché de `U` à `V+1`, `U` figure encore dans `PotVersion(P, V).authorizedUserIds` mais plus dans `PotVersion(P, V+1).authorizedUserIds`.
   * Or `V3-5E` (§14 et §17 `S-02` l.309) impose que la lecture historique à `V` exige l’appartenance à **la fois** à `PotVersion(P, V_courant)` et à `PotVersion(P, V)` : un participant retiré perd immédiatement tout accès courant et historique (`404`). De même, un `Unlink(E)` invalide immédiatement `CurrentBinding(E)`, coupant tout accès via `E`.

8. **Reprise après crash, retry, quarantaine et événements hors ordre** :
   * Crash avant commit WRITE ou READ : rollback atomique ; le slot existant conserve son bail qui expirera (`leaseExpiresAt < now`), permettant à un autre worker de reprendre le slot (`attemptCount + 1`).
   * Échec technique : libération/expiration du bail avec backoff. Au-delà de `maxAttempts`, le slot passe en `QUARANTINED` (isolé du flux normal des slots `READY`, ne bloquant que les travaux dépendant causalement de ce même Pot/EUI).
   * Hors ordre (`V+1` traité avant `V` en READ) : `PotVersion(P, V+1)` et `PotBalance(P, V+1)` s’insèrent sans conflit ; `CurrentPot(P)` et `PotVisibilityState(P)` avancent à `V+1`. Lorsque `V` arrive ensuite, `PotVersion(P, V)` et `PotBalance(P, V)` sont insérés pour les lectures historiques, tandis que `CurrentPot(P)` et `PotVisibilityState(P)` ignorent `V` grâce à la garde `V < version_courante`.

9. **Reconstruction intégrale de READ (`PRJ-03`)** :
   * En cas de perte ou corruption de la base READ, la base READ est vidée et les checkpoints `K` sont remis à `0`.
   * Les Consumptions non-burnables relisent les journaux durables WRITE (`POT_EVENT`, `IDENTITY_EVENT`, `COMMAND_COMPLETED_EVENT`, `IDENTITY_REQUEST_COMPLETED_EVENT`) depuis l’origine. Grâce aux reconstructions déterministes `Pot@V` depuis WRITE et aux gardes monotones sur `CurrentPot`, `CurrentBinding` et `PotVisibilityState`, l’état READ converge vers le même état final quel que soit l’ordre d’entrelacement des workers.

---

## III. Registre des constats

### `AUD-V3-001` — Impasse fonctionnelle et contradiction d’autorisation sur le créateur d’un Pot (`CreatePot` à `V1` illisible en READ et absence de règles d’autorisation en WRITE)

* **Identifiant** : `AUD-V3-001`
* **Sévérité** : **Bloquant**
* **Nature** : Contradiction documentaire / Trou fonctionnel et de sécurité (WRITE & READ)
* **Localisation** :
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) (§1 l.14, l.22 ; §5 l.67 ; §6 l.80-81)
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§4 l.43 ; §9 l.107, l.110 ; §10 l.120-122)
  * [SPEC-09_Performance.md](./SPEC-09_Performance.md) (§7 `C-03` l.67-71)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§20 l.683, l.697 ; §43 l.1455)
* **Constat** :
  1. En WRITE, `V3-1` (§1 l.22) stipule que *« `PotDetails.creatorId` peut différer de tout Shareholder »* et montre dans son scénario canonique (§6 l.80-81) qu’à `V1` (création du Pot), seuls `PotHeader h1` et `PotDetails d1` sont créés, sans aucun `Shareholder` (les `Shareholders` `S1, S2` sont ajoutés à `V2`).
  2. En READ, `V3-3` (§4 l.43) stipule que dans `CurrentUserPotList`, un Pot actif est visible pour le *« créateur immuable **ou** utilisateur associé à au moins un Shareholder »*.
  3. À l’inverse, pour la lecture du Pot lui-même (`PotVersion(P,V)`), `V3-3` (§9 l.107, l.110 et §10 l.122) restreint l’autorisation aux seuls *« `pocomaUserId` rattachés à un Shareholder de P à V »*, en affirmant explicitement (§9 l.110) : *« Une liste de navigation visible (incluant éventuellement un créateur non-Shareholder) n'accorde aucun droit »*, et `V3-5D` (§43 l.1455) exige même de certifier le refus de lecture (`404`) pour un *« créateur non-Shareholder »* !
  4. De surcroît, `V3-1` ne définit **aucune règle d’autorisation métier en WRITE** : il mentionne l’identité de l’auteur (§5 l.67 : *« Résolution à l’exécution depuis l’EUI authentifié et son `bindingToken` vérifié dans WRITE »*), mais ne spécifie jamais **qui** (créateur ? Shareholder actif ? n’importe quel `PocomaUser` ?) est autorisé à exécuter `ModifyPotDetails`, `DeletePot`, `CreateShareholder`, `ModifyShareholderDetails`, `ModifyShareholderWeight`, `DeleteShareholder`, `CreateExpense`, `ModifyExpense*` ou `DeleteExpense`.
* **Scénario ou contre-exemple** :
  1. L’utilisateur `U_Creator` crée un Pot `P` (`CreatePot`). La commande réussit et produit `V1` (aucun `Shareholder` à `V1`, ou `Shareholders` non liés à `U_Creator`).
  2. `CurrentUserPotList` affiche `P` comme visible à `U_Creator`.
  3. `U_Creator` clique sur `P` (ou attend le jalon `C-03` `GET /pots/{P}?minVersion=1`). Comme `U_Creator` n’est rattaché à aucun `Shareholder` de `P` à `V1`, `PotVersion(P, 1).authorizedUserIds` est vide (`[]`). La Supra READ renvoie **`404 Not Found`** à `U_Creator` sur le Pot qu’il vient de créer !
  4. Pire : comment `U_Creator` (ou quiconque) peut-il ajouter le premier `Shareholder` à `V2` ? Si WRITE exige d’être `Shareholder` du Pot pour le modifier, personne ne peut jamais modifier un Pot créé sans `Shareholder` lié. Si WRITE autorise `creatorId` à modifier le Pot mais que READ lui renvoie `404`, le créateur peut modifier à l’aveugle un Pot qu’il n’a pas le droit de lire (et dont il ne peut même pas consulter l’état pour obtenir `observedVersion`, hors `CommandResult`) !
* **Conséquence** :
  * Incohérence fonctionnelle majeure dès `V1` (`CreatePot`).
  * Impossibilité mathématique de satisfaire l’oracle de performance `C-03` (`V3-5C` §7 l.67 : *« première lecture API READ réussie du Pot, authentifiée et autorisée, avec `minVersion=V` »*) pour toute commande `CreatePot` (ou tant qu’aucun `Shareholder` n’est lié à l’auteur).
  * Sous-spécification critique du domaine WRITE : sans matrice d’autorisation WRITE dans `V3-1`, deux développeurs implémenteront des règles d’accès WRITE incompatibles.
* **Recommandation** :
  1. **Expliciter dans `V3-1` la matrice d’autorisation WRITE** par commande (ex. : qui a le droit de modifier le Pot, de gérer les Shareholders, de rattacher/détacher un `userId`, de gérer les Expenses).
  2. **Aligner la visibilité (`CurrentUserPotList`), l’autorisation READ (`PotVersion.authorizedUserIds`) et l’autorisation WRITE** selon l’une des deux options cohérentes (voir Question d’arbitrage #1 en section VII) :
     * **Option A (Recommandée — Créateur autorisé de plein droit)** : Tout Pot non supprimé est lisible et modifiable par son `creatorId` immuable **ainsi que** par tout `PocomaUserId` rattaché à un `Shareholder` actif à `V`. Dès lors, `PotVersion(P,V).authorizedUserIds` contient `{creatorId} ∪ {ShareholderDetails.userId actifs à V}` (et $\emptyset$ si `PotHeader.deleted = true`).
     * **Option B (Créateur obligatoirement Shareholder dès `V1`)** : Imposer à la création du Pot (`CreatePot` en `V1`) la création atomique d’un premier `Shareholder` initial rattaché à `creatorId` (ou supprimer la visibilité du créateur non-Shareholder dans `CurrentUserPotList` et interdire un Pot sans Shareholder rattaché au créateur à `V1`).
* **Impact** : `V3-1`, `V3-3` (§4, §9, §10), `V3-5D` (§20, §43), `V3-5E` (§14).
* **Statut** : Contradiction active dans la cible V3 finale.

---

### `AUD-V3-002` — Règle de consentement d’auto-rattachement d’un `Shareholder` cachée dans `V3-3` et incompatible avec l’accès READ/WRITE d’un nouvel utilisateur

* **Identifiant** : `AUD-V3-002`
* **Sévérité** : **Majeur**
* **Nature** : Défaut fonctionnel / Règle métier WRITE placée dans READ et créant un blocage d’usage
* **Localisation** :
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§4 l.47)
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) (§1 l.16 ; §5 l.58-59)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§25 `DOM-01`, §26 `DOM-02`)
* **Constat** :
  Dans [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§4 l.47), au milieu de la description de `CurrentUserPotList`, figure une règle critique du domaine WRITE qui est totalement absente de `V3-1` et de `V3-5D` :
  > *« Consentement : seul l'utilisateur lui-même peut rattacher son `PocomaUser` à un Shareholder ; lui-même ou une autorité Pot adéquate peut le détacher. Le domaine Pot porte `ShareholderDetails.userId` mais l'API de commande ne permet pas de fournir arbitrairement un autre `PocomaUserId`. Identité de l'auteur résolue à l'exécution depuis EUI + bindingToken vérifié. »*
* **Scénario ou contre-exemple** :
  Supposons qu’Alice crée un Pot `P` (elle se rattache à `S1`) et crée un second participant `S2` ("Bob", `userId = null`) à `V2`. Comment Bob (`U_Bob`) peut-il rattacher son `PocomaUser` à `S2` ?
  1. Selon `V3-3` §4 l.47, **seul Bob lui-même** peut exécuter la commande rattachant `U_Bob` à `S2` (Alice ne peut pas fournir `U_Bob`).
  2. Mais tant que `S2.userId` vaut `null`, Bob n’est rattaché à **aucun** `Shareholder` de `P` !
  3. Par conséquent :
     * En READ (`V3-3` §9), Bob a interdiction absolue de lire le Pot `P` (`404 Not Found`) : il ne peut connaître ni l’état du Pot, ni `shareholderId = S2`, ni `observedVersion = 2` !
     * En WRITE, si seuls les participants déjà autorisés du Pot peuvent soumettre une commande sur `P`, la commande de Bob est rejetée ; et si n’importe quel `PocomaUser` authentifié qui devine `(potId, shareholderId, observedVersion)` peut s’auto-rattacher à n’importe quel `Shareholder` libre d’un Pot sans invitation ni droit d’accès, n’importe qui peut s’introduire dans un Pot tiers !
* **Conséquence** :
  Soit le rattachement d’un second utilisateur à un `Shareholder` est fonctionnellement impossible (deadlock entre « il faut être déjà rattaché à un Shareholder pour accéder au Pot » et « seul l’utilisateur lui-même peut se rattacher »), soit il ouvre une faille d’intrusion si toute commande d’auto-rattachement est acceptée sans contrôle d’accès au Pot. En outre, cette règle n’est couverte par aucun scénario de test dans `V3-5D`.
* **Recommandation** :
  1. Rapatrier dans `V3-1` toutes les règles de rattachement/détachement de `ShareholderDetails.userId` et les ajouter au plan de certification `V3-5D` (`DOM-02` / `SEC-01`).
  2. Trancher le mécanisme fonctionnel d’entrée d’un participant dans un Pot (voir Question d’arbitrage #2 en section VII) : soit le rattachement initial se fait via l’identité courante à la création du Pot et via fusion d’EUI (`Link`), soit via un mécanisme explicite d’invitation/claim autorisé, soit en clarifiant qui peut exécuter `ClaimShareholder` / `ModifyShareholderDetails`.
* **Impact** : `V3-1`, `V3-3` (§4), `V3-5D` (§26), `V3-5E`.
* **Statut** : Défaut actif dans la cible V3 finale.

---

### `AUD-V3-003` — Insuffisance des préconditions `observedVersion` sur les références croisées (`Expense` → `ShareholderDetails`) et sur la suppression d’un `Shareholder`

* **Identifiant** : `AUD-V3-003`
* **Sévérité** : **Majeur**
* **Nature** : Défaut de concurrence / Invariant métier incomplet
* **Localisation** :
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) (§3 l.36-39 ; §4 l.51 ; §5 l.55-71)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§26 l.944-970 : table et cas `D02-B` à `D02-F`)
* **Constat** :
  La table des préconditions de concurrence de `V3-1` (§5 l.55-66) et `V3-5D` (§26 l.944-955) présente trois lacunes :
  1. **Changement concurrent de `ShareholderDetails` lors d’une création ou modification financière d’`Expense`** :
     * Pour `Créer Expense`, la table indique : *« Pas de cible antérieure ; contrôler état du Pot et validité des Shareholders »* (c’est-à-dire uniquement `PotHeader.deleted = false` et `ShareholderHeader.deleted = false`).
     * Pour `Modifier ExpenseFinancials`, la table indique : *« `ExpenseFinancials` inchangé depuis `observedVersion` ; Pot et Shareholders référencés actifs »*.
     * Aucune de ces deux opérations ne vérifie que les `ShareholderDetails` (ni même les `ShareholderHeader`) des `Shareholders` référencés (payeur et participants) avaient `activeFrom <= observedVersion`.
  2. **Omission de la vérification `Header.activeFrom <= observedVersion` sur un sous-objet référencé** :
     * Pour `Créer Expense` avec `observedVersion = 5` référençant un `Shareholder` créé à `V6`, l’absence de contrôle `ShareholderHeader.activeFrom <= observedVersion` accepte une dépense référençant un participant qui n’existait même pas à la version observée `V5`.
  3. **Suppression d’un `Shareholder` référencé par une `Expense` active** :
     * Pour `Supprimer Shareholder`, `V3-1` (§5 l.60) vérifie uniquement que `ShareholderHeader`, `ShareholderDetails` et `ShareholderWeight` n’ont pas changé depuis `observedVersion`, sans interdire la suppression d’un `Shareholder` référencé dans une `Expense` active !
* **Scénario ou contre-exemple** :
  * **Contre-exemple A (Substitution de participant sous `CreateExpense` / `ModifyExpenseFinancials`)** :
    À `V5`, `S1` a pour `ShareholderDetails` `(name = "Alice", userId = U_Alice, activeFrom = 2)`. Bob consulte le Pot à `V5` et soumet `CreateExpense(observedVersion = 5, payer = S1, amount = 1000€)`. Concurremment, à `V6`, `S1` est modifié en `(name = "Charlie", userId = U_Charlie, activeFrom = 6)`. La commande de Bob s’exécute après `V6` : `P` est actif, `S1` est actif (`ShareholderHeader.activeFrom = 2, deleted = false`). Comme `CreateExpense` ne vérifie pas `ShareholderDetails.activeFrom <= observedVersion`, la dépense de 1000 € est créée à `V7` et imputée à Charlie au lieu d’Alice !
  * **Contre-exemple B (Rupture de l’invariant de somme nulle `DOM-01` / `PRJ-02` après `DeleteShareholder`)** :
    À `V3`, une dépense de 100 € payée par `S1` est répartie entre `S1` (poids 1) et `S2` (poids 1). Les balances sont `S1: +50, S2: -50`. À `V4`, une commande `DeleteShareholder(S2)` est soumise. Si `S2` est supprimé alors que l’`Expense` reste active à `V4`, `V3-1` (§4 l.51) exige que : *« Somme des balances des Shareholders **actifs à V** égale à zéro »* et que les balances soient calculées à partir des `Expenses` actives à `V`. Comme seul `S1` est actif à `V4` et que sa balance vaut `+50`, la somme des balances des `Shareholders` actifs à `V4` vaut `+50 ≠ 0`, violant `DOM-01` et `PRJ-02` !
* **Conséquence** :
  1. Risque d’imputation financière erronée lors de modifications concurrentes de `ShareholderDetails`.
  2. Violation directe de l’invariant de somme nulle (`DOM-01` / `PRJ-02`) si un `Shareholder` impliqué dans une `Expense` active peut être supprimé.
* **Recommandation** :
  1. **Exiger pour `CreateExpense` et `ModifyExpenseFinancials`** que pour tout `Shareholder` référencé (payeur et participants aux parts), `ShareholderHeader.deleted = false`, `ShareholderHeader.activeFrom <= observedVersion` **et** `ShareholderDetails.activeFrom <= observedVersion`.
  2. **Interdire `DeleteShareholder` dans `V3-1` (§3–§5)** tant que ce `Shareholder` est référencé (comme payeur ou participant) par au moins une `Expense` active (`ExpenseHeader.deleted = false`).
* **Impact** : `V3-1` (§3, §4, §5), `V3-5D` (§25 `DOM-01`, §26 `DOM-02`).
* **Statut** : Défaut actif dans la cible V3 finale.

---

### `AUD-V3-004` — Incohérence entre le verrouillage pessimiste des compteurs de séquence (`EVT-01`) et l’indétermination de la « borne sûre `H` » dans `Discovery` (`CON-01`)

* **Identifiant** : `AUD-V3-004`
* **Sévérité** : **Majeur**
* **Nature** : Ambiguïté algorithmique / Preuve de progression et de non-perte incomplète
* **Localisation** :
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) (§5 l.54-60)
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) (§2 l.19, l.23)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§6 `EVT-01` l.94-115 ; §9 `CON-01` l.202, l.235)
* **Constat** :
  `V3-2` (§5 l.56-59) et `V3-5D` (§6 `E01-B` l.99) prescrivent que `sequencePosition` est allouée via des **compteurs transactionnels dont le verrou est retenu jusqu’à la résolution physique (commit/rollback) de la transaction** : *« Tant que T1 n'est pas résolue, T2 attend et n'obtient pas `N+1` avant la résolution de T1 »*.
  Pourtant, `V3-4` (§2 l.19) et `V3-5D` (§9 l.202, l.235) parlent d’une « borne sûre `H` » dont *« le contrat ne prescrit pas encore la construction SQL »* pour éviter qu’une position plus haute `N+1` soit visible avant `N`.
  Or :
  1. Si le compteur de famille est effectivement verrouillé en exclusivité jusqu’au commit de `T1`, alors `T2` ne peut même pas allouer `N+1` avant que `T1` ait committé `N`. En PostgreSQL, il ne peut donc **jamais** exister de trou non committé `N` derrière un `N+1` committé, **à la seule condition** que `Discovery` lise la fenêtre d’événements dans un **unique snapshot SQL** (une seule requête `SELECT` sur la table d’événements, ou une transaction `REPEATABLE READ` si `H` et les événements sont lus par deux requêtes distinctes).
  2. En revanche, si `Discovery` lit d’abord les événements puis lit `H` dans une seconde requête `READ COMMITTED` après que `T2` a committé `11`, il avancera son checkpoint `K` à `11` sans avoir lu l’événement `11` !
* **Scénario ou contre-exemple** :
  Un développeur lit `V3-5D` §9 l.235 (*« le contrat ne prescrit pas encore la construction SQL de la borne sûre H »*) et implémente `Discovery` en deux requêtes SQL en mode `READ COMMITTED` :
  1. Requête 1 : lit les événements de la partition pour `sequence_position > K LIMIT 100` (voit jusqu’à la position `10`).
  2. Entre la Requête 1 et la Requête 2, la transaction WRITE `T2` (pour la même partition) committe la position `11`.
  3. Requête 2 : lit la borne courante du compteur `H = 11` et avance le checkpoint `K` à `11`.
  4. **Résultat** : L’événement `11` est définitivement sauté (`K = 11` sans slot créé pour `11`), violant `CON-01`.
* **Conséquence** :
  Risque critique de perte silencieuse d’événements si la relation exacte entre le verrouillage du compteur `EVT-01` et le snapshot de lecture SQL de `Discovery` n’est pas explicitée normativement.
* **Recommandation** :
  Lever toute ambiguïté dans `V3-2` §5 et `V3-4` §2 en prescrivant la règle simple suivante :
  * Grâce au verrou exclusif du compteur de famille maintenu jusqu’au commit (`EVT-01`), la séquence visible dans la table d’événements est toujours un préfixe sans trou au sein d’un même snapshot PostgreSQL.
  * `Discovery` doit déterminer sa fenêtre `(K, H]` **en une seule requête SQL atomique** (par exemple en sélectionnant le lot borné `WHERE sequence_position > :K ORDER BY sequence_position ASC LIMIT :batchMax`, en prenant comme nouveau checkpoint `K'` la `sequence_position` maximale effectivement balayée par ce snapshot unique, puis en filtrant par `partitionId`).
* **Impact** : `V3-2` (§5), `V3-4` (§2), `V3-5D` (§6, §9).
* **Statut** : Sous-spécification active dans la cible V3 finale.

---

### `AUD-V3-005` — Complexité et redondance majeures de l’étage intermédiaire `PreparePotProjectionTasks` + tâches burnables `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK`

* **Identifiant** : `AUD-V3-005`
* **Sévérité** : **Majeur**
* **Nature** : Complexité évitable / Redondance d’états durables et pénalité de latence/stockage
* **Localisation** :
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) (§Amendement V3-4 l.92-94)
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§7 l.65-82 ; §9 l.109-112)
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) (§2 l.21 ; §6 R1 l.61, R8 l.75 ; §8 l.94)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§16 l.517)
* **Constat** :
  Au nom de la règle R8 de `V3-4` (*« chaque Consumption produit un seul effet conceptuel »*), le traitement READ d’un `POT_EVENT(P,V)` a été découpé en **4 Consumptions, 3 familles de consommables et 2 étages de Discovery** :
  1. `MaterializeCurrentPot` consomme `POT_EVENT(P,V)`, reconstruit `Pot@V` depuis WRITE, et écrit `SubobjectVersion`, `PotVersion(P,V)` et `CurrentPot(P)`.
  2. `PreparePotProjectionTasks` consomme le même `POT_EVENT(P,V)`, **reconstruit une seconde fois `Pot@V` depuis WRITE** (`V3-3` §7 l.69), **calcule déjà les balances rationnelles `balances` et la liste `visibleUserIds[]`**, et insère en READ deux lignes durables `PENDING` : `PotBalanceTask(P,V,balances)` et `PotVisibilityTask(P,V,visibleUserIds[])`.
  3. `BurnableDiscovery` scanne `PotBalanceTask` (`PENDING` sans slot) et crée un slot. Puis un worker `MaterializePotBalance` acquiert ce slot, lit `PotBalanceTask(P,V,balances)`, **recopie `balances` tel quel** dans `PotBalance(P,V)` (`V3-5D` §16 l.517 : *« La tâche `POT_BALANCE_TASK` contient le résultat préparé pour V ; son traitement matérialise ce résultat »*), passe `PotBalanceTask` à `TERMINAL` et supprime le slot !
  4. En parallèle, `BurnableDiscovery` scanne `PotVisibilityTask` (`PENDING` sans slot), crée un slot, et un worker `MaterializePotVisibility` met à jour `PotVisibilityState(P)` et `CurrentUserPotList`, puis passe `PotVisibilityTask` à `TERMINAL`.
* **Scénario ou contre-exemple** :
  Observons ce que fait concrètement l’étage `PotBalanceTask` :
  * Le calcul métier lourd (reconstruction de `Pot@V` + calcul rationnel des balances sur 2 000 dépenses) est **déjà terminé** dans `PreparePotProjectionTasks` !
  * Au lieu d’écrire directement la ligne immuable `PotBalance(P,V,balances)` (qui est sans conflit et sans verrou inter-version, exactement comme `PotVersion(P,V)` !), `PreparePotProjectionTasks` écrit `PotBalanceTask(P,V,balances, status='PENDING')`.
  * Ensuite, il faut attendre un cycle de polling `BurnableDiscovery` (`SELECT ... WHERE status='PENDING' AND NOT EXISTS slot`), insérer un slot, attendre un cycle de claim `Engine`, copier `balances` de la table `pot_balance_tasks` vers la table `pot_balances`, mettre `pot_balance_tasks.status = 'TERMINAL'`, et supprimer le slot !
  * Résultat : chaque balance de chaque version `V` est stockée **deux fois durablement** dans la base READ (une fois dans `PotBalance(P,V)` et une fois pour toujours dans `PotBalanceTask(P,V,balances, TERMINAL)`), et le chemin critique de lecture d’un Pot (`C-03` / `PERF-01` ≤ 500 ms) subit inutilement un second saut complet `Discovery -> Slot -> Claim -> Copy` !
* **Conséquence** :
  * Doublement inutile du stockage des balances et des listes de visibilité en READ (les tâches `TERMINAL` ne sont jamais purgées).
  * Ajout d’un saut asynchrone complet (`BurnableDiscovery` + `Engine`) sur le chemin critique de `PotBalance(P,V)`, qui conditionne directement le jalon E2E `C-03` (p95 ≤ 500 ms).
  * Inflation conceptuelle : création de 2 familles de consommables artificielles (`POT_BALANCE_TASK`, `POT_VISIBILITY_TASK`), d’identifiants composites déterministes (`R1`), et de dizaines de cas de tests dédiés (`EVT-03`, `PRJ-04`).
* **Recommandation** :
  **Supprimer `PreparePotProjectionTasks`, `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK`** au profit de Consumptions directes de `POT_EVENT` (voir l’analyse détaillée et les deux variantes en Section IV.1) :
  * Soit **3 Consumptions non-burnables directes sur `POT_EVENT`** : `MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility` (chacune ne chargeant depuis WRITE que les sous-objets dont elle a besoin : `Visibility` n’a même pas besoin de charger les `Expenses` !).
  * Soit, encore plus simple et rapide : puisque `PotVersion(P,V)` et `PotBalance(P,V)` sont tous deux des **snapshots immuables par `(P,V)` nécessaires simultanément pour toute lecture de Pot (`C-03`)**, les matérialiser dès la lecture de `POT_EVENT`, et confier `PotVisibilityState` + `CurrentUserPotList` à une seconde Consumption non-burnable directe de `POT_EVENT` (`MaterializePotVisibility`).
* **Impact** : `V3-2`, `V3-3`, `V3-4`, `V3-5A`, `V3-5B`, `V3-5C`, `V3-5D`.
* **Statut** : Sur-complexité active dans la cible V3 finale.

---

### `AUD-V3-006` — Complexité et coût SQL évitables du `BurnableDiscovery` asynchrone pour `COMMAND` et `IDENTITY_REQUEST`

* **Identifiant** : `AUD-V3-006`
* **Sévérité** : **Majeur**
* **Nature** : Complexité évitable / Latence et coût SQL PostgreSQL inutiles
* **Localisation** :
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) (§2 l.19-21 ; §3 l.31-37)
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) (§2 l.21 ; §3 l.32-39 ; §4 l.42)
  * [SPEC-08_Observability.md](./SPEC-08_Observability.md) (§2 l.29-31 : jalons `T1` et `T2`)
* **Constat** :
  Pour les demandes burnables (`COMMAND`, `IDENTITY_REQUEST`), la demande durable (`status = 'PENDING'`) et son `Slot` résident **dans la même base de données WRITE**, et chaque demande a **une seule et unique `Consumption` cible connue dès l’admission**.
  Or, `V3-4` (§2 l.21) impose qu’après l’insertion transactionnelle de la demande `PENDING` à l’admission (`T1`), un composant `Discovery` séparé scrute périodiquement la table des demandes (`WHERE status = 'PENDING' AND NOT EXISTS (SELECT 1 FROM slots ...)` et filtré par `partitionId`) pour créer le slot à `T2`, avant qu’un worker `Engine` puisse enfin l’acquérir à `T3`.
* **Scénario ou contre-exemple** :
  Sous charge ou après accumulation de demandes en quarantaine (`QUARANTINED`), la requête de `BurnableDiscovery` doit évaluer à chaque cycle et pour chaque partition un anti-join (`PENDING` sans slot présent) entre la table des demandes et la table des slots. De plus, chaque commande subit le délai d’attente de polling `T1 -> T2` de `Discovery` en WRITE avant même d’être éligible à l’exécution par un worker WRITE.
  Pourtant, le plan de conception initial de Discovery (`docs/plans/2026-10-22-a2-minimal-consumption-discovery.md` §2 l.60) avait justement relevé : *« Sans journal E, une demande/task pour une Consumption connue peut être enrôlée à sa création, dans la même transaction. »*
* **Conséquence** :
  * Latence additionnelle `T1 -> T2` sur chaque écriture (`Command` et `IdentityRequest`).
  * Requêtes SQL de polling + anti-join + écritures de slots supplémentaires sur la base WRITE (qui est la ressource la plus critique à protéger).
* **Recommandation** :
  Créer le `Slot(consumptionId, requestId, partitionId)` **atomiquement dans la transaction d’admission** de la `COMMAND` ou de l’`IDENTITY_REQUEST` (ou fusionner le slot avec l’état `PENDING` de la demande mono-consommateur). Ainsi, `Discovery` n’a plus qu’un seul rôle uniforme et pur dans tout le système : **faire progresser les checkpoints `K` sur les séquences d’événements non-burnables** (voir Section IV.2).
* **Impact** : `V3-2` (§3), `V3-4` (§2), `V3-5B` (§2), `V3-5D` (§9).
* **Statut** : Complexité évitable active dans la cible V3 finale.

---

### `AUD-V3-007` — Blocage potentiel de progression dans `Discovery` non-burnable lorsque le plafond souple de slots (`softCap`) inclut les slots `QUARANTINED`

* **Identifiant** : `AUD-V3-007`
* **Sévérité** : **Majeur**
* **Nature** : Cas limite algorithmique / Risque de famine ou d’arrêt de progression par partition
* **Localisation** :
  * [SPEC-05_Consumption_Discovery.md](./SPEC-05_Consumption_Discovery.md) (§2 l.23)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§9 l.204, `C01-G` l.222 ; §12 l.327, `C04-F` l.342)
* **Constat** :
  `V3-4` (§2 l.23) définit un *« plafond souple de slots présents par `(consumptionId, partitionId)`, mesuré par simple `COUNT` »*.
  En parallèle, `V3-5D` (§12 l.327) stipule que les slots `QUARANTINED` **restent présents dans la table des slots** (pour conserver l’historique d’échec et bloquer uniquement les travaux dépendant causalement de ce même Pot/EUI, sans bloquer les autres Pots/EUI partageant la même partition `hash(potId)`).
  Or, si le plafond de slots est mesuré par un simple `COUNT(*)` de **tous** les slots présents pour `(consumptionId, partitionId)` **en incluant les slots `QUARANTINED`**, alors une accumulation de slots `QUARANTINED` sur certains Pots d’une partition saturera le plafond `COUNT(*)` de la partition et empêchera `Discovery` d’enrôler tout nouvel événement pour **tous les autres Pots sains** de cette même partition !
* **Scénario ou contre-exemple** :
  Soit `softCap = 50` pour `(MaterializeCurrentPot, partition 3)`.
  À la suite d’un bug ou d’une corruption sur un Pot `P_bad` (ou sur 50 événements successifs de `P_bad` dans la partition 3), 50 slots de `P_bad` passent en état `QUARANTINED`.
  Selon `CON-04` (`V3-5D` §12 `C04-F` l.342), les autres Pots sains `P_good` de la partition 3 doivent pouvoir continuer à être découverts et traités.
  Mais comme `Discovery` mesure le plafond par `COUNT(*)` des slots présents dans `(MaterializeCurrentPot, partition 3)` et trouve `50 >= softCap`, `Discovery` refuse d’enrôler les nouveaux événements de la partition 3 : `K(MaterializeCurrentPot, 3)` reste figé pour toujours, paralysant l’intégralité des Pots sains de la partition 3 !
* **Conséquence** :
  Contradiction directe entre `V3-4` §2 l.23 (simple `COUNT` des slots présents) et l’exigence d’isolation des quarantaines `CON-04` (`C04-F` : les slots `QUARANTINED` ne doivent pas bloquer les travaux indépendants de la même partition).
* **Recommandation** :
  Spécifier explicitement dans `V3-4` (§2 l.23) et `V3-5D` (§9 et §12) que le plafond souple de `Discovery` par `(consumptionId, partitionId)` ne compte que les slots **actifs non mis en quarantaine** (`WHERE status IN ('READY', 'CLAIMED')` ou `status != 'QUARANTINED'`), jamais les slots `QUARANTINED`.
* **Impact** : `V3-4` (§2), `V3-5D` (§9 `CON-01`, §12 `CON-04`).
* **Statut** : Défaut algorithmique actif dans la cible V3 finale.

---

### `AUD-V3-008` — Infaisabilité standard de l’attestation externe IdP pour `Link` (`C-01`) et contradiction avec l’absence d’index inverse dans `CurrentBinding`

* **Identifiant** : `AUD-V3-008`
* **Sévérité** : **Majeur**
* **Nature** : Faisabilité technique / Contradiction entre documents (`V3-3` vs `V3-5D`)
* **Localisation** :
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) (§4 l.38-45 ; §Amendement l.85)
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§1 l.14 ; §5 l.50)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§§32-37 l.1200-1329)
  * [V3_Canon_and_Roadmap.md](./V3_Canon_and_Roadmap.md) (§12 Risque `R-01` l.299)
* **Constat** :
  Les amendements §§36–37 de `V3-5D` (`C-01` final) imposent que pour `Link(E1 -> E2)` :
  1. Pocoma lise en READ le **nombre de Pots accessibles** par `PocomaUser` (`CurrentUserPotList`) et le **nombre d’EUI actuellement liés** à ce `PocomaUser` (`CurrentBinding`), ainsi que l’horodatage de cette consultation.
  2. Ces compteurs soient transmis à l’autorité d’identité externe (IdP de `E2`), qui doit **elle-même afficher l’écran de confirmation** mentionnant Pocoma, `E1`, et ces compteurs (sans aucune page de confirmation Pocoma : §32 l.1210), puis émettre une attestation cryptographique signée couvrant ces champs.
  Or ce mécanisme souffre de deux problèmes :
  * **Contradiction interne sur `CurrentBinding`** : Pour compter les EUI actuellement liés à `pocomaUserId` en READ (`V3-5D` §33 l.1225, §36 l.1289), il faut interroger `CurrentBinding` par `pocomaUserId`. Or `V3-3` (§1 l.14) affirme : *« Pas d’annuaire inverse Identity »* et `V3-3` (§5 l.50) définit `CurrentBinding` uniquement par clé `EUI (issuer, subject)`. Sans index sur `(pocomaUserId, active)` dans `CurrentBinding`, ce comptage impose un scan complet (`Seq Scan`) de tous les utilisateurs de Pocoma à chaque initiation de `Link` !
  * **Faisabilité Keycloak / OIDC standard** : En Configuration B (`V3-5C` §5 l.41 : *« OIDC réel conteneurisé type Keycloak »*), Keycloak standard ne supporte pas nativement le rendu dynamique d’attributs métier arbitraires issus de `authorization_details` (RFC 9396 RAR) sur un écran de consentement transactionnel ni l’émission d’un jeton d’attestation de consentement dédié sans développement et déploiement d’un SPI Java personnalisé dans le conteneur Keycloak.
* **Scénario ou contre-exemple** :
  Lors de l’implémentation de `V3-6` / `V3-7` avec Keycloak conteneurisé (Configuration B), l’équipe découvre que Keycloak ignore les champs métier `potCount`, `linkedEuiCount` et `readTimestamp` dans son écran de consentement standard, ou qu’un fournisseur OIDC tiers réel (Google, Apple, Auth0 standard) refuse d’afficher sur son propre écran de login le nombre de Pots Pocoma d’un utilisateur.
* **Conséquence** :
  1. En production réelle avec des fournisseurs d’identité externes standards (Google, Microsoft, etc.), aucun IdP tiers n’affichera jamais une phrase métier propriétaire de Pocoma (*« Ce compte Pocoma accède à 4 Pots et possède 2 EUI liés »*) sur sa propre page de consentement OIDC.
  2. En READ, absence d’index secondaire assumé sur `CurrentBinding(pocomaUserId)`.
* **Recommandation** :
  1. **Autoriser explicitement un index secondaire sur `CurrentBinding(pocomaUserId)`** (pour les `Binding` actifs) dans `V3-3` §5 si le comptage des EUI liés est maintenu.
  2. **Réaliser dès la préparation de `V3-6` un spike sur le contrat d’attestation `Link` (`R-01`)** et distinguer clairement :
     * Ce que prouve un IdP OIDC standard (une **ré-authentification fraîche de `E2`** liée par `nonce` / `state` / PKCE à une intention de liaison initiée et affichée côté Pocoma, ou un claim `acr`/`auth_time`),
     * D’un profil RAR/consentement enrichi supporté uniquement par un IdP sous contrôle (ou SPI Keycloak en Config B). Ne pas verrouiller le domaine WRITE sur une exigence que les IdP OIDC standards du marché ne peuvent pas remplir sans page de confirmation côté application.
* **Impact** : `V3-1 Identity` (§4), `V3-3` (§1, §5), `V3-5D` (§§32–37), `V3-5E` (§20).
* **Statut** : Contradiction documentaire (`CurrentBinding`) et risque majeur de faisabilité IdP actifs dans la cible V3 finale.

---

### `AUD-V3-009` — Impossibilité ou sous-spécification de la récupération du nouveau `bindingToken` via `CurrentBinding` après `Registration` et surtout après `Link`

* **Identifiant** : `AUD-V3-009`
* **Sévérité** : **Majeur**
* **Nature** : Trou fonctionnel et de sécurité sur le contrat de lecture de `CurrentBinding`
* **Localisation** :
  * [SPEC-02_Identity_Domain.md](./SPEC-02_Identity_Domain.md) (§2 l.16 ; §5 l.51 ; §Amendement l.90)
  * [SPEC-03_Events_Requests_Consumables.md](./SPEC-03_Events_Requests_Consumables.md) (§4 l.43)
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§2 l.23, l.25 ; §5 l.50-53 ; §6 l.59)
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) (§17 l.302)
* **Constat** :
  1. Le nouveau `bindingToken` créé lors d’un `Registration` ou d’un `Link` ne figure ni dans `IDENTITY_EVENT` (`V3-2` §4 l.43 : *« pas de `bindingToken` dans l’événement partagé »*), ni dans `IdentityRequestResult` (`V3-3` §2 l.25 : ne contient que `(requestId, issue, targetEuiId, targetEuiVersion)`).
  2. Question 1 : **Comment `CurrentBinding` en READ connaît-il le `bindingToken` si `IDENTITY_EVENT` ne le contient pas ?**
     Dans `V3-2` §4 l.43, `IDENTITY_EVENT` ne porte que `(euiId, version)`. Si la projection `CurrentBinding` en READ doit vérifier le `bindingToken` à chaque requête READ (`V3-3` §5 l.51 : *« Vérification systématique que le `bindingToken` présenté correspond au `Binding` actif courant dans `CurrentBinding` »*) et le restituer au client après `Registration`/`Link` (`V3-3` §6 l.59), la Consumption `MaterializeCurrentBinding` doit obligatoirement lire l’état `Binding@V` dans la base WRITE (exactement comme `MaterializeCurrentPot` lit `Pot@V` dans WRITE). Or `V3-3` ne le précise jamais !
  3. Question 2 : **Qui a le droit d’appeler l’endpoint Supra `GET CurrentBinding` (qui délivre le `bindingToken` sans que le client possède encore ce `bindingToken`), et comment fonctionne la fin du flux `Link(E1 -> E2)` ?**
     Dans `V3-5E` (§17 l.302), la ligne *« Consultation `CurrentBinding` après Identity : réponse publique minimale, présence et exposition du `bindingToken` à qualifier »* a été marquée *« À préciser avant clôture »*, puis **oubliée** dans les §§18–21 qui ont clôturé `V3-5E` !
     Or, lors d’un `Link(E1 -> E2)` :
     * C’est `E1` qui soumet la requête `Link` et qui consulte `IdentityRequestResult`, lequel retourne `(targetEuiId = E2, targetEuiVersion = V_E2)`.
     * Mais `V3-1 Identity` (§Amendement l.90) et `V3-5A` (`BLOC-03` l.73) interdisent formellement à `E1` de lire le `bindingToken` de `E2` ! Seule une requête authentifiée avec un jeton OIDC de **`E2`** peut lire `CurrentBinding(E2)` et récupérer son nouveau `bindingToken`.
     * Pourtant, `V3-3` (§6 l.59) et `V3-5D` (`F01-E` §24.2 l.851) enchaînent : *« `IdentityRequest` réussie → lire `CurrentBinding(EUI cible)` avec `minVersion=targetVersion` »* sans préciser que pour `Link`, le client doit basculer sur l’authentification Bearer de **`E2`** (et non `E1`) pour appeler `CurrentBinding` !
* **Scénario ou contre-exemple** :
  1. Un client authentifié avec le JWT de `E1` soumet `POST /identity/link` (liant `E2` au compte de `E1`), attend `IdentityRequestResult` (`COMPLETED`, `targetEuiId = E2, targetVersion = 1`), puis appelle `GET /identity/current-binding?minVersion=1` avec son JWT `E1`.
  2. S’il demande `CurrentBinding` de `E2` en étant authentifié comme `E1`, la requête viole `BLOC-03` et doit être rejetée. S’il demande son propre `CurrentBinding(E1)`, la version de `E1` n’a pas bougé (`targetVersion` concerne `E2`) et il n’obtient pas le `bindingToken` de `E2` !
* **Conséquence** :
  Contrat Supra incomplet sur l’endpoint `CurrentBinding` (laissé « À préciser » dans `V3-5E` §17 l.302) et ambiguïté sur la reconstruction de `CurrentBinding` depuis WRITE ainsi que sur le changement de contexte d’authentification (`E1` $\rightarrow$ `E2`) après `Link`.
* **Recommandation** :
  1. Expliciter dans `V3-3` (§5) que la `Consumption` de `IDENTITY_EVENT(EUI, V)` charge l’état `Binding` correspondant depuis la base WRITE (puisque `IDENTITY_EVENT` ne transporte pas le `bindingToken`) et met à jour `CurrentBinding(EUI)` sous garde monotone `version < V`.
  2. Fermer le point ouvert de `V3-5E` (§17 l.302) en spécifiant le contrat de l’endpoint `GET /identity/me/binding?minVersion=V` :
     * Il s’authentifie **uniquement** par le jeton OIDC de l’EUI appelant (sans exiger de `bindingToken` en entrée, puisque son but est justement de récupérer le `bindingToken` actif de cet EUI).
     * Il ne retourne **que** le `CurrentBinding` de l’EUI authentifié dans le Bearer token (`EUI_appelant`), contenant `(version, active, bindingToken [si actif])` — et ne révèle jamais `pocomaUserId` ni aucun autre EUI.
     * Après `Link(E1 -> E2)`, `E1` constate la réussite via `IdentityRequestResult`, et c’est une session authentifiée sous **`E2`** qui appelle `GET /identity/me/binding?minVersion=targetEuiVersion` pour récupérer le `bindingToken` de `E2`.
* **Impact** : `V3-3` (§5, §6), `V3-5D` (§24.2 `F01-E`), `V3-5E` (§17, §18).
* **Statut** : Omission active dans la cible V3 finale (`V3-5E` §17 l.302 non résolu).

---

### `AUD-V3-010` — Absence d’autorisation propriétaire et d’arbitrage HTTP (`404` vs `503`) sur `CommandResult` et `IdentityRequestResult`

* **Identifiant** : `AUD-V3-010`
* **Sévérité** : **Majeur**
* **Nature** : Faille de sécurité potentielle (IDOR / fuite d’existence) et contrat HTTP incomplet
* **Localisation** :
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§2 l.24-25 ; §5 l.53 ; §6 l.56-62)
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) (§13 l.210 ; §14 l.234 ; §17 l.298)
* **Constat** :
  1. Dans `V3-3` (§2 l.24-25), `CommandResult` contient uniquement `(requestId, issue, potId, resultingPotVersion)` et `IdentityRequestResult` contient uniquement `(requestId, issue, targetEuiId, targetEuiVersion)`. Aucun des deux ne stocke l’identité du demandeur (`requesterPocomaUserId` ou `requesterEuiId`) !
  2. Dans `V3-5E` (§17 l.298), la table de revue signale explicitement que le contrat de lecture de `CommandResult` et `IdentityRequestResult` (*« contrat HTTP quand résultat pas encore matérialisé, sans fuite d'existence »*) est *« À préciser avant clôture »*, mais les §§18–21 ont clôturé `V3-5E` sans jamais le trancher !
* **Scénario ou contre-exemple** :
  * **Problème 1 (Autorisation / IDOR)** : Si `CommandResult` ne stocke pas `requesterPocomaUserId` (et si `IdentityRequestResult` ne stocke pas `requesterEuiId`), tout utilisateur authentifié qui connaît ou intercepte un `requestId` peut interroger `GET /commands/{requestId}/result` et découvrir `potId`, `resultingPotVersion` et le motif de rejet métier d’une commande appartenant à un tiers.
  * **Problème 2 (`404` vs `503` avant matérialisation)** : En READ, tant que `COMMAND_COMPLETED_EVENT` n’a pas été projeté dans `CommandResult`, la base READ ignore totalement si `requestId` correspond à une commande en cours en WRITE, à une commande appartenant à un autre utilisateur, ou à un UUID inexistant.
    * Si la Supra READ retourne `503 Service Unavailable` pour toute ligne absente de `CommandResult`, alors n’importe quel appel sur un UUID aléatoire retourne `503` (au lieu de `404`).
    * Si la Supra READ retourne `404 Not Found` tant que la ligne n’est pas dans `CommandResult` (ou si le demandeur n’en est pas le propriétaire), le contrat est parfaitement étanche (zéro fuite d’existence et pas d’accès WRITE depuis READ), mais le client doit savoir que `404` sur `CommandResult` après un `202 Accepted` signifie « pas encore matérialisé (ou inexistant) — continuer le polling ».
* **Conséquence** :
  Incompatibilité potentielle entre le client et la Supra sur le polling des résultats de commandes/requêtes d’identité, et risque de divulgation inter-utilisateurs des résultats si le propriétaire de la demande n’est pas projeté dans `CommandResult` / `IdentityRequestResult`.
* **Recommandation** :
  1. Inclure dans `COMMAND_COMPLETED_EVENT` / `CommandResult` le `requesterPocomaUserId` (ou `requesterEuiId` si rejeté pour `bindingToken` invalide) et dans `IDENTITY_REQUEST_COMPLETED_EVENT` / `IdentityRequestResult` le `requesterEuiId`.
  2. Spécifier dans `V3-5E` que la lecture de `CommandResult(requestId)` / `IdentityRequestResult(requestId)` exige que l’appelant authentifié soit le demandeur enregistré sur ce résultat ; si la ligne n’est pas encore présente en READ ou si le demandeur ne correspond pas, retourner uniformément `404 Not Found` (documenté comme normal pendant la fenêtre de polling suivant un `202 Accepted`, sans jamais interroger la base WRITE).
* **Impact** : `V3-2` (§4), `V3-3` (§2, §5), `V3-5E` (§13, §14, §17).
* **Statut** : Omission active dans la cible V3 finale (`V3-5E` §17 l.298 non résolu).

---

### `AUD-V3-011` — Ambiguïté sur la lecture d’un Pot supprimé (`DeletePot`) vis-à-vis de `PotVersion(P,V)` et de l’oracle `C-03`, et ordre de vérification `404` vs `503`

* **Identifiant** : `AUD-V3-011`
* **Sévérité** : **Majeur**
* **Nature** : Ambiguïté fonctionnelle et risque de fuite d’existence (`503` avant `404`)
* **Localisation** :
  * [SPEC-01_Pot_Domain.md](./SPEC-01_Pot_Domain.md) (§3 l.37, l.39 ; §6 l.88-90)
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§3 l.33 ; §4 l.43 ; §9 l.107-111)
  * [SPEC-09_Performance.md](./SPEC-09_Performance.md) (§7 `C-03` l.67-71)
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) (§13 l.208-211 ; §14 l.234-239 ; §17 `S-02` l.309)
* **Constat** :
  1. **Comportement de lecture d’un Pot supprimé (`PotHeader.deleted = true`)** :
     * `V3-3` (§3 l.33) indique que le Pot courant porte l’« indicateur supprimé », et `V3-3` (§4 l.43) indique : *« Pot supprimé : invisible pour tous »* dans `CurrentUserPotList`.
     * Mais que se passe-t-il lorsqu’un participant appelle `GET /pots/{potId}` ou `GET /pots/{potId}/versions/{V}` après que le Pot a été supprimé à `V9` ?
     * Selon `V3-5E` (§14 l.234), toute ressource non autorisée ou dont l’existence ne doit pas être révélée retourne `404`, et selon `V3-5E` (§17 `S-02` l.309), la lecture historique à `V` exige que l’utilisateur soit autorisé **dans la version courante ET dans la version `V`**.
     * Si un Pot supprimé à `V9` refuse toute lecture (`404`), pourquoi `CurrentPot` / `PotVersion` porte-t-il un « indicateur supprimé » public, et comment la commande `DeletePot` (qui est une commande appliquée produisant `resultingPotVersion = V9`) peut-elle satisfaire l’oracle universel `C-03` (`V3-5C` §7 l.67 : *« Pour **toute** commande appliquée produisant `resultingPotVersion=V`, le jalon E2E Pot accessible est la première lecture API READ réussie du Pot, authentifiée et autorisée, avec `minVersion=V`, retournant un Pot complet »*) ?
     * Inversement, si un Pot supprimé reste lisible à `V9` (avec `deleted = true`) par ses derniers participants alors qu’il a disparu de `CurrentUserPotList`, cela doit être explicitement spécifié.
  2. **Ordre d’évaluation `404` (autorisation) vs `503` (complétude / `minVersion`)** :
     * `V3-5E` (§13 l.210) prescrit `503 Service Unavailable` lorsque `CurrentPot(P).version < minVersion` ou lorsque `PotBalance(P,V)` n’est pas encore matérialisée.
     * Si la Supra READ vérifie `CurrentPot(P).version < minVersion` ou la présence de `PotBalance(P,V)` **avant** d’avoir vérifié que l’appelant appartient à `PotVersion(P, V_courant).authorizedUserIds`, alors un attaquant non autorisé interrogeant un `potId` existant avec `minVersion=999999` reçoit **`503 Service Unavailable`** pour un Pot existant et **`404 Not Found`** pour un Pot inexistant ! C’est une fuite directe d’existence d’un `potId` tiers, violant `SEC-01` et `V3-5E` §14.
* **Scénario ou contre-exemple** :
  Un utilisateur quelconque appelle `GET /pots/{targetPotId}?minVersion=999999`. Si la Supra teste d’abord `CurrentPot.version < minVersion` et renvoie `503`, l’attaquant sait immédiatement que `{targetPotId}` existe (et peut même trouver sa version courante exacte par dichotomie sur `minVersion`).
* **Conséquence** :
  Oracle `503` vs `404` permettant de découvrir l’existence et la version courante de n’importe quel `potId` sans y avoir droit, et ambiguïté sur la lisibilité d’un Pot supprimé.
* **Recommandation** :
  1. **Imposer dans `V3-3` (§9) et `V3-5E` (§13–14) l’ordre strict des contrôles en lecture d’un Pot** :
     * Étape 1 : Authentification `EUI` + vérification `CurrentBinding(EUI)` actif $\rightarrow$ `PocomaUserId` (sinon `401`/`403`).
     * Étape 2 : Lecture de `CurrentPot(P) -> V_courant` et de `PotVersion(P, V_courant)`. Si absent **ou** si `PocomaUserId` $\notin$ `PotVersion(P, V_courant).authorizedUserIds`, retourner immédiatement **`404 Not Found`** (avant toute comparaison à `minVersion` et avant toute vérification de `PotBalance` !).
     * Étape 3 : Seulement une fois le droit d’accès courant prouvé sur `PotVersion(P, V_courant)`, vérifier `V_courant >= minVersion` et la disponibilité de `PotBalance(P, V)` / `SubobjectVersion` (sinon `503 Service Unavailable`).
  2. **Trancher explicitement le statut de lecture d’un Pot supprimé** (et exclure `DeletePot` de l’oracle `C-03` de lecture de Pot complet si un Pot supprimé renvoie `404` à tous).
* **Impact** : `V3-3` (§3, §9), `V3-5C` (§7 `C-03`), `V3-5D` (§20 `SEC-01`, §40), `V3-5E` (§13, §14).
* **Statut** : Défaut actif dans la cible V3 finale.

---

### `AUD-V3-012` — Contradictions documentaires internes et numérotations conflictuelles dans `V3-3`, `V3-5D` et `V3-5E`

* **Identifiant** : `AUD-V3-012`
* **Sévérité** : **Mineur** (mais **Majeur pour la lisibilité d’implémentation**)
* **Nature** : Contradictions internes par empilement d’amendements sans nettoyage des sections remplacées
* **Localisation** :
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§§1–3 l.14-35 vs §§9–10 l.96-125)
  * [SPEC-10_Test_Certification.md](./SPEC-10_Test_Certification.md) (§5 l.46, §15 l.478, §19 l.636, §20 l.675, §24.2 l.847, §24.4 l.879, §30 l.1117, §§31–35 l.1178-1283 vs §§36–49 l.1285-1501)
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) (§§6–8 l.55-97 ; double `## 17.` à l.286 et l.320 ; collision d’identifiant `S-02` à l.309 et l.343)
* **Constat** :
  Les documents ont été amendés par ajout de sections en fin de fichier sans mise à jour des sections initiales, ce qui laisse des instructions contradictoires dans un même fichier :
  1. **Dans `V3-3`** :
     * §1 l.14 affirme : *« Pas de snapshots complets Pot par version »* et §2 l.19 liste *« 7 projections »*, tandis que §9 l.99 introduit `PotVersion(P,V)` et porte le total à 8 projections.
     * §3 l.35 affirme que le chargement des détails de sous-objets se fait *« en lot ou par jointure »* lors de la lecture de `CurrentPot`, tandis que `V3-5E` (§9 l.113) interdit de retourner les détails d’Expenses dans la lecture principale du Pot.
  2. **Dans `V3-5E`** :
     * Il existe **deux sections numérotées `## 17.`** (l.286 et l.320).
     * L’identifiant **`S-02` désigne deux décisions totalement différentes** dans le même document : à la ligne 309, `S-02` désigne la révocation de lecture historique après retrait d’un Shareholder ; à la ligne 343 (et dans `V3-5D` §47 l.1483), `S-02` désigne le manifeste complet des références d’Expenses !
     * Le §6 l.57 autorise encore *« un jeton de reprise opaque »* ou *« un manifeste complet »* pour les Expenses, alors que le second §17 (`S-02` l.343) impose exclusivement le manifeste complet sans pagination des références.
  3. **Dans `V3-5D`** :
     * Le §24.2 (`F01-A` à `F01-D` l.847-850) et le §24.4 point 4 (l.879) exigent encore que `PotVisibilityState(P).materializedVersion >= V` fasse partie du jalon « Pot complet » de `PERF-01`, alors que le §40 (`C-03` l.1387) l’a retiré du jalon de lecture du Pot pour en faire un jalon distinct « Liste utilisateur à jour ».
     * Les §§31–35 (l.1178-1283) contiennent **quatre versions successives et contradictoires de `C-01`** (page SPA Pocoma, puis fraîcheur 5 minutes, puis preuve de vidage de préfixe) avant la version finale des §§36–37.
     * La table de synthèse §5 (l.46) et la matrice §30 (l.1117) s’arrêtent à 24 exigences et omettent `ADM-01`, `SUPRA-READ-01`, `S-01`, `S-02`, `S-03` ajoutées aux §§44–49.
* **Scénario ou contre-exemple** :
  Un développeur implémentant `PERF-01` à partir de la section dédiée §24 de `V3-5D` codera l’oracle E2E avec `PotVisibilityState`, tandis qu’un autre lisant le §40 codera un oracle différent. De même, une référence à `S-02` est ambiguë entre sécurité historique et pagination d’Expenses.
* **Conséquence** :
  Risque élevé d’erreurs d’interprétation pendant `V3-6` et `V3-7`.
* **Recommandation** :
  Consolider les documents `V3-3`, `V3-5D` et `V3-5E` (et renommer l’un des deux `S-02`, par exemple `S-02A` pour l’autorisation historique et `S-02B` pour le manifeste d’Expenses) avant le lancement de `V3-6`.
* **Impact** : `V3-3`, `V3-5D`, `V3-5E`.
* **Statut** : Défaut de lisibilité documentaire actif.

---

### `AUD-V3-013` — Coût d’amplification d’écriture et de payload HTTP sur un « Grand Pot » (100 Shareholders, 2 000 Expenses)

* **Identifiant** : `AUD-V3-013`
* **Sévérité** : **Mineur**
* **Nature** : Risque de performance et de dimensionnement (`PERF-01` / `PERF-02`)
* **Localisation** :
  * [SPEC-04_Read_Projections.md](./SPEC-04_Read_Projections.md) (§9 l.99-115)
  * [SPEC-09_Performance.md](./SPEC-09_Performance.md) (§5 l.43, l.55)
  * [SPEC-06_Supra_Contracts.md](./SPEC-06_Supra_Contracts.md) (§9 l.113 ; §17 `S-02` l.343 ; §19 l.370)
* **Constat** :
  Sur un « Grand Pot » au sens de `V3-5C` (§5 l.43 : 100 Shareholders, 2 000 Expenses, jusqu’à 100 parts par Expense, soit jusqu’à 200 000 lignes de parts actives) :
  1. **À chaque mutation du Pot (même un simple renommage du Pot ou d’un libellé d’Expense)** :
     * `MaterializeCurrentPot` reconstruit `Pot@V` depuis WRITE et insère un nouveau snapshot complet `PotVersion(P,V)` contenant les 2 000 couples `(expenseId, version)` + 100 couples `(shareholderId, version)` + 100 `authorizedUserIds` (~150 à 300 Ko par version). Après 2 100 versions (création des 100 Shareholders + 2 000 Expenses), la table `PotVersion` contient déjà $\sum_{k=1}^{2100} k \approx 2,2 \times 10^6$ références d’Expenses pour **un seul** Grand Pot.
     * `PreparePotProjectionTasks` reconstruit une seconde fois `Pot@V` (y compris les 2 000 `ExpenseFinancials` et jusqu’à 200 000 parts) et recalcule en arithmétique rationnelle les 100 balances, même si la mutation à `V` n’a touché qu’un libellé (`PotDetails` ou `ExpenseDetails`).
  2. **À chaque lecture principale du Pot (`GET /pots/{potId}`)** :
     * La réponse retourne l’intégralité des 100 `Shareholders` (avec détails et poids), les 100 `Balances` rationnelles, et le manifeste complet des 2 000 références `(expenseId, version)` (~150 à 250 Ko de JSON par lecture).
* **Scénario ou contre-exemple** :
  Dans le scénario de charge `F02-D` (`V3-5D` §24.2 l.857 : Grand Pot 100 Shareholders / 2 000 Expenses), la reconstruction SQL de `Pot@V` pour calculer les balances charge 2 000 `ExpenseFinancials` et jusqu’à 200 000 parts depuis WRITE à chaque événement `POT_EVENT`, deux fois par version (`MaterializeCurrentPot` + `PreparePotProjectionTasks`), avec un objectif E2E p95 ≤ 500 ms.
* **Conséquence** :
  Risque sérieux de dépassement du budget de latence p95 ≤ 500 ms (`PERF-01`) et d’explosion du volume de `PotVersion` / `PotBalance` sur les Grands Pots.
* **Recommandation** :
  1. Appliquer la simplification `AUD-V3-005` (supprimer la double reconstruction et les tables de tâches intermédiaires).
  2. Lors de la reconstruction de `Pot@V` pour `MaterializeCurrentPot` (`PotVersion` + `SubobjectVersion`), ne charger les détails complets d’une `Expense` (`ExpenseDetails` / `ExpenseFinancials` et ses 100 parts) que si sa version `(expenseId, version)` n’est **pas encore présente** dans `SubobjectVersion` en READ (à `V`, au plus **une seule** `Expense` a changé ! Pour les 1 999 autres Expenses, seuls `ExpenseHeader`, `ExpenseDetails.version` et `ExpenseFinancials.version` suffisent à construire `PotVersion(P,V)` si l’on évite de recharger les `SubobjectVersion` déjà matérialisées).
* **Impact** : `V3-3` (§7, §9), `V3-5C` (§5).
* **Statut** : Risque de performance actif à prendre en compte dès la conception des requêtes SQL en `V3-6`.

---

## IV. Analyse de simplification

Conformément à la règle d’or de la mission (*« refuser la complexité qui ne procure pas de garantie ou de valeur identifiable »*), voici trois simplifications architecturales concrètes qui réduisent drastiquement le nombre de concepts, de tables durables, de cycles de polling et de transactions SQL, tout en **conservant intégralement** les invariants métier, d’isolation et de concurrence de Pocoma V3.

### 1. Suppression de `PreparePotProjectionTasks`, `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK`

| Critère | Conception V3 actuelle (`V3-3` §7, `V3-4` §8) | Alternative simplifiée proposée |
| :--- | :--- | :--- |
| **Mécanisme** | `POT_EVENT` est consommé par `MaterializeCurrentPot` ET par `PreparePotProjectionTasks`. Ce dernier reconstruit `Pot@V` une 2e fois, calcule les balances et la visibilité, et écrit deux tâches burnables durables `PotBalanceTask(P,V,balances)` et `PotVisibilityTask(P,V,visibleUserIds)`. Deux autres boucles `BurnableDiscovery` + `Engine` créent ensuite des slots, lisent ces tâches, recopient `balances` dans `PotBalance(P,V)` et mettent à jour `PotVisibilityState` + `CurrentUserPotList`. | **Supprimer `PreparePotProjectionTasks`, `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK`.**<br>Avoir uniquement **2 (ou 3) Consumptions non-burnables directes de `POT_EVENT`** :<br>• *Variante 1A (2 Consumptions — Recommandée)* : `MaterializePotSnapshot` (écrit les projections immuables par version : `SubobjectVersion`, `PotVersion(P,V)`, `PotBalance(P,V)` et avance le pointeur `CurrentPot(P)`) + `MaterializePotVisibility` (met à jour `PotVisibilityState(P)` et `CurrentUserPotList`).<br>• *Variante 1B (3 Consumptions)* : `MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility` directement sur `POT_EVENT`. |
| **Ce qui est simplifié / supprimé** | 8 familles de consommables, 6 Consumptions READ, 2 tables de tâches durables en READ conservées indéfiniment en état `TERMINAL`, 2 boucles `BurnableDiscovery` en READ, identifiants déterministes composites `(preparationConsumptionId, potEventId, taskType)`. | **-2 familles de consommables** (retour aux 6 familles naturelles de `V3-2`).<br>**-1 à -2 Consumptions**.<br>**-2 tables durables en READ** (plus de duplication infinie des balances et visibilités en état `TERMINAL`).<br>**-2 boucles `BurnableDiscovery` en READ**.<br>**-1 saut asynchrone complet (`Discovery -> Slot -> Claim`)** sur le chemin critique E2E `C-03`. |
| **Garanties conservées** | Indépendance entre la lecture du Pot (`PotVersion` + `PotBalance`, sans verrou inter-version) et la mise à jour de `CurrentUserPotList` (sérialisée par `PotVisibilityState(P)`). | **100 % conservées** : dans la Variante 1A comme dans la Variante 1B, `PotBalance(P,V)` et `PotVersion(P,V)` sont des insertions immuables par `(P,V)` qui ne prennent **aucun verrou** sur `PotVisibilityState(P)` ni sur `CurrentUserPotList`. Si `MaterializePotVisibility` subit une contention ou un retry, la lecture du Pot (`PotVersion` + `PotBalance`) n’est jamais ralentie, et vice versa. |
| **Coût WRITE / READ** | 2 reconstructions complètes de `Pot@V` depuis WRITE + écriture de `PotBalanceTask` + écriture de `PotBalance` + 2 cycles de slots READ. | • En *Variante 1A* : **1 seule reconstruction complète** de `Pot@V` pour `PotVersion` + `PotBalance`, et 1 lecture légère des seuls `PotHeader/Details` + `ShareholderHeader/Details` (sans aucune `Expense` !) pour `MaterializePotVisibility`.<br>• En *Variante 1B* : aucune tâche intermédiaire, écriture directe de `PotBalance(P,V)`. |

> **Pourquoi `PreparePotProjectionTasks` était un faux ami** : La règle R8 de `V3-4` (*« un seul effet conceptuel par Consumption »*) a conduit à créer une Consumption (`PreparePotProjectionTasks`) qui produit **deux** effets durables (`PotBalanceTask` et `PotVisibilityTask`) contenant déjà le résultat calculé, uniquement pour que deux autres Consumptions recopient ces résultats ! Écrire directement `PotBalance(P,V)` au lieu d’écrire `PotBalanceTask(P,V,balances)` fait exactement le même travail SQL, avec une étape asynchrone et une table durable en moins.

---

### 2. Suppression du `BurnableDiscovery` par polling pour `COMMAND` et `IDENTITY_REQUEST` (Enrôlement atomique à l’admission)

| Critère | Conception V3 actuelle (`V3-4` §2 l.21) | Alternative simplifiée proposée |
| :--- | :--- | :--- |
| **Mécanisme** | À l’admission (`T1`), la Supra WRITE insère la demande `PENDING` dans la base WRITE. Plus tard (`T2`), un processus `Discovery` séparé exécute un polling SQL (`WHERE status = 'PENDING' AND NOT EXISTS slot`) sur la même base WRITE pour insérer un `Slot(consumptionId, requestId)`. Puis (`T3`) un worker `Engine` acquiert ce slot. | **Insérer le `Slot(consumptionId, requestId, partitionId)` dans la même transaction WRITE que l’admission de la `COMMAND` ou de l’`IDENTITY_REQUEST`.**<br>*(Combiné avec la simplification #1 ci-dessus, il n’existe plus aucun `BurnableDiscovery` dans tout le système !)* |
| **Ce qui est simplifié / supprimé** | Deux algorithmes de `Discovery` distincts (séquences non-burnables à checkpoint `K` vs tables burnables sans checkpoint avec anti-join SQL `NOT EXISTS slot`), jalon `T2` d’attente de découverte sur chaque commande. | **Un seul algorithme de `Discovery` dans tout Pocoma V3** (exclusivement dédié aux 4 journaux d’événements non-burnables avec checkpoint `K`).<br>Suppression totale du polling SQL `PENDING sans slot` et suppression de la latence `T1 -> T2` sur toutes les commandes et requêtes d’identité. |
| **Garanties conservées** | Idempotence d’admission, partitionnement par `potId` / `targetEuiId`, retries bornés, quarantaine des slots, fencing par bail. | **100 % conservées** : le slot créé à l’admission est strictement identique au slot qu’aurait créé `BurnableDiscovery`, dans la même base WRITE et avec la même clé primaire `(consumptionId, targetId)`. |

---

### 3. Chargement différentiel des `SubobjectVersion` lors de la matérialisation de `PotVersion(P,V)`

* **Problème** : Dans `V3-3` (§7 et §9), `MaterializeCurrentPot` reconstruit l’état complet `Pot@V` depuis WRITE et écrit `SubobjectVersion` + `PotVersion(P,V)`. Sur un Pot de 2 000 Expenses, reconstruire les 2 000 Expenses avec leurs 100 parts chacune à chaque nouvelle version `V` représente jusqu’à 200 000 lignes lues en WRITE alors qu’une **seule** composante d’une seule Expense (ou du Pot) a changé à la version `V` (`activeFrom = V`).
* **Simplification / Optimisation** :
  * Pour construire `PotVersion(P,V)`, seules les identités et numéros de version `(shareholderId, version)` et `(expenseId, version)` des sous-objets actifs à `V` sont nécessaires.
  * Pour `SubobjectVersion`, seules les composantes dont **`activeFrom = V`** ont pu apparaître à la version `V` (toutes les composantes avec `activeFrom < V` ont déjà leur `SubobjectVersion` correspondante, ou peuvent être chargées uniquement si absentes).
  * Cette propriété découle directement de l’invariant de `V3-1` (§2 l.29 : toute nouvelle instance créée à `V` porte `activeFrom = V`) et réduit d’un facteur $1\,000\times$ le volume de détails/parts à sérialiser dans `SubobjectVersion` à chaque version `V`.

---

## V. Matrice de couverture et de preuves

Le tableau ci-dessous évalue les **29 exigences** du corpus V3 (les 24 exigences de `V3-5A` + les 5 exigences/décisions de `V3-5E` ajoutées dans `V3-5D` §§44–49) au regard des mécanismes définis et des protocoles de certification de `V3-5D` :

| ID Exigence | Intitulé / Garantie attendue | Mécanisme V3 prévu | Protocole `V3-5D` | Évaluation de la preuve & Angles morts |
| :--- | :--- | :--- | :--- | :--- |
| **`EVT-01`** | Séquencement monotone sans trou visible | Compteur transactionnel par famille verrouillé jusqu’au commit (`V3-2` §5) | §6 (`E01-A` à `E01-H`) | **Partiel (`AUD-V3-004`)** : Le verrou compteur empêche l’allocation hors ordre, mais `V3-4` §2 doit imposer une lecture atomique en un seul snapshot SQL côté `Discovery`. |
| **`EVT-02`** | Atomicité état métier + événement métier + événement de complétion | Transaction WRITE unique (`V3-2` §4–5) | §7 (`E02-A` à `E02-I`) | **Solide** : Couvre acceptation, rejet métier (`OBSOLETE` sans `POT_EVENT`), et rollback sur panne d’insertion. |
| **`EVT-03`** | Idempotence de création des consommables dérivés | Clé déterministe `(preparationConsumptionId, potEventId, taskType)` (`V3-4` R1) | §8 (`E03-A` à `E03-G`) | **Solide mais évitable (`AUD-V3-005`)** : Sans objet si `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK` sont supprimées. |
| **`CON-01`** | Progression des checkpoints sans perte d’événement | Fenêtre bornée `(K, H]` + slots + avancement atomique de `K` en base d’effets (`V3-4` §2, §4) | §9 (`C01-A` à `C01-I`) | **Défaut (`AUD-V3-004`, `AUD-V3-007`)** : Risque de blocage si `softCap` compte les slots `QUARANTINED`, et construction SQL de `(K, H]` non spécifiée. |
| **`CON-02`** | Exclusivité d’acquisition et reprise après expiration de bail | Claim atomique + `leaseExpiresAt` + vérification du bail au commit (`V3-4` §5) | §10 (`C02-A` à `C02-H`) | **Solide** : Preuve de non-double-effet par fencing dans la transaction d’effets. |
| **`CON-03`** | Convergence sous rejeu concurrent et hors ordre | `PotVersion`/`PotBalance` immuables par `(P,V)` + gardes monotones `version < V` (`V3-3` §§6–9) | §11 (`C03-A` à `C03-H`) | **Solide** : Le découplage `PotVersion(P,V)` / `CurrentPot(P)` résout proprement l’arrivée de `V+1` avant `V`. |
| **`CON-04`** | Retry borné et quarantaine isolée | `attemptCount`, backoff, passage à `QUARANTINED` à `maxAttempts` (`V3-4` §5) | §12 (`C04-A` à `C04-G`) | **Contradiction (`AUD-V3-007`)** : `C04-F` exige que `QUARANTINED` ne bloque pas la partition, mais le `COUNT` de `V3-4` §2 l.23 inclut les slots présents. |
| **`CON-05`** | Cohérence causale par Pot et par EUI | Clé de partition = `potId` ou `targetEuiId` ; sérialisation par entité en WRITE (`V3-4` §3) | §13 (`C05-A` à `C05-H`) | **Solide** : Garantit l’ordre par Pot en WRITE et la convergence par garde monotone en READ. |
| **`CON-06`** | Alignement transactionnel `Slot ↔ Effets` | Tables de slots et checkpoints logées dans la DB des effets (`WRITE` ou `READ`) (`V3-4` §4) | §14 (`C06-A` à `C06-H`) | **Solide** : Élimine le besoin de 2PC entre WRITE et READ. |
| **`PRJ-01`** | Indépendance entre lecture du Pot et visibilité | Séparation `MaterializeCurrentPot`, `PotBalance` et `PotVisibility` (`V3-3` §7, §9) | §15 + §43 (`P01-A` à `P01-G`) | **À nettoyer (`AUD-V3-012`)** : Le §15 teste encore l’ancien modèle pré-`PotVersion`, corrigé seulement au §43. |
| **`PRJ-02`** | Exactitude rationnelle et somme nulle des balances | Calcul rationnel `BigInteger num/den` irréductible, `den > 0` (`V3-1` §4, `V3-5D` §42) | §16 + §42 (`P02-A` à `P02-H`) | **Attention (`AUD-V3-003`)** : La preuve suppose que tous les Shareholders référencés par une Expense active sont actifs à `V` ; échoue si `DeleteShareholder` supprime un participant d’une Expense active ! |
| **`PRJ-03`** | Reconstruction intégrale de READ depuis WRITE | Purge READ + rejeu depuis `K=0` des 4 journaux WRITE (`V3-3` §8) | §17 (`P03-A` à `P03-G`) | **Incomplet (`AUD-V3-009`)** : Omet de préciser que la reconstruction de `CurrentBinding` doit relire `Binding@V` en WRITE pour retrouver le `bindingToken`. |
| **`PRJ-04`** | Sérialisation de `PotVisibilityState(P)` et garde monotone `CurrentUserPotList` | Verrouillage de `PotVisibilityState(P)` + `lastPotVersion < V` (`V3-3` §6–7) | §18 (`P04-A` à `P04-H`) | **Solide** : Empêche un événement `V` tardif de ré-ouvrir la visibilité fermée à `V+1`. |
| **`READ-01`** | Lecture cohérente à une version `V` et respect de `minVersion` | `PotVersion(P,V)` immuable + `SubobjectVersion` + `PotBalance(P,V)` (`V3-3` §§9–10) | §19 + §43 (`R01-A` à `R01-H`) | **Incomplet (`AUD-V3-010`, `AUD-V3-011`)** : Contrat de `CommandResult`/`IdentityRequestResult` avant matérialisation et cas de `DeletePot` non tranchés. |
| **`SEC-01`** | Autorisation READ et confidentialité du `bindingToken` | `CurrentBinding` + `PotVersion.authorizedUserIds` + masquage `404` (`V3-3` §9, `V3-5E` §14) | §20 + §43 (`S01-A` à `S01-O`) | **Bloquant (`AUD-V3-001`, `AUD-V3-009`, `AUD-V3-011`)** : Contradiction sur le créateur non-Shareholder à `V1`, ordre de contrôle `404` vs `503`, et lecture de `CurrentBinding` après `Link`. |
| **`OBS-01`** | Corrélation bout en bout des traces et jalons `T0..T6` | Propagation de `requestId`, `eventId`, `taskId`, `consumptionId` (`V3-5B` §§1–2) | §21 (`O01-A` à `O01-G`) | **Solide** : Bien spécifié. |
| **`OBS-02`** | Métriques de lag, slots et quarantaines | Compteurs par `(consumptionId, partitionId)` et âge des quarantaines (`V3-5B` §§3–5) | §22 (`O02-A` à `O02-G`) | **Solide** : Requêtes d’observabilité découplées du chemin critique. |
| **`OBS-03`** | Non-exposition des secrets dans les logs/traces/métriques | Interdiction de journaliser `bindingToken`, JWT et `subject` brut (`V3-5B` §6) | §23 (`O03-A` à `O03-G`) | **Solide** : Couvre succès, rejets, retries et quarantaines. |
| **`PERF-01`** | Latence E2E p95 ≤ 500 ms / p99 ≤ 1 500 ms | Oracle `C-03` (`PotVersion` + `PotBalance` + `SubobjectVersion` pour `W >= V`) (`V3-5C` §2, §7) | §24 + §40 (`F01-A` à `F01-F`) | **À corriger (`AUD-V3-001`, `AUD-V3-011`, `AUD-V3-012`)** : Impossible pour `CreatePot` à `V1` tant que le créateur n’a pas le droit de lire le Pot ; §24.2 et §24.4 contredisent §40. |
| **`PERF-02`** | Débit nominal (20 cmd/s, 200 lectures/s) et rattrapage | Exécution hors-ordre bornée par partition, index dédiés (`V3-5C` §§2–4) | §24 + §39 (`F02-A` à `F02-G`) | **Risque (`AUD-V3-005`, `AUD-V3-013`)** : La double reconstruction de `Pot@V` sur un Grand Pot (2 000 Expenses) menace le budget de 500 ms et de rattrapage en 60 s. |
| **`DOM-01`** | Invariants de versions `[activeFrom, activeTo]` et reconstruction à `V` | Fermeture `activeTo = V`, ouverture `activeFrom = V+1`, filtre SQL d’intervalle (`V3-1` §§2–3) | §25 (`D01-A` à `D01-K`) | **Solide sur le versionnement, mais lacune sur `DeleteShareholder` (`AUD-V3-003`)**. |
| **`DOM-02`** | Préconditions de concurrence (`observedVersion`) et rejet `OBSOLETE` | Vérification `activeFrom <= observedVersion` sur les composantes dépendantes (`V3-1` §5) | §26 (`D02-A` à `D02-I`) | **Incomplet (`AUD-V3-002`, `AUD-V3-003`)** : Omet la dépendance de `CreateExpense` / `ModifyExpenseFinancials` envers `ShareholderDetails` et les règles d’autorisation WRITE. |
| **`ID-01`** | Unicité d’appartenance d’un EUI et versionnement de `Binding` | Partitionnement par EUI cible + intervalles `[activeFrom, activeTo]` (`V3-1 Identity` §§2–4) | §27 (`I01-A` à `I01-H`) | **Solide** : Concurrence `Registration`/`Link`/`Unlink` bien couverte. |
| **`ID-02`** | Sécurité du `Link` (consentement de `E2` et rotation du `bindingToken`) | Vérification de `E1` + attestation signée de `E2` + rotation `bindingToken` (`V3-1 Identity` §4) | §28 + §§36–37 (`I02-A` à `I02-O`) | **Risque majeur (`AUD-V3-008`)** : Attestation externe avec compteurs READ incompatible avec Keycloak/OIDC standard sans extension custom et sans index inverse `CurrentBinding(pocomaUserId)`. |
| **`ADM-01`** | Frontière d’admission Supra (`400`/`413`/`422`/`503` avant persistance vs rejet Worker après `202`) | Validation intrinsèque hors DB métier + admission transactionnelle WRITE (`V3-5E` §§2–4) | §44 (`ADM01-A` à `ADM01-E`) | **Solide** : Séparation claire entre forme intrinsèque et état métier. |
| **`SUPRA-READ-01`** | Contrats HTTP de lecture (`CurrentUserPotList`, `Pot`, `Expenses`, `404`/`503`) | Pagination composite `(lastPotVersion DESC, potId ASC)`, manifeste d’Expenses, batch details (`V3-5E` §§5–15) | §45 (`SR01-A` à `SR01-F`) | **Incomplet (`AUD-V3-009`, `AUD-V3-010`, `AUD-V3-011`)** : `CurrentBinding`, `CommandResult`, et ordre d’évaluation `404` vs `503` à compléter. |
| **`S-01`** | Bornes techniques explicites à l’admission et en lecture | Constantes normatives (`V3-5E` §19 : 100 Shareholders, 2 000 Expenses, etc.) | §46 (`S01-SUPRA-A` à `D`) | **Solide** : Bornes explicites et alignées avec le profil « Grand Pot » de `V3-5C`. |
| **`S-02`** | Manifeste complet des références d’Expenses + chargement groupé borné | `PotVersion` retourne les $\le 2\,000$ `(expenseId, version)` ; batch details $\le 100$ (`V3-5E` §17 l.343) | §47 (`S02-SUPRA-A` à `D`) | **Collision d’identifiant (`AUD-V3-012`)** : Renommer l’un des deux `S-02` de `V3-5E` et certifier aussi la révocation historique (`S-02` l.309). |
| **`S-03`** | Idempotence d’admission WRITE et READ batch | `Idempotency-Key` obligatoire en WRITE (`202` rejoué, `409` si payload différent) ; lecture batch sans effet (`V3-5E` §20) | §48 (`S03-SUPRA-A` à `D`) | **Solide** : Il manque uniquement la durée de rétention minimale des clés d’idempotence en WRITE (ex. 24 h). |

*Note importante* : Conformément au jalon `V3-CADRE` (`V3_Canon_and_Roadmap.md` §3 l.55), **100 % de ces preuves sont à ce stade des protocoles planifiés** ; aucune n’est encore exécutée (la réalisation des tests se fera en `V3-6` et la certification complète en `V3-7`).

---

## VI. Plan de remédiation du cadrage

### Catégorie A — À corriger impérativement **avant** de démarrer `V3-6` (préparation d’implémentation)

1. **`[AUD-V3-001]` Trancher et documenter la matrice d’autorisation WRITE et le statut READ/WRITE du créateur (`creatorId`)** dans `V3-1` et `V3-3` :
   * Définir qui a le droit d’exécuter chaque commande sur un Pot.
   * Résoudre l’impasse de `CreatePot` à `V1` (soit en incluant `creatorId` dans `PotVersion(P,V).authorizedUserIds` tant que le Pot n’est pas supprimé, soit en créant obligatoirement un `Shareholder` initial rattaché au créateur dès `CreatePot` en `V1`).
2. **`[AUD-V3-002]` Clarifier et rapatrier dans `V3-1` la règle d’attribution et de détachement de `ShareholderDetails.userId`** :
   * Résoudre le paradoxe d’auto-rattachement d’un utilisateur non encore `Shareholder` du Pot.
3. **`[AUD-V3-003]` Compléter la table des préconditions `observedVersion` dans `V3-1` (§5) et `V3-5D` (§26)** :
   * Exiger `ShareholderHeader.activeFrom <= observedVersion` et `ShareholderDetails.activeFrom <= observedVersion` pour tous les `Shareholders` référencés lors de `CreateExpense` et `ModifyExpenseFinancials`.
   * Interdire dans `DeleteShareholder` la suppression d’un `Shareholder` référencé par au moins une `Expense` active (`ExpenseHeader.deleted = false`), afin de préserver l’invariant de somme nulle `DOM-01` / `PRJ-02`.
4. **`[AUD-V3-005]` et `[AUD-V3-006]` Arbitrer la suppression de `PreparePotProjectionTasks` + tâches burnables (`POT_BALANCE_TASK`, `POT_VISIBILITY_TASK`) et de `BurnableDiscovery`** :
   * Ce choix structure directement le schéma SQL des bases WRITE et READ ainsi que le découpage des lots `V3-6` (`Lot 2` et `Lot 4` de `V3_Canon_and_Roadmap.md` §10).
5. **`[AUD-V3-009]` et `[AUD-V3-010]` Fermer les deux points laissés « À préciser avant clôture » dans `V3-5E` (§17 l.298 et l.302)** :
   * Spécifier le contrat d’authentification et de réponse de `CurrentBinding` (notamment après `Link(E1 -> E2)` et sa reconstruction depuis WRITE).
   * Ajouter l’identité du demandeur dans `CommandResult` / `IdentityRequestResult` et fixer le code HTTP retourné lorsque le résultat n’est pas encore matérialisé en READ.

### Catégorie B — À préciser pendant la préparation `V3-6`, avant le lot d’implémentation concerné

1. **`[AUD-V3-004]` et `[AUD-V3-007]` (Avant le Lot 2 — Séquencement, Slots, Discovery, Engine)** :
   * Spécifier dans `V3-4` §2 que la sélection de la fenêtre `(K, H]` par `Discovery` s’effectue en un seul snapshot SQL atomique, et que le plafond souple (`softCap`) de slots présents par `(consumptionId, partitionId)` exclut les slots `QUARANTINED` (`status != 'QUARANTINED'`).
2. **`[AUD-V3-008]` (Avant le Lot 3 — Domaine Identity)** :
   * Réaliser le spike `R-01` sur l’attestation de consentement `Link` avec Keycloak conteneurisé (Config B) et autoriser un index secondaire sur `CurrentBinding(pocomaUserId)` en READ si le comptage des EUI liés est maintenu.
3. **`[AUD-V3-011]` (Avant les Lots 4 et 5 — Projections READ et Supras)** :
   * Imposer l’ordre strict d’évaluation en lecture (`AuthN -> CurrentBinding -> existence & autorisation dans PotVersion(P, V_courant) [sinon 404] -> vérification minVersion & disponibilité PotBalance [sinon 503]`).
   * Préciser le comportement de lecture d’un Pot supprimé (`deleted = true`) et son traitement dans l’oracle `C-03`.
4. **`[AUD-V3-012]` (Nettoyage documentaire)** :
   * Nettoyer les sections remplacées dans `V3-3` (§§1–3), `V3-5D` (§15, §19, §20, §24, §§31–35) et `V3-5E` (§§6–8, double §17, dédoublement de `S-02`).

### Catégorie C — À vérifier pendant `V3-6` et `V3-7` (implémentation et certification)

1. **`[AUD-V3-013]` (Pendant les Lots 4 et 5, puis certification en `V3-7`)** :
   * Optimiser l’insertion de `SubobjectVersion` (ne sérialiser les détails/parts d’Expenses que pour les versions locales nouvellement créées `activeFrom = V`) et vérifier sur le profil « Grand Pot » (`F02-D` : 100 Shareholders, 2 000 Expenses) la tenue du budget p95 ≤ 500 ms.
2. **Durée de rétention des clés d’idempotence (`S-03`)** :
   * Fixer la fenêtre de rétention (ex. 24 h) et l’index d’expiration associé lors de l’implémentation du Lot 5 (Supras).

---

## VII. Questions d’arbitrage

Voici, par ordre de priorité décroissante, les 5 décisions d’architecture et de métier qui nécessitent un arbitrage explicite avant de figer le cadrage d’implémentation `V3-6` :

### 1. Statut du créateur du Pot (`creatorId`) en lecture, en écriture et lors de `CreatePot` (`V1`) — *Réf. `AUD-V3-001`*

* **Options possibles** :
  * **Option 1A (Recommandée)** : **Le créateur immuable `creatorId` dispose de plein droit de l’accès en lecture et en écriture au Pot (tant que le Pot n’est pas supprimé), au même titre que tout `PocomaUser` rattaché à un `Shareholder` actif à `V`.** `PotVersion(P,V).authorizedUserIds` contient alors `{creatorId} ∪ {ShareholderDetails.userId des Shareholders actifs à V}` (et $\emptyset$ si le Pot est supprimé).
  * **Option 1B** : **Seuls les `Shareholders` actifs à `V` ont accès au Pot en lecture et en écriture, MAIS `CreatePot` (`V1`) crée obligatoirement un premier `Shareholder` initial rattaché à `creatorId`** (et il est interdit de détacher ou supprimer le dernier `Shareholder` rattaché à un `PocomaUser` actif sans supprimer le Pot, et un créateur non-Shareholder n’a pas non plus de visibilité dans `CurrentUserPotList`).
* **Recommandation** : **Option 1A** (ou **Option 1B** si le produit souhaite que tout créateur soit systématiquement participant initial du Pot). L’Option 1A est la seule qui conserve telles quelles les règles de `V3-1` (`creatorId` distinct des Shareholders, `CreatePot` à `V1` sans Shareholder) et de `V3-3` §4 (`CurrentUserPotList` visible au créateur) tout en rendant `Pot@V1` immédiatement lisible (`C-03`) et administrable par son créateur.
* **Impact** : `V3-1` (§1, §5), `V3-3` (§4, §9, §10), `V3-5D` (§20, §43).

### 2. Règle d’attribution d’un `PocomaUserId` à un `Shareholder` et matrice d’autorisation WRITE — *Réf. `AUD-V3-001`, `AUD-V3-002`*

* **Options possibles** :
  * **Option 2A (Recommandée)** :
    1. Toute commande WRITE sur un Pot existant exige que l’auteur authentifié (`PocomaUserId`) soit un acteur autorisé du Pot à la version courante (créateur ou `Shareholder` actif).
    2. Lors de `CreateShareholder` ou `ModifyShareholderDetails`, l’auteur ne peut renseigner `ShareholderDetails.userId` qu’avec **son propre `PocomaUserId`** (auto-rattachement d’un acteur déjà autorisé sur le Pot) ou `null` (détachement / participant non lié). Pour qu’un tiers rejoigne le même participant, il lie son `EUI` via `Link`, ou bien un mécanisme d’invitation explicite (hors V3 initial ou jeton d’invitation Pot) l’autorise à réclamer un `Shareholder` libre.
  * **Option 2B** : Autoriser un membre du Pot à désigner un autre `PocomaUserId` connu lors de l’ajout/modification d’un `Shareholder` (ce qui abandonne la règle de consentement de `V3-3` §4 l.47).
* **Recommandation** : **Option 2A**, en déplaçant la règle de `V3-3` §4 vers `V3-1` §5 et en clarifiant comment un second `PocomaUser` distinct du créateur peut rejoindre un Pot.
* **Impact** : `V3-1` (§1, §5), `V3-3` (§4), `V3-5D` (§26).

### 3. Suppression d’un `Shareholder` référencé par une `Expense` active et contrôle `observedVersion` sur `ShareholderDetails` — *Réf. `AUD-V3-003`*

* **Options possibles** :
  * **Option 3A (Recommandée)** :
    1. **Interdire `DeleteShareholder`** si le `Shareholder` est référencé (comme payeur ou participant) par au moins une `Expense` active (`ExpenseHeader.deleted = false`) à la version courante (il faut d’abord supprimer ou réaffecter ces `Expenses`).
    2. **Exiger dans `CreateExpense` et `ModifyExpenseFinancials`** que chaque `Shareholder` référencé ait `ShareholderHeader.activeFrom <= observedVersion` et `ShareholderDetails.activeFrom <= observedVersion`.
  * **Option 3B** : Autoriser `DeleteShareholder` uniquement si sa balance courante vaut `0/1` et recalculer les balances en incluant les `Shareholders` supprimés ou en figeant l’historique (beaucoup plus complexe et contredit l’invariant `V3-1` §4 selon lequel les balances sont calculées sur les `Shareholders` actifs à `V` à partir des `Expenses` actives à `V`).
* **Recommandation** : **Option 3A**. Elle garantit mathématiquement et sans cas particulier l’invariant de somme nulle (`DOM-01` / `PRJ-02`) et protège contre l’imputation financière à un participant modifié concurremment (`DOM-02`).
* **Impact** : `V3-1` (§3, §4, §5), `V3-5D` (§25, §26).

### 4. Simplification du pipeline de projections READ (`PreparePotProjectionTasks` + tâches burnables) et de l’enrôlement des demandes — *Réf. `AUD-V3-005`, `AUD-V3-006`*

* **Options possibles** :
  * **Option 4A (Recommandée — Simplification maximale)** :
    1. Supprimer `PreparePotProjectionTasks`, `POT_BALANCE_TASK` et `POT_VISIBILITY_TASK` : consommer `POT_EVENT` directement par les Consumptions de matérialisation (`MaterializePotVersion` + `MaterializePotBalance` [ou groupés en une seule Consumption de snapshots immuables par `(P,V)`] et `MaterializePotVisibility`).
    2. Créer le `Slot` de `COMMAND` et d’`IDENTITY_REQUEST` atomiquement dans la transaction d’admission en WRITE, supprimant ainsi tout `BurnableDiscovery` par polling.
  * **Option 4B (Conservatrice)** : Conserver les 8 familles de consommables, `PreparePotProjectionTasks` et `BurnableDiscovery` tels que décrits dans `V3-4`.
* **Recommandation** : **Option 4A**. Elle élimine 2 familles de consommables, 2 tables durables redondantes en READ, 1 Consumption complète, le polling `BurnableDiscovery` en WRITE et en READ, et un saut asynchrone complet sur le chemin critique de 500 ms (`PERF-01`), sans affaiblir aucune garantie d’isolation ni de reprise.
* **Impact** : `V3-2`, `V3-3`, `V3-4`, `V3-5B`, `V3-5D`.

### 5. Portée de l’attestation de consentement `Link` (`C-01`) vis-à-vis des IdP OIDC standards — *Réf. `AUD-V3-008`*

* **Options possibles** :
  * **Option 5A (Recommandée)** : Exiger cryptographiquement dans WRITE une preuve d’authentification fraîche de `E2` liée à l’intention de liaison `(Pocoma, E1, pocomaUserId / contexte)` (réalisable en OIDC standard via `nonce` / claims signés ou SPI léger en Config B), tout en permettant que le récapitulatif métier détaillé (compteurs de Pots et d’EUI liés) soit présenté par l’interface appelante avant la redirection OIDC et/ou par l’IdP lorsqu’il supporte RAR, et autoriser un index secondaire `CurrentBinding(pocomaUserId)` en READ.
  * **Option 5B** : Maintenir l’exigence stricte que seul l’écran hébergé par l’IdP affiche les compteurs métier dynamiques (`potCount`, `linkedEuiCount`, `readTimestamp`) et les signe dans une attestation propriétaire (ce qui impose de développer un SPI Keycloak sur mesure pour la Config B et exclut les IdP OIDC standards non modifiables).
* **Recommandation** : **Option 5A**, complétée par l’index secondaire `CurrentBinding(pocomaUserId)` en READ.
* **Impact** : `V3-1 Identity` (§4), `V3-3` (§1, §5), `V3-5D` (§§32–37), `V3-5E` (§20).
