# Registre des réserves L7.5 — Contre-audit indépendant et frontière L7 / L8

- **Date** : 23 novembre 2026
- **Branche** : `fix/l7-5-ci-visibility-oracle`
- **SHA code qualifié** : `424bde2ae86ae38c24261482480f4134cb019280` (sur base `main` `521ff27d2abe1c1f6bc18de7c95f0cfc877bf857`)
- **Preuve exécutable** : [`qualification-proof.json`](../testing/evidence/l7-5-counter-audit/qualification-proof.json) (`1 222/1 222 PASS`, `52/52` `architecture-tests`, `143/143` CLI Python)

---

## 1. Contre-audit des `13` obligations `CURRENT_PARTIAL` historiques

Chaque réserve a été reconstituée indépendamment depuis le code et les tests exécutables. Décision explicite entre `CLOSED_WITH_EVIDENCE`, `ACCEPTED_WITH_RESERVE` et `BLOCKING_L7`.

### 1.1 Sécurité et droits (`SEC-02`, `A10-F`, `A10-H`, `A10-I`, `A11-K`)

| ID | Invariant exigé | Déjà couvert (code & tests vérifiés) | Non couvert / sous-variante | Preuve exécutable | Risque résiduel | Décision L7.5 |
|---|---|---|---|---|---|---|
| `SEC-02` | Retard READ `CurrentBinding` accepté ; aucune promesse de révocation WRITE immédiate dans READ ; WRITE reste autoritaire | `InvitationHttpTest.unlinkCannotPreserveStaleInvitationAuthority` (verrou `FOR UPDATE` sur `read_current_binding`, `Unlink` `COMPLETED`, `GET /places` `200`, `POST /commands` rejeté `INVALID_BINDING`), `ReadHttpContractTest.bindingTokenAndForeignEui`, `AdmissionFlowPostgresTest.selfUsesWriteAndSurvivesBindingChange` | Fenêtre exacte `Unlink` + `GET /pots/{id}` sous verrou `read_current_binding` non isolée séparément | `1 221/1 221 PASS` (`fff03569`) | **Faible** (WRITE rejette systématiquement ; lag READ est contractuellement consultatif) | **`ACCEPTED_WITH_RESERVE`** |
| `A10-F` | `E1` authentifiée retrouve ses anciens résultats sans ancien ni nouveau binding | `ReadHttpContractTest.terminalResultsWithoutBinding` (binding révoqué puis renouvelé, `200` sur `/commands/.../result` et `/identity/requests/.../result`), `AdmissionFlowPostgresTest.selfUsesWriteAndSurvivesBindingChange` (`Unlink` puis ré-enregistrement `owner`, lecture résultat `200` par JWT seul) | Séquence publique Bruno multi-transitions complète (hors `Link` retiré par `ID-02`) | `1 221/1 221 PASS` (`fff03569`) | **Faible** | **`ACCEPTED_WITH_RESERVE`** |
| `A10-H` | `404` avant matérialisation (`PENDING`), `200` après matérialisation | `TerminalResultsReadStorePostgresTest.mappingsAndOwnership` (`A10-H absent before materialization`), `AdmissionFlowPostgresTest.resultsAndReadProgressIndependently`, Bruno `B30` | Corrélation continue `PENDING WRITE` + effet terminal sur un seul chemin continu | `1 221/1 221 PASS` (`fff03569`) | **Faible** | **`ACCEPTED_WITH_RESERVE`** |
| `A10-I` | Distinction AuthN absente (`401`), absence normale (`404`) et panne store résultats (`503`) | `ReadHttpContractTest.terminalResultsStoreUnavailabilityReturns503DistinctFrom401And404` (`401` sans AuthN, `200`/`404` nominaux, `503` sur `/commands/{id}/result` et `/identity/requests/{id}/result` après `REVOKE SELECT on read_command_result, read_identity_request_result`, reprise `200`/`404` après `GRANT SELECT`), `ReadHttpContractTest.missingBearer` (`401`), `terminalResultsOwnership` (`404`), `TerminalResultsReadStorePostgresTest.mappingsAndOwnership` | — (entièrement couvert au niveau HTTP + PostgreSQL) | `1 222/1 222 PASS` (`424bde2a`) | **Nul** | **`CLOSED_WITH_EVIDENCE`** (`CURRENT_FULL`) |
| `A11-K` | Droit historique conservé et binding révoqué refusé | `ReadHttpContractTest.historicalAccessAndDeletion` + `bindingTokenAndForeignEui`, `InvitationHttpTest.finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads` | Fenêtre physique continue sur une même requête combinée | `1 221/1 221 PASS` (`fff03569`) | **Faible** | **`ACCEPTED_WITH_RESERVE`** |

