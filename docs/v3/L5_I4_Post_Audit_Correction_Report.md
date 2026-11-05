# Rapport d'implémentation — Corrections ciblées post-audit (L5/I4)

---

## 1. Identification

- **Dépôt :** `kartaguez/pocoma`
- **Branche :** `feat/v3-architecture`
- **SHA de référence initial :** `d722e9d5858c9e4eba5aa1883cf72988b463d780`
- **SHA d'arrivée (code corrigé) :** `2b1271d0ca3db6c6e9dfddd74f2b877cf4631dd2`
- **Mode de mission :** `IMPLEMENTATION AUTHORIZED — TARGETED CORRECTIONS ONLY — NO CLOSURE`
- **Statut terminal exact :**
  ```
  L5/I4 — TARGETED CORRECTIONS IMPLEMENTED — LOCAL VERIFICATION REPORTED — PENDING INDEPENDENT REVIEW
  ```

---

## 2. Traitement d'AUD-I4-01 (MAJOR — Divergence des routes HTTP Invitation)

### Décision normative retenue et justification
Le plan directeur `docs/v3/L5_Supra_Entry_Audit_and_Implementation_Plan.md` (lignes 142–143 et 787–788) ainsi que la spécification contractuelle SPEC-06 (§13 et §14) constituent la référence normative applicable.
Aucune décision d'architecture ultérieure n'a ratifié les chemins non conformes `/by-request/{requestId}` et `/places`.
Les routes contractuelles obligatoires ont donc été strictement rétablies :
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

### Diagnostic technique précis
Dans `V3InvitationWritePostgresTest.java`, la méthode `expirationAfterPublicationCounterWait` attendait artificiellement `expiry.minusMillis(400)` avant d'envoyer la commande `/commands`.
Ce délai de 400 ms reposait sur la supposition erronée que la DataSource métier appliquait un `lock_timeout=500ms`. Or, la configuration productive (`V3ConsumptionWorkerConfiguration`) configure pour le pool métier `write-business` :
`-c statement_timeout=60000 -c transaction_timeout=65000` (aucun `lock_timeout` court).
Sous charge machine, les phases d'admission HTTP, d'écriture en base et de notification du worker pouvaient excéder 400 ms : `clock_timestamp()` dépassait alors `expiry` avant l'exécution de `ExecutePotCommand`, conduisant à un rejet immédiat (`unexpired(expiry) == false`) sans passage par le point de verrouillage `v3_pot_version` / `v3_event_counter`.

### Solution déterministe retenue
1. **Élimination de la fenêtre de course :** La commande est soumise immédiatement après la réservation du compteur par la connexion `gate` (`select position from v3_event_counter where family='COMMAND_COMPLETION' for update`), avec une date d'expiration fixée à `clock_timestamp() + interval '4 seconds'`.
2. **Entrée garantie en phase critique :** Bénéficiant de plusieurs secondes de marge, la commande franchit systématiquement la vérification amont `unexpired(expiry)`, applique les mutations sur `v3_shareholder_details` et `v3_pot_version`, insère `v3_command_result` (ce qui arme le trigger différé), puis bloque sur l'avancement du compteur `COMMAND_COMPLETION`.
3. **Synchronisation déterministe :**
   - Le test observe dans `pg_stat_activity` que le worker est en attente (`wait_event_type='Lock'`) sur `v3_event_counter` tout en détenant un `RowExclusiveLock` sur `v3_pot_version`.
   - Le test attend ensuite que l'horloge PostgreSQL atteigne `clock_timestamp() >= expiry`.
   - Le test valide `gate.commit()`.
4. **Déclenchement du trigger différé et rollback :** À la libération du verrou, le worker tente de committer sa transaction. Le trigger différé `v3_invitation_final_expiry` s'exécute à l'instant du commit physique, constate l'expiration, et lève l'exception `40001` (`'Invitation expired before final commit'`).
5. **Vérification terminale :** La transaction est rollbackée dans son intégralité. Le worker réessaie la commande, qui est alors rejetée de façon déterministe en `INVALID_INVITATION`. Le test vérifie qu'aucun shareholder n'a été rattaché, que le compteur de version n'a pas augmenté et qu'aucun `v3_pot_event` n'a été publié.

