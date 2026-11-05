# L5/I4 — Invitation et désaffiliation volontaire

État : COMPLÉTÉ — VÉRIFICATION VALIDÉE (preuves locales acquises, portes globales différées à Q5)

## Mandat et baseline

Le 5 novembre 2026, la demande humaine « Implémente ce plan » a autorisé la réalisation du plan opérationnel I4.
Branche : `feat/v3-architecture`. SHA de référence : `ca21034f7873ed5c16f296665ae2aaf63d4090d7`.

Arbitrages complémentaires intégrés :
- Trousseau AES-GCM dédié, configuration Spring externe existante, identifiant de clé dans le chiffré ; sauvegarde hors Git avec les bases et conservation des anciennes clés pour restauration. Aucun gestionnaire de secrets nouveau.
- `expiresAt` UTC explicite fourni par le client ; aucun TTL/plafond métier ajouté. Lien headless `pocoma://invitation/<token>` ; restitution READ par creationRequestId, consultation des places par POST avec token dans le corps.

## Tranches de vérification déclarées

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` :

- **Tranche primaire :** WEB (`runtime-web-api`)
- **Tranches secondaires :** COMMAND (`runtime-command-consumption-worker`), PROJECTION (`runtime-task-consumption-worker`)
- **Ancre complémentaire :** `runtime-monolith` (composition monolithique et validation des invariants d'intégration)
- **Niveau d'intégration :** INTEGRATION avec conteneur PostgreSQL dédié (Testcontainers) et bases CURRENT V3 WRITE et READ séparées.
- **Portes globales :** `architecture-tests`, full natif unique/G5, B5 et certification SPEC-10 restent **REQUIRED, NOT RUN**, différées à la phase globale Q5/G5.
- **Full reactor :** **NON EXÉCUTÉ** conformément à la politique (réservé comme preuve d'intégration globale à Q5).

## Commandes exécutées et résultats observés

### 1. Preuve pure domaine (`domain-pot`)
```sh
./mvnw -pl domain-pot -am test -Dtest=InvitationAttachmentTest -Dsurefire.failIfNoSpecifiedTests=false
```
- Résultat : **SUCCESS** (3 tests, 0 échec, 0 erreur).

### 2. Preuve WRITE et atomicité (`runtime-monolith`)
```sh
./mvnw -pl runtime-monolith -am test -Dtest=V3InvitationWritePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```
- Tests exécutés (6/6) :
  - `atomicCreationAndIdempotence` (T4-A)
  - `creatorOnlyAndExpiredCreation` (T4-B)
  - `twoGuestsOnePlaceAndReusableToken` (T4-C)
  - `oneGuestCannotTakeTwoPlaces` (T4-D)
  - `rollbackAfterInvitationPositionKeepsDensePublication` (T4-E)
  - `expirationAfterPublicationCounterWait` (T4-F)
- Résultat : **SUCCESS** (6 tests, 0 échec, 0 erreur, temps 74.12s).

### 3. Preuve READ et cryptographique (`runtime-monolith`)
```sh
./mvnw -pl runtime-monolith -am test -Dtest=V3InvitationReadPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```
- Tests exécutés (4/4) :
  - `monotoneDuplicateReceiptAndProjectionReconstructionWithoutProducer`
  - `authenticatedEncryptionAndOldKeyRestoration` (T4-G)
  - `unauthenticatedDecryptionRejected`
  - `confidentialRetrievalRestrictedToCreator`
- Résultat : **SUCCESS** (4 tests, 0 échec, 0 erreur, temps 32.49s).

### 4. Preuve HTTP et corpus Bruno B4 (`runtime-monolith`)
```sh
./mvnw -pl runtime-monolith -am test -Dtest=V3InvitationHttpTest -Dsurefire.failIfNoSpecifiedTests=false
```
- Tests exécutés (5/5) :
  - `creatorRetrievesLinkAndOthers404` (T4-H)
  - `guestBrowsesFreePlacesAndOthers404` (T4-I)
  - `unlinkCannotPreserveStaleInvitationAuthority` (T4-J)
  - `finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` (T4-K)
  - `brunoB4StandaloneCorpus` (scénarios B401, B402, B403, B404 validés via CLI Bruno local 4.3.0)
- Résultat : **SUCCESS** (5 tests, 0 échec, 0 erreur, temps 68.38s).

### 5. Preuve Topologie et Ressources (`runtime-monolith`)
```sh
./mvnw -pl runtime-monolith -am test -Dtest=V3InvitationTopologyPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```
- Tests exécutés (3/3) :
  - `invitationPrivilegePreflightsRejectColumnEscalation`
  - `activeTopologyMaintainsConfiguredLimitsAndTargetReadPartitions`
  - `leaseNaturalExpiryAndRestartPreservesPartitions` (T4-L : topologie 3 monolith READ + 4 PROJECTION READ = 7 participants READ, 4 partitions couvertes, pool maximal 65 connexions, expiration naturelle du bail de 180s et reprise sans partition orpheline)
- Résultat : **SUCCESS** (3 tests, 0 échec, 0 erreur, temps 213.0s).

### 6. Preuve Désaffiliation Volontaire (`runtime-monolith`)
```sh
./mvnw -pl runtime-monolith -am test -Dtest=V3AdmissionFlowPostgresTest#voluntaryDeparturePreservesHistoryAndIdentity -Dsurefire.failIfNoSpecifiedTests=false
```
- Validation du contrat existant `UpdateShareholderDetails` (`attachment=DETACH`, `userId=null`) :
  - Autorisation par l'utilisateur affilié ou par le créateur.
  - Conservation du Shareholder (état libre, non supprimé).
  - Préservation intégrale des Expenses et soldes historiques.
  - Départ du dernier affilié autorisé sans suppression du Pot.
  - Préservation des droits indépendants du créateur et des EUI/bindings.
- Résultat : **SUCCESS** (1 test, 0 échec, 0 erreur, temps 21.32s).

### 7. Preuve Tranche Web autonome (`runtime-web-api`)
```sh
./mvnw -pl runtime-web-api -am test -Dtest=V3InvitationStandaloneWebPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```
- Résultat : **SUCCESS** (2 tests, 0 échec, 0 erreur, temps 5.18s).

### 8. Vérification canonique complète de la tranche I4 (§7.3 du plan)
```sh
./mvnw -pl runtime-web-api,runtime-command-consumption-worker,runtime-task-consumption-worker,runtime-monolith -am test -Dtest=V3InvitationWritePostgresTest,V3InvitationReadPostgresTest,V3InvitationHttpTest,V3InvitationTopologyPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```
- Résultat : **BUILD SUCCESS** (temps total : 06:14 min, 0 échec, 0 erreur sur l'ensemble des modules de la tranche).

## Ajustements techniques apportés lors de la finalisation

1. **`V3InvitationController` :**
   - Ajout d'un `@ExceptionHandler(Exception.class)` traduisant les exceptions `V3ReadTransport.Failure` en statuts HTTP appropriés (400, 404, 503) pour éviter que Tomcat ne serve la page d'erreur 404 générique.
2. **`V3InvitationFixture` :**
   - Ajout de `Command=4` dans `pocoma.v3.worker.consumption-limits` pour autoriser l'exécution concurrente de deux commandes lors du test de collision.
   - Configuration de `min-poll=5` et `max-poll=25` (valeurs en millisecondes requises par `V3ConsumptionWorkerSettings`).
   - Configuration de `pocoma.v3.worker.lifecycle.retry-delay-ms=0` afin que les commandes en collision soient rejouées immédiatement à la libération des verrous sans attendre le délai par défaut de 30 secondes.
3. **`V3InvitationHttpTest` :**
   - Normalisation absolue des chemins de fichiers transmis à l'exécutable Bruno (`requests.jsonl`, `bruno.json`, `bruno.log`).
   - Ajout de `Files.deleteIfExists` avant le lancement de Bruno pour garantir l'idempotence des réceptions JSONL entre exécutions successives.
4. **`V3InvitationWritePostgresTest` :**
   - Calibrage du timing dans `expirationAfterPublicationCounterWait` à `expiry.minusMillis(400)` pour s'insérer proprement dans la borne `lock_timeout=500ms` de PostgreSQL tout en garantissant que la commande entre en attente de verrou avant l'expiration.

## Bilan de conformité

- **Franchissement inattendu de tranche :** Aucun.
- **Portes globales :** `architecture-tests`, native reactor et B5 restent différées à Q5.
- **Full reactor :** Non exécuté (conforme à la politique).
- **Invariants financiers et structurels :** Intégralement préservés.
