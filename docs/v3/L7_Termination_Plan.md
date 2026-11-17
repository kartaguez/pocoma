# Plan résiduel de terminaison L7 (Post-clôture des 15 obligations L7.3)

- **Date** : 17 novembre 2026
- **Référence Git** : `feat/l7-i3-closure-and-consolidation` (issue de `main` @ `0227dd0f`)
- **Objectif** : tracer le chemin résiduel ordonné jusqu'à la clôture de **L7** après la solde des `15` obligations bloquantes `L7.3` et la préparation du protocole `SPEC-09` local.

---

## 1. Travaux achevés dans ce mandat

- **Phase 1 — Intégration documentaire** : PR [#7](https://github.com/kartaguez/pocoma/pull/7) verte (`8/8` CI) fusionnée dans `main` (`0227dd0f`).
- **Phase 2 — Clôture des `15` obligations L7.3** : `15/15` passées à **`CURRENT_FULL`** (`1` réemploi validé `L2M-E02-J-REPORT` + `11/11` tests PostgreSQL ciblés PASS + audit sémantique complet des `2 704` candidats de `writer-inventory-final`).
- **Phase 3 — Consolidation matricielle (`522` lignes)** : `CURRENT_FULL` passé de `53` à **`74`** (`+21`), sans aucune promotion indue de preuve partielle.
- **Phase 4 — Protocole `SPEC-09` local** : [`L7_SPEC09_Local_Qualification_Protocol.md`](L7_SPEC09_Local_Qualification_Protocol.md) publié pour `Moyen` (`20/200`) et `Grand` (`100/2000`), sans lancer de campagne lourde ni modifier le moteur de projection.

---

## 2. Plan résiduel de terminaison L7 avec estimation d'effort

| Ordre | Lot / Cible | Action exacte requise | Dépendance | Effort estimé |
|---|---|---|---|---|
| **R1** | Revue d'allocation `L7.2` (`41` `CURRENT_PARTIAL`) & `L7.3` (`338` `CURRENT_PARTIAL` + `30` `HISTORICAL_PARTIAL`) | Rapprochement automatisé/documentaire par famille (`DOM`, `SEC`, `CON`, `ADM`, `INV`, `REC`, `OPS`) contre les suites CI existantes et les reçus `L7-LB`/`L7-I2`/`L7-I3` ; isoler les éventuels manques réels. | Aucune | **Moyen** (`1–2` jours-personne) |
| **R2** | Caractérisation locale `SPEC-09` (`LOC-MED-1`, `LOC-MED-10`, `LOC-GRAND-1`, `LOC-IDLE`, `LOC-DEPTH`) | Exécuter le protocole [`L7_SPEC09_Local_Qualification_Protocol.md`](L7_SPEC09_Local_Qualification_Protocol.md) (`3` répétitions par profil) pour mesurer séparément admission, consommation, calcul `PotBalance` et disponibilité READ. | Aucune | **Moyen** (`1` jour-personne) |
| **R3** | Arbitrage conditionnel sur projections | **Uniquement si** `R2` révèle un goulot mesuré menaçant `500 ms` (`Moyen`) ou `2 000 ms` (`Grand`) : soumettre un mandat d'arbitrage dédié avant toute modification architecturale. | **R2** | **Conditionnel** (`0` si conforme, sinon à instruire) |
| **R4** | Dette outillage non bloquante `DEBT-L7LB-01` | Ajouter le contrôle disque dans `pocoma-local doctor` (`runtime.py`) + tests unitaires Python. | Aucune | **Faible** (`0.25` jour-personne) |
| **R5** | Gates globales `L7.5` (`CERT-FULL`, `CERT-ARCH`, `CERT-SPEC10`, `CERT-AUDIT`, `CERT-TRACE`) | Sur le SHA candidat gelé : exécuter `./mvnw test` complet (sans exclusion `@Tag("slow")`) + `./mvnw -pl architecture-tests -am test`, générer le manifeste final et l'audit indépendant. | **R1**, **R2** | **Moyen** (`0.5–1` jour-personne, incluant ~50 min de réacteur) |

---

## 3. Périmètre maintenu en L8 / Production (hors clôture L7)

1. Infrastructure distante `SPEC-09` Configuration A/B (`2 × 4 vCPU / 8 GiB` + Keycloak isolé) et campagnes `F01-A..J`, `ENDURANCE`, `SATURATION`.
2. Volumes globaux `10k / 1M` et `100k / 10M` (`F01-I-GLOBAL`).
3. Haute disponibilité multi-nœuds (`RES-L7LB-S6-02`), TLS/mTLS, rotation des secrets et politique CORS/BFF.
