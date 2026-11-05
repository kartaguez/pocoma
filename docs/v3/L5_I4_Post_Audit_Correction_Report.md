# Rapport d'implémentation — Corrections ciblées post-audit (L5/I4)

---

## 1. Identification

- **Dépôt :** `kartaguez/pocoma`
- **Branche :** `feat/v3-architecture`
- **Baseline antérieure (I3 close) :** `ca21034f7873ed5c16f296665ae2aaf63d4090d7`
- **Introduction du code I4 :** `7a284405093c0c7593f8ca4f9ba70b5e605e1df3`
- **Commit initial audité :** `d722e9d5858c9e4eba5aa1883cf72988b463d780`
- **SHA des corrections techniques post-audit :** `2b1271d0ca3db6c6e9dfddd74f2b877cf4631dd2`
- **SHA de publication du rapport post-audit :** `a087dd8b3f07e2c6116533f98b597f78080da5eb`
- **Mode de mission :** `DOCUMENTATION-ONLY — NO IMPLEMENTATION — NO TEST REPLAY — NO CLOSURE`
- **Statut terminal exact :**
  ```
  L5/I4 — POST-AUDIT DOCUMENTATION FINALIZED — READY FOR HUMAN CLOSURE REVIEW
  ```

---

## 2. Traitement d'AUD-I4-01 (MAJOR — Divergence des routes HTTP Invitation)

- **Verdict :** **RESOLVED**

### Décision normative retenue et justification
Le plan directeur `docs/v3/L5_Supra_Entry_Audit_and_Implementation_Plan.md` (lignes 142–143 et 787–788) ainsi que la spécification contractuelle SPEC-06 (§13 et §14) constituent la référence normative applicable.
Aucune décision d'architecture ultérieure n'a ratifié les chemins non conformes `/by-request/{requestId}` et `/places`.
Les routes contractuelles obligatoires ont donc été strictement rétablies au SHA `2b1271d0` :
- **Restitution du lien confidentiel :** `GET /api/v3/invitations/by-creation-request/{requestId}`
- **Consultation des places libres :** `POST /api/v3/invitations/available-shareholders/query`

### Fichiers modifiés
1. `app/supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/v3/V3InvitationController.java` : correction des annotations `@GetMapping` et `@PostMapping`.
2. `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3InvitationFixture.java` : alignement des helpers `link(UUID)` et `places(...)`.
3. `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3InvitationHttpTest.java` : mise à jour des requêtes directes vers `/invitations/by-creation-request/`.
4. `app/runtime-web-api/src/test/java/com/kartaguez/pocoma/V3InvitationStandaloneWebPostgresTest.java` : mise à jour de l'ensemble des assertions de la tranche Web autonome vers les deux routes contractuelles.
5. `scripts/bruno-v3/lib/i4-invitation.js` : scénarios Bruno B401–B404 mis à jour vers les deux routes contractuelles.

### Preuve de non-régression et absence d'alias
- **Aucun alias rétrocompatible :** Les anciens chemins ne sont pas conservés et renvoient désormais uniformément un statut 404.
- **Invariants préservés :** Méthodes HTTP (`GET`/`POST`), payload JSON (`{"token":"..."}` pour les places), headers `X-Pocoma-Binding-Token`, masquage de l'existence (404 uniforme), en-tête `Cache-Control: no-store` et codes d'erreur (400, 401, 404, 503) intégralement validés par `V3InvitationStandaloneWebPostgresTest` (2 tests, 0 échec) et `V3InvitationHttpTest` (5 tests, 0 échec).

---

## 3. Traitement d'AUD-I4-02 (MINOR — Fragilité temporelle du test d'expiration)

- **Verdict :** **RESOLVED WITH MINOR RESERVATION**

### Diagnostic technique précis
Dans `V3InvitationWritePostgresTest.java`, la méthode `expirationAfterPublicationCounterWait` attendait artificiellement `expiry.minusMillis(400)` avant d'envoyer la commande `/commands`.
Ce délai de 400 ms reposait sur la supposition erronée que la DataSource métier appliquait un `lock_timeout=500ms`. Or, la configuration productive (`V3ConsumptionWorkerConfiguration`) configure pour le pool métier `write-business` :
`-c statement_timeout=60000 -c transaction_timeout=65000` (aucun `lock_timeout` court).
Sous charge machine, les phases d'admission HTTP, d'écriture en base et de notification du worker pouvaient excéder 400 ms : `clock_timestamp()` dépassait alors `expiry` avant l'exécution de `ExecutePotCommand`, conduisant à un rejet immédiat (`unexpired(expiry) == false`) sans passage par le point de verrouillage `v3_pot_version` / `v3_event_counter`.

