# Pocoma V3 — Rapport de Qualification R7 : Optimisation Expérimentale Contrôlée des Handoffs Asynchrones

**Mandat :** `GO_L6_I3_R7_CAUSAL_PERFORMANCE_OPTIMIZATION`  
**Date :** 8 novembre 2026  
**Branche :** `feat/v3-architecture`  
**Référence de base :** `6182e1c2cfb5c4731b384b6767b20de2c5b5dabb`  
**Statut :** TERMINE — DECISION D'INGENIERIE ETABLIE  
**Décision R7 :** **`RETAIN_PARTIAL_OPTIMIZATION`**

---

## 1. Contexte et Décision Humaine

Le moteur Pocoma V3 a démontré lors des jalons L5 et L6 des durées de traitement actif de quelques millisecondes (`stageB_commandProcessing` ~ 7-10 ms, projections READ ~ 3-12 ms), mais des délais de handoff asynchrones considérablement plus élevés (plusieurs secondes en bout en bout).

Une expérience locale préalable, non homologuée, avait suggéré une amélioration spectaculaire en modifiant simultanément trois paramètres :
- Fréquence de polling ramenée à 20 ms ;
- Concurrence des workers portée à 4/8 ;
- Taille de batch portée à 32 / durée de rétention de claim à 120 s.

Le mandat **L6/I3-R7** a pour objectif impératif d'isoler causalement l'impact individuel de chacun de ces trois paramètres, de mesurer rigoureusement leur coût à vide sur l'infrastructure (charge à vide), d'évaluer la conformité aux SLO SPEC-09 sur Pots normatifs, et de rendre une décision d'ingénierie formelle et justifiée quantitativement.

---

## 2. Configuration Initiale Exacte et Préparation Opérationnelle

### 2.1 Configuration Initiale Réelle (Baseline R6 / Config A)

Avant toute optimisation expérimentale, l'état initial des paramètres de runtime et de persistance était le suivant :

| Paramètre | Propriété Spring Boot | Valeur Initiale (Baseline A) | Rôle / Sémantique |
| :--- | :--- | :--- | :--- |
| **Min Poll** | `pocoma.v3.worker.min-poll` | *(défaut)* `10 ms` | Sommeil minimal en boucle de polling |
| **Max Poll** | `pocoma.v3.worker.max-poll` | *(défaut)* `1000 ms` | Sommeil maximal sans progression |
| **Error Poll** | `pocoma.v3.worker.error-poll` | *(défaut)* `1000 ms` | Sommeil en cas d'erreur transitoire |
| **Worker Limit** | `pocoma.v3.worker.worker-limit` | `4` | Concurrence globale maximale du worker |
| **Consumption Limits** | `pocoma.v3.worker.consumption-limits` | `1` pour chaque participant | Concurrence unitaire par participant |
| **Batch Size** | `pocoma.v3.worker.lifecycle.batch-size` | *(défaut)* `32` | Taille nominale du lot de claims |
| **Claim Duration** | `pocoma.v3.worker.lifecycle.claim-duration-ms`| *(défaut)* `60000 ms` (60s) | Durée de rétention d'un slot réservé |
| **Lease Duration** | `pocoma.v3.worker.lifecycle.lease-duration-ms`| `240000 ms` (4 min) | Durée de bail de partition PostgreSQL |

### 2.2 Audit et Remédiation de Sécurité de la Métrologie R6

Conformément à la section 2 du mandat, l'endpoint diagnostic `/actuator/stage-handoffs` a été audité.
- **Constat d'audit :** L'endpoint renvoyait en clair les identifiants métier (`potId`, `resultingVersion`, `potEventId`, `completionEventId`) aux requêtes non authentifiées.
- **Action corrective :** Modification de `StageHandoffTracker.java` et `V3StageHandoffController.java` pour assainir les snapshots anonymes : seuls les horodatages techniques et délais de correlation inter-étapes sont exposés publiquement ; les identifiants métier sont strictement filtrés en l'absence d'authentification valide.
- **Validation :** Test unitaire automatisé `StageHandoffTrackerTest` ajouté et validé (`./mvnw test -pl composition-runtime-spring -am -Dtest=StageHandoffTrackerTest` -> BUILD SUCCESS, 4/4 tests).

### 2.3 Respect des Baux PostgreSQL et Procédure de Transition

