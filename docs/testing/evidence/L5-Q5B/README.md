# Dossier de preuves de qualification L5/Q5-B

Date : **5 novembre 2026**
Dépôt : **`kartaguez/pocoma`**
Branche : **`feat/v3-architecture`**
Statut global : **QUALIFICATION FAILED — REPETITION LIMIT REACHED — LOT L5 OPEN**

---

## 1. Vue d'ensemble des campagnes

Le mandat Q5-B a encadré l'exécution de la qualification finale mutualisée en autorisant au maximum deux campagnes globales Maven CERTIFICATION (la tentative initiale, puis une reprise unique après correction ciblée du test défaillant).

| Campagne | SHA | Date / Heure | Durée | Tests | Statut | Module d'arrêt | Issue |
|---|---|---|---|---|---|---|---|
| **Tentative 1** | `ac32a7db58545bf147b244a2fe528636b4bb464f` | 05/11/2026 16:29 | 1 051 s | 1 532 (1 531 PASS, 1 FAIL, 0 ERROR) | **FAIL** | `pocoma-infra-persistence-primary-jpa` (24/61) | AUD-Q5B-01 |
| **Tentative 2 (reprise)** | `0e5583478fa5a2b5da3028671e4a2ad7ee1412de` | 05/11/2026 16:59 | 1 276 s | 1 682 (1 678 PASS, 0 FAIL, 4 ERROR) | **FAIL** | `pocoma-runtime-task-consumption-worker` (51/61) | AUD-Q5B-02 |
| **Tentative 3 (Q5-B3)** | `429529a6e17b0c7a7060b3ec45c7d36657b77095` | 05/11/2026 19:40 | 2 036 s | 1 724 (1 698 PASS, 2 FAIL, 24 ERROR) | **FAIL** | `pocoma-runtime-monolith` (52/61) | AUD-Q5B-03 |

---

## 2. Analyse détaillée des deux tentatives

### 2.1. Tentative 1 (`ac32a7db`) — Incident AUD-Q5B-01
- **Échec** : `IdentityDiscoveryPostgresTest#l3_identity_windows` (assertThrows IllegalArgumentException attendue sur `ConsumableFamily.INVITATION_EVENT`).
- **Cause** : Assertion historique L3 devenue obsolète suite à l'introduction légitime de la famille `INVITATION_EVENT` en L5/I4.
- **Traitement** : Correction ciblée du test par commit `0e558347` : oracle positif vérifiant l'acceptation de `INVITATION_EVENT`, oracle négatif repositionné sur `ConsumableFamily.COMMAND`.
- **Preuve locale** : 3/3 tests PASS sur `IdentityDiscoveryPostgresTest`.

### 2.2. Tentative 2 (`0e558347`) — Progression et Incident AUD-Q5B-02
- **Validation AUD-Q5B-01** : Le module `pocoma-infra-persistence-primary-jpa` s'est exécuté intégralement avec succès en 16 min 38 s (0 failure, 0 error). Les modules 44 à 50 ont également tous réussi.
- **Nouvel arrêt** : Arrêt au module 51 (`pocoma-runtime-task-consumption-worker`) avec 4 erreurs sur 104 tests du module :
  1. `ReadActivationSettingsTest#independentSelectionAndTemporalPreflight` :
     `java.lang.ArrayIndexOutOfBoundsException: Index 6 out of bounds for length 6`
     *Origine* : `V3ConsumptionWorkerSettings.READ_NAMES` contient 7 participants de lecture (incluant les évolutions L5), mais la liste locale des identifiants attendus `ids` dans le test ne compte que 6 entrées fixes.
  2. `ReadFacadesPostgresTest#bindingAndResultsKeepTheirOwnAuthorities` (via `realUnlinkPropagationWindow`) :
     `java.lang.IllegalArgumentException: renewal budget exceeds pilot margins; explicit requalification required`
     *Origine* : Garde temporelle stricte dans `V3ConsumptionWorkerSettings.java:316` déclenchée lors de la configuration de l'environnement multi-participants sous `ReadRuntimeI8Fixture`.
  3. `ReadRuntimeAssemblyPostgresTest#sixParticipantsProgressThroughWorkers` :
     `java.lang.IllegalArgumentException: renewal budget exceeds pilot margins; explicit requalification required`
  4. `ReadRuntimeAssemblyPostgresTest#rootsAndPrivilegesAreClosed` :
     `java.lang.IllegalArgumentException: renewal budget exceeds pilot margins; explicit requalification required`
