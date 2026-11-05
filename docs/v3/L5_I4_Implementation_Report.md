# L5/I4 — Invitation et désaffiliation volontaire

État : L5/I4 — POST-AUDIT DOCUMENTATION FINALIZED — READY FOR HUMAN CLOSURE REVIEW

## 1. Mandat et baseline

Le 5 novembre 2026, la réalisation de l'incrément I4 a été autorisée en mode :
`IMPLEMENTATION AUTHORIZED — TARGETED CORRECTIONS ONLY — NO CLOSURE`.

### Traçabilité Git des jalons
- **Baseline antérieure (clôture L5/I3) :** `ca21034f7873ed5c16f296665ae2aaf63d4090d7`
- **Introduction du code I4 :** `7a284405093c0c7593f8ca4f9ba70b5e605e1df3`
- **Commit initial audité :** `d722e9d5858c9e4eba5aa1883cf72988b463d780`
- **Corrections ciblées post-audit (AUD-I4-01 / AUD-I4-02) :** `2b1271d0ca3db6c6e9dfddd74f2b877cf4631dd2`
- **Publication du rapport post-audit initial :** `a087dd8b3f07e2c6116533f98b597f78080da5eb`

Arbitrages complémentaires intégrés :
- Trousseau AES-GCM dédié, configuration Spring externe existante, identifiant de clé dans le chiffré ; sauvegarde hors Git avec les bases et conservation des anciennes clés pour restauration. Aucun gestionnaire de secrets nouveau.
- `expiresAt` UTC explicite fourni par le client ; aucun TTL/plafond métier ajouté. Lien headless `pocoma://invitation/<token>`.
- Routes contractuelles conformes à SPEC-06 et au plan global L5 :
  - Restitution du lien : `GET /api/v3/invitations/by-creation-request/{requestId}`
  - Consultation des places libres : `POST /api/v3/invitations/available-shareholders/query` (token dans le corps).

---

## 2. Tranches de vérification déclarées (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` :

- **Tranche primaire :** WEB (`runtime-web-api`), vérifiée via `V3InvitationStandaloneWebPostgresTest`.
- **Ancre monolithique :** `runtime-monolith`, vérifiée sur les tests HTTP (`V3InvitationHttpTest`) et le test d'expiration (`V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait`).
- **Tranches secondaires réutilisées :** `domain-pot` (pur domaine), `runtime-monolith` (tests WRITE 1–5, READ 1–3, Topology 1–2, AdmissionFlow 1) dont les hypothèses restent inchangées.
- **Portes globales :** `architecture-tests`, native reactor et B5 restent différées à Q5.
- **Full reactor :** **NON EXÉCUTÉ** conformément à la politique (réservé comme intégration globale).

---

## 3. Traitement des observations d'audit

### AUD-I4-01 (MAJOR — Divergence des routes HTTP Invitation) — Verdict : RESOLVED
Les routes publiques d'invitation ont été alignées au SHA `2b1271d0` sur les exigences normatives du plan L5 et de SPEC-06 (§13, §14) :
- `GET /api/v3/invitations/by-creation-request/{requestId}`
- `POST /api/v3/invitations/available-shareholders/query`
Les contrôleurs, fixtures, tests HTTP, tests Web autonomes et scénarios Bruno B401–B404 ont été mis à jour sans aucun alias rétrocompatible.

### AUD-I4-02 (MINOR — Fragilité temporelle du test d'expiration) — Verdict : RESOLVED WITH MINOR RESERVATION
Le test `V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait` a été fiabilisé en supprimant le délai pré-soumission artificiel de 400 ms. La commande est postée immédiatement avec une date d'expiration à `clock_timestamp() + interval '4 seconds'`, entre dans l'attente sur le verrou `v3_event_counter` (vérifiée par `pg_stat_activity`), attend l'expiration réelle de l'horloge PostgreSQL, puis déclenche le trigger différé `v3_invitation_final_expiry` lors du commit de la porte, provoquant un rollback `40001` et un rejet propre en `INVALID_INVITATION`.

