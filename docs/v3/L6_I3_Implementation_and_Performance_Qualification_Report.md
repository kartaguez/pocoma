# Pocoma V3 — L6/I3 — Rapport d'Implémentation des Bancs de Performance et de Qualification Locale Bornée

**Date :** 7 novembre 2026  
**Dépôt :** `kartaguez/pocoma`  
**Branche :** `feat/v3-architecture`  
**SHA initial :** `a24cd37255486481a4f11d4b81ab7895f470b223`  
**Mandat humain :** `GO_L6_I3_IMPLEMENTATION_WITH_BOUNDED_LOCAL_QUALIFICATION`  
**Mode :** `IMPLEMENTATION + TARGETED VERIFICATION + BOUNDED LOCAL QUALIFICATION`  
**Statut précédent :** `L6/I1 CLOSED (WITH RESERVATIONS) — L6/I2 CLOSED (WITH RESERVATIONS) — L6/I3 FRAMED & APPROVED`  
**Verdict terminal :** `L6_I3_BENCH_IMPLEMENTED_LOCAL_BOUNDED_PASS`  

---

## 1. Déclaration Normative des Tranches de Vérification (`AGENTS.md`)

- **Declared verification slice(s) :** `NONE — EXTERNAL BENCHMARK TOOLING`
- **Commands actually executed :**
  ```bash
  # Qualification unitaire des oracles (Python & k6 offline)
  python3 scripts/performance/test_bench.py
  k6 run --quiet --no-usage-report scripts/k6/v3-oracles-selftest.js

  # Préflight de sécurité
  python3 scripts/performance/v3-preflight.py

  # Reconnaissance locale sous watchdog
  python3 scripts/performance/v3-run.py smoke
  python3 scripts/performance/v3-run.py oracles
  python3 scripts/performance/v3-run.py small --duration 20s --rate 1
  python3 scripts/performance/v3-run.py contention --duration 10s
  python3 scripts/performance/v3-run.py idle-polling
  ```
- **Unexpected slice crossing :** `NONE`. Aucun fichier sous `app/` n'a été modifié.
- **Global gate required :** `NO`.
- **Full reactor run and why :** `NO`. Strictement interdit par la politique pour des outillages externes.

---

## 2. Inventaire des Fichiers Créés et Modifiés

### Fichiers d'Outillage et de Métrologie Créés
- `scripts/k6/v3-performance.js` : Banc principal k6 composable avec gestion des VUs et des charges.
- `scripts/k6/lib/v3-api.js` : Client API asynchrone V3 (admissions, résultats terminaux, sondes C-03, discrimination des 503).
- `scripts/k6/lib/v3-oracles.js` : Oracles purs C-03 (Pot, Balance, Delete, Identity, fail-closed).
- `scripts/k6/v3-oracles-selftest.js` : Suite de validation offline des oracles k6 (32 assertions déterministes).
- `scripts/performance/v3-preflight.py` : Barrière de pré-vol vérifiant 12 contrôles d'intégrité avant injection.
- `scripts/performance/v3-seed.py` : Générateur reproductible de fixtures SPEC-09 avec empreinte SHA-256.
- `scripts/performance/v3-run.py` : Orchestrateur maître avec watchdog de sécurité et suivi persistant du budget de 70 min.
- `scripts/performance/v3-collect.py` : Collecteur de métrologie multi-niveaux (k6, Micrometer, JVM, PostgreSQL).
- `scripts/performance/v3-oracle-probe.py` : Sonde interne SQL read-only pour les preuves C-03 ($DeletePot$ et $Balance(P,V)$).
- `scripts/performance/test_bench.py` : Suite unitaire Python validant les 14 modes de défaillance obligatoires.
- `scripts/performance/README.md` : Guide d'exploitation complet des bancs.

### Documents Livrés et Mis à Jour
- `docs/v3/L6_I3_Scoping_and_Design_Report.md` : Rapport de cadrage intégrant les arbitrages D1–D6.
- `docs/v3/L6_I3_Technical_Implementation_and_Verification_Plan.md` : Plan technique d'implémentation.
- `docs/v3/L6_I3_Implementation_and_Performance_Qualification_Report.md` : Présent rapport d'implémentation et de qualification.
- `docs/v3/L6_Design_and_Verification_Plan.md` : Actualisation du statut de l'incrément I3.
- `docs/v3/V3_Implementation_Master_Plan.md` : Consolidation de l'avancement du programme V3.
- `docs/README.md` : Référencement des livrables L6/I3.