### 1.2 Invitations et concurrence (`INV-F`, `INV-G`)

| ID | Invariant exigé | Couverture vérifiée (HTTP + effets WRITE committés) | Preuve exécutable | Décision L7.5 |
|---|---|---|---|---|
| `INV-F` | Deux acceptations concurrentes sur une même place : un seul gagnant, aucun double effet, rejet propre du perdant | `InvitationHttpTest.l73_concurrentInvitationAcceptanceHasOneWinnerForOnePlace` & `l73_concurrentInvitationAcceptanceAttachesEachActorToTheirOwnPlace` (2 `POST /commands` derrière verrou `FOR UPDATE`, vérification HTTP `COMPLETED`/`REJECTED` + `v3_pot_event`, `v3_shareholder_details`, `v3_eui_version`, `current_version`), `concurrentUnlinkAndAttachmentSerializeAtIdentity`, `CommandExecutionPostgresTest.f04InvitationPrimitiveSerializesCompetingUsersAndSameUserTargets` | `InvitationHttpTest` `8/8 PASS` (`fff03569`) | **`CLOSED_WITH_EVIDENCE`** (`CURRENT_FULL`) |
| `INV-G` | Place attribuée ou binding révoqué entre consultation READ et acceptation : WRITE rejette sans mutation partielle | `InvitationHttpTest.unlinkCannotPreserveStaleInvitationAuthority` + **nouveau test** `placeClaimedBetweenReadConsultationAndAcceptanceIsRejectedWithoutMutation` (`GET /invitations/.../places` `200` → `second` réclame `f.share` → `guest` rejeté `INVALID_ATTACHMENT` → `v3_shareholder_details` et `v3_pot_event` vérifiés intacts) | `InvitationHttpTest` `8/8 PASS` (`fff03569`) | **`CLOSED_WITH_EVIDENCE`** (`CURRENT_FULL`) |

### 1.3 Reconstruction READ (`PG-REBUILD-EQ`, `RCV01-A`, `RCV01-N`)

| ID | Invariant exigé | Couverture vérifiée | Preuve exécutable | Décision L7.5 |
|---|---|---|---|---|
| `PG-REBUILD-EQ` | Équivalence du contenu logique et historique READ après reconstruction complète | `ReadReconstructionPostgresTest.readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication` inclut désormais `CreateInvitation` (`f.produceInvitation`) → `read_current_invitation` et `CURRENT_INVITATION_CHANGED` peuplés, réinitialisés et rejoués avec empreinte SHA-256 exacte | `ReadReconstructionPostgresTest` `13/13 PASS` (`fff03569`) | **`CLOSED_WITH_EVIDENCE`** (`CURRENT_FULL`) |
| `RCV01-A` | Reset complet READ sur base peuplée préservant strictement WRITE | `ReadReconstructionPostgresTest.rcv01A_fullResetOnPopulatedRead_writeRemainsIntact` inclut désormais `CreateInvitation` (`read_current_invitation >= 1` avant reset, `0` après reset, snapshot WRITE inchangé) | `ReadReconstructionPostgresTest` `13/13 PASS` (`fff03569`) | **`CLOSED_WITH_EVIDENCE`** (`CURRENT_FULL`) |
| `RCV01-N` | Égalité des valeurs métier/historiques après rejeu | `ReadReconstructionPostgresTest.readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication` couvre Pot multiversion, Pot supprimé, balances, visibilité, binding, résultats et `Invitation` | `ReadReconstructionPostgresTest` `13/13 PASS` (`fff03569`) | **`CLOSED_WITH_EVIDENCE`** (`CURRENT_FULL`) |