> **Formulation normative de la réserve :**
> Le test réduit fortement la sensibilité temporelle initiale et vérifie effectivement l'entrée dans l'attente transactionnelle. Un ralentissement exceptionnel avant ce point peut encore provoquer un échec de qualification ; il ne permet pas un faux PASS de l'oracle recherché.

### AUD-I4-03 (MODERATE — Provenance des preuves et inventaire) — Verdict : RESOLVED
L'inventaire complet des 22 méthodes JUnit et des 4 scénarios Bruno a été dressé, la matrice T4-A à T4-L a été corrigée, et l'ensemble des preuves Surefire XML et Bruno disponibles a été archivé dans `docs/testing/evidence/L5-I4/` avec leurs empreintes SHA-256.

---

## 4. Matrice de correspondance T4-A à T4-L et inventaire d'exécution

### Inventaire JUnit réel (22 méthodes + 4 scénarios Bruno)
- `InvitationAttachmentTest` (3 méthodes) : `occupiedDeletedAbsentAndAlreadyAttachedRemainRejected`, `mixedOrMissingDurableFactsAreTechnicalAnomalies`, `outsiderAndUnattachedCreatorAttachOnlyThemselvesWithOneDetailsRevision`
- `V3InvitationWritePostgresTest` (6 méthodes) : `atomicCreationAndIdempotence`, `creatorOnlyAndExpiredCreation`, `twoGuestsOnePlaceAndReusableToken`, `oneGuestCannotTakeTwoPlaces`, `rollbackAfterInvitationPositionKeepsDensePublication`, `expirationAfterPublicationCounterWait`
- `V3InvitationReadPostgresTest` (3 méthodes) : `reconstructOnFreshReadWithoutProducer`, `authenticatedEncryptionAndOldKeyRestoration`, `monotoneDuplicateAndDivergence`
- `V3InvitationHttpTest` (5 méthodes) : `confidentialLinkAndRestrictedPlaces`, `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads`, `concurrentUnlinkAndAttachmentSerializeAtIdentity`, `unlinkCannotPreserveStaleInvitationAuthority`, `brunoB4StandaloneCorpus`
- `V3InvitationTopologyPostgresTest` (2 méthodes) : `invitationPrivilegePreflightsRejectColumnEscalation`, `targetThreeFourPartitionsConnectionsRenewalAndRestart`
- `V3InvitationStandaloneWebPostgresTest` (2 méthodes) : `exactEuiBindingIndependenceAndMissingKey`, `restrictedPlacesWithoutWriteOrDecryption`
- `V3AdmissionFlowPostgresTest` (1 méthode) : `voluntaryDeparturePreservesHistoryAndIdentity`

### Matrice T4-A à T4-L et traçabilité des preuves