### Preuve d'exécution
- **Méthode :** `V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait`
- **Résultat :** PASS en 15.86s (1 test, 0 failure, 0 error)
- **Rapport Surefire :** `app/runtime-monolith/target/surefire-reports/TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml`
- **Traces confirmées :** Présence de la trace PostgreSQL `"Invitation expired before final commit"` dans le conteneur.

---

## 4. Traitement d'AUD-I4-03 (MODERATE — Provenance des preuves et inventaire des méthodes)

### Inventaire exhaustif des méthodes JUnit réellement présentes
Le code sous test comporte exactement **22 méthodes JUnit** et **4 scénarios Bruno** :

| Classe | Module | Nombre de méthodes | Noms réels des méthodes dans le code |
|---|---|:---:|---|
| `InvitationAttachmentTest` | `domain-pot` | 3 | `occupiedDeletedAbsentAndAlreadyAttachedRemainRejected`, `mixedOrMissingDurableFactsAreTechnicalAnomalies`, `outsiderAndUnattachedCreatorAttachOnlyThemselvesWithOneDetailsRevision` |
| `V3InvitationWritePostgresTest` | `runtime-monolith` | 6 | `atomicCreationAndIdempotence`, `creatorOnlyAndExpiredCreation`, `twoGuestsOnePlaceAndReusableToken`, `oneGuestCannotTakeTwoPlaces`, `rollbackAfterInvitationPositionKeepsDensePublication`, `expirationAfterPublicationCounterWait` |
| `V3InvitationReadPostgresTest` | `runtime-monolith` | 3 | `reconstructOnFreshReadWithoutProducer`, `authenticatedEncryptionAndOldKeyRestoration`, `monotoneDuplicateAndDivergence` |
| `V3InvitationHttpTest` | `runtime-monolith` | 5 | `confidentialLinkAndRestrictedPlaces`, `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads`, `concurrentUnlinkAndAttachmentSerializeAtIdentity`, `unlinkCannotPreserveStaleInvitationAuthority`, `brunoB4StandaloneCorpus` |
| `V3InvitationTopologyPostgresTest` | `runtime-monolith` | 2 | `invitationPrivilegePreflightsRejectColumnEscalation`, `targetThreeFourPartitionsConnectionsRenewalAndRestart` |
| `V3InvitationStandaloneWebPostgresTest` | `runtime-web-api` | 2 | `exactEuiBindingIndependenceAndMissingKey`, `restrictedPlacesWithoutWriteOrDecryption` |
| `V3AdmissionFlowPostgresTest` | `runtime-monolith` | 1 | `voluntaryDeparturePreservesHistoryAndIdentity` |

### Matrice corrigée T4-A à T4-L et traçabilité

| Code plan | Méthode JUnit / Scénario | Classe | Nature de la preuve | Statut |
|---|---|---|---|:---:|
| **T4-A** | `atomicCreationAndIdempotence` | `V3InvitationWritePostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | PASS historique réutilisé |
| **T4-B** | `creatorOnlyAndExpiredCreation` | `V3InvitationWritePostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | PASS historique réutilisé |
| **T4-C** | `twoGuestsOnePlaceAndReusableToken` | `V3InvitationWritePostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | PASS historique réutilisé |
| **T4-D** | `oneGuestCannotTakeTwoPlaces` | `V3InvitationWritePostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | PASS historique réutilisé |
| **T4-E** | `rollbackAfterInvitationPositionKeepsDensePublication` | `V3InvitationWritePostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` | PASS historique réutilisé |
| **T4-F** | `expirationAfterPublicationCounterWait` | `V3InvitationWritePostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml` (14:31 CET) | **PASS observé et attribué** |
| **T4-G** | `authenticatedEncryptionAndOldKeyRestoration` | `V3InvitationReadPostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationReadPostgresTest.xml` (14:07 CET) | PASS historique réutilisé |
| **T4-H & T4-I** | `confidentialLinkAndRestrictedPlaces` | `V3InvitationHttpTest` | `TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` (14:33 CET) | **PASS observé et attribué** |
| **T4-J** | `unlinkCannotPreserveStaleInvitationAuthority` | `V3InvitationHttpTest` | `TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` (14:33 CET) | **PASS observé et attribué** |
| **T4-K** | `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` | `V3InvitationHttpTest` | `TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml` (14:33 CET) | **PASS observé et attribué** |
| **T4-L** | `targetThreeFourPartitionsConnectionsRenewalAndRestart` | `V3InvitationTopologyPostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.xml` (14:12 CET) | PASS historique réutilisé |
| **B401–B404**| `brunoB4StandaloneCorpus` | `V3InvitationHttpTest` | `app/runtime-monolith/target/bruno-b4/bruno.log` (14:33 CET) | **PASS observé et attribué** |
| **WEB-1**| `exactEuiBindingIndependenceAndMissingKey` | `V3InvitationStandaloneWebPostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml` (14:31 CET) | **PASS observé et attribué** |
| **WEB-2**| `restrictedPlacesWithoutWriteOrDecryption` | `V3InvitationStandaloneWebPostgresTest` | `TEST-com.kartaguez.pocoma.V3InvitationStandaloneWebPostgresTest.xml` (14:31 CET) | **PASS observé et attribué** |
| **DEP-1**| `voluntaryDeparturePreservesHistoryAndIdentity` | `V3AdmissionFlowPostgresTest` | `TEST-com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.xml` (13:40 CET) | PASS historique réutilisé |