- **Conséquence** : 10 modules avals non exécutés (`pocoma-runtime-monolith`, `pocoma-runtime-web-api`, et 8 autres modules dont `architecture-tests`).
- **Application du mandat** : Conformément à l'interdiction stricte de boucle automatique de reprise, aucun troisième run n'a été engagé. Le complément B1 et la consolidation B5 n'ont pas été lancés.

### 2.3. Tentative 3 (`429529a6`) — Progression au module 52 et Incident AUD-Q5B-03
- **Validation AUD-Q5B-02** : Le module 51 (`pocoma-runtime-task-consumption-worker`) a été franchi avec un **succès intégral** (100% SUCCESS en 4 min 09 s, 0 failure, 0 error), démontrant la pleine efficacité des réconciliations Q5-BF sur le Task Worker et ses réglages de baux.
- **Nouvel arrêt** : Arrêt au module 52 (`pocoma-runtime-monolith`) après 12 min 23 s d'exécution du module (durée totale réacteur 33 min 56 s) :
  - **42 tests exécutés** dans le module, 16 PASS, **2 Failures**, **24 Errors**, 0 Skip.
  - **Anomalie 1 (FlywayValidateException — 23 tests en erreur)** :
    `V3AdmissionFlowPostgresTest` (7 tests), `V3InvitationWritePostgresTest` (6 tests), `V3InvitationHttpTest` (5 tests), `V3InvitationReadPostgresTest` (3 tests), `V3InvitationTopologyPostgresTest` (2 tests) et `V3LinkBrunoPostgresTest` (1 test) ont échoué lors de leur setup avec l'erreur :
    `FlywayValidateException: Validate failed: Migrations have failed validation. Detected resolved migration not applied to database: 7, 8, 9.`
    *Cause* : Dans `ReadMonolithI8Fixture`, la migration Flyway applique `classpath:db/v3-invitation/migration` contenant `V10__invitation_history.sql`. Lors de l'initialisation ultérieure de l'admission dans les tests applicatifs, Flyway est invoqué sur le même schéma avec `classpath:db/v3-admission/migration` (contenant V7, V8, V9). Ayant déjà enregistré la version 10, Flyway rejette les versions antérieures 7, 8, 9 comme étant non appliquées hors-ordre sans `-outOfOrder=true`.
  - **Anomalie 2 (Course d'acquisition de partition — 1 failure)** :
    `ReadRootsCoexistencePostgresTest#projectionAndMonolithSharePartitionsSafely` :
    `AssertionFailedError: first started root owns the shared READ pairs ==> expected: <28> but was: <27>`
    *Cause* : Sous charge concurrente du réacteur complet, l'acquisition de la 28ème partition par le premier root a accusé un décalage temporel lors de l'observation.
  - **Anomalie 3 (Cardinalité des routes HTTP — 1 failure)** :
    `V3MonolithHttpCompositionPostgresTest#monolithRoot` :
    `AssertionFailedError: expected: <8> but was: <10>`
    *Cause* : L'oracle historique attendait 8 routes HTTP, alors que l'incrément L5/I4 a légitimement ajouté 2 routes d'invitation (`/invitations/...`), portant le total à 10 routes.
- **Conséquence** : 9 modules avals non exécutés (`pocoma-runtime-web-api`, `pocoma-runtime-event-consumption-worker`, `pocoma-runtime-command-result-consumption-worker`, `pocoma-runtime-registration-result-consumption-worker`, `pocoma-runtime-registration-consumption-worker`, `pocoma-runtime-latest-known-version-consumption-worker`, `pocoma-runtime-binding-consumption-worker`, `pocoma-runtime-command-consumption-worker`, `pocoma-architecture-tests`).
- **Application du mandat** : Aucun relancement automatique. Clôture de la tentative et diagnostic complet consigné.

---

## 3. Structure du dossier de preuves

```
docs/testing/evidence/L5-Q5B/
├── README.md                           # Présente synthèse
├── manifest.json                       # Inventaire complet et empreintes SHA-256
├── attempt-1-ac32a7db/                 # Artefacts de la première tentative
│   ├── plan.json                       # Plan de vérification CERTIFICATION
│   ├── provenance.json                 # Environnement, Git SHA, versions
│   ├── result.json                     # Résultat collecteur (1 532 tests, 1 FAIL)
│   ├── timings.json                    # Chronométrie des phases (1 051 s)
│   └── surefire-reports/               # Rapports Surefire du test défaillant
│       ├── TEST-com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest.xml
│       └── com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityDiscoveryPostgresTest.txt
├── attempt-2-0e558347/                 # Artefacts de la deuxième tentative (reprise bornée)
│   ├── plan.json                       # Plan de vérification CERTIFICATION
│   ├── provenance.json                 # Environnement, Git SHA, versions
│   ├── result.json                     # Résultat collecteur (1 682 tests, 4 ERRORS)
│   ├── timings.json                    # Chronométrie des phases (1 276 s)
│   ├── maven-failure.txt               # Trace terminale de l'arrêt au module 51
│   └── surefire-reports/               # Rapports Surefire des 3 classes en erreur
│       ├── TEST-com.kartaguez.pocoma.runtime.task.v3.ReadActivationSettingsTest.xml
│       ├── com.kartaguez.pocoma.runtime.task.v3.ReadActivationSettingsTest.txt
│       ├── TEST-com.kartaguez.pocoma.runtime.task.v3.ReadFacadesPostgresTest.xml
│       ├── com.kartaguez.pocoma.runtime.task.v3.ReadFacadesPostgresTest.txt
│       ├── TEST-com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeAssemblyPostgresTest.xml
│       └── com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeAssemblyPostgresTest.txt
└── attempt-3-429529a6/                 # Artefacts de la troisième tentative (Q5-B3)
    ├── plan.json                       # Plan de vérification CERTIFICATION
    ├── provenance.json                 # Environnement, Git SHA, versions
    ├── result.json                     # Résultat collecteur (1 724 tests, 2 FAIL, 24 ERRORS)
    ├── timings.json                    # Chronométrie des phases (2 044 s)
    ├── maven-failure.txt               # Trace terminale de l'arrêt au module 52
    └── surefire-reports/               # Rapports Surefire des classes en échec
        ├── TEST-com.kartaguez.pocoma.ReadRootsCoexistencePostgresTest.xml
        ├── com.kartaguez.pocoma.ReadRootsCoexistencePostgresTest.txt
        ├── TEST-com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.xml
        ├── com.kartaguez.pocoma.V3AdmissionFlowPostgresTest.txt
        ├── TEST-com.kartaguez.pocoma.V3InvitationHttpTest.xml
        ├── com.kartaguez.pocoma.V3InvitationHttpTest.txt
        ├── TEST-com.kartaguez.pocoma.V3InvitationReadPostgresTest.xml
        ├── com.kartaguez.pocoma.V3InvitationReadPostgresTest.txt
        ├── TEST-com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.xml
        ├── com.kartaguez.pocoma.V3InvitationTopologyPostgresTest.txt
        ├── TEST-com.kartaguez.pocoma.V3InvitationWritePostgresTest.xml
        ├── com.kartaguez.pocoma.V3InvitationWritePostgresTest.txt
        ├── TEST-com.kartaguez.pocoma.V3LinkBrunoPostgresTest.xml
        ├── com.kartaguez.pocoma.V3LinkBrunoPostgresTest.txt
        ├── TEST-com.kartaguez.pocoma.V3MonolithHttpCompositionPostgresTest.xml
        └── com.kartaguez.pocoma.V3MonolithHttpCompositionPostgresTest.txt
```