| Code plan | Méthode JUnit / Scénario | Classe | `SOURCE_SHA` | `TEST_RUN_SHA` | Preuve archivée dans `docs/testing/evidence/L5-I4/` | Statut |
|---|---|---|---|---|---|:---:|
| **T4-A** | `atomicCreationAndIdempotence` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par run ciblé) |
| **T4-B** | `creatorOnlyAndExpiredCreation` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par run ciblé) |
| **T4-C** | `twoGuestsOnePlaceAndReusableToken` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par run ciblé) |
| **T4-D** | `oneGuestCannotTakeTwoPlaces` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par run ciblé) |
| **T4-E** | `rollbackAfterInvitationPositionKeepsDensePublication` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par run ciblé) |
| **T4-F** | `expirationAfterPublicationCounterWait` | `V3InvitationWritePostgresTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | **PASS observé et attribué** |
| **T4-G** | `authenticatedEncryptionAndOldKeyRestoration` | `V3InvitationReadPostgresTest` | `7a284405` | NOT ESTABLISHED | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationReadPostgresTest.xml` | PASS historique réutilisé (archivé) |
| **T4-H & T4-I** | `confidentialLinkAndRestrictedPlaces` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` | **PASS observé et attribué** |
| **T4-J** | `unlinkCannotPreserveStaleInvitationAuthority` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` | **PASS observé et attribué** |
| **T4-K** | `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` | **PASS observé et attribué** |
| **T4-L** | `targetThreeFourPartitionsConnectionsRenewalAndRestart` | `V3InvitationTopologyPostgresTest` | `7a284405` | NOT ESTABLISHED | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.xml` | PASS historique réutilisé (archivé) |
| **B401–B404**| `brunoB4StandaloneCorpus` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `bruno-b4/bruno.log` + `bruno.json` | **PASS observé et attribué** |
| **WEB-1**| `exactEuiBindingIndependenceAndMissingKey` | `V3InvitationStandaloneWebPostgresTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml` | **PASS observé et attribué** |
| **WEB-2**| `restrictedPlacesWithoutWriteOrDecryption` | `V3InvitationStandaloneWebPostgresTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml` | **PASS observé et attribué** |
| **DEP-1**| `voluntaryDeparturePreservesHistoryAndIdentity` | `V3AdmissionFlowPostgresTest` | `7a284405` | NOT ESTABLISHED | `surefire-reports/TEST-com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.xml` | PASS historique réutilisé (archivé) |

---

## 5. Contrôle complémentaire SQL — Non-régression des résultats Command

La revue statique et dynamique des migrations `V10__invitation_history.sql`, `V10__current_invitation.sql`, de `JdbcPotCommandStore.java` et de `ExecutePotCommand.java` établit :

1. **Formes historiques "CHANGED", "UNCHANGED" et "REJECTED" :**
   - **Garantie établie par inspection statique et soutenue par tests d'intégration historiques.**
   - Dans `V10__invitation_history.sql`, la contrainte `v3_command_result_shape` remplace `v3_command_result_check` pour autoriser `INVITATION_CREATED` tout en maintenant strictement les formes historiques (`CHANGED`/`UNCHANGED` imposent `rejection is null` et `resulting_pot_version is not null` ; `REJECTED` impose `rejection is not null` et `resulting_pot_version is null`).
   - La fonction `v3_require_command_unit()` (remplaçant la version V8) vérifie que `CHANGED` impose l'existence d'une ligne dans `v3_pot_version`, un événement dans `v3_pot_event` avec ses composants et un numéro de version incrémenté. `UNCHANGED` et `REJECTED` interdisent tout événement de mutation `v3_pot_event` (levée d'exception `23514`).
2. **Issue "INVITATION_CREATED" strictement réservée à "CreateInvitation" :**
   - **Garantie établie par inspection statique et démontrée par tests PostgreSQL.**
   - `v3_require_command_unit()` vérifie : `if c.operation='CreateInvitation' then if r.outcome not in ('INVITATION_CREATED','REJECTED') ... end if; elsif r.outcome='INVITATION_CREATED' then raise exception 'Invitation outcome on Pot operation' using errcode='23514'; end if;`.
   - Dans `V10__current_invitation.sql`, la contrainte `read_command_result_shape` sur la table `read_command_result` impose cette même exclusivité.
   - Dans `ExecutePotCommand.java`, l'issue n'est produite que dans la branche dédiée `CreateInvitation`.
3. **Absence de POT_EVENT ou de PotVersion pour une commande Invitation réussie :**
   - **Garantie établie par inspection statique et démontrée par `V3InvitationWritePostgresTest#atomicCreationAndIdempotence`.**
   - Pour `CreateInvitation`, `resultingPotVersion` est null, aucune ligne `v3_pot_version` n'est créée, et aucun événement `POT` n'est émis. Le trigger `v3_require_command_unit()` lève une exception `23514` (`'Invitation success without exact effect'`) si un `v3_pot_version` avec le writer xid courant existe ou si un `v3_pot_event` est présent. (Pour `AttachShareholderViaInvitation`, commande de mutation du Pot, elle produit bien `CHANGED` avec son `POT_EVENT`).
4. **Présence obligatoire de l'événement de complétion pour tout résultat terminal :**
   - **Garantie établie par inspection statique et triggers différés.**
   - Dans `JdbcPotCommandStore.java`, chaque appel à `finishTerminal` est suivi dans la même transaction par l'insertion dans `v3_command_completion_event` et l'avancement du compteur `position("COMMAND_COMPLETION", completion)`. La fonction `v3_require_command_unit()` vérifie l'existence de la complétion avant d'autoriser le commit.
