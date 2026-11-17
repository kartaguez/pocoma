# Matrice résiduelle de certification L7 (`L7_I3_CLOSURE_ASSESSED / L7_I5_EVIDENCE_CONSOLIDATED`)

- **Date d'actualisation** : 17 novembre 2026
- **Référence Git** : `feat/l7-i3-closure-and-consolidation` (issue de `main` @ `0227dd0f`, post-fusion PR #7)
- **Sources normatives et matricielles** :
  - [`L7_I1_Certification_Coverage_Matrix.csv`](L7_I1_Certification_Coverage_Matrix.csv) (`522` IDs consolidés)
  - [`L7_I3_Closure_Report.md`](L7_I3_Closure_Report.md) (clôture des `15` obligations bloquantes `L7.3`)
  - [`L7_I4_Contribution_to_L7_I3_Report.md`](L7_I4_Contribution_to_L7_I3_Report.md) (`e1-e3-06` @ `230feb05` et inventaire `writer-inventory-final`)
  - [`L7_LB_Closure_Act.md`](L7_LB_Closure_Act.md) (`L7_LB_CLOSED`)
  - [`L7_SPEC09_Local_Qualification_Protocol.md`](L7_SPEC09_Local_Qualification_Protocol.md) (`SPEC09_LOCAL_READY`)

---

## 1. Bilan chiffré avant / après consolidation (`522` lignes)

| Incrément | Total | `CURRENT_FULL` | `HISTORICAL_REUSABLE` | `CURRENT_PARTIAL` | `HISTORICAL_PARTIAL` | `MISSING` | `RETIRED_BY_HUMAN_DECISION` | Évolution |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| **L7.2** | 52 | **10** *(avant : 5)* | 0 | **41** *(avant : 46)* | 0 | 0 | 1 | `+5` `CURRENT_FULL` (`CERT-BRUNO`, `FUNC-POT`, `FUNC-DETACH`, `FUNC-UNLINK`, `INV-I`) ; `41` partielles maintenues sans promotion indue. |
| **L7.3** | 432 | **63** *(avant : 48)* | 0 | **338** *(avant : 353)* | 30 | 0 | 1 | **`+15` `CURRENT_FULL` (15/15 bloquantes closes)** ; `338` + `30` partielles à statuer avant L7.5. |
| **L7.4** | 20 | 0 | 0 | 0 | 9 | 11 | 0 | `SPEC-09` Config A/B `NOT_RUN` (protocole local prêt dans `L7_SPEC09_Local_Qualification_Protocol.md`). |
| **L7.5** | 18 | **1** *(avant : 0)* | 5 | 0 | 10 | **2** *(avant : 3)* | 0 | `+1` `CURRENT_FULL` (`ARC-13` via S1 + S3 + CI PR #7 `8/8`) ; gates globales `full` + `architecture-tests` bloquées jusqu'au gel L7. |
| **Total** | **522** | **74** *(avant : 53)* | **5** | **379** *(avant : 399)* | **49** | **13** *(avant : 14)* | **2** | **`+21` `CURRENT_FULL`**, aucune preuve partielle promue sans preuve complète. |

---

## 2. Statut consolidé des 15 obligations L7.3 (toutes clôturées `CURRENT_FULL`)

| Identifiant | Mode de clôture | Preuve actuelle / réemployée | Statut consolidé |
|---|---|---|---|
| `L2M-E02-J-REPORT` | Réemploi validé | `L7-I4` `e1-e3-06/receipt.json` (@ `230feb05`) + `L7-LB` `S5-B`/`S6`, diff `230feb05..0227dd0f` vérifié | **`CURRENT_FULL`** |
| `L2M-E01-E` | Essai ciblé | `L73TerminationWritePostgresTest` + `L73TerminationIdentityPostgresTest` (`5/5` PASS) | **`CURRENT_FULL`** |
| `L2M-CON-05-NOPURGE` | Essai ciblé + Audit | `L73TerminationWritePostgresTest` (`42501` + `deleteProtected`) + `writer-inventory-final` | **`CURRENT_FULL`** |
| `L2M-C01-A-REPORT` | Essai ciblé | `L73TerminationReadPostgresTest` (`17` événements sur `4` partitions, slots & `K` exacts) | **`CURRENT_FULL`** |
| `L2M-C01-D-REPORT` | Essai ciblé | `L73TerminationReadPostgresTest` (barrière publication `Identity` + `Discovery READ` commit/rollback) | **`CURRENT_FULL`** |
| `L2M-E01-G-INV-COMP` | Essai ciblé | `L73TerminationWritePostgresTest` (`INVITATION → COMMAND_COMPLETION`, `pg_blocking_pids`) | **`CURRENT_FULL`** |
| `L2M-E01-G-ID-IDCOMP` | Essai ciblé | `L73TerminationWritePostgresTest` (`IDENTITY → IDENTITY_COMPLETION`, `pg_blocking_pids`) | **`CURRENT_FULL`** |
| `L2M-CON-05-CRASH` | Essai ciblé multi-processus | `L73TerminationCrashAndCompositePostgresTest` (`SIGKILL` avec claim acquis WRITE + READ) | **`CURRENT_FULL`** |
| `L2M-C03-J-CRASH` | Essai ciblé multi-processus | `L73TerminationCrashAndCompositePostgresTest` (`SIGKILL` en écriture en vol WRITE + READ) | **`CURRENT_FULL`** |
| `L2M-C04-K` | Essai ciblé composite | `L73TerminationCrashAndCompositePostgresTest` (takeover, rollback, reprise, réconciliation exacte) | **`CURRENT_FULL`** |
| `L2M-E01-H-REPORT` | Essai ciblé mixte | `L73TerminationCrashAndCompositePostgresTest` (`Pot` + `Identity` + `Invitation`, 5 familles denses) | **`CURRENT_FULL`** |
| `L2M-C04-L-REPORT` | Audit sémantique + refus | `writer-inventory-final` (`410` prod + `65` tool-or-sql) + `L73TerminationWritePostgresTest` | **`CURRENT_FULL`** |
| `L2M-C06-M-REPORT` | Audit sémantique | `writer-inventory-final` (`272` `TRANSACTION_OWNER` + `34` `DYNAMIC_SQL` prod) | **`CURRENT_FULL`** |
| `L2M-R8-03` | Audit sémantique + refus | `writer-inventory-final` (`1` `SLOT_DELETE` prod, `3+2` `CHECKPOINT_WRITE`) + `42501` | **`CURRENT_FULL`** |
| `L2M-R8-05` | Audit sémantique | `writer-inventory-final` (`2 704` candidats / `239` fichiers rapprochés) | **`CURRENT_FULL`** |

---

## 3. Dettes de couverture restantes avant clôture L7

1. **Revue d'allocation `L7.2` (`41` `CURRENT_PARTIAL`) et `L7.3` (`338` `CURRENT_PARTIAL` + `30` `HISTORICAL_PARTIAL`)** : vérifier ligne à ligne les références existantes sans promotion collective.
2. **Caractérisation locale `SPEC-09`** : exécuter [`L7_SPEC09_Local_Qualification_Protocol.md`](L7_SPEC09_Local_Qualification_Protocol.md) sur `Moyen` (`20/200`) et `Grand` (`100/2000`) avant tout arbitrage sur les projections.
3. **Gates globales `L7.5`** : réacteur complet non filtré (`./mvnw test`) + `./mvnw -pl architecture-tests -am test` sur le SHA candidat gelé, manifeste de provenance et audit final (`CERT-FULL`, `CERT-ARCH`, `CERT-SPEC10`, `CERT-AUDIT`, `CERT-TRACE`).