---

## 5. Contrôle complémentaire SQL — Non-régression des résultats Command

1. **Les résultats historiques "CHANGED", "UNCHANGED" et "REJECTED" conservent-ils leurs formes autorisées ?**
   - **OUI.** Dans `V10__invitation_history.sql` (trigger `v3_command_result_atomic_publication_step`), les vérifications pour ces trois issues demeurent inchangées : `CHANGED` exige strictement la création d'un `v3_pot_version`, d'un `v3_pot_event` avec ses composants et l'incrémentation monotone de version. `UNCHANGED` et `REJECTED` interdisent tout événement de mutation.
2. **"INVITATION_CREATED" est-il strictement réservé à "CreateInvitation" ?**
   - **OUI.** Le trigger `v3_invitation_check_unit` vérifie explicitement : `if c.status<>'TERMINAL' or c.operation<>'CreateInvitation' ... or not exists(select 1 from v3_command_result where request_id=c.request_id and outcome='INVITATION_CREATED')`. De plus, dans `ExecutePotCommand.java`, l'issue `INVITATION_CREATED` n'est produite que dans la branche dédiée à `CreateInvitation`.
3. **Une commande Invitation réussie peut-elle produire un POT_EVENT ou un PotVersion ?**
   - **NON.** Pour `CreateInvitation`, `resultingPotVersion` est `null`, aucune ligne n'est insérée dans `v3_pot_version`, et aucun événement `POT` n'est publié. Le trigger `v3_command_result_atomic_publication_step` lève une exception `23514` (`'No-effect Command has mutation event'`) si un `v3_pot_event` était associé à `CreateInvitation`. (Pour `AttachShareholderViaInvitation`, commande de mutation du Pot, elle produit bien `CHANGED` avec son `POT_EVENT`, conformément au contrat de rattachement).
4. **Un résultat terminal peut-il exister sans son événement de complétion ?**
   - **NON.** Dans `JdbcPotCommandStore.java`, l'insertion de l'événement de complétion dans `v3_command_completion_event` et l'avancement du compteur `position("COMMAND_COMPLETION", completion)` sont exécutés dans la même transaction physique que l'enregistrement du résultat terminal. Les contraintes différées `v3_command_completion_unit` et `v3_command_counter_step` interdisent le commit si la complétion fait défaut.
5. **Un événement Invitation peut-il être publié sans son histoire exacte ?**
   - **NON.** Le trigger différé `v3_invitation_check_unit` sur `v3_invitation` et `v3_invitation_event` garantit que chaque tuple `(invitation_id, version)` possède sa commande parente `CreateInvitation`, son résultat `INVITATION_CREATED` et sa position séquentielle continue dans `v3_event_position`.
6. **Une anomalie provoque-t-elle bien un rollback transactionnel ?**
   - **OUI.** Toute anomalie métier, violation de contrainte SQL ou levée d'exception par un trigger différé (par exemple `v3_invitation_final_expiry` levant `40001`) provoque le rollback total de la transaction PostgreSQL. Les tests de faute injectée (`rollbackAfterInvitationPositionKeepsDensePublication` et `expirationAfterPublicationCounterWait`) prouvent empiriquement qu'aucun état partiel n'est persisté.
