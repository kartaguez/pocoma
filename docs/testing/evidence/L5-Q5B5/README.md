# Dossier de Preuves de Qualification Finale L5/Q5-B5

Date : **5 novembre 2026**
Dépôt : **`kartaguez/pocoma`**
Branche : **`feat/v3-architecture`**
SHA de référence qualifié : **`67b49b4ac47ecd350ae6eafbf7756e35f2f8ee8d`**
Invocation ID : **`392a0f6f9040474ba7f9339be44be056`**
Mode : **PREFLIGHT → SINGLE CERTIFICATION RUN → EVIDENCE → ANALYSIS → DOCUMENTATION → COMMIT & PUSH**
Verdict terminal : **`CERTIFICATION_FAIL`**
Statut du lot L5 : **`L5 — OPEN — CORRECTION REQUIRED BEFORE CLOSURE`**

---

## 1. Vue d'Ensemble de la Campagne

La campagne globale unique de certification Q5-B5 a été exécutée de bout en bout en une seule passe sur le SHA immuable `67b49b4a`, suite aux corrections ciblées de Q5-B4.

| Indicateur | Valeur observée | Statut |
|---|:---:|:---:|
| **Verdict global** | **`CERTIFICATION_FAIL`** | **FAIL** |
| **SHA qualifié** | `67b49b4ac47ecd350ae6eafbf7756e35f2f8ee8d` | Figé |
| **Prévol complet** | Validé à 100% (Git, Docker, PostgreSQL 16.6, Keycloak 26.7.0, OIDC, Bruno 4.3.0, JDK 21) | `PREFLIGHT_PASS` |
| **Invocations globales** | **Exactement 1** (aucun rejeu, aucune boucle) | CONFORME |
| **Durée totale réacteur** | **2 486,57 s** (~41 min 26 s) | Budget respecté (< 130 min) |
| **Modules réacteur résolus** | 61 modules | 61 résolus |
| **Modules en succès** | **51 modules** | SUCCESS |
| **Module d'arrêt (premier échec)** | Module 52 (`pocoma-runtime-monolith`) | **ÉCHEC** |
| **Modules skippés** | 9 modules (dont `web-api` et `architecture-tests`) | SKIPPED |
| **Tests exécutés** | **1 724 tests** | — |
| **Tests PASS** | **1 722 tests** | +24 tests PASS vs Q5-B3 |
| **Échecs d'assertion (FAILURES)** | **1** (`V3InvitationTopologyPostgresTest`) | Non conforme |
| **Erreurs d'exécution (ERRORS)** | **1** (`ReadWorkerResourcesPostgresTest`) | Non conforme |
| **Tests sautés (SKIPPED)** | **0** | CONFORME |
| **Gate Architecture globale** | Non atteinte (module 61 skippé) | **NOT RUN** |
| **Bruno B2 (Admission / Commandes)** | 24 scénarios (B201–B224) | **PASS** |
| **Bruno B3 (Link OIDC réel)** | 10 scénarios (B301–B313) | **PASS** |
| **Bruno B4 (Invitations)** | 4 scénarios (B401–B404) | **PASS** |
| **Bruno B1 (READ autonome)** | Non exécuté (`i1-classpath.txt` non généré) | **NOT RUN** |
| **Suite Bruno B5 consolidée** | Incomplète (38 / 70 scénarios acquis) | **INCOMPLETE** |

---

## 2. Statut des Anomalies Historiques Q5-B3

Les trois anomalies identifiées lors de la campagne Q5-B3 ont été **intégralement validées comme résolues** au sein de cette campagne globale :

1. **AUD-Q5B-03-A (Ordonnancement Flyway V7–V10)** :
   - **RÉSULTAT : VALIDÉ / RÉSOLU**.
   - Zéro `FlywayValidateException`. L'ensemble des 6 classes affectées (`V3AdmissionFlowPostgresTest`, `V3InvitationWritePostgresTest`, `V3InvitationHttpTest`, `V3InvitationReadPostgresTest`, `V3InvitationTopologyPostgresTest`, `V3LinkBrunoPostgresTest`) se sont initialisées avec succès.