### 1.4 Contention et certification (`F01-H`, `CERT-SPEC10`, `CERT-AUDIT`)

| ID | Invariant exigé | Couverture vérifiée | Non couvert / limite | Risque résiduel | Décision L7.5 |
|---|---|---|---|---|---|
| `F01-H` | Contention même Pot : `0` double effet, `0` perte, drainage | `c2-contention-1pot` (`3/3 PASS`, `0` double effet, `availValid=true`) | `2–6/120` commandes résiduelles au `drainageTimeout` sous `T0` ; revalidation sous `A2` + Config A différée L8 | **Faible en correction** (aucun double effet), **Moyen en débit sous contention** (à valider en L8 Config A) | **`ACCEPTED_WITH_RESERVE`** (L8) |
| `CERT-SPEC10` | Certification de toutes les obligations SPEC-10 applicables | `1 222/1 222 PASS`, `52/52` `architecture-tests`, `143/143` CLI Python, T1A/NATS qualifié | `17` obligations `SPEC-09` bornées localement (`LOCAL_ENV_BOUNDED`), `1` différée L8 (`F01-F`), `4` sous-variantes sécurité/résultats + `F01-H` | Documenté et tracé | **`ACCEPTED_WITH_RESERVE`** |
| `CERT-AUDIT` | Audit du code V3 unifié | Audit interne (`C1–C4`, T1A/NATS) + contre-audit indépendant L7.5 (`424bde2a`) | Audit externe par un tiers indépendant (décision de gouvernance humaine) | Gouvernance | **`ACCEPTED_WITH_RESERVE`** |

**Bilan du contre-audit (`13` réserves) :**
- **`6` `CLOSED_WITH_EVIDENCE`** (`PG-REBUILD-EQ`, `RCV01-A`, `RCV01-N`, `INV-F`, `INV-G`, `A10-I`) ;
- **`7` `ACCEPTED_WITH_RESERVE`** (`SEC-02`, `A10-F`, `A10-H`, `A11-K`, `F01-H`, `CERT-SPEC10`, `CERT-AUDIT`) ;
- **`0` `BLOCKING_L7`**.

---

## 2. Frontière stricte L7 (local) vs L8 (infrastructure cible / production)

### Maintenues acceptées pour L7 (`R2` — non bloquantes)
- `RES-L7LB-S6-01` : plateau mono-hôte local (`36,83 cmd/s` sur `6 CPUs` Docker ; `35 cmd/s` soutenable avec `p95 = 134 ms`).
- `RES-T1A-BACKLOG-01` : sur drainage de backlog `10 000`, NATS réduit le CPU total (`-6,1 %`) et la latence Identity concurrente (`p50 -12,0 %`), sans gain démontré de débit de drainage (`134,92 s` vs `127,68 s`).
- `LB-J1-GUI` : ergonomie Bruno GUI par opérateur humain.
- Les `7` réserves `ACCEPTED_WITH_RESERVE` ci-dessus (`4` sous-variantes sécurité/résultats + `F01-H` + `CERT-SPEC10` + `CERT-AUDIT`).

### Explicitement différées à L8 (`R3` — hors périmètre local L7)
- `L74-ENV-01` : environnement cible `SPEC-09` Configuration A/B (`2 × 4 vCPU / 8 GiB` + Keycloak isolé, `F01-F` et pointes `50 cmd/s`).
- `RES-L8-01` / `RES-L7LB-S6-02` / `RES-L8-PROD` : topologie multi-VM, clustering NATS 3 nœuds, haute disponibilité distribuée active/passive, volumes `10k/1M` et `100k/10M`, TLS/mTLS, gestion/rotation des secrets de production et CORS/BFF SPA.
