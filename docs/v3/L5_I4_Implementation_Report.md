# L5/I4 — Invitation et désaffiliation volontaire

État : L5/I4 — TARGETED CORRECTIONS IMPLEMENTED — LOCAL VERIFICATION REPORTED — PENDING INDEPENDENT REVIEW

## 1. Contexte et mandat de correction

Le 5 novembre 2026, à la suite d'un audit indépendant sur le commit `d722e9d5858c9e4eba5aa1883cf72988b463d780` de la branche `feat/v3-architecture`, une mission de corrections ciblées a été autorisée en mode :
`IMPLEMENTATION AUTHORIZED — TARGETED CORRECTIONS ONLY — NO CLOSURE`.

Ce mandat a pour objet exclusif le traitement de trois observations et d'un contrôle SQL complémentaire :
- **AUD-I4-01 (MAJOR) :** Divergence des routes HTTP d'invitation par rapport au cadrage contractuel approuvé.
- **AUD-I4-02 (MINOR) :** Fragilité temporelle du test PostgreSQL d'expiration `V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait`.
- **AUD-I4-03 (MODERATE) :** Provenance insuffisante des preuves finales et inventaire de méthodes JUnit inexact dans le rapport initial.
- **Contrôle complémentaire SQL :** Revue formelle de non-régression sur les 10 propriétés des résultats de commande et des contraintes différées.

Ce mandat n'autorise ni refonte, ni nouvelle fonctionnalité, ni clôture I4, ni altération de quotas/timeouts productifs, ni affaiblissement d'assertions, ni modification du domaine Identity ou Pot, ni exécution de portes globales non requises.

---

## 2. Déclaration du périmètre de vérification (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` :

- **Tranche primaire :** WEB (`runtime-web-api`), validée via `V3InvitationStandaloneWebPostgresTest`.
- **Ancre monolithique :** `runtime-monolith`, validée sur les tests HTTP (`V3InvitationHttpTest`) et le test d'expiration fiabilisé (`V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait`).
- **Tranches non invalidées réutilisées :** `domain-pot` (pur domaine), `runtime-monolith` (tests WRITE 1–5, READ 1–3, Topology 1–2, AdmissionFlow 1) dont les hypothèses et le code restent strictement identiques.
- **Portes globales (`architecture-tests`, native reactor, G5) :** **NON REQUISES** au stade local et non exécutées.
- **Full reactor :** **NON EXÉCUTÉ** conformément à la politique (réservé comme intégration globale).

---

## 3. Résolution des observations d'audit

### AUD-I4-01 — Alignement des routes HTTP Invitation

1. **Vérification normative :** Le plan `docs/v3/L5_Supra_Entry_Audit_and_Implementation_Plan.md` (lignes 142–143 et 787–788) ainsi que SPEC-06 (§13, §14) fixent contractuellement :
   - Restitution du lien : `GET /api/v3/invitations/by-creation-request/{requestId}`
   - Consultation des places libres : `POST /api/v3/invitations/available-shareholders/query`
   Aucune décision modificative ultérieure n'a ratifié les chemins non conformes `/by-request/{requestId}` et `/places`.
2. **Corrections appliquées :**
   - `app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/v3/V3InvitationController.java` :
     - `@GetMapping("/api/v3/invitations/by-creation-request/{requestId}")`
     - `@PostMapping("/api/v3/invitations/available-shareholders/query")`
   - `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3InvitationFixture.java` : routes mises à jour dans `link(UUID)` et `places(String, String, String)`.
   - `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3InvitationHttpTest.java` : appels directs mis à jour vers `/invitations/by-creation-request/`.
   - `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/V3InvitationStandaloneWebPostgresTest.java` : ensemble des appels mis à jour vers les deux routes contractuelles.
   - `scripts/bruno-v3/lib/i4-invitation.js` : scénarios B401, B402, B403, B404 mis à jour vers les deux routes contractuelles.
3. **Absence d'alias :** Aucun alias rétrocompatible n'a été créé, évitant toute persistance accidentelle des chemins non retenus. Formats de réponse, AuthN et absence de cache demeurent inchangés.

### AUD-I4-02 — Fiabilisation déterministe du test d'expiration

