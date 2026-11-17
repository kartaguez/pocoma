# Registre des réserves actualisé — Post-clôture L7-LB & Audit résiduel L7

- **Date d'actualisation** : 17 novembre 2026
- **Référence Git** : `main` @ `59f68956e984a932bc28da6d9588680ce49d8210`
- **Périmètre** : consolidation des réserves issues de [`L7_I1_Residual_Reservations_Register.md`](L7_I1_Residual_Reservations_Register.md), [`L7_I4_Anomalies_and_Reservations_Register.md`](L7_I4_Anomalies_and_Reservations_Register.md), [`L7_Local_Playground_Reservations.md`](L7_Local_Playground_Reservations.md), [`L7_LB_Distribution_Qualification_and_Guarantees.md`](L7_LB_Distribution_Qualification_and_Guarantees.md) et [`L7_LB_S6_Operational_Hardening_Report.md`](L7_LB_S6_Operational_Hardening_Report.md).

**Classes de réserves** :
- **R1 (Bloquante L7)** : garantie ou preuve nécessaire avant clôture de L7.
- **R2 (Limite caractérisée / non bloquante L7-LB)** : limite d'exploitation ou de capacité connue et documentée, recevable avec mention explicite.
- **R3 (Report L8 / Production)** : exigence relevant de l'infrastructure cible (Configuration A/B), de la haute disponibilité multi-nœuds ou de la production Internet.
- **R4 (Soldée / Remplacée)** : réserve close par correction et requalification dans sa portée explicite.

---

## 1. Réserves L7-LB (Distribution locale)