### Solution technique et réserve explicite
1. **Élimination du délai pré-soumission :** La commande est soumise immédiatement après la réservation du compteur par la connexion `gate` (`select position from v3_event_counter where family='COMMAND_COMPLETION' for update`), avec une date d'expiration fixée à `clock_timestamp() + interval '4 seconds'`.
2. **Entrée dans la phase critique :** Bénéficiant de plusieurs secondes de marge, la commande passe les vérifications amont `unexpired(expiry)`, applique les mutations sur `v3_shareholder_details` et `v3_pot_version`, insère `v3_command_result` (ce qui arme le trigger différé), puis bloque sur l'avancement du compteur `COMMAND_COMPLETION`.
3. **Synchronisation :**
   - Le test observe dans `pg_stat_activity` que le worker est en attente (`wait_event_type='Lock'`) sur `v3_event_counter` tout en détenant un `RowExclusiveLock` sur `v3_pot_version`.
   - Le test attend ensuite que l'horloge PostgreSQL atteigne `clock_timestamp() >= expiry`.
   - Le test valide `gate.commit()`.
4. **Déclenchement du trigger différé et rollback :** À la libération du verrou, le worker tente de committer sa transaction. Le trigger différé `v3_invitation_final_expiry` s'exécute à l'instant du commit physique, constate l'expiration, et lève l'exception `40001` (`'Invitation expired before final commit'`).
5. **Vérification terminale :** La transaction est rollbackée dans son intégralité. Le worker réessaie la commande, qui est alors rejetée de façon déterministe en `INVALID_INVITATION`. Le test vérifie qu'aucun shareholder n'a été rattaché, que le compteur de version n'a pas augmenté et qu'aucun `v3_pot_event` n'a été publié.

> **Formulation normative de la réserve AUD-I4-02 :**
> Le test réduit fortement la sensibilité temporelle initiale et vérifie effectivement l'entrée dans l'attente transactionnelle. Un ralentissement exceptionnel avant ce point peut encore provoquer un échec de qualification ; il ne permet pas un faux PASS de l'oracle recherché.

### Preuve d'exécution
- **Méthode :** `V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait`
- **Résultat :** PASS en 15.86s (1 test, 0 failure, 0 error)
- **Rapport Surefire archivé :** `docs/testing/evidence/L5-I4/surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml`
- **SHA-256 :** `c83c06fc2d0eae4e3fa7e920df51634ca50884625b10a10c49d3d40e384f1f24`
- **`TEST_RUN_SHA` :** `2b1271d0ca3db6c6e9dfddd74f2b877cf4631dd2`
- **Traces confirmées :** Présence de la trace PostgreSQL `"Invitation expired before final commit"` dans le conteneur.

---

## 4. Traitement d'AUD-I4-03 (MODERATE — Provenance des preuves et inventaire des méthodes)

- **Verdict :** **RESOLVED**

### 4.1 Inventaire réel des méthodes JUnit vs inventaire initialement rapporté
Le code sous test comporte exactement **22 méthodes JUnit** et **4 scénarios Bruno** :

| Classe | Module | Nombre réel | Méthodes réellement présentes dans le code | Écart par rapport au rapport initial |
|---|---|:---:|---|---|
| `InvitationAttachmentTest` | `domain-pot` | 3 | `occupiedDeletedAbsentAndAlreadyAttachedRemainRejected`, `mixedOrMissingDurableFactsAreTechnicalAnomalies`, `outsiderAndUnattachedCreatorAttachOnlyThemselvesWithOneDetailsRevision` | Conforme (3 tests). |
| `V3InvitationWritePostgresTest` | `runtime-monolith` | 6 | `atomicCreationAndIdempotence`, `creatorOnlyAndExpiredCreation`, `twoGuestsOnePlaceAndReusableToken`, `oneGuestCannotTakeTwoPlaces`, `rollbackAfterInvitationPositionKeepsDensePublication`, `expirationAfterPublicationCounterWait` | Conforme (6 tests). |
| `V3InvitationReadPostgresTest` | `runtime-monolith` | 3 | `reconstructOnFreshReadWithoutProducer`, `authenticatedEncryptionAndOldKeyRestoration`, `monotoneDuplicateAndDivergence` | **Corrigé** : le rapport initial annonçait 4 méthodes fictives ; la classe réelle en contient 3. |
| `V3InvitationHttpTest` | `runtime-monolith` | 5 | `confidentialLinkAndRestrictedPlaces`, `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads`, `concurrentUnlinkAndAttachmentSerializeAtIdentity`, `unlinkCannotPreserveStaleInvitationAuthority`, `brunoB4StandaloneCorpus` | **Corrigé** : les noms rapportés `creatorRetrievesLinkAndOthers404` et `guestBrowsesFreePlacesAndOthers404` sont en réalité groupés dans `confidentialLinkAndRestrictedPlaces`. |
| `V3InvitationTopologyPostgresTest` | `runtime-monolith` | 2 | `invitationPrivilegePreflightsRejectColumnEscalation`, `targetThreeFourPartitionsConnectionsRenewalAndRestart` | **Corrigé** : le rapport initial annonçait 3 méthodes ; la classe réelle en compte 2 (dont le renouvellement de lease de 180s T4-L). |
| `V3InvitationStandaloneWebPostgresTest` | `runtime-web-api` | 2 | `exactEuiBindingIndependenceAndMissingKey`, `restrictedPlacesWithoutWriteOrDecryption` | Conforme (2 tests). |
| `V3AdmissionFlowPostgresTest` | `runtime-monolith` | 1 | `voluntaryDeparturePreservesHistoryAndIdentity` | Conforme (1 test). |