1. **Analyse de la cause racine :** Le test `V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait` introduisait un délai artificiel jusqu'à `expiry.minusMillis(400)` avant de poster la commande `/commands`, sous la fausse hypothèse que la DataSource métier appliquait un `lock_timeout=500ms`. En réalité, la DataSource `write-business` utilise `-c statement_timeout=60000 -c transaction_timeout=65000` sans `lock_timeout`. Sous charge machine, le délai d'admission HTTP et de dépilement pouvait dépasser 400 ms, causant le rejet amont de la commande avant qu'elle n'ait pu verrouiller `v3_pot_version` et attendre `v3_event_counter`.
2. **Correction déterministe :**
   - La soumission de la commande est réalisée immédiatement après la prise de verrou amont par la connexion `gate` (`select position from v3_event_counter where family='COMMAND_COMPLETION' for update`), avec une date d'expiration fixée à `clock_timestamp() + interval '4 seconds'`.
   - La commande passe ainsi de manière garantie les vérifications amont `unexpired(expiry)`, applique les mutations sur `v3_shareholder_details` et `v3_pot_version`, et se met en attente exclusive sur le compteur `COMMAND_COMPLETION`.
   - Le test observe via `pg_stat_activity` que la transaction est effectivement bloquée sur `v3_event_counter` tout en détenant un `RowExclusiveLock` sur `v3_pot_version`.
   - Le test attend ensuite que l'horloge PostgreSQL atteigne `clock_timestamp() >= expiry` avant d'exécuter `gate.commit()`.
   - À la libération du verrou, la transaction du worker tente son commit physique : le trigger différé `v3_invitation_final_expiry` s'exécute, constate l'expiration dépassée, et lève l'exception `40001`.
   - La transaction est rollbackée intégralement, le worker réessaie et produit un résultat `REJECTED` (`INVALID_INVITATION`).
   - Toutes les assertions discriminantes sont vérifiées sans aucune sensibilité aux variations de performance du runner.

### AUD-I4-03 — Inventaire exact des méthodes et provenance des preuves

1. **Inventaire JUnit exhaustif au commit audité et corrigé :**
   - Module `pocoma-domain-pot` (`InvitationAttachmentTest`) — 3 méthodes :
     - `occupiedDeletedAbsentAndAlreadyAttachedRemainRejected`
     - `mixedOrMissingDurableFactsAreTechnicalAnomalies`
     - `outsiderAndUnattachedCreatorAttachOnlyThemselvesWithOneDetailsRevision`
   - Module `pocoma-runtime-monolith` (`V3InvitationWritePostgresTest`) — 6 méthodes :
     - `atomicCreationAndIdempotence` (T4-A)
     - `creatorOnlyAndExpiredCreation` (T4-B)
     - `twoGuestsOnePlaceAndReusableToken` (T4-C)
     - `oneGuestCannotTakeTwoPlaces` (T4-D)
     - `rollbackAfterInvitationPositionKeepsDensePublication` (T4-E)
     - `expirationAfterPublicationCounterWait` (T4-F)
   - Module `pocoma-runtime-monolith` (`V3InvitationReadPostgresTest`) — 3 méthodes :
     - `reconstructOnFreshReadWithoutProducer`
     - `authenticatedEncryptionAndOldKeyRestoration` (T4-G)
     - `monotoneDuplicateAndDivergence`
   - Module `pocoma-runtime-monolith` (`V3InvitationHttpTest`) — 5 méthodes :
     - `confidentialLinkAndRestrictedPlaces` (T4-H & T4-I)
     - `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` (T4-K)
     - `concurrentUnlinkAndAttachmentSerializeAtIdentity`
     - `unlinkCannotPreserveStaleInvitationAuthority` (T4-J)
     - `brunoB4StandaloneCorpus` (orchestre et valide les scénarios Bruno B401, B402, B403, B404)
   - Module `pocoma-runtime-monolith` (`V3InvitationTopologyPostgresTest`) — 2 méthodes :
     - `invitationPrivilegePreflightsRejectColumnEscalation`
     - `targetThreeFourPartitionsConnectionsRenewalAndRestart` (T4-L)
   - Module `pocoma-runtime-web-api` (`V3InvitationStandaloneWebPostgresTest`) — 2 méthodes :
     - `exactEuiBindingIndependenceAndMissingKey`
     - `restrictedPlacesWithoutWriteOrDecryption`
   - Module `pocoma-runtime-monolith` (`V3AdmissionFlowPostgresTest`) — 1 méthode :
     - `voluntaryDeparturePreservesHistoryAndIdentity`
   **Total : 22 méthodes JUnit + 4 scénarios Bruno.**

