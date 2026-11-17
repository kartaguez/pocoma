# Dossier de Preuves de Certification Finale Globale L5/Q5-B6

Date : **6 novembre 2026**  
Dépôt : **`kartaguez/pocoma`**  
Branche : **`feat/v3-architecture`**  
SHA de référence qualifié : **`2890d88f6fa94ff2262d08a2809f48512ce27c9d`** (`2890d88f`)  
Invocation ID : **`eead78b94c294a948253ec7022ec1ab6`**  
Mode : **PREFLIGHT → SINGLE CERTIFICATION RUN → EVIDENCE → ANALYSIS → DOCUMENTATION → COMMIT & PUSH**  
Verdict terminal : **`CERTIFICATION_PASS`**  
Statut du lot L5 : **`L5 — CLOSED (QUALIFIED)`**  

---

## 1. Vue d'Ensemble de la Campagne

La campagne globale unique de certification finale Q5-B6 a été exécutée de bout en bout en une seule passe canonique sur le SHA figé `2890d88f`, suite aux corrections ciblées de tests et de fixtures (AUD-ARCH-01..03, AUD-WORKER-01, AUD-Q5B5-01..02).

| Indicateur | Valeur observée | Statut |
|---|:---:|:---:|
| **Verdict global** | **`CERTIFICATION_PASS`** | **PASS** |
| **SHA qualifié** | `2890d88f6fa94ff2262d08a2809f48512ce27c9d` | Immuable |
| **Prévol complet** | Validé à 100% (Git, Docker, PostgreSQL 16.6, Keycloak 26.7.0, OIDC, Bruno 4.3.0, JDK 21) | `PREFLIGHT_PASS` |
| **Invocations globales Maven** | **Exactement 1** (aucun rejeu, aucune boucle de retry, aucun `-rf`) | CONFORME |
| **Durée totale réacteur** | **4 984,58 s** (~1h 23 min 05 s) | Budget respecté (< 130 min) |
| **Modules réacteur résolus** | **61 modules** | 61 résolus |
| **Modules en succès** | **61 modules (100% SUCCESS)** | **SUCCESS** |
| **Modules skippés ou en échec** | **0** | **0** |
| **Tests JUnit / Surefire exécutés** | **2 344 tests** | — |
| **Tests réussis (PASS)** | **2 344 tests (100%)** | **PASS** |
| **Échecs d'assertion (FAILURES)** | **0** | CONFORME |
| **Erreurs d'exécution (ERRORS)** | **0** | CONFORME |
| **Tests sautés ou exclus (SKIPPED)** | **0** | CONFORME |
| **Gate Architecture globale** | **170 / 170 tests PASS** (`pocoma-architecture-tests`) | **PASS** |
| **Gate PostgreSQL** | V1–V10 canonique, 0 erreur Flyway, baux et topologies validés | **PASS** |
| **Gate HTTP Monolithe & Web-API** | 10 routes monolithe validées, contrats HTTP Web-API validés | **PASS** |
| **Bruno B1 (READ autonome)** | 32 scénarios (B01–B32) exécutés et réussis (exit 0) | **PASS** |
| **Bruno B2 (Admission / Commandes)** | 24 scénarios (B201–B224) exécutés et réussis (exit 0) | **PASS** |
| **Bruno B3 (Link OIDC réel)** | 10 scénarios (B301–B313) sous Keycloak 26.7.0 réel (exit 0) | **PASS** |
| **Bruno B4 (Invitations)** | 4 scénarios (B401–B404) exécutés et réussis (exit 0) | **PASS** |
| **Suite Bruno B5 consolidée** | **70 / 70 scénarios validés** (`bruno-b5-consolidated.json`) | **B5 COMPOSITE PASS** |

---

## 2. Statut des Anomalies Résolues

Toutes les anomalies historiques et récentes ont été validées comme définitivement résolues :

1. **AUD-Q5B-03-A (Ordonnancement Flyway V7–V10)** :
   - Zéro `FlywayValidateException` sur l'ensemble des 61 modules et tests d'intégration.
2. **AUD-Q5B-03-B (Observation des 28 partitions)** :
   - `ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely` est **PASS**.
3. **AUD-Q5B-03-C (Oracle des 10 routes HTTP)** :
   - `V3MonolithHttpCompositionPostgresTest#monolithRoot` et `V3HttpCompositionPostgresTest#webRoot` sont **PASS**.
4. **AUD-Q5B5-01 (Bail Invitation 180s)** :
   - `V3InvitationTopologyPostgresTest` est **PASS**.
5. **AUD-Q5B5-02 (NullPointerException Hikari actual.close())** :
   - `ReadWorkerResourcesPostgresTest` est **PASS**.
6. **AUD-ARCH-01..03 (Oracles ArchUnit Invitation et java.sql.Timestamp)** :
   - `pocoma-architecture-tests` est **100% PASS** (170 tests).
7. **AUD-WORKER-01 (Réflexion constructeur worker)** :
   - `pocoma-runtime-command-consumption-worker` est **100% PASS** (321 tests).

---

## 3. Contenu du Répertoire de Preuves

- `plan.json` : plan de qualification CERTIFICATION résolu par le sélecteur canonique.
- `provenance.json` : empreintes complètes de l'environnement, JDK, Docker, Git.
- `result.json` : rapport structuré complet des exécutions JUnit et compteurs (verdict PASS).
- `timings.json` : chronométrage par phase du sélecteur canonique.
- `discovery.log` : journal de découverte Maven effective-pom.
- `surefire-reports/` : rapports XML des classes de test névralgiques (monolithe, architecture, web-api, worker).
- `bruno-b1/` : rapports et reçus du corpus B1 autonome (32 scénarios, exit 0).
- `bruno-b2/` : rapports et reçus du corpus B2 (24 scénarios, exit 0).
- `bruno-i3/` : rapports et reçus du corpus B3 sous Keycloak réel (10 scénarios, exit 0).
- `bruno-b4/` : rapports et reçus du corpus B4 (4 scénarios, exit 0).
- `bruno-b5-consolidated.json` : consolidation formelle unifiée des 70 scénarios Bruno (B5 COMPOSITE PASS).
- `manifest.json` : empreintes cryptographiques SHA-256 de l'intégralité des fichiers archivés.