7. **Les droits SQL runtime permettent-ils une modification ou une suppression illégitime ?**
   - **NON.** La migration applique des révocations strictes : `revoke all on v3_invitation, v3_invitation_event from public, "${expectedRuntime}"; grant select, insert on v3_invitation, v3_invitation_event to "${expectedRuntime}";`. Aucun droit `UPDATE` ou `DELETE` n'est conféré, et les triggers `v3_invitation_no_truncate` et `v3_invitation_event_no_truncate` empêchent tout `TRUNCATE`.
8. **Les contraintes différées conservent-elles les propriétés des commandes historiques ?**
   - **OUI.** Les triggers différés historiques (`v3_command_completion_unit`, `v3_pot_event_unit`, `v3_event_counter_step`) sont conservés sans altération. Les nouvelles contraintes sont strictement additives et restreintes aux opérations et à la famille `'INVITATION'`.
9. **Les projections READ acceptent-elles les résultats Invitation sans rejeter les formes historiques valides ?**
   - **OUI.** La table `v3_current_invitation` est matérialisée de manière autonome par la tâche `MaterializeInvitation`. Les projections existantes (`CurrentPotRead`, `CurrentBindingRead`, `LatestKnownVersionRead`) n'écoutent que la famille `'POT'` et ignorent les événements d'invitation.
10. **Les nouvelles vérifications SQL sont-elles compatibles avec les rôles et transactions réels ?**
    - **OUI.** Les rôles PostgreSQL configurés (`write`, `read`, `expectedRuntime`) et les niveaux d'isolation sont respectés. Le test `V3InvitationTopologyPostgresTest#invitationPrivilegePreflightsRejectColumnEscalation` prouve que les prévols d'autorisation s'exécutent avec succès et qu'aucune escalade de colonnes ou de privilèges n'est admise.

---

## 6. Bilan de vérification (Reactor Verification Policy)

### Tranches déclarées et commandes exécutées
- **Tranches invalidées et re-vérifiées :**
  1. `runtime-web-api` :
     ```sh
     ./mvnw -pl runtime-web-api -am test -Dtest=V3InvitationStandaloneWebPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
     ```
     *Résultat :* 2 tests exécutés, 0 échec, 0 erreur, temps 7.116s (horodatage 14:31 CET).
  2. `runtime-monolith` :
     ```sh
     ./mvnw -pl runtime-monolith -am test -Dtest=V3InvitationHttpTest,V3InvitationWritePostgresTest#expirationAfterPublicationCounterWait -Dsurefire.failIfNoSpecifiedTests=false
     ```
     *Résultat :* 6 tests exécutés (5 HTTP + 1 Write), 0 échec, 0 erreur, temps 01:27 min (horodatage 14:33 CET). Les 4 scénarios Bruno B401–B404 ont été ré-exécutés et validés avec succès via `V3InvitationHttpTest#brunoB4StandaloneCorpus`.

### Non-exécution des portes globales et du full reactor
- Conformément à `docs/testing/Reactor_Verification_Policy.md`, les modifications apportées étant strictement circonscrites aux routes d'invitation et au timing d'un test spécifique, aucun franchissement de frontière d'architecture n'a eu lieu.
- La porte globale `architecture-tests` et le full reactor global ne sont pas requis au stade local et restent réservés à la phase d'intégration Q5/G5.
- Le test de renouvellement de lease de 180 s (`targetThreeFourPartitionsConnectionsRenewalAndRestart`) n'a pas été rejoué inutilement, sa preuve initiale restant pleinement valide.

### Budget de temps consommé
- Re-vérification `runtime-web-api` : 9.4s (dont 7.1s de tests).
- Re-vérification `runtime-monolith` : 87s (dont 67.7s HTTP + 15.9s Write).
- Total temps d'exécution Maven : **~1 minute 36 secondes** (très largement en-deçà de l'enveloppe autorisée de 10 minutes).

---

## 7. Statut terminal

```
L5/I4 — TARGETED CORRECTIONS IMPLEMENTED — LOCAL VERIFICATION REPORTED — PENDING INDEPENDENT REVIEW
```