2. **Matrice de correspondance T4-A à T4-L :**
   | Code plan | Méthode JUnit / Scénario | Classe | Module | Statut |
   |---|---|---|---|---|
   | **T4-A** | `atomicCreationAndIdempotence` | `V3InvitationWritePostgresTest` | `runtime-monolith` | PASS historique réutilisé |
   | **T4-B** | `creatorOnlyAndExpiredCreation` | `V3InvitationWritePostgresTest` | `runtime-monolith` | PASS historique réutilisé |
   | **T4-C** | `twoGuestsOnePlaceAndReusableToken` | `V3InvitationWritePostgresTest` | `runtime-monolith` | PASS historique réutilisé |
   | **T4-D** | `oneGuestCannotTakeTwoPlaces` | `V3InvitationWritePostgresTest` | `runtime-monolith` | PASS historique réutilisé |
   | **T4-E** | `rollbackAfterInvitationPositionKeepsDensePublication` | `V3InvitationWritePostgresTest` | `runtime-monolith` | PASS historique réutilisé |
   | **T4-F** | `expirationAfterPublicationCounterWait` | `V3InvitationWritePostgresTest` | `runtime-monolith` | **PASS observé et attribué** |
   | **T4-G** | `authenticatedEncryptionAndOldKeyRestoration` | `V3InvitationReadPostgresTest` | `runtime-monolith` | PASS historique réutilisé |
   | **T4-H** | `confidentialLinkAndRestrictedPlaces` (partie link) | `V3InvitationHttpTest` | `runtime-monolith` | **PASS observé et attribué** |
   | **T4-I** | `confidentialLinkAndRestrictedPlaces` (partie places) | `V3InvitationHttpTest` | `runtime-monolith` | **PASS observé et attribué** |
   | **T4-J** | `unlinkCannotPreserveStaleInvitationAuthority` | `V3InvitationHttpTest` | `runtime-monolith` | **PASS observé et attribué** |
   | **T4-K** | `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` | `V3InvitationHttpTest` | `runtime-monolith` | **PASS observé et attribué** |
   | **T4-L** | `targetThreeFourPartitionsConnectionsRenewalAndRestart` | `V3InvitationTopologyPostgresTest` | `runtime-monolith` | PASS historique réutilisé (bail 180s non rejoué) |
   | **B401–B404**| `brunoB4StandaloneCorpus` | `V3InvitationHttpTest` | `runtime-monolith` | **PASS observé et attribué** |
   | **WEB-1**| `exactEuiBindingIndependenceAndMissingKey` | `V3InvitationStandaloneWebPostgresTest` | `runtime-web-api` | **PASS observé et attribué** |
   | **WEB-2**| `restrictedPlacesWithoutWriteOrDecryption` | `V3InvitationStandaloneWebPostgresTest` | `runtime-web-api` | **PASS observé et attribué** |
   | **DEP-1**| `voluntaryDeparturePreservesHistoryAndIdentity` | `V3AdmissionFlowPostgresTest` | `runtime-monolith` | PASS historique réutilisé |

---

## 4. Traçabilité et provenance des preuves d'exécution

### Environnement d'exécution
- **Machine / OS :** macOS Darwin (Apple Silicon aarch64)
- **Java :** OpenJDK 21.0.9 (Eclipse Temurin, `/Users/julien.guezennec/.sdkman/candidates/java/21-tem`)
- **Maven :** Apache Maven 3.9.x via `./mvnw`
- **PostgreSQL conteneurisé :** PostgreSQL 17.11 via Testcontainers (Docker Desktop local)
- **Bruno CLI :** `@usebruno/cli` v4.3.0 (`scripts/bruno-v3/node_modules/.bin/bru`)

### 4.1 Campagne ciblée post-audit (5 novembre 2026, 14:31–14:33 CET)
Preuves observées et générées directement sur le code corrigé :

1. **Tranche `runtime-web-api` :**
   - **Commande :** `./mvnw -pl runtime-web-api -am test -Dtest=V3InvitationStandaloneWebPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`
   - **Résultat :** `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.116 s`
   - **Rapport XML :** `app/runtime-web-api/target/surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml`
   - **Horodatage :** 2026-11-05T14:31:26+01:00
   - **Statut :** **PASS observé et attribué**.

2. **Tranche `runtime-monolith` (HTTP, Bruno B4 et Expiration) :**
   - **Commande :** `./mvnw -pl runtime-monolith -am test -Dtest=V3InvitationHttpTest,V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait -Dsurefire.failIfNoSpecifiedTests=false`
   - **Résultat global :** `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 01:27 min`
   - **Rapports générés :**
     - `app/runtime-monolith/target/surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` (5 tests, 0 failures, 0 errors, time 67.68s)
     - `app/runtime-monolith/target/surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` (1 test `expirationAfterPublicationCounterWait`, 0 failures, 0 errors, time 17.834s)
     - `app/runtime-monolith/target/bruno-b4/bruno.log` (4/4 passed : B401, B402, B403, B404, duration 11738 ms)
     - `app/runtime-monolith/target/bruno-b4/bruno.json` (rapport structuré Bruno complet)
   - **Horodatage :** 2026-11-05T14:33:00+01:00
   - **Statut :** **PASS observé et attribué**.

