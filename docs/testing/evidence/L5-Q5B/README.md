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
└── attempt-2-0e558347/                 # Artefacts de la reprise bornée
    ├── plan.json                       # Plan de vérification CERTIFICATION
    ├── provenance.json                 # Environnement, Git SHA, versions
    ├── result.json                     # Résultat collecteur (1 682 tests, 4 ERRORS)
    ├── timings.json                    # Chronométrie des phases (1 276 s)
    ├── maven-failure.txt               # Trace terminale de l'arrêt au module 51
    └── surefire-reports/               # Rapports Surefire des 3 classes en erreur
        ├── TEST-com.kartaguez.pocoma.runtime.task.v3.ReadActivationSettingsTest.xml
        ├── com.kartaguez.pocoma.runtime.task.v3.ReadActivationSettingsTest.txt
        ├── TEST-com.kartaguez.pocoma.runtime.task.v3.ReadFacadesPostgresTest.xml
        ├── com.kartaguez.pocoma.runtime.task.v3.ReadFacadesPostgresTest.txt
        ├── TEST-com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeAssemblyPostgresTest.xml
        └── com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeAssemblyPostgresTest.txt
```
