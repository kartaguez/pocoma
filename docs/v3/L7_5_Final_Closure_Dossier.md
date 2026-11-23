# Pocoma V3 — L7.5 — Dossier de Contre-Audit et de Clôture Technique Finale

- **Date** : 23 novembre 2026
- **Branche PR #12** : `fix/l7-5-ci-visibility-oracle`
- **Base `main` initiale** : `521ff27d2abe1c1f6bc18de7c95f0cfc877bf857` (*Merge T1A five-runtime topology and Core NATS wakeup into main*)
- **Commit correctif P0 (PR #12)** : `7761ecd04adb8e36c9c6ef2a5c3ae21a7e664037`
- **SHA code qualifié (L7.5)** : `fff035695a1361816e1473e5274ead43a62b563f` (**0 fichier de production `src/main/**`, migration SQL ou script `scripts/local/**` modifié par rapport à `main`**)
- **Verdict technique recommandé** : **`GO_LOCAL_WITH_RESERVES`**

> [!IMPORTANT]
> **Distinction normative L7 (local) vs L8 (production / infrastructure cible)** :
> - **Périmètre local L7** : **techniquement clos et certifié** (`1 221/1 221` tests Maven sur les 44 modules du réacteur, `52/52` `architecture-tests`, `143/143` tests CLI Python, CI PR #12 `45142597104` `8/8` jobs verts).
> - **Périmètre L8 (hors L7)** : configuration multi-VM `SPEC-09` Configuration A/B (`2 × 4 vCPU / 8 GiB` + Keycloak isolé), pointe `50 cmd/s`, volumes `10k/1M` et `100k/10M`, HA distribuée, clustering NATS 3 nœuds, TLS/mTLS et gestion des secrets de production. Aucune capacité L8 non démontrée n'est revendiquée comme certifiée.

---

## 1. État initial et restauration de la CI (P0)

### 1.1 Diagnostic de l'état initial
- `origin/main` (`521ff27d`) avait intégré T1A + Core NATS, mais la CI associée (`45140761571`) échouait à la compilation sous Java 21 (Temurin 21) sur `PotVisibilityReadStorePostgresTest.outOfOrderArrivalConvergesToIdenticalLogicalVisibility` en raison d'une ambiguïté de résolution de surcharge `Assertions.assertEquals` avec l'appel générique `tx.read(...)`.
- La PR #12 (`fix/l7-5-ci-visibility-oracle`, commit `7761ecd0`) a résolu l'ambiguïté en extrayant quatre variables locales typées `Optional<PotVisibility>` avant `assertEquals(...)`.

### 1.2 Vérification P0 (sans affaiblissement d'oracle)
- **Revue du diff `7761ecd0`** : aucune assertion supprimée ni affaiblie ; les deux assertions indépendantes (équivalence entre ordres d'arrivée `v1 → v2` vs `v2 → v1`, et égalité exacte avec la valeur attendue) sont intégralement préservées.
- **Compilation propre et test PostgreSQL 17 local** :
  `./mvnw --batch-mode --no-transfer-progress -pl infra-postgres-read -am clean test -Dtest=PotVisibilityReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false` → **`5/5 PASS`** (`9.930 s`, `0` échec, `0` erreur, `0` skip).
- **CI PR #12 (`45142597104` sur `7761ecd0`)** : **`8/8` jobs `success`** (`core-web-and-architecture`, `infra-postgres-write-discovery`, `infra-postgres-write-rest`, `worker-process-e2e`, `worker-process-rest`, `worker-materialize`, `monolith-resources`, `monolith-rest`).

---

## 2. Contre-audit indépendant des réserves (P1)

Le détail complet par obligation figure dans [`L7_5_Reservations_Register.md`](L7_5_Reservations_Register.md) et [`L7_I1_Certification_Coverage_Matrix.csv`](L7_I1_Certification_Coverage_Matrix.csv).

### 2.1 Défauts / écarts identifiés et corrections apportées (`fff03569`)
1. **`PG-REBUILD-EQ`, `RCV01-A`, `RCV01-N` (corpus de reconstruction READ sans `Invitation`)** :
   - **Cause** : `ReadReconstructionPostgresTest` vérifiait déjà `read_current_invitation` dans `readContent()` et `CURRENT_INVITATION_CHANGED` dans `assertAvailabilityMatchesWrite()`, mais n'émettait aucune commande `CreateInvitation` dans `rcv01A` ni dans `readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication` (`read_current_invitation` restait vide).
   - **Correction (test uniquement)** : ajout de `ReadRuntimeI8Fixture.produceInvitation(...)` (câblant `JdbcInvitationStore` + `InvitationCryptography`) et ajout de `PotCommand.CreateInvitation` dans `rcv01A_fullResetOnPopulatedRead_writeRemainsIntact` et `readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication`.
   - **Preuve** : `ReadReconstructionPostgresTest` **`13/13 PASS`** (`271.3 s` sous PostgreSQL 17).
2. **`INV-G` (place réclamée entre consultation READ et acceptation)** :
   - **Cause** : `InvitationHttpTest` couvrait déjà les courses d'acceptation concurrentes (`INV-F` : `l73_concurrentInvitationAcceptanceHasOneWinnerForOnePlace` et `l73_concurrentInvitationAcceptanceAttachesEachActorToTheirOwnPlace`, qui étaient restées non rapprochées dans la matrice) et le lag READ lors d'un `Unlink`, mais pas le cas séquentiel « consultation READ `GET /invitations/.../places` → réclamation de la place par un autre acteur → tentative d'acceptation sur place déjà prise ».
   - **Correction (test uniquement)** : ajout de `InvitationHttpTest.placeClaimedBetweenReadConsultationAndAcceptanceIsRejectedWithoutMutation` avec oracle sur la réponse HTTP (`REJECTED` / `INVALID_ATTACHMENT`) **et** sur les effets WRITE committés (`v3_shareholder_details.user_id` inchangé sur le gagnant, `0` nouvel événement `v3_pot_event`).
   - **Preuve** : `InvitationHttpTest` **`8/8 PASS`** (`67.19 s` sous PostgreSQL 17).

### 2.2 Réconciliation finale de la matrice (`522` obligations)

| Statut | Avant L7.5 | Après L7.5 (`fff03569`) | Delta |
|---|---:|---:|---:|
| `CURRENT_FULL` | 486 | **491** | **`+5`** (`PG-REBUILD-EQ`, `RCV01-A`, `RCV01-N`, `INV-F`, `INV-G`) |
| `HISTORICAL_REUSABLE` | 3 | **3** | 0 |
| `LOCAL_ENV_BOUNDED` | 17 | **17** | 0 |
| `CURRENT_PARTIAL` | 13 | **8** | **`-5`** (toutes les 8 qualifiées `ACCEPTED_WITH_RESERVE`) |
| `DEFERRED_TO_L8_INFRA` | 1 | **1** | 0 |
| `RETIRED_BY_HUMAN_DECISION` | 2 | **2** | 0 |
| `MISSING` | 0 | **0** | 0 |
| **Total** | **522** | **522** | — |

**`0` obligation `BLOCKING_L7` ouverte.**

---

## 3. Contrôle d'intégration T1A / Core NATS (P1)

Vérifié sur le code fusionné dans `main` (`521ff27d` / `fff03569`) sans réouverture de C1–C4 :
- **Suites Java T1A/NATS** (`NatsWakeupTransportTest`, `NatsWakeupTransportStateTest`, `WakeupContractTest`, `WakeupWorkerBehaviorTest`, `RenewalServiceLeaseRetryTest`, `SpringAttemptTransactionsAfterCommitTest`, `AdmissionWakeupIsolationTest`) : **`37/37 PASS`** (`8.077 s`).
- **Tests CLI Python & injection de fautes `PREPARING → APPLYING → COMMITTED`** (`scripts/local/test_*.py`) : **`143/143 PASS`** (`2.037 s`).
- **Intégrité des preuves T1A/NATS archivées** (`cd docs/testing/evidence/t1a-nats-qualification && shasum -a 256 -c manifest.sha256`) : **`8/8 OK`**.
- **Invariant conservé** : le réveil NATS améliore la latence centrale et réduit le CPU à vide/en interférence, mais ne constitue pas un gain démontré sur le drainage de backlog `10 000` (`RES-T1A-BACKLOG-01`).

---

## 4. Preuve de qualification sur réacteur complet (`Reactor_Verification_Policy.md` §§30, 35)

```text
Start HEAD: 521ff27d2abe1c1f6bc18de7c95f0cfc877bf857 (origin/main)
PR #12 P0 Commit: 7761ecd04adb8e36c9c6ef2a5c3ae21a7e664037
Qualified Code SHA (tests): fff035695a1361816e1473e5274ead43a62b563f
Expected production impact (app/): NONE (test-only changes in runtime-worker-materialize and runtime-monolith)
Observed production impact (app/): NONE (0 src/main/**, SQL migration, or scripts/local/** files modified)
Declared slices:
- Primary slice: PROJECTION (runtime-worker-materialize -am)
- Secondary slice: Monolith (runtime-monolith -am)
- Global gate: FULL REACTOR + GLOBAL ARCHITECTURE TESTS (44/44 modules via -pl architecture-tests -am test)
Verification commands executed:
1. cd app && ./mvnw --batch-mode --no-transfer-progress -pl infra-postgres-read -am clean test -Dtest=PotVisibilityReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
   -> PASS (5/5, 9.930 s) on 7761ecd0
2. python3 -B -m unittest discover -s scripts/local -p "test_*.py"
   -> PASS (143/143, 2.037 s)
3. cd app && ./mvnw --batch-mode --no-transfer-progress -pl infra-wakeup-nats,contract-consumption,supra-polling-consumption,infra-tx-spring,compose-web-write-postgres -am test -Dtest=NatsWakeupTransportTest,NatsWakeupTransportStateTest,WakeupContractTest,WakeupWorkerBehaviorTest,RenewalServiceLeaseRetryTest,SpringAttemptTransactionsAfterCommitTest,AdmissionWakeupIsolationTest -Dsurefire.failIfNoSpecifiedTests=false
   -> PASS (37/37, 8.077 s)
4. cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-monolith -am test -Dtest=InvitationHttpTest -Dsurefire.failIfNoSpecifiedTests=false
   -> PASS (8/8, 67.19 s) on fff03569
5. cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize -am test -Dtest=ReadReconstructionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
   -> PASS (13/13, 271.3 s) on fff03569
6. cd app && ./mvnw --batch-mode --no-transfer-progress -pl architecture-tests -am test
   -> PASS (1 221/1 221 across all 29 test-bearing modules / 44 reactor projects, 0 failures, 0 errors, 0 skipped, 52/52 architecture-tests, 01:21 h) on fff03569
Unexpected slice crossings: NONE
Global architecture gate: RUN (52/52 PASS)
Full reactor: RUN (44/44 projects, 1 221/1 221 PASS)
Database verification: CURRENT (PostgreSQL 17.11 Testcontainers)
Merge to main: NOT PERFORMED (awaiting human arbitration)
Certification: QUALIFIED PASS — GO_LOCAL_WITH_RESERVES
```

---

## 5. Verdict proposé

**Verdict : `GO_LOCAL_WITH_RESERVES`**

Tous les critères de sortie techniques de L7.5 sont satisfaits :
- CI verte sur la PR #12 (`45142597104` `8/8`), relancée sur le commit final de la PR #12 ;
- réacteur complet `44/44` modules et `architecture-tests` (`52/52`) verts (`1 221/1 221 PASS`, `0` skip) sur le code gelé `fff03569` ;
- `5` réserves soldées par preuves exécutables (`PG-REBUILD-EQ`, `RCV01-A`, `RCV01-N`, `INV-F`, `INV-G`) et `8` réserves non bloquantes explicitement qualifiées (`ACCEPTED_WITH_RESERVE`) ;
- frontière L7 vs L8 strictement respectée.

**Décision humaine attendue** : autorisation de fusionner la PR #12 dans `main` et de clôturer formellement le jalon L7 (avec les réserves documentées maintenues et reportées à L8).