### 4.2 Preuves historiques réutilisées (Campagne initiale du 5 novembre 2026, 13:40–14:12 CET)
Ces preuves ont été établies sur le SHA `ca21034f`/`d722e9d5`. Leurs hypothèses et le code sous test étant strictement inchangés par les corrections locales d'AUD-I4-01 et AUD-I4-02, elles sont conservées sans ré-exécution (préservant ainsi l'enveloppe temporelle, en particulier l'attente de 180 s de lease de topologie) :

1. **`InvitationAttachmentTest` (3 tests) :**
   - **Rapport XML :** `app/domain-pot/target/surefire-reports/TEST-com.kartaguez.pocoma.domain.pot.v3.InvitationAttachmentTest.xml`
   - **Résultat :** 3 tests, 0 failures, 0 errors, time 0.039s (13:57 CET).
   - **Statut :** **PASS historique réutilisé**.

2. **`V3InvitationReadPostgresTest` (3 tests) :**
   - **Rapport XML :** `app/runtime-monolith/target/surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationReadPostgresTest.xml`
   - **Résultat :** 3 tests, 0 failures, 0 errors, time 28.327s (14:07 CET).
   - **Statut :** **PASS historique réutilisé**.

3. **`V3InvitationTopologyPostgresTest` (2 tests incluant le renouvellement de lease T4-L de 180s) :**
   - **Rapport XML :** `app/runtime-monolith/target/surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.xml`
   - **Résultat :** 2 tests, 0 failures, 0 errors, time 207.42s (14:12 CET).
   - **Statut :** **PASS historique réutilisé**.

4. **`V3AdmissionFlowPostgresTest#voluntaryDeparturePreservesHistoryAndIdentity` (1 test) :**
   - **Rapport XML :** `app/runtime-monolith/target/surefire-reports/TEST-com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.xml`
   - **Résultat :** 1 test, 0 failures, 0 errors, time 19.187s (13:40 CET).
   - **Statut :** **PASS historique réutilisé**.

5. **`V3InvitationWritePostgresTest` (méthodes 1 à 5) :**
   - Établies lors de la campagne initiale à 14:06 CET (5 tests PASS, inchangés par la retouche de la méthode 6).
   - **Statut :** **PASS historique réutilisé**.

---

## 5. Contrôle complémentaire SQL — Non-régression des résultats Command

La revue statique et l'analyse dynamique des migrations `V10__invitation_history.sql`, `V10__current_invitation.sql`, de `JdbcPotCommandStore.java` et de `ExecutePotCommand.java` répondent de manière exhaustive aux 10 questions du mandat :

1. **Les résultats historiques "CHANGED", "UNCHANGED" et "REJECTED" conservent-ils leurs formes autorisées ?**
   - **OUI.** Dans `V10__invitation_history.sql` (trigger `v3_command_result_atomic_publication_step`), les vérifications pour `new.outcome in ('CHANGED', 'UNCHANGED', 'REJECTED')` restent strictement identiques. `CHANGED` impose l'existence d'une ligne dans `v3_pot_version`, d'un événement dans `v3_pot_event` avec ses composants dans `v3_pot_event_component`, et un numéro de version incrémenté. `UNCHANGED` et `REJECTED` interdisent tout événement de mutation `v3_pot_event`.
2. **"INVITATION_CREATED" est-il strictement réservé à "CreateInvitation" ?**
   - **OUI.** Le trigger `v3_invitation_check_unit` vérifie explicitement : `if c.status<>'TERMINAL' or c.operation<>'CreateInvitation' ... or not exists(select 1 from v3_command_result where request_id=c.request_id and outcome='INVITATION_CREATED')`. De plus, dans `ExecutePotCommand.java`, l'issue `INVITATION_CREATED` n'est produite que dans la branche dédiée `if (c.intent() instanceof CreateInvitation)`. Enfin, `v3_command_result_atomic_publication_step` interdit tout `v3_pot_event` pour cette issue et requiert l'existence exacte de la ligne dans `v3_invitation` et `v3_invitation_event`.