### 4.2 Matrice corrigée T4-A à T4-L et traçabilité Git

| Code plan | Méthode JUnit / Scénario | Classe | `SOURCE_SHA` | `TEST_RUN_SHA` | Fichier archivé dans `docs/testing/evidence/L5-I4/` | Statut de preuve |
|---|---|---|---|---|---|:---:|
| **T4-A** | `atomicCreationAndIdempotence` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par le run ciblé) |
| **T4-B** | `creatorOnlyAndExpiredCreation` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par le run ciblé) |
| **T4-C** | `twoGuestsOnePlaceAndReusableToken` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par le run ciblé) |
| **T4-D** | `oneGuestCannotTakeTwoPlaces` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par le run ciblé) |
| **T4-E** | `rollbackAfterInvitationPositionKeepsDensePublication` | `V3InvitationWritePostgresTest` | `7a284405` | NOT ESTABLISHED | - | PASS historique réutilisé (`EVIDENCE NOT AVAILABLE FOR INDEPENDENT INSPECTION` — XML écrasé par le run ciblé) |
| **T4-F** | `expirationAfterPublicationCounterWait` | `V3InvitationWritePostgresTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | **PASS observé et attribué** |
| **T4-G** | `authenticatedEncryptionAndOldKeyRestoration` | `V3InvitationReadPostgresTest` | `7a284405` | NOT ESTABLISHED | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationReadPostgresTest.xml` | PASS historique réutilisé (preuve archivée) |
| **T4-H & T4-I** | `confidentialLinkAndRestrictedPlaces` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` | **PASS observé et attribué** |
| **T4-J** | `unlinkCannotPreserveStaleInvitationAuthority` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` | **PASS observé et attribué** |
| **T4-K** | `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` | **PASS observé et attribué** |
| **T4-L** | `targetThreeFourPartitionsConnectionsRenewalAndRestart` | `V3InvitationTopologyPostgresTest` | `7a284405` | NOT ESTABLISHED | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.xml` | PASS historique réutilisé (preuve archivée, bail 180s non rejoué) |
| **B401–B404**| `brunoB4StandaloneCorpus` | `V3InvitationHttpTest` | `2b1271d0` | `2b1271d0` | `bruno-b4/bruno.log` + `bruno.json` | **PASS observé et attribué** |
| **WEB-1**| `exactEuiBindingIndependenceAndMissingKey` | `V3InvitationStandaloneWebPostgresTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml` | **PASS observé et attribué** |
| **WEB-2**| `restrictedPlacesWithoutWriteOrDecryption` | `V3InvitationStandaloneWebPostgresTest` | `2b1271d0` | `2b1271d0` | `surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml` | **PASS observé et attribué** |
| **DEP-1**| `voluntaryDeparturePreservesHistoryAndIdentity` | `V3AdmissionFlowPostgresTest` | `7a284405` | NOT ESTABLISHED | `surefire-reports/TEST-com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.xml` | PASS historique réutilisé (preuve archivée) |

---

## 5. Contrôle complémentaire SQL — Non-régression des résultats Command

La relecture directe au HEAD des migrations `V10__invitation_history.sql`, `V10__current_invitation.sql`, de `JdbcPotCommandStore.java` et de `ExecutePotCommand.java` permet de rectifier les dénominations d'objets et de qualifier rigoureusement les garanties :

1. **Les résultats historiques "CHANGED", "UNCHANGED" et "REJECTED" conservent-ils leurs formes autorisées ?**
   - **OUI.** *(Garantie établie par inspection statique et soutenue par les tests d'intégration historiques)*.
   - Dans `V10__invitation_history.sql`, la contrainte `v3_command_result_shape` remplace `v3_command_result_check` pour autoriser la 4e issue `INVITATION_CREATED` tout en maintenant strictement les formes historiques : `CHANGED` et `UNCHANGED` imposent `rejection is null` et `resulting_pot_version is not null` ; `REJECTED` impose `rejection is not null` et `resulting_pot_version is null`.
   - La fonction `v3_require_command_unit()` (déclenchée par les triggers différés `v3_command_unit` et `v3_command_result_unit`) continue d'exiger pour `CHANGED` l'existence exacte de l'événement dans `v3_pot_event`, ses composants dans `v3_pot_event_component` et la correspondance de version `subject_version=r.resulting_pot_version`. Pour toute issue sans mutation (`UNCHANGED`, `REJECTED`), l'existence d'un `v3_pot_event` lève l'exception `23514` (`'No-effect Command has mutation event'`).
2. **"INVITATION_CREATED" est-il strictement réservé à "CreateInvitation" ?**
   - **OUI.** *(Garantie établie par inspection statique et démontrée par tests PostgreSQL)*.
   - `v3_require_command_unit()` vérifie : `if c.operation='CreateInvitation' then if r.outcome not in ('INVITATION_CREATED','REJECTED') ... end if; elsif r.outcome='INVITATION_CREATED' then raise exception 'Invitation outcome on Pot operation' using errcode='23514'; end if;`.
   - Dans `V10__current_invitation.sql`, la contrainte `read_command_result_shape` sur la table `read_command_result` impose : `(operation='CreateInvitation' and write_outcome='INVITATION_CREATED' ...) or (operation<>'CreateInvitation' and write_outcome in ('CHANGED','UNCHANGED') ...)`.
   - Dans `ExecutePotCommand.java`, `Outcome.INVITATION_CREATED` n'est retourné que dans la branche `if (c.intent() instanceof CreateInvitation)`.
3. **Une commande Invitation réussie peut-elle produire un POT_EVENT ou un PotVersion ?**
   - **NON.** *(Garantie établie par inspection statique et vérifiée par `V3InvitationWritePostgresTest#atomicCreationAndIdempotence`)*.
   - Pour `CreateInvitation`, `v3_require_command_unit()` lève l'exception `23514` si un événement `v3_pot_event` existe ou si une version `v3_pot_version` avec `writer_xid=pg_current_xact_id()` a été insérée (`'Invitation success without exact effect'`).
   - (Pour `AttachShareholderViaInvitation`, commande de mutation du Pot, elle produit bien `CHANGED` avec son `POT_EVENT`, conformément au contrat de rattachement).