2. **AUD-Q5B-03-B (Observation des 28 partitions)** :
   - **RÉSULTAT : VALIDÉ / RÉSOLU**.
   - `ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely` est **PASS** dans la campagne globale.
3. **AUD-Q5B-03-C (Oracle des 10 routes HTTP)** :
   - **RÉSULTAT : VALIDÉ / RÉSOLU**.
   - `V3MonolithHttpCompositionPostgresTest#monolithRoot` est **PASS** dans la campagne globale.

---

## 3. Nouvelles Anomalies Isolées (Q5-B5)

L'arrêt au module 52 (`runtime-monolith`) résulte de deux anomalies distinctes :

### AUD-Q5B5-01 : Dérive d'oracle sur la durée de lease Invitation (1 failure)
- **Classe & Méthode** : `V3InvitationTopologyPostgresTest#targetThreeFourPartitionsConnectionsRenewalAndRestart` (ligne 90)
- **Diagnostic** : `expected: <PT3M> but was: <PT4M>`
- **Cause racine** : Lors de Q5-BF (commit `cca25e86`), `ReadMonolithI8Fixture` a augmenté la durée de lease de 180s (PT3M) à 240s (PT4M) pour accommoder les 7 participants READ. Les classes associées avaient été mises à niveau, mais `V3InvitationTopologyPostgresTest` avait conservé son assertion sur `Duration.ofSeconds(180)`. En Q5-B3, ce test n'avait pas atteint cette assertion à cause de l'erreur Flyway en setup.
- **Classification** : `ORACLE_DRIFT` / `TEST_ORACLE_DEFECT_SUSPECTED`.

### AUD-Q5B5-02 : NullPointerException sur éviction de connexion Hikari (1 error)
- **Classe & Méthode** : `ReadWorkerResourcesPostgresTest#allParticipantsFitPoolsAndRenewalEnvelope` (ligne 952)
- **Diagnostic** : `java.lang.NullPointerException: Cannot invoke "java.sql.Connection.setAutoCommit(boolean)" because "connection" is null` à `com.zaxxer.hikari.pool.PoolBase.resetConnectionState(PoolBase.java:223)`
- **Cause racine** : Le spy `RetainingSource` simule une perte d'ACK en lançant un `SQLException` avec le SQLState fatal "08006". HikariCP considère "08006" comme une rupture de connexion et évince la connexion physique du pool (`poolEntry.connection = null`). Lorsque le test appelle ultérieurement `fault.resolve(false)` qui invoque `actual.close()`, Hikari tente de réinitialiser l'auto-commit sur la connexion physique nulle.
- **Classification** : `FIXTURE_DEFECT_SUSPECTED` / `TEST_DEFECT_SUSPECTED`.

---

## 4. Contenu du Répertoire de Preuves

- `plan.json` : plan de qualification CERTIFICATION résolu.
- `provenance.json` : empreintes d'environnement, JDK, Docker, Git.
- `result.json` : rapport structuré complet des exécutions JUnit et compteurs.
- `timings.json` : chronométrage par phase du sélecteur.
- `maven-failure.txt` : extrait des logs d'erreurs et Reactor Summary.
- `surefire-reports/` : rapports XML et TXT Surefire des classes du module 52 sous test.
- `bruno-b2/` : rapports et reçus du corpus B2 (24 scénarios, exit 0).
- `bruno-i3/` : rapports et reçus du corpus B3 sous Keycloak réel (10 scénarios, exit 0).
- `bruno-b4/` : rapports et reçus du corpus B4 (4 scénarios, exit 0).
- `manifest.json` : empreintes SHA-256 de l'intégralité des fichiers archivés.