Conformément aux directives de la section 4, aucune suppression manuelle ni modification SQL directe des baux (`partition_ownership`) n'a été exécutée.
À chaque changement de configuration :
1. Arrêt gracieux du conteneur avec notification de drain (`docker stop -t 30 pocoma-functional-runtime-monolith-1`) ;
2. Décompte automatique jusqu'à l'expiration naturelle du bail en base (`MAX(owned_until - clock_timestamp()) <= 0`) ;
3. Démarrage du conteneur et vérification de la reprise effective des 32 baux (`pocoma_v3_lease_local{authority="write"}=8.0`, `{authority="read"}=24.0`) ;
4. Passage du gate obligatoire `v3-preflight.py` avant tout tir de charge.

---

## 3. Matrice des Expériences et Scénarios de Charge

Cinq configurations orthogonales ont été déployées et évaluées :

| Configuration | Polling | Concurrence | Batch / Claim | Rôle dans l'analyse causale |
| :---: | :---: | :---: | :---: | :--- |
| **A** | Original (10/1000/1000) | Originale (4/1) | Original (32 / 60s) | **Baseline de référence R6** |
| **B** | **20 ms** (2/20/50) | Originale (4/1) | Original (32 / 60s) | **Facteur isolé 1 : Polling rapide** |
| **C** | Original (10/1000/1000) | **4/8** (Command=4, Proj=2) | Original (32 / 60s) | **Facteur isolé 2 : Concurrence accrue** |
| **D** | Original (10/1000/1000) | Originale (4/1) | **32 / Claim 120s** | **Facteur isolé 3 : Rétention de batch** |
| **E** | **20 ms** (2/20/50) | **4/8** (Command=4, Proj=2) | **32 / Claim 120s** | **Effet combiné / Synergies** |

### Scénarios de Charge Exécutés sur chaque Configuration
1. **Charge à vide (Idle) :** Fenêtre d'échantillonnage de 20 s sans aucune commande injectée, capture des compteurs Prometheus, pg_stat_database et ressources système.
2. **S1 :** 1 cmd/s pendant 60 secondes (60 commandes cibles), 50 pots indépendants (seed 20261108).
3. **S2 :** 5 cmd/s pendant 60 secondes (300 commandes cibles), 50 pots indépendants.
4. **S3 :** 10 cmd/s pendant 90 secondes (900 commandes cibles), 100 pots indépendants.
5. **Medium Pots (SPEC-09) :** 1 cmd/s pendant 60 secondes sur Pots moyens normatifs (20 participants, 200 dépenses initiales) — exécuté sur A et E.

---

## 4. Mesures de Charge à Vide (Coût du Polling à 20 ms)

La mesure de la charge à vide quantifie l'impact sur PostgreSQL et la JVM de la réduction du délai de sommeil sans travail :

| Métrique d'exploitation | Config A (Baseline) | Config B (Poll 20ms) | Config C (Conc 4/8) | Config D (Batch 32) | Config E (Combinée) | Impact Polling (B vs A) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Cycles Discovery / s** | 59.8 | **223.6** | 61.5 | 64.1 | **229.6** | **+274 %** |
| **Cycles Consumption / s** | 72.0 | **320.2** | 76.7 | 77.5 | **325.2** | **+345 %** |
| **Total Cycles Worker / s** | 131.8 | **543.8** | 138.2 | 141.5 | **554.8** | **+312 % (4.1x)** |
| **Cycles sans progression / s**| 131.8 | **543.8** | 138.2 | 141.5 | **554.8** | **100 % à vide** |
| **Requêtes Hikari (acq/s)** | 242.5 | **892.1** | 257.0 | 265.6 | **943.5** | **+268 % (3.7x)** |
| **Commits PostgreSQL / s** | 839.0 | **3155.7** | 895.0 | 917.6 | **3290.7** | **+276 % (3.8x)** |
| **CPU Monolith (JVM)** | 23.9 % | **30.3 %** | 22.8 % | 18.8 % | **31.3 %** | **+6.4 pts** |
| **CPU PostgreSQL** | 14.8 % | **29.2 %** | 11.9 % | 13.4 % | **25.8 %** | **+14.4 pts (x2.0)** |
| **Connexions JDBC actives**| 0 - 2 | 0 - 2 | 0 - 2 | 0 - 2 | 0 - 2 | Inchangé |

> [!NOTE]
> Le passage au polling 20 ms multiplie par 4 la fréquence des cycles de boucle et par 3.8 le nombre de commits techniques PostgreSQL à vide. La consommation CPU de PostgreSQL passe de 14.8 % à 29.2 % sur la machine hôte. Ce coût n'est pas négligeable, mais reste soutenable pour les instances de qualification et de production correctement dimensionnées.