4. **Un résultat terminal peut-il exister sans son événement de complétion ?**
   - **NON.** *(Garantie établie par inspection statique et triggers différés)*.
   - Dans `v3_require_command_unit()`, si `c.status<>'PENDING'`, la fonction vérifie obligatoirement : `exists(select 1 from v3_command_completion_event e join v3_event_position p on p.event_id=e.event_id and p.family='COMMAND_COMPLETION' where e.subject_id=c.request_id)`. Dans `JdbcPotCommandStore.java`, l'insertion de complétion et l'avancement du compteur `COMMAND_COMPLETION` sont physiquement chaînés dans la même transaction physique.
5. **Un événement Invitation peut-il être publié sans son histoire exacte ?**
   - **NON.** *(Garantie établie par inspection statique et triggers de séquence)*.
   - Le trigger différé `v3_invitation_unit` (sur `v3_invitation`) et `v3_invitation_event_unit` (sur `v3_invitation_event`), exécutant `v3_invitation_check_unit()`, exigent que chaque tuple `(invitation_id, version)` soit rattaché à une commande terminale `CreateInvitation` ayant produit `INVITATION_CREATED` et publié dans `v3_event_position` pour la famille `'INVITATION'`.
6. **Une anomalie provoque-t-elle bien un rollback transactionnel ?**
   - **OUI.** *(Garantie démontrée par tests de faute injectée et d'expiration)*.
   - Toute violation d'intégrité ou contrainte différée (telle que `v3_invitation_final_expiry` levant `40001`) provoque le rollback de la transaction PostgreSQL. Les tests `rollbackAfterInvitationPositionKeepsDensePublication` et `expirationAfterPublicationCounterWait` démontrent empiriquement le rollback total (0 ligne orpheline dans `v3_invitation`, `v3_invitation_event` ou `v3_command_result`).
7. **Les droits SQL runtime permettent-ils une modification ou une suppression illégitime ?**
   - **NON.** *(Garantie établie par inspection statique des migrations et vérifiée par prévols de privilèges)*.
   - `V10__invitation_history.sql` applique : `revoke all on v3_invitation,v3_invitation_event from public,"${expectedRuntime}"; grant select,insert on v3_invitation,v3_invitation_event to "${expectedRuntime}";`. Aucun droit `UPDATE` ou `DELETE` n'est accordé. Les triggers `v3_invitation_no_truncate` et `v3_invitation_event_no_truncate` interdisent `TRUNCATE`.
   - `V10__current_invitation.sql` applique des restrictions similaires sur `read_current_invitation` (seules les colonnes `version`, `expires_at`, `protected_link` sont modifiables par le runtime sous contrôle du trigger `read_invitation_monotone`).
   - Le test `V3InvitationTopologyPostgresTest#invitationPrivilegePreflightsRejectColumnEscalation` confirme l'absence d'escalade.
8. **Les contraintes différées conservent-elles les propriétés des commandes historiques ?**
   - **OUI.** *(Garantie établie par inspection statique et non-régression locale)*.
   - La migration V10 remplace la fonction partagée `v3_require_command_unit()` : les blocs de validation historiques (`PENDING`, `CHANGED`, `UNCHANGED`, `REJECTED`, consommation de slot) sont conservés à l'identique. Les branches ajoutées ne s'activent que pour `operation='CreateInvitation'` et `outcome='INVITATION_CREATED'`. Les triggers différés historiques `v3_command_unit` et `v3_command_result_unit` continuent de s'exécuter sur chaque commande.
9. **Les projections READ acceptent-elles les résultats Invitation sans rejeter les formes historiques valides ?**
   - **OUI.** *(Garantie établie par inspection statique et tests READ)*.
   - La table matérialisée pour les invitations côté lecture est **`read_current_invitation`** (et non `v3_current_invitation`). Les projections existantes (`CurrentPotRead`, `CurrentBindingRead`, `LatestKnownVersionRead`) n'écoutent que la famille `'POT'` et ignorent la famille `'INVITATION'`. La contrainte `read_command_result_shape` sur `read_command_result` accepte les formes historiques tout en intégrant `INVITATION_CREATED`.
10. **Les nouvelles vérifications SQL sont-elles compatibles avec les rôles et transactions réels ?**
    - **OUI.** *(Garantie soutenue par les tests d'intégration multi-rôles et réservée pour homologation globale à Q5)*.
    - Les rôles PostgreSQL `write`, `read`, `expectedRuntime` fonctionnent sur des schémas séparés conformément à l'architecture V3. Les prévols de topologie et les tests multi-rôles s'exécutent avec succès. L'absence de régression globale sur l'ensemble des modules du réacteur reste une obligation réservée à la phase Q5/G5.

---

## 6. Bilan de vérification (Reactor Verification Policy)

- **Tranches déclarées et ré-exécutées au SHA `2b1271d0` :**
  - `runtime-web-api` : `V3InvitationStandaloneWebPostgresTest` (2 tests, 0 échec, durée 7.116s, PASS observé).
  - `runtime-monolith` : `V3InvitationHttpTest` (5 tests dont Bruno B401–B404, 0 échec, durée 67.68s, PASS observé) et `V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait` (1 test, 0 échec, durée 17.834s, PASS observé).
- **Tranches non ré-exécutées (preuves archivées ou réutilisées) :**
  - `domain-pot` (`InvitationAttachmentTest`, 3 tests)
  - `runtime-monolith` (`V3InvitationReadPostgresTest`, 3 tests ; `V3InvitationTopologyPostgresTest`, 2 tests ; `V3AdmissionFlowPostgresTest`, 1 test).
- **Portes globales :** `architecture-tests`, native reactor et B5 restent non requises à ce stade et différées à Q5.
- **Full reactor :** Non exécuté (conforme à la politique).
- **Présente intervention :** Strictement documentaire, aucune commande Maven, JUnit ou Docker exécutée.

---

## 7. Statut terminal

```
L5/I4 — POST-AUDIT DOCUMENTATION FINALIZED — READY FOR HUMAN CLOSURE REVIEW
```