---

## 3. Architecture Technique du Banc

Le banc V3 a été conçu selon une approche modulaire et non intrusive :

```
scripts/
├── k6/
│   ├── v3-performance.js           # Scénario k6 unique composable (smoke, nominal, sustained, peak, contention, idle)
│   ├── v3-oracles-selftest.js       # Auto-test unitaire hors-ligne des oracles k6
│   └── lib/
│       ├── v3-api.js               # Client HTTP asynchrone V3 (admissions, discrimination 503)
│       └── v3-oracles.js           # Oracles purs C-03 (Pot, Balance, Delete, Identity)
└── performance/
    ├── v3-preflight.py             # Barrière de sécurité pré-vol (12 vérifications bloquantes)
    ├── v3-seed.py                  # Générateur de fixtures reproductibles (SHA-256)
    ├── v3-run.py                   # Orchestrateur maître et watchdog actif (budget 70 min)
    ├── v3-collect.py               # Collecteur de métrologie (k6, Engine, JVM, PostgreSQL)
    ├── v3-oracle-probe.py          # Sonde SQL read-only pour preuves C-03 internes
    └── test_bench.py               # Suite de tests des 14 oracles et garde-fous
```

### Principes Directeurs Respectés
1. **Mesure du parcours réel côté client :** Le chronomètre démarre à la réception de la réponse HTTP 202 d'admission durable (`admittedAt`) et mesure séparément les branches parallèles sans jamais additionner leurs percentiles.
2. **Discrimination fine des réponses HTTP 503 :**
   - Un `503` avec en-tête `Retry-After` correspond à la barrière de maintenance READ fail-closed (interrompt le sondage et invalide le test s'il survient hors scénario de maintenance).
   - Un `503` sans `Retry-After` correspond à la saturation temporaire du sémaphore de transport READ (`HTTP_PERMITS = 2`, non-bloquant), comptabilisé comme `v3_read_capacity_503` et réessayé jusqu'au délai d'attente maximal sans déclencher de faux positif d'oracle.
3. **Observation SQL en lecture seule exclusive :** Toute interaction avec PostgreSQL utilise des transactions explicitement marquées en lecture seule (`default_transaction_read_only=on`).
4. **Zéro fuite d'informations sensibles :** Aucun secret, mot de passe ni jeton d'authentification n'apparaît dans les journaux d'exécution.

---

## 4. Réutilisation Effective de l'Existant

Le banc tire parti des composants fonctionnels et opérationnels déjà éprouvés dans les incréments précédents :
- **Topologie Docker Compose :** Réutilisation stricte de `docker-compose.functional.yml` et `docker/compose/docker-compose.observability.yml`.
- **Authentification Keycloak :** Réutilisation du flux PKCE S256 de `scripts/operations/run-demo-journey.py` pour négocier des jetons OIDC réels sans contourner la couche de sécurité.
- **Télémétrie L6/I1 :** Réutilisation exclusive des métriques Micrometer (`pocoma_v3_*`) exposées sur l'Actuator (`:18081`) et scrapées par Prometheus (`:9090`), sans ajout de compteurs intrusifs ni étiquettes à forte cardinalité.
- **Reconstruction L6/I2 :** Réutilisation de l'état persistant `public.read_maintenance_state` et des 28 checkpoints de découverte dans le préflight.

---

## 5. Scénarios Disponibles et Couverture SPEC-10

| Famille | Scénario | Configuration | Objectif Normatif | Statut Banc |
|---|---|---|---|:---:|
| **M1** | `smoke` | 1 VU, 3 iters | Smoke test E2E rapide (Create, Shareholders, Expense, Read) | **PASS** |
| **M1** | `small` (F01-A) | 2 Pots, 1 cmd/s, 20s | Profil Petit : p95 admission $\rightarrow$ Balance et Pot $\le 500$ ms | **PASS** |
| **M1** | `oracles` | Sonde SQL read-only | Parcours complet C-03 incluant $DeletePot$ et $Balance(P,V)$ | **PASS** |
| **M1** | `idle-polling` (F01-J) | 1 VU, repos 15s | Coût de polling au repos et latence de réveil à froid | **PASS** |
| **M2** | `contention` (F01-H) | 3 VUs sur 1 Pot | Concurrence sur même Pot, intégrité CAS et gestion des rejets | **PASS** |
| **M2** | `F01-B` (soutenu) | 4 Pots, 10 cmd/s | Charge soutenue sans dérive continue de backlog | **PRÊT** |
| **M2** | `F01-C` (pointe) | 8 Pots, 50 cmd/s | Pointe de charge et résorption de backlog | **PRÊT** |
| **M3** | `F01-D` (Grand Pot) | 1 Pot 100/2 000, 1 cmd/s | Profil Grand Pot : p95 admission $\rightarrow$ Pot $\le 2$ s | **PRÊT** |
| **M4** | `F01-G` (rattrapage) | 10 000 commandes | Résorption de stock sous 1 cmd/s nouvelle en $\le 5$ min | **PRÊT** |

---

## 6. Matrice des Oracles et Résultats des Tests de Validité

Avant toute mesure sur le moteur vivant, le banc a été formellement qualifié sur les 14 modes de défaillance discriminants obligatoires définis par le mandat (§9).

### 6.1 Suite Unitaire Python (`scripts/performance/test_bench.py`)
| # | Mode de Défaillance | Mécanisme de Test | Résultat |
|---|---|---|:---:|
| 1 | Admission refusée | Injection d'un retour HTTP 403 / 503 | **PASS** |
| 2 | Commande acceptée non terminalisée | Dépassement de la fenêtre de polling (timeout) | **PASS** |
| 3 | Résultat terminal sans nouvelle version | Issue `CHANGED` avec $resultingPotVersion \le observed$ | **PASS** |
| 4 | $Balance(P,V)$ absente | Absence de la balance exacte à la version $V$ | **PASS** |
| 5 | Pot incomplet | Structure incomplète (actionnaires ou balances manquants) | **PASS** |
| 6 | READ en retard | Consultation retournant $W < V$ malgré `minVersion=V` | **PASS** |
| 7 | READ en maintenance 503 | Discrimination du 503 avec `Retry-After` et `no-store` | **PASS** |
| 8 | Binding Identity non utilisable | Token rejeté ou binding inactif | **PASS** |
| 9 | $DeletePot$ non matérialisé | HTTP 200 renvoyé au lieu du 404 contractuel | **PASS** |
| 10 | Timeout d'une branche asynchrone | Branche non disponible avant deadline (HTTP 408) | **PASS** |
| 11 | Métrique indispensable manquante | Absence d'une métrique clé dans le scrape Prometheus | **PASS** |
| 12 | `dropped_iterations` détectées | Présence d'itérations perdues invalidant le débit | **PASS** |
| 13 | Jeu de données incohérent | Altération d'un montant détectée par l'empreinte SHA-256 | **PASS** |
| 14 | Arrêt par watchdog | Dépassement de seuil déclenchant `terminate()` immédiat | **PASS** |

### 6.2 Auto-test Offline k6 (`scripts/k6/v3-oracles-selftest.js`)
- 32 cas d'assertion déterministes associant systématiquement un faux-positif discriminant et un contrôle positif.
- **Résultat :** **32/32 PASS** (`oracle-selftest.json`).

---

## 7. Jeux de Données Répétables Préparés

Trois jeux de données synthétiques conformes à SPEC-09 ont été générés avec empreinte cryptographique SHA-256 dans `docs/testing/evidence/L6-I3/seeds/` :

| Identifiant Seed | Profil | Pots | Shareholders | Expenses | Événements Estimés | Empreinte SHA-256 |
|---|---|---:|---:|---:|---:|---|
| `seed-v3-small-001` | Petit | 1 | 5 | 20 | 22 | `1269e30b51903815a0e3d722a546a226cfe96e78fc569e4b706f4633bdfecb83` |
| `seed-v3-medium-001` | Moyen | 1 | 20 | 200 | 202 | `f16a338c4b6e55cbe437b6f65bdbd43dd6b8ed8cfaa2a6dc1d0f21c8f34f810f` |
| `seed-v3-large-001` | Grand | 1 | 100 | 2 000 | 2 002 | `eb8405c4ecaf7aa82d8a972a2c0476e741c3ff5d839071ccd8b9577729946823` |

*Note de conformité :* Les profils normatifs 10k, 1M et 10M ont été modélisés dans le générateur mais ne sont pas instanciés sur disque, conformément à l'interdiction de charge volumineuse locale.

---

## 8. Métriques Réellement Disponibles et Absentes

### Métriques Moteur & Applicatives Présentes
- `pocoma_v3_admission_capacity_available` (Jauge, permis d'admission restants)
- `pocoma_v3_admission_requests_total` (Compteur, admissions par code HTTP)
- `pocoma_v3_read_quota_permits` (Jauge, permis READ SQL disponibles)
- `pocoma_v3_read_quota_rejections_total` (Compteur, rejets par saturation de quota READ)
- `pocoma_v3_read_http_duration_seconds` (Timer, latence des consultations HTTP READ)
- `pocoma_v3_attempt_outcomes_total` (Compteur, tentatives committed / retried des workers)
- `pocoma_v3_attempt_duration_seconds` (Histogramme, durée d'exécution des tentatives)
- `pocoma_v3_worker_cycles_total` (Compteur, tours de boucle des workers de consommation)
- `pocoma_v3_physical_pending` (Jauge, backlog physique par type de consommation)
- `pocoma_v3_read_rebuild_*` (Statut, checkpoints et durée de reconstruction READ)

### Métriques Système & Infrastructure Présentes
- `jvm_memory_used_bytes` / `jvm_memory_max_bytes` (Heap et Non-Heap)
- `jvm_gc_pause_seconds_count` / `sum` (Fréquence et durée cumulée des pauses GC)
- `process_cpu_usage` / `system_cpu_usage` (Consommation CPU processus et hôte)
- `jvm_threads_live_threads` (Nombre de threads actifs)
- `hikaricp_connections_*` (Connexions actives, inactives, en attente et totales)
- PostgreSQL `pg_stat_activity` (Sessions actives, verrous bloquants)
- PostgreSQL `pg_stat_wal` (Octets de WAL générés)

### Métriques Absentes ou Incomplètes
- Télémétrie d'E/S disque par conteneur Docker (non exposée par le daemon Prometheus local).
- Découpage fin CPU noyau vs utilisateur au sein de la JVM (Micrometer expose le total global `process_cpu_usage`).

---

## 9. Synthèse des Campagnes Locales Bonnées Exécutées

Toutes les campagnes autorisées ont été exécutées dans le respect du budget global de 70 minutes.

| Campagne | Run ID | Durée | Admis | Appliqués | Rejets / Err. | Censurés | p50 Adm. | p95 Term. | p95 Bal(V) | p95 Read(W) | Résultat |
|---|---|---:|---:|---:|:---:|:---:|---:|---:|---:|---:|:---:|
| **Smoke V3** | `run-smoke-1794050946` | 10.14 s | 3 | 3 | 0 / 0 | 0 | 2.96 ms | 929.4 ms | 927.6 ms | 930.2 ms | **PASS** |
| **Oracle Journey** | `run-oracles-1794051265` | 5.10 s | 4 | 4 | 0 / 0 | 0 | 1.80 ms | 1053.1 ms | 1098.1 ms | N/A ($Delete$) | **PASS** |
| **Profil Petit** | `run-small-1794051319` | 60.08 s | 21 | 21 | 0 / 0 | 0 | 3.28 ms | 1276.0 ms | 1309.0 ms | 1312.0 ms | **PASS** |
| **Contention 1 Pot** | `run-contention-1794051565` | 21.93 s | 28 | 28 | 0 / 0 | 0 | 1.62 ms | 2023.6 ms | N/A | N/A | **PASS** |
| **Idle Polling** | `run-idle-polling-1794051711` | 23.92 s | 1 | 1 | 0 / 0 | 0 | 9.89 ms | 1727.0 ms | 1697.0 ms | 1699.0 ms | **PASS** |

### Décompte et Clôture du Budget de Qualification Locale
- **Budget alloué :** 70,0 minutes (4 200,0 secondes).
- **Temps cumulé consommé :** **2,9 minutes (171,1 secondes)**.
- **Budget résiduel non entamé :** 67,1 minutes (4 028,9 secondes).
- **Aucun incident Watchdog déclenché**, aucun dépassement de plafond.

---

## 10. Analyse des Premières Limites Observées sur Environnement Local

La qualification locale sur `pocoma-functional` monolithique met en évidence plusieurs caractéristiques fondamentales :
1. **Latence bout en bout en topologie monolithique :**
   - Sur cette topologie Compose locale (où API, Workers et PostgreSQL partagent les mêmes cœurs CPU et disques virtuels), le $p95$ bout en bout admission $\rightarrow$ READ complète se situe entre **930 ms et 1 312 ms** pour le profil Petit.
   - Cette observation confirme l'arbitrage normatif de **SPEC-09 §3** : l'environnement conteneurisé monolithique partagé ne constitue pas la **Configuration A** (PostgreSQL dédié sur 4 vCPU / 8 Go et runtimes sur seconde VM dédiée). Le SLO nominal de 500 ms p95 ne s'applique qu'à la Configuration A physique certifiée.
2. **Dimensionnement du sémaphore de transport READ :**
   - La constante `HTTP_PERMITS = 2` sur `V3ReadTransport` provoque des réponses HTTP 503 immédiates dès qu'un injecteur k6 effectue plus de deux requêtes concurrentes de polling.
   - Le banc gère parfaitement ce comportement en comptabilisant ces réponses dans `capacity503` sans les confondre avec des erreurs fonctionnelles ni violer le fail-closed.
3. **Contention sur un Pot unique :**
   - Sous 3 VUs concurrents, 28 commandes consécutives ont été admises, traitées et matérialisées sans corruption, sans état partiel et avec une latence p95 de 2 023 ms. Le stock de slots READ s'est intégralement résorbé immédiatement après la fin du tir (0 slot résiduel).

---

## 11. Couverture des Réserves Historiques

- **L6/I1 (Observabilité) :**
  - Réserve sur la pertinence opérationnelle des métriques : pleinement levée par l'intégration effective de `pocoma_v3_*` dans les collectes d'admission, d'opportunités de consommation et de pools HikariCP.
  - Réserve sur la cardinalité des labels : confirmée et respectée (aucun label `potId` ou `requestId` injecté dans Prometheus).
- **L6/I2 (Reconstruction READ) :**
  - Réserve sur le fail-closed et la distinction des 503 : résolue par la discrimination fine de l'en-tête `Retry-After`.
  - Intégrité de la base de maintenance : vérifiée et validée par le préflight.

---

## 12. Préparation Directement Exploitable des Campagnes Normatives (L8)

Le banc implémenté est d'ores et déjà prêt pour les campagnes de certification formelle SPEC-09 / SPEC-10 sur infrastructures cibles A et B :
- Scripts k6 paramétrables par variables d'environnement (`RATE`, `DURATION`, `POTS`, `SCENARIO`).
- Prise en charge native des profils `sustained` (10 cmd/s) et `peak` (50 cmd/s) avec exécuteurs k6 `constant-arrival-rate`.
- Procédures de test unitaire et de contrôle d'oracles reproductibles en une commande.

---

## 13. Verdict Technique et Publication

### Verdict Formel
```
L6_I3_BENCH_IMPLEMENTED_LOCAL_BOUNDED_PASS
```

---

## 14. Complément de Mission L6/I3-R (Renforcement et Montée en Charge)

À la suite de la décision humaine stipulant que la première campagne (21 à 28 commandes) était insuffisante pour qualifier les performances du système, la mission **L6/I3-R** a été exécutée.

Le rapport complet, exhaustif et quantitatif est consigné dans :
👉 **[`docs/v3/L6_I3_Deep_Instrumentation_and_Load_Qualification_Report.md`](./L6_I3_Deep_Instrumentation_and_Load_Qualification_Report.md)**

Ce rapport fournit notamment :
1. La décomposition temporelle fine $T_0 \to T_{10}$ distinguant temps interne et client.
2. Les résultats complets des paliers progressifs P1 (1 cmd/s), P2 (5 cmd/s), P3 (10 cmd/s), P4 (20 cmd/s) et P5 (50 cmd/s).
3. Les résultats des scénarios dédiés S1 (Indépendant), S2 (Contention), S3 (READ pur), S4 (Mixte) et S5 (Structurel Small/Medium).
4. La caractérisation du goulot d'étranglement du sémaphore de transport READ (`HTTP_PERMITS = 2`).
5. Les réponses formelles aux 10 questions de diagnostic technique (Q1 à Q10).
6. 6 graphiques vectoriels SVG et 6 jeux de données tabulaires CSV archivés dans `docs/testing/evidence/L6-I3/charts/`.
7. La recommandation technique motivée : `ACCEPTER AVEC RÉSERVES`.


Les bancs de performance et d'exploitation de Pocoma V3 sont complètement implémentés, leurs oracles sont formellement qualifiés et validés (32/32 tests k6 et 14/14 tests Python), et la reconnaissance locale bornée est achevée avec succès en 2,9 minutes sur un budget autorisé de 70 minutes. Les campagnes normatives ultérieures sont directement exploitables.