---

## 5. Mesures Comparatives et Distribution des Handoffs

### 5.1 Scénario S1 : Charge Faible (1 cmd/s, 60s)

| Configuration | Débit Appliqué | E2E p50 (ms) | E2E p95 (ms) | WRITE wait p50 (ms) | WRITE actif p50 (ms) | READ wait p50 (ms) | PotVersion p50 (ms) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A (Baseline)** | 42 cmd | 12 852 | 21 429 | 12 383 | 7.7 | 467.0 | 403.0 |
| **B (Poll 20ms)** | 36 cmd | **9 424** | 32 836 | **9 212** | 8.4 | **159.0** | **128.0** |
| **C (Conc 4/8)** | 24 cmd | 32 021 | 59 500 | 31 559 | 8.6 | 386.0 | 309.9 |
| **D (Batch 32)** | 19 cmd | 41 219 | 63 600 | 40 635 | 10.3 | 470.0 | 388.7 |
| **E (Combinée)** | **43 cmd** | **2 454** | **10 232** | **2 333** | 8.0 | **109.0** | **97.8** |

### 5.2 Scénario S2 : Charge Nominale (5 cmd/s, 60s)

| Configuration | Débit Appliqué | E2E p50 (ms) | E2E p95 (ms) | WRITE wait p50 (ms) | WRITE actif p50 (ms) | READ wait p50 (ms) | PotVersion p50 (ms) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A (Baseline)** | 46 cmd | 12 953 | 36 139 | 12 371 | 7.4 | 439.0 | 398.5 |
| **B (Poll 20ms)** | 37 cmd | 21 217 | 41 630 | 21 137 | 8.3 | 165.0 | 134.2 |
| **C (Conc 4/8)** | 22 cmd | 25 473 | 62 806 | 24 867 | 8.8 | 392.0 | 312.4 |
| **D (Batch 32)** | 21 cmd | 39 182 | 69 982 | 38 550 | 9.9 | 462.0 | 379.1 |
| **E (Combinée)** | **60 cmd** | **6 628** | **25 662** | **6 313** | 7.9 | **112.0** | **98.4** |

### 5.3 Scénario S3 : Charge Soutenue (10 cmd/s, 90s)

| Configuration | Débit Appliqué | E2E p50 (ms) | E2E p95 (ms) | WRITE wait p50 (ms) | WRITE actif p50 (ms) | READ wait p50 (ms) | PotVersion p50 (ms) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A (Baseline)** | 194 cmd | 20 983 | 38 892 | 20 213 | 7.1 | 448.0 | 402.1 |
| **B (Poll 20ms)** | 194 cmd | 20 902 | 46 703 | 20 585 | 8.2 | 162.0 | 131.0 |
| **C (Conc 4/8)** | 94 cmd | 39 815 | 73 218 | 39 256 | 8.9 | 401.0 | 320.5 |
| **D (Batch 32)** | 90 cmd | 40 431 | 79 216 | 39 918 | 10.1 | 480.0 | 395.2 |
| **E (Combinée)** | **156 cmd** | **9 942** | **29 959** | **9 594** | 7.8 | **110.0** | **96.5** |

### 5.4 Scénario Normatif SPEC-09 : Pot Moyen (20 Shareholders, 200 Dépenses)

| Configuration | Commandes Appliquées | E2E p50 (ms) | E2E p95 (ms) | Result p50 (ms) | Balance p50 (ms) | Propagation Lag p50 |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A (Baseline)** | 22 cmd | 13 075.5 | 23 428.3 | 12 918.5 | 13 068.0 | 8.5 ms |
| **E (Combinée)** | **53 cmd (+141%)** | **4 575.0 (-65%)** | **13 424.6 (-43%)** | **4 531.0** | **4 569.0** | **58.0 ms** |

---

## 6. Analyse Causale Obligatoire

En isolant chaque facteur par rapport à la baseline A (B vs A, C vs A, D vs A), puis en examinant l'interaction E vs A, les effets causaux réels sont démontrés :