| ID | Origine | Description factuelle | Classe | Statut post-`59f68956` | Impact L7 |
|---|---|---|---|---|---|
| `DEBT-L7LB-01` | Clôture `L7_LB_CLOSED` (`59f68956`) | `pocoma-local doctor` ([`runtime.py:1314-1340`](../../scripts/local/runtime.py#L1314-L1340)) ne contrôle pas l'espace disque hôte (`PRE-DISK` vérifié uniquement au `start` aux lignes `678-688`). | **R2** | **OPEN** (non bloquant `L7-LB`) | Non bloquant L7 ; fiche [`DOCTOR_DISK_CHECK/Debt.md`](../internal/debts/DOCTOR_DISK_CHECK/Debt.md). |
| `RES-L7LB-S6-01` | `L7-LB S6` | À `50 cmd/s` soutenus avec `admission.capacity=4`, aucune requête n'est rejetée (`0` `503`), mais la latence READ visible `p95` atteint `25.18 s` (`business-read-worker` / PG saturés). L'enveloppe nominale soutenable locale est bornée à `25 cmd/s`. | **R2** | **OPEN** (caractérisée) | Non bloquant L7-LB ; à confronter sur Config A en L8 (`SPEC-09`). |
| `RES-L7LB-S6-02` | `L7-LB S5/S6` | Topologie mono-worker par rôle (`1` conteneur) et PostgreSQL partagé (`pocoma_write` + `pocoma_read`) : prouve la reprise après redémarrage post-TTL (`30 s`), non le failover actif multi-nœuds. | **R3** | **OPEN** (borne topologique locale) | Relève de L8 (multi-nœuds / HA). |
| `RES-L7LB-S5-01..03` | `L7-LB S5` §6 (points 1, 2, 3, 5) | Divergence `/actuator/health` vs `/readiness` en `draining`, `capacity=1` par défaut ignoré par `http-write.properties`, distinction `operationalState`/`qualificationState`, rejet `--dry-run` hors `reset`/`destroy`, unités `ms` dans `configuration.md`. | **R4** | **RESOLVED** en S6 (`f9c0ef64`→S6) | Soldée par S6 et PR #6. |
| `LB-J1-GUI` / `LB-J1-CLIENT` | [`L7_Local_Playground_Reservations.md`](L7_Local_Playground_Reservations.md) | Ergonomie Bruno GUI non testée par humain dans le mandat automatisé ; pas de SDK client généré ni de politique CORS SPA production. | **R2 / R3** | **OPEN** (PKCE S256 & `gui` prouvés) | Non bloquant L7-LB ; CORS/SPA relève d'un futur BFF/L8. |
| `LB-J1-COLD` | Même source | Caches Docker/Maven présents sur le Mac lors de la première qualification ; installation depuis clone vierge de `main` confirmée en recette indépendante (`L7_LB_CERTIFIABLE`). | **R4 / R2** | **CLOSED** pour clone vierge local | Seconde machine vierge complète à froid relève de L8/distribution. |

---

## 2. Réserves L7.3 (Sûreté, atomicité, non-purge et couverture matricielle)

| ID / Groupe | Origine | Description factuelle | Classe | Statut courant | Impact L7 |
|---|---|---|---|---|---|
| `L2M-E02-J-REPORT` | `L7_I3_Final_Certification_Report.md` & `L7_I4` | Crash après admission HTTP durable avant finalisation : couvert techniquement par `e1-e3-06` (@ `230feb05`) + `S5-B`/`S6`, en attente de ratification formelle. | **R1** | **PARTIAL** *(clôturable par réemploi)* | Bloquant jusqu'à ratification documentaire (ou rejeu sur `59f68956`). |
| `14` obligations `L7.3` restantes | [`L7_Residual_Certification_Matrix.md`](L7_Residual_Certification_Matrix.md) §3 | `L2M-C04-K`, `E01-E`, `C01-A-REPORT`, `C01-D-REPORT`, `E01-H-REPORT`, `E01-G-INV-COMP`, `E01-G-ID-IDCOMP`, `CON-05-CRASH`, `C03-J-CRASH`, `C04-L-REPORT`, `C06-M-REPORT`, `CON-05-NOPURGE`, `R8-03`, `R8-05`. | **R1** | **PARTIAL** | **Bloquant L7** — chaque obligation exige son essai ciblé ou son audit sémantique. |
| `338` `CURRENT_PARTIAL` + `30` `HISTORICAL_PARTIAL` `L7.3` | `L7_I1_Certification_Coverage_Matrix.csv` | Contributions partielles hors des 31 lignes déjà rapprochées (`L7.3`). | **R1** | **PARTIAL** | **Bloquant L7** — revue d'allocation/suffisance requise avant L7.5. |
| `46` `CURRENT_PARTIAL` `L7.2` | `L7_I1_Certification_Coverage_Matrix.csv` | Couverture fonctionnelle/sécurité `L7.2` à réconcilier sur le gel final `main`. | **R1** | **PARTIAL** | Requis pour la clôture L7.5. |

---

## 3. Réserves L7.4 (Métrologie, banc et `SPEC-09`)

| ID | Origine | Description factuelle | Classe | Statut courant | Impact L7 |
|---|---|---|---|---|---|
| `L74-ENV-01` | [`L7_I4_Anomalies_and_Reservations_Register.md`](L7_I4_Anomalies_and_Reservations_Register.md) | Environnement A/B (`2 × 4 vCPU / 8 GiB` + Keycloak isolé) indisponible ; toutes les `13` familles `SPEC-09` sont `NOT_RUN` en conditions contractuelles. | **R3** | **OPEN** | Relève de L8 / labo A/B dédié (ne pas simuler par quotas locaux). |
| `L74-MET-02` / `L74-MET-03` | Même source | Coût causal de collecte non calibré (avec/sans collecteur) ; `E1` limité aux Petits Pots (`n=12`), sans `Balance` indépendante ni `W>V`. | **R1 / R2** | **OPEN** | À corriger dans le harnais avant toute campagne `SPEC-09` certifiante. |
| `L74-BENCH-01` | Même source | Profils `Moyen` (`20/200`), `Grand` (`100/2000`), stock `10 000` (`F01-G`), `DeletePot` interne et mix complet non intégrés au runner `E1`. | **R1 / R3** | **OPEN** | Profils `Moyen`/`Grand` à préparer en L7 pour caractérisation locale ; campagnes complètes sur A/B en L8. |
| `L74-SAFETY-01` | Même source | `E3` (`e1-e3-06`) n'interrompt le processus qu'après admission avant calcul ; crash pendant calcul/finalisation et sur `READ` restent dus (`C03-J-CRASH`, `CON-05-CRASH`). | **R1** | **OPEN** | Couvert par les essais ciblés `L7.3` du plan de terminaison. |
| `L74-ADMIN-01` | Même source | `2 704` références candidates dans `239` fichiers (`writer-inventory-final`) ; SQL indirect et pouvoirs administratifs non exhaustivement audités. | **R1** | **OPEN** | Couvert par l'audit sémantique `C04-L` / `C06-M` / `R8-03` / `R8-05`. |

---

## 4. Réserves L7.5 & Reports L8 / Production

| ID | Description | Classe | Statut |
|---|---|---|---|
| `RES-L75-GATES` | Exécution des gates globales non filtrées (`full` reactor sans exclusion `@Tag("slow")` + `architecture-tests`) sur le SHA candidat gelé de L7, manifeste de provenance et audit indépendant final (`18` lignes L7.5). | **R1** | **BLOCKED** (attend clôture `L7.3`) |
| `RES-L8-PROD` | Déploiement multi-nœuds/HA, séparation réseau, TLS/mTLS, coffre-fort et rotation des secrets, volumes globaux `10k/1M` et `100k/10M` (`F01-I-GLOBAL`), campagnes `SPEC-09` Config A/B. | **R3** | **DEFERRED TO L8** |