5. **Publication d'un événement Invitation subordonnée à son histoire exacte :**
   - **Garantie établie par inspection statique et triggers de séquence.**
   - Le trigger différé `v3_invitation_unit` et `v3_invitation_event_unit`, exécutant `v3_invitation_check_unit()`, vérifient que chaque tuple `(invitation_id, version)` possède sa commande parente `CreateInvitation`, son résultat `INVITATION_CREATED` et sa position séquentielle continue dans `v3_event_position`.
6. **Rollback transactionnel systématique en cas d'anomalie :**
   - **Garantie démontrée par tests de faute injectée et d'expiration.**
   - Toute violation d'assertion ou contrainte différée (comme `v3_invitation_final_expiry` levant `40001`) provoque le rollback de la transaction PostgreSQL. Les tests `rollbackAfterInvitationPositionKeepsDensePublication` et `expirationAfterPublicationCounterWait` démontrent empiriquement le rollback total (0 ligne orpheline persistée).
7. **Interdiction des modifications ou suppressions illégitimes par les droits SQL runtime :**
   - **Garantie établie par inspection statique des migrations et vérifiée par prévols de privilèges.**
   - `V10__invitation_history.sql` applique des révocations strictes (`revoke all on v3_invitation, v3_invitation_event from public, "${expectedRuntime}"; grant select, insert on v3_invitation, v3_invitation_event to "${expectedRuntime}";`). Aucun droit `UPDATE` ou `DELETE` n'est accordé. Les triggers `v3_invitation_no_truncate` et `v3_invitation_event_no_truncate` interdisent `TRUNCATE`.
   - `V10__current_invitation.sql` applique les mêmes restrictions sur `read_current_invitation` (seules les colonnes `version`, `expires_at`, `protected_link` sont modifiables par le runtime via `read_invitation_monotone`).
   - Le test `V3InvitationTopologyPostgresTest#invitationPrivilegePreflightsRejectColumnEscalation` confirme l'absence d'escalade.
8. **Préservation des propriétés des commandes historiques par les contraintes différées :**
   - **Garantie établie par inspection statique et non-régression locale.**
   - V10 remplace la fonction partagée `v3_require_command_unit()` : les blocs de validation historiques (`PENDING`, `CHANGED`, `UNCHANGED`, `REJECTED`, consommation de slot) sont conservés à l'identique. Les branches ajoutées ne s'activent que pour `operation='CreateInvitation'` et `outcome='INVITATION_CREATED'`. Les triggers différés historiques `v3_command_unit` et `v3_command_result_unit` continuent de s'exécuter sur chaque commande.
9. **Acceptation des résultats Invitation par les projections READ sans régression sur les formes historiques :**
   - **Garantie établie par inspection statique et tests READ.**
   - La table matérialisée côté lecture est **`read_current_invitation`** (et non `v3_current_invitation`). Les projections existantes (`CurrentPotRead`, `CurrentBindingRead`, `LatestKnownVersionRead`) n'écoutent que la famille `'POT'` et ignorent `'INVITATION'`. La contrainte `read_command_result_shape` sur `read_command_result` accepte les formes historiques tout en intégrant `INVITATION_CREATED`.
10. **Compatibilité des vérifications SQL avec les rôles et transactions réels :**
    - **Garantie soutenue par les tests d'intégration multi-rôles et réservée pour homologation globale à Q5.**
    - Les rôles PostgreSQL `write`, `read`, `expectedRuntime` fonctionnent sur des schémas séparés. Les prévols de topologie et les tests multi-rôles s'exécutent avec succès. L'absence de régression globale sur l'ensemble des modules du réacteur reste une obligation réservée à la phase Q5/G5.

---

## 6. Bilan et statut terminal

- **Franchissement inattendu de tranche :** Aucun.
- **Portes globales :** `architecture-tests`, native reactor et B5 restent différées à Q5.
- **Full reactor :** Non exécuté (conforme à la politique).
- **Statut terminal de la mission :**
  ```
  L5/I4 — POST-AUDIT DOCUMENTATION FINALIZED — READY FOR HUMAN CLOSURE REVIEW
  ```