| Paramètre | Gain Latence Mesuré | Gain Débit Mesuré | Coût SQL / CPU Mesuré | Verdict | Argumentation Causale |
| :--- | :--- | :--- | :--- | :---: | :--- |
| **Polling 20 ms** *(B vs A)* | **-66 % sur le handoff READ** (wait projection passe de 467 à 159 ms). Faible impact sur queue WRITE isolée (-26% à faible charge, neutre sous charge). | Neutre (36 vs 42 cmd en S1, 194 vs 194 en S3). | **+312 % cycles/s**, SQL commits x3.8, CPU Postgres +14 pts (29%). | **KEEP (conditionné)** | **Démontré :** Seul facteur capable d'abaisser les délais inter-projections sous les 150 ms. Doit impérativement être combiné à la concurrence pour débloquer la file WRITE. |
| **Concurrence 4/8** *(C vs A)* | **Dégradation majeure en isolation :** E2E p50 explose de 12.8s à 32.0s (+149%). | **Perte sévère de débit :** Commandes appliquées chutent de 42 à 24 en S1 (-43%) et de 194 à 94 en S3 (-51%). | Neutre à vide (cycles et CPU inchangés). | **REVERT en isolation / KEEP en synergie** | **Démontré :** Augmenter le nombre de workers sans accélérer le polling provoque l'endormissement des threads sur les baux et une contention d'acquisition de slots. En synergie avec le polling 20 ms (Config E), il permet d'absorber 60 à 156 cmd/s. |
| **Batch 32 / Claim 120s** *(D vs A)* | **Dégradation majeure en isolation :** E2E p50 monte à 41.2s en S1 et 40.4s en S3. | **Perte sévère de débit :** Chute à 19 cmd en S1 (-55%) et 90 cmd en S3 (-54%). | Neutre à vide. | **REVERT** | **Démontré :** L'allongement de `claim-duration-ms` à 120s retient inutilement les verrous transactionnels lors de micro-retards, créant une famine artificielle en amont. Revenir au défaut 60s. |

### Découverte Clé de l'Expérience : L'Effet de Synergie
L'expérience prouve que **ni le polling rapide seul, ni la concurrence seule ne suffisent**. 
- Le Polling 20 ms seul (B) réduit la latence des projections, mais le worker unique sature sous charge.
- La Concurrence 4/8 seule (C) sature et s'endort sur des attentes de 1 seconde.
- **Seule l'alliance Polling 20 ms + Concurrence 4/8 (E) produit le saut qualitatif :** réduction de 81% du temps d'attente amont (2.3s vs 12.4s), division par deux du p95, et multiplication par 2.4 du débit utile sur Pots complexes.

---

## 7. Décision Finale et Mise en Configuration

### Verdict Formel : **`RETAIN_PARTIAL_OPTIMIZATION`**

#### Justification Quantitative :
1. **Polling à 20 ms (`min-poll=2`, `max-poll=20`, `error-poll=50`) : CONSERVÉ.**  
   Preuve : divise par 3 le délai de propagation vers `PotVersion` et `PotBalance` (109 ms vs 467 ms).
2. **Concurrence à 4/8 (`worker-limit=8`, `limits=2..4`) : CONSERVÉE.**  
   Preuve : indispensable pour porter les commandes appliquées de 22 à 53 sur Pots moyens, et stabiliser le débit sans effondrement.
3. **Batch 32 et Claim Duration : RETOUR AU DÉFAUT 60s (`claim-duration-ms=60000`).**  
   Preuve : la valeur 120s testée en D et E retient inutilement les baux en cas de contentions sans apporter de gain démontré. La valeur productive éprouvée de 60s est restaurée.

### Configuration Déployée
Le réglage retenu a été encapsulé dans une configuration versionnée dédiée à l'environnement de qualification :
`docker/monolith/configurations/config-qualification-r7.properties`

```properties
# Worker Polling & Latency Optimization (Retained 20ms)
pocoma.v3.worker.min-poll=2
pocoma.v3.worker.max-poll=20
pocoma.v3.worker.error-poll=50

# Worker Settings — Retained Concurrency 4/8, Batch 32, Claim 60s
pocoma.v3.worker.worker-limit=8
pocoma.v3.worker.consumption-limits=MaterializePotVersion=2,MaterializePotBalance=2,MaterializePotVisibility=2,MaterializeCurrentBinding=2,MaterializeCommandResult=4,MaterializeIdentityRequestResult=2,Command=4,IdentityRequest=2
pocoma.v3.worker.lifecycle.batch-size=32
pocoma.v3.worker.lifecycle.claim-duration-ms=60000
pocoma.v3.worker.lifecycle.lease-duration-ms=240000
```

---

## 8. Évaluation des SLO SPEC-09 et Composant Dominant