3. **Une commande Invitation réussie peut-elle produire un POT_EVENT ou un PotVersion ?**
   - **NON.** Pour `CreateInvitation`, `resultingPotVersion` est null, aucune ligne `v3_pot_version` n'est créée, et aucun événement `POT` n'est émis (`if (potEvent != null) position("POT", potEvent)` avec `potEvent == null`). La contrainte SQL `v3_command_result_atomic_publication_step` lève une exception `23514` (`'No-effect Command has mutation event'`) si un `v3_pot_event` était associé à un `CreateInvitation`. (Pour `AttachShareholderViaInvitation`, qui est une commande de mutation du Pot, elle produit bien `CHANGED` avec `POT_EVENT`, conformément au contrat de rattachement du Shareholder).
4. **Un résultat terminal peut-il exister sans son événement de complétion ?**
   - **NON.** Dans `JdbcPotCommandStore.java`, chaque appel à `finishTerminal(command, prepared.result())` est suivi dans la même transaction par l'insertion dans `v3_command_completion_event` et l'avancement du compteur `position("COMMAND_COMPLETION", completion)`. Les contraintes différées `v3_command_completion_unit` et `v3_command_counter_step` interdisent le commit si la complétion manque.
5. **Un événement Invitation peut-il être publié sans son histoire exacte ?**
   - **NON.** Le trigger différé `v3_invitation_check_unit` sur `v3_invitation` et `v3_invitation_event` vérifie que chaque `invitation_id` et `version` possède sa commande parente terminale `CreateInvitation`, son résultat `INVITATION_CREATED`, et sa position monotone continue dans `v3_event_position` pour la famille `'INVITATION'`.
6. **Une anomalie provoque-t-elle bien un rollback transactionnel ?**
   - **OUI.** Tout échec d'assertion métier, violation de contrainte SQL (définie avec `using errcode='23514'` ou `40001`), ou erreur de trigger différé (par exemple `v3_invitation_final_expiry` qui lève `40001`) provoque le rollback immédiat de la transaction PostgreSQL. Les tests de faute injectée (`rollbackAfterInvitationPositionKeepsDensePublication` et `expirationAfterPublicationCounterWait`) démontrent empiriquement le rollback total (0 invitation résiduelle, 0 événement résiduel, compteur non incrémenté).
7. **Les droits SQL runtime permettent-ils une modification ou une suppression illégitime ?**
   - **NON.** La migration `V10__invitation_history.sql` applique des révocations strictes : `revoke all on v3_invitation, v3_invitation_event from public, "${expectedRuntime}"; grant select, insert on v3_invitation, v3_invitation_event to "${expectedRuntime}";`. Aucun droit `UPDATE` ou `DELETE` n'est accordé. Les triggers `v3_invitation_no_truncate` et `v3_invitation_event_no_truncate` interdisent formellement tout `TRUNCATE`.
8. **Les contraintes différées conservent-elles les propriétés des commandes historiques ?**
   - **OUI.** Les triggers différés existants (`v3_command_completion_unit`, `v3_pot_event_unit`, `v3_event_counter_step`, etc.) restent actifs et inchangés. Les nouvelles contraintes ajoutées sont strictement additives et conditionnées aux opérations et familles `'INVITATION'`, sans modifier la sémantique des 11 commandes historiques.
9. **Les projections READ acceptent-elles les résultats Invitation sans rejeter les formes historiques valides ?**
   - **OUI.** La table `v3_current_invitation` est matérialisée indépendamment par la tâche de projection `MaterializeInvitation`. Les projections existantes (`CurrentPotRead`, `CurrentBindingRead`, `LatestKnownVersionRead`) n'écoutent que la famille `'POT'` et ignorent les événements de la famille `'INVITATION'`. De plus, les tests READ (`V3InvitationReadPostgresTest`, `V3InvitationStandaloneWebPostgresTest`) confirment le bon découplage et l'absence de régression.
10. **Les nouvelles vérifications SQL sont-elles compatibles avec les rôles et transactions réels ?**
    - **OUI.** Les rôles PostgreSQL configurés (`write`, `read`, `expectedRuntime`, etc.) et les niveaux d'isolation sont respectés. Les prévols de privilèges dans `V3InvitationTopologyPostgresTest#invitationPrivilegePreflightsRejectColumnEscalation` prouvent que les rôles réels de production disposent exactement des droits requis et qu'aucune escalade de colonnes ou de privilèges n'est permise.

---

## 6. Synthèse et statut terminal

- **Franchissement inattendu de tranche :** Aucun.
- **Portes globales :** `architecture-tests`, native reactor et B5 restent différées à Q5.
- **Full reactor :** Non exécuté (conforme à la politique).
- **Statut terminal de la mission :**
  `L5/I4 — TARGETED CORRECTIONS IMPLEMENTED — LOCAL VERIFICATION REPORTED — PENDING INDEPENDENT REVIEW`