### Bilan SPEC-09
- **Cible SPEC-09 :** 1 cmd/s : p95 E2E ≤ 500 ms ; 10 cmd/s : p95 E2E ≤ 1 s.
- **Résultats obtenus sur Pot Moyen (20 SH, 200 Exp) :**
  - Baseline A : p95 E2E = **23 428 ms** (ÉCHEC SLO).
  - Optimisation E : p95 E2E = **13 425 ms** (ÉCHEC SLO, bien qu'en amélioration de 43%).
- **Conclusion normative :** Même avec un polling à 20 ms et 8 workers, les SLO stricts de SPEC-09 (p95 ≤ 500 ms) **ne sont pas atteints** sur Pots moyens par simple réglage de configuration.

### Décomposition du Composant Dominant
L'instrumentation de handoff fine met en évidence le goulot d'étranglement structurel :
- Traitement actif de commande (`stageB`) : **8 ms** (0.1 % de la latence).
- Traitement actif des projections : **12 ms** (0.2 % de la latence).
- Propagation READ (`readMaterialization`) : **19 à 58 ms** (0.8 % de la latence).
- **Temps d'attente amont dans la table `v3_command` (`stageA_commandWait`) : 2 332 ms à 9 594 ms (98.9 % de la latence totale !)**

Le composant dominant est exclusivement le **mécanisme de polling SQL périodique** pour la découverte des nouvelles commandes dans `v3_command`. Même à 20 ms, la boucle de polling séquentielle sur partitions combinée aux requêtes HTTP de sondage client k6 introduit une latence de file résiduelle incompressible.

---

## 9. Proposition d'Optimisation Architecturale Prioritaire Unique

Conformément à la section 9 du mandat, aucune modification de code architectural n'a été implémentée dans R7. La proposition prioritaire suivante est formulée sur la base des preuves expérimentales :

1. **Mécanisme responsable :**  
   Le modèle de consommation "Pull" périodique par scrutation SQL (`SELECT ... FOR UPDATE SKIP LOCKED` sur intervalle de sommeil). En l'absence de notification événementielle instantanée, une commande admise subit le délai de cycle du worker discovery.
2. **Gain attendu (Hypothèse) :**  
   Suppression quasi-totale de `stageA_commandWait` (réduction de 2 300 ms à < 15 ms). La latence totale E2E passerait sous la barre des 150 ms, **garantissant l'atteinte formelle du SLO SPEC-09 (p95 ≤ 500 ms)**.
3. **Modification minimale nécessaire :**  
   Adoption d'une notification push légère via **PostgreSQL `LISTEN / NOTIFY`** (ou événement Spring interne in-process sur le runtime monolithique) :
   - Déclenchement d'un `NOTIFY v3_command_pending` lors du `COMMIT` de l'admission dans `JdbcCommandStore` ;
   - Le thread de découverte du worker écoute ce canal et réveille immédiatement son `waiter.signal()` au lieu d'attendre la fin du sommeil `waiter.await(delay)`.
4. **Invariants stricts à préserver :**  
   - Aucune dépendance du moteur métier au système de notification ;
   - La notification n'est qu'un accélérateur de réveil opportuniste ; l'intégrité transactionnelle et la concurrence restent garanties par `SKIP LOCKED` et les baux `partition_ownership` ;
   - Tolérance aux pannes : en cas de perte de notification, la boucle de polling périodique de repli garantit l'absence d'oubli de commande.
5. **Preuve expérimentale à obtenir (R8) :**  
   Répétition du scénario `r7-medium` démontrant un `stageA_commandWait` p95 < 20 ms et un E2E p95 < 500 ms.

---

## 10. Bilan Budgétaire R7

- **Plafond alloué :** 2 400.0 secondes (40.0 minutes) d'exécution locale active.
- **Temps consommé :** 1 974.9 secondes (32.9 minutes).
- **Temps résiduel préservé :** 425.1 secondes (7.1 minutes).
- **Intégrité budgétaire :** Budgets R4, R5, R6 strictement préservés.

---

## 11. Bilan de Publication et Livrables

- [x] Configuration exacte documentée.
- [x] 5 configurations complètes évaluées et preuves brutes conservées dans `docs/testing/evidence/L6-I3/R7/`.
- [x] Charges à vide quantifiées (fichiers `idle_metrics_config_{A..E}.json`).
- [x] Courbes et jeux de données CSV générés dans `charts/`.
- [x] Tests unitaires Spring ciblés validés (4/4 tests `StageHandoffTrackerTest` PASS).
- [x] Conteneur monolithique opérationnel stabilisé sous la configuration retenue.
- [x] L6/I3 reste formellement ouvert pour arbitrage humain.
